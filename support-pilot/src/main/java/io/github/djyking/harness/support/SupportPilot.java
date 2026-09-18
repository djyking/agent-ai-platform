package io.github.djyking.harness.support;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.client.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Independent loopback customer-service application; the platform owns every Run and decision. */
public final class SupportPilot implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  static {
    JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    JSON.getFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JSON.getFactory().setStreamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(16000).build());
  }
  public record Configuration(
      URI platformOrigin, String project, int port, SecretProvider applicationSecret,
      SecretProvider operatorToken, String operatorAccessCode,
      SecretProvider reviewerToken, String reviewerAccessCode) {
    public Configuration {
      Objects.requireNonNull(applicationSecret);
      Objects.requireNonNull(operatorToken);
      if (port < 0 || port > 65535 || operatorAccessCode == null || operatorAccessCode.length() < 24)
        throw new IllegalArgumentException("A port and strong operator access code are required");
      if ((reviewerToken == null) != (reviewerAccessCode == null)
          || reviewerAccessCode != null && (reviewerAccessCode.length() < 24 || reviewerAccessCode.equals(operatorAccessCode)))
        throw new IllegalArgumentException("Reviewer requires a distinct strong access code and token");
      ClientConfiguration.defaults(platformOrigin, project, applicationSecret, operatorToken);
    }
    @Override public String toString() { return "SupportConfiguration[project=" + project + ", port=" + port + "]"; }
  }
  private record Session(String role, String csrf, long expiresAt, PlatformClient client) {}
  private final Configuration config;
  private final HttpServer server;
  private final ExecutorService executor;
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final byte[] operatorCode;
  private final byte[] reviewerCode;
  private final String origin;
  private long loginWindow;
  private int loginAttempts;

  public SupportPilot(Configuration config) throws IOException {
    this.config = config;
    this.operatorCode = hash(config.operatorAccessCode());
    this.reviewerCode = config.reviewerAccessCode() == null ? null : hash(config.reviewerAccessCode());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 32);
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    executor = Executors.newFixedThreadPool(8);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }
  public void start() { server.start(); }
  public URI origin() { return URI.create(origin); }
  @Override public void close() { server.stop(1); executor.shutdownNow(); sessions.clear(); }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      var headers = exchange.getResponseHeaders();
      headers.set("Cache-Control", "no-store");
      headers.set("X-Content-Type-Options", "nosniff");
      headers.set("Referrer-Policy", "no-referrer");
      headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'");
      require(("127.0.0.1:" + server.getAddress().getPort()).equals(oneHeader(exchange, "Host")), 400, "INVALID_HOST");
      String path = exchange.getRequestURI().getRawPath();
      String method = exchange.getRequestMethod();
      if (method.equals("GET") && Set.of("/", "/app.js", "/app.css").contains(path)) {
        asset(exchange, path.equals("/") ? "index.html" : path.substring(1)); return;
      }
      if (method.equals("GET") && path.equals("/health")) {
        respond(exchange, 200, JSON.createObjectNode().put("status", "UP").put("application", "support-pilot")); return;
      }
      require(path.startsWith("/api/"), 404, "NOT_FOUND");
      if (method.equals("POST")) require(origin.equals(oneHeader(exchange, "Origin")), 403, "ORIGIN_REJECTED");
      if (path.equals("/api/session") && method.equals("POST")) {
        login(exchange, read(exchange)); return;
      }
      Session session = session(exchange);
      if (path.equals("/api/session") && method.equals("GET")) {
        ObjectNode body = JSON.createObjectNode().put("signedIn", session != null);
        if (session != null) body.put("role", session.role()).put("csrfToken", session.csrf()).put("project", config.project());
        respond(exchange, 200, body); return;
      }
      require(session != null, 401, "SESSION_REQUIRED");
      if (method.equals("POST"))
        require(equal(session.csrf(), oneHeader(exchange, "X-CSRF-Token")), 403, "CSRF_REJECTED");
      if (path.equals("/api/logout") && method.equals("POST")) {
        sessions.remove(cookie(exchange));
        exchange.getResponseHeaders().set("Set-Cookie", "support_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        respond(exchange, 200, JSON.createObjectNode().put("signedIn", false)); return;
      }
      if (path.equals("/api/agents") && method.equals("GET")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        relay(exchange, session.client().publishedReleases()); return;
      }
      if (path.equals("/api/runs") && method.equals("GET")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        relay(exchange, session.client().listRuns(query(exchange).get("cursor"), 20, null)); return;
      }
      if (path.equals("/api/runs") && method.equals("POST")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        create(exchange, session, read(exchange)); return;
      }
      String[] parts = path.split("/");
      require(parts.length >= 4 && parts.length <= 5 && parts[2].equals("runs"), 404, "NOT_FOUND");
      String run = parts[3];
      require(run.matches("[a-f0-9-]{36}"), 400, "INVALID_RUN");
      if (method.equals("GET") && parts.length == 4) {
        relay(exchange, session.client().getRun(run)); return;
      }
      require(parts.length == 5, 404, "NOT_FOUND");
      String action = parts[4];
      if (method.equals("GET") && action.equals("events")) {
        relay(exchange, session.client().events(run, query(exchange).get("cursor"), 50)); return;
      }
      if (method.equals("GET") && action.equals("approval")) {
        require(session.role().equals("reviewer"), 403, "REVIEWER_REQUIRED");
        relay(exchange, session.client().approval(run)); return;
      }
      require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED");
      JsonNode body = read(exchange);
      String etag = text(body, "etag", 4096), key = text(body, "requestKey", 128);
      if (action.equals("decision")) {
        require(session.role().equals("reviewer"), 403, "REVIEWER_REQUIRED");
        String decision = text(body, "decision", 10);
        require(Set.of("APPROVE", "REJECT").contains(decision), 400, "INVALID_DECISION");
        ObjectNode upstream = JSON.createObjectNode().put("digest", text(body, "digest", 100))
            .put("decision", decision).put("reason", text(body, "reason", 1000));
        if (body.has("input")) upstream.set("input", body.get("input"));
        relay(exchange, session.client().decideApproval(run, text(body, "approvalId", 200), upstream, etag, key)); return;
      }
      require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
      ApiResponse response = switch (action) {
        case "pause" -> session.client().pause(run, etag, key);
        case "resume" -> session.client().resume(run, etag, key);
        case "cancel" -> session.client().cancel(run, etag, key);
        default -> throw new Failure(404, "NOT_FOUND");
      };
      relay(exchange, response);
    } catch (Failure failure) {
      respond(exchange, failure.status, JSON.createObjectNode().put("code", failure.code));
    } catch (PlatformException failure) {
      ObjectNode body = JSON.createObjectNode().put("code", failure.code()).put("outcomeUnknown", failure.outcomeUnknown());
      if (failure.requestId() != null) body.put("requestId", failure.requestId());
      respond(exchange, failure.status() >= 400 && failure.status() <= 599 ? failure.status() : 502, body);
    } catch (IllegalArgumentException failure) {
      respond(exchange, 400, JSON.createObjectNode().put("code", "INVALID_REQUEST"));
    } catch (Exception failure) {
      respond(exchange, 500, JSON.createObjectNode().put("code", "INTERNAL_ERROR"));
    } finally { exchange.close(); }
  }

  private void create(HttpExchange exchange, Session session, JsonNode body) throws IOException {
    String agentId = text(body, "agentId", 128), releaseId = text(body, "releaseId", 64), key = text(body, "requestKey", 128);
    require(body.path("inputs").isObject(), 400, "INPUTS_REQUIRED");
    JsonNode selected = null;
    for (JsonNode item : session.client().publishedReleases().body().path("items"))
      if (item.path("agentId").asText().equals(agentId) && item.path("releaseRef").path("releaseId").asText().equals(releaseId)) selected = item;
    require(selected != null, 404, "RELEASE_UNAVAILABLE");
    ObjectNode upstream = JSON.createObjectNode();
    upstream.set("releaseRef", selected.path("releaseRef"));
    upstream.set("inputs", body.get("inputs"));
    upstream.put("clientReference", "support-" + key);
    relay(exchange, session.client().createRun(upstream, key));
  }
  private synchronized void login(HttpExchange exchange, JsonNode body) throws IOException {
    long now = System.currentTimeMillis();
    if (now - loginWindow >= 60000) { loginWindow = now; loginAttempts = 0; }
    require(++loginAttempts <= 30, 429, "LOGIN_RATE_LIMITED");
    byte[] supplied = hash(text(body, "accessCode", 512));
    boolean operator = MessageDigest.isEqual(supplied, operatorCode);
    boolean reviewer = reviewerCode != null && MessageDigest.isEqual(supplied, reviewerCode);
    require(operator || reviewer, 401, "ACCESS_CODE_INVALID");
    sessions.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    require(sessions.size() < 128, 429, "SESSION_LIMIT");
    String previous = cookie(exchange);
    if (previous != null) sessions.remove(previous);
    String token = random(), csrf = random();
    String role = operator ? "operator" : "reviewer";
    PlatformClient client = new PlatformClient(ClientConfiguration.defaults(config.platformOrigin(), config.project(),
        config.applicationSecret(), operator ? config.operatorToken() : config.reviewerToken()));
    sessions.put(token, new Session(role, csrf, now + Duration.ofHours(1).toMillis(), client));
    exchange.getResponseHeaders().set("Set-Cookie", "support_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600");
    respond(exchange, 200, JSON.createObjectNode().put("signedIn", true).put("role", role).put("csrfToken", csrf).put("project", config.project()));
  }
  private Session session(HttpExchange exchange) {
    String key = cookie(exchange);
    Session session = key == null ? null : sessions.get(key);
    if (session != null && session.expiresAt() <= System.currentTimeMillis()) { sessions.remove(key); return null; }
    return session;
  }
  private static String cookie(HttpExchange exchange) {
    String cookie = oneHeader(exchange, "Cookie");
    if (cookie == null) return null;
    String result = null;
    for (String pair : cookie.split(";")) if (pair.trim().startsWith("support_session=")) {
      require(result == null, 400, "INVALID_COOKIE");
      result = pair.trim().substring("support_session=".length());
    }
    return result != null && result.matches("[A-Za-z0-9_-]{43}") ? result : null;
  }
  private static String oneHeader(HttpExchange exchange, String name) {
    List<String> values = exchange.getRequestHeaders().get(name);
    require(values == null || values.size() == 1, 400, "DUPLICATE_HEADER");
    return values == null ? null : values.get(0);
  }
  private static JsonNode read(HttpExchange exchange) throws IOException {
    require("application/json".equalsIgnoreCase(oneHeader(exchange, "Content-Type")), 415, "JSON_REQUIRED");
    byte[] raw = exchange.getRequestBody().readNBytes(65537);
    require(raw.length <= 65536, 413, "REQUEST_TOO_LARGE");
    JsonNode body;
    try { body = JSON.readTree(raw); }
    catch (Exception failure) { throw new Failure(400, "INVALID_JSON"); }
    require(body != null && body.isObject(), 400, "INVALID_JSON");
    return body;
  }
  private static Map<String, String> query(HttpExchange exchange) {
    Map<String, String> result = new HashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) return result;
    require(query.length() <= 10000, 400, "QUERY_TOO_LONG");
    for (String pair : query.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      require(result.putIfAbsent(key, value) == null, 400, "DUPLICATE_QUERY");
    }
    return result;
  }
  private static String text(JsonNode body, String name, int max) {
    JsonNode value = body.get(name);
    require(value != null && value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max, 400, "INVALID_REQUEST");
    return value.asText();
  }
  private static void relay(HttpExchange exchange, ApiResponse response) throws IOException {
    ObjectNode value = JSON.createObjectNode().put("status", response.status());
    value.set("body", response.body());
    if (response.etag() != null) value.put("etag", response.etag());
    if (response.requestId() != null) value.put("requestId", response.requestId());
    respond(exchange, response.status(), value);
  }
  private static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
  private static void asset(HttpExchange exchange, String file) throws IOException {
    try (var input = SupportPilot.class.getResourceAsStream("/web/" + file)) {
      require(input != null, 404, "NOT_FOUND");
      byte[] bytes = input.readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", file.endsWith(".js") ? "text/javascript; charset=utf-8" : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }
  private static byte[] hash(String value) {
    try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
    catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }
  private static boolean equal(String left, String right) { return right != null && MessageDigest.isEqual(hash(left), hash(right)); }
  private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private static void require(boolean valid, int status, String code) { if (!valid) throw new Failure(status, code); }
  private static final class Failure extends RuntimeException {
    final int status; final String code;
    Failure(int status, String code) { this.status = status; this.code = code; }
  }
  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Required support configuration missing");
    return value;
  }
  public static void main(String[] args) throws Exception {
    String reviewer = System.getenv("SUPPORT_REVIEWER_USER_TOKEN");
    Configuration config = new Configuration(URI.create(required("SUPPORT_PLATFORM_ORIGIN")),
        System.getenv().getOrDefault("SUPPORT_PROJECT", "support-pilot"),
        Integer.parseInt(System.getenv().getOrDefault("SUPPORT_PORT", "8098")),
        SecretProvider.environment("SUPPORT_APPLICATION_SECRET"), SecretProvider.environment("SUPPORT_OPERATOR_USER_TOKEN"),
        required("SUPPORT_OPERATOR_ACCESS_CODE"), reviewer == null ? null : SecretProvider.environment("SUPPORT_REVIEWER_USER_TOKEN"),
        reviewer == null ? null : required("SUPPORT_REVIEWER_ACCESS_CODE"));
    required("SUPPORT_APPLICATION_SECRET"); required("SUPPORT_OPERATOR_USER_TOKEN");
    SupportPilot app = new SupportPilot(config);
    Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    app.start();
    System.out.println("Support pilot listening at " + app.origin());
  }
}
