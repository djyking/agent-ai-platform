package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Wire contract and deployment-binding drift, complementing the real workflow lifecycle tests. */
final class StudioHttpContractTest {
  private static final String BASE = "/v1/projects/project-a/studio";

  private MockMvc mvc(StudioLifecycleSecurityTest.Fixture f) {
    return MockMvcBuilders.standaloneSetup(
            new StudioController(f.runtime.service, f.runtime.studio))
        .setControllerAdvice(new ApiErrors())
        .addFilters(new ApiErrors.RequestHeaders())
        .build();
  }

  private MvcResult call(
      MockMvc mvc, String subject, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    MvcResult result =
        mvc.perform(
                request
                    .header("Authorization", "Bearer app-a-credential")
                    .header("X-Harness-User-Token", subject + "-token"))
            .andReturn();
    assertEquals(
        expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    return result;
  }

  private MockHttpServletRequestBuilder save(String app, String key, String match, JsonNode body) {
    var request =
        put(BASE + "/applications/" + app)
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .content(Json.write(body));
    if (match != null) request.header("If-Match", match);
    return request;
  }

  @Test
  void templatesApplicationsReceiptsAndCasHaveExactHttpScopes() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      MockMvc mvc = mvc(f);
      var templates =
          Json.read(
                  call(mvc, "alice", get(BASE + "/templates"), 200)
                      .getResponse()
                      .getContentAsString())
              .path("items");
      Set<String> ids = new HashSet<>();
      templates.forEach(t -> ids.add(t.path("id").asText()));
      assertEquals(Set.of("knowledge", "research", "structured"), ids);
      var capabilities =
          Json.read(
              call(mvc, "alice", get(BASE + "/capabilities"), 200)
                  .getResponse()
                  .getContentAsString());
      assertEquals("fixture-profile", capabilities.path("models").get(0).path("id").asText());
      MvcResult created =
          call(mvc, "alice", save("research", "http-create-app-01", "\"s0\"", f.draft()), 200);
      assertEquals("\"s1\"", created.getResponse().getHeader("ETag"));
      assertEquals(
          created.getResponse().getContentAsString(),
          call(mvc, "alice", save("research", "http-create-app-01", "\"s0\"", f.draft()), 200)
              .getResponse()
              .getContentAsString());
      var receipt =
          Json.read(
              call(mvc, "alice", get(BASE + "/commands/http-create-app-01"), 200)
                  .getResponse()
                  .getContentAsString());
      assertEquals("COMPLETED", receipt.path("status").asText());
      assertEquals("research", receipt.path("resourceId").asText());
      assertEquals("save", receipt.path("operation").asText());
      call(mvc, "bob", get(BASE + "/commands/http-create-app-01"), 404);
      call(mvc, "alice", save("another", "http-create-app-01", "\"s0\"", f.draft()), 409);
      call(mvc, "alice", save("research", "http-conflict-01", "\"s0\"", f.draft()), 412);
      call(mvc, "alice", save("research", "http-missing-cas", null, f.draft()), 428);
      call(mvc, "alice", get(BASE + "/commands/http-conflict-01"), 404);
      assertEquals(0, f.runCount());
      assertEquals(0, f.modelCalls.get());
    }
  }

  @Test
  void strictRequestBodiesDuplicateHeadersAndFreshPermissionRevocationAreEnforced()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      MockMvc mvc = mvc(f);
      call(
          mvc,
          "alice",
          save(
              "bad-field",
              "http-extra-field",
              "\"s0\"",
              f.draft().put("endpoint", "http://169.254.169.254")),
          422);
      String duplicate =
          Json.write(f.draft())
              .replace(
                  "\"templateId\":\"research\"",
                  "\"templateId\":\"research\",\"templateId\":\"structured\"");
      call(
          mvc,
          "alice",
          put(BASE + "/applications/duplicate")
              .contentType("application/json")
              .header("Idempotency-Key", "http-duplicate-json")
              .header("If-Match", "\"s0\"")
              .content(duplicate),
          400);
      call(
          mvc,
          "alice",
          save("duplicate-header", "http-duplicate-header", "\"s0\"", f.draft())
              .header("If-Match", "\"s0\""),
          400);
      call(
          mvc,
          "alice",
          save("typed", "http-wrong-type", "\"s0\"", f.draft().put("requireReview", "false")),
          400);
      f.identities.revoke("app-a", "project-a", "alice", "catalog:write");
      call(mvc, "alice", save("revoked", "http-permission-revoked", "\"s0\"", f.draft()), 403);
      assertTrue(f.runtime.studio.applications(f.alice()).path("items").isEmpty());
      assertEquals(0, f.runCount());
    }
  }

  @Test
  void httpPreviewAndArtifactUseRealRunAndOwnerPermissions() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      MockMvc mvc = mvc(f);
      call(mvc, "alice", save("research", "http-save-preview", "\"s0\"", f.draft()), 200);
      var preview =
          call(
              mvc,
              "alice",
              post(BASE + "/applications/research/preview")
                  .contentType("application/json")
                  .header("Idempotency-Key", "http-preview-command")
                  .header("If-Match", "\"s1\"")
                  .content("{\"input\":{\"question\":\"fixture question\"}}"),
              200);
      JsonNode task = Json.read(preview.getResponse().getContentAsString()).path("lastTask");
      f.drive(task.path("runId").asText());
      JsonNode rendered =
          Json.read(
              call(mvc, "alice", get(BASE + "/tasks/" + task.path("id").asText()), 200)
                  .getResponse()
                  .getContentAsString());
      String artifact = rendered.path("artifacts").get(0).path("id").asText();
      String path = BASE + "/tasks/" + task.path("id").asText() + "/artifacts/" + artifact;
      MvcResult downloaded = call(mvc, "alice", get(path), 200);
      assertEquals("trusted fixture answer", downloaded.getResponse().getContentAsString());
      assertTrue(
          downloaded.getResponse().getHeader("Content-Disposition").startsWith("attachment;"));
      call(mvc, "bob", get(path), 404);
      call(
          mvc,
          "alice",
          get(BASE + "/tasks/" + task.path("id").asText() + "/artifacts/forged"),
          404);
      f.identities.revoke("app-a", "project-a", "alice", "runs:output:read");
      call(mvc, "alice", get(path), 404);
      assertEquals(1, f.modelCalls.get());
    }
  }

  @Test
  void providerBindingChangeCannotReusePassingEvaluationOrExecuteOldPreview() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      JsonNode saved = f.save("research", f.draft(), "\"s0\"", "binding-save-app");
      JsonNode evaluated = f.evaluate("research", saved, "binding-evaluate-app");
      f.driveAll();
      String experiment = evaluated.path("lastExperiment").path("id").asText();
      JsonNode result = f.runtime.studio.experiment(f.alice(), experiment);
      assertTrue(result.path("passed").asBoolean());
      String previousRun = result.path("rows").get(0).path("candidate").path("runId").asText();
      String previousRelease = f.runtime.repository.owned(previousRun).release().releaseId();
      Deployment old = f.runtime.deployment;
      List<JsonNode> changedModels =
          old.models().stream()
              .map(
                  m -> {
                    ObjectNode copy = m.deepCopy();
                    copy.put(
                        "endpoint",
                        m.path("endpoint")
                            .asText()
                            .replace("/chat/completions", "/changed/chat/completions"));
                    return (JsonNode) copy;
                  })
              .toList();
      Deployment changed =
          new Deployment(
              old.identityOrigin(),
              old.signingSecret(),
              old.applicationSecrets(),
              old.projects(),
              old.releases(),
              old.disabledReleases(),
              old.tools(),
              changedModels,
              old.concurrency(),
              old.leaseMillis(),
              false);
      f.runtime.close();
      f.runtime =
          new PlatformRuntime(
              f.data,
              changed,
              ref ->
                  ref.equals("env:APP")
                      ? "app-a-credential"
                      : "synthetic-local-fixture-signing-and-model-secret-0123456789",
              f.identities);
      JsonNode body = Json.object().put("reviewConfirmed", true).put("evaluationId", experiment);
      ApiFailure failure =
          assertThrows(
              ApiFailure.class,
              () ->
                  f.runtime.studio.publish(
                      f.alice(),
                      "research",
                      "binding-stale-publish",
                      StudioService.etag(evaluated),
                      body));
      assertEquals(409, failure.status);
      assertEquals("PASSING_CURRENT_EVALUATION_REQUIRED", failure.code);
      assertEquals(
          "STUDIO_BINDING_CHANGED",
          assertThrows(
                  ApiFailure.class,
                  () -> f.runtime.studio.requireExecution("project-a", previousRelease))
              .code);
      assertEquals(
          0, f.runtime.studio.application(f.alice(), "research").path("publishedVersions").size());
      assertEquals(1, f.modelCalls.get());
    }
  }

  @Test
  void deniedKnowledgeAndDisabledModelsDoNotPoisonNestedCapabilitiesProjection() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      f.knowledge();
      var acl =
          Json.object()
              .put("name", "Private fixture notes")
              .put("visibility", "PROJECT")
              .put("disabled", false);
      acl.putArray("allowedSubjects").add("bob");
      f.runtime.knowledge.saveCollection(
          f.alice(), "private-notes", "projection-revoke-acl", "\"k1\"", acl);
      f.runtime.capabilities.save(
          f.alice(),
          "model",
          "fixture",
          "projection-disable-model",
          "\"k0\"",
          Json.object().put("name", "Fixture model").put("enabled", false));
      JsonNode choices =
          f.runtime.repository.transaction(c -> f.runtime.studio.capabilities(f.alice()));
      assertTrue(choices.path("models").isEmpty());
      assertTrue(choices.path("knowledge").isEmpty());
      assertEquals(0, f.modelCalls.get());
    }
  }

  @Test
  void evaluationAndTaskListsDoNotReintroduceHiddenInputsAfterSourceRevocation() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      f.knowledge();
      JsonNode saved =
          f.save(
              "research",
              f.draft().put("knowledgeId", "private-notes"),
              "\"s0\"",
              "projection-save-research");
      JsonNode evaluated = f.evaluate("research", saved, "projection-evaluate-research");
      f.driveAll();
      JsonNode tasks = f.runtime.studio.tasks(f.alice(), "research").path("items");
      assertEquals("fixture question", tasks.get(0).path("input").path("question").asText());
      f.runtime.knowledge.saveDocument(
          f.alice(),
          "private-notes",
          "source",
          "projection-revoke-source",
          "\"k1\"",
          Json.object()
              .put("title", "Fixture note")
              .put("text", "fixture knowledge contains a controlled fact")
              .put("revoked", true));
      JsonNode result =
          f.runtime.studio.experiment(
              f.alice(), evaluated.path("lastExperiment").path("id").asText());
      assertFalse(result.path("passed").asBoolean());
      JsonNode row = result.path("rows").get(0);
      assertFalse(row.has("input"));
      assertFalse(row.has("expectedContains"));
      assertFalse(row.path("candidate").has("output"));
      JsonNode after = f.runtime.studio.tasks(f.alice(), "research").path("items").get(0);
      assertFalse(after.has("input"));
      assertTrue(after.path("artifacts").isEmpty());
      assertEquals("OMITTED", after.path("run").path("output").path("visibility").asText());
    }
  }
}
