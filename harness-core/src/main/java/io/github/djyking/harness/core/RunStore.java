package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.*;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Atomic state + event store. All returns are detached copies. No tool/model I/O in transactions.
 */
public interface RunStore extends AutoCloseable {
  /** Uniqueness scope: actor.project + actor.subject + creationKey. Digest mismatch is conflict. */
  RunState create(RunState initial);

  RunState get(String id);

  /** Null if busy/not ready; claim increments fence and revision using authoritative store time. */
  RunState claim(String id, Duration lease);

  List<String> ready(int limit);

  /** Idle waits whose run or approval deadline expired; uncertain effects must be excluded. */
  default List<String> expirable(int limit) {
    StateGuards.checkLimit(limit);
    return List.of();
  }

  void assertLease(RunState claimed);

  /**
   * Check revision + fence + live lease, atomically persist state/event and return new revision.
   * releaseLease=false is the durable IN_FLIGHT barrier before a remote effect.
   */
  RunState save(RunState claimed, RunEvent event, boolean releaseLease);

  /** CAS host control update; mutation runs on a detached current state within the transaction. */
  RunState update(
      String id, long expectedRevision, UnaryOperator<RunState> mutation, RunEvent event);

  List<StoredEvent> events(String id, long afterSequence, int limit);

  default void close() {}

  class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }

  /** A caller's compare-and-set precondition failed, distinct from an invalid lifecycle state. */
  class RevisionConflict extends Conflict {
    public RevisionConflict() {
      super("Run revision changed");
    }
  }

  class NotFound extends RuntimeException {
    public NotFound(String id) {
      super("Run not found: " + id);
    }
  }
}
