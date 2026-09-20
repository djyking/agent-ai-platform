package io.github.djyking.harness.platform;

import java.sql.*;
import java.util.Map;

/** Read-only startup validation; schema changes belong to an explicit migration process. */
public final class SchemaValidation {
  private SchemaValidation() {}

  public static void require(
      Connection c, String component, int version, Map<String, String> tables) throws SQLException {
    if (component != null) {
      try (var s =
          c.prepareStatement("SELECT schema_version FROM harness_schema WHERE component=?")) {
        s.setString(1, component);
        try (var r = s.executeQuery()) {
          if (!r.next() || r.getInt(1) != version)
            throw new IllegalStateException("Schema migration required for " + component);
        }
      }
    }
    for (var table : tables.entrySet()) {
      if (!table.getKey().matches("[a-z_]+") || !table.getValue().matches("[a-z_,]+"))
        throw new IllegalArgumentException("Invalid schema contract");
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT " + table.getValue() + " FROM " + table.getKey() + " WHERE 1=0")) {
        r.getMetaData();
      }
    }
    if (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")) {
      for (String table : tables.keySet()) {
        try (var s =
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                    + " table_name=? AND column_name IN"
                    + " ('project_id','application_id','subject_id','actor_scope','scope_key','pool_id','cluster_id',"
                    + "'resource_type','resource_id','document_kind','document_id','kind','record_id')"
                    + " AND collation_name IS NOT NULL AND collation_name<>'utf8mb4_bin'")) {
          s.setString(1, table);
          try (var r = s.executeQuery()) {
            r.next();
            if (r.getInt(1) != 0)
              throw new IllegalStateException("Identity collation migration required");
          }
        }
      }
    }
  }
}
