package io.github.djyking.harness.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ValidationTest {
  @Test
  void preflightAccountsForMcpAuthenticationMode() {
    var values = new HashMap<String, String>();
    values.put("HARNESS_MCP_ENDPOINT", "https://example.com/mcp");
    values.put("HARNESS_MCP_READ_TOOL", "read");
    values.put("HARNESS_MCP_ARGUMENTS_FILE", "args.json");
    assertEquals(List.of("HARNESS_MCP_TOKEN"), new LiveSettings(values).missing().get("mcp-read"));
    values.put("HARNESS_MCP_AUTH", "none");
    assertTrue(new LiveSettings(values).missing().get("mcp-read").isEmpty());
    values.put("HARNESS_MCP_AUTH", "typo");
    assertThrows(IllegalArgumentException.class, () -> new LiveSettings(values).missing());
  }

  @Test
  void preflightIsOfflineAndNeverIncludesSecretValues() {
    var s = new LiveSettings(Map.of("DEEPSEEK_API_KEY", "secret-never-print"));
    var result = ValidationMain.preflight(s);
    assertEquals(0, result.path("networkCalls").asInt());
    assertFalse(result.toString().contains("secret-never-print"));
    assertEquals("MISSING_CONFIGURATION", result.path("model").path("status").asText());
  }

  @Test
  void rejectsBusinessSchemasAndRemoteInsecureSql() {
    var base = new HashMap<String, String>();
    base.put("HARNESS_MYSQL_HOST", "127.0.0.1");
    base.put("HARNESS_MYSQL_DATABASE", "ops_rag");
    assertThrows(IllegalArgumentException.class, () -> new LiveSettings(base).jdbcUrl());
    base.put("HARNESS_MYSQL_DATABASE", "harness_validation_test");
    assertTrue(new LiveSettings(base).jdbcUrl().contains("harness_validation_test"));
    base.put("HARNESS_MYSQL_HOST", "remote.example.com");
    base.put("HARNESS_MYSQL_TLS_MODE", "DISABLED");
    assertThrows(IllegalArgumentException.class, () -> new LiveSettings(base).jdbcUrl());
  }

  @Test
  void invalidEndpointsAndSecretsAreNotEchoed() {
    var s =
        new LiveSettings(
            Map.of(
                "HARNESS_MODEL_ENDPOINT",
                "https://user:secret@example.com/path",
                "TOKEN",
                "secret\nheader"));
    var endpoint =
        assertThrows(IllegalArgumentException.class, () -> s.endpoint("HARNESS_MODEL_ENDPOINT"));
    assertFalse(endpoint.getMessage().contains("secret"));
    assertThrows(IllegalArgumentException.class, () -> s.bearer("TOKEN"));
  }

  @Test
  void limitsAndMissingValuesFailClosed() {
    var s = new LiveSettings(Map.of("HARNESS_MODEL_CALL_LIMIT", "200"));
    assertThrows(
        IllegalArgumentException.class, () -> s.integer("HARNESS_MODEL_CALL_LIMIT", 2, 2, 2));
    assertThrows(IllegalArgumentException.class, () -> s.required("UNSET"));
  }

  @Test
  void acceptanceAlgorithmUsesRealStoreInOfflineSmoke() throws Exception {
    JdbcDataSource s = new JdbcDataSource();
    s.setURL(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    var report = MySqlAcceptance.exercise(s);
    assertEquals("H2", report.path("databaseProduct").asText());
    assertEquals(6, report.path("passedChecks").asInt());
    assertFalse(report.path("businessTablesAccessed").asBoolean());
  }
}
