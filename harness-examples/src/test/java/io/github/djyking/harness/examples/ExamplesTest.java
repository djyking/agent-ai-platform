package io.github.djyking.harness.examples;

import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExamplesTest {
  @TempDir Path directory;

  @Test
  void questionAnswerUsesAuthorizedRagAndPersistsItsCitations() throws Exception {
    var output = new ByteArrayOutputStream();
    RunState completed = command(output, "qa", directory.toString());
    assertEquals(RunStatus.COMPLETED, completed.status, completed.stopReason);
    assertEquals(2, completed.modelCalls);
    assertEquals(1, completed.toolCalls);
    assertEquals(325, completed.chargedTokens);
    assertTrue(completed.output.path("content").asText().contains("[1]"));
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("Private garden"));
    assertEquals(1, Json.read(output.toString(StandardCharsets.UTF_8)).path("citations").size());

    RunState reopened =
        command(new ByteArrayOutputStream(), "inspect", directory.toString(), completed.id);
    assertEquals(completed.output, reopened.output);
    assertEquals(completed.chargedTokens, reopened.chargedTokens);
    assertTrue(Files.exists(directory.resolve("harness.mv.db")));
  }

  @Test
  void separateCliInvocationsResumeHumanWaitAndRequireAnotherExactWriteApproval() throws Exception {
    RunState initial = command(new ByteArrayOutputStream(), "approval", directory.toString());
    assertEquals(RunStatus.WAITING_INPUT, initial.status);
    assertEquals(0, changes(initial.id));

    RunState inspected =
        command(new ByteArrayOutputStream(), "inspect", directory.toString(), initial.id);
    assertEquals(initial.approval.digest(), inspected.approval.digest());
    assertThrows(
        RunStore.Conflict.class,
        () ->
            command(
                new ByteArrayOutputStream(),
                "decide",
                directory.toString(),
                initial.id,
                "stale",
                "approve",
                "yes"));
    RunState waitingWrite =
        command(
            new ByteArrayOutputStream(),
            "decide",
            directory.toString(),
            initial.id,
            initial.approval.digest(),
            "approve",
            "yes");
    assertEquals(RunStatus.WAITING_APPROVAL, waitingWrite.status);
    assertEquals(0, changes(initial.id));
    assertNotEquals(initial.approval.digest(), waitingWrite.approval.digest());

    RunState completed =
        command(
            new ByteArrayOutputStream(),
            "decide",
            directory.toString(),
            initial.id,
            waitingWrite.approval.digest(),
            "approve",
            "approved synthetic demo");
    assertEquals(RunStatus.COMPLETED, completed.status, completed.stopReason);
    assertEquals(1, completed.toolCalls);
    assertEquals(initial.actor, completed.actor);
    assertEquals(initial.deadline, completed.deadline);
    assertEquals(1, changes(initial.id));
    command(new ByteArrayOutputStream(), "tick", directory.toString(), completed.id);
    assertEquals(1, changes(initial.id));
  }

  @Test
  void declinedHumanInputDoesNotReachTheWriteTool() throws Exception {
    RunState initial = command(new ByteArrayOutputStream(), "approval", directory.toString());
    RunState declined =
        command(
            new ByteArrayOutputStream(),
            "decide",
            directory.toString(),
            initial.id,
            initial.approval.digest(),
            "approve",
            "no");
    assertEquals(RunStatus.COMPLETED, declined.status);
    assertEquals("no", declined.output.path("input").asText());
    assertEquals(0, declined.toolCalls);
    assertEquals(0, changes(initial.id));
  }

  @Test
  void explicitAutomaticDemoCompletesOnlyTheSyntheticChange() throws Exception {
    RunState completed =
        command(new ByteArrayOutputStream(), "demo-approval", directory.toString());
    assertEquals(RunStatus.COMPLETED, completed.status, completed.stopReason);
    assertEquals("demo-feature", completed.output.path("resource").asText());
    assertEquals(1, changes(completed.id));
  }

  @Test
  void invalidCommandsDoNotInitializeADatabase() {
    Path unused = directory.resolve("unused");
    assertThrows(
        IllegalArgumentException.class,
        () -> command(new ByteArrayOutputStream(), "unknown", unused.toString()));
    assertFalse(Files.exists(unused));
    assertThrows(
        IllegalArgumentException.class,
        () -> command(new ByteArrayOutputStream(), "inspect", unused.toString(), "missing-run"));
    assertFalse(Files.exists(unused));
    assertThrows(
        IllegalArgumentException.class, () -> new DemoPlatform(directory.resolve("bad;path")));
    assertFalse(Files.exists(directory.resolve("bad;path")));
  }

  private RunState command(ByteArrayOutputStream output, String... args) throws Exception {
    try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      return ExamplesMain.execute(args, stream);
    }
  }

  private int changes(String runId) throws Exception {
    try (var demo = new DemoPlatform(directory)) {
      return demo.changes(runId);
    }
  }
}
