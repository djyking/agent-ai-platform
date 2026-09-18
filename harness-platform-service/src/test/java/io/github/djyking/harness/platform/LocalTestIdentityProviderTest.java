package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.testing.MutableClock;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LocalTestIdentityProviderTest {
  @TempDir Path temporary;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-18T00:00:00Z"));
  private final Map<String, String> values =
      new HashMap<>(
          Map.of(
              "env:SIGNING", "synthetic-signing-secret-long-enough-for-local-tests",
              "env:APP_A", "synthetic-application-a-credential-0123456789",
              "env:APP_B", "synthetic-application-b-credential-0123456789",
              "env:ALICE", "synthetic-alice-local-password-12345"));
  private final Deployment deployment =
      new Deployment(
          "https://identity.invalid",
          "env:SIGNING",
          Map.of("app-a", "env:APP_A", "app-b", "env:APP_B"),
          List.of(
              new Deployment.Project("studio", Set.of("app-a", "app-b"), 10, 100000, 5, 50000, 1),
              new Deployment.Project("private", Set.of("app-a"), 10, 100000, 5, 50000, 1)),
          List.of(),
          Set.of(),
          List.of(),
          List.of(),
          1,
          10000,
          false);

  private ObjectNode configuration(String... permissions) {
    var grant = Json.object().put("application", "app-a").put("project", "studio");
    var items = grant.putArray("permissions");
    for (String permission : permissions) items.add(permission);
    var user =
        Json.object()
            .put("username", "alice")
            .put("subject", "subject-alice")
            .put("secretRef", "env:ALICE");
    user.putArray("grants").add(grant);
    var root = Json.object();
    root.putArray("users").add(user);
    return root;
  }

  private void write(ObjectNode root) throws Exception {
    Files.writeString(temporary.resolve("users.json"), Json.write(root));
  }

  private LocalTestIdentityProvider provider(boolean enabled, String address) {
    return new LocalTestIdentityProvider(
        deployment,
        values::get,
        temporary.resolve("users.json"),
        temporary.resolve("state"),
        enabled,
        address,
        clock);
  }

  private String login(LocalTestIdentityProvider provider) {
    return provider.login(
        Json.object().put("username", "alice").put("password", values.get("env:ALICE")));
  }

  @BeforeEach
  void setup() throws Exception {
    write(configuration("runs:create", "runs:read", "model:invoke"));
  }

  @Test
  void factoryKeepsOpsDefaultAndRequiresExplicitLocalTestConfigurationAndLoopback() {
    var ops = IdentityProviders.create(deployment, values::get, new MockEnvironment());
    assertInstanceOf(OpsAgentIdentityProvider.class, ops.identity());
    assertInstanceOf(ConsoleIdentityLogin.class, ops.login());
    assertFalse(ops.login().configuration().path("localTestOnly").asBoolean());
    assertThrows(IllegalArgumentException.class, () -> provider(false, "127.0.0.1"));
    for (String address : List.of("", "0.0.0.0", "::", "localhost", "192.168.1.1"))
      assertThrows(IllegalArgumentException.class, () -> provider(true, address));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IdentityProviders.create(
                deployment,
                values::get,
                new MockEnvironment().withProperty("HARNESS_IDENTITY_PROVIDER", "unknown")));
    var env =
        new MockEnvironment()
            .withProperty("HARNESS_IDENTITY_PROVIDER", "local-test")
            .withProperty("HARNESS_ALLOW_LOCAL_TEST_IDENTITY", "true")
            .withProperty("server.address", "127.0.0.1")
            .withProperty(
                "HARNESS_LOCAL_IDENTITY_CONFIG", temporary.resolve("users.json").toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> IdentityProviders.create(deployment, values::get, env));
    env.withProperty("HARNESS_LOCAL_IDENTITY_STATE", temporary.resolve("state").toString());
    var local = IdentityProviders.create(deployment, values::get, env);
    assertSame(local.identity(), local.login());
    assertTrue(local.login().configuration().path("localTestOnly").asBoolean());
    assertFalse(local.login().captchaRequired());
    assertFalse(local.login().captcha().path("required").asBoolean(true));
  }

  @Test
  void loginNeverGrantsProjectsOrApplicationsMissingFromTrustedGrants() {
    var identity = provider(true, "127.0.0.1");
    String token = login(identity);
    var principal = identity.authenticate("studio", values.get("env:APP_A"), token);
    assertEquals("subject-alice", principal.subject());
    assertEquals(Set.of("runs:create", "runs:read", "model:invoke"), principal.permissions());
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () ->
                    identity.login(Json.object().put("username", "alice").put("password", "wrong")))
            .status);
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () ->
                    identity.login(
                        Json.object()
                            .put("username", "not-an-account")
                            .put("password", values.get("env:ALICE"))))
            .status);
    assertEquals(
        401,
        assertThrows(ApiFailure.class, () -> identity.authenticate("studio", "wrong-app", token))
            .status);
    assertEquals(
        403,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_B"), token))
            .status);
    assertEquals(
        403,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("private", values.get("env:APP_A"), token))
            .status);
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_A"), token + "tamper"))
            .status);
    assertFalse(
        new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8)
            .contains(values.get("env:ALICE")));
  }

  @Test
  void persistedDelegationSurvivesRestartButIsBoundToRunScopeDeadlineAndFreshGrants()
      throws Exception {
    var identity = provider(true, "127.0.0.1");
    String token = login(identity), run = UUID.randomUUID().toString();
    var principal = identity.authenticate("studio", values.get("env:APP_A"), token);
    String delegation =
        identity.delegate(
            principal, values.get("env:APP_A"), token, run, clock.instant().plusSeconds(3600));
    assertTrue(delegation.length() <= 128);
    var restarted = provider(true, "127.0.0.1");
    assertEquals(principal, restarted.current("app-a", "studio", delegation, run));
    for (String[] mismatch :
        List.of(
            new String[] {"app-b", "studio", run},
            new String[] {"app-a", "private", run},
            new String[] {"app-a", "studio", UUID.randomUUID().toString()}))
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () -> restarted.current(mismatch[0], mismatch[1], delegation, mismatch[2]))
              .status);
    assertEquals(
        403,
        assertThrows(ApiFailure.class, () -> restarted.current("app-a", "studio", "../users", run))
            .status);
    clock.advance(Duration.ofMinutes(31));
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () -> restarted.authenticate("studio", values.get("env:APP_A"), token))
            .status);
    assertEquals(principal, restarted.current("app-a", "studio", delegation, run));
    write(configuration("runs:read"));
    assertEquals(
        Set.of("runs:read"), restarted.current("app-a", "studio", delegation, run).permissions());
    assertEquals(
        403,
        assertThrows(
                ApiFailure.class,
                () -> restarted.toolAuthorization("app-a", "studio", delegation, run))
            .status);
    clock.advance(Duration.ofMinutes(30));
    assertEquals(
        403,
        assertThrows(ApiFailure.class, () -> restarted.current("app-a", "studio", delegation, run))
            .status);
  }

  @Test
  void secretRotationTamperingAndAccountRemovalInvalidateExistingAuthority() throws Exception {
    var identity = provider(true, "127.0.0.1");
    String token = login(identity), run = UUID.randomUUID().toString();
    var p = identity.authenticate("studio", values.get("env:APP_A"), token);
    String delegation =
        identity.delegate(p, values.get("env:APP_A"), token, run, clock.instant().plusSeconds(60));
    Path receipt = temporary.resolve("state").resolve(delegation + ".json");
    String original = Files.readString(receipt);
    assertFalse(original.contains(values.get("env:ALICE")));
    assertFalse(original.contains(values.get("env:APP_A")));
    Files.writeString(receipt, original + "tamper");
    assertEquals(
        403,
        assertThrows(ApiFailure.class, () -> identity.current("app-a", "studio", delegation, run))
            .status);
    Files.writeString(receipt, original);
    values.put("env:ALICE", "synthetic-rotated-password-123456789");
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_A"), token))
            .status);
    assertEquals(
        403,
        assertThrows(ApiFailure.class, () -> identity.current("app-a", "studio", delegation, run))
            .status);
    String fresh = login(identity);
    var removed = configuration("runs:read");
    ((ObjectNode) removed.path("users").get(0))
        .put("username", "replacement")
        .put("subject", "replacement");
    write(removed);
    assertEquals(
        401,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_A"), fresh))
            .status);
  }

  @Test
  void malformedTrustedConfigAndAmbiguousAppSecretsFailClosed() throws Exception {
    write(configuration("*"));
    assertEquals(503, assertThrows(ApiFailure.class, () -> provider(true, "127.0.0.1")).status);
    write(configuration("runs:read").put("password", "never-accepted-in-configuration"));
    assertEquals(503, assertThrows(ApiFailure.class, () -> provider(true, "127.0.0.1")).status);
    write(configuration("runs:read"));
    var identity = provider(true, "127.0.0.1");
    String token = login(identity);
    values.put("env:APP_B", values.get("env:APP_A"));
    assertEquals(
        503,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_A"), token))
            .status);
    Files.writeString(temporary.resolve("users.json"), "{\"users\":[],\"users\":[]}");
    assertEquals(
        503,
        assertThrows(
                ApiFailure.class,
                () -> identity.authenticate("studio", values.get("env:APP_A"), token))
            .status);
  }
}
