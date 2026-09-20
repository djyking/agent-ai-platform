package io.github.djyking.harness.platform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Process-local counters with a fixed, bounded label vocabulary. Restart resets are expected. */
public final class PlatformHttpMetrics {
  enum Route {
    runs,
    catalog,
    studio,
    knowledge,
    capabilities,
    operations,
    auth,
    other_api
  }

  enum Method {
    read,
    write,
    other
  }

  private record Labels(Route route, Method method, int statusClass) {}

  private static final double[] BOUNDS = {
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30
  };

  private static final class Sample {
    final LongAdder count = new LongAdder();
    final DoubleAdder sum = new DoubleAdder();
    final LongAdder[] buckets =
        Arrays.stream(BOUNDS).mapToObj(v -> new LongAdder()).toArray(LongAdder[]::new);
  }

  private final Map<Labels, Sample> samples = new ConcurrentHashMap<>();

  static Route route(String path) {
    if (path == null) return null;
    if (Set.of(
            "/console/login", "/console/session", "/console/auth/config", "/console/auth/captcha")
        .contains(path)) return Route.auth;
    String prefix =
        path.startsWith("/console/api/projects/")
            ? "/console/api/projects/"
            : path.startsWith("/v1/projects/") ? "/v1/projects/" : null;
    if (prefix == null)
      return null; // Static content, health and scrape requests are not API SLO traffic.
    String remainder = path.substring(prefix.length());
    int slash = remainder.indexOf('/');
    if (slash < 0) return Route.other_api;
    String domain = remainder.substring(slash + 1).split("/", 2)[0];
    return switch (domain) {
      case "runs" -> Route.runs;
      case "catalog" -> Route.catalog;
      case "studio" -> Route.studio;
      case "knowledge" -> Route.knowledge;
      case "capabilities" -> Route.capabilities;
      case "operations" -> Route.operations;
      default -> Route.other_api;
    };
  }

  void observe(Route route, String method, int status, long durationNanos) {
    if (route == null) return;
    Method category =
        switch (Objects.toString(method, "")) {
          case "GET", "HEAD" -> Method.read;
          case "POST", "PUT", "PATCH", "DELETE" -> Method.write;
          default -> Method.other;
        };
    int statusClass = status >= 200 && status < 600 ? status / 100 : 0;
    Sample sample =
        samples.computeIfAbsent(new Labels(route, category, statusClass), unused -> new Sample());
    double seconds = Math.max(0, durationNanos) / 1_000_000_000.0;
    synchronized (sample) {
      sample.count.increment();
      sample.sum.add(seconds);
      for (int i = 0; i < BOUNDS.length; i++)
        if (seconds <= BOUNDS[i]) sample.buckets[i].increment();
    }
  }

  public String scrape() {
    StringBuilder out =
        new StringBuilder(
            "# HELP harness_http_requests_total Completed synchronous API HTTP requests in this"
                + " process; reset on restart.\n"
                + "# TYPE harness_http_requests_total counter\n"
                + "# HELP harness_http_request_duration_seconds Synchronous API request duration in"
                + " this process; model generation runs asynchronously.\n"
                + "# TYPE harness_http_request_duration_seconds histogram\n");
    samples.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().toString()))
        .forEach(
            entry -> {
              Labels key = entry.getKey();
              Sample sample = entry.getValue();
              synchronized (sample) {
                String labels =
                    "route=\""
                        + key.route
                        + "\",method=\""
                        + key.method
                        + "\",status_class=\""
                        + (key.statusClass == 0 ? "other" : key.statusClass + "xx")
                        + "\"";
                out.append("harness_http_requests_total{")
                    .append(labels)
                    .append("} ")
                    .append(sample.count.sum())
                    .append('\n');
                for (int i = 0; i < BOUNDS.length; i++)
                  out.append("harness_http_request_duration_seconds_bucket{")
                      .append(labels)
                      .append(",le=\"")
                      .append(BOUNDS[i])
                      .append("\"} ")
                      .append(sample.buckets[i].sum())
                      .append('\n');
                out.append("harness_http_request_duration_seconds_bucket{")
                    .append(labels)
                    .append(",le=\"+Inf\"} ")
                    .append(sample.count.sum())
                    .append('\n');
                out.append("harness_http_request_duration_seconds_count{")
                    .append(labels)
                    .append("} ")
                    .append(sample.count.sum())
                    .append('\n');
                out.append("harness_http_request_duration_seconds_sum{")
                    .append(labels)
                    .append("} ")
                    .append(sample.sum.sum())
                    .append('\n');
              }
            });
    return out.toString();
  }
}
