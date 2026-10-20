package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class OnboardingServiceTest {
  @TempDir Path temporary;
  final IdentityProvider.Principal owner =
      new IdentityProvider.Principal("app-a", "project-a", "123", Set.of("onboarding:request"));
  final IdentityProvider.Principal admin =
      new IdentityProvider.Principal("app-a", "project-a", "999", Set.of("onboarding:admin"));

  OnboardingService service(PlatformTestSupport f) {
    new CapabilityRepository(f.repository).initialize();
    var service =
        new OnboardingService(
            f.config, f.repository, f.identities, ref -> "unused-test-secret-01234567890");
    service.initialize();
    return service;
  }

  ObjectNode input() {
    return Json.object()
        .put("targetProject", "project-a")
        .put("applicationId", "support-new")
        .put("name", "Support")
        .put("purpose", "Owner 123 maintains read-only support")
        .put("profile", "knowledge-reader");
  }

  ObjectNode approve(OnboardingService service) {
    service.request(owner, "request-1", "request-key-1", "\"k0\"", input());
    return (ObjectNode)
        service.decide(
            admin,
            "request-1",
            "approve-key-1",
            "\"k1\"",
            Json.object().put("decision", "APPROVE").put("reason", "Reviewed fixed scope"));
  }

  @Test
  void approvalIsIdempotentAuditedButDoesNotActivateAndScopesStayHidden() {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      var approved = approve(service);
      assertEquals("APPROVED", approved.path("state").asText());
      assertEquals(2, approved.path("revision").asInt());
      assertEquals(2, approved.path("audit").size());
      assertEquals(
          ApiJson.canonical(approved),
          ApiJson.canonical(
              service.decide(
                  admin,
                  "request-1",
                  "approve-key-1",
                  "\"k1\"",
                  Json.object().put("decision", "APPROVE").put("reason", "Reviewed fixed scope"))));
      assertEquals(2, service.get(owner, "request-1").path("revision").asInt());
      assertEquals(2, service.get(owner, "request-1").path("audit").size());
      long gateCount =
          f.repository.transaction(
              c -> {
                try (var statement = c.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM onboarding_bindings")) {
                  rows.next();
                  return rows.getLong(1);
                }
              });
      assertEquals(1, gateCount, "Replayed approval must not add another application gate");
      var app = new IdentityProvider.Principal("support-new", "project-a", "123", Set.of());
      assertEquals(
          "APPLICATION_NOT_ACTIVE",
          assertThrows(ApiFailure.class, () -> service.requireAccess(app)).code);
      var other =
          new IdentityProvider.Principal("app-a", "project-a", "456", Set.of("onboarding:request"));
      assertEquals(0, service.list(other, "", 10).path("items").size());
      assertEquals(
          404, assertThrows(ApiFailure.class, () -> service.get(other, "request-1")).status);
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.decide(
                          owner,
                          "request-1",
                          "no-admin-key",
                          "\"k2\"",
                          Json.object().put("decision", "APPROVE").put("reason", "no")))
              .status);
      var foreign =
          new IdentityProvider.Principal("app-a", "project-b", "999", Set.of("onboarding:admin"));
      assertEquals(
          404, assertThrows(ApiFailure.class, () -> service.get(foreign, "request-1")).status);
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.request(
                          owner,
                          "request-1",
                          "request-key-1",
                          "\"k0\"",
                          input().put("profile", "builder")))
              .status);
    }
  }

  @Test
  void ownerPaginationFiltersBeforeLimitAndSchemaOneBackfillsWithoutLosingRequests() {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      var other =
          new IdentityProvider.Principal("app-a", "project-a", "456", Set.of("onboarding:request"));
      for (int n = 0; n < 12; n++) {
        String id = String.format("request-%02d", n);
        service.request(
            n == 3 || n == 11 ? owner : other,
            id,
            "create-" + id,
            "\"k0\"",
            input().put("applicationId", "application-" + n));
      }
      JsonNode first = service.list(owner, "", 1);
      assertEquals(1, first.path("items").size());
      assertEquals("request-03", first.path("items").get(0).path("id").asText());
      assertEquals("request-03", first.path("nextCursor").asText());
      JsonNode last = service.list(owner, first.path("nextCursor").asText(), 1);
      assertEquals("request-11", last.path("items").get(0).path("id").asText());
      assertFalse(last.has("nextCursor"));
      assertEquals(12, service.list(admin, "", 100).path("items").size());
      f.repository.transaction(
          c -> {
            execute(c, "DROP TABLE onboarding_requests");
            execute(c, "UPDATE harness_schema SET schema_version=1 WHERE component='onboarding'");
            return null;
          });
      var migrated = new OnboardingRepository(f.repository);
      assertThrows(RuntimeException.class, migrated::validateSchema);
      migrated.initialize();
      migrated.initialize();
      migrated.validateSchema();
      assertEquals(ApiJson.canonical(first), ApiJson.canonical(service.list(owner, "", 1)));
      assertEquals(12, service.list(admin, "", 100).path("items").size());
      long owners =
          f.repository.transaction(
              c -> {
                try (var statement = c.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM onboarding_requests")) {
                  assertTrue(rows.next());
                  return rows.getLong(1);
                }
              });
      assertEquals(12, owners);
    }
  }

  @Test
  void rejectsUserSuppliedPermissionsSecretsAndCrossProjectAuthority() {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      assertEquals(
          400,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.request(
                          owner,
                          "req-1",
                          "request-key-2",
                          "\"k0\"",
                          input().put("permissions", "*")))
              .status);
      assertEquals(
          400,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.request(
                          owner,
                          "req-1",
                          "request-key-2",
                          "\"k0\"",
                          input().put("secretRef", "file:/secret")))
              .status);
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.request(
                          owner,
                          "req-1",
                          "request-key-2",
                          "\"k0\"",
                          input().put("targetProject", "project-b")))
              .status);
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.request(
                          owner,
                          "req-1",
                          "request-key-2",
                          "\"k0\"",
                          input().put("applicationId", "app-a")))
              .status);
    }
  }

  @Test
  void concurrentReviewAllowsOnlyOneRevisionAndOneGate() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      service.request(owner, "request-1", "request-key-1", "\"k0\"", input());
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> replies = new ArrayList<>();
        for (String decision : List.of("APPROVE", "REJECT")) {
          replies.add(
              executor.submit(
                  () -> {
                    start.await();
                    try {
                      return service
                          .decide(
                              admin,
                              "request-1",
                              "decision-" + decision,
                              "\"k1\"",
                              Json.object()
                                  .put("decision", decision)
                                  .put("reason", "Concurrent review"))
                          .path("state")
                          .asText();
                    } catch (ApiFailure stale) {
                      assertEquals(412, stale.status);
                      return "STALE";
                    }
                  }));
        }
        start.countDown();
        List<String> outcomes = List.of(replies.get(0).get(), replies.get(1).get());
        assertEquals(1, outcomes.stream().filter("STALE"::equals).count());
        assertEquals(2, service.get(owner, "request-1").path("revision").asInt());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void realOfflineCliCreatesAuthorityThenVerificationRotationAndDisableAreEnforced()
      throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      var approved = approve(service);
      JdbcDataSource authority = authority();
      Path source = temporary.resolve("source.json");
      Files.writeString(source, Json.write(f.config));
      var env = environment(f, authority, source);
      Path command = temporary.resolve("command.json");
      Files.writeString(command, Json.write(command(approved, -1)));
      OnboardingProvisioner.run(command, env);
      OnboardingProvisioner.run(command, env);
      var provisioned = service.get(owner, "request-1");
      assertEquals("PROVISIONED", provisioned.path("state").asText());
      assertEquals(3, provisioned.path("revision").asInt());
      Path candidate = temporary.resolve("private/request-1-g1/deployment.json");
      String oldCredential =
          Files.readString(candidate.getParent().resolve("application.credential"));
      assertFalse(Json.write(provisioned).contains(oldCredential));
      assertFalse(Files.readString(candidate).contains(oldCredential));
      assertEquals(
          Json.write(f.config), Files.readString(source), "Never overwrite the live manifest");
      assertEquals(
          OnboardingProvisioner.credentialHash(oldCredential),
          authorityScalar(
              authority,
              "SELECT credential_hash FROM ops_harness_application WHERE"
                  + " application_id='support-new'"));

      var live =
          new OnboardingService(
              Deployment.load(candidate),
              f.repository,
              identity(authority),
              SecretProvider.environment());
      live.verify(owner, "request-1", "verify-key-1", "\"k3\"", "user-123");
      var accepted = new IdentityProvider.Principal("support-new", "project-a", "123", Set.of());
      live.requireAccess(accepted);
      var rotated =
          live.lifecycle(
              admin,
              "request-1",
              "rotate-key-1",
              "\"k4\"",
              Json.object().put("operation", "ROTATE").put("reason", "Scheduled rotation"));
      assertEquals(
          "APPLICATION_NOT_ACTIVE",
          assertThrows(ApiFailure.class, () -> live.requireAccess(accepted)).code);
      env.setProperty("HARNESS_CONFIG", candidate.toString());
      Files.writeString(command, Json.write(command(rotated, 0)));
      OnboardingProvisioner.run(command, env);
      Path nextCandidate = temporary.resolve("private/request-1-g2/deployment.json");
      String newCredential =
          Files.readString(nextCandidate.getParent().resolve("application.credential"));
      assertNotEquals(oldCredential, newCredential);
      assertThrows(
          ApiFailure.class,
          () -> identity(authority).authenticate("project-a", oldCredential, "user-123"));
      var next =
          new OnboardingService(
              Deployment.load(nextCandidate),
              f.repository,
              identity(authority),
              SecretProvider.environment());
      next.verify(owner, "request-1", "verify-key-2", "\"k6\"", "user-123");
      next.requireAccess(accepted);
      var disabled =
          next.lifecycle(
              admin,
              "request-1",
              "disable-key-1",
              "\"k7\"",
              Json.object().put("operation", "DISABLE").put("reason", "Application retired"));
      assertEquals(403, assertThrows(ApiFailure.class, () -> next.requireAccess(accepted)).status);
      env.setProperty("HARNESS_CONFIG", nextCandidate.toString());
      Files.writeString(command, Json.write(command(disabled, 1)));
      OnboardingProvisioner.run(command, env);
      assertEquals(
          "0",
          authorityScalar(
              authority,
              "SELECT enabled FROM ops_harness_application WHERE application_id='support-new'"));
      assertThrows(
          ApiFailure.class,
          () -> identity(authority).authenticate("project-a", newCredential, "user-123"));
      assertEquals("DISABLED", next.get(owner, "request-1").path("state").asText());
      assertFalse(Json.write(next.get(owner, "request-1")).contains(newCredential));
      assertEquals(
          0, f.count("harness_runs"), "Credential lifecycle does not create or reassign Runs");
    }
  }

  @Test
  void offlineCliRejectsActiveRunsAndDoesNotWriteCredentialOrAuthority() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = service(f);
      var approved = approve(service);
      JdbcDataSource authority = authority();
      Path source = temporary.resolve("source.json");
      Files.writeString(source, Json.write(f.config));
      var env = environment(f, authority, source);
      Path command = temporary.resolve("command.json");
      Files.writeString(command, Json.write(command(approved, -1)));
      f.create("read", "active-run-key");
      assertThrows(IllegalStateException.class, () -> OnboardingProvisioner.run(command, env));
      assertFalse(Files.exists(temporary.resolve("private/request-1-g1")));
      assertEquals("0", authorityScalar(authority, "SELECT COUNT(*) FROM ops_harness_application"));
      assertEquals("APPROVED", service.get(owner, "request-1").path("state").asText());
    }
  }

  @Test
  void privateOutputsRejectConflictsAndCredentialReuseIsStable() throws Exception {
    Path directory = temporary.toRealPath().resolve("private-output");
    OnboardingProvisioner.ensurePrivateDirectory(directory);
    Path secret = directory.resolve("secret");
    String credential = OnboardingProvisioner.secret(secret);
    assertEquals(43, credential.length());
    assertEquals(credential, OnboardingProvisioner.secret(secret));
    assertThrows(
        IllegalStateException.class, () -> OnboardingProvisioner.writeOnce(secret, "different"));
    assertEquals(credential, Files.readString(secret));
  }

  @Test
  void deploymentAttestationAllowsWorkerRolesButBindsIdentityAndProjectQuotas() {
    try (var f = new PlatformTestSupport()) {
      Deployment api = f.config;
      Deployment worker =
          new Deployment(
              api.identityOrigin(),
              api.signingSecret(),
              api.applicationSecrets(),
              api.projects(),
              api.releases(),
              api.disabledReleases(),
              api.tools(),
              api.models(),
              10,
              api.leaseMillis(),
              true);
      assertEquals(
          OnboardingProvisioner.deploymentDigest(api),
          OnboardingProvisioner.deploymentDigest(worker));
      Deployment otherIdentity =
          new Deployment(
              "https://other-identity.invalid",
              api.signingSecret(),
              api.applicationSecrets(),
              api.projects(),
              api.releases(),
              api.disabledReleases(),
              api.tools(),
              api.models(),
              api.concurrency(),
              api.leaseMillis(),
              api.workerEnabled());
      assertNotEquals(
          OnboardingProvisioner.deploymentDigest(api),
          OnboardingProvisioner.deploymentDigest(otherIdentity));
    }
  }

  ObjectNode command(JsonNode request, long revision) {
    return Json.object()
        .put("sponsorProject", "project-a")
        .put("requestId", "request-1")
        .put("revision", request.path("revision").asLong())
        .put("planDigest", request.path("plan").path("digest").asText())
        .put("requiredRole", "USER")
        .put("expectedApplicationRevision", revision)
        .put("operator", "test-operator");
  }

  MockEnvironment environment(PlatformTestSupport f, JdbcDataSource authority, Path source)
      throws Exception {
    Path root = temporary.resolve("private");
    Files.createDirectories(root);
    return new MockEnvironment()
        .withProperty("HARNESS_CONFIG", source.toString())
        .withProperty("HARNESS_ONBOARDING_PRIVATE_DIR", root.toString())
        .withProperty("HARNESS_ONBOARDING_OFFLINE_ACK", "drained-isolated-cluster")
        .withProperty("HARNESS_MIGRATION_JDBC_URL", f.dataSource.getURL())
        .withProperty("HARNESS_MIGRATION_JDBC_USER", f.dataSource.getUser())
        .withProperty("HARNESS_MIGRATION_JDBC_PASSWORD", f.dataSource.getPassword())
        .withProperty("HARNESS_ONBOARDING_IDENTITY_JDBC_URL", authority.getURL())
        .withProperty("HARNESS_ONBOARDING_IDENTITY_JDBC_USER", authority.getUser())
        .withProperty("HARNESS_ONBOARDING_IDENTITY_JDBC_PASSWORD", authority.getPassword());
  }

  JdbcDataSource authority() throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    ds.setUser("authority");
    ds.setPassword("synthetic-only");
    try (var c = ds.getConnection()) {
      execute(
          c,
          "CREATE TABLE ops_harness_application(application_id VARCHAR(64) PRIMARY"
              + " KEY,credential_hash CHAR(64) UNIQUE NOT NULL,enabled INTEGER NOT NULL,revision"
              + " BIGINT NOT NULL)");
      execute(
          c,
          "CREATE TABLE ops_harness_grant(application_id VARCHAR(64),project_id VARCHAR(64),user_id"
              + " BIGINT,required_role VARCHAR(64),permissions_json TEXT,enabled INTEGER,revision"
              + " BIGINT,PRIMARY KEY(application_id,project_id,user_id))");
    }
    return ds;
  }

  String authorityScalar(JdbcDataSource ds, String sql) throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      assertTrue(r.next());
      return r.getString(1);
    }
  }

  IdentityProvider identity(JdbcDataSource authority) {
    return new IdentityProvider() {
      public Principal authenticate(String project, String credential, String userToken) {
        if (!userToken.equals("user-123")) throw ApiFailure.denied();
        try (var c = authority.getConnection();
            var s =
                prepare(
                    c,
                    "SELECT a.application_id,g.user_id,g.permissions_json FROM"
                        + " ops_harness_application a JOIN ops_harness_grant g ON"
                        + " a.application_id=g.application_id WHERE a.credential_hash=? AND"
                        + " a.enabled=1 AND g.enabled=1 AND g.project_id=?",
                    OnboardingProvisioner.credentialHash(credential),
                    project);
            var r = s.executeQuery()) {
          if (!r.next()) throw ApiFailure.denied();
          Set<String> permissions = new TreeSet<>();
          Json.read(r.getString(3)).forEach(v -> permissions.add(v.asText()));
          return new Principal(r.getString(1), project, r.getString(2), permissions);
        } catch (SQLException failure) {
          throw new IllegalStateException(failure);
        }
      }

      public String delegate(Principal p, String a, String u, String r, Instant d) {
        throw new UnsupportedOperationException();
      }

      public Principal current(String a, String p, String d, String r) {
        throw new UnsupportedOperationException();
      }

      public String toolAuthorization(String a, String p, String d, String r) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
