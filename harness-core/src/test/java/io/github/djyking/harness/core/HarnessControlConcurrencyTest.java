package io.github.djyking.harness.core;

import static io.github.djyking.harness.core.HarnessRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.ScriptedModel;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class HarnessControlConcurrencyTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pausedPendingApprovalOrInputResumesTheSameWaitWithoutDecidingOrRenewing(boolean human) {
    var clock = clock();
    var store = new InMemoryRunStore(clock);
    AtomicInteger calls = new AtomicInteger();
    try (Harness harness =
        harness(
            store,
            new ScriptedModel(),
            registry(
                ToolPolicy.approvedWrite(),
                (tool, args, context) -> {
                  calls.incrementAndGet();
                  return ToolResult.success(args);
                }),
            clock,
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      harness.registerProgram(
          "human",
          run ->
              run.results.containsKey("input")
                  ? new CompleteAction(run.results.get("input").value())
                  : new WaitAction("input", "human", "Enter a literal answer"));
      RunState created =
          human
              ? harness.start(
                  new ProgramDefinition("human", "1", "human", Json.object()),
                  ACTOR,
                  List.of(),
                  Budget.defaults(),
                  Duration.ofHours(1),
                  "paused-human")
              : startTool(harness, "paused-tool");
      RunState waiting = drive(harness, created.id);
      RunStatus expected = human ? RunStatus.WAITING_INPUT : RunStatus.WAITING_APPROVAL;
      assertEquals(expected, waiting.status);
      Approval originalApproval = waiting.approval;
      String pendingDigest = Json.hash(waiting.pending);
      for (int cycle = 0; cycle < 3; cycle++) {
        RunState paused = harness.pause(created.id, ACTOR, waiting.revision);
        assertEquals(waiting.revision + 1, paused.revision);
        assertEquals(RunStatus.PAUSED, paused.status);
        clock.advance(Duration.ofMinutes(1));
        RunState resumed = harness.resume(created.id, ACTOR, paused.revision);
        assertEquals(paused.revision + 1, resumed.revision);
        waiting = resumed.status == RunStatus.QUEUED ? harness.tick(created.id) : resumed;
        assertEquals(expected, waiting.status);
        assertEquals(originalApproval, waiting.approval);
        assertEquals(pendingDigest, Json.hash(waiting.pending));
        assertEquals(created.deadline, waiting.deadline);
        assertEquals(created.budget, waiting.budget);
        assertFalse(waiting.pauseRequested);
        assertEquals(0, calls.get());
      }
      assertEquals(
          1,
          store.events(created.id, 0, 100).stream()
              .filter(event -> event.event().type().equals("APPROVAL_REQUESTED"))
              .count());
      harness.decide(
          created.id,
          ACTOR,
          waiting.revision,
          originalApproval.digest(),
          true,
          human ? "literal input" : null);
      RunState completed = drive(harness, created.id);
      assertEquals(RunStatus.COMPLETED, completed.status);
      assertEquals(human ? 0 : 1, calls.get());
      if (human) assertEquals("literal input", completed.output.path("input").asText());
    }
  }

  enum Boundary {
    INTENT,
    BARRIER,
    AFTER_BARRIER,
    RESULT,
    UNKNOWN
  }

  @ParameterizedTest
  @EnumSource(Boundary.class)
  void cancellationAtEveryCommitBoundaryConvergesWithoutRepeatingEffects(Boundary boundary) {
    InterleavingStore store = new InterleavingStore();
    AtomicReference<Harness> owner = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean fired = new AtomicBoolean();
    Runnable cancel =
        () -> {
          if (fired.compareAndSet(false, true)) owner.get().cancel(store.runId, ACTOR);
        };
    Telemetry telemetry =
        (context, operation, target) -> {
          // This is the precise old CI interleaving: cancel increments revision after the durable
          // barrier, before the former stale assertLease check. No scheduling or sleep is involved.
          if (boundary == Boundary.AFTER_BARRIER) cancel.run();
          return Telemetry.noop().start(context, operation, target);
        };
    ToolRegistry tools =
        registry(
            new ToolPolicy(false, false, false, 1, 5000, Set.of()),
            (tool, arguments, context) -> {
              calls.incrementAndGet();
              if (boundary == Boundary.UNKNOWN)
                throw new InvocationException(FailureKind.UNKNOWN, "REMOTE_WRITE_REPLY_LOST");
              return new ToolResult(arguments, false, "verified-receipt");
            });
    try (Harness harness =
        harness(
            store,
            new ScriptedModel(),
            tools,
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            telemetry)) {
      owner.set(harness);
      RunState run = startTool(harness, boundary.name());
      store.runId = run.id;
      store.beforeSave =
          event -> {
            if (boundary == Boundary.INTENT && event.type().equals("INVOCATION_PREPARED")
                || boundary == Boundary.BARRIER && event.type().equals("ATTEMPT_STARTED")
                || boundary == Boundary.RESULT && event.type().equals("TOOL_COMPLETED")
                || boundary == Boundary.UNKNOWN && event.type().equals("RUN_NEEDS_ATTENTION"))
              cancel.run();
          };
      RunState stopped = harness.tick(run.id);
      if (stopped.status == RunStatus.QUEUED) stopped = harness.tick(run.id);
      assertTrue(fired.get());
      assertTrue(stopped.cancelRequested);
      assertEquals(
          boundary == Boundary.UNKNOWN ? RunStatus.NEEDS_ATTENTION : RunStatus.CANCELLED,
          stopped.status);
      assertEquals(
          boundary == Boundary.RESULT || boundary == Boundary.UNKNOWN ? 1 : 0, calls.get());
      if (boundary == Boundary.UNKNOWN) {
        assertEquals(InvocationPhase.UNKNOWN, stopped.pending.phase);
        assertThrows(RunStore.Conflict.class, () -> harness.resume(run.id, ACTOR));
      } else if (boundary == Boundary.RESULT) {
        assertNull(stopped.pending);
        assertEquals("verified-receipt", stopped.receipts.get("call"));
        assertTrue(stopped.results.containsKey("call"));
      } else {
        assertEquals(InvocationPhase.PREPARED, stopped.pending.phase);
      }
      assertEquals(stopped.status, harness.tick(run.id).status);
      assertEquals(
          boundary == Boundary.RESULT || boundary == Boundary.UNKNOWN ? 1 : 0, calls.get());
    }
  }

  @Test
  void pauseArrivingAfterKnownResultBeforeCommitRetainsResultAndResumeDoesNotRepeat() {
    InterleavingStore store = new InterleavingStore();
    AtomicInteger calls = new AtomicInteger();
    try (Harness harness =
        harness(
            store,
            new ScriptedModel(),
            registry(
                ToolPolicy.readOnlyPolicy(),
                (tool, args, context) -> {
                  calls.incrementAndGet();
                  return ToolResult.success(args);
                }),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run = startTool(harness, "pause-result");
      AtomicBoolean fired = new AtomicBoolean();
      store.beforeSave =
          event -> {
            if (event.type().equals("TOOL_COMPLETED") && fired.compareAndSet(false, true))
              harness.pause(run.id, ACTOR);
          };
      harness.tick(run.id);
      RunState paused = harness.tick(run.id);
      assertEquals(RunStatus.PAUSED, paused.status);
      assertTrue(paused.results.containsKey("call"));
      harness.resume(run.id, ACTOR, paused.revision);
      assertEquals(RunStatus.COMPLETED, harness.tick(run.id).status);
      assertEquals(1, calls.get());
    }
  }

  @Test
  void expectedRevisionIsCheckedBeforeEveryControlAndAtomicallyAtUpdate() {
    InterleavingStore store = new InterleavingStore();
    try (Harness harness =
        harness(
            store,
            new ScriptedModel(),
            registry(ToolPolicy.approvedWrite(), (t, a, c) -> ToolResult.success(a)),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run = startTool(harness, "revision");
      RunState paused = harness.pause(run.id, ACTOR, run.revision);
      assertRejectedWithoutEvent(store, run.id, () -> harness.pause(run.id, ACTOR, run.revision));
      assertRejectedWithoutEvent(store, run.id, () -> harness.cancel(run.id, ACTOR, run.revision));
      assertRejectedWithoutEvent(store, run.id, () -> harness.resume(run.id, ACTOR, run.revision));
      harness.resume(run.id, ACTOR, paused.revision);
      harness.tick(run.id);
      RunState waiting = harness.tick(run.id);
      assertRejectedWithoutEvent(
          store,
          run.id,
          () ->
              harness.decide(
                  run.id, ACTOR, waiting.revision - 1, waiting.approval.digest(), true, null));
      assertRejectedWithoutEvent(
          store,
          run.id,
          () ->
              harness.reconcileTool(
                  run.id,
                  ACTOR,
                  waiting.revision - 1,
                  "call",
                  new ToolResult(Json.object(), false, "receipt")));
      // A competing control wins after the method's read/precheck and before its store CAS.
      store.beforeUpdate =
          () -> {
            store.beforeUpdate = null;
            harness.cancel(run.id, ACTOR, waiting.revision);
          };
      assertThrows(
          RunStore.Conflict.class,
          () ->
              harness.decide(
                  run.id, ACTOR, waiting.revision, waiting.approval.digest(), true, null));
      assertEquals(RunStatus.CANCELLED, store.get(run.id).status);
      assertEquals("PENDING", store.get(run.id).approval.status());
    }
  }

  @Test
  void explicitRunIdIsCanonicalAndIdempotencyReturnsTheOriginalRun() {
    try (Harness harness =
        new Harness(new InMemoryRunStore(), new ScriptedModel(), new ToolRegistry())) {
      String first = UUID.randomUUID().toString();
      RunState run =
          harness.startWithId(
              first,
              agent("question", 1),
              ACTOR,
              List.of(),
              Budget.defaults(),
              Duration.ofHours(1),
              "creation");
      RunState replay =
          harness.startWithId(
              UUID.randomUUID().toString(),
              agent("question", 1),
              ACTOR,
              List.of(),
              Budget.defaults(),
              Duration.ofHours(1),
              "creation");
      assertEquals(first, run.id);
      assertEquals(first, replay.id);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              harness.startWithId(
                  "1-1-1-1-1",
                  agent("question", 1),
                  ACTOR,
                  List.of(),
                  Budget.defaults(),
                  Duration.ofHours(1),
                  "invalid"));
      assertThrows(
          RunStore.Conflict.class,
          () ->
              harness.startWithId(
                  first,
                  agent("different", 1),
                  ACTOR,
                  List.of(),
                  Budget.defaults(),
                  Duration.ofHours(1),
                  "different"));
    }
  }

  @Test
  void waitingExpiryIsIdempotentAndDoesNotHideUnknownEffects() {
    var clock = clock();
    InMemoryRunStore store = new InMemoryRunStore(clock);
    try (Harness harness =
        harness(
            store,
            new ScriptedModel(),
            registry(
                ToolPolicy.approvedWrite(),
                (t, a, c) -> {
                  throw new InvocationException(FailureKind.UNKNOWN, "WRITE_REPLY_LOST");
                }),
            clock,
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState waiting = startTool(harness, "expiry");
      harness.tick(waiting.id);
      waiting = harness.tick(waiting.id);
      RunState unknown = startTool(harness, "unknown-expiry");
      harness.tick(unknown.id);
      unknown = harness.tick(unknown.id);
      harness.decide(unknown.id, ACTOR, unknown.revision, unknown.approval.digest(), true, null);
      unknown = harness.tick(unknown.id);
      clock.advance(Duration.ofHours(2));
      assertEquals(List.of(waiting.id), store.expirable(10));
      RunState expired = harness.expire(waiting.id);
      assertEquals(RunStatus.EXPIRED, expired.status);
      assertEquals(expired.revision, harness.expire(waiting.id).revision);
      assertEquals(RunStatus.NEEDS_ATTENTION, harness.expire(unknown.id).status);
      assertEquals(InvocationPhase.UNKNOWN, store.get(unknown.id).pending.phase);
      assertTrue(store.expirable(10).isEmpty());
    }
  }

  private void assertRejectedWithoutEvent(RunStore store, String id, Runnable action) {
    RunState before = store.get(id);
    int events = store.events(id, 0, 100).size();
    assertThrows(RunStore.Conflict.class, action::run);
    assertEquals(before.revision, store.get(id).revision);
    assertEquals(events, store.events(id, 0, 100).size());
  }

  private static class InterleavingStore implements RunStore {
    private final InMemoryRunStore delegate = new InMemoryRunStore();
    private String runId;
    private java.util.function.Consumer<RunEvent> beforeSave = ignored -> {};
    private Runnable beforeUpdate;

    public RunState create(RunState run) {
      return delegate.create(run);
    }

    public RunState get(String id) {
      return delegate.get(id);
    }

    public RunState claim(String id, Duration lease) {
      return delegate.claim(id, lease);
    }

    public List<String> ready(int limit) {
      return delegate.ready(limit);
    }

    public void assertLease(RunState run) {
      delegate.assertLease(run);
    }

    public RunState save(RunState run, RunEvent event, boolean release) {
      beforeSave.accept(event);
      return delegate.save(run, event, release);
    }

    public RunState update(
        String id, long revision, UnaryOperator<RunState> mutation, RunEvent event) {
      if (beforeUpdate != null) beforeUpdate.run();
      return delegate.update(id, revision, mutation, event);
    }

    public List<StoredEvent> events(String id, long after, int limit) {
      return delegate.events(id, after, limit);
    }
  }
}
