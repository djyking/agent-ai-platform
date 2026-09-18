package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.*;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Shared invariants for every RunStore adapter; these checks perform no external I/O. */
public final class StateGuards {
  private StateGuards() {}

  public static void validateInitial(RunState run) {
    validateVersion(run);
    if (run.id == null || run.id.isBlank() || run.id.length() > 128)
      throw new IllegalArgumentException("Run id must contain 1 to 128 characters");
    if (run.creationKey == null || run.creationKey.isBlank())
      throw new IllegalArgumentException("Creation key required");
    if (run.creationDigest == null
        || run.creationDigest.isBlank()
        || run.creationDigest.length() > 128)
      throw new IllegalArgumentException("Creation digest required");
    Objects.requireNonNull(run.actor, "Run actor required");
    Objects.requireNonNull(run.createdAt, "Creation time required");
    Objects.requireNonNull(run.deadline, "Deadline required");
    Objects.requireNonNull(run.definition, "Program definition required");
    Objects.requireNonNull(run.budget, "Budget required");
    if (run.status != RunStatus.QUEUED)
      throw new IllegalArgumentException("New runs must be queued");
    if (run.chargedTokens < 0 || run.modelCalls < 0 || run.toolCalls < 0 || run.steps < 0) {
      throw new IllegalArgumentException("Negative run usage");
    }
  }

  public static void validateVersion(RunState state) {
    Objects.requireNonNull(state);
    if (state.schemaVersion != 1 || !"1".equals(state.runtimeVersion)) {
      throw new RunStore.Conflict("Unsupported persisted run version; explicit migration required");
    }
  }

  public static String creationScope(RunState run) {
    return Json.hash(List.of(run.actor.project(), run.actor.subject(), run.creationKey));
  }

  public static void checkBaseline(RunState before, RunState after) {
    validateVersion(after);
    if (!Objects.equals(before.id, after.id)
        || !Objects.equals(before.creationKey, after.creationKey)
        || !Objects.equals(before.creationDigest, after.creationDigest)
        || !Json.hash(before.actor).equals(Json.hash(after.actor))
        || !Json.hash(before.definition).equals(Json.hash(after.definition))
        || !Objects.equals(before.budget, after.budget)
        || !Objects.equals(before.createdAt, after.createdAt)
        || !Objects.equals(before.deadline, after.deadline)
        || !Json.hash(before.tools).equals(Json.hash(after.tools))) {
      throw new RunStore.Conflict("Run identity and creation baseline are immutable");
    }
  }

  /** Only a worker result commit can release a model reservation. Host control updates cannot. */
  public static void checkUsage(RunState before, RunState after, boolean allowModelSettlement) {
    if (after.modelCalls < before.modelCalls
        || after.toolCalls < before.toolCalls
        || after.steps < before.steps) {
      throw new RunStore.Conflict("Run usage counters must not decrease");
    }
    if (after.chargedTokens >= before.chargedTokens) return;
    if (!allowModelSettlement || !validModelSettlement(before, after)) {
      throw new RunStore.Conflict(
          "Token charge may decrease only by exact settlement of a known model response");
    }
  }

  private static boolean validModelSettlement(RunState before, RunState after) {
    RunState.Pending pending = before.pending;
    if (pending == null
        || !"MODEL".equals(pending.kind)
        || pending.phase != InvocationPhase.IN_FLIGHT
        || pending.tokenReservation < 0
        || before.chargedTokens < pending.tokenReservation
        || after.pending != null
        || before.results.containsKey(pending.id)) return false;
    StepResult result = after.results.get(pending.id);
    if (result == null || !"MODEL".equals(result.kind()) || result.error()) return false;
    try {
      ModelResponse response = Json.convert(result.value(), ModelResponse.class);
      return response.usage().known()
          && after.chargedTokens
              == Math.addExact(
                  before.chargedTokens - pending.tokenReservation, response.usage().total());
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return false;
    }
  }

  public static boolean eligible(RunState state, long nowMillis) {
    return (state.status == RunStatus.QUEUED || state.status == RunStatus.RUNNING)
        && (state.leaseUntil == null || state.leaseUntil.toEpochMilli() <= nowMillis)
        && (state.nextAttemptAt == null || state.nextAttemptAt.toEpochMilli() <= nowMillis);
  }

  public static boolean expirable(RunState state, long nowMillis) {
    return java.util.Set.of(
                RunStatus.PAUSED,
                RunStatus.WAITING_APPROVAL,
                RunStatus.WAITING_INPUT,
                RunStatus.NEEDS_ATTENTION)
            .contains(state.status)
        && (state.pending == null || state.pending.phase == InvocationPhase.PREPARED)
        && (state.deadline.toEpochMilli() <= nowMillis
            || state.approval != null && state.approval.expiresAt().toEpochMilli() <= nowMillis);
  }

  /**
   * The store supplies authoritative time and persisted lease metadata, never caller lease time.
   */
  public static void checkLease(RunState current, RunState supplied, long nowMillis) {
    if (!Objects.equals(current.id, supplied.id)
        || current.revision != supplied.revision
        || current.fence != supplied.fence
        || current.leaseUntil == null
        || current.leaseUntil.toEpochMilli() <= nowMillis) {
      throw new RunStore.Conflict("Run lease expired or revision/fencing token changed");
    }
  }

  public static long leaseMillis(Duration lease) {
    long millis = Objects.requireNonNull(lease).toMillis();
    if (millis <= 0) throw new IllegalArgumentException("Lease must be at least one millisecond");
    return millis;
  }

  public static void validateEvent(RunEvent event) {
    Objects.requireNonNull(event, "Run event required");
    if (event.type() == null || event.type().isBlank() || event.at() == null) {
      throw new IllegalArgumentException("Event type and time required");
    }
  }

  public static void checkLimit(int limit) {
    if (limit < 1 || limit > 10_000)
      throw new IllegalArgumentException("Limit must be between 1 and 10000");
  }
}
