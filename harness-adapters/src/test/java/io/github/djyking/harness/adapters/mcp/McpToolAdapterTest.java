package io.github.djyking.harness.adapters.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {
  @Test
  void localPolicyOverridesRemoteRiskHintsAndBindingPreservesCompleteResults() throws Exception {
    try (var server = new LocalMcpServer();
        var connection =
            McpConnection.connect(
                McpConnectionConfig.defaults(
                    "fixture", server.endpoint(), HeaderProvider.none()))) {
      var adapter = new McpToolAdapter(connection);
      var descriptor = adapter.bind(Map.of("update", ToolPolicy.approvedWrite())).get(0);
      assertFalse(descriptor.policy().readOnly());
      assertTrue(descriptor.policy().approvalRequired());
      assertEquals("mcp:fixture:update", descriptor.key());
      assertTrue(descriptor.modelName().matches("[A-Za-z0-9_-]{1,64}"));
      var result = adapter.invoke(descriptor, Json.read("{\"query\":\"x\"}"), context());
      assertEquals("image", result.output().path("content").get(1).path("type").asText());
      assertEquals(7, result.output().path("structuredContent").path("value").asInt());
      assertNull(result.receipt(), "A local ID is not a remote execution receipt");
      adapter.bind(Map.of("lookup", ToolPolicy.readOnlyPolicy()));
      assertEquals(
          FailureKind.DENIED,
          assertThrows(
                  InvocationException.class,
                  () -> adapter.invoke(descriptor, Json.object(), context()))
              .kind());
      assertEquals(1, server.calls.get());
    }
  }

  @Test
  void remoteContractDriftPreventsAnAlreadyBoundWrite() throws Exception {
    try (var server = new LocalMcpServer();
        var connection =
            McpConnection.connect(
                McpConnectionConfig.defaults(
                    "fixture", server.endpoint(), HeaderProvider.none()))) {
      var adapter = new McpToolAdapter(connection);
      var descriptor = adapter.bind(Map.of("update", ToolPolicy.approvedWrite())).get(0);
      server.changedContract = true;
      var failure =
          assertThrows(
              InvocationException.class,
              () -> adapter.invoke(descriptor, Json.read("{\"query\":\"change\"}"), context()));
      assertEquals(FailureKind.DENIED, failure.kind());
      assertEquals("MCP_REMOTE_CONTRACT_CHANGED", failure.getMessage());
      assertEquals(0, server.calls.get());
    }
  }

  @Test
  void genericMcpWritesCannotDeclareRemoteIdempotencyWithoutAProtocolContract() throws Exception {
    try (var server = new LocalMcpServer();
        var connection =
            McpConnection.connect(
                McpConnectionConfig.defaults(
                    "fixture", server.endpoint(), HeaderProvider.none()))) {
      var adapter = new McpToolAdapter(connection);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              adapter.bind(Map.of("update", new ToolPolicy(false, true, true, 2, 1000, Set.of()))));
      assertEquals(0, server.calls.get());
    }
  }

  @Test
  void writeTimeoutBecomesUnknownWithOneWireAttempt() throws Exception {
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
        var adapter = new McpToolAdapter(connection);
        var descriptor = adapter.bind(Map.of("update", ToolPolicy.approvedWrite())).get(0);
        server.callDelayMillis = 2_000;
        var failure =
            assertThrows(
                InvocationException.class,
                () -> adapter.invoke(descriptor, Json.read("{\"query\":\"x\"}"), context()));
        assertEquals(FailureKind.UNKNOWN, failure.kind());
        assertNull(failure.getCause());
        assertEquals(1, server.calls.get());
      }
    }
  }

  private ExecutionContext context() {
    return new ExecutionContext(
        "run-1",
        "node-1",
        "invocation-1",
        "attempt-1",
        new Actor("user", "project", Set.of()),
        Instant.now().plusSeconds(20),
        "trace-1");
  }
}
