package io.github.djyking.harness.capabilities.workflow;

import static io.github.djyking.harness.core.Contracts.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import io.github.djyking.harness.core.AgentProgram;
import io.github.djyking.harness.core.ContextWindow;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import java.util.*;

/**
 * Pure workflow state machine. It returns effects to the shared runtime and owns no executor or
 * ledger.
 */
public final class WorkflowProgram implements Program {
  public static final String PROGRAM = "workflow";
  private static final String MEMORY = "workflow";

  public record Spec(WorkflowDefinition workflow, ModelProfile model, JsonNode inputs) {
    public Spec {
      Objects.requireNonNull(workflow, "workflow");
      boolean needsModel =
          workflow.nodes().values().stream()
              .anyMatch(
                  node ->
                      node instanceof WorkflowDefinition.Model
                          || node instanceof WorkflowDefinition.Agent);
      if (needsModel) Objects.requireNonNull(model, "model");
      if (inputs == null || !inputs.isObject())
        throw new IllegalArgumentException("Workflow inputs must be an object");
      inputs = inputs.deepCopy();
    }

    public ProgramDefinition definition() {
      return new ProgramDefinition(workflow.id(), workflow.version(), PROGRAM, Json.tree(this));
    }
  }

  @Override
  public Action next(RunState run) {
    try {
      return advance(run);
    } catch (IllegalArgumentException ex) {
      return new FailAction("INVALID_WORKFLOW_CONFIGURATION_OR_STATE");
    }
  }

  private Action advance(RunState run) {
    Spec spec = Json.convert(run.definition.spec(), Spec.class);
    if (!spec.workflow().id().equals(run.definition.id())
        || !spec.workflow().version().equals(run.definition.version()))
      return new FailAction("WORKFLOW_IDENTITY_MISMATCH");
    String digest = Json.hash(spec);
    ObjectNode state;
    if (!run.memory.has(MEMORY)) {
      state = run.memory.putObject(MEMORY);
      state
          .put("schemaVersion", 1)
          .put("fingerprint", digest)
          .put("pc", spec.workflow().start())
          .put("transitions", 0);
      state.set("variables", spec.inputs().deepCopy());
      state.putObject("promptSnapshots");
    } else {
      if (!(run.memory.get(MEMORY) instanceof ObjectNode object))
        return new FailAction("INVALID_WORKFLOW_STATE");
      state = object;
      if (!state.path("schemaVersion").isIntegralNumber()
          || !state.path("schemaVersion").canConvertToInt()
          || state.path("schemaVersion").intValue() != 1)
        return new FailAction("UNSUPPORTED_WORKFLOW_STATE_VERSION");
      if (!digest.equals(state.path("fingerprint").asText()))
        return new FailAction("WORKFLOW_CONFIGURATION_CHANGED");
    }
    if (!(state.get("variables") instanceof ObjectNode variables)
        || !state.path("transitions").isIntegralNumber()
        || !state.get("transitions").canConvertToInt())
      return new FailAction("INVALID_WORKFLOW_STATE");
    int transitions = state.get("transitions").intValue();
    if (transitions < 0) return new FailAction("INVALID_WORKFLOW_STATE");
    String pc = state.path("pc").asText();
    WorkflowDefinition.Node node = spec.workflow().nodes().get(pc);
    if (node == null) return new FailAction("INVALID_WORKFLOW_POSITION");
    if (node instanceof WorkflowDefinition.End end) {
      return variables.has(end.output())
          ? new CompleteAction(variables.get(end.output()).deepCopy())
          : new FailAction("WORKFLOW_OUTPUT_MISSING");
    }
    if (transitions >= spec.workflow().maxTransitions())
      return new FailAction("WORKFLOW_TRANSITION_LIMIT");
    String actionId = "workflow:" + transitions + ":" + pc;

    if (node instanceof WorkflowDefinition.Condition condition) {
      if (!variables.has(condition.variable())) return new FailAction("WORKFLOW_VARIABLE_MISSING");
      return move(
          state,
          transitions,
          variables.get(condition.variable()).equals(Json.read(condition.equalsJson()))
              ? condition.whenTrue()
              : condition.whenFalse());
    }
    if (node instanceof WorkflowDefinition.Tool tool) {
      StepResult result = run.results.get(actionId);
      if (result != null)
        return consume(state, variables, transitions, tool.output(), tool.next(), result, "TOOL");
      ObjectNode arguments = (ObjectNode) Json.read(tool.argumentsJson());
      tool.argumentBindings()
          .forEach(
              (argument, variable) ->
                  arguments.set(argument, required(variables, variable).deepCopy()));
      return new ToolAction(actionId, pc, tool.toolName(), arguments);
    }
    if (node instanceof WorkflowDefinition.Human human) {
      StepResult result = run.results.get(actionId);
      if (result != null)
        return consume(
            state, variables, transitions, human.output(), human.next(), result, "HUMAN");
      return new WaitAction(actionId, pc, human.message());
    }
    if (node instanceof WorkflowDefinition.Model model) {
      StepResult result = run.results.get(actionId);
      if (result != null) {
        if (!"MODEL".equals(result.kind())
            || result.error()
            || result.value() == null
            || result.value().isNull()) return new FailAction("WORKFLOW_MODEL_FAILED");
        ModelResponse response = Json.convert(result.value(), ModelResponse.class);
        if (!"assistant".equals(response.message().role())
            || response.finishReason() != FinishReason.FINAL
            || !response.message().toolCalls().isEmpty())
          return new FailAction("WORKFLOW_MODEL_DID_NOT_FINISH");
        variables.set(model.output(), Json.tree(response.message().content()));
        return move(state, transitions, model.next());
      }
      PromptTemplate.RenderedPrompt prompt =
          prompt(state, actionId, model.prompt(), model.inputBindings(), variables);
      ModelRequest request =
          new ModelRequest(
              actionId,
              spec.model(),
              ContextWindow.fit(messages(prompt), List.of(), spec.model()),
              List.of());
      return new ModelAction(actionId, pc, request);
    }
    if (node instanceof WorkflowDefinition.Agent agent) {
      PromptTemplate.RenderedPrompt prompt =
          prompt(state, actionId, agent.prompt(), agent.inputBindings(), variables);
      Action action =
          AgentProgram.step(
              run,
              actionId + ":agent",
              new AgentProgram.AgentSpec(spec.model(), messages(prompt), agent.maxTurns()),
              agent.allowedTools());
      if (action instanceof CompleteAction complete) {
        variables.set(agent.output(), complete.output().deepCopy());
        return move(state, transitions, agent.next());
      }
      return action;
    }
    return new FailAction("UNSUPPORTED_WORKFLOW_NODE");
  }

  private static Action consume(
      ObjectNode state,
      ObjectNode variables,
      int transitions,
      String output,
      String next,
      StepResult result,
      String kind) {
    if (!kind.equals(result.kind()) || result.error())
      return new FailAction("WORKFLOW_NODE_FAILED");
    variables.set(output, result.value() == null ? Json.tree(null) : result.value().deepCopy());
    return move(state, transitions, next);
  }

  private static ContinueAction move(ObjectNode state, int transitions, String next) {
    state.put("pc", next).put("transitions", transitions + 1);
    return new ContinueAction();
  }

  private static PromptTemplate.RenderedPrompt prompt(
      ObjectNode state,
      String actionId,
      PromptTemplate template,
      Map<String, String> bindings,
      ObjectNode variables) {
    if (!(state.get("promptSnapshots") instanceof ObjectNode snapshots))
      throw new IllegalArgumentException("Missing prompt snapshots");
    if (snapshots.has(actionId))
      return Json.convert(snapshots.get(actionId), PromptTemplate.RenderedPrompt.class);
    Map<String, Object> inputs = new TreeMap<>();
    bindings.forEach(
        (name, variable) ->
            inputs.put(name, Json.convert(required(variables, variable), Object.class)));
    PromptTemplate.RenderedPrompt rendered = template.render(inputs);
    snapshots.set(actionId, Json.tree(rendered));
    return rendered;
  }

  private static List<Message> messages(PromptTemplate.RenderedPrompt prompt) {
    return List.of(Message.text("system", prompt.system()), Message.text("user", prompt.user()));
  }

  private static JsonNode required(ObjectNode values, String key) {
    if (!values.has(key)) throw new IllegalArgumentException("Missing workflow variable");
    return values.get(key);
  }
}
