package io.github.djyking.harness.integrations.opsagent;

import io.github.djyking.harness.core.Contracts.ExecutionContext;
import java.net.URI;

/**
 * Trusted host bridge: resolve an authenticated OpsAgent identity for this actor and run, then
 * return its current audience-specific Authorization value. The host must verify its own binding;
 * never turn caller-supplied Harness permissions into OpsAgent roles. This callback is not
 * persisted.
 */
@FunctionalInterface
public interface OpsAgentAuthorization {
  String authorization(URI endpoint, String audience, ExecutionContext context);
}
