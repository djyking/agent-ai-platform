package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Explicitly opt-in, loopback-only acceptance identity. No production account store, registration,
 * role administration or third-party delegation. Secret references and grants are operator-owned.
 */
public final class LocalTestIdentityProvider implements IdentityProvider, IdentityLogin {
  private record Grant(String application, String project, Set<String> permissions) {}

  private record User(String username, String subject, String secretRef, List<Grant> grants) {}

  private final Deployment deployment;
  private final SecretProvider secrets;
  private final Path configFile;
  private final Path delegationDirectory;
  private final SignedTokens signing;
  private final Clock clock;

  public LocalTestIdentityProvider(
      Deployment deployment,
      SecretProvider secrets,
      Path configFile,
      Path delegationDirectory,
      boolean explicitlyEnabled,
      String listenAddress) {
    this(
        deployment,
        secrets,
        configFile,
        delegationDirectory,
        explicitlyEnabled,
        listenAddress,
        Clock.systemUTC());
  }

  LocalTestIdentityProvider(
      Deployment deployment,
      SecretProvider secrets,
      Path configFile,
      Path delegationDirectory,
      boolean explicitlyEnabled,
      String listenAddress,
      Clock clock) {
    if (!explicitlyEnabled || !Set.of("127.0.0.1", "::1", "[::1]").contains(listenAddress))
      throw new IllegalArgumentException("Local test identity requires explicit loopback binding");
    this.deployment = Objects.requireNonNull(deployment);
    this.secrets = Objects.requireNonNull(secrets);
    this.configFile = configFile.toAbsolutePath().normalize();
    this.delegationDirectory = delegationDirectory.toAbsolutePath().normalize();
    // Separate signing domain from platform cursors/ETags even when using the same host secret.
    this.signing =
        new SignedTokens(
            Json.hash(
                List.of(
                    "harness-local-test-identity-v1",
                    secrets.resolve(deployment.signingSecret()))));
    this.clock = clock;
    users(); // Refuse invalid grants or missing secrets during startup.
    try {
      Files.createDirectories(this.delegationDirectory);
    } catch (Exception unavailable) {
      throw new IllegalStateException("Local test identity state unavailable");
    }
  }

  @Override
  public boolean captchaRequired() {
    return false;
  }

  @Override
  public JsonNode configuration() {
    return Json.object()
        .put("provider", "local-test")
        .put("localTestOnly", true)
        .put("captchaRequired", false);
  }

  @Override
  public JsonNode captcha() {
    return Json.object()
        .put("required", false)
        .put("provider", "local-test")
        .put("notice", "仅本机隔离验收身份，不是生产账号库");
  }

  @Override
  public String login(JsonNode input) {
    if (input == null || !input.isObject()) throw unauthenticated();
    String username = input.path("username").asText("");
    String password = input.path("password").asText("");
    if (username.length() > 128 || password.length() > 512) throw unauthenticated();
    User user = users().get(username);
    if (user == null || !same(password, secrets.resolve(user.secretRef()))) throw unauthenticated();
    return signing.sign(
        token("session", user, clock.instant().plusSeconds(1800))
            .put("nonce", UUID.randomUUID().toString()));
  }

  @Override
  public Principal authenticate(String project, String applicationCredential, String userToken) {
    JsonNode token = verify(userToken, "session");
    User user = tokenUser(token);
    String application = application(applicationCredential);
    return principal(user, application, project);
  }

  @Override
  public String delegate(
      Principal principal,
      String applicationCredential,
      String userToken,
      String runId,
      Instant deadline) {
    if (!principal.equals(authenticate(principal.project(), applicationCredential, userToken)))
      throw ApiFailure.denied();
    ApiJson.uuid(runId);
    Instant now = clock.instant();
    if (deadline == null || !deadline.isAfter(now) || deadline.isAfter(now.plusSeconds(172800)))
      throw ApiFailure.denied();
    User user = tokenUser(verify(userToken, "session"));
    String id = "local-" + UUID.randomUUID();
    String value =
        signing.sign(
            token("delegation", user, deadline)
                .put("application", principal.application())
                .put("project", principal.project())
                .put("run", runId)
                .put("id", id));
    try {
      // Opaque, short IDs fit the existing durable delegation column. The separate signed receipt
      // survives an API/worker restart and stores no application credential, password or user
      // token.
      Files.writeString(
          delegationDirectory.resolve(id + ".json"),
          value,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return id;
    } catch (Exception unavailable) {
      throw new ApiFailure(503, "AUTH_PROVIDER_UNAVAILABLE");
    }
  }

  @Override
  public Principal current(String application, String project, String delegationId, String runId) {
    if (delegationId == null
        || !delegationId.matches(
            "local-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
      throw ApiFailure.denied();
    try (InputStream stream =
        Files.newInputStream(delegationDirectory.resolve(delegationId + ".json"))) {
      byte[] bytes = stream.readNBytes(4097);
      if (bytes.length > 4096) throw ApiFailure.denied();
      JsonNode token = verify(new String(bytes, StandardCharsets.UTF_8), "delegation");
      if (!token.path("id").asText().equals(delegationId)
          || !token.path("application").asText().equals(application)
          || !token.path("project").asText().equals(project)
          || !token.path("run").asText().equals(runId)) throw ApiFailure.denied();
      return principal(tokenUser(token), application, project);
    } catch (Exception unavailable) {
      throw ApiFailure.denied();
    }
  }

  @Override
  public String toolAuthorization(
      String application, String project, String delegationId, String runId) {
    // A local acceptance identity must never impersonate a user in an external business system.
    throw ApiFailure.denied();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode token(
      String type, User user, Instant expires) {
    return Json.object()
        .put("type", type)
        .put("username", user.username())
        .put("subject", user.subject())
        .put("credentialVersion", credentialVersion(user))
        .put("expiresAt", expires.toEpochMilli());
  }

  private JsonNode verify(String value, String type) {
    try {
      JsonNode token = signing.verify(value, "INVALID_IDENTITY_TOKEN");
      if (!token.isObject()
          || !token.path("type").asText().equals(type)
          || !token.path("expiresAt").isIntegralNumber()
          || !token.path("expiresAt").canConvertToLong()
          || token.path("expiresAt").asLong() <= clock.millis()) throw unauthenticated();
      return token;
    } catch (RuntimeException invalid) {
      throw unauthenticated();
    }
  }

  private User tokenUser(JsonNode token) {
    User user = users().get(token.path("username").asText());
    if (user == null
        || !user.subject().equals(token.path("subject").asText())
        || !same(credentialVersion(user), token.path("credentialVersion").asText()))
      throw unauthenticated();
    return user;
  }

  private String credentialVersion(User user) {
    // Only the MAC of this data is persisted; the password and encoded payload never leave memory.
    String signed =
        signing.sign(
            Json.object()
                .put("user", user.username())
                .put("credential", secrets.resolve(user.secretRef())));
    return signed.substring(signed.indexOf('.') + 1);
  }

  private Principal principal(User user, String application, String project) {
    return user.grants().stream()
        .filter(g -> g.application().equals(application) && g.project().equals(project))
        .map(g -> new Principal(application, project, user.subject(), g.permissions()))
        .findFirst()
        .orElseThrow(ApiFailure::denied);
  }

  private String application(String credential) {
    if (credential == null || credential.length() > 8192) throw unauthenticated();
    String matched = null;
    for (var entry : deployment.applicationSecrets().entrySet()) {
      if (same(credential, secrets.resolve(entry.getValue()))) {
        if (matched != null) throw new ApiFailure(503, "AUTH_PROVIDER_UNAVAILABLE");
        matched = entry.getKey();
      }
    }
    if (matched == null) throw unauthenticated();
    return matched;
  }

  /** Reloads trusted grants for every decision, so removing a grant also revokes existing runs. */
  private Map<String, User> users() {
    try (InputStream stream = Files.newInputStream(configFile)) {
      JsonNode root = ApiJson.read(stream);
      fields(root, Set.of("users"));
      if (!root.path("users").isArray()
          || root.path("users").isEmpty()
          || root.path("users").size() > 100) throw new IllegalArgumentException();
      Map<String, User> users = new LinkedHashMap<>();
      Set<String> subjects = new HashSet<>();
      for (JsonNode node : root.path("users")) {
        fields(node, Set.of("username", "subject", "secretRef", "grants"));
        String username = text(node, "username"),
            subject = text(node, "subject"),
            secretRef = text(node, "secretRef");
        ApiJson.identifier(username);
        ApiJson.identifier(subject);
        if (!secretRef.matches("env:[A-Z][A-Z0-9_]{1,100}") && !secretRef.startsWith("file:"))
          throw new IllegalArgumentException();
        String password = secrets.resolve(secretRef);
        if (password == null || password.length() < 16 || password.length() > 512)
          throw new IllegalArgumentException();
        if (!node.path("grants").isArray() || node.path("grants").size() > 100)
          throw new IllegalArgumentException();
        List<Grant> grants = new ArrayList<>();
        Set<String> scopes = new HashSet<>();
        for (JsonNode grant : node.path("grants")) {
          fields(grant, Set.of("application", "project", "permissions"));
          String app = text(grant, "application"), project = text(grant, "project");
          if (!deployment.project(project).applications().contains(app)
              || !scopes.add(app + "/" + project)
              || !grant.path("permissions").isArray()
              || grant.path("permissions").size() > 100) throw new IllegalArgumentException();
          Set<String> permissions = new HashSet<>();
          for (JsonNode permission : grant.path("permissions")) {
            if (!permission.isTextual()
                || !permission.asText().matches("[A-Za-z0-9][A-Za-z0-9:._/-]{0,127}")
                || !permissions.add(permission.asText())) throw new IllegalArgumentException();
          }
          grants.add(new Grant(app, project, Set.copyOf(permissions)));
        }
        if (users.putIfAbsent(username, new User(username, subject, secretRef, List.copyOf(grants)))
                != null
            || !subjects.add(subject)) throw new IllegalArgumentException();
      }
      return users;
    } catch (Exception invalid) {
      throw new ApiFailure(503, "AUTH_PROVIDER_UNAVAILABLE");
    }
  }

  private static void fields(JsonNode node, Set<String> allowed) {
    if (!node.isObject() || node.size() != allowed.size()) throw new IllegalArgumentException();
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw new IllegalArgumentException();
            });
  }

  private static String text(JsonNode node, String name) {
    if (!node.path(name).isTextual() || node.path(name).asText().isBlank())
      throw new IllegalArgumentException();
    return node.path(name).asText();
  }

  private static boolean same(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static ApiFailure unauthenticated() {
    return new ApiFailure(401, "UNAUTHENTICATED");
  }
}
