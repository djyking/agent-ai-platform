package io.github.djyking.harness.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/**
 * Local protocol fixtures only; these tests make no claim about live OpenAI or Kimi availability.
 */
class ChatCompletionsModelTest {
  private static final String FINAL =
      """
      {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"fixture answer"}}],
       "usage":{"prompt_tokens":11,"completion_tokens":7}}
      """;

  @Test
  void tokenLimitDialectIsExplicitAndGenericRequestsDoNotSendDeepSeekThinking() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var wires = new ArrayList<JsonNode>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try (exchange) {
            wires.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            byte[] body = FINAL.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      URI endpoint =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
      for (String field : List.of("max_completion_tokens", "max_tokens")) {
        var adapter =
            new ChatCompletionsModel(
                new ChatCompletionsConfig(
                    endpoint,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    65536,
                    ignored -> Map.of("Authorization", "Bearer synthetic-local-fixture"),
                    field,
                    false));
        var result =
            adapter.invoke(
                DeepSeekChatModelTest.request(List.of(Message.text("user", "fixture"))),
                DeepSeekChatModelTest.context());
        assertEquals("fixture answer", result.message().content());
        assertEquals(18, result.usage().total());
        JsonNode wire = wires.get(wires.size() - 1);
        assertEquals(256, wire.path(field).asInt());
        assertFalse(wire.has(field.equals("max_tokens") ? "max_completion_tokens" : "max_tokens"));
        assertFalse(wire.has("thinking"));
        assertEquals("function", wire.path("tools").get(0).path("type").asText());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void redirectAndUsageUnknownFailuresHaveNoImplicitReplayOrProviderBodyLeak() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger(),
        redirects = new AtomicInteger(),
        status = new AtomicInteger(302);
    server.createContext(
        "/chat",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/redirected");
            byte[] body = "secret-fixture-provider-detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.createContext(
        "/redirected",
        exchange -> {
          redirects.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new ChatCompletionsModel(
              new ChatCompletionsConfig(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"),
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(5),
                  65536,
                  ignored -> Map.of(),
                  "max_completion_tokens",
                  false));
      var request = DeepSeekChatModelTest.request(List.of(Message.text("user", "fixture")));
      var redirect =
          assertThrows(
              InvocationException.class,
              () -> adapter.invoke(request, DeepSeekChatModelTest.context()));
      assertEquals(FailureKind.PERMANENT, redirect.kind());
      assertEquals(1, calls.get());
      assertEquals(0, redirects.get());
      status.set(503);
      var unknown =
          assertThrows(
              InvocationException.class,
              () -> adapter.invoke(request, DeepSeekChatModelTest.context()));
      assertEquals(FailureKind.UNKNOWN, unknown.kind());
      assertEquals(2, calls.get());
      assertFalse(unknown.getMessage().contains("secret-fixture"));
      assertNull(unknown.getCause());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void invalidTokenFieldsAndCleartextRemoteEndpointsCannotEnterTrustedBinding() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatCompletionsConfig(
                URI.create("https://example.invalid/v1/chat/completions"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                65536,
                ignored -> Map.of(),
                "unbounded_tokens",
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatCompletionsConfig(
                URI.create("http://example.invalid/v1/chat/completions"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                65536,
                ignored -> Map.of(),
                "max_tokens",
                false));
  }
}
