package io.github.djyking.harness.core;

import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BudgetTerminalControlTest {
  enum Control {
    PAUSE,
    CANCEL,
    RESUME
  }

  @ParameterizedTest
  @EnumSource(Control.class)
  void exhaustedRunRejectsControlWithoutChangingStateOrAudit(Control control) {
    var actor = new Actor("operator", "budget-terminal", Set.of("*"));
    var calls = new AtomicInteger();
    var tools = new ToolRegistry();
    tools.register(
        new ToolDescriptor(
            "read",
            "read",
            "Count a synthetic read",
            "internal",
            "read",
            "1",
            Json.read("{\"type\":\"object\",\"additionalProperties\":false}"),
            new ToolPolicy(true, false, false, 1, 1000, Set.of())),
        (descriptor, arguments, context) ->
            ToolResult.success(Json.object().put("call", calls.incrementAndGet())));
    try (var harness =
        new Harness(
            new InMemoryRunStore(),
            (request, context) -> {
              throw new AssertionError("The budget test must not invoke a model");
            },
            tools,
            AccessPolicy.actorPermissions(),
            Telemetry.noop(),
            Clock.systemUTC(),
            Duration.ofSeconds(10))) {
      harness.registerProgram(
          "consume-tools",
          run -> new ToolAction("call-" + run.results.size(), "read", "read", Json.object()));
      RunState run =
          harness.start(
              new ProgramDefinition("budget-terminal", "1", "consume-tools", Json.object()),
              actor,
              List.of("read"),
              new Budget(1000, 1, 1, 20),
              Duration.ofMinutes(1),
              "exhausted-" + control);
      for (int i = 0; i < 10 && run.status == RunStatus.QUEUED; i++) run = harness.tick(run.id);
      assertEquals(RunStatus.BUDGET_EXCEEDED, run.status);
      assertEquals("TOOL_BUDGET_EXCEEDED", run.stopReason);
      assertEquals(1, calls.get(), "The budget must be consumed by a real successful dispatch");
      assertEquals(1, run.toolCalls);
      assertEquals(1, run.results.size());
      String id = run.id;
      var stateBefore = Json.tree(run);
      long revisionBefore = run.revision;
      var auditBefore = harness.events(id, actor, 0, 100);

      assertThrows(
          RunStore.Conflict.class,
          () -> {
            switch (control) {
              case PAUSE -> harness.pause(id, actor);
              case CANCEL -> harness.cancel(id, actor);
              case RESUME -> harness.resume(id, actor);
            }
          });

      RunState after = harness.get(id, actor);
      assertEquals(revisionBefore, after.revision);
      assertEquals(stateBefore, Json.tree(after));
      assertEquals(auditBefore, harness.events(id, actor, 0, 100));
      assertEquals(stateBefore, Json.tree(harness.tick(id)));
      assertEquals(1, calls.get());
      assertEquals(auditBefore, harness.events(id, actor, 0, 100));
    }
  }
}
