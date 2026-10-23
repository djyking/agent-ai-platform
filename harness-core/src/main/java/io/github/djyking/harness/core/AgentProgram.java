package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Contracts.*;
import java.util.*;

/** Default bounded decision strategy; all external effects are commands executed by Harness. */
public final class AgentProgram implements Program {
  public record AgentSpec(ModelProfile profile, List<Message> messages, int maxTurns) {
    public AgentSpec {
      Objects.requireNonNull(profile);
      messages = List.copyOf(messages);
      if (messages.isEmpty() || maxTurns < 1 || maxTurns > 100)
        throw new IllegalArgumentException("Invalid agent spec");
    }
  }

  public Action next(RunState run) {
    return step(run, "agent", Json.convert(run.definition.spec(), AgentSpec.class));
  }

  public static Action step(RunState run, String namespace, AgentSpec spec) {
    return step(
        run,
        namespace,
        spec,
        run.tools.stream().map(ToolDescriptor::key).collect(java.util.stream.Collectors.toSet()));
  }

  public static Action step(
      RunState run, String namespace, AgentSpec spec, Set<String> allowedKeys) {
    ObjectNode agents = run.memory.withObject("/agents");
    if (!agents.has(namespace))
      agents.set(
          namespace,
          Json.object()
              .put("turn", 0)
              .put("phase", "READY")
              .set("messages", Json.tree(spec.messages())));
    ObjectNode state = (ObjectNode) agents.get(namespace);
    List<ToolDescriptor> tools =
        run.tools.stream().filter(t -> allowedKeys.contains(t.key())).toList();
    List<Message> messages =
        new ArrayList<>(Arrays.asList(Json.convert(state.path("messages"), Message[].class)));
    int turn = state.path("turn").asInt();
    String modelId = namespace + ":turn:" + turn + ":model";
    if ("MODEL".equals(state.path("phase").asText())) {
      StepResult result = run.results.get(modelId);
      if (result == null)
        return new ModelAction(
            modelId, namespace, Json.convert(state.path("request"), ModelRequest.class));
      if (!"MODEL".equals(result.kind()) || result.error())
        return new FailAction("MODEL_RESULT_INVALID");
      ModelResponse response = Json.convert(result.value(), ModelResponse.class);
      if (!"assistant".equals(response.message().role()))
        return new FailAction("MODEL_RESULT_INVALID");
      if (response.finishReason() == FinishReason.FINAL) {
        if (!response.message().toolCalls().isEmpty()
            || response.message().content() == null
            || response.message().content().isBlank()) return new FailAction("MODEL_FINAL_INVALID");
        return new CompleteAction(Json.object().put("content", response.message().content()));
      }
      if (response.finishReason() != FinishReason.TOOL_CALLS
          || response.message().toolCalls().isEmpty()
          || response.message().toolCalls().size() > 8)
        return new FailAction("MODEL_RESULT_NOT_ACTIONABLE");
      Set<String> ids = new HashSet<>();
      for (ToolCall call : response.message().toolCalls()) {
        if (call.id().isBlank()
            || !ids.add(call.id())
            || tools.stream().noneMatch(t -> t.modelName().equals(call.name())))
          return new FailAction("MODEL_TOOL_NOT_GRANTED_OR_DUPLICATE");
      }
      messages.add(response.message());
      state.set("messages", Json.tree(messages));
      state.set("calls", Json.tree(response.message().toolCalls()));
      state.put("index", 0).put("phase", "TOOLS");
      return new ContinueAction();
    }
    if ("TOOLS".equals(state.path("phase").asText())) {
      ToolCall[] calls = Json.convert(state.path("calls"), ToolCall[].class);
      int index = state.path("index").asInt();
      if (index < calls.length) {
        ToolCall call = calls[index];
        String id = namespace + ":turn:" + turn + ":tool:" + index;
        StepResult result = run.results.get(id);
        if (result == null) {
          ToolDescriptor tool =
              tools.stream()
                  .filter(t -> t.modelName().equals(call.name()))
                  .findFirst()
                  .orElseThrow(
                      () -> new InvocationException(FailureKind.DENIED, "TOOL_NOT_GRANTED"));
          return new ToolAction(id, namespace, tool.key(), call.arguments());
        }
        messages.add(
            Message.tool(
                call.id(), Json.write(Map.of("error", result.error(), "result", result.value()))));
        state.set("messages", Json.tree(messages));
        state.put("index", index + 1);
        return new ContinueAction();
      }
      state.put("phase", "READY");
    }
    if (turn >= spec.maxTurns()) return new FailAction("AGENT_TURN_LIMIT");
    turn++;
    modelId = namespace + ":turn:" + turn + ":model";
    ModelRequest request =
        new ModelRequest(
            modelId, spec.profile(), ContextWindow.fit(messages, tools, spec.profile()), tools);
    state.put("turn", turn).put("phase", "MODEL");
    state.set("request", Json.tree(request));
    return new ModelAction(modelId, namespace, request);
  }
}
