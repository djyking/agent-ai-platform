package io.github.djyking.harness.adapters.mcp;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Official MCP Java SDK transport. Discovering a tool never grants execution permission. Own one
 * connection per credential/security domain and close it with the owning application.
 */
public final class McpConnection implements AutoCloseable {
  private final McpConnectionConfig config;
  private static final String CALL_HEADERS = "harness.call.headers";
  private final McpAsyncClient client;
  private volatile boolean closed;

  private McpConnection(McpConnectionConfig config, McpAsyncClient client) {
    this.config = config;
    this.client = client;
  }

  public static McpConnection connect(McpConnectionConfig config) {
    Objects.requireNonNull(config, "config");
    var endpoint = config.endpoint();
    var origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
    var path = endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
    var transport =
        HttpClientStreamableHttpTransport.builder(origin)
            .endpoint(path)
            .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
            .connectTimeout(config.connectTimeout())
            .maxResponseSize(config.maxResponseBytes())
            .openConnectionOnStartup(false)
            .httpRequestCustomizer(
                (builder, method, target, body, context) -> {
                  if (!target.equals(endpoint)) {
                    throw new IllegalStateException(
                        "MCP credentials cannot be forwarded to another endpoint");
                  }
                  var provider =
                      context.get(CALL_HEADERS) instanceof HeaderProvider captured
                          ? captured
                          : config.headers();
                  provider.headers(target).forEach(builder::setHeader);
                })
            .build();
    var client =
        McpClient.async(transport)
            .clientInfo(McpSchema.Implementation.builder("agent-ai-platform", "0.1.0").build())
            .requestTimeout(config.requestTimeout())
            .initializationTimeout(config.requestTimeout())
            .build();
    try {
      client.initialize().block(config.requestTimeout());
      return new McpConnection(config, client);
    } catch (RuntimeException failure) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
        /* Preserve the sanitized initialization error. */
      }
      // SDK failures can carry raw HTTP snapshots. Do not expose them in framework errors.
      throw new IllegalStateException("MCP initialization failed for server " + config.serverId());
    }
  }

  public String serverId() {
    return config.serverId();
  }

  public java.time.Duration requestTimeout() {
    return config.requestTimeout();
  }

  /** Returns the raw server catalog for explicit local review and binding. */
  public List<McpSchema.Tool> discover() {
    return discoverUntil(
        Instant.now().plus(config.requestTimeout().multipliedBy(config.maxDiscoveryPages())));
  }

  List<McpSchema.Tool> discoverUntil(Instant deadline) {
    requireOpen();
    var tools = new LinkedHashMap<String, McpSchema.Tool>();
    var cursors = new HashSet<String>();
    String cursor = null;
    for (int page = 0; page < config.maxDiscoveryPages(); page++) {
      final McpSchema.ListToolsResult result;
      try {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero())
          throw new IllegalStateException("MCP discovery deadline exceeded");
        Duration timeout =
            remaining.compareTo(config.requestTimeout()) < 0 ? remaining : config.requestTimeout();
        // The no-argument SDK method expands pages without a bound. Use its paged API.
        result = client.listTools(cursor).contextWrite(callHeaders()).block(timeout);
      } catch (RuntimeException failure) {
        throw new IllegalStateException("MCP discovery failed for server " + config.serverId());
      }
      if (result == null || result.tools() == null) {
        throw new IllegalStateException("MCP discovery returned no tools array");
      }
      for (var tool : result.tools()) {
        if (tool.name().isBlank() || tools.putIfAbsent(tool.name(), tool) != null) {
          throw new IllegalStateException("MCP discovery contains a blank or duplicate tool name");
        }
        if (tools.size() > config.maxDiscoveredTools()) {
          throw new IllegalStateException("MCP discovery tool limit exceeded");
        }
      }
      cursor = result.nextCursor();
      if (cursor == null || cursor.isEmpty()) {
        return List.copyOf(tools.values());
      }
      if (!cursors.add(cursor)) {
        throw new IllegalStateException("MCP discovery repeated a pagination cursor");
      }
    }
    throw new IllegalStateException("MCP discovery page limit exceeded");
  }

  /** Single attempt only. A local invocation ID is never sent as a remote idempotency key. */
  McpSchema.CallToolResult call(String remoteToolName, Map<String, Object> arguments) {
    return call(remoteToolName, arguments, config.requestTimeout());
  }

  McpSchema.CallToolResult call(
      String remoteToolName, Map<String, Object> arguments, Duration remaining) {
    requireOpen();
    Duration timeout =
        remaining.compareTo(config.requestTimeout()) < 0 ? remaining : config.requestTimeout();
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalStateException("MCP call deadline exceeded");
    return client
        .callTool(McpSchema.CallToolRequest.builder(remoteToolName).arguments(arguments).build())
        .contextWrite(callHeaders())
        .block(timeout);
  }

  private java.util.function.Function<reactor.util.context.Context, reactor.util.context.Context>
      callHeaders() {
    HeaderProvider captured = config.headers().forCurrentCall();
    var metadata = McpTransportContext.create(Map.of(CALL_HEADERS, captured));
    return context -> context.put(McpTransportContext.KEY, metadata);
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("MCP connection is closed");
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      try {
        client.close();
      } catch (RuntimeException failure) {
        throw new IllegalStateException("MCP close failed for server " + config.serverId());
      }
    }
  }
}
