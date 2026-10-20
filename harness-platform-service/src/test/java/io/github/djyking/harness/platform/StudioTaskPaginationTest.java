package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class StudioTaskPaginationTest {
  private ObjectNode seed(StudioLifecycleSecurityTest.Fixture f) {
    JsonNode saved = f.save("assistant", f.draft(), "\"s0\"", "save-pagination-seed");
    var started =
        f.runtime.studio.run(
            f.alice(),
            "app-a-credential",
            "alice-token",
            "assistant",
            "preview",
            "pagination-seed-start",
            StudioService.etag(saved),
            Json.object().set("input", Json.object().put("question", "pagination fixture")));
    String id = started.path("lastTask").path("id").asText();
    return f.runtime.repository.transaction(
        c -> new StudioRepository(f.runtime.repository).get(c, "project-a", "TASK", id, true));
  }

  private ObjectNode copy(ObjectNode seed, int id, String app, String actor) {
    return seed.deepCopy()
        .put("id", String.format("00000000-0000-0000-0000-%012d", id))
        .put("applicationId", app)
        .put("owner", actor)
        .put("createdAt", "2026-01-01T00:00:00Z");
  }

  @Test
  void moreThanTwoThousandTasksAreFullyReachableWithStableTiesAndScopedSqlFiltering()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      ObjectNode original = seed(f);
      var documents = new StudioRepository(f.runtime.repository);
      f.runtime.repository.transaction(
          c -> {
            f.runtime.repository.lockProject(c, "project-a");
            for (int i = 1; i <= 2105; i++) {
              ObjectNode task = copy(original, i, "assistant", "app-a/alice");
              documents.put(c, "project-a", "TASK", task.path("id").asText(), task);
            }
            for (int i = 3000; i <= 5105; i++) {
              ObjectNode task = copy(original, i, "other-app", "app-a/bob");
              documents.put(c, "project-a", "TASK", task.path("id").asText(), task);
            }
            ObjectNode otherApp = copy(original, 6000, "other-app", "app-a/alice");
            documents.put(c, "project-a", "TASK", otherApp.path("id").asText(), otherApp);
            return null;
          });
      JsonNode first = f.runtime.studio.tasks(f.alice(), "assistant", null, 100);
      assertEquals(100, first.path("items").size());
      assertTrue(first.path("hasMore").asBoolean());
      assertTrue(first.path("nextCursor").isTextual());
      assertEquals(50, f.runtime.studio.tasks(f.alice(), "assistant").path("items").size());

      // Another committed writer inserts both an earlier tie and a newer task between requests.
      // Neither may leak into the original snapshot, even if its UUID is below the last cursor.
      ExecutorService writer = Executors.newSingleThreadExecutor();
      try {
        writer
            .submit(
                () ->
                    f.runtime.repository.transaction(
                        c -> {
                          f.runtime.repository.lockProject(c, "project-a");
                          var tied = copy(original, 0, "assistant", "app-a/alice");
                          documents.put(c, "project-a", "TASK", tied.path("id").asText(), tied);
                          var latest =
                              copy(original, 9000, "assistant", "app-a/alice")
                                  .put("createdAt", Instant.now().plusSeconds(1).toString());
                          documents.put(c, "project-a", "TASK", latest.path("id").asText(), latest);
                          return null;
                        }))
            .get(10, TimeUnit.SECONDS);
      } finally {
        writer.shutdownNow();
      }
      Set<String> seen = new HashSet<>();
      String cursor = null, priorId = null;
      JsonNode page = first;
      int pages = 0;
      do {
        if (cursor != null) page = f.runtime.studio.tasks(f.alice(), "assistant", cursor, 100);
        assertTrue(page.path("items").size() <= 100);
        for (JsonNode task : page.path("items")) {
          String id = task.path("id").asText();
          assertTrue(seen.add(id), "No duplicates on equal creation times");
          assertEquals("assistant", task.path("applicationId").asText());
          if (!id.equals(original.path("id").asText())) {
            if (priorId != null) assertTrue(priorId.compareTo(id) > 0);
            priorId = id;
          }
        }
        cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        pages++;
      } while (cursor != null);
      assertEquals(22, pages);
      assertEquals(2106, seen.size());
      assertFalse(seen.contains("00000000-0000-0000-0000-000000000000"));
      assertFalse(seen.contains("00000000-0000-0000-0000-000000009000"));
      assertFalse(page.path("hasMore").asBoolean());
      assertEquals(
          first,
          f.runtime.studio.tasks(f.alice(), "assistant", first.path("pageCursor").asText(), 100),
          "Previous-page cursor retains the original watermark");
      assertEquals(
          "00000000-0000-0000-0000-000000009000",
          f.runtime
              .studio
              .tasks(f.alice(), "assistant", null, 1)
              .path("items")
              .get(0)
              .path("id")
              .asText());
      assertEquals(
          1, f.runtime.studio.tasks(f.alice(), "other-app", null, 100).path("items").size());

      f.runtime.repository.transaction(
          c -> {
            var rows =
                StudioTaskIndex.page(
                    c, f.bob(), "other-app", Long.MAX_VALUE, Long.MAX_VALUE, "~", 101);
            assertEquals(
                101, rows.size(), "Other owners use the indexed candidate query independently");
            assertTrue(
                rows.stream()
                    .allMatch(row -> row.task().path("owner").asText().equals("app-a/bob")));
            return null;
          });
    }
  }

  @Test
  void signedCursorRejectsCrossActorProjectAppPermissionTamperingAndExpiredTokens()
      throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      seed(f);
      JsonNode first = f.runtime.studio.tasks(f.alice(), "assistant", null, 1);
      String cursor = first.path("pageCursor").asText();
      var p = f.alice();
      List<IdentityProvider.Principal> otherScopes =
          List.of(
              f.bob(),
              new IdentityProvider.Principal(
                  p.application(), "other-project", p.subject(), p.permissions()),
              new IdentityProvider.Principal(
                  "other-client", p.project(), p.subject(), p.permissions()),
              new IdentityProvider.Principal(
                  p.application(), p.project(), p.subject(), Set.of("runs:read")));
      for (var other : otherScopes)
        assertEquals(
            "CURSOR_INVALID",
            assertThrows(
                    ApiFailure.class, () -> f.runtime.studio.tasks(other, "assistant", cursor, 1))
                .code);
      assertEquals(
          "CURSOR_INVALID",
          assertThrows(ApiFailure.class, () -> f.runtime.studio.tasks(p, "other-app", cursor, 1))
              .code);
      assertEquals(
          "CURSOR_INVALID",
          assertThrows(
                  ApiFailure.class, () -> f.runtime.studio.tasks(p, "assistant", cursor + "x", 1))
              .code);
      var tokens = new SignedTokens("synthetic-local-fixture-signing-and-model-secret-0123456789");
      ObjectNode expired = (ObjectNode) tokens.verify(cursor, "CURSOR_INVALID");
      expired.put("expires", System.currentTimeMillis() - 1);
      assertEquals(
          410,
          assertThrows(
                  ApiFailure.class,
                  () -> f.runtime.studio.tasks(p, "assistant", tokens.sign(expired), 1))
              .status);
      for (int limit : new int[] {0, -1, 101, Integer.MAX_VALUE})
        assertEquals(
            400,
            assertThrows(ApiFailure.class, () -> f.runtime.studio.tasks(p, null, null, limit))
                .status);
      for (String app : List.of("", "has space", "../other", "x".repeat(129)))
        assertEquals(
            400,
            assertThrows(ApiFailure.class, () -> f.runtime.studio.tasks(p, app, null, 1)).status);
    }
  }

  @Test
  void indexMigratesVersionOneDocumentsAndRuntimeRejectsMissingIndexWithoutDdl() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      ObjectNode task = seed(f);
      String before = Json.write(task);
      f.runtime.repository.transaction(
          c -> {
            execute(c, "DROP TABLE studio_task_index");
            execute(c, "UPDATE harness_schema SET schema_version=1 WHERE component='studio'");
            return null;
          });
      var readonly = new StudioRepository(new PlatformRepository(f.runtime.store, false));
      assertThrows(IllegalStateException.class, readonly::initialize);
      new StudioRepository(f.runtime.repository).initialize();
      readonly.initialize();
      f.runtime.repository.transaction(
          c -> {
            assertEquals(
                before,
                Json.write(
                    new StudioRepository(f.runtime.repository)
                        .get(c, "project-a", "TASK", task.path("id").asText(), true)));
            try (var query =
                    prepare(
                        c, "SELECT schema_version FROM harness_schema WHERE component='studio'");
                var rows = query.executeQuery()) {
              assertTrue(rows.next());
              assertEquals(2, rows.getInt(1));
            }
            execute(c, "DROP INDEX studio_task_app_idx");
            return null;
          });
      assertThrows(IllegalStateException.class, readonly::initialize);
      new StudioRepository(f.runtime.repository).initialize();
      assertEquals(1, f.runtime.studio.tasks(f.alice(), null).path("items").size());
    }
  }

  @Test
  void taskAndIndexRollbackTogetherAndArchivingDoesNotChangePagination() throws Exception {
    try (var f = new StudioLifecycleSecurityTest.Fixture()) {
      ObjectNode seed = seed(f);
      var documents = new StudioRepository(f.runtime.repository);
      var inserted = copy(seed, 33, "assistant", "app-a/alice");
      assertThrows(
          IllegalStateException.class,
          () ->
              f.runtime.repository.transaction(
                  c -> {
                    documents.put(c, "project-a", "TASK", inserted.path("id").asText(), inserted);
                    throw new IllegalStateException("rollback fixture");
                  }));
      JsonNode page = f.runtime.studio.tasks(f.alice(), null);
      assertEquals(1, page.path("items").size());
      f.runtime.repository.transaction(
          c -> {
            ObjectNode archived = seed.deepCopy().put("payloadArchived", true);
            archived.remove("input");
            documents.put(c, "project-a", "TASK", seed.path("id").asText(), archived);
            return null;
          });
      var archived =
          f.runtime
              .studio
              .tasks(f.alice(), null, page.path("pageCursor").asText(), 50)
              .path("items")
              .get(0);
      assertTrue(archived.path("payloadArchived").asBoolean());
      assertFalse(archived.has("input"));
      assertEquals(seed.path("id"), archived.path("id"));
      assertThrows(
          IllegalStateException.class,
          () ->
              f.runtime.repository.transaction(
                  c -> {
                    documents.put(
                        c,
                        "project-a",
                        "TASK",
                        seed.path("id").asText(),
                        seed.deepCopy().put("owner", "app-a/bob"));
                    return null;
                  }));
    }
  }
}
