package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Contracts.RunStatus;
import io.github.djyking.harness.core.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AcpControllerTest {
  @Test
  void allDisconnectCallbacksReleaseFullCapacityExactlyOnceWithoutCancellingTheRun()
      throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var controller = new AcpController(x.f.service, x.catalog);
      try {
        String id = x.f.id(x.f.create("read", "acp-disconnect-queued"));
        long revision = x.f.store.get(id).revision;
        var acp = new AcpService(x.f.service, x.catalog);
        var slots = slots(controller);
        var reads = new AtomicInteger();
        List<TestEmitter> clients = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          assertTrue(slots.tryAcquire());
          var client = new TestEmitter(false);
          clients.add(client);
          controller.stream(
              () -> {
                reads.incrementAndGet();
                return acp.get(x.f.principal(), id);
              },
              client);
        }
        for (var client : clients) assertTrue(client.firstSend.await(5, TimeUnit.SECONDS));
        assertFalse(slots.tryAcquire());
        for (int i = 0; i < clients.size(); i++) {
          var client = clients.get(i);
          switch (i % 3) {
            case 0 -> client.completion.run();
            case 1 -> client.timeout.run();
            default -> client.error.accept(new IOException("closed test client"));
          }
        }
        await(() -> slots.availablePermits() == 8);
        int finishedReads = reads.get();
        // Servlet containers can report both an error and completion for the same request.
        for (var client : clients) {
          client.completion.run();
          client.timeout.run();
          client.error.accept(new IOException("duplicate callback"));
        }
        assertEquals(8, slots.availablePermits());
        assertEquals(finishedReads, reads.get());
        assertEquals(revision, x.f.store.get(id).revision);
        assertEquals(RunStatus.QUEUED, x.f.store.get(id).status);
        assertEquals(1, x.f.count("harness_runs"));
        assertEquals(0, x.f.reads.get());
        assertEquals(0, x.f.modelCalls.get());
      } finally {
        controller.close();
      }
    }
  }

  @Test
  void unchangedRunHeartbeatDetectsBrokenSocketAndStopsObservation() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var controller = new AcpController(x.f.service, x.catalog);
      try {
        var slots = slots(controller);
        assertTrue(slots.tryAcquire());
        var reads = new AtomicInteger();
        var client = new TestEmitter(true);
        controller.stream(
            () -> {
              reads.incrementAndGet();
              return queued();
            },
            client);
        assertTrue(client.firstSend.await(5, TimeUnit.SECONDS));
        await(() -> slots.availablePermits() == 8);
        assertEquals(2, client.sends.get(), "unchanged state still sends a heartbeat");
        assertTrue(reads.get() >= 2);
        client.completion.run();
        assertEquals(8, slots.availablePermits());
      } finally {
        controller.close();
      }
    }
  }

  @Test
  void cancellationOfNotYetStartedTaskCannotReadOrReleaseTwice() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var controller = new AcpController(x.f.service, x.catalog);
      try {
        var slots = slots(controller);
        assertTrue(slots.tryAcquire());
        var reads = new AtomicInteger();
        var watch =
            controller
            .new StreamWatch(
                () -> {
                  reads.incrementAndGet();
                  return queued();
                },
                new TestEmitter(false));
        watch.cancel();
        watch.cancel();
        watch.future.run();
        assertTrue(watch.future.isCancelled());
        assertEquals(0, reads.get());
        assertEquals(8, slots.availablePermits());
      } finally {
        controller.close();
      }
    }
  }

  @Test
  void cancelledReadKeepsItsPermitUntilTheUnderlyingCallActuallyReturns() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var controller = new AcpController(x.f.service, x.catalog);
      var entered = new CountDownLatch(1);
      var interrupted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try {
        var slots = slots(controller);
        assertTrue(slots.tryAcquire());
        var client = new TestEmitter(false);
        controller.stream(
            () -> {
              entered.countDown();
              // Model a bounded HTTP read whose library does not immediately honor interrupt.
              boolean done = false;
              while (!done) {
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                  interrupted.countDown();
                }
              }
              return queued();
            },
            client);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        client.error.accept(new IOException("client disconnected"));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(7, slots.availablePermits(), "an in-flight read still occupies capacity");
        client.timeout.run();
        assertEquals(7, slots.availablePermits());
        release.countDown();
        await(() -> slots.availablePermits() == 8);
        assertEquals(0, client.sends.get(), "no event can be sent after cancellation");
      } finally {
        release.countDown();
        controller.close();
      }
    }
  }

  @Test
  void shutdownCancelsObserversAndRejectedSubmissionReturnsItsPermit() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var controller = new AcpController(x.f.service, x.catalog);
      try {
        var slots = slots(controller);
        assertTrue(slots.tryAcquire());
        var client = new TestEmitter(false);
        controller.stream(AcpControllerTest::queued, client);
        assertTrue(client.firstSend.await(5, TimeUnit.SECONDS));
        controller.close();
        await(() -> slots.availablePermits() == 8);
        assertTrue(slots.tryAcquire());
        error(
            503,
            "ACP_OBSERVER_UNAVAILABLE",
            () -> controller.stream(AcpControllerTest::queued, new TestEmitter(false)));
        assertEquals(8, slots.availablePermits());
      } finally {
        controller.close();
      }
    }
  }

  private static JsonNode queued() {
    return Json.object().put("run_id", "synthetic-observation-only").put("status", "created");
  }

  private static Semaphore slots(AcpController controller) throws Exception {
    var field = AcpController.class.getDeclaredField("watchers");
    field.setAccessible(true);
    return (Semaphore) field.get(controller);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "observer did not release its capacity");
  }

  private static final class TestEmitter extends SseEmitter {
    private final boolean brokenAfterFirst;
    final CountDownLatch firstSend = new CountDownLatch(1);
    final AtomicInteger sends = new AtomicInteger();
    Runnable completion = () -> {};
    Runnable timeout = () -> {};
    Consumer<Throwable> error = failure -> {};

    TestEmitter(boolean brokenAfterFirst) {
      this.brokenAfterFirst = brokenAfterFirst;
    }

    @Override
    public void onCompletion(Runnable callback) {
      completion = callback;
    }

    @Override
    public void onTimeout(Runnable callback) {
      timeout = callback;
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
      error = callback;
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      if (sends.incrementAndGet() > 1 && brokenAfterFirst)
        throw new IOException("closed test socket");
      firstSend.countDown();
    }

    @Override
    public void complete() {
      completion.run();
    }
  }
}
