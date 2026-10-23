package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UnknownRetryControlTest {
  private static final Actor ACTOR = new Actor("operator", "unknown-write-test", Set.of("*"));

  enum Control {
    NONE,
    CANCEL,
    PAUSE,
    EXPIRE
  }

  @ParameterizedTest
  @EnumSource(Control.class)
  void uncertainWriteNeverRetriesOrLosesReconciliationEvenWhenItsPolicyIsRetrySafe(Control control)
      throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
    InMemoryRunStore store = new InMemoryRunStore(clock);
    AtomicInteger effects = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ToolRegistry registry =
        registry(
            new ToolPolicy(false, true, true, 3, Duration.ofMinutes(5).toMillis(), Set.of()),
            (tool, arguments, context) -> {
              effects.incrementAndGet();
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Unknown-write test was not released");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Unknown-write test was interrupted", interrupted);
              }
              throw new InvocationException(FailureKind.UNKNOWN, "WRITE_SUCCEEDED_CONNECTION_LOST");
            });
    ExecutorService callers = Executors.newSingleThreadExecutor();
    try (Harness harness = harness(store, registry, clock)) {
      RunState approved = approved(harness, "unknown-" + control, Duration.ofSeconds(30));
      String invocationId = approved.pending.id;
      Future<RunState> result = callers.submit(() -> harness.tick(approved.id));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      switch (control) {
        case CANCEL -> harness.cancel(approved.id, ACTOR);
        case PAUSE -> harness.pause(approved.id, ACTOR);
        case EXPIRE -> clock.advance(Duration.ofSeconds(31));
        case NONE -> {}
      }
      release.countDown();
      RunState unknown = result.get(5, TimeUnit.SECONDS);
      assertEquals(RunStatus.NEEDS_ATTENTION, unknown.status);
      assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
      assertEquals(invocationId, unknown.pending.id);
      assertEquals(1, unknown.pending.attempts);
      assertEquals(1, unknown.toolCalls);
      assertEquals(control == Control.CANCEL, unknown.cancelRequested);
      assertEquals(control == Control.PAUSE, unknown.pauseRequested);
      assertEquals(1, effects.get());
      assertFalse(store.ready(10).contains(unknown.id));
      assertFalse(
          store.events(unknown.id, 0, 100).stream()
              .anyMatch(event -> "RETRY_SCHEDULED".equals(event.event().type())));
      assertThrows(RunStore.Conflict.class, () -> harness.resume(unknown.id, ACTOR));
      assertEquals(InvocationPhase.UNKNOWN, harness.tick(unknown.id).pending.phase);
      assertEquals(
          1, effects.get(), "Inspecting an uncertain write must not repeat its remote effect");

      RunState reconciled =
          harness.reconcileTool(
              unknown.id,
              ACTOR,
              invocationId,
              new ToolResult(
                  Json.object().put("verified", true), false, "verified-remote-receipt"));
      assertNull(reconciled.pending);
      assertEquals(
          control == Control.CANCEL ? RunStatus.CANCELLED : RunStatus.PAUSED, reconciled.status);
      assertTrue(reconciled.results.get(invocationId).value().path("verified").asBoolean());
      assertEquals(1, effects.get());
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  void manualResumeCannotExceedTheToolsMaximumAttempts() {
    MutableClock clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
    InMemoryRunStore store = new InMemoryRunStore(clock);
    AtomicInteger calls = new AtomicInteger();
    ToolRegistry registry =
        registry(
            new ToolPolicy(false, true, false, 2, 10_000, Set.of()),
            (tool, arguments, context) -> {
              calls.incrementAndGet();
              throw new InvocationException(
                  FailureKind.PERMANENT, "REMOTE_REJECTED_WITHOUT_EFFECT");
            });
    try (Harness harness = harness(store, registry, clock)) {
      RunState approved = approved(harness, "manual-resume-attempt-limit", Duration.ofHours(1));
      RunState firstFailure = harness.tick(approved.id);
      assertEquals(RunStatus.NEEDS_ATTENTION, firstFailure.status);
      assertEquals(1, calls.get());
      assertEquals(InvocationPhase.PREPARED, firstFailure.pending.phase);
      harness.resume(approved.id, ACTOR);
      RunState secondFailure = harness.tick(approved.id);
      assertEquals(RunStatus.NEEDS_ATTENTION, secondFailure.status);
      assertEquals(2, calls.get());

      for (int i = 0; i < 3; i++) {
        try {
          harness.resume(approved.id, ACTOR);
          harness.tick(approved.id);
        } catch (RunStore.Conflict exhausted) {
          // Rejecting at resume itself is also a valid enforcement boundary.
        }
      }
      RunState exhausted = store.get(approved.id);
      assertEquals(2, calls.get(), "Host resume must not bypass maxAttempts");
      assertEquals(2, exhausted.pending.attempts);
      assertEquals(2, exhausted.toolCalls);
      assertFalse(exhausted.results.containsKey("write-1"));
    }
  }

  private Harness harness(InMemoryRunStore store, ToolRegistry registry, Clock clock) {
    Harness harness =
        new Harness(
            store,
            (request, context) -> {
              throw new AssertionError("Tool-only test must not call a model");
            },
            registry,
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            clock,
            Duration.ofMinutes(10));
    harness.registerProgram(
        "one-write",
        run ->
            run.results.containsKey("write-1")
                ? new CompleteAction(run.results.get("write-1").value())
                : new ToolAction("write-1", "write-node", "sandbox:write", Json.object()));
    return harness;
  }

  private ToolRegistry registry(ToolPolicy policy, ToolHandler handler) {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new ToolDescriptor(
            "sandbox:write",
            "sandbox_write",
            "Test write",
            "internal",
            "write",
            "1",
            Json.read("{\"type\":\"object\",\"additionalProperties\":false}"),
            policy),
        handler);
    return registry;
  }

  private RunState approved(Harness harness, String creationKey, Duration lifetime) {
    RunState created =
        harness.start(
            new ProgramDefinition("write-test", "1", "one-write", Json.object()),
            ACTOR,
            List.of("sandbox:write"),
            Budget.defaults(),
            lifetime,
            creationKey);
    harness.tick(created.id);
    RunState waiting = harness.tick(created.id);
    assertEquals(RunStatus.WAITING_APPROVAL, waiting.status);
    return harness.decide(created.id, ACTOR, waiting.approval.digest(), true, null);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value;

    MutableClock(Instant initial) {
      value = new AtomicReference<>(initial);
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
