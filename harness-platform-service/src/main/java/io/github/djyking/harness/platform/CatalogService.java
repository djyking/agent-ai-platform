package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Project-scoped resource lifecycle; every mutation is CAS + idempotency + durable audit. */
public final class CatalogService {
  private final Deployment config;
  private final PlatformRepository platform;
  private final CatalogRepository repository;
  private final CatalogValidator validator;

  public CatalogService(
      Deployment config,
      PlatformRepository platform,
      ToolRegistry tools,
      Set<String> knownSecrets) {
    this.config = config;
    this.platform = platform;
    repository = new CatalogRepository(platform);
    repository.initialize();
    validator = new CatalogValidator(config, tools, repository, knownSecrets);
  }

  private void require(IdentityProvider.Principal p, String permission) {
    if (!config.project(p.project()).applications().contains(p.application()))
      throw ApiFailure.hidden();
    if (!p.permits(permission)) throw ApiFailure.denied();
  }

  private void identity(String type, String id) {
    if (!CatalogValidator.TYPES.contains(type)) throw ApiFailure.hidden();
    ApiJson.identifier(id);
  }

  public static String etag(JsonNode value) {
    return "\"c" + value.path("revision").asLong() + "\"";
  }

  public JsonNode capabilities(IdentityProvider.Principal p) {
    require(p, "catalog:read");
    return validator.capabilities(p.project());
  }

  public JsonNode list(IdentityProvider.Principal p, String type) {
    require(p, "catalog:read");
    if (type != null && !CatalogValidator.TYPES.contains(type)) throw ApiFailure.invalid();
    return platform.transaction(
        c -> {
          ObjectNode out = Json.object();
          ArrayNode items = out.putArray("items");
          for (ObjectNode r : repository.resources(c, p.project(), type))
            items.add(view(c, p.project(), r));
          return out;
        });
  }

  public JsonNode get(IdentityProvider.Principal p, String type, String id) {
    require(p, "catalog:read");
    identity(type, id);
    return platform.transaction(
        c -> view(c, p.project(), repository.resource(c, p.project(), type, id, true)));
  }

  public JsonNode version(IdentityProvider.Principal p, String type, String id, int version) {
    require(p, "catalog:read");
    identity(type, id);
    return platform.transaction(c -> repository.version(c, p.project(), type, id, version));
  }

  private ObjectNode view(Connection c, String project, ObjectNode resource) throws SQLException {
    ObjectNode out = resource.deepCopy();
    ArrayNode versions = out.putArray("versions");
    for (ObjectNode v :
        repository.versions(
            c, project, resource.path("type").asText(), resource.path("id").asText())) {
      ObjectNode summary =
          Json.object()
              .put("version", v.path("version").asInt())
              .put("digest", v.path("digest").asText())
              .put("publishedAt", v.path("publishedAt").asText())
              .put("disabled", v.path("disabled").asBoolean());
      if (v.has("releaseRef")) summary.set("releaseRef", v.get("releaseRef"));
      versions.add(summary);
    }
    out.set(
        "audit",
        repository.audit(c, project, resource.path("type").asText(), resource.path("id").asText()));
    return out;
  }

  public JsonNode mutate(
      IdentityProvider.Principal p,
      String type,
      String id,
      String operation,
      Integer version,
      String key,
      String ifMatch,
      JsonNode body) {
    identity(type, id);
    if (!Set.of("save", "validate", "publish", "disable", "default", "revoke").contains(operation))
      throw ApiFailure.hidden();
    require(
        p,
        switch (operation) {
          case "save" -> "catalog:write";
          case "validate" -> "catalog:validate";
          default -> "catalog:publish";
        });
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw new ApiFailure(400, "IDEMPOTENCY_KEY_REQUIRED");
    if (ifMatch == null) throw new ApiFailure(428, "PRECONDITION_REQUIRED");
    if (!ifMatch.matches("\"c[0-9]{1,18}\"")) throw ApiFailure.invalid();
    long expected = Long.parseLong(ifMatch.substring(2, ifMatch.length() - 1));
    if (body == null || !body.isObject()) throw ApiFailure.invalid();
    validator.sanitize(body);
    if (Json.write(body).length() > 60000) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    String hash =
        Json.hash(
            List.of(
                p.project(),
                p.application(),
                p.subject(),
                type,
                id,
                operation,
                Objects.toString(version, ""),
                key));
    String digest = Json.hash(List.of(ApiJson.canonical(body), ifMatch));
    return platform.transaction(
        c -> {
          platform.lockProject(c, p.project());
          JsonNode prior = repository.receipt(c, hash, digest);
          if (prior != null) return prior;
          ObjectNode r = repository.resource(c, p.project(), type, id, !operation.equals("save"));
          long current = r == null ? 0 : r.path("revision").asLong();
          if (current != expected) throw new ApiFailure(412, "PRECONDITION_FAILED");
          if (r == null)
            r =
                Json.object()
                    .put("type", type)
                    .put("id", id)
                    .put("revision", 0)
                    .put("status", "DRAFT")
                    .put("disabled", false)
                    .put("revoked", false)
                    .put("defaultVersion", 0);
          switch (operation) {
            case "save" -> {
              CatalogValidator.fields(
                  body, Set.of("name", "spec", "regressionCases"), Set.of("name", "spec"));
              String name = CatalogValidator.text(body, "name");
              if (name.length() > 200 || !body.path("spec").isObject())
                throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
              validator.draftShape(type, body.path("spec"));
              JsonNode cases =
                  body.has("regressionCases")
                      ? body.get("regressionCases")
                      : Json.MAPPER.createArrayNode();
              if (!cases.isArray() || cases.size() > 20)
                throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
              r.put("name", name).put("status", "DRAFT");
              r.set("spec", body.path("spec").deepCopy());
              r.set("regressionCases", cases.deepCopy());
              r.putNull("validation");
            }
            case "validate" -> {
              CatalogValidator.fields(body, Set.of(), Set.of());
              ObjectNode report;
              try {
                validator.validate(c, p.project(), type, r.path("spec"));
                if (type.equals("Agent")) {
                  var compiled =
                      validator.compile(
                          c, p.project(), id, UUID.randomUUID().toString(), r.path("spec"));
                  report =
                      (ObjectNode)
                          validator.regression(compiled.release(), r.path("regressionCases"));
                  report.set("references", compiled.references());
                } else
                  report =
                      Json.object().put("passed", true).put("mode", "TYPED_CONTRACT_VALIDATION");
                String binding = validator.bindingDigest(p.project(), type, r.path("spec"));
                if (!binding.isEmpty()) report.put("bindingDigest", binding);
              } catch (ApiFailure ex) {
                report = Json.object().put("passed", false).put("code", ex.code);
              } catch (IllegalArgumentException | InvocationException ex) {
                report = Json.object().put("passed", false).put("code", "CATALOG_SPEC_INVALID");
              }
              report
                  .put("draftDigest", draftDigest(r))
                  .put("validatedAt", Instant.ofEpochMilli(PlatformRepository.now(c)).toString());
              r.set("validation", report);
              r.put("status", report.path("passed").asBoolean() ? "VALIDATED" : "DRAFT");
            }
            case "publish" -> {
              CatalogValidator.fields(body, Set.of(), Set.of());
              if (r.path("disabled").asBoolean() || r.path("revoked").asBoolean())
                throw new ApiFailure(409, "RESOURCE_UNAVAILABLE");
              if (!r.path("validation").path("passed").asBoolean()
                  || !r.path("validation").path("draftDigest").asText().equals(draftDigest(r)))
                throw new ApiFailure(409, "VALIDATION_REQUIRED");
              try {
                validator.validate(c, p.project(), type, r.path("spec"));
              } catch (IllegalArgumentException | InvocationException ex) {
                throw new ApiFailure(422, "CATALOG_SPEC_INVALID");
              }
              List<ObjectNode> versions = repository.versions(c, p.project(), type, id);
              int next = versions.isEmpty() ? 1 : versions.get(0).path("version").asInt() + 1;
              ObjectNode published =
                  Json.object()
                      .put("type", type)
                      .put("id", id)
                      .put("name", r.path("name").asText())
                      .put("version", next)
                      .put("digest", draftDigest(r))
                      .put(
                          "publishedAt", Instant.ofEpochMilli(PlatformRepository.now(c)).toString())
                      .put("disabled", false);
              published.set("spec", r.path("spec").deepCopy());
              published.set("regressionCases", r.path("regressionCases").deepCopy());
              published.set("validation", r.path("validation").deepCopy());
              String binding = validator.bindingDigest(p.project(), type, r.path("spec"));
              if (!binding.isEmpty()) {
                if (!binding.equals(r.path("validation").path("bindingDigest").asText()))
                  throw new ApiFailure(409, "VALIDATION_REQUIRED");
                published.put("bindingDigest", binding);
              }
              if (type.equals("Agent")) {
                var compiled =
                    validator.compile(
                        c, p.project(), id, UUID.randomUUID().toString(), r.path("spec"));
                JsonNode replay =
                    validator.regression(compiled.release(), r.path("regressionCases"));
                if (!replay.path("passed").asBoolean()
                    || !replay
                        .path("resultDigest")
                        .equals(r.path("validation").path("resultDigest")))
                  throw new ApiFailure(409, "VALIDATION_REQUIRED");
                ArrayNode refs = (ArrayNode) compiled.references().deepCopy();
                refs.add(
                    Json.object()
                        .put("type", "Agent")
                        .put("id", id)
                        .put("version", next)
                        .put("digest", published.path("digest").asText()));
                repository.release(c, compiled.release(), next, refs);
                published.set("releaseRef", compiled.release().reference());
                published.set("references", refs);
                if (r.path("defaultVersion").asInt() == 0) r.put("defaultVersion", next);
              }
              repository.publish(c, p.project(), published);
              r.put("status", "PUBLISHED");
            }
            case "default" -> {
              CatalogValidator.fields(body, Set.of("version"), Set.of("version"));
              if (!type.equals("Agent") || !body.path("version").isInt())
                throw ApiFailure.invalid();
              ObjectNode v =
                  repository.version(c, p.project(), type, id, body.path("version").asInt());
              requireNewRun(c, p.project(), v.path("releaseRef").path("releaseId").asText());
              r.put("defaultVersion", body.path("version").asInt());
            }
            case "disable" -> {
              CatalogValidator.fields(body, Set.of("disabled"), Set.of("disabled"));
              if (!body.path("disabled").isBoolean()) throw ApiFailure.invalid();
              if (version == null) r.put("disabled", body.path("disabled").asBoolean());
              else
                repository.disable(
                    c, p.project(), type, id, version, body.path("disabled").asBoolean());
            }
            case "revoke" -> {
              CatalogValidator.fields(body, Set.of("revoked"), Set.of("revoked"));
              if (!body.path("revoked").isBoolean() || version != null) throw ApiFailure.invalid();
              r.put("revoked", body.path("revoked").asBoolean());
            }
            default -> throw ApiFailure.invalid();
          }
          r.put("revision", current + 1);
          repository.save(c, p.project(), r, current);
          JsonNode response = Json.read(Json.write(view(c, p.project(), r)));
          repository.receipt(
              c,
              hash,
              digest,
              response,
              p,
              type,
              id,
              operation + (version == null ? "" : "-version-" + version));
          return response;
        });
  }

  private String draftDigest(JsonNode r) {
    return "sha256:"
        + Json.hash(
            List.of(
                ApiJson.canonical(r.path("spec")), ApiJson.canonical(r.path("regressionCases"))));
  }

  public Deployment.Release releaseForRun(String project, String releaseId) {
    return platform.transaction(
        c -> {
          requireNewRun(c, project, releaseId);
          Deployment.Release r = repository.release(c, project, releaseId);
          try {
            config.validateRelease(r, validatorTools());
          } catch (RuntimeException ex) {
            throw new ApiFailure(409, "RELEASE_RUNTIME_UNAVAILABLE");
          }
          return r;
        });
  }

  // Validation already shares the runtime registry; avoid exposing handlers through the catalog.
  private ToolRegistry validatorTools() {
    return validator.registry();
  }

  public void requireNewRun(Connection c, String project, String releaseId) throws SQLException {
    Deployment.Release release = repository.release(c, project, releaseId);
    if (config.disabled(release)) throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
    JsonNode refs = repository.releaseRefs(c, project, releaseId);
    if (refs == null) return;
    for (JsonNode ref : refs) {
      ObjectNode r =
          repository.resource(c, project, ref.path("type").asText(), ref.path("id").asText(), true);
      ObjectNode v =
          repository.version(
              c,
              project,
              ref.path("type").asText(),
              ref.path("id").asText(),
              ref.path("version").asInt());
      if (r.path("disabled").asBoolean()
          || r.path("revoked").asBoolean()
          || v.path("disabled").asBoolean()) throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
      if (ref.has("bindingDigest")) {
        try {
          if (!ref.path("bindingDigest")
              .asText()
              .equals(validator.bindingDigest(project, ref.path("type").asText(), v.path("spec"))))
            throw new ApiFailure(409, "TRUSTED_BINDING_CHANGED");
        } catch (ApiFailure | InvocationException unavailable) {
          throw new ApiFailure(409, "TRUSTED_BINDING_CHANGED");
        }
      }
    }
  }

  /**
   * Only explicit emergency revocation affects existing runs; normal version retirement does not.
   */
  public void requireExecution(String project, String releaseId) {
    platform.transaction(
        c -> {
          JsonNode refs = repository.releaseRefs(c, project, releaseId);
          if (refs != null)
            for (JsonNode ref : refs)
              if (repository
                  .resource(c, project, ref.path("type").asText(), ref.path("id").asText(), true)
                  .path("revoked")
                  .asBoolean()) throw ApiFailure.denied();
          return null;
        });
  }

  public JsonNode releases(IdentityProvider.Principal p) {
    if (!p.permits("catalog:read")) require(p, "runs:create");
    else require(p, "catalog:read");
    return platform.transaction(
        c -> {
          ObjectNode out = Json.object();
          ArrayNode items = out.putArray("items");
          for (ObjectNode r : repository.resources(c, p.project(), "Agent"))
            for (ObjectNode v : repository.versions(c, p.project(), "Agent", r.path("id").asText()))
              try {
                items.add(discovery(c, p, r, v));
              } catch (ApiFailure ex) {
                if (ex.status != 404 && ex.status != 409) throw ex;
              }
          return out;
        });
  }

  public JsonNode defaultRelease(IdentityProvider.Principal p, String id) {
    if (!p.permits("catalog:read")) require(p, "runs:create");
    else require(p, "catalog:read");
    ApiJson.identifier(id);
    return platform.transaction(
        c -> {
          ObjectNode r = repository.resource(c, p.project(), "Agent", id, true);
          if (r.path("defaultVersion").asInt() == 0)
            throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
          return discovery(
              c,
              p,
              r,
              repository.version(c, p.project(), "Agent", id, r.path("defaultVersion").asInt()));
        });
  }

  private ObjectNode discovery(
      Connection c, IdentityProvider.Principal p, JsonNode resource, JsonNode version)
      throws SQLException {
    String project = p.project();
    String releaseId = version.path("releaseRef").path("releaseId").asText();
    requireNewRun(c, project, releaseId);
    Deployment.Release release = repository.release(c, project, releaseId);
    if (!p.permits("catalog:read")
        && release.executionPermissions().stream().anyMatch(permission -> !p.permits(permission)))
      throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
    ObjectNode out =
        Json.object()
            .put("agentId", release.agentId())
            .put("version", version.path("version").asInt())
            .put("name", version.path("name").asText())
            .put(
                "isDefault",
                resource.path("defaultVersion").asInt() == version.path("version").asInt());
    out.set("releaseRef", release.reference());
    out.set("inputSchema", release.inputSchema());
    out.set("outputSchema", release.outputSchema());
    return out;
  }

  public JsonNode diff(
      IdentityProvider.Principal p, String type, String id, int version, int against) {
    require(p, "catalog:read");
    identity(type, id);
    return platform.transaction(
        c -> {
          JsonNode before = repository.version(c, p.project(), type, id, against),
              after = repository.version(c, p.project(), type, id, version);
          ObjectNode out = Json.object().put("fromVersion", against).put("toVersion", version);
          ArrayNode changes = out.putArray("changes");
          diff("/spec", before.path("spec"), after.path("spec"), changes);
          diff(
              "/regressionCases",
              before.path("regressionCases"),
              after.path("regressionCases"),
              changes);
          return out;
        });
  }

  private void diff(String path, JsonNode before, JsonNode after, ArrayNode out) {
    if (before.equals(after)) return;
    if (before.isObject() && after.isObject()) {
      Set<String> keys = new TreeSet<>();
      before.fieldNames().forEachRemaining(keys::add);
      after.fieldNames().forEachRemaining(keys::add);
      for (String key : keys)
        diff(
            path + "/" + key.replace("~", "~0").replace("/", "~1"),
            before.path(key),
            after.path(key),
            out);
    } else {
      ObjectNode change = Json.object().put("path", path);
      change.set("before", before.isMissingNode() ? NullNode.instance : before);
      change.set("after", after.isMissingNode() ? NullNode.instance : after);
      out.add(change);
    }
  }
}
