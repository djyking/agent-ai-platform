package io.github.djyking.harness.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.AgentProgram;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Harness;
import io.github.djyking.harness.core.InMemoryRunStore;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.ToolRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekChatModelTest {
  private static final String TOOL_RESPONSE =
      """
{"id":"chat-1","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
  "tool_calls":[{"id":"provider-call-42","type":"function","function":{"name":"lookup","arguments":"{\\\"query\\\":\\\"guide\\\"}"}}]}}],
 "usage":{"prompt_tokens":24,"completion_tokens":12,"total_tokens":36}}
""";
  private static final String FINAL_RESPONSE =
      """
{"id":"chat-2","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Found it"}}]}
""";

  @Test
  void emitsNativeToolsAndPreservesProviderCallIdsAcrossRoundTrip() throws Exception {
    try (var server = new ModelServer()) {
      var token = new AtomicReference<>("Bearer local-fixture-one");
      var model =
          new DeepSeekChatModel(
              server.config(1_000_000, endpoint -> Map.of("Authorization", token.get())));
      server.response.set(TOOL_RESPONSE);
      var firstRequest =
          request(List.of(Message.text("system", "Use tools"), Message.text("user", "Find guide")));
      var first = model.invoke(firstRequest, context());
      assertEquals(FinishReason.TOOL_CALLS, first.finishReason());
      assertEquals("provider-call-42", first.message().toolCalls().get(0).id());
      assertEquals("guide", first.message().toolCalls().get(0).arguments().path("query").asText());
      assertEquals(36, first.usage().total());
      assertTrue(first.usage().known());
      JsonNode wire = server.requests.get(0);
      assertEquals("function", wire.path("tools").get(0).path("type").asText());
      assertEquals("lookup", wire.path("tools").get(0).path("function").path("name").asText());
      assertEquals("disabled", wire.path("thinking").path("type").asText());
      assertEquals(256, wire.path("max_tokens").asInt());
      assertFalse(wire.path("stream").asBoolean());
      assertFalse(wire.has("invocationId"));

      server.response.set(FINAL_RESPONSE);
      token.set("Bearer local-fixture-two");
      var second =
          model.invoke(
              request(
                  List.of(
                      Message.text("user", "Find guide"),
                      first.message(),
                      Message.tool("provider-call-42", "{\"found\":true}"))),
              context());
      assertEquals(FinishReason.FINAL, second.finishReason());
      assertFalse(second.usage().known(), "Missing provider usage must remain unknown");
      JsonNode secondWire = server.requests.get(1).path("messages");
      assertEquals(
          "provider-call-42", secondWire.get(1).path("tool_calls").get(0).path("id").asText());
      assertEquals("provider-call-42", secondWire.get(2).path("tool_call_id").asText());
      assertEquals(
          List.of("Bearer local-fixture-one", "Bearer local-fixture-two"), server.authorizations);
    }
  }

  @Test
  void realWireModelRunsThroughHarnessWithGovernedInternalTool() throws Exception {
    try (var server = new ModelServer()) {
      server.responder =
          wire -> {
            for (var message : wire.path("messages"))
              if (message.path("role").asText().equals("tool")) return FINAL_RESPONSE;
            return TOOL_RESPONSE;
          };
      var model = new DeepSeekChatModel(server.config(1_000_000, endpoint -> Map.of()));
      var descriptor = request(List.of(Message.text("user", "Find guide"))).tools().get(0);
      var registry = new ToolRegistry();
      var calls = new AtomicInteger();
      registry.register(
          descriptor,
          (tool, arguments, context) -> {
            calls.incrementAndGet();
            assertEquals("guide", arguments.path("query").asText());
            return ToolResult.success(Json.object().put("found", true));
          });
      var store = new InMemoryRunStore();
      var harness = new Harness(store, model, registry);
      var profile = request(List.of()).profile();
      var definition =
          new ProgramDefinition(
              "wire-model-agent",
              "1",
              "agent",
              Json.tree(
                  new AgentProgram.AgentSpec(
                      profile, List.of(Message.text("user", "Find guide")), 3)));
      var run =
          harness.start(
              definition,
              new Actor("fixture", "fixture-project", Set.of("*")),
              List.of(descriptor.key()),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "wire-model-fixture");
      for (int i = 0;
          i < 100 && (run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING);
          i++) run = harness.tick(run.id);
      assertEquals(RunStatus.COMPLETED, run.status, run.stopReason);
      assertEquals("Found it", run.output.path("content").asText());
      assertEquals(1, calls.get());
      assertEquals(2, server.requests.size());
    }
  }

  @Test
  void malformedArgumentsAndUnknownFinishesAreNotSuccessfulToolCalls() throws Exception {
    try (var server = new ModelServer()) {
      var model = new DeepSeekChatModel(server.config(1_000_000, endpoint -> Map.of()));
      server.response.set(TOOL_RESPONSE.replace("{\\\"query\\\":\\\"guide\\\"}", "[]"));
      var failure =
          assertThrows(
              InvocationException.class,
              () -> model.invoke(request(List.of(Message.text("user", "test"))), context()));
      assertEquals(FailureKind.UNKNOWN, failure.kind());
      assertNull(failure.getCause());
      server.response.set(FINAL_RESPONSE.replace("stop", "insufficient_system_resource"));
      assertEquals(
          FinishReason.UNKNOWN,
          model.invoke(request(List.of(Message.text("user", "test"))), context()).finishReason());
    }
  }

  @Test
  void authorizationFailureNeverCopiesProviderErrorBodyIntoLogsOrException() throws Exception {
    try (var server = new ModelServer()) {
      server.status = 401;
      server.response.set("{\"error\":\"secret-provider-credential\"}");
      var model = new DeepSeekChatModel(server.config(1024, endpoint -> Map.of()));
      var failure =
          assertThrows(
              InvocationException.class,
              () -> model.invoke(request(List.of(Message.text("user", "test"))), context()));
      assertEquals(FailureKind.DENIED, failure.kind());
      assertFalse(failure.toString().contains("secret-provider"));
      assertNull(failure.getCause());
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  void rejectsOversizedResponseAndDoesNotRetry() throws Exception {
    try (var server = new ModelServer()) {
      server.response.set(FINAL_RESPONSE.repeat(100));
      var model = new DeepSeekChatModel(server.config(100, endpoint -> Map.of()));
      var failure =
          assertThrows(
              InvocationException.class,
              () -> model.invoke(request(List.of(Message.text("user", "test"))), context()));
      assertEquals(FailureKind.UNKNOWN, failure.kind());
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  void timeoutCoversResponseBodyAndCancelsLocalWaitWithoutPretendingRemoteCancellation()
      throws Exception {
    try (var server = new ModelServer()) {
      server.bodyDelayMillis = 1_500;
      var model =
          new DeepSeekChatModel(
              new DeepSeekConfig(
                  server.endpoint(),
                  Duration.ofSeconds(1),
                  Duration.ofMillis(200),
                  1024,
                  endpoint -> Map.of()));
      var failure =
          assertThrows(
              InvocationException.class,
              () -> model.invoke(request(List.of(Message.text("user", "test"))), context()));
      assertEquals(FailureKind.UNKNOWN, failure.kind());
      assertEquals(1, server.requests.size());
    }
  }

  static ExecutionContext context() {
    return new ExecutionContext(
        "run-1",
        "node-1",
        "internal-invocation",
        "attempt-1",
        new Actor("fixture-user", "fixture-project", Set.of()),
        Instant.now().plusSeconds(20),
        "trace-1");
  }

  static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        "internal-invocation",
        new ModelProfile(
            "fixture-model", "deepseek", "configured-model", 4096, 256, 5_000, Json.object()),
        messages,
        List.of(
            new ToolDescriptor(
                "docs:lookup",
                "lookup",
                "Look up documents",
                "internal",
                "lookup",
                "1",
                Json.read(
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"),
                ToolPolicy.readOnlyPolicy())));
  }

  private static final class ModelServer implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    final AtomicReference<String> response = new AtomicReference<>(FINAL_RESPONSE);
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile long bodyDelayMillis;
    volatile java.util.function.Function<JsonNode, String> responder;

    ModelServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/chat/completions",
          exchange -> {
            try (exchange) {
              var request = Json.MAPPER.readTree(exchange.getRequestBody());
              requests.add(request);
              authorizations.add(
                  String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
              byte[] bytes =
                  (responder == null ? response.get() : responder.apply(request))
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, bytes.length);
              if (bodyDelayMillis > 0) {
                try {
                  Thread.sleep(bodyDelayMillis);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }

    DeepSeekConfig config(
        int maxBytes, io.github.djyking.harness.adapters.http.HeaderProvider headers) {
      return new DeepSeekConfig(
          endpoint(), Duration.ofSeconds(2), Duration.ofSeconds(5), maxBytes, headers);
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
