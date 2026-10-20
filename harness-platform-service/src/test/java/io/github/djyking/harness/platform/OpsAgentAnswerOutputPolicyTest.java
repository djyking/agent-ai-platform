package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OpsAgentAnswerOutputPolicyTest {
  @Test
  void modelOutputRetainsExactPromptProvenanceAndCurrentDomainAuthorization() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      x.base(false);
      var policy = (ObjectNode) x.policy(false, false);
      policy.withArray("executionPermissions").add("model:invoke");
      x.publish("ToolPolicy", "policy", policy);
      x.publish(
          "Prompt",
          "prompt",
          Json.read(
              """
              {"id":"prompt","version":"1","system":"Use only supplied evidence","user":"{{prompt}}",
               "variables":{"prompt":"STRING"}}
              """));
      x.publish("ModelProfile", "model", Json.object().put("trustedProfile", "test"));
      x.publish(
          "Workflow",
          "flow",
          Json.read(
              """
              {"definition":{"id":"ops-answer","version":"1","start":"answer","maxTransitions":20,
               "nodes":{"answer":{"kind":"model","promptRef":{"id":"prompt","version":1},
                 "inputBindings":{"prompt":"prompt"},"output":"result","next":"end"},
                 "end":{"kind":"end","output":"result"}}}}
              """));
      var agent = (ObjectNode) x.agent(1, Json.object().put("type", "string"));
      agent.set("modelProfile", x.ref("model", 1));
      agent.set("toolPolicy", x.ref("policy", 2));
      agent.set(
          "inputSchema",
          Json.read(
              """
              {"type":"object","required":["prompt","contextId"],"additionalProperties":false,
               "properties":{"prompt":{"type":"string"},"contextId":{"type":"string"}}}
              """));
      String context = UUID.randomUUID().toString();
      String prompt = "Synthetic source: 退款审批需要复核。";
      var inputs = Json.object().put("prompt", prompt).put("contextId", context);
      var replay = Json.object().put("name", "frozen-answer");
      replay.set("inputs", inputs);
      replay.putObject("modelResults").put("answer", "Synthetic answer");
      replay.put("expectedOutput", "Synthetic answer").putArray("expectedToolKeys");
      replay
          .putObject("expectedPrompts")
          .set(
              "answer",
              Json.tree(
                  List.of(
                      io.github.djyking.harness.core.Contracts.Message.text(
                          "system", "Use only supplied evidence"),
                      io.github.djyking.harness.core.Contracts.Message.text("user", prompt))));
      x.publish(
          "Agent",
          OpsAgentAnswerOutputPolicy.AGENT,
          agent,
          Json.MAPPER.createArrayNode().add(replay));
      var body = Json.object();
      body.set(
          "releaseRef",
          x.catalog.defaultRelease(x.admin, OpsAgentAnswerOutputPolicy.AGENT).path("releaseRef"));
      body.set("inputs", inputs);
      String id =
          x.f.id(
              x.f.service.create(
                  x.f.principal(), "app-a-credential", "alice-token", "ops-answer-001", body));
      x.f.drive(id);
      var owned = x.f.repository.owned(id);
      AtomicBoolean allowed = new AtomicBoolean(true);
      AtomicInteger checks = new AtomicInteger();
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(prompt.getBytes(StandardCharsets.UTF_8)));
      var outputPolicy =
          new OpsAgentAnswerOutputPolicy(
              x.f.store,
              x.f.identities,
              (auth, check) -> {
                checks.incrementAndGet();
                assertEquals("Bearer explicitly-test-only", auth);
                assertEquals(context, check.path("contextId").asText());
                assertEquals(digest, check.path("promptSha256").asText());
                assertEquals(2, check.size());
                return allowed.get();
              });
      var output = x.f.store.get(id).output;
      assertTrue(outputPolicy.canRead(x.f.principal(), owned, output));
      allowed.set(false);
      assertFalse(outputPolicy.canRead(x.f.principal(), owned, output));
      assertEquals(2, checks.get());
      assertFalse(outputPolicy.canRead(x.f.principal("app-a", "project-a", "bob"), owned, output));
      assertFalse(
          outputPolicy.canRead(x.f.principal("app-b", "project-a", "alice"), owned, output));
      assertFalse(
          outputPolicy.canRead(
              x.f.principal(), owned, Json.object().put("content", "replacement")));
      x.f.identities.revoke("app-a", "project-a", "alice", "runs:output:read");
      assertFalse(outputPolicy.canRead(x.f.principal(), owned, output));
      assertEquals(2, checks.get());
      assertFalse(
          new OpsAgentAnswerOutputPolicy(x.f.store, x.f.identities, (URI) null)
              .canRead(x.f.principal(), owned, output));
    }
  }

  @Test
  void arbitraryModelWorkflowCannotClaimOpsSourceAuthorization() {
    try (var fixture = new PlatformTestSupport()) {
      for (var release : fixture.releases.values())
        assertFalse(OpsAgentAnswerOutputPolicy.accepts(release));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new OpsAgentAnswerOutputPolicy(
                  fixture.store, fixture.identities, URI.create("http://untrusted.example")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new OpsAgentAnswerOutputPolicy(
                  fixture.store,
                  fixture.identities,
                  URI.create("https://host.example/untrusted-path")));
    }
  }
}
