package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.RunStatus;
import io.github.djyking.harness.core.Json;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

final class OperationsTest {
  static IdentityProvider.Principal admin() {
    return new IdentityProvider.Principal("app-a", "project-a", "alice", Set.of("*"));
  }

  @Test
  void aggregatesOwnScopeUnlessExplicitlyGrantedOperationsAdmin() {
    try (var f = new PlatformTestSupport()) {
      new PlatformTrace(f.dataSource);
      f.create("read", "own-run-key1");
      f.create(f.principal("app-a", "project-a", "bob"), "read", "other-run-key1");
      f.create(f.principal("app-a", "project-b", "alice"), "read", "other-project-key1");
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      var ordinary =
          new IdentityProvider.Principal(
              "app-a", "project-a", "alice", Set.of("catalog:read", "runs:admin"));
      var own = service.overview(ordinary);
      assertEquals("OWN_TASKS", own.path("scope").asText());
      assertEquals(1, own.path("statusCounts").path("QUEUED").asInt());
      assertTrue(own.path("workers").isEmpty());
      assertEquals(2, service.overview(admin()).path("statusCounts").path("QUEUED").asInt());
      assertFalse(own.toString().contains("snapshot"));
      assertThrows(ApiFailure.class, () -> service.overview(f.principal()));
      assertTrue(service.health());
      service.validateSchema();
    }
  }

  @Test
  void alertsDeduplicateRecoverRecurAndAcknowledgeWithActorScopedReceipts() {
    try (var f = new PlatformTestSupport()) {
      new PlatformTrace(f.dataSource);
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      for (int i = 0; i < 2; i++)
        f.repository.transaction(
            c -> {
              f.repository.lockProject(c, "project-a");
              service.transition(c, "project-a", "QUEUE_DELAY", true, 42000, "WARNING");
              return null;
            });
      assertEquals(1, service.inbox(admin(), 0, 100).path("items").size());
      var first = service.overview(admin()).path("alerts").get(0);
      assertEquals(1, first.path("revision").asInt());
      var ack = service.acknowledge(admin(), "QUEUE_DELAY", "ack-command-1234", "\"o1\"");
      assertEquals(
          Json.write(ack),
          Json.write(service.acknowledge(admin(), "QUEUE_DELAY", "ack-command-1234", "\"o1\"")));
      assertEquals(Json.write(ack), Json.write(service.command(admin(), "ack-command-1234")));
      assertEquals(2, service.inbox(admin(), 0, 100).path("items").size());
      assertEquals(
          412,
          assertThrows(
                  ApiFailure.class,
                  () -> service.acknowledge(admin(), "QUEUE_DELAY", "ack-command-5678", "\"o1\""))
              .status);
      var bob = new IdentityProvider.Principal("app-a", "project-a", "bob", Set.of("*"));
      assertEquals(
          404,
          assertThrows(ApiFailure.class, () -> service.command(bob, "ack-command-1234")).status);
      f.repository.transaction(
          c -> {
            f.repository.lockProject(c, "project-a");
            service.transition(c, "project-a", "QUEUE_DELAY", false, 0, "WARNING");
            service.transition(c, "project-a", "QUEUE_DELAY", true, 45000, "WARNING");
            return null;
          });
      var next = service.overview(admin()).path("alerts").get(0);
      assertFalse(next.path("acknowledged").asBoolean());
      assertEquals(2, next.path("occurrences").asInt());
      assertEquals(4, service.inbox(admin(), 0, 100).path("items").size());
      assertEquals(
          4,
          new OperationsService(f.config, f.repository)
              .inbox(admin(), 0, 100)
              .path("items")
              .size());
    }
  }

  @Test
  void heartbeatAndProviderUnknownAreFixedPayloadFreeAlerts() {
    try (var f = new PlatformTestSupport()) {
      new PlatformTrace(f.dataSource);
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      var execution = Json.object();
      execution.putArray("workers");
      execution.putArray("unknownPermits").addObject().put("permitId", "private-invocation-id");
      service.governance(project -> execution);
      service.refreshAlerts("project-a");
      service.refreshAlerts("project-a");
      var inbox = service.inbox(admin(), 0, 100);
      assertEquals(2, inbox.path("items").size());
      assertFalse(inbox.toString().contains("private-invocation-id"));
      assertTrue(service.metrics().contains("harness_workers_available{project=\"project-a\"} 0"));
      assertFalse(service.metrics().contains("private-invocation-id"));
    }
  }

  @Test
  void alertRefreshUsesActiveIndexInsteadOfScanningTerminalHistory() {
    try (var f = new PlatformTestSupport()) {
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      String history = f.id(f.create("read", "alert-history-seed"));
      assertEquals(RunStatus.COMPLETED, f.drive(history).status);
      String queued = f.id(f.create("read", "alert-queued-run"));
      String unknown = f.id(f.create("model", "alert-unknown-run"));
      f.uncertainModel.set(true);
      assertEquals(RunStatus.NEEDS_ATTENTION, f.drive(unknown).status);
      String foreign =
          f.id(f.create(f.principal("app-a", "project-b", "alice"), "read", "alert-foreign-run"));
      f.repository.transaction(
          c -> {
            // Large valid terminal fixture: copies retain matching IDs in their snapshots.
            execute(
                c,
                "INSERT INTO harness_runs SELECT CONCAT('alert-history-',x),"
                    + " CONCAT('alert-history-',x),creation_digest,revision,fence,lease_until,"
                    + " run_status,next_attempt_at,created_at,"
                    + " REPLACE(snapshot_json,run_id,CONCAT('alert-history-',x)),event_sequence"
                    + " FROM harness_runs CROSS JOIN SYSTEM_RANGE(1,1000) WHERE run_id=?",
                history);
            execute(
                c,
                "INSERT INTO harness_run_index (run_id,run_status,charged_tokens,model_calls,"
                    + "tool_calls,updated_at,terminal_at,deadline_at,expirable_at,"
                    + PlatformRunProjection.DIMENSIONS
                    + ") SELECT CONCAT('alert-history-',x),run_status,"
                    + " charged_tokens,model_calls,tool_calls,updated_at,terminal_at,deadline_at,"
                    + " expirable_at,"
                    + PlatformRunProjection.DIMENSIONS
                    + " FROM harness_run_index CROSS JOIN SYSTEM_RANGE(1,1000)"
                    + " WHERE run_id=?",
                history);
            execute(
                c,
                "INSERT INTO platform_runs SELECT CONCAT('alert-history-',x),project_id,"
                    + " application_id,subject_id,delegation_id,release_json,limits_json,"
                    + " client_reference,created_at,reserved_tokens FROM platform_runs"
                    + " CROSS JOIN SYSTEM_RANGE(1,1000) WHERE run_id=?",
                history);
            execute(
                c,
                "UPDATE platform_runs SET created_at=? WHERE run_id IN (?,?)",
                now(c) - 60_000,
                queued,
                foreign);
            execute(
                c,
                "UPDATE harness_run_index SET platform_created_at= (SELECT created_at FROM"
                    + " platform_runs p WHERE p.run_id=harness_run_index.run_id) WHERE run_id IN"
                    + " (?,?)",
                queued,
                foreign);
            execute(c, "ANALYZE");
            execute(c, "SET QUERY_STATISTICS TRUE");
            return null;
          });
      service.refreshAlerts("project-a");
      service.refreshAlerts("project-a");
      var inbox = service.inbox(admin(), 0, 100).path("items");
      assertEquals(2, inbox.size());
      f.repository.transaction(
          c -> {
            assertEquals(
                1,
                service
                    .repository
                    .alert(c, "project-a", "UNRESOLVED_EXECUTION")
                    .path("value")
                    .asLong());
            assertTrue(
                service.repository.alert(c, "project-a", "QUEUE_DELAY").path("value").asLong()
                    >= 60_000);
            List<String> historyReads = new ArrayList<>();
            try (var query =
                    prepare(c, "SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS");
                var rows = query.executeQuery()) {
              while (rows.next()) {
                String sql = rows.getString(1);
                if (sql.toLowerCase(Locale.ROOT).contains("from harness_run_index")
                    || sql.toLowerCase(Locale.ROOT).contains("join harness_run_index"))
                  historyReads.add(sql);
              }
            }
            // This inspects statements actually executed by refresh, not a disconnected SQL string.
            assertEquals(List.of(OperationsService.ALERT_RUNS_SQL), historyReads);
            try (var query =
                    prepare(c, "EXPLAIN ANALYZE " + historyReads.get(0), "project-a", "project-a");
                var rows = query.executeQuery()) {
              assertTrue(rows.next());
              String plan = rows.getString(1);
              assertTrue(plan.contains("HARNESS_PLATFORM_USAGE_IDX"), plan);
              var scans = Pattern.compile("scanCount: (\\d+)").matcher(plan);
              int measured = 0;
              while (scans.find()) {
                measured++;
                assertTrue(Long.parseLong(scans.group(1)) < 20, plan);
              }
              assertTrue(measured > 0, plan);
            }
            return null;
          });
    }
  }

  @Test
  void metricsRequireDedicatedSecretAndNeverTrustForwardedLoopback() {
    try (var f = new PlatformTestSupport()) {
      new PlatformTrace(f.dataSource);
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      var request = new MockHttpServletRequest();
      request.addHeader("X-Forwarded-For", "127.0.0.1");
      var disabled = new PlatformMetricsController(service, new MockEnvironment());
      assertEquals(404, assertThrows(ApiFailure.class, () -> disabled.scrape(request)).status);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new PlatformMetricsController(
                  service, new MockEnvironment().withProperty("HARNESS_METRICS_TOKEN", "short")));
      String token = "synthetic-independent-metrics-secret-123456";
      var metrics =
          new PlatformMetricsController(
              service, new MockEnvironment().withProperty("HARNESS_METRICS_TOKEN", token));
      assertEquals(401, assertThrows(ApiFailure.class, () -> metrics.scrape(request)).status);
      request.addHeader("Authorization", "Bearer " + token);
      assertTrue(metrics.scrape(request).getBody().contains("harness_database_ready 1"));
      assertFalse(metrics.scrape(request).getBody().contains(token));
      request.addHeader("Authorization", "Bearer extra");
      assertEquals(400, assertThrows(ApiFailure.class, () -> metrics.scrape(request)).status);
    }
  }
}
