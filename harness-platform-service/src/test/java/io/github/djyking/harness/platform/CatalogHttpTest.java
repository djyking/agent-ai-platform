package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import io.github.djyking.harness.core.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class CatalogHttpTest {
  private static final String BASE = "/v1/projects/project-a/catalog";

  private MockMvc mvc(CatalogLifecycleTest.Fixture x) {
    return MockMvcBuilders.standaloneSetup(new CatalogController(x.f.service, x.catalog))
        .setControllerAdvice(new ApiErrors())
        .addFilters(new ApiErrors.RequestHeaders())
        .build();
  }

  private MvcResult response(MockMvc mvc, MockHttpServletRequestBuilder request, int status)
      throws Exception {
    MvcResult r =
        mvc.perform(
                request
                    .header("Authorization", "Bearer app-a-credential")
                    .header("X-Harness-User-Token", "publisher-token"))
            .andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
    return r;
  }

  private JsonNode json(MvcResult r) throws Exception {
    return Json.read(r.getResponse().getContentAsString());
  }

  private void schema(String name, JsonNode value) throws Exception {
    try (var in = getClass().getResourceAsStream("/api/catalog-openapi.json")) {
      JsonNode doc = Json.MAPPER.readTree(Objects.requireNonNull(in));
      ObjectNode root = Json.object().put("$ref", "#/components/schemas/" + name);
      root.set("components", doc.path("components"));
      var errors =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(root)
              .validate(value);
      assertTrue(errors.isEmpty(), errors.toString());
    }
  }

  @Test
  void lifecycleHttpContractSchemasEtagSafeErrorsAndDiscovery() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      MockMvc mvc = mvc(x);
      schema("Capabilities", json(response(mvc, get(BASE + "/capabilities"), 200)));
      ObjectNode body = Json.object().put("name", "HTTP limits");
      body.set(
          "spec",
          Json.read(
              "{\"maxTokens\":1000,\"maxModelCalls\":2,\"maxToolCalls\":2,\"maxSteps\":20,\"lifetimeSeconds\":3600}"));
      String path = BASE + "/resources/RunPolicy/http-limits";
      MvcResult saved =
          response(
              mvc,
              put(path)
                  .contentType("application/json")
                  .header("Idempotency-Key", "http-save-0001")
                  .header("If-Match", "\"c0\"")
                  .content(Json.write(body)),
              200);
      assertEquals("\"c1\"", saved.getResponse().getHeader("ETag"));
      schema("Resource", json(saved));
      MvcResult checked =
          response(
              mvc,
              post(path + "/validate")
                  .contentType("application/json")
                  .header("Idempotency-Key", "http-validate-0001")
                  .header("If-Match", "\"c1\"")
                  .content("{}"),
              200);
      assertTrue(json(checked).path("validation").path("passed").asBoolean());
      MvcResult published =
          response(
              mvc,
              post(path + "/publish")
                  .contentType("application/json")
                  .header("Idempotency-Key", "http-publish-0001")
                  .header("If-Match", "\"c2\"")
                  .content("{}"),
              200);
      schema("Resource", json(published));
      schema("Version", json(response(mvc, get(path + "/versions/1"), 200)));
      schema("ResourceList", json(response(mvc, get(BASE + "/resources"), 200)));
      response(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "http-stale-0001")
              .header("If-Match", "\"c1\"")
              .content(Json.write(body)),
          412);
      response(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "http-no-precondition")
              .content(Json.write(body)),
          428);
      response(
          mvc,
          put(path)
              .contentType("text/plain")
              .header("Idempotency-Key", "http-media-0001")
              .header("If-Match", "\"c3\"")
              .content(Json.write(body)),
          415);
      response(
          mvc,
          put(path)
              .contentType("application/json")
              .header("Idempotency-Key", "http-duplicate-keys")
              .header("If-Match", "\"c3\"")
              .content("{\"name\":\"a\",\"name\":\"b\",\"spec\":{}}"),
          400);
      x.agentPublish("read");
      schema("ReleaseList", json(response(mvc, get(BASE + "/releases"), 200)));
      schema("Release", json(response(mvc, get(BASE + "/agents/demo/default"), 200)));
      response(
          mvc,
          post(path + "/disable")
              .contentType("application/json")
              .header("Idempotency-Key", "http-disable-0001")
              .header("If-Match", "\"c3\"")
              .content("{\"disabled\":true,\"secret\":\"must-not-echo\"}"),
          422);
    }
  }

  @Test
  void controllerAuthenticatesEveryRequestAndDoesNotExposeOtherProjects() throws Exception {
    try (var x = new CatalogLifecycleTest.Fixture()) {
      MockMvc mvc = mvc(x);
      MvcResult missing = mvc.perform(get(BASE + "/resources")).andReturn();
      assertEquals(401, missing.getResponse().getStatus());
      MvcResult denied =
          mvc.perform(
                  get(BASE + "/resources")
                      .header("Authorization", "Bearer app-a-credential")
                      .header("X-Harness-User-Token", "alice-token"))
              .andReturn();
      assertEquals(403, denied.getResponse().getStatus());
      x.f.identities.grant("app-a", "project-b", "publisher", Set.of("catalog:read"));
      x.agentPublish("read");
      response(mvc, get("/v1/projects/project-b/catalog/resources/Agent/demo"), 404);
      x.f.identities.revoke("app-a", "project-a", "publisher", "*");
      response(mvc, get(BASE + "/resources"), 403);
    }
  }
}
