package io.github.djyking.harness.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Incomplete real HTTP responses must release their sockets without waiting for upstream EOF. */
class PlatformClientDeadlineTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RUN = "24b522e7-345b-4cd0-806c-2fba5538291c";

  @Test
  void deadlineCancelsARequestWhoseHeadersNeverArrive() throws Exception {
    assertDeadline(ResponseMode.NO_HEADERS, false);
  }

  @Test
  void deadlineIncludesTheWholeBodyForReadsAndCommands() throws Exception {
    assertDeadline(ResponseMode.PARTIAL_BODY, false);
    assertDeadline(ResponseMode.PARTIAL_BODY, true);
  }

  private void assertDeadline(ResponseMode mode, boolean mutation) throws Exception {
    try (var upstream = new IncompleteResponse(mode)) {
      PlatformClient client = upstream.client(Duration.ofSeconds(1));
      PlatformException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(4),
              () -> assertThrows(PlatformException.class, () -> invoke(client, mutation)));
      assertEquals("TRANSPORT_FAILED", failure.code());
      assertEquals(0, failure.status());
      assertEquals(mutation, failure.outcomeUnknown());
      assertEquals(0, upstream.responseStarted.getCount(), "Fixture must reach its response phase");
      assertTrue(
          upstream.peerClosed.get(2, TimeUnit.SECONDS), "Timed out request must close its socket");
    }
  }

  @Test
  void interruptionRestoresTheFlagAndCancelsAnIncompleteCommandResponse() throws Exception {
    try (var upstream = new IncompleteResponse(ResponseMode.PARTIAL_BODY)) {
      PlatformClient client = upstream.client(Duration.ofSeconds(30));
      var failure = new CompletableFuture<PlatformException>();
      var interruptRestored = new AtomicBoolean();
      Thread caller =
          new Thread(
              () -> {
                try {
                  invoke(client, true);
                  failure.completeExceptionally(new AssertionError("Expected interruption"));
                } catch (PlatformException interrupted) {
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                  failure.complete(interrupted);
                } catch (Throwable unexpected) {
                  failure.completeExceptionally(unexpected);
                }
              },
              "platform-client-interruption-test");
      caller.setDaemon(true);
      caller.start();
      try {
        assertTrue(upstream.responseStarted.await(3, TimeUnit.SECONDS));
        caller.interrupt();
        PlatformException result = failure.get(2, TimeUnit.SECONDS);
        assertEquals("REQUEST_INTERRUPTED", result.code());
        assertTrue(result.outcomeUnknown());
        assertTrue(interruptRestored.get());
        assertTrue(upstream.peerClosed.get(2, TimeUnit.SECONDS));
      } finally {
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @Test
  void oversizedIncompleteBodyCancelsWithoutWaitingForTheDeadlineOrRemainingBytes()
      throws Exception {
    try (var upstream = new IncompleteResponse(ResponseMode.OVERSIZED_BODY)) {
      PlatformClient client = upstream.client(Duration.ofSeconds(30));
      PlatformException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(4),
              () -> assertThrows(PlatformException.class, () -> invoke(client, true)));
      assertEquals("TRANSPORT_FAILED", failure.code());
      assertTrue(failure.outcomeUnknown());
      assertTrue(upstream.peerClosed.get(2, TimeUnit.SECONDS));
    }
  }

  private static void invoke(PlatformClient client, boolean mutation) {
    if (mutation) client.createRun(JSON.createObjectNode(), "synthetic-timeout-command");
    else client.getRun(RUN);
  }

  private enum ResponseMode {
    NO_HEADERS,
    PARTIAL_BODY,
    OVERSIZED_BODY
  }

  private static final class IncompleteResponse implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Socket connection;
    final CountDownLatch responseStarted = new CountDownLatch(1);
    final Future<Boolean> peerClosed;

    IncompleteResponse(ResponseMode mode) throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      peerClosed =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  connection = socket;
                  socket.setSoTimeout(6000);
                  var input = new BufferedInputStream(socket.getInputStream());
                  consumeRequest(input);
                  if (mode != ResponseMode.NO_HEADERS) {
                    var output = socket.getOutputStream();
                    output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n"
                                + "Content-Type: application/json\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(
                        (mode == ResponseMode.PARTIAL_BODY ? "{" : "x".repeat(2048))
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                  }
                  responseStarted.countDown();
                  try {
                    return input.read() == -1;
                  } catch (SocketException resetByClient) {
                    return true;
                  }
                }
              });
    }

    PlatformClient client(Duration timeout) {
      return new PlatformClient(
          new ClientConfiguration(
              URI.create("http://127.0.0.1:" + server.getLocalPort()),
              "support-pilot",
              () -> "synthetic-app-secret",
              () -> "synthetic-user-token",
              timeout,
              1024));
    }

    private static void consumeRequest(BufferedInputStream input) throws IOException {
      var headers = new ByteArrayOutputStream();
      int suffix = 0;
      while (suffix != 0x0d0a0d0a) {
        int value = input.read();
        if (value == -1 || headers.size() >= 65536)
          throw new IOException("Incomplete fixture request");
        headers.write(value);
        suffix = (suffix << 8) | value;
      }
      int contentLength = 0;
      for (String line : headers.toString(StandardCharsets.US_ASCII).split("\r\n")) {
        if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
          contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
        }
      }
      if (contentLength < 0
          || contentLength > 65536
          || input.readNBytes(contentLength).length != contentLength) {
        throw new IOException("Incomplete fixture request body");
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      Socket current = connection;
      if (current != null) current.close();
      executor.shutdownNow();
    }
  }
}
