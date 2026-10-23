package io.github.djyking.harness.integrations.opsagent;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

/** One independent HTTP/1.1 peer; observes cancellation without releasing the response body. */
final class StalledHttpResponse implements AutoCloseable {
  private final ServerSocket listener;
  private final ExecutorService server = Executors.newSingleThreadExecutor();
  private final CountDownLatch received = new CountDownLatch(1);
  private final Future<Boolean> disconnected;
  private volatile Socket connection;

  StalledHttpResponse(boolean afterHeaders) throws Exception {
    listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    disconnected =
        server.submit(
            () -> {
              try (Socket socket = listener.accept()) {
                connection = socket;
                socket.setSoTimeout(6000);
                var input = socket.getInputStream();
                var headers = new ByteArrayOutputStream();
                int matched = 0;
                byte[] ending = {'\r', '\n', '\r', '\n'};
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
                if (afterHeaders) {
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n"
                                  + "Content-Type: application/json\r\nConnection: close\r\n\r\n{")
                              .getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().flush();
                }
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

  boolean received() throws InterruptedException {
    return received.await(3, TimeUnit.SECONDS);
  }

  boolean cancelled() throws Exception {
    return disconnected.get(3, TimeUnit.SECONDS);
  }

  @Override
  public void close() throws Exception {
    listener.close();
    if (connection != null) connection.close();
    server.shutdownNow();
    server.awaitTermination(3, TimeUnit.SECONDS);
  }
}
