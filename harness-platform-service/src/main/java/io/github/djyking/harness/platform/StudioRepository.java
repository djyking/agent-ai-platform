package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/** Product documents and receipts enlist in the same transaction as the execution ledger. */
public final class StudioRepository {
  private final PlatformRepository platform;

  public StudioRepository(PlatformRepository platform) {
    this.platform = platform;
  }

  public void initialize() {
    platform.transaction(
        c -> {
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS studio_documents(project_id VARCHAR(128) NOT"
                + " NULL,document_kind VARCHAR(32) NOT NULL,document_id VARCHAR(128) NOT"
                + " NULL,document_json LONGTEXT NOT NULL,PRIMARY"
                + " KEY(project_id,document_kind,document_id))",
            "CREATE TABLE IF NOT EXISTS studio_commands(command_hash VARCHAR(64) PRIMARY"
                + " KEY,request_digest VARCHAR(64) NOT NULL,project_id VARCHAR(128) NOT"
                + " NULL,actor_scope VARCHAR(300) NOT NULL,resource_id VARCHAR(128) NOT"
                + " NULL,operation VARCHAR(40) NOT NULL,response_json LONGTEXT NOT NULL,accepted_at"
                + " BIGINT NOT NULL)"
          };
          for (String sql : ddl) {
            if (mysql)
              sql =
                  sql.replaceAll(
                          "(\\b(?:project_id|document_kind|document_id|actor_scope|resource_id)"
                              + " VARCHAR\\(\\d+\\))",
                          "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                      + " ENGINE=InnoDB";
            execute(c, sql);
          }
          try (var s =
                  prepare(c, "SELECT schema_version FROM harness_schema WHERE component='studio'");
              var r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1)
              throw new IllegalStateException("Unsupported Studio schema");
          }
          try {
            execute(c, "INSERT INTO harness_schema(component,schema_version) VALUES('studio',1)");
          } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) throw e;
          }
          return null;
        });
  }

  public ObjectNode get(Connection c, String project, String kind, String id, boolean required)
      throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT document_json FROM studio_documents WHERE project_id=? AND document_kind=?"
                    + " AND document_id=?",
                project,
                kind,
                id);
        var r = s.executeQuery()) {
      if (r.next()) return (ObjectNode) Json.read(r.getString(1));
      if (required) throw ApiFailure.hidden();
      return null;
    }
  }

  public List<ObjectNode> list(Connection c, String project, String kind) throws SQLException {
    var out = new ArrayList<ObjectNode>();
    try (var s =
            prepare(
                c,
                "SELECT document_json FROM studio_documents WHERE project_id=? AND document_kind=?"
                    + " ORDER BY document_id",
                project,
                kind);
        var r = s.executeQuery()) {
      while (r.next()) {
        if (out.size() >= 2000) throw new ApiFailure(409, "STUDIO_LIST_LIMIT");
        out.add((ObjectNode) Json.read(r.getString(1)));
      }
    }
    return out;
  }

  public void put(Connection c, String project, String kind, String id, JsonNode body)
      throws SQLException {
    int updated;
    try (var s =
        prepare(
            c,
            "UPDATE studio_documents SET document_json=? WHERE project_id=? AND document_kind=? AND"
                + " document_id=?",
            Json.write(body),
            project,
            kind,
            id)) {
      updated = s.executeUpdate();
    }
    if (updated == 0)
      execute(
          c, "INSERT INTO studio_documents VALUES(?,?,?,?)", project, kind, id, Json.write(body));
  }

  public ObjectNode receipt(Connection c, String hash, String digest) throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT request_digest,resource_id,operation,response_json FROM studio_commands"
                    + " WHERE command_hash=?",
                hash);
        var r = s.executeQuery()) {
      if (!r.next()) return null;
      if (digest != null && !r.getString(1).equals(digest))
        throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
      var out =
          Json.object()
              .put("status", "COMPLETED")
              .put("resourceId", r.getString(2))
              .put("operation", r.getString(3));
      out.set("response", Json.read(r.getString(4)));
      return out;
    }
  }

  public void receipt(
      Connection c,
      String hash,
      String digest,
      IdentityProvider.Principal p,
      String id,
      String operation,
      JsonNode result)
      throws SQLException {
    execute(
        c,
        "INSERT INTO studio_commands VALUES(?,?,?,?,?,?,?,?)",
        hash,
        digest,
        p.project(),
        p.assignment(),
        id,
        operation,
        Json.write(result),
        now(c));
  }

  public void release(Connection c, Deployment.Release release) throws SQLException {
    execute(
        c,
        "INSERT INTO platform_releases VALUES(?,?,?,?)",
        release.projectId(),
        release.releaseId(),
        release.digest(),
        Json.write(release));
  }
}
