package io.github.djyking.harness.adapters.model;

import io.github.djyking.harness.core.Contracts.*;
import java.util.Objects;

/** Compatible DeepSeek adapter: thinking remains explicitly disabled and max_tokens unchanged. */
public final class DeepSeekChatModel implements ModelGateway {
  private final ChatCompletionsModel delegate;

  public DeepSeekChatModel(DeepSeekConfig config) {
    Objects.requireNonNull(config, "config");
    delegate =
        new ChatCompletionsModel(
            new ChatCompletionsConfig(
                config.endpoint(),
                config.connectTimeout(),
                config.requestTimeout(),
                config.maxResponseBytes(),
                config.headers(),
                "max_tokens",
                true));
  }

  @Override
  public ModelResponse invoke(ModelRequest request, ExecutionContext context) {
    return delegate.invoke(request, context);
  }
}
