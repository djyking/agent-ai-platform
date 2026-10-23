package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.*;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SQL-backed dispatch; each process shares database slots and core fences with other workers. */
public final class PlatformWorker implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(PlatformWorker.class.getName());
  private final Deployment config;
  private final JdbcRunStore store;
  private final PlatformRepository repository;
  private final Harness harness;
  private final ExecutionGovernance governance;
  private int projectCursor;
  private long lastHeartbeat;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "platform-scheduler");
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService workers;
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closing = new AtomicBoolean();

  public PlatformWorker(
      Deployment config, JdbcRunStore store, PlatformRepository repository, Harness harness) {
    this(config, store, repository, harness, null);
  }

  public PlatformWorker(
      Deployment config,
      JdbcRunStore store,
      PlatformRepository repository,
      Harness harness,
      ExecutionGovernance governance) {
    this.governance = governance;
    this.config = config;
    this.store = store;
    this.repository = repository;
    this.harness = harness;
    workers =
        new ThreadPoolExecutor(
            config.concurrency(),
            config.concurrency(),
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, "platform-tick");
              t.setDaemon(false);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(this::pollSafely, 0, 200, TimeUnit.MILLISECONDS);
  }

  private void pollSafely() {
    try {
      poll();
    } catch (RuntimeException ex) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Worker poll failed; durable work retained ({0})",
          ex.getClass().getSimpleName());
    }
  }

  public void poll() {
    if (closing.get()) return;
    if (governance != null && System.nanoTime() - lastHeartbeat > TimeUnit.SECONDS.toNanos(1)) {
      governance.heartbeat(active.size(), false);
      for (Deployment.Project project : config.projects()) governance.settle(project.id());
      lastHeartbeat = System.nanoTime();
    }
    for (String id : store.expirable(100))
      try {
        repository.owned(id);
        harness.expire(id);
      } catch (ApiFailure | RunStore.Conflict ignored) {
      }
    List<String> projects = config.projects().stream().map(Deployment.Project::id).toList();
    if (projects.isEmpty()) return;
    int first =
        governance == null
            ? Math.floorMod(projectCursor++, projects.size())
            : governance.nextProject(projects.size());
    for (String id : repository.readyFair(projects, config.concurrency() * 4, first)) {
      if (closing.get() || active.size() >= config.concurrency()) break;
      PlatformRepository.Owned owned;
      try {
        owned = repository.owned(id);
      } catch (ApiFailure ignored) {
        continue;
      }
      if (!active.add(id)) continue;
      String attempt = UUID.randomUUID().toString();
      boolean acquired = false;
      try {
        acquired =
            repository.acquireSlots(
                owned.project(),
                attempt,
                config.leaseMillis() + 10000,
                config.concurrency(),
                config.project(owned.project()).concurrency());
        if (!acquired) {
          active.remove(id);
          continue;
        }
        workers.submit(
            () -> {
              try {
                harness.tick(id);
              } catch (RuntimeException ex) {
                LOG.log(
                    System.Logger.Level.WARNING,
                    "Tick interrupted; durable state retained ({0})",
                    ex.getClass().getSimpleName());
              } finally {
                try {
                  repository.releaseSlots(attempt);
                } finally {
                  active.remove(id);
                }
              }
            });
      } catch (RuntimeException ex) {
        active.remove(id);
        if (acquired) repository.releaseSlots(attempt);
        if (!(ex instanceof RejectedExecutionException)) throw ex;
      }
    }
  }

  public int active() {
    return active.size();
  }

  @Override
  public void close() {
    if (!closing.compareAndSet(false, true)) return;
    scheduler.shutdown();
    workers.shutdown();
    try {
      if (!workers.awaitTermination(config.leaseMillis() + 5000, TimeUnit.MILLISECONDS))
        workers.shutdownNow();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    } finally {
      if (governance != null)
        try {
          governance.heartbeat(active.size(), true);
        } catch (RuntimeException ignored) {
          /* Offline metadata retains its last heartbeat. */
        }
    }
  }
}
