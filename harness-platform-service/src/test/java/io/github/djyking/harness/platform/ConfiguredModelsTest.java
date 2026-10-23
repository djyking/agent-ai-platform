package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.adapters.model.*;
import io.github.djyking.harness.core.Json;
import org.junit.jupiter.api.Test;

class ConfiguredModelsTest {
  @Test
  void approvedBindingSelectsAdapterWithoutCallingProviderOrResolvingSecretDuringConstruction() {
    var binding =
        Json.object()
            .put("kind", "deepseek")
            .put("provider", "primary")
            .put("endpoint", "https://model.invalid/chat/completions")
            .put("secretRef", "env:MODEL_SECRET");
    SecretProvider noCall =
        ref -> {
          fail("Constructor must not send or load a credential");
          return "";
        };
    assertInstanceOf(DeepSeekChatModel.class, ConfiguredModels.create(binding, noCall));
    binding.put("kind", "openai-compatible");
    assertInstanceOf(ChatCompletionsModel.class, ConfiguredModels.create(binding, noCall));
    binding.put("tokenLimitParameter", "max_tokens");
    assertInstanceOf(ChatCompletionsModel.class, ConfiguredModels.create(binding, noCall));
    binding.put("tokenLimitParameter", "invalid");
    assertThrows(IllegalArgumentException.class, () -> ConfiguredModels.create(binding, noCall));
    binding.remove("tokenLimitParameter");
    binding.put("kind", "arbitrary-plugin");
    assertThrows(IllegalArgumentException.class, () -> ConfiguredModels.create(binding, noCall));
  }
}
