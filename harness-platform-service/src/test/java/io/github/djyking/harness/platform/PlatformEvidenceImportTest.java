package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformEvidenceImportTest {
  @Test
  void offlineImportBindsVerifiedReceiptWithoutExecutingTheToolOrLoggingItsContent(
      @TempDir Path temporary) throws Exception {
    String url =
        "jdbc:h2:file:"
            + temporary.resolve("evidence-db").toAbsolutePath().toString().replace('\\', '/')
            + ";MODE=MySQL;DB_CLOSE_DELAY=0";
    try (var f = new PlatformTestSupport(url)) {
      f.uncertainWrite.set(true);
      String id = f.id(f.create("write", "import-unknown01"));
      f.drive(id);
      f.approve(id, "import-approve01");
      var run = f.drive(id);
      assertEquals(RunStatus.NEEDS_ATTENTION, run.status);
      String receiptContent = "independently-verified-receipt-must-not-be-logged";
      var evidence =
          new PlatformRepository.Evidence(
              "offline-evidence-01",
              "project-a",
              id,
              run.pending.id,
              PlatformRepository.invocationDigest(run),
              new ToolResult(Json.object().put("value", 7), false, receiptContent),
              "independent-test-verifier",
              System.currentTimeMillis() - 1000);
      var wrong = Json.tree(evidence);
      ((com.fasterxml.jackson.databind.node.ObjectNode) wrong)
          .put("digest", "sha256:" + "0".repeat(64));
      Path invalid = temporary.resolve("mismatched.json");
      Files.writeString(invalid, Json.write(wrong));
      var rejected = invoke(temporary, url, invalid, "mismatch");
      assertEquals(1, rejected.exit());
      assertFalse(rejected.output().contains(receiptContent));
      assertFalse(rejected.errors().contains(receiptContent));
      PlatformTestSupport.error(
          409,
          "EVIDENCE_NOT_VERIFIED",
          () -> f.repository.transaction(c -> f.repository.evidence(c, evidence.id())));

      Path valid = temporary.resolve("verified.json");
      Files.writeString(valid, Json.write(evidence));
      var imported = invoke(temporary, url, valid, "valid");
      assertEquals(0, imported.exit(), imported.errors());
      assertTrue(imported.output().contains("\"evidenceRef\":\"offline-evidence-01\""));
      assertTrue(imported.output().contains("\"imported\":true"));
      assertFalse(imported.output().contains(receiptContent));
      assertFalse(imported.errors().contains(receiptContent));
      var stored = f.repository.transaction(c -> f.repository.evidence(c, evidence.id()));
      assertEquals(evidence, stored);
      assertEquals(run.revision, f.store.get(id).revision);
      assertEquals(InvocationPhase.UNKNOWN, f.store.get(id).pending.phase);
      assertEquals(1, f.writes.get());
    }
  }

  private static Result invoke(Path temporary, String jdbcUrl, Path receipt, String name)
      throws Exception {
    Path stdout = temporary.resolve(name + ".out"), stderr = temporary.resolve(name + ".err");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder command =
        new ProcessBuilder(
                java,
                "-cp",
                classpath,
                PlatformApplication.class.getName(),
                "import-evidence",
                receipt.toString())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile());
    command.environment().put("HARNESS_JDBC_URL", jdbcUrl);
    command.environment().put("HARNESS_JDBC_USER", "platform-test");
    command.environment().put("HARNESS_JDBC_PASSWORD", "explicitly-synthetic-test-password");
    Process child = command.start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Evidence import did not terminate");
      return new Result(child.exitValue(), Files.readString(stdout), Files.readString(stderr));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  private record Result(int exit, String output, String errors) {}
}
