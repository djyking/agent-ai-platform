package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConsoleIdentityLoginTest {
  @Test
  void existingAuthContractIsProjectedAndProviderMessagesAreHidden() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger mode = new AtomicInteger();
    server.createContext(
        "/api/auth/",
        exchange -> {
          String response =
              exchange.getRequestURI().getPath().endsWith("captcha")
                  ? "{\"code\":0,\"data\":{\"captchaId\":\"0123456789abcdef0123456789abcdef\",\"imageDataUrl\":\"data:image/png;base64,aA==\",\"expiresInSeconds\":120}}"
                  : "{\"code\":0,\"data\":{\"accessToken\":\"existing-user-token\",\"refreshToken\":\"never-returned\"}}";
          if (mode.get() == 1) response = "{\"code\":401,\"message\":\"secret-provider-detail\"}";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new ConsoleIdentityLogin(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      assertEquals(3, client.captcha().size());
      assertEquals("existing-user-token", client.login(Json.object().put("username", "synthetic")));
      mode.set(1);
      var failure = assertThrows(ApiFailure.class, () -> client.login(Json.object()));
      assertEquals(401, failure.status);
      assertFalse(failure.getMessage().contains("secret-provider-detail"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void configuredOriginRejectsCredentialsPathsAndCleartextRemoteTargets() {
    for (String uri :
        new String[] {
          "http://remote.invalid",
          "https://user:secret@identity.invalid",
          "https://identity.invalid/login",
          "https://identity.invalid?target=other",
          "https://identity.invalid#x"
        })
      assertThrows(IllegalArgumentException.class, () -> new ConsoleIdentityLogin(URI.create(uri)));
  }

  @Test
  void completeBodyDeadlineAndLimitApplyAfterHeadersWithoutFollowingRedirects() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newCachedThreadPool();
    var caller = Executors.newSingleThreadExecutor();
    var bodyStarted = new CountDownLatch(1);
    var releaseBody = new CountDownLatch(1);
    AtomicInteger mode = new AtomicInteger(), redirected = new AtomicInteger();
    server.setExecutor(workers);
    server.createContext(
        "/unexpected",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/api/auth/captcha",
        exchange -> {
          try {
            if (mode.get() == 1) {
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().write('{');
              exchange.getResponseBody().flush();
              bodyStarted.countDown();
              releaseBody.await(10, TimeUnit.SECONDS);
            } else if (mode.get() == 2) {
              exchange.sendResponseHeaders(200, 262145);
              exchange.getResponseBody().write(new byte[262145]);
            } else if (mode.get() == 3) {
              exchange.getResponseHeaders().set("Location", "/unexpected");
              exchange.sendResponseHeaders(302, -1);
            } else {
              byte[] bytes =
                  "{\"code\":0,\"data\":{\"captchaId\":\"0123456789abcdef0123456789abcdef\",\"imageDataUrl\":\"data:image/png;base64,aA==\"}}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (java.io.IOException cancelled) {
            /* Expected when the bounded client cancels. */
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var client =
          new ConsoleIdentityLogin(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Duration.ofSeconds(2));
      assertNotNull(client.captcha()); // Warm HTTP/JIT before the deliberate after-headers stall.
      mode.set(1);
      Future<?> stalled = caller.submit(client::captcha);
      assertTrue(bodyStarted.await(5, TimeUnit.SECONDS));
      ExecutionException stopped =
          assertThrows(ExecutionException.class, () -> stalled.get(5, TimeUnit.SECONDS));
      assertInstanceOf(ApiFailure.class, stopped.getCause());
      assertEquals(503, ((ApiFailure) stopped.getCause()).status);
      releaseBody.countDown();
      mode.set(2);
      assertEquals(503, assertThrows(ApiFailure.class, client::captcha).status);
      mode.set(3);
      assertEquals(503, assertThrows(ApiFailure.class, client::captcha).status);
      assertEquals(0, redirected.get());
    } finally {
      releaseBody.countDown();
      server.stop(0);
      workers.shutdownNow();
      caller.shutdownNow();
    }
  }
}
