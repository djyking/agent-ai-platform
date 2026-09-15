package io.github.djyking.harness.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import io.github.djyking.harness.core.RunStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcRunStoreTest {
  private JdbcDataSource dataSource;
  private JdbcRunStore store;

  @BeforeEach
  void database() {
    dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
  }

  @Test
  void snapshotsAndReturnedValuesAreDetachedAndSurviveReopening() {
    RunState initial = initial("copy");
    initial.memory.put("question", "original");
    initial.tools.add(
        new ToolDescriptor(
            "test:lookup",
            "lookup",
            "lookup",
            "internal",
            "lookup",
            "1",
            Json.read("{\"type\":\"object\"}"),
            ToolPolicy.readOnlyPolicy()));
    RunState created = store.create(initial);
    initial.memory.put("question", "changed input");
    created.memory.put("question", "changed result");
    RunState fetched = new JdbcRunStore(dataSource).get(initial.id);
    assertEquals("original", fetched.memory.path("question").asText());
    assertEquals(initial.createdAt, fetched.createdAt);
    assertEquals(initial.tools.get(0), fetched.tools.get(0));
    fetched.memory.removeAll();
    assertEquals("original", store.get(initial.id).memory.path("question").asText());
    assertEquals("RUN_CREATED", store.events(initial.id, 0, 10).get(0).event().type());
  }

  @Test
  void fileDatabaseRestoresAnInterruptedIntentAfterAllConnectionsClose(@TempDir Path directory) {
    String url =
        "jdbc:h2:file:"
            + directory.resolve("durable-run").toAbsolutePath().toString().replace('\\', '/')
            + ";MODE=MySQL";
    JdbcDataSource originalDataSource = new JdbcDataSource();
    originalDataSource.setURL(url);
    JdbcRunStore original = new JdbcRunStore(originalDataSource);
    original.initializeSchema();
    RunState run = original.create(initial("disk-recovery"));
    RunState intent = original.claim(run.id, Duration.ofMinutes(1));
    intent.pending = new RunState.Pending();
    intent.pending.id = "durable-invocation";
    intent.pending.kind = "TOOL";
    intent.pending.phase = InvocationPhase.IN_FLIGHT;
    original.save(intent, event("TOOL_INTENT"), false);
    original.close();
    JdbcDataSource restartedDataSource = new JdbcDataSource();
    restartedDataSource.setURL(url);
    JdbcRunStore restarted = new JdbcRunStore(restartedDataSource);
    RunState recovered = restarted.get(run.id);
    assertEquals("durable-invocation", recovered.pending.id);
    assertEquals(InvocationPhase.IN_FLIGHT, recovered.pending.phase);
    assertEquals(3, restarted.events(run.id, 0, 10).size());
  }

  @Test
  void leaseIsCheckedAfterAContendedRowLockInsteadOfBeforeWaiting() throws Exception {
    RunState run = store.create(initial("lock-clock"));
    RunState claimed = store.claim(run.id, Duration.ofMillis(250));
    var executor = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement statement =
          locker.prepareStatement("SELECT run_id FROM harness_runs WHERE run_id = ? FOR UPDATE")) {
        statement.setString(1, run.id);
        statement.executeQuery().close();
      }
      CountDownLatch submitted = new CountDownLatch(1);
      Future<Boolean> save =
          executor.submit(
              () -> {
                submitted.countDown();
                try {
                  store.save(claimed, event("TOO_LATE"), true);
                  return true;
                } catch (RunStore.Conflict expected) {
                  return false;
                }
              });
      assertTrue(submitted.await(5, TimeUnit.SECONDS));
      Thread.sleep(350);
      locker.commit();
      assertFalse(save.get(5, TimeUnit.SECONDS), "Waiting for a lock must not extend a lease");
      assertEquals(claimed.revision, store.get(run.id).revision);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void creationIsIdempotentWithinActorScopeAndRejectsRequestMismatch() {
    RunState first = store.create(initial("same-key"));
    RunState second = store.create(initial("same-key"));
    assertEquals(first.id, second.id);
    assertEquals(1, store.events(first.id, 0, 10).size());
    RunState mismatch = initial("same-key");
    mismatch.creationDigest = Json.hash("different request");
    assertThrows(RunStore.Conflict.class, () -> store.create(mismatch));
    RunState otherActor = initial("same-key");
    otherActor.actor = new Actor("other-user", "test-project", Set.of("*"));
    assertNotEquals(first.id, store.create(otherActor).id);
  }

  @Test
  void concurrentCreationCommitsExactlyOneRunAndCreationEvent() throws Exception {
    List<RunState> states = race(8, () -> store.create(initial("race-key")));
    String id = states.get(0).id;
    assertTrue(states.stream().allMatch(state -> state.id.equals(id)));
    assertEquals(1, store.ready(100).size());
    assertEquals(1, store.events(id, 0, 100).size());
  }

  @Test
  void concurrentWorkersCannotBothClaimOneRun() throws Exception {
    RunState run = store.create(initial("claim-race"));
    List<RunState> claims = race(8, () -> store.claim(run.id, Duration.ofMinutes(1)));
    List<RunState> winners = claims.stream().filter(java.util.Objects::nonNull).toList();
    assertEquals(1, winners.size());
    assertEquals(1, winners.get(0).fence);
    assertEquals(2, winners.get(0).revision);
    assertEquals(2, store.events(run.id, 0, 100).size());
    assertFalse(store.ready(100).contains(run.id));
  }

  @Test
  void staleFencingTokenCannotCommitAfterAReplacementClaims() throws Exception {
    RunState run = store.create(initial("fence"));
    RunState former = store.claim(run.id, Duration.ofMinutes(1));
    expireLease(run.id);
    RunState replacement = store.claim(run.id, Duration.ofMinutes(1));
    assertEquals(former.fence + 1, replacement.fence);
    former.revision =
        replacement.revision; // Even a current revision does not grant the old fencing token.
    former.leaseUntil = Instant.now().plus(Duration.ofDays(99));
    former.memory.put("stale", true);
    assertThrows(RunStore.Conflict.class, () -> store.save(former, event("STALE"), true));
    assertThrows(RunStore.Conflict.class, () -> store.assertLease(former));
    assertFalse(store.get(run.id).memory.has("stale"));
    assertEquals(3, store.events(run.id, 0, 100).size());
  }

  @Test
  void databaseLeaseCannotBeExtendedByChangingTheWorkerClockOrDto() throws Exception {
    RunState run = store.create(initial("clock"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    expireLease(run.id);
    claimed.leaseUntil = Instant.parse("2999-01-01T00:00:00Z");
    assertThrows(RunStore.Conflict.class, () -> store.assertLease(claimed));
    assertThrows(
        RunStore.Conflict.class,
        () ->
            store.save(
                claimed,
                new RunEvent("FUTURE_WORKER_CLOCK", null, null, claimed.leaseUntil, Map.of()),
                true));
    assertTrue(store.ready(10).contains(run.id));
  }

  @Test
  void durableIntentRetainsIdentityAndReservationsAcrossInterruptedWriteRecovery()
      throws Exception {
    RunState run = store.create(initial("recover"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    claimed.pending = new RunState.Pending();
    claimed.pending.id = "invocation-original";
    claimed.pending.nodeId = "change-node";
    claimed.pending.kind = "tool";
    claimed.pending.toolKey = "sandbox:change";
    claimed.pending.contractDigest = "frozen-contract";
    claimed.pending.arguments = Json.read("{\"resource\":\"test-only\"}");
    claimed.pending.phase = InvocationPhase.IN_FLIGHT;
    claimed.pending.attempts = 1;
    claimed.pending.tokenReservation = 42;
    claimed.chargedTokens = 100;
    claimed.toolCalls = 1;
    claimed.approval =
        new Approval(
            claimed.pending.id, "exact-approval", run.deadline, "APPROVED", "operator", null);
    RunState intent = store.save(claimed, event("TOOL_INTENT"), false);
    assertEquals(claimed.revision + 1, intent.revision);
    assertEquals(claimed.fence, intent.fence);
    assertEquals(claimed.leaseUntil, intent.leaseUntil);
    assertNull(store.claim(run.id, Duration.ofMinutes(1)));
    expireLease(run.id);
    JdbcRunStore restarted = new JdbcRunStore(dataSource);
    RunState recovered = restarted.claim(run.id, Duration.ofMinutes(1));
    assertEquals("invocation-original", recovered.pending.id);
    assertEquals(InvocationPhase.IN_FLIGHT, recovered.pending.phase);
    assertEquals(1, recovered.pending.attempts);
    assertEquals(42, recovered.pending.tokenReservation);
    assertEquals(100, recovered.chargedTokens);
    assertEquals(1, recovered.toolCalls);
    assertEquals(run.deadline, recovered.deadline);
    assertEquals(claimed.approval, recovered.approval);
    assertEquals(claimed.actor, recovered.actor);
  }

  @Test
  void externalControlUsesCasAndDoesNotRevokeOrExtendLease() {
    RunState run = store.create(initial("controls"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    RunState controlled =
        store.update(
            run.id,
            claimed.revision,
            next -> {
              next.pauseRequested = true;
              next.cancelRequested = true;
              next.leaseUntil = null;
              next.fence = 999;
              return next;
            },
            event("CONTROL_REQUESTED"));
    assertEquals(claimed.leaseUntil, controlled.leaseUntil);
    assertEquals(claimed.fence, controlled.fence);
    assertThrows(RunStore.Conflict.class, () -> store.save(claimed, event("STALE_RESULT"), true));
    assertThrows(
        RunStore.Conflict.class,
        () -> store.update(run.id, claimed.revision, value -> value, event("STALE_CONTROL")));
    RunState latest = store.get(run.id);
    latest.status = RunStatus.CANCELLED;
    RunState finalState = store.save(latest, event("CANCELLED"), true);
    assertNull(finalState.leaseUntil);
    assertTrue(finalState.pauseRequested);
    assertTrue(finalState.cancelRequested);
    assertFalse(store.ready(10).contains(run.id));
  }

  @Test
  void twoExternalUpdatesWithOneRevisionCannotOverwriteEachOther() throws Exception {
    RunState run = store.create(initial("cas-race"));
    List<Boolean> success =
        race(
            8,
            () -> {
              try {
                store.update(
                    run.id,
                    run.revision,
                    next -> {
                      next.pauseRequested = true;
                      return next;
                    },
                    event("PAUSE"));
                return true;
              } catch (RunStore.Conflict expected) {
                return false;
              }
            });
    assertEquals(1, success.stream().filter(Boolean::booleanValue).count());
    assertEquals(run.revision + 1, store.get(run.id).revision);
    assertEquals(2, store.events(run.id, 0, 10).size());
  }

  @Test
  void failedEventAppendRollsBackTheStateUpdate() throws Exception {
    RunState run = store.create(initial("rollback"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    // Force an append failure after the UPDATE has executed, using a conflicting event PK.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO harness_run_events (run_id, event_sequence, event_json) VALUES (?, 3,"
                    + " ?)")) {
      statement.setString(1, run.id);
      statement.setString(2, Json.write(event("INJECTED_CONFLICT")));
      statement.executeUpdate();
    }
    claimed.memory.put("mustRollback", true);
    claimed.status = RunStatus.COMPLETED;
    assertThrows(JdbcStorageException.class, () -> store.save(claimed, event("COMPLETE"), true));
    RunState after = store.get(run.id);
    assertEquals(RunStatus.RUNNING, after.status);
    assertEquals(claimed.revision, after.revision);
    assertEquals(claimed.leaseUntil, after.leaseUntil);
    assertFalse(after.memory.has("mustRollback"));
    assertTrue(
        store.events(run.id, 0, 100).stream()
            .noneMatch(value -> value.event().type().equals("COMPLETE")));
  }

  @Test
  void persistedBaselineCannotChangeDuringControlOrExecution() {
    RunState run = store.create(initial("baseline"));
    assertThrows(
        RunStore.Conflict.class,
        () ->
            store.update(
                run.id,
                run.revision,
                next -> {
                  next.deadline = next.deadline.plusSeconds(60);
                  return next;
                },
                event("RESET_DEADLINE")));
    assertThrows(
        RunStore.Conflict.class,
        () ->
            store.update(
                run.id,
                run.revision,
                next -> {
                  next.actor = new Actor("somebody-else", next.actor.project(), Set.of("*"));
                  return next;
                },
                event("REPLACE_ACTOR")));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    claimed.definition = new ProgramDefinition("agent", "silently-new", "test", Json.object());
    assertThrows(RunStore.Conflict.class, () -> store.save(claimed, event("REPLACE_PROMPT"), true));
    assertEquals(run.definition, store.get(run.id).definition);
  }

  @Test
  void modelReservationCanSettleOnlyAgainstTheMatchingKnownResponse() {
    RunState run = store.create(initial("settle"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    claimed.pending = new RunState.Pending();
    claimed.pending.id = "model-invocation";
    claimed.pending.kind = "MODEL";
    claimed.pending.phase = InvocationPhase.IN_FLIGHT;
    claimed.pending.tokenReservation = 1_000;
    claimed.chargedTokens = 1_500; // 500 already charged, 1000 reserved by this invocation.
    claimed.modelCalls = 2;
    RunState intent = store.save(claimed, event("MODEL_INTENT"), false);
    ModelResponse response =
        new ModelResponse(
            Message.text("assistant", "done"), FinishReason.FINAL, new Usage(40, 60, true));
    assertThrows(
        RunStore.Conflict.class,
        () ->
            store.update(
                run.id,
                intent.revision,
                next -> {
                  next.results.put(
                      next.pending.id, new StepResult("MODEL", Json.tree(response), false));
                  next.pending = null;
                  next.chargedTokens = 600;
                  return next;
                },
                event("HOST_RELEASES_RESERVATION")));
    RunState forged = intent.copy();
    forged.results.put(forged.pending.id, new StepResult("MODEL", Json.tree(response), false));
    forged.pending = null;
    forged.chargedTokens = 599;
    assertThrows(
        RunStore.Conflict.class, () -> store.save(forged, event("INCORRECT_SETTLEMENT"), true));
    forged.chargedTokens = 600;
    RunState settled = store.save(forged, event("MODEL_COMPLETED"), true);
    assertEquals(600, settled.chargedTokens);
    assertEquals(2, settled.modelCalls);
    assertNull(settled.pending);
  }

  @Test
  void unknownOrDifferentInvocationCannotReleaseReservation() {
    RunState run = store.create(initial("unknown-settle"));
    RunState claimed = store.claim(run.id, Duration.ofMinutes(1));
    claimed.pending = new RunState.Pending();
    claimed.pending.id = "original-model";
    claimed.pending.kind = "MODEL";
    claimed.pending.phase = InvocationPhase.IN_FLIGHT;
    claimed.pending.tokenReservation = 1_000;
    claimed.chargedTokens = 1_000;
    RunState intent = store.save(claimed, event("MODEL_INTENT"), false);
    RunState invalid = intent.copy();
    invalid.pending = null;
    invalid.chargedTokens = 0;
    invalid.results.put(
        "original-model",
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    Message.text("assistant", "done"), FinishReason.FINAL, Usage.unknown())),
            false));
    assertThrows(
        RunStore.Conflict.class, () -> store.save(invalid, event("UNKNOWN_SETTLEMENT"), true));
    invalid.results.clear();
    invalid.results.put(
        "different-model",
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(
                    Message.text("assistant", "done"), FinishReason.FINAL, new Usage(0, 0, true))),
            false));
    assertThrows(
        RunStore.Conflict.class, () -> store.save(invalid, event("DIFFERENT_SETTLEMENT"), true));
    assertEquals(1_000, store.get(run.id).chargedTokens);
  }

  @Test
  void readyHonorsBackoffAndWaitStatesAndEventsSupportCursors() {
    RunState future = initial("future");
    future.nextAttemptAt = Instant.now().plusSeconds(3_600);
    store.create(future);
    RunState waiting = store.create(initial("waiting"));
    store.update(
        waiting.id,
        waiting.revision,
        next -> {
          next.status = RunStatus.WAITING_APPROVAL;
          return next;
        },
        event("APPROVAL_REQUIRED"));
    RunState runnable = store.create(initial("runnable"));
    assertEquals(List.of(runnable.id), store.ready(10));
    assertNull(store.claim(future.id, Duration.ofSeconds(10)));
    assertNull(store.claim(waiting.id, Duration.ofSeconds(10)));
    assertEquals("APPROVAL_REQUIRED", store.events(waiting.id, 1, 1).get(0).event().type());
    assertEquals(2, store.events(waiting.id, 1, 1).get(0).sequence());
    assertTrue(store.events(waiting.id, 2, 10).isEmpty());
  }

  @Test
  void unknownSnapshotVersionIsRejectedBeforeDeserializingNewFields() throws Exception {
    RunState run = store.create(initial("version"));
    var unsupported = Json.tree(run).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("schemaVersion", 999);
    ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("futureOnly", true);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE harness_runs SET snapshot_json = ? WHERE run_id = ?")) {
      statement.setString(1, Json.write(unsupported));
      statement.setString(2, run.id);
      statement.executeUpdate();
    }
    JdbcStorageException failure =
        assertThrows(JdbcStorageException.class, () -> store.get(run.id));
    assertTrue(failure.getMessage().contains("explicit migration"));
  }

  @Test
  void schemaBootstrapCanRepeatButRejectsAnUnsupportedVersion() throws Exception {
    store.initializeSchema();
    try (Connection connection = dataSource.getConnection()) {
      connection.createStatement().executeUpdate("UPDATE harness_schema SET schema_version = 999");
    }
    assertThrows(JdbcStorageException.class, store::initializeSchema);
    assertThrows(JdbcStorageException.class, () -> new JdbcRunStore(dataSource));
  }

  private RunState initial(String creationKey) {
    RunState run = new RunState();
    run.id = UUID.randomUUID().toString();
    run.creationKey = creationKey;
    run.creationDigest = Json.hash(List.of("same creation request", creationKey));
    run.actor = new Actor("test-user", "test-project", Set.of("*"));
    run.definition = new ProgramDefinition("agent", "1", "test", Json.object());
    run.budget = Budget.defaults();
    run.createdAt = Instant.now();
    run.deadline = run.createdAt.plus(Duration.ofHours(1));
    return run;
  }

  private RunEvent event(String type) {
    return new RunEvent(type, null, null, Instant.now(), Map.of());
  }

  private void expireLease(String id) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE harness_runs SET lease_until = 1 WHERE run_id = ?")) {
      statement.setString(1, id);
      statement.executeUpdate();
    }
  }

  private <T> List<T> race(int workers, Callable<T> action) throws Exception {
    var executor = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < workers; i++)
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Race start timed out");
                  return action.call();
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
