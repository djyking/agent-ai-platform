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

class ApprovalDispatchTest {
  @Test
  void anApprovalThatExpiresWhileWaitingForTheExecutorCannotDispatchAWrite() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
    InMemoryRunStore store = new InMemoryRunStore(clock);
    Actor actor = new Actor("operator", "approval-dispatch-test", Set.of("*"));
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondIntentCommitted = new CountDownLatch(1);
    AtomicReference<String> secondId = new AtomicReference<>();
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new ToolDescriptor(
            "sandbox:write",
            "sandbox_write",
            "Approved test write",
            "internal",
            "write",
            "1",
            Json.read(
                "{\"type\":\"object\",\"required\":[\"target\"],\"properties\":{\"target\":{\"enum\":[\"first\",\"second\"]}},\"additionalProperties\":false}"),
            new ToolPolicy(false, true, false, 1, Duration.ofMinutes(40).toMillis(), Set.of())),
        (tool, arguments, context) -> {
          if ("first".equals(arguments.path("target").asText())) {
            firstCalls.incrementAndGet();
            firstEntered.countDown();
            try {
              if (!releaseFirst.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Test did not release first write");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError("First write interrupted", interrupted);
            }
          } else secondCalls.incrementAndGet();
          return new ToolResult(Json.object().put("written", true), false, "test-receipt");
        });
    Telemetry telemetry =
        (context, operation, target) -> {
          // This is after the durable dispatch barrier and the original approval check.
          if (context.runId().equals(secondId.get())) secondIntentCommitted.countDown();
          return Telemetry.noop().start(context, operation, target);
        };
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try (Harness harness =
        new Harness(
            store,
            (request, context) -> {
              throw new AssertionError("A tool-only program must not call a model");
            },
            registry,
            AccessPolicy.actorPermissions(),
            telemetry,
            clock,
            Duration.ofMinutes(40),
            new InvocationExecutor(1, 4))) {
      harness.registerProgram(
          "approved-write",
          run ->
              run.results.containsKey("write-1")
                  ? new CompleteAction(run.results.get("write-1").value())
                  : new ToolAction(
                      "write-1", "write-node", "sandbox:write", run.definition.spec()));
      RunState first = approved(harness, actor, "first");
      RunState second = approved(harness, actor, "second");
      secondId.set(second.id);
      assertEquals(clock.instant().plus(Duration.ofMinutes(30)), second.approval.expiresAt());

      Future<RunState> firstResult = callers.submit(() -> harness.tick(first.id));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      Future<RunState> secondResult = callers.submit(() -> harness.tick(second.id));
      assertTrue(secondIntentCommitted.await(5, TimeUnit.SECONDS));
      assertEquals(InvocationPhase.IN_FLIGHT, store.get(second.id).pending.phase);
      assertEquals(0, secondCalls.get());

      // The first write owns the only executor slot. Both leases and run lifetimes
      // remain live, but the second write's thirty-minute approval expires in its wait.
      clock.advance(Duration.ofMinutes(31));
      releaseFirst.countDown();

      RunState firstCommitted = firstResult.get(5, TimeUnit.SECONDS);
      RunState secondDenied = secondResult.get(5, TimeUnit.SECONDS);
      assertEquals(1, firstCalls.get());
      assertNotNull(firstCommitted.results.get("write-1"));
      assertEquals(0, secondCalls.get(), "Expired queued approval must prevent the remote write");
      assertEquals(RunStatus.NEEDS_ATTENTION, secondDenied.status);
      assertEquals("APPROVAL_NOT_VALID_AT_DISPATCH", secondDenied.stopReason);
      assertEquals(InvocationPhase.PREPARED, secondDenied.pending.phase);
      assertFalse(secondDenied.results.containsKey("write-1"));
      assertTrue(secondDenied.deadline.isAfter(clock.instant()));
    } finally {
      releaseFirst.countDown();
      callers.shutdownNow();
    }
  }

  private RunState approved(Harness harness, Actor actor, String target) {
    RunState created =
        harness.start(
            new ProgramDefinition(
                "approved-write-test", "1", "approved-write", Json.object().put("target", target)),
            actor,
            List.of("sandbox:write"),
            Budget.defaults(),
            Duration.ofHours(1),
            "creation-" + target);
    harness.tick(created.id);
    RunState waiting = harness.tick(created.id);
    assertEquals(RunStatus.WAITING_APPROVAL, waiting.status);
    return harness.decide(created.id, actor, waiting.approval.digest(), true, null);
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
