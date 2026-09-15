package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded local concurrency. A timeout ends waiting; it cannot undo a remote side effect. */
public final class InvocationExecutor implements AutoCloseable {
  private final ThreadPoolExecutor executor;

  public InvocationExecutor(int parallelism, int queueCapacity) {
    if (parallelism < 1 || queueCapacity < 1)
      throw new IllegalArgumentException("Positive executor limits required");
    AtomicInteger names = new AtomicInteger();
    executor =
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
              Thread t = new Thread(r, "harness-call-" + names.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
  }

  public <T> T invoke(Callable<T> operation, ExecutionContext context, Clock clock) {
    long remaining = context.remaining(clock).toMillis();
    if (remaining < 1)
      throw new InvocationException(FailureKind.TRANSIENT, "CALL_NOT_DISPATCHED_DEADLINE");
    Future<T> pending;
    try {
      pending = executor.submit(operation);
    } catch (RejectedExecutionException full) {
      throw new InvocationException(FailureKind.TRANSIENT, "EXECUTOR_BUSY");
    }
    try {
      return pending.get(remaining, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      pending.cancel(true);
      throw new InvocationException(FailureKind.UNKNOWN, "CALL_DEADLINE_OUTCOME_UNKNOWN");
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new InvocationException(FailureKind.UNKNOWN, "CALL_INTERRUPTED_OUTCOME_UNKNOWN");
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof InvocationException known) throw known;
      throw new InvocationException(FailureKind.UNKNOWN, "CALL_RESULT_UNKNOWN", cause);
    }
  }

  public void close() {
    executor.shutdownNow();
  }
}
