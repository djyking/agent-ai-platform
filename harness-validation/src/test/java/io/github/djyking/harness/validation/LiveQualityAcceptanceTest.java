package io.github.djyking.harness.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.ScriptedModel;
import io.github.djyking.harness.evals.QualityBaseline;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveQualityAcceptanceTest {
  @Test
  void planFixesGoldAndModelConfigurationWithoutCredentialsOrNetwork() throws Exception {
    var settings = new LiveSettings(Map.of("HARNESS_MODEL_ENDPOINT", "https://api.deepseek.com/chat/completions",
        "HARNESS_MODEL_NAME", "explicit-test-model", "HARNESS_QUALITY_CALL_LIMIT", "10"));
    var plan = LiveQualityAcceptance.plan(settings);
    assertEquals(0, plan.path("networkCalls").asInt());
    assertEquals(10, plan.path("plannedModelCalls").asInt());
    assertEquals(1.0, plan.path("thresholds").path("allCasePassRate").asDouble());
    assertEquals(64, plan.path("datasetDigest").asText().length());
  }

  @Test
  void actualHarnessRetrievalFeedsOneAnswerGenerationWithScopedContext() throws Exception {
    var dataset = QualityBaseline.builtin();
    var scenario = dataset.cases().get(0);
    String answer = "{\"answerable\":true,\"answer\":\"Above 2% for 5 minutes; traffic weight 0%.\",\"citations\":[\"ops-amber-current\"]}";
    var profile = new ModelProfile("quality-test", "test", "offline", 16000, 512, 10000, Json.object());
    RunState result = LiveQualityAcceptance.execute(dataset, scenario, profile, (request, context) -> {
      assertTrue(request.messages().get(1).content().contains("ops-amber-current"));
      assertFalse(request.messages().get(1).content().contains("SYNTHETIC_ENG_RELEASE_6C42"));
      return ScriptedModel.answer(answer);
    });
    assertEquals(RunStatus.COMPLETED, result.status);
    assertEquals(1, result.modelCalls);
    assertEquals(1, result.toolCalls);
    assertEquals(answer, result.results.get("answer").value().path("message").path("content").asText());
  }
}
