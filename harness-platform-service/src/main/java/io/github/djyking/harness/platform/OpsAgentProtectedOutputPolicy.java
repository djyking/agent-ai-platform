package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.integrations.opsagent.OpsAgentProjectionClient;
import io.github.djyking.harness.integrations.opsagent.OpsAgentRagTool;
import java.net.URI;
import java.time.Duration;
import java.util.function.BiPredicate;

/** Ops-specific provenance and current domain ACL are confined to this compatibility adapter. */
public final class OpsAgentProtectedOutputPolicy implements ProtectedOutputPolicy {
  private final IdentityProvider identity;
  private final BiPredicate<String, JsonNode> projection;

  public OpsAgentProtectedOutputPolicy(IdentityProvider identity, URI ragOrigin) {
    this(
        identity,
        ragOrigin == null
            ? (authorization, citations) -> false
            : new OpsAgentProjectionClient(ragOrigin, Duration.ofSeconds(5))::allowed);
  }

  OpsAgentProtectedOutputPolicy(
      IdentityProvider identity, BiPredicate<String, JsonNode> projection) {
    this.identity = identity;
    this.projection = projection;
  }

  static boolean exactRetrieval(PlatformRepository.Owned owned) {
    var workflow = owned.release().workflow();
    return owned.release().model() == null
        && workflow.nodes().size() == 2
        && workflow.nodes().get(workflow.start()) instanceof WorkflowDefinition.Tool tool
        && tool.toolName().equals(OpsAgentRagTool.KEY)
        && workflow.nodes().get(tool.next()) instanceof WorkflowDefinition.End end
        && end.output().equals(tool.output());
  }

  @Override
  public boolean canRead(
      IdentityProvider.Principal reader, PlatformRepository.Owned owned, JsonNode output) {
    if (!exactRetrieval(owned)
        || !reader.application().equals(owned.application())
        || !reader.project().equals(owned.project())
        || !reader.subject().equals(owned.subject())
        || !reader.permits("runs:output:read")
        || !reader.permits(OpsAgentRagTool.PERMISSION)
        || !reader.permits("tool:" + OpsAgentRagTool.KEY)
        || output == null
        || !output.isObject()
        || output.size() != 2
        || !output.path("evidence").isTextual()
        || !output.path("citations").isArray()) return false;
    try {
      String authorization =
          identity.toolAuthorization(
              owned.application(), owned.project(), owned.delegation(), owned.runId());
      return projection.test(authorization, output.path("citations"));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
