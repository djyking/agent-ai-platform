package io.github.djyking.harness.adapters.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.github.djyking.harness.adapters.telemetry.OpenTelemetryHeaders;
import io.github.djyking.harness.adapters.telemetry.OpenTelemetryTelemetry;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpHarnessIntegrationTest {
  @Test
  void internalAndRealMcpCallsShareApprovalBudgetAuditAndExportedTrace() throws Exception {
    var exporter = InMemorySpanExporter.create();
    try (var traces =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var server = new LocalMcpServer();
        var connection =
            McpConnection.connect(
                McpConnectionConfig.defaults(
                    "fixture",
                    server.endpoint(),
                    new OpenTelemetryHeaders(
                        endpoint -> Map.of("Authorization", "Bearer test-only-secret"))))) {
      var adapter = new McpToolAdapter(connection);
      var remote = adapter.bind(Map.of("update", ToolPolicy.approvedWrite())).get(0);
      var internal =
          new ToolDescriptor(
              "internal:check",
              "internal_check",
              "Check local precondition",
              "internal",
              "check",
              "1",
              Json.read("{\"type\":\"object\",\"additionalProperties\":false}"),
              ToolPolicy.readOnlyPolicy());
      var registry = new ToolRegistry();
      var localCalls = new AtomicInteger();
      registry.register(
          internal,
          (tool, args, ctx) -> {
            localCalls.incrementAndGet();
            return ToolResult.success(Json.object().put("ready", true));
          });
      registry.register(remote, adapter);
      var modelCalls = new AtomicInteger();
      ModelGateway model =
          (request, context) -> {
            if (modelCalls.getAndIncrement() == 0) {
              return new ModelResponse(
                  new Message(
                      "assistant",
                      null,
                      List.of(
                          new ToolCall("native-local-id", internal.modelName(), Json.object()),
                          new ToolCall(
                              "native-mcp-id",
                              remote.modelName(),
                              Json.read("{\"query\":\"change\"}"))),
                      null),
                  FinishReason.TOOL_CALLS,
                  new Usage(20, 10, true));
            }
            assertEquals(
                List.of("native-local-id", "native-mcp-id"),
                request.messages().stream()
                    .filter(message -> message.role().equals("tool"))
                    .map(Message::toolCallId)
                    .toList());
            return new ModelResponse(
                Message.text("assistant", "Completed"), FinishReason.FINAL, new Usage(35, 5, true));
          };
      var store = new InMemoryRunStore();
      var harness =
          new Harness(
              store,
              model,
              registry,
              AccessPolicy.actorPermissions(),
              new OpenTelemetryTelemetry(traces.get("harness-test")),
              Clock.systemUTC(),
              Duration.ofSeconds(20));
      var owner = new Actor("owner", "fixture-project", Set.of("*"));
      var run =
          harness.start(
              definition(),
              owner,
              List.of(internal.key(), remote.key()),
              Budget.defaults(),
              Duration.ofMinutes(1),
              "approval-trace-fixture");
      run = advanceUntilStopped(harness, run);
      assertEquals(RunStatus.WAITING_APPROVAL, run.status);
      assertEquals(1, localCalls.get());
      assertEquals(0, server.calls.get(), "MCP writes must not cross the approval boundary");
      final String runId = run.id;
      var approver = new Actor("approver", "fixture-project", Set.of("approval:decide"));
      assertThrows(
          RunStore.Conflict.class,
          () -> harness.decide(runId, approver, "stale-digest", true, "approved"));
      harness.decide(run.id, approver, run.approval.digest(), true, "Approved fixture change");
      run = advanceUntilStopped(harness, store.get(run.id));
      assertEquals(RunStatus.COMPLETED, run.status, run.stopReason);
      assertEquals(1, server.calls.get());
      assertEquals(2, run.toolCalls);
      assertEquals(2, run.modelCalls);
      assertEquals(70, run.chargedTokens);
      var events = store.events(run.id, 0, 100);
      assertTrue(
          events.stream().anyMatch(event -> event.event().type().equals("APPROVAL_GRANTED")));
      assertEquals(
          4,
          events.stream().filter(event -> event.event().type().equals("ATTEMPT_STARTED")).count());

      var spans = exporter.getFinishedSpanItems();
      assertEquals(4, spans.size());
      String traceId = Json.hash(run.id).substring(0, 32);
      var spanIds = new HashSet<String>();
      for (var span : spans) {
        assertEquals(traceId, span.getTraceId());
        assertEquals(run.id, span.getAttributes().get(AttributeKey.stringKey("harness.run.id")));
        assertEquals(
            "SUCCESS", span.getAttributes().get(AttributeKey.stringKey("harness.outcome")));
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertTrue(spanIds.add(span.getSpanId()));
        assertFalse(span.getAttributes().toString().contains("test-only-secret"));
      }
      assertTrue(
          server.callTraceparents.get(0).startsWith("00-" + traceId + "-"),
          "MCP async transport must receive the current invocation trace context");
      assertFalse(
          io.opentelemetry.api.trace.Span.current().getSpanContext().isValid(),
          "Tick must close tracing scope");
    }
  }

  @Test
  void revocationAfterApprovalPreventsWireDispatch() throws Exception {
    try (var server = new LocalMcpServer();
        var connection =
            McpConnection.connect(
                McpConnectionConfig.defaults(
                    "fixture", server.endpoint(), HeaderProvider.none()))) {
      var adapter = new McpToolAdapter(connection);
      var descriptor = adapter.bind(Map.of("update", ToolPolicy.approvedWrite())).get(0);
      var registry = new ToolRegistry();
      registry.register(descriptor, adapter);
      var revoked = new AtomicBoolean();
      AccessPolicy policy =
          (actor, permission, resource) -> {
            if (revoked.get() && permission.equals("tool:" + descriptor.key()))
              throw new InvocationException(FailureKind.DENIED, "REVOKED");
          };
      var store = new InMemoryRunStore();
      var harness =
          new Harness(
              store,
              (request, context) ->
                  new ModelResponse(
                      new Message(
                          "assistant",
                          null,
                          List.of(
                              new ToolCall(
                                  "native-call",
                                  descriptor.modelName(),
                                  Json.read("{\"query\":\"change\"}"))),
                          null),
                      FinishReason.TOOL_CALLS,
                      new Usage(10, 10, true)),
              registry,
              policy,
              Telemetry.noop(),
              Clock.systemUTC(),
              Duration.ofSeconds(20));
      var actor = new Actor("owner", "fixture-project", Set.of());
      var run =
          advanceUntilStopped(
              harness,
              harness.start(
                  definition(),
                  actor,
                  List.of(descriptor.key()),
                  Budget.defaults(),
                  Duration.ofMinutes(1),
                  "revocation-fixture"));
      assertEquals(RunStatus.WAITING_APPROVAL, run.status);
      harness.decide(run.id, actor, run.approval.digest(), true, "Approved");
      revoked.set(true);
      run = advanceUntilStopped(harness, store.get(run.id));
      assertEquals(RunStatus.NEEDS_ATTENTION, run.status);
      assertEquals(0, server.calls.get());
    }
  }

  private ProgramDefinition definition() {
    var profile =
        new ModelProfile("fixture", "scripted", "fixture", 8192, 512, 5_000, Json.object());
    return new ProgramDefinition(
        "fixture-agent",
        "1",
        "agent",
        Json.tree(
            new AgentProgram.AgentSpec(
                profile, List.of(Message.text("user", "Perform the fixture change")), 3)));
  }

  private RunState advanceUntilStopped(Harness harness, RunState run) {
    for (int i = 0;
        i < 100 && (run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING);
        i++) run = harness.tick(run.id);
    return run;
  }
}
