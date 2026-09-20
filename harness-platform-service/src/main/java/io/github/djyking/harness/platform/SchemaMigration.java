package io.github.djyking.harness.platform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.core.env.Environment;

/** Offline DDL entry point. Never constructs an HTTP client, connector, worker or web server. */
final class SchemaMigration {
  private SchemaMigration() {}

  static void run(Environment env) {
    run(env, SecretProvider.environment());
  }

  static void run(Environment env, SecretProvider secrets) {
    RuntimeSettings.from(env);
    Deployment deployment = Deployment.load(Path.of(required(env, "HARNESS_CONFIG")));
    String bindings =
        GovernanceSettings.bindingFingerprint(
            deployment, secrets, env.getProperty("HARNESS_IDENTITY_PROVIDER", "opsagent"));
    HikariConfig jdbc = new HikariConfig();
    jdbc.setJdbcUrl(database(env, "URL"));
    jdbc.setUsername(database(env, "USER"));
    jdbc.setPassword(database(env, "PASSWORD"));
    jdbc.setMaximumPoolSize(1);
    jdbc.setConnectionTimeout(5000);
    try (HikariDataSource dataSource = new HikariDataSource(jdbc)) {
      var store = new JdbcRunStore(dataSource);
      var platform = new PlatformRepository(store, true);
      var governance =
          new ExecutionGovernance(platform, GovernanceSettings.load(env), deployment, bindings);
      String previous = env.getProperty("HARNESS_EXPECTED_GOVERNANCE_FINGERPRINT", "");
      try (var connection = dataSource.getConnection()) {
        preflight(
            connection,
            deployment,
            GovernanceSettings.load(env),
            governance.fingerprint(),
            previous,
            env.getProperty("HARNESS_MIGRATION_OFFLINE_ACK", ""));
      } catch (SQLException unavailable) {
        throw new IllegalStateException("Migration preflight unavailable");
      }
      store.initializeSchema();
      platform.initialize(deployment);
      new CatalogRepository(platform).initialize();
      new StudioRepository(platform).initialize();
      new CapabilityRepository(platform).initialize();
      new PlatformTrace(dataSource, true);
      if (!previous.isBlank()) governance.migrateConfiguration(previous);
      governance.initialize();
      new OperationsService(deployment, platform).initialize();
      System.out.println(
          Json.write(
              Json.object()
                  .put("status", "MIGRATED")
                  .put("governanceFingerprint", governance.fingerprint())));
    }
  }

  /**
   * Read-only preflight must precede MySQL's implicitly committed DDL and static manifest writes.
   */
  static void preflight(
      Connection c,
      Deployment deployment,
      ExecutionGovernanceConfig config,
      String fingerprint,
      String previous,
      String offlineAck)
      throws SQLException {
    Set<String> tables = new HashSet<>();
    try (var metadata =
        c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
      while (metadata.next()) tables.add(metadata.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
    }
    if (!tables.contains("harness_schema")) {
      if (!previous.isBlank())
        throw new IllegalStateException("MIGRATION_CAS_WITHOUT_EXISTING_CLUSTER");
      return;
    }
    if (!offlineAck.equals("drained-isolated-cluster"))
      throw new IllegalStateException("MIGRATION_OFFLINE_ACK_REQUIRED");
    if (tables.contains("harness_runs")
        && scalar(
                c,
                "SELECT COUNT(*) FROM harness_runs WHERE run_status NOT IN"
                    + " ('COMPLETED','FAILED','CANCELLED','EXPIRED','BUDGET_EXCEEDED')")
            > 0) throw new IllegalStateException("MIGRATION_REQUIRES_DRAINED_CLUSTER");
    long recent = PlatformRepository.now(c) - Math.max(30000, deployment.leaseMillis() + 10000);
    if (tables.contains("platform_slots")
        && scalar(
                c,
                "SELECT COUNT(*) FROM platform_slots WHERE owner_id IS NOT NULL AND lease_until>?",
                PlatformRepository.now(c))
            > 0) throw new IllegalStateException("MIGRATION_REQUIRES_DRAINED_CLUSTER");
    if (tables.contains("gov_worker")
        && scalar(
                c,
                "SELECT COUNT(*) FROM gov_worker WHERE state<>'STOPPED' AND heartbeat_at>?",
                recent)
            > 0) throw new IllegalStateException("MIGRATION_REQUIRES_OFFLINE_WORKERS");
    if (tables.contains("gov_permit")
        && scalar(
                c,
                "SELECT COUNT(*) FROM gov_permit WHERE state IN"
                    + " ('RESERVED','DISPATCHED','UNKNOWN')")
            > 0) throw new IllegalStateException("MIGRATION_REQUIRES_RESOLVED_PERMITS");
    if (tables.contains("gov_config")) {
      try (var statement =
          c.prepareStatement("SELECT fingerprint FROM gov_config WHERE cluster_id=?")) {
        statement.setString(1, config.clusterId());
        try (var rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException("MIGRATION_CLUSTER_MISMATCH");
          String existing = rows.getString(1);
          if ((!previous.isBlank() && !previous.equals(existing))
              || (previous.isBlank() && !fingerprint.equals(existing)))
            throw new IllegalStateException("MIGRATION_FINGERPRINT_REVIEW_REQUIRED");
        }
      }
    } else if (!previous.isBlank())
      throw new IllegalStateException("MIGRATION_CAS_WITHOUT_EXISTING_CLUSTER");
  }

  private static long scalar(Connection connection, String sql, Object... values)
      throws SQLException {
    try (var statement = PlatformRepository.prepare(connection, sql, values);
        var rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static String database(Environment env, String suffix) {
    String explicit = env.getProperty("HARNESS_MIGRATION_JDBC_" + suffix);
    return explicit == null || explicit.isBlank()
        ? required(env, "HARNESS_JDBC_" + suffix)
        : explicit;
  }

  private static String required(Environment env, String name) {
    String value = env.getProperty(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Required configuration missing: " + name);
    return value;
  }
}
