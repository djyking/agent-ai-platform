package io.github.djyking.harness.integrations.opsagent;

import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.Harness;
import io.github.djyking.harness.core.InMemoryRunStore;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import io.github.djyking.harness.core.ToolRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Synthetic HTTP wire contract, deliberately not a real JWT/auth-server integration test. */
class OpsAgentRagToolTest {
  private final Actor actor =
      new Actor(
          "user-7",
          "ops-pilot",
          Set.of(
              "run:create", "run:read", "tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION));
  private HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicReference<String> seenBody = new AtomicReference<>();
  private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
  private final AtomicReference<String> seenMethod = new AtomicReference<>();
  private final AtomicReference<String> seenPath = new AtomicReference<>();

  @AfterEach
  void close() {
    if (server != null) server.stop(0);
  }

  @Test
  void realWireThroughHarnessPreservesSourcesAndNeverPersistsCallbackCredential() throws Exception {
    URI origin = server(200, success(), null, 0);
    var identities = new AtomicInteger();
    var tool =
        new OpsAgentRagTool(
            OpsAgentRagConfig.defaults(origin),
            (endpoint, audience, context) -> {
              assertEquals(origin.resolve("/internal/rag/search"), endpoint);
              assertEquals("rag", audience);
              assertEquals(actor, context.actor());
              assertTrue(context.invocationId().startsWith(context.runId()));
              identities.incrementAndGet();
              return "Bearer synthetic-only-token";
            });
    var store = new InMemoryRunStore();
    try (var harness = harness(store, tool)) {
      var definition = OpsAgentRetrievalPilot.definition("如何查询 Redis 内存？", 3);
      RunState started =
          harness.start(
              definition,
              actor,
              List.of(OpsAgentRagTool.KEY),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "new-request-1");
      RunState done = drive(harness, started.id);
      assertEquals(RunStatus.COMPLETED, done.status, done.stopReason);
      assertEquals(1, done.toolCalls);
      assertEquals(0, done.modelCalls);
      assertEquals(0, done.chargedTokens);
      assertEquals("[S1] Use INFO memory.", done.output.path("evidence").asText());
      assertEquals("S1", done.output.at("/citations/0/sourceId").asText());
      assertEquals(7, done.output.at("/citations/0/documentId").asLong());
      assertEquals("Redis guide", done.output.at("/citations/0/documentName").asText());
      assertFalse(Json.write(done).contains("synthetic-only-token"));
      assertFalse(Json.write(done).contains("not-projected"));
      assertEquals("POST", seenMethod.get());
      assertEquals("/internal/rag/search", seenPath.get());
      assertEquals(
          Json.read("{\"query\":\"如何查询 Redis 内存？\",\"topK\":3}"), Json.read(seenBody.get()));
      assertEquals("Bearer synthetic-only-token", seenAuthorization.get());
      assertEquals(
          started.id,
          harness.start(
                  definition,
                  actor,
                  List.of(OpsAgentRagTool.KEY),
                  Budget.defaults(),
                  Duration.ofMinutes(1),
                  "new-request-1")
              .id);
      harness.tick(done.id);
      assertEquals(1, requests.get());
      assertEquals(1, identities.get());
    }
  }

  @Test
  void identityIsResolvedPerCallAndIsNotTakenFromToolArguments() throws Exception {
    URI origin = server(200, success(), null, 0);
    var count = new AtomicInteger();
    var tool =
        new OpsAgentRagTool(
            OpsAgentRagConfig.defaults(origin),
            (endpoint, audience, context) -> "Bearer fresh-" + count.incrementAndGet());
    invoke(tool);
    assertEquals("Bearer fresh-1", seenAuthorization.get());
    invoke(tool);
    assertEquals("Bearer fresh-2", seenAuthorization.get());
    var forged = Json.read("{\"query\":\"redis\",\"topK\":1,\"roles\":[\"ADMIN\"]}");
    assertEquals(
        FailureKind.INVALID,
        assertThrows(
                InvocationException.class, () -> tool.invoke(tool.descriptor(), forged, context()))
            .kind());
    assertEquals(2, requests.get());
    assertEquals(2, count.get());
  }

  @Test
  void harnessRejectsMissingToolPermissionBeforeHttpOrIdentityResolution() throws Exception {
    URI origin = server(200, success(), null, 0);
    var tool =
        new OpsAgentRagTool(
            OpsAgentRagConfig.defaults(origin),
            (endpoint, audience, context) -> {
              throw new AssertionError("Must not resolve identity");
            });
    try (var harness = harness(new InMemoryRunStore(), tool)) {
      var denied = new Actor(actor.subject(), actor.project(), Set.of("run:create"));
      assertThrows(
          InvocationException.class,
          () ->
              harness.start(
                  OpsAgentRetrievalPilot.definition("redis", 1),
                  denied,
                  List.of(OpsAgentRagTool.KEY),
                  Budget.defaults(),
                  Duration.ofMinutes(1),
                  "denied"));
      assertEquals(0, requests.get());
    }
  }

  @Test
  void upstreamRevocationFailsClosedWithoutPublicSearchFallback() throws Exception {
    URI origin =
        server(200, "{\"code\":40300,\"message\":\"secret-in-error\",\"data\":null}", null, 0);
    var tool = tool(origin);
    try (var harness = harness(new InMemoryRunStore(), tool)) {
      var started =
          harness.start(
              OpsAgentRetrievalPilot.definition("redis", 1),
              actor,
              List.of(OpsAgentRagTool.KEY),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "revoked");
      RunState denied = drive(harness, started.id);
      assertEquals(RunStatus.NEEDS_ATTENTION, denied.status);
      assertEquals("OPSAGENT_RETRIEVAL_DENIED", denied.stopReason);
      assertEquals("OPSAGENT_RETRIEVAL_DENIED", denied.pending.failureCode);
      assertEquals(InvocationPhase.PREPARED, denied.pending.phase);
      assertEquals(1, denied.pending.attempts);
      assertEquals(1, denied.toolCalls);
      assertTrue(denied.results.isEmpty());
      assertEquals(RunStatus.NEEDS_ATTENTION, harness.tick(denied.id).status);
      assertEquals(1, requests.get());
      assertEquals("/internal/rag/search", seenPath.get());
      assertFalse(Json.write(denied).contains("secret-in-error"));
      assertTrue(
          harness.events(denied.id, actor, 0, 100).stream()
              .noneMatch(event -> "RETRY_SCHEDULED".equals(event.event().type())));
    }
  }

  @Test
  void absentOrInvalidHostCredentialNeverDispatches() throws Exception {
    URI origin = server(200, success(), null, 0);
    for (String credential : List.of("", "Basic ignored", "Bearer value\r\nOther: injected")) {
      var tool =
          new OpsAgentRagTool(
              OpsAgentRagConfig.defaults(origin), (endpoint, audience, context) -> credential);
      assertEquals(
          FailureKind.DENIED, assertThrows(InvocationException.class, () -> invoke(tool)).kind());
    }
    var failure =
        new OpsAgentRagTool(
            OpsAgentRagConfig.defaults(origin),
            (endpoint, audience, context) -> {
              throw new IllegalStateException("secret-from-broker");
            });
    var exception = assertThrows(InvocationException.class, () -> invoke(failure));
    assertEquals("OPSAGENT_IDENTITY_UNAVAILABLE", exception.getMessage());
    assertNull(exception.getCause());
    assertEquals(0, requests.get());
  }

  @Test
  void rejectsRedirectAndNeverFollowsCredentialToAnotherPath() throws Exception {
    URI origin = server(302, "redirect", "/credential-trap", 0);
    var exception = assertThrows(InvocationException.class, () -> invoke(tool(origin)));
    assertEquals("OPSAGENT_RETRIEVAL_HTTP_302", exception.getMessage());
    assertEquals(1, requests.get());
    assertEquals("/internal/rag/search", seenPath.get());
  }

  @Test
  void boundedResponseAndDeadlineCancelTransport() throws Exception {
    URI origin = server(200, success() + " ".repeat(1024), null, 0);
    var small =
        new OpsAgentRagTool(
            new OpsAgentRagConfig(
                origin, Duration.ofSeconds(1), Duration.ofSeconds(2), 256, 1000, 10),
            (endpoint, audience, context) -> "Bearer fixture");
    assertEquals(
        FailureKind.UNKNOWN, assertThrows(InvocationException.class, () -> invoke(small)).kind());
    var expired =
        new ExecutionContext(
            "run",
            "search",
            "run:search",
            "run:search:1",
            actor,
            Instant.now().minusMillis(1),
            "trace");
    var expiredFailure =
        assertThrows(
            InvocationException.class,
            () -> small.invoke(small.descriptor(), arguments(), expired));
    assertEquals(FailureKind.PERMANENT, expiredFailure.kind());
    assertEquals("OPSAGENT_RETRIEVAL_DEADLINE_EXCEEDED", expiredFailure.getMessage());
    assertEquals(1, requests.get());
  }

  @Test
  void responseWaitUsesInvocationDeadline() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseResponse = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/rag/search",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() > 1) {
              requestEntered.countDown();
              if (!releaseResponse.await(30, TimeUnit.SECONDS)) return;
            }
            byte[] bytes = success().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    var config =
        OpsAgentRagConfig.defaults(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    var tool = new OpsAgentRagTool(config, (endpoint, audience, context) -> "Bearer fixture-token");
    var descriptor = tool.descriptor();
    var input = arguments();
    // Warm descriptor/schema serialization and the actual HTTP connection before starting the
    // deadline under test. The second response remains gated even after that deadline expires.
    tool.invoke(descriptor, input, context());
    assertEquals(1, requests.get());
    assertEquals(Duration.ofSeconds(15), config.requestTimeout());
    var worker = Executors.newSingleThreadExecutor();
    try {
      var invocation =
          worker.submit(
              () -> {
                var shortDeadline =
                    new ExecutionContext(
                        "run",
                        "search",
                        "run:search",
                        "run:search:1",
                        actor,
                        Instant.now().plusSeconds(3),
                        "trace");
                return assertThrows(
                    InvocationException.class, () -> tool.invoke(descriptor, input, shortDeadline));
              });
      assertTrue(requestEntered.await(5, TimeUnit.SECONDS), "The timed request must dispatch");
      // 5 seconds to observe dispatch plus 8 seconds to complete is below the configured 15-second
      // transport timeout. No response is released, so only the invocation deadline can stop it.
      var exception = invocation.get(8, TimeUnit.SECONDS);
      assertEquals(1, releaseResponse.getCount());
      assertEquals(2, requests.get());
      assertEquals(FailureKind.UNKNOWN, exception.kind());
      assertEquals("OPSAGENT_RETRIEVAL_OUTCOME_UNKNOWN", exception.getMessage());
    } finally {
      releaseResponse.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void rejectsMalformedEnvelopeAndDoesNotExposeRawResponse() throws Exception {
    URI origin = server(200, "{\"message\":\"secret-response\",\"data\":{}}", null, 0);
    var exception = assertThrows(InvocationException.class, () -> invoke(tool(origin)));
    assertEquals("OPSAGENT_RETRIEVAL_RESPONSE_INVALID", exception.getMessage());
    assertNull(exception.getCause());
  }

  @Test
  void rejectsBrokenCitationsInsteadOfSilentlyDroppingEvidence() throws Exception {
    URI origin = server(200, success().replace("\"documentId\":7", "\"documentId\":0"), null, 0);
    assertEquals(
        "OPSAGENT_RETRIEVAL_RESPONSE_INVALID",
        assertThrows(InvocationException.class, () -> invoke(tool(origin))).getMessage());
  }

  @Test
  void boundsInputsAndRejectsInsecureOrCredentialBearingOrigins() {
    for (String uri :
        List.of(
            "http://example.com",
            "https://user:secret@example.com",
            "https://example.com?token=secret",
            "https://example.com/alternate"))
      assertThrows(
          IllegalArgumentException.class, () -> OpsAgentRagConfig.defaults(URI.create(uri)));
    assertThrows(InvocationException.class, () -> OpsAgentRetrievalPilot.definition(" ", 1));
    assertThrows(
        InvocationException.class, () -> OpsAgentRetrievalPilot.definition("x".repeat(2001), 1));
    assertThrows(InvocationException.class, () -> OpsAgentRetrievalPilot.definition("redis", 21));
  }

  private ToolResult invoke(OpsAgentRagTool tool) {
    return tool.invoke(tool.descriptor(), arguments(), context());
  }

  private com.fasterxml.jackson.databind.JsonNode arguments() {
    return Json.object().put("query", "redis").put("topK", 1);
  }

  private ExecutionContext context() {
    return new ExecutionContext(
        "run",
        "search",
        "run:search",
        "run:search:1",
        actor,
        Instant.now().plusSeconds(5),
        "trace");
  }

  private OpsAgentRagTool tool(URI origin) {
    return new OpsAgentRagTool(
        OpsAgentRagConfig.defaults(origin),
        (endpoint, audience, context) -> "Bearer fixture-token");
  }

  private Harness harness(InMemoryRunStore store, OpsAgentRagTool tool) {
    var registry = new ToolRegistry();
    registry.register(tool.descriptor(), tool);
    var runtime =
        new Harness(
            store,
            (request, context) -> {
              throw new AssertionError("Retrieval pilot must not invoke a model");
            },
            registry);
    runtime.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    return runtime;
  }

  private RunState drive(Harness harness, String runId) {
    RunState state = harness.get(runId, actor);
    for (int i = 0;
        i < 20 && (state.status == RunStatus.QUEUED || state.status == RunStatus.RUNNING);
        i++) state = harness.tick(runId);
    return state;
  }

  private URI server(int status, String body, String location, long delayMillis) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          seenBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          seenMethod.set(exchange.getRequestMethod());
          seenPath.set(exchange.getRequestURI().getPath());
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (location != null) exchange.getResponseHeaders().set("Location", location);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private String success() {
    return """
           {"code":0,"message":"success","traceId":"trace-fixture","data":{
             "evidence":"[S1] Use INFO memory.","citations":[{
             "chunkId":10,"documentId":7,"chunkIndex":0,"documentName":"Redis guide",
             "page":2,"version":1,"sourceId":"S1","headingPath":"Memory",
             "pageStart":2,"pageEnd":2,"sourceType":"KNOWLEDGE_DOCUMENT","sourceUrl":null,
             "sourceUpdatedAt":"2026-09-01T00:00:00Z","sourceRetrievedAt":"2026-09-15T00:00:00Z",
             "score":0.8,"unknownExtra":"not-projected"}]}}
           """;
  }
}
