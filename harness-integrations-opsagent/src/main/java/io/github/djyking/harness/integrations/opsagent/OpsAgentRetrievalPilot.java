package io.github.djyking.harness.integrations.opsagent;

import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.Contracts.ProgramDefinition;
import io.github.djyking.harness.core.Json;
import java.util.Map;

/**
 * A bounded, model-free pilot; registration and credential resolution remain host responsibilities.
 */
public final class OpsAgentRetrievalPilot {
  private OpsAgentRetrievalPilot() {}

  public static ProgramDefinition definition(String query, int topK) {
    var arguments = Json.object().put("query", query).put("topK", topK);
    OpsAgentRagTool.validateArguments(arguments);
    var workflow =
        new WorkflowDefinition(
            "opsagent-authorized-retrieval",
            "1",
            "search",
            3,
            Map.of(
                "search",
                new WorkflowDefinition.Tool(
                    OpsAgentRagTool.KEY, Json.write(arguments), Map.of(), "evidence", "done"),
                "done",
                new WorkflowDefinition.End("evidence")));
    return new WorkflowProgram.Spec(workflow, null, Json.object()).definition();
  }
}
