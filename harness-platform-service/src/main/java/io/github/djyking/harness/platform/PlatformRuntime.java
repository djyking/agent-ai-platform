package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.mcp.*;
import io.github.djyking.harness.adapters.model.*;
import io.github.djyking.harness.capabilities.rag.*;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.integrations.opsagent.*;
import io.github.djyking.harness.storage.jdbc.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Explicit composition root for the service; SDK modules remain framework-independent. */
public final class PlatformRuntime implements AutoCloseable {
  public final Deployment deployment;
  public final JdbcRunStore store;
  public final PlatformRepository repository;
  public final Harness harness;
  public final PlatformService service;
  public final PlatformWorker worker;
  private final List<AutoCloseable> connections = new ArrayList<>();

  public PlatformRuntime(
      DataSource dataSource, Deployment config, SecretProvider secrets, IdentityProvider identity) {
    deployment = config;
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
    repository = new PlatformRepository(store);
    SignedTokens signing = new SignedTokens(secrets.resolve(config.signingSecret()));
    for (String reference : config.applicationSecrets().values()) secrets.resolve(reference);
    RuntimeAccess access = new RuntimeAccess(repository, identity);
    ToolRegistry tools = new ToolRegistry();
    ModelRouter models = new ModelRouter();
    for (JsonNode model : config.models()) {
      String provider = model.path("provider").asText();
      if (model.path("kind").asText().equals("deepseek")) {
        String reference = model.path("secretRef").asText();
        URI endpoint = URI.create(model.path("endpoint").asText());
        models.register(
            provider,
            new DeepSeekChatModel(
                new DeepSeekConfig(
                    endpoint,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(60),
                    1024 * 1024,
                    url -> Map.of("Authorization", "Bearer " + secrets.resolve(reference)))));
      } else throw new IllegalArgumentException("Unsupported configured model adapter");
    }
    for (JsonNode tool : config.tools()) {
      switch (tool.path("kind").asText()) {
        case "opsagent-rag" -> {
          OpsAgentRagTool rag =
              new OpsAgentRagTool(
                  OpsAgentRagConfig.defaults(URI.create(tool.path("origin").asText())),
                  (endpoint, audience, context) -> {
                    var owned = repository.owned(context.runId());
                    return identity.toolAuthorization(
                        owned.application(), owned.project(), owned.delegation(), owned.runId());
                  });
          tools.register(rag.descriptor(), rag);
        }
        case "lexical-rag" -> {
          List<Retrieval.Document> documents = new ArrayList<>();
          for (JsonNode document : tool.path("documents"))
            documents.add(Json.convert(document, Retrieval.Document.class));
          RagTool rag = new RagTool(new InMemoryRetriever(documents), 8, 12000);
          tools.register(
              rag.descriptor(tool.path("key").asText(), tool.path("modelName").asText()), rag);
        }
        case "mcp" -> {
          String secret = tool.path("secretRef").asText();
          McpConnection connection =
              McpConnection.connect(
                  McpConnectionConfig.defaults(
                      tool.path("serverId").asText(),
                      URI.create(tool.path("endpoint").asText()),
                      url -> Map.of("Authorization", "Bearer " + secrets.resolve(secret))));
          connections.add(connection);
          McpToolAdapter adapter = new McpToolAdapter(connection);
          Map<String, ToolPolicy> policies = new LinkedHashMap<>();
          Map<String, JsonNode> restrictions = new HashMap<>();
          for (JsonNode binding : tool.path("bindings")) {
            String remote = binding.path("remoteName").asText();
            boolean readOnly = binding.path("readOnly").asBoolean(false);
            if (!readOnly && !binding.has("argumentSchema"))
              throw new IllegalArgumentException("MCP writes require an explicit argument scope");
            policies.put(
                remote, readOnly ? ToolPolicy.readOnlyPolicy() : ToolPolicy.approvedWrite());
            restrictions.put(remote, binding.path("argumentSchema"));
          }
          for (ToolDescriptor remote : adapter.bind(policies)) {
            JsonNode restriction = restrictions.get(remote.remoteName());
            if (restriction == null || restriction.isMissingNode()) tools.register(remote, adapter);
            else {
              var schema = Json.object();
              schema.putArray("allOf").add(remote.inputSchema()).add(restriction);
              ToolDescriptor scoped =
                  new ToolDescriptor(
                      remote.key(),
                      remote.modelName(),
                      remote.description(),
                      remote.adapter(),
                      remote.remoteName(),
                      Json.hash(List.of(remote.version(), restriction)),
                      schema,
                      remote.policy());
              tools.register(
                  scoped,
                  (descriptor, args, context) -> {
                    ToolRegistry.validate(scoped, args);
                    return adapter.invoke(remote, args, context);
                  });
            }
          }
        }
        default -> throw new IllegalArgumentException("Unsupported configured tool adapter");
      }
    }
    for (Deployment.Release release : config.releases()) {
      for (var node : release.workflow().nodes().values()) {
        if (node
                instanceof
                io.github.djyking.harness.capabilities.workflow.WorkflowDefinition.Tool tool
            && !release.toolKeys().contains(tool.toolName()))
          throw new IllegalArgumentException("Workflow tool is outside release capabilities");
        if (node
                instanceof
                io.github.djyking.harness.capabilities.workflow.WorkflowDefinition.Agent agent
            && !release.toolKeys().containsAll(agent.allowedTools()))
          throw new IllegalArgumentException("Nested agent tools are outside release capabilities");
        if ((node
                    instanceof
                    io.github.djyking.harness.capabilities.workflow.WorkflowDefinition.Model
                || node
                    instanceof
                    io.github.djyking.harness.capabilities.workflow.WorkflowDefinition.Agent)
            && (release.model() == null
                || !release.executionPermissions().contains("model:invoke")))
          throw new IllegalArgumentException("Workflow model and permission required");
      }
      if (release.model() != null
          && config.models().stream()
              .noneMatch(m -> m.path("provider").asText().equals(release.model().provider())))
        throw new IllegalArgumentException("Release model provider unavailable");
      for (ToolDescriptor descriptor : tools.snapshot(release.toolKeys())) {
        if (!release.executionPermissions().contains("tool:" + descriptor.key())
            || !release
                .executionPermissions()
                .containsAll(descriptor.policy().requiredPermissions()))
          throw new IllegalArgumentException("Release missing tool permissions");
        if (descriptor.policy().timeoutMillis() + 1000 >= config.leaseMillis())
          throw new IllegalArgumentException("Tool deadline must fit worker lease");
      }
      if (release.model() != null && release.model().timeoutMillis() + 1000 >= config.leaseMillis())
        throw new IllegalArgumentException("Model deadline must fit worker lease");
    }
    // Do not publish a new immutable manifest until all capabilities and secrets are valid.
    repository.initialize(config);
    harness =
        new Harness(
            store,
            models,
            tools,
            access,
            new StructuredTelemetry(),
            Clock.systemUTC(),
            Duration.ofMillis(config.leaseMillis()),
            new InvocationExecutor(config.concurrency(), config.concurrency() * 2));
    harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    service =
        new PlatformService(
            config, store, repository, harness, access, identity, signing, Clock.systemUTC());
    worker = new PlatformWorker(config, store, repository, harness);
  }

  public void start() {
    if (deployment.workerEnabled()) worker.start();
  }

  @Override
  public void close() {
    worker.close();
    harness.close();
    for (AutoCloseable c : connections)
      try {
        c.close();
      } catch (Exception ignored) {
      }
  }
}
