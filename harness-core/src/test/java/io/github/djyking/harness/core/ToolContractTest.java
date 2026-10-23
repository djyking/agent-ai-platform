package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolContractTest {
  @Test
  void validatesNestedNumericBooleanArrayAndLocalReferenceConstraints() {
    ToolDescriptor tool =
        tool(
            """
{"type":"object","$defs":{"item":{"type":"object","required":["weight","active"],"properties":{"weight":{"type":"number","minimum":0},"active":{"type":"boolean"}},"additionalProperties":false}},
 "properties":{"items":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/item"}}},"required":["items"],"additionalProperties":false}
""");
    assertDoesNotThrow(
        () ->
            ToolRegistry.validate(
                tool, Json.read("{\"items\":[{\"weight\":1.5,\"active\":true}]}")));
    for (String invalid :
        List.of(
            "{\"items\":[]}",
            "{\"items\":[{\"weight\":-1,\"active\":true}]}",
            "{\"items\":[{\"weight\":1,\"active\":\"true\"}]}",
            "{\"items\":[{\"weight\":1}]}",
            "{\"items\":[],\"unknown\":1}"))
      assertThrows(
          InvocationException.class, () -> ToolRegistry.validate(tool, Json.read(invalid)));
  }

  @Test
  void remoteSchemaResolutionIsRejectedBeforeUse() {
    ToolRegistry registry = new ToolRegistry();
    for (String schema :
        List.of(
            "{\"$ref\":\"https://example.invalid/private\"}",
            "{\"properties\":{\"x\":{\"$dynamicRef\":\"file:///private\"}}}",
            "{\"$schema\":\"https://example.invalid/schema\"}"))
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.register(tool(schema), (t, a, c) -> ToolResult.success(a)));
  }

  @Test
  void registryAndReturnedDescriptorsCannotMutateTheStoredContract() {
    ToolRegistry registry = new ToolRegistry();
    ToolDescriptor source = tool("{\"type\":\"object\"}");
    registry.register(source, (t, a, c) -> ToolResult.success(a));
    String hash = source.digest();
    ((com.fasterxml.jackson.databind.node.ObjectNode) source.inputSchema()).put("type", "null");
    ToolDescriptor returned = registry.require(source.key()).descriptor();
    ((com.fasterxml.jackson.databind.node.ObjectNode) returned.inputSchema())
        .put("type", "boolean");
    assertEquals(hash, registry.require(source.key()).descriptor().digest());
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register(tool("{}"), (t, a, c) -> ToolResult.success(a)));
  }

  @Test
  void hashIsStableForPermissionOrderAndJsonObjectOrder() {
    ToolPolicy first =
        new ToolPolicy(true, false, true, 2, 1000, new LinkedHashSet<>(List.of("b", "a")));
    ToolPolicy second =
        new ToolPolicy(true, false, true, 2, 1000, new LinkedHashSet<>(List.of("a", "b")));
    assertEquals(Json.hash(first), Json.hash(second));
    assertEquals(
        Json.hash(new Actor("a", "p", first.requiredPermissions())),
        Json.hash(new Actor("a", "p", second.requiredPermissions())));
    assertEquals(
        Json.hash(Json.read("{\"b\":2,\"a\":1}")), Json.hash(Json.read("{\"a\":1,\"b\":2}")));
  }

  @Test
  void contextRejectsUnpairedNativeToolMessagesAndKeepsHistoryDetached() {
    assertThrows(
        InvocationException.class,
        () -> ContextWindow.validatePairing(List.of(Message.tool("unknown", "value"))));
    Message calls =
        new Message(
            "assistant", null, List.of(new ToolCall("native", "search", Json.object())), null);
    assertThrows(
        InvocationException.class,
        () -> ContextWindow.validatePairing(List.of(calls, Message.text("user", "next"))));
    List<Message> history =
        List.of(Message.text("user", "question"), calls, Message.tool("native", "x".repeat(9000)));
    List<Message> fitted = ContextWindow.fit(history, List.of(), HarnessRuntimeTest.PROFILE);
    assertEquals(9000, history.get(2).content().length());
    assertTrue(fitted.get(2).content().contains("truncated"));
    assertDoesNotThrow(() -> ContextWindow.validatePairing(fitted));
  }

  private static ToolDescriptor tool(String schema) {
    return new ToolDescriptor(
        "test",
        "test",
        "test",
        "internal",
        "test",
        "1",
        Json.read(schema),
        ToolPolicy.readOnlyPolicy());
  }
}
