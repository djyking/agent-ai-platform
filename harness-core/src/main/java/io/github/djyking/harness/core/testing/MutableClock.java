package io.github.djyking.harness.core.testing;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic domain/lease clock for in-memory scenarios. SQL leases use database time. */
public final class MutableClock extends Clock {
  private final AtomicReference<Instant> now;

  public MutableClock(Instant initial) {
    now = new AtomicReference<>(initial);
  }

  public void advance(Duration duration) {
    if (duration.isNegative()) throw new IllegalArgumentException("Clock cannot go backwards");
    now.updateAndGet(i -> i.plus(duration));
  }

  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  public Clock withZone(ZoneId zone) {
    if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("Test clock uses UTC");
    return this;
  }

  public Instant instant() {
    return now.get();
  }
}
