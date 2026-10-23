package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvocationGateDispatchTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aControlAcceptedDuringTheGateWaitCannotDispatchToolOrModel(boolean model) {
    var clock = Clock.systemUTC();
    var store = new InMemoryRunStore(clock);
    var actor = new Actor("operator", "gate-test", Set.of("*"));
    var owner = new AtomicReference<Harness>();
    var calls = new AtomicInteger();
    var released = new AtomicBoolean();
    var tools = new ToolRegistry();
    tools.register(
        new ToolDescriptor(
            "test:read",
            "test_read",
            "test",
            "local",
            "read",
            "1",
            Json.object().put("type", "object"),
            new ToolPolicy(true, false, false, 1, 5000, Set.of())),
        (tool, args, context) -> {
          calls.incrementAndGet();
          return ToolResult.success(args);
        });
    InvocationGate gate =
        (run, timeout) ->
            new InvocationGate.Reservation() {
              public boolean granted() {
                return true;
              }

              public Instant retryAt() {
                return null;
              }

              public <T> T invoke(Callable<T> operation) throws Exception {
                // Deterministic equivalent of a control arriving while the SQL permit lock was
                // awaited.
                owner.get().pause(run.id, actor);
                try {
                  return operation.call();
                } catch (InvocationGate.NotDispatched failure) {
                  released.set(true);
                  throw failure;
                }
              }

              public void abandon() {}
            };
    try (var harness =
        new Harness(
            store,
            (request, context) -> {
              calls.incrementAndGet();
              throw new AssertionError("Must not dispatch");
            },
            tools,
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            clock,
            Duration.ofSeconds(10),
            new InvocationExecutor(1, 2),
            gate)) {
      owner.set(harness);
      harness.registerProgram(
          "test",
          run ->
              model
                  ? new ModelAction(
                      "call",
                      "node",
                      new ModelRequest(
                          "request",
                          new ModelProfile("test", "test", "test", 16000, 128, 5000, Json.object()),
                          List.of(Message.text("user", "test")),
                          List.of()))
                  : new ToolAction("call", "node", "test:read", Json.object()));
      var run =
          harness.start(
              new ProgramDefinition("test", "1", "test", Json.object()),
              actor,
              model ? List.of() : List.of("test:read"),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "local-gate");
      harness.tick(run.id);
      var stopped = harness.tick(run.id);
      assertEquals(RunStatus.PAUSED, stopped.status);
      assertEquals(InvocationPhase.PREPARED, stopped.pending.phase);
      assertEquals(0, calls.get());
      assertTrue(released.get());
      assertEquals(
          1,
          stopped.pending.attempts); // Durable attempt barrier exists; no remote effect occurred.
    }
  }
}
