package io.github.djyking.harness.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

/**
 * Real HTTP login/captcha/introspection and BFF cookies, using only synthetic identity fixtures.
 */
class SupportPilotIdentityTest extends SupportPilotFixture {
  HttpServer auth;
  final AtomicBoolean revoked = new AtomicBoolean();
  final AtomicBoolean unavailable = new AtomicBoolean();
  final List<JsonNode> credentials = new ArrayList<>();
  final List<String> identityAuthorizations = new ArrayList<>();

  @BeforeEach
  @Override
  void start() throws Exception {
    super.start();
    app.close();
    auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    auth.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String path = exchange.getRequestURI().getPath();
            JsonNode data;
            int status = 200;
            if (unavailable.get()) {
              exchange.sendResponseHeaders(503, -1);
              return;
            }
            if (path.equals("/api/auth/captcha")) {
              data =
                  JSON.createObjectNode()
                      .put("captchaId", "a".repeat(32))
                      .put("imageDataUrl", "data:image/png;base64,aGVsbG8=")
                      .put("expiresInSeconds", 120);
            } else if (path.equals("/api/auth/login")) {
              JsonNode input = JSON.readTree(exchange.getRequestBody());
              credentials.add(input);
              String username = input.path("username").asText();
              if (!Set.of("alice", "bob", "reviewer").contains(username)
                  || !input.path("password").asText().equals("synthetic-password")
                  || !input.path("captchaId").asText().equals("a".repeat(32))
                  || !input.path("captchaCode").asText().equals("1234")) {
                status = 401;
                data = JSON.createObjectNode();
              } else
                data =
                    JSON.createObjectNode()
                        .put("accessToken", "synthetic-jwt-" + username)
                        .put("refreshToken", "synthetic-refresh-never-to-browser");
            } else if (path.equals("/internal/harness/introspect")) {
              identityAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
              JsonNode input = JSON.readTree(exchange.getRequestBody());
              String token = input.path("userToken").asText();
              if (revoked.get() || !token.startsWith("synthetic-jwt-")) {
                status = 403;
                data = JSON.createObjectNode();
              } else {
                String user = token.substring("synthetic-jwt-".length());
                var principal =
                    JSON.createObjectNode()
                        .put("applicationId", "support-client")
                        .put("projectId", "support-pilot")
                        .put("subject", user);
                var permissions = principal.putArray("permissions");
                (user.equals("reviewer")
                        ? List.of("approvals:read", "approvals:decide")
                        : List.of("runs:read", "runs:create", "runs:list", "catalog:read"))
                    .forEach(permissions::add);
                data = principal;
              }
            } else {
              exchange.sendResponseHeaders(404, -1);
              return;
            }
            byte[] bytes =
                JSON.writeValueAsBytes(JSON.createObjectNode().put("code", 0).set("data", data));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    auth.start();
    app = new SupportPilot(configuration("development", "opsagent", null));
    app.start();
  }

  SupportPilot.Configuration configuration(String mode, String authMode, URI publicOrigin) {
    return new SupportPilot.Configuration(
        URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
        "support-pilot",
        0,
        () -> APP,
        null,
        null,
        null,
        null,
        mode,
        authMode,
        URI.create("http://127.0.0.1:" + auth.getAddress().getPort()),
        publicOrigin);
  }

  @AfterEach
  @Override
  void stop() {
    super.stop();
    auth.stop(0);
  }

  JsonNode loginUser(String user, String role) throws Exception {
    var response =
        post(
            "/api/session",
            Map.of(
                "username",
                user,
                "password",
                "synthetic-password",
                "captchaId",
                "a".repeat(32),
                "captchaCode",
                "1234",
                "role",
                role));
    assertEquals(200, response.statusCode(), response.body());
    cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    JsonNode body = JSON.readTree(response.body());
    csrf = body.path("csrfToken").asText();
    assertFalse(response.body().contains("synthetic-jwt"));
    assertFalse(response.body().contains("synthetic-refresh"));
    assertFalse(response.body().contains("synthetic-password"));
    assertFalse(response.body().contains(APP));
    return body;
  }

  @Test
  void captchaLoginAndPerUserSessionsUseExistingIdentityWithoutBrowserTokens() throws Exception {
    JsonNode config = JSON.readTree(get("/api/auth/config").body());
    assertEquals("opsagent", config.path("mode").asText());
    assertTrue(config.path("captchaRequired").asBoolean());
    var captcha = get("/api/auth/captcha");
    assertEquals(200, captcha.statusCode());
    assertEquals("a".repeat(32), JSON.readTree(captcha.body()).path("captchaId").asText());
    assertEquals("alice", loginUser("alice", "operator").path("subject").asText());
    String aliceCookie = cookie, aliceCsrf = csrf;
    assertEquals(200, get("/api/runs").statusCode());
    assertEquals("synthetic-jwt-alice", calls.get(calls.size() - 1).user());
    cookie = null;
    csrf = null;
    assertEquals("bob", loginUser("bob", "operator").path("subject").asText());
    String bobCookie = cookie;
    assertNotEquals(aliceCookie, bobCookie);
    assertEquals(200, get("/api/runs").statusCode());
    assertEquals("synthetic-jwt-bob", calls.get(calls.size() - 1).user());
    cookie = aliceCookie;
    csrf = aliceCsrf;
    assertEquals("alice", JSON.readTree(get("/api/session").body()).path("subject").asText());
    assertEquals(202, post("/api/runs", create(RELEASE)).statusCode());
    assertEquals("synthetic-jwt-alice", calls.get(calls.size() - 1).user());
    assertTrue(identityAuthorizations.stream().allMatch(value -> value.equals("Bearer " + APP)));
    var names = new HashSet<String>();
    credentials.get(0).fieldNames().forEachRemaining(names::add);
    assertEquals(Set.of("username", "password", "captchaId", "captchaCode"), names);
  }

  @Test
  void reviewerRoleMustBeGrantedAndNeverBorrowsAnOperatorIdentity() throws Exception {
    assertEquals(
        403,
        post(
                "/api/session",
                Map.of(
                    "username",
                    "alice",
                    "password",
                    "synthetic-password",
                    "captchaId",
                    "a".repeat(32),
                    "captchaCode",
                    "1234",
                    "role",
                    "reviewer"))
            .statusCode());
    assertEquals("reviewer", loginUser("reviewer", "reviewer").path("role").asText());
    assertEquals(403, post("/api/runs", create(RELEASE)).statusCode());
    assertEquals(200, get("/api/runs/" + RUN + "/approval").statusCode());
    assertEquals("synthetic-jwt-reviewer", calls.get(calls.size() - 1).user());
  }

  @Test
  void grantRevocationAndIdentityOutageFailClosedBeforePlatformDispatch() throws Exception {
    loginUser("alice", "operator");
    revoked.set(true);
    assertEquals(403, get("/api/runs").statusCode());
    assertEquals(0, calls.size());
    assertFalse(JSON.readTree(get("/api/session").body()).path("signedIn").asBoolean());
    revoked.set(false);
    cookie = null;
    csrf = null;
    loginUser("alice", "operator");
    unavailable.set(true);
    assertEquals(503, post("/api/runs", create(RELEASE)).statusCode());
    assertEquals(0, calls.size());
    // Logout is local and remains usable when the identity provider is unavailable.
    assertEquals(200, post("/api/logout", Map.of()).statusCode());
    assertEquals(401, get("/api/runs").statusCode());
  }

  @Test
  void accessCodesAndTokenInjectionAreRejectedByFormalLogin() throws Exception {
    assertEquals(400, post("/api/session", Map.of("accessCode", OPERATOR_CODE)).statusCode());
    assertEquals(
        400,
        post(
                "/api/session",
                Map.of(
                    "username",
                    "alice",
                    "password",
                    "synthetic-password",
                    "captchaId",
                    "a".repeat(32),
                    "captchaCode",
                    "1234",
                    "role",
                    "operator",
                    "userToken",
                    "forged"))
            .statusCode());
    assertEquals(0, credentials.size());
    assertEquals(
        401,
        post(
                "/api/session",
                Map.of(
                    "username",
                    "alice",
                    "password",
                    "wrong-password",
                    "captchaId",
                    "a".repeat(32),
                    "captchaCode",
                    "1234",
                    "role",
                    "operator"))
            .statusCode());
  }

  @Test
  void productionRequiresHttpsAndCannotEnableLoopbackAccessCodeMode() {
    assertThrows(
        IllegalArgumentException.class, () -> configuration("production", "opsagent", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration("production", "opsagent", URI.create("http://127.0.0.1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportPilot.Configuration(
                URI.create("http://127.0.0.1"),
                "support-pilot",
                0,
                () -> APP,
                () -> USER,
                OPERATOR_CODE,
                null,
                null,
                "production",
                "local-test",
                null,
                URI.create("https://support.example.test")));
    assertDoesNotThrow(
        () -> configuration("production", "opsagent", URI.create("https://support.example.test")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportPilot.Configuration(
                URI.create("http://127.0.0.1"),
                "support-pilot",
                0,
                () -> APP,
                null,
                null,
                null,
                null,
                "development",
                "opsagent",
                URI.create("http://public.example.test"),
                null));
  }
}
