package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.integrations.opsagent.OpsAgentRagTool;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

final class CatalogLifecycleTest {
  static final class Fixture implements AutoCloseable {
    final PlatformTestSupport f = new PlatformTestSupport();
    final ToolRegistry tools = new ToolRegistry();
    final Deployment config;
    final CatalogService catalog;
    final IdentityProvider.Principal admin;

    Fixture() {
      tools.register(
          tool("read", ToolPolicy.readOnlyPolicy()),
          (d, a, c) -> {
            throw new AssertionError("Catalog replay invoked a real tool");
          });
      tools.register(
          tool("write", ToolPolicy.approvedWrite()),
          (d, a, c) -> {
            throw new AssertionError("Catalog replay invoked a real write");
          });
      tools.register(
          new ToolDescriptor(
              OpsAgentRagTool.KEY,
              "protected_retrieve",
              "Protected fixture",
              "test-only",
              "retrieve",
              "1",
              valueSchema(),
              new ToolPolicy(true, false, false, 1, 5000, Set.of(OpsAgentRagTool.PERMISSION))),
          (d, a, c) -> {
            throw new AssertionError("Catalog replay invoked retrieval");
          });
      config =
          new Deployment(
              f.config.identityOrigin(),
              f.config.signingSecret(),
              f.config.applicationSecrets(),
              f.config.projects(),
              f.config.releases(),
              Set.of(),
              List.of(),
              List.of(Json.object().put("provider", "test")),
              4,
              30000,
              false);
      catalog =
          new CatalogService(
              config, f.repository, tools, Set.of("synthetic-credential-must-not-persist"));
      f.service.catalog(catalog);
      f.access.releaseCheck(catalog::requireExecution);
      f.identities.grant("app-a", "project-a", "publisher", Set.of("*"));
      admin = f.principal("app-a", "project-a", "publisher");
    }

    JsonNode command(String type, String id, String operation, JsonNode body) {
      long revision = 0;
      try {
        revision = catalog.get(admin, type, id).path("revision").asLong();
      } catch (ApiFailure e) {
        if (e.status != 404) throw e;
      }
      return catalog.mutate(
          admin,
          type,
          id,
          operation,
          null,
          UUID.randomUUID().toString(),
          "\"c" + revision + "\"",
          body);
    }

    JsonNode save(String type, String id, JsonNode spec, JsonNode cases) {
      ObjectNode body = Json.object().put("name", id);
      body.set("spec", spec);
      body.set("regressionCases", cases);
      return command(type, id, "save", body);
    }

    JsonNode publish(String type, String id, JsonNode spec) {
      return publish(type, id, spec, Json.MAPPER.createArrayNode());
    }

    JsonNode publish(String type, String id, JsonNode spec, JsonNode cases) {
      save(type, id, spec, cases);
      JsonNode checked = command(type, id, "validate", Json.object());
      assertTrue(checked.path("validation").path("passed").asBoolean(), checked.toPrettyString());
      return command(type, id, "publish", Json.object());
    }

    JsonNode ref(String id, int version) {
      return Json.object().put("id", id).put("version", version);
    }

    JsonNode policy(boolean tool, boolean publicOutput) {
      ObjectNode p =
          (ObjectNode)
              Json.read(
                  "{\"toolConnections\":[],\"executionPermissions\":[],\"approvers\":[\"app-a/reviewer\"],\"reviewFields\":[\"value\"],\"publicOutput\":false}");
      p.put("publicOutput", publicOutput);
      if (tool) {
        p.withArray("toolConnections").add(ref("read-connection", 1));
        p.withArray("executionPermissions").add("tool:test:read");
      }
      return p;
    }

    void base(boolean tool) {
      if (tool)
        publish("ToolConnection", "read-connection", Json.object().put("toolKey", "test:read"));
      publish("ToolPolicy", "policy", policy(tool, true));
      publish("RunPolicy", "limits", Json.tree(new Deployment.Limits(10000, 4, 8, 100, 3600)));
    }

    JsonNode workflow(String kind) {
      return switch (kind) {
        case "read" ->
            Json.read(
                "{\"definition\":{\"id\":\"test\",\"version\":\"1\",\"start\":\"read\",\"maxTransitions\":20,\"nodes\":{\"read\":{\"kind\":\"tool\",\"toolName\":\"test:read\",\"argumentsJson\":\"{}\",\"argumentBindings\":{\"value\":\"value\"},\"output\":\"result\",\"next\":\"end\"},\"end\":{\"kind\":\"end\",\"output\":\"result\"}}}}");
        case "human" ->
            Json.read(
                "{\"definition\":{\"id\":\"test\",\"version\":\"1\",\"start\":\"review\",\"maxTransitions\":20,\"nodes\":{\"review\":{\"kind\":\"human\",\"message\":\"Exact"
                    + " original"
                    + " question\",\"output\":\"result\",\"next\":\"end\"},\"end\":{\"kind\":\"end\",\"output\":\"result\"}}}}");
        default ->
            Json.read(
                "{\"definition\":{\"id\":\"test\",\"version\":\"1\",\"start\":\"end\",\"maxTransitions\":20,\"nodes\":{\"end\":{\"kind\":\"end\",\"output\":\"value\"}}}}");
      };
    }

    JsonNode agent(int workflow, JsonNode output) {
      ObjectNode a = Json.object();
      a.set("workflow", ref("flow", workflow));
      a.set("toolPolicy", ref("policy", 1));
      a.set("runPolicy", ref("limits", 1));
      a.set("inputSchema", valueSchema());
      a.set("outputSchema", output);
      return a;
    }

    JsonNode cases(String kind) {
      return switch (kind) {
        case "read" ->
            Json.read(
                "[{\"name\":\"frozen-tool\",\"inputs\":{\"value\":7},\"toolResults\":{\"read\":{\"value\":7}},\"expectedToolArguments\":{\"read\":{\"value\":7}},\"expectedOutput\":{\"value\":7},\"expectedToolKeys\":[\"test:read\"]}]");
        case "human" ->
            Json.read(
                "[{\"name\":\"recorded-approval\",\"inputs\":{\"value\":7},\"humanInputs\":{\"review\":\"accepted\"},\"expectedOutput\":{\"input\":\"accepted\"},\"expectedToolKeys\":[]}]");
        default ->
            Json.read(
                "[{\"name\":\"literal\",\"inputs\":{\"value\":7},\"expectedOutput\":7,\"expectedToolKeys\":[]}]");
      };
    }

    JsonNode agentPublish(String kind) {
      base(kind.equals("read"));
      publish("Workflow", "flow", workflow(kind));
      JsonNode output =
          kind.equals("read")
              ? valueSchema()
              : kind.equals("human")
                  ? Json.read(
                      "{\"type\":\"object\",\"required\":[\"input\"],\"properties\":{\"input\":{\"type\":\"string\"}},\"additionalProperties\":false}")
                  : Json.object().put("type", "integer");
      return publish("Agent", "demo", agent(1, output), cases(kind));
    }

    JsonNode create(JsonNode release, String key) {
      ObjectNode body = Json.object();
      body.set("releaseRef", release);
      body.set("inputs", Json.object().put("value", 7));
      return f.service.create(f.principal(), "app-a-credential", "alice-token", key, body);
    }

    public void close() {
      f.close();
    }
  }

  @Test
  void publishesEightContractsAndExecutesNewDatabaseReleaseWithoutRestart() {
    try (Fixture x = new Fixture()) {
      JsonNode published = x.agentPublish("read");
      assertEquals("PUBLISHED", published.path("status").asText());
      assertEquals(0, x.f.reads.get());
      JsonNode release = x.catalog.defaultRelease(x.admin, "demo");
      String id = x.f.id(x.create(release.path("releaseRef"), "create-online-0001"));
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      assertEquals(1, x.f.reads.get());
      assertEquals(1, x.catalog.releases(x.f.principal()).path("items").size());
      x.f.identities.grant("app-b", "project-a", "limited-caller", Set.of("runs:create"));
      var limited = x.f.principal("app-b", "project-a", "limited-caller");
      assertTrue(x.catalog.releases(limited).path("items").isEmpty());
      error(404, "RELEASE_UNAVAILABLE", () -> x.catalog.defaultRelease(limited, "demo"));
      x.publish(
          "Prompt",
          "prompt",
          Json.read(
              "{\"id\":\"prompt\",\"version\":\"1\",\"system\":\"Literal\",\"user\":\"{{question}}\",\"variables\":{\"question\":\"STRING\"}}"));
      x.publish(
          "ModelProfile",
          "model",
          Json.object().put("trustedProfile", "test").put("maxOutputTokens", 64));
      x.publish(
          "ToolConnection",
          "retrieval-connection",
          Json.object().put("toolKey", OpsAgentRagTool.KEY));
      ObjectNode retrieval = Json.object();
      retrieval.set("toolConnection", x.ref("retrieval-connection", 1));
      x.publish("RetrievalProfile", "retrieval", retrieval);
      assertEquals(8, x.catalog.capabilities(x.admin).path("types").size());
      assertFalse(x.catalog.capabilities(x.admin).toString().contains("secretRef"));
    }
  }

  @Test
  void actualReplayBlocksBadExpectedOutputArgumentsAndMissingFixtures() {
    try (Fixture x = new Fixture()) {
      x.base(true);
      x.publish("Workflow", "flow", x.workflow("read"));
      JsonNode cases = x.cases("read");
      ((ObjectNode) cases.get(0)).set("expectedOutput", Json.object().put("value", 999));
      x.save("Agent", "bad", x.agent(1, valueSchema()), cases);
      JsonNode checked = x.command("Agent", "bad", "validate", Json.object());
      assertFalse(checked.path("validation").path("passed").asBoolean());
      assertEquals(
          "EXPECTATION_MISMATCH",
          checked.path("validation").path("results").get(0).path("code").asText());
      error(409, "VALIDATION_REQUIRED", () -> x.command("Agent", "bad", "publish", Json.object()));
      ((ObjectNode) cases.get(0)).remove("expectedToolArguments");
      x.save("Agent", "bad", x.agent(1, valueSchema()), cases);
      checked = x.command("Agent", "bad", "validate", Json.object());
      assertEquals(
          "FROZEN_TOOL_CASE_REQUIRED",
          checked.path("validation").path("results").get(0).path("code").asText());
      assertEquals(0, x.f.reads.get());
    }
  }

  @Test
  void revisionCasAndIdempotencyAreAtomicAcrossIndependentCatalogInstances() throws Exception {
    try (Fixture x = new Fixture()) {
      JsonNode spec = Json.tree(new Deployment.Limits(10000, 4, 8, 100, 3600));
      ObjectNode body = Json.object().put("name", "limits");
      body.set("spec", spec);
      CatalogService second = new CatalogService(x.config, x.f.repository, x.tools, Set.of());
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        var first =
            pool.submit(
                () ->
                    x.catalog.mutate(
                        x.admin,
                        "RunPolicy",
                        "race",
                        "save",
                        null,
                        "same-create-key",
                        "\"c0\"",
                        body));
        var other =
            pool.submit(
                () ->
                    second.mutate(
                        x.admin,
                        "RunPolicy",
                        "race",
                        "save",
                        null,
                        "same-create-key",
                        "\"c0\"",
                        body));
        assertEquals(first.get(), other.get());
      } finally {
        pool.shutdownNow();
      }
      error(
          412,
          "PRECONDITION_FAILED",
          () ->
              x.catalog.mutate(
                  x.admin, "RunPolicy", "race", "save", null, "stale-create-key", "\"c0\"", body));
      ObjectNode different = body.deepCopy();
      different.put("name", "changed");
      error(
          409,
          "IDEMPOTENCY_CONFLICT",
          () ->
              second.mutate(
                  x.admin,
                  "RunPolicy",
                  "race",
                  "save",
                  null,
                  "same-create-key",
                  "\"c0\"",
                  different));
      assertEquals(1, x.catalog.get(x.admin, "RunPolicy", "race").path("audit").size());
    }
  }

  @Test
  void newVersionsAndOrdinaryDisablePreserveWaitingRunSnapshot() {
    try (Fixture x = new Fixture()) {
      x.agentPublish("human");
      JsonNode old = x.catalog.defaultRelease(x.admin, "demo").path("releaseRef");
      String id = x.f.id(x.create(old, "waiting-original-0001"));
      RunState waiting = x.f.drive(id);
      assertEquals(RunStatus.WAITING_INPUT, waiting.status);
      String definition = Json.write(waiting.definition);
      String approval = waiting.approval.digest();
      JsonNode changed = x.workflow("human");
      ((ObjectNode) changed.path("definition").path("nodes").path("review"))
          .put("message", "New version question");
      x.publish("Workflow", "flow", changed);
      JsonNode original = x.catalog.get(x.admin, "Agent", "demo").path("spec").deepCopy();
      ((ObjectNode) original).set("workflow", x.ref("flow", 2));
      x.publish("Agent", "demo", original, x.cases("human"));
      x.command("Agent", "demo", "default", Json.object().put("version", 2));
      long rev = x.catalog.get(x.admin, "Agent", "demo").path("revision").asLong();
      x.catalog.mutate(
          x.admin,
          "Agent",
          "demo",
          "disable",
          1,
          "disable-old-agent",
          "\"c" + rev + "\"",
          Json.object().put("disabled", true));
      error(404, "RELEASE_UNAVAILABLE", () -> x.create(old, "disabled-new-run-0001"));
      assertEquals(definition, Json.write(x.f.store.get(id).definition));
      assertEquals(approval, x.f.store.get(id).approval.digest());
      var view = x.f.service.approval(x.f.reviewer(), id);
      x.f.service.decide(
          x.f.reviewer(),
          id,
          view.body().path("id").asText(),
          "approve-old-run-0001",
          view.etag(),
          Json.object()
              .put("digest", view.body().path("digest").asText())
              .put("decision", "APPROVE")
              .put("input", "accepted"));
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      assertEquals(2, x.catalog.defaultRelease(x.admin, "demo").path("version").asInt());
      assertFalse(x.catalog.diff(x.admin, "Workflow", "flow", 2, 1).path("changes").isEmpty());
    }
  }

  @Test
  void emergencyRevocationStopsFollowingToolOnAlreadyCreatedRun() {
    try (Fixture x = new Fixture()) {
      x.agentPublish("read");
      String id =
          x.f.id(
              x.create(
                  x.catalog.defaultRelease(x.admin, "demo").path("releaseRef"),
                  "before-revoke-0001"));
      x.command("ToolConnection", "read-connection", "revoke", Json.object().put("revoked", true));
      RunState stopped = x.f.drive(id);
      assertEquals(0, x.f.reads.get());
      assertNotEquals(RunStatus.COMPLETED, stopped.status);
      assertEquals(0, x.catalog.releases(x.f.principal()).path("items").size());
      x.command("ToolConnection", "read-connection", "revoke", Json.object().put("revoked", false));
      assertEquals(1, x.catalog.releases(x.f.principal()).path("items").size());
    }
  }

  @Test
  void projectIsolationPermissionRevocationAndMalformedBodiesFailClosed() {
    try (Fixture x = new Fixture()) {
      x.publish("RunPolicy", "limits", Json.tree(new Deployment.Limits(10000, 4, 8, 100, 3600)));
      error(403, "FORBIDDEN", () -> x.catalog.get(x.f.principal(), "RunPolicy", "limits"));
      x.f.identities.grant("app-a", "project-b", "publisher", Set.of("*"));
      var other = x.f.principal("app-a", "project-b", "publisher");
      error(404, "NOT_FOUND", () -> x.catalog.get(other, "RunPolicy", "limits"));
      assertTrue(x.catalog.list(other, null).path("items").isEmpty());
      x.f.identities.revoke("app-a", "project-a", "publisher", "*");
      var revoked = x.f.principal("app-a", "project-a", "publisher");
      error(403, "FORBIDDEN", () -> x.catalog.list(revoked, null));
    }
  }

  @Test
  void rejectsHiddenCredentialsArbitraryEndpointsAndUnknownProperties() {
    try (Fixture x = new Fixture()) {
      for (JsonNode bad :
          List.of(
              Json.read("{\"trustedProfile\":\"test\",\"endpoint\":\"https://evil.invalid\"}"),
              Json.read(
                  "{\"id\":\"p\",\"version\":\"1\",\"system\":\"synthetic-credential-must-not-persist\",\"user\":\"hello\",\"variables\":{}}"),
              Json.read(
                  "{\"definition\":{\"argumentsJson\":\"{\\\"password\\\":\\\"opaque\\\"}\"}}")))
        error(
            422,
            "CATALOG_CREDENTIAL_OR_ENDPOINT_FORBIDDEN",
            () -> x.save("Prompt", "secret", bad, Json.MAPPER.createArrayNode()));
      ObjectNode duplicateEncoded = Json.object();
      duplicateEncoded.put(
          "argumentsJson", "{\"value\":1,\"value\":2,\"password\":\"unregistered-private-value\"}");
      error(
          422,
          "CATALOG_SPEC_INVALID",
          () ->
              x.save(
                  "Workflow",
                  "encoded-duplicate",
                  duplicateEncoded,
                  Json.MAPPER.createArrayNode()));
      ObjectNode escapedField = Json.object();
      escapedField.put("argumentsJson", "{\"\\u0070assword\":\"unregistered-private-value\"}");
      error(
          422,
          "CATALOG_CREDENTIAL_OR_ENDPOINT_FORBIDDEN",
          () -> x.save("Workflow", "encoded-escape", escapedField, Json.MAPPER.createArrayNode()));
      // A normal prompt placeholder is not an encoded argument object and remains valid.
      x.publish(
          "Prompt",
          "placeholder",
          Json.read(
              "{\"id\":\"placeholder\",\"version\":\"1\",\"system\":\"Answer\",\"user\":\"{{question}}\",\"variables\":{\"question\":\"STRING\"}}"));
      error(
          422,
          "CATALOG_SPEC_INVALID",
          () ->
              x.save(
                  "ModelProfile",
                  "model",
                  Json.object().put("trustedProfile", "test").put("provider", "other"),
                  Json.MAPPER.createArrayNode()));
      error(404, "NOT_FOUND", () -> x.catalog.get(x.admin, "ModelProfile", "model"));
      ObjectNode connection = Json.object().put("toolKey", "test:read");
      connection.putObject("authentication").put("value", "unknown-credential");
      error(
          422,
          "CATALOG_CREDENTIAL_OR_ENDPOINT_FORBIDDEN",
          () -> x.save("ToolConnection", "hidden-auth", connection, Json.MAPPER.createArrayNode()));
      error(404, "NOT_FOUND", () -> x.catalog.get(x.admin, "ToolConnection", "hidden-auth"));
      x.save(
          "ModelProfile",
          "model",
          Json.object().put("trustedProfile", "test").put("timeoutMillis", 999999),
          Json.MAPPER.createArrayNode());
      assertFalse(
          x.command("ModelProfile", "model", "validate", Json.object())
              .path("validation")
              .path("passed")
              .asBoolean());
    }
  }

  @Test
  void immutableReferencesCannotBeReboundAfterPublishingAndRepublishingIsIdempotent() {
    try (Fixture x = new Fixture()) {
      x.agentPublish("read");
      JsonNode v1 = x.catalog.version(x.admin, "Agent", "demo", 1);
      String releaseDigest = v1.path("releaseRef").path("digest").asText();
      x.save("Agent", "demo", x.agent(1, valueSchema()), x.cases("read"));
      error(409, "VALIDATION_REQUIRED", () -> x.command("Agent", "demo", "publish", Json.object()));
      assertEquals(
          releaseDigest,
          x.catalog
              .version(x.admin, "Agent", "demo", 1)
              .path("releaseRef")
              .path("digest")
              .asText());
      x.command("Agent", "demo", "validate", Json.object());
      JsonNode r = x.catalog.get(x.admin, "Agent", "demo");
      String etag = CatalogService.etag(r);
      JsonNode first =
          x.catalog.mutate(
              x.admin, "Agent", "demo", "publish", null, "publish-repeat-key", etag, Json.object());
      JsonNode retry =
          x.catalog.mutate(
              x.admin, "Agent", "demo", "publish", null, "publish-repeat-key", etag, Json.object());
      assertEquals(first, retry);
      assertEquals(2, retry.path("versions").size());
    }
  }

  @Test
  void modelReplayPinsPromptVersionAndRejectsUnexpectedRenderedMessages() {
    try (Fixture x = new Fixture()) {
      x.base(false);
      ObjectNode policy = (ObjectNode) x.policy(false, true);
      policy.withArray("executionPermissions").add("model:invoke");
      x.publish("ToolPolicy", "policy", policy);
      ObjectNode prompt =
          (ObjectNode)
              Json.read(
                  "{\"id\":\"prompt\",\"version\":\"1\",\"system\":\"Original"
                      + " instruction\",\"user\":\"{{value}}\",\"variables\":{\"value\":\"NUMBER\"}}");
      x.publish("Prompt", "prompt", prompt);
      x.publish("ModelProfile", "model", Json.object().put("trustedProfile", "test"));
      JsonNode workflow =
          Json.read(
              "{\"definition\":{\"id\":\"model-flow\",\"version\":\"1\",\"start\":\"answer\",\"maxTransitions\":20,\"nodes\":{\"answer\":{\"kind\":\"model\",\"promptRef\":{\"id\":\"prompt\",\"version\":1},\"inputBindings\":{\"value\":\"value\"},\"output\":\"result\",\"next\":\"end\"},\"end\":{\"kind\":\"end\",\"output\":\"result\"}}}}");
      x.publish("Workflow", "flow", workflow);
      ObjectNode agent = (ObjectNode) x.agent(1, Json.object().put("type", "string"));
      agent.set("modelProfile", x.ref("model", 1));
      agent.set("toolPolicy", x.ref("policy", 2));
      JsonNode cases =
          Json.read(
              "[{\"name\":\"prompt-replay\",\"inputs\":{\"value\":7},\"modelResults\":{\"answer\":\"Frozen"
                  + " answer\"},\"expectedOutput\":\"Frozen answer\",\"expectedToolKeys\":[]}]");
      ((ObjectNode) cases.get(0))
          .putObject("expectedPrompts")
          .set(
              "answer",
              Json.tree(
                  List.of(
                      Message.text("system", "Original instruction"), Message.text("user", "7"))));
      x.publish("Agent", "demo", agent, cases);
      assertEquals(0, x.f.modelCalls.get());
      JsonNode original = x.catalog.defaultRelease(x.admin, "demo").path("releaseRef");
      prompt.put("system", "Unintended replacement instruction");
      x.publish("Prompt", "prompt", prompt);
      ((ObjectNode) workflow.path("definition").path("nodes").path("answer"))
          .set("promptRef", x.ref("prompt", 2));
      x.publish("Workflow", "flow", workflow);
      agent.set("workflow", x.ref("flow", 2));
      x.save("Agent", "demo", agent, cases);
      JsonNode result = x.command("Agent", "demo", "validate", Json.object());
      assertFalse(result.path("validation").path("passed").asBoolean());
      assertEquals(
          "PROMPT_MISMATCH",
          result.path("validation").path("results").get(0).path("code").asText());
      String id = x.f.id(x.create(original, "old-prompt-run-001"));
      assertEquals(
          "Original instruction",
          x.f
              .store
              .get(id)
              .definition
              .spec()
              .path("workflow")
              .path("nodes")
              .path("answer")
              .path("prompt")
              .path("system")
              .asText());
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
    }
  }

  @Test
  void trustedToolContractDriftRejectsOldReleaseBeforeNewRun() {
    try (Fixture x = new Fixture()) {
      x.agentPublish("read");
      JsonNode release = x.catalog.defaultRelease(x.admin, "demo").path("releaseRef");
      ToolDescriptor old = x.tools.require("test:read").descriptor();
      x.tools.replace(
          new ToolDescriptor(
              old.key(),
              old.modelName(),
              old.description(),
              old.adapter(),
              old.remoteName(),
              "changed-version",
              old.inputSchema(),
              old.policy()),
          (d, a, c) -> ToolResult.success(a));
      error(409, "TRUSTED_BINDING_CHANGED", () -> x.create(release, "drift-new-run-001"));
      assertEquals(0, x.f.reads.get());
      assertTrue(x.catalog.releases(x.admin).path("items").isEmpty());
      x.tools.remove("test:read");
      assertTrue(x.catalog.releases(x.admin).path("items").isEmpty());
      error(409, "TRUSTED_BINDING_CHANGED", () -> x.create(release, "removed-binding-001"));
    }
  }
}
