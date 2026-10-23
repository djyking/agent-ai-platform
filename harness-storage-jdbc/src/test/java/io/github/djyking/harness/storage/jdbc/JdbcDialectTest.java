package io.github.djyking.harness.storage.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDialectTest {
  @Test
  void requiresStatementScopedH2Clock() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
    try (Connection connection = dataSource.getConnection()) {
      JdbcStorageException failure =
          assertThrows(JdbcStorageException.class, () -> JdbcDialect.detect(connection));
      assertTrue(failure.getMessage().contains("MODE=MySQL"));
    }
  }

  @Test
  void databaseTimeAdvancesInsideOneTransactionAndHasNoSessionTimezoneOffset() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
    try (Connection connection = dataSource.getConnection()) {
      connection.createStatement().execute("SET TIME ZONE '+08:00'");
      connection.setAutoCommit(false);
      JdbcDialect dialect = JdbcDialect.detect(connection);
      long first = dialect.nowMillis(connection);
      Thread.sleep(30);
      long second = dialect.nowMillis(connection);
      assertTrue(
          second > first, "A lock wait must not freeze lease validation at transaction start");
      assertTrue(
          Math.abs(Instant.now().toEpochMilli() - second) < 2_000,
          "UTC epoch milliseconds must be independent of the database session timezone");
      connection.rollback();
    }
  }
}
