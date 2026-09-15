package io.github.djyking.harness.evals;

import static io.github.djyking.harness.core.Contracts.*;
import static io.github.djyking.harness.evals.RecordedExchanges.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.testing.ScriptedModel;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecordedExchangesTest {
  private static final Actor ACTOR = new Actor("engineer", "engineering", Set.of("tool:lookup"));
  private static final Metadata METADATA =
      new Metadata(
          "synthetic-v1",
          "engineering",
          Classification.SYNTHETIC,
          "Only invented test values, no source credentials or private data");
  private static final ModelProfile PROFILE =
      new ModelProfile("test", "scripted", "test-v1", 10000, 100, 5000, Json.object());
  private static final ToolDescriptor TOOL =
      new ToolDescriptor(
          "lookup",
          "lookup",
          "Lookup",
          "internal",
          "lookup",
          "1",
          Json.read("{\"type\":\"object\"}"),
          ToolPolicy.readOnlyPolicy());

  @Test
  void exactSemanticModelRequestReplaysAcrossRunIdsAndReturnsDetachedData() {
    var original = request("one", "What is the review policy?");
    var context = context("one", ACTOR);
    var exchange =
        model(METADATA, original, context, ScriptedModel.answer("Synthetic review policy"));
    var provider = new RecordedModel(List.of(exchange));
    var result =
        provider.invoke(
            request("different-run", "What is the review policy?"),
            context("different-run", ACTOR));
    assertEquals("Synthetic review policy", result.message().content());
    assertEquals(30, result.usage().total());
    assertEquals(exchange.fingerprint(), modelFingerprint(original, context));
  }

  @Test
  void modelPromptProfileAndActorChangesMissWithoutAnyFallback() {
    var original = request("one", "question");
    var context = context("one", ACTOR);
    var provider =
        new RecordedModel(
            List.of(model(METADATA, original, context, ScriptedModel.answer("answer"))));
    assertMiss(() -> provider.invoke(request("one", "changed"), context));
    assertMiss(
        () ->
            provider.invoke(
                original,
                context("one", new Actor("other-user", "engineering", ACTOR.permissions()))));
    assertMiss(
        () ->
            provider.invoke(
                original,
                context("one", new Actor("engineer", "other-project", ACTOR.permissions()))));
    var changed =
        new ModelRequest(
            "one",
            new ModelProfile("test", "scripted", "new-version", 10000, 100, 5000, Json.object()),
            original.messages(),
            original.tools());
    assertMiss(() -> provider.invoke(changed, context));
  }

  @Test
  void toolArgumentsContractAndPermissionsAreBoundAndResponseCannotBeMutated() {
    var args = Json.object().put("query", "review");
    var context = context("one", ACTOR);
    var exchange =
        tool(
            METADATA,
            TOOL,
            args,
            context,
            new ToolResult(
                Json.object().put("source", "memory:/review"), false, "synthetic-receipt"));
    var provider = new RecordedTool(List.of(exchange));
    var first = provider.invoke(TOOL, args, context);
    ((com.fasterxml.jackson.databind.node.ObjectNode) first.output()).put("source", "changed");
    assertEquals(
        "memory:/review", provider.invoke(TOOL, args, context).output().path("source").asText());
    assertMiss(() -> provider.invoke(TOOL, Json.object().put("query", "different"), context));
    var newVersion =
        new ToolDescriptor(
            TOOL.key(),
            TOOL.modelName(),
            TOOL.description(),
            TOOL.adapter(),
            TOOL.remoteName(),
            "2",
            TOOL.inputSchema(),
            TOOL.policy());
    assertMiss(() -> provider.invoke(newVersion, args, context));
    assertMiss(
        () ->
            provider.invoke(
                TOOL, args, context("one", new Actor("engineer", "engineering", Set.of()))));
  }

  @Test
  void ambiguousFixturesAndMissingProvenanceAreRejected() {
    var context = context("one", ACTOR);
    var exchange = model(METADATA, request("one", "question"), context, ScriptedModel.answer("a"));
    assertThrows(
        IllegalArgumentException.class, () -> new RecordedModel(List.of(exchange, exchange)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Metadata("fixture", "engineering", Classification.REDACTED, ""));
    assertThrows(
        InvocationException.class,
        () ->
            model(
                METADATA,
                request("one", "question"),
                context("one", new Actor("engineer", "finance", Set.of())),
                ScriptedModel.answer("a")));
  }

  @Test
  void serializedFixtureRemainsOfflineAndChecksDeclaredScope() {
    var context = context("one", ACTOR);
    var exchange = model(METADATA, request("one", "question"), context, ScriptedModel.answer("a"));
    var loaded = Json.convert(Json.read(Json.write(exchange)), ModelExchange.class);
    assertEquals(
        "a",
        new RecordedModel(List.of(loaded))
            .invoke(request("new-run", "question"), context("new-run", ACTOR))
            .message()
            .content());
    var forged =
        new ModelExchange(
            new Metadata(
                "other", "finance", Classification.SYNTHETIC, "Synthetic invalid scope fixture"),
            exchange.fingerprint(),
            exchange.response());
    var failure =
        assertThrows(
            InvocationException.class,
            () -> new RecordedModel(List.of(forged)).invoke(request("one", "question"), context));
    assertEquals(FailureKind.DENIED, failure.kind());
    assertEquals("REPLAY_SCOPE_MISMATCH", failure.getMessage());
  }

  private static ModelRequest request(String id, String question) {
    return new ModelRequest(id, PROFILE, List.of(Message.text("user", question)), List.of(TOOL));
  }

  private static ExecutionContext context(String id, Actor actor) {
    return new ExecutionContext(
        id,
        "lookup-node",
        id + ":call",
        id + ":attempt",
        actor,
        Instant.now().plusSeconds(10),
        id + ":trace");
  }

  private static void assertMiss(org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(InvocationException.class, action);
    assertEquals(FailureKind.PERMANENT, failure.kind());
    assertEquals("REPLAY_MISS", failure.getMessage());
  }
}
