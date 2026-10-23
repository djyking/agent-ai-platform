package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/**
 * Text knowledge with immutable document versions, current ACLs and conservative output provenance.
 */
public final class KnowledgeService {
  private final Deployment config;
  private final PlatformRepository platform;
  private final CapabilityRepository repository;

  public KnowledgeService(Deployment config, PlatformRepository platform) {
    this.config = config;
    this.platform = platform;
    this.repository = new CapabilityRepository(platform);
    repository.initialize();
  }

  public JsonNode list(IdentityProvider.Principal p) {
    CapabilityInput.require(config, p, "catalog:read");
    return platform.transaction(
        c -> {
          var out = Json.object();
          var items = out.putArray("items");
          for (var value : repository.list(c, p.project(), "knowledge-collection"))
            if (readable(p, value) || p.permits("catalog:publish")) items.add(value);
          return out;
        });
  }

  public JsonNode command(IdentityProvider.Principal p, String key) {
    CapabilityInput.require(config, p, "catalog:read");
    return repository.command(p, key, true);
  }

  public JsonNode get(IdentityProvider.Principal p, String collection) {
    CapabilityInput.require(config, p, "catalog:read");
    ApiJson.identifier(collection);
    return platform.transaction(
        c -> {
          ObjectNode value =
              repository.get(c, p.project(), "knowledge-collection", collection, true);
          if (!readable(p, value) && !p.permits("catalog:publish")) throw ApiFailure.hidden();
          return value;
        });
  }

  /**
   * Availability projection for Studio choices; denial does not poison an enclosing transaction.
   */
  public boolean canReadCollection(IdentityProvider.Principal p, String id) {
    CapabilityInput.require(config, p, "catalog:read");
    ApiJson.identifier(id);
    return platform.transaction(
        c -> {
          try {
            collection(c, p, id);
            return true;
          } catch (ApiFailure denied) {
            return false;
          }
        });
  }

  /** An explicit publisher grant is required to declare public data or change ACLs. */
  public JsonNode saveCollection(
      IdentityProvider.Principal p, String id, String key, String etag, JsonNode body) {
    CapabilityInput.require(config, p, "catalog:publish");
    ApiJson.identifier(id);
    CapabilityInput.fields(
        body,
        Set.of("name", "visibility", "allowedSubjects", "disabled"),
        Set.of("name", "visibility", "allowedSubjects", "disabled"));
    String name = CapabilityInput.text(body, "name", 200),
        visibility = CapabilityInput.text(body, "visibility", 20);
    if (!Set.of("PUBLIC", "PROJECT").contains(visibility)) throw ApiFailure.invalid();
    boolean disabled = CapabilityInput.bool(body, "disabled");
    JsonNode subjects = body.path("allowedSubjects");
    if (!subjects.isArray()
        || subjects.size() > 100
        || (visibility.equals("PUBLIC") && !subjects.isEmpty())) throw ApiFailure.invalid();
    var unique = new TreeSet<String>();
    for (JsonNode subject : subjects) {
      if (!subject.isTextual()) throw ApiFailure.invalid();
      ApiJson.identifier(subject.asText());
      if (!unique.add(subject.asText())) throw ApiFailure.invalid();
    }
    return repository.mutate(
        p,
        "knowledge-collection",
        id,
        "save",
        key,
        etag,
        body,
        (c, previous) -> {
          if (previous == null
              && repository.list(c, p.project(), "knowledge-collection").size() >= 200)
            throw new ApiFailure(409, "KNOWLEDGE_COLLECTION_LIMIT");
          if (previous != null
              && previous.path("visibility").asText().equals("PROJECT")
              && visibility.equals("PUBLIC"))
            throw new ApiFailure(422, "PROTECTED_SOURCE_CANNOT_BECOME_PUBLIC");
          ObjectNode next =
              Json.object()
                  .put("id", id)
                  .put("name", name)
                  .put("visibility", visibility)
                  .put("disabled", disabled);
          next.set("allowedSubjects", Json.tree(unique));
          next.put("retrieval", "LEXICAL_TEXT_V1");
          return next;
        });
  }

  public JsonNode documents(IdentityProvider.Principal p, String collection) {
    CapabilityInput.require(config, p, "catalog:read");
    ApiJson.identifier(collection);
    return platform.transaction(
        c -> {
          collection(c, p, collection);
          var out = Json.object();
          var items = out.putArray("items");
          for (var document : repository.list(c, p.project(), "knowledge-document"))
            if (document.path("collectionId").asText().equals(collection)) {
              if (document.path("revoked").asBoolean()) document.remove("text");
              items.add(document);
            }
          return out;
        });
  }

  public JsonNode saveDocument(
      IdentityProvider.Principal p,
      String collection,
      String id,
      String key,
      String etag,
      JsonNode body) {
    CapabilityInput.require(config, p, "catalog:write");
    ApiJson.identifier(collection);
    ApiJson.identifier(id);
    CapabilityInput.fields(
        body, Set.of("title", "text", "revoked"), Set.of("title", "text", "revoked"));
    String title = CapabilityInput.text(body, "title", 200),
        text = CapabilityInput.text(body, "text", 24000);
    boolean revoked = CapabilityInput.bool(body, "revoked");
    // Check in the same locked transaction before receipt replay too: old responses cannot
    // bypass revocation.
    return repository.mutate(
        p,
        "knowledge-document",
        collection + "~" + id,
        "save",
        key,
        etag,
        body,
        c -> {
          collection(c, p, collection);
          var previous =
              repository.get(c, p.project(), "knowledge-document", collection + "~" + id, false);
          if (previous != null && previous.path("revoked").asBoolean() && !revoked)
            throw new ApiFailure(409, "DOCUMENT_REVOCATION_PERMANENT");
        },
        (c, previous) -> {
          collection(c, p, collection);
          if (previous != null && previous.path("revoked").asBoolean() && !revoked)
            throw new ApiFailure(409, "DOCUMENT_REVOCATION_PERMANENT");
          if (previous == null
              && repository.list(c, p.project(), "knowledge-document").size() >= 500)
            throw new ApiFailure(409, "KNOWLEDGE_DOCUMENT_LIMIT");
          return Json.object()
              .put("id", id)
              .put("collectionId", collection)
              .put("title", title)
              .put("text", text)
              .put("revoked", revoked)
              .put("digest", "sha256:" + Json.hash(List.of(title, text)));
        });
  }

  public JsonNode documentVersion(
      IdentityProvider.Principal p, String collection, String id, long version) {
    CapabilityInput.require(config, p, "catalog:read");
    ApiJson.identifier(collection);
    ApiJson.identifier(id);
    return platform.transaction(
        c -> {
          collection(c, p, collection);
          var current =
              repository.get(c, p.project(), "knowledge-document", collection + "~" + id, true);
          if (current.path("revoked").asBoolean()) throw ApiFailure.hidden();
          return repository.version(
              c, p.project(), "knowledge-document", collection + "~" + id, version);
        });
  }

  public JsonNode document(IdentityProvider.Principal p, String collection, String id) {
    CapabilityInput.require(config, p, "catalog:read");
    ApiJson.identifier(collection);
    ApiJson.identifier(id);
    return platform.transaction(
        c -> {
          collection(c, p, collection);
          ObjectNode value =
              repository.get(c, p.project(), "knowledge-document", collection + "~" + id, true);
          if (value.path("revoked").asBoolean()) value.remove("text");
          return value;
        });
  }

  /** Fixed source references, never mutable text. Save these in the immutable preview/release. */
  public JsonNode snapshot(IdentityProvider.Principal p, List<String> collections) {
    CapabilityInput.require(config, p, "catalog:read");
    if (collections == null
        || collections.size() > 20
        || new HashSet<>(collections).size() != collections.size()) throw ApiFailure.invalid();
    collections.forEach(ApiJson::identifier);
    return platform.transaction(
        c -> {
          var refs = Json.MAPPER.createArrayNode();
          for (String id : collections) {
            var group = collection(c, p, id);
            for (var document : repository.list(c, p.project(), "knowledge-document")) {
              if (!document.path("collectionId").asText().equals(id)
                  || document.path("revoked").asBoolean()) continue;
              refs.add(
                  Json.object()
                      .put("projectId", p.project())
                      .put("collectionId", id)
                      .put("documentId", document.path("id").asText())
                      .put("version", document.path("revision").asLong())
                      .put("digest", document.path("digest").asText())
                      .put("visibility", group.path("visibility").asText()));
            }
          }
          return refs;
        });
  }

  /** Runtime passes a freshly resolved principal and only server-held release references. */
  public JsonNode retrieve(
      IdentityProvider.Principal p, JsonNode refs, String query, String runId) {
    member(p);
    if (query == null
        || query.isBlank()
        || query.length() > 8192
        || refs == null
        || !refs.isArray()
        || refs.size() > 500) throw ApiFailure.invalid();
    return platform.transaction(
        c -> {
          // Serialize source revocation with retrieval+receipt; no result is returned
          // before provenance commits.
          platform.lockProject(c, p.project());
          if (runId != null) {
            var owned = platform.owned(c, runId);
            if (!owned.project().equals(p.project())
                || !owned.application().equals(p.application())
                || !owned.subject().equals(p.subject())) throw ApiFailure.hidden();
          }
          record Candidate(JsonNode ref, ObjectNode document, int score) {}
          List<Candidate> candidates = new ArrayList<>();
          for (JsonNode ref : refs) {
            ObjectNode document = source(c, p, ref);
            int score =
                score(
                    query, document.path("title").asText() + " " + document.path("text").asText());
            if (score > 0) candidates.add(new Candidate(ref, document, score));
          }
          candidates.sort(
              Comparator.comparingInt(Candidate::score)
                  .reversed()
                  .thenComparing(
                      v ->
                          v.ref().path("collectionId").asText()
                              + "/"
                              + v.ref().path("documentId").asText()));
          ObjectNode out =
              Json.object().put("retrieval", "LEXICAL_TEXT_V1").put("untrustedSourceData", true);
          ArrayNode citations = out.putArray("citations");
          StringBuilder context = new StringBuilder();
          for (Candidate hit : candidates) {
            if (citations.size() >= 8 || context.length() >= 11000) break;
            String excerpt = hit.document().path("text").asText();
            excerpt =
                excerpt.substring(
                    0, Math.min(excerpt.length(), Math.min(2400, 12000 - context.length())));
            int number = citations.size() + 1;
            var citation = ((ObjectNode) hit.ref()).deepCopy();
            citation
                .put("number", number)
                .put("title", hit.document().path("title").asText())
                .put("excerpt", excerpt);
            citations.add(citation);
            context
                .append('[')
                .append(number)
                .append("] ")
                .append(hit.document().path("title").asText())
                .append('\n')
                .append(excerpt)
                .append("\n\n");
            if (runId != null) repository.source(c, runId, p.project(), hit.ref());
          }
          out.put("context", context.toString());
          out.put("truncated", candidates.size() > citations.size());
          return out;
        });
  }

  public JsonNode query(IdentityProvider.Principal p, List<String> collections, String query) {
    return retrieve(p, snapshot(p, collections), query, null);
  }

  /** Empty source sets are not proof that an arbitrary protected output is readable. */
  public boolean canReadOutput(IdentityProvider.Principal p, String runId) {
    try {
      member(p);
      return platform.transaction(
          c -> {
            try {
              if (!platform.owned(c, runId).project().equals(p.project())) return false;
              List<JsonNode> refs = repository.sources(c, runId, p.project());
              if (refs.isEmpty()) return false;
              for (JsonNode ref : refs) source(c, p, ref);
              return true;
            } catch (ApiFailure | IllegalArgumentException denied) {
              // An expected projection denial must not mark an enclosing read transaction
              // rollback-only.
              return false;
            }
          });
    } catch (ApiFailure | IllegalArgumentException ex) {
      return false;
    }
  }

  /** Guards all frozen knowledge inputs even when a query returned no citations. */
  public boolean canReadSources(IdentityProvider.Principal p, JsonNode refs) {
    try {
      member(p);
      if (refs == null || !refs.isArray() || refs.size() > 500) return false;
      return platform.transaction(
          c -> {
            try {
              for (JsonNode ref : refs) source(c, p, ref);
              return true;
            } catch (ApiFailure | IllegalArgumentException denied) {
              return false;
            }
          });
    } catch (ApiFailure | IllegalArgumentException ex) {
      return false;
    }
  }

  private void member(IdentityProvider.Principal p) {
    if (!config.project(p.project()).applications().contains(p.application()))
      throw ApiFailure.hidden();
    if (!p.permits("catalog:read")
        && !p.permits("runs:create")
        && !p.permits("runs:read")
        && !p.permits("tool:studio:knowledge")) throw ApiFailure.denied();
  }

  private ObjectNode collection(Connection c, IdentityProvider.Principal p, String id)
      throws SQLException {
    ObjectNode value = repository.get(c, p.project(), "knowledge-collection", id, true);
    if (!readable(p, value)) throw ApiFailure.hidden();
    return value;
  }

  private boolean readable(IdentityProvider.Principal p, JsonNode value) {
    if (value.path("disabled").asBoolean()) return false;
    if (value.path("visibility").asText().equals("PUBLIC")
        || value.path("allowedSubjects").isEmpty()) return true;
    for (JsonNode subject : value.path("allowedSubjects"))
      if (subject.asText().equals(p.subject())) return true;
    return false;
  }

  private ObjectNode source(Connection c, IdentityProvider.Principal p, JsonNode ref)
      throws SQLException {
    CapabilityInput.fields(
        ref,
        Set.of("projectId", "collectionId", "documentId", "version", "digest", "visibility"),
        Set.of("projectId", "collectionId", "documentId", "version", "digest", "visibility"));
    if (!ref.path("projectId").asText().equals(p.project())
        || !ref.path("version").isIntegralNumber()
        || !ref.path("version").canConvertToLong()
        || ref.path("version").asLong() < 1) throw ApiFailure.hidden();
    String group = CapabilityInput.text(ref, "collectionId", 128),
        id = CapabilityInput.text(ref, "documentId", 128);
    ApiJson.identifier(group);
    ApiJson.identifier(id);
    collection(c, p, group);
    String key = group + "~" + id;
    if (repository.get(c, p.project(), "knowledge-document", key, true).path("revoked").asBoolean())
      throw ApiFailure.hidden();
    ObjectNode value =
        repository.version(c, p.project(), "knowledge-document", key, ref.path("version").asLong());
    if (value.path("revoked").asBoolean() || !value.path("digest").equals(ref.path("digest")))
      throw ApiFailure.hidden();
    return value;
  }

  private static int score(String question, String text) {
    String q = question.toLowerCase(Locale.ROOT), haystack = text.toLowerCase(Locale.ROOT);
    Set<String> terms = new HashSet<>(List.of(q.split("[^\\p{L}\\p{N}]+")));
    int[] chars = q.codePoints().toArray();
    for (int i = 0; i + 1 < chars.length; i++)
      if (Character.UnicodeScript.of(chars[i]) == Character.UnicodeScript.HAN
          && Character.UnicodeScript.of(chars[i + 1]) == Character.UnicodeScript.HAN)
        terms.add(new String(chars, i, 2));
    int score = 0;
    for (String term : terms) if (!term.isBlank() && haystack.contains(term)) score++;
    return score;
  }
}
