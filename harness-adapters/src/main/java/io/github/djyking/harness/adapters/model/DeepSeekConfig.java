package io.github.djyking.harness.adapters.model;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.github.djyking.harness.adapters.http.HttpEndpoints;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Non-streaming text/tool chat protocol. The endpoint is the complete /chat/completions URL. */
public record DeepSeekConfig(
    URI endpoint,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBytes,
    HeaderProvider headers) {
  public DeepSeekConfig {
    endpoint = HttpEndpoints.requireSecureOrLoopback(endpoint);
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(headers, "headers");
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || maxResponseBytes < 1) {
      throw new IllegalArgumentException("Timeouts and response size limit must be positive");
    }
  }

  public static DeepSeekConfig defaults(HeaderProvider headers) {
    return new DeepSeekConfig(
        URI.create("https://api.deepseek.com/chat/completions"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(60),
        4 * 1024 * 1024,
        headers);
  }
}
