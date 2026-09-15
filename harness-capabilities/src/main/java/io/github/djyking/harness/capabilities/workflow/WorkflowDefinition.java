package io.github.djyking.harness.capabilities.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import java.security.*;
import java.util.*;

/**
 * Immutable bounded graph. Conditions and bindings operate on named values, never executable
 * expressions.
 */
public record WorkflowDefinition(
    String id, String version, String start, int maxTransitions, Map<String, Node> nodes) {
  public WorkflowDefinition {
    requireName(id);
    requireName(version);
    requireName(start);
    if (maxTransitions < 1 || maxTransitions > 10000)
      throw new IllegalArgumentException("maxTransitions must be 1..10000");
    nodes = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(nodes, "nodes")));
    if (nodes.isEmpty() || !nodes.containsKey(start))
      throw new IllegalArgumentException("Missing start node");
    for (var entry : nodes.entrySet()) {
      requireName(entry.getKey());
      Objects.requireNonNull(entry.getValue(), "node");
      for (String target : entry.getValue().targets())
        if (!nodes.containsKey(target))
          throw new IllegalArgumentException("Unknown workflow target: " + target);
    }
  }

  public String fingerprint() {
    try {
      byte[] bytes = new ObjectMapper().writeValueAsBytes(this);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Tool.class, name = "tool"),
    @JsonSubTypes.Type(value = Model.class, name = "model"),
    @JsonSubTypes.Type(value = Agent.class, name = "agent"),
    @JsonSubTypes.Type(value = Condition.class, name = "condition"),
    @JsonSubTypes.Type(value = Human.class, name = "human"),
    @JsonSubTypes.Type(value = End.class, name = "end")
  })
  public sealed interface Node permits Tool, Model, Agent, Condition, Human, End {
    List<String> targets();
  }

  public record Tool(
      String toolName,
      String argumentsJson,
      Map<String, String> argumentBindings,
      String output,
      String next)
      implements Node {
    public Tool {
      if (toolName == null || toolName.isBlank() || toolName.length() > 200)
        throw new IllegalArgumentException("Invalid tool key");
      requireName(output);
      requireName(next);
      argumentBindings = bindings(argumentBindings);
      try {
        if (!new ObjectMapper().readTree(argumentsJson).isObject())
          throw new IllegalArgumentException("Tool arguments must be a JSON object");
      } catch (JsonProcessingException ex) {
        throw new IllegalArgumentException("Invalid tool arguments JSON", ex);
      }
    }

    @Override
    public List<String> targets() {
      return List.of(next);
    }
  }

  public record Model(
      PromptTemplate prompt, Map<String, String> inputBindings, String output, String next)
      implements Node {
    public Model {
      Objects.requireNonNull(prompt, "prompt");
      inputBindings = promptBindings(prompt, inputBindings);
      requireName(output);
      requireName(next);
    }

    @Override
    public List<String> targets() {
      return List.of(next);
    }
  }

  /** The nested agent uses the parent run's budget and the intersection with its allowed tools. */
  public record Agent(
      PromptTemplate prompt,
      Map<String, String> inputBindings,
      Set<String> allowedTools,
      int maxTurns,
      String output,
      String next)
      implements Node {
    public Agent {
      Objects.requireNonNull(prompt, "prompt");
      inputBindings = promptBindings(prompt, inputBindings);
      allowedTools =
          Collections.unmodifiableSet(
              new TreeSet<>(Objects.requireNonNull(allowedTools, "allowedTools")));
      if (maxTurns < 1 || maxTurns > 100)
        throw new IllegalArgumentException("maxTurns must be 1..100");
      requireName(output);
      requireName(next);
    }

    @Override
    public List<String> targets() {
      return List.of(next);
    }
  }

  public record Condition(String variable, String equalsJson, String whenTrue, String whenFalse)
      implements Node {
    public Condition {
      requireName(variable);
      requireName(whenTrue);
      requireName(whenFalse);
      try {
        if (new ObjectMapper().readTree(equalsJson) == null)
          throw new IllegalArgumentException("Missing condition value");
      } catch (JsonProcessingException ex) {
        throw new IllegalArgumentException("Invalid condition value JSON", ex);
      }
    }

    @Override
    public List<String> targets() {
      return List.of(whenTrue, whenFalse);
    }
  }

  public record Human(String message, String output, String next) implements Node {
    public Human {
      Objects.requireNonNull(message, "message");
      requireName(output);
      requireName(next);
    }

    @Override
    public List<String> targets() {
      return List.of(next);
    }
  }

  public record End(String output) implements Node {
    public End {
      requireName(output);
    }

    @Override
    public List<String> targets() {
      return List.of();
    }
  }

  private static Map<String, String> promptBindings(
      PromptTemplate template, Map<String, String> bindings) {
    Map<String, String> copy = bindings(bindings);
    if (!copy.keySet().equals(template.variables().keySet()))
      throw new IllegalArgumentException("Prompt bindings must match its declared variables");
    return copy;
  }

  private static Map<String, String> bindings(Map<String, String> values) {
    Map<String, String> copy =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(values, "bindings")));
    copy.forEach(
        (key, value) -> {
          requireName(key);
          requireName(value);
        });
    return copy;
  }

  private static void requireName(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
      throw new IllegalArgumentException("Invalid workflow name");
  }
}
