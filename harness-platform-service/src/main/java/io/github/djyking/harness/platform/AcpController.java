package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated ACP compatibility endpoint. Protocol reads use the same current authorization. */
@RestController
@RequestMapping("/acp/projects/{project}")
public final class AcpController {
  private final PlatformService platform;
  private final AcpService acp;
  private final Semaphore watchers = new Semaphore(8);
  private final Set<StreamWatch> activeStreams = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor streams =
      new ThreadPoolExecutor(
          8,
          8,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8),
          r -> {
            var thread = new Thread(r, "acp-observer");
            thread.setDaemon(true);
            return thread;
          });

  public AcpController(PlatformService platform, CatalogService catalog) {
    this.platform = platform;
    this.acp = new AcpService(platform, catalog);
  }

  @PreDestroy
  public void close() {
    activeStreams.forEach(StreamWatch::cancel);
    streams.shutdownNow();
  }

  @ModelAttribute
  public void noStore(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
  }

  private static String header(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) throw ApiFailure.invalid();
    return values.isEmpty() ? null : values.get(0);
  }

  private static String credential(HttpServletRequest request) {
    String auth = header(request, "Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) throw new ApiFailure(401, "UNAUTHENTICATED");
    return auth.substring(7);
  }

  private IdentityProvider.Principal principal(String project, HttpServletRequest request) {
    return platform.authenticate(
        project, credential(request), header(request, "X-Harness-User-Token"));
  }

  private static JsonNode body(HttpServletRequest request) {
    String content = request.getContentType();
    if (content == null
        || !content.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    if (request.getContentLengthLong() > 65536) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    try {
      return ApiJson.read(request.getInputStream());
    } catch (IOException invalid) {
      throw ApiFailure.invalid();
    }
  }

  @GetMapping("/ping")
  public JsonNode ping(@PathVariable("project") String project, HttpServletRequest request) {
    principal(project, request);
    return Json.object();
  }

  @GetMapping("/agents")
  public JsonNode agents(
      @PathVariable("project") String project,
      @RequestParam(name = "limit", defaultValue = "10") int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset,
      HttpServletRequest request) {
    return acp.agents(principal(project, request), limit, offset);
  }

  @GetMapping("/agents/{name}")
  public JsonNode agent(
      @PathVariable("project") String project,
      @PathVariable("name") String name,
      HttpServletRequest request) {
    return acp.agent(principal(project, request), name);
  }

  @PostMapping("/runs")
  public Object create(@PathVariable("project") String project, HttpServletRequest request) {
    var p = principal(project, request);
    var input = body(request);
    String mode = AcpService.mode(input);
    if (!mode.equals("async") && !watchers.tryAcquire())
      throw new ApiFailure(429, "ACP_OBSERVER_CAPACITY");
    boolean observer = !mode.equals("async");
    try {
      String id =
          acp.create(
              p,
              credential(request),
              header(request, "X-Harness-User-Token"),
              header(request, "Idempotency-Key"),
              input);
      observer = false;
      return respond(project, id, mode, request);
    } finally {
      if (observer) watchers.release();
    }
  }

  @GetMapping("/runs/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(acp.get(principal(project, request), id));
  }

  @PostMapping("/runs/{id}/cancel")
  public ResponseEntity<JsonNode> cancel(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    var p = principal(project, request);
    acp.cancel(p, id, header(request, "Idempotency-Key"), header(request, "If-Match"));
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(acp.get(p, id));
  }

  @PostMapping("/runs/{id}")
  public Object resume(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    var p = principal(project, request);
    var input = body(request);
    String mode = AcpService.mode(input);
    if (!mode.equals("async") && !watchers.tryAcquire())
      throw new ApiFailure(429, "ACP_OBSERVER_CAPACITY");
    boolean observer = !mode.equals("async");
    try {
      acp.resume(p, id, header(request, "Idempotency-Key"), input);
      observer = false;
      return respond(project, id, mode, request);
    } finally {
      if (observer) watchers.release();
    }
  }

  @GetMapping("/runs/{id}/events")
  public JsonNode events(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @RequestParam(name = "cursor", required = false) String cursor,
      HttpServletRequest request) {
    return acp.events(principal(project, request), id, cursor);
  }

  private Object respond(String project, String id, String mode, HttpServletRequest request) {
    // Copy headers before leaving the servlet thread; never retain a pooled servlet request.
    String credential = credential(request), token = header(request, "X-Harness-User-Token");
    Supplier<JsonNode> read = () -> acp.get(platform.authenticate(project, credential, token), id);
    if (mode.equals("async"))
      return ResponseEntity.accepted()
          .cacheControl(CacheControl.noStore())
          .header("Location", "/acp/projects/" + project + "/runs/" + id)
          .body(read.get());
    if (mode.equals("stream")) {
      return stream(read, new SseEmitter(65000L));
    }
    try {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      JsonNode run = read.get();
      while (!AcpService.settled(run) && System.nanoTime() < until) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ApiFailure(503, "ACP_OBSERVER_INTERRUPTED");
        }
        run = read.get();
      }
      return ResponseEntity.status(AcpService.settled(run) ? 200 : 202)
          .cacheControl(CacheControl.noStore())
          .header("Location", "/acp/projects/" + project + "/runs/" + id)
          .body(run);
    } finally {
      watchers.release();
    }
  }

  /** Caller already holds one observer permit. Disconnect cancels observation, never the Run. */
  SseEmitter stream(Supplier<JsonNode> read, SseEmitter emitter) {
    var watch = new StreamWatch(read, emitter);
    activeStreams.add(watch);
    emitter.onCompletion(watch::cancel);
    emitter.onTimeout(watch::cancel);
    emitter.onError(failure -> watch.cancel());
    try {
      streams.execute(watch.future);
    } catch (RejectedExecutionException shuttingDown) {
      watch.cancel();
      throw new ApiFailure(503, "ACP_OBSERVER_UNAVAILABLE");
    }
    return emitter;
  }

  final class StreamWatch {
    private final Supplier<JsonNode> read;
    private final SseEmitter emitter;
    // 0 queued, 1 executing, 2 finished/cancelled before execution.
    private final AtomicInteger state = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    final FutureTask<Void> future;

    StreamWatch(Supplier<JsonNode> read, SseEmitter emitter) {
      this.read = read;
      this.emitter = emitter;
      future =
          new FutureTask<>(
              () -> {
                observe();
                return null;
              });
    }

    void cancel() {
      cancelled.set(true);
      if (state.compareAndSet(0, 2)) release();
      future.cancel(true);
      streams.remove(future);
      // An executing task releases only after its interrupted read has actually returned.
    }

    private void release() {
      if (released.compareAndSet(false, true)) {
        activeStreams.remove(this);
        watchers.release();
      }
    }

    private void observe() {
      if (!state.compareAndSet(0, 1)) return;
      try {
        String previous = "";
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long lastSend = 0;
        while (!cancelled.get() && System.nanoTime() < until) {
          JsonNode run = read.get();
          if (cancelled.get()) break;
          String serialized = Json.write(AcpService.event(run));
          if (!serialized.equals(previous)) {
            emitter.send(SseEmitter.event().data(serialized, MediaType.APPLICATION_JSON));
            previous = serialized;
            lastSend = System.nanoTime();
          } else if (System.nanoTime() - lastSend >= TimeUnit.SECONDS.toNanos(1)) {
            // Unchanged Runs still need writes so that a closed client is detected promptly.
            emitter.send(SseEmitter.event().comment("keepalive"));
            lastSend = System.nanoTime();
          }
          if (AcpService.settled(run)) break;
          Thread.sleep(250);
        }
        if (!cancelled.get()) emitter.complete();
      } catch (ApiFailure denied) {
        if (!cancelled.get()) {
          try {
            emitter.send(
                SseEmitter.event()
                    .data(
                        Json.write(
                            Json.object()
                                .put("type", "error")
                                .set("error", AcpService.error(denied.status, denied.code))),
                        MediaType.APPLICATION_JSON));
          } catch (IOException ignored) {
          }
          emitter.complete();
        }
      } catch (Exception disconnected) {
        if (!cancelled.get()) emitter.complete();
      } finally {
        state.set(2);
        release();
      }
    }
  }

  @ExceptionHandler(ApiFailure.class)
  public ResponseEntity<JsonNode> failure(ApiFailure failure) {
    return ResponseEntity.status(failure.status)
        .cacheControl(CacheControl.noStore())
        .body(AcpService.error(failure.status, failure.code));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<JsonNode> unexpected(Exception failure, HttpServletRequest request) {
    var mapped = new ApiErrors().error(failure, request);
    return ResponseEntity.status(mapped.getStatusCode())
        .cacheControl(CacheControl.noStore())
        .body(
            AcpService.error(
                mapped.getStatusCode().value(),
                mapped.getBody().path("error").path("code").asText()));
  }
}
