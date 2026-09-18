package io.github.djyking.harness.platform;

import io.github.djyking.harness.integrations.opsagent.OpsAgentIdentityClient;
import io.github.djyking.harness.integrations.opsagent.OpsAgentProjectionClient;
import java.net.URI;
import java.time.*;
import java.util.Map;

/** Adapter to existing OpsAgent Auth; does not create or own user accounts. */
public final class OpsAgentIdentityProvider implements IdentityProvider {
  private final OpsAgentIdentityClient client;
  private final Map<String, String> applicationSecrets;
  private final SecretProvider secrets;
  private final OpsAgentProjectionClient projection;

  public OpsAgentIdentityProvider(
      URI origin, Map<String, String> applications, SecretProvider secrets) {
    this(origin, null, applications, secrets);
  }

  public OpsAgentIdentityProvider(
      URI origin, URI ragOrigin, Map<String, String> applications, SecretProvider secrets) {
    client = new OpsAgentIdentityClient(origin, Duration.ofSeconds(5));
    applicationSecrets = Map.copyOf(applications);
    this.secrets = secrets;
    projection =
        ragOrigin == null ? null : new OpsAgentProjectionClient(ragOrigin, Duration.ofSeconds(5));
  }

  private String credential(String application) {
    return "Bearer " + secrets.resolve(applicationSecrets.get(application));
  }

  private Principal principal(OpsAgentIdentityClient.Identity i) {
    return new Principal(i.applicationId(), i.projectId(), i.subject(), i.permissions());
  }

  private ApiFailure failure(OpsAgentIdentityClient.IdentityFailure ex) {
    return switch (ex.status()) {
      case 401 -> new ApiFailure(401, "UNAUTHENTICATED");
      case 403 -> ApiFailure.denied();
      case 404 -> ApiFailure.hidden();
      case 409 -> new ApiFailure(409, "INVALID_STATE");
      default -> new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    };
  }

  public Principal authenticate(String project, String applicationCredential, String userToken) {
    try {
      Principal p =
          principal(client.introspect("Bearer " + applicationCredential, project, userToken));
      if (!p.project().equals(project) || !applicationSecrets.containsKey(p.application()))
        throw ApiFailure.denied();
      // Background work must use exactly the application authenticated on this request.
      if (!java.security.MessageDigest.isEqual(
          applicationCredential.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          secrets
              .resolve(applicationSecrets.get(p.application()))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw ApiFailure.denied();
      return p;
    } catch (OpsAgentIdentityClient.IdentityFailure ex) {
      throw failure(ex);
    }
  }

  public String delegate(Principal p, String app, String user, String run, Instant deadline) {
    try {
      var d = client.bind("Bearer " + app, p.project(), user, run, deadline);
      if (!principal(d.identity()).equals(p) || d.expiresAt().isBefore(deadline))
        throw ApiFailure.denied();
      return d.delegationId();
    } catch (OpsAgentIdentityClient.IdentityFailure ex) {
      throw failure(ex);
    }
  }

  public Principal current(String app, String project, String delegation, String run) {
    try {
      Principal p = principal(client.current(credential(app), delegation, run));
      if (!p.application().equals(app) || !p.project().equals(project)) throw ApiFailure.denied();
      return p;
    } catch (OpsAgentIdentityClient.IdentityFailure ex) {
      throw failure(ex);
    }
  }

  public String toolAuthorization(String app, String project, String delegation, String run) {
    try {
      return client.token(credential(app), delegation, run, "rag").authorization();
    } catch (OpsAgentIdentityClient.IdentityFailure ex) {
      throw failure(ex);
    }
  }

  @Override
  public boolean canReadProtectedOutput(
      Principal reader,
      PlatformRepository.Owned owned,
      com.fasterxml.jackson.databind.JsonNode output) {
    if (projection == null
        || !reader.application().equals(owned.application())
        || !reader.project().equals(owned.project())
        || !reader.subject().equals(owned.subject())
        || !reader.permits("opsagent:rag:search")
        || !reader.permits("tool:opsagent/rag-search")
        || !output.isObject()
        || output.size() != 2
        || !output.path("evidence").isTextual()
        || !output.path("citations").isArray()) return false;
    try {
      String authorization =
          toolAuthorization(
              owned.application(), owned.project(), owned.delegation(), owned.runId());
      return projection.allowed(authorization, output.path("citations"));
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
