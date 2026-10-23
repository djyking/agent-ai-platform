package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Contracts.RunStatus;
import io.github.djyking.harness.core.Json;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StudioReleaseDiscoveryTest {
  @Test
  void onlyPublishedFrozenVersionsAreDiscoverableAndUsableByIndependentConsumers()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "discovery-save-01");
      hidden(f, "assistant");
      JsonNode preview =
          f.runtime.studio.run(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "assistant",
              "preview",
              "discovery-preview-01",
              StudioService.etag(saved),
              Json.object().set("input", Json.object().put("question", "fixture question")));
      hidden(f, "assistant");
      ObjectNode authorPreview = Json.object();
      authorPreview.set(
          "releaseRef",
          f.runtime
              .repository
              .owned(preview.path("lastTask").path("runId").asText())
              .release()
              .reference());
      authorPreview.set("inputs", Json.object().put("question", "Author preview"));
      ObjectNode publishedOnlyPreview = authorPreview.deepCopy().put("requirePublished", true);
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.alice(),
                          "app-a-credential",
                          "alice-token",
                          "published-preview-denied",
                          publishedOnlyPreview))
              .status);
      JsonNode allowedPreview =
          f.runtime.service.create(
              f.alice(),
              "app-a-credential",
              "alice-token",
              "ordinary-author-preview",
              authorPreview);
      assertEquals(
          RunStatus.COMPLETED, f.drive(allowedPreview.path("run").path("id").asText()).status);
      JsonNode evaluated = f.evaluate("assistant", preview, "discovery-evaluate-01");
      hidden(f, "assistant");
      JsonNode experiment =
          f.runtime.studio.experiment(
              f.alice(), evaluated.path("lastExperiment").path("id").asText());
      String evaluationRun =
          experiment.path("rows").get(0).path("candidate").path("runId").asText();
      ObjectNode evaluationRequest = authorPreview.deepCopy().put("requirePublished", true);
      evaluationRequest.set(
          "releaseRef", f.runtime.repository.owned(evaluationRun).release().reference());
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.alice(),
                          "app-a-credential",
                          "alice-token",
                          "published-evaluation-denied",
                          evaluationRequest))
              .status);
      f.driveAll();
      hidden(f, "assistant");
      JsonNode published = publish(f, "assistant", evaluated);
      JsonNode discovered = f.runtime.catalog.defaultRelease(f.bob(), "assistant");
      assertEquals(
          published.path("publishedVersions").get(0).path("releaseRef"),
          discovered.path("releaseRef"));
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      assertTrue(discovered.path("isDefault").asBoolean());
      assertEquals("Fixture assistant", discovered.path("name").asText());

      JsonNode changed =
          f.save(
              "assistant",
              f.draft().put("name", "PRIVATE_DRAFT").put("instructions", "PRIVATE_PROMPT"),
              StudioService.etag(published),
              "discovery-edit-draft");
      assertEquals(discovered, f.runtime.catalog.defaultRelease(f.bob(), "assistant"));
      assertFalse(f.runtime.catalog.releases(f.bob()).toString().contains("PRIVATE_"));
      f.identities.grant(
          "app-a",
          "project-a",
          "bob",
          Set.of("runs:create", "runs:read", "runs:output:read", "model:invoke"));
      assertEquals(discovered, f.runtime.catalog.defaultRelease(f.bob(), "assistant"));
      ObjectNode request = Json.object().put("requirePublished", true);
      request.set("releaseRef", discovered.path("releaseRef"));
      request.set("inputs", Json.object().put("question", "Independent consumer"));
      JsonNode accepted =
          f.runtime.service.create(
              f.bob(), "app-a-credential", "bob-token", "discovery-run-01", request);
      assertEquals(RunStatus.COMPLETED, f.drive(accepted.path("run").path("id").asText()).status);
      f.runtime.studio.configure(
          f.alice(),
          "assistant",
          "disable",
          "receipt-disable-app",
          StudioService.etag(changed),
          Json.object().put("disabled", true));
      assertEquals(
          accepted,
          f.runtime.service.create(
              f.bob(), "app-a-credential", "bob-token", "discovery-run-01", request));
      assertEquals(
          404,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.bob(), "app-a-credential", "bob-token", "retired-new-key", request))
              .status);
      ObjectNode changedSemantics = request.deepCopy().put("requirePublished", false);
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.bob(),
                          "app-a-credential",
                          "bob-token",
                          "discovery-run-01",
                          changedSemantics))
              .status);
      changedSemantics.remove("requirePublished");
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.create(
                          f.bob(),
                          "app-a-credential",
                          "bob-token",
                          "discovery-run-01",
                          changedSemantics))
              .status);
    }
  }

  @Test
  void currentSourceAclAndExecutionPermissionsApplyEvenToCatalogReaders() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      f.knowledge();
      JsonNode saved =
          f.save(
              "research",
              f.draft().put("knowledgeId", "private-notes"),
              "\"s0\"",
              "discovery-private-save");
      JsonNode evaluated = f.evaluate("research", saved, "discovery-private-eval");
      f.driveAll();
      publish(f, "research", evaluated);
      hidden(f, "research");
      ObjectNode acl =
          Json.object()
              .put("name", "Private fixture notes")
              .put("visibility", "PROJECT")
              .put("disabled", false);
      acl.putArray("allowedSubjects").add("alice").add("bob");
      f.runtime.knowledge.saveCollection(
          f.alice(), "private-notes", "discovery-acl-grant", "\"k1\"", acl);
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      f.identities.revoke("app-a", "project-a", "bob", "model:invoke");
      hidden(f, "research");
      f.identities.grant(
          "app-a", "project-a", "bob", StudioLifecycleSecurityTest.Fixture.PERMISSIONS);
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      f.runtime.knowledge.saveDocument(
          f.alice(),
          "private-notes",
          "source",
          "discovery-source-revoke",
          "\"k1\"",
          Json.object()
              .put("title", "Fixture note")
              .put("text", "fixture knowledge contains a controlled fact")
              .put("revoked", true));
      hidden(f, "research");
    }
  }

  @Test
  void disabledApplicationsProvidersAndChangedBindingsAreNotAdvertised() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "discovery-disable-save");
      JsonNode evaluated = f.evaluate("assistant", saved, "discovery-disable-eval");
      f.driveAll();
      JsonNode published = publish(f, "assistant", evaluated);
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      JsonNode disabled =
          f.runtime.studio.configure(
              f.alice(),
              "assistant",
              "disable",
              "discovery-disable-app",
              StudioService.etag(published),
              Json.object().put("disabled", true));
      hidden(f, "assistant");
      f.runtime.studio.configure(
          f.alice(),
          "assistant",
          "disable",
          "discovery-enable-app",
          StudioService.etag(disabled),
          Json.object().put("disabled", false));
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      f.runtime.capabilities.save(
          f.alice(),
          "model",
          "fixture",
          "discovery-disable-model",
          "\"k0\"",
          Json.object().put("name", "Fixture model").put("enabled", false));
      hidden(f, "assistant");
      f.runtime.capabilities.save(
          f.alice(),
          "model",
          "fixture",
          "discovery-enable-model",
          "\"k1\"",
          Json.object().put("name", "Fixture model").put("enabled", true));
      assertEquals(1, f.runtime.catalog.releases(f.bob()).path("items").size());
      String releaseId =
          published.path("publishedVersions").get(0).path("releaseRef").path("releaseId").asText();
      f.runtime.repository.transaction(
          c -> {
            var documents = new StudioRepository(f.runtime.repository);
            ObjectNode snapshot = documents.get(c, "project-a", "SNAPSHOT", releaseId, true);
            snapshot.put("bindingDigest", "changed-fixture-binding");
            documents.put(c, "project-a", "SNAPSHOT", releaseId, snapshot);
            return null;
          });
      hidden(f, "assistant");
    }
  }

  private static JsonNode publish(
      StudioLifecycleSecurityTest.Fixture f, String id, JsonNode evaluated) {
    return f.runtime.studio.publish(
        f.alice(),
        id,
        "discovery-publish-" + id,
        StudioService.etag(evaluated),
        Json.object()
            .put("reviewConfirmed", true)
            .put("evaluationId", evaluated.path("lastExperiment").path("id").asText()));
  }

  private static void hidden(StudioLifecycleSecurityTest.Fixture f, String id) {
    assertEquals(0, f.runtime.catalog.releases(f.bob()).path("items").size());
    assertEquals(
        404,
        assertThrows(ApiFailure.class, () -> f.runtime.catalog.defaultRelease(f.bob(), id)).status);
  }
}
