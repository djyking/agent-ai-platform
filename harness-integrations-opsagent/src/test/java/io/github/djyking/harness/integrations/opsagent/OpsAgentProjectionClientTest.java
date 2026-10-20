package io.github.djyking.harness.integrations.opsagent;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpsAgentProjectionClientTest {
  private static com.fasterxml.jackson.databind.JsonNode citations() {
    return Json.MAPPER
        .createArrayNode()
        .add(Json.object().put("documentId", 1).put("chunkId", 2).put("version", 1));
  }

  @Test
  void stalledHeadersAndBodyDenyAccessWithinDeadlineAndCloseConnection() throws Exception {
    for (boolean afterHeaders : new boolean[] {false, true}) {
      try (var peer = new StalledHttpResponse(afterHeaders)) {
        var client = new OpsAgentProjectionClient(peer.origin(), Duration.ofMillis(500));
        assertFalse(
            assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> client.allowed("Bearer synthetic-token", citations())));
        assertTrue(peer.received());
        assertTrue(peer.cancelled());
      }
    }
  }

  @Test
  void interruptedProjectionDeniesAccessAndRestoresInterrupt() throws Exception {
    try (var peer = new StalledHttpResponse(true)) {
      var client = new OpsAgentProjectionClient(peer.origin(), Duration.ofSeconds(10));
      var allowed = new AtomicBoolean(true);
      var interrupted = new AtomicBoolean();
      Thread caller =
          new Thread(
              () -> {
                allowed.set(client.allowed("Bearer synthetic-token", citations()));
                interrupted.set(Thread.currentThread().isInterrupted());
              });
      caller.start();
      try {
        assertTrue(peer.received());
        caller.interrupt();
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertFalse(allowed.get());
        assertTrue(interrupted.get());
        assertTrue(peer.cancelled());
      } finally {
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @Test
  void onlyExplicitBoundedDomainSuccessPermitsReferencesWithoutSendingEvidence() throws Exception {
    var response = new AtomicReference<>("{\"code\":0,\"data\":{\"allowed\":true}}");
    var seen = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/rag/output/validate-citations",
        exchange -> {
          seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      var client =
          new OpsAgentProjectionClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      var citations =
          Json.MAPPER
              .createArrayNode()
              .add(
                  Json.object()
                      .put("documentId", 1)
                      .put("chunkId", 2)
                      .put("version", 3)
                      .put("evidence", "private-content"));
      assertTrue(client.allowed("Bearer synthetic-token", citations));
      assertFalse(seen.get().contains("private-content"));
      for (String invalid :
          new String[] {
            "{\"code\":4294967296,\"data\":{\"allowed\":true}}",
            "{\"code\":0,\"data\":{\"allowed\":\"true\"}}",
            "{\"code\":40300,\"data\":{\"allowed\":true}}",
            "{\"code\":0,\"data\":{\"allowed\":false}}",
            " ".repeat(4097)
          }) {
        response.set(invalid);
        assertFalse(client.allowed("Bearer synthetic-token", citations));
      }
      assertFalse(client.allowed("Bearer synthetic-token", Json.MAPPER.createArrayNode()));
      assertFalse(
          client.allowed(
              "Bearer synthetic-token",
              Json.MAPPER
                  .createArrayNode()
                  .add(Json.object().put("documentId", 1).put("chunkId", 2))));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void redirectIsNeverFollowedWithDelegatedCredential() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var forwarded = new AtomicInteger();
    server.createContext(
        "/internal/rag/output/validate-citations",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/other");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.createContext(
        "/other",
        exchange -> {
          forwarded.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new OpsAgentProjectionClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      assertFalse(
          client.allowed(
              "Bearer synthetic-token",
              Json.MAPPER
                  .createArrayNode()
                  .add(Json.object().put("documentId", 1).put("chunkId", 2).put("version", 1))));
      assertEquals(0, forwarded.get());
    } finally {
      server.stop(0);
    }
  }
}
