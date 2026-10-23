package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.Json;
import java.nio.file.Path;
import java.util.*;
import org.springframework.core.env.Environment;

/** Identical policy and credential bindings are required for every process in a cluster. */
final class GovernanceSettings {
  private GovernanceSettings() {}

  static ExecutionGovernanceConfig load(Environment environment) {
    String path = environment.getProperty("HARNESS_GOVERNANCE_CONFIG", "");
    return path.isBlank()
        ? ExecutionGovernanceConfig.defaults()
        : ExecutionGovernanceConfig.load(Path.of(path));
  }

  static String bindingFingerprint(Deployment config, SecretProvider secrets, String identity) {
    Map<String, String> bindings = new TreeMap<>();
    bindings.put("identity", identity);
    Set<String> references = new TreeSet<>(config.applicationSecrets().values());
    references.add(config.signingSecret());
    for (var model : config.models())
      if (model.has("secretRef")) references.add(model.path("secretRef").asText());
    for (var tool : config.tools())
      if (tool.has("secretRef")) references.add(tool.path("secretRef").asText());
    for (String reference : references)
      bindings.put("secret:" + reference, Json.hash(secrets.resolve(reference)));
    return Json.hash(bindings);
  }
}
