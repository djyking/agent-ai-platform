package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OpsAgentIdentityProviderTest {
  @Test
  void existingOpsBridgeContractAndScopeChecksRemainCompatible() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String secret = "synthetic-application-a-credential-0123456789";
    String delegation = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
    Instant expiry = Instant.now().plusSeconds(3600);
    AtomicReference<String> app = new AtomicReference<>("app-a"),
        project = new AtomicReference<>("studio");
    AtomicReference<String> lastAuthorization = new AtomicReference<>();
    AtomicInteger status = new AtomicInteger(200);
    server.createContext(
        "/internal/harness/",
        exchange -> {
          try (exchange) {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            var identity =
                Json.object()
                    .put("applicationId", app.get())
                    .put("projectId", project.get())
                    .put("subject", "alice");
            identity.putArray("permissions").add("runs:read").add("runs:create");
            var data = identity;
            if (exchange.getRequestURI().getPath().endsWith("/token"))
              data =
                  Json.object()
                      .put("authorization", "Bearer synthetic-domain-token")
                      .put("expiresAt", expiry.toString());
            else if (exchange.getRequestURI().getPath().endsWith("/delegations")) {
              data =
                  Json.object().put("delegationId", delegation).put("expiresAt", expiry.toString());
              data.set("identity", identity);
            }
            byte[] body =
                Json.write(Json.object().put("code", 0).set("data", data))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      var provider =
          new OpsAgentIdentityProvider(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              Map.of("app-a", "env:APP_A", "app-b", "env:APP_B"),
              ref ->
                  ref.equals("env:APP_A")
                      ? secret
                      : "synthetic-application-b-credential-0123456789");
      var principal = provider.authenticate("studio", secret, "synthetic-user-token");
      assertEquals("Bearer " + secret, lastAuthorization.get());
      assertEquals(Set.of("runs:read", "runs:create"), principal.permissions());
      assertEquals(
          delegation, provider.delegate(principal, secret, "synthetic-user-token", run, expiry));
      assertEquals(principal, provider.current("app-a", "studio", delegation, run));
      assertEquals(
          "Bearer synthetic-domain-token",
          provider.toolAuthorization("app-a", "studio", delegation, run));
      app.set("app-b");
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () -> provider.authenticate("studio", secret, "synthetic-user-token"))
              .status);
      assertEquals(
          403,
          assertThrows(ApiFailure.class, () -> provider.current("app-a", "studio", delegation, run))
              .status);
      app.set("app-a");
      project.set("private");
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () -> provider.authenticate("studio", secret, "synthetic-user-token"))
              .status);
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () -> provider.current("unknown-app", "studio", delegation, run))
              .status);
      for (int rejected : List.of(401, 403, 404, 503)) {
        status.set(rejected);
        assertEquals(
            rejected,
            assertThrows(
                    ApiFailure.class,
                    () -> provider.authenticate("studio", secret, "synthetic-user-token"))
                .status);
      }
    } finally {
      server.stop(0);
    }
  }
}
