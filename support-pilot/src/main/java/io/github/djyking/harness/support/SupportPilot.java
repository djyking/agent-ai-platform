package io.github.djyking.harness.support;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.djyking.harness.client.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Independent customer-service BFF; existing Ops identity and the platform own authorization. */
public final class SupportPilot implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();

  static {
    JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    JSON.getFactory()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JSON.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(16000).build());
  }

  public record Configuration(
      URI platformOrigin,
      String project,
      int port,
      SecretProvider applicationSecret,
      SecretProvider operatorToken,
      String operatorAccessCode,
      SecretProvider reviewerToken,
      String reviewerAccessCode,
      String runtimeMode,
      String authMode,
      URI identityOrigin,
      URI publicOrigin,
      String bindAddress) {
    /** Existing callers retain a loopback-only listener. */
    public Configuration(
        URI platformOrigin,
        String project,
        int port,
        SecretProvider applicationSecret,
        SecretProvider operatorToken,
        String operatorAccessCode,
        SecretProvider reviewerToken,
        String reviewerAccessCode,
        String runtimeMode,
        String authMode,
        URI identityOrigin,
        URI publicOrigin) {
      this(
          platformOrigin,
          project,
          port,
          applicationSecret,
          operatorToken,
          operatorAccessCode,
          reviewerToken,
          reviewerAccessCode,
          runtimeMode,
          authMode,
          identityOrigin,
          publicOrigin,
          "127.0.0.1");
    }

    /** Explicit compatibility constructor for loopback test fixtures only. */
    public Configuration(
        URI platformOrigin,
        String project,
        int port,
        SecretProvider applicationSecret,
        SecretProvider operatorToken,
        String operatorAccessCode,
        SecretProvider reviewerToken,
        String reviewerAccessCode) {
      this(
          platformOrigin,
          project,
          port,
          applicationSecret,
          operatorToken,
          operatorAccessCode,
          reviewerToken,
          reviewerAccessCode,
          "development",
          "local-test",
          null,
          null);
    }

    public Configuration {
      Objects.requireNonNull(applicationSecret);
      InetAddress listener = literalAddress(bindAddress);
      bindAddress = listener.getHostAddress();
      if (port < 0
          || port > 65535
          || !Set.of("development", "staging", "production").contains(runtimeMode)
          || !Set.of("opsagent", "local-test").contains(authMode))
        throw new IllegalArgumentException("Invalid support runtime configuration");
      ClientConfiguration.defaults(
          platformOrigin, project, applicationSecret, () -> "validation-only");
      if (authMode.equals("local-test")) {
        Objects.requireNonNull(operatorToken);
        if (!runtimeMode.equals("development")
            || !listener.isLoopbackAddress()
            || publicOrigin != null
            || operatorAccessCode == null
            || operatorAccessCode.length() < 24)
          throw new IllegalArgumentException(
              "Access codes require explicit loopback development mode");
        if ((reviewerToken == null) != (reviewerAccessCode == null)
            || reviewerAccessCode != null
                && (reviewerAccessCode.length() < 24
                    || reviewerAccessCode.equals(operatorAccessCode)))
          throw new IllegalArgumentException(
              "Reviewer requires a distinct strong access code and token");
      } else {
        Objects.requireNonNull(identityOrigin);
        new SupportIdentity(identityOrigin, project, applicationSecret);
        if (operatorToken != null
            || reviewerToken != null
            || operatorAccessCode != null
            || reviewerAccessCode != null)
          throw new IllegalArgumentException(
              "Ops login cannot accept shared user tokens or access codes");
      }
      if (publicOrigin != null) {
        ClientConfiguration.defaults(
            publicOrigin, project, applicationSecret, () -> "validation-only");
        publicOrigin =
            URI.create(publicOrigin.getScheme() + "://" + publicOrigin.getRawAuthority());
      }
      if (!runtimeMode.equals("development")
          && (publicOrigin == null || !publicOrigin.getScheme().equals("https")))
        throw new IllegalArgumentException(
            "Staging and production require an explicit HTTPS public origin");
      if (!listener.isLoopbackAddress()
          && (!authMode.equals("opsagent")
              || publicOrigin == null
              || !publicOrigin.getScheme().equals("https")))
        throw new IllegalArgumentException(
            "Non-loopback listeners require Ops identity and an explicit HTTPS public origin");
    }

    private static InetAddress literalAddress(String value) {
      if (value == null
          || !(value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
              || value.contains(":") && value.matches("[0-9a-fA-F:]+")))
        throw new IllegalArgumentException("Support bind address must be an IP literal");
      if (!value.contains(":")) {
        for (String octet : value.split("\\.")) {
          if (Integer.parseInt(octet) > 255)
            throw new IllegalArgumentException("Invalid support bind address");
        }
      }
      try {
        return InetAddress.getByName(value);
      } catch (UnknownHostException invalid) {
        throw new IllegalArgumentException("Invalid support bind address");
      }
    }

    @Override
    public String toString() {
      return "SupportConfiguration[project=" + project + ", port=" + port + "]";
    }
  }

  private record Session(
      String role,
      String subject,
      String application,
      String userToken,
      String csrf,
      long expiresAt,
      PlatformClient client) {
    @Override
    public String toString() {
      return "SupportSession[role=" + role + "]";
    }
  }

  private final Configuration config;
  private final HttpServer server;
  private final ExecutorService executor;
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final byte[] operatorCode;
  private final byte[] reviewerCode;
  private final String origin;
  private final SupportIdentity identity;
  private long loginWindow;
  private int loginAttempts;

  public SupportPilot(Configuration config) throws IOException {
    this.config = config;
    this.operatorCode =
        config.operatorAccessCode() == null ? null : hash(config.operatorAccessCode());
    this.reviewerCode =
        config.reviewerAccessCode() == null ? null : hash(config.reviewerAccessCode());
    identity =
        config.authMode().equals("opsagent")
            ? new SupportIdentity(
                config.identityOrigin(), config.project(), config.applicationSecret())
            : null;
    server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 32);
    origin =
        config.publicOrigin() == null
            ? "http://"
                + (config.bindAddress().contains(":")
                    ? "[" + config.bindAddress() + "]"
                    : config.bindAddress())
                + ":"
                + server.getAddress().getPort()
            : config.publicOrigin().toString();
    executor = Executors.newFixedThreadPool(8);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public URI origin() {
    return URI.create(origin);
  }

  InetSocketAddress listenAddress() {
    return server.getAddress();
  }

  @Override
  public void close() {
    server.stop(1);
    executor.shutdownNow();
    sessions.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      var headers = exchange.getResponseHeaders();
      headers.set("Cache-Control", "no-store");
      headers.set("X-Content-Type-Options", "nosniff");
      headers.set("Referrer-Policy", "no-referrer");
      headers.set(
          "Content-Security-Policy",
          "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
              + " connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none';"
              + " form-action 'self'");
      require(
          URI.create(origin).getRawAuthority().equals(oneHeader(exchange, "Host")),
          400,
          "INVALID_HOST");
      String path = exchange.getRequestURI().getRawPath();
      String method = exchange.getRequestMethod();
      if (method.equals("GET") && Set.of("/", "/app.js", "/app.css").contains(path)) {
        asset(exchange, path.equals("/") ? "index.html" : path.substring(1));
        return;
      }
      if (method.equals("GET") && path.equals("/health")) {
        respond(
            exchange,
            200,
            JSON.createObjectNode().put("status", "UP").put("application", "support-pilot"));
        return;
      }
      require(path.startsWith("/api/"), 404, "NOT_FOUND");
      if (method.equals("GET") && path.equals("/api/auth/config")) {
        respond(
            exchange,
            200,
            JSON.createObjectNode()
                .put("mode", config.authMode())
                .put("captchaRequired", identity != null));
        return;
      }
      if (method.equals("GET") && path.equals("/api/auth/captcha")) {
        require(identity != null, 404, "NOT_FOUND");
        respond(exchange, 200, identity.captcha());
        return;
      }
      if (method.equals("POST"))
        require(origin.equals(oneHeader(exchange, "Origin")), 403, "ORIGIN_REJECTED");
      if (path.equals("/api/session") && method.equals("POST")) {
        login(exchange, read(exchange));
        return;
      }
      Session session = session(exchange);
      if (path.equals("/api/session") && method.equals("GET")) {
        if (session != null) validateSession(session);
        ObjectNode body = JSON.createObjectNode().put("signedIn", session != null);
        if (session != null) sessionBody(body, session);
        respond(exchange, 200, body);
        return;
      }
      require(session != null, 401, "SESSION_REQUIRED");
      if (method.equals("POST"))
        require(equal(session.csrf(), oneHeader(exchange, "X-CSRF-Token")), 403, "CSRF_REJECTED");
      if (path.equals("/api/logout") && method.equals("POST")) {
        sessions.remove(cookie(exchange));
        exchange.getResponseHeaders().set("Set-Cookie", cookieHeader("", 0));
        respond(exchange, 200, JSON.createObjectNode().put("signedIn", false));
        return;
      }
      validateSession(session);
      if (path.equals("/api/agents") && method.equals("GET")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        relay(exchange, session.client().publishedReleases());
        return;
      }
      if (path.equals("/api/runs") && method.equals("GET")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        relay(exchange, session.client().listRuns(query(exchange).get("cursor"), 20, null));
        return;
      }
      if (path.equals("/api/runs") && method.equals("POST")) {
        require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
        create(exchange, session, read(exchange));
        return;
      }
      String[] parts = path.split("/");
      require(parts.length >= 4 && parts.length <= 5 && parts[2].equals("runs"), 404, "NOT_FOUND");
      String run = parts[3];
      require(run.matches("[a-f0-9-]{36}"), 400, "INVALID_RUN");
      if (method.equals("GET") && parts.length == 4) {
        relay(exchange, session.client().getRun(run));
        return;
      }
      require(parts.length == 5, 404, "NOT_FOUND");
      String action = parts[4];
      if (method.equals("GET") && action.equals("events")) {
        relay(exchange, session.client().events(run, query(exchange).get("cursor"), 50));
        return;
      }
      if (method.equals("GET") && action.equals("approval")) {
        require(session.role().equals("reviewer"), 403, "REVIEWER_REQUIRED");
        relay(exchange, session.client().approval(run));
        return;
      }
      require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED");
      JsonNode body = read(exchange);
      String etag = text(body, "etag", 4096), key = text(body, "requestKey", 128);
      if (action.equals("decision")) {
        require(session.role().equals("reviewer"), 403, "REVIEWER_REQUIRED");
        String decision = text(body, "decision", 10);
        require(Set.of("APPROVE", "REJECT").contains(decision), 400, "INVALID_DECISION");
        ObjectNode upstream =
            JSON.createObjectNode()
                .put("digest", text(body, "digest", 100))
                .put("decision", decision)
                .put("reason", text(body, "reason", 1000));
        if (body.has("input")) upstream.set("input", body.get("input"));
        relay(
            exchange,
            session
                .client()
                .decideApproval(run, text(body, "approvalId", 200), upstream, etag, key));
        return;
      }
      require(session.role().equals("operator"), 403, "OPERATOR_REQUIRED");
      ApiResponse response =
          switch (action) {
            case "pause" -> session.client().pause(run, etag, key);
            case "resume" -> session.client().resume(run, etag, key);
            case "cancel" -> session.client().cancel(run, etag, key);
            default -> throw new Failure(404, "NOT_FOUND");
          };
      relay(exchange, response);
    } catch (SupportIdentity.Failure failure) {
      if (failure.status == 401 || failure.status == 403) {
        String previous = cookie(exchange);
        if (previous != null) sessions.remove(previous);
      }
      respond(exchange, failure.status, JSON.createObjectNode().put("code", failure.code));
    } catch (Failure failure) {
      respond(exchange, failure.status, JSON.createObjectNode().put("code", failure.code));
    } catch (PlatformException failure) {
      ObjectNode body =
          JSON.createObjectNode()
              .put("code", failure.code())
              .put("outcomeUnknown", failure.outcomeUnknown());
      if (failure.requestId() != null) body.put("requestId", failure.requestId());
      respond(
          exchange,
          failure.status() >= 400 && failure.status() <= 599 ? failure.status() : 502,
          body);
    } catch (IllegalArgumentException failure) {
      respond(exchange, 400, JSON.createObjectNode().put("code", "INVALID_REQUEST"));
    } catch (Exception failure) {
      respond(exchange, 500, JSON.createObjectNode().put("code", "INTERNAL_ERROR"));
    } finally {
      exchange.close();
    }
  }

  private void create(HttpExchange exchange, Session session, JsonNode body) throws IOException {
    String agentId = text(body, "agentId", 128),
        releaseId = text(body, "releaseId", 64),
        key = text(body, "requestKey", 128);
    require(body.path("inputs").isObject(), 400, "INPUTS_REQUIRED");
    JsonNode reference = body.path("releaseRef");
    require(reference.isObject(), 400, "RELEASE_REFERENCE_REQUIRED");
    fields(reference, Set.of("agentId", "releaseId", "digest"));
    require(
        agentId.equals(text(reference, "agentId", 128))
            && releaseId.equals(text(reference, "releaseId", 64))
            && agentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
            && UUID.fromString(releaseId).toString().equals(releaseId)
            && text(reference, "digest", 71).matches("sha256:[a-f0-9]{64}"),
        400,
        "INVALID_RELEASE_REFERENCE");
    ObjectNode upstream = JSON.createObjectNode();
    upstream.set("releaseRef", reference);
    // The platform checks receipts first, then requires formal publication for every new key.
    // A BFF restart or publication retirement cannot prevent an exact old receipt lookup.
    upstream.put("requirePublished", true);
    upstream.set("inputs", body.get("inputs"));
    upstream.put("clientReference", "support-" + key);
    relay(exchange, session.client().createRun(upstream, key));
  }

  private synchronized void login(HttpExchange exchange, JsonNode body) throws IOException {
    long now = System.currentTimeMillis();
    if (now - loginWindow >= 60000) {
      loginWindow = now;
      loginAttempts = 0;
    }
    require(++loginAttempts <= 30, 429, "LOGIN_RATE_LIMITED");
    Session previousSession = session(exchange);
    if (previousSession != null)
      require(
          equal(previousSession.csrf(), oneHeader(exchange, "X-CSRF-Token")), 403, "CSRF_REJECTED");
    String role, userToken, subject, application;
    if (identity == null) {
      fields(body, Set.of("accessCode"));
      byte[] supplied = hash(text(body, "accessCode", 512));
      boolean operator = MessageDigest.isEqual(supplied, operatorCode);
      boolean reviewer = reviewerCode != null && MessageDigest.isEqual(supplied, reviewerCode);
      require(operator || reviewer, 401, "ACCESS_CODE_INVALID");
      role = operator ? "operator" : "reviewer";
      userToken = (operator ? config.operatorToken() : config.reviewerToken()).resolve();
      subject = "local-test-" + role;
      application = "local-test";
    } else {
      fields(body, Set.of("username", "password", "captchaId", "captchaCode", "role"));
      role = text(body, "role", 16);
      require(Set.of("operator", "reviewer").contains(role), 400, "INVALID_ROLE");
      userToken = identity.login(body);
      var principal = identity.current(userToken);
      requireRole(principal.permissions(), role);
      subject = principal.subject();
      application = principal.applicationId();
    }
    sessions.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    require(sessions.size() < 128, 429, "SESSION_LIMIT");
    String previous = cookie(exchange);
    if (previous != null) sessions.remove(previous);
    String token = random(), csrf = random();
    PlatformClient client =
        new PlatformClient(
            ClientConfiguration.defaults(
                config.platformOrigin(),
                config.project(),
                config.applicationSecret(),
                () -> userToken));
    var created =
        new Session(
            role,
            subject,
            application,
            userToken,
            csrf,
            now + Duration.ofHours(1).toMillis(),
            client);
    sessions.put(token, created);
    exchange.getResponseHeaders().set("Set-Cookie", cookieHeader(token, 3600));
    respond(exchange, 200, sessionBody(JSON.createObjectNode().put("signedIn", true), created));
  }

  private ObjectNode sessionBody(ObjectNode body, Session session) {
    return body.put("role", session.role())
        .put("subject", session.subject())
        .put("application", session.application())
        .put("csrfToken", session.csrf())
        .put("project", config.project());
  }

  private String cookieHeader(String value, int seconds) {
    return "support_session="
        + value
        + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
        + seconds
        + (origin.startsWith("https://") ? "; Secure" : "");
  }

  private void validateSession(Session session) {
    if (identity == null) return;
    var current = identity.current(session.userToken());
    require(
        current.subject().equals(session.subject())
            && current.applicationId().equals(session.application()),
        403,
        "SESSION_IDENTITY_CHANGED");
    requireRole(current.permissions(), session.role());
  }

  private static void requireRole(Set<String> permissions, String role) {
    Set<String> required =
        role.equals("reviewer")
            ? Set.of("approvals:read", "approvals:decide")
            : Set.of("runs:create", "runs:read", "runs:list");
    require(
        permissions.contains("*") || permissions.containsAll(required), 403, "ROLE_NOT_GRANTED");
  }

  private static void fields(JsonNode body, Set<String> allowed) {
    body.fieldNames()
        .forEachRemaining(name -> require(allowed.contains(name), 400, "INVALID_REQUEST"));
  }

  private Session session(HttpExchange exchange) {
    String key = cookie(exchange);
    Session session = key == null ? null : sessions.get(key);
    if (session != null && session.expiresAt() <= System.currentTimeMillis()) {
      sessions.remove(key);
      return null;
    }
    return session;
  }

  private static String cookie(HttpExchange exchange) {
    String cookie = oneHeader(exchange, "Cookie");
    if (cookie == null) return null;
    String result = null;
    for (String pair : cookie.split(";"))
      if (pair.trim().startsWith("support_session=")) {
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
    require(
        "application/json".equalsIgnoreCase(oneHeader(exchange, "Content-Type")),
        415,
        "JSON_REQUIRED");
    byte[] raw = exchange.getRequestBody().readNBytes(65537);
    require(raw.length <= 65536, 413, "REQUEST_TOO_LARGE");
    JsonNode body;
    try {
      body = JSON.readTree(raw);
    } catch (Exception failure) {
      throw new Failure(400, "INVALID_JSON");
    }
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
    require(
        value != null
            && value.isTextual()
            && !value.asText().isBlank()
            && value.asText().length() <= max,
        400,
        "INVALID_REQUEST");
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
      exchange
          .getResponseHeaders()
          .set(
              "Content-Type",
              file.endsWith(".js")
                  ? "text/javascript; charset=utf-8"
                  : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static boolean equal(String left, String right) {
    return right != null && MessageDigest.isEqual(hash(left), hash(right));
  }

  private static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void require(boolean valid, int status, String code) {
    if (!valid) throw new Failure(status, code);
  }

  private static final class Failure extends RuntimeException {
    final int status;
    final String code;

    Failure(int status, String code) {
      this.status = status;
      this.code = code;
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Required support configuration missing");
    return value;
  }

  public static void main(String[] args) throws Exception {
    String authMode = System.getenv().getOrDefault("SUPPORT_AUTH_MODE", "opsagent");
    boolean localTest = authMode.equals("local-test");
    String reviewer = localTest ? System.getenv("SUPPORT_REVIEWER_USER_TOKEN") : null;
    String publicOrigin = System.getenv("SUPPORT_PUBLIC_ORIGIN");
    Configuration config =
        new Configuration(
            URI.create(required("SUPPORT_PLATFORM_ORIGIN")),
            System.getenv().getOrDefault("SUPPORT_PROJECT", "support-pilot"),
            Integer.parseInt(System.getenv().getOrDefault("SUPPORT_PORT", "8098")),
            SecretProvider.environment("SUPPORT_APPLICATION_SECRET"),
            localTest ? SecretProvider.environment("SUPPORT_OPERATOR_USER_TOKEN") : null,
            localTest ? required("SUPPORT_OPERATOR_ACCESS_CODE") : null,
            reviewer == null ? null : SecretProvider.environment("SUPPORT_REVIEWER_USER_TOKEN"),
            reviewer == null ? null : required("SUPPORT_REVIEWER_ACCESS_CODE"),
            System.getenv().getOrDefault("SUPPORT_RUNTIME_MODE", "development"),
            authMode,
            localTest ? null : URI.create(required("SUPPORT_IDENTITY_ORIGIN")),
            publicOrigin == null || publicOrigin.isBlank() ? null : URI.create(publicOrigin),
            System.getenv().getOrDefault("SUPPORT_BIND_ADDRESS", "127.0.0.1"));
    required("SUPPORT_APPLICATION_SECRET");
    if (localTest) required("SUPPORT_OPERATOR_USER_TOKEN");
    SupportPilot app = new SupportPilot(config);
    Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    app.start();
    System.out.println("Support pilot listening at " + app.origin());
  }
}
