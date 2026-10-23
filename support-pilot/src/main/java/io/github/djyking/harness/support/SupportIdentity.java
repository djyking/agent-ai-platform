package io.github.djyking.harness.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.client.SecretProvider;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.integrations.opsagent.OpsAgentIdentityClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/** Fixed-origin adapter to existing Ops accounts; never stores passwords or creates users. */
final class SupportIdentity {
  private final URI origin;
  private final String project;
  private final SecretProvider secret;
  private final OpsAgentIdentityClient identities;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  SupportIdentity(URI origin, String project, SecretProvider secret) {
    this.origin = origin;
    this.project = project;
    this.secret = secret;
    identities = new OpsAgentIdentityClient(origin, Duration.ofSeconds(10));
  }

  JsonNode captcha() {
    JsonNode data = call("/api/auth/captcha", null);
    String id = data.path("captchaId").asText(), image = data.path("imageDataUrl").asText();
    if (!id.matches("[a-f0-9]{32}")
        || !image.matches("data:image/png;base64,[A-Za-z0-9+/=]+")
        || image.length() > 200000) throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
    return Json.object()
        .put("captchaId", id)
        .put("imageDataUrl", image)
        .put(
            "expiresInSeconds",
            Math.max(1, Math.min(300, data.path("expiresInSeconds").asInt(120))));
  }

  String login(JsonNode input) {
    var credentials = Json.object();
    for (String name : Set.of("username", "password", "captchaId", "captchaCode")) {
      JsonNode value = input.path(name);
      int max = name.equals("password") ? 512 : 128;
      if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
        throw failure(400, "INVALID_REQUEST");
      credentials.set(name, value);
    }
    String token = call("/api/auth/login", credentials).path("accessToken").asText();
    if (!token.matches("[A-Za-z0-9._~-]{1,8192}")) throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
    return token;
  }

  OpsAgentIdentityClient.Identity current(String token) {
    try {
      var value = identities.introspect("Bearer " + secret.resolve(), project, token);
      if (!value.projectId().equals(project)) throw failure(403, "FORBIDDEN");
      return value;
    } catch (OpsAgentIdentityClient.IdentityFailure rejected) {
      int status = rejected.status();
      if (status == 401) throw failure(401, "SESSION_REQUIRED");
      if (status == 403 || status == 404) throw failure(403, "FORBIDDEN");
      throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
    }
  }

  private JsonNode call(String path, JsonNode body) {
    var request =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json");
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
    var pending = http.sendAsync(request.build(), new LimitedBodyHandler(262144));
    try {
      var response = pending.get(10, TimeUnit.SECONDS);
      if (response.statusCode() == 429) throw failure(429, "AUTH_RATE_LIMITED");
      if (Set.of(400, 401, 403).contains(response.statusCode()))
        throw failure(401, "UNAUTHENTICATED");
      if (response.statusCode() != 200) throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
      JsonNode envelope = Json.MAPPER.readTree(response.body());
      if (envelope == null
          || !envelope.path("code").isIntegralNumber()
          || !envelope.path("code").canConvertToInt())
        throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
      if (envelope.path("code").asInt(-1) != 0) throw failure(401, "UNAUTHENTICATED");
      if (!envelope.path("data").isObject()) throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
      return envelope.path("data");
    } catch (Failure rejected) {
      throw rejected;
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
    } catch (Exception unavailable) {
      pending.cancel(true);
      throw failure(503, "AUTH_PROVIDER_UNAVAILABLE");
    }
  }

  private static Failure failure(int status, String code) {
    return new Failure(status, code);
  }

  static final class Failure extends RuntimeException {
    final int status;
    final String code;

    Failure(int status, String code) {
      super(code);
      this.status = status;
      this.code = code;
    }
  }
}
