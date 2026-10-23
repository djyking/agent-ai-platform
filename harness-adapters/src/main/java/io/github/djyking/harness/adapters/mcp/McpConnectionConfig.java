package io.github.djyking.harness.adapters.mcp;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.github.djyking.harness.adapters.http.HttpEndpoints;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Credentials are resolved lazily; configuration contains no secret value. */
public record McpConnectionConfig(
    String serverId,
    URI endpoint,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBytes,
    int maxDiscoveryPages,
    int maxDiscoveredTools,
    HeaderProvider headers) {
  public McpConnectionConfig {
    if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,40}")) {
      throw new IllegalArgumentException(
          "serverId must contain 1-40 letters, digits, underscores or hyphens");
    }
    endpoint = HttpEndpoints.requireSecureOrLoopback(endpoint);
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(headers, "headers");
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || maxResponseBytes < 1
        || maxDiscoveryPages < 1
        || maxDiscoveredTools < 1) {
      throw new IllegalArgumentException("Timeouts and limits must be positive");
    }
  }

  public static McpConnectionConfig defaults(
      String serverId, URI endpoint, HeaderProvider headers) {
    return new McpConnectionConfig(
        serverId,
        endpoint,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        4 * 1024 * 1024,
        100,
        10_000,
        headers);
  }
}
