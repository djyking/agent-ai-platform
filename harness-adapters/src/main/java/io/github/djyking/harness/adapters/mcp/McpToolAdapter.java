package io.github.djyking.harness.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import io.modelcontextprotocol.spec.McpError;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Binds locally authorized catalog entries to the common tool execution contract. */
public final class McpToolAdapter implements ToolHandler {
  private final McpConnection connection;
  private volatile Map<String, ToolDescriptor> bindings = Map.of();

  public McpToolAdapter(McpConnection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  public String adapterId() {
    return "mcp:" + connection.serverId();
  }

  /**
   * Discovery supplies schemas only. The host explicitly supplies each allowed remote name and
   * policy. Rebinding replaces this adapter's catalog atomically; publish the returned descriptors
   * to the registry.
   */
  public List<ToolDescriptor> bind(Map<String, ToolPolicy> allowedRemoteTools) {
    Objects.requireNonNull(allowedRemoteTools, "allowedRemoteTools");
    var policies = Map.copyOf(allowedRemoteTools);
    var next = new LinkedHashMap<String, ToolDescriptor>();
    for (var remote : connection.discover()) {
      ToolPolicy policy = policies.get(remote.name());
      if (policy == null) continue;
      if (!policy.readOnly() && (policy.retrySafe() || policy.maxAttempts() > 1)) {
        throw new IllegalArgumentException(
            "Generic MCP writes have no remote idempotency contract; use one attempt");
      }
      var descriptor =
          new ToolDescriptor(
              adapterId() + ":" + remote.name(),
              "mcp_" + connection.serverId() + "_" + Json.hash(remote.name()).substring(0, 16),
              remote.description(),
              adapterId(),
              remote.name(),
              Json.hash(remote),
              Json.tree(remote.inputSchema()),
              policy);
      next.put(descriptor.key(), descriptor);
    }
    if (next.size() != policies.size()) {
      throw new IllegalArgumentException(
          "A locally allowed MCP tool is missing from the server catalog");
    }
    bindings = Map.copyOf(next);
    return List.copyOf(next.values());
  }

  @Override
  public ToolResult invoke(ToolDescriptor tool, JsonNode arguments, ExecutionContext context) {
    var bound = bindings.get(tool.key());
    if (bound == null || !bound.digest().equals(tool.digest())) {
      throw new InvocationException(FailureKind.DENIED, "MCP_BINDING_CHANGED_OR_NOT_ALLOWED");
    }
    if (arguments == null || !arguments.isObject()) {
      throw new InvocationException(FailureKind.INVALID, "MCP_ARGUMENTS_MUST_BE_OBJECT");
    }
    if (!Instant.now().isBefore(context.deadline())) {
      throw new InvocationException(FailureKind.PERMANENT, "MCP_DEADLINE_EXCEEDED");
    }
    final String remoteVersion;
    try {
      remoteVersion =
          connection.discoverUntil(context.deadline()).stream()
              .filter(remote -> remote.name().equals(bound.remoteName()))
              .findFirst()
              .map(Json::hash)
              .orElse("");
    } catch (RuntimeException unavailable) {
      // Contract discovery has no tool side effect; unlike a tools/call timeout, this is not an
      // unknown write.
      throw new InvocationException(FailureKind.TRANSIENT, "MCP_CONTRACT_LOOKUP_FAILED");
    }
    if (!bound.version().equals(remoteVersion)) {
      throw new InvocationException(FailureKind.DENIED, "MCP_REMOTE_CONTRACT_CHANGED");
    }
    var currentBinding = bindings.get(tool.key());
    if (currentBinding == null || !currentBinding.digest().equals(bound.digest())) {
      throw new InvocationException(FailureKind.DENIED, "MCP_BINDING_CHANGED_OR_NOT_ALLOWED");
    }
    Map<String, Object> values = Json.MAPPER.convertValue(arguments, new TypeReference<>() {});
    try {
      var result =
          connection.call(
              bound.remoteName(),
              values,
              java.time.Duration.between(Instant.now(), context.deadline()));
      // Preserve all typed content (including non-text blocks), structured data and metadata.
      return new ToolResult(Json.tree(result), Boolean.TRUE.equals(result.isError()), null);
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      for (int i = 0; i < 8 && cause != null; i++) {
        if (cause instanceof McpError error && error.getJsonRpcError() != null) {
          int code = error.getJsonRpcError().code();
          if (code == -32601 || code == -32602) {
            throw new InvocationException(FailureKind.INVALID, "MCP_REQUEST_REJECTED");
          }
        }
        if (cause
            .getClass()
            .getSimpleName()
            .equals("McpHttpClientTransportAuthorizationException")) {
          throw new InvocationException(FailureKind.DENIED, "MCP_AUTHORIZATION_FAILED");
        }
        cause = cause.getCause();
      }
      // Transport timeout/disconnect may follow completed side effects. No wrapper retry or fake
      // receipt.
      throw new InvocationException(FailureKind.UNKNOWN, "MCP_CALL_OUTCOME_UNKNOWN");
    }
  }
}
