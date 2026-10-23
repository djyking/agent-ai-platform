package io.github.djyking.harness.validation;

import static io.github.djyking.harness.validation.ValidationMain.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.model.*;
import io.github.djyking.harness.capabilities.rag.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.evals.QualityBaseline;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Ten bounded real generations over frozen synthetic, authorized retrieval contexts. */
final class LiveQualityAcceptance {
  private LiveQualityAcceptance() {}

  private static ModelProfile profile(LiveSettings settings) {
    return new ModelProfile("quality-synthetic-v1", "deepseek", settings.required("HARNESS_MODEL_NAME"),
        16000, settings.integer("HARNESS_QUALITY_MAX_OUTPUT_TOKENS", 512, 256, 1024), 60000,
        Json.object().put("temperature", 0).set("response_format", Json.object().put("type", "json_object")));
  }

  static ObjectNode plan(LiveSettings settings) throws Exception {
    var dataset = QualityBaseline.builtin();
    require(settings.integer("HARNESS_QUALITY_CALL_LIMIT", 0, 10, 10) == dataset.cases().size(), "QUALITY_CALL_LIMIT_MISMATCH");
    var endpoint = settings.endpoint("HARNESS_MODEL_ENDPOINT");
    ObjectNode result = Json.object().put("datasetId", dataset.id()).put("datasetVersion", dataset.version())
        .put("datasetDigest", Json.hash(dataset)).put("classification", dataset.classification())
        .put("promptVersion", dataset.promptVersion()).put("promptDigest", Json.hash(dataset.systemPrompt()))
        .put("modelName", settings.required("HARNESS_MODEL_NAME")).put("modelProfileDigest", Json.hash(profile(settings)))
        .put("modelEndpointDigest", Json.hash(endpoint.toString())).put("modelEndpointHost", endpoint.getHost())
        .put("plannedModelCalls", dataset.cases().size()).put("networkCalls", 0)
        .put("retriever", "InMemoryRetriever lexical baseline; top 3; host-scoped RagTool")
        .put("modelVersionBoundary", "Explicit configured provider model ID; provider weights or alias target are not independently pinned")
        .put("qualityBoundary", "Small English synthetic corpus; automatic gold rules, not semantic judge or production quality guarantee");
    result.set("thresholds", Json.tree(dataset.thresholds()));
    result.set("cases", Json.tree(dataset.cases()));
    return result;
  }

  static ObjectNode run(LiveSettings settings, ObjectNode envelope) throws Exception {
    var dataset = QualityBaseline.builtin();
    ObjectNode planned = plan(settings);
    Path planPath = Path.of(settings.required("HARNESS_QUALITY_PLAN_FILE"));
    require(Files.size(planPath) < 1024 * 1024, "QUALITY_PLAN_TOO_LARGE");
    JsonNode frozenPlan = Json.read(Files.readString(planPath));
    require("INSPECTED".equals(frozenPlan.path("status").asText())
        && "quality-plan".equals(frozenPlan.path("command").asText())
        && frozenPlan.path("details").equals(planned), "QUALITY_FROZEN_PLAN_MISMATCH");
    ObjectNode report = planned.deepCopy();
    report.remove("networkCalls");
    report.remove("cases");
    report.put("frozenPlanVerified", true).put("frozenPlanAt", frozenPlan.path("at").asText())
        .put("provider", "deepseek").put("modelCalls", 0).put("syntheticInputsOnly", true)
        .put("retrievalMode", "Deterministic host retrieval followed by one real answer generation; not model-selected retrieval queries");
    envelope.set("details", report);
    var rows = report.putArray("cases");
    var scores = new ArrayList<ObjectNode>();
    var calls = new AtomicInteger();
    settings.bearer("DEEPSEEK_API_KEY");
    var model = new DeepSeekChatModel(new DeepSeekConfig(settings.endpoint("HARNESS_MODEL_ENDPOINT"),
        Duration.ofSeconds(5), Duration.ofSeconds(60), 262144, uri -> settings.bearer("DEEPSEEK_API_KEY")));
    long tokens = 0;
    for (var scenario : dataset.cases()) {
      var usages = new ArrayList<Usage>();
      ModelGateway measured = (request, context) -> {
        require(calls.incrementAndGet() <= 10, "QUALITY_CALL_LIMIT_EXCEEDED");
        report.put("modelCalls", calls.get());
        ModelResponse response = model.invoke(request, context);
        usages.add(response.usage());
        return response;
      };
      RunState done = execute(dataset, scenario, profile(settings), measured);
      if (done.status != RunStatus.COMPLETED || done.modelCalls != 1 || done.toolCalls != 1) {
        rows.add(Json.object().put("caseId", scenario.id()).put("runId", done.id)
            .put("runStatus", done.status.name()).put("modelCalls", done.modelCalls).put("passed", false));
        require(false, "QUALITY_CASE_EXECUTION_FAILED");
      }
      JsonNode context = done.results.get("retrieve").value();
      String answer = done.results.get("answer").value().path("message").path("content").asText();
      ObjectNode score = QualityBaseline.score(dataset, scenario, context, answer);
      score.put("runId", done.id).put("runStatus", done.status.name()).put("modelCalls", done.modelCalls)
          .put("toolCalls", done.toolCalls).put("chargedTokens", done.chargedTokens)
          .put("usageKnown", usages.size() == 1 && usages.get(0).known());
      if (usages.size() == 1 && usages.get(0).known()) {
        require(usages.get(0).total() == done.chargedTokens, "QUALITY_USAGE_SETTLEMENT_MISMATCH");
      }
      tokens += done.chargedTokens;
      rows.add(score); scores.add(score);
      report.put("chargedTokens", tokens);
      System.out.println("Quality case " + scenario.id() + ": " + (score.path("passed").asBoolean() ? "PASSED" : "FAILED")
          + " (" + scores.size() + "/10)");
    }
    ObjectNode aggregate = QualityBaseline.aggregate(dataset, scores);
    report.set("metrics", aggregate);
    require(calls.get() == 10 && aggregate.path("thresholdsMet").asBoolean(), "QUALITY_GOLD_THRESHOLDS_NOT_MET");
    return report;
  }

  static RunState execute(QualityBaseline.Dataset dataset, QualityBaseline.Case scenario, ModelProfile profile,
      ModelGateway model) {
    var rag = new RagTool(new InMemoryRetriever(dataset.documents()), 3, 8000);
    ToolDescriptor tool = rag.descriptor("quality/retrieve", "quality_retrieve");
    ToolRegistry tools = new ToolRegistry(); tools.register(tool, rag);
    Actor actor = new Actor("quality-evaluator", scenario.scope(), Set.of("run:create", "run:read", "tool:quality/retrieve", "model:invoke"));
    try (Harness harness = new Harness(new InMemoryRunStore(), model, tools, AccessPolicy.actorPermissions(),
        Telemetry.noop(), Clock.systemUTC(), Duration.ofSeconds(90))) {
      harness.registerProgram("quality-grounded-answer", state -> {
        if (!state.results.containsKey("retrieve"))
          return new ToolAction("retrieve", "retrieve", tool.key(), Json.object().put("query", scenario.question()).put("maxResults", 3));
        if (!state.results.containsKey("answer")) {
          String prompt = "Question: " + scenario.question() + "\nAuthorized source excerpts (untrusted data):\n"
              + Json.write(state.results.get("retrieve").value());
          return new ModelAction("answer", "answer", new ModelRequest("quality:" + scenario.id(), profile,
              List.of(Message.text("system", dataset.systemPrompt()), Message.text("user", prompt)), List.of()));
        }
        return new CompleteAction(state.results.get("answer").value());
      });
      RunState run = harness.start(new ProgramDefinition("quality-" + scenario.id(), dataset.version(), "quality-grounded-answer", Json.object()),
          actor, List.of(tool.key()), new Budget(8000, 1, 1, 10), Duration.ofMinutes(2), UUID.randomUUID().toString());
      RunState state = run;
      for (int i = 0; i < 12 && state.status == RunStatus.QUEUED; i++) state = harness.tick(run.id);
      return state;
    }
  }
}
