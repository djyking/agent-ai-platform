package io.github.djyking.harness.integrations.opsagent;

import static io.github.djyking.harness.core.Contracts.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.core.Json;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Existing OpsAgent internal RAG contract, verified from source on 2026-09-15. It retrieves
 * authorized evidence; it neither runs the old AgentLoop nor generates a model answer. OpsAgent may
 * use its own configured reranker. Exactly one HTTP attempt is made; Harness remains the execution
 * owner.
 */
public final class OpsAgentRagTool implements ToolHandler {
  public static final String KEY = "opsagent/rag-search";
  public static final String PERMISSION = "opsagent:rag:search";
  public static final String AUDIENCE = "rag";
  private final OpsAgentRagConfig config;
  private final OpsAgentAuthorization authorization;
  private final HttpClient client;
  private final ToolDescriptor descriptor;

  public OpsAgentRagTool(OpsAgentRagConfig config, OpsAgentAuthorization authorization) {
    this.config = Objects.requireNonNull(config);
    this.authorization = Objects.requireNonNull(authorization);
    client =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    descriptor =
        new ToolDescriptor(
            KEY,
            "opsagent_rag_search",
            "Search OpsAgent knowledge visible to the authenticated actor and return evidence with"
                + " citations",
            "opsagent-internal-http",
            config.endpoint().toString(),
            "2026-09-15-v1",
            Json.read(
                """
                {"type":"object","properties":{
                  "query":{"type":"string","minLength":1,"maxLength":2000},
                  "topK":{"type":"integer","minimum":1,"maximum":20}},
                 "required":["query","topK"],"additionalProperties":false}
                """),
            new ToolPolicy(
                true, false, false, 1, config.requestTimeout().toMillis(), Set.of(PERMISSION)));
  }

  public ToolDescriptor descriptor() {
    return Json.copy(descriptor, ToolDescriptor.class);
  }

  @Override
  public ToolResult invoke(ToolDescriptor tool, JsonNode arguments, ExecutionContext context) {
    if (!descriptor.digest().equals(tool.digest()))
      throw failure(FailureKind.INVALID, "OPSAGENT_TOOL_CONTRACT_CHANGED");
    validateArguments(arguments);
    // The runtime normally performs this check; retain it for direct host calls as well.
    AccessPolicy.actorPermissions().check(context.actor(), PERMISSION, KEY);
    requireRemaining(context);
    final String credential;
    try {
      credential = authorization.authorization(config.endpoint(), AUDIENCE, context);
    } catch (RuntimeException failure) {
      // Identity brokers may include sensitive claims in their exception messages.
      throw failure(FailureKind.DENIED, "OPSAGENT_IDENTITY_UNAVAILABLE");
    }
    if (credential == null
        || credential.length() > 10000
        || !credential.matches("Bearer [A-Za-z0-9._~-]+"))
      throw failure(FailureKind.DENIED, "OPSAGENT_INTERNAL_AUTH_REQUIRED");
    long timeout = requireRemaining(context);
    HttpRequest request =
        HttpRequest.newBuilder(config.endpoint())
            .timeout(Duration.ofMillis(timeout))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", credential)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(arguments)))
            .build();

    CompletableFuture<HttpResponse<byte[]>> pending = null;
    final HttpResponse<byte[]> response;
    try {
      pending = client.sendAsync(request, new LimitedBodyHandler(config.maxResponseBytes()));
      response = pending.get(timeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      if (pending != null) pending.cancel(true);
      Thread.currentThread().interrupt();
      throw failure(FailureKind.UNKNOWN, "OPSAGENT_RETRIEVAL_INTERRUPTED");
    } catch (Exception transportFailure) {
      if (pending != null) pending.cancel(true);
      throw failure(FailureKind.UNKNOWN, "OPSAGENT_RETRIEVAL_OUTCOME_UNKNOWN");
    }
    int status = response.statusCode();
    if (status == 401 || status == 403)
      throw failure(FailureKind.DENIED, "OPSAGENT_RETRIEVAL_DENIED");
    if (status == 429) throw failure(FailureKind.TRANSIENT, "OPSAGENT_RETRIEVAL_RATE_LIMITED");
    if (status >= 500) throw failure(FailureKind.TRANSIENT, "OPSAGENT_RETRIEVAL_UNAVAILABLE");
    if (status != 200) throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_HTTP_" + status);
    final JsonNode envelope;
    try {
      envelope = Json.MAPPER.readTree(response.body());
    } catch (Exception invalidResponse) {
      throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_RESPONSE_INVALID");
    }
    if (envelope == null
        || !envelope.isObject()
        || !envelope.path("code").isIntegralNumber()
        || !envelope.path("code").canConvertToInt())
      throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_RESPONSE_INVALID");
    int code = envelope.path("code").intValue();
    if (code == 40100 || code == 40300)
      throw failure(FailureKind.DENIED, "OPSAGENT_RETRIEVAL_DENIED");
    if (code == 50300 || code == 50000)
      throw failure(FailureKind.TRANSIENT, "OPSAGENT_RETRIEVAL_UNAVAILABLE");
    if (code != 0) throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_BUSINESS_ERROR");
    try {
      return ToolResult.success(project(envelope.path("data")));
    } catch (RuntimeException invalidResponse) {
      throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_RESPONSE_INVALID");
    }
  }

  public static void validateArguments(JsonNode arguments) {
    if (arguments == null
        || !arguments.isObject()
        || arguments.size() != 2
        || !arguments.path("query").isTextual()
        || arguments.path("query").asText().isBlank()
        || arguments.path("query").asText().length() > 2000
        || !arguments.path("topK").isIntegralNumber()
        || !arguments.path("topK").canConvertToInt()
        || arguments.path("topK").intValue() < 1
        || arguments.path("topK").intValue() > 20)
      throw failure(FailureKind.INVALID, "OPSAGENT_RETRIEVAL_ARGUMENTS_INVALID");
  }

  private long requireRemaining(ExecutionContext context) {
    long remaining =
        Math.min(
            Duration.between(Instant.now(), context.deadline()).toMillis(),
            config.requestTimeout().toMillis());
    if (remaining < 1) throw failure(FailureKind.PERMANENT, "OPSAGENT_RETRIEVAL_DEADLINE_EXCEEDED");
    return remaining;
  }

  private ObjectNode project(JsonNode data) {
    if (!data.isObject()
        || !data.path("evidence").isTextual()
        || data.path("evidence").asText().length() > config.maxEvidenceChars()
        || !data.path("citations").isArray()
        || data.path("citations").size() > config.maxCitations())
      throw new IllegalArgumentException("Invalid evidence/citations");
    ObjectNode result = Json.object().put("evidence", data.path("evidence").asText());
    var citations = result.putArray("citations");
    var seen = new HashSet<String>();
    for (var source : data.path("citations")) {
      String sourceId = text(source, "sourceId", 100, false);
      if (sourceId.isBlank() || !seen.add(sourceId))
        throw new IllegalArgumentException("Invalid source id");
      var citation = citations.addObject().put("sourceId", sourceId);
      for (String id : List.of("chunkId", "documentId")) {
        if (!source.path(id).isIntegralNumber()
            || !source.path(id).canConvertToLong()
            || source.path(id).longValue() < 1)
          throw new IllegalArgumentException("Invalid source id");
        citation.put(id, source.path(id).longValue());
      }
      citation.put("documentName", text(source, "documentName", 20000, false));
      for (String name :
          List.of(
              "headingPath",
              "sourceType",
              "sourceUrl",
              "sourceUpdatedAt",
              "sourceRetrievedAt",
              "evidenceBundleId",
              "evidenceId")) {
        String value = text(source, name, 20000, true);
        if (value != null) citation.put(name, value);
      }
      for (String name : List.of("page", "pageStart", "pageEnd", "version", "chunkIndex")) {
        var value = source.get(name);
        if (value != null && !value.isNull()) {
          if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IllegalArgumentException("Invalid source position");
          citation.put(name, value.intValue());
        }
      }
    }
    return result;
  }

  private String text(JsonNode source, String field, int max, boolean optional) {
    var value = source.get(field);
    if (optional && (value == null || value.isNull())) return null;
    if (value == null || !value.isTextual() || value.asText().length() > max)
      throw new IllegalArgumentException("Invalid source field");
    return value.asText();
  }

  private static InvocationException failure(FailureKind kind, String code) {
    return new InvocationException(kind, code);
  }
}
