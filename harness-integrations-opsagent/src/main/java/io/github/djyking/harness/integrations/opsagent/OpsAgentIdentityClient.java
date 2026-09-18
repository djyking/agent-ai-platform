package io.github.djyking.harness.integrations.opsagent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Existing OpsAgent user identities; application credentials and login JWTs remain request-local.
 */
public final class OpsAgentIdentityClient {
  private final URI origin;
  private final Duration timeout;
  private final HttpClient client;

  public OpsAgentIdentityClient(URI origin, Duration timeout) {
    Objects.requireNonNull(origin);
    boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(origin.getHost());
    if (!("https".equals(origin.getScheme()) || ("http".equals(origin.getScheme()) && loopback))
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || origin.getHost() == null
        || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
      throw new IllegalArgumentException("OPSAGENT_AUTH_ORIGIN_INVALID");
    }
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("OPSAGENT_AUTH_TIMEOUT_INVALID");
    }
    this.origin = origin;
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Identity introspect(String applicationAuthorization, String projectId, String userToken) {
    return identity(
        call(
            applicationAuthorization,
            "/internal/harness/introspect",
            Json.object().put("projectId", projectId).put("userToken", userToken)));
  }

  public Delegation bind(
      String applicationAuthorization,
      String projectId,
      String userToken,
      String runId,
      Instant deadline) {
    JsonNode result =
        call(
            applicationAuthorization,
            "/internal/harness/delegations",
            Json.object()
                .put("projectId", projectId)
                .put("userToken", userToken)
                .put("runId", runId)
                .put("deadline", deadline.toString()));
    return new Delegation(
        text(result, "delegationId"),
        Instant.parse(text(result, "expiresAt")),
        identity(result.path("identity")));
  }

  public Identity current(String applicationAuthorization, String delegationId, String runId) {
    return identity(
        call(
            applicationAuthorization,
            delegationPath(delegationId)
                + "?runId="
                + URLEncoder.encode(runId, StandardCharsets.UTF_8),
            null));
  }

  public Token token(
      String applicationAuthorization, String delegationId, String runId, String audience) {
    JsonNode result =
        call(
            applicationAuthorization,
            delegationPath(delegationId) + "/token",
            Json.object().put("runId", runId).put("audience", audience));
    String authorization = text(result, "authorization");
    if (!authorization.matches("Bearer [A-Za-z0-9._~-]{1,9990}"))
      throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
    return new Token(authorization, Instant.parse(text(result, "expiresAt")));
  }

  private String delegationPath(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("OPSAGENT_DELEGATION_INVALID");
    return "/internal/harness/delegations/" + id;
  }

  private JsonNode call(String authorization, String path, JsonNode body) {
    if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9._~+/=-]{32,512}")) {
      throw new IdentityFailure(401, "OPSAGENT_APPLICATION_INVALID");
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(timeout)
            .header("Authorization", authorization)
            .header("Accept", "application/json");
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
    try {
      HttpResponse<byte[]> response = client.send(request.build(), new LimitedBodyHandler(65536));
      if (response.statusCode() != 200)
        throw new IdentityFailure(response.statusCode(), "OPSAGENT_IDENTITY_REJECTED");
      JsonNode envelope = Json.read(new String(response.body(), StandardCharsets.UTF_8));
      if (!envelope.path("code").isIntegralNumber() || !envelope.path("code").canConvertToInt())
        throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
      if (envelope.path("code").asInt() != 0) {
        int status = envelope.path("code").asInt() / 100;
        throw new IdentityFailure(
            status >= 400 && status <= 599 ? status : 502, "OPSAGENT_IDENTITY_REJECTED");
      }
      if (!envelope.path("data").isObject())
        throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
      return envelope.path("data");
    } catch (IdentityFailure failure) {
      throw failure;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IdentityFailure(503, "OPSAGENT_IDENTITY_UNAVAILABLE");
    } catch (Exception failure) {
      throw new IdentityFailure(503, "OPSAGENT_IDENTITY_UNAVAILABLE");
    }
  }

  private Identity identity(JsonNode value) {
    if (!value.path("permissions").isArray())
      throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
    var permissions = new java.util.HashSet<String>();
    value
        .path("permissions")
        .forEach(
            p -> {
              if (!p.isTextual() || !p.asText().matches("[a-z][A-Za-z0-9._:/-]{1,204}")) {
                throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
              }
              permissions.add(p.asText());
            });
    return new Identity(
        text(value, "applicationId"),
        text(value, "projectId"),
        text(value, "subject"),
        permissions);
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.path(name);
    if (!field.isTextual() || field.asText().isBlank() || field.asText().length() > 10000) {
      throw new IdentityFailure(502, "OPSAGENT_AUTH_RESPONSE_INVALID");
    }
    return field.asText();
  }

  public record Identity(
      String applicationId, String projectId, String subject, Set<String> permissions) {
    public Identity {
      permissions = Set.copyOf(permissions);
    }
  }

  public record Delegation(String delegationId, Instant expiresAt, Identity identity) {}

  public record Token(String authorization, Instant expiresAt) {
    @Override
    public String toString() {
      return "Token[authorization=REDACTED, expiresAt=" + expiresAt + "]";
    }
  }

  public static final class IdentityFailure extends RuntimeException {
    private final int status;

    public IdentityFailure(int status, String code) {
      super(code);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
