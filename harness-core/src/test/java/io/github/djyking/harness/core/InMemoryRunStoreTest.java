package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryRunStoreTest {
  private MutableClock clock;
  private InMemoryRunStore store;

  @BeforeEach
  void setup() {
    clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
    store = new InMemoryRunStore(clock);
  }

  @Test
  void creationIsScopedIdempotentAndEveryBoundaryReturnsDetachedState() {
    RunState initial = initial("same-key");
    initial.memory.put("value", "original");
    initial.revision = 999;
    initial.fence = 123;
    initial.leaseUntil = clock.instant().plusSeconds(100);
    RunState created = store.create(initial);
    assertEquals(1, created.revision);
    assertEquals(0, created.fence);
    assertNull(created.leaseUntil);
    initial.memory.put("value", "input changed");
    created.memory.put("value", "returned copy changed");
    RunState duplicate = store.create(initial("same-key"));
    assertEquals(created.id, duplicate.id);
    assertEquals("original", duplicate.memory.path("value").asText());
    duplicate.memory.removeAll();
    assertEquals("original", store.get(created.id).memory.path("value").asText());
    assertEquals(1, store.events(created.id, 0, 100).size());
    RunState mismatch = initial("same-key");
    mismatch.creationDigest = "different-request";
    assertThrows(RunStore.Conflict.class, () -> store.create(mismatch));
    RunState otherProject = initial("same-key");
    otherProject.actor = new Actor("operator", "different-project", Set.of("*"));
    assertNotEquals(created.id, store.create(otherProject).id);
  }

  @Test
  void bothWorkerAndExternalUpdatesFreezeIdentityConfigurationAndLifetime() {
    RunState run = store.create(initial("immutable"));
    List<Consumer<RunState>> forbiddenChanges =
        List.of(
            state -> state.id = "different-id",
            state -> state.creationKey = "different-key",
            state -> state.creationDigest = "different-digest",
            state -> state.actor = new Actor("somebody-else", "test-project", Set.of("*")),
            state ->
                state.actor = new Actor("operator", "test-project", Set.of("different-permission")),
            state -> state.definition = new ProgramDefinition("agent", "2", "test", Json.object()),
            state -> state.budget = new Budget(500_000, 100, 100, 1_000),
            state -> state.createdAt = state.createdAt.plusSeconds(1),
            state -> state.deadline = state.deadline.plusSeconds(1),
            state -> state.tools.clear(),
            state -> state.schemaVersion = 2,
            state -> state.runtimeVersion = "2");
    for (Consumer<RunState> mutation : forbiddenChanges) {
      assertThrows(
          RunStore.Conflict.class,
          () ->
              store.update(
                  run.id,
                  run.revision,
                  next -> {
                    mutation.accept(next);
                    return next;
                  },
                  event("FORBIDDEN_CONTROL")));
    }
    assertEquals(1, store.events(run.id, 0, 100).size());
    RunState claimed = store.claim(run.id, Duration.ofSeconds(10));
    // id changes look up another run, so worker saves reject them as missing rather than CAS
    // conflicts.
    for (Consumer<RunState> mutation : forbiddenChanges.subList(1, forbiddenChanges.size())) {
      RunState invalid = claimed.copy();
      mutation.accept(invalid);
      assertThrows(
          RunStore.Conflict.class, () -> store.save(invalid, event("FORBIDDEN_SAVE"), true));
    }
    assertEquals(Json.hash(run.definition), Json.hash(store.get(run.id).definition));
    assertEquals(2, store.events(run.id, 0, 100).size());
  }

  @Test
  void externalControlPreservesLeaseAndFenceButInvalidatesAnOlderRevision() {
    RunState run = store.create(initial("external-control"));
    RunState claimed = store.claim(run.id, Duration.ofSeconds(10));
    RunState updated =
        store.update(
            run.id,
            claimed.revision,
            next -> {
              next.pauseRequested = true;
              next.leaseUntil = null;
              next.fence = 999;
              next.revision = -1;
              return next;
            },
            event("PAUSE_REQUESTED"));
    assertEquals(claimed.fence, updated.fence);
    assertEquals(claimed.leaseUntil, updated.leaseUntil);
    assertEquals(claimed.revision + 1, updated.revision);
    assertNull(store.claim(run.id, Duration.ofSeconds(10)));
    assertThrows(RunStore.Conflict.class, () -> store.save(claimed, event("STALE"), true));
    assertThrows(
        RunStore.Conflict.class,
        () -> store.update(run.id, claimed.revision, value -> value, event("STALE")));
    updated.status = RunStatus.PAUSED;
    RunState paused = store.save(updated, event("PAUSED"), true);
    assertNull(paused.leaseUntil);
    assertTrue(paused.pauseRequested);
  }

  @Test
  void fencingAndStoreClockRejectExpiredWorkersEvenWithForgedLeaseTime() {
    RunState run = store.create(initial("fencing"));
    RunState old = store.claim(run.id, Duration.ofSeconds(10));
    clock.advance(Duration.ofSeconds(10));
    old.leaseUntil = Instant.parse("2999-01-01T00:00:00Z");
    assertThrows(RunStore.Conflict.class, () -> store.assertLease(old));
    assertThrows(RunStore.Conflict.class, () -> store.save(old, event("TOO_LATE"), true));
    RunState replacement = store.claim(run.id, Duration.ofSeconds(10));
    assertEquals(old.fence + 1, replacement.fence);
    old.revision = replacement.revision;
    assertThrows(RunStore.Conflict.class, () -> store.save(old, event("OLD_FENCE"), true));
    assertEquals(
        List.of("RUN_CREATED", "LEASE_CLAIMED", "LEASE_CLAIMED"),
        store.events(run.id, 0, 100).stream().map(value -> value.event().type()).toList());
  }

  @Test
  void aWorkerCannotExtendItsLeaseBySavingANewLeaseTime() {
    RunState run = store.create(initial("lease-forgery"));
    RunState claimed = store.claim(run.id, Duration.ofSeconds(10));
    Instant realExpiry = claimed.leaseUntil;
    claimed.leaseUntil = realExpiry.plusSeconds(999);
    RunState saved = store.save(claimed, event("INTENT"), false);
    assertEquals(realExpiry, saved.leaseUntil);
    assertEquals(claimed.revision + 1, saved.revision);
    saved.memory.put("only-a-copy", true);
    assertFalse(store.get(run.id).memory.has("only-a-copy"));
  }

  @Test
  void usageAndStepCountersCannotBeResetThroughEitherUpdatePath() {
    RunState initial = initial("usage");
    initial.chargedTokens = 200;
    initial.modelCalls = 2;
    initial.toolCalls = 3;
    initial.steps = 4;
    RunState run = store.create(initial);
    List<Consumer<RunState>> reductions =
        List.of(
            next -> next.chargedTokens--,
            next -> next.modelCalls--,
            next -> next.toolCalls--,
            next -> next.steps--);
    for (Consumer<RunState> reduction : reductions) {
      assertThrows(
          RunStore.Conflict.class,
          () ->
              store.update(
                  run.id,
                  run.revision,
                  next -> {
                    reduction.accept(next);
                    return next;
                  },
                  event("RESET_USAGE")));
    }
    RunState claimed = store.claim(run.id, Duration.ofSeconds(10));
    for (Consumer<RunState> reduction : reductions) {
      RunState invalid = claimed.copy();
      reduction.accept(invalid);
      assertThrows(RunStore.Conflict.class, () -> store.save(invalid, event("RESET_USAGE"), true));
    }
    assertEquals(200, store.get(run.id).chargedTokens);
    assertEquals(2, store.events(run.id, 0, 100).size());
  }

  @Test
  void exactKnownModelSettlementIsAllowedOnlyAsAWorkerResultCommit() {
    RunState intent = modelIntent("known-settlement");
    assertThrows(
        RunStore.Conflict.class,
        () ->
            store.update(
                intent.id,
                intent.revision,
                next -> settle(next, "model-1", new Usage(40, 60, true), 600),
                event("HOST_SETTLEMENT")));
    RunState incorrect = settle(intent.copy(), "model-1", new Usage(40, 60, true), 599);
    assertThrows(
        RunStore.Conflict.class, () -> store.save(incorrect, event("INCORRECT_SETTLEMENT"), true));
    RunState exact = settle(intent.copy(), "model-1", new Usage(40, 60, true), 600);
    RunState completed = store.save(exact, event("MODEL_COMPLETED"), true);
    assertEquals(600, completed.chargedTokens);
    assertEquals(2, completed.modelCalls);
    assertNull(completed.pending);
  }

  @Test
  void unknownOrMismatchedModelResponseCannotReleaseAReservation() {
    RunState intent = modelIntent("invalid-settlement");
    RunState unknown = settle(intent.copy(), "model-1", Usage.unknown(), 500);
    assertThrows(RunStore.Conflict.class, () -> store.save(unknown, event("UNKNOWN_USAGE"), true));
    RunState wrongInvocation = settle(intent.copy(), "different-model", new Usage(0, 0, true), 500);
    assertThrows(
        RunStore.Conflict.class,
        () -> store.save(wrongInvocation, event("WRONG_INVOCATION"), true));
    RunState prepared = intent.copy();
    prepared.pending.phase = InvocationPhase.PREPARED;
    prepared = store.save(prepared, event("RESET_TO_PREPARED"), false);
    RunState withoutInFlightBarrier = settle(prepared, "model-1", new Usage(0, 0, true), 500);
    assertThrows(
        RunStore.Conflict.class,
        () -> store.save(withoutInFlightBarrier, event("NO_DISPATCH_BARRIER"), true));
    assertEquals(1_500, store.get(intent.id).chargedTokens);
  }

  @Test
  void failingMutationOrInvalidEventPublishesNeitherStateNorAuditEvent() {
    RunState run = store.create(initial("atomic-control"));
    assertThrows(
        IllegalStateException.class,
        () ->
            store.update(
                run.id,
                run.revision,
                next -> {
                  next.memory.put("partial", true);
                  throw new IllegalStateException("callback failed");
                },
                event("MUST_NOT_APPEAR")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.update(
                run.id,
                run.revision,
                next -> {
                  next.pauseRequested = true;
                  return next;
                },
                new RunEvent("", null, null, clock.instant(), Map.of())));
    assertEquals(1, store.get(run.id).revision);
    assertFalse(store.get(run.id).memory.has("partial"));
    assertFalse(store.get(run.id).pauseRequested);
    assertEquals(1, store.events(run.id, 0, 100).size());
  }

  @Test
  void readyOrderingBackoffAndCursorsMatchSqlBehavior() {
    RunState second = initial("second");
    second.id = "b";
    store.create(second);
    RunState first = initial("first");
    first.id = "a";
    store.create(first);
    RunState future = initial("future");
    future.id = "c";
    future.nextAttemptAt = clock.instant().plusSeconds(5);
    store.create(future);
    assertEquals(List.of("a", "b"), store.ready(10));
    assertNull(store.claim("c", Duration.ofSeconds(10)));
    RunState claimed = store.claim("a", Duration.ofSeconds(10));
    assertEquals("LEASE_CLAIMED", store.events("a", 1, 1).get(0).event().type());
    assertTrue(store.events("a", 2, 10).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.ready(0));
    assertThrows(IllegalArgumentException.class, () -> store.events(claimed.id, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> store.claim("b", Duration.ofNanos(1)));
  }

  @Test
  void concurrentClaimAndCasEachHaveOnlyOneWinner() throws Exception {
    RunState run = store.create(initial("claim-race"));
    List<RunState> claims = race(6, () -> store.claim(run.id, Duration.ofSeconds(10)));
    List<RunState> winners = claims.stream().filter(Objects::nonNull).toList();
    assertEquals(1, winners.size());
    RunState claimed = winners.get(0);
    List<Boolean> updates =
        race(
            6,
            () -> {
              try {
                store.update(
                    run.id,
                    claimed.revision,
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
    assertEquals(1, updates.stream().filter(Boolean::booleanValue).count());
    assertEquals(3, store.events(run.id, 0, 100).size());
  }

  @Test
  void unsupportedStateVersionIsRejectedInsteadOfSilentlyUpgraded() {
    RunState future = initial("future-version");
    future.schemaVersion = 2;
    assertThrows(RunStore.Conflict.class, () -> store.create(future));
    future.schemaVersion = 1;
    future.runtimeVersion = "2";
    assertThrows(RunStore.Conflict.class, () -> store.create(future));
    assertTrue(store.ready(10).isEmpty());
  }

  private RunState modelIntent(String key) {
    RunState run = store.create(initial(key));
    RunState claimed = store.claim(run.id, Duration.ofSeconds(10));
    claimed.pending = new RunState.Pending();
    claimed.pending.id = "model-1";
    claimed.pending.kind = "MODEL";
    claimed.pending.phase = InvocationPhase.IN_FLIGHT;
    claimed.pending.tokenReservation = 1_000;
    claimed.chargedTokens = 1_500;
    claimed.modelCalls = 2;
    return store.save(claimed, event("MODEL_INTENT"), false);
  }

  private RunState settle(RunState next, String invocation, Usage usage, long charge) {
    next.results.put(
        invocation,
        new StepResult(
            "MODEL",
            Json.tree(
                new ModelResponse(Message.text("assistant", "done"), FinishReason.FINAL, usage)),
            false));
    next.pending = null;
    next.chargedTokens = charge;
    return next;
  }

  private RunState initial(String key) {
    RunState state = new RunState();
    state.id = UUID.randomUUID().toString();
    state.creationKey = key;
    state.creationDigest = Json.hash(List.of("request", key));
    state.actor = new Actor("operator", "test-project", Set.of("*"));
    state.definition = new ProgramDefinition("agent", "1", "test", Json.object());
    state.budget = Budget.defaults();
    state.createdAt = clock.instant();
    state.deadline = clock.instant().plusSeconds(3_600);
    state.tools.add(
        new ToolDescriptor(
            "test:read",
            "test_read",
            "read",
            "internal",
            "read",
            "1",
            Json.read("{\"type\":\"object\"}"),
            ToolPolicy.readOnlyPolicy()));
    return state;
  }

  private RunEvent event(String type) {
    return new RunEvent(type, null, null, clock.instant(), Map.of());
  }

  private <T> List<T> race(int workers, Callable<T> operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(workers);
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
                    throw new IllegalStateException("Race timed out");
                  return operation.call();
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value;

    MutableClock(Instant value) {
      this.value = new AtomicReference<>(value);
    }

    void advance(Duration duration) {
      value.updateAndGet(previous -> previous.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return value.get();
    }
  }
}
