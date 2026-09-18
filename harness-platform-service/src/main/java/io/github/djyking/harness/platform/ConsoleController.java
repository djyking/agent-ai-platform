package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ConsoleController {
  static final String COOKIE = "harness_console";
  private final ConsoleSessions sessions;
  private final IdentityLogin login;

  public ConsoleController(ConsoleSessions sessions, IdentityLogin login) {
    this.sessions = sessions;
    this.login = login;
  }

  private ConsoleSessions.Session session(HttpServletRequest request) {
    String id = null;
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (COOKIE.equals(cookie.getName())) {
          if (id != null) throw ApiFailure.invalid();
          id = cookie.getValue();
        }
    return sessions.get(id);
  }

  private void cookie(
      HttpServletRequest request, HttpServletResponse response, String value, long seconds) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(COOKIE, value)
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Strict")
            .path("/console")
            .maxAge(seconds)
            .build()
            .toString());
  }

  private void origin(HttpServletRequest request, boolean required) {
    List<String> origins = Collections.list(request.getHeaders("Origin"));
    if (origins.size() > 1 || (required && origins.isEmpty()))
      throw new ApiFailure(403, "ORIGIN_REJECTED");
    if (origins.isEmpty()) return;
    try {
      URI actual = URI.create(origins.get(0));
      int port =
          actual.getPort() < 0 ? ("https".equals(actual.getScheme()) ? 443 : 80) : actual.getPort();
      if (!Objects.equals(actual.getScheme(), request.getScheme())
          || !Objects.equals(actual.getHost(), request.getServerName())
          || port != request.getServerPort()
          || actual.getUserInfo() != null
          || actual.getQuery() != null
          || actual.getFragment() != null
          || !(actual.getPath().isEmpty() || actual.getPath().equals("/")))
        throw new ApiFailure(403, "ORIGIN_REJECTED");
    } catch (IllegalArgumentException ex) {
      throw new ApiFailure(403, "ORIGIN_REJECTED");
    }
  }

  private JsonNode body(HttpServletRequest request, Set<String> allowed) throws IOException {
    String content = request.getContentType();
    if (content == null
        || !content.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    JsonNode body = ApiJson.read(request.getInputStream());
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw ApiFailure.invalid();
            });
    return body;
  }

  @GetMapping("/console/session")
  public JsonNode current(HttpServletRequest request) {
    origin(request, false);
    return sessions.view(session(request));
  }

  @PostMapping("/console/session")
  public JsonNode exchange(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    origin(request, true);
    var previous = session(request);
    JsonNode body = body(request, Set.of("projectId", "userToken"));
    if ((body.has("projectId") && !body.path("projectId").isTextual())
        || (body.has("userToken") && !body.path("userToken").isTextual()))
      throw ApiFailure.invalid();
    if (previous != null) sessions.csrf(previous, request.getHeader("X-CSRF-Token"));
    String token = body.path("userToken").asText(previous == null ? "" : previous.userToken());
    var next = sessions.create(body.path("projectId").asText(), token, previous);
    cookie(request, response, next.id(), 1800);
    return sessions.view(next);
  }

  @DeleteMapping("/console/session")
  public JsonNode logout(HttpServletRequest request, HttpServletResponse response) {
    origin(request, true);
    var current = session(request);
    sessions.csrf(current, request.getHeader("X-CSRF-Token"));
    sessions.remove(current);
    cookie(request, response, "", 0);
    return sessions.view(null);
  }

  @GetMapping("/console/auth/captcha")
  public JsonNode captcha(HttpServletRequest request) {
    origin(request, false);
    if (!sessions.enabled()) throw new ApiFailure(503, "CONSOLE_NOT_CONFIGURED");
    return login.captcha();
  }

  @GetMapping("/console/auth/config")
  public JsonNode identityConfiguration(HttpServletRequest request) {
    origin(request, false);
    return login.configuration();
  }

  @PostMapping("/console/login")
  public JsonNode login(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    origin(request, true);
    if (!sessions.enabled()) throw new ApiFailure(503, "CONSOLE_NOT_CONFIGURED");
    var previous = session(request);
    if (previous != null) sessions.csrf(previous, request.getHeader("X-CSRF-Token"));
    JsonNode input =
        body(request, Set.of("projectId", "username", "password", "captchaId", "captchaCode"));
    if (input.has("projectId")) {
      if (!input.path("projectId").isTextual()) throw ApiFailure.invalid();
      if (!input.path("projectId").asText().isBlank())
        ApiJson.identifier(input.path("projectId").asText());
    }
    var credentials = Json.object();
    List<String> credentialFields =
        login.captchaRequired()
            ? List.of("username", "password", "captchaId", "captchaCode")
            : List.of("username", "password");
    for (String key : credentialFields) {
      if (!input.path(key).isTextual()
          || input.path(key).asText().isBlank()
          || input.path(key).asText().length() > (key.equals("password") ? 512 : 128))
        throw ApiFailure.invalid();
      credentials.set(key, input.get(key));
    }
    String token = login.login(credentials);
    var next = sessions.create(input.path("projectId").asText(), token, previous);
    cookie(request, response, next.id(), 1800);
    return sessions.view(next);
  }

  @RequestMapping("/console/api/**")
  public void proxy(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    boolean mutating = !request.getMethod().equals("GET");
    origin(request, mutating);
    var current = session(request);
    if (current == null) throw new ApiFailure(401, "UNAUTHENTICATED");
    if (mutating) sessions.csrf(current, request.getHeader("X-CSRF-Token"));
    if (!Set.of("GET", "POST", "PUT", "DELETE").contains(request.getMethod()))
      throw new ApiFailure(405, "METHOD_NOT_ALLOWED");
    String suffix = request.getRequestURI().substring("/console/api".length());
    if (!suffix.matches("/projects/[A-Za-z0-9][A-Za-z0-9._-]{0,127}/[A-Za-z0-9/._-]+")
        || suffix.contains("..")
        || suffix.contains("//")) throw ApiFailure.invalid();
    String project = suffix.split("/", 4)[2];
    if (!current.project().equals(project)) throw ApiFailure.hidden();
    sessions.principal(current); // Recheck the original authority on every browser request.
    if (request.getContentLengthLong() > 65536) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    HttpServletRequest delegated =
        new HttpServletRequestWrapper(request) {
          @Override
          public String getHeader(String name) {
            if (name.equalsIgnoreCase("Authorization")) return "Bearer " + sessions.credential();
            if (name.equalsIgnoreCase("X-Harness-User-Token")) return current.userToken();
            return super.getHeader(name);
          }

          @Override
          public Enumeration<String> getHeaders(String name) {
            if (name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("X-Harness-User-Token"))
              return Collections.enumeration(List.of(getHeader(name)));
            return super.getHeaders(name);
          }

          @Override
          public Enumeration<String> getHeaderNames() {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(Collections.list(super.getHeaderNames()));
            names.add("Authorization");
            names.add("X-Harness-User-Token");
            return Collections.enumeration(names);
          }
        };
    request.getRequestDispatcher("/v1" + suffix).forward(delegated, response);
  }
}
