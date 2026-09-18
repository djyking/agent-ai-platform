package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/** Shared-capability state and receipts use the same database and transaction as Runs. */
public final class CapabilityRepository {
  private final PlatformRepository platform;

  public CapabilityRepository(PlatformRepository platform) {
    this.platform = platform;
  }

  public void initialize() {
    platform.transaction(
        c -> {
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          try (var s =
                  prepare(
                      c,
                      "SELECT schema_version FROM harness_schema WHERE"
                          + " component='capabilities'");
              var r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1)
              throw new IllegalStateException("Unsupported capabilities schema");
          }
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS capability_records(project_id VARCHAR(128) NOT"
                + " NULL,kind VARCHAR(32) NOT NULL,record_id VARCHAR(260) NOT"
                + " NULL,revision BIGINT NOT NULL,document_json LONGTEXT NOT"
                + " NULL,PRIMARY KEY(project_id,kind,record_id))",
            "CREATE TABLE IF NOT EXISTS capability_versions(project_id VARCHAR(128) NOT"
                + " NULL,kind VARCHAR(32) NOT NULL,record_id VARCHAR(260) NOT"
                + " NULL,version_no BIGINT NOT NULL,document_json LONGTEXT NOT"
                + " NULL,PRIMARY KEY(project_id,kind,record_id,version_no))",
            "CREATE TABLE IF NOT EXISTS capability_commands(command_hash VARCHAR(64)"
                + " PRIMARY KEY,request_digest VARCHAR(64) NOT NULL,response_json"
                + " LONGTEXT NOT NULL,project_id VARCHAR(128) NOT NULL,actor_scope"
                + " VARCHAR(300) NOT NULL,kind VARCHAR(32) NOT NULL,record_id"
                + " VARCHAR(260) NOT NULL,operation VARCHAR(40) NOT NULL,accepted_at"
                + " BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS knowledge_run_sources(run_id VARCHAR(128) NOT"
                + " NULL,source_hash VARCHAR(64) NOT NULL,project_id VARCHAR(128) NOT"
                + " NULL,source_json LONGTEXT NOT NULL,PRIMARY KEY(run_id,source_hash))"
          };
          for (String sql : ddl) {
            if (mysql)
              sql =
                  sql.replaceAll(
                          "(\\b(?:project_id|kind|record_id|actor_scope)" + " VARCHAR\\(\\d+\\))",
                          "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                      + " ENGINE=InnoDB";
            execute(c, sql);
          }
          try {
            execute(
                c,
                "INSERT INTO harness_schema(component,schema_version)"
                    + " VALUES('capabilities',1)");
          } catch (SQLException ex) {
            if (ex.getSQLState() == null || !ex.getSQLState().startsWith("23")) throw ex;
          }
          return null;
        });
  }

  public ObjectNode get(Connection c, String project, String kind, String id, boolean required)
      throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT document_json FROM capability_records WHERE project_id=?"
                    + " AND kind=? AND record_id=?",
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
    List<ObjectNode> result = new ArrayList<>();
    try (var s =
            prepare(
                c,
                "SELECT document_json FROM capability_records WHERE project_id=?"
                    + " AND kind=? ORDER BY record_id LIMIT 1000",
                project,
                kind);
        var r = s.executeQuery()) {
      while (r.next()) result.add((ObjectNode) Json.read(r.getString(1)));
    }
    return result;
  }

  public void save(
      Connection c, String project, String kind, String id, long previous, ObjectNode value)
      throws SQLException {
    value.put("revision", previous + 1);
    if (previous == 0)
      execute(
          c,
          "INSERT INTO capability_records VALUES(?,?,?,?,?)",
          project,
          kind,
          id,
          1,
          Json.write(value));
    else if (execute(
            c,
            "UPDATE capability_records SET revision=?,document_json=? WHERE"
                + " project_id=? AND kind=? AND record_id=? AND revision=?",
            previous + 1,
            Json.write(value),
            project,
            kind,
            id,
            previous)
        != 1) throw new ApiFailure(412, "PRECONDITION_FAILED");
    execute(
        c,
        "INSERT INTO capability_versions VALUES(?,?,?,?,?)",
        project,
        kind,
        id,
        previous + 1,
        Json.write(value));
  }

  public ObjectNode version(Connection c, String project, String kind, String id, long version)
      throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT document_json FROM capability_versions WHERE project_id=?"
                    + " AND kind=? AND record_id=? AND version_no=?",
                project,
                kind,
                id,
                version);
        var r = s.executeQuery()) {
      if (!r.next()) throw ApiFailure.hidden();
      return (ObjectNode) Json.read(r.getString(1));
    }
  }

  @FunctionalInterface
  public interface Mutation {
    ObjectNode apply(Connection c, ObjectNode current) throws SQLException;
  }

  @FunctionalInterface
  public interface Guard {
    void check(Connection c) throws SQLException;
  }

  public JsonNode mutate(
      IdentityProvider.Principal p,
      String kind,
      String id,
      String operation,
      String key,
      String etag,
      JsonNode body,
      Mutation mutation) {
    return mutate(p, kind, id, operation, key, etag, body, c -> {}, mutation);
  }

  public JsonNode mutate(
      IdentityProvider.Principal p,
      String kind,
      String id,
      String operation,
      String key,
      String etag,
      JsonNode body,
      Guard guard,
      Mutation mutation) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw new ApiFailure(400, "IDEMPOTENCY_KEY_REQUIRED");
    if (etag == null) throw new ApiFailure(428, "PRECONDITION_REQUIRED");
    if (!etag.matches("\"k[0-9]{1,18}\"")) throw ApiFailure.invalid();
    long expected = Long.parseLong(etag.substring(2, etag.length() - 1));
    if (body == null || !body.isObject()) throw ApiFailure.invalid();
    if (Json.write(body).length() > 60000) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    String hash = commandHash(p, key);
    String digest = Json.hash(List.of(kind, id, operation, ApiJson.canonical(body), etag));
    return platform.transaction(
        c -> {
          platform.lockProject(c, p.project());
          guard.check(c);
          try (var s =
                  prepare(
                      c,
                      "SELECT request_digest,response_json FROM"
                          + " capability_commands WHERE command_hash=?",
                      hash);
              var r = s.executeQuery()) {
            if (r.next()) {
              if (!digest.equals(r.getString(1))) throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
              return Json.read(r.getString(2));
            }
          }
          ObjectNode current = get(c, p.project(), kind, id, false);
          long revision = current == null ? 0 : current.path("revision").asLong();
          if (revision != expected) throw new ApiFailure(412, "PRECONDITION_FAILED");
          ObjectNode next = mutation.apply(c, current);
          save(c, p.project(), kind, id, revision, next);
          execute(
              c,
              "INSERT INTO capability_commands VALUES(?,?,?,?,?,?,?,?,?)",
              hash,
              digest,
              Json.write(next),
              p.project(),
              p.assignment(),
              kind,
              id,
              operation,
              now(c));
          return next;
        });
  }

  public void source(Connection c, String run, String project, JsonNode source)
      throws SQLException {
    String hash = Json.hash(ApiJson.canonical(source));
    try (var s =
            prepare(
                c,
                "SELECT source_hash FROM knowledge_run_sources WHERE run_id=? AND"
                    + " source_hash=?",
                run,
                hash);
        var r = s.executeQuery()) {
      if (!r.next())
        execute(
            c,
            "INSERT INTO knowledge_run_sources VALUES(?,?,?,?)",
            run,
            hash,
            project,
            Json.write(source));
    }
  }

  public List<JsonNode> sources(Connection c, String run, String project) throws SQLException {
    List<JsonNode> out = new ArrayList<>();
    try (var s =
            prepare(
                c,
                "SELECT source_json FROM knowledge_run_sources WHERE run_id=? AND"
                    + " project_id=? ORDER BY source_hash",
                run,
                project);
        var r = s.executeQuery()) {
      while (r.next()) out.add(Json.read(r.getString(1)));
    }
    return out;
  }

  /** Receipt metadata only: expired document access must not expose an earlier response body. */
  public JsonNode command(IdentityProvider.Principal p, String key, boolean knowledge) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw ApiFailure.invalid();
    return platform.transaction(
        c -> {
          try (var s =
                  prepare(
                      c,
                      "SELECT kind,record_id,operation,response_json FROM"
                          + " capability_commands WHERE command_hash=?",
                      commandHash(p, key));
              var r = s.executeQuery()) {
            if (!r.next() || r.getString(1).startsWith("knowledge-") != knowledge)
              return Json.object().put("status", "NOT_FOUND");
            JsonNode response = Json.read(r.getString(4));
            return Json.object()
                .put("status", "COMPLETED")
                .put("kind", r.getString(1))
                .put("id", r.getString(2))
                .put("operation", r.getString(3))
                .put("revision", response.path("revision").asLong())
                .put("responseDigest", "sha256:" + Json.hash(ApiJson.canonical(response)));
          }
        });
  }

  private static String commandHash(IdentityProvider.Principal p, String key) {
    return Json.hash(List.of(p.project(), p.application(), p.subject(), key));
  }

  public static String etag(JsonNode value) {
    return "\"k" + value.path("revision").asLong() + "\"";
  }
}
