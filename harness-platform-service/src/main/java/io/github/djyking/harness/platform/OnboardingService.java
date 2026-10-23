package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.now;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.util.*;

/**
 * Approval does not grant identity authority. A trusted offline provisioner installs bindings;
 * activation verifies the running manifest and an actual identity-authority introspection.
 */
public final class OnboardingService {
  private final Deployment deployment;
  final OnboardingRepository repository;
  private final IdentityProvider identity;
  private final SecretProvider secrets;

  public OnboardingService(
      Deployment deployment,
      PlatformRepository platform,
      IdentityProvider identity,
      SecretProvider secrets) {
    this.deployment = deployment;
    repository = new OnboardingRepository(platform);
    this.identity = identity;
    this.secrets = secrets;
  }

  public void initialize() {
    repository.initialize();
  }

  public void requireAccess(IdentityProvider.Principal principal) {
    repository.requireAccess(principal);
  }

  static boolean admin(IdentityProvider.Principal p) {
    return p.permits("onboarding:admin") || p.permits("onboarding:platform-admin");
  }

  private static void readPermission(IdentityProvider.Principal p) {
    if (!p.permits("onboarding:request") && !admin(p)) throw ApiFailure.denied();
  }

  static void fields(JsonNode body, Set<String> allowed) {
    if (body == null || !body.isObject()) throw ApiFailure.invalid();
    body.fieldNames()
        .forEachRemaining(
            n -> {
              if (!allowed.contains(n)) throw ApiFailure.invalid();
            });
  }

  static String text(JsonNode body, String field, int max) {
    if (!body.path(field).isTextual()) throw ApiFailure.invalid();
    String result = body.path(field).asText().strip();
    if (result.isEmpty() || result.length() > max || result.chars().anyMatch(c -> c < 32))
      throw ApiFailure.invalid();
    return result;
  }

  /**
   * All permissions are selected by a reviewed fixed profile, never by request-supplied strings.
   */
  public static Set<String> permissions(String profile) {
    TreeSet<String> set =
        new TreeSet<>(
            Set.of(
                "runs:create",
                "runs:read",
                "runs:list",
                "runs:events:read",
                "runs:control",
                "runs:output:read",
                "model:invoke",
                "tool:studio:knowledge",
                "studio:knowledge",
                "onboarding:request"));
    if (profile.equals("knowledge-reader") || profile.equals("builder")) {
      set.add("tool:opsagent/rag-search");
      set.add("opsagent:rag:search");
    }
    if (profile.equals("builder"))
      set.addAll(Set.of("catalog:read", "catalog:write", "catalog:validate", "catalog:publish"));
    else if (!profile.equals("assistant") && !profile.equals("knowledge-reader"))
      throw ApiFailure.invalid();
    return Collections.unmodifiableSet(set);
  }

  public JsonNode list(IdentityProvider.Principal p, String after, int limit) {
    readPermission(p);
    return repository.list(p, after, limit, admin(p));
  }

  private void visible(IdentityProvider.Principal p, ObjectNode request) {
    readPermission(p);
    if (!admin(p) && !request.path("requester").asText().equals(p.assignment()))
      throw ApiFailure.hidden();
  }

  public JsonNode get(IdentityProvider.Principal p, String id) {
    ApiJson.identifier(id);
    return repository.platform.transaction(
        c -> {
          var value = repository.get(c, p.project(), id);
          visible(p, value);
          return value;
        });
  }

  public JsonNode command(IdentityProvider.Principal p, String key) {
    readPermission(p);
    JsonNode value = repository.records.command(p, key, false);
    if (value.has("kind") && !value.path("kind").asText().equals(OnboardingRepository.KIND))
      return Json.object().put("status", "NOT_FOUND");
    return value;
  }

  public JsonNode request(
      IdentityProvider.Principal p, String id, String key, String etag, JsonNode input) {
    readPermission(p);
    ApiJson.identifier(id);
    fields(input, Set.of("targetProject", "applicationId", "name", "purpose", "profile"));
    String project = text(input, "targetProject", 64);
    String app = text(input, "applicationId", 64);
    ApiJson.identifier(project);
    ApiJson.identifier(app);
    String name = text(input, "name", 100);
    String purpose = text(input, "purpose", 600);
    String profile = text(input, "profile", 32);
    permissions(profile);
    if (!project.equals(p.project()) && !p.permits("onboarding:platform-admin"))
      throw ApiFailure.denied();
    return repository.records.mutate(
        p,
        OnboardingRepository.KIND,
        id,
        "request",
        key,
        etag,
        input,
        (c, current) -> {
          if (current != null) throw new ApiFailure(409, "REQUEST_ALREADY_EXISTS");
          if (deployment.applicationSecrets().containsKey(app))
            throw new ApiFailure(409, "APPLICATION_ALREADY_CONFIGURED");
          var value =
              Json.object()
                  .put("id", id)
                  .put("sponsorProject", p.project())
                  .put("targetProject", project)
                  .put("applicationId", app)
                  .put("name", name)
                  .put("purpose", purpose)
                  .put("profile", profile)
                  .put("requester", p.assignment())
                  .put("requestingApplication", p.application())
                  .put("subject", p.subject())
                  .put("state", "REQUESTED")
                  .put("generation", 1)
                  .put("createdAt", now(c));
          audit(value, p.assignment(), "REQUESTED", now(c));
          repository.owner(c, value);
          return value;
        });
  }

  public JsonNode decide(
      IdentityProvider.Principal p, String id, String key, String etag, JsonNode input) {
    if (!admin(p)) throw ApiFailure.denied();
    fields(input, Set.of("decision", "reason"));
    String decision = text(input, "decision", 16);
    String reason = text(input, "reason", 300);
    if (!Set.of("APPROVE", "REJECT").contains(decision)) throw ApiFailure.invalid();
    return repository.records.mutate(
        p,
        OnboardingRepository.KIND,
        id,
        "decide",
        key,
        etag,
        input,
        (c, current) -> {
          requireState(current, "REQUESTED");
          if (!current.path("targetProject").asText().equals(p.project())
              && !p.permits("onboarding:platform-admin")) throw ApiFailure.denied();
          current.put("decisionReason", reason).put("approvedBy", p.assignment());
          if (decision.equals("APPROVE")) {
            repository.bind(c, current);
            current.put("state", "APPROVED");
            current.set("plan", plan(current));
          } else current.put("state", "REJECTED");
          audit(current, p.assignment(), decision, now(c));
          return current;
        });
  }

  static ObjectNode plan(ObjectNode request) {
    var plan =
        Json.object()
            .put("version", 1)
            .put("targetProject", request.path("targetProject").asText())
            .put("applicationId", request.path("applicationId").asText())
            .put("subject", request.path("subject").asText())
            .put("consoleApplication", request.path("requestingApplication").asText())
            .put(
                "consoleProjectGrant",
                !request
                    .path("targetProject")
                    .asText()
                    .equals(request.path("sponsorProject").asText()))
            .put("generation", request.path("generation").asInt())
            .put("profile", request.path("profile").asText())
            .put("requiresOfflineMigration", true)
            .put("requiresExistingIdentity", true)
            .put("requiresRuntimeVerification", true);
    plan.set("permissions", Json.MAPPER.valueToTree(permissions(request.path("profile").asText())));
    plan.put("digest", Json.hash(ApiJson.canonical(plan)));
    return plan;
  }

  public JsonNode lifecycle(
      IdentityProvider.Principal p, String id, String key, String etag, JsonNode input) {
    if (!admin(p)) throw ApiFailure.denied();
    fields(input, Set.of("operation", "reason"));
    String operation = text(input, "operation", 16);
    String reason = text(input, "reason", 300);
    if (!Set.of("ROTATE", "DISABLE").contains(operation)) throw ApiFailure.invalid();
    return repository.records.mutate(
        p,
        OnboardingRepository.KIND,
        id,
        "lifecycle",
        key,
        etag,
        input,
        (c, current) -> {
          if (operation.equals("ROTATE")) requireState(current, "ACTIVE");
          else requireState(current, "APPROVED", "ACTIVE", "PROVISIONED", "ROTATION_APPROVED");
          if (!current.path("targetProject").asText().equals(p.project())
              && !p.permits("onboarding:platform-admin")) throw ApiFailure.denied();
          repository.enabled(c, current, false);
          current.put("state", operation.equals("DISABLE") ? "DISABLED" : "ROTATION_APPROVED");
          current.put("lifecycleReason", reason);
          if (operation.equals("ROTATE")) {
            current.put("generation", current.path("generation").asInt() + 1);
            current.set("plan", plan(current));
            current.remove("provisioned");
          }
          audit(current, p.assignment(), operation, now(c));
          return current;
        });
  }

  /**
   * Reuses a freshly supplied original user token; neither token nor application secret is stored.
   */
  public JsonNode verify(
      IdentityProvider.Principal p, String id, String key, String etag, String userToken) {
    var value = (ObjectNode) get(p, id);
    if (!value.path("subject").asText().equals(p.subject())) throw ApiFailure.denied();
    String app = value.path("applicationId").asText();
    String project = value.path("targetProject").asText();
    if (!deployment.project(project).applications().contains(app)
        || !deployment.applicationSecrets().containsKey(app))
      throw new ApiFailure(409, "DEPLOYMENT_NOT_APPLIED");
    if (!OnboardingProvisioner.deploymentDigest(deployment)
        .equals(value.path("provisioned").path("candidateDigest").asText()))
      throw new ApiFailure(409, "DEPLOYMENT_DIGEST_MISMATCH");
    String credential = secrets.resolve(deployment.applicationSecrets().get(app));
    String credentialDigest = OnboardingProvisioner.credentialHash(credential);
    if (!credentialDigest.equals(value.path("provisioned").path("credentialFingerprint").asText()))
      throw new ApiFailure(409, "CREDENTIAL_NOT_APPLIED");
    IdentityProvider.Principal actual = identity.authenticate(project, credential, userToken);
    if (!actual.project().equals(project)
        || !actual.application().equals(app)
        || !actual.subject().equals(value.path("subject").asText())
        || !actual.permissions().equals(permissions(value.path("profile").asText())))
      throw new ApiFailure(403, "IDENTITY_BINDING_MISMATCH");
    return repository.records.mutate(
        p,
        OnboardingRepository.KIND,
        id,
        "verify",
        key,
        etag,
        Json.object(),
        (c, current) -> {
          requireState(current, "PROVISIONED", "ACTIVE");
          if (!value.path("provisioned").equals(current.path("provisioned")))
            throw new ApiFailure(412, "PRECONDITION_FAILED");
          repository.enabled(c, current, true);
          current.put("state", "ACTIVE").put("verifiedAt", now(c));
          audit(current, p.assignment(), "VERIFIED", now(c));
          return current;
        });
  }

  static void requireState(ObjectNode current, String... states) {
    if (current == null) throw ApiFailure.hidden();
    if (!Set.of(states).contains(current.path("state").asText()))
      throw new ApiFailure(409, "ONBOARDING_STATE_CONFLICT");
  }

  static void audit(ObjectNode request, String actor, String operation, long time) {
    var audit = request.withArray("audit");
    if (audit.size() >= 200) throw new ApiFailure(409, "ONBOARDING_AUDIT_LIMIT");
    audit.add(Json.object().put("actor", actor).put("operation", operation).put("at", time));
  }
}
