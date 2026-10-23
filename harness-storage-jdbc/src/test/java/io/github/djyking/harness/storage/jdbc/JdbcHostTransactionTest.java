package io.github.djyking.harness.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.ScriptedModel;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcHostTransactionTest {
  private static final Actor ACTOR = new Actor("application:user", "project", Set.of("*"));
  private JdbcRunStore store;
  private JdbcDataSource dataSource;

  @BeforeEach
  void database() throws SQLException {
    dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE host_commands (command_key VARCHAR(80) PRIMARY KEY, run_id VARCHAR(80))");
    }
  }

  @Test
  void hostAcceptanceAndRunEventsCommitTogetherAndNestedCallsShareConnection() {
    try (Harness harness = harness()) {
      String id = UUID.randomUUID().toString();
      RunState accepted =
          store.inTransaction(
              connection -> {
                store.inTransaction(
                    nested -> {
                      assertSame(connection, nested);
                      return null;
                    });
                insert(connection, "accepted", id);
                RunState run = start(harness, id, "accepted");
                assertEquals(run.id, store.get(id).id);
                assertEquals(1, store.events(id, 0, 20).size());
                return run;
              });
      assertEquals(id, accepted.id);
      assertEquals(1, countCommands());
      assertEquals(id, new JdbcRunStore(dataSource).get(id).id);
      assertEquals(1, store.events(id, 0, 20).size());
    }
  }

  @Test
  void failureAfterRunCreationRollsBackHostRowRunAndAudit() {
    try (Harness harness = harness()) {
      String id = UUID.randomUUID().toString();
      assertThrows(
          JdbcStorageException.class,
          () ->
              store.inTransaction(
                  connection -> {
                    insert(connection, "lost-response", id);
                    start(harness, id, "lost-response");
                    insert(connection, "lost-response", id); // Fail after all state/event writes.
                    return null;
                  }));
      assertEquals(0, countCommands());
      assertThrows(RunStore.NotFound.class, () -> store.get(id));
      // The thread-local transaction was removed; a new same-key transaction can succeed.
      store.inTransaction(
          connection -> {
            insert(connection, "lost-response", id);
            return start(harness, id, "lost-response");
          });
      assertEquals(1, countCommands());
      assertEquals(1, store.events(id, 0, 20).size());
    }
  }

  @Test
  void catchingNestedFailureStillPreventsPartialCommit() {
    try (Harness harness = harness()) {
      String id = UUID.randomUUID().toString();
      assertThrows(
          JdbcStorageException.class,
          () ->
              store.inTransaction(
                  connection -> {
                    insert(connection, "nested", id);
                    start(harness, id, "nested");
                    assertThrows(
                        IllegalArgumentException.class,
                        () ->
                            store.inTransaction(
                                nested -> {
                                  throw new IllegalArgumentException("host validation failed");
                                }));
                    return id;
                  }));
      assertEquals(0, countCommands());
      assertThrows(RunStore.NotFound.class, () -> store.get(id));
    }
  }

  @Test
  void simultaneousHostTransactionsUseSeparateConnectionsAndIndependentRollbackState()
      throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch bothEntered = new CountDownLatch(2);
    var connections = new ConcurrentLinkedQueue<Connection>();
    String rolledBackId = UUID.randomUUID().toString(), committedId = UUID.randomUUID().toString();
    try (Harness harness = harness()) {
      Future<?> rollback =
          workers.submit(
              () -> {
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        store.inTransaction(
                            connection -> {
                              connections.add(connection);
                              insert(connection, "rollback-thread", rolledBackId);
                              start(harness, rolledBackId, "rollback-thread");
                              bothEntered.countDown();
                              await(bothEntered);
                              throw new IllegalStateException("rollback this thread only");
                            }));
              });
      Future<?> commit =
          workers.submit(
              () ->
                  store.inTransaction(
                      connection -> {
                        connections.add(connection);
                        insert(connection, "commit-thread", committedId);
                        start(harness, committedId, "commit-thread");
                        bothEntered.countDown();
                        await(bothEntered);
                        return null;
                      }));
      rollback.get(5, TimeUnit.SECONDS);
      commit.get(5, TimeUnit.SECONDS);
      assertEquals(2, connections.size());
      Connection[] used = connections.toArray(Connection[]::new);
      assertNotSame(used[0], used[1]);
      assertEquals(1, countCommands());
      assertThrows(RunStore.NotFound.class, () -> store.get(rolledBackId));
      assertEquals(committedId, store.get(committedId).id);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void controlAndCommandResponseRollBackTogetherAndStaleRevisionHasNoEffect() {
    try (Harness harness = harness()) {
      RunState run = start(harness, UUID.randomUUID().toString(), "control");
      assertThrows(
          JdbcStorageException.class,
          () ->
              store.inTransaction(
                  connection -> {
                    harness.pause(run.id, ACTOR, run.revision);
                    insert(connection, "control-response", run.id);
                    insert(connection, "control-response", run.id);
                    return null;
                  }));
      assertEquals(run.revision, store.get(run.id).revision);
      assertEquals(RunStatus.QUEUED, store.get(run.id).status);
      assertEquals(1, store.events(run.id, 0, 20).size());
      RunState paused =
          store.inTransaction(
              connection -> {
                RunState changed = harness.pause(run.id, ACTOR, run.revision);
                insert(connection, "control-response", run.id);
                return changed;
              });
      assertEquals(RunStatus.PAUSED, paused.status);
      assertThrows(
          RunStore.Conflict.class,
          () ->
              store.inTransaction(
                  connection -> {
                    insert(connection, "stale-response", run.id);
                    return harness.cancel(run.id, ACTOR, run.revision);
                  }));
      assertEquals(1, countCommands());
      assertEquals(paused.revision, store.get(run.id).revision);
      assertEquals(2, store.events(run.id, 0, 20).size());
    }
  }

  @Test
  void workerCannotDispatchInsideUncommittedHostTransaction() {
    AtomicInteger calls = new AtomicInteger();
    try (Harness harness =
        new Harness(
            store,
            (request, context) -> {
              calls.incrementAndGet();
              return ScriptedModel.answer("done");
            },
            new ToolRegistry())) {
      RunState run = start(harness, UUID.randomUUID().toString(), "worker");
      assertThrows(
          IllegalStateException.class,
          () -> store.inTransaction(connection -> harness.tick(run.id)));
      assertEquals(0, calls.get());
      assertEquals(run.revision, store.get(run.id).revision);
      assertThrows(
          JdbcStorageException.class,
          () ->
              store.inTransaction(
                  connection -> {
                    assertThrows(IllegalStateException.class, store::initializeSchema);
                    return null;
                  }));
    }
  }

  @Test
  void databaseTimeFindsExpiredWaitsButExcludesUnknownInvocations() {
    try (Harness harness = harness()) {
      RunState known = start(harness, UUID.randomUUID().toString(), "expired-wait");
      RunState unknown = start(harness, UUID.randomUUID().toString(), "unknown-wait");
      setWait(known, InvocationPhase.PREPARED);
      setWait(unknown, InvocationPhase.UNKNOWN);
      assertEquals(List.of(known.id), store.expirable(10));
      assertEquals(RunStatus.EXPIRED, harness.expire(known.id).status);
      assertEquals(RunStatus.NEEDS_ATTENTION, harness.expire(unknown.id).status);
      assertTrue(store.expirable(10).isEmpty());
    }
  }

  private void setWait(RunState initial, InvocationPhase phase) {
    store.update(
        initial.id,
        initial.revision,
        run -> {
          run.status =
              phase == InvocationPhase.UNKNOWN
                  ? RunStatus.NEEDS_ATTENTION
                  : RunStatus.WAITING_APPROVAL;
          run.pending = new RunState.Pending();
          run.pending.id = "waiting";
          run.pending.kind = "TOOL";
          run.pending.phase = phase;
          run.approval = new Approval("waiting", "digest", Instant.EPOCH, "PENDING", null, null);
          return run;
        },
        new RunEvent("WAITING", "", "", Instant.now(), Map.of()));
  }

  private Harness harness() {
    return new Harness(store, new ScriptedModel(), new ToolRegistry());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private RunState start(Harness harness, String id, String key) {
    ModelProfile model =
        new ModelProfile("recorded", "test", "test", 32000, 256, 5000, Json.object());
    ProgramDefinition definition =
        new ProgramDefinition(
            "agent",
            "1",
            "agent",
            Json.tree(
                new AgentProgram.AgentSpec(model, List.of(Message.text("user", "hello")), 1)));
    return harness.startWithId(
        id, definition, ACTOR, List.of(), Budget.defaults(), Duration.ofHours(1), key);
  }

  private void insert(Connection connection, String key, String id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO host_commands VALUES (?, ?)")) {
      statement.setString(1, key);
      statement.setString(2, id);
      statement.executeUpdate();
    }
  }

  private int countCommands() {
    return store.inTransaction(
        connection -> {
          try (Statement statement = connection.createStatement();
              ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM host_commands")) {
            assertTrue(result.next());
            return result.getInt(1);
          }
        });
  }
}
