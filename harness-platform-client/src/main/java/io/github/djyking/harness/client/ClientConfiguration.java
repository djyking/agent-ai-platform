package io.github.djyking.harness.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record ClientConfiguration(
    URI origin,
    String project,
    SecretProvider applicationCredential,
    SecretProvider userToken,
    Duration timeout,
    int maxResponseBytes) {
  public ClientConfiguration {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(applicationCredential);
    Objects.requireNonNull(userToken);
    Objects.requireNonNull(timeout);
    String host = origin.getHost();
    if (host == null
        || origin.getRawUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
        || !("https".equals(origin.getScheme())
            || "http".equals(origin.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(host)))
      throw new IllegalArgumentException("Platform origin must be HTTPS or loopback HTTP");
    if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw new IllegalArgumentException("Invalid project identifier");
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0)
      throw new IllegalArgumentException("Invalid request timeout");
    if (maxResponseBytes < 1024 || maxResponseBytes > 4 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid response limit");
    origin = URI.create(origin.getScheme() + "://" + origin.getRawAuthority());
  }

  public static ClientConfiguration defaults(
      URI origin, String project, SecretProvider applicationCredential, SecretProvider userToken) {
    return new ClientConfiguration(
        origin, project, applicationCredential, userToken, Duration.ofSeconds(20), 1024 * 1024);
  }

  @Override
  public String toString() {
    return "ClientConfiguration[origin=" + origin + ", project=" + project + "]";
  }
}
