package io.github.djyking.harness.integrations.opsagent;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpsAgentIdentityClientTest {
  private static final String APP = "Bearer synthetic-application-credential-at-least-thirty-two";

  @Test
  void exactToolScopesPreserveSlashAndUnderscore() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/harness/introspect",
        exchange -> {
          byte[] body =
              ("{\"code\":0,\"data\":{\"applicationId\":\"app\",\"projectId\":\"p\",\"subject\":\"10\","
                   + "\"permissions\":[\"tool:opsagent/rag-search\",\"tool:mcp:acceptance-fixture:probe_unknown_write\"]}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      var client =
          new OpsAgentIdentityClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      assertEquals(
          java.util.Set.of(
              "tool:opsagent/rag-search", "tool:mcp:acceptance-fixture:probe_unknown_write"),
          client.introspect(APP, "p", "user-token").permissions());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void existingBusinessErrorEnvelopeAndHttpStatusFailClosedWithoutBodyDisclosure()
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/harness/introspect",
        exchange -> {
          byte[] body =
              "{\"code\":40300,\"message\":\"secret-must-not-appear\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      var client =
          new OpsAgentIdentityClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      var failure =
          assertThrows(
              OpsAgentIdentityClient.IdentityFailure.class,
              () -> client.introspect(APP, "project", "user-token"));
      assertEquals(403, failure.status());
      assertFalse(failure.toString().contains("secret-must-not-appear"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void redirectsNeverForwardApplicationOrLoginCredential() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var followed = new AtomicInteger();
    server.createContext(
        "/internal/harness/introspect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/other");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.createContext(
        "/other",
        exchange -> {
          followed.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new OpsAgentIdentityClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      assertThrows(
          OpsAgentIdentityClient.IdentityFailure.class,
          () -> client.introspect(APP, "project", "user-token"));
      assertEquals(0, followed.get());
    } finally {
      server.stop(0);
    }
  }
}
