package io.github.djyking.harness.client;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Thin synchronous HTTP client. No worker, state machine, polling, or automatic command retries. */
public final class PlatformClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  static {
    JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    JSON.getFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JSON.getFactory().setStreamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(1024 * 1024).build());
  }
  private final ClientConfiguration config;
  private final HttpClient http;
  private final String base;

  public PlatformClient(ClientConfiguration config) {
    this.config = config;
    this.http = HttpClient.newBuilder().connectTimeout(config.timeout())
        .followRedirects(HttpClient.Redirect.NEVER).build();
    this.base = "/v1/projects/" + config.project();
  }

  public ApiResponse createRun(JsonNode request, String idempotencyKey) {
    return request("POST", base + "/runs", request, idempotencyKey, null);
  }
  public ApiResponse getRun(String runId) {
    return request("GET", run(runId), null, null, null);
  }
  public ApiResponse listRuns(String cursor, int limit, String status) {
    page(limit, 100);
    return request("GET", base + "/runs?limit=" + limit + query("cursor", cursor) + query("status", status), null, null, null);
  }
  public ApiResponse events(String runId, String cursor, int limit) {
    page(limit, 100);
    return request("GET", run(runId) + "/events?limit=" + limit + query("cursor", cursor), null, null, null);
  }
  public ApiResponse pause(String runId, String etag, String idempotencyKey) {
    return control(runId, "pause", etag, idempotencyKey);
  }
  public ApiResponse resume(String runId, String etag, String idempotencyKey) {
    return control(runId, "resume", etag, idempotencyKey);
  }
  public ApiResponse cancel(String runId, String etag, String idempotencyKey) {
    return control(runId, "cancel", etag, idempotencyKey);
  }
  public ApiResponse approval(String runId) {
    return request("GET", run(runId) + "/approval", null, null, null);
  }
  public ApiResponse decideApproval(String runId, String approvalId, JsonNode decision, String etag, String idempotencyKey) {
    identifier(approvalId);
    return request("POST", run(runId) + "/approvals/" + approvalId + "/decision", decision, idempotencyKey, strongTag(etag));
  }
  public ApiResponse unknownInvocation(String runId) {
    return request("GET", run(runId) + "/unknown-invocation", null, null, null);
  }
  public ApiResponse reconcileTool(String runId, JsonNode evidenceReference, String etag, String idempotencyKey) {
    return request("POST", run(runId) + "/tool-reconciliations", evidenceReference, idempotencyKey, strongTag(etag));
  }
  public ApiResponse publishedReleases() {
    return request("GET", base + "/catalog/releases", null, null, null);
  }
  public ApiResponse defaultRelease(String agentId) {
    identifier(agentId);
    return request("GET", base + "/catalog/agents/" + agentId + "/default", null, null, null);
  }

  private ApiResponse control(String runId, String operation, String etag, String key) {
    return request("POST", run(runId) + "/" + operation, JSON.createObjectNode(), key, strongTag(etag));
  }
  private String run(String id) {
    if (id == null || !UUID.fromString(id).toString().equals(id))
      throw new IllegalArgumentException("Invalid run identifier");
    return base + "/runs/" + id;
  }
  private static void identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"))
      throw new IllegalArgumentException("Invalid identifier");
  }
  private static void page(int limit, int max) {
    if (limit < 1 || limit > max) throw new IllegalArgumentException("Invalid page limit");
  }
  private static String query(String key, String value) {
    if (value == null || value.isEmpty()) return "";
    if (value.length() > 8192) throw new IllegalArgumentException("Query value too long");
    return "&" + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
  private static String strongTag(String value) {
    if (value == null || !value.matches("\"[!#-~]{1,4094}\""))
      throw new IllegalArgumentException("A current strong ETag is required");
    return value;
  }
  private static String credential(SecretProvider provider) {
    String value;
    try { value = provider.resolve(); }
    catch (RuntimeException failure) { throw new IllegalArgumentException("Credential unavailable"); }
    if (value == null || value.isBlank() || value.length() > 16384 || value.chars().anyMatch(c -> c < 33 || c > 126))
      throw new IllegalArgumentException("Credential unavailable");
    return value;
  }

  private ApiResponse request(String method, String path, JsonNode body, String key, String etag) {
    boolean mutation = method.equals("POST");
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.origin() + path))
        .timeout(config.timeout()).header("Accept", "application/json")
        .header("Authorization", "Bearer " + credential(config.applicationCredential()))
        .header("X-Harness-User-Token", credential(config.userToken()));
    if (mutation) {
      if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
        throw new IllegalArgumentException("Explicit idempotency key required");
      if (body == null || !body.isObject()) throw new IllegalArgumentException("JSON object required");
      byte[] encoded;
      try { encoded = JSON.writeValueAsBytes(body); }
      catch (Exception failure) { throw new IllegalArgumentException("Invalid JSON request"); }
      if (encoded.length > 65536) throw new IllegalArgumentException("Request too large");
      builder.header("Idempotency-Key", key).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(encoded));
      if (etag != null) builder.header("If-Match", etag);
    } else builder.GET();
    HttpResponse<byte[]> response;
    try {
      // Exactly one send. On an ambiguous transport failure the caller retains the original key/body.
      response = http.send(builder.build(), info -> new LimitedBody(config.maxResponseBytes()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PlatformException(0, "REQUEST_INTERRUPTED", null, mutation);
    } catch (Exception failure) {
      throw new PlatformException(0, "TRANSPORT_FAILED", null, mutation);
    }
    String requestId = header(response, "X-Request-Id");
    JsonNode value;
    try { value = JSON.readTree(response.body()); }
    catch (Exception failure) { throw new PlatformException(response.statusCode(), "INVALID_RESPONSE", requestId, mutation); }
    if (value == null || !value.isObject())
      throw new PlatformException(response.statusCode(), "INVALID_RESPONSE", requestId, mutation);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String code = value.path("error").path("code").asText(value.path("code").asText());
      if (!code.matches("[A-Z][A-Z0-9_]{0,79}")) code = "HTTP_ERROR";
      throw new PlatformException(response.statusCode(), code, requestId,
          mutation && (response.statusCode() >= 500 || response.statusCode() == 408));
    }
    return new ApiResponse(response.statusCode(), value, header(response, "ETag"), requestId,
        header(response, "Location"), header(response, "Retry-After"));
  }
  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).filter(v -> v.length() <= 8192).orElse(null);
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBody(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IllegalStateException("Response limit exceeded"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
