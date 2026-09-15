package io.github.djyking.harness.core.testing;

import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.util.*;
import java.util.function.Function;

/** Deterministic recorded model. Suitable for tests/examples, not an external model provider. */
public final class ScriptedModel implements ModelGateway {
  private final Deque<Function<ModelRequest, ModelResponse>> steps = new ArrayDeque<>();
  private final Map<String, String> digests = new HashMap<>();
  private final Map<String, ModelResponse> responses = new HashMap<>();
  private final List<ModelRequest> requests = new ArrayList<>();

  public ScriptedModel(ModelResponse... responses) {
    for (ModelResponse response : responses) then(request -> response);
  }

  public synchronized ScriptedModel then(Function<ModelRequest, ModelResponse> step) {
    steps.add(step);
    return this;
  }

  public synchronized ModelResponse invoke(ModelRequest request, ExecutionContext context) {
    String digest = Json.hash(request);
    if (responses.containsKey(request.invocationId())) {
      if (!digest.equals(digests.get(request.invocationId())))
        throw new IllegalStateException("Replay request changed");
      return Json.copy(responses.get(request.invocationId()), ModelResponse.class);
    }
    if (steps.isEmpty()) throw new IllegalStateException("Scripted model exhausted");
    requests.add(Json.copy(request, ModelRequest.class));
    ModelResponse response = steps.remove().apply(request);
    digests.put(request.invocationId(), digest);
    responses.put(request.invocationId(), Json.copy(response, ModelResponse.class));
    return response;
  }

  public synchronized List<ModelRequest> requests() {
    return requests.stream().map(r -> Json.copy(r, ModelRequest.class)).toList();
  }

  public static ModelResponse answer(String text) {
    return new ModelResponse(
        Message.text("assistant", text), FinishReason.FINAL, new Usage(20, 10, true));
  }

  public static ModelResponse calls(ToolCall... calls) {
    return new ModelResponse(
        new Message("assistant", null, List.of(calls), null),
        FinishReason.TOOL_CALLS,
        new Usage(20, 10, true));
  }
}
