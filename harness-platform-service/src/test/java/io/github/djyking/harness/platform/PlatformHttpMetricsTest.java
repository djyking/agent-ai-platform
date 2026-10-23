package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import jakarta.servlet.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

final class PlatformHttpMetricsTest {
  @org.junit.jupiter.api.Test
  void newProtocolAndAdmissionRoutesUseBoundedLabels() {
    var metrics = new PlatformHttpMetrics();
    for (int i = 0; i < 100; i++) {
      metrics.observe(
          PlatformHttpMetrics.route("/acp/projects/private-" + i + "/runs/run-" + i),
          "POST",
          202,
          1000000);
      metrics.observe(
          PlatformHttpMetrics.route(
              "/console/api/projects/private-" + i + "/onboarding/requests/request-" + i),
          "GET",
          200,
          1000000);
    }
    String scraped = metrics.scrape();
    org.junit.jupiter.api.Assertions.assertTrue(scraped.contains("route=\"acp\""));
    org.junit.jupiter.api.Assertions.assertTrue(scraped.contains("route=\"onboarding\""));
    org.junit.jupiter.api.Assertions.assertFalse(scraped.contains("private-"));
    org.junit.jupiter.api.Assertions.assertEquals(
        2, scraped.lines().filter(line -> line.startsWith("harness_http_requests_total{")).count());
  }

  @RestController
  static class Handler {
    @GetMapping("/v1/projects/{project}/runs/{run}")
    Map<String, Boolean> get() {
      return Map.of("ok", true);
    }

    @PostMapping("/console/api/projects/{project}/studio/applications")
    Map<String, Boolean> post() {
      throw new ApiFailure(422, "SYNTHETIC_REJECTION");
    }
  }

  @Test
  void realHttpResponsesHaveBoundedRouteAndStatusLabelsWithoutPayload() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = new OperationsService(f.config, f.repository);
      var filter = new PlatformHttpMetricsFilter(service);
      var mvc =
          MockMvcBuilders.standaloneSetup(new Handler())
              .setControllerAdvice(new ApiErrors())
              .addFilters(filter)
              .build();
      assertEquals(
          200,
          mvc.perform(
                  get("/v1/projects/private-project/runs/private-run-id")
                      .header("Authorization", "Bearer private-token"))
              .andReturn()
              .getResponse()
              .getStatus());
      assertEquals(
          422,
          mvc.perform(
                  post("/console/api/projects/private-project/studio/applications")
                      .contentType("application/json")
                      .content("{\"input\":\"private-prompt\"}"))
              .andReturn()
              .getResponse()
              .getStatus());
      String metrics = service.httpMetrics().scrape();
      assertTrue(
          metrics.contains(
              "harness_http_requests_total{route=\"runs\",method=\"read\",status_class=\"2xx\"}"
                  + " 1"));
      assertTrue(
          metrics.contains(
              "harness_http_requests_total{route=\"studio\",method=\"write\",status_class=\"4xx\"}"
                  + " 1"));
      for (String sensitive :
          List.of("private-project", "private-run-id", "private-token", "private-prompt", "/v1/"))
        assertFalse(metrics.contains(sensitive));
    }
  }

  @Test
  void bucketsAreCumulativeAndArbitraryRoutesCannotCreateNewLabels() {
    var metrics = new PlatformHttpMetrics();
    for (int i = 0; i < 1000; i++)
      metrics.observe(
          PlatformHttpMetrics.route("/v1/projects/secret/unique-" + i),
          "SECRET_METHOD_" + i,
          403,
          450_000_000);
    metrics.observe(PlatformHttpMetrics.route("/internal/metrics"), "GET", 200, 10);
    metrics.observe(PlatformHttpMetrics.route("/console/assets/secret.js"), "GET", 200, 10);
    metrics.observe(PlatformHttpMetrics.route("/health/ready"), "GET", 200, 10);
    String text = metrics.scrape();
    assertEquals(
        1, text.lines().filter(line -> line.startsWith("harness_http_requests_total{")).count());
    assertTrue(
        text.contains(
            "harness_http_request_duration_seconds_bucket{route=\"other_api\",method=\"other\",status_class=\"4xx\",le=\"0.25\"}"
                + " 0"));
    assertTrue(
        text.contains(
            "harness_http_request_duration_seconds_bucket{route=\"other_api\",method=\"other\",status_class=\"4xx\",le=\"0.5\"}"
                + " 1000"));
    assertTrue(text.contains("le=\"+Inf\"} 1000"));
    assertFalse(text.contains("secret"));
    assertFalse(text.contains("SECRET_METHOD"));
    assertFalse(new PlatformHttpMetrics().scrape().contains("harness_http_requests_total{"));
  }

  @Test
  void internalForwardIsOneObservationAndUncaughtFailureIsServerError() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = new OperationsService(f.config, f.repository);
      var filter = new PlatformHttpMetricsFilter(service);
      var request = new MockHttpServletRequest("GET", "/console/api/projects/private/operations");
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request,
          response,
          (req, res) ->
              filter.doFilter(
                  req, res, (again, result) -> ((MockHttpServletResponse) result).setStatus(204)));
      assertTrue(
          service
              .httpMetrics()
              .scrape()
              .contains(
                  "harness_http_requests_total{route=\"operations\",method=\"read\",status_class=\"2xx\"}"
                      + " 1"));
      assertThrows(
          ServletException.class,
          () ->
              filter.doFilter(
                  new MockHttpServletRequest("GET", "/v1/projects/private/runs/private-id"),
                  new MockHttpServletResponse(),
                  (req, res) -> {
                    throw new ServletException("private-failure-body");
                  }));
      String text = service.httpMetrics().scrape();
      assertTrue(text.contains("route=\"runs\",method=\"read\",status_class=\"5xx\"} 1"));
      assertFalse(text.contains("private"));
    }
  }

  @Test
  void dependencyDurationsUseRecentProjectScopedTraceAndRequireMigratedIndex() {
    try (var f = new PlatformTestSupport()) {
      new PlatformTrace(f.dataSource);
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      String one = f.id(f.create("read", "latency-project-a"));
      String two =
          f.id(f.create(f.principal("app-a", "project-b", "alice"), "read", "latency-project-b"));
      f.repository.transaction(
          c -> {
            long now = now(c);
            for (Object[] row :
                List.of(
                    new Object[] {one, "MODEL", 300, now},
                    new Object[] {one, "MODEL", 700, now},
                    new Object[] {one, "TOOL", 2000, now},
                    new Object[] {two, "MODEL", 9000, now},
                    new Object[] {one, "MODEL", 99000, now - 1_000_000}))
              execute(
                  c,
                  "INSERT INTO"
                      + " harness_platform_trace(run_id,operation_name,target_name,started_at,duration_ms)"
                      + " VALUES(?,?,?,?,?)",
                  row[0],
                  row[1],
                  "private-target-label",
                  row[3],
                  row[2]);
            return null;
          });
      String text = service.metrics();
      assertTrue(
          text.contains(
              "harness_dependency_calls_window{project=\"project-a\",operation=\"MODEL\"} 2"));
      assertTrue(
          text.contains(
              "harness_dependency_duration_seconds_window_mean{project=\"project-a\",operation=\"MODEL\"}"
                  + " 0.5"));
      assertTrue(
          text.contains(
              "harness_dependency_duration_seconds_window_max{project=\"project-a\",operation=\"MODEL\"}"
                  + " 0.7"));
      assertTrue(
          text.contains(
              "harness_dependency_duration_seconds_window_sum{project=\"project-b\",operation=\"MODEL\"}"
                  + " 9.0"));
      assertFalse(text.contains(one));
      assertFalse(text.contains("private-target-label"));
      f.repository.transaction(
          c -> {
            execute(c, "DROP INDEX ix_harness_trace_window");
            return null;
          });
      assertThrows(IllegalStateException.class, () -> new PlatformTrace(f.dataSource, false));
      new PlatformTrace(f.dataSource, true);
      assertDoesNotThrow(() -> new PlatformTrace(f.dataSource, false));
    }
  }
}
