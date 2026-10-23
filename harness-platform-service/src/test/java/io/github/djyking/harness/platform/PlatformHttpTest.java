package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real controller/filter/advice/service/store stack with explicit test identity authority. */
class PlatformHttpTest {
  private PlatformTestSupport f;
  private MockMvc mvc;
  private static final String BASE = "/v1/projects/project-a/runs";

  @BeforeEach
  void setup() {
    f = new PlatformTestSupport();
    mvc =
        MockMvcBuilders.standaloneSetup(new PlatformController(f.service))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new ApiErrors.RequestHeaders())
            .build();
  }

  @AfterEach
  void close() {
    f.close();
  }

  @Test
  void allElevenPublishedOperationsExecuteThroughTheHttpBoundary() throws Exception {
    MvcResult accepted =
        response(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "http-create-read1")
                .content(Json.write(f.body("project-a", "read"))),
            "alice",
            202,
            "AcceptedRun");
    String id = f.id(json(accepted));
    assertEquals(BASE + "/" + id, accepted.getResponse().getHeader("Location"));
    assertEquals("1", accepted.getResponse().getHeader("Retry-After"));
    String tag =
        response(get(BASE + "/" + id), "alice", 200, "RunView").getResponse().getHeader("ETag");
    response(get(BASE), "alice", 200, "RunPage");
    response(get(BASE + "/" + id + "/events"), "alice", 200, "EventPage");
    response(
        post(BASE + "/" + id + "/pause")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", tag)
            .header("Idempotency-Key", "http-pause-0001")
            .content("{}"),
        "alice",
        202,
        "AcceptedRun");
    tag = response(get(BASE + "/" + id), "alice", 200, "RunView").getResponse().getHeader("ETag");
    response(
        post(BASE + "/" + id + "/resume")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", tag)
            .header("Idempotency-Key", "http-resume-001")
            .content("{}"),
        "alice",
        202,
        "AcceptedRun");
    tag = response(get(BASE + "/" + id), "alice", 200, "RunView").getResponse().getHeader("ETag");
    response(
        post(BASE + "/" + id + "/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", tag)
            .header("Idempotency-Key", "http-cancel-001")
            .content("{}"),
        "alice",
        202,
        "AcceptedRun");
    assertEquals(RunStatus.CANCELLED, f.store.get(id).status);

    f.uncertainWrite.set(true);
    MvcResult write =
        response(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "http-create-write")
                .content(Json.write(f.body("project-a", "write"))),
            "alice",
            202,
            "AcceptedRun");
    String writeId = f.id(json(write));
    f.drive(writeId);
    MvcResult approval =
        response(get(BASE + "/" + writeId + "/approval"), "reviewer", 200, "ApprovalView");
    JsonNode approveBody =
        Json.object()
            .put("digest", json(approval).path("digest").asText())
            .put("decision", "APPROVE");
    response(
        post(BASE
                + "/"
                + writeId
                + "/approvals/"
                + json(approval).path("id").asText()
                + "/decision")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", approval.getResponse().getHeader("ETag"))
            .header("Idempotency-Key", "http-approve-001")
            .content(Json.write(approveBody)),
        "reviewer",
        202,
        "AcceptedRun");
    assertEquals(RunStatus.NEEDS_ATTENTION, f.drive(writeId).status);
    MvcResult unknown =
        response(
            get(BASE + "/" + writeId + "/unknown-invocation"),
            "alice",
            200,
            "UnknownInvocationView");
    RunState state = f.store.get(writeId);
    f.repository.importVerified(
        new PlatformRepository.Evidence(
            "http-evidence-01",
            "project-a",
            writeId,
            state.pending.id,
            PlatformRepository.invocationDigest(state),
            new ToolResult(Json.object().put("value", 7), false, "independent-http-test-receipt"),
            "test-verifier",
            f.clock.millis()));
    JsonNode receipt =
        Json.object()
            .put("invocationRef", json(unknown).path("invocationRef").asText())
            .put("invocationDigest", json(unknown).path("invocationDigest").asText())
            .put("evidenceRef", "http-evidence-01")
            .put("reason", "Verified through independent test read");
    response(
        post(BASE + "/" + writeId + "/tool-reconciliations")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", unknown.getResponse().getHeader("ETag"))
            .header("Idempotency-Key", "http-reconcile01")
            .content(Json.write(receipt)),
        "alice",
        202,
        "AcceptedRun");
    assertEquals(RunStatus.PAUSED, f.store.get(writeId).status);
    assertEquals(1, f.writes.get());
  }

  @Test
  void requestParsingAuthenticationAndConcurrencyErrorsUseSafeHttpResponses() throws Exception {
    response(
        post(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "duplicate-json01")
            .content("{\"inputs\":{},\"inputs\":{}}"),
        "alice",
        400,
        "Error");
    response(
        post(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "trailing-json001")
            .content("{} {}"),
        "alice",
        400,
        "Error");
    response(
        post(BASE)
            .contentType(MediaType.TEXT_PLAIN)
            .header("Idempotency-Key", "wrong-media-0001")
            .content("{}"),
        "alice",
        415,
        "Error");
    response(
        post(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "oversized-json01")
            .content("{\"x\":\"" + "x".repeat(65536) + "\"}"),
        "alice",
        413,
        "Error");
    MvcResult unauthenticated = mvc.perform(get(BASE).header("X-User-Id", "alice")).andReturn();
    assertEquals(401, unauthenticated.getResponse().getStatus());
    ApiJson.validate("Error", json(unauthenticated));
    String id = f.id(f.create("read", "http-precondition"));
    response(get(BASE + "/" + id + "/events").param("limit", "201"), "alice", 400, "Error");
    response(
        post(BASE + "/" + id + "/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "http-no-etag-001")
            .content("{}"),
        "alice",
        428,
        "Error");
    String tag =
        response(get(BASE + "/" + id), "alice", 200, "RunView").getResponse().getHeader("ETag");
    response(
        post(BASE + "/" + id + "/pause")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", tag)
            .header("Idempotency-Key", "http-pause-tag01")
            .content("{}"),
        "alice",
        202,
        "AcceptedRun");
    response(
        post(BASE + "/" + id + "/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .header("If-Match", tag)
            .header("Idempotency-Key", "http-stale-tag01")
            .content("{}"),
        "alice",
        412,
        "Error");
    response(get(BASE + "/" + id), "bob", 404, "Error");
    f.identities.revoke("app-a", "project-a", "alice", "runs:read");
    response(get(BASE + "/" + id), "alice", 403, "Error");
    assertEquals(1, f.count("harness_runs"));
    assertEquals(0, f.reads.get());
  }

  @Test
  void unsupportedMethodRemainsAnHttpMethodErrorAndDuplicateSecurityHeadersAreRejected()
      throws Exception {
    MvcResult unsupported =
        response(
            put(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"), "alice", 405, "Error");
    String allowed = unsupported.getResponse().getHeader("Allow");
    assertNotNull(allowed);
    assertTrue(allowed.contains("GET") && allowed.contains("POST"));
    assertNull(unsupported.getResponse().getHeader("Retry-After"));
    MvcResult duplicates =
        mvc.perform(
                get(BASE)
                    .header("Authorization", "Bearer app-a-credential", "Bearer app-b-credential")
                    .header("X-Harness-User-Token", "alice-token"))
            .andReturn();
    assertEquals(400, duplicates.getResponse().getStatus());
    ApiJson.validate("Error", json(duplicates));
  }

  private MvcResult response(
      MockHttpServletRequestBuilder request, String subject, int status, String schema)
      throws Exception {
    MvcResult result =
        mvc.perform(
                request
                    .header("Authorization", "Bearer app-a-credential")
                    .header("X-Harness-User-Token", subject + "-token"))
            .andReturn();
    assertEquals(
        status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    assertNotNull(result.getResponse().getHeader("X-Request-Id"));
    ApiJson.validate(schema, json(result));
    if (status >= 400)
      assertEquals(
          result.getResponse().getHeader("X-Request-Id"),
          json(result).path("error").path("requestId").asText());
    return result;
  }

  private static JsonNode json(MvcResult result) throws Exception {
    return Json.read(result.getResponse().getContentAsString());
  }
}
