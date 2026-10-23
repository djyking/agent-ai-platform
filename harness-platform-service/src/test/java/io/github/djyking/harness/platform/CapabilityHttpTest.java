package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.github.djyking.harness.core.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class CapabilityHttpTest {
  private MvcResult call(MockMvc mvc, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mvc.perform(
                request
                    .header("Authorization", "Bearer app-a-credential")
                    .header("X-Harness-User-Token", "publisher-token"))
            .andReturn();
    assertEquals(
        expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    return result;
  }

  @Test
  void knowledgeHttpUsesStrictBodiesFreshAuthAndExactCas() throws Exception {
    try (var f = new PlatformTestSupport()) {
      f.identities.grant("app-a", "project-a", "publisher", Set.of("*"));
      var knowledge = new KnowledgeService(f.config, f.repository);
      var capability =
          new CapabilityService(f.config, f.repository, ref -> "unused-synthetic-secret");
      var mvc =
          MockMvcBuilders.standaloneSetup(
                  new KnowledgeController(f.service, knowledge),
                  new CapabilityController(f.service, capability))
              .setControllerAdvice(new ApiErrors())
              .addFilters(new ApiErrors.RequestHeaders())
              .build();
      String path = "/v1/projects/project-a/knowledge/collections/faq";
      String body =
          "{\"name\":\"FAQ\",\"visibility\":\"PUBLIC\",\"allowedSubjects\":[],\"disabled\":false}";
      var create =
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "create-collection-1")
              .header("If-Match", "\"k0\"")
              .content(body);
      assertEquals("\"k1\"", call(mvc, create, 200).getResponse().getHeader("ETag"));
      call(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "create-collection-2")
              .header("If-Match", "\"k0\"")
              .content(body),
          412);
      call(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "create-collection-3")
              .content(body),
          428);
      call(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "create-collection-4")
              .header("If-Match", "\"k1\"")
              .content(body.replace("\"disabled\":false", "\"disabled\":false,\"disabled\":true")),
          400);
      call(
          mvc,
          put(path + "/documents/shipping")
              .contentType("application/json")
              .header("Idempotency-Key", "create-document-1")
              .header("If-Match", "\"k0\"")
              .content(
                  "{\"title\":\"Shipping\",\"text\":\"Shipping two" + " days\",\"revoked\":false}"),
          200);
      var query =
          call(
              mvc,
              post("/v1/projects/project-a/knowledge/query")
                  .contentType("application/json")
                  .content("{\"collections\":[\"faq\"],\"query\":\"shipping\"}"),
              200);
      assertEquals(1, Json.read(query.getResponse().getContentAsString()).path("citations").size());
      call(mvc, get("/v1/projects/project-a/capabilities/connections"), 200);
      f.identities.grant("app-a", "project-a", "publisher", Set.of("runs:read"));
      call(mvc, get(path), 403);
    }
  }
}
