package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.storage.jdbc.JdbcStorageException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PlatformServiceTest {
  @Test
  void acceptedCreationReplaysAfterExecutionWithoutChangingItsDeadlineOrResponse() {
    try (var f = new PlatformTestSupport()) {
      JsonNode accepted = f.create("read", "create-replay-01");
      ApiJson.validate("AcceptedRun", accepted);
      String id = f.id(accepted);
      Instant deadline = f.store.get(id).deadline;
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      ObjectNode reordered = Json.object();
      reordered.set("inputs", Json.object().put("value", 7));
      reordered.set("releaseRef", f.releases.get("project-a/read").reference());
      JsonNode replay =
          f.service.create(
              f.principal(), "app-a-credential", "alice-token", "create-replay-01", reordered);
      assertEquals(accepted, replay);
      assertEquals(deadline, f.store.get(id).deadline);
      assertEquals(1, f.identities.issued.get());
      assertEquals(1, f.reads.get());
      assertEquals(1, f.count("harness_runs"));
      assertEquals(1, f.count("platform_commands"));
      reordered.withObject("/inputs").put("value", 8);
      error(
          409,
          "IDEMPOTENCY_CONFLICT",
          () ->
              f.service.create(
                  f.principal(), "app-a-credential", "alice-token", "create-replay-01", reordered));
      var view = f.service.get(f.principal(), id);
      ApiJson.validate("RunView", view.body());
      assertEquals("AVAILABLE", view.body().path("output").path("visibility").asText());
      assertEquals(7, view.body().path("output").path("value").path("value").asInt());
      for (String internal :
          List.of(
              "memory",
              "actor",
              "permissions",
              "definition",
              "tools",
              "receipts",
              "leaseUntil",
              "fence")) assertFalse(view.body().has(internal));
    }
  }

  @Test
  void concurrentSameKeyCommitsExactlyOneRunAndOriginalAcceptance() throws Exception {
    try (var f = new PlatformTestSupport()) {
      CyclicBarrier ready = new CyclicBarrier(2);
      f.identities.beforeDelegate = () -> await(ready);
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        Callable<JsonNode> create = () -> f.create("read", "concurrent-key-01");
        Future<JsonNode> one = workers.submit(create), two = workers.submit(create);
        assertEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        assertEquals(1, f.count("harness_runs"));
        assertEquals(1, f.count("platform_runs"));
        assertEquals(1, f.count("platform_commands"));
        assertEquals(1, f.count("platform_command_audit"));
      } finally {
        workers.shutdownNow();
      }
    }
  }

  @Test
  void controlsRequireCurrentEtagButAcceptedReplayIgnoresItsOldPrecondition() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("read", "control-create-01"));
      var initial = f.service.get(f.principal(), id);
      error(
          428,
          "PRECONDITION_REQUIRED",
          () ->
              f.service.control(f.principal(), id, "pause", "missing-tag-01", null, Json.object()));
      error(
          400,
          "INVALID_REQUEST",
          () ->
              f.service.control(f.principal(), id, "pause", "invalid-tag-01", "*", Json.object()));
      JsonNode paused =
          f.service.control(
              f.principal(), id, "pause", "pause-command-01", initial.etag(), Json.object());
      assertEquals("PAUSED", paused.path("run").path("status").asText());
      long revision = f.store.get(id).revision;
      error(
          412,
          "PRECONDITION_FAILED",
          () ->
              f.service.control(
                  f.principal(), id, "cancel", "stale-tag-cancel", initial.etag(), Json.object()));
      assertEquals(revision, f.store.get(id).revision);
      var pausedView = f.service.get(f.principal(), id);
      f.service.control(
          f.principal(), id, "resume", "resume-command-1", pausedView.etag(), Json.object());
      assertEquals(
          paused,
          f.service.control(
              f.principal(), id, "pause", "pause-command-01", initial.etag(), Json.object()));
      assertEquals(RunStatus.QUEUED, f.store.get(id).status);
      f.service.control(
          f.principal(),
          id,
          "cancel",
          "cancel-command-1",
          f.service.get(f.principal(), id).etag(),
          Json.object());
      assertEquals(RunStatus.CANCELLED, f.store.get(id).status);
      error(
          409,
          "INVALID_STATE",
          () ->
              f.service.control(
                  f.principal(),
                  id,
                  "resume",
                  "terminal-resume-1",
                  f.service.get(f.principal(), id).etag(),
                  Json.object()));
      assertEquals(0, f.reads.get());
    }
  }

  @Test
  void twoProjectsApplicationsAndSubjectsRemainIsolatedAcrossAllReadEntrypoints() {
    try (var f = new PlatformTestSupport()) {
      Map<IdentityProvider.Principal, String> owned = new LinkedHashMap<>();
      for (String project : List.of("project-a", "project-b"))
        for (String app : List.of("app-a", "app-b"))
          for (String subject : List.of("alice", "bob")) {
            var principal = f.principal(app, project, subject);
            owned.put(principal, f.id(f.create(principal, "read", "scoped-key-0001")));
          }
      assertEquals(8, new HashSet<>(owned.values()).size());
      for (var entry : owned.entrySet()) {
        var reader = entry.getKey();
        String own = entry.getValue();
        assertEquals(own, f.service.get(reader, own).body().path("id").asText());
        for (var target : owned.entrySet()) {
          if (target.getKey().equals(reader)) continue;
          String other = target.getValue();
          error(404, "NOT_FOUND", () -> f.service.get(reader, other));
          error(404, "NOT_FOUND", () -> f.service.events(reader, other, null, 10));
          error(404, "NOT_FOUND", () -> f.service.unknown(reader, other));
          error(
              404,
              "NOT_FOUND",
              () ->
                  f.service.control(
                      reader,
                      other,
                      "cancel",
                      "outsider-cancel1",
                      f.service.get(target.getKey(), other).etag(),
                      Json.object()));
        }
        JsonNode list = f.service.list(reader, null, 10, null);
        ApiJson.validate("RunPage", list);
        assertEquals(1, list.path("items").size());
        assertEquals(own, list.path("items").get(0).path("id").asText());
      }
    }
  }

  @Test
  void exactApprovalDoesNotGrantRunReadAndIncompleteReviewCanOnlyReject() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("write", "approval-create1"));
      assertEquals(RunStatus.WAITING_APPROVAL, f.drive(id).status);
      error(404, "NOT_FOUND", () -> f.service.approval(f.principal(), id));
      error(404, "NOT_FOUND", () -> f.service.get(f.reviewer(), id));
      error(403, "FORBIDDEN", () -> f.service.list(f.reviewer(), null, 10, null));
      var approval = f.service.approval(f.reviewer(), id);
      ApiJson.validate("ApprovalView", approval.body());
      ObjectNode decision =
          Json.object().put("digest", "sha256:" + "a".repeat(64)).put("decision", "APPROVE");
      error(
          409,
          "APPROVAL_STALE",
          () ->
              f.service.decide(
                  f.reviewer(),
                  id,
                  approval.body().path("id").asText(),
                  "wrong-digest-01",
                  approval.etag(),
                  decision));
      decision
          .put("digest", approval.body().path("digest").asText())
          .put("input", "forbidden on tool approval");
      error(
          422,
          "INPUT_INVALID",
          () ->
              f.service.decide(
                  f.reviewer(),
                  id,
                  approval.body().path("id").asText(),
                  "tool-input-0001",
                  approval.etag(),
                  decision));
      decision.remove("input");
      JsonNode accepted =
          f.service.decide(
              f.reviewer(),
              id,
              approval.body().path("id").asText(),
              "approve-write-01",
              approval.etag(),
              decision);
      ApiJson.validate("AcceptedRun", accepted);
      assertFalse(accepted.path("run").has("output"));
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      assertEquals(1, f.writes.get());
      assertEquals(
          accepted,
          f.service.decide(
              f.reviewer(),
              id,
              approval.body().path("id").asText(),
              "approve-write-01",
              approval.etag(),
              decision));
      String blindId = f.id(f.create("write", "blind-review-001"));
      f.drive(blindId);
      var blind = f.principal("app-a", "project-a", "blind-reviewer");
      var masked = f.service.approval(blind, blindId);
      assertFalse(masked.body().path("reviewComplete").asBoolean());
      ObjectNode blindDecision =
          Json.object()
              .put("digest", masked.body().path("digest").asText())
              .put("decision", "APPROVE");
      error(
          409,
          "APPROVAL_REVIEW_INCOMPLETE",
          () ->
              f.service.decide(
                  blind,
                  blindId,
                  masked.body().path("id").asText(),
                  "blind-approve-01",
                  masked.etag(),
                  blindDecision));
      blindDecision.put("decision", "REJECT");
      f.service.decide(
          blind,
          blindId,
          masked.body().path("id").asText(),
          "blind-reject-001",
          masked.etag(),
          blindDecision);
      assertEquals(RunStatus.FAILED, f.store.get(blindId).status);
      assertEquals(1, f.writes.get());
    }
  }

  @Test
  void humanInputAndAuditReasonRemainSeparateLiteralValues() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("human", "human-create-001"));
      assertEquals(RunStatus.WAITING_INPUT, f.drive(id).status);
      var approval = f.service.approval(f.reviewer(), id);
      ObjectNode body =
          Json.object()
              .put("digest", approval.body().path("digest").asText())
              .put("decision", "APPROVE")
              .put("reason", "audit reason only");
      error(
          422,
          "INPUT_INVALID",
          () ->
              f.service.decide(
                  f.reviewer(),
                  id,
                  approval.body().path("id").asText(),
                  "human-missing-01",
                  approval.etag(),
                  body));
      body.put("input", "{{literal}} <script>untrusted text</script>");
      f.service.decide(
          f.reviewer(),
          id,
          approval.body().path("id").asText(),
          "human-decision-1",
          approval.etag(),
          body);
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      assertEquals(body.path("input"), f.store.get(id).output.path("input"));
      String reason =
          f.repository.transaction(
              c -> {
                try (PreparedStatement s =
                        PlatformRepository.prepare(
                            c,
                            "SELECT reason_text FROM platform_command_audit WHERE run_id=? AND"
                                + " operation='decision'",
                            id);
                    ResultSet r = s.executeQuery()) {
                  assertTrue(r.next());
                  return r.getString(1);
                }
              });
      assertEquals("audit reason only", reason);
    }
  }

  @Test
  void unknownWriteNeedsBoundIndependentEvidenceAndNeverRepeatsOnResume() {
    try (var f = new PlatformTestSupport()) {
      f.uncertainWrite.set(true);
      String id = f.id(f.create("write", "unknown-write-01"));
      f.drive(id);
      f.approve(id, "approve-unknown1");
      assertEquals(RunStatus.NEEDS_ATTENTION, f.drive(id).status);
      assertEquals(1, f.writes.get());
      var unknown = f.service.unknown(f.principal(), id);
      ApiJson.validate("UnknownInvocationView", unknown.body());
      JsonNode audit = f.service.events(f.principal(), id, null, 100);
      ApiJson.validate("EventPage", audit);
      assertTrue(
          java.util.stream.StreamSupport.stream(audit.path("items").spliterator(), false)
              .anyMatch(event -> event.path("type").asText().equals("INVOCATION_UNKNOWN")));
      RunState run = f.store.get(id);
      PlatformRepository.Evidence wrong =
          new PlatformRepository.Evidence(
              "evidence-wrong",
              "project-b",
              id,
              run.pending.id,
              PlatformRepository.invocationDigest(run),
              new ToolResult(Json.object().put("value", 7), false, "remote-receipt"),
              "independent-test-verifier",
              f.clock.millis());
      error(409, "EVIDENCE_MISMATCH", () -> f.repository.importVerified(wrong));
      ObjectNode body =
          Json.object()
              .put("invocationRef", unknown.body().path("invocationRef").asText())
              .put("invocationDigest", PlatformRepository.invocationDigest(run))
              .put("evidenceRef", "evidence-good")
              .put("reason", "queried remote receipt independently");
      error(
          409,
          "EVIDENCE_NOT_VERIFIED",
          () -> f.service.reconcile(f.principal(), id, "missing-evidence", unknown.etag(), body));
      body.put("success", true);
      error(
          422,
          "INPUT_INVALID",
          () -> f.service.reconcile(f.principal(), id, "forged-evidence1", unknown.etag(), body));
      body.remove("success");
      f.repository.importVerified(
          new PlatformRepository.Evidence(
              "evidence-good",
              "project-a",
              id,
              run.pending.id,
              PlatformRepository.invocationDigest(run),
              new ToolResult(Json.object().put("value", 7), false, "remote-receipt"),
              "independent-test-verifier",
              f.clock.millis()));
      body.put("invocationRef", "wrong-invocation");
      error(
          409,
          "EVIDENCE_MISMATCH",
          () -> f.service.reconcile(f.principal(), id, "wrong-invocation", unknown.etag(), body));
      body.put("invocationRef", unknown.body().path("invocationRef").asText());
      JsonNode accepted =
          f.service.reconcile(f.principal(), id, "reconcile-good01", unknown.etag(), body);
      assertEquals("PAUSED", accepted.path("run").path("status").asText());
      f.service.control(
          f.principal(),
          id,
          "resume",
          "resume-reconciled",
          f.service.get(f.principal(), id).etag(),
          Json.object());
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      assertEquals(1, f.writes.get());
      assertEquals(
          accepted,
          f.service.reconcile(f.principal(), id, "reconcile-good01", unknown.etag(), body));
    }
  }

  @Test
  void revocationBlocksAcceptedReplayAndTheNextWorkerDispatch() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("read", "revocation-run01"));
      f.harness.tick(id);
      f.identities.revoke("app-a", "project-a", "alice", "tool:test:read");
      assertEquals(RunStatus.NEEDS_ATTENTION, f.harness.tick(id).status);
      assertEquals(0, f.reads.get());
      JsonNode deniedEvents = f.service.events(f.principal(), id, null, 100);
      assertFalse(
          java.util.stream.StreamSupport.stream(deniedEvents.path("items").spliterator(), false)
              .anyMatch(event -> event.path("type").asText().equals("INVOCATION_UNKNOWN")),
          "Known authorization denial is not an unknown external effect");
      f.identities.revoke("app-a", "project-a", "alice", "runs:create");
      error(403, "FORBIDDEN", () -> f.create("read", "revocation-run01"));
      f.identities.revoke("app-a", "project-a", "alice", "runs:read");
      error(403, "FORBIDDEN", () -> f.service.get(f.principal(), id));
      f.identities.revoke("app-a", "project-a", "alice", "runs:events:read");
      error(403, "FORBIDDEN", () -> f.service.events(f.principal(), id, null, 20));
    }
  }

  @Test
  void concurrentQuotaAdmissionNeverLeavesAPartialRunAndReleasesAfterTermination()
      throws Exception {
    try (var f = new PlatformTestSupport(1, 10000)) {
      CyclicBarrier ready = new CyclicBarrier(2);
      f.identities.beforeDelegate = () -> await(ready);
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        List<Future<Object>> results = new ArrayList<>();
        for (String key : List.of("quota-racing-one", "quota-racing-two"))
          results.add(
              workers.submit(
                  () -> {
                    try {
                      return f.create("read", key);
                    } catch (ApiFailure ex) {
                      return ex;
                    }
                  }));
        List<Object> values =
            List.of(
                results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        assertEquals(1, values.stream().filter(JsonNode.class::isInstance).count());
        ApiFailure rejected =
            (ApiFailure)
                values.stream().filter(ApiFailure.class::isInstance).findFirst().orElseThrow();
        assertEquals(429, rejected.status);
        assertEquals("QUOTA_EXCEEDED", rejected.code);
        assertEquals(1, f.count("harness_runs"));
        assertEquals(1, f.count("platform_runs"));
        assertEquals(1, f.count("platform_commands"));
        String id =
            f.id(
                (JsonNode)
                    values.stream().filter(JsonNode.class::isInstance).findFirst().orElseThrow());
        f.identities.beforeDelegate = () -> {};
        f.service.control(
            f.principal(),
            id,
            "cancel",
            "quota-cancel-001",
            f.service.get(f.principal(), id).etag(),
            Json.object());
        assertNotEquals(id, f.id(f.create("read", "quota-after-free")));
      } finally {
        workers.shutdownNow();
      }
    }
  }

  @Test
  void signedKeysetCursorIsBoundToScopeFilterAndWatermarkAndEventsAdvancePastFilteredRows() {
    try (var f = new PlatformTestSupport()) {
      Set<String> old = new HashSet<>();
      for (int i = 0; i < 3; i++) old.add(f.id(f.create("read", "page-created-" + i)));
      JsonNode first = f.service.list(f.principal(), null, 1, null);
      String cursor = first.path("nextCursor").asText();
      String newer = f.id(f.create("read", "page-newer-0001"));
      error(
          400,
          "CURSOR_INVALID",
          () -> f.service.list(f.principal("app-b", "project-a", "alice"), cursor, 1, null));
      error(400, "CURSOR_INVALID", () -> f.service.list(f.principal(), cursor, 1, "QUEUED"));
      Set<String> seen = new HashSet<>();
      seen.add(first.path("items").get(0).path("id").asText());
      JsonNode page = first;
      while (!page.path("nextCursor").isNull()) {
        page = f.service.list(f.principal(), page.path("nextCursor").asText(), 1, null);
        for (JsonNode row : page.path("items")) assertTrue(seen.add(row.path("id").asText()));
      }
      assertEquals(old, seen);
      assertFalse(seen.contains(newer));
      f.drive(newer);
      JsonNode events = f.service.events(f.principal(), newer, null, 1);
      String originalCursor = events.path("nextCursor").asText();
      Set<String> sequences = new HashSet<>(), types = new HashSet<>();
      int pages = 0;
      while (true) {
        ApiJson.validate("EventPage", events);
        for (JsonNode event : events.path("items")) {
          assertTrue(sequences.add(event.path("sequence").asText()));
          types.add(event.path("type").asText());
        }
        if (!events.path("hasMore").asBoolean()) break;
        String before = events.path("nextCursor").asText();
        events = f.service.events(f.principal(), newer, before, 1);
        assertNotEquals(before, events.path("nextCursor").asText());
        assertTrue(++pages < 50);
      }
      JsonNode tail = f.service.events(f.principal(), newer, events.path("nextCursor").asText(), 1);
      assertTrue(tail.path("items").isEmpty());
      assertFalse(tail.path("hasMore").asBoolean());
      assertTrue(
          types.containsAll(
              Set.of(
                  "RUN_CREATED",
                  "INVOCATION_STARTED",
                  "INVOCATION_FINISHED",
                  "RUN_STATUS_CHANGED")));
      f.clock.advance(Duration.ofHours(2));
      error(410, "CURSOR_EXPIRED", () -> f.service.events(f.principal(), newer, originalCursor, 1));
    }
  }

  @Test
  void failedCommandReceiptInsertRollsBackCreationOwnershipAndEvents() {
    try (var f = new PlatformTestSupport()) {
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(
                c,
                "ALTER TABLE platform_commands ADD CONSTRAINT reject_test_response CHECK"
                    + " (scope_hash='not-a-real-command')");
            return null;
          });
      assertThrows(JdbcStorageException.class, () -> f.create("read", "rollback-create1"));
      for (String table :
          List.of(
              "harness_runs",
              "harness_run_events",
              "platform_runs",
              "platform_commands",
              "platform_command_audit")) assertEquals(0, f.count(table), table);
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(
                c, "ALTER TABLE platform_commands DROP CONSTRAINT reject_test_response");
            return null;
          });
      assertFalse(f.id(f.create("read", "rollback-create1")).isBlank());
      assertEquals(1, f.count("harness_runs"));
    }
  }

  @Test
  void failedControlAuditInsertRollsBackRevisionIntentEventsAndReceipt() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("read", "control-rollback1"));
      var before = f.store.get(id);
      var view = f.service.get(f.principal(), id);
      long eventCount = f.count("harness_run_events");
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(
                c,
                "ALTER TABLE platform_command_audit ADD CONSTRAINT reject_control_audit CHECK"
                    + " (operation='create')");
            return null;
          });
      assertThrows(
          JdbcStorageException.class,
          () ->
              f.service.control(
                  f.principal(), id, "pause", "rollback-pause01", view.etag(), Json.object()));
      var after = f.store.get(id);
      assertEquals(before.revision, after.revision);
      assertEquals(before.status, after.status);
      assertFalse(after.pauseRequested);
      assertEquals(eventCount, f.count("harness_run_events"));
      assertEquals(1, f.count("platform_commands"));
      assertEquals(1, f.count("platform_command_audit"));
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(
                c, "ALTER TABLE platform_command_audit DROP CONSTRAINT reject_control_audit");
            return null;
          });
      f.service.control(f.principal(), id, "pause", "rollback-pause01", view.etag(), Json.object());
      assertEquals(RunStatus.PAUSED, f.store.get(id).status);
      assertEquals(2, f.count("platform_commands"));
    }
  }

  @Test
  void modelUnknownIsVisibleButCannotBeReconciledWithToolEvidence() {
    try (var f = new PlatformTestSupport()) {
      f.uncertainModel.set(true);
      String id = f.id(f.create("model", "model-unknown-01"));
      assertEquals(RunStatus.NEEDS_ATTENTION, f.drive(id).status);
      var view = f.service.unknown(f.principal(), id);
      assertFalse(view.body().path("reconcilable").asBoolean());
      ObjectNode body =
          Json.object()
              .put("invocationRef", view.body().path("invocationRef").asText())
              .put("invocationDigest", view.body().path("invocationDigest").asText())
              .put("evidenceRef", "tool-evidence")
              .put("reason", "cannot reinterpret model as tool");
      error(
          409,
          "OUTCOME_UNKNOWN",
          () -> f.service.reconcile(f.principal(), id, "reconcile-model1", view.etag(), body));
      assertTrue(f.store.get(id).chargedTokens > 0);
      assertEquals(1, f.modelCalls.get());
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
