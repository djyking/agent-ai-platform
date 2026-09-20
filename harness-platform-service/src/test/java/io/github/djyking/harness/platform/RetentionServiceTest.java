package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

final class RetentionServiceTest {
  private static final String PRIVATE = "synthetic-private-retention-payload-19437";
  private static final long DAY = 86_400_000L;

  private RetentionArchive key() {
    return new RetentionArchive(Base64.getEncoder().encodeToString(new byte[32]));
  }

  private void initialize(PlatformTestSupport f) {
    new ExecutionGovernance(f.repository, ExecutionGovernanceConfig.defaults(), f.config)
        .initialize();
    new OperationsRepository(f.repository).initialize();
    new StudioRepository(f.repository).initialize();
    new PlatformTrace(f.dataSource);
  }

  private String old(PlatformTestSupport f, String key, int days) {
    String id = f.id(f.create("read", key));
    assertEquals(RunStatus.COMPLETED, f.drive(id).status);
    f.repository.transaction(
        c -> {
          RunState run = f.store.get(id);
          run.memory.put("private", PRIVATE);
          run.output = Json.tree(PRIVATE);
          execute(
              c,
              "UPDATE harness_runs SET snapshot_json=?,lease_until=NULL WHERE" + " run_id=?",
              Json.write(run),
              id);
          execute(
              c,
              "UPDATE harness_run_index SET terminal_at=? WHERE run_id=?",
              now(c) - days * DAY,
              id);
          execute(
              c,
              "INSERT INTO harness_platform_trace(run_id,started_at,duration_ms)"
                  + " VALUES(?,?,?)",
              id,
              now(c) - 20 * DAY,
              12);
          return null;
        });
    return id;
  }

  private JsonNode apply(RetentionService service, JsonNode plan) {
    return service.apply(
        OperationsTest.admin(),
        UUID.randomUUID().toString(),
        plan.path("planId").asText(),
        plan.path("digest").asText());
  }

  @Test
  void reviewedArchiveErasesLiveCopiesPreservesCountersAndPermanentIdempotency() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      var cipher = key();
      var service = new RetentionService(f.repository, cipher);
      String id = old(f, "retention-created-key", 31),
          taskId = UUID.randomUUID().toString(),
          experimentId = UUID.randomUUID().toString();
      f.repository.transaction(
          c -> {
            var documents = new StudioRepository(f.repository);
            var task =
                Json.object()
                    .put("id", taskId)
                    .put("runId", id)
                    .put("owner", "app-a/alice")
                    .put("input", PRIVATE);
            documents.put(c, "project-a", "TASK", taskId, task);
            var experiment = Json.object().put("id", experimentId).put("owner", "app-a/alice");
            var row = experiment.putArray("rows").addObject().put("input", PRIVATE);
            row.putArray("expectedContains").add(PRIVATE);
            row.set("candidate", task.deepCopy());
            documents.put(c, "project-a", "EXPERIMENT", experimentId, experiment);
            execute(
                c,
                "INSERT INTO studio_commands VALUES(?,?,?,?,?,?,?,?)",
                "synthetic-old-command",
                "synthetic-digest",
                "project-a",
                "app-a/alice",
                "app",
                "save",
                Json.write(Json.object().put("id", "app").put("instructions", PRIVATE)),
                now(c) - 31 * DAY);
            return null;
          });
      long revision = f.store.get(id).revision;
      var plan = service.preview(OperationsTest.admin(), 100);
      assertEquals(1, plan.path("candidates").size());
      assertFalse(plan.toString().contains(PRIVATE));
      assertFalse(f.store.get(id).payloadArchived);
      String command = UUID.randomUUID().toString();
      JsonNode result =
          service.apply(
              OperationsTest.admin(),
              command,
              plan.path("planId").asText(),
              plan.path("digest").asText());
      assertEquals(1, result.path("archivedRuns").asInt());
      assertEquals(1, result.path("deletedTraces").asInt());
      assertEquals(
          result,
          service.apply(
              OperationsTest.admin(),
              command,
              plan.path("planId").asText(),
              plan.path("digest").asText()));
      RunState compacted = f.store.get(id);
      assertTrue(compacted.payloadArchived);
      assertEquals(revision, compacted.revision);
      assertTrue(compacted.output == null || compacted.output.isNull());
      assertFalse(Json.write(compacted).contains(PRIVATE));
      assertTrue(f.service.get(f.principal(), id).body().path("payloadArchived").asBoolean());
      assertEquals(id, f.id(f.create("read", "retention-created-key")));
      assertThrows(
          RunStore.Conflict.class,
          () ->
              f.store.update(
                  id,
                  revision,
                  run -> run,
                  new RunEvent("ILLEGAL_REOPEN", null, null, Instant.now(), Map.of())));
      f.repository.transaction(
          c -> {
            try (var s =
                    prepare(
                        c,
                        "SELECT archive_id,ciphertext FROM" + " operations_archives WHERE run_id=?",
                        id);
                var r = s.executeQuery()) {
              assertTrue(r.next());
              assertFalse(r.getString(2).contains(PRIVATE));
              JsonNode original = cipher.decrypt(r.getString(1), r.getString(2));
              assertTrue(original.path("snapshot").toString().contains(PRIVATE));
              assertThrows(
                  IllegalStateException.class,
                  () -> cipher.decrypt("different-archive", r.getString(2)));
            }
            for (String sql :
                List.of(
                    "SELECT document_json FROM studio_documents WHERE" + " project_id='project-a'",
                    "SELECT response_json FROM studio_commands WHERE" + " project_id='project-a'",
                    "SELECT response_json FROM platform_commands WHERE run_id='" + id + "'"))
              try (var s = prepare(c, sql);
                  var r = s.executeQuery()) {
                while (r.next()) assertFalse(r.getString(1).contains(PRIVATE));
              }
            return null;
          });
      assertTrue(service.preview(OperationsTest.admin(), 100).path("candidates").isEmpty());
    }
  }

  @Test
  void missingKeyAndStalePlanRollbackAllChanges() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      String id = old(f, "retention-no-key", 31);
      var disabled = new RetentionService(f.repository, new RetentionArchive(null));
      var plan = disabled.preview(OperationsTest.admin(), 100);
      assertEquals(
          "RETENTION_ARCHIVE_KEY_REQUIRED",
          assertThrows(ApiFailure.class, () -> apply(disabled, plan)).code);
      assertFalse(f.store.get(id).payloadArchived);
      var service = new RetentionService(f.repository, key());
      f.repository.transaction(
          c -> {
            RunState changed = f.store.get(id);
            changed.memory.put("race", "changed");
            execute(
                c,
                "UPDATE harness_runs SET snapshot_json=? WHERE run_id=?",
                Json.write(changed),
                id);
            return null;
          });
      assertEquals(412, assertThrows(ApiFailure.class, () -> apply(service, plan)).status);
      assertFalse(f.store.get(id).payloadArchived);
    }
  }

  @Test
  void unresolvedOrApprovalEvidenceIsPinnedEvenWhenStatusMetadataSaysTerminal() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      String id = old(f, "retention-unknown", 31);
      f.repository.transaction(
          c -> {
            RunState pending = f.store.get(id);
            pending.pending = new RunState.Pending();
            pending.pending.id = "uncertain";
            pending.pending.phase = InvocationPhase.UNKNOWN;
            execute(
                c,
                "UPDATE harness_runs SET snapshot_json=? WHERE run_id=?",
                Json.write(pending),
                id);
            return null;
          });
      var plan = new RetentionService(f.repository, key()).preview(OperationsTest.admin(), 100);
      assertEquals(1, plan.path("pinnedCount").asInt());
      assertTrue(plan.path("candidates").isEmpty());
      assertEquals(
          1,
          f.repository
              .transaction(
                  c -> {
                    try (var s =
                            prepare(
                                c,
                                "SELECT COUNT(*) FROM"
                                    + " harness_platform_trace WHERE"
                                    + " run_id=?",
                                id);
                        var r = s.executeQuery()) {
                      r.next();
                      return r.getInt(1);
                    }
                  })
              .intValue());
    }
  }

  @Test
  void archivesAndOldAuditExpireButCreationAndCommandTombstonesRemain() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      String id = old(f, "retention-audit-key", 181);
      var service = new RetentionService(f.repository, key());
      var result = apply(service, service.preview(OperationsTest.admin(), 100));
      assertTrue(result.path("deletedAuditRows").asInt() > 0);
      f.repository.transaction(
          c -> {
            execute(
                c, "UPDATE operations_archives SET expires_at=? WHERE run_id=?", now(c) - 1, id);
            return null;
          });
      var next = service.preview(OperationsTest.admin(), 100);
      assertEquals(1, next.path("expiredArchives").size());
      assertEquals(1, apply(service, next).path("deletedArchives").asInt());
      assertEquals(id, f.id(f.create("read", "retention-audit-key")));
      assertTrue(service.preview(OperationsTest.admin(), 100).path("candidates").isEmpty());
    }
  }

  @Test
  void plansAreAdministratorAndActorScoped() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      var service = new RetentionService(f.repository, key());
      assertEquals(
          403, assertThrows(ApiFailure.class, () -> service.preview(f.principal(), 100)).status);
      var plan = service.preview(OperationsTest.admin(), 100);
      var other = new IdentityProvider.Principal("app-a", "project-a", "bob", Set.of("*"));
      assertEquals(
          404,
          assertThrows(ApiFailure.class, () -> service.plan(other, plan.path("planId").asText()))
              .status);
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      service.apply(
                          other,
                          UUID.randomUUID().toString(),
                          plan.path("planId").asText(),
                          plan.path("digest").asText()))
              .status);
    }
  }

  private void unknownPermit(PlatformTestSupport f, String runId) {
    f.repository.transaction(
        c -> {
          execute(
              c,
              "INSERT INTO"
                  + " gov_permit(permit_id,run_id,project_id,invocation_id,fence,pool_id,model_call,state,created_at,updated_at,lease_until)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID().toString(),
              runId,
              "project-a",
              "synthetic-uncertain-invocation",
              1,
              "model:fixture",
              true,
              "UNKNOWN",
              now(c),
              now(c),
              now(c) - 1000);
          return null;
        });
  }

  @Test
  void terminalRunsWithUnresolvedProviderPermitsArePinnedAndCursorAdvances() {
    try (var f = new PlatformTestSupport()) {
      initialize(f);
      String pinned = old(f, "retention-provider-pinned", 40);
      unknownPermit(f, pinned);
      String first = old(f, "retention-next-first", 35);
      String second = old(f, "retention-next-second", 31);
      var service = new RetentionService(f.repository, key());
      var plan = service.preview(OperationsTest.admin(), 1);
      assertEquals(1, plan.path("pinnedCount").asInt());
      assertEquals(first, plan.path("candidates").get(0).path("runId").asText());
      assertTrue(plan.path("hasMore").asBoolean());
      var next = service.preview(OperationsTest.admin(), 1, plan.path("nextCursor").asText());
      assertEquals(second, next.path("candidates").get(0).path("runId").asText());
      unknownPermit(f, second);
      assertEquals(412, assertThrows(ApiFailure.class, () -> apply(service, next)).status);
      assertFalse(f.store.get(second).payloadArchived);
      assertEquals(1, apply(service, plan).path("archivedRuns").asInt());
      assertFalse(f.store.get(pinned).payloadArchived);
    }
  }
}
