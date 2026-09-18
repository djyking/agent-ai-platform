package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

final class KnowledgeServiceTest {
  private IdentityProvider.Principal admin() {
    return new IdentityProvider.Principal("app-a", "project-a", "alice", Set.of("*"));
  }

  private JsonNode collection(String visibility, String... subjects) {
    var out =
        Json.object()
            .put("name", "Research sources")
            .put("visibility", visibility)
            .put("disabled", false);
    out.set("allowedSubjects", Json.tree(subjects));
    return out;
  }

  private JsonNode document(String text, boolean revoked) {
    return Json.object().put("title", "Shipping policy").put("text", text).put("revoked", revoked);
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  @Test
  void immutableVersionsAndRestartKeepFrozenResearchSources() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PUBLIC"));
      k.saveDocument(
          p,
          "faq",
          "shipping",
          key(),
          "\"k0\"",
          document("Shipping takes two days. 发货需要两个工作日。", false));
      JsonNode refs = k.snapshot(p, List.of("faq"));
      k.saveDocument(
          p, "faq", "shipping", key(), "\"k1\"", document("Shipping takes five days.", false));
      var reopened = new KnowledgeService(f.config, f.repository);
      assertTrue(
          reopened
              .retrieve(p, refs, "Shipping", null)
              .path("context")
              .asText()
              .contains("two days"));
      assertTrue(
          reopened
              .query(p, List.of("faq"), "Shipping")
              .path("context")
              .asText()
              .contains("five days"));
      assertEquals(1, refs.get(0).path("version").asLong());
      assertEquals("PUBLIC", refs.get(0).path("visibility").asText());
      assertEquals(1, reopened.retrieve(p, refs, "什么时候发货？", null).path("citations").size());
    }
  }

  @Test
  void sourceAclRevocationHidesExistingModelAnswerAcrossApplications() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PROJECT", "alice", "bob"));
      k.saveDocument(p, "faq", "shipping", key(), "\"k0\"", document("Shipping two days", false));
      var refs = k.snapshot(p, List.of("faq"));
      String run = f.id(f.create("read", key()));
      assertFalse(k.canReadOutput(p, run));
      k.retrieve(p, refs, "Shipping", run);
      var otherApp =
          new IdentityProvider.Principal("app-b", "project-a", "bob", Set.of("runs:read"));
      assertTrue(k.canReadOutput(otherApp, run));
      k.saveCollection(p, "faq", key(), "\"k1\"", collection("PROJECT", "alice"));
      assertFalse(k.canReadOutput(otherApp, run));
      assertFalse(k.canReadSources(otherApp, refs));
      assertThrows(ApiFailure.class, () -> k.retrieve(otherApp, refs, "Shipping", null));
      assertTrue(k.canReadOutput(p, run));
      k.saveDocument(p, "faq", "shipping", key(), "\"k1\"", document("Shipping two days", true));
      assertFalse(k.canReadOutput(p, run));
      assertFalse(k.canReadSources(p, refs));
      assertFalse(f.repository.<Boolean>transaction(c -> k.canReadOutput(p, run)));
      assertFalse(f.repository.<Boolean>transaction(c -> k.canReadSources(p, refs)));
      assertThrows(ApiFailure.class, () -> k.documentVersion(p, "faq", "shipping", 1));
    }
  }

  @Test
  void ownershipProjectIsolationAndForgedSourceDigestsFailClosed() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PUBLIC"));
      k.saveDocument(p, "faq", "shipping", key(), "\"k0\"", document("Shipping two days", false));
      var refs = k.snapshot(p, List.of("faq"));
      var elsewhere = new IdentityProvider.Principal("app-a", "project-b", "alice", Set.of("*"));
      assertFalse(k.canReadSources(elsewhere, refs));
      String run = f.id(f.create("read", key()));
      var bob = new IdentityProvider.Principal("app-a", "project-a", "bob", Set.of("*"));
      assertThrows(ApiFailure.class, () -> k.retrieve(bob, refs, "Shipping", run));
      ((com.fasterxml.jackson.databind.node.ObjectNode) refs.get(0)).put("digest", "sha256:forged");
      assertFalse(k.canReadSources(p, refs));
      assertThrows(ApiFailure.class, () -> k.retrieve(p, refs, "Shipping", run));
      assertFalse(k.canReadOutput(p, run));
    }
  }

  @Test
  void collectionPolicyCannotBeDowngradedAndDocumentRevocationIsPermanent() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      var writer =
          new IdentityProvider.Principal("app-a", "project-a", "alice", Set.of("catalog:write"));
      assertEquals(
          403,
          assertThrows(
                  ApiFailure.class,
                  () -> k.saveCollection(writer, "faq", key(), "\"k0\"", collection("PUBLIC")))
              .status);
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PROJECT"));
      assertEquals(
          "PROTECTED_SOURCE_CANNOT_BECOME_PUBLIC",
          assertThrows(
                  ApiFailure.class,
                  () -> k.saveCollection(p, "faq", key(), "\"k1\"", collection("PUBLIC")))
              .code);
      k.saveDocument(
          writer, "faq", "shipping", key(), "\"k0\"", document("Shipping two days", true));
      assertEquals(
          "DOCUMENT_REVOCATION_PERMANENT",
          assertThrows(
                  ApiFailure.class,
                  () ->
                      k.saveDocument(
                          writer,
                          "faq",
                          "shipping",
                          key(),
                          "\"k1\"",
                          document("Shipping two days", false)))
              .code);
    }
  }

  @Test
  void casIdempotencyAndReceiptsCannotBypassCurrentAcl() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PROJECT", "alice"));
      String same = key();
      JsonNode body = document("Shipping two days", false);
      JsonNode first = k.saveDocument(p, "faq", "shipping", same, "\"k0\"", body);
      assertEquals(
          ApiJson.canonical(first),
          ApiJson.canonical(k.saveDocument(p, "faq", "shipping", same, "\"k0\"", body)));
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () ->
                      k.saveDocument(
                          p, "faq", "shipping", same, "\"k0\"", document("Different", false)))
              .status);
      assertEquals(
          412,
          assertThrows(
                  ApiFailure.class,
                  () -> k.saveDocument(p, "faq", "shipping", key(), "\"k0\"", body))
              .status);
      k.saveCollection(p, "faq", key(), "\"k1\"", collection("PROJECT", "bob"));
      assertThrows(
          ApiFailure.class, () -> k.saveDocument(p, "faq", "shipping", same, "\"k0\"", body));
      JsonNode receipt = k.command(p, same);
      assertEquals("COMPLETED", receipt.path("status").asText());
      assertEquals("knowledge-document", receipt.path("kind").asText());
      assertEquals("faq~shipping", receipt.path("id").asText());
      assertFalse(receipt.toString().contains("Shipping two days"));
      assertEquals(
          "NOT_FOUND",
          k.command(
                  new IdentityProvider.Principal(
                      "app-b", "project-a", "alice", Set.of("catalog:read")),
                  same)
              .path("status")
              .asText());
      assertEquals(
          409,
          assertThrows(
                  ApiFailure.class,
                  () -> k.saveCollection(p, "other", same, "\"k0\"", collection("PUBLIC")))
              .status);
    }
  }

  @Test
  void revokedDocumentsDoNotLeakThroughOldReceiptsOrDocumentLists() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PUBLIC"));
      String write = key();
      JsonNode body = document("Shipping two days", false);
      k.saveDocument(p, "faq", "shipping", write, "\"k0\"", body);
      k.saveDocument(p, "faq", "shipping", key(), "\"k1\"", document("Shipping two days", true));
      assertThrows(
          ApiFailure.class, () -> k.saveDocument(p, "faq", "shipping", write, "\"k0\"", body));
      assertFalse(k.documents(p, "faq").path("items").get(0).has("text"));
      assertEquals("COMPLETED", k.command(p, write).path("status").asText());
    }
  }

  @Test
  void noLexicalHitDoesNotInventCitationsOrProvenance() {
    try (var f = new PlatformTestSupport()) {
      var k = new KnowledgeService(f.config, f.repository);
      var p = admin();
      k.saveCollection(p, "faq", key(), "\"k0\"", collection("PUBLIC"));
      k.saveDocument(p, "faq", "shipping", key(), "\"k0\"", document("Shipping two days", false));
      var result = k.query(p, List.of("faq"), "unrelated");
      assertTrue(result.path("citations").isEmpty());
      assertEquals("", result.path("context").asText());
      assertTrue(result.path("untrustedSourceData").asBoolean());
      assertThrows(
          ApiFailure.class,
          () ->
              k.saveDocument(
                  p,
                  "faq",
                  "other",
                  key(),
                  "\"k0\"",
                  Json.object().put("title", "bad").put("text", "data").put("revoked", "false")));
    }
  }
}
