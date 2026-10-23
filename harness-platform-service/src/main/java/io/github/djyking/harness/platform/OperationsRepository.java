package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/** Durable, payload-free operations inbox and reviewable retention commands. */
public final class OperationsRepository {
  final PlatformRepository platform;

  public OperationsRepository(PlatformRepository platform) {
    this.platform = Objects.requireNonNull(platform);
  }

  public void initialize() {
    platform.transaction(
        c -> {
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS operations_alerts(project_id VARCHAR(128) NOT"
                + " NULL,rule_id VARCHAR(64) NOT NULL,alert_json LONGTEXT NOT"
                + " NULL,PRIMARY KEY(project_id,rule_id))",
            "CREATE TABLE IF NOT EXISTS operations_inbox(event_id VARCHAR(128) PRIMARY"
                + " KEY,project_id VARCHAR(128) NOT NULL,rule_id VARCHAR(64) NOT"
                + " NULL,event_json LONGTEXT NOT NULL,created_at BIGINT NOT NULL,INDEX"
                + " operations_inbox_project_idx(project_id,created_at))",
            "CREATE TABLE IF NOT EXISTS operations_commands(command_hash VARCHAR(64)"
                + " PRIMARY KEY,request_digest VARCHAR(64) NOT NULL,response_json"
                + " LONGTEXT NOT NULL,accepted_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS operations_retention_plans(plan_id VARCHAR(128)"
                + " PRIMARY KEY,project_id VARCHAR(128) NOT NULL,actor_scope"
                + " VARCHAR(300) NOT NULL,plan_json LONGTEXT NOT NULL,created_at BIGINT"
                + " NOT NULL)",
            "CREATE TABLE IF NOT EXISTS operations_retention_audit(audit_id"
                + " VARCHAR(128) PRIMARY KEY,project_id VARCHAR(128) NOT"
                + " NULL,audit_json LONGTEXT NOT NULL,created_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS operations_archives(archive_id VARCHAR(128)"
                + " PRIMARY KEY,project_id VARCHAR(128) NOT NULL,run_id VARCHAR(128)"
                + " NOT NULL,key_id VARCHAR(64) NOT NULL,payload_digest VARCHAR(64) NOT"
                + " NULL,ciphertext LONGTEXT NOT NULL,created_at BIGINT NOT"
                + " NULL,expires_at BIGINT NOT NULL,INDEX"
                + " operations_archive_expiry_idx(project_id,expires_at))",
            "CREATE TABLE IF NOT EXISTS operations_retained_runs(run_id VARCHAR(128)"
                + " PRIMARY KEY,archived_at BIGINT NOT NULL,audit_pruned INTEGER NOT"
                + " NULL)"
          };
          for (String sql : ddl) {
            if (mysql)
              sql =
                  sql.replaceAll(
                          "(\\b(?:project_id|rule_id|actor_scope)" + " VARCHAR\\(\\d+\\))",
                          "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                      + " ENGINE=InnoDB";
            execute(c, sql);
          }
          try (var s =
                  prepare(
                      c,
                      "SELECT schema_version FROM harness_schema WHERE"
                          + " component='operations'");
              var r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1)
              throw new IllegalStateException("Unsupported operations schema");
          }
          try {
            execute(
                c,
                "INSERT INTO harness_schema(component,schema_version)" + " VALUES('operations',1)");
          } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) throw e;
          }
          return null;
        });
  }

  public void validateSchema() {
    platform.transaction(
        c -> {
          SchemaValidation.require(
              c,
              "operations",
              1,
              Map.of(
                  "operations_alerts", "project_id,rule_id,alert_json",
                  "operations_inbox", "event_id,project_id,rule_id,event_json,created_at",
                  "operations_commands", "command_hash,request_digest,response_json,accepted_at",
                  "operations_retention_plans",
                      "plan_id,project_id,actor_scope,plan_json,created_at",
                  "operations_retention_audit", "audit_id,project_id,audit_json,created_at",
                  "operations_archives",
                      "archive_id,project_id,run_id,key_id,payload_digest,ciphertext,created_at,expires_at",
                  "operations_retained_runs", "run_id,archived_at,audit_pruned"));
          try (var s =
                  prepare(
                      c,
                      "SELECT schema_version FROM harness_schema WHERE"
                          + " component='operations'");
              var r = s.executeQuery()) {
            if (!r.next() || r.getInt(1) != 1)
              throw new IllegalStateException("Operations migration required");
          }
          for (String table :
              List.of(
                  "operations_alerts",
                  "operations_inbox",
                  "operations_commands",
                  "operations_retention_plans",
                  "operations_retention_audit",
                  "operations_archives",
                  "operations_retained_runs"))
            try (var s = prepare(c, "SELECT * FROM " + table + " WHERE 1=0")) {
              s.executeQuery().close();
            }
          return null;
        });
  }

  ObjectNode alert(Connection c, String project, String rule) throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT alert_json FROM operations_alerts WHERE project_id=? AND" + " rule_id=?",
                project,
                rule);
        var r = s.executeQuery()) {
      return r.next() ? (ObjectNode) Json.read(r.getString(1)) : null;
    }
  }

  void alert(Connection c, String project, String rule, JsonNode alert) throws SQLException {
    if (execute(
            c,
            "UPDATE operations_alerts SET alert_json=? WHERE project_id=? AND" + " rule_id=?",
            Json.write(alert),
            project,
            rule)
        == 0)
      execute(c, "INSERT INTO operations_alerts VALUES(?,?,?)", project, rule, Json.write(alert));
  }

  List<JsonNode> alerts(Connection c, String project) throws SQLException {
    List<JsonNode> result = new ArrayList<>();
    try (var s =
            prepare(
                c,
                "SELECT alert_json FROM operations_alerts WHERE project_id=? ORDER" + " BY rule_id",
                project);
        var r = s.executeQuery()) {
      while (r.next()) result.add(Json.read(r.getString(1)));
    }
    return result;
  }

  void event(Connection c, String project, String rule, ObjectNode alert, String event)
      throws SQLException {
    long now = now(c);
    String id = UUID.randomUUID().toString();
    var body =
        Json.object()
            .put("id", id)
            .put("project", project)
            .put("rule", rule)
            .put("transition", event)
            .put("revision", alert.path("revision").asLong())
            .put("createdAt", now)
            .put("delivery", "LOCAL_INBOX");
    execute(
        c,
        "INSERT INTO operations_inbox VALUES(?,?,?,?,?)",
        id,
        project,
        rule,
        Json.write(body),
        now);
  }

  JsonNode receipt(Connection c, String hash, String digest) throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT request_digest,response_json FROM operations_commands WHERE"
                    + " command_hash=?",
                hash);
        var r = s.executeQuery()) {
      if (!r.next()) return null;
      if (digest != null && !r.getString(1).equals(digest))
        throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
      return Json.read(r.getString(2));
    }
  }

  void receipt(Connection c, String hash, String digest, JsonNode response) throws SQLException {
    execute(
        c,
        "INSERT INTO operations_commands VALUES(?,?,?,?)",
        hash,
        digest,
        Json.write(response),
        now(c));
  }

  static String commandHash(IdentityProvider.Principal p, String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}")) throw ApiFailure.invalid();
    return Json.hash(List.of("operations", p.project(), p.assignment(), key));
  }
}
