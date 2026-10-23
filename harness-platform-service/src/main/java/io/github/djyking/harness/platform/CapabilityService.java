package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.http.*;
import io.github.djyking.harness.adapters.mcp.*;
import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Project lifecycle for deployment-approved destinations. Browser input never supplies URLs or
 * secrets.
 */
public final class CapabilityService {
  private final Deployment config;
  private final PlatformRepository platform;
  private final CapabilityRepository repository;
  private final SecretProvider secrets;
  private final Map<String, JsonNode> bindings = new LinkedHashMap<>();

  public CapabilityService(Deployment config, PlatformRepository platform, SecretProvider secrets) {
    this.config = config;
    this.platform = platform;
    this.secrets = secrets;
    repository = new CapabilityRepository(platform);
    repository.initialize();
    for (JsonNode model : config.models()) add("model", model.path("provider").asText(), model);
    for (JsonNode tool : config.tools())
      if (tool.path("kind").asText().equals("mcp"))
        add("mcp", tool.path("serverId").asText(), tool);
  }

  private void add(String kind, String id, JsonNode source) {
    ApiJson.identifier(id);
    if (bindings.putIfAbsent(kind + "/" + id, source.deepCopy()) != null)
      throw new IllegalArgumentException("Duplicate approved capability");
  }

  public JsonNode list(IdentityProvider.Principal p) {
    CapabilityInput.require(config, p, "catalog:read");
    return platform.transaction(
        c -> {
          var out = Json.object();
          var items = out.putArray("items");
          for (String key : bindings.keySet()) {
            var split = key.split("/", 2);
            ObjectNode state =
                repository.get(c, p.project(), "connection-" + split[0], split[1], false);
            items.add(view(split[0], split[1], state));
          }
          out.put("targetPolicy", "DEPLOYMENT_APPROVED_ONLY")
              .put("discoveryGrantsExecution", false);
          return out;
        });
  }

  public JsonNode command(IdentityProvider.Principal p, String key) {
    CapabilityInput.require(config, p, "catalog:read");
    return repository.command(p, key, false);
  }

  public JsonNode get(IdentityProvider.Principal p, String kind, String id) {
    CapabilityInput.require(config, p, "catalog:read");
    binding(kind, id);
    return platform.transaction(
        c -> view(kind, id, repository.get(c, p.project(), "connection-" + kind, id, false)));
  }

  public JsonNode save(
      IdentityProvider.Principal p,
      String kind,
      String id,
      String key,
      String etag,
      JsonNode body) {
    CapabilityInput.require(config, p, "catalog:publish");
    binding(kind, id);
    CapabilityInput.fields(body, Set.of("name", "enabled"), Set.of("name", "enabled"));
    String name = CapabilityInput.text(body, "name", 200);
    boolean enabled = CapabilityInput.bool(body, "enabled");
    return repository.mutate(
        p,
        "connection-" + kind,
        id,
        "save",
        key,
        etag,
        body,
        (c, previous) -> {
          var next =
              previous == null
                  ? Json.object().put("id", id).put("kind", kind)
                  : previous.deepCopy();
          next.put("name", name)
              .put("enabled", enabled)
              .put("approvedBindingDigest", approvedDigest(kind, id));
          next.remove("diagnostic");
          return next;
        });
  }

  /**
   * Connection diagnostics discover metadata only: no model generation and no MCP tool invocation.
   */
  public JsonNode diagnose(
      IdentityProvider.Principal p,
      String kind,
      String id,
      String key,
      String etag,
      JsonNode body) {
    CapabilityInput.require(config, p, "catalog:validate");
    binding(kind, id);
    CapabilityInput.fields(body, Set.of(), Set.of());
    // Read-only remote diagnostics are bounded; hold the project lock so a concurrent disable
    // wins
    // before or after this operation, never halfway through a state update. No retries occur.
    return repository.mutate(
        p,
        "connection-" + kind,
        id,
        "diagnose",
        key,
        etag,
        body,
        (c, previous) -> {
          var next =
              previous == null
                  ? Json.object()
                      .put("id", id)
                      .put("kind", kind)
                      .put("name", id)
                      .put("enabled", true)
                  : previous.deepCopy();
          if (!next.path("enabled").asBoolean()) throw new ApiFailure(409, "CONNECTION_DISABLED");
          if (previous != null
              && !next.path("approvedBindingDigest").asText().equals(approvedDigest(kind, id)))
            throw new ApiFailure(409, "CONNECTION_BINDING_CHANGED");
          JsonNode diagnostic = diagnostic(kind, id, next.path("discovery"));
          if (diagnostic.has("tools")) next.set("discovery", diagnostic.path("tools"));
          next.set("diagnostic", diagnostic);
          next.put("approvedBindingDigest", approvedDigest(kind, id));
          return next;
        });
  }

  public void assertModelEnabled(String project, String provider) {
    assertEnabled(project, "model", provider);
  }

  public void assertToolEnabled(String project, String toolKey) {
    // Only MCP connections have this lifecycle. Locally registered tools retain their own
    // policies.
    for (var entry : bindings.entrySet()) {
      if (!entry.getKey().startsWith("mcp/")) continue;
      String id = entry.getKey().substring(4);
      if (toolKey.startsWith("mcp:" + id + ":")) assertEnabled(project, "mcp", id);
    }
  }

  private void assertEnabled(String project, String kind, String id) {
    config.project(project);
    binding(kind, id);
    boolean enabled =
        platform.transaction(
            c -> {
              var value = repository.get(c, project, "connection-" + kind, id, false);
              return value == null
                  || (value.path("enabled").asBoolean()
                      && value
                          .path("approvedBindingDigest")
                          .asText()
                          .equals(approvedDigest(kind, id)));
            });
    // Expected availability filtering can occur inside a larger read transaction.
    if (!enabled) throw new ApiFailure(409, "CONNECTION_DISABLED_OR_CHANGED");
  }

  private JsonNode binding(String kind, String id) {
    ApiJson.identifier(id);
    JsonNode value = bindings.get(kind + "/" + id);
    if (value == null) throw new ApiFailure(404, "APPROVED_CONNECTION_NOT_FOUND");
    return value;
  }

  private String approvedDigest(String kind, String id) {
    return "sha256:" + Json.hash(ApiJson.canonical(binding(kind, id)));
  }

  private ObjectNode view(String kind, String id, ObjectNode state) {
    JsonNode approved = binding(kind, id);
    ObjectNode out =
        state == null
            ? Json.object()
                .put("id", id)
                .put("kind", kind)
                .put("name", id)
                .put("enabled", true)
                .put("revision", 0)
            : state.deepCopy();
    out.put("adapter", approved.path("kind").asText())
        .put("credentialConfigured", approved.hasNonNull("secretRef"));
    out.put("targetApproved", true).put("approvedBindingDigest", approvedDigest(kind, id));
    if (state != null
        && !state.path("approvedBindingDigest").asText().equals(approvedDigest(kind, id)))
      out.put("enabled", false).put("bindingChanged", true);
    // The original secret reference and any returned authorization values are never exposed.
    return out;
  }

  private ObjectNode diagnostic(String kind, String id, JsonNode previousTools) {
    long start = System.nanoTime();
    ObjectNode out =
        Json.object()
            .put("checkedAt", Instant.now().toString())
            .put("mode", kind.equals("mcp") ? "MCP_DISCOVERY_ONLY" : "MODEL_CATALOG_ONLY");
    try {
      JsonNode b = binding(kind, id);
      URI endpoint = HttpEndpoints.requireSecureOrLoopback(URI.create(b.path("endpoint").asText()));
      String credential = secrets.resolve(b.path("secretRef").asText());
      if (kind.equals("mcp")) {
        try (var connection =
            McpConnection.connect(
                new McpConnectionConfig(
                    id,
                    endpoint,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    256 * 1024,
                    2,
                    100,
                    target -> {
                      if (!target.equals(endpoint))
                        throw new IllegalStateException("Unapproved diagnostic destination");
                      return Map.of("Authorization", "Bearer " + credential);
                    }))) {
          var tools = Json.MAPPER.createArrayNode();
          for (var tool : connection.discover()) {
            var value = Json.object().put("name", tool.name()).put("executionApproved", false);
            value.set("inputSchema", Json.tree(tool.inputSchema()));
            value.set("outputSchema", Json.tree(tool.outputSchema()));
            value.put(
                "schemaDigest",
                "sha256:"
                    + Json.hash(
                        List.of(
                            ApiJson.canonical(value.path("inputSchema")),
                            ApiJson.canonical(value.path("outputSchema")))));
            // Remote descriptions can contain secrets or untrusted instructions;
            // persist only schema.
            tools.add(value);
          }
          if (Json.write(tools).length() > 48000 || Json.write(tools).contains(credential))
            throw new IllegalArgumentException("Discovery rejected");
          out.set("tools", tools);
          out.set("schemaDiff", schemaDiff(previousTools, tools));
          out.put("reachable", true).put("toolCount", tools.size()).put("executionApproved", false);
        }
      } else {
        if (!Set.of("deepseek", "openai-compatible").contains(b.path("kind").asText()))
          throw new IllegalArgumentException("Unsupported model diagnostic");
        String path = endpoint.getRawPath();
        if (!path.endsWith("/chat/completions"))
          throw new IllegalArgumentException("Approved model endpoint must use chat completions");
        URI target =
            URI.create(
                endpoint.getScheme()
                    + "://"
                    + endpoint.getRawAuthority()
                    + path.substring(0, path.length() - "/chat/completions".length())
                    + "/models");
        var request =
            HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + credential)
                .header("Accept", "application/json")
                .GET()
                .build();
        var client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending =
            client.sendAsync(request, new LimitedBodyHandler(256 * 1024));
        HttpResponse<byte[]> response;
        try {
          response = pending.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
          pending.cancel(true);
          throw ex;
        }
        out.put("httpStatus", response.statusCode());
        if (response.statusCode() != 200)
          throw new IllegalStateException("Model catalog unavailable");
        JsonNode data = Json.MAPPER.readTree(response.body()).path("data");
        if (!data.isArray() || data.size() > 1000)
          throw new IllegalStateException("Model catalog invalid");
        out.put("reachable", true).put("modelCount", data.size()).put("generationVerified", false);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      out.put("reachable", false).put("code", "CONNECTION_CHECK_INTERRUPTED");
    } catch (Exception ex) {
      out.remove("tools");
      out.put("reachable", false).put("code", "CONNECTION_CHECK_FAILED");
    }
    out.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    return out;
  }

  private static JsonNode schemaDiff(JsonNode previous, JsonNode current) {
    Map<String, String> before = new TreeMap<>(), after = new TreeMap<>();
    if (previous.isArray())
      for (JsonNode t : previous)
        before.put(t.path("name").asText(), t.path("schemaDigest").asText());
    for (JsonNode t : current) after.put(t.path("name").asText(), t.path("schemaDigest").asText());
    var out = Json.object();
    var added = out.putArray("added");
    var changed = out.putArray("changed");
    var removed = out.putArray("removed");
    after.forEach(
        (name, digest) -> {
          if (!before.containsKey(name)) added.add(name);
          else if (!before.get(name).equals(digest)) changed.add(name);
        });
    before.keySet().stream().filter(name -> !after.containsKey(name)).forEach(removed::add);
    return out;
  }
}
