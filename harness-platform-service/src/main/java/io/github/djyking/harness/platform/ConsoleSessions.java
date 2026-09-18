package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Browser sessions retain existing identity tokens only in bounded process memory. */
public final class ConsoleSessions {
  public record Session(
      String id, String csrf, String project, String userToken, Instant expiresAt) {
    @Override
    public String toString() {
      return "ConsoleSession[redacted]";
    }
  }

  private final PlatformService service;
  private final Deployment deployment;
  private final String credential;
  private final Clock clock;
  private final int capacity;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public ConsoleSessions(
      PlatformService service,
      Deployment deployment,
      String credential,
      Clock clock,
      int capacity) {
    this.service = service;
    this.deployment = deployment;
    this.credential = credential;
    this.clock = clock;
    this.capacity = capacity;
  }

  public boolean enabled() {
    return credential != null && !credential.isBlank();
  }

  public IdentityProvider.Principal principal(Session session) {
    return service.authenticate(session.project(), credential, session.userToken());
  }

  public Session get(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) return null;
    Session value = sessions.get(id);
    if (value != null && !value.expiresAt().isAfter(clock.instant())) {
      sessions.remove(id, value);
      return null;
    }
    return value;
  }

  public synchronized Session create(String project, String userToken, Session previous) {
    if (!enabled()) throw new ApiFailure(503, "CONSOLE_NOT_CONFIGURED");
    if (userToken == null || userToken.isBlank() || userToken.length() > 8192)
      throw new ApiFailure(401, "UNAUTHENTICATED");
    if (project == null || project.isBlank()) project = discover(userToken);
    else {
      ApiJson.identifier(project);
      service.authenticate(project, credential, userToken);
    }
    sessions.values().removeIf(s -> !s.expiresAt().isAfter(clock.instant()));
    if (sessions.size() >= capacity && (previous == null || !sessions.containsKey(previous.id())))
      throw new ApiFailure(429, "SESSION_CAPACITY_REACHED");
    Session session =
        new Session(random(), random(), project, userToken, clock.instant().plusSeconds(1800));
    if (previous != null) sessions.remove(previous.id());
    sessions.put(session.id(), session);
    return session;
  }

  /** Project names are disclosed only after the user's original authority grants that scope. */
  private String discover(String userToken) {
    for (var project : deployment.projects()) {
      try {
        service.authenticate(project.id(), credential, userToken);
        return project.id();
      } catch (ApiFailure failure) {
        if (failure.status >= 500 || failure.status == 429) throw failure;
        if (!Set.of(401, 403, 404).contains(failure.status)) throw failure;
      }
    }
    throw new ApiFailure(403, "NO_AUTHORIZED_PROJECT");
  }

  public void remove(Session session) {
    if (session != null) sessions.remove(session.id());
  }

  String credential() {
    return credential;
  }

  public void csrf(Session session, String submitted) {
    if (session == null) throw new ApiFailure(401, "UNAUTHENTICATED");
    if (submitted == null
        || !MessageDigest.isEqual(
            session.csrf().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            submitted.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new ApiFailure(403, "CSRF_REJECTED");
  }

  public JsonNode view(Session session) {
    var out = Json.object().put("authenticated", session != null).put("configured", enabled());
    var projects = out.putArray("projects");
    if (session == null) return out;
    var p = principal(session);
    out.put("csrfToken", session.csrf()).put("expiresAt", session.expiresAt().toString());
    out.set("principal", Json.tree(p));
    for (var project : deployment.projects()) {
      try {
        service.authenticate(project.id(), credential, session.userToken());
        projects.addObject().put("id", project.id());
      } catch (ApiFailure failure) {
        if (failure.status >= 500 || failure.status == 429) throw failure;
        if (!Set.of(401, 403, 404).contains(failure.status)) throw failure;
      }
    }
    return out;
  }

  private String random() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
