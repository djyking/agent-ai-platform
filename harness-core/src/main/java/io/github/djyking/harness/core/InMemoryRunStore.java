package io.github.djyking.harness.core;

import static io.github.djyking.harness.core.StateGuards.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Thread-safe development store with the same state invariants as SQL; process restarts lose it.
 */
public final class InMemoryRunStore implements RunStore {
  private final Clock clock;
  private final Map<String, RunState> runs = new LinkedHashMap<>();
  private final Map<String, String> creationScopes = new HashMap<>();
  private final Map<String, List<StoredEvent>> events = new HashMap<>();

  public InMemoryRunStore() {
    this(Clock.systemUTC());
  }

  public InMemoryRunStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public synchronized RunState create(RunState initial) {
    RunState proposed = Objects.requireNonNull(initial).copy();
    validateInitial(proposed);
    String scope = creationScope(proposed);
    String previousId = creationScopes.get(scope);
    if (previousId != null) {
      RunState previous = get(previousId);
      if (!previous.creationDigest.equals(proposed.creationDigest))
        throw new Conflict("Creation key was used for a different request");
      return previous;
    }
    if (runs.containsKey(proposed.id)) throw new Conflict("Run id already exists");
    proposed.revision = 1;
    proposed.fence = 0;
    proposed.leaseUntil = null;
    RunState created = publish(proposed, new RunEvent("RUN_CREATED", null, null, now(), Map.of()));
    creationScopes.put(scope, proposed.id);
    return created;
  }

  @Override
  public synchronized RunState get(String id) {
    RunState value = runs.get(Objects.requireNonNull(id));
    if (value == null) throw new NotFound(id);
    validateVersion(value);
    return value.copy();
  }

  @Override
  public synchronized RunState claim(String id, Duration lease) {
    long ttl = leaseMillis(lease);
    RunState claimed = get(id);
    long now = clock.millis();
    if (!eligible(claimed, now)) return null;
    claimed.status = RunStatus.RUNNING;
    claimed.fence = Math.addExact(claimed.fence, 1);
    claimed.revision = Math.addExact(claimed.revision, 1);
    claimed.leaseUntil = Instant.ofEpochMilli(Math.addExact(now, ttl));
    return publish(
        claimed,
        new RunEvent(
            "LEASE_CLAIMED",
            null,
            null,
            Instant.ofEpochMilli(now),
            Map.of("fence", Long.toString(claimed.fence))));
  }

  @Override
  public synchronized List<String> ready(int limit) {
    checkLimit(limit);
    long now = clock.millis();
    return runs.values().stream()
        .filter(run -> eligible(run, now))
        .sorted(
            Comparator.comparingLong((RunState run) -> run.createdAt.toEpochMilli())
                .thenComparing(run -> run.id))
        .limit(limit)
        .map(run -> run.id)
        .toList();
  }

  @Override
  public synchronized void assertLease(RunState supplied) {
    RunState detached = Objects.requireNonNull(supplied).copy();
    checkLease(get(detached.id), detached, clock.millis());
  }

  @Override
  public synchronized List<String> expirable(int limit) {
    checkLimit(limit);
    long now = clock.millis();
    return runs.values().stream()
        .filter(run -> StateGuards.expirable(run, now))
        .sorted(Comparator.comparing((RunState run) -> run.deadline).thenComparing(run -> run.id))
        .limit(limit)
        .map(run -> run.id)
        .toList();
  }

  @Override
  public synchronized RunState save(RunState claimed, RunEvent event, boolean releaseLease) {
    RunState next = Objects.requireNonNull(claimed).copy();
    validateEvent(event);
    RunState current = get(next.id);
    checkLease(current, next, clock.millis());
    checkBaseline(current, next);
    checkUsage(current, next, true);
    next.revision = Math.addExact(current.revision, 1);
    next.fence = current.fence;
    next.leaseUntil = releaseLease ? null : current.leaseUntil;
    return publish(next, event);
  }

  @Override
  public synchronized RunState update(
      String id, long expectedRevision, UnaryOperator<RunState> mutation, RunEvent event) {
    Objects.requireNonNull(mutation);
    validateEvent(event);
    RunState current = get(id);
    if (current.revision != expectedRevision) throw new RevisionConflict();
    RunState next =
        Objects.requireNonNull(mutation.apply(current.copy()), "Mutation returned null").copy();
    checkBaseline(current, next);
    checkUsage(current, next, false);
    next.revision = Math.addExact(current.revision, 1);
    next.fence = current.fence;
    next.leaseUntil = current.leaseUntil;
    return publish(next, event);
  }

  /** All validation and copying completes before publishing either side of the state/event pair. */
  private RunState publish(RunState next, RunEvent event) {
    validateVersion(next);
    validateEvent(event);
    Objects.requireNonNull(next.status, "Run status required");
    RunState stored = next.copy();
    RunState returned = stored.copy();
    RunEvent storedEvent = Json.copy(event, RunEvent.class);
    List<StoredEvent> previous = events.getOrDefault(next.id, List.of());
    List<StoredEvent> appended = new ArrayList<>(previous);
    appended.add(new StoredEvent(Math.addExact(previous.size(), 1), storedEvent));
    List<StoredEvent> publishedEvents = List.copyOf(appended);
    runs.put(stored.id, stored);
    events.put(stored.id, publishedEvents);
    return returned;
  }

  @Override
  public synchronized List<StoredEvent> events(String id, long afterSequence, int limit) {
    checkLimit(limit);
    if (afterSequence < 0) throw new IllegalArgumentException("Negative event cursor");
    get(id);
    return events.get(id).stream()
        .filter(event -> event.sequence() > afterSequence)
        .limit(limit)
        .map(event -> Json.copy(event, StoredEvent.class))
        .toList();
  }

  private Instant now() {
    return Instant.ofEpochMilli(clock.millis());
  }
}
