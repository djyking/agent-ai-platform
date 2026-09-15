package io.github.djyking.harness.adapters.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpConnectionTest {
  @Test
  void realSdkNegotiatesPagesAndRotatesCredentialsWithoutRemoteIdempotencyClaims()
      throws Exception {
    try (var server = new LocalMcpServer()) {
      var credential = new AtomicReference<>("Bearer first");
      var config =
          McpConnectionConfig.defaults(
              "fixture", server.endpoint(), endpoint -> Map.of("Authorization", credential.get()));
      try (var connection = McpConnection.connect(config)) {
        var catalog = connection.discover();
        assertEquals(2, catalog.size());
        assertEquals("lookup", catalog.get(0).name());
        assertEquals(false, catalog.get(0).inputSchema().get("additionalProperties"));
        assertNotNull(catalog.get(0).outputSchema());
        credential.set("Bearer second");
        var result = connection.call("lookup", Map.of("query", "test"));
        assertFalse(result.isError());
        assertEquals(2, result.content().size());
        assertEquals(Map.of("value", 7), result.structuredContent());
        var call =
            server.requests.stream()
                .filter(r -> r.path("method").asText().equals("tools/call"))
                .findFirst()
                .orElseThrow();
        assertEquals("lookup", call.path("params").path("name").asText());
        assertFalse(call.path("params").has("_meta"));
        assertTrue(server.authorizations.contains("Bearer first"));
        assertTrue(server.authorizations.contains("Bearer second"));
        assertEquals(1, server.calls.get());
      }
    }
  }

  @Test
  void boundedDiscoveryRejectsCyclicCursor() throws Exception {
    try (var server = new LocalMcpServer()) {
      server.cyclicCursor = true;
      try (var connection =
          McpConnection.connect(
              McpConnectionConfig.defaults("fixture", server.endpoint(), HeaderProvider.none()))) {
        assertTrue(
            assertThrows(IllegalStateException.class, connection::discover)
                .getMessage()
                .contains("pagination cursor"));
      }
    }
  }

  @Test
  void preservesToolErrorsAndStructuredArrays() throws Exception {
    try (var server = new LocalMcpServer()) {
      server.errorResult = true;
      server.structuredArray = true;
      try (var connection =
          McpConnection.connect(
              McpConnectionConfig.defaults("fixture", server.endpoint(), HeaderProvider.none()))) {
        var result = connection.call("lookup", Map.of("query", "test"));
        assertTrue(result.isError());
        assertInstanceOf(java.util.List.class, result.structuredContent());
      }
    }
  }

  @Test
  void timeoutDoesNotResubmitRemoteCall() throws Exception {
    try (var server = new LocalMcpServer()) {
      var config =
          new McpConnectionConfig(
              "fixture",
              server.endpoint(),
              Duration.ofSeconds(2),
              Duration.ofMillis(500),
              1024 * 1024,
              10,
              100,
              HeaderProvider.none());
      try (var connection = McpConnection.connect(config)) {
        server.callDelayMillis = 2_000;
        assertThrows(
            RuntimeException.class, () -> connection.call("update", Map.of("query", "test")));
        assertEquals(1, server.calls.get(), "Timeout must not replay a possibly completed write");
      }
    }
  }
}
