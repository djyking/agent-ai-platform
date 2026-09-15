package io.github.djyking.harness.examples;

import static io.github.djyking.harness.core.Contracts.*;

import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.RunState;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Small restartable CLI for the two offline examples; no credentials or network services are
 * needed.
 */
public final class ExamplesMain {
  private ExamplesMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || "help".equals(args[0])) {
      usage(System.out);
      return;
    }
    execute(args, System.out);
  }

  public static RunState execute(String[] args, PrintStream output) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "A command and local data directory are required; use help");
    String command = args[0];
    int expected =
        switch (command) {
          case "qa", "approval", "demo-approval" -> 2;
          case "inspect", "tick", "resume" -> 3;
          case "decide" -> 6;
          default -> throw new IllegalArgumentException("Unknown command; use help");
        };
    if (args.length != expected)
      throw new IllegalArgumentException("Invalid argument count; use help");
    if ("decide".equals(command) && !Set.of("approve", "reject").contains(args[4]))
      throw new IllegalArgumentException("Decision must be approve or reject");
    Path directory = Path.of(args[1]);
    if (Set.of("inspect", "tick", "resume", "decide").contains(command)
        && !Files.isRegularFile(directory.resolve("harness.mv.db")))
      throw new IllegalArgumentException("No existing demo database in this directory");
    try (var demo = new DemoPlatform(directory)) {
      return execute(demo, command, args, output);
    }
  }

  private static RunState execute(
      DemoPlatform demo, String command, String[] args, PrintStream output) throws Exception {
    RunState state =
        switch (command) {
          case "qa" -> demo.drive(demo.startQuestionAnswer().id);
          case "approval" -> demo.drive(demo.startApprovalWorkflow().id);
          case "demo-approval" -> automaticSyntheticDemo(demo);
          case "inspect" -> demo.harness().get(args[2], DemoPlatform.ACTOR);
          case "tick" -> demo.drive(args[2]);
          case "resume" -> demo.drive(demo.harness().resume(args[2], DemoPlatform.ACTOR).id);
          case "decide" -> {
            // args[3] binds the decision to the exact pending request shown by inspect.
            demo.harness()
                .decide(args[2], DemoPlatform.ACTOR, args[3], "approve".equals(args[4]), args[5]);
            yield demo.drive(args[2]);
          }
          default -> throw new IllegalArgumentException("Unknown command");
        };
    var summary =
        Json.object()
            .put("runId", state.id)
            .put("status", state.status.name())
            .put("program", state.definition.program())
            .put("version", state.definition.version())
            .put("modelCalls", state.modelCalls)
            .put("toolCalls", state.toolCalls)
            .put("chargedTokens", state.chargedTokens)
            .put("simulatedChanges", demo.changes(state.id));
    if (state.stopReason != null) summary.put("reason", state.stopReason);
    if (state.output != null) summary.set("output", state.output);
    if (state.pending != null) {
      var pending =
          summary
              .putObject("pending")
              .put("kind", state.pending.kind)
              .put("invocationId", state.pending.id);
      if (state.pending.prompt != null) pending.put("prompt", state.pending.prompt);
      if (state.pending.arguments != null) pending.set("arguments", state.pending.arguments);
      if (state.approval != null)
        pending
            .put("approvalDigest", state.approval.digest())
            .put("expiresAt", state.approval.expiresAt().toString());
    }
    var citations = summary.putArray("citations");
    state.results.values().stream()
        .filter(result -> "TOOL".equals(result.kind()) && result.value().has("citations"))
        .forEach(result -> result.value().get("citations").forEach(citations::add));
    output.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    return state;
  }

  private static RunState automaticSyntheticDemo(DemoPlatform demo) {
    RunState state = demo.drive(demo.startApprovalWorkflow().id);
    for (int i = 0;
        i < 3
            && (state.status == RunStatus.WAITING_INPUT
                || state.status == RunStatus.WAITING_APPROVAL);
        i++) {
      String input =
          state.status == RunStatus.WAITING_INPUT
              ? "yes"
              : "Approved by the explicit synthetic demo driver";
      demo.harness().decide(state.id, DemoPlatform.ACTOR, state.approval.digest(), true, input);
      state = demo.drive(state.id);
    }
    return state;
  }

  public static void usage(PrintStream out) {
    out.println(
        """
Offline Harness examples (all data and writes stay in the selected local H2 directory):
  qa <data-directory>
  approval <data-directory>
  demo-approval <data-directory>  (automatically approves only this synthetic demo)
  inspect <data-directory> <runId>
  tick <data-directory> <runId>
  resume <data-directory> <runId>
  decide <data-directory> <runId> <approvalDigest> approve|reject <input-text>
The approval command stops for human input; after input=yes it stops again for write approval.
""");
  }
}
