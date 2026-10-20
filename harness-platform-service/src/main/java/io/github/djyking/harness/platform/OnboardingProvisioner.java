package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.core.env.Environment;

/**
 * Explicit offline provisioning. Source manifests are never overwritten, credentials never enter
 * SQL records or stdout, and identity changes are safely repeatable after either database fails.
 */
public final class OnboardingProvisioner {
  private OnboardingProvisioner() {}

  public static void run(Path commandFile, Environment env) throws Exception {
    if (!"drained-isolated-cluster".equals(env.getProperty("HARNESS_ONBOARDING_OFFLINE_ACK")))
      throw new IllegalStateException("OFFLINE_ACK_REQUIRED");
    JsonNode command;
    try (var in = Files.newInputStream(commandFile)) {
      command = ApiJson.read(in);
    }
    OnboardingService.fields(
        command,
        Set.of(
            "sponsorProject",
            "requestId",
            "revision",
            "planDigest",
            "requiredRole",
            "expectedApplicationRevision",
            "operator"));
    String sponsor = OnboardingService.text(command, "sponsorProject", 64);
    String id = OnboardingService.text(command, "requestId", 128);
    String operator = OnboardingService.text(command, "operator", 100);
    ApiJson.identifier(sponsor);
    ApiJson.identifier(id);
    ApiJson.identifier(operator);
    if (!command.path("revision").isIntegralNumber()
        || command.path("revision").asLong() < 1
        || !command.path("expectedApplicationRevision").isIntegralNumber()
        || command.path("expectedApplicationRevision").asLong() < -1) throw ApiFailure.invalid();
    String role = OnboardingService.text(command, "requiredRole", 64);
    if (!Set.of("ADMIN", "USER", "DEMO").contains(role)) throw ApiFailure.invalid();
    String digest = OnboardingService.text(command, "planDigest", 64);
    Path configPath = Path.of(required(env, "HARNESS_CONFIG")).toRealPath();
    Deployment deployment = Deployment.load(configPath);
    Path outputRoot = Path.of(required(env, "HARNESS_ONBOARDING_PRIVATE_DIR")).toRealPath();
    if (!Files.isDirectory(outputRoot) || Files.isSymbolicLink(outputRoot))
      throw new IllegalStateException("PRIVATE_DIRECTORY_REQUIRED");
    try (var platformData = dataSource(env, "HARNESS_MIGRATION_JDBC");
        var authorityData = dataSource(env, "HARNESS_ONBOARDING_IDENTITY_JDBC")) {
      JdbcRunStore store = new JdbcRunStore(platformData);
      store.validateSchema();
      PlatformRepository platform = new PlatformRepository(store, false);
      OnboardingRepository repository = new OnboardingRepository(platform);
      repository.validateSchema();
      var result =
          platform.transaction(
              c -> {
                platform.lockProject(c, sponsor);
                requireOffline(c, deployment.leaseMillis());
                ObjectNode request = repository.get(c, sponsor, id);
                String commandDigest = Json.hash(ApiJson.canonical(command));
                if (request
                    .path("provisioned")
                    .path("commandDigest")
                    .asText()
                    .equals(commandDigest))
                  return Json.object().put("status", "ALREADY_PROVISIONED").put("requestId", id);
                if (request.path("revision").asLong() != command.path("revision").asLong())
                  throw new ApiFailure(412, "PRECONDITION_FAILED");
                OnboardingService.requireState(
                    request, "APPROVED", "ROTATION_APPROVED", "DISABLED");
                if (!request.path("plan").path("digest").asText().equals(digest))
                  throw new ApiFailure(409, "PLAN_DIGEST_MISMATCH");
                long user;
                try {
                  user = Long.parseLong(request.path("subject").asText());
                } catch (NumberFormatException invalid) {
                  throw new IllegalStateException("EXISTING_OPSAGENT_IDENTITY_REQUIRED");
                }
                if (user < 1)
                  throw new IllegalStateException("EXISTING_OPSAGENT_IDENTITY_REQUIRED");
                boolean disabled = request.path("state").asText().equals("DISABLED");
                Path directory =
                    outputRoot.resolve(id + "-g" + request.path("generation").asInt()).normalize();
                if (!directory.getParent().equals(outputRoot))
                  throw new IllegalStateException("INVALID_OUTPUT_DIRECTORY");
                try {
                  ensurePrivateDirectory(directory);
                  var marker =
                      Json.object()
                          .put("commandDigest", commandDigest)
                          .put("sourceDigest", deploymentDigest(deployment));
                  // Disable may follow a successfully installed generation and has its own receipt
                  // folder.
                  if (disabled) {
                    directory =
                        outputRoot.resolve(id + "-disable-r" + request.path("revision").asLong());
                    ensurePrivateDirectory(directory);
                  }
                  writeOnce(directory.resolve("operation.json"), Json.write(marker));
                  Path secretFile = directory.resolve("application.credential");
                  String credential = disabled ? null : secret(secretFile);
                  String fingerprint = disabled ? "" : credentialHash(credential);
                  String app = request.path("applicationId").asText();
                  String project = request.path("targetProject").asText();
                  Path candidate = directory.resolve("deployment.json");
                  if (!disabled)
                    writeOnce(
                        candidate,
                        ApiJson.canonical(
                            deploymentDocument(candidate(deployment, request, secretFile))));
                  // The authority transaction can commit before platform SQL; replay uses the same
                  // private credential and exact hash/revision/profile checks to recover safely.
                  try (Connection authority = authorityData.getConnection()) {
                    authority.setAutoCommit(false);
                    try {
                      installIdentity(
                          authority,
                          request,
                          user,
                          role,
                          fingerprint,
                          command.path("expectedApplicationRevision").asLong(),
                          disabled);
                      authority.commit();
                    } catch (RuntimeException | SQLException failure) {
                      authority.rollback();
                      throw failure;
                    }
                  }
                  var provisioned =
                      Json.object()
                          .put("commandDigest", commandDigest)
                          .put("credentialFingerprint", fingerprint)
                          .put("generation", request.path("generation").asInt())
                          .put("identityDisabled", disabled)
                          .put("at", now(c));
                  if (!disabled)
                    provisioned.put(
                        "candidateDigest", deploymentDigest(Deployment.load(candidate)));
                  request.set("provisioned", provisioned);
                  if (!disabled) request.put("state", "PROVISIONED");
                  repository.enabled(c, request, false);
                  OnboardingService.audit(
                      request,
                      "offline:" + operator,
                      disabled ? "IDENTITY_DISABLED" : "PROVISIONED",
                      now(c));
                  repository.records.save(
                      c,
                      sponsor,
                      OnboardingRepository.KIND,
                      id,
                      request.path("revision").asLong(),
                      request);
                  return Json.object()
                      .put("status", disabled ? "DISABLED" : "PROVISIONED")
                      .put("requestId", id)
                      .put("requiresMigrationAndRestart", !disabled)
                      .put("requiresIdentityVerification", !disabled);
                } catch (java.io.IOException ex) {
                  throw new IllegalStateException("PRIVATE_OUTPUT_UNAVAILABLE");
                }
              });
      System.out.println(Json.write(result));
    }
  }

  static Deployment candidate(Deployment source, ObjectNode request, Path secretFile) {
    String app = request.path("applicationId").asText();
    String project = request.path("targetProject").asText();
    Map<String, String> applications = new TreeMap<>(source.applicationSecrets());
    boolean rotation = request.path("state").asText().equals("ROTATION_APPROVED");
    if (rotation != applications.containsKey(app))
      throw new IllegalStateException("SOURCE_APPLICATION_STATE_MISMATCH");
    applications.put(app, "file:" + secretFile.toAbsolutePath().normalize());
    List<Deployment.Project> projects = new ArrayList<>();
    boolean existing = false;
    for (Deployment.Project p : source.projects()) {
      if (p.id().equals(project)) {
        TreeSet<String> members = new TreeSet<>(p.applications());
        members.add(app);
        projects.add(
            new Deployment.Project(
                p.id(),
                members,
                p.maxActiveRuns(),
                p.maxReservedTokens(),
                p.maxActiveRunsPerApplication(),
                p.maxReservedTokensPerApplication(),
                p.concurrency()));
        existing = true;
      } else projects.add(p);
    }
    if (!existing) {
      if (rotation) throw new IllegalStateException("SOURCE_PROJECT_STATE_MISMATCH");
      // A new project starts with a conservative quota and shares only reviewed server adapters.
      String console = request.path("requestingApplication").asText();
      if (!applications.containsKey(console))
        throw new IllegalStateException("CONSOLE_BINDING_MISSING");
      projects.add(new Deployment.Project(project, Set.of(app, console), 2, 100000, 2, 100000, 1));
    }
    return new Deployment(
        source.identityOrigin(),
        source.signingSecret(),
        applications,
        projects,
        source.releases(),
        source.disabledReleases(),
        source.tools(),
        source.models(),
        source.concurrency(),
        source.leaseMillis(),
        source.workerEnabled());
  }

  static void installIdentity(
      Connection c,
      ObjectNode request,
      long user,
      String role,
      String fingerprint,
      long expectedRevision,
      boolean disabled)
      throws SQLException {
    String app = request.path("applicationId").asText();
    String project = request.path("targetProject").asText();
    String permissions =
        Json.write(
            Json.MAPPER.valueToTree(
                OnboardingService.permissions(request.path("profile").asText())));
    String actualHash = null;
    long revision = -1;
    int enabled = 0;
    try (var s =
            prepare(
                c,
                "SELECT credential_hash,revision,enabled FROM ops_harness_application WHERE"
                    + " application_id=? FOR UPDATE",
                app);
        var r = s.executeQuery()) {
      if (r.next()) {
        actualHash = r.getString(1);
        revision = r.getLong(2);
        enabled = r.getInt(3);
      }
    }
    if (disabled) {
      if (expectedRevision == -1 && revision == -1) return;
      if (revision == expectedRevision && actualHash != null) {
        if (execute(
                c,
                "UPDATE ops_harness_application SET enabled=0,revision=revision+1 WHERE"
                    + " application_id=? AND revision=?",
                app,
                expectedRevision)
            != 1) throw new IllegalStateException("IDENTITY_REVISION_CONFLICT");
      } else if (revision != expectedRevision + 1 || enabled != 0)
        throw new IllegalStateException("IDENTITY_REVISION_CONFLICT");
      return;
    }
    if (expectedRevision == -1 && revision == -1) {
      execute(
          c,
          "INSERT INTO ops_harness_application(application_id,credential_hash,enabled,revision)"
              + " VALUES(?,?,1,0)",
          app,
          fingerprint);
    } else if (revision == expectedRevision && expectedRevision >= 0) {
      if (execute(
              c,
              "UPDATE ops_harness_application SET credential_hash=?,enabled=1,revision=revision+1"
                  + " WHERE application_id=? AND revision=?",
              fingerprint,
              app,
              expectedRevision)
          != 1) throw new IllegalStateException("IDENTITY_REVISION_CONFLICT");
    } else if (revision != expectedRevision + 1 || enabled != 1 || !fingerprint.equals(actualHash))
      throw new IllegalStateException("IDENTITY_REVISION_CONFLICT");
    List<JsonNode> grants = new ArrayList<>();
    try (var s =
            prepare(
                c,
                "SELECT project_id,user_id,required_role,permissions_json,enabled FROM"
                    + " ops_harness_grant WHERE application_id=? FOR UPDATE",
                app);
        var r = s.executeQuery()) {
      while (r.next())
        grants.add(
            Json.object()
                .put("project", r.getString(1))
                .put("user", r.getLong(2))
                .put("role", r.getString(3))
                .put("permissions", r.getString(4))
                .put("enabled", r.getInt(5)));
    }
    if (grants.isEmpty() && expectedRevision == -1) {
      execute(
          c,
          "INSERT INTO"
              + " ops_harness_grant(application_id,project_id,user_id,required_role,permissions_json,enabled,revision)"
              + " VALUES(?,?,?,?,?,1,0)",
          app,
          project,
          user,
          role,
          permissions);
    } else if (grants.size() != 1
        || !grants.get(0).path("project").asText().equals(project)
        || grants.get(0).path("user").asLong() != user
        || !grants.get(0).path("role").asText().equals(role)
        || grants.get(0).path("enabled").asInt() != 1
        || !Json.read(grants.get(0).path("permissions").asText()).equals(Json.read(permissions)))
      throw new IllegalStateException("IDENTITY_GRANT_REVIEW_REQUIRED");
    if (!request.path("targetProject").asText().equals(request.path("sponsorProject").asText())) {
      String console = request.path("requestingApplication").asText();
      if (scalar(
              c,
              "SELECT COUNT(*) FROM ops_harness_application WHERE application_id=? AND enabled=1",
              console)
          != 1) throw new IllegalStateException("CONSOLE_IDENTITY_MISSING");
      try (var s =
              prepare(
                  c,
                  "SELECT required_role,permissions_json,enabled FROM ops_harness_grant WHERE"
                      + " application_id=? AND project_id=? AND user_id=? FOR UPDATE",
                  console,
                  project,
                  user);
          var r = s.executeQuery()) {
        if (r.next()) {
          if (!r.getString(1).equals(role)
              || !Json.read(r.getString(2)).equals(Json.read(permissions))
              || r.getInt(3) != 1) throw new IllegalStateException("CONSOLE_GRANT_REVIEW_REQUIRED");
        } else {
          execute(
              c,
              "INSERT INTO"
                  + " ops_harness_grant(application_id,project_id,user_id,required_role,permissions_json,enabled,revision)"
                  + " VALUES(?,?,?,?,?,1,0)",
              console,
              project,
              user,
              role,
              permissions);
        }
      }
    }
  }

  static void requireOffline(Connection c, long leaseMillis) throws SQLException {
    Set<String> tables = new HashSet<>();
    try (var r = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
      while (r.next()) tables.add(r.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
    }
    if (scalar(
            c,
            "SELECT COUNT(*) FROM harness_runs WHERE run_status NOT IN"
                + " ('COMPLETED','FAILED','CANCELLED','EXPIRED','BUDGET_EXCEEDED')")
        != 0) throw new IllegalStateException("ONBOARDING_REQUIRES_DRAINED_CLUSTER");
    if (tables.contains("gov_worker")
        && scalar(
                c,
                "SELECT COUNT(*) FROM gov_worker WHERE state<>'STOPPED' AND heartbeat_at>?",
                now(c) - Math.max(30000, leaseMillis + 10000))
            != 0) throw new IllegalStateException("ONBOARDING_REQUIRES_OFFLINE_WORKERS");
    if (tables.contains("platform_slots")
        && scalar(
                c,
                "SELECT COUNT(*) FROM platform_slots WHERE owner_id IS NOT NULL AND lease_until>?",
                now(c))
            != 0) throw new IllegalStateException("ONBOARDING_REQUIRES_RELEASED_SLOTS");
    if (tables.contains("gov_permit")
        && scalar(
                c,
                "SELECT COUNT(*) FROM gov_permit WHERE state IN"
                    + " ('RESERVED','DISPATCHED','UNKNOWN')")
            != 0) throw new IllegalStateException("ONBOARDING_REQUIRES_RESOLVED_PERMITS");
  }

  private static long scalar(Connection c, String sql, Object... values) throws SQLException {
    try (var s = prepare(c, sql, values);
        var r = s.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }

  static String credentialHash(String secret) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** JVM Set iteration order must not change a manifest's identity after restarting. */
  static String deploymentDigest(Deployment deployment) {
    ObjectNode value = deploymentDocument(deployment);
    // These are process-role settings; API and worker nodes intentionally differ.
    value.remove(List.of("workerEnabled", "concurrency"));
    return Json.hash(value);
  }

  private static ObjectNode deploymentDocument(Deployment deployment) {
    ObjectNode value = Json.MAPPER.valueToTree(deployment);
    value.set(
        "disabledReleases", Json.MAPPER.valueToTree(new TreeSet<>(deployment.disabledReleases())));
    for (JsonNode project : value.path("projects")) {
      var sorted = new TreeSet<String>();
      project.path("applications").forEach(v -> sorted.add(v.asText()));
      ((ObjectNode) project).set("applications", Json.MAPPER.valueToTree(sorted));
    }
    // Release and Workflow constructors normalize their declared sets to sorted sets.
    return value;
  }

  static void ensurePrivateDirectory(Path directory) throws java.io.IOException {
    if (Files.isSymbolicLink(directory)) throw new IllegalStateException("SYMLINK_REJECTED");
    if (!Files.exists(directory)) Files.createDirectory(directory);
    if (!Files.isDirectory(directory)) throw new IllegalStateException("DIRECTORY_REQUIRED");
    if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))
      throw new IllegalStateException("REDIRECTED_DIRECTORY_REJECTED");
    restrict(directory, true);
  }

  private static void restrict(Path file, boolean directory) throws java.io.IOException {
    if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
      Files.setPosixFilePermissions(
          file, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    } else {
      AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
      if (acl == null) throw new IllegalStateException("PRIVATE_PERMISSIONS_UNSUPPORTED");
      var entry =
          AclEntry.newBuilder()
              .setType(AclEntryType.ALLOW)
              .setPrincipal(Files.getOwner(file))
              .setPermissions(EnumSet.allOf(AclEntryPermission.class));
      if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
      acl.setAcl(List.of(entry.build()));
    }
  }

  static void writeOnce(Path file, String text) throws java.io.IOException {
    if (Files.isSymbolicLink(file)) throw new IllegalStateException("SYMLINK_REJECTED");
    if (Files.exists(file)) {
      restrict(file, false);
      if (!Files.readString(file).equals(text)) throw new IllegalStateException("OUTPUT_CONFLICT");
      return;
    }
    Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      Files.createFile(temporary);
      restrict(temporary, false);
      Files.writeString(
          temporary, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(temporary, file);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static String secret(Path file) throws java.io.IOException {
    if (Files.isSymbolicLink(file)) throw new IllegalStateException("SYMLINK_REJECTED");
    if (Files.exists(file)) {
      restrict(file, false);
      String value = Files.readString(file);
      if (!value.matches("[A-Za-z0-9_-]{43}"))
        throw new IllegalStateException("CREDENTIAL_FILE_INVALID");
      return value;
    }
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    writeOnce(file, secret);
    return secret;
  }

  private static HikariDataSource dataSource(Environment env, String prefix) {
    var settings = new HikariConfig();
    settings.setJdbcUrl(required(env, prefix + "_URL"));
    settings.setUsername(required(env, prefix + "_USER"));
    settings.setPassword(required(env, prefix + "_PASSWORD"));
    settings.setMaximumPoolSize(1);
    settings.setConnectionTimeout(5000);
    return new HikariDataSource(settings);
  }

  private static String required(Environment env, String key) {
    String value = env.getProperty(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException("ONBOARDING_CONFIGURATION_REQUIRED");
    return value;
  }
}
