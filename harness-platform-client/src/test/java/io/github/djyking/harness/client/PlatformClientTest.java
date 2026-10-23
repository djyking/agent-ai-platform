package io.github.djyking.harness.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class PlatformClientTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String RUN = "24b522e7-345b-4cd0-806c-2fba5538291c";
  HttpServer server;
  final List<String> routes = Collections.synchronizedList(new ArrayList<>());
  final List<Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());
  final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger status = new AtomicInteger(200);
  final AtomicReference<String> response = new AtomicReference<>("{\"items\":[]}");
  final AtomicBoolean disconnect = new AtomicBoolean();
  URI origin;
  PlatformClient client;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      routes.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      headers.add(Map.of("application", exchange.getRequestHeaders().getFirst("Authorization"),
          "user", exchange.getRequestHeaders().getFirst("X-Harness-User-Token"),
          "key", Objects.toString(exchange.getRequestHeaders().getFirst("Idempotency-Key"), ""),
          "etag", Objects.toString(exchange.getRequestHeaders().getFirst("If-Match"), "")));
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if (disconnect.get()) { exchange.close(); return; }
      byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("ETag", "\"opaque-view-1\"");
      exchange.getResponseHeaders().set("X-Request-Id", "req_fixture");
      exchange.getResponseHeaders().set("Location", origin + "/redirect-must-never-follow");
      exchange.getResponseHeaders().set("Retry-After", "2");
      exchange.sendResponseHeaders(status.get(), bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    client = new PlatformClient(ClientConfiguration.defaults(origin, "support-pilot", () -> "synthetic-app-secret", () -> "synthetic-user-token"));
  }
  @AfterEach void stop() { server.stop(0); }

  @Test void allElevenRunOperationsAndDiscoveryUsePublishedRoutesAndExplicitConcurrencyHeaders() {
    var body = JSON.createObjectNode().put("marker", "synthetic");
    client.createRun(body, "create-key-0001");
    ApiResponse get = client.getRun(RUN);
    assertEquals("\"opaque-view-1\"", get.etag());
    assertEquals("req_fixture", get.requestId());
    client.listRuns("a+b/==", 20, "WAITING_INPUT");
    client.events(RUN, "cursor+one", 50);
    client.pause(RUN, get.etag(), "pause-key-0001");
    client.resume(RUN, get.etag(), "resume-key-0001");
    client.cancel(RUN, get.etag(), "cancel-key-0001");
    client.approval(RUN);
    client.decideApproval(RUN, "approval-1", body, get.etag(), "decision-key-0001");
    client.unknownInvocation(RUN);
    client.reconcileTool(RUN, body, get.etag(), "reconcile-key-0001");
    client.publishedReleases();
    client.defaultRelease("support-faq");
    String base = "/v1/projects/support-pilot";
    assertEquals(List.of("POST " + base + "/runs", "GET " + base + "/runs/" + RUN,
        "GET " + base + "/runs?limit=20&cursor=a%2Bb%2F%3D%3D&status=WAITING_INPUT",
        "GET " + base + "/runs/" + RUN + "/events?limit=50&cursor=cursor%2Bone",
        "POST " + base + "/runs/" + RUN + "/pause", "POST " + base + "/runs/" + RUN + "/resume",
        "POST " + base + "/runs/" + RUN + "/cancel", "GET " + base + "/runs/" + RUN + "/approval",
        "POST " + base + "/runs/" + RUN + "/approvals/approval-1/decision",
        "GET " + base + "/runs/" + RUN + "/unknown-invocation",
        "POST " + base + "/runs/" + RUN + "/tool-reconciliations",
        "GET " + base + "/catalog/releases", "GET " + base + "/catalog/agents/support-faq/default"), routes);
    assertEquals("create-key-0001", headers.get(0).get("key"));
    assertEquals("\"opaque-view-1\"", headers.get(4).get("etag"));
    assertEquals("{}", bodies.get(4));
    assertTrue(headers.stream().allMatch(h -> h.get("application").equals("Bearer synthetic-app-secret") && h.get("user").equals("synthetic-user-token")));
  }

  @Test void unauthorizedStaleAndServiceFailuresExposeCodesWithoutProviderPayloadAndNeverRetry() {
    for (int code : List.of(401, 403, 409, 412, 429, 503)) {
      status.set(code);
      response.set("{\"error\":{\"code\":\"FIXTURE_ERROR\",\"message\":\"do-not-expose-provider-content\"}}");
      int before = routes.size();
      PlatformException error = assertThrows(PlatformException.class, () -> client.pause(RUN, "\"opaque\"", "command-key-0001"));
      assertEquals(code, error.status());
      assertEquals("FIXTURE_ERROR", error.code());
      assertEquals(code >= 500, error.outcomeUnknown());
      assertEquals(before + 1, routes.size());
      assertFalse(error.toString().contains("do-not-expose"));
    }
  }

  @Test void redirectDoesNotForwardCredentialsOrReplayCommand() {
    status.set(307);
    PlatformException error = assertThrows(PlatformException.class, () -> client.createRun(JSON.createObjectNode(), "create-key-redirect"));
    assertEquals(307, error.status());
    assertEquals(1, routes.size());
  }

  @Test void realSocketCloseAfterCommandIsAmbiguousAndNeverResent() {
    disconnect.set(true);
    PlatformException error = assertThrows(PlatformException.class, () -> client.createRun(JSON.createObjectNode(), "create-key-socket"));
    assertTrue(error.outcomeUnknown());
    assertEquals(0, error.status());
    assertEquals(1, routes.size());
  }

  @Test void oversizedResponseIsBounded() {
    client = new PlatformClient(new ClientConfiguration(origin, "support-pilot", () -> "app", () -> "user", Duration.ofSeconds(5), 1024));
    response.set("{\"content\":\"" + "x".repeat(2048) + "\"}");
    assertEquals("TRANSPORT_FAILED", assertThrows(PlatformException.class, () -> client.getRun(RUN)).code());
    assertEquals(1, routes.size());
  }

  @Test void invalidMutationKeysAndTagsAndPathInjectionFailBeforeSending() {
    assertThrows(IllegalArgumentException.class, () -> client.createRun(JSON.createObjectNode(), null));
    assertThrows(IllegalArgumentException.class, () -> client.pause(RUN, null, "command-key-0001"));
    assertThrows(IllegalArgumentException.class, () -> client.pause(RUN, "W/\"weak\"", "command-key-0001"));
    assertThrows(IllegalArgumentException.class, () -> client.defaultRelease("../secret"));
    assertThrows(IllegalArgumentException.class, () -> client.getRun("../runs"));
    assertThrows(IllegalArgumentException.class, () -> client.listRuns(null, 101, null));
    assertEquals(0, routes.size());
  }

  @Test void secretProvidersResolveAtRequestTimeAndConfigurationNeverRendersCredentials() {
    AtomicReference<String> token = new AtomicReference<>("first-token");
    ClientConfiguration config = ClientConfiguration.defaults(origin, "support-pilot", () -> "app-private", token::get);
    client = new PlatformClient(config);
    client.getRun(RUN); token.set("rotated-token"); client.getRun(RUN);
    assertEquals("first-token", headers.get(0).get("user"));
    assertEquals("rotated-token", headers.get(1).get("user"));
    assertFalse(config.toString().contains("app-private"));
    assertFalse(config.toString().contains("token"));
  }

  @Test void originCannotContainCredentialsPathsOrRemotePlaintextTransport() {
    for (String invalid : List.of("http://example.com", "https://user:pass@example.com", "https://example.com/other", "https://example.com?token=secret", "file:///tmp/test"))
      assertThrows(IllegalArgumentException.class, () -> ClientConfiguration.defaults(URI.create(invalid), "support", () -> "app", () -> "user"));
  }
}
