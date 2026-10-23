package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Project-scoped, payload-free operational projections. Alert delivery defaults to a SQL inbox. */
public final class OperationsService {
  static final String ALERT_RUNS_SQL =
      "SELECT run_status,COUNT(*),MIN(platform_created_at) FROM harness_run_index"
          + " WHERE platform_project_id=? AND run_status='QUEUED' GROUP BY run_status"
          + " UNION ALL SELECT run_status,COUNT(*),MIN(platform_created_at) FROM harness_run_index"
          + " WHERE platform_project_id=? AND run_status='NEEDS_ATTENTION'"
          + " GROUP BY run_status";

  private final Deployment config;
  private final PlatformRepository platform;
  final OperationsRepository repository;
  private final PlatformHttpMetrics httpMetrics = new PlatformHttpMetrics();
  private Function<String, JsonNode> governance = project -> Json.object().put("available", false);

  public OperationsService(Deployment config, PlatformRepository platform) {
    this.config = config;
    this.platform = platform;
    this.repository = new OperationsRepository(platform);
  }

  public void initialize() {
    repository.initialize();
  }

  public void validateSchema() {
    repository.validateSchema();
  }

  public void governance(Function<String, JsonNode> source) {
    governance = Objects.requireNonNull(source);
  }

  public PlatformHttpMetrics httpMetrics() {
    return httpMetrics;
  }

  public boolean health() {
    try {
      return platform.transaction(
          c -> {
            try (var s = prepare(c, "SELECT 1");
                var r = s.executeQuery()) {
              return r.next() && r.getInt(1) == 1;
            }
          });
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static void require(IdentityProvider.Principal p, String permission) {
    if (!p.permits(permission)) throw ApiFailure.denied();
  }

  public JsonNode overview(IdentityProvider.Principal p) {
    require(p, "catalog:read");
    boolean admin = p.permits("operations:admin");
    ObjectNode out = platform.transaction(c -> summary(c, p.project(), admin ? null : p));
    out.put("scope", admin ? "PROJECT" : "OWN_TASKS");
    out.putObject("capabilities").put("admin", admin);
    out.set("retention", RetentionService.policy());
    out.putObject("backup")
        .put("status", "EXTERNAL_EVIDENCE_REQUIRED")
        .put("source", "BACKUP_JOB_AND_RESTORE_PROOF");
    if (admin) {
      JsonNode execution = governance.apply(p.project());
      out.set("execution", execution);
      out.set(
          "workers",
          execution.has("workers") ? execution.path("workers") : Json.MAPPER.createArrayNode());
      out.set("alerts", Json.tree(platform.transaction(c -> repository.alerts(c, p.project()))));
    } else {
      out.putArray("alerts");
      out.putArray("workers");
      out.putObject("execution").put("visibility", "ADMIN_REQUIRED");
    }
    return out;
  }

  ObjectNode summary(Connection c, String project, IdentityProvider.Principal owner)
      throws SQLException {
    long now = now(c);
    ObjectNode out = Json.object().put("project", project).put("generatedAt", now);
    ObjectNode counts = out.putObject("statusCounts");
    String where =
        " WHERE platform_project_id=?"
            + (owner == null ? "" : " AND platform_application_id=? AND platform_subject_id=?");
    Object[] args =
        owner == null
            ? new Object[] {project}
            : new Object[] {project, owner.application(), owner.subject()};
    long reserved = 0, charged = 0, models = 0, tools = 0, oldest = now, queued = 0;
    try (var s =
            prepare(
                c,
                "SELECT"
                    + " run_status,COUNT(*),COALESCE(SUM(platform_reserved_tokens),0),COALESCE(SUM(charged_tokens),0),COALESCE(SUM(model_calls),0),COALESCE(SUM(tool_calls),0),MIN(platform_created_at)"
                    + " FROM harness_run_index"
                    + where
                    + " GROUP BY run_status",
                args);
        var r = s.executeQuery()) {
      while (r.next()) {
        String status = r.getString(1);
        counts.put(status, r.getLong(2));
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "BUDGET_EXCEEDED")
            .contains(status)) reserved += r.getLong(3);
        charged += r.getLong(4);
        models += r.getLong(5);
        tools += r.getLong(6);
        if (status.equals("QUEUED")) {
          queued += r.getLong(2);
          oldest = Math.min(oldest, r.getLong(7));
        }
      }
    }
    out.putObject("queue")
        .put("queued", queued)
        .put("oldestAgeMs", queued == 0 ? 0 : Math.max(0, now - oldest));
    out.putObject("usage")
        .put("reservedTokens", reserved)
        .put("chargedTokens", charged)
        .put("modelCalls", models)
        .put("toolCalls", tools)
        .put("accounting", "CONSERVATIVE_LEDGER");
    return out;
  }

  /** Called periodically by the host; reads do not silently change alert state. */
  public void refreshAlerts() {
    for (var project : config.projects()) refreshAlerts(project.id());
  }

  public void refreshAlerts(String project) {
    platform.transaction(
        c -> {
          platform.lockProject(c, project);
          // Alert refresh shares the admission lock. Restrict its projection to indexed active
          // states; a full historical usage summary here makes create latency grow with history.
          long unknown = 0, age = 0, observedAt = now(c);
          try (var query = prepare(c, ALERT_RUNS_SQL, project, project);
              var rows = query.executeQuery()) {
            while (rows.next()) {
              if (rows.getString(1).equals("NEEDS_ATTENTION")) unknown = rows.getLong(2);
              if (rows.getString(1).equals("QUEUED"))
                age = Math.max(0, observedAt - rows.getLong(3));
            }
          }
          transition(c, project, "UNRESOLVED_EXECUTION", unknown > 0, unknown, "ACTION_REQUIRED");
          transition(c, project, "QUEUE_DELAY", age > 30_000, age, "WARNING");
          JsonNode execution = governance.apply(project);
          if (execution.has("workers")) {
            long now = now(c), live = 0;
            for (JsonNode worker : execution.path("workers"))
              if (worker.path("heartbeatAt").asLong() >= now - 45_000
                  && worker.path("fingerprintMatches").asBoolean()
                  && !worker.path("state").asText().equals("STOPPED")) live++;
            transition(c, project, "WORKER_UNAVAILABLE", live == 0, live, "ACTION_REQUIRED");
          }
          if (execution.has("unknownPermits"))
            transition(
                c,
                project,
                "UNRESOLVED_PROVIDER_PERMIT",
                execution.path("unknownPermitCount").asLong(execution.path("unknownPermits").size())
                    > 0,
                execution
                    .path("unknownPermitCount")
                    .asLong(execution.path("unknownPermits").size()),
                "ACTION_REQUIRED");
          return null;
        });
  }

  /** Fixed rule names only: receivers never accept arbitrary remote messages or payloads. */
  void transition(
      Connection c, String project, String rule, boolean firing, long value, String severity)
      throws SQLException {
    ObjectNode alert = repository.alert(c, project, rule);
    if (alert == null && !firing) return;
    String state = firing ? "FIRING" : "RECOVERED";
    long now = now(c);
    if (alert == null)
      alert =
          Json.object()
              .put("id", rule)
              .put("revision", 0)
              .put("firstSeenAt", now)
              .put("occurrences", 0);
    boolean changed = !state.equals(alert.path("state").asText());
    alert.put("state", state).put("severity", severity).put("value", value).put("observedAt", now);
    if (changed) {
      alert
          .put("revision", alert.path("revision").asLong() + 1)
          .put("changedAt", now)
          .put("acknowledged", false);
      alert.remove(List.of("acknowledgedBy", "acknowledgedAt"));
      if (firing) alert.put("occurrences", alert.path("occurrences").asLong() + 1);
      repository.event(c, project, rule, alert, state);
    }
    repository.alert(c, project, rule, alert);
  }

  public JsonNode acknowledge(IdentityProvider.Principal p, String id, String key, String ifMatch) {
    require(p, "operations:admin");
    ApiJson.identifier(id);
    String hash = OperationsRepository.commandHash(p, key);
    String digest = Json.hash(List.of("ack", id, Objects.toString(ifMatch, "")));
    return platform.transaction(
        c -> {
          platform.lockProject(c, p.project());
          JsonNode previous = repository.receipt(c, hash, digest);
          if (previous != null) return previous;
          ObjectNode alert = repository.alert(c, p.project(), id);
          if (alert == null) throw ApiFailure.hidden();
          if (ifMatch == null) throw new ApiFailure(428, "PRECONDITION_REQUIRED");
          if (!ifMatch.equals("\"o" + alert.path("revision").asLong() + "\""))
            throw new ApiFailure(412, "PRECONDITION_FAILED");
          alert
              .put("revision", alert.path("revision").asLong() + 1)
              .put("acknowledged", true)
              .put("acknowledgedBy", p.assignment())
              .put("acknowledgedAt", now(c));
          repository.alert(c, p.project(), id, alert);
          repository.event(c, p.project(), id, alert, "ACKNOWLEDGED");
          repository.receipt(c, hash, digest, alert);
          return alert;
        });
  }

  public JsonNode command(IdentityProvider.Principal p, String key) {
    require(p, "operations:admin");
    return platform.transaction(
        c -> {
          JsonNode value = repository.receipt(c, OperationsRepository.commandHash(p, key), null);
          if (value == null) throw ApiFailure.hidden();
          return value;
        });
  }

  public JsonNode inbox(IdentityProvider.Principal p, long after, int limit) {
    require(p, "operations:admin");
    if (after < 0 || limit < 1 || limit > 200) throw ApiFailure.invalid();
    return platform.transaction(
        c -> {
          ObjectNode out = Json.object().put("delivery", "LOCAL_INBOX");
          var items = out.putArray("items");
          try (var s =
                  prepare(
                      c,
                      "SELECT event_json FROM operations_inbox WHERE"
                          + " project_id=? AND created_at>=? ORDER BY"
                          + " created_at,event_id LIMIT ?",
                      p.project(),
                      after,
                      limit);
              var r = s.executeQuery()) {
            while (r.next()) items.add(Json.read(r.getString(1)));
          }
          return out;
        });
  }

  public String metrics() {
    StringBuilder text =
        new StringBuilder(
            "# HELP harness_database_ready Database probe succeeded.\n"
                + "# TYPE harness_database_ready gauge\n");
    try {
      var samples =
          platform.transaction(
              c -> {
                StringBuilder b = new StringBuilder("harness_database_ready 1\n");
                for (var project : config.projects()) {
                  JsonNode summary = summary(c, project.id(), null);
                  String labels = "project=\"" + metricLabel(project.id()) + "\"";
                  summary
                      .path("statusCounts")
                      .fields()
                      .forEachRemaining(
                          e ->
                              b.append("harness_runs{")
                                  .append(labels)
                                  .append(",status=\"")
                                  .append(metricLabel(e.getKey()))
                                  .append("\"} ")
                                  .append(e.getValue().asLong())
                                  .append('\n'));
                  for (var metric :
                      Map.of(
                              "harness_tokens_charged",
                              "chargedTokens",
                              "harness_tokens_reserved",
                              "reservedTokens",
                              "harness_model_calls_total",
                              "modelCalls",
                              "harness_tool_calls_total",
                              "toolCalls")
                          .entrySet())
                    b.append(metric.getKey())
                        .append('{')
                        .append(labels)
                        .append("} ")
                        .append(summary.path("usage").path(metric.getValue()).asLong())
                        .append('\n');
                  b.append("harness_queue_oldest_age_seconds{")
                      .append(labels)
                      .append("} ")
                      .append(summary.path("queue").path("oldestAgeMs").asDouble() / 1000)
                      .append('\n');
                  b.append("harness_alerts_firing{")
                      .append(labels)
                      .append("} ")
                      .append(
                          repository.alerts(c, project.id()).stream()
                              .filter(a -> a.path("state").asText().equals("FIRING"))
                              .count())
                      .append('\n');
                  JsonNode execution = governance.apply(project.id());
                  if (execution.has("workers")) {
                    long live = 0, current = now(c);
                    for (JsonNode worker : execution.path("workers"))
                      if (worker.path("heartbeatAt").asLong() >= current - 45_000
                          && worker.path("fingerprintMatches").asBoolean()
                          && !worker.path("state").asText().equals("STOPPED")) live++;
                    b.append("harness_workers_available{")
                        .append(labels)
                        .append("} ")
                        .append(live)
                        .append('\n');
                  }
                  if (execution.has("unknownPermits"))
                    b.append("harness_unknown_provider_permits{")
                        .append(labels)
                        .append("} ")
                        .append(
                            execution
                                .path("unknownPermitCount")
                                .asLong(execution.path("unknownPermits").size()))
                        .append('\n');
                }
                appendDependencyLatency(c, b);
                return b.toString();
              });
      return text.append(samples).append(httpMetrics.scrape()).toString();
    } catch (RuntimeException unavailable) {
      return text.append("harness_database_ready 0\n").append(httpMetrics.scrape()).toString();
    }
  }

  private void appendDependencyLatency(Connection c, StringBuilder output) throws SQLException {
    output.append(
        "# HELP harness_dependency_calls_window Recorded model/tool spans started in the last 900"
            + " seconds; incomplete/crashed spans are not inferred.\n"
            + "# TYPE harness_dependency_calls_window gauge\n"
            + "# HELP harness_dependency_duration_seconds_window_sum Total recorded dependency"
            + " duration within the 900 second window.\n"
            + "# TYPE harness_dependency_duration_seconds_window_sum gauge\n"
            + "# TYPE harness_dependency_duration_seconds_window_mean gauge\n"
            + "# TYPE harness_dependency_duration_seconds_window_max gauge\n");
    Map<String, double[]> values = new HashMap<>();
    long now = now(c);
    // One bounded-time index range over telemetry, joined to the ownership index. No Run JSON.
    try (var s =
            prepare(
                c,
                "SELECT"
                    + " p.project_id,t.operation_name,COUNT(*),SUM(t.duration_ms),MAX(t.duration_ms)"
                    + " FROM harness_platform_trace t JOIN platform_runs p ON p.run_id=t.run_id"
                    + " WHERE t.started_at>=? AND t.started_at<=? AND t.operation_name IN"
                    + " ('MODEL','TOOL') GROUP BY p.project_id,t.operation_name",
                now - 900_000,
                now);
        var r = s.executeQuery()) {
      while (r.next())
        values.put(
            r.getString(1) + "/" + r.getString(2),
            new double[] {r.getDouble(3), r.getDouble(4) / 1000, r.getDouble(5) / 1000});
    }
    for (var project : config.projects())
      for (String operation : List.of("MODEL", "TOOL")) {
        double[] data = values.getOrDefault(project.id() + "/" + operation, new double[3]);
        String labels =
            "project=\"" + metricLabel(project.id()) + "\",operation=\"" + operation + "\"";
        output
            .append("harness_dependency_calls_window{")
            .append(labels)
            .append("} ")
            .append((long) data[0])
            .append('\n');
        output
            .append("harness_dependency_duration_seconds_window_sum{")
            .append(labels)
            .append("} ")
            .append(data[1])
            .append('\n');
        output
            .append("harness_dependency_duration_seconds_window_mean{")
            .append(labels)
            .append("} ")
            .append(data[0] == 0 ? 0 : data[1] / data[0])
            .append('\n');
        output
            .append("harness_dependency_duration_seconds_window_max{")
            .append(labels)
            .append("} ")
            .append(data[2])
            .append('\n');
      }
  }

  private static String metricLabel(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
