package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.model.*;
import io.github.djyking.harness.core.Contracts.ModelGateway;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Creates a model from operator-approved deployment bindings, never browser-provided URLs. */
public final class ConfiguredModels {
  private ConfiguredModels() {}

  public static ModelGateway create(JsonNode binding, SecretProvider secrets) {
    if (binding == null
        || !binding.isObject()
        || !binding.path("endpoint").isTextual()
        || !binding.path("secretRef").isTextual())
      throw new IllegalArgumentException("Invalid configured model binding");
    URI endpoint = URI.create(binding.path("endpoint").asText());
    String reference = binding.path("secretRef").asText();
    var headers =
        (io.github.djyking.harness.adapters.http.HeaderProvider)
            url -> Map.of("Authorization", "Bearer " + secrets.resolve(reference));
    return switch (binding.path("kind").asText()) {
      case "deepseek" ->
          new DeepSeekChatModel(
              new DeepSeekConfig(
                  endpoint, Duration.ofSeconds(5), Duration.ofSeconds(60), 1024 * 1024, headers));
      case "openai-compatible" -> {
        String tokenParameter = binding.path("tokenLimitParameter").asText("max_completion_tokens");
        if (binding.has("tokenLimitParameter") && !binding.path("tokenLimitParameter").isTextual())
          throw new IllegalArgumentException("Invalid token limit parameter");
        yield new ChatCompletionsModel(
            new ChatCompletionsConfig(
                endpoint,
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                1024 * 1024,
                headers,
                tokenParameter,
                false));
      }
      default -> throw new IllegalArgumentException("Unsupported configured model adapter");
    };
  }
}
