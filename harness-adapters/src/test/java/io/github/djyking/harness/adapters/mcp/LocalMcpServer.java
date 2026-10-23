package io.github.djyking.harness.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A wire-level MCP fixture: the client under test is the real official SDK. */
final class LocalMcpServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newFixedThreadPool(4);
  final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  final List<String> authorizations = new CopyOnWriteArrayList<>();
  final List<String> callTraceparents = new CopyOnWriteArrayList<>();
  final AtomicInteger calls = new AtomicInteger();
  volatile boolean cyclicCursor;
  volatile boolean errorResult;
  volatile boolean structuredArray;
  volatile long callDelayMillis;
  volatile boolean changedContract;

  LocalMcpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/mcp", this::handle);
    server.start();
  }

  URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      if (!exchange.getRequestMethod().equals("POST")) {
        exchange.sendResponseHeaders(exchange.getRequestMethod().equals("DELETE") ? 204 : 405, -1);
        return;
      }
      var request = JSON.readTree(exchange.getRequestBody());
      requests.add(request);
      if (!request.has("id")) {
        exchange.sendResponseHeaders(202, -1);
        return;
      }
      Object result;
      switch (request.path("method").asText()) {
        case "initialize" -> {
          exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
          result =
              Map.of(
                  "protocolVersion",
                  request.path("params").path("protocolVersion").asText(),
                  "capabilities",
                  Map.of("tools", Map.of()),
                  "serverInfo",
                  Map.of("name", "local-fixture", "version", "1.0"));
        }
        case "tools/list" -> {
          String cursor = request.path("params").path("cursor").asText("");
          result =
              cursor.isEmpty() || cyclicCursor
                  ? Map.of(
                      "tools",
                      List.of(tool(cursor.isEmpty() ? "lookup" : "lookup-next")),
                      "nextCursor",
                      "page-2")
                  : Map.of("tools", List.of(tool("update")));
        }
        case "tools/call" -> {
          calls.incrementAndGet();
          callTraceparents.add(
              String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
          if (callDelayMillis > 0) {
            try {
              Thread.sleep(callDelayMillis);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          result =
              Map.of(
                  "content",
                  List.of(
                      Map.of("type", "text", "text", "fixture-result"),
                      Map.of("type", "image", "mimeType", "image/png", "data", "AA==")),
                  "structuredContent",
                  structuredArray ? List.of("a", "b") : Map.of("value", 7),
                  "isError",
                  errorResult);
        }
        case "ping" -> result = Map.of();
        default -> {
          respond(
              exchange,
              Map.of(
                  "jsonrpc",
                  "2.0",
                  "id",
                  request.get("id"),
                  "error",
                  Map.of("code", -32601, "message", "Unknown method")));
          return;
        }
      }
      respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
    }
  }

  private Map<String, Object> tool(String name) {
    return Map.of(
        "name",
        name,
        "description",
        "Local fixture tool " + name + (changedContract ? " changed" : ""),
        "inputSchema",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("query", Map.of("type", "string")),
            "required",
            List.of("query"),
            "additionalProperties",
            false),
        "outputSchema",
        Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer"))),
        // Deliberately misleading: the adapter must take risk from local policy.
        "annotations",
        Map.of("readOnlyHint", true));
  }

  private void respond(HttpExchange exchange, Object response) throws IOException {
    byte[] bytes = JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
