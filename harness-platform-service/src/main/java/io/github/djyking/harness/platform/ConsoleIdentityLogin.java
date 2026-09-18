package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.core.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Fixed-origin proxy to the existing OpsAgent captcha/login contract; no account database. */
public final class ConsoleIdentityLogin implements IdentityLogin {
  private final URI origin;
  private final Duration timeout;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public ConsoleIdentityLogin(URI origin) {
    this(origin, Duration.ofSeconds(10));
  }

  ConsoleIdentityLogin(URI origin, Duration timeout) {
    if (origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
        || !("https".equals(origin.getScheme())
            || ("http".equals(origin.getScheme())
                && java.util.Set.of("127.0.0.1", "localhost", "[::1]", "::1")
                    .contains(origin.getHost()))))
      throw new IllegalArgumentException(
          "Identity login requires a trusted HTTPS or loopback origin");
    this.origin = origin;
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Invalid identity timeout");
    this.timeout = timeout;
  }

  public JsonNode captcha() {
    JsonNode data = request("/api/auth/captcha", null);
    String id = data.path("captchaId").asText(), image = data.path("imageDataUrl").asText();
    if (!id.matches("[a-f0-9]{32}")
        || !image.matches("data:image/png;base64,[A-Za-z0-9+/=]+")
        || image.length() > 200000) throw unavailable();
    return Json.object()
        .put("captchaId", id)
        .put("imageDataUrl", image)
        .put(
            "expiresInSeconds",
            Math.max(1, Math.min(300, data.path("expiresInSeconds").asLong(120))));
  }

  public String login(JsonNode body) {
    JsonNode data = request("/api/auth/login", body);
    String token = data.path("accessToken").asText();
    if (token.isBlank() || token.length() > 8192) throw unavailable();
    return token;
  }

  private JsonNode request(String path, JsonNode body) {
    var builder =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(timeout)
            .header("Accept", "application/json");
    if (body != null)
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
    else builder.GET();
    var pending = client.sendAsync(builder.build(), new LimitedBodyHandler(262144));
    try {
      // The deadline covers the complete bounded body, including a provider that sends headers
      // promptly and then stops sending data. ofInputStream would finish send() at the headers.
      var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      byte[] bytes = response.body();
      if (response.statusCode() == 429) throw new ApiFailure(429, "AUTH_RATE_LIMITED");
      if (response.statusCode() == 401
          || response.statusCode() == 403
          || response.statusCode() == 400) throw new ApiFailure(401, "UNAUTHENTICATED");
      if (response.statusCode() != 200 || bytes.length > 262144) throw unavailable();
      JsonNode envelope = Json.MAPPER.readTree(bytes);
      if (envelope == null || !envelope.isObject()) throw unavailable();
      if (envelope.has("code")
          && envelope.path("code").asInt(-1) != 0
          && envelope.path("code").asInt(-1) != 200) throw new ApiFailure(401, "UNAUTHENTICATED");
      JsonNode data = envelope.path("data");
      if (!data.isObject()) throw unavailable();
      return data;
    } catch (InterruptedException ex) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (TimeoutException ex) {
      pending.cancel(true);
      throw unavailable();
    } catch (ExecutionException | IOException ex) {
      throw unavailable();
    }
  }

  private static ApiFailure unavailable() {
    return new ApiFailure(503, "AUTH_PROVIDER_UNAVAILABLE");
  }
}
