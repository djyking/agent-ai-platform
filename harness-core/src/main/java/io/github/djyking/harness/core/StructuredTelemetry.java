package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Payload-free logs and low-cardinality counters; hosts may additionally supply an OTel adapter.
 */
public final class StructuredTelemetry implements Telemetry {
  private static final Logger LOG = LoggerFactory.getLogger(StructuredTelemetry.class);
  private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

  public long count(String operation, String outcome) {
    return counts.getOrDefault(operation + ":" + outcome, new LongAdder()).sum();
  }

  public Span start(ExecutionContext c, String operation, String target) {
    long started = System.nanoTime();
    return new Span() {
      private String result = "UNKNOWN";
      private boolean closed;

      public void outcome(String code) {
        result = code;
      }

      public void close() {
        if (closed) return;
        closed = true;
        counts.computeIfAbsent(operation + ":" + result, k -> new LongAdder()).increment();
        LOG.info(
            "harness operation={} target={} runId={} step={} invocation={} attempt={} traceId={}"
                + " outcome={} durationMs={}",
            operation,
            target,
            c.runId(),
            c.nodeId(),
            c.invocationId(),
            c.attemptId(),
            c.traceId(),
            result,
            (System.nanoTime() - started) / 1_000_000);
      }
    };
  }
}
