package io.github.djyking.harness.platform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.nio.file.*;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Privileged offline action; the operator must independently verify remote termination first. */
final class PermitResolution {
  private PermitResolution() {}

  static void run(Path file, Environment env) throws Exception {
    RuntimeSettings.from(env);
    if (Files.size(file) > 4096) throw new IllegalArgumentException("Evidence reference too large");
    com.fasterxml.jackson.databind.JsonNode evidence;
    try (var input = Files.newInputStream(file)) {
      evidence = ApiJson.read(input);
    }
    CatalogValidator.fields(
        evidence,
        Set.of("permitId", "evidenceRef", "verifier", "verifiedAt", "externalCallStopped"),
        Set.of("permitId", "evidenceRef", "verifier", "verifiedAt", "externalCallStopped"));
    ApiJson.uuid(evidence.path("permitId").asText());
    ApiJson.identifier(evidence.path("evidenceRef").asText());
    ApiJson.identifier(evidence.path("verifier").asText());
    if (!evidence.path("externalCallStopped").isBoolean()
        || !evidence.path("externalCallStopped").asBoolean()
        || !evidence.path("verifiedAt").isIntegralNumber()
        || evidence.path("verifiedAt").asLong() <= 0
        || evidence.path("verifiedAt").asLong() > System.currentTimeMillis())
      throw new IllegalArgumentException("Verified termination evidence required");
    Deployment deployment = Deployment.load(Path.of(required(env, "HARNESS_CONFIG")));
    var jdbc = new HikariConfig();
    jdbc.setJdbcUrl(required(env, "HARNESS_JDBC_URL"));
    jdbc.setUsername(required(env, "HARNESS_JDBC_USER"));
    jdbc.setPassword(required(env, "HARNESS_JDBC_PASSWORD"));
    jdbc.setMaximumPoolSize(1);
    try (var ds = new HikariDataSource(jdbc)) {
      var store = new JdbcRunStore(ds);
      store.validateSchema();
      var repository = new PlatformRepository(store, false);
      repository.initialize(deployment);
      var governance =
          new ExecutionGovernance(
              repository,
              GovernanceSettings.load(env),
              deployment,
              GovernanceSettings.bindingFingerprint(
                  deployment,
                  SecretProvider.environment(),
                  env.getProperty("HARNESS_IDENTITY_PROVIDER", "opsagent")));
      governance.initialize();
      governance.resolveUnknownPermit(evidence.path("permitId").asText(), Json.write(evidence));
      System.out.println(
          Json.write(
              Json.object()
                  .put("permitId", evidence.path("permitId").asText())
                  .put("status", "RELEASED")
                  .put("runChanged", false)));
    }
  }

  private static String required(Environment env, String key) {
    String value = env.getProperty(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Required configuration missing: " + key);
    return value;
  }
}
