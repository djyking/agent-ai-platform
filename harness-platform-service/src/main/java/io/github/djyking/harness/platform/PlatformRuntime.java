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
  public final CatalogService catalog;
  public final StudioService studio;
  public final KnowledgeService knowledge;
  public final CapabilityService capabilities;
  public final PlatformTrace traces;
  public final PlatformWorker worker;
  private final List<AutoCloseable> connections = new ArrayList<>();

  public PlatformRuntime(
      DataSource dataSource, Deployment config, SecretProvider secrets, IdentityProvider identity) {
    this(dataSource, config, secrets, identity, ProtectedOutputPolicy.legacyIdentity(identity));
  }

  public PlatformRuntime(
      DataSource dataSource,
      Deployment config,
      SecretProvider secrets,
      IdentityProvider identity,
      ProtectedOutputPolicy outputPolicy) {
    deployment = config;
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
    repository = new PlatformRepository(store);
    SignedTokens signing = new SignedTokens(secrets.resolve(config.signingSecret()));
    for (String reference : config.applicationSecrets().values()) secrets.resolve(reference);
    RuntimeAccess access = new RuntimeAccess(repository, identity);
    ToolRegistry tools = new ToolRegistry();
    java.util.concurrent.atomic.AtomicReference<StudioService> studioRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    var knowledgeSchema = Json.object().put("type", "object").put("additionalProperties", false);
    knowledgeSchema
        .putObject("properties")
        .putObject("query")
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", 6000);
    knowledgeSchema.putArray("required").add("query");
    tools.register(
        new ToolDescriptor(
            StudioCompiler.KNOWLEDGE_TOOL,
            "studio_knowledge",
            "Retrieve pinned authorized application knowledge",
            "studio",
            "knowledge",
            "1",
            knowledgeSchema,
            new ToolPolicy(true, false, false, 1, 5000, Set.of())),
        (descriptor, args, context) -> studioRef.get().retrieve(args, context));
    ModelRouter models = new ModelRouter();
    for (JsonNode model : config.models()) {
      String provider = model.path("provider").asText();
      models.register(provider, ConfiguredModels.create(model, secrets));
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
    for (Deployment.Release release : config.releases()) config.validateRelease(release, tools);
    // Do not publish a new immutable manifest until all capabilities and secrets are valid.
    repository.initialize(config);
    Set<String> catalogSecrets = new HashSet<>();
    catalogSecrets.add(secrets.resolve(config.signingSecret()));
    for (String reference : config.applicationSecrets().values())
      catalogSecrets.add(secrets.resolve(reference));
    for (JsonNode binding : config.models())
      if (binding.has("secretRef"))
        catalogSecrets.add(secrets.resolve(binding.path("secretRef").asText()));
    for (JsonNode binding : config.tools())
      if (binding.has("secretRef"))
        catalogSecrets.add(secrets.resolve(binding.path("secretRef").asText()));
    catalog = new CatalogService(config, repository, tools, catalogSecrets);
    knowledge = new KnowledgeService(config, repository);
    capabilities = new CapabilityService(config, repository, secrets);
    access.releaseCheck(
        (project, release) -> {
          catalog.requireExecution(project, release);
          Deployment.Release binding =
              repository.transaction(
                  c -> new CatalogRepository(repository).release(c, project, release));
          if (binding.model() != null)
            capabilities.assertModelEnabled(project, binding.model().provider());
          for (String key : binding.toolKeys()) capabilities.assertToolEnabled(project, key);
          if (studioRef.get() != null) studioRef.get().requireExecution(project, release);
        });
    traces = new PlatformTrace(dataSource);
    harness =
        new Harness(
            store,
            models,
            tools,
            access,
            traces,
            Clock.systemUTC(),
            Duration.ofMillis(config.leaseMillis()),
            new InvocationExecutor(config.concurrency(), config.concurrency() * 2));
    harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    service =
        new PlatformService(
            config,
            store,
            repository,
            harness,
            access,
            identity,
            signing,
            Clock.systemUTC(),
            ProtectedOutputPolicy.anyOf(
                outputPolicy,
                (reader, owned, output) ->
                    studioRef.get() != null
                        && studioRef.get().canReadOutput(reader, owned, output)));
    service.catalog(catalog);
    studio =
        new StudioService(
            repository,
            service,
            new StudioCompiler(config, tools, catalog, repository, catalogSecrets),
            knowledge,
            capabilities,
            identity);
    studioRef.set(studio);
    service.studio(studio);
    access.principalCheck(studio::requireRunExecution);
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
