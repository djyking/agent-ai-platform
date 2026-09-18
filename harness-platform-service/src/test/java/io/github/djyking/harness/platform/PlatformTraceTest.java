package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformTraceTest {
  @Test
  void twoCollectorsShareOrderedPayloadFreeRecordsAndEnforceCurrentOwnership() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var traces = new PlatformTrace(f.dataSource);
      var p = f.service.authenticate("project-a", "app-a-credential", "alice-token");
      String id =
          f.id(
              f.service.create(
                  p,
                  "app-a-credential",
                  "alice-token",
                  "trace-test-run1",
                  f.body("project-a", "read")));
      var context =
          new ExecutionContext(
              id,
              "search",
              id + ":invocation",
              "attempt-1",
              new Actor("alice", "project-a", Set.of()),
              Instant.now().plusSeconds(10),
              "abcdef1234567890");
      var first = traces.start(context, "TOOL", "safe:read");
      first.outcome("SUCCESS");
      first.close();
      first.close();
      var second = new PlatformTrace(f.dataSource).start(context, "TOOL", "secret\nprovider body");
      second.close();
      var page = traces.list(id, 0, 1);
      assertEquals(1, page.path("items").size());
      assertTrue(page.path("hasMore").asBoolean());
      var next = traces.list(id, page.path("nextAfter").asLong(), 100);
      assertEquals("REDACTED", next.path("items").get(0).path("target").asText());
      assertEquals("UNKNOWN", next.path("items").get(0).path("outcome").asText());
      assertFalse(next.toString().contains("provider body"));
      var mvc =
          MockMvcBuilders.standaloneSetup(new TraceController(f.service, traces))
              .setControllerAdvice(new ApiErrors())
              .build();
      String route = "/v1/projects/project-a/runs/" + id + "/trace";
      mvc.perform(
              get(route)
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(2));
      mvc.perform(
              get(route)
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "bob-token"))
          .andExpect(status().isNotFound());
      mvc.perform(
              get(route)
                  .header("Authorization", "Bearer app-b-credential")
                  .header("X-Harness-User-Token", "alice-token"))
          .andExpect(status().isNotFound());
      mvc.perform(
              get(route.replace("project-a", "project-b"))
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token"))
          .andExpect(status().isNotFound());
      f.identities.revoke("app-a", "project-a", "alice", "runs:events:read");
      mvc.perform(
              get(route)
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void diagnosticStorageFailureCannotChangeTheExternalCallOutcome() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var traces = new PlatformTrace(f.dataSource);
      var context =
          new ExecutionContext(
              "synthetic-run",
              "node",
              "invocation",
              "attempt",
              new Actor("alice", "project-a", Set.of()),
              Instant.now().plusSeconds(10),
              "trace");
      var span = traces.start(context, "TOOL", "read");
      span.outcome("SUCCESS");
      try (var c = f.dataSource.getConnection();
          var statement = c.createStatement()) {
        statement.execute("DROP TABLE harness_platform_trace");
      }
      assertDoesNotThrow(span::close);
      assertEquals(
          503, assertThrows(ApiFailure.class, () -> traces.list("synthetic-run", 0, 10)).status);
    }
  }

  @Test
  void unsafeDiagnosticValuesAreRedactedInBothDatabaseAndStructuredLogs() throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                io.github.djyking.harness.core.StructuredTelemetry.class);
    var records =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    records.start();
    logger.addAppender(records);
    try (var f = new PlatformTestSupport()) {
      var traces = new PlatformTrace(f.dataSource);
      var context =
          new ExecutionContext(
              "safe-run",
              "node\nprivate-node-value",
              "invocation",
              "attempt",
              new Actor("alice", "project-a", Set.of()),
              Instant.now().plusSeconds(10),
              "trace");
      var span =
          traces.start(
              context, "TOOL", "https://credential:private-provider-value@example.invalid");
      span.outcome("failure\nprivate-error-value");
      span.close();
      String persisted = traces.list("safe-run", 0, 10).toString();
      String logged =
          records.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .collect(java.util.stream.Collectors.joining("\n"));
      for (String secret :
          new String[] {"private-node-value", "private-provider-value", "private-error-value"}) {
        assertFalse(persisted.contains(secret));
        assertFalse(logged.contains(secret));
      }
      assertTrue(logged.contains("REDACTED"));
    } finally {
      logger.detachAppender(records);
      records.stop();
    }
  }
}
