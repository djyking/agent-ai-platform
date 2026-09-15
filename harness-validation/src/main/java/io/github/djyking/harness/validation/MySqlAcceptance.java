package io.github.djyking.harness.validation;

import static io.github.djyking.harness.validation.ValidationMain.require;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mysql.cj.jdbc.MysqlDataSource;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.ScriptedModel;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** Acceptance against a real, dedicated MySQL schema. No DROP, DELETE, or business-table access. */
public final class MySqlAcceptance {
  private MySqlAcceptance() {}

  public static ObjectNode run(LiveSettings settings) throws Exception {
    MysqlDataSource source = new MysqlDataSource();
    source.setURL(settings.jdbcUrl());
    source.setUser(settings.required("HARNESS_MYSQL_USER"));
    source.setPassword(settings.required("HARNESS_MYSQL_PASSWORD"));
    try (Connection c = source.getConnection()) {
      require("MySQL".equals(c.getMetaData().getDatabaseProductName()), "MYSQL_SERVER_REQUIRED");
      require(settings.schema().equals(c.getCatalog()), "MYSQL_SCHEMA_MISMATCH");
    }
    return exercise(source);
  }

  static ObjectNode exercise(DataSource source) throws Exception {
    ObjectNode report = Json.object();
    try (Connection c = source.getConnection()) {
      report
          .put("databaseProduct", c.getMetaData().getDatabaseProductName())
          .put("databaseVersion", c.getMetaData().getDatabaseProductVersion());
    }
    JdbcRunStore store = new JdbcRunStore(source);
    store.initializeSchema();
    var checks = report.putArray("checks");
    String namespace = UUID.randomUUID().toString();

    String key = namespace + ":creation";
    List<RunState> creations = race(4, () -> store.create(initial(key)));
    String id = creations.get(0).id;
    require(
        creations.stream().allMatch(r -> r.id.equals(id)) && store.events(id, 0, 100).size() == 1,
        "CONCURRENT_CREATE_FAILED");
    RunState wrong = initial(key);
    wrong.creationDigest = Json.hash("different");
    conflict(() -> store.create(wrong));
    checks.add("concurrent_creation_and_payload_idempotency");

    List<RunState> claims = race(4, () -> store.claim(id, Duration.ofSeconds(10)));
    require(claims.stream().filter(Objects::nonNull).count() == 1, "CONCURRENT_CLAIM_FAILED");
    RunState claimed = claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
    RunState stale = claimed.copy();
    try (Connection c = source.getConnection();
        PreparedStatement s =
            c.prepareStatement("UPDATE harness_runs SET lease_until=1 WHERE run_id=?")) {
      s.setString(1, id);
      s.executeUpdate();
    }
    RunState replacement = store.claim(id, Duration.ofSeconds(10));
    require(replacement.fence > stale.fence, "FENCE_NOT_INCREMENTED");
    stale.revision = replacement.revision;
    conflict(() -> store.save(stale, event("STALE"), true));
    checks.add("single_worker_claim_and_stale_fence_rejected");
    int events = store.events(id, 0, 100).size();
    conflict(
        () ->
            store.update(
                id,
                replacement.revision,
                r -> {
                  r.deadline = r.deadline.plusSeconds(1);
                  return r;
                },
                event("INVALID_BASELINE")));
    require(
        store.get(id).revision == replacement.revision && store.events(id, 0, 100).size() == events,
        "ROLLBACK_NOT_ATOMIC");
    RunState paused =
        store.update(
            id,
            replacement.revision,
            r -> {
              r.pauseRequested = true;
              return r;
            },
            event("PAUSE_REQUESTED"));
    conflict(() -> store.save(replacement, event("OVERWRITE"), true));
    require(
        paused.fence == replacement.fence && paused.leaseUntil.equals(replacement.leaseUntil),
        "CONTROL_STOLE_LEASE");
    checks.add("control_cas_and_failed_transaction_rollback");

    RunState locked = store.create(initial(namespace + ":lock"));
    RunState lockClaim = store.claim(locked.id, Duration.ofMillis(250));
    ExecutorService one = Executors.newSingleThreadExecutor();
    try (Connection locker = source.getConnection()) {
      locker.setAutoCommit(false);
      try (PreparedStatement s =
          locker.prepareStatement("SELECT run_id FROM harness_runs WHERE run_id=? FOR UPDATE")) {
        s.setString(1, locked.id);
        s.executeQuery().close();
      }
      CountDownLatch submitted = new CountDownLatch(1);
      Future<Boolean> late =
          one.submit(
              () -> {
                submitted.countDown();
                try {
                  store.save(lockClaim, event("LATE"), true);
                  return true;
                } catch (RunStore.Conflict expected) {
                  return false;
                }
              });
      require(submitted.await(3, TimeUnit.SECONDS), "LOCK_TEST_DID_NOT_START");
      Thread.sleep(400);
      locker.commit();
      require(!late.get(10, TimeUnit.SECONDS), "LEASE_EXTENDED_BY_LOCK_WAIT");
    } finally {
      one.shutdownNow();
    }
    // This confirms rejection after a delayed save while another connection holds the row.
    // It does not observe the server's lock-wait queue, so do not claim that timing was proven.
    checks.add("expired_lease_rejected_after_delayed_save");

    ModelProfile profile =
        new ModelProfile("sql-acceptance", "scripted", "local", 16000, 128, 2000, Json.object());
    var definition =
        new ProgramDefinition(
            "sql-qa",
            "1",
            "agent",
            Json.tree(
                new AgentProgram.AgentSpec(
                    profile, List.of(Message.text("user", "Synthetic storage validation")), 1)));
    String modelRun;
    try (Harness h =
        new Harness(
            store,
            new ScriptedModel(ScriptedModel.answer("stored")),
            new ToolRegistry(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            Clock.systemUTC(),
            Duration.ofSeconds(10))) {
      RunState run =
          h.start(
              definition,
              ValidationMain.actor(),
              List.of(),
              Budget.defaults(),
              Duration.ofMinutes(2),
              namespace + ":model");
      modelRun = run.id;
      h.tick(run.id);
      h.tick(run.id);
      require(
          h.get(run.id, ValidationMain.actor()).chargedTokens == 30,
          "MODEL_RESERVATION_SETTLEMENT_FAILED");
    }
    try (Harness h =
        new Harness(
            new JdbcRunStore(source),
            new ScriptedModel(),
            new ToolRegistry(),
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            Clock.systemUTC(),
            Duration.ofSeconds(10))) {
      require(h.tick(modelRun).status == RunStatus.COMPLETED, "MODEL_RESULT_NOT_RESTORED");
    }
    checks.add("exact_model_usage_and_worker_reconstruction");

    AtomicInteger effects = new AtomicInteger();
    ToolRegistry tools = new ToolRegistry();
    tools.register(
        new ToolDescriptor(
            "validation/write",
            "validation_write",
            "Synthetic in-process effect",
            "internal",
            "write",
            "1",
            Json.read("{\"type\":\"object\",\"additionalProperties\":false}"),
            ToolPolicy.approvedWrite()),
        (t, a, c) -> {
          effects.incrementAndGet();
          throw new InvocationException(FailureKind.UNKNOWN, "SYNTHETIC_RESPONSE_LOST");
        });
    String writeId;
    try (Harness h = writeRuntime(store, tools)) {
      RunState run =
          h.start(
              new ProgramDefinition("write-acceptance", "1", "write", Json.object()),
              ValidationMain.actor(),
              List.of("validation/write"),
              Budget.defaults(),
              Duration.ofMinutes(2),
              namespace + ":write");
      writeId = run.id;
      h.tick(run.id);
      require(h.tick(run.id).status == RunStatus.WAITING_APPROVAL, "WRITE_NOT_GATED");
      require(effects.get() == 0, "WRITE_BEFORE_APPROVAL");
    }
    try (Harness h = writeRuntime(new JdbcRunStore(source), tools)) {
      RunState waiting = h.get(writeId, ValidationMain.actor());
      conflict(() -> h.decide(writeId, ValidationMain.actor(), "stale-digest", true, null));
      h.decide(
          writeId, ValidationMain.actor(), waiting.approval.digest(), true, "synthetic-test-only");
      RunState unknown = h.tick(writeId);
      require(unknown.pending.phase == InvocationPhase.UNKNOWN, "UNKNOWN_RESULT_NOT_PRESERVED");
      h.cancel(writeId, ValidationMain.actor());
      RunState result =
          h.reconcileTool(
              writeId,
              ValidationMain.actor(),
              unknown.pending.id,
              new ToolResult(Json.object().put("verified", true), false, "synthetic-receipt"));
      require(
          result.status == RunStatus.CANCELLED
              && effects.get() == 1
              && "synthetic-receipt"
                  .equals(new JdbcRunStore(source).get(writeId).receipts.get("write")),
          "RECONCILIATION_FAILED");
    }
    checks.add("approval_restart_unknown_cancel_and_receipt_reconciliation");
    report
        .put("passedChecks", checks.size())
        .put("modelWasScripted", true)
        .put("businessTablesAccessed", false);
    return report;
  }

  private static Harness writeRuntime(JdbcRunStore store, ToolRegistry tools) {
    Harness h =
        new Harness(
            store,
            new ScriptedModel(),
            tools,
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            Clock.systemUTC(),
            Duration.ofSeconds(10));
    h.registerProgram(
        "write",
        r ->
            r.results.containsKey("write")
                ? new CompleteAction(r.results.get("write").value())
                : new ToolAction("write", "synthetic", "validation/write", Json.object()));
    return h;
  }

  private static RunState initial(String key) {
    RunState r = new RunState();
    r.id = UUID.randomUUID().toString();
    r.creationKey = key;
    r.creationDigest = Json.hash(key);
    r.actor = ValidationMain.actor();
    r.definition = new ProgramDefinition("storage-test", "1", "agent", Json.object());
    r.budget = Budget.defaults();
    r.createdAt = Instant.now();
    r.deadline = r.createdAt.plusSeconds(300);
    r.nextAttemptAt = r.createdAt;
    return r;
  }

  private static RunEvent event(String type) {
    return new RunEvent(type, "", "", Instant.now(), Map.of());
  }

  private static void conflict(Runnable operation) {
    try {
      operation.run();
    } catch (RunStore.Conflict expected) {
      return;
    }
    throw new IllegalStateException("Expected conflict was not raised");
  }

  private static <T> List<T> race(int n, Callable<T> operation) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> tasks = new ArrayList<>();
      for (int i = 0; i < n; i++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  return operation.call();
                }));
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> task : tasks) results.add(task.get(15, TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
