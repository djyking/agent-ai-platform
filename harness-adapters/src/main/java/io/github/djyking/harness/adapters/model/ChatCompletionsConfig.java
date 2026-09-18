package io.github.djyking.harness.adapters.model;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.github.djyking.harness.adapters.http.HttpEndpoints;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Trusted chat-completions endpoint options; never inferred from a provider's display name. */
public record ChatCompletionsConfig(
    URI endpoint,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBytes,
    HeaderProvider headers,
    String tokenLimitParameter,
    boolean disableThinking) {
  public ChatCompletionsConfig {
    endpoint = HttpEndpoints.requireSecureOrLoopback(endpoint);
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(headers, "headers");
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || maxResponseBytes < 1)
      throw new IllegalArgumentException("Timeouts and response size limit must be positive");
    if (!Set.of("max_tokens", "max_completion_tokens").contains(tokenLimitParameter))
      throw new IllegalArgumentException("Unsupported completion token limit parameter");
  }
}
