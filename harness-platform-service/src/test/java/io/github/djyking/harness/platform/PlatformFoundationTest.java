package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformFoundationTest {
  @Test
  void releaseReferencesRemainStableAcrossIndependentJavaProcesses(@TempDir Path temporary)
      throws Exception {
    try (var f = new PlatformTestSupport()) {
      Path manifest = temporary.resolve("deployment.json");
      Files.writeString(manifest, Json.write(f.config));
      var expected = Json.MAPPER.createArrayNode();
      for (Deployment.Release release : f.config.releases()) {
        var row = Json.object().put("projectId", release.projectId());
        row.set("releaseRef", release.reference());
        expected.add(row);
        for (Set<String> values :
            List.of(release.toolKeys(), release.executionPermissions(), release.approvers()))
          assertEquals(new ArrayList<>(new TreeSet<>(values)), new ArrayList<>(values));
      }
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      for (int i = 0; i < 4; i++) {
        Path output = temporary.resolve("references-" + i + ".json");
        Path errors = temporary.resolve("references-" + i + ".err");
        Process child =
            new ProcessBuilder(
                    java,
                    "-cp",
                    classpath,
                    PlatformApplication.class.getName(),
                    "releases",
                    manifest.toString())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
        try {
          assertTrue(
              child.waitFor(15, TimeUnit.SECONDS), "Release reference CLI did not terminate");
          assertEquals(0, child.exitValue(), Files.readString(errors));
          assertEquals(
              expected,
              Json.read(Files.readString(output)),
              "A release reference must be identical after a fresh JVM startup");
        } finally {
          if (child.isAlive()) child.destroyForcibly();
        }
      }
    }
  }

  @Test
  void parserRejectsAmbiguousOversizedDeepAndUnknownRequestData() {
    for (String invalid :
        List.of(
            "{\"inputs\":{},\"inputs\":{}}",
            "{} {}",
            "[]",
            "null",
            "{\"nested\":".repeat(17) + "{}" + "}".repeat(17)))
      error(400, "INVALID_REQUEST", () -> parse(invalid));
    error(413, "PAYLOAD_TOO_LARGE", () -> parse("{\"text\":\"" + "x".repeat(65536) + "\"}"));
    error(
        422,
        "INPUT_INVALID",
        () -> ApiJson.validate("ControlRequest", Json.object().put("actor", "admin")));
    assertEquals(Json.object(), parse("{}"));
    error(400, "INVALID_REQUEST", () -> ApiJson.identifier("project:invalid"));
  }

  @Test
  void releaseSchemasAreDetachedAndAllSchemaPositionsRejectRemoteReferences() {
    try (var f = new PlatformTestSupport()) {
      Deployment.Release source = f.releases.get("project-a/read");
      ObjectNode input = valueSchema(),
          output = valueSchema(),
          human = Json.object().put("type", "string");
      Deployment.Release detached = copy(source, input, output, human);
      String digest = detached.digest();
      input.put("description", "later mutation");
      output.put("description", "later mutation");
      human.put("maxLength", 2);
      assertEquals(digest, detached.digest());
      JsonNode remote = Json.object().put("$ref", "https://untrusted.invalid/schema.json");
      assertThrows(
          IllegalArgumentException.class,
          () -> copy(source, remote, source.outputSchema(), source.humanInputSchema()));
      assertThrows(
          IllegalArgumentException.class,
          () -> copy(source, source.inputSchema(), remote, source.humanInputSchema()));
      assertThrows(
          IllegalArgumentException.class,
          () -> copy(source, source.inputSchema(), source.outputSchema(), remote));
    }
  }

  @Test
  void sharedSlotsBoundTwoRepositoryInstancesAndStaleOwnersCannotReleaseReplacementSlots()
      throws Exception {
    try (var f = new PlatformTestSupport()) {
      var second = new PlatformRepository(new JdbcRunStore(f.dataSource));
      ExecutorService workers = Executors.newFixedThreadPool(6);
      CountDownLatch start = new CountDownLatch(1);
      try {
        List<Future<Boolean>> acquisitions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
          final int index = i;
          acquisitions.add(
              workers.submit(
                  () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return (index % 2 == 0 ? f.repository : second)
                        .acquireSlots("project-a", "attempt-" + index, 60000, 4, 2);
                  }));
        }
        start.countDown();
        int accepted = 0;
        for (Future<Boolean> acquisition : acquisitions)
          if (acquisition.get(10, TimeUnit.SECONDS)) accepted++;
        assertEquals(2, accepted);
        assertTrue(second.acquireSlots("project-b", "other-project-1", 60000, 4, 2));
        assertTrue(second.acquireSlots("project-b", "other-project-2", 60000, 4, 2));
        assertFalse(f.repository.acquireSlots("project-b", "over-global", 60000, 4, 2));
        f.repository.transaction(
            c -> {
              PlatformRepository.execute(
                  c, "UPDATE platform_slots SET owner_id=NULL,lease_until=NULL");
              return null;
            });
        assertTrue(f.repository.acquireSlots("project-a", "expired-owner", 60000, 1, 1));
        f.repository.transaction(
            c -> {
              PlatformRepository.execute(
                  c, "UPDATE platform_slots SET lease_until=0 WHERE owner_id='expired-owner'");
              return null;
            });
        assertTrue(second.acquireSlots("project-a", "replacement-owner", 60000, 1, 1));
        f.repository.releaseSlots("expired-owner");
        assertFalse(f.repository.acquireSlots("project-a", "third-owner", 60000, 1, 1));
        second.releaseSlots("replacement-owner");
        assertTrue(f.repository.acquireSlots("project-a", "third-owner", 60000, 1, 1));
      } finally {
        start.countDown();
        workers.shutdownNow();
      }
    }
  }

  @Test
  void unavailableProjectSlotDoesNotReserveAGlobalSlot() {
    try (var f = new PlatformTestSupport()) {
      assertTrue(f.repository.acquireSlots("project-a", "local-full", 60000, 2, 1));
      assertFalse(f.repository.acquireSlots("project-a", "rejected-local", 60000, 2, 1));
      assertTrue(f.repository.acquireSlots("project-b", "global-still-free", 60000, 2, 1));
    }
  }

  @Test
  void startupRejectsAnIncompatibleSchemaBeforePerformingDdl() {
    try (var f = new PlatformTestSupport()) {
      f.repository.transaction(
          c -> {
            PlatformRepository.execute(c, "DROP TABLE platform_evidence");
            PlatformRepository.execute(
                c, "UPDATE harness_schema SET schema_version=99 WHERE component='platform'");
            return null;
          });
      assertThrows(IllegalStateException.class, () -> f.repository.initialize(f.config));
      boolean exists =
          f.repository.transaction(
              c -> {
                try (ResultSet r =
                    c.getMetaData()
                        .getTables(null, null, "PLATFORM_EVIDENCE", new String[] {"TABLE"})) {
                  return r.next();
                }
              });
      assertFalse(exists, "Version rejection must precede schema mutation");
    }
  }

  @Test
  void signedTokensRejectTamperingAndCanonicalizationIgnoresObjectKeyOrderOnly() {
    SignedTokens tokens = new SignedTokens("explicit-test-secret-with-at-least-32-bytes");
    JsonNode original = Json.read("{\"b\":2,\"a\":{\"y\":1,\"x\":0}}");
    String signed = tokens.sign(original);
    assertEquals(original, tokens.verify(signed, "CURSOR_INVALID"));
    String[] parts = signed.split("\\.");
    String changed =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"admin\":true}".getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[1];
    error(400, "CURSOR_INVALID", () -> tokens.verify(changed, "CURSOR_INVALID"));
    error(412, "PRECONDITION_FAILED", () -> tokens.verify(changed, "PRECONDITION_FAILED"));
    assertEquals(
        ApiJson.canonical(original),
        ApiJson.canonical(Json.read("{\"a\":{\"x\":0,\"y\":1},\"b\":2}")));
    assertNotEquals(ApiJson.canonical(Json.read("[1,2]")), ApiJson.canonical(Json.read("[2,1]")));
  }

  private static JsonNode parse(String json) {
    return ApiJson.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static Deployment.Release copy(
      Deployment.Release s, JsonNode input, JsonNode output, JsonNode human) {
    return new Deployment.Release(
        s.projectId(),
        s.agentId(),
        s.releaseId(),
        s.workflow(),
        s.model(),
        input,
        output,
        s.toolKeys(),
        s.executionPermissions(),
        s.approvers(),
        s.reviewFields(),
        human,
        s.publicOutput(),
        s.limits());
  }
}
