package io.github.djyking.harness.support;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class SupportIdentityTest {
  private final AtomicReference<String> businessCode = new AtomicReference<>("0");
  private HttpServer server;
  private SupportIdentity identity;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/auth/login",
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes =
                ("{\"code\":"
                        + businessCode.get()
                        + ",\"data\":{\"accessToken\":\"synthetic-user-token\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    identity =
        new SupportIdentity(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "support-pilot",
            () -> "synthetic-application-secret");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void onlyZeroBusinessCodeSucceedsAndValidNonzeroIntegersRemainAuthenticationFailures() {
    assertEquals("synthetic-user-token", login());
    for (String code : List.of("-2147483648", "-1", "1", "2147483647")) {
      businessCode.set(code);
      SupportIdentity.Failure failure = assertThrows(SupportIdentity.Failure.class, this::login);
      assertEquals(401, failure.status, code);
      assertEquals("UNAUTHENTICATED", failure.code, code);
    }
  }

  @Test
  void overflowingAndMalformedBusinessCodesAreUnavailableInsteadOfWrappingToSuccess() {
    for (String code :
        List.of(
            "2147483648",
            "-2147483649",
            "4294967296",
            "-4294967296",
            "\"0\"",
            "0.0",
            "null",
            "true")) {
      businessCode.set(code);
      SupportIdentity.Failure failure = assertThrows(SupportIdentity.Failure.class, this::login);
      assertEquals(503, failure.status, code);
      assertEquals("AUTH_PROVIDER_UNAVAILABLE", failure.code, code);
    }
  }

  private String login() {
    return identity.login(
        Json.object()
            .put("username", "synthetic-user")
            .put("password", "synthetic-password")
            .put("captchaId", "a".repeat(32))
            .put("captchaCode", "1234"));
  }
}
