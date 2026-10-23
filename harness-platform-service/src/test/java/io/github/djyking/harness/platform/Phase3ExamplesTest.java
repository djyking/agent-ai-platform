package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.capabilities.rag.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.integrations.opsagent.*;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** Public deployment/examples must pass the real publication gate without calling providers. */
final class Phase3ExamplesTest {
  @Test
  void publicExamplesPublishAgainstTheirUnmodifiedDeploymentAndFrozenCases() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (!Files.exists(root.resolve("deploy/platform"))) root = root.getParent();
    Deployment config = Deployment.load(root.resolve("deploy/platform/phase3-deployment.example.json"));
    JsonNode examples = Json.read(Files.readString(root.resolve("deploy/platform/phase3-catalog.examples.json")));
    ToolRegistry tools = new ToolRegistry();
    for (JsonNode tool : config.tools()) {
      ToolDescriptor descriptor;
      switch (tool.path("kind").asText()) {
        case "opsagent-rag" -> descriptor = new OpsAgentRagTool(
            OpsAgentRagConfig.defaults(URI.create(tool.path("origin").asText())),
            (endpoint, audience, context) -> { throw new AssertionError("No remote credentials during publication"); })
            .descriptor();
        case "lexical-rag" -> {
          List<Retrieval.Document> documents = new ArrayList<>();
          for (JsonNode document : tool.path("documents"))
            documents.add(Json.convert(document, Retrieval.Document.class));
          descriptor = new RagTool(new InMemoryRetriever(documents), 8, 12000)
              .descriptor(tool.path("key").asText(), tool.path("modelName").asText());
        }
        default -> throw new AssertionError("Unexpected public example adapter");
      }
      tools.register(descriptor, (d, a, c) -> { throw new AssertionError("Publication must use frozen tool results"); });
    }
    for (Deployment.Release release : config.releases()) config.validateRelease(release, tools);
    assertTrue(config.disabled(config.release("support-pilot", "d7171780-92ba-4aaa-938c-fc8bc4fd651f")));
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:phase3-examples-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try (JdbcRunStore store = new JdbcRunStore(dataSource)) {
      store.initializeSchema();
      PlatformRepository repository = new PlatformRepository(store);
      repository.initialize(config);
      CatalogService catalog = new CatalogService(config, repository, tools, Set.of());
      var admin = new IdentityProvider.Principal("platform-console", examples.path("projectId").asText(), "synthetic-publisher", Set.of("*"));
      Set<String> types = new HashSet<>();
      for (JsonNode resource : examples.path("resources")) {
        String type = resource.path("type").asText(), id = resource.path("id").asText();
        types.add(type);
        JsonNode saved = mutate(catalog, admin, type, id, "save", "\"c0\"", resource.path("body"));
        JsonNode validated = mutate(catalog, admin, type, id, "validate", CatalogService.etag(saved), Json.object());
        assertTrue(validated.path("validation").path("passed").asBoolean(), type + "/" + id + ": " + validated.toPrettyString());
        JsonNode published = mutate(catalog, admin, type, id, "publish", CatalogService.etag(validated), Json.object());
        assertEquals("PUBLISHED", published.path("status").asText());
        assertEquals(1, published.path("versions").size());
      }
      assertEquals(CatalogValidator.TYPES, types);
      JsonNode agent = catalog.get(admin, "Agent", "support-faq");
      mutate(catalog, admin, "Agent", "support-faq", "default", CatalogService.etag(agent), Json.object().put("version", 1));
      JsonNode release = catalog.defaultRelease(admin, "support-faq");
      assertEquals(1, release.path("version").asInt());
      assertEquals("support-faq", release.path("releaseRef").path("agentId").asText());
      assertEquals(1, catalog.releases(admin).path("items").size());
      // The FAQ is deliberately tool-only: model/prompt examples are independently published.
      assertFalse(agent.path("spec").has("modelProfile"));
      assertEquals(Set.of("support:faq"), CatalogValidator.strings(agent.path("regressionCases").get(0).path("expectedToolKeys")));
    }
  }

  private JsonNode mutate(CatalogService catalog, IdentityProvider.Principal principal, String type,
      String id, String operation, String etag, JsonNode body) {
    return catalog.mutate(principal, type, id, operation, null, UUID.randomUUID().toString(), etag, body);
  }
}
