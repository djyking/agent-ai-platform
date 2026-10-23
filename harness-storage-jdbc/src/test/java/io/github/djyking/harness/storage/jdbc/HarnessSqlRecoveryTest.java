package io.github.djyking.harness.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.AgentProgram;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Harness;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import io.github.djyking.harness.core.RunStore;
import io.github.djyking.harness.core.ToolRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runtime + durable store integration; these tests make no external service calls. */
class HarnessSqlRecoveryTest {
  private static final Actor ACTOR = new Actor("operator", "sql-test", Set.of("*"));
  private JdbcDataSource dataSource;
  private JdbcRunStore store;

  @BeforeEach
  void database() {
    dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
  }

  @Test
  void knownModelUsageSettlesReservationAndCompletedStateSurvivesRestart() {
    AtomicInteger calls = new AtomicInteger();
    ModelGateway model =
        (request, context) -> {
          calls.incrementAndGet();
          return new ModelResponse(
              Message.text("assistant", "SQL durable answer"),
              FinishReason.FINAL,
              new Usage(17, 5, true));
        };
    ModelProfile profile =
        new ModelProfile(
            "development-model", "test", "scripted", 16_384, 128, 5_000, Json.object());
    ProgramDefinition definition =
        new ProgramDefinition(
            "question-answering",
            "prompt-v1",
            "agent",
            Json.tree(
                new AgentProgram.AgentSpec(
                    profile, List.of(Message.text("user", "Test persistence")), 2)));
    String id;
    Instant deadline;
    try (Harness first = harness(store, model, new ToolRegistry())) {
      RunState run =
          first.start(
              definition, ACTOR, List.of(), Budget.defaults(), Duration.ofMinutes(10), "model-sql");
      id = run.id;
      deadline = run.deadline;
      assertNotNull(first.tick(id).pending);
      RunState settled = first.tick(id);
      assertNull(settled.pending);
      assertEquals(22, settled.chargedTokens);
      assertEquals(1, settled.modelCalls);
      assertEquals(1, settled.results.size());
    }
    JdbcRunStore restarted = new JdbcRunStore(dataSource);
    try (Harness second = harness(restarted, model, new ToolRegistry())) {
      RunState completed = second.tick(id);
      assertEquals(RunStatus.COMPLETED, completed.status);
      assertEquals("SQL durable answer", completed.output.path("content").asText());
      assertEquals(22, completed.chargedTokens);
      assertEquals(deadline, completed.deadline);
      assertEquals(Json.hash(definition), Json.hash(completed.definition));
      assertEquals(1, calls.get());
      assertTrue(
          second.events(id, ACTOR, 0, 100).stream()
              .anyMatch(event -> "MODEL_COMPLETED".equals(event.event().type())));
    }
  }

  @Test
  void exactApprovalSurvivesRestartAndExecutesOnce() {
    AtomicInteger calls = new AtomicInteger();
    ToolRegistry registry =
        registry(
            (tool, arguments, context) -> {
              calls.incrementAndGet();
              return new ToolResult(
                  Json.object().put("changed", "sandbox-only"), false, "remote-receipt-1");
            });
    String id;
    String digest;
    try (Harness first = toolHarness(store, registry)) {
      RunState waiting = waiting(first, "approved-write");
      id = waiting.id;
      digest = waiting.approval.digest();
      assertEquals(0, calls.get());
    }
    JdbcRunStore restarted = new JdbcRunStore(dataSource);
    try (Harness second = toolHarness(restarted, registry)) {
      assertEquals(digest, second.get(id, ACTOR).approval.digest());
      assertThrows(
          RunStore.Conflict.class, () -> second.decide(id, ACTOR, "wrong-digest", true, null));
      second.decide(id, ACTOR, digest, true, "Approved test resource only");
      RunState result = second.tick(id);
      assertNull(result.pending);
      assertEquals(1, result.toolCalls);
      assertEquals("remote-receipt-1", restarted.get(id).receipts.get("change-1"));
      RunState completed = second.tick(id);
      assertEquals(RunStatus.COMPLETED, completed.status);
      assertEquals("sandbox-only", completed.output.path("changed").asText());
      assertEquals(1, calls.get());
      assertTrue(
          second.events(id, ACTOR, 0, 100).stream()
              .anyMatch(event -> "APPROVAL_GRANTED".equals(event.event().type())));
    }
  }

  @Test
  void interruptedApprovedWriteThenCancellationRetainsReconciliationEntryAfterRestart()
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    ToolRegistry registry =
        registry(
            (tool, arguments, context) -> {
              handlerCalls.incrementAndGet();
              return ToolResult.success(Json.object());
            });
    String id;
    String invocationId;
    try (Harness first = toolHarness(store, registry)) {
      RunState waiting = waiting(first, "cancel-interrupted");
      id = waiting.id;
      first.decide(id, ACTOR, waiting.approval.digest(), true, null);
      RunState claimed = store.claim(id, Duration.ofMinutes(1));
      invocationId = claimed.pending.id;
      claimed.pending.phase = InvocationPhase.IN_FLIGHT;
      claimed.pending.attempts = 1;
      claimed.toolCalls = 1;
      store.save(
          claimed,
          new RunEvent(
              "ATTEMPT_STARTED",
              claimed.pending.nodeId,
              invocationId,
              Instant.now(),
              Map.of("attempt", "1")),
          false);
      // The process dies after the dispatch barrier. The test does not repeat the uncertain call.
      first.cancel(id, ACTOR);
      expireLease(id);
    }
    JdbcRunStore restarted = new JdbcRunStore(dataSource);
    try (Harness second = toolHarness(restarted, registry)) {
      RunState unknown = second.tick(id);
      assertEquals(RunStatus.NEEDS_ATTENTION, unknown.status);
      assertTrue(unknown.cancelRequested);
      assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
      assertEquals(invocationId, unknown.pending.id);
      assertThrows(RunStore.Conflict.class, () -> second.resume(id, ACTOR));
      RunState reconciled =
          second.reconcileTool(
              id,
              ACTOR,
              invocationId,
              new ToolResult(
                  Json.object().put("confirmed", true), false, "verified-remote-receipt"));
      assertEquals(RunStatus.CANCELLED, reconciled.status);
      assertNull(reconciled.pending);
      assertTrue(reconciled.results.get(invocationId).value().path("confirmed").asBoolean());
      assertEquals("verified-remote-receipt", restarted.get(id).receipts.get(invocationId));
      assertEquals(1, reconciled.toolCalls);
      assertEquals(
          0, handlerCalls.get(), "Recovery and reconciliation must not call the write tool again");
    }
  }

  @Test
  void cancelAfterUnknownWriteCannotHideTheUnresolvedOutcome() {
    AtomicInteger effects = new AtomicInteger();
    ToolRegistry registry =
        registry(
            (tool, arguments, context) -> {
              effects.incrementAndGet();
              throw new InvocationException(FailureKind.UNKNOWN, "WRITE_SUCCEEDED_CONNECTION_LOST");
            });
    try (Harness harness = toolHarness(store, registry)) {
      RunState waiting = waiting(harness, "cancel-known-unknown");
      harness.decide(waiting.id, ACTOR, waiting.approval.digest(), true, null);
      RunState unknown = harness.tick(waiting.id);
      assertEquals(RunStatus.NEEDS_ATTENTION, unknown.status);
      assertEquals(InvocationPhase.UNKNOWN, unknown.pending.phase);
      RunState cancelled = harness.cancel(waiting.id, ACTOR);
      assertEquals(RunStatus.NEEDS_ATTENTION, cancelled.status);
      assertTrue(cancelled.cancelRequested);
      RunState reconciled =
          harness.reconcileTool(
              waiting.id,
              ACTOR,
              unknown.pending.id,
              new ToolResult(Json.object().put("confirmed", true), false, "verified-after-cancel"));
      assertEquals(RunStatus.CANCELLED, reconciled.status);
      assertEquals(1, effects.get());
    }
  }

  private Harness harness(JdbcRunStore store, ModelGateway model, ToolRegistry tools) {
    return new Harness(
        store,
        model,
        tools,
        AccessPolicy.actorPermissions(),
        Telemetry.noop(),
        Clock.systemUTC(),
        Duration.ofSeconds(10));
  }

  private Harness toolHarness(JdbcRunStore store, ToolRegistry registry) {
    Harness harness =
        harness(
            store,
            (request, context) -> {
              throw new AssertionError("Tool-only test must not call a model");
            },
            registry);
    harness.registerProgram(
        "single-tool",
        run ->
            run.results.containsKey("change-1")
                ? new CompleteAction(run.results.get("change-1").value())
                : new ToolAction(
                    "change-1",
                    "resource-change",
                    "sandbox:change",
                    Json.object().put("resource", "sandbox-only")));
    return harness;
  }

  private ToolRegistry registry(ToolHandler handler) {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        new ToolDescriptor(
            "sandbox:change",
            "sandbox_change",
            "Change a test resource",
            "internal",
            "change",
            "1",
            Json.read(
                "{\"type\":\"object\",\"required\":[\"resource\"],\"properties\":{\"resource\":{\"const\":\"sandbox-only\"}},\"additionalProperties\":false}"),
            ToolPolicy.approvedWrite()),
        handler);
    return registry;
  }

  private RunState waiting(Harness harness, String creationKey) {
    RunState run =
        harness.start(
            new ProgramDefinition("change-test", "1", "single-tool", Json.object()),
            ACTOR,
            List.of("sandbox:change"),
            Budget.defaults(),
            Duration.ofMinutes(10),
            creationKey);
    harness.tick(run.id);
    RunState waiting = harness.tick(run.id);
    assertEquals(RunStatus.WAITING_APPROVAL, waiting.status);
    return waiting;
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
}
