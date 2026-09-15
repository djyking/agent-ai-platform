package io.github.djyking.harness.capabilities.rag;

import static io.github.djyking.harness.capabilities.rag.Retrieval.*;
import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Json;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagToolTest {
  private final ExecutionContext context =
      new ExecutionContext(
          "run",
          "node",
          "invoke",
          "attempt",
          new Actor("alice", "team-a", Set.of()),
          Instant.now().plusSeconds(30),
          "trace");

  @Test
  void callerCannotChooseAnotherScopeAndBadAdaptersCannotLeakDocuments() {
    Retriever faulty =
        request ->
            List.of(
                new Hit(new Document("b", "team-b", "Secret", "memory:/secret", "secret"), 1),
                new Hit(new Document("a", "team-a", "Allowed", "memory:/allowed", "allowed"), 1));
    var tool = new RagTool(faulty, 5, 100);
    var descriptor = tool.descriptor("knowledge", "knowledge");
    var result = tool.invoke(descriptor, Json.read("{\"query\":\"guide\"}"), context);
    assertTrue(result.output().path("text").asText().contains("allowed"));
    assertFalse(Json.write(result).contains("secret"));
    assertThrows(
        InvocationException.class,
        () ->
            tool.invoke(
                descriptor, Json.read("{\"query\":\"guide\",\"scope\":\"team-b\"}"), context));
  }

  @Test
  void currentHostScopesAreResolvedOnEveryCall() {
    var permitted = new HashSet<>(Set.of("team-a"));
    var tool =
        new RagTool(
            new InMemoryRetriever(
                List.of(new Document("a", "team-a", "Guide", "memory:/a", "guide"))),
            actor -> permitted,
            3,
            100);
    var descriptor = tool.descriptor("knowledge", "knowledge");
    assertEquals(
        1,
        tool.invoke(descriptor, Json.read("{\"query\":\"guide\"}"), context)
            .output()
            .path("citations")
            .size());
    permitted.clear();
    assertEquals(
        0,
        tool.invoke(descriptor, Json.read("{\"query\":\"guide\"}"), context)
            .output()
            .path("citations")
            .size());
  }

  @Test
  void scopesRevokedDuringRetrievalAreFilteredBeforeReturningResults() {
    var permitted = new HashSet<>(Set.of("team-a"));
    Retriever slowIndex =
        request -> {
          assertEquals(Set.of("team-a"), request.authorizedScopes());
          permitted.clear();
          return List.of(
              new Hit(new Document("a", "team-a", "Guide", "memory:/a", "revoked content"), 1));
        };
    var tool = new RagTool(slowIndex, actor -> permitted, 3, 100);
    var result =
        tool.invoke(
            tool.descriptor("knowledge", "knowledge"), Json.read("{\"query\":\"guide\"}"), context);
    assertTrue(result.output().path("text").asText().isEmpty());
    assertTrue(result.output().path("citations").isEmpty());
  }
}
