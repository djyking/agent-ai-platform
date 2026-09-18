package io.github.djyking.harness.integrations.opsagent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** Current domain authorization for stored, unmodified OpsAgent retrieval output. */
public final class OpsAgentProjectionClient {
  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient http;

  public OpsAgentProjectionClient(URI ragOrigin, Duration timeout) {
    if (ragOrigin == null
        || ragOrigin.getHost() == null
        || ragOrigin.getUserInfo() != null
        || ragOrigin.getQuery() != null
        || ragOrigin.getFragment() != null
        || !(ragOrigin.getPath().isEmpty() || "/".equals(ragOrigin.getPath()))
        || !("https".equals(ragOrigin.getScheme())
            || ("http".equals(ragOrigin.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(ragOrigin.getHost()))))
      throw new IllegalArgumentException("OPSAGENT_RAG_ORIGIN_INVALID");
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("OPSAGENT_PROJECTION_TIMEOUT_INVALID");
    this.endpoint = ragOrigin.resolve("/internal/rag/validate-citations");
    this.timeout = timeout;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * Fails closed on malformed references, expired identity, redirects, or domain unavailability.
   */
  public boolean allowed(String ragAuthorization, JsonNode citations) {
    if (ragAuthorization == null
        || !ragAuthorization.matches("Bearer [A-Za-z0-9._~-]{1,9990}")
        || citations == null
        || !citations.isArray()
        || citations.isEmpty()
        || citations.size() > 100) return false;
    var refs = Json.MAPPER.createArrayNode();
    for (JsonNode citation : citations) {
      for (String name : new String[] {"documentId", "chunkId", "version"})
        if (!citation.path(name).isIntegralNumber()
            || !citation.path(name).canConvertToLong()
            || citation.path(name).asLong() < 1) return false;
      if (!citation.path("version").canConvertToInt()) return false;
      refs.add(
          Json.object()
              .put("documentId", citation.path("documentId").asLong())
              .put("chunkId", citation.path("chunkId").asLong())
              .put("version", citation.path("version").asInt()));
    }
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(timeout)
                  .header("Authorization", ragAuthorization)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          Json.write(Json.object().set("citations", refs))))
                  .build(),
              new LimitedBodyHandler(4096));
      if (response.statusCode() != 200) return false;
      JsonNode envelope = Json.read(new String(response.body(), StandardCharsets.UTF_8));
      return envelope.path("code").isIntegralNumber()
          && envelope.path("code").canConvertToInt()
          && envelope.path("code").asInt() == 0
          && envelope.path("data").path("allowed").isBoolean()
          && envelope.path("data").path("allowed").asBoolean();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception unavailable) {
      return false;
    }
  }
}
