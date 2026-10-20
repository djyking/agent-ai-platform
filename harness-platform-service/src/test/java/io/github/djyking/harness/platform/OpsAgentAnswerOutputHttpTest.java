package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpsAgentAnswerOutputHttpTest {
  private static final String TOKEN = "Bearer synthetic-token";

  @Test
  void stalledBodyDeniesAccessWithinDeadlineAndReleasesConnection() throws Exception {
    try (var peer = new StalledPeer()) {
      var client = OpsAgentAnswerOutputPolicy.client(peer.origin());
      assertFalse(
          assertTimeoutPreemptively(
              Duration.ofSeconds(8), () -> client.test(TOKEN, Json.object())));
      assertTrue(peer.received.await(2, TimeUnit.SECONDS));
      assertTrue(peer.disconnected.get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void interruptedBodyWaitDeniesAccessRestoresFlagAndReleasesConnection() throws Exception {
    try (var peer = new StalledPeer()) {
      var client = OpsAgentAnswerOutputPolicy.client(peer.origin());
      var allowed = new AtomicBoolean(true);
      var interrupted = new AtomicBoolean();
      Thread caller =
          new Thread(
              () -> {
                allowed.set(client.test(TOKEN, Json.object()));
                interrupted.set(Thread.currentThread().isInterrupted());
              });
      caller.start();
      try {
        assertTrue(peer.received.await(3, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertFalse(allowed.get());
        assertTrue(interrupted.get());
        assertTrue(peer.disconnected.get(2, TimeUnit.SECONDS));
      } finally {
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @Test
  void onlyExplicitBoundedSuccessGrantsAccessIncludingOverflowedBusinessCode() throws Exception {
    var body = new AtomicReference<>("{\"code\":0,\"data\":{\"allowed\":true}}");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/rag/harness/output-answer-access",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            output.write(body.get().getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      var client =
          OpsAgentAnswerOutputPolicy.client(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      assertTrue(client.test(TOKEN, Json.object()));
      for (String invalid :
          new String[] {
            "{\"code\":4294967296,\"data\":{\"allowed\":true}}",
            "{\"code\":-4294967296,\"data\":{\"allowed\":true}}",
            "{\"code\":40300,\"data\":{\"allowed\":true}}",
            "{\"code\":0,\"data\":{\"allowed\":\"true\"}}",
            " ".repeat(4097)
          }) {
        body.set(invalid);
        assertFalse(client.test(TOKEN, Json.object()));
      }
    } finally {
      server.stop(0);
    }
  }

  private static final class StalledPeer implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService server = Executors.newSingleThreadExecutor();
    final CountDownLatch received = new CountDownLatch(1);
    final Future<Boolean> disconnected;
    private volatile Socket connection;

    StalledPeer() throws Exception {
      listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      disconnected =
          server.submit(
              () -> {
                try (Socket socket = listener.accept()) {
                  connection = socket;
                  socket.setSoTimeout(10000);
                  var input = socket.getInputStream();
                  var headers = new ByteArrayOutputStream();
                  byte[] ending = {'\r', '\n', '\r', '\n'};
                  int matched = 0;
                  while (matched < ending.length) {
                    int value = input.read();
                    if (value < 0 || headers.size() >= 65536)
                      throw new IllegalStateException("Incomplete fixture request headers");
                    headers.write(value);
                    matched = value == ending[matched] ? matched + 1 : value == '\r' ? 1 : 0;
                  }
                  int length = 0;
                  for (String line : headers.toString(StandardCharsets.US_ASCII).split("\r\n"))
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                      length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
                  if (input.readNBytes(length).length != length)
                    throw new IllegalStateException("Incomplete fixture request body");
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n"
                                  + "Content-Type: application/json\r\nConnection: close\r\n\r\n{")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().flush();
                  received.countDown();
                  try {
                    return input.read() == -1;
                  } catch (SocketException closed) {
                    return true;
                  }
                }
              });
    }

    URI origin() {
      return URI.create("http://127.0.0.1:" + listener.getLocalPort());
    }

    @Override
    public void close() throws Exception {
      listener.close();
      if (connection != null) connection.close();
      server.shutdownNow();
      server.awaitTermination(3, TimeUnit.SECONDS);
    }
  }
}
