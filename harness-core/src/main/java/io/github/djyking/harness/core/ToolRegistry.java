package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.djyking.harness.core.Contracts.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery alone never registers an executable capability: host policy and handler are required.
 */
public final class ToolRegistry {
  public record Entry(ToolDescriptor descriptor, ToolHandler handler) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public synchronized void register(ToolDescriptor tool, ToolHandler handler) {
    if (entries.containsKey(tool.key())) throw new IllegalArgumentException("Duplicate tool key");
    put(tool, handler);
  }

  public synchronized void replace(ToolDescriptor tool, ToolHandler handler) {
    put(tool, handler);
  }

  private void put(ToolDescriptor tool, ToolHandler handler) {
    Objects.requireNonNull(handler);
    for (Entry e : entries.values())
      if (!e.descriptor.key().equals(tool.key())
          && e.descriptor.modelName().equals(tool.modelName()))
        throw new IllegalArgumentException("Duplicate model tool name");
    inspectSchema(tool.inputSchema());
    entries.put(tool.key(), new Entry(Json.copy(tool, ToolDescriptor.class), handler));
  }

  public void remove(String key) {
    entries.remove(key);
  }

  public Entry require(String key) {
    Entry entry = entries.get(key);
    if (entry == null)
      throw new InvocationException(FailureKind.DENIED, "TOOL_DISABLED_OR_UNREGISTERED");
    return new Entry(Json.copy(entry.descriptor(), ToolDescriptor.class), entry.handler());
  }

  public List<ToolDescriptor> snapshot(Collection<String> keys) {
    if (keys.size() > 64) throw new IllegalArgumentException("Select at most 64 tools per run");
    return keys.stream().distinct().sorted().map(k -> require(k).descriptor()).toList();
  }

  public static void validate(ToolDescriptor tool, JsonNode arguments) {
    if (arguments == null || !arguments.isObject() || Json.write(arguments).length() > 1_048_576)
      throw new InvocationException(FailureKind.INVALID, "TOOL_ARGUMENTS_INVALID");
    inspectSchema(tool.inputSchema());
    var schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(tool.inputSchema());
    if (!schema.validate(arguments).isEmpty())
      throw new InvocationException(FailureKind.INVALID, "TOOL_SCHEMA_VALIDATION_FAILED");
  }

  private static void inspectSchema(JsonNode schema) {
    if ((!schema.isObject() && !schema.isBoolean()) || Json.write(schema).length() > 131_072)
      throw new IllegalArgumentException("Invalid or oversized tool schema");
    inspectReferences(schema);
  }

  private static void inspectReferences(JsonNode node) {
    if (node.isObject()) {
      for (String key : List.of("$ref", "$dynamicRef", "$recursiveRef"))
        if (node.has(key) && !node.path(key).asText().startsWith("#"))
          throw new IllegalArgumentException("Remote schema references are disabled");
      if (node.has("$schema")
          && !Set.of(
                  "https://json-schema.org/draft/2020-12/schema",
                  "http://json-schema.org/draft-07/schema#",
                  "https://json-schema.org/draft/2019-09/schema")
              .contains(node.path("$schema").asText()))
        throw new IllegalArgumentException("Unsupported schema dialect");
    }
    if (node.isContainerNode()) node.forEach(ToolRegistry::inspectReferences);
  }
}
