package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Shared, payload-free diagnostics. Run events remain the durable execution audit authority. */
public final class PlatformTrace implements Telemetry {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(PlatformTrace.class);
  private final DataSource dataSource;
  private final StructuredTelemetry logs = new StructuredTelemetry();

  public PlatformTrace(DataSource dataSource) {
    this.dataSource = dataSource;
    try (var c = dataSource.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS harness_platform_trace (sequence_id BIGINT AUTO_INCREMENT"
              + " PRIMARY KEY, run_id VARCHAR(128) NOT NULL,trace_id VARCHAR(128),invocation_id"
              + " VARCHAR(128),attempt_id VARCHAR(128),node_id VARCHAR(128),operation_name"
              + " VARCHAR(64),target_name VARCHAR(256),outcome VARCHAR(64),started_at BIGINT NOT"
              + " NULL,duration_ms BIGINT NOT NULL)");
      // Index DDL is portable across MySQL and H2; metadata avoids duplicate-index exceptions.
      boolean indexed = false;
      for (String table : new String[] {"harness_platform_trace", "HARNESS_PLATFORM_TRACE"}) {
        try (var rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
          while (rs.next())
            if ("ix_harness_trace_run".equalsIgnoreCase(rs.getString("INDEX_NAME"))) indexed = true;
        }
      }
      if (!indexed)
        try {
          s.execute(
              "CREATE INDEX ix_harness_trace_run ON harness_platform_trace(run_id,sequence_id)");
        } catch (SQLException race) {
          // Concurrent API/worker startup may have created precisely the same index.
          if (race.getErrorCode() != 1061 && !"42S11".equals(race.getSQLState())) throw race;
        }
    } catch (SQLException ex) {
      throw new IllegalStateException("Trace schema unavailable");
    }
  }

  @Override
  public Span start(ExecutionContext context, String operation, String target) {
    var safeContext =
        new ExecutionContext(
            safe(context.runId(), 128),
            safe(context.nodeId(), 128),
            safe(context.invocationId(), 128),
            safe(context.attemptId(), 128),
            context.actor(),
            context.deadline(),
            safe(context.traceId(), 128));
    Span log = logs.start(safeContext, safe(operation, 64), safe(target, 256));
    long started = System.currentTimeMillis(), nano = System.nanoTime();
    return new Span() {
      private String outcome = "UNKNOWN";
      private final AtomicBoolean closed = new AtomicBoolean();

      public void outcome(String value) {
        outcome = safe(value, 64);
        log.outcome(outcome);
      }

      public void close() {
        if (!closed.compareAndSet(false, true)) return;
        log.close();
        try (var c = dataSource.getConnection();
            var s =
                c.prepareStatement(
                    "INSERT INTO"
                        + " harness_platform_trace(run_id,trace_id,invocation_id,attempt_id,node_id,operation_name,target_name,outcome,started_at,duration_ms)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
          s.setString(1, safe(context.runId(), 128));
          s.setString(2, safe(context.traceId(), 128));
          s.setString(3, safe(context.invocationId(), 128));
          s.setString(4, safe(context.attemptId(), 128));
          s.setString(5, safe(context.nodeId(), 128));
          s.setString(6, safe(operation, 64));
          s.setString(7, safe(target, 256));
          s.setString(8, outcome);
          s.setLong(9, started);
          s.setLong(10, Math.max(0, (System.nanoTime() - nano) / 1_000_000));
          s.executeUpdate();
        } catch (SQLException failure) {
          LOG.warn("harness trace persistence unavailable; consult durable run events");
        }
      }
    };
  }

  public JsonNode list(String run, long after, int limit) {
    if (after < 0 || limit < 1 || limit > 200) throw ApiFailure.invalid();
    var out = Json.object().put("source", "PERSISTED_TELEMETRY").put("auditSource", "RUN_EVENTS");
    var items = out.putArray("items");
    long next = after;
    boolean more = false;
    try (var c = dataSource.getConnection();
        var s =
            c.prepareStatement(
                "SELECT * FROM harness_platform_trace WHERE run_id=? AND sequence_id>? ORDER BY"
                    + " sequence_id LIMIT ?")) {
      s.setString(1, run);
      s.setLong(2, after);
      s.setInt(3, limit + 1);
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          if (items.size() == limit) {
            more = true;
            break;
          }
          next = rows.getLong("sequence_id");
          var row =
              items
                  .addObject()
                  .put("sequence", next)
                  .put("startedAt", Instant.ofEpochMilli(rows.getLong("started_at")).toString())
                  .put("durationMs", rows.getLong("duration_ms"));
          row.put("traceId", rows.getString("trace_id"));
          row.put("invocationId", rows.getString("invocation_id"));
          row.put("attemptId", rows.getString("attempt_id"));
          row.put("nodeId", rows.getString("node_id"));
          row.put("operation", rows.getString("operation_name"));
          row.put("target", rows.getString("target_name"));
          row.put("outcome", rows.getString("outcome"));
        }
      }
    } catch (SQLException ex) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
    return out.put("nextAfter", next).put("hasMore", more);
  }

  private static String safe(String value, int length) {
    if (value == null) return null;
    return value.length() <= length && value.matches("[A-Za-z0-9:/_.-]*") ? value : "REDACTED";
  }
}
