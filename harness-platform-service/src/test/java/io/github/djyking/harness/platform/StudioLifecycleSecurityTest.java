package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** Full local SQL + workflow + HTTP-model fixture. No provider calls or production identities. */
class StudioLifecycleSecurityTest {
  @Test
  void structuredFieldsReachTheActualModelHttpPayloadForPreviewAndEvaluation() throws Exception {
    try (var f = new Fixture("deepseek")) {
      String system = "请输出给定公司与预算，包含预算数字。输入未给出资料时输出缺少资料。";
      ObjectNode input = Json.object().put("question", "请总结");
      input.putObject("fields").put("company", "Synthetic").put("budget", "6300");
      ObjectNode draft = f.draft().put("templateId", "structured").put("instructions", system);
      ((ObjectNode) draft.path("samples").get(0)).set("input", input.deepCopy());
      JsonNode saved = f.save("structured-app", draft, "\"s0\"", "save-structured-wire");
      JsonNode preview =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "structured-app",
              "preview",
              "structured-wire-preview",
              StudioService.etag(saved),
              Json.object().set("input", input));
      String run = preview.path("lastTask").path("runId").asText();
      assertEquals(RunStatus.COMPLETED, f.drive(run).status);
      assertEquals(input, f.runtime.store.get(run).definition.spec().path("inputs"));
      JsonNode evaluated = f.evaluate("structured-app", preview, "structured-wire-eval");
      f.driveAll();
      assertEquals(2, f.modelRequests.size());
      for (JsonNode request : f.modelRequests) {
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals(2, request.path("messages").size());
        JsonNode sentSystem = request.path("messages").get(0),
            sentUser = request.path("messages").get(1);
        assertEquals("system", sentSystem.path("role").asText());
        assertEquals(system, sentSystem.path("content").asText());
        assertEquals("user", sentUser.path("role").asText());
        String userText = sentUser.path("content").asText();
        assertTrue(userText.startsWith("任务：请总结\n资料字段："), userText);
        JsonNode fields =
            Json.read(userText.substring(userText.indexOf("资料字段：") + "资料字段：".length()));
        assertEquals(input.path("fields"), fields);
        assertEquals("Synthetic", fields.path("company").asText());
        assertEquals("6300", fields.path("budget").asText());
      }
      assertTrue(
          f.runtime
              .studio
              .experiment(f.alice(), evaluated.path("lastExperiment").path("id").asText())
              .path("passed")
              .asBoolean());
      ObjectNode numericInput = input.deepCopy();
      ((ObjectNode) numericInput.path("fields")).put("budget", 6300);
      assertEquals(
          400,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.run(
                          f.alice(),
                          "app-a-credential",
                          "alice-token",
                          "structured-app",
                          "preview",
                          "numeric-input-reject",
                          StudioService.etag(evaluated),
                          Json.object().set("input", numericInput)))
              .status);
      assertEquals(
          2,
          f.modelRequests.size(),
          "Unsupported numeric fields fail before calling a model, never disappear silently");
    }
  }

  @Test
  void privatePreviewCannotBeReusedThroughRawRunApiAndRetriesCreateOnlyOneRun() throws Exception {
    try (var f = new Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-preview-01");
      JsonNode body = Json.object().set("input", Json.object().put("question", "fixture question"));
      JsonNode first =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "assistant",
              "preview",
              "preview-command-01",
              StudioService.etag(saved),
              body);
      JsonNode replay =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "assistant",
              "preview",
              "preview-command-01",
              StudioService.etag(saved),
              body);
      assertEquals(ApiJson.canonical(first), ApiJson.canonical(replay));
      assertEquals(1, f.runCount());
      var task = first.path("lastTask");
      String run = task.path("runId").asText();
      JsonNode raw =
          Json.object().set("releaseRef", f.runtime.repository.owned(run).release().reference());
      ((ObjectNode) raw).set("inputs", body.path("input"));
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.bob(), "app-a-credential", "bob-token", "raw-stolen-preview", raw))
              .status);
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class, () -> f.runtime.studio.task(f.bob(), task.path("id").asText()))
              .status);
      assertEquals(0, f.runtime.studio.tasks(f.bob(), null).path("items").size());
      assertEquals(1, f.runCount());
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.run(
                          f.alice(),
                          "app-a-credential",
                          "alice-token",
                          "assistant",
                          "preview",
                          "preview-command-01",
                          StudioService.etag(saved),
                          Json.object().set("input", Json.object().put("question", "different"))))
              .status);
      RunState finished = f.drive(run);
      assertEquals(RunStatus.COMPLETED, finished.status, finished.stopReason);
      JsonNode visible = f.runtime.studio.task(f.alice(), task.path("id").asText());
      assertEquals("AVAILABLE", visible.path("run").path("output").path("visibility").asText());
      assertEquals(1, visible.path("artifacts").size());
      assertEquals(1, f.modelCalls.get());
    }
  }

  @Test
  void publicationRequiresCurrentPassingEvaluationAndCurrentOutputAccess() throws Exception {
    try (var f = new Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-evaluation-01");
      JsonNode evaluation = f.evaluate("assistant", saved, "evaluate-command-01");
      String experiment = evaluation.path("lastExperiment").path("id").asText();
      JsonNode publish = Json.object().put("reviewConfirmed", true).put("evaluationId", experiment);
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.publish(
                          f.alice(),
                          "assistant",
                          "publish-pending-01",
                          StudioService.etag(evaluation),
                          publish))
              .status);
      f.driveAll();
      assertTrue(f.runtime.studio.experiment(f.alice(), experiment).path("passed").asBoolean());
      assertEquals(
          404,
          assertThrows(ApiFailure.class, () -> f.runtime.studio.experiment(f.bob(), experiment))
              .status);
      f.identities.revoke("app-a", "project-a", "alice", "runs:output:read");
      assertFalse(f.runtime.studio.experiment(f.alice(), experiment).path("passed").asBoolean());
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.publish(
                          f.alice(),
                          "assistant",
                          "publish-hidden-01",
                          StudioService.etag(evaluation),
                          publish))
              .status);
      f.identities.grant("app-a", "project-a", "alice", Fixture.PERMISSIONS);
      JsonNode released =
          f.runtime.studio.publish(
              f.alice(),
              "assistant",
              "publish-complete-01",
              StudioService.etag(evaluation),
              publish);
      assertEquals(1, released.path("defaultVersion").asInt());
      JsonNode changed =
          f.save(
              "assistant",
              f.draft().put("instructions", "Changed instructions require another evaluation"),
              StudioService.etag(released),
              "save-new-draft-01");
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.publish(
                          f.alice(),
                          "assistant",
                          "publish-stale-eval",
                          StudioService.etag(changed),
                          publish))
              .status);
      f.identities.revoke("app-a", "project-a", "alice", "catalog:publish");
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.studio.publish(
                          f.alice(),
                          "assistant",
                          "publish-revoked-01",
                          StudioService.etag(changed),
                          publish))
              .status);
      assertEquals(
          1, f.runtime.studio.application(f.alice(), "assistant").path("publishedVersions").size());
    }
  }

  @Test
  void concurrentEditsUseCasAndFailedEditsLeaveNoReceiptOrPartialState() throws Exception {
    try (var f = new Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-concurrent-01");
      assertEquals(
          428,
          assertThrows(
                  ApiFailure.class, () -> f.save("assistant", f.draft(), null, "missing-match-01"))
              .status);
      var pool = Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      try {
        var first = pool.submit(() -> f.raceSave(start, saved, "first"));
        var second = pool.submit(() -> f.raceSave(start, saved, "second"));
        start.countDown();
        var outcomes =
            new HashSet<>(
                List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        assertEquals(Set.of(200, 412), outcomes);
        JsonNode latest = f.runtime.studio.application(f.alice(), "assistant");
        assertEquals(2, latest.path("revision").asInt());
        assertEquals(0, f.runCount());
        assertEquals(0, f.modelCalls.get());
        assertEquals(
            404,
            assertThrows(
                    ApiFailure.class, () -> f.runtime.studio.command(f.alice(), "missing-match-01"))
                .status);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void revokedKnowledgeSuppressesFinishedOutputsArtifactsAndPendingExecution() throws Exception {
    try (var f = new Fixture()) {
      f.knowledge();
      JsonNode saved =
          f.save(
              "research",
              f.draft().put("knowledgeId", "private-notes"),
              "\"s0\"",
              "save-research-01");
      JsonNode first =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "research",
              "preview",
              "research-first-01",
              StudioService.etag(saved),
              Json.object().set("input", Json.object().put("question", "fixture knowledge")));
      String firstTask = first.path("lastTask").path("id").asText(),
          firstRun = first.path("lastTask").path("runId").asText();
      assertEquals(RunStatus.COMPLETED, f.drive(firstRun).status);
      JsonNode before = f.runtime.studio.task(f.alice(), firstTask);
      String artifact = before.path("artifacts").get(0).path("id").asText();
      assertEquals(
          "trusted fixture answer",
          f.runtime.studio.artifact(f.alice(), firstTask, artifact).content());
      JsonNode pending =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "research",
              "preview",
              "research-second-01",
              StudioService.etag(first),
              Json.object().set("input", Json.object().put("question", "fixture knowledge")));
      int calls = f.modelCalls.get();
      f.runtime.knowledge.saveDocument(
          f.alice(),
          "private-notes",
          "source",
          "revoke-document-01",
          "\"k1\"",
          Json.object()
              .put("title", "Fixture note")
              .put("text", "fixture knowledge contains a controlled fact")
              .put("revoked", true));
      JsonNode hidden = f.runtime.studio.task(f.alice(), firstTask);
      assertEquals("OMITTED", hidden.path("run").path("output").path("visibility").asText());
      assertEquals(0, hidden.path("artifacts").size());
      assertFalse(hidden.has("input"));
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class, () -> f.runtime.studio.artifact(f.alice(), firstTask, artifact))
              .status);
      RunState blocked = f.drive(pending.path("lastTask").path("runId").asText());
      assertEquals(RunStatus.NEEDS_ATTENTION, blocked.status);
      assertEquals("PLATFORM_CURRENT_ACCESS_DENIED", blocked.stopReason);
      assertEquals(
          calls, f.modelCalls.get(), "Revoked evidence must not reach a subsequent model call");
      assertEquals(
          "trusted fixture answer",
          f.runtime.store.get(firstRun).output.asText(),
          "Projection must preserve the durable audit result");
    }
  }

  @Test
  void evaluationRetainsHumanReviewAndNeverManufacturesApproval() throws Exception {
    try (var f = new Fixture()) {
      ObjectNode draft = f.draft().put("requireReview", true);
      draft.putArray("approvers").add("app-a/reviewer");
      JsonNode saved = f.save("reviewed", draft, "\"s0\"", "save-reviewed-01");
      JsonNode evaluated = f.evaluate("reviewed", saved, "evaluate-review-01");
      f.driveAll();
      String experiment = evaluated.path("lastExperiment").path("id").asText();
      JsonNode result = f.runtime.studio.experiment(f.alice(), experiment);
      assertFalse(result.path("passed").asBoolean());
      assertEquals(0, f.modelCalls.get());
      String run = result.path("rows").get(0).path("candidate").path("runId").asText();
      assertEquals(RunStatus.WAITING_INPUT, f.runtime.store.get(run).status);
      var reviewer =
          f.runtime.service.authenticate("project-a", "app-a-credential", "reviewer-token");
      var approval = f.runtime.service.approval(reviewer, run);
      f.runtime.service.decide(
          reviewer,
          run,
          approval.body().path("id").asText(),
          "review-decision-01",
          approval.etag(),
          Json.object()
              .put("digest", approval.body().path("digest").asText())
              .put("decision", "APPROVE")
              .put("reason", "Explicit fixture review")
              .put("input", "Proceed using the controlled fixture"));
      assertEquals(RunStatus.COMPLETED, f.drive(run).status);
      assertTrue(f.runtime.studio.experiment(f.alice(), experiment).path("passed").asBoolean());
      assertEquals(1, f.modelCalls.get());
    }
  }

  @Test
  void publishedConsumerDoesNotSeeUnpublishedDraftAndDisableOnlyRejectsNewRuns() throws Exception {
    try (var f = new Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-consumer-01");
      JsonNode evaluated = f.evaluate("assistant", saved, "evaluate-consumer-01");
      f.driveAll();
      JsonNode released =
          f.runtime.studio.publish(
              f.alice(),
              "assistant",
              "publish-consumer-01",
              StudioService.etag(evaluated),
              Json.object()
                  .put("reviewConfirmed", true)
                  .put("evaluationId", evaluated.path("lastExperiment").path("id").asText()));
      JsonNode changed =
          f.save(
              "assistant",
              f.draft().put("instructions", "UNPUBLISHED_CONTROLLED_DRAFT"),
              StudioService.etag(released),
              "save-private-draft");
      f.identities.grant(
          "app-a",
          "project-a",
          "bob",
          Set.of("runs:create", "runs:read", "runs:list", "runs:output:read", "model:invoke"));
      JsonNode response =
          f.runtime.studio.run(
              f.bob(),
              "app-a-credential",
              "bob-token",
              "assistant",
              "run",
              "consumer-create-01",
              StudioService.etag(changed),
              Json.object().set("input", Json.object().put("question", "fixture question")));
      assertFalse(
          Json.write(response).contains("UNPUBLISHED_CONTROLLED_DRAFT"),
          "Run-only consumer must not see an author's unpublished draft");
      assertFalse(response.has("samples"));
      String run = response.path("lastTask").path("runId").asText();
      JsonNode current = f.runtime.studio.application(f.alice(), "assistant");
      f.runtime.studio.configure(
          f.alice(),
          "assistant",
          "disable",
          "disable-application",
          StudioService.etag(current),
          Json.object().put("disabled", true));
      assertEquals(
          RunStatus.COMPLETED,
          f.drive(run).status,
          "Ordinary app disable preserves an already accepted immutable run");
      var raw = Json.object();
      raw.set("releaseRef", f.runtime.repository.owned(run).release().reference());
      raw.set("inputs", Json.object().put("question", "fixture question"));
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.bob(), "app-a-credential", "bob-token", "disabled-new-run", raw))
              .status);
      assertEquals(2, f.runCount());
    }
  }

  @Test
  void evaluationComparesImmutableBaselineAndCandidateAndCanRestorePublishedDefault()
      throws Exception {
    try (var f = new Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-baseline-01");
      JsonNode first = f.evaluate("assistant", saved, "evaluate-baseline-01");
      f.driveAll();
      JsonNode release =
          f.runtime.studio.publish(
              f.alice(),
              "assistant",
              "publish-baseline-01",
              StudioService.etag(first),
              Json.object()
                  .put("reviewConfirmed", true)
                  .put("evaluationId", first.path("lastExperiment").path("id").asText()));
      JsonNode changed =
          f.save(
              "assistant",
              f.draft().put("instructions", "New reviewed instructions"),
              StudioService.etag(release),
              "save-candidate-01");
      JsonNode next = f.evaluate("assistant", changed, "evaluate-comparison");
      f.driveAll();
      JsonNode comparison =
          f.runtime.studio.experiment(f.alice(), next.path("lastExperiment").path("id").asText());
      JsonNode row = comparison.path("rows").get(0);
      assertTrue(row.path("candidate").path("passed").asBoolean());
      assertTrue(row.path("baseline").path("passed").asBoolean());
      assertNotEquals(row.path("candidate").path("runId"), row.path("baseline").path("runId"));
      assertEquals(3, f.modelCalls.get());
      JsonNode second =
          f.runtime.studio.publish(
              f.alice(),
              "assistant",
              "publish-candidate-01",
              StudioService.etag(next),
              Json.object()
                  .put("reviewConfirmed", true)
                  .put("evaluationId", comparison.path("id").asText()));
      JsonNode restored =
          f.runtime.studio.configure(
              f.alice(),
              "assistant",
              "default",
              "restore-default-01",
              StudioService.etag(second),
              Json.object().put("version", 1));
      assertEquals(1, restored.path("defaultVersion").asInt());
      assertEquals(2, restored.path("publishedVersions").size());
    }
  }

  static final class Fixture implements AutoCloseable {
    static final Set<String> PERMISSIONS =
        Set.of(
            "catalog:read",
            "catalog:write",
            "catalog:validate",
            "catalog:publish",
            "runs:create",
            "runs:read",
            "runs:list",
            "runs:events:read",
            "runs:control",
            "runs:output:read",
            "model:invoke",
            "tool:studio:knowledge");
    final HttpServer server;
    final AtomicInteger modelCalls = new AtomicInteger();
    final List<JsonNode> modelRequests = new CopyOnWriteArrayList<>();
    final PlatformTestSupport.TestIdentities identities = new PlatformTestSupport.TestIdentities();
    final JdbcDataSource data = new JdbcDataSource();
    PlatformRuntime runtime;

    Fixture() throws Exception {
      this("openai-compatible");
    }

    Fixture(String modelKind) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            try (exchange) {
              modelRequests.add(Json.MAPPER.readTree(exchange.getRequestBody()));
              modelCalls.incrementAndGet();
              byte[] response =
                  "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"trusted fixture answer\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            }
          });
      server.start();
      for (String subject : List.of("alice", "bob"))
        identities.grant("app-a", "project-a", subject, PERMISSIONS);
      identities.grant(
          "app-a",
          "project-a",
          "reviewer",
          Set.of("approvals:read", "approvals:decide", "approvals:review"));
      var profile =
          new ModelProfile(
              "fixture-profile",
              "fixture",
              "configured-fixture-model",
              16000,
              256,
              3000,
              Json.object());
      var prompt =
          new PromptTemplate(
              "seed",
              "1",
              "A controlled fixture",
              "{{question}}",
              Map.of("question", PromptTemplate.VariableType.STRING));
      var workflow =
          new WorkflowDefinition(
              "seed",
              "1",
              "answer",
              10,
              Map.of(
                  "answer",
                  new WorkflowDefinition.Model(
                      prompt, Map.of("question", "question"), "answer", "end"),
                  "end",
                  new WorkflowDefinition.End("answer")));
      var release =
          new Deployment.Release(
              "project-a",
              "seed",
              UUID.randomUUID().toString(),
              workflow,
              profile,
              StudioCompiler.inputSchema("research"),
              Json.object().put("type", "string"),
              Set.of(),
              Set.of("model:invoke"),
              Set.of(),
              List.of(),
              Json.object().put("type", "string"),
              true,
              new Deployment.Limits(12000, 4, 8, 60, 1800));
      var model =
          Json.object()
              .put("provider", "fixture")
              .put("kind", modelKind)
              .put(
                  "endpoint",
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")
              .put("secretRef", "env:MODEL");
      var deployment =
          new Deployment(
              "https://identity.invalid",
              "env:SIGNING",
              Map.of("app-a", "env:APP"),
              List.of(
                  new Deployment.Project(
                      "project-a", Set.of("app-a"), 100, 10000000, 100, 10000000, 2)),
              List.of(release),
              Set.of(),
              List.of(),
              List.of(model),
              2,
              10000,
              false);
      data.setURL(
          "jdbc:h2:mem:studio-"
              + UUID.randomUUID()
              + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
      data.setUser("synthetic-studio");
      data.setPassword("synthetic-studio-password");
      runtime =
          new PlatformRuntime(
              data,
              deployment,
              reference ->
                  reference.equals("env:APP")
                      ? "app-a-credential"
                      : "synthetic-local-fixture-signing-and-model-secret-0123456789",
              identities);
    }

    IdentityProvider.Principal alice() {
      return runtime.service.authenticate("project-a", "app-a-credential", "alice-token");
    }

    IdentityProvider.Principal bob() {
      return runtime.service.authenticate("project-a", "app-a-credential", "bob-token");
    }

    ObjectNode draft() {
      var value =
          Json.object()
              .put("name", "Fixture assistant")
              .put("templateId", "research")
              .put("instructions", "Return an accurate fixture answer")
              .put("modelProfileId", "fixture-profile");
      var sample =
          value
              .putArray("samples")
              .addObject()
              .put("id", "sample-one")
              .put("name", "Fixture answer");
      sample.set("input", Json.object().put("question", "fixture question"));
      sample.putArray("expectedContains").add("fixture answer");
      return value;
    }

    JsonNode save(String id, JsonNode body, String match, String key) {
      return runtime.studio.save(alice(), id, key, match, body);
    }

    JsonNode evaluate(String id, JsonNode saved, String key) {
      return runtime.studio.evaluate(
          alice(),
          "app-a-credential",
          "alice-token",
          id,
          key,
          StudioService.etag(saved),
          Json.object());
    }

    int raceSave(CountDownLatch start, JsonNode previous, String side) throws Exception {
      start.await();
      try {
        save(
            "assistant",
            draft().put("name", side),
            StudioService.etag(previous),
            "concurrent-" + side);
        return 200;
      } catch (ApiFailure failure) {
        return failure.status;
      }
    }

    RunState drive(String id) throws Exception {
      RunState run = runtime.store.get(id);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (Set.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(run.status)) {
        if (System.nanoTime() > until) fail("Fixture run did not settle: " + run.status);
        run = runtime.harness.tick(id);
        if (Set.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(run.status)) Thread.sleep(5);
      }
      return run;
    }

    void driveAll() throws Exception {
      for (JsonNode task : runtime.studio.tasks(alice(), null).path("items"))
        drive(task.path("runId").asText());
    }

    long runCount() {
      return runtime.repository.transaction(
          c -> {
            try (var statement = c.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM platform_runs")) {
              rows.next();
              return rows.getLong(1);
            }
          });
    }

    void knowledge() {
      var group =
          Json.object()
              .put("name", "Private fixture notes")
              .put("visibility", "PROJECT")
              .put("disabled", false);
      group.putArray("allowedSubjects").add("alice");
      runtime.knowledge.saveCollection(
          alice(), "private-notes", "create-knowledge-01", "\"k0\"", group);
      runtime.knowledge.saveDocument(
          alice(),
          "private-notes",
          "source",
          "create-document-01",
          "\"k0\"",
          Json.object()
              .put("title", "Fixture note")
              .put("text", "fixture knowledge contains a controlled fact")
              .put("revoked", false));
    }

    public void close() {
      runtime.close();
      server.stop(0);
    }
  }
}
