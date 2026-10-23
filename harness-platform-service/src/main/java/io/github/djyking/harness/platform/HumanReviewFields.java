package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Declared Human review context, bound to the exact immutable workflow checkpoint. */
final class HumanReviewFields {
  record View(ArrayNode fields, boolean complete) {}

  private HumanReviewFields() {}

  static View project(
      PlatformRepository.Owned owned,
      RunState run,
      boolean reviewGrant,
      Predicate<JsonNode> mayDisclose) {
    var fields = Json.MAPPER.createArrayNode();
    boolean optIn =
        owned.release().workflow().nodes().values().stream()
            .anyMatch(
                node -> node instanceof WorkflowDefinition.Human human && human.reviewContext());
    // Absence of the explicit capability retains pre-phase-six Human semantics and hashes.
    if (!optIn) return new View(fields, true);
    try {
      var definition = run.definition;
      var spec = Json.convert(definition.spec(), WorkflowProgram.Spec.class);
      JsonNode state = run.memory.path("workflow");
      if (run.payloadArchived
          || !definition.program().equals(WorkflowProgram.PROGRAM)
          || !definition.id().equals(spec.workflow().id())
          || !definition.version().equals(spec.workflow().version())
          || !Json.hash(spec.workflow()).equals(Json.hash(owned.release().workflow()))
          || !Json.hash(spec.model()).equals(Json.hash(owned.release().model()))
          || !state.path("schemaVersion").isInt()
          || state.path("schemaVersion").asInt(-1) != 1
          || !state.path("fingerprint").asText().equals(Json.hash(spec))
          || !state.path("transitions").isIntegralNumber()
          || !state.path("transitions").canConvertToInt()
          || state.path("transitions").asInt() < 0
          || !state.path("variables").isObject()
          || run.pending == null
          || !"HUMAN".equals(run.pending.kind)
          || run.approval == null) return new View(fields, false);
      String pc = state.path("pc").asText();
      var node = owned.release().workflow().nodes().get(pc);
      if (!(node instanceof WorkflowDefinition.Human human)
          || !pc.equals(run.pending.nodeId)
          || !Objects.equals(human.message(), run.pending.prompt)
          || !Json.hash(human.message()).equals(run.pending.contractDigest)
          || !("workflow:" + state.path("transitions").asInt() + ":" + pc).equals(run.pending.id)
          || !run.pending.id.equals(run.approval.invocationId())
          || !Objects.equals(run.pending.approvalDigest, run.approval.digest()))
        return new View(fields, false);
      if (!human.reviewContext()) return new View(fields, true);
      List<String> names = owned.release().reviewFields();
      if (names.isEmpty()
          || names.size() > 64
          || new HashSet<>(names).size() != names.size()
          || names.stream().anyMatch(name -> !name.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
        return new View(fields, false);
      boolean complete = reviewGrant;
      int totalBytes = 0;
      for (String name : names) {
        JsonNode value = state.path("variables").get(name);
        String text = value == null ? "" : value.isTextual() ? value.asText() : Json.write(value);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        boolean show =
            reviewGrant
                && value != null
                && !value.isNull()
                && text.length() <= 16384
                && bytes <= 16384
                && totalBytes + bytes <= 65536;
        if (show) {
          try {
            show = mayDisclose.test(value);
          } catch (RuntimeException unavailable) {
            show = false;
          }
        }
        if (show) totalBytes += bytes;
        else complete = false;
        fields.add(
            Json.object()
                .put("name", name)
                .put("value", show ? text : "[redacted]")
                .put("masked", !show));
      }
      return new View(fields, complete);
    } catch (RuntimeException invalidCheckpoint) {
      return new View(Json.MAPPER.createArrayNode(), false);
    }
  }
}
