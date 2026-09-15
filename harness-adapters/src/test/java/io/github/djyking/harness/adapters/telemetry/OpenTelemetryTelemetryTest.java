package io.github.djyking.harness.adapters.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenTelemetryTelemetryTest {
  @Test
  void resumedAttemptsUseSameTraceDistinctSpansAndSafeErrorOutcome() {
    var exporter = InMemorySpanExporter.create();
    try (var provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()) {
      String traceId = "1234567890abcdef1234567890abcdef";
      var actor = new Actor("owner-secret", "project", Set.of());
      var first =
          new ExecutionContext(
              "run-1",
              "node-1",
              "invocation-1",
              "attempt-1",
              actor,
              Instant.now().plusSeconds(10),
              traceId);
      var second =
          new ExecutionContext(
              "run-1",
              "node-1",
              "invocation-1",
              "attempt-2",
              actor,
              Instant.now().plusSeconds(10),
              traceId);
      try (var span =
          new OpenTelemetryTelemetry(provider.get("test")).start(first, "TOOL", "fixture")) {
        assertEquals(traceId, Span.current().getSpanContext().getTraceId());
        span.outcome("UNKNOWN");
      }
      // A fresh bridge models a new runtime worker/process with no in-memory span registry.
      try (var span =
          new OpenTelemetryTelemetry(provider.get("test")).start(second, "TOOL", "fixture")) {
        span.outcome("SUCCESS");
      }
      var spans = exporter.getFinishedSpanItems();
      assertEquals(2, spans.size());
      assertEquals(traceId, spans.get(0).getTraceId());
      assertEquals(traceId, spans.get(1).getTraceId());
      assertEquals(spans.get(0).getParentSpanId(), spans.get(1).getParentSpanId());
      assertNotEquals(spans.get(0).getSpanId(), spans.get(1).getSpanId());
      assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
      assertEquals(
          "UNKNOWN", spans.get(0).getAttributes().get(AttributeKey.stringKey("harness.outcome")));
      assertFalse(spans.toString().contains("owner-secret"));
      assertFalse(Span.current().getSpanContext().isValid());
    }
  }
}
