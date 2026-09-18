package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Platform indexes and command receipts share the core store's actual JDBC transaction. */
public final class PlatformRepository {
  public static final Set<RunStatus> TERMINAL =
      Set.of(
          RunStatus.COMPLETED,
          RunStatus.FAILED,
          RunStatus.CANCELLED,
          RunStatus.EXPIRED,
          RunStatus.BUDGET_EXCEEDED);

  public record Owned(
      String runId,
      String project,
      String application,
      String subject,
      String delegation,
      Deployment.Release release,
      Deployment.Limits limits,
      String clientReference,
      long createdAt) {}

  public record Command(String digest, String runId, JsonNode response) {}

  public record Evidence(
      String id,
      String project,
      String runId,
      String invocation,
      String digest,
      ToolResult result,
      String verifier,
      long verifiedAt) {}

  private final JdbcRunStore store;

  public PlatformRepository(JdbcRunStore store) {
    this.store = store;
  }

  public <T> T transaction(JdbcRunStore.JdbcWork<T> work) {
    return store.inTransaction(work);
  }

  /** Startup bootstrap only. MySQL DDL commits implicitly; never call from a Run command. */
  public void initialize(Deployment config) {
    transaction(
        c -> {
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          String engine = mysql ? " ENGINE=InnoDB" : "";
          try (PreparedStatement s =
                  c.prepareStatement(
                      "SELECT schema_version FROM harness_schema WHERE" + " component='platform'");
              ResultSet r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1)
              throw new IllegalStateException("Unsupported platform schema");
          }
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS platform_scope_locks(scope_key VARCHAR(200)"
                + " PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS platform_releases(project_id VARCHAR(128) NOT"
                + " NULL,release_id VARCHAR(36) NOT NULL,digest VARCHAR(80) NOT"
                + " NULL,manifest_json LONGTEXT NOT NULL,PRIMARY"
                + " KEY(project_id,release_id))",
            "CREATE TABLE IF NOT EXISTS platform_runs(run_id VARCHAR(128) PRIMARY KEY,project_id"
                + " VARCHAR(128) NOT NULL,application_id VARCHAR(128) NOT NULL,subject_id"
                + " VARCHAR(128) NOT NULL,delegation_id VARCHAR(128) NOT NULL,release_json LONGTEXT"
                + " NOT NULL,limits_json LONGTEXT NOT NULL,client_reference VARCHAR(200),created_at"
                + " BIGINT NOT NULL,reserved_tokens BIGINT NOT NULL,INDEX"
                + " platform_owner_idx(project_id,application_id,subject_id,created_at,run_id),FOREIGN"
                + " KEY(run_id) REFERENCES harness_runs(run_id))",
            "CREATE TABLE IF NOT EXISTS platform_commands(scope_hash VARCHAR(64)"
                + " PRIMARY KEY,request_digest VARCHAR(64) NOT NULL,run_id VARCHAR(128)"
                + " NOT NULL,response_json LONGTEXT NOT NULL,accepted_at BIGINT NOT"
                + " NULL,FOREIGN KEY(run_id) REFERENCES harness_runs(run_id))",
            "CREATE TABLE IF NOT EXISTS platform_command_audit(command_hash VARCHAR(64)"
                + " PRIMARY KEY,run_id VARCHAR(128) NOT NULL,actor_scope VARCHAR(300)"
                + " NOT NULL,operation VARCHAR(40) NOT NULL,reason_text"
                + " VARCHAR(1000),accepted_at BIGINT NOT NULL,FOREIGN KEY(run_id)"
                + " REFERENCES harness_runs(run_id))",
            "CREATE TABLE IF NOT EXISTS platform_evidence(evidence_id VARCHAR(128)"
                + " PRIMARY KEY,project_id VARCHAR(128) NOT NULL,run_id VARCHAR(128)"
                + " NOT NULL,invocation_id VARCHAR(240) NOT NULL,invocation_digest"
                + " VARCHAR(80) NOT NULL,result_json LONGTEXT NOT NULL,verifier"
                + " VARCHAR(128) NOT NULL,verified_at BIGINT NOT NULL,FOREIGN"
                + " KEY(run_id) REFERENCES harness_runs(run_id))",
            "CREATE TABLE IF NOT EXISTS platform_slots(pool_id VARCHAR(200) NOT"
                + " NULL,slot_index INTEGER NOT NULL,owner_id VARCHAR(128),lease_until"
                + " BIGINT,PRIMARY KEY(pool_id,slot_index))"
          };
          for (String sql : ddl) {
            // Core UUID foreign keys retain their original collation; semantic
            // identities are exact.
            if (mysql)
              sql =
                  sql.replaceAll(
                      "(\\b(?:project_id|application_id|subject_id|scope_key|pool_id|actor_scope)"
                          + " VARCHAR\\(\\d+\\))",
                      "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            execute(c, sql + engine);
          }
          if (mysql)
            try (PreparedStatement s =
                    c.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE"
                            + " table_schema=DATABASE() AND table_name LIKE 'platform_%' AND"
                            + " column_name IN"
                            + " ('project_id','application_id','subject_id','scope_key','pool_id','actor_scope')"
                            + " AND collation_name<>'utf8mb4_bin'");
                ResultSet r = s.executeQuery()) {
              r.next();
              if (r.getInt(1) != 0)
                throw new IllegalStateException(
                    "Platform identity columns require binary collation" + " migration");
            }
          try (PreparedStatement s =
                  c.prepareStatement(
                      "SELECT schema_version FROM harness_schema WHERE" + " component='platform'");
              ResultSet r = s.executeQuery()) {
            if (r.next()) {
              if (r.getInt(1) != 1) throw new IllegalStateException("Unsupported platform schema");
            } else
              try {
                execute(
                    c,
                    "INSERT INTO harness_schema(component,schema_version)"
                        + " VALUES('platform',1)");
              } catch (SQLException ex) {
                if (!constraint(ex)) throw ex;
              }
          }
          for (Deployment.Project p : config.projects()) ensureScope(c, p.id());
          for (Deployment.Release release : config.releases()) {
            try (PreparedStatement s =
                    prepare(
                        c,
                        "SELECT digest FROM platform_releases WHERE"
                            + " project_id=? AND release_id=?",
                        release.projectId(),
                        release.releaseId());
                ResultSet r = s.executeQuery()) {
              if (r.next()) {
                if (!r.getString(1).equals(release.digest()))
                  throw new IllegalStateException("Published release was overwritten");
              } else
                try {
                  execute(
                      c,
                      "INSERT INTO platform_releases VALUES(?,?,?,?)",
                      release.projectId(),
                      release.releaseId(),
                      release.digest(),
                      Json.write(release));
                } catch (SQLException ex) {
                  if (!constraint(ex)) throw ex;
                  try (PreparedStatement check =
                          prepare(
                              c,
                              "SELECT digest FROM platform_releases"
                                  + " WHERE project_id=? AND"
                                  + " release_id=? FOR UPDATE",
                              release.projectId(),
                              release.releaseId());
                      ResultSet found = check.executeQuery()) {
                    if (!found.next() || !found.getString(1).equals(release.digest()))
                      throw new IllegalStateException("Published release was overwritten");
                  }
                }
            }
          }
          slots(c, "global", config.concurrency());
          for (Deployment.Project p : config.projects())
            slots(c, "project:" + p.id(), p.concurrency());
          return null;
        });
  }

  private void slots(Connection c, String pool, int count) throws SQLException {
    for (int i = 0; i < count; i++)
      try {
        execute(c, "INSERT INTO platform_slots(pool_id,slot_index) VALUES(?,?)", pool, i);
      } catch (SQLException ex) {
        if (!constraint(ex)) throw ex;
      }
  }

  private void ensureScope(Connection c, String project) throws SQLException {
    try {
      execute(c, "INSERT INTO platform_scope_locks VALUES(?)", project);
    } catch (SQLException ex) {
      if (!constraint(ex)) throw ex;
    }
  }

  public void lockProject(Connection c, String project) throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT scope_key FROM platform_scope_locks WHERE scope_key=? FOR" + " UPDATE",
                project);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw ApiFailure.hidden();
    }
  }

  public Owned owned(String id) {
    return transaction(c -> owned(c, id));
  }

  public Owned owned(Connection c, String id) throws SQLException {
    try (PreparedStatement s = prepare(c, "SELECT * FROM platform_runs WHERE run_id=?", id);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw ApiFailure.hidden();
      return owned(r);
    }
  }

  private Owned owned(ResultSet r) throws SQLException {
    return new Owned(
        r.getString("run_id"),
        r.getString("project_id"),
        r.getString("application_id"),
        r.getString("subject_id"),
        r.getString("delegation_id"),
        Json.convert(Json.read(r.getString("release_json")), Deployment.Release.class),
        Json.convert(Json.read(r.getString("limits_json")), Deployment.Limits.class),
        r.getString("client_reference"),
        r.getLong("created_at"));
  }

  public void insert(Connection c, Owned o) throws SQLException {
    execute(
        c,
        "INSERT INTO"
            + " platform_runs(run_id,project_id,application_id,subject_id,delegation_id,release_json,limits_json,client_reference,created_at,reserved_tokens)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        o.runId,
        o.project,
        o.application,
        o.subject,
        o.delegation,
        Json.write(o.release),
        Json.write(o.limits),
        o.clientReference,
        o.createdAt,
        o.limits.maxTokens());
  }

  public Command command(Connection c, String hash) throws SQLException {
    try (PreparedStatement s =
            prepare(c, "SELECT * FROM platform_commands WHERE scope_hash=?", hash);
        ResultSet r = s.executeQuery()) {
      return r.next()
          ? new Command(
              r.getString("request_digest"),
              r.getString("run_id"),
              Json.read(r.getString("response_json")))
          : null;
    }
  }

  public void command(
      Connection c,
      String hash,
      String digest,
      Owned o,
      JsonNode response,
      String actor,
      String operation,
      String reason)
      throws SQLException {
    long now = now(c);
    execute(
        c,
        "INSERT INTO platform_commands VALUES(?,?,?,?,?)",
        hash,
        digest,
        o.runId,
        Json.write(response),
        now);
    execute(
        c,
        "INSERT INTO platform_command_audit VALUES(?,?,?,?,?,?)",
        hash,
        o.runId,
        actor,
        operation,
        reason,
        now);
  }

  public void admit(
      Connection c, Deployment.Project project, String application, long requestedTokens)
      throws SQLException {
    long projectCount = 0, projectTokens = 0, appCount = 0, appTokens = 0;
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT p.application_id,p.reserved_tokens,r.run_status FROM"
                    + " platform_runs p JOIN harness_runs r ON p.run_id=r.run_id"
                    + " WHERE p.project_id=? FOR UPDATE",
                project.id());
        ResultSet r = s.executeQuery()) {
      while (r.next()) {
        if (TERMINAL.contains(RunStatus.valueOf(r.getString(3)))) continue;
        projectCount++;
        projectTokens = Math.addExact(projectTokens, r.getLong(2));
        if (application.equals(r.getString(1))) {
          appCount++;
          appTokens = Math.addExact(appTokens, r.getLong(2));
        }
      }
    }
    if (projectCount >= project.maxActiveRuns()
        || appCount >= project.maxActiveRunsPerApplication()
        || requestedTokens > project.maxReservedTokens() - projectTokens
        || requestedTokens > project.maxReservedTokensPerApplication() - appTokens)
      throw new ApiFailure(429, "QUOTA_EXCEEDED");
  }

  public List<Owned> list(
      IdentityProvider.Principal p,
      String status,
      long watermark,
      long lastTime,
      String lastId,
      int limit) {
    return transaction(
        c -> {
          String sql =
              "SELECT p.* FROM platform_runs p JOIN harness_runs r ON"
                  + " p.run_id=r.run_id WHERE p.project_id=?";
          List<Object> args = new ArrayList<>(List.of(p.project()));
          if (!p.permits("runs:admin")) {
            sql += " AND p.application_id=? AND p.subject_id=?";
            args.add(p.application());
            args.add(p.subject());
          }
          sql +=
              " AND p.created_at<=? AND (p.created_at<? OR (p.created_at=? AND" + " p.run_id<?))";
          Collections.addAll(args, watermark, lastTime, lastTime, lastId);
          if (status != null) {
            sql += " AND r.run_status=?";
            args.add(status);
          }
          sql += " ORDER BY p.created_at DESC,p.run_id DESC LIMIT ?";
          args.add(limit);
          List<Owned> out = new ArrayList<>();
          try (PreparedStatement s = prepare(c, sql, args.toArray());
              ResultSet r = s.executeQuery()) {
            while (r.next()) out.add(owned(r));
          }
          return out;
        });
  }

  public long firstEvent(String id) {
    return transaction(
        c -> {
          try (PreparedStatement s =
                  prepare(
                      c,
                      "SELECT MIN(event_sequence) FROM harness_run_events" + " WHERE run_id=?",
                      id);
              ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
          }
        });
  }

  public Evidence evidence(Connection c, String id) throws SQLException {
    try (PreparedStatement s =
            prepare(c, "SELECT * FROM platform_evidence WHERE evidence_id=?", id);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new ApiFailure(409, "EVIDENCE_NOT_VERIFIED");
      return new Evidence(
          id,
          r.getString("project_id"),
          r.getString("run_id"),
          r.getString("invocation_id"),
          r.getString("invocation_digest"),
          Json.convert(Json.read(r.getString("result_json")), ToolResult.class),
          r.getString("verifier"),
          r.getLong("verified_at"));
    }
  }

  /**
   * For independently verified receipts only. No public HTTP evidence insertion endpoint exists.
   */
  public void importVerified(Evidence e) {
    if (e.verifier == null
        || e.verifier.isBlank()
        || e.result.receipt() == null
        || e.result.receipt().isBlank())
      throw new IllegalArgumentException("Verified source required");
    transaction(
        c -> {
          Owned o = owned(c, e.runId);
          RunState run = store.get(e.runId);
          if (!o.project.equals(e.project)
              || run.pending == null
              || run.pending.phase != InvocationPhase.UNKNOWN
              || !run.pending.kind.equals("TOOL")
              || !run.pending.id.equals(e.invocation)
              || !invocationDigest(run).equals(e.digest))
            throw new ApiFailure(409, "EVIDENCE_MISMATCH");
          execute(
              c,
              "INSERT INTO platform_evidence VALUES(?,?,?,?,?,?,?,?)",
              e.id,
              e.project,
              e.runId,
              e.invocation,
              e.digest,
              Json.write(e.result),
              e.verifier,
              e.verifiedAt);
          return null;
        });
  }

  public static String invocationDigest(RunState run) {
    return "sha256:"
        + Json.hash(
            List.of(
                run.id,
                run.pending.id,
                Objects.toString(run.pending.contractDigest, ""),
                Objects.toString(run.pending.toolKey, ""),
                run.pending.arguments == null
                    ? Json.tree(run.pending.modelRequest)
                    : run.pending.arguments));
  }

  public boolean acquireSlots(
      String project, String owner, long leaseMillis, int globalLimit, int projectLimit) {
    return transaction(
        c -> {
          int global = freeSlot(c, "global", globalLimit);
          if (global < 0) return false;
          int local = freeSlot(c, "project:" + project, projectLimit);
          if (local < 0) return false;
          long now = now(c);
          execute(
              c,
              "UPDATE platform_slots SET owner_id=?,lease_until=? WHERE pool_id=? AND"
                  + " slot_index=?",
              owner,
              now + leaseMillis,
              "global",
              global);
          execute(
              c,
              "UPDATE platform_slots SET owner_id=?,lease_until=? WHERE pool_id=? AND"
                  + " slot_index=?",
              owner,
              now + leaseMillis,
              "project:" + project,
              local);
          return true;
        });
  }

  private int freeSlot(Connection c, String pool, int limit) throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT slot_index,owner_id,lease_until FROM platform_slots WHERE"
                    + " pool_id=? AND slot_index<? ORDER BY slot_index FOR UPDATE",
                pool,
                limit);
        ResultSet r = s.executeQuery()) {
      long now = now(c);
      while (r.next()) if (r.getString(2) == null || r.getLong(3) <= now) return r.getInt(1);
      return -1;
    }
  }

  public void releaseSlots(String owner) {
    transaction(
        c -> {
          execute(
              c,
              "UPDATE platform_slots SET owner_id=NULL,lease_until=NULL WHERE" + " owner_id=?",
              owner);
          return null;
        });
  }

  public static long now(Connection c) throws SQLException {
    String sql =
        c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
            ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)"
            : "SELECT DATEDIFF('MILLISECOND', TIMESTAMP '1970-01-01 00:00:00',"
                + " CURRENT_TIMESTAMP)";
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  public static PreparedStatement prepare(Connection c, String sql, Object... args)
      throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    return s;
  }

  public static int execute(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = prepare(c, sql, args)) {
      return s.executeUpdate();
    }
  }

  private static boolean constraint(SQLException ex) {
    return ex.getSQLState() != null && ex.getSQLState().startsWith("23");
  }
}
