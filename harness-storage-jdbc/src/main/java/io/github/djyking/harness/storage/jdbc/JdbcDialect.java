package io.github.djyking.harness.storage.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** The deliberately small set of databases supported by the initial SQL adapter. */
enum JdbcDialect {
  H2("SELECT CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3)) * 1000 AS BIGINT)"),
  MYSQL("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)");

  private final String clockQuery;

  JdbcDialect(String clockQuery) {
    this.clockQuery = clockQuery;
  }

  static JdbcDialect detect(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    if ("MySQL".equals(product)) return MYSQL;
    if ("H2".equals(product)) {
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME ="
                      + " 'MODE'")) {
        if (!result.next() || !"MySQL".equalsIgnoreCase(result.getString(1))) {
          throw new JdbcStorageException(
              "H2 must use MODE=MySQL for statement-scoped database lease time");
        }
      }
      return H2;
    }
    throw new JdbcStorageException(
        "Unsupported JDBC database: " + product + "; supported: H2 MODE=MySQL, MySQL 8");
  }

  long nowMillis(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(clockQuery)) {
      if (!result.next()) throw new SQLException("Database clock query returned no result");
      return result.getLong(1);
    }
  }
}
