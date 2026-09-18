package io.github.djyking.harness.platform;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.RunStatus;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlatformWorkerTest {
  @Test
  void twoWorkerHostsCompleteReadAndModelRunsOnceUsingSharedSqlOwnership() {
    try (var f = new PlatformTestSupport()) {
      JdbcRunStore secondStore = new JdbcRunStore(f.dataSource);
      PlatformRepository secondRepository = new PlatformRepository(secondStore);
      try (var secondHarness = f.newHarness(secondStore);
          var one = new PlatformWorker(f.config, f.store, f.repository, f.harness);
          var two = new PlatformWorker(f.config, secondStore, secondRepository, secondHarness)) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(f.id(f.create("read", "worker-read-" + i)));
        for (int i = 0; i < 2; i++) ids.add(f.id(f.create("model", "worker-model-" + i)));
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(20))
            .until(
                () -> {
                  one.poll();
                  two.poll();
                  return ids.stream().allMatch(id -> f.store.get(id).status == RunStatus.COMPLETED);
                });
        assertEquals(8, f.reads.get());
        assertEquals(2, f.modelCalls.get());
        await().atMost(Duration.ofSeconds(3)).until(() -> one.active() == 0 && two.active() == 0);
        long occupied =
            f.repository.transaction(
                c -> {
                  try (var statement = c.createStatement();
                      var rows =
                          statement.executeQuery(
                              "SELECT COUNT(*) FROM platform_slots WHERE owner_id IS NOT NULL")) {
                    assertTrue(rows.next());
                    return rows.getLong(1);
                  }
                });
        assertEquals(0, occupied);
      }
    }
  }

  @Test
  void approvalWaitSurvivesWorkerReplacementAndExpiredWaitsAreSweptWithoutDispatch() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("write", "worker-approval1"));
      try (var original = new PlatformWorker(f.config, f.store, f.repository, f.harness)) {
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(20))
            .until(
                () -> {
                  original.poll();
                  return f.store.get(id).status == RunStatus.WAITING_APPROVAL;
                });
      }
      var waiting = f.store.get(id);
      JdbcRunStore replacementStore = new JdbcRunStore(f.dataSource);
      try (var replacementHarness = f.newHarness(replacementStore);
          var replacement =
              new PlatformWorker(
                  f.config,
                  replacementStore,
                  new PlatformRepository(replacementStore),
                  replacementHarness)) {
        f.approve(id, "worker-approval2");
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(20))
            .until(
                () -> {
                  replacement.poll();
                  return f.store.get(id).status == RunStatus.COMPLETED;
                });
        assertEquals(waiting.deadline, f.store.get(id).deadline);
        assertEquals(waiting.budget, f.store.get(id).budget);
        assertEquals(1, f.writes.get());
        String expired = f.id(f.create("human", "worker-expire001"));
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(20))
            .until(
                () -> {
                  replacement.poll();
                  return f.store.get(expired).status == RunStatus.WAITING_INPUT;
                });
        // Make the persisted approval deadline due without wall-clock sleeps or bypassing the
        // worker's sweep path. This represents an already elapsed real database deadline.
        var state = f.store.get(expired);
        f.store.update(
            expired,
            state.revision,
            run -> {
              run.approval =
                  new io.github.djyking.harness.core.Contracts.Approval(
                      run.approval.invocationId(),
                      run.approval.digest(),
                      java.time.Instant.EPOCH,
                      "PENDING",
                      null,
                      null);
              return run;
            },
            new io.github.djyking.harness.core.Contracts.RunEvent(
                "TEST_CLOCK_ELAPSED", "", "", f.clock.instant(), Map.of()));
        replacement.poll();
        assertEquals(RunStatus.EXPIRED, f.store.get(expired).status);
        assertEquals(1, f.writes.get());
      }
    }
  }
}
