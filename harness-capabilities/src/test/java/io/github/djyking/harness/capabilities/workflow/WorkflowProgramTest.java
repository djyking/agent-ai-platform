package io.github.djyking.harness.capabilities.workflow;

import static io.github.djyking.harness.capabilities.workflow.WorkflowDefinition.*;
import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowProgramTest {
  private final WorkflowProgram program = new WorkflowProgram();
  private final ModelProfile model =
      new ModelProfile("test", "scripted", "test", 4096, 256, 1000, Json.object());

  @Test
  void restoredToolResultAdvancesWithoutAnotherInvocationThenWaitsAndBranches() {
    var workflow =
        new WorkflowDefinition(
            "change",
            "1",
            "lookup",
            10,
            Map.of(
                "lookup",
                new Tool(
                    "internal/read", "{}", Map.of("resource", "target"), "lookupResult", "review"),
                "review",
                new Human("Confirm proposed action", "approved", "branch"),
                "branch",
                new Condition("approved", "{\"input\":\"yes\"}", "success", "declined"),
                "success",
                new End("lookupResult"),
                "declined",
                new End("approved")));
    RunState run = run(workflow, "{\"target\":\"sandbox\"}");
    var planned = assertInstanceOf(ToolAction.class, program.next(run));
    assertEquals("sandbox", planned.arguments().path("resource").asText());
    // This is the same durable result slot the harness commits before the next program tick.
    run.results.put(
        planned.id(), new StepResult("TOOL", Json.read("{\"status\":\"ready\"}"), false));
    run = run.copy();
    assertInstanceOf(ContinueAction.class, program.next(run));
    var wait = assertInstanceOf(WaitAction.class, program.next(run));
    RunState restored = run.copy();
    assertEquals(wait, program.next(restored));
    restored.results.put(
        wait.id(), new StepResult("HUMAN", Json.read("{\"input\":\"yes\"}"), false));
    assertInstanceOf(ContinueAction.class, program.next(restored));
    assertInstanceOf(ContinueAction.class, program.next(restored));
    assertEquals(
        "ready",
        assertInstanceOf(CompleteAction.class, program.next(restored))
            .output()
            .path("status")
            .asText());
  }

  @Test
  void refusesConfigurationChangesAndCapsCycles() {
    var workflow =
        new WorkflowDefinition(
            "cycle",
            "1",
            "again",
            2,
            Map.of("again", new Condition("flag", "true", "again", "again")));
    RunState run = run(workflow, "{\"flag\":true}");
    assertInstanceOf(ContinueAction.class, program.next(run));
    assertInstanceOf(ContinueAction.class, program.next(run));
    assertEquals(
        "WORKFLOW_TRANSITION_LIMIT", assertInstanceOf(FailAction.class, program.next(run)).code());

    RunState changed = run(workflow, "{\"flag\":true}");
    program.next(changed);
    changed.definition.spec().withObject("/inputs").put("flag", false);
    assertEquals(
        "WORKFLOW_CONFIGURATION_CHANGED",
        assertInstanceOf(FailAction.class, program.next(changed)).code());
  }

  @Test
  void modelNodePinsPromptInputsAndRejectsIncompleteModelResult() {
    var prompt =
        new PromptTemplate(
            "answer",
            "1",
            "Answer",
            "{{question}}",
            Map.of("question", PromptTemplate.VariableType.STRING));
    var workflow =
        new WorkflowDefinition(
            "qa",
            "1",
            "answer",
            2,
            Map.of(
                "answer",
                new Model(prompt, Map.of("question", "question"), "answer", "done"),
                "done",
                new End("answer")));
    RunState run = run(workflow, "{\"question\":\"Why?\"}");
    var action = assertInstanceOf(ModelAction.class, program.next(run));
    assertEquals("Why?", action.request().messages().get(1).content());
    assertTrue(action.request().tools().isEmpty());
    assertTrue(
        run.memory.path("workflow").path("promptSnapshots").path(action.id()).has("fingerprint"));
    run = run.copy();
    run.results.put(
        action.id(),
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    Message.text("assistant", "Partial"), FinishReason.LENGTH, Usage.unknown())),
            false));
    assertEquals(
        "WORKFLOW_MODEL_DID_NOT_FINISH",
        assertInstanceOf(FailAction.class, program.next(run)).code());
    run.results.put(
        action.id(),
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    Message.text("user", "Unexpected role"), FinishReason.FINAL, Usage.unknown())),
            false));
    assertEquals(
        "WORKFLOW_MODEL_DID_NOT_FINISH",
        assertInstanceOf(FailAction.class, program.next(run)).code());
    run.results.put(action.id(), new StepResult("MODEL", null, false));
    assertEquals(
        "WORKFLOW_MODEL_FAILED", assertInstanceOf(FailAction.class, program.next(run)).code());
  }

  @Test
  void corruptedWorkflowProgressIsRejectedBeforeEmittingAnotherToolAction() {
    var workflow =
        new WorkflowDefinition(
            "state-check",
            "1",
            "call",
            3,
            Map.of(
                "call",
                new Tool("read", "{}", Map.of(), "result", "done"),
                "done",
                new End("result")));
    RunState run = run(workflow, "{}");
    assertInstanceOf(ToolAction.class, program.next(run));
    run.memory.withObject("/workflow").put("schemaVersion", "1");
    assertEquals(
        "UNSUPPORTED_WORKFLOW_STATE_VERSION",
        assertInstanceOf(FailAction.class, program.next(run)).code());
    run.memory.withObject("/workflow").put("schemaVersion", 1).put("transitions", 4_294_967_296L);
    assertEquals(
        "INVALID_WORKFLOW_STATE", assertInstanceOf(FailAction.class, program.next(run)).code());
  }

  @Test
  void invalidGraphAndMissingInputFailBeforeExternalExecution() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowDefinition(
                "broken",
                "1",
                "first",
                3,
                Map.of("first", new Human("hello", "response", "missing"))));
    var workflow =
        new WorkflowDefinition(
            "missing",
            "1",
            "call",
            3,
            Map.of(
                "call",
                new Tool("read", "{}", Map.of("resource", "unknown"), "result", "done"),
                "done",
                new End("result")));
    assertInstanceOf(FailAction.class, program.next(run(workflow, "{}")));
  }

  @Test
  void nestedAgentUsesSharedResultsAndOnlyItsAllowedToolSubset() {
    var prompt =
        new PromptTemplate("lookup", "1", "Use available tools", "Find the guide", Map.of());
    var workflow =
        new WorkflowDefinition(
            "nested",
            "1",
            "agent",
            3,
            Map.of(
                "agent",
                new Agent(prompt, Map.of(), Set.of("lookup"), 2, "answer", "done"),
                "done",
                new End("answer")));
    RunState run = run(workflow, "{}");
    run.tools = List.of(descriptor("lookup"), descriptor("change"));
    var first = assertInstanceOf(ModelAction.class, program.next(run));
    assertEquals(
        List.of("lookup"), first.request().tools().stream().map(ToolDescriptor::key).toList());
    run.results.put(
        first.id(),
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    new Message(
                        "assistant",
                        null,
                        List.of(new ToolCall("provider-call-7", "lookup", Json.object())),
                        null),
                    FinishReason.TOOL_CALLS,
                    new Usage(20, 10, true))),
            false));
    assertInstanceOf(ContinueAction.class, program.next(run));
    var tool = assertInstanceOf(ToolAction.class, program.next(run));
    assertEquals("lookup", tool.toolKey());
    run.results.put(tool.id(), new StepResult("TOOL", Json.read("{\"guide\":\"found\"}"), false));
    run = run.copy();
    assertInstanceOf(ContinueAction.class, program.next(run));
    var second = assertInstanceOf(ModelAction.class, program.next(run));
    assertEquals(
        "provider-call-7",
        second.request().messages().get(second.request().messages().size() - 1).toolCallId());
    run.results.put(
        second.id(),
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    Message.text("assistant", "Found"),
                    FinishReason.FINAL,
                    new Usage(30, 10, true))),
            false));
    assertInstanceOf(ContinueAction.class, program.next(run));
    assertEquals(
        "Found",
        assertInstanceOf(CompleteAction.class, program.next(run))
            .output()
            .path("content")
            .asText());
  }

  private ToolDescriptor descriptor(String key) {
    return new ToolDescriptor(
        key,
        key,
        "test",
        "internal",
        key,
        "1",
        Json.read("{\"type\":\"object\"}"),
        ToolPolicy.readOnlyPolicy());
  }

  private RunState run(WorkflowDefinition definition, String input) {
    RunState run = new RunState();
    run.id = "test-run";
    run.definition = new WorkflowProgram.Spec(definition, model, Json.read(input)).definition();
    return run;
  }
}
