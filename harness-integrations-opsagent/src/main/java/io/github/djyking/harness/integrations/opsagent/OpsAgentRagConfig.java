package io.github.djyking.harness.integrations.opsagent;

import io.github.djyking.harness.adapters.http.HttpEndpoints;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Host-owned origin and bounds. Credentials, query arguments and business identities stay separate.
 */
public record OpsAgentRagConfig(
    URI origin,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBytes,
    int maxEvidenceChars,
    int maxCitations) {
  public OpsAgentRagConfig {
    origin = HttpEndpoints.requireSecureOrLoopback(origin);
    if (!origin.getRawPath().equals("/"))
      throw new IllegalArgumentException("OpsAgent origin must not include a path");
    Objects.requireNonNull(connectTimeout);
    Objects.requireNonNull(requestTimeout);
    if (connectTimeout.toMillis() < 1
        || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
        || requestTimeout.toMillis() < 1
        || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0
        || maxResponseBytes < 256
        || maxResponseBytes > 4 * 1024 * 1024
        || maxEvidenceChars < 1
        || maxEvidenceChars > 200_000
        || maxCitations < 1
        || maxCitations > 100)
      throw new IllegalArgumentException("Invalid OpsAgent response/deadline bounds");
  }

  public static OpsAgentRagConfig defaults(URI origin) {
    return new OpsAgentRagConfig(
        origin, Duration.ofSeconds(3), Duration.ofSeconds(15), 1024 * 1024, 100_000, 100);
  }

  public URI endpoint() {
    return origin.resolve("/internal/rag/search");
  }
}
