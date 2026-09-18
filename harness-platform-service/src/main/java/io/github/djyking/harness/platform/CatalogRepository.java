package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/** Durable catalog, immutable versions and exact command receipts in the Run database. */
public final class CatalogRepository {
  private final PlatformRepository platform;

  public CatalogRepository(PlatformRepository platform) {
    this.platform = platform;
  }

  public void initialize() {
    platform.transaction(
        c -> {
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS catalog_resources(project_id VARCHAR(128) NOT"
                + " NULL,resource_type VARCHAR(32) NOT NULL,resource_id VARCHAR(128) NOT"
                + " NULL,revision BIGINT NOT NULL,document_json LONGTEXT NOT NULL,PRIMARY"
                + " KEY(project_id,resource_type,resource_id))",
            "CREATE TABLE IF NOT EXISTS catalog_versions(project_id VARCHAR(128) NOT"
                + " NULL,resource_type VARCHAR(32) NOT NULL,resource_id VARCHAR(128) NOT"
                + " NULL,version_no INTEGER NOT NULL,digest VARCHAR(80) NOT NULL,document_json"
                + " LONGTEXT NOT NULL,disabled BOOLEAN NOT NULL,PRIMARY"
                + " KEY(project_id,resource_type,resource_id,version_no))",
            "CREATE TABLE IF NOT EXISTS catalog_release_links(project_id VARCHAR(128) NOT"
                + " NULL,release_id VARCHAR(36) NOT NULL,agent_id VARCHAR(128) NOT NULL,version_no"
                + " INTEGER NOT NULL,refs_json LONGTEXT NOT NULL,PRIMARY"
                + " KEY(project_id,release_id))",
            "CREATE TABLE IF NOT EXISTS catalog_commands(command_hash VARCHAR(64) PRIMARY"
                + " KEY,request_digest VARCHAR(64) NOT NULL,response_json LONGTEXT NOT"
                + " NULL,project_id VARCHAR(128) NOT NULL,actor_scope VARCHAR(300) NOT"
                + " NULL,resource_type VARCHAR(32) NOT NULL,resource_id VARCHAR(128) NOT"
                + " NULL,operation VARCHAR(40) NOT NULL,accepted_at BIGINT NOT NULL)"
          };
          try (PreparedStatement s =
                  prepare(
                      c, "SELECT schema_version FROM harness_schema WHERE component='catalog'");
              ResultSet r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1)
              throw new IllegalStateException("Unsupported catalog schema");
          }
          for (String sql : ddl) {
            if (mysql)
              sql =
                  sql.replaceAll(
                          "(\\b(?:project_id|resource_type|resource_id|agent_id|actor_scope)"
                              + " VARCHAR\\(\\d+\\))",
                          "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                      + " ENGINE=InnoDB";
            execute(c, sql);
          }
          try {
            execute(c, "INSERT INTO harness_schema(component,schema_version) VALUES('catalog',1)");
          } catch (SQLException ex) {
            if (ex.getSQLState() == null || !ex.getSQLState().startsWith("23")) throw ex;
          }
          return null;
        });
  }

  public ObjectNode resource(Connection c, String project, String type, String id, boolean required)
      throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT document_json FROM catalog_resources WHERE project_id=? AND resource_type=?"
                    + " AND resource_id=?",
                project,
                type,
                id);
        ResultSet r = s.executeQuery()) {
      if (r.next()) return (ObjectNode) Json.read(r.getString(1));
      if (required) throw ApiFailure.hidden();
      return null;
    }
  }

  public void save(Connection c, String project, ObjectNode value, long previous)
      throws SQLException {
    String type = value.path("type").asText(), id = value.path("id").asText();
    long revision = value.path("revision").asLong();
    if (previous == 0)
      execute(
          c,
          "INSERT INTO catalog_resources VALUES(?,?,?,?,?)",
          project,
          type,
          id,
          revision,
          Json.write(value));
    else if (execute(
            c,
            "UPDATE catalog_resources SET revision=?,document_json=? WHERE project_id=? AND"
                + " resource_type=? AND resource_id=? AND revision=?",
            revision,
            Json.write(value),
            project,
            type,
            id,
            previous)
        != 1) throw new ApiFailure(412, "PRECONDITION_FAILED");
  }

  public List<ObjectNode> resources(Connection c, String project, String type) throws SQLException {
    List<ObjectNode> out = new ArrayList<>();
    String sql =
        "SELECT document_json FROM catalog_resources WHERE project_id=?"
            + (type == null ? "" : " AND resource_type=?")
            + " ORDER BY resource_type,resource_id LIMIT 1000";
    try (PreparedStatement s =
            type == null ? prepare(c, sql, project) : prepare(c, sql, project, type);
        ResultSet r = s.executeQuery()) {
      while (r.next()) out.add((ObjectNode) Json.read(r.getString(1)));
    }
    return out;
  }

  public ObjectNode version(Connection c, String project, String type, String id, int version)
      throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT document_json,disabled FROM catalog_versions WHERE project_id=? AND"
                    + " resource_type=? AND resource_id=? AND version_no=?",
                project,
                type,
                id,
                version);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw ApiFailure.hidden();
      ObjectNode out = (ObjectNode) Json.read(r.getString(1));
      out.put("disabled", r.getBoolean(2));
      return out;
    }
  }

  public List<ObjectNode> versions(Connection c, String project, String type, String id)
      throws SQLException {
    List<ObjectNode> out = new ArrayList<>();
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT document_json,disabled FROM catalog_versions WHERE project_id=? AND"
                    + " resource_type=? AND resource_id=? ORDER BY version_no DESC",
                project,
                type,
                id);
        ResultSet r = s.executeQuery()) {
      while (r.next()) {
        ObjectNode v = (ObjectNode) Json.read(r.getString(1));
        v.put("disabled", r.getBoolean(2));
        out.add(v);
      }
    }
    return out;
  }

  public void publish(Connection c, String project, ObjectNode version) throws SQLException {
    execute(
        c,
        "INSERT INTO catalog_versions VALUES(?,?,?,?,?,?,?)",
        project,
        version.path("type").asText(),
        version.path("id").asText(),
        version.path("version").asInt(),
        version.path("digest").asText(),
        Json.write(version),
        false);
  }

  public void disable(
      Connection c, String project, String type, String id, int version, boolean disabled)
      throws SQLException {
    if (execute(
            c,
            "UPDATE catalog_versions SET disabled=? WHERE project_id=? AND resource_type=? AND"
                + " resource_id=? AND version_no=?",
            disabled,
            project,
            type,
            id,
            version)
        != 1) throw ApiFailure.hidden();
  }

  public void release(Connection c, Deployment.Release release, int version, JsonNode refs)
      throws SQLException {
    execute(
        c,
        "INSERT INTO platform_releases VALUES(?,?,?,?)",
        release.projectId(),
        release.releaseId(),
        release.digest(),
        Json.write(release));
    execute(
        c,
        "INSERT INTO catalog_release_links VALUES(?,?,?,?,?)",
        release.projectId(),
        release.releaseId(),
        release.agentId(),
        version,
        Json.write(refs));
  }

  public Deployment.Release release(Connection c, String project, String id) throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT manifest_json FROM platform_releases WHERE project_id=? AND release_id=?",
                project,
                id);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
      return Json.convert(Json.read(r.getString(1)), Deployment.Release.class);
    }
  }

  public JsonNode releaseRefs(Connection c, String project, String release) throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT refs_json FROM catalog_release_links WHERE project_id=? AND release_id=?",
                project,
                release);
        ResultSet r = s.executeQuery()) {
      return r.next() ? Json.read(r.getString(1)) : null;
    }
  }

  public JsonNode receipt(Connection c, String hash, String digest) throws SQLException {
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT request_digest,response_json FROM catalog_commands WHERE command_hash=?",
                hash);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) return null;
      if (!r.getString(1).equals(digest)) throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
      return Json.read(r.getString(2));
    }
  }

  public void receipt(
      Connection c,
      String hash,
      String digest,
      JsonNode response,
      IdentityProvider.Principal p,
      String type,
      String id,
      String operation)
      throws SQLException {
    execute(
        c,
        "INSERT INTO catalog_commands VALUES(?,?,?,?,?,?,?,?,?)",
        hash,
        digest,
        Json.write(response),
        p.project(),
        p.assignment(),
        type,
        id,
        operation,
        now(c));
  }

  public JsonNode audit(Connection c, String project, String type, String id) throws SQLException {
    var out = Json.MAPPER.createArrayNode();
    try (PreparedStatement s =
            prepare(
                c,
                "SELECT actor_scope,operation,accepted_at FROM catalog_commands WHERE project_id=?"
                    + " AND resource_type=? AND resource_id=? ORDER BY accepted_at DESC LIMIT 100",
                project,
                type,
                id);
        ResultSet r = s.executeQuery()) {
      while (r.next())
        out.add(
            Json.object()
                .put("actor", r.getString(1))
                .put("operation", r.getString(2))
                .put("at", java.time.Instant.ofEpochMilli(r.getLong(3)).toString()));
    }
    return out;
  }
}
