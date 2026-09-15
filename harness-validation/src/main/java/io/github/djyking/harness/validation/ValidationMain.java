package io.github.djyking.harness.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.mcp.*;
import io.github.djyking.harness.adapters.model.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit live acceptance commands. Ordinary Maven tests never invoke these commands. */
public final class ValidationMain {
  private ValidationMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !Set.of("preflight", "model", "mcp-read", "mysql").contains(args[0]))
      throw new IllegalArgumentException("Usage: preflight|model|mcp-read|mysql <new-report.json>");
    Path path = Path.of(args[1]).toAbsolutePath().normalize();
    if (Files.exists(path))
      throw new IllegalArgumentException("Report already exists; choose a new path");
    if (path.getParent() != null) Files.createDirectories(path.getParent());
    LiveSettings settings = new LiveSettings(System.getenv());
    ObjectNode report =
        Json.object()
            .put("schemaVersion", 1)
            .put("command", args[0])
            .put("at", Instant.now().toString());
    boolean passed = false;
    try {
      ObjectNode details =
          switch (args[0]) {
            case "preflight" -> preflight(settings);
            case "model" -> model(settings);
            case "mcp-read" -> mcp(settings);
            case "mysql" -> MySqlAcceptance.run(settings);
            default -> throw new IllegalStateException();
          };
      report.set("details", details);
      report.put("status", "preflight".equals(args[0]) ? "INSPECTED" : "PASSED");
      passed = true;
    } catch (Exception failure) {
      // Never serialize remote exception messages, JDBC URLs or credential values.
      report.put("status", "FAILED").put("failureType", failure.getClass().getSimpleName());
      if (failure instanceof InvocationException invocation
          && invocation.getMessage().matches("[A-Z0-9_]{1,100}"))
        report.put("failureCode", invocation.getMessage());
    }
    Files.writeString(
        path,
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardOpenOption.CREATE_NEW);
    System.out.println(
        "Acceptance " + args[0] + ": " + report.path("status").asText() + "; report=" + path);
    if (!passed)
      throw new IllegalStateException(
          "Acceptance failed; see sanitized report and preflight settings");
  }

  static ObjectNode preflight(LiveSettings settings) {
    ObjectNode result = Json.object().put("networkCalls", 0).put("containsSecretValues", false);
    settings
        .missing()
        .forEach(
            (name, missing) ->
                result.set(
                    name,
                    Json.object()
                        .put(
                            "status",
                            missing.isEmpty() ? "CONFIGURED_NOT_VERIFIED" : "MISSING_CONFIGURATION")
                        .set("missingKeys", Json.tree(missing))));
    return result;
  }

  private static ObjectNode model(LiveSettings settings) {
    // A required cap prevents an accidentally configured credential from enabling an unbounded
    // test.
    settings.required("HARNESS_MODEL_CALL_LIMIT");
    int calls = settings.integer("HARNESS_MODEL_CALL_LIMIT", 2, 2, 2);
    int output = settings.integer("HARNESS_MODEL_MAX_OUTPUT_TOKENS", 128, 32, 512);
    var config =
        new DeepSeekConfig(
            settings.endpoint("HARNESS_MODEL_ENDPOINT"),
            Duration.ofSeconds(5),
            Duration.ofSeconds(45),
            262144,
            e -> settings.bearer("DEEPSEEK_API_KEY"));
    settings.bearer("DEEPSEEK_API_KEY");
    ToolRegistry tools = new ToolRegistry();
    AtomicInteger echoes = new AtomicInteger();
    ToolDescriptor echo =
        new ToolDescriptor(
            "validation/echo",
            "validation_echo",
            "Return the supplied synthetic marker",
            "internal",
            "echo",
            "1",
            Json.read(
                "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"const\":\"HARNESS_OK\"}},\"additionalProperties\":false}"),
            new ToolPolicy(true, false, false, 1, 1000, Set.of()));
    tools.register(
        echo,
        (t, a, c) -> {
          echoes.incrementAndGet();
          return ToolResult.success(a);
        });
    ModelProfile profile =
        new ModelProfile(
            "live-acceptance",
            "deepseek",
            settings.required("HARNESS_MODEL_NAME"),
            32000,
            output,
            45000,
            Json.object());
    ProgramDefinition definition =
        new ProgramDefinition(
            "live-native-tools",
            "1",
            "agent",
            Json.tree(
                new AgentProgram.AgentSpec(
                    profile,
                    List.of(
                        Message.text(
                            "user",
                            "Call validation_echo once with value HARNESS_OK, then reply with the"
                                + " returned marker. This is synthetic protocol validation.")),
                    2)));
    var model = new DeepSeekChatModel(config);
    var usage = new java.util.concurrent.CopyOnWriteArrayList<Usage>();
    ModelGateway measured =
        (request, context) -> {
          ModelResponse response = model.invoke(request, context);
          usage.add(response.usage());
          return response;
        };
    try (Harness h = runtime(measured, tools)) {
      RunState run =
          h.start(
              definition,
              actor(),
              List.of(echo.key()),
              new Budget(12000, calls, 1, 30),
              Duration.ofMinutes(2),
              UUID.randomUUID().toString());
      RunState done = drive(h, run.id);
      require(
          done.status == RunStatus.COMPLETED
              && echoes.get() == 1
              && done.modelCalls == 2
              && done.output.path("content").asText().contains("HARNESS_OK"),
          "NATIVE_TOOL_ROUND_TRIP_FAILED");
      boolean usageKnown = usage.size() == done.modelCalls && usage.stream().allMatch(Usage::known);
      long reportedTokens = usage.stream().filter(Usage::known).mapToLong(Usage::total).sum();
      require(
          !usageKnown || reportedTokens == done.chargedTokens, "MODEL_USAGE_SETTLEMENT_MISMATCH");
      return summary(done)
          .put("provider", profile.provider())
          .put("modelName", profile.model())
          .put("usageKnown", usageKnown)
          .put("reportedKnownTokens", reportedTokens)
          .put("syntheticInput", true)
          .put("outputTokensPerCallCap", output)
          .put("nativeToolRoundTrip", true);
    }
  }

  private static ObjectNode mcp(LiveSettings settings) throws Exception {
    String remote = settings.required("HARNESS_MCP_READ_TOOL");
    Path argsFile = Path.of(settings.required("HARNESS_MCP_ARGUMENTS_FILE"));
    require(Files.size(argsFile) <= 65536, "MCP_ARGUMENT_FILE_TOO_LARGE");
    JsonNode arguments = Json.read(Files.readString(argsFile));
    require(arguments.isObject(), "MCP_ARGUMENTS_NOT_OBJECT");
    var config =
        new McpConnectionConfig(
            "live-acceptance",
            settings.endpoint("HARNESS_MCP_ENDPOINT"),
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            1048576,
            4,
            256,
            e ->
                "none".equals(settings.mcpAuth())
                    ? Map.of()
                    : settings.bearer("HARNESS_MCP_TOKEN"));
    if (!"none".equals(settings.mcpAuth())) settings.bearer("HARNESS_MCP_TOKEN");
    try (McpConnection connection = McpConnection.connect(config)) {
      McpToolAdapter adapter = new McpToolAdapter(connection);
      var descriptor =
          adapter
              .bind(Map.of(remote, new ToolPolicy(true, false, false, 1, 15000, Set.of())))
              .get(0);
      ToolRegistry tools = new ToolRegistry();
      tools.register(descriptor, adapter);
      try (Harness h =
          runtime(
              (r, c) -> {
                throw new AssertionError("No model permitted in MCP acceptance");
              },
              tools)) {
        h.registerProgram(
            "single-read",
            r ->
                r.results.containsKey("read")
                    ? new CompleteAction(r.results.get("read").value())
                    : new ToolAction("read", "mcp", descriptor.key(), arguments));
        RunState run =
            h.start(
                new ProgramDefinition("mcp-read", "1", "single-read", Json.object()),
                actor(),
                List.of(descriptor.key()),
                new Budget(1, 1, 1, 10),
                Duration.ofSeconds(90),
                UUID.randomUUID().toString());
        RunState done = drive(h, run.id);
        require(
            done.status == RunStatus.COMPLETED && !done.results.get("read").error(),
            "MCP_READ_FAILED");
        return summary(done)
            .put("hostDeclaredReadOnly", true)
            .put("toolKey", descriptor.key())
            .put("contractDigest", descriptor.digest())
            .put("argumentsDigest", Json.hash(arguments))
            .put("outputDigest", Json.hash(done.output));
      }
    }
  }

  static Actor actor() {
    return new Actor("acceptance-host", "harness-validation", Set.of("*"));
  }

  static Harness runtime(ModelGateway model, ToolRegistry tools) {
    return new Harness(
        new InMemoryRunStore(),
        model,
        tools,
        AccessPolicy.actorPermissions(),
        Telemetry.noop(),
        Clock.systemUTC(),
        Duration.ofSeconds(60));
  }

  static RunState drive(Harness h, String id) {
    RunState state = h.get(id, actor());
    for (int i = 0; i < 100 && state.status == RunStatus.QUEUED; i++) {
      RunState next = h.tick(id);
      if (next.revision == state.revision) break;
      state = next;
    }
    return state;
  }

  static ObjectNode summary(RunState run) {
    return Json.object()
        .put("runId", run.id)
        .put("runStatus", run.status.name())
        .put("modelCalls", run.modelCalls)
        .put("toolCalls", run.toolCalls)
        .put("chargedTokens", run.chargedTokens);
  }

  static void require(boolean ok, String code) {
    if (!ok) throw new InvocationException(FailureKind.INVALID, code);
  }
}
