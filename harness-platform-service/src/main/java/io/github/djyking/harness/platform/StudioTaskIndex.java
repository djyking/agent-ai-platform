package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable task ownership and ordering metadata, committed with the product document. */
final class StudioTaskIndex {
  static final String COLUMNS =
      "task_sequence,project_id,actor_scope,application_id,document_id,snapshot_id,created_at";
  private static final Map<String, String> INDEXES =
      Map.of(
          "studio_task_owner_idx", "project_id,actor_scope,created_at,document_id,task_sequence",
          "studio_task_app_idx",
              "project_id,actor_scope,application_id,created_at,document_id,task_sequence");

  private StudioTaskIndex() {}

  static void initialize(Connection c) throws SQLException {
    boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
    String ddl =
        "CREATE TABLE IF NOT EXISTS studio_task_index(task_sequence BIGINT AUTO_INCREMENT PRIMARY"
            + " KEY,project_id VARCHAR(128) NOT NULL,actor_scope VARCHAR(300) NOT"
            + " NULL,application_id VARCHAR(128) NOT NULL,document_id VARCHAR(128) NOT"
            + " NULL,snapshot_id VARCHAR(128) NOT NULL,created_at BIGINT NOT NULL,"
            + "UNIQUE(project_id,document_id))";
    if (mysql)
      ddl =
          ddl.replaceAll(
                  "(\\b(?:project_id|actor_scope|application_id|document_id|snapshot_id)"
                      + " VARCHAR\\(\\d+\\))",
                  "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
              + " ENGINE=InnoDB";
    execute(c, ddl);
    Map<String, List<String>> existing = indexes(c);
    for (var index : INDEXES.entrySet())
      if (!existing.containsKey(index.getKey()))
        execute(
            c,
            "CREATE INDEX " + index.getKey() + " ON studio_task_index(" + index.getValue() + ")");
    // Explicit offline migration only. Batches bound heap use and permit safe retries after
    // MySQL implicitly commits DDL. Original documents and payloads remain unchanged.
    String afterProject = "", afterId = "";
    while (true) {
      var batch = new ArrayList<Backfill>();
      try (var statement =
              prepare(
                  c,
                  "SELECT d.project_id,d.document_id,d.document_json FROM studio_documents d"
                      + " LEFT JOIN studio_task_index i ON i.project_id=d.project_id"
                      + " AND i.document_id=d.document_id WHERE d.document_kind='TASK'"
                      + " AND i.task_sequence IS NULL AND (d.project_id>? OR"
                      + " (d.project_id=? AND d.document_id>?))"
                      + " ORDER BY d.project_id,d.document_id LIMIT 500",
                  afterProject,
                  afterProject,
                  afterId);
          var rows = statement.executeQuery()) {
        while (rows.next())
          batch.add(
              new Backfill(rows.getString(1), rows.getString(2), Json.read(rows.getString(3))));
      }
      if (batch.isEmpty()) break;
      for (var row : batch) insert(c, row.project(), row.id(), row.body());
      var end = batch.get(batch.size() - 1);
      afterProject = end.project();
      afterId = end.id();
    }
    validate(c);
  }

  private record Backfill(String project, String id, JsonNode body) {}

  static void insert(Connection c, String project, String id, JsonNode body) throws SQLException {
    if (!id.equals(body.path("id").asText())
        || body.path("owner").asText().isBlank()
        || body.path("owner").asText().length() > 300)
      throw new IllegalStateException("Invalid Studio task ownership metadata");
    ApiJson.identifier(project);
    ApiJson.uuid(id);
    ApiJson.identifier(body.path("applicationId").asText());
    ApiJson.uuid(body.path("snapshotId").asText());
    long createdAt = Instant.parse(body.path("createdAt").asText()).toEpochMilli();
    if (createdAt < 0) throw new IllegalStateException("Invalid Studio task creation time");
    execute(
        c,
        "INSERT INTO studio_task_index(project_id,actor_scope,application_id,document_id,"
            + "snapshot_id,created_at) VALUES(?,?,?,?,?,?)",
        project,
        body.path("owner").asText(),
        body.path("applicationId").asText(),
        id,
        body.path("snapshotId").asText(),
        createdAt);
  }

  static void validate(Connection c) throws SQLException {
    SchemaValidation.require(c, null, 0, Map.of("studio_task_index", COLUMNS));
    var actual = indexes(c);
    for (var index : INDEXES.entrySet())
      if (!List.of(index.getValue().split(",")).equals(actual.get(index.getKey())))
        throw new IllegalStateException("Studio task index migration required");
    try (var statement =
            prepare(
                c,
                "SELECT d.document_id FROM studio_documents d LEFT JOIN studio_task_index i"
                    + " ON i.project_id=d.project_id AND i.document_id=d.document_id"
                    + " WHERE d.document_kind='TASK' AND i.task_sequence IS NULL LIMIT 1");
        var rows = statement.executeQuery()) {
      if (rows.next()) throw new IllegalStateException("Incomplete Studio task index migration");
    }
    try (var statement =
            prepare(
                c,
                "SELECT i.document_id FROM studio_task_index i LEFT JOIN studio_documents d"
                    + " ON d.project_id=i.project_id AND d.document_id=i.document_id"
                    + " AND d.document_kind='TASK' WHERE d.document_id IS NULL LIMIT 1");
        var rows = statement.executeQuery()) {
      if (rows.next()) throw new IllegalStateException("Orphaned Studio task index");
    }
  }

  private static Map<String, List<String>> indexes(Connection c) throws SQLException {
    String table =
        c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
            ? "studio_task_index"
            : "STUDIO_TASK_INDEX";
    var columns = new HashMap<String, TreeMap<Integer, String>>();
    try (var metadata = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
      while (metadata.next()) {
        String index = metadata.getString("INDEX_NAME"), column = metadata.getString("COLUMN_NAME");
        if (index != null && column != null)
          columns
              .computeIfAbsent(index.toLowerCase(Locale.ROOT), ignored -> new TreeMap<>())
              .put(metadata.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
      }
    }
    var indexes = new HashMap<String, List<String>>();
    columns.forEach((name, values) -> indexes.put(name, List.copyOf(values.values())));
    return indexes;
  }

  record Row(ObjectNode task, ObjectNode snapshot, long createdAt) {}

  static List<Row> page(
      Connection c,
      IdentityProvider.Principal p,
      String applicationId,
      long watermark,
      long beforeTime,
      String beforeId,
      int limit)
      throws SQLException {
    String sql =
        "SELECT d.document_json,s.document_json,i.created_at FROM studio_task_index i"
            + " JOIN studio_documents d ON d.project_id=i.project_id"
            + " AND d.document_kind='TASK' AND d.document_id=i.document_id"
            + " LEFT JOIN studio_documents s ON s.project_id=i.project_id"
            + " AND s.document_kind='SNAPSHOT' AND s.document_id=i.snapshot_id"
            + " WHERE i.project_id=? AND i.actor_scope=?"
            + (applicationId == null ? "" : " AND i.application_id=?")
            + " AND i.task_sequence<=? AND (i.created_at<? OR"
            + " (i.created_at=? AND i.document_id<?))"
            + " ORDER BY i.created_at DESC,i.document_id DESC LIMIT ?";
    var args = new ArrayList<Object>(List.of(p.project(), p.assignment()));
    if (applicationId != null) args.add(applicationId);
    args.addAll(List.of(watermark, beforeTime, beforeTime, beforeId, limit));
    var out = new ArrayList<Row>();
    try (var statement = prepare(c, sql, args.toArray());
        var rows = statement.executeQuery()) {
      while (rows.next()) {
        if (rows.getString(2) == null)
          throw new IllegalStateException("Missing Studio task snapshot");
        out.add(
            new Row(
                (ObjectNode) Json.read(rows.getString(1)),
                (ObjectNode) Json.read(rows.getString(2)),
                rows.getLong(3)));
      }
    }
    return out;
  }
}
