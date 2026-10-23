package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PlatformRunProjectionTest {
  @Test
  void failedOuterCreationRollsBackRunOwnershipAndReceiptAndAllowsTheSameKey() {
    try (var f = new PlatformTestSupport()) {
      var provisionalId = new AtomicReference<String>();
      var failure = new IllegalStateException("synthetic outer creation failure");
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  f.repository.transaction(
                      c -> {
                        String id = f.id(f.create("read", "projection-create-rollback"));
                        provisionalId.set(id);
                        assertEquals("project-a", ownership(f, id).project());
                        assertEquals(1, scalar(c, "SELECT COUNT(*) FROM harness_runs"));
                        assertEquals(1, scalar(c, "SELECT COUNT(*) FROM platform_commands"));
                        throw failure;
                      })));
      f.repository.transaction(
          c -> {
            for (String table :
                new String[] {
                  "harness_runs",
                  "harness_run_index",
                  "harness_run_events",
                  "platform_runs",
                  "platform_commands",
                  "platform_command_audit"
                }) assertEquals(0, scalar(c, "SELECT COUNT(*) FROM " + table), table);
            return null;
          });
      String committed = f.id(f.create("read", "projection-create-rollback"));
      assertNotEquals(provisionalId.get(), committed);
      assertEquals(committed, f.id(f.create("read", "projection-create-rollback")));
      assertEquals(1, f.count("harness_runs"));
      assertEquals(1, f.count("platform_commands"));
      assertEquals("project-a", ownership(f, committed).project());
    }
  }

  @Test
  void executionPreservesDimensionsAndOuterFailureRollsBackCoreUsageAndSummary() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected-model", "projection-execution"));
      Ownership original = ownership(f, id);
      RunState completed = f.drive(id);
      assertEquals(RunStatus.COMPLETED, completed.status);
      assertTrue(completed.chargedTokens > 0);
      assertEquals(1, completed.modelCalls);
      assertEquals(1, completed.toolCalls);
      assertEquals(original, ownership(f, id));
      assertIndexMatches(f, completed);
      var operations = new OperationsService(f.config, f.repository);
      JsonNode summary = f.repository.transaction(c -> operations.summary(c, "project-a", null));
      assertEquals(1, summary.path("statusCounts").path("COMPLETED").asLong());
      assertEquals(0, summary.path("usage").path("reservedTokens").asLong());
      assertEquals(completed.chargedTokens, summary.path("usage").path("chargedTokens").asLong());
      assertEquals(1, summary.path("usage").path("modelCalls").asLong());
      assertEquals(1, summary.path("usage").path("toolCalls").asLong());
      int events = f.store.events(id, 0, 100).size();
      var failure = new IllegalStateException("synthetic outer usage failure");
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  f.store.inTransaction(
                      c -> {
                        RunState provisional =
                            f.store.update(
                                id,
                                completed.revision,
                                run -> {
                                  run.chargedTokens += 17;
                                  run.modelCalls++;
                                  run.toolCalls++;
                                  return run;
                                },
                                new RunEvent(
                                    "SYNTHETIC_USAGE_UPDATE", null, null, Instant.now(), Map.of()));
                        assertIndexMatches(f, provisional);
                        assertEquals(
                            completed.chargedTokens + 17,
                            operations
                                .summary(c, "project-a", null)
                                .path("usage")
                                .path("chargedTokens")
                                .asLong());
                        throw failure;
                      })));
      assertEquals(Json.tree(completed), Json.tree(f.store.get(id)));
      assertEquals(events, f.store.events(id, 0, 100).size());
      assertEquals(original, ownership(f, id));
      assertIndexMatches(f, completed);
      JsonNode after = f.repository.transaction(c -> operations.summary(c, "project-a", null));
      assertEquals(summary.path("statusCounts"), after.path("statusCounts"));
      assertEquals(summary.path("usage"), after.path("usage"));
    }
  }

  @Test
  void versionOneMigrationBackfillsOwnershipWithoutChangingCoreStateOrSdkRuns() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("read", "projection-migrate"));
      RunState sdk = f.store.get(id).copy();
      sdk.id = UUID.randomUUID().toString();
      sdk.creationKey = "sdk-only-projection-migration";
      RunState sdkBefore = f.store.create(sdk);
      Ownership expected = ownership(f, id);
      RunState before = f.drive(id);
      int events = f.store.events(id, 0, 100).size();
      f.repository.transaction(
          c -> {
            execute(c, "UPDATE harness_schema SET schema_version=1 WHERE component='platform'");
            execute(
                c, "DELETE FROM harness_schema WHERE component=?", PlatformRunProjection.COMPONENT);
            execute(c, "DROP INDEX harness_platform_usage_idx");
            execute(c, "DROP INDEX harness_platform_owner_usage_idx");
            for (String column : PlatformRunProjection.DIMENSIONS.split(","))
              execute(c, "ALTER TABLE harness_run_index DROP COLUMN " + column);
            return null;
          });
      assertThrows(RuntimeException.class, () -> f.repository.validateSchema(f.config));
      f.repository.initialize(f.config);
      assertDoesNotThrow(() -> f.repository.validateSchema(f.config));
      assertEquals(expected, ownership(f, id));
      assertEquals(Json.tree(before), Json.tree(f.store.get(id)));
      assertEquals(Json.tree(sdkBefore), Json.tree(f.store.get(sdk.id)));
      assertEquals(events, f.store.events(id, 0, 100).size());
      assertIndexMatches(f, before);
      assertEquals(new Ownership(null, null, null, null, null), ownership(f, sdk.id));
      f.repository.transaction(
          c -> {
            assertEquals(
                2,
                scalar(c, "SELECT schema_version FROM harness_schema WHERE component='platform'"));
            assertEquals(
                1,
                scalar(c, "SELECT schema_version FROM harness_schema WHERE component='run-index'"));
            return null;
          });
      // The added columns remain optional for SDK hosts creating Runs after platform migration.
      sdk.id = UUID.randomUUID().toString();
      sdk.creationKey = "sdk-only-after-projection-migration";
      f.store.create(sdk);
      assertEquals(new Ownership(null, null, null, null, null), ownership(f, sdk.id));
      assertDoesNotThrow(() -> f.repository.validateSchema(f.config));
    }
  }

  @Test
  void validationRejectsMissingOrInconsistentOwnershipAndMissingIndexesWithoutRepair() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("read", "projection-read-only-validation"));
      Ownership original = ownership(f, id);
      f.repository.transaction(
          c -> {
            execute(c, "UPDATE harness_run_index SET platform_subject_id=NULL WHERE run_id=?", id);
            return null;
          });
      assertThrows(IllegalStateException.class, () -> f.repository.validateSchema(f.config));
      assertNull(ownership(f, id).subject());
      f.repository.transaction(
          c -> {
            execute(
                c,
                "UPDATE harness_run_index SET platform_subject_id='different-subject' WHERE"
                    + " run_id=?",
                id);
            return null;
          });
      assertThrows(IllegalStateException.class, () -> f.repository.validateSchema(f.config));
      assertEquals("different-subject", ownership(f, id).subject());
      f.repository.transaction(
          c -> {
            execute(
                c,
                "UPDATE harness_run_index SET platform_subject_id=? WHERE run_id=?",
                original.subject(),
                id);
            execute(c, "DROP INDEX harness_platform_owner_usage_idx");
            return null;
          });
      assertThrows(IllegalStateException.class, () -> f.repository.validateSchema(f.config));
      assertEquals(original, ownership(f, id));
      f.repository.transaction(
          c -> {
            try (var indexes =
                c.getMetaData()
                    .getIndexInfo(c.getCatalog(), null, "HARNESS_RUN_INDEX", false, false)) {
              while (indexes.next())
                assertNotEquals(
                    "HARNESS_PLATFORM_OWNER_USAGE_IDX", indexes.getString("INDEX_NAME"));
            }
            return null;
          });
    }
  }

  private record Ownership(
      String project, String application, String subject, Long createdAt, Long reservedTokens) {}

  private static Ownership ownership(PlatformTestSupport f, String id) {
    return f.repository.transaction(
        c -> {
          try (var query =
                  prepare(
                      c,
                      "SELECT "
                          + PlatformRunProjection.DIMENSIONS
                          + " FROM harness_run_index WHERE run_id=?",
                      id);
              var rows = query.executeQuery()) {
            assertTrue(rows.next());
            return new Ownership(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getObject(4, Long.class),
                rows.getObject(5, Long.class));
          }
        });
  }

  private static void assertIndexMatches(PlatformTestSupport f, RunState run) {
    f.repository.transaction(
        c -> {
          try (var query =
                  prepare(
                      c,
                      "SELECT run_status,charged_tokens,model_calls,tool_calls FROM"
                          + " harness_run_index WHERE run_id=?",
                      run.id);
              var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(run.status.name(), rows.getString(1));
            assertEquals(run.chargedTokens, rows.getLong(2));
            assertEquals(run.modelCalls, rows.getInt(3));
            assertEquals(run.toolCalls, rows.getInt(4));
          }
          return null;
        });
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var query = c.prepareStatement(sql);
        var rows = query.executeQuery()) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
