package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import java.sql.*;
import java.util.*;

/**
 * Platform-owned, immutable dimensions on the core's transactional metadata index. The SDK knows
 * nothing about these optional columns; its explicit-column status/usage updates preserve them.
 */
final class PlatformRunProjection {
  static final String COMPONENT = "platform-run-projection";
  static final String DIMENSIONS =
      "platform_project_id,platform_application_id,platform_subject_id,platform_created_at,platform_reserved_tokens";
  private static final String MEASURES =
      "run_status,charged_tokens,model_calls,tool_calls,platform_reserved_tokens,platform_created_at";
  private static final Map<String, String> INDEXES =
      Map.of(
          "harness_platform_usage_idx", "platform_project_id," + MEASURES,
          "harness_platform_owner_usage_idx",
              "platform_project_id,platform_application_id,platform_subject_id," + MEASURES);

  private PlatformRunProjection() {}

  static void initialize(Connection c) throws SQLException {
    try (var query =
            prepare(c, "SELECT schema_version FROM harness_schema WHERE component=?", COMPONENT);
        var rows = query.executeQuery()) {
      if (rows.next() && rows.getInt(1) != 1)
        throw new IllegalStateException("Unsupported platform Run projection schema");
    }
    boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
    String table = mysql ? "harness_run_index" : "HARNESS_RUN_INDEX";
    Set<String> columns = new HashSet<>();
    try (var metadata = c.getMetaData().getColumns(c.getCatalog(), null, table, null)) {
      while (metadata.next())
        columns.add(metadata.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
    }
    for (String column : DIMENSIONS.split(",")) {
      if (!columns.contains(column)) {
        String type =
            column.endsWith("_id")
                ? "VARCHAR(128)" + (mysql ? " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "")
                : "BIGINT";
        execute(c, "ALTER TABLE harness_run_index ADD COLUMN " + column + " " + type);
      }
    }
    // This runs only in explicit migration/bootstrap, before serving requests. Run payloads and
    // statuses are untouched, including UNKNOWN state; admission writes these dimensions
    // atomically.
    String assignments =
        "i.platform_project_id=p.project_id,i.platform_application_id=p.application_id,"
            + "i.platform_subject_id=p.subject_id,i.platform_created_at=p.created_at,"
            + "i.platform_reserved_tokens=p.reserved_tokens";
    execute(
        c,
        mysql
            ? "UPDATE harness_run_index i JOIN platform_runs p ON p.run_id=i.run_id SET "
                + assignments
            : "MERGE INTO harness_run_index i USING platform_runs p ON p.run_id=i.run_id"
                + " WHEN MATCHED THEN UPDATE SET "
                + assignments);
    Map<String, List<String>> indexes = indexes(c, table);
    for (var entry : INDEXES.entrySet())
      if (!indexes.containsKey(entry.getKey()))
        execute(
            c,
            "CREATE INDEX " + entry.getKey() + " ON harness_run_index(" + entry.getValue() + ")");
    try (var query =
            prepare(c, "SELECT schema_version FROM harness_schema WHERE component=?", COMPONENT);
        var rows = query.executeQuery()) {
      if (!rows.next()) execute(c, "INSERT INTO harness_schema VALUES(?,1)", COMPONENT);
    }
    validate(c);
  }

  static void attach(Connection c, Owned owned) throws SQLException {
    try (var query =
        prepare(
            c,
            "UPDATE harness_run_index SET platform_project_id=?,platform_application_id=?,"
                + "platform_subject_id=?,platform_created_at=?,platform_reserved_tokens=?"
                + " WHERE run_id=? AND platform_project_id IS NULL",
            owned.project(),
            owned.application(),
            owned.subject(),
            owned.createdAt(),
            owned.limits().maxTokens(),
            owned.runId())) {
      if (query.executeUpdate() != 1)
        throw new IllegalStateException("Missing or already owned platform Run projection");
    }
  }

  static void validate(Connection c) throws SQLException {
    SchemaValidation.require(c, COMPONENT, 1, Map.of("harness_run_index", "run_id," + DIMENSIONS));
    boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
    Map<String, List<String>> indexes =
        indexes(c, mysql ? "harness_run_index" : "HARNESS_RUN_INDEX");
    for (var entry : INDEXES.entrySet())
      if (!List.of(entry.getValue().split(",")).equals(indexes.get(entry.getKey())))
        throw new IllegalStateException("Platform Run projection index migration required");
    if (mysql)
      try (var query =
              prepare(
                  c,
                  "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                      + " AND table_name='harness_run_index' AND column_name IN"
                      + " ('platform_project_id','platform_application_id','platform_subject_id')"
                      + " AND collation_name<>'utf8mb4_bin'");
          var rows = query.executeQuery()) {
        rows.next();
        if (rows.getInt(1) != 0)
          throw new IllegalStateException("Platform Run projection identity collation mismatch");
      }
    try (var query =
            prepare(
                c,
                "SELECT p.run_id FROM platform_runs p LEFT JOIN harness_run_index i ON"
                    + " i.run_id=p.run_id WHERE i.run_id IS NULL OR i.platform_project_id IS NULL"
                    + " OR i.platform_application_id IS NULL OR i.platform_subject_id IS NULL OR"
                    + " i.platform_created_at IS NULL OR i.platform_reserved_tokens IS NULL OR"
                    + " i.platform_project_id<>p.project_id OR"
                    + " i.platform_application_id<>p.application_id OR"
                    + " i.platform_subject_id<>p.subject_id OR i.platform_created_at<>p.created_at"
                    + " OR i.platform_reserved_tokens<>p.reserved_tokens LIMIT 1");
        var rows = query.executeQuery()) {
      if (rows.next())
        throw new IllegalStateException("Incomplete platform Run projection migration");
    }
    try (var query =
            prepare(
                c,
                "SELECT i.run_id FROM harness_run_index i LEFT JOIN platform_runs p ON"
                    + " p.run_id=i.run_id WHERE i.platform_project_id IS NOT NULL AND p.run_id IS"
                    + " NULL LIMIT 1");
        var rows = query.executeQuery()) {
      if (rows.next()) throw new IllegalStateException("Orphaned platform Run projection");
    }
  }

  private static Map<String, List<String>> indexes(Connection c, String table) throws SQLException {
    Map<String, TreeMap<Integer, String>> columns = new HashMap<>();
    try (var metadata = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
      while (metadata.next()) {
        String index = metadata.getString("INDEX_NAME"), column = metadata.getString("COLUMN_NAME");
        if (index != null && column != null)
          columns
              .computeIfAbsent(index.toLowerCase(Locale.ROOT), ignored -> new TreeMap<>())
              .put(metadata.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
      }
    }
    Map<String, List<String>> indexes = new HashMap<>();
    columns.forEach((name, values) -> indexes.put(name, List.copyOf(values.values())));
    return indexes;
  }
}
