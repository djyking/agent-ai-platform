package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CapabilityServiceTest {
  private IdentityProvider.Principal admin() {
    return new IdentityProvider.Principal("app-a", "project-a", "alice", Set.of("*"));
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private Deployment config(PlatformTestSupport f, String endpoint) {
    return new Deployment(
        f.config.identityOrigin(),
        f.config.signingSecret(),
        f.config.applicationSecrets(),
        f.config.projects(),
        f.config.releases(),
        Set.of(),
        List.of(
            Json.object()
                .put("kind", "mcp")
                .put("serverId", "tools")
                .put("endpoint", endpoint)
                .put("secretRef", "env:MCP_TEST")),
        List.of(
            Json.object()
                .put("kind", "deepseek")
                .put("provider", "deepseek")
                .put("endpoint", endpoint)
                .put("secretRef", "env:MODEL_TEST"),
            Json.object()
                .put("kind", "openai-compatible")
                .put("provider", "openai")
                .put("endpoint", endpoint)
                .put("secretRef", "env:MODEL_TEST")),
        4,
        10000,
        false);
  }

  @Test
  void approvedConnectionsHavePersistentProjectScopedDisableWithoutUrlOrSecretWrites() {
    try (var f = new PlatformTestSupport()) {
      var d = config(f, "https://approved.invalid/v1/chat/completions");
      var service = new CapabilityService(d, f.repository, ref -> "synthetic-private-credential");
      assertEquals(3, service.list(admin()).path("items").size());
      assertFalse(service.list(admin()).toString().contains("MODEL_TEST"));
      service.assertModelEnabled("project-a", "deepseek");
      service.save(
          admin(),
          "model",
          "deepseek",
          key(),
          "\"k0\"",
          Json.object().put("name", "DeepSeek").put("enabled", false));
      var reopened = new CapabilityService(d, f.repository, ref -> "synthetic-private-credential");
      assertThrows(ApiFailure.class, () -> reopened.assertModelEnabled("project-a", "deepseek"));
      assertDoesNotThrow(() -> reopened.assertModelEnabled("project-b", "deepseek"));
      assertDoesNotThrow(() -> reopened.assertModelEnabled("project-a", "openai"));
      assertThrows(
          ApiFailure.class,
          () ->
              service.save(
                  admin(),
                  "model",
                  "unknown",
                  key(),
                  "\"k0\"",
                  Json.object().put("name", "New").put("enabled", true)));
      assertThrows(
          ApiFailure.class,
          () ->
              service.save(
                  admin(),
                  "model",
                  "openai",
                  key(),
                  "\"k0\"",
                  Json.object()
                      .put("name", "New")
                      .put("enabled", true)
                      .put("endpoint", "http://169.254.169.254")));
      assertThrows(
          ApiFailure.class,
          () ->
              service.save(
                  admin(),
                  "model",
                  "openai",
                  key(),
                  "\"k0\"",
                  Json.object()
                      .put("name", "New")
                      .put("enabled", true)
                      .put("secretRef", "file:arbitrary")));
      service.save(
          admin(),
          "mcp",
          "tools",
          key(),
          "\"k0\"",
          Json.object().put("name", "Tools").put("enabled", false));
      assertThrows(
          ApiFailure.class, () -> service.assertToolEnabled("project-a", "mcp:tools:read"));
      assertDoesNotThrow(() -> service.assertToolEnabled("project-a", "studio:knowledge"));
    }
  }

  @Test
  void diagnosticsUseMetadataOnlyAndExactReceiptNeverReplaysNetworkRequest() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
          requests.incrementAndGet();
          assertEquals("GET", exchange.getRequestMethod());
          assertEquals(
              "Bearer synthetic-private-credential",
              exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] content =
              "{\"data\":[{\"id\":\"fixture-model\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, content.length);
          exchange.getResponseBody().write(content);
          exchange.close();
        });
    server.start();
    try (var f = new PlatformTestSupport()) {
      var service =
          new CapabilityService(
              config(
                  f, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
              f.repository,
              ref -> "synthetic-private-credential");
      String same = key();
      JsonNode first = service.diagnose(admin(), "model", "openai", same, "\"k0\"", Json.object());
      assertTrue(first.path("diagnostic").path("reachable").asBoolean());
      assertFalse(first.path("diagnostic").path("generationVerified").asBoolean());
      assertEquals("MODEL_CATALOG_ONLY", first.path("diagnostic").path("mode").asText());
      assertEquals(
          ApiJson.canonical(first),
          ApiJson.canonical(
              service.diagnose(admin(), "model", "openai", same, "\"k0\"", Json.object())));
      assertEquals(1, requests.get());
      assertFalse(first.toString().contains("synthetic-private-credential"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void redirectsAndCredentialBearingErrorsAreNotFollowedOrPersisted() throws Exception {
    AtomicInteger redirected = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/leak");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/leak",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try (var f = new PlatformTestSupport()) {
      var service =
          new CapabilityService(
              config(
                  f, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
              f.repository,
              ref -> "synthetic-private-credential");
      var result = service.diagnose(admin(), "model", "deepseek", key(), "\"k0\"", Json.object());
      assertFalse(result.path("diagnostic").path("reachable").asBoolean());
      assertEquals(302, result.path("diagnostic").path("httpStatus").asInt());
      assertEquals(0, redirected.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void writeOnlyAndReadOnlyPrincipalsCannotEnableOrProbeConnections() {
    try (var f = new PlatformTestSupport()) {
      var service =
          new CapabilityService(
              config(f, "https://approved.invalid/v1/chat/completions"),
              f.repository,
              ref -> {
                throw new AssertionError("Unauthorized secret resolution");
              });
      var writer =
          new IdentityProvider.Principal(
              "app-a", "project-a", "alice", Set.of("catalog:write", "catalog:read"));
      assertThrows(
          ApiFailure.class,
          () ->
              service.save(
                  writer,
                  "model",
                  "deepseek",
                  key(),
                  "\"k0\"",
                  Json.object().put("name", "new").put("enabled", true)));
      assertThrows(
          ApiFailure.class,
          () -> service.diagnose(writer, "model", "deepseek", key(), "\"k0\"", Json.object()));
    }
  }

  @Test
  void mcpDiscoveryPersistsSchemaDifferencesButNeverCallsToolsOrApprovesThem() throws Exception {
    AtomicInteger calls = new AtomicInteger(), schema = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/mcp",
        exchange -> {
          try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
              exchange.sendResponseHeaders(
                  exchange.getRequestMethod().equals("DELETE") ? 204 : 405, -1);
              return;
            }
            JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
              exchange.sendResponseHeaders(202, -1);
              return;
            }
            Object result;
            switch (request.path("method").asText()) {
              case "initialize" -> {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "synthetic-session");
                result =
                    Map.of(
                        "protocolVersion",
                        request.path("params").path("protocolVersion").asText(),
                        "capabilities",
                        Map.of("tools", Map.of()),
                        "serverInfo",
                        Map.of("name", "fixture", "version", "1"));
              }
              case "tools/list" ->
                  result =
                      Map.of(
                          "tools",
                          List.of(
                              Map.of(
                                  "name",
                                  "lookup",
                                  "inputSchema",
                                  Map.of(
                                      "type",
                                      "object",
                                      "properties",
                                      Map.of(
                                          "query",
                                          Map.of(
                                              "type",
                                              schema.get() == 0 ? "string" : "integer"))))));
              case "tools/call" -> {
                calls.incrementAndGet();
                result = Map.of();
              }
              default -> result = Map.of();
            }
            byte[] bytes =
                Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    try (var f = new PlatformTestSupport()) {
      var service =
          new CapabilityService(
              config(f, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
              f.repository,
              ref -> "synthetic-private-credential");
      var first = service.diagnose(admin(), "mcp", "tools", key(), "\"k0\"", Json.object());
      assertTrue(first.path("diagnostic").path("reachable").asBoolean(), first.toString());
      assertEquals(
          "lookup", first.path("diagnostic").path("schemaDiff").path("added").get(0).asText());
      assertFalse(first.path("discovery").get(0).path("executionApproved").asBoolean());
      schema.incrementAndGet();
      var next = service.diagnose(admin(), "mcp", "tools", key(), "\"k1\"", Json.object());
      assertTrue(next.path("diagnostic").path("reachable").asBoolean(), next.toString());
      assertEquals(
          "lookup", next.path("diagnostic").path("schemaDiff").path("changed").get(0).asText());
      assertEquals(0, calls.get());
    } finally {
      server.stop(0);
    }
  }
}
