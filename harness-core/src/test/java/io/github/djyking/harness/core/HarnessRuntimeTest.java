package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.MutableClock;
import io.github.djyking.harness.core.testing.ScriptedModel;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HarnessRuntimeTest {
  static final Actor ACTOR = new Actor("alice", "project", Set.of("*"));
  static final ModelProfile PROFILE =
      new ModelProfile("default", "test", "recorded", 32000, 256, 5000, Json.object());
  static final ProgramDefinition TOOL_PROGRAM =
      new ProgramDefinition("test", "1", "single", Json.object());

  static ToolDescriptor descriptor(ToolPolicy policy) {
    return new ToolDescriptor(
        "test/change",
        "test_change",
        "test resource",
        "internal",
        "change",
        "1",
        Json.read(
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"],\"additionalProperties\":false}"),
        policy);
  }

  static ProgramDefinition agent(String prompt, int turns) {
    return new ProgramDefinition(
        "qa",
        "1",
        "agent",
        Json.tree(
            new AgentProgram.AgentSpec(PROFILE, List.of(Message.text("user", prompt)), turns)));
  }

  static Harness harness(
      RunStore store,
      ModelGateway model,
      ToolRegistry tools,
      Clock clock,
      AccessPolicy access,
      Telemetry telemetry) {
    Harness harness =
        new Harness(store, model, tools, access, telemetry, clock, Duration.ofSeconds(10));
    single(harness);
    return harness;
  }

  static void single(Harness harness) {
    harness.registerProgram(
        "single",
        run ->
            run.results.containsKey("call")
                ? new CompleteAction(run.results.get("call").value())
                : new ToolAction("call", "change", "test/change", Json.object().put("value", 7)));
  }

  static RunState startTool(Harness h, String key) {
    return h.start(
        TOOL_PROGRAM, ACTOR, List.of("test/change"), Budget.defaults(), Duration.ofHours(1), key);
  }

  static RunState drive(Harness h, String id) {
    RunState state = h.get(id, ACTOR);
    for (int i = 0; i < 60 && state.status == RunStatus.QUEUED; i++) state = h.tick(id);
    return state;
  }

  static ToolRegistry registry(ToolPolicy policy, ToolHandler handler) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(descriptor(policy), handler);
    return tools;
  }

  static MutableClock clock() {
    return new MutableClock(Instant.parse("2026-09-15T00:00:00Z"));
  }

  @Test
  void nativeToolCallIdsAndKnownUsageSurviveTheFullAgentLoop() {
    ScriptedModel model =
        new ScriptedModel(
            ScriptedModel.calls(
                new ToolCall("native-42", "test_change", Json.object().put("value", 7))),
            ScriptedModel.answer("done"));
    AtomicInteger calls = new AtomicInteger();
    ToolRegistry tools =
        registry(
            ToolPolicy.readOnlyPolicy(),
            (tool, args, context) -> {
              calls.incrementAndGet();
              assertTrue(context.invocationId().startsWith(context.runId() + ":"));
              return ToolResult.success(args);
            });
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            model,
            tools,
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run =
          h.start(
              agent("question", 2),
              ACTOR,
              List.of("test/change"),
              new Budget(10000, 2, 1, 50),
              Duration.ofMinutes(1),
              "native");
      RunState done = drive(h, run.id);
      assertEquals(RunStatus.COMPLETED, done.status);
      assertEquals(60, done.chargedTokens);
      assertEquals(2, done.modelCalls);
      assertEquals(1, calls.get());
      Message reply =
          model.requests().get(1).messages().stream()
              .filter(m -> "tool".equals(m.role()))
              .findFirst()
              .orElseThrow();
      assertEquals("native-42", reply.toolCallId());
      assertEquals("done", done.output.path("content").asText());
    }
  }

  @Test
  void invocationIdentityIsDistinctAcrossRunsAndConfigIsFrozen() {
    ScriptedModel model =
        new ScriptedModel(ScriptedModel.answer("one"), ScriptedModel.answer("two"));
    ProgramDefinition definition = agent("original", 1);
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            model,
            new ToolRegistry(),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState one =
          h.start(definition, ACTOR, List.of(), Budget.defaults(), Duration.ofMinutes(1), "one");
      RunState again =
          h.start(definition, ACTOR, List.of(), Budget.defaults(), Duration.ofMinutes(1), "one");
      assertEquals(one.id, again.id);
      assertThrows(
          RunStore.Conflict.class,
          () ->
              h.start(
                  agent("changed", 1),
                  ACTOR,
                  List.of(),
                  Budget.defaults(),
                  Duration.ofMinutes(1),
                  "one"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) definition.spec()).put("extra", true);
      assertFalse(h.get(one.id, ACTOR).definition.spec().has("extra"));
      RunState two =
          h.start(
              agent("other", 1), ACTOR, List.of(), Budget.defaults(), Duration.ofMinutes(1), "two");
      assertEquals("one", drive(h, one.id).output.path("content").asText());
      assertEquals("two", drive(h, two.id).output.path("content").asText());
      assertNotEquals(
          model.requests().get(0).invocationId(), model.requests().get(1).invocationId());
    }
  }

  @Test
  void exactApprovalRejectsWrongDigestAndExecutesAtMostOnce() {
    AtomicInteger effects = new AtomicInteger();
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            registry(
                ToolPolicy.approvedWrite(),
                (t, a, c) -> {
                  effects.incrementAndGet();
                  return new ToolResult(a, false, "receipt-1");
                }),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState waiting = drive(h, startTool(h, "approval").id);
      assertEquals(RunStatus.WAITING_APPROVAL, waiting.status);
      assertEquals(0, effects.get());
      assertThrows(
          RunStore.Conflict.class, () -> h.decide(waiting.id, ACTOR, "tampered", true, null));
      h.decide(waiting.id, ACTOR, waiting.approval.digest(), true, null);
      assertThrows(
          RunStore.Conflict.class,
          () -> h.decide(waiting.id, ACTOR, waiting.approval.digest(), true, null));
      RunState done = drive(h, waiting.id);
      assertEquals(RunStatus.COMPLETED, done.status);
      assertEquals(1, effects.get());
      assertEquals("receipt-1", done.receipts.get("call"));
    }
  }

  @Test
  void rejectedOrExpiredApprovalCannotDispatch() {
    MutableClock clock = clock();
    AtomicInteger effects = new AtomicInteger();
    try (Harness h =
        harness(
            new InMemoryRunStore(clock),
            new ScriptedModel(),
            registry(
                ToolPolicy.approvedWrite(),
                (t, a, c) -> {
                  effects.incrementAndGet();
                  return ToolResult.success(a);
                }),
            clock,
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState rejected = drive(h, startTool(h, "reject").id);
      h.decide(rejected.id, ACTOR, rejected.approval.digest(), false, "no");
      assertEquals(RunStatus.FAILED, h.tick(rejected.id).status);
      RunState expired = drive(h, startTool(h, "expire").id);
      clock.advance(Duration.ofMinutes(31));
      assertThrows(
          RunStore.Conflict.class,
          () -> h.decide(expired.id, ACTOR, expired.approval.digest(), true, null));
      assertEquals(0, effects.get());
    }
  }

  @Test
  void approvedToolContractReplacementAndRevokedPermissionAreRechecked() {
    AtomicInteger effects = new AtomicInteger();
    AtomicBoolean revoked = new AtomicBoolean();
    ToolHandler handler =
        (t, a, c) -> {
          effects.incrementAndGet();
          return ToolResult.success(a);
        };
    ToolRegistry tools = registry(ToolPolicy.approvedWrite(), handler);
    AccessPolicy policy =
        (actor, permission, resource) -> {
          if (revoked.get() && permission.startsWith("tool:"))
            throw new InvocationException(FailureKind.DENIED, "REVOKED");
        };
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            tools,
            Clock.systemUTC(),
            policy,
            Telemetry.noop())) {
      RunState changed = drive(h, startTool(h, "change").id);
      h.decide(changed.id, ACTOR, changed.approval.digest(), true, null);
      ToolDescriptor d = descriptor(ToolPolicy.approvedWrite());
      tools.replace(
          new ToolDescriptor(
              d.key(),
              d.modelName(),
              d.description(),
              d.adapter(),
              d.remoteName(),
              "2",
              d.inputSchema(),
              d.policy()),
          handler);
      assertEquals("TOOL_CONTRACT_CHANGED", h.tick(changed.id).stopReason);
      tools.replace(d, handler);
      RunState denied = drive(h, startTool(h, "revoke").id);
      h.decide(denied.id, ACTOR, denied.approval.digest(), true, null);
      revoked.set(true);
      assertEquals("REVOKED", h.tick(denied.id).stopReason);
      assertEquals(0, effects.get());
    }
  }

  @Test
  void lostWriteThenCancelAndExpiryRetainsExplicitReconciliation() {
    MutableClock clock = clock();
    AtomicInteger effects = new AtomicInteger();
    ToolRegistry tools =
        registry(
            ToolPolicy.approvedWrite(),
            (t, a, c) -> {
              effects.incrementAndGet();
              throw new InvocationException(FailureKind.UNKNOWN, "WRITE_RESPONSE_LOST");
            });
    try (Harness h =
        harness(
            new InMemoryRunStore(clock),
            new ScriptedModel(),
            tools,
            clock,
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState waiting = drive(h, startTool(h, "unknown").id);
      h.decide(waiting.id, ACTOR, waiting.approval.digest(), true, null);
      RunState unknown = h.tick(waiting.id);
      assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
      h.cancel(waiting.id, ACTOR);
      clock.advance(Duration.ofHours(2));
      assertThrows(RunStore.Conflict.class, () -> h.resume(waiting.id, ACTOR));
      RunState reconciled =
          h.reconcileTool(
              waiting.id,
              ACTOR,
              "call",
              new ToolResult(Json.object().put("verified", true), false, "verified-receipt"));
      assertEquals(RunStatus.CANCELLED, reconciled.status);
      assertEquals(1, effects.get());
      assertEquals("verified-receipt", reconciled.receipts.get("call"));
    }
  }

  @Test
  void safeReadRetriesUseSameInvocationNewAttemptAndCountEveryAttempt() {
    MutableClock clock = clock();
    List<ExecutionContext> contexts = new CopyOnWriteArrayList<>();
    ToolRegistry tools =
        registry(
            ToolPolicy.readOnlyPolicy(),
            (t, a, c) -> {
              contexts.add(c);
              if (contexts.size() == 1)
                throw new InvocationException(FailureKind.TRANSIENT, "SERVER_BUSY");
              return ToolResult.success(a);
            });
    try (Harness h =
        harness(
            new InMemoryRunStore(clock),
            new ScriptedModel(),
            tools,
            clock,
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run = startTool(h, "retry");
      h.tick(run.id);
      RunState retry = h.tick(run.id);
      assertEquals(RunStatus.QUEUED, retry.status);
      assertEquals(1, retry.toolCalls);
      assertTrue(retry.nextAttemptAt.isAfter(clock.instant()));
      h.tick(run.id);
      assertEquals(1, contexts.size());
      clock.advance(Duration.ofSeconds(1));
      assertEquals(RunStatus.COMPLETED, drive(h, run.id).status);
      assertEquals(2, h.get(run.id, ACTOR).toolCalls);
      assertEquals(contexts.get(0).invocationId(), contexts.get(1).invocationId());
      assertNotEquals(contexts.get(0).attemptId(), contexts.get(1).attemptId());
    }
  }

  @Test
  void pauseDuringCallPreservesCommittedReceiptAndOriginalBudget() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ToolRegistry tools =
        registry(
            ToolPolicy.readOnlyPolicy(),
            (t, a, c) -> {
              entered.countDown();
              await(release);
              return new ToolResult(a, false, "completed-while-pausing");
            });
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            tools,
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run = startTool(h, "pause");
      h.tick(run.id);
      CompletableFuture<RunState> execution = CompletableFuture.supplyAsync(() -> h.tick(run.id));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      h.pause(run.id, ACTOR);
      release.countDown();
      RunState paused = execution.get(3, TimeUnit.SECONDS);
      assertEquals(RunStatus.PAUSED, paused.status);
      assertNull(paused.pending);
      assertEquals(1, paused.toolCalls);
      h.resume(run.id, ACTOR);
      RunState done = drive(h, run.id);
      assertEquals(RunStatus.COMPLETED, done.status);
      assertEquals(run.deadline, done.deadline);
      assertEquals(1, done.toolCalls);
      assertEquals("completed-while-pausing", done.receipts.get("call"));
    } finally {
      release.countDown();
    }
  }

  @Test
  void queuedCallRechecksCancellationAtActualDispatch() throws Exception {
    queuedControl(false);
  }

  @Test
  void queuedCallRechecksAuthorizationAtActualDispatch() throws Exception {
    queuedControl(true);
  }

  private void queuedControl(boolean revoke) throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        secondAttempt = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger(), spans = new AtomicInteger();
    AtomicBoolean revoked = new AtomicBoolean();
    ToolRegistry tools =
        registry(
            ToolPolicy.readOnlyPolicy(),
            (t, a, c) -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                entered.countDown();
                await(release);
              }
              return ToolResult.success(a);
            });
    AccessPolicy access =
        (a, p, r) -> {
          if (revoked.get() && p.startsWith("tool:"))
            throw new InvocationException(FailureKind.DENIED, "REVOKED");
        };
    Telemetry telemetry =
        (c, o, t) -> {
          if (spans.incrementAndGet() == 2) secondAttempt.countDown();
          return Telemetry.noop().start(c, o, t);
        };
    try (Harness h =
        new Harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            tools,
            access,
            telemetry,
            Clock.systemUTC(),
            Duration.ofSeconds(10),
            new InvocationExecutor(1, 2))) {
      single(h);
      RunState one = startTool(h, "queue-one"), two = startTool(h, "queue-two");
      h.tick(one.id);
      h.tick(two.id);
      CompletableFuture<RunState> first = CompletableFuture.supplyAsync(() -> h.tick(one.id));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      CompletableFuture<RunState> second = CompletableFuture.supplyAsync(() -> h.tick(two.id));
      assertTrue(secondAttempt.await(3, TimeUnit.SECONDS));
      if (revoke) revoked.set(true);
      else h.cancel(two.id, ACTOR);
      release.countDown();
      first.get(3, TimeUnit.SECONDS);
      RunState stopped = second.get(3, TimeUnit.SECONDS);
      assertEquals(1, calls.get());
      assertEquals(revoke ? RunStatus.NEEDS_ATTENTION : RunStatus.CANCELLED, stopped.status);
      assertEquals(InvocationPhase.PREPARED, stopped.pending.phase);
    } finally {
      release.countDown();
    }
  }

  @Test
  void handlerIgnoringDeadlineCannotBlockTickAndUnknownWriteIsNotRepeated() {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    ToolPolicy policy = new ToolPolicy(false, false, false, 1, 100, Set.of());
    ToolRegistry tools =
        registry(
            policy,
            (t, a, c) -> {
              calls.incrementAndGet();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                }
              }
              return ToolResult.success(a);
            });
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            tools,
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run = startTool(h, "timeout");
      h.tick(run.id);
      RunState unknown = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> h.tick(run.id));
      assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
      assertEquals(RunStatus.NEEDS_ATTENTION, unknown.status);
      h.tick(run.id);
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void brokenTelemetryCallbacksDoNotLoseKnownSuccessfulResult() {
    Telemetry broken =
        (c, o, t) ->
            new Telemetry.Span() {
              public void outcome(String code) {
                throw new IllegalStateException("exporter failed");
              }

              public <T> Callable<T> wrap(Callable<T> task) {
                throw new IllegalStateException("context unavailable");
              }

              public void close() {
                throw new IllegalStateException("exporter failed");
              }
            };
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(ScriptedModel.answer("success")),
            new ToolRegistry(),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            broken)) {
      RunState run =
          h.start(
              agent("question", 1),
              ACTOR,
              List.of(),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "telemetry");
      assertEquals(RunStatus.COMPLETED, drive(h, run.id).status);
      assertEquals(30, h.get(run.id, ACTOR).chargedTokens);
    }
  }

  @Test
  void unknownModelUsageKeepsReservationAndBudgetSurvivesResume() {
    ScriptedModel model =
        new ScriptedModel(
            new ModelResponse(
                Message.text("assistant", "done"), FinishReason.FINAL, Usage.unknown()));
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            model,
            new ToolRegistry(),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run =
          h.start(
              agent("question", 1),
              ACTOR,
              List.of(),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "usage");
      RunState prepared = h.tick(run.id);
      long reservation = ContextWindow.reserve(prepared.pending.modelRequest);
      h.tick(run.id);
      h.pause(run.id, ACTOR);
      h.resume(run.id, ACTOR);
      RunState done = drive(h, run.id);
      assertEquals(RunStatus.COMPLETED, done.status);
      assertEquals(reservation, done.chargedTokens);
      assertEquals(1, done.modelCalls);
    }
  }

  @Test
  void insufficientTokenBudgetDoesNotDispatchModel() {
    ScriptedModel model = new ScriptedModel();
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            model,
            new ToolRegistry(),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run =
          h.start(
              agent("question", 1),
              ACTOR,
              List.of(),
              new Budget(1, 1, 1, 10),
              Duration.ofMinutes(1),
              "budget");
      assertEquals(RunStatus.BUDGET_EXCEEDED, drive(h, run.id).status);
      assertTrue(model.requests().isEmpty());
    }
  }

  @Test
  void contradictoryFinalResponseCannotExecuteTools() {
    ScriptedModel model =
        new ScriptedModel(
            new ModelResponse(
                new Message(
                    "assistant",
                    "done",
                    List.of(new ToolCall("native", "test_change", Json.object().put("value", 7))),
                    null),
                FinishReason.FINAL,
                Usage.unknown()));
    AtomicInteger effects = new AtomicInteger();
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            model,
            registry(
                ToolPolicy.readOnlyPolicy(),
                (t, a, c) -> {
                  effects.incrementAndGet();
                  return ToolResult.success(a);
                }),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run =
          h.start(
              agent("question", 1),
              ACTOR,
              List.of("test/change"),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "invalid-final");
      RunState done = drive(h, run.id);
      assertEquals(RunStatus.FAILED, done.status);
      assertEquals("MODEL_FINAL_INVALID", done.stopReason);
      assertEquals(0, effects.get());
    }
  }

  @Test
  void projectAndOwnerScopeAreEnforced() {
    try (Harness h =
        harness(
            new InMemoryRunStore(),
            new ScriptedModel(),
            new ToolRegistry(),
            Clock.systemUTC(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop())) {
      RunState run =
          h.start(
              agent("question", 1),
              ACTOR,
              List.of(),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "scope");
      assertThrows(
          InvocationException.class, () -> h.get(run.id, new Actor("alice", "other", Set.of("*"))));
      assertThrows(
          InvocationException.class,
          () -> h.get(run.id, new Actor("bob", "project", Set.of("run:read"))));
      assertEquals(
          run.id, h.get(run.id, new Actor("admin", "project", Set.of("run:read", "run:admin"))).id);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("test latch timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
