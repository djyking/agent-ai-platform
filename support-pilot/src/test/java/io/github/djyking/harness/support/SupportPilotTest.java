package io.github.djyking.harness.support;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class SupportPilotTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String APP = "synthetic-application-secret-only-on-server";
  static final String USER = "synthetic-operator-user-token-only-on-server";
  static final String REVIEWER = "synthetic-reviewer-user-token-only-on-server";
  static final String OPERATOR_CODE = "synthetic-operator-access-code-at-least-24";
  static final String REVIEWER_CODE = "synthetic-reviewer-access-code-at-least-24";
  static final String RUN = "653a8d84-54c4-467a-8539-e4a41bba54fd";
  static final String RELEASE = "d1d168fd-ab3a-496b-ae42-cb6fd8e17870";
  record Captured(String method, String path, String app, String user, String key, String etag, JsonNode body) {}
  final List<Captured> calls = Collections.synchronizedList(new ArrayList<>());
  final AtomicBoolean disconnectCreate = new AtomicBoolean();
  HttpServer upstream;
  SupportPilot app;
  HttpClient http = HttpClient.newHttpClient();
  String cookie;
  String csrf;

  @BeforeEach void start() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      byte[] raw = exchange.getRequestBody().readAllBytes();
      String path = exchange.getRequestURI().getPath();
      calls.add(new Captured(exchange.getRequestMethod(), path,
          exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Harness-User-Token"),
          exchange.getRequestHeaders().getFirst("Idempotency-Key"), exchange.getRequestHeaders().getFirst("If-Match"),
          raw.length == 0 ? null : JSON.readTree(raw)));
      if (disconnectCreate.get() && exchange.getRequestMethod().equals("POST") && path.endsWith("/runs")) { exchange.close(); return; }
      JsonNode body;
      int status = exchange.getRequestMethod().equals("POST") ? 202 : 200;
      if (path.endsWith("/catalog/releases")) {
        var item = JSON.createObjectNode().put("agentId", "support-faq").put("version", 1).put("name", "<img src=x onerror=alert(1)>").put("isDefault", true);
        item.set("releaseRef", JSON.createObjectNode().put("agentId", "support-faq").put("releaseId", RELEASE).put("digest", "sha256:" + "a".repeat(64)));
        item.set("inputSchema", JSON.createObjectNode().put("type", "object"));
        var response = JSON.createObjectNode(); response.putArray("items").add(item); body = response;
      } else if (path.endsWith("/approval")) {
        body = JSON.createObjectNode().put("id", "approval-fixture").put("runId", RUN).put("digest", "sha256:" + "b".repeat(64)).put("reviewComplete", true).put("inputRequired", true).put("summary", "核对合成客服答复");
      } else if (exchange.getRequestMethod().equals("POST")) {
        var response = JSON.createObjectNode(); response.set("run", JSON.createObjectNode().put("id", RUN).put("status", "QUEUED")); body = response;
      } else { var response = JSON.createObjectNode(); response.putArray("items"); body = response; }
      byte[] bytes = JSON.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
      exchange.getResponseHeaders().set("X-Request-Id", "req_support_fixture");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    upstream.start();
    app = new SupportPilot(new SupportPilot.Configuration(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), "support-pilot", 0,
        () -> APP, () -> USER, OPERATOR_CODE, () -> REVIEWER, REVIEWER_CODE));
    app.start();
  }
  @AfterEach void stop() { app.close(); upstream.stop(0); }

  HttpResponse<String> request(String method, String path, String body, String suppliedOrigin, String suppliedCsrf) throws Exception {
    var builder = HttpRequest.newBuilder(app.origin().resolve(path));
    if (cookie != null) builder.header("Cookie", cookie);
    if (suppliedOrigin != null) builder.header("Origin", suppliedOrigin);
    if (suppliedCsrf != null) builder.header("X-CSRF-Token", suppliedCsrf);
    if (method.equals("POST")) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
  HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, null, null); }
  HttpResponse<String> post(String path, Object body) throws Exception { return request("POST", path, JSON.writeValueAsString(body), app.origin().toString(), csrf); }
  JsonNode login(String code) throws Exception {
    var response = post("/api/session", Map.of("accessCode", code));
    assertEquals(200, response.statusCode());
    String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
    assertTrue(setCookie.contains("HttpOnly")); assertTrue(setCookie.contains("SameSite=Strict"));
    cookie = setCookie.split(";", 2)[0];
    JsonNode session = JSON.readTree(response.body()); csrf = session.path("csrfToken").asText();
    for (String secret : List.of(APP, USER, REVIEWER, OPERATOR_CODE, REVIEWER_CODE)) assertFalse(response.body().contains(secret));
    return session;
  }
  Map<String, Object> create(String release) {
    return Map.of("agentId", "support-faq", "releaseId", release, "inputs", Map.of("question", "订单什么时候发货？"), "requestKey", "support-create-key-0001");
  }

  @Test void unauthenticatedApiCannotUseServerCredentialsAndStaticPageHasRestrictivePolicy() throws Exception {
    assertEquals(401, get("/api/runs").statusCode());
    assertFalse(JSON.readTree(get("/api/session").body()).path("signedIn").asBoolean());
    var page = get("/"); assertEquals(200, page.statusCode());
    assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self'"));
    assertTrue(page.body().contains("客服助手"));
    assertFalse(page.body().contains(APP));
    assertEquals(0, calls.size());
  }

  @Test void independentOperatorIdentityDiscoversPublishedAgentsAndCreatesExactRelease() throws Exception {
    assertEquals("operator", login(OPERATOR_CODE).path("role").asText());
    var agents = get("/api/agents"); assertEquals(200, agents.statusCode());
    assertTrue(agents.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
    assertEquals("nosniff", agents.headers().firstValue("X-Content-Type-Options").orElseThrow());
    assertEquals(202, post("/api/runs", create(RELEASE)).statusCode());
    Captured command = calls.get(calls.size() - 1);
    assertEquals("/v1/projects/support-pilot/runs", command.path());
    assertEquals("Bearer " + APP, command.app()); assertEquals(USER, command.user());
    assertEquals("support-create-key-0001", command.key());
    assertEquals(RELEASE, command.body().path("releaseRef").path("releaseId").asText());
    assertEquals("sha256:" + "a".repeat(64), command.body().path("releaseRef").path("digest").asText());
    assertEquals("订单什么时候发货？", command.body().path("inputs").path("question").asText());
    assertFalse(command.body().has("applicationSecret"));
    assertEquals(200, get("/api/runs").statusCode());
    assertTrue(calls.get(calls.size() - 1).path().startsWith("/v1/projects/support-pilot/"));
  }

  @Test void forgedOrUnavailableReleaseCannotBeDispatched() throws Exception {
    login(OPERATOR_CODE);
    assertEquals(404, post("/api/runs", create("unpublished-release")).statusCode());
    assertEquals(0, calls.stream().filter(c -> c.method().equals("POST")).count());
  }

  @Test void mutationsRequireSameOriginAndSessionCsrfBeforeAnyUpstreamCall() throws Exception {
    login(OPERATOR_CODE);
    String body = JSON.writeValueAsString(create(RELEASE));
    assertEquals(403, request("POST", "/api/runs", body, "http://evil.example", csrf).statusCode());
    assertEquals(403, request("POST", "/api/runs", body, app.origin().toString(), null).statusCode());
    assertEquals(403, request("POST", "/api/runs", body, null, csrf).statusCode());
    assertEquals(0, calls.size());
  }

  @Test void reviewerUsesOwnTokenAndExactApprovalTagWithoutOperatorPrivileges() throws Exception {
    assertEquals("reviewer", login(REVIEWER_CODE).path("role").asText());
    assertEquals(403, get("/api/agents").statusCode());
    assertEquals(403, post("/api/runs", create(RELEASE)).statusCode());
    var response = get("/api/runs/" + RUN + "/approval"); assertEquals(200, response.statusCode());
    assertEquals(REVIEWER, calls.get(0).user());
    var approval = JSON.readTree(response.body());
    assertEquals(202, post("/api/runs/" + RUN + "/decision", Map.of("approvalId", "approval-fixture", "digest", approval.path("body").path("digest").asText(),
        "decision", "APPROVE", "input", "已核对合成答复", "reason", "人工核验完成", "etag", approval.path("etag").asText(), "requestKey", "approval-key-0001")).statusCode());
    Captured decision = calls.get(calls.size() - 1);
    assertEquals(REVIEWER, decision.user()); assertEquals("\"fixture-etag\"", decision.etag());
    assertEquals("已核对合成答复", decision.body().path("input").asText());
  }

  @Test void logoutInvalidatesServerSessionAndOldCookieCannotBeReused() throws Exception {
    login(OPERATOR_CODE);
    assertEquals(200, post("/api/logout", Map.of()).statusCode());
    assertEquals(401, get("/api/agents").statusCode());
    assertEquals(0, calls.size());
  }

  @Test void ambiguousCommandTransportFailureIsExposedAndNotRetried() throws Exception {
    login(OPERATOR_CODE); disconnectCreate.set(true);
    var result = post("/api/runs", create(RELEASE));
    assertEquals(502, result.statusCode());
    assertTrue(JSON.readTree(result.body()).path("outcomeUnknown").asBoolean());
    assertEquals(1, calls.stream().filter(c -> c.method().equals("POST")).count());
    assertFalse(result.body().contains(APP)); assertFalse(result.body().contains(USER));
  }

  @Test void duplicateJsonAndWrongHostAreRejected() throws Exception {
    String duplicate = "{\"accessCode\":\"" + OPERATOR_CODE + "\",\"accessCode\":\"other\"}";
    assertEquals(400, request("POST", "/api/session", duplicate, app.origin().toString(), null).statusCode());
    URI wrongHost = URI.create(app.origin().toString().replace("127.0.0.1", "localhost"));
    assertEquals(400, http.send(HttpRequest.newBuilder(wrongHost).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(0, calls.size());
  }
}
