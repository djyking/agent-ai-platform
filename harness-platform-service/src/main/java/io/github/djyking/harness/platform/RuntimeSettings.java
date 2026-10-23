package io.github.djyking.harness.platform;

import java.util.Set;
import org.springframework.core.env.Environment;

/** Deployment safety choices are explicit and never inferred from a reachable database. */
public record RuntimeSettings(String environment, boolean bootstrapSchema) {
  public static RuntimeSettings from(Environment env) {
    String environment = env.getProperty("HARNESS_ENVIRONMENT", "development");
    if (!Set.of("development", "staging", "production").contains(environment))
      throw new IllegalArgumentException("Unsupported deployment environment");
    boolean deployed = !environment.equals("development");
    String mode = env.getProperty("HARNESS_SCHEMA_MODE", deployed ? "validate" : "bootstrap");
    if (!Set.of("bootstrap", "validate").contains(mode))
      throw new IllegalArgumentException("Unsupported schema mode");
    if (deployed && mode.equals("bootstrap"))
      throw new IllegalArgumentException("Deployed instances require explicit offline migration");
    if (deployed && env.getProperty("HARNESS_IDENTITY_PROVIDER", "opsagent").equals("local-test"))
      throw new IllegalArgumentException("Local test identity is prohibited outside development");
    if (deployed && env.getProperty("HARNESS_GOVERNANCE_CONFIG", "").isBlank())
      throw new IllegalArgumentException("Deployed instances require explicit governance policy");
    String forwarding = env.getProperty("server.forward-headers-strategy", "none");
    if (!forwarding.equalsIgnoreCase("none")
        && (!forwarding.equalsIgnoreCase("native")
            || env.getProperty("server.tomcat.remoteip.internal-proxies", "").isBlank()))
      throw new IllegalArgumentException(
          "Forwarded headers require explicit trusted proxy matching");
    return new RuntimeSettings(environment, mode.equals("bootstrap"));
  }
}
