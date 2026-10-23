package io.github.djyking.harness.adapters.telemetry;

import io.github.djyking.harness.core.Contracts;
import io.github.djyking.harness.core.Json;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/** API-only bridge; the host owns SDK, sampling, processors and exporters. */
public final class OpenTelemetryTelemetry implements Contracts.Telemetry {
  private final Tracer tracer;

  public OpenTelemetryTelemetry(Tracer tracer) {
    this.tracer = Objects.requireNonNull(tracer, "tracer");
  }

  @Override
  public Contracts.Telemetry.Span start(
      Contracts.ExecutionContext execution, String operation, String target) {
    String traceId = execution.traceId();
    if (traceId == null
        || !traceId.matches("[a-f0-9]{32}")
        || traceId.equals("00000000000000000000000000000000")) {
      traceId = Json.hash(execution.runId()).substring(0, 32);
    }
    // A stable correlation parent survives restarts; it is not a fabricated exported run span.
    String parentId = Json.hash("harness-run-parent:" + execution.runId()).substring(0, 16);
    var correlation =
        SpanContext.createFromRemoteParent(
            traceId, parentId, TraceFlags.getSampled(), TraceState.getDefault());
    Context parent = Context.root().with(io.opentelemetry.api.trace.Span.wrap(correlation));
    var span =
        tracer
            .spanBuilder("harness." + operation.toLowerCase(java.util.Locale.ROOT))
            .setParent(parent)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("harness.run.id", execution.runId())
            .setAttribute("harness.node.id", Objects.requireNonNullElse(execution.nodeId(), ""))
            .setAttribute("harness.invocation.id", execution.invocationId())
            .setAttribute("harness.attempt.id", execution.attemptId())
            .setAttribute("harness.target", target)
            .startSpan();
    Scope scope = span.makeCurrent();
    Context invocationContext = Context.current();
    return new Contracts.Telemetry.Span() {
      private boolean closed;

      public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        return () -> {
          try (Scope ignored = invocationContext.makeCurrent()) {
            return task.call();
          }
        };
      }

      public void outcome(String code) {
        String safe = code != null && code.matches("[A-Z0-9_]{1,100}") ? code : "UNKNOWN";
        span.setAttribute("harness.outcome", safe);
        span.setStatus("SUCCESS".equals(safe) ? StatusCode.OK : StatusCode.ERROR);
      }

      public void close() {
        if (!closed) {
          closed = true;
          try {
            scope.close();
          } finally {
            span.end();
          }
        }
      }
    };
  }
}
