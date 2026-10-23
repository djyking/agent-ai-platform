package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.github.djyking.harness.core.Json;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class OperationsHttpTest {
  private static final String BASE = "/v1/projects/project-a/operations";

  private MockMvc mvc(PlatformTestSupport f, OperationsService service) {
    new ExecutionGovernance(f.repository, ExecutionGovernanceConfig.defaults(), f.config)
        .initialize();
    new StudioRepository(f.repository).initialize();
    new PlatformTrace(f.dataSource);
    return MockMvcBuilders.standaloneSetup(
            new OperationsController(
                f.service, service, new RetentionService(f.repository, new RetentionArchive(null))))
        .setControllerAdvice(new ApiErrors())
        .build();
  }

  private MvcResult call(
      MockMvc mvc, String user, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mvc.perform(
                request
                    .header("Authorization", "Bearer app-a-credential")
                    .header("X-Harness-User-Token", user + "-token"))
            .andReturn();
    assertEquals(
        expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    return result;
  }

  @Test
  void governanceRoutesAuthenticateAuthorizeAndKeepPlanScopes() throws Exception {
    try (var f = new PlatformTestSupport()) {
      var service = new OperationsService(f.config, f.repository);
      service.initialize();
      var mvc = mvc(f, service);
      assertEquals(401, mvc.perform(get(BASE)).andReturn().getResponse().getStatus());
      call(mvc, "alice", get(BASE), 403);
      f.identities.grant("app-a", "project-a", "alice", Set.of("catalog:read"));
      var own = Json.read(call(mvc, "alice", get(BASE), 200).getResponse().getContentAsString());
      assertEquals("OWN_TASKS", own.path("scope").asText());
      call(
          mvc,
          "alice",
          post(BASE + "/retention/preview").contentType("application/json").content("{}"),
          403);
      f.identities.grant("app-a", "project-a", "alice", Set.of("*"));
      var plan =
          Json.read(
              call(
                      mvc,
                      "alice",
                      post(BASE + "/retention/preview")
                          .contentType("application/json")
                          .content("{\"limit\":1}"),
                      200)
                  .getResponse()
                  .getContentAsString());
      assertEquals(64, plan.path("digest").asText().length());
      call(mvc, "alice", get(BASE + "/retention/plans/" + plan.path("planId").asText()), 200);
      f.identities.grant("app-a", "project-a", "bob", Set.of("*"));
      call(mvc, "bob", get(BASE + "/retention/plans/" + plan.path("planId").asText()), 404);
      call(
          mvc,
          "alice",
          post(BASE + "/retention/preview")
              .contentType("application/json")
              .content("{\"limit\":\"100\"}"),
          400);
      call(
          mvc,
          "alice",
          post(BASE + "/retention/preview")
              .contentType("application/json")
              .content("{\"deleteAll\":true}"),
          400);
    }
  }
}
