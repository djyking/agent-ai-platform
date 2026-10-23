package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.core.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpsAgentOutputAuthorizationTest {
  private static final String SECRET = "synthetic-output-application-credential-0123456789";
  private static final Set<String> READ = Set.of("runs:output:read", "opsagent:rag:search");

  @Test
  void expiredExecutionCannotBeReusedButDedicatedReadRouteCanAuthorize() throws Exception {
    try (var authority = new Authority()) {
      var provider = authority.provider();
      var owner = authority.owner();
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      provider.toolAuthorization(
                          owner.application(), owner.project(), owner.delegation(), owner.runId()))
              .status);
      assertEquals(
          "Bearer synthetic-output-only",
          provider.outputAuthorization(reader("app-a", "project-a", "10", READ), owner));
      assertEquals(1, authority.executionCalls.get());
      assertEquals(1, authority.outputCalls.get());
      assertEquals("Bearer " + SECRET, authority.authorization.get());
      assertEquals(
          Json.object()
              .put("projectId", owner.project())
              .put("subject", owner.subject())
              .put("runId", owner.runId()),
          authority.body.get());
    }
  }

  @Test
  void crossScopeOrMissingCurrentReadPermissionIsRejectedBeforeAuthorityCall() throws Exception {
    try (var authority = new Authority()) {
      var provider = authority.provider();
      for (var reader :
          List.of(
              reader("app-b", "project-a", "10", READ),
              reader("app-a", "project-b", "10", READ),
              reader("app-a", "project-a", "11", READ),
              reader("app-a", "project-a", "10", Set.of("runs:output:read")),
              reader("app-a", "project-a", "10", Set.of("opsagent:rag:search")))) {
        assertEquals(
            403,
            assertThrows(
                    ApiFailure.class, () -> provider.outputAuthorization(reader, authority.owner()))
                .status);
      }
      assertEquals(0, authority.outputCalls.get());
      assertEquals(0, authority.executionCalls.get());
    }
  }

  @Test
  void authorityRevocationOrOriginalCeilingDenialNeverFallsBackToExecution() throws Exception {
    try (var authority = new Authority()) {
      var provider = authority.provider();
      for (int status : List.of(401, 403, 404, 503)) {
        authority.outputStatus.set(status);
        assertEquals(
            status,
            assertThrows(
                    ApiFailure.class,
                    () ->
                        provider.outputAuthorization(
                            reader("app-a", "project-a", "10", READ), authority.owner()))
                .status);
      }
      assertEquals(4, authority.outputCalls.get());
      assertEquals(0, authority.executionCalls.get());
    }
  }

  @Test
  void executionOnlyIdentityHasNoImplicitProtectedOutputCapability() {
    try (var fixture = new PlatformTestSupport()) {
      var identity = new ExecutionOnlyIdentity();
      String run = fixture.id(fixture.create("protected", "default-output-denied"));
      var owner = fixture.repository.owned(run);
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class, () -> identity.outputAuthorization(fixture.principal(), owner))
              .status);
      var projections = new AtomicInteger();
      var policy =
          new OpsAgentProtectedOutputPolicy(
              identity,
              (token, citations) -> {
                projections.incrementAndGet();
                return true;
              });
      assertFalse(
          policy.canRead(fixture.principal(), owner, PlatformTestSupport.protectedResult()));
      assertEquals(0, identity.executionCalls.get());
      assertEquals(0, projections.get());
    }
  }

  private static IdentityProvider.Principal reader(
      String app, String project, String subject, Set<String> permissions) {
    return new IdentityProvider.Principal(app, project, subject, permissions);
  }

  private static final class Authority implements AutoCloseable {
    final HttpServer server;
    final String delegation = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
    final AtomicInteger outputStatus = new AtomicInteger(200);
    final AtomicInteger outputCalls = new AtomicInteger(), executionCalls = new AtomicInteger();
    final AtomicReference<JsonNode> body = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();

    Authority() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/internal/harness/delegations/" + delegation,
          exchange -> {
            try (exchange) {
              authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
              body.set(
                  Json.read(
                      new String(
                          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
              int status;
              JsonNode reply;
              if (exchange.getRequestURI().getPath().endsWith("/output-token")) {
                outputCalls.incrementAndGet();
                status = outputStatus.get();
                reply =
                    Json.object()
                        .put("code", status == 200 ? 0 : status * 100)
                        .set(
                            "data",
                            Json.object()
                                .put("authorization", "Bearer synthetic-output-only")
                                .put("expiresAt", Instant.now().plusSeconds(120).toString()));
              } else {
                executionCalls.incrementAndGet();
                status = 403;
                reply = Json.object().put("code", 40300);
              }
              byte[] bytes = Json.write(reply).getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(status, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    OpsAgentIdentityProvider provider() {
      return new OpsAgentIdentityProvider(
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          Map.of("app-a", "env:APP_A"),
          ref -> SECRET);
    }

    PlatformRepository.Owned owner() {
      return new PlatformRepository.Owned(
          run, "project-a", "app-a", "10", delegation, null, null, null, 0);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class ExecutionOnlyIdentity implements IdentityProvider {
    final AtomicInteger executionCalls = new AtomicInteger();

    public Principal authenticate(String project, String application, String token) {
      throw ApiFailure.denied();
    }

    public String delegate(
        Principal principal, String application, String token, String run, Instant deadline) {
      throw ApiFailure.denied();
    }

    public Principal current(String application, String project, String delegation, String run) {
      throw ApiFailure.denied();
    }

    public String toolAuthorization(
        String application, String project, String delegation, String run) {
      executionCalls.incrementAndGet();
      return "Bearer must-not-be-borrowed-for-output";
    }
  }
}
