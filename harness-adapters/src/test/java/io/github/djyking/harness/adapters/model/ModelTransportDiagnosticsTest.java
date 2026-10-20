package io.github.djyking.harness.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.adapters.http.LimitedBodyHandler.ResponseTooLargeException;
import io.github.djyking.harness.core.Contracts.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ModelTransportDiagnosticsTest {
  @Test
  void unwrapsAsyncFailuresIntoFixedCategoriesWithoutLeakingMessages() {
    var failures =
        new Throwable[] {
          new HttpConnectTimeoutException("private-url-token"),
          new HttpTimeoutException("private-url-token"),
          new SSLHandshakeException("private-certificate"),
          new UnknownHostException("private-host"),
          new ConnectException("private-address"),
          new TimeoutException("private-body"),
          new ResponseTooLargeException(),
          new EOFException("private-body"),
          new ClosedChannelException(),
          new ProtocolException("private-header"),
          new SocketException("private-address"),
          new IOException("private-secret"),
          new CancellationException("private-secret"),
          new IllegalStateException("private-secret")
        };
    var expected =
        new String[] {
          "CONNECT_TIMEOUT",
          "RESPONSE_TIMEOUT",
          "TLS_FAILURE",
          "DNS_FAILURE",
          "CONNECT_FAILURE",
          "RESPONSE_TIMEOUT",
          "RESPONSE_TOO_LARGE",
          "EOF",
          "CHANNEL_CLOSED",
          "PROTOCOL_FAILURE",
          "SOCKET_FAILURE",
          "IO_FAILURE",
          "CALL_CANCELLED",
          "CALL_OUTCOME"
        };
    for (int i = 0; i < failures.length; i++) {
      String code = ChatCompletionsModel.transportCode(new ExecutionException(failures[i]));
      assertEquals("MODEL_" + expected[i] + (i == failures.length - 1 ? "_UNKNOWN" : "_USAGE_UNKNOWN"), code);
      assertFalse(code.contains("private"));
    }
  }

  @Test
  void completeCauseChainPreservesSpecificFailuresInsideIoWrappers() {
    var failures = List.of(
        new SSLHandshakeException("private-certificate"),
        new HttpTimeoutException("private-endpoint"),
        new HttpConnectTimeoutException("private-address"));
    var expected = List.of("TLS_FAILURE", "RESPONSE_TIMEOUT", "CONNECT_TIMEOUT");
    for (int index = 0; index < failures.size(); index++) {
      Throwable wrapped = failures.get(index);
      for (int depth = 0; depth < 24; depth++) wrapped = new IOException("private-secret", wrapped);
      assertEquals(
          "MODEL_" + expected.get(index) + "_USAGE_UNKNOWN",
          ChatCompletionsModel.transportCode(new CompletionException(wrapped)));
    }
  }

  @Test
  void cyclicCausesTerminateWithoutInspectingMessages() {
    var first = new IOException("private-first");
    var second = new RuntimeException("private-second", first);
    first.initCause(second);
    assertEquals("MODEL_IO_FAILURE_USAGE_UNKNOWN", ChatCompletionsModel.transportCode(first));
  }

  @Test
  void truncatedHttpBodyRemainsUnknownWithoutReplayOrBodyDisclosure() throws Exception {
    assertWireFailure(
        "HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\nprivate-partial-body",
        1024,
        "MODEL_EOF_USAGE_UNKNOWN");
  }

  @Test
  void connectionClosedBeforeResponseHeadersRemainsUnknownWithoutReplay() throws Exception {
    assertWireFailure("", 1024, "MODEL_EOF_USAGE_UNKNOWN");
  }

  @Test
  void declaredResponseLimitHasDedicatedUnknownCode() throws Exception {
    String body = "private-provider-body-" + "x".repeat(128);
    assertWireFailure(
        "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
            + "\r\nConnection: close\r\n\r\n" + body,
        64,
        "MODEL_RESPONSE_TOO_LARGE_USAGE_UNKNOWN");
  }

  @Test
  void chunkedResponseLimitHasDedicatedUnknownCode() throws Exception {
    String body = "private-provider-body-" + "x".repeat(128);
    assertWireFailure(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
            + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n",
        64,
        "MODEL_RESPONSE_TOO_LARGE_USAGE_UNKNOWN");
  }

  private void assertWireFailure(String response, int limit, String code) throws Exception {
    try (var fixture = new RawHttpFixture(response)) {
      var model = new ChatCompletionsModel(
          new ChatCompletionsConfig(
              fixture.endpoint(), Duration.ofSeconds(1), Duration.ofSeconds(5), limit,
              ignored -> Map.of("Authorization", "Bearer private-fixture-token"),
              "max_completion_tokens", false));
      var failure = assertThrows(
          InvocationException.class,
          () -> model.invoke(
              DeepSeekChatModelTest.request(List.of(Message.text("user", "private-fixture-input"))),
              DeepSeekChatModelTest.context()));
      assertEquals(FailureKind.UNKNOWN, failure.kind());
      assertEquals(code, failure.getMessage());
      assertNull(failure.getCause());
      assertEquals(0, failure.getSuppressed().length);
      assertFalse(failure.toString().contains("private"));
      assertEquals(1, fixture.requests.get(), "One adapter invocation must not replay the POST");
    }
  }

  /** Real loopback HTTP with deliberately incomplete framing; no external provider is contacted. */
  private static final class RawHttpFixture implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<?> serving;
    private final AtomicInteger requests = new AtomicInteger();

    RawHttpFixture(String response) throws IOException {
      byte[] wire = response.getBytes(StandardCharsets.ISO_8859_1);
      serving = executor.submit(() -> {
        while (!server.isClosed()) {
          final Socket accepted;
          try {
            accepted = server.accept();
          } catch (SocketException closed) {
            if (server.isClosed()) break;
            throw closed;
          }
          try (var socket = accepted) {
            socket.setSoTimeout(5000);
            drainRequest(socket.getInputStream());
            requests.incrementAndGet();
            socket.getOutputStream().write(wire);
            socket.getOutputStream().flush();
          }
        }
        return null;
      });
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/chat");
    }

    private static void drainRequest(InputStream input) throws IOException {
      var headers = new ByteArrayOutputStream();
      int ending = 0;
      while (ending != 0x0d0a0d0a) {
        int next = input.read();
        if (next < 0 || headers.size() >= 65536) throw new IOException("Incomplete fixture headers");
        headers.write(next);
        ending = (ending << 8) | next;
      }
      var length = Pattern.compile("(?im)^Content-Length: *(\\d+)\\r?$")
          .matcher(headers.toString(StandardCharsets.ISO_8859_1));
      if (!length.find()) throw new IOException("Missing fixture content length");
      int bytes = Integer.parseInt(length.group(1));
      if (bytes > 65536 || input.readNBytes(bytes).length != bytes)
        throw new IOException("Incomplete fixture request");
    }

    @Override
    public void close() throws Exception {
      server.close();
      executor.shutdown();
      try {
        serving.get(5, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
  }
}
