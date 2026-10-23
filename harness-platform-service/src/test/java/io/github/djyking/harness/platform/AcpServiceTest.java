package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.RunStatus;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AcpServiceTest {
  private ObjectNode request(String name, JsonNode inputs) {
    var body = Json.object().put("agent_name", name).put("mode", "async");
    body.putArray("input").add(AcpService.message("user", "application/json", Json.write(inputs)));
    return body;
  }

  @Test
  void discoveredReleaseIsPinnedAndIdempotentWithOwnerIsolation() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      x.agentPublish("read");
      var acp = new AcpService(x.f.service, x.catalog);
      String name = acp.agents(x.f.principal(), 10, 0).path("agents").get(0).path("name").asText();
      var body = request(name, Json.object().put("value", 7));
      String id =
          acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-create-001", body);
      assertEquals(
          id,
          acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-create-001", body));
      assertEquals(1, x.f.count("harness_runs"));
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      assertEquals(1, x.f.reads.get());
      var result = acp.get(x.f.principal(), id);
      assertEquals(name, result.path("agent_name").asText());
      assertEquals("completed", result.path("status").asText());
      assertEquals(
          "{\"value\":7}",
          result.path("output").get(0).path("parts").get(0).path("content").asText());
      error(404, null, () -> acp.get(x.f.principal("app-a", "project-a", "bob"), id));
      error(404, null, () -> acp.get(x.f.principal("app-b", "project-a", "alice"), id));
      error(404, null, () -> acp.get(x.f.principal("app-a", "project-b", "alice"), id));
      error(
          409,
          null,
          () ->
              acp.create(
                  x.f.principal(),
                  "app-a-credential",
                  "alice-token",
                  "acp-create-001",
                  request(name, Json.object().put("value", 8))));
      x.publish("Workflow", "flow", x.workflow("literal"));
      x.publish(
          "Agent", "demo", x.agent(2, Json.object().put("type", "integer")), x.cases("literal"));
      assertEquals(
          id,
          acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-create-001", body));
      assertEquals(name, acp.get(x.f.principal(), id).path("agent_name").asText());
    }
  }

  @Test
  void retiredReleaseRecoversTheOriginalReceiptButRejectsNewRuns() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      x.agentPublish("read");
      var acp = new AcpService(x.f.service, x.catalog);
      String name = acp.agents(x.f.principal(), 10, 0).path("agents").get(0).path("name").asText();
      var body = request(name, Json.object().put("value", 7));
      String id =
          acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-retired-001", body);
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      x.command("Agent", "demo", "disable", Json.object().put("disabled", true));
      assertTrue(acp.agents(x.f.principal(), 10, 0).path("agents").isEmpty());
      error(404, null, () -> acp.agent(x.f.principal(), name));
      long revision = x.f.store.get(id).revision;
      long commands = x.f.count("platform_commands"), audit = x.f.count("platform_command_audit");
      assertEquals(
          id,
          acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-retired-001", body));
      error(
          404,
          null,
          () ->
              acp.create(
                  x.f.principal(), "app-a-credential", "alice-token", "acp-retired-002", body));
      error(
          409,
          null,
          () ->
              acp.create(
                  x.f.principal(),
                  "app-a-credential",
                  "alice-token",
                  "acp-retired-001",
                  request(name, Json.object().put("value", 8))));
      assertEquals(1, x.f.count("harness_runs"));
      assertEquals(commands, x.f.count("platform_commands"));
      assertEquals(audit, x.f.count("platform_command_audit"));
      assertEquals(revision, x.f.store.get(id).revision);
      assertEquals(1, x.f.reads.get());
    }
  }

  @Test
  void knowingAnUnpublishedDeploymentReleaseDoesNotAuthorizeAcpCreation() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var acp = new AcpService(x.f.service, x.catalog);
      String name = x.f.releases.get("project-a/read").releaseId();
      assertTrue(acp.agents(x.f.principal(), 10, 0).path("agents").isEmpty());
      error(
          404,
          "RELEASE_UNAVAILABLE",
          () ->
              acp.create(
                  x.f.principal(),
                  "app-a-credential",
                  "alice-token",
                  "acp-unpublished-001",
                  request(name, Json.object().put("value", 7))));
      assertEquals(0, x.f.count("harness_runs"));
      assertEquals(0, x.f.count("platform_commands"));
      assertEquals(0, x.f.count("platform_command_audit"));
      assertEquals(0, x.f.reads.get());
    }
  }

  @Test
  void publishedAdmissionConstraintDoesNotChangeExistingAcpReceiptDigest() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      x.agentPublish("read");
      var acp = new AcpService(x.f.service, x.catalog);
      var discovered = acp.agents(x.f.principal(), 10, 0).path("agents").get(0);
      String name = discovered.path("name").asText();
      var inputs = Json.object().put("value", 7);
      // ACP originally passed this exact body after checking publication through discovery.
      // Moving admission after receipt lookup must preserve that already-accepted command hash.
      var original = Json.object();
      original.set(
          "releaseRef", discovered.path("metadata").path("agent_platform").path("release_ref"));
      original.set("inputs", inputs);
      String id =
          x.f.id(
              x.f.service.create(
                  x.f.principal(),
                  "app-a-credential",
                  "alice-token",
                  "acp-existing-hash",
                  original));
      assertEquals(
          id,
          acp.create(
              x.f.principal(),
              "app-a-credential",
              "alice-token",
              "acp-existing-hash",
              request(name, inputs)));
      assertEquals(1, x.f.count("harness_runs"));
      assertEquals(1, x.f.count("platform_commands"));
      assertEquals(0, x.f.reads.get());
    }
  }

  @Test
  void unknownModelIsAwaitingAndCannotBeReplayedByResume() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var acp = new AcpService(x.f.service, x.catalog);
      x.f.uncertainModel.set(true);
      String id = x.f.id(x.f.create("model", "acp-unknown-001"));
      assertEquals(RunStatus.NEEDS_ATTENTION, x.f.drive(id).status);
      var result = acp.get(x.f.principal(), id);
      assertEquals("awaiting", result.path("status").asText());
      var awaitDetail =
          Json.read(
              result
                  .path("await_request")
                  .path("message")
                  .path("parts")
                  .get(0)
                  .path("content")
                  .asText());
      assertEquals("external-reconciliation-required", awaitDetail.path("operation").asText());
      var decision =
          Json.object()
              .put("operation", "resume")
              .put("etag", x.f.service.get(x.f.principal(), id).etag());
      assertThrows(
          RunStore.Conflict.class,
          () -> acp.resume(x.f.principal(), id, "acp-resume-unknown", resume(id, decision)));
      x.f.drive(id);
      assertEquals(1, x.f.modelCalls.get());
      assertEquals("awaiting", acp.get(x.f.principal(), id).path("status").asText());
    }
  }

  @Test
  void humanResumeRequiresReviewedDigestAndExactRevision() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var rights = new HashSet<>(OWNER_PERMISSIONS);
      rights.addAll(REVIEW_PERMISSIONS);
      x.f.identities.grant("app-a", "project-a", "reviewer", rights);
      var reviewer = x.f.reviewer();
      var acp = new AcpService(x.f.service, x.catalog);
      String id = x.f.id(x.f.create(reviewer, "human", "acp-human-001"));
      x.f.drive(id);
      var awaiting = acp.get(reviewer, id);
      assertEquals("awaiting", awaiting.path("status").asText());
      var detail =
          Json.read(
              awaiting
                  .path("await_request")
                  .path("message")
                  .path("parts")
                  .get(0)
                  .path("content")
                  .asText());
      var approval = detail.path("approval");
      var decision =
          Json.object()
              .put("operation", "approval")
              .put("etag", detail.path("etag").asText())
              .put("approvalId", approval.path("id").asText());
      decision.set(
          "decision",
          Json.object()
              .put("digest", "incorrect")
              .put("decision", "APPROVE")
              .put("input", "Reviewed answer"));
      assertThrows(
          ApiFailure.class,
          () -> acp.resume(reviewer, id, "acp-human-invalid", resume(id, decision)));
      ((ObjectNode) decision.path("decision")).put("digest", approval.path("digest").asText());
      acp.resume(reviewer, id, "acp-human-valid", resume(id, decision));
      acp.resume(reviewer, id, "acp-human-valid", resume(id, decision));
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      assertTrue(acp.get(reviewer, id).path("output").toString().contains("Reviewed answer"));
      assertEquals(0, x.f.modelCalls.get());
    }
  }

  @Test
  void assignedNonownerReviewerMustUseApprovalInterfaceAndAcpCannotWriteBeforeDenial() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var acp = new AcpService(x.f.service, x.catalog);
      String id = x.f.id(x.f.create("human", "acp-separate-reviewer"));
      assertEquals(RunStatus.WAITING_INPUT, x.f.drive(id).status);
      var reviewer = x.f.reviewer();
      var approval = x.f.service.approval(reviewer, id);
      var decision =
          Json.object()
              .put("operation", "approval")
              .put("etag", approval.etag())
              .put("approvalId", approval.body().path("id").asText());
      var approved =
          Json.object()
              .put("digest", approval.body().path("digest").asText())
              .put("decision", "APPROVE")
              .put("input", "Reviewed through the assigned approval interface");
      decision.set("decision", approved);
      long revision = x.f.store.get(id).revision;
      long commands = x.f.count("platform_commands"), audit = x.f.count("platform_command_audit");
      error(
          404,
          "NOT_FOUND",
          () -> acp.resume(reviewer, id, "acp-reviewer-denied", resume(id, decision)));
      assertEquals(revision, x.f.store.get(id).revision);
      assertEquals(RunStatus.WAITING_INPUT, x.f.store.get(id).status);
      assertEquals(commands, x.f.count("platform_commands"));
      assertEquals(audit, x.f.count("platform_command_audit"));
      assertEquals(approval, x.f.service.approval(reviewer, id));
      x.f.service.decide(
          reviewer,
          id,
          approval.body().path("id").asText(),
          "platform-reviewer-accepted",
          approval.etag(),
          approved);
      assertEquals(RunStatus.COMPLETED, x.f.drive(id).status);
      assertTrue(
          acp.get(x.f.principal(), id).path("output").toString().contains("Reviewed through"));
      assertEquals(0, x.f.modelCalls.get());
      assertEquals(0, x.f.reads.get());
      assertEquals(0, x.f.writes.get());
    }
  }

  private ObjectNode resume(String id, JsonNode decision) {
    var body = Json.object().put("run_id", id).put("mode", "async");
    body.set(
        "await_resume",
        Json.object()
            .put("type", "message")
            .set("message", AcpService.message("user", "application/json", Json.write(decision))));
    return body;
  }

  @Test
  void outputRevocationAlsoAppliesToEventsAndCancellationDoesNotExecute() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      var acp = new AcpService(x.f.service, x.catalog);
      String id = x.f.id(x.f.create("model", "acp-output-001"));
      x.f.drive(id);
      assertFalse(acp.get(x.f.principal(), id).path("output").isEmpty());
      x.f.identities.revoke("app-a", "project-a", "alice", "runs:output:read");
      assertTrue(acp.get(x.f.principal(), id).path("output").isEmpty());
      assertFalse(acp.events(x.f.principal(), id, null).toString().contains("A recorded answer"));
      String cancelled = x.f.id(x.f.create("read", "acp-cancel-001"));
      acp.cancel(x.f.principal(), cancelled, "acp-cancel-intent", null);
      x.f.drive(cancelled);
      assertEquals("cancelled", acp.get(x.f.principal(), cancelled).path("status").asText());
      assertEquals(0, x.f.reads.get());
    }
  }

  @Test
  void unsupportedPayloadsFailBeforeAnyExecution() {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      x.agentPublish("read");
      var acp = new AcpService(x.f.service, x.catalog);
      String name = acp.agents(x.f.principal(), 10, 0).path("agents").get(0).path("name").asText();
      var body = request(name, Json.object().put("value", 7));
      body.put("session_id", UUID.randomUUID().toString());
      error(
          422,
          "ACP_SESSION_NOT_SUPPORTED",
          () ->
              acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-session", body));
      body.remove("session_id");
      ((ObjectNode) body.path("input").get(0).path("parts").get(0))
          .put("content_url", "http://127.0.0.1/private");
      error(
          422,
          "ACP_UNSUPPORTED_MESSAGE_PART",
          () -> acp.create(x.f.principal(), "app-a-credential", "alice-token", "acp-remote", body));
      assertEquals(0, x.f.count("harness_runs"));
      assertEquals(0, x.f.reads.get());
    }
  }

  @Test
  void httpContractRequiresBothIdentitiesAndDurableWriteKey() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture();
        var controller = new ControllerFixture(x)) {
      x.agentPublish("read");
      String name =
          new AcpService(x.f.service, x.catalog)
              .agents(x.f.principal(), 10, 0)
              .path("agents")
              .get(0)
              .path("name")
              .asText();
      var mvc = MockMvcBuilders.standaloneSetup(controller.controller).build();
      mvc.perform(get("/acp/projects/project-a/agents")).andExpect(status().isUnauthorized());
      mvc.perform(
              get("/acp/projects/project-a/agents")
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token"))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.agents[0].name").value(name));
      mvc.perform(
              post("/acp/projects/project-a/runs")
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(Json.write(request(name, Json.object().put("value", 7)))))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post("/acp/projects/project-a/runs")
                  .header("Authorization", "Bearer app-a-credential")
                  .header("X-Harness-User-Token", "alice-token")
                  .header("Idempotency-Key", "acp-http-001")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(Json.write(request(name, Json.object().put("value", 7)))))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.run_id").isString())
          .andExpect(jsonPath("$.status").value("created"));
      assertEquals(1, x.f.count("harness_runs"));
    }
  }

  private static final class ControllerFixture implements AutoCloseable {
    final AcpController controller;

    ControllerFixture(CatalogLifecycleTest.Fixture fixture) {
      controller = new AcpController(fixture.f.service, fixture.catalog);
    }

    public void close() {
      controller.close();
    }
  }
}
