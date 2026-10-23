package io.github.djyking.harness.core;

import java.time.Instant;
import java.util.concurrent.Callable;

/** Host admission runs before the durable dispatch barrier; deferral never counts as an attempt. */
public interface InvocationGate {
  /** A local authorization/control/deadline check rejected before any remote invocation. */
  final class NotDispatched extends Contracts.InvocationException {
    public NotDispatched(Contracts.FailureKind kind, String safeCode) {
      super(kind, safeCode);
    }
  }

  Reservation reserve(RunState claimed, long timeoutMillis);

  default boolean permitsUnknownRetry() {
    return true;
  }

  interface Reservation {
    boolean granted();

    Instant retryAt();

    <T> T invoke(Callable<T> operation) throws Exception;

    /**
     * Releases only if no invocation started. A timeout must not release an executing operation.
     */
    void abandon();
  }

  static InvocationGate unlimited() {
    return (run, timeout) ->
        new Reservation() {
          public boolean granted() {
            return true;
          }

          public Instant retryAt() {
            return null;
          }

          public <T> T invoke(Callable<T> operation) throws Exception {
            return operation.call();
          }

          public void abandon() {}
        };
  }
}
