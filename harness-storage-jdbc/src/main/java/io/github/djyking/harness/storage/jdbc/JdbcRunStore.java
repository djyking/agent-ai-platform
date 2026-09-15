package io.github.djyking.harness.storage.jdbc;

import static io.github.djyking.harness.core.StateGuards.*;

import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import io.github.djyking.harness.core.RunStore;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/**
 * Plain JDBC run store. State and events share a transaction and the run row is their lock. Call
 * initializeSchema once during development or apply the equivalent DDL using host migrations. This
 * store does not own or close the host's DataSource.
 */
public final class JdbcRunStore implements RunStore {
  private final DataSource dataSource;
  private final JdbcDialect dialect;

  public JdbcRunStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
    try (Connection connection = dataSource.getConnection()) {
      this.dialect = JdbcDialect.detect(connection);
      verifyExistingSchemaVersion(connection);
      verifyTransactionalTables(connection);
    } catch (SQLException e) {
      throw failure("inspect database", e);
    }
  }

  /**
   * Idempotent bootstrap for schema v1; production hosts may apply this DDL in their migration
   * tool.
   */
  public void initializeSchema() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      String engine = dialect == JdbcDialect.MYSQL ? " ENGINE=InnoDB" : "";
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS harness_schema (component VARCHAR(64) PRIMARY KEY,"
              + " schema_version INTEGER NOT NULL)"
              + engine);
      try (ResultSet result =
          statement.executeQuery(
              "SELECT schema_version FROM harness_schema WHERE component = 'run-store'")) {
        if (result.next() && result.getInt(1) != 1)
          throw new JdbcStorageException("Unsupported run-store SQL schema version");
      }
      statement.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS harness_runs (
            run_id VARCHAR(128) PRIMARY KEY,
            creation_scope VARCHAR(64) NOT NULL UNIQUE,
            creation_digest VARCHAR(128) NOT NULL,
            revision BIGINT NOT NULL,
            fence BIGINT NOT NULL,
            lease_until BIGINT,
            run_status VARCHAR(32) NOT NULL,
            next_attempt_at BIGINT,
            created_at BIGINT NOT NULL,
            snapshot_json LONGTEXT NOT NULL,
            event_sequence BIGINT NOT NULL,
            INDEX harness_runs_ready_idx (run_status, next_attempt_at, lease_until, created_at)
          )
          """
              + engine);
      statement.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS harness_run_events (
            run_id VARCHAR(128) NOT NULL,
            event_sequence BIGINT NOT NULL,
            event_json LONGTEXT NOT NULL,
            PRIMARY KEY (run_id, event_sequence),
            FOREIGN KEY (run_id) REFERENCES harness_runs(run_id)
          )
          """
              + engine);
      try {
        statement.executeUpdate(
            "INSERT INTO harness_schema (component, schema_version) VALUES ('run-store', 1)");
      } catch (SQLException conflict) {
        if (!constraintFailure(conflict)) throw conflict;
        try (ResultSet result =
            statement.executeQuery(
                "SELECT schema_version FROM harness_schema WHERE component = 'run-store'")) {
          if (!result.next() || result.getInt(1) != 1)
            throw new JdbcStorageException("Unsupported run-store SQL schema version");
        }
      }
      verifyTransactionalTables(connection);
    } catch (SQLException e) {
      throw failure("initialize schema", e);
    }
  }

  @Override
  public RunState create(RunState initial) {
    RunState proposed = Objects.requireNonNull(initial).copy();
    validateInitial(proposed);
    String scope = creationScope(proposed);
    try {
      return transaction(
          connection -> {
            Row existing = findByScope(connection, scope);
            if (existing != null) return duplicate(existing.state, proposed);
            proposed.revision = 1;
            proposed.fence = 0;
            proposed.leaseUntil = null;
            String json = Json.write(proposed);
            try (PreparedStatement statement =
                connection.prepareStatement(
                    """
                    INSERT INTO harness_runs
                      (run_id, creation_scope, creation_digest, revision, fence, lease_until,
                       run_status, next_attempt_at, created_at, snapshot_json, event_sequence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
              statement.setString(1, proposed.id);
              statement.setString(2, scope);
              statement.setString(3, proposed.creationDigest);
              statement.setLong(4, 1);
              statement.setLong(5, 0);
              statement.setNull(6, Types.BIGINT);
              statement.setString(7, proposed.status.name());
              instant(statement, 8, proposed.nextAttemptAt);
              statement.setLong(9, proposed.createdAt.toEpochMilli());
              statement.setString(10, json);
              statement.setLong(11, 1);
              statement.executeUpdate();
            }
            append(
                connection,
                proposed.id,
                1,
                new RunEvent(
                    "RUN_CREATED",
                    null,
                    null,
                    Instant.ofEpochMilli(dialect.nowMillis(connection)),
                    Map.of()));
            return proposed.copy();
          });
    } catch (JdbcStorageException failure) {
      if (!(failure.getCause() instanceof SQLException sql) || !constraintFailure(sql))
        throw failure;
      // A duplicate-key insert must be rolled back before reading the winning transaction.
      return transaction(
          connection -> {
            Row winner = findByScope(connection, scope);
            if (winner != null) return duplicate(winner.state, proposed);
            throw new Conflict("Run id already exists");
          });
    }
  }

  @Override
  public RunState get(String id) {
    return transaction(connection -> require(connection, id, false).state.copy());
  }

  @Override
  public RunState claim(String id, Duration lease) {
    long ttl = leaseMillis(lease);
    return transaction(
        connection -> {
          Row row = require(connection, id, true);
          long now =
              dialect.nowMillis(connection); // Read after row lock, never use worker wall time.
          if (!eligible(row.state, now)) return null;
          RunState claimed = row.state.copy();
          claimed.status = RunStatus.RUNNING;
          claimed.fence = Math.addExact(claimed.fence, 1);
          claimed.revision = Math.addExact(claimed.revision, 1);
          claimed.leaseUntil = Instant.ofEpochMilli(Math.addExact(now, ttl));
          return persist(
              connection,
              row,
              claimed,
              new RunEvent(
                  "LEASE_CLAIMED",
                  null,
                  null,
                  Instant.ofEpochMilli(now),
                  Map.of("fence", Long.toString(claimed.fence))));
        });
  }

  @Override
  public List<String> ready(int limit) {
    checkLimit(limit);
    return transaction(
        connection -> {
          long now = dialect.nowMillis(connection);
          List<String> ids = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT run_id FROM harness_runs
                  WHERE run_status IN ('QUEUED', 'RUNNING')
                    AND (lease_until IS NULL OR lease_until <= ?)
                    AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                  ORDER BY created_at, run_id LIMIT ?
                  """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) ids.add(result.getString(1));
            }
          }
          return List.copyOf(ids);
        });
  }

  @Override
  public void assertLease(RunState claimed) {
    RunState supplied = Objects.requireNonNull(claimed).copy();
    transaction(
        connection -> {
          Row current = require(connection, supplied.id, true);
          checkLease(current.state, supplied, dialect.nowMillis(connection));
          return null;
        });
  }

  @Override
  public RunState save(RunState claimed, RunEvent event, boolean releaseLease) {
    RunState next = Objects.requireNonNull(claimed).copy();
    validateEvent(event);
    return transaction(
        connection -> {
          Row row = require(connection, next.id, true);
          checkLease(row.state, next, dialect.nowMillis(connection));
          checkBaseline(row.state, next);
          checkUsage(row.state, next, true);
          next.revision = Math.addExact(row.state.revision, 1);
          next.fence = row.state.fence;
          next.leaseUntil = releaseLease ? null : row.state.leaseUntil;
          return persist(connection, row, next, event);
        });
  }

  @Override
  public RunState update(
      String id, long expectedRevision, UnaryOperator<RunState> mutation, RunEvent event) {
    Objects.requireNonNull(mutation);
    validateEvent(event);
    return transaction(
        connection -> {
          Row row = require(connection, id, true);
          if (row.state.revision != expectedRevision) throw new Conflict("Run revision changed");
          RunState next =
              Objects.requireNonNull(mutation.apply(row.state.copy()), "Mutation returned null")
                  .copy();
          checkBaseline(row.state, next);
          checkUsage(row.state, next, false);
          next.revision = Math.addExact(row.state.revision, 1);
          next.fence = row.state.fence;
          next.leaseUntil = row.state.leaseUntil;
          return persist(connection, row, next, event);
        });
  }

  @Override
  public List<StoredEvent> events(String id, long afterSequence, int limit) {
    checkLimit(limit);
    if (afterSequence < 0) throw new IllegalArgumentException("Negative event cursor");
    return transaction(
        connection -> {
          require(connection, id, false);
          List<StoredEvent> result = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT event_sequence, event_json FROM harness_run_events
                  WHERE run_id = ? AND event_sequence > ? ORDER BY event_sequence LIMIT ?
                  """)) {
            statement.setString(1, id);
            statement.setLong(2, afterSequence);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next())
                result.add(
                    new StoredEvent(
                        rows.getLong(1),
                        Json.convert(Json.read(rows.getString(2)), RunEvent.class)));
            }
          }
          return List.copyOf(result);
        });
  }

  private RunState persist(Connection connection, Row previous, RunState next, RunEvent event)
      throws SQLException {
    validateVersion(next);
    Objects.requireNonNull(next.status, "Run status required");
    long sequence = Math.addExact(previous.sequence, 1);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE harness_runs SET revision = ?, fence = ?, lease_until = ?, run_status = ?,
              next_attempt_at = ?, snapshot_json = ?, event_sequence = ?
            WHERE run_id = ? AND revision = ? AND fence = ?
            """)) {
      statement.setLong(1, next.revision);
      statement.setLong(2, next.fence);
      instant(statement, 3, next.leaseUntil);
      statement.setString(4, next.status.name());
      instant(statement, 5, next.nextAttemptAt);
      statement.setString(6, Json.write(next));
      statement.setLong(7, sequence);
      statement.setString(8, previous.state.id);
      statement.setLong(9, previous.state.revision);
      statement.setLong(10, previous.state.fence);
      if (statement.executeUpdate() != 1)
        throw new Conflict("Run revision or fencing token changed");
    }
    append(connection, next.id, sequence, event);
    return next.copy();
  }

  private void append(Connection connection, String id, long sequence, RunEvent event)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO harness_run_events (run_id, event_sequence, event_json) VALUES (?, ?,"
                + " ?)")) {
      statement.setString(1, id);
      statement.setLong(2, sequence);
      statement.setString(3, Json.write(event));
      statement.executeUpdate();
    }
  }

  private Row require(Connection connection, String id, boolean lock) throws SQLException {
    Objects.requireNonNull(id);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT snapshot_json, revision, fence, lease_until, event_sequence FROM harness_runs"
                + " WHERE run_id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
      statement.setString(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new NotFound(id);
        return decode(result);
      }
    }
  }

  private Row findByScope(Connection connection, String scope) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT snapshot_json, revision, fence, lease_until, event_sequence FROM harness_runs"
                + " WHERE creation_scope = ?")) {
      statement.setString(1, scope);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? decode(result) : null;
      }
    }
  }

  private Row decode(ResultSet result) throws SQLException {
    RunState state;
    try {
      var encoded = Json.read(result.getString("snapshot_json"));
      if (encoded.path("schemaVersion").asInt(-1) != 1
          || !"1".equals(encoded.path("runtimeVersion").asText())) {
        throw new JdbcStorageException(
            "Unsupported persisted run version; explicit migration required");
      }
      state = Json.convert(encoded, RunState.class);
    } catch (IllegalArgumentException malformed) {
      throw new JdbcStorageException("Invalid persisted run snapshot", malformed);
    }
    validateVersion(state);
    state.revision = result.getLong("revision");
    state.fence = result.getLong("fence");
    long leaseUntil = result.getLong("lease_until");
    state.leaseUntil = result.wasNull() ? null : Instant.ofEpochMilli(leaseUntil);
    return new Row(state, result.getLong("event_sequence"));
  }

  private static RunState duplicate(RunState existing, RunState proposed) {
    if (!Objects.equals(existing.creationDigest, proposed.creationDigest))
      throw new Conflict("Creation key was used for a different request");
    return existing.copy();
  }

  private static void verifyExistingSchemaVersion(Connection connection) throws SQLException {
    boolean exists = false;
    try (ResultSet tables =
        connection
            .getMetaData()
            .getTables(connection.getCatalog(), null, null, new String[] {"TABLE"})) {
      while (tables.next()) {
        if ("harness_schema".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
          exists = true;
          break;
        }
      }
    }
    if (!exists) return;
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT schema_version FROM harness_schema WHERE component = 'run-store'")) {
      if (result.next() && result.getInt(1) != 1)
        throw new JdbcStorageException("Unsupported run-store SQL schema version");
    }
  }

  private void verifyTransactionalTables(Connection connection) throws SQLException {
    if (dialect != JdbcDialect.MYSQL) return;
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                """
                SELECT TABLE_NAME, ENGINE FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME IN ('harness_schema', 'harness_runs', 'harness_run_events')
                """)) {
      while (rows.next()) {
        if (!"InnoDB".equalsIgnoreCase(rows.getString("ENGINE"))) {
          throw new JdbcStorageException("Harness MySQL tables require InnoDB transaction support");
        }
      }
    }
  }

  private static void instant(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) statement.setNull(index, Types.BIGINT);
    else statement.setLong(index, value.toEpochMilli());
  }

  private <T> T transaction(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        if (failure instanceof SQLException sql) throw failure("execute transaction", sql);
        throw (RuntimeException) failure;
      }
    } catch (SQLException e) {
      throw failure("open/close transaction", e);
    }
  }

  private static boolean constraintFailure(SQLException exception) {
    return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
  }

  private static JdbcStorageException failure(String operation, SQLException cause) {
    return new JdbcStorageException("Could not " + operation + " in JDBC run store", cause);
  }

  private record Row(RunState state, long sequence) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
  }
}
