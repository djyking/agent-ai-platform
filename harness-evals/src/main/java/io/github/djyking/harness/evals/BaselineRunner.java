package io.github.djyking.harness.evals;

import static io.github.djyking.harness.core.Contracts.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.capabilities.rag.*;
import io.github.djyking.harness.capabilities.workflow.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.testing.ScriptedModel;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;

/** Data-driven engineering regression baseline; scripted model outcomes are not quality scores. */
public final class BaselineRunner {
  private static final String RAG_KEY = "engineering/knowledge";
  private static final String WRITE_KEY = "engineering/release-note";
  private static final ModelProfile PROFILE =
      new ModelProfile(
          "eval", "scripted", "engineering-fixture-v1", 32000, 256, 5000, Json.object());

  public record Dataset(
      int schemaVersion,
      String id,
      String classification,
      List<Retrieval.Document> documents,
      List<Scenario> scenarios) {
    public Dataset {
      if (schemaVersion != 1
          || id == null
          || !id.matches("[a-zA-Z0-9_.-]{1,80}")
          || !"SYNTHETIC".equals(classification))
        throw new IllegalArgumentException(
            "Only version 1 synthetic engineering datasets supported");
      documents = List.copyOf(documents);
      scenarios = List.copyOf(scenarios);
      if (documents.size() > 100 || scenarios.isEmpty() || scenarios.size() > 100)
        throw new IllegalArgumentException("Dataset size out of bounds");
      Set<String> ids = new HashSet<>();
      for (Scenario scenario : scenarios)
        if (!ids.add(scenario.id())) throw new IllegalArgumentException("Duplicate scenario id");
    }
  }

  public record Scenario(
      String id,
      String kind,
      String project,
      String question,
      String toolName,
      JsonNode arguments,
      String decision,
      boolean faultyRetriever,
      JsonNode expectations) {
    public Scenario {
      if (id == null
          || !id.matches("[a-zA-Z0-9_.-]{1,80}")
          || project == null
          || project.isBlank()
          || !Set.of("knowledge", "write", "resume-budget").contains(kind)
          || expectations == null
          || !expectations.isObject()
          || expectations.isEmpty()) throw new IllegalArgumentException("Invalid scenario");
      if (!"write".equals(kind) && (question == null || toolName == null || arguments == null))
        throw new IllegalArgumentException("Knowledge request required");
      if ("write".equals(kind) && !Set.of("approve", "reject", "unknown").contains(decision))
        throw new IllegalArgumentException("Invalid synthetic approval decision");
      arguments = arguments == null ? Json.object() : arguments.deepCopy();
      expectations = expectations.deepCopy();
    }
  }

  public record Check(String name, JsonNode expected, JsonNode actual, boolean passed) {}

  public record CaseReport(
      String id,
      String kind,
      boolean passed,
      long elapsedMillis,
      JsonNode observations,
      List<Check> checks,
      String failureCode) {}

  public record Report(
      int schemaVersion,
      String datasetId,
      String datasetFingerprint,
      String harnessVersion,
      String mode,
      String dataClassification,
      Instant generatedAt,
      boolean passed,
      int passedCases,
      int totalCases,
      List<CaseReport> cases,
      List<String> limitations) {}

  public static Dataset builtin() throws IOException {
    try (InputStream stream =
        BaselineRunner.class.getResourceAsStream("/datasets/engineering-v1.json")) {
      if (stream == null) throw new FileNotFoundException("Built-in baseline missing");
      return Json.convert(Json.MAPPER.readTree(stream), Dataset.class);
    }
  }

  public static Dataset load(Path file) throws IOException {
    if (Files.size(file) > 2_000_000) throw new IllegalArgumentException("Dataset too large");
    return Json.convert(Json.read(Files.readString(file)), Dataset.class);
  }

  public Report run(Dataset source, Path dataDirectory) throws IOException {
    Dataset dataset = Json.copy(source, Dataset.class);
    Files.createDirectories(dataDirectory);
    Path execution = Files.createTempDirectory(dataDirectory, dataset.id() + "-");
    List<CaseReport> reports = new ArrayList<>();
    for (Scenario scenario : dataset.scenarios()) {
      long started = System.nanoTime();
      ObjectNode actual = Json.object();
      String failure = null;
      try {
        Path database = execution.resolve(scenario.id()).resolve("state");
        Files.createDirectories(database.getParent());
        actual =
            switch (scenario.kind()) {
              case "knowledge" -> knowledge(dataset, scenario, database, false);
              case "resume-budget" -> knowledge(dataset, scenario, database, true);
              case "write" -> write(scenario, database);
              default -> throw new IllegalArgumentException("Unknown scenario kind");
            };
      } catch (Exception problem) {
        // Reports may become CI artifacts: never expose arbitrary adapter exception payloads.
        failure = "SCENARIO_EXCEPTION:" + problem.getClass().getSimpleName();
      }
      List<Check> checks = new ArrayList<>();
      for (var fields = scenario.expectations().fields(); fields.hasNext(); ) {
        var field = fields.next();
        JsonNode observation = actual.path(field.getKey());
        checks.add(
            new Check(
                field.getKey(),
                field.getValue().deepCopy(),
                observation.deepCopy(),
                actual.has(field.getKey())
                    && Json.hash(field.getValue()).equals(Json.hash(observation))));
      }
      reports.add(
          new CaseReport(
              scenario.id(),
              scenario.kind(),
              failure == null && checks.stream().allMatch(Check::passed),
              Duration.ofNanos(System.nanoTime() - started).toMillis(),
              actual,
              List.copyOf(checks),
              failure));
    }
    int passed = (int) reports.stream().filter(CaseReport::passed).count();
    return new Report(
        1,
        dataset.id(),
        Json.hash(dataset),
        "0.1.0-SNAPSHOT",
        "SCRIPTED_ENGINEERING_REGRESSION",
        dataset.classification(),
        Instant.now(),
        passed == reports.size(),
        passed,
        reports.size(),
        List.copyOf(reports),
        List.of(
            "Scripted responses verify runtime contracts, not real model reasoning or answer"
                + " quality.",
            "Usage comes from synthetic reported token counts; it is not billed usage or real"
                + " cost.",
            "Elapsed time includes local H2 I/O and JVM warmup; it is not provider latency or an"
                + " SLA.",
            "SQL reopen verifies local H2 persistence; this baseline does not certify MySQL or"
                + " remote services.",
            "All release-note writes and approvals are synthetic scenario actions, never business"
                + " writes."));
  }

  private ObjectNode knowledge(Dataset dataset, Scenario scenario, Path database, boolean reopen) {
    Actor actor = actor(scenario.project());
    AtomicInteger handlerCalls = new AtomicInteger();
    ToolRegistry tools = new ToolRegistry();
    Retrieval.Retriever retriever =
        scenario.faultyRetriever()
            ? request -> dataset.documents().stream().map(d -> new Retrieval.Hit(d, 1)).toList()
            : new InMemoryRetriever(dataset.documents());
    RagTool rag = new RagTool(retriever, 4, 2000);
    tools.register(
        rag.descriptor(RAG_KEY, "engineering_knowledge"),
        (tool, arguments, context) -> {
          handlerCalls.incrementAndGet();
          return rag.invoke(tool, arguments, context);
        });
    ScriptedModel model =
        new ScriptedModel(
            ScriptedModel.calls(
                new ToolCall("knowledge-call", scenario.toolName(), scenario.arguments())));
    model.then(
        request -> {
          Message toolReply =
              request.messages().stream()
                  .filter(m -> "tool".equals(m.role()))
                  .reduce((first, second) -> second)
                  .orElseThrow();
          if (!"knowledge-call".equals(toolReply.toolCallId()))
            throw new IllegalStateException("Native tool call identity was not retained");
          JsonNode retrieved = Json.read(toolReply.content()).path("result");
          return ScriptedModel.answer(answer(retrieved));
        });
    Budget budget = new Budget(20000, reopen ? 1 : 2, 1, 70);
    ProgramDefinition definition =
        new ProgramDefinition(
            "engineering-qa",
            "1",
            "agent",
            Json.tree(
                new AgentProgram.AgentSpec(
                    PROFILE,
                    List.of(
                        Message.text(
                            "system",
                            "Use the engineering knowledge tool. Cite only returned sources."),
                        Message.text("user", scenario.question())),
                    2)));
    RunState state;
    RunState before = null;
    try (Harness harness = harness(database, model, tools)) {
      state =
          harness.start(
              definition, actor, List.of(RAG_KEY), budget, Duration.ofMinutes(3), scenario.id());
      if (reopen) {
        harness.tick(state.id); // prepared model intent
        harness.tick(state.id); // model result, known token settlement
        before = harness.pause(state.id, actor);
      } else state = drive(harness, state.id, actor);
    }
    boolean preserved = false;
    int postRestartModelCalls = 0;
    if (reopen) {
      ScriptedModel restarted = new ScriptedModel(ScriptedModel.answer("must not be called"));
      try (Harness harness = harness(database, restarted, tools)) {
        RunState loaded = harness.get(state.id, actor);
        preserved =
            loaded.chargedTokens == before.chargedTokens
                && loaded.modelCalls == before.modelCalls
                && loaded.budget.equals(before.budget)
                && loaded.deadline.equals(before.deadline)
                && loaded.definition.equals(before.definition);
        harness.resume(state.id, actor);
        state = drive(harness, state.id, actor);
        postRestartModelCalls = restarted.requests().size();
      }
    }
    ObjectNode actual = observations(state).put("handlerCalls", handlerCalls.get());
    var ids = actual.putArray("citationIds");
    var uris = actual.putArray("citationUris");
    JsonNode retrieval = Json.object();
    for (StepResult result : state.results.values()) {
      if ("TOOL".equals(result.kind()) && result.value().has("citations")) {
        retrieval = result.value();
        for (JsonNode citation : retrieval.path("citations")) {
          ids.add(citation.path("documentId").asText());
          uris.add(citation.path("uri").asText());
        }
      }
    }
    actual.put(
        "answerUsesReturnedContext",
        state.output != null && answer(retrieval).equals(state.output.path("content").asText()));
    actual.put(
        "crossScopeContentAbsent", !Json.write(state).contains("FORBIDDEN_SYNTHETIC_FINANCE"));
    if (reopen)
      actual
          .put("budgetPreservedAcrossSqlReopen", preserved)
          .put("postRestartModelCalls", postRestartModelCalls);
    return actual;
  }

  private ObjectNode write(Scenario scenario, Path database) {
    Actor actor = actor(scenario.project());
    AtomicInteger effects = new AtomicInteger();
    ToolRegistry tools = new ToolRegistry();
    tools.register(
        new ToolDescriptor(
            WRITE_KEY,
            "release_note",
            "Synthetic release-note fixture",
            "internal",
            "release-note",
            "1",
            Json.read(
                "{\"type\":\"object\",\"properties\":{\"release\":{\"type\":\"string\"}},\"required\":[\"release\"],\"additionalProperties\":false}"),
            ToolPolicy.approvedWrite()),
        (tool, arguments, context) -> {
          effects.incrementAndGet();
          if ("unknown".equals(scenario.decision()))
            throw new InvocationException(FailureKind.UNKNOWN, "SYNTHETIC_RECEIPT_LOST");
          return new ToolResult(arguments, false, "synthetic-release-receipt");
        });
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "release-note-fixture",
            "1",
            "write",
            4,
            Map.of(
                "write",
                new WorkflowDefinition.Tool(
                    WRITE_KEY, "{\"release\":\"synthetic-1\"}", Map.of(), "receipt", "end"),
                "end",
                new WorkflowDefinition.End("receipt")));
    RunState state;
    boolean waiting, tamperRejected = false, resumeRejected = false;
    try (Harness harness = harness(database, new ScriptedModel(), tools)) {
      state =
          harness.start(
              new WorkflowProgram.Spec(workflow, null, Json.object()).definition(),
              actor,
              List.of(WRITE_KEY),
              Budget.defaults(),
              Duration.ofMinutes(3),
              scenario.id());
      state = drive(harness, state.id, actor);
      waiting = state.status == RunStatus.WAITING_APPROVAL && effects.get() == 0;
      try {
        harness.decide(state.id, actor, "wrong-digest", true, null);
      } catch (RunStore.Conflict expected) {
        tamperRejected = true;
      }
      harness.decide(
          state.id,
          actor,
          state.approval.digest(),
          !"reject".equals(scenario.decision()),
          "Explicit synthetic dataset decision");
      state = drive(harness, state.id, actor);
    }
    // Recreate the runtime and store against the same file; terminal/unknown runs cannot replay
    // writes.
    int effectsBefore = effects.get();
    try (Harness harness = harness(database, new ScriptedModel(), tools)) {
      state = harness.get(state.id, actor);
      if (state.pending != null && state.pending.phase == InvocationPhase.UNKNOWN) {
        try {
          harness.resume(state.id, actor);
        } catch (RunStore.Conflict expected) {
          resumeRejected = true;
        }
      }
      for (int i = 0; i < 5; i++) state = harness.tick(state.id);
    }
    return observations(state)
        .put("effects", effects.get())
        .put("approvalBlockedDispatch", waiting)
        .put("tamperedApprovalRejected", tamperRejected)
        .put("reopenAndTicksDidNotRepeatWrite", effectsBefore == effects.get())
        .put(
            "unknownObserved",
            state.pending != null && state.pending.phase == InvocationPhase.UNKNOWN)
        .put("unknownResumeRejected", resumeRejected);
  }

  private static Harness harness(Path database, ModelGateway model, ToolRegistry tools) {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/') + ";MODE=MySQL");
    source.setUser("sa");
    JdbcRunStore store = new JdbcRunStore(source);
    store.initializeSchema();
    Harness harness = new Harness(store, model, tools);
    harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    return harness;
  }

  private static Actor actor(String project) {
    return new Actor(
        "synthetic-engineer",
        project,
        Set.of(
            "run:create",
            "run:read",
            "run:control",
            "model:invoke",
            "approval:decide",
            "tool:" + RAG_KEY,
            "tool:" + WRITE_KEY));
  }

  private static RunState drive(Harness harness, String id, Actor actor) {
    RunState state = harness.get(id, actor);
    for (int i = 0; i < 100 && state.status == RunStatus.QUEUED; i++) state = harness.tick(id);
    if (state.status == RunStatus.QUEUED)
      throw new IllegalStateException("Scenario tick bound exceeded");
    return state;
  }

  private static ObjectNode observations(RunState state) {
    return Json.object()
        .put("status", state.status.name())
        .put("stopReason", state.stopReason)
        .put("modelAttempts", state.modelCalls)
        .put("toolAttempts", state.toolCalls)
        .put("chargedTokens", state.chargedTokens)
        .put("steps", state.steps);
  }

  private static String answer(JsonNode retrieval) {
    return retrieval.path("citations").isEmpty()
        ? "No authorized source was found."
        : "Engineering source excerpts:\n" + retrieval.path("text").asText();
  }
}
