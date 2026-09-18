package io.github.djyking.harness.validation;

import io.github.djyking.harness.adapters.http.HttpEndpoints;
import java.net.URI;
import java.util.*;

/**
 * Explicit host configuration. Missing settings never fall back to another application's secrets.
 */
public final class LiveSettings {
  private final Map<String, String> values;

  public LiveSettings(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  public String required(String key) {
    String value = values.get(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing setting: " + key);
    return value;
  }

  public String optional(String key, String fallback) {
    String v = values.get(key);
    return v == null || v.isBlank() ? fallback : v;
  }

  public int integer(String key, int fallback, int min, int max) {
    int value;
    try {
      value = Integer.parseInt(optional(key, Integer.toString(fallback)));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Invalid integer setting: " + key);
    }
    if (value < min || value > max)
      throw new IllegalArgumentException("Setting outside allowed range: " + key);
    return value;
  }

  public URI endpoint(String key) {
    try {
      return HttpEndpoints.requireSecureOrLoopback(URI.create(required(key)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid secure endpoint setting: " + key);
    }
  }

  public Map<String, String> bearer(String key) {
    String token = required(key);
    if (token.contains("\n") || token.contains("\r"))
      throw new IllegalArgumentException("Invalid credential format: " + key);
    return Map.of("Authorization", "Bearer " + token);
  }

  public String schema() {
    String database = required("HARNESS_MYSQL_DATABASE");
    if (!database.matches("harness_validation_[a-z0-9_]{1,40}"))
      throw new IllegalArgumentException(
          "MySQL probe requires a dedicated harness_validation_* schema");
    return database;
  }

  public String jdbcUrl() {
    String host = required("HARNESS_MYSQL_HOST");
    if (!host.matches("[A-Za-z0-9.-]{1,253}"))
      throw new IllegalArgumentException("Invalid MySQL host");
    int port = integer("HARNESS_MYSQL_PORT", 3306, 1, 65535);
    boolean local = Set.of("localhost", "127.0.0.1").contains(host);
    String tls = optional("HARNESS_MYSQL_TLS_MODE", local ? "DISABLED" : "VERIFY_IDENTITY");
    if (!"VERIFY_IDENTITY".equals(tls) && !(local && "DISABLED".equals(tls)))
      throw new IllegalArgumentException("Remote MySQL requires VERIFY_IDENTITY TLS");
    return "jdbc:mysql://"
        + host
        + ":"
        + port
        + "/"
        + schema()
        + "?sslMode="
        + tls
        + "&connectTimeout=5000&socketTimeout=15000"
        + (local ? "&allowPublicKeyRetrieval=true" : "");
  }

  public Map<String, List<String>> missing() {
    Map<String, List<String>> groups = new LinkedHashMap<>();
    groups.put(
        "model",
        List.of(
            "HARNESS_MODEL_ENDPOINT",
            "HARNESS_MODEL_NAME",
            "DEEPSEEK_API_KEY",
            "HARNESS_MODEL_CALL_LIMIT"));
    groups.put(
        "mcp-read",
        mcpAuth().equals("none")
            ? List.of("HARNESS_MCP_ENDPOINT", "HARNESS_MCP_READ_TOOL", "HARNESS_MCP_ARGUMENTS_FILE")
            : List.of(
                "HARNESS_MCP_ENDPOINT",
                "HARNESS_MCP_READ_TOOL",
                "HARNESS_MCP_ARGUMENTS_FILE",
                "HARNESS_MCP_TOKEN"));
    groups.put(
        "github-write",
        List.of("HARNESS_GITHUB_REPOSITORY", "HARNESS_GITHUB_ACCEPTANCE_TAG", "HARNESS_GITHUB_WRITE_CONFIRM", "HARNESS_MCP_TOKEN"));
    groups.put(
        "live-quality",
        List.of("HARNESS_MODEL_ENDPOINT", "HARNESS_MODEL_NAME", "DEEPSEEK_API_KEY", "HARNESS_QUALITY_CALL_LIMIT", "HARNESS_QUALITY_PLAN_FILE"));
    groups.put(
        "mysql",
        List.of(
            "HARNESS_MYSQL_HOST",
            "HARNESS_MYSQL_DATABASE",
            "HARNESS_MYSQL_USER",
            "HARNESS_MYSQL_PASSWORD"));
    groups.replaceAll(
        (name, keys) ->
            keys.stream().filter(k -> !values.containsKey(k) || values.get(k).isBlank()).toList());
    return groups;
  }

  public String mcpAuth() {
    String mode = optional("HARNESS_MCP_AUTH", "bearer");
    if (!Set.of("bearer", "none").contains(mode))
      throw new IllegalArgumentException("HARNESS_MCP_AUTH must be bearer or none");
    return mode;
  }
}
