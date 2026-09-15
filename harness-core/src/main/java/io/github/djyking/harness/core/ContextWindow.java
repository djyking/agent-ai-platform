package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fits a request copy while retaining the durable history and complete native tool-call groups. */
public final class ContextWindow {
  private ContextWindow() {}

  public static long reserve(ModelRequest request) {
    return Json.write(request).getBytes(StandardCharsets.UTF_8).length
        + 128L
        + request.profile().maxOutputTokens();
  }

  public static List<Message> fit(
      List<Message> history, List<ToolDescriptor> tools, ModelProfile profile) {
    List<Message> result = new ArrayList<>(history);
    for (int i = 0; i < result.size(); i++) {
      Message m = result.get(i);
      if ("tool".equals(m.role()) && m.content() != null && m.content().length() > 8000)
        result.set(
            i,
            Message.tool(
                m.toolCallId(),
                m.content().substring(0, 7900) + "\n[truncated; full result in run store]"));
    }
    while (reserve(new ModelRequest("context-estimate", profile, result, tools))
        > profile.contextTokens()) {
      int start = -1, end = -1;
      for (int i = 1; i < result.size(); i++) {
        if ("assistant".equals(result.get(i).role())) {
          if (start < 0) start = i;
          else {
            end = i;
            break;
          }
        }
      }
      if (start < 0 || end < 0)
        throw new InvocationException(FailureKind.INVALID, "CONTEXT_CAPACITY_EXCEEDED");
      result.subList(start, end).clear();
    }
    validatePairing(result);
    return List.copyOf(result);
  }

  public static void validatePairing(List<Message> messages) {
    Set<String> pending = new LinkedHashSet<>();
    for (Message message : messages) {
      if ("tool".equals(message.role())) {
        if (!pending.remove(message.toolCallId()))
          throw new InvocationException(FailureKind.INVALID, "UNPAIRED_TOOL_RESULT");
      } else {
        if (!pending.isEmpty())
          throw new InvocationException(FailureKind.INVALID, "MISSING_TOOL_RESULT");
        if (!message.toolCalls().isEmpty() && !"assistant".equals(message.role()))
          throw new InvocationException(FailureKind.INVALID, "INVALID_TOOL_CALL_ROLE");
        for (ToolCall call : message.toolCalls())
          if (!pending.add(call.id()))
            throw new InvocationException(FailureKind.INVALID, "DUPLICATE_TOOL_CALL_ID");
      }
    }
    if (!pending.isEmpty())
      throw new InvocationException(FailureKind.INVALID, "MISSING_TOOL_RESULT");
  }
}
