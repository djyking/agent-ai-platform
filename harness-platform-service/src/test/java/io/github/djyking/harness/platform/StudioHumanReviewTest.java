package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.RunStatus;
import java.util.*;
import org.junit.jupiter.api.Test;

class StudioHumanReviewTest {
  private IdentityProvider.Principal reviewer(StudioLifecycleSecurityTest.Fixture f) {
    return f.runtime.service.authenticate("project-a", "app-a-credential", "reviewer-token");
  }

  private String start(StudioLifecycleSecurityTest.Fixture f, boolean knowledge) {
    ObjectNode draft = f.draft().put("requireReview", true);
    draft.putArray("approvers").add("app-a/reviewer");
    if (knowledge) draft.put("knowledgeId", "private-notes");
    JsonNode saved = f.save("reviewed", draft, "\"s0\"", "save-reviewed-context");
    var started =
        f.runtime.studio.run(
            f.alice(),
            "app-a-credential",
            "alice-token",
            "reviewed",
            "preview",
            "start-reviewed-context",
            StudioService.etag(saved),
            Json.object().set("input", Json.object().put("question", "fixture knowledge")));
    return started.path("lastTask").path("runId").asText();
  }

  private JsonNode decision(PlatformService.View approval) {
    return Json.object()
        .put("decision", "APPROVE")
        .put("digest", approval.body().path("digest").asText())
        .put("input", "审核完成；这条意见不能替换已经生成的答案")
        .put("reason", "Synthetic fixture review");
  }

  private JsonNode decide(
      StudioLifecycleSecurityTest.Fixture f, String run, PlatformService.View view, String key) {
    return f.runtime.service.decide(
        reviewer(f), run, view.body().path("id").asText(), key, view.etag(), decision(view));
  }

  @Test
  void generatedAnswerIsReviewedAndApprovalKeepsAnswerWithSameKeyReplayAfterCompletion()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      String id = start(f, false);
      assertEquals(RunStatus.WAITING_INPUT, f.drive(id).status);
      assertEquals(1, f.modelCalls.get());
      var unapprovedOutput = f.runtime.store.get(id).output;
      assertTrue(
          unapprovedOutput == null || unapprovedOutput.isNull(),
          "Unapproved answer is not a final output");
      var view = f.runtime.service.approval(reviewer(f), id);
      assertTrue(view.body().path("reviewComplete").asBoolean());
      assertEquals(
          "trusted fixture answer", view.body().path("fields").get(0).path("value").asText());
      assertEquals(1, view.body().path("fields").size());
      ApiJson.validate("ApprovalView", view.body());
      var forged = (ObjectNode) decision(view);
      forged.putObject("fields").put("answer", "forged replacement");
      assertEquals(
          422,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      f.runtime.service.decide(
                          reviewer(f),
                          id,
                          view.body().path("id").asText(),
                          "forged-fields-command",
                          view.etag(),
                          forged))
              .status);
      JsonNode accepted = decide(f, id, view, "approve-generated-answer");
      var finished = f.drive(id);
      assertEquals(RunStatus.COMPLETED, finished.status);
      assertEquals("trusted fixture answer", finished.output.asText());
      assertEquals(1, f.modelCalls.get(), "Approval cannot regenerate a different model answer");
      assertEquals(accepted, decide(f, id, view, "approve-generated-answer"));
    }
  }

  @Test
  void privateReviewUsesReviewersCurrentSourceAclAndRevocationInvalidatesTheExistingEtag()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      f.knowledge();
      f.identities.grant(
          "app-a",
          "project-a",
          "reviewer",
          Set.of(
              "approvals:read", "approvals:decide", "approvals:review", "tool:studio:knowledge"));
      String id = start(f, true);
      assertEquals(RunStatus.WAITING_INPUT, f.drive(id).status);
      var denied = f.runtime.service.approval(reviewer(f), id);
      assertFalse(denied.body().path("reviewComplete").asBoolean());
      assertTrue(denied.body().path("fields").get(0).path("masked").asBoolean());
      assertFalse(Json.write(denied.body()).contains("trusted fixture answer"));
      assertEquals(
          409,
          assertThrows(ApiFailure.class, () -> decide(f, id, denied, "deny-private-review"))
              .status);
      ObjectNode collection =
          Json.object()
              .put("name", "Private fixture notes")
              .put("visibility", "PROJECT")
              .put("disabled", false);
      collection.putArray("allowedSubjects").add("alice").add("reviewer");
      f.runtime.knowledge.saveCollection(
          f.alice(), "private-notes", "grant-reviewer-source", "\"k1\"", collection);
      var allowed = f.runtime.service.approval(reviewer(f), id);
      assertTrue(allowed.body().path("reviewComplete").asBoolean());
      assertFalse(allowed.body().path("fields").get(0).path("masked").asBoolean());
      assertEquals(
          404, assertThrows(ApiFailure.class, () -> f.runtime.service.get(reviewer(f), id)).status);
      long revision = f.runtime.store.get(id).revision;
      collection.putArray("allowedSubjects").add("alice");
      f.runtime.knowledge.saveCollection(
          f.alice(), "private-notes", "revoke-reviewer-source", "\"k2\"", collection);
      assertEquals(
          revision, f.runtime.store.get(id).revision, "Source ACL changes do not mutate the Run");
      assertEquals(
          412,
          assertThrows(ApiFailure.class, () -> decide(f, id, allowed, "stale-private-review"))
              .status);
      assertEquals("PENDING", f.runtime.store.get(id).approval.status());
      var hidden = f.runtime.service.approval(reviewer(f), id);
      assertFalse(hidden.body().path("reviewComplete").asBoolean());
      assertNotEquals(allowed.etag(), hidden.etag());
    }
  }

  @Test
  void forgedCheckpointsMissingFieldsAndOversizedContentNeverBecomeCompleteReview()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      String id = start(f, false);
      f.drive(id);
      var owned = f.runtime.repository.owned(id);
      var original = f.runtime.store.get(id);
      List<RunState> forged = new ArrayList<>();
      var fingerprint = original.copy();
      ((ObjectNode) fingerprint.memory.path("workflow")).put("fingerprint", "forged");
      forged.add(fingerprint);
      var wrongPc = original.copy();
      ((ObjectNode) wrongPc.memory.path("workflow")).put("pc", "answer");
      forged.add(wrongPc);
      var pending = original.copy();
      pending.pending.nodeId = "different-node";
      forged.add(pending);
      var missing = original.copy();
      ((ObjectNode) missing.memory.path("workflow").path("variables")).remove("answer");
      forged.add(missing);
      var oversized = original.copy();
      ((ObjectNode) oversized.memory.path("workflow").path("variables"))
          .put("answer", "secret".repeat(4000));
      forged.add(oversized);
      for (var value : forged) {
        var projected = HumanReviewFields.project(owned, value, true, ignored -> true);
        assertFalse(projected.complete());
        assertFalse(Json.write(projected.fields()).contains("trusted fixture answer"));
      }
      var variables = (ObjectNode) original.memory.path("workflow").path("variables");
      variables.put("undeclaredSecret", "must-not-be-projected");
      var valid = HumanReviewFields.project(owned, original, true, ignored -> true);
      assertTrue(valid.complete());
      assertEquals(1, valid.fields().size());
      assertFalse(Json.write(valid.fields()).contains("must-not-be-projected"));
      assertFalse(HumanReviewFields.project(owned, original, true, ignored -> false).complete());
    }
  }

  @Test
  void oldHumanJsonAndFingerprintRemainIdenticalUnlessReviewContextIsExplicitlyEnabled()
      throws Exception {
    String oldJson =
        """
        {"id":"legacy","version":"1","start":"review","maxTransitions":10,"nodes":{"end":{"kind":"end","output":"result"},"review":{"kind":"human","message":"Legacy prompt","output":"result","next":"end"}}}
        """
            .trim();
    var legacy = Json.convert(Json.read(oldJson), WorkflowDefinition.class);
    assertFalse(((WorkflowDefinition.Human) legacy.nodes().get("review")).reviewContext());
    assertEquals(oldJson, Json.write(legacy));
    assertEquals(
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(oldJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        legacy.fingerprint());
    assertEquals(
        Json.read(
            "{\"kind\":\"human\",\"message\":\"Legacy"
                + " prompt\",\"output\":\"result\",\"next\":\"end\"}"),
        Json.tree(new WorkflowDefinition.Human("Legacy prompt", "result", "end")));
    assertTrue(
        Json.tree(new WorkflowDefinition.Human("Review", "result", "end", true))
            .path("reviewContext")
            .asBoolean());
  }
}
