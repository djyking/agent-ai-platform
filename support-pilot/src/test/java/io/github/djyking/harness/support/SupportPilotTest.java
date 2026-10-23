package io.github.djyking.harness.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SupportPilotTest extends SupportPilotFixture {
  @Test
  void unauthenticatedApiCannotUseServerCredentialsAndStaticPageHasRestrictivePolicy()
      throws Exception {
    assertEquals(401, get("/api/runs").statusCode());
    assertFalse(JSON.readTree(get("/api/session").body()).path("signedIn").asBoolean());
    var page = get("/");
    assertEquals(200, page.statusCode());
    assertTrue(
        page.headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("script-src 'self'"));
    assertTrue(page.body().contains("客服助手"));
    assertFalse(page.body().contains(APP));
    assertEquals(0, calls.size());
  }

  @Test
  void independentOperatorIdentityDiscoversPublishedAgentsAndCreatesExactRelease()
      throws Exception {
    assertEquals("operator", login(OPERATOR_CODE).path("role").asText());
    var agents = get("/api/agents");
    assertEquals(200, agents.statusCode());
    assertTrue(
        agents.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
    assertEquals("nosniff", agents.headers().firstValue("X-Content-Type-Options").orElseThrow());
    assertEquals(202, post("/api/runs", create(RELEASE)).statusCode());
    Captured command = calls.get(calls.size() - 1);
    assertEquals("/v1/projects/support-pilot/runs", command.path());
    assertEquals("Bearer " + APP, command.app());
    assertEquals(USER, command.user());
    assertEquals("support-create-key-0001", command.key());
    assertEquals(RELEASE, command.body().path("releaseRef").path("releaseId").asText());
    assertEquals(
        "sha256:" + "a".repeat(64), command.body().path("releaseRef").path("digest").asText());
    assertTrue(command.body().path("requirePublished").asBoolean());
    assertEquals("订单什么时候发货？", command.body().path("inputs").path("question").asText());
    assertFalse(command.body().has("applicationSecret"));
    assertEquals(200, get("/api/runs").statusCode());
    assertTrue(calls.get(calls.size() - 1).path().startsWith("/v1/projects/support-pilot/"));
  }

  @Test
  void malformedOrMismatchedReleaseCannotBeDispatched() throws Exception {
    login(OPERATOR_CODE);
    assertEquals(400, post("/api/runs", create("unpublished-release")).statusCode());
    var mismatched = new HashMap<>(create(RELEASE));
    mismatched.put("releaseId", UUID.randomUUID().toString());
    assertEquals(400, post("/api/runs", mismatched).statusCode());
    assertEquals(0, calls.stream().filter(c -> c.method().equals("POST")).count());
  }

  @Test
  void exactCreateRetriesReachThePlatformReceiptWithoutDependingOnCurrentDiscovery()
      throws Exception {
    login(OPERATOR_CODE);
    var request = create(RELEASE);
    assertEquals(202, post("/api/runs", request).statusCode());
    assertEquals(202, post("/api/runs", request).statusCode());
    assertEquals(2, calls.size());
    assertTrue(calls.stream().allMatch(c -> c.method().equals("POST")));
    assertEquals(calls.get(0).body(), calls.get(1).body());
    assertTrue(calls.get(0).body().path("requirePublished").asBoolean());
    assertEquals(calls.get(0).key(), calls.get(1).key());
  }

  @Test
  void mutationsRequireSameOriginAndSessionCsrfBeforeAnyUpstreamCall() throws Exception {
    login(OPERATOR_CODE);
    String body = JSON.writeValueAsString(create(RELEASE));
    assertEquals(403, request("POST", "/api/runs", body, "http://evil.example", csrf).statusCode());
    assertEquals(
        403, request("POST", "/api/runs", body, app.origin().toString(), null).statusCode());
    assertEquals(403, request("POST", "/api/runs", body, null, csrf).statusCode());
    assertEquals(0, calls.size());
  }

  @Test
  void reviewerUsesOwnTokenAndExactApprovalTagWithoutOperatorPrivileges() throws Exception {
    assertEquals("reviewer", login(REVIEWER_CODE).path("role").asText());
    assertEquals(403, get("/api/agents").statusCode());
    assertEquals(403, post("/api/runs", create(RELEASE)).statusCode());
    var response = get("/api/runs/" + RUN + "/approval");
    assertEquals(200, response.statusCode());
    assertEquals(REVIEWER, calls.get(0).user());
    var approval = JSON.readTree(response.body());
    assertEquals(
        202,
        post(
                "/api/runs/" + RUN + "/decision",
                Map.of(
                    "approvalId",
                    "approval-fixture",
                    "digest",
                    approval.path("body").path("digest").asText(),
                    "decision",
                    "APPROVE",
                    "input",
                    "已核对合成答复",
                    "reason",
                    "人工核验完成",
                    "etag",
                    approval.path("etag").asText(),
                    "requestKey",
                    "approval-key-0001"))
            .statusCode());
    Captured decision = calls.get(calls.size() - 1);
    assertEquals(REVIEWER, decision.user());
    assertEquals("\"fixture-etag\"", decision.etag());
    assertEquals("已核对合成答复", decision.body().path("input").asText());
  }

  @Test
  void logoutInvalidatesServerSessionAndOldCookieCannotBeReused() throws Exception {
    login(OPERATOR_CODE);
    assertEquals(200, post("/api/logout", Map.of()).statusCode());
    assertEquals(401, get("/api/agents").statusCode());
    assertEquals(0, calls.size());
  }

  @Test
  void ambiguousCommandTransportFailureIsExposedAndNotRetried() throws Exception {
    login(OPERATOR_CODE);
    disconnectCreate.set(true);
    var result = post("/api/runs", create(RELEASE));
    assertEquals(502, result.statusCode());
    assertTrue(JSON.readTree(result.body()).path("outcomeUnknown").asBoolean());
    assertEquals(1, calls.stream().filter(c -> c.method().equals("POST")).count());
    assertFalse(result.body().contains(APP));
    assertFalse(result.body().contains(USER));
  }

  @Test
  void duplicateJsonAndWrongHostAreRejected() throws Exception {
    String duplicate = "{\"accessCode\":\"" + OPERATOR_CODE + "\",\"accessCode\":\"other\"}";
    assertEquals(
        400,
        request("POST", "/api/session", duplicate, app.origin().toString(), null).statusCode());
    URI wrongHost = URI.create(app.origin().toString().replace("127.0.0.1", "localhost"));
    assertEquals(
        400,
        http.send(
                HttpRequest.newBuilder(wrongHost).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(0, calls.size());
  }
}
