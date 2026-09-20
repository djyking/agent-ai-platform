package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Json;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SchemaMigrationTest {
  @TempDir Path directory;

  @Test
  void offlineMigrationIsIdempotentAndRuntimeRepositoriesValidateWithoutCreatingWorkers()
      throws Exception {
    try (var f = new PlatformTestSupport()) {
      Path file = directory.resolve("deployment.json");
      Files.writeString(file, Json.write(f.config));
      var env =
          new MockEnvironment()
              .withProperty("HARNESS_CONFIG", file.toString())
              .withProperty("HARNESS_MIGRATION_OFFLINE_ACK", "drained-isolated-cluster")
              .withProperty("HARNESS_MIGRATION_JDBC_URL", f.dataSource.getURL())
              .withProperty("HARNESS_MIGRATION_JDBC_USER", "platform-test")
              .withProperty("HARNESS_MIGRATION_JDBC_PASSWORD", "explicitly-synthetic-test-password")
              .withProperty("HARNESS_IDENTITY_PROVIDER", "opsagent");
      SecretProvider secrets = reference -> "synthetic-migration-only-secret";
      SchemaMigration.run(env, secrets);
      SchemaMigration.run(env, secrets);
      f.store.validateSchema();
      var runtimeRepository = new PlatformRepository(f.store, false);
      runtimeRepository.initialize(f.config);
      new CatalogRepository(runtimeRepository).initialize();
      new CapabilityRepository(runtimeRepository).initialize();
      new StudioRepository(runtimeRepository).initialize();
      new PlatformTrace(f.dataSource, false);
      new OperationsService(f.config, runtimeRepository).validateSchema();
      var governance =
          new ExecutionGovernance(
              runtimeRepository,
              ExecutionGovernanceConfig.defaults(),
              f.config,
              GovernanceSettings.bindingFingerprint(f.config, secrets, "opsagent"));
      governance.initialize();
      assertEquals(0, governance.overview("project-a").path("workers").size());
      assertThrows(
          IllegalStateException.class,
          () ->
              new ExecutionGovernance(
                      runtimeRepository,
                      ExecutionGovernanceConfig.defaults(),
                      f.config,
                      GovernanceSettings.bindingFingerprint(f.config, secrets, "local-test"))
                  .initialize());
    }
  }

  @Test
  void activeOldRunsRejectMigrationBeforeNewTablesAreCreated() throws Exception {
    try (var f = new PlatformTestSupport()) {
      f.create("read", "preflight-active");
      Path file = directory.resolve("active.json");
      Files.writeString(file, Json.write(f.config));
      var env =
          new MockEnvironment()
              .withProperty("HARNESS_CONFIG", file.toString())
              .withProperty("HARNESS_MIGRATION_OFFLINE_ACK", "drained-isolated-cluster")
              .withProperty("HARNESS_MIGRATION_JDBC_URL", f.dataSource.getURL())
              .withProperty("HARNESS_MIGRATION_JDBC_USER", "platform-test")
              .withProperty(
                  "HARNESS_MIGRATION_JDBC_PASSWORD", "explicitly-synthetic-test-password");
      assertThrows(
          IllegalStateException.class,
          () -> SchemaMigration.run(env, r -> "synthetic-migration-only-secret"));
      try (var c = f.dataSource.getConnection();
          var s = c.createStatement();
          var rows =
              s.executeQuery(
                  "SELECT COUNT(*) FROM harness_schema WHERE component IN"
                      + " ('catalog','operations','execution-governance')")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
      }
    }
  }

  @Test
  void bindingChangesWhenSecretAssignmentsChange() {
    try (var f = new PlatformTestSupport()) {
      Map<String, String> original =
          Map.of("env:APP_A", "first-test-material", "env:APP_B", "second-test-material");
      Map<String, String> swapped =
          Map.of("env:APP_A", "second-test-material", "env:APP_B", "first-test-material");
      assertNotEquals(
          GovernanceSettings.bindingFingerprint(
              f.config, r -> original.getOrDefault(r, "signing-material"), "opsagent"),
          GovernanceSettings.bindingFingerprint(
              f.config, r -> swapped.getOrDefault(r, "signing-material"), "opsagent"));
    }
  }
}
