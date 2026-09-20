package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionGovernanceTest {
  private ExecutionGovernance governance(
      PlatformTestSupport f,
      int capacity,
      int rate,
      int threshold,
      Map<String, ExecutionGovernanceConfig.Budget> budgets) {
    var pool = new ExecutionGovernanceConfig.Pool(capacity, rate, threshold, 1000);
    var config =
        new ExecutionGovernanceConfig(
            "test", "v1", capacity, pool, pool, Map.of(), Map.of(), budgets);
    var governance = new ExecutionGovernance(f.repository, config, f.config);
    governance.initialize();
    return governance;
  }

  private RunState prepared(PlatformTestSupport f, String key) {
    String id = f.id(f.create("model", key));
    f.harness.tick(id);
    return f.store.claim(id, Duration.ofSeconds(10));
  }

  @Test
  void configurationMismatchFailsClosedAndValidateOnlyNeverBootstraps() {
    try (var f = new PlatformTestSupport()) {
      var one = governance(f, 2, 10, 2, Map.of());
      one.heartbeat(1, false);
      var matching = governance(f, 2, 10, 2, Map.of());
      assertEquals(one.fingerprint(), matching.fingerprint());
      assertThrows(IllegalStateException.class, () -> governance(f, 3, 10, 2, Map.of()));
      f.store.validateSchema();
      new PlatformRepository(f.store, false).initialize(f.config);
      assertEquals(1, one.overview("project-a").path("workers").size());
    }
  }

  @Test
  void reviewedMigrationRejectsLiveWorkerAndActiveRunThenUsesExpectedFingerprint() {
    try (var f = new PlatformTestSupport()) {
      var original = governance(f, 1, 100, 2, Map.of());
      original.heartbeat(0, false);
      var defaults = ExecutionGovernanceConfig.defaults();
      var changed =
          new ExecutionGovernance(
              f.repository,
              new ExecutionGovernanceConfig(
                  "test",
                  "v2",
                  2,
                  defaults.defaultProvider(),
                  defaults.defaultTool(),
                  Map.of(),
                  Map.of(),
                  Map.of()),
              f.config);
      assertThrows(
          IllegalStateException.class, () -> changed.migrateConfiguration(original.fingerprint()));
      original.heartbeat(0, true);
      String active = f.id(f.create("read", "migration-active"));
      assertThrows(
          IllegalStateException.class, () -> changed.migrateConfiguration(original.fingerprint()));
      assertEquals(RunStatus.COMPLETED, f.drive(active).status);
      assertThrows(IllegalStateException.class, () -> changed.migrateConfiguration("0".repeat(64)));
      changed.migrateConfiguration(original.fingerprint());
      assertThrows(
          IllegalStateException.class, () -> f.create("read", "stale-api-after-migration"));
      changed.initialize();
      assertThrows(IllegalStateException.class, () -> original.heartbeat(0, false));
    }
  }

  @Test
  void unknownRunRetainsCalendarBudgetAndOtherProjectCannotReadItsPool() {
    try (var f = new PlatformTestSupport()) {
      long cap = f.releases.get("project-a/model").limits().maxTokens();
      var governance =
          governance(
              f,
              5,
              100,
              10,
              Map.of("project-a", new ExecutionGovernanceConfig.Budget(cap, cap, cap, cap)));
      String id = f.id(f.create("model", "unknown-budget"));
      f.uncertainModel.set(true);
      assertEquals(RunStatus.NEEDS_ATTENTION, f.drive(id).status);
      governance.settle("project-a");
      for (var bucket : governance.overview("project-a").path("budgets"))
        assertEquals(cap, bucket.path("reservedTokens").asLong());
      assertThrows(ApiFailure.class, () -> f.create("model", "unknown-next"));
      assertTrue(governance.overview("project-b").path("pools").isEmpty());
    }
  }

  @Test
  void sharedConcurrencyUnknownQuarantineAndIndependentResolution() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var one = governance(f, 1, 100, 10, Map.of());
      var two = governance(f, 1, 100, 10, Map.of());
      var first = prepared(f, "permit-one");
      var second = prepared(f, "permit-two");
      var permit = one.reserve(first, 1000);
      assertTrue(permit.granted());
      assertFalse(two.reserve(second, 1000).granted());
      assertThrows(
          InvocationException.class,
          () ->
              permit.invoke(
                  () -> {
                    throw new InvocationException(FailureKind.UNKNOWN, "LOST_REPLY");
                  }));
      permit.abandon();
      one.heartbeat(0, false);
      assertFalse(two.reserve(second, 1000).granted());
      var pending = one.overview("project-a").path("unknownPermits");
      assertEquals(1, pending.size());
      String id = pending.get(0).path("permitId").asText();
      one.resolveUnknownPermit(id, "test remote independently confirmed stopped");
      var next = two.reserve(second, 1000);
      assertTrue(next.granted());
      assertEquals("ok", next.invoke(() -> "ok"));
      assertEquals(0, one.overview("project-a").path("pools").get(0).path("occupied").asInt());
    }
  }

  @Test
  void unknownResolutionReplayRequiresExactEvidenceAndDoesNotRewriteRunOrReceipt()
      throws Exception {
    try (var f = new PlatformTestSupport()) {
      var governor = governance(f, 2, 100, 10, Map.of());
      var run = prepared(f, "resolution-idempotency");
      var permit = governor.reserve(run, 1000);
      assertThrows(
          InvocationException.class,
          () ->
              permit.invoke(
                  () -> {
                    throw new InvocationException(FailureKind.UNKNOWN, "LOST_REPLY");
                  }));
      String id =
          governor.overview("project-a").path("unknownPermits").get(0).path("permitId").asText();
      String before = Json.hash(f.store.get(run.id));
      String evidence = "independently verified remote call stopped: Case-42";
      governor.resolveUnknownPermit(id, evidence);
      long updated =
          f.repository.transaction(
              c -> {
                try (var query =
                        PlatformRepository.prepare(
                            c, "SELECT updated_at FROM gov_permit WHERE permit_id=?", id);
                    var rows = query.executeQuery()) {
                  rows.next();
                  return rows.getLong(1);
                }
              });
      assertDoesNotThrow(() -> governor.resolveUnknownPermit(id, evidence));
      assertThrows(
          ApiFailure.class,
          () -> governor.resolveUnknownPermit(id, evidence.toLowerCase(Locale.ROOT)));
      assertThrows(
          ApiFailure.class,
          () -> governor.resolveUnknownPermit(UUID.randomUUID().toString(), evidence));
      long after =
          f.repository.transaction(
              c -> {
                try (var query =
                        PlatformRepository.prepare(
                            c, "SELECT updated_at FROM gov_permit WHERE permit_id=?", id);
                    var rows = query.executeQuery()) {
                  rows.next();
                  return rows.getLong(1);
                }
              });
      assertEquals(updated, after);
      assertEquals(before, Json.hash(f.store.get(run.id)));
      var another = prepared(f, "resolution-reserved");
      governor.reserve(another, 1000);
      String reserved =
          f.repository.transaction(
              c -> {
                try (var query =
                        PlatformRepository.prepare(
                            c, "SELECT permit_id FROM gov_permit WHERE run_id=?", another.id);
                    var rows = query.executeQuery()) {
                  rows.next();
                  return rows.getString(1);
                }
              });
      assertThrows(ApiFailure.class, () -> governor.resolveUnknownPermit(reserved, evidence));
    }
  }

  @Test
  void unknownPermitCountIsNotTruncatedToTheDetailPageLimit() {
    try (var f = new PlatformTestSupport()) {
      var governor = governance(f, 2, 100, 10, Map.of());
      var run = prepared(f, "unknown-count");
      f.repository.transaction(
          c -> {
            long current = PlatformRepository.now(c);
            for (int i = 0; i < 101; i++)
              PlatformRepository.execute(
                  c,
                  "INSERT INTO"
                      + " gov_permit(permit_id,run_id,project_id,invocation_id,fence,pool_id,model_call,state,created_at,updated_at,lease_until)"
                      + " VALUES(?,?,?,?,?,?,?,'UNKNOWN',?,?,?)",
                  UUID.randomUUID().toString(),
                  run.id,
                  "project-a",
                  "test-" + i,
                  run.fence,
                  "provider:test",
                  true,
                  current,
                  current,
                  current);
            return null;
          });
      var view = governor.overview("project-a");
      assertEquals(101, view.path("unknownPermitCount").asInt());
      assertEquals(100, view.path("unknownPermits").size());
      assertEquals(0, governor.overview("project-b").path("unknownPermitCount").asInt());
    }
  }

  @Test
  void heldCallCannotBeReleasedByTimeoutAbandonOrExpiredHeartbeat() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var governance = governance(f, 1, 100, 10, Map.of());
      var first = prepared(f, "late-one");
      var second = prepared(f, "late-two");
      var permit = governance.reserve(first, 1000);
      CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try {
        Future<String> task =
            executor.submit(
                () ->
                    permit.invoke(
                        () -> {
                          entered.countDown();
                          finish.await();
                          return "late";
                        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        permit.abandon();
        f.repository.transaction(
            c -> {
              PlatformRepository.execute(
                  c, "UPDATE gov_permit SET lease_until=0 WHERE run_id=?", first.id);
              return null;
            });
        governance.heartbeat(0, false);
        assertFalse(governance.reserve(second, 1000).granted());
        finish.countDown();
        assertEquals("late", task.get(3, TimeUnit.SECONDS));
        assertTrue(governance.reserve(second, 1000).granted());
      } finally {
        finish.countDown();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void rateAndFailureIsolationPersistAcrossGovernorReplacement() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var one = governance(f, 5, 100, 1, Map.of());
      var first = prepared(f, "circuit-one");
      var permit = one.reserve(first, 1000);
      assertThrows(
          InvocationException.class,
          () ->
              permit.invoke(
                  () -> {
                    throw new InvocationException(FailureKind.TRANSIENT, "KNOWN_REJECTED");
                  }));
      var two = governance(f, 5, 100, 1, Map.of());
      var second = prepared(f, "circuit-two");
      assertFalse(two.reserve(second, 1000).granted());
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(c, "UPDATE gov_pool SET open_until=0,request_count=100");
            return null;
          });
      assertFalse(two.reserve(second, 1000).granted());
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(c, "UPDATE gov_pool SET minute_bucket=0");
            return null;
          });
      assertTrue(two.reserve(second, 1000).granted());
    }
  }

  @Test
  void localPostGateRejectionReleasesPermitWithoutChangingCircuitHistory() {
    try (var f = new PlatformTestSupport()) {
      var governor = governance(f, 2, 100, 5, Map.of());
      var run = prepared(f, "local-gate-denied");
      var permit = governor.reserve(run, 1000);
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(c, "UPDATE gov_pool SET failures=2");
            return null;
          });
      assertThrows(
          InvocationGate.NotDispatched.class,
          () ->
              permit.invoke(
                  () -> {
                    throw new InvocationGate.NotDispatched(
                        FailureKind.DENIED, "PERMISSION_REVOKED");
                  }));
      long failures =
          f.repository.transaction(
              c -> {
                try (var q = c.createStatement();
                    var r = q.executeQuery("SELECT failures FROM gov_pool")) {
                  r.next();
                  return r.getLong(1);
                }
              });
      assertEquals(2, failures);
      assertEquals(0, governor.overview("project-a").path("pools").get(0).path("occupied").asInt());
    }
  }

  @Test
  void expiredCoreFenceCannotCrossDispatchBarrier() {
    try (var f = new PlatformTestSupport()) {
      var governance = governance(f, 1, 100, 10, Map.of());
      var first = prepared(f, "fence-one");
      var permit = governance.reserve(first, 1000);
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(
                c, "UPDATE harness_runs SET fence=fence+1 WHERE run_id=?", first.id);
            return null;
          });
      AtomicInteger calls = new AtomicInteger();
      assertThrows(RunStore.Conflict.class, () -> permit.invoke(() -> calls.incrementAndGet()));
      permit.abandon();
      assertEquals(0, calls.get());
    }
  }

  @Test
  void calendarQuotaReservesAtomicallySettlesKnownAndKeepsUnknown() {
    try (var f = new PlatformTestSupport()) {
      long cap = f.releases.get("project-a/model").limits().maxTokens();
      var governance =
          governance(
              f,
              5,
              100,
              10,
              Map.of(
                  "project-a", new ExecutionGovernanceConfig.Budget(cap, cap * 10, cap, cap * 10)));
      String first = f.id(f.create("model", "budget-one"));
      assertEquals(
          "CALENDAR_TOKEN_QUOTA_EXCEEDED",
          assertThrows(ApiFailure.class, () -> f.create("model", "budget-two")).getMessage());
      assertEquals(RunStatus.COMPLETED, f.drive(first).status);
      governance.settle("project-a");
      var data = governance.overview("project-a");
      assertTrue(data.path("budgets").size() >= 4);
      for (var bucket : data.path("budgets"))
        assertEquals(0, bucket.path("reservedTokens").asLong());
      // Reserve just below the remaining daily allowance; rejected attempts did not leak a
      // reservation.
      f.repository.transaction(
          c -> {
            governance.admitBudget(c, "project-a", "app-a", 1);
            return null;
          });
      var body = f.body("project-a", "read");
      body.putObject("limits").put("maxTokens", 1);
      String second =
          f.id(
              f.service.create(
                  f.principal(), "app-a-credential", "alice-token", "budget-read", body));
      assertNotNull(second);
    }
  }

  @Test
  void deniedCapacityDefersWithoutConsumingAttemptAndUnknownIsNeverReplayed() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var governance = governance(f, 1, 100, 10, Map.of());
      var occupied = prepared(f, "occupy-model");
      var permit = governance.reserve(occupied, 1000);
      AtomicInteger calls = new AtomicInteger();
      try (var harness =
          new Harness(
              f.store,
              (request, context) -> {
                calls.incrementAndGet();
                throw new InvocationException(FailureKind.UNKNOWN, "MODEL_REPLY_LOST");
              },
              new ToolRegistry(),
              f.access,
              Telemetry.noop(),
              f.clock,
              Duration.ofSeconds(10),
              new InvocationExecutor(1, 2),
              governance)) {
        harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
        String id = f.id(f.create("model", "queue-model"));
        harness.tick(id);
        var deferred = harness.tick(id);
        assertEquals(RunStatus.QUEUED, deferred.status);
        assertEquals(0, deferred.modelCalls);
        assertEquals(0, deferred.pending.attempts);
        assertEquals(0, calls.get());
        permit.abandon();
        var queued = f.store.get(id);
        f.store.update(
            id,
            queued.revision,
            r -> {
              r.nextAttemptAt = Instant.EPOCH;
              return r;
            },
            new RunEvent("TEST_ELAPSED", null, null, f.clock.instant(), Map.of()));
        var unknown = harness.tick(id);
        assertEquals(RunStatus.NEEDS_ATTENTION, unknown.status);
        assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
        for (int i = 0; i < 4; i++) harness.tick(id);
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void readySchedulingInterleavesProjectsInsteadOfOldestProjectBacklog() {
    try (var f = new PlatformTestSupport()) {
      for (int i = 0; i < 8; i++) f.create("read", "backlog-" + i);
      var principal = f.principal("app-a", "project-b", "alice");
      String b = f.id(f.create(principal, "read", "other-project"));
      var ready = f.repository.readyFair(List.of("project-a", "project-b"), 4, 0);
      assertEquals(b, ready.get(1));
    }
  }
}
