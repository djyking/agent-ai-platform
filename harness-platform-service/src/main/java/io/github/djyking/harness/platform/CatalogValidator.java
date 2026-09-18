package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import io.github.djyking.harness.capabilities.workflow.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.integrations.opsagent.OpsAgentRagTool;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Typed catalog contracts and deterministic, network-free workflow replay publication gate. */
public final class CatalogValidator {
  public static final Set<String> TYPES =
      Set.of(
          "Agent",
          "ModelProfile",
          "Prompt",
          "ToolConnection",
          "ToolPolicy",
          "RetrievalProfile",
          "Workflow",
          "RunPolicy");
  private static final ObjectMapper STRICT =
      Json.MAPPER
          .copy()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  private static final Pattern CREDENTIAL =
      Pattern.compile(
          "(?is)(?:Bearer\\s+[A-Za-z0-9._~-]{12,}|(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{16,}|-----BEGIN"
              + " [^-]*PRIVATE"
              + " KEY-----|eyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}|(?:https?://)[^/\\s]+:[^/\\s]+@)");
  private static final Set<String> FORBIDDEN_FIELDS =
      Set.of(
          "secret",
          "secretref",
          "password",
          "passwd",
          "authorization",
          "accesstoken",
          "refreshtoken",
          "apikey",
          "clientsecret",
          "privatekey",
          "headers",
          "endpoint",
          "origin",
          "url",
          "credential",
          "credentials",
          "authentication");
  private final Deployment config;
  private final ToolRegistry tools;
  private final CatalogRepository repository;
  private final Set<String> knownSecrets;

  public record Compiled(Deployment.Release release, JsonNode references) {}

  public CatalogValidator(
      Deployment config,
      ToolRegistry tools,
      CatalogRepository repository,
      Set<String> knownSecrets) {
    this.config = config;
    this.tools = tools;
    this.repository = repository;
    this.knownSecrets = Set.copyOf(knownSecrets);
  }

  ToolRegistry registry() {
    return tools;
  }

  String bindingDigest(String project, String type, JsonNode spec) {
    return switch (type) {
      case "ToolConnection" ->
          "sha256:" + Json.hash(ApiJson.canonical(Json.tree(tool(project, spec))));
      case "ModelProfile" ->
          "sha256:" + Json.hash(ApiJson.canonical(Json.tree(model(project, spec))));
      default -> "";
    };
  }

  public static void fields(JsonNode value, Set<String> allowed, Set<String> required) {
    if (value == null || !value.isObject()) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    Set<String> names = new HashSet<>();
    value.fieldNames().forEachRemaining(names::add);
    if (!allowed.containsAll(names) || !names.containsAll(required))
      throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
  }

  public static String text(JsonNode value, String key) {
    JsonNode n = value.get(key);
    if (n == null || !n.isTextual() || n.asText().isBlank() || n.asText().length() > 10000)
      throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    return n.asText();
  }

  public static Set<String> strings(JsonNode value) {
    if (value == null || !value.isArray() || value.size() > 64)
      throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    Set<String> out = new TreeSet<>();
    for (JsonNode n : value)
      if (!n.isTextual()
          || n.asText().isBlank()
          || n.asText().length() > 240
          || !out.add(n.asText())) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    return out;
  }

  public static <T> T convert(JsonNode value, Class<T> type) {
    try {
      return STRICT.treeToValue(value, type);
    } catch (Exception ex) {
      throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    }
  }

  public void sanitize(JsonNode value) {
    sanitize(value, 0);
  }

  /** Drafts may be incomplete, but never become an untyped storage channel for hidden settings. */
  public void draftShape(String type, JsonNode spec) {
    Set<String> allowed =
        switch (type) {
          case "Agent" ->
              Set.of(
                  "workflow",
                  "runPolicy",
                  "toolPolicy",
                  "modelProfile",
                  "retrievalProfile",
                  "inputSchema",
                  "outputSchema",
                  "humanInputSchema");
          case "ModelProfile" -> Set.of("trustedProfile", "maxOutputTokens", "timeoutMillis");
          case "Prompt" -> Set.of("id", "version", "system", "user", "variables");
          case "ToolConnection" -> Set.of("toolKey");
          case "ToolPolicy" ->
              Set.of(
                  "toolConnections",
                  "executionPermissions",
                  "approvers",
                  "reviewFields",
                  "publicOutput");
          case "RetrievalProfile" -> Set.of("toolConnection");
          case "Workflow" -> Set.of("definition");
          case "RunPolicy" ->
              Set.of("maxTokens", "maxModelCalls", "maxToolCalls", "maxSteps", "lifetimeSeconds");
          default -> throw ApiFailure.invalid();
        };
    fields(spec, allowed, Set.of());
    for (String key :
        Set.of(
            "workflow",
            "runPolicy",
            "toolPolicy",
            "modelProfile",
            "retrievalProfile",
            "toolConnection"))
      if (spec.has(key)) fields(spec.get(key), Set.of("id", "version"), Set.of());
    if (spec.has("toolConnections")) {
      if (!spec.path("toolConnections").isArray())
        throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      for (JsonNode connection : spec.path("toolConnections"))
        fields(connection, Set.of("id", "version"), Set.of());
    }
    if (type.equals("Workflow") && spec.has("definition")) {
      JsonNode definition = spec.get("definition");
      fields(definition, Set.of("id", "version", "start", "maxTransitions", "nodes"), Set.of());
      if (definition.has("nodes")) {
        if (!definition.path("nodes").isObject()) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
        for (JsonNode node : definition.path("nodes")) {
          Set<String> nodeFields =
              switch (node.path("kind").asText()) {
                case "tool" ->
                    Set.of(
                        "kind", "toolName", "argumentsJson", "argumentBindings", "output", "next");
                case "model" -> Set.of("kind", "promptRef", "inputBindings", "output", "next");
                case "agent" ->
                    Set.of(
                        "kind",
                        "promptRef",
                        "inputBindings",
                        "allowedTools",
                        "maxTurns",
                        "output",
                        "next");
                case "condition" ->
                    Set.of("kind", "variable", "equalsJson", "whenTrue", "whenFalse");
                case "human" -> Set.of("kind", "message", "output", "next");
                case "end" -> Set.of("kind", "output");
                default -> throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
              };
          fields(node, nodeFields, Set.of("kind"));
          if (node.has("promptRef"))
            fields(node.get("promptRef"), Set.of("id", "version"), Set.of());
        }
      }
    }
  }

  private void sanitize(JsonNode value, int depth) {
    if (depth > 16) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    if (value.isObject()) {
      var it = value.fields();
      while (it.hasNext()) {
        var e = it.next();
        String normalized = e.getKey().replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
        if (FORBIDDEN_FIELDS.contains(normalized))
          throw new ApiFailure(422, "CATALOG_CREDENTIAL_OR_ENDPOINT_FORBIDDEN");
        if (Set.of("argumentsJson", "equalsJson").contains(e.getKey())) {
          if (!e.getValue().isTextual()) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
          try {
            JsonNode encoded = ApiJson.MAPPER.readTree(e.getValue().asText());
            if (encoded == null) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
            sanitize(encoded, depth + 1);
          } catch (ApiFailure failure) {
            throw failure;
          } catch (Exception invalid) {
            // Never fall back to Jackson's permissive workflow constructor for malformed or
            // duplicate-key encoded arguments, which could hide credential-bearing fields.
            throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
          }
        }
        sanitize(e.getValue(), depth + 1);
      }
    } else if (value.isArray()) {
      for (JsonNode n : value) sanitize(n, depth + 1);
    } else if (value.isTextual()) {
      String s = value.asText();
      if (CREDENTIAL.matcher(s).find()
          || knownSecrets.stream().anyMatch(k -> k.length() >= 8 && s.contains(k)))
        throw new ApiFailure(422, "CATALOG_CREDENTIAL_OR_ENDPOINT_FORBIDDEN");
      // Arguments/conditions are JSON-encoded strings in the existing workflow contract.
      String trimmed = s.trim();
      if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
          || (trimmed.startsWith("[") && trimmed.endsWith("]")))
        try {
          sanitize(ApiJson.MAPPER.readTree(trimmed), depth + 1);
        } catch (ApiFailure ex) {
          throw ex;
        } catch (Exception ignored) {
        }
    }
  }

  private Set<String> permittedToolKeys(String project) {
    Set<String> keys = new TreeSet<>();
    for (var r : config.releases()) if (r.projectId().equals(project)) keys.addAll(r.toolKeys());
    for (JsonNode t : config.tools())
      if (t.path("projects").isArray() && strings(t.path("projects")).contains(project)) {
        if (t.has("key")) keys.add(t.path("key").asText());
        if (t.path("kind").asText().equals("opsagent-rag")) keys.add(OpsAgentRagTool.KEY);
        for (JsonNode b : t.path("bindings"))
          keys.add("mcp:" + t.path("serverId").asText() + ":" + b.path("remoteName").asText());
      }
    return keys;
  }

  public Map<String, ModelProfile> trustedModels(String project) {
    Map<String, ModelProfile> result = new TreeMap<>();
    for (var r : config.releases())
      if (r.projectId().equals(project) && r.model() != null) {
        var old = result.putIfAbsent(r.model().id(), r.model());
        if (old != null && !old.equals(r.model()))
          throw new IllegalStateException("Ambiguous trusted model profile");
      }
    return result;
  }

  public JsonNode capabilities(String project) {
    ObjectNode out = Json.object();
    out.set("types", Json.tree(new TreeSet<>(TYPES)));
    ArrayNode safeModels = out.putArray("trustedModels");
    for (ModelProfile model : trustedModels(project).values()) {
      ObjectNode safe = Json.MAPPER.valueToTree(model);
      safe.remove("parameters");
      safeModels.add(safe);
    }
    ArrayNode trusted = out.putArray("trustedTools");
    for (String key : permittedToolKeys(project)) {
      var d = tools.require(key).descriptor();
      ObjectNode t =
          Json.object()
              .put("key", key)
              .put("modelName", d.modelName())
              .put("readOnly", d.policy().readOnly())
              .put("approvalRequired", d.policy().approvalRequired());
      t.set("inputSchema", d.inputSchema());
      trusted.add(t);
    }
    ObjectNode templates = out.putObject("templates");
    templates.set(
        "Agent",
        Json.read(
            "{\"workflow\":{\"id\":\"sample-workflow\",\"version\":1},\"runPolicy\":{\"id\":\"sample-limits\",\"version\":1},\"toolPolicy\":{\"id\":\"sample-policy\",\"version\":1},\"inputSchema\":{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false},\"outputSchema\":{\"type\":\"string\"}}"));
    templates.set(
        "Workflow",
        Json.read(
            "{\"definition\":{\"id\":\"sample\",\"version\":\"1\",\"start\":\"end\",\"maxTransitions\":10,\"nodes\":{\"end\":{\"kind\":\"end\",\"output\":\"answer\"}}}}"));
    templates.set(
        "Prompt",
        Json.read(
            "{\"id\":\"sample\",\"version\":\"1\",\"system\":\"Answer from the provided"
                + " context.\",\"user\":\"{{question}}\",\"variables\":{\"question\":\"STRING\"}}"));
    templates.set(
        "ToolPolicy",
        Json.read(
            "{\"toolConnections\":[],\"executionPermissions\":[],\"approvers\":[],\"reviewFields\":[],\"publicOutput\":false}"));
    templates.set("RunPolicy", Json.tree(new Deployment.Limits(10000, 4, 8, 100, 3600)));
    templates.set(
        "ModelProfile",
        Json.object()
            .put(
                "trustedProfile",
                trustedModels(project).keySet().stream()
                    .findFirst()
                    .orElse("select-trusted-profile")));
    templates.set(
        "ToolConnection",
        Json.object()
            .put(
                "toolKey",
                permittedToolKeys(project).stream().findFirst().orElse("select-trusted-tool")));
    templates.set(
        "RetrievalProfile",
        Json.read("{\"toolConnection\":{\"id\":\"retrieval-tool\",\"version\":1}}"));
    out.set(
        "regressionCaseTemplate",
        Json.read(
            "{\"name\":\"literal-answer\",\"inputs\":{\"answer\":\"Synthetic"
                + " answer\"},\"toolResults\":{},\"modelResults\":{},\"humanInputs\":{},\"expectedOutput\":\"Synthetic"
                + " answer\",\"expectedToolKeys\":[]}"));
    return out;
  }

  private final class Resolution {
    final Connection c;
    final String project;
    final Map<String, JsonNode> refs = new TreeMap<>();

    Resolution(Connection c, String project) {
      this.c = c;
      this.project = project;
    }

    JsonNode ref(String type, JsonNode ref) throws SQLException {
      fields(ref, Set.of("id", "version"), Set.of("id", "version"));
      String id = text(ref, "id");
      ApiJson.identifier(id);
      if (!ref.path("version").isInt() || ref.path("version").asInt() < 1)
        throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      int version = ref.path("version").asInt();
      ObjectNode resource = repository.resource(c, project, type, id, true);
      ObjectNode v = repository.version(c, project, type, id, version);
      if (resource.path("disabled").asBoolean()
          || resource.path("revoked").asBoolean()
          || v.path("disabled").asBoolean()) throw new ApiFailure(409, "RESOURCE_UNAVAILABLE");
      ObjectNode pinned =
          Json.object()
              .put("type", type)
              .put("id", id)
              .put("version", version)
              .put("digest", v.path("digest").asText());
      if (v.has("bindingDigest")) {
        String current = bindingDigest(project, type, v.path("spec"));
        if (!v.path("bindingDigest").asText().equals(current))
          throw new ApiFailure(409, "TRUSTED_BINDING_CHANGED");
        pinned.put("bindingDigest", current);
      }
      refs.put(type + "/" + id + "/" + version, pinned);
      return v.path("spec");
    }
  }

  public void validate(Connection c, String project, String type, JsonNode spec)
      throws SQLException {
    sanitize(spec);
    Resolution r = new Resolution(c, project);
    switch (type) {
      case "Agent" -> compile(c, project, "validation-agent", UUID.randomUUID().toString(), spec);
      case "Prompt" -> convert(spec, PromptTemplate.class);
      case "ModelProfile" -> model(project, spec);
      case "ToolConnection" -> tool(project, spec);
      case "ToolPolicy" -> policy(r, spec);
      case "RetrievalProfile" -> retrieval(r, spec);
      case "RunPolicy" -> convert(spec, Deployment.Limits.class);
      case "Workflow" -> workflow(r, spec);
      default -> throw ApiFailure.invalid();
    }
  }

  private ModelProfile model(String project, JsonNode spec) {
    fields(
        spec,
        Set.of("trustedProfile", "maxOutputTokens", "timeoutMillis"),
        Set.of("trustedProfile"));
    ModelProfile base = trustedModels(project).get(text(spec, "trustedProfile"));
    if (base == null) throw new ApiFailure(422, "TRUSTED_BINDING_REQUIRED");
    int output = base.maxOutputTokens();
    long timeout = base.timeoutMillis();
    if (spec.has("maxOutputTokens")) {
      if (!spec.path("maxOutputTokens").isInt()) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      output = spec.path("maxOutputTokens").asInt();
    }
    if (spec.has("timeoutMillis")) {
      if (!spec.path("timeoutMillis").isIntegralNumber())
        throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      timeout = spec.path("timeoutMillis").asLong();
    }
    if (output < 1
        || output > base.maxOutputTokens()
        || timeout < 1
        || timeout > base.timeoutMillis()
        || timeout + 1000 >= config.leaseMillis())
      throw new ApiFailure(422, "TRUSTED_BINDING_REQUIRED");
    return new ModelProfile(
        base.id(),
        base.provider(),
        base.model(),
        base.contextTokens(),
        output,
        timeout,
        base.parameters());
  }

  private ToolDescriptor tool(String project, JsonNode spec) {
    fields(spec, Set.of("toolKey"), Set.of("toolKey"));
    String key = text(spec, "toolKey");
    if (!permittedToolKeys(project).contains(key))
      throw new ApiFailure(422, "TRUSTED_BINDING_REQUIRED");
    var d = tools.require(key).descriptor();
    if (d.policy().timeoutMillis() + 1000 >= config.leaseMillis())
      throw new ApiFailure(422, "TRUSTED_BINDING_REQUIRED");
    return d;
  }

  private Set<String> policy(Resolution r, JsonNode spec) throws SQLException {
    fields(
        spec,
        Set.of(
            "toolConnections", "executionPermissions", "approvers", "reviewFields", "publicOutput"),
        Set.of(
            "toolConnections",
            "executionPermissions",
            "approvers",
            "reviewFields",
            "publicOutput"));
    if (!spec.path("toolConnections").isArray()
        || spec.path("toolConnections").size() > 64
        || !spec.path("publicOutput").isBoolean())
      throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    Set<String> keys = new TreeSet<>();
    for (JsonNode ref : spec.path("toolConnections"))
      keys.add(tool(r.project, r.ref("ToolConnection", ref)).key());
    Set<String> permissions = strings(spec.path("executionPermissions"));
    strings(spec.path("reviewFields"));
    for (String approver : strings(spec.path("approvers"))) {
      String[] a = approver.split("/", -1);
      if (a.length != 2
          || !config.project(r.project).applications().contains(a[0])
          || !a[1].matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
        throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    }
    Set<String> allowed = new TreeSet<>(Set.of("model:invoke"));
    for (String key : keys) {
      ToolDescriptor d = tools.require(key).descriptor();
      allowed.add("tool:" + key);
      allowed.addAll(d.policy().requiredPermissions());
      if (!permissions.contains("tool:" + key)
          || !permissions.containsAll(d.policy().requiredPermissions()))
        throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      if (d.policy().approvalRequired() && spec.path("approvers").isEmpty())
        throw new ApiFailure(422, "APPROVER_REQUIRED");
    }
    if (!allowed.containsAll(permissions)) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    if (spec.path("publicOutput").asBoolean())
      for (String key : keys)
        if (key.equals(OpsAgentRagTool.KEY)
            || config.releases().stream()
                .noneMatch(
                    v ->
                        v.projectId().equals(r.project)
                            && v.publicOutput()
                            && v.toolKeys().contains(key)))
          throw new ApiFailure(422, "PROTECTED_OUTPUT_REQUIRED");
    return keys;
  }

  private String retrieval(Resolution r, JsonNode spec) throws SQLException {
    fields(spec, Set.of("toolConnection"), Set.of("toolConnection"));
    var d = tool(r.project, r.ref("ToolConnection", spec.path("toolConnection")));
    if (!d.policy().readOnly()
        || !(d.key().equals(OpsAgentRagTool.KEY)
            || config.tools().stream()
                .anyMatch(
                    t ->
                        t.path("kind").asText().equals("lexical-rag")
                            && t.path("key").asText().equals(d.key()))))
      throw new ApiFailure(422, "RETRIEVAL_BINDING_REQUIRED");
    return d.key();
  }

  private WorkflowDefinition workflow(Resolution r, JsonNode spec) throws SQLException {
    fields(spec, Set.of("definition"), Set.of("definition"));
    JsonNode definition = spec.path("definition").deepCopy();
    if (!definition.isObject()
        || !definition.path("nodes").isObject()
        || definition.path("nodes").size() > 100) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
    var nodes = definition.path("nodes").fields();
    while (nodes.hasNext()) {
      var entry = nodes.next();
      if (!entry.getValue().isObject()) throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
      ObjectNode node = (ObjectNode) entry.getValue();
      String kind = node.path("kind").asText();
      if (Set.of("model", "agent").contains(kind)) {
        if (node.has("prompt") || !node.has("promptRef"))
          throw new ApiFailure(422, "PROMPT_REFERENCE_REQUIRED");
        JsonNode prompt = r.ref("Prompt", node.remove("promptRef"));
        convert(prompt, PromptTemplate.class);
        node.set("prompt", prompt);
      }
    }
    WorkflowDefinition workflow = convert(definition, WorkflowDefinition.class);
    // Every persisted node must be reachable; publication cannot hide dormant capabilities.
    Set<String> visited = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.add(workflow.start());
    while (!todo.isEmpty()) {
      String at = todo.remove();
      if (visited.add(at)) todo.addAll(workflow.nodes().get(at).targets());
    }
    if (visited.size() != workflow.nodes().size())
      throw new ApiFailure(422, "WORKFLOW_UNREACHABLE_NODE");
    return workflow;
  }

  public Compiled compile(Connection c, String project, String id, String releaseId, JsonNode spec)
      throws SQLException {
    fields(
        spec,
        Set.of(
            "workflow",
            "runPolicy",
            "toolPolicy",
            "modelProfile",
            "retrievalProfile",
            "inputSchema",
            "outputSchema",
            "humanInputSchema"),
        Set.of("workflow", "runPolicy", "toolPolicy", "inputSchema", "outputSchema"));
    Resolution r = new Resolution(c, project);
    WorkflowDefinition workflow = workflow(r, r.ref("Workflow", spec.path("workflow")));
    JsonNode policy = r.ref("ToolPolicy", spec.path("toolPolicy"));
    Set<String> keys = policy(r, policy);
    if (spec.has("retrievalProfile")
        && !keys.contains(retrieval(r, r.ref("RetrievalProfile", spec.path("retrievalProfile")))))
      throw new ApiFailure(422, "RETRIEVAL_BINDING_REQUIRED");
    ModelProfile profile =
        spec.has("modelProfile")
            ? model(project, r.ref("ModelProfile", spec.path("modelProfile")))
            : null;
    Deployment.Release release =
        new Deployment.Release(
            project,
            id,
            releaseId,
            workflow,
            profile,
            spec.path("inputSchema"),
            spec.path("outputSchema"),
            keys,
            strings(policy.path("executionPermissions")),
            strings(policy.path("approvers")),
            new ArrayList<>(strings(policy.path("reviewFields"))),
            spec.get("humanInputSchema"),
            policy.path("publicOutput").asBoolean(),
            convert(r.ref("RunPolicy", spec.path("runPolicy")), Deployment.Limits.class));
    config.validateRelease(release, tools);
    if (workflow.nodes().values().stream().anyMatch(n -> n instanceof WorkflowDefinition.Human)
        && release.approvers().isEmpty()) throw new ApiFailure(422, "APPROVER_REQUIRED");
    return new Compiled(release, Json.tree(r.refs.values()));
  }

  public JsonNode regression(Deployment.Release release, JsonNode cases) {
    if (!cases.isArray() || cases.isEmpty() || cases.size() > 20)
      throw new ApiFailure(422, "REGRESSION_CASE_REQUIRED");
    ArrayNode results = Json.MAPPER.createArrayNode();
    boolean all = true;
    for (JsonNode test : cases) {
      fields(
          test,
          Set.of(
              "name",
              "inputs",
              "toolResults",
              "modelResults",
              "humanInputs",
              "expectedOutput",
              "expectedToolKeys",
              "expectedToolArguments",
              "expectedPrompts"),
          Set.of("name", "inputs", "expectedOutput", "expectedToolKeys"));
      ObjectNode result = Json.object().put("name", text(test, "name"));
      try {
        ObjectNode actual = replay(release, test);
        boolean pass =
            actual.path("output").equals(test.path("expectedOutput"))
                && strings(test.path("expectedToolKeys")).equals(strings(actual.path("toolKeys")));
        if (!pass) all = false;
        result.put("passed", pass).put("code", pass ? "MATCH" : "EXPECTATION_MISMATCH");
        result.set("actual", actual);
        result.put(
            "expectedDigest",
            "sha256:" + Json.hash(ApiJson.canonical(test.path("expectedOutput"))));
      } catch (RuntimeException ex) {
        all = false;
        result
            .put("passed", false)
            .put("code", ex instanceof ApiFailure f ? f.code : "REPLAY_FAILED");
      }
      results.add(result);
    }
    ObjectNode report =
        Json.object()
            .put("passed", all)
            .put("mode", "FROZEN_REPLAY_NO_EXTERNAL_CALLS")
            .put("caseCount", cases.size())
            .put("casesDigest", "sha256:" + Json.hash(ApiJson.canonical(cases)));
    report.set("results", results);
    report.put("resultDigest", "sha256:" + Json.hash(ApiJson.canonical(results)));
    return report;
  }

  private ObjectNode replay(Deployment.Release release, JsonNode test) {
    release.validateInputs(test.path("inputs"));
    RunState run = new RunState();
    run.id = "catalog-replay";
    run.actor = new Actor("catalog-replay", release.projectId(), release.executionPermissions());
    run.definition = release.definition(test.path("inputs"));
    run.budget = release.limits().budget();
    run.tools = tools.snapshot(release.toolKeys());
    WorkflowProgram program = new WorkflowProgram();
    Set<String> toolKeys = new TreeSet<>();
    ArrayNode trace = Json.MAPPER.createArrayNode();
    for (int i = 0; i < Math.min(2000, release.limits().maxSteps()); i++) {
      Action action = program.next(run);
      run.steps++;
      if (action instanceof ContinueAction) continue;
      if (action instanceof FailAction) throw new ApiFailure(422, "WORKFLOW_REPLAY_FAILED");
      if (action instanceof CompleteAction done) {
        if (!SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(release.outputSchema())
            .validate(done.output())
            .isEmpty()) throw new ApiFailure(422, "OUTPUT_SCHEMA_MISMATCH");
        ObjectNode out = Json.object();
        out.set("output", done.output());
        out.set("toolKeys", Json.tree(toolKeys));
        out.set("trace", trace);
        out.put("steps", run.steps);
        return out;
      }
      if (action instanceof ToolAction tool) {
        if (!release.toolKeys().contains(tool.toolKey()))
          throw new ApiFailure(422, "TOOL_OUTSIDE_RELEASE");
        ToolDescriptor d = tools.require(tool.toolKey()).descriptor();
        ToolRegistry.validate(d, tool.arguments());
        if (!test.path("toolResults").has(tool.nodeId())
            || !test.path("expectedToolArguments").has(tool.nodeId()))
          throw new ApiFailure(422, "FROZEN_TOOL_CASE_REQUIRED");
        if (!tool.arguments().equals(test.path("expectedToolArguments").path(tool.nodeId())))
          throw new ApiFailure(422, "TOOL_ARGUMENT_MISMATCH");
        toolKeys.add(tool.toolKey());
        run.toolCalls++;
        if (run.toolCalls > release.limits().maxToolCalls())
          throw new ApiFailure(422, "REPLAY_BUDGET_EXCEEDED");
        run.results.put(
            tool.id(), new StepResult("TOOL", test.path("toolResults").get(tool.nodeId()), false));
        trace.add(
            Json.object()
                .put("node", tool.nodeId())
                .put("kind", "TOOL")
                .put("key", tool.toolKey())
                .put("argumentsDigest", Json.hash(ApiJson.canonical(tool.arguments()))));
      } else if (action instanceof ModelAction model) {
        JsonNode answer = test.path("modelResults").get(model.nodeId());
        if (answer == null || !answer.isTextual())
          throw new ApiFailure(422, "FROZEN_MODEL_CASE_REQUIRED");
        run.modelCalls++;
        if (run.modelCalls > release.limits().maxModelCalls())
          throw new ApiFailure(422, "REPLAY_BUDGET_EXCEEDED");
        String prompt = ApiJson.canonical(Json.tree(model.request().messages()));
        if (!test.path("expectedPrompts").has(model.nodeId()))
          throw new ApiFailure(422, "EXPECTED_PROMPT_REQUIRED");
        if (!Json.tree(model.request().messages())
            .equals(test.path("expectedPrompts").path(model.nodeId())))
          throw new ApiFailure(422, "PROMPT_MISMATCH");
        ModelResponse response =
            new ModelResponse(
                Message.text("assistant", answer.asText()),
                FinishReason.FINAL,
                new Usage(0, 0, true));
        run.results.put(model.id(), new StepResult("MODEL", Json.tree(response), false));
        trace.add(
            Json.object()
                .put("node", model.nodeId())
                .put("kind", "MODEL")
                .put("promptDigest", Json.hash(prompt)));
      } else if (action instanceof WaitAction wait) {
        JsonNode input = test.path("humanInputs").get(wait.nodeId());
        if (input == null
            || !input.isTextual()
            || !SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(release.humanInputSchema())
                .validate(input)
                .isEmpty()) throw new ApiFailure(422, "FROZEN_HUMAN_CASE_REQUIRED");
        run.results.put(
            wait.id(), new StepResult("HUMAN", Json.object().put("input", input.asText()), false));
        trace.add(Json.object().put("node", wait.nodeId()).put("kind", "HUMAN"));
      }
    }
    throw new ApiFailure(422, "REPLAY_BUDGET_EXCEEDED");
  }
}
