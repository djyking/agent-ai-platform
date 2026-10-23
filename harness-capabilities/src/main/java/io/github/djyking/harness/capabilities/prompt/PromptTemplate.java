package io.github.djyking.harness.capabilities.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A versioned prompt value; rendering never executes expressions or recursively expands input. */
public record PromptTemplate(
    String id, String version, String system, String user, Map<String, VariableType> variables) {
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]{0,79})\\s*}}");
  private static final ObjectMapper JSON = new ObjectMapper();

  public enum VariableType {
    STRING,
    NUMBER,
    BOOLEAN,
    STRING_LIST,
    JSON
  }

  public PromptTemplate {
    requireName(id, "id");
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
      throw new IllegalArgumentException("Invalid prompt version");
    Objects.requireNonNull(system, "system");
    Objects.requireNonNull(user, "user");
    variables =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(variables, "variables")));
    variables.forEach(
        (name, type) -> {
          requireName(name, "variable");
          Objects.requireNonNull(type, "variable type");
        });
    Set<String> referenced = new HashSet<>();
    for (String template : List.of(system, user)) {
      Matcher matcher = PLACEHOLDER.matcher(template);
      while (matcher.find()) referenced.add(matcher.group(1));
      String remaining = PLACEHOLDER.matcher(template).replaceAll("");
      if (remaining.contains("{{") || remaining.contains("}}"))
        throw new IllegalArgumentException("Malformed prompt placeholder");
    }
    if (!referenced.equals(variables.keySet()))
      throw new IllegalArgumentException("Declared variables must match the placeholders exactly");
  }

  /**
   * Returned strings and canonical JSON are immutable and suitable for a run's pinned
   * configuration.
   */
  public RenderedPrompt render(Map<String, ?> input) {
    Objects.requireNonNull(input, "input");
    if (!variables.keySet().equals(input.keySet()))
      throw new IllegalArgumentException(
          "Prompt input must contain exactly the declared variables");
    Map<String, Object> canonical = new TreeMap<>();
    Map<String, String> substitutions = new HashMap<>();
    variables.forEach(
        (name, type) -> {
          Object value = input.get(name);
          if (!valid(type, value))
            throw new IllegalArgumentException("Invalid value type for prompt variable: " + name);
          // JSON serialization also detaches mutable lists/maps from the caller.
          String encoded = json(value);
          canonical.put(name, JSON.valueToTree(value));
          substitutions.put(name, type == VariableType.STRING ? (String) value : encoded);
        });
    String fingerprint = sha256(json(this));
    return new RenderedPrompt(
        id,
        version,
        fingerprint,
        substitute(system, substitutions),
        substitute(user, substitutions),
        json(canonical));
  }

  private static boolean valid(VariableType type, Object value) {
    if (value == null) return false;
    return switch (type) {
      case STRING -> value instanceof String;
      case BOOLEAN -> value instanceof Boolean;
      case NUMBER -> value instanceof Number && finiteNumber((Number) value);
      case STRING_LIST ->
          value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
      case JSON -> true;
    };
  }

  private static boolean finiteNumber(Number number) {
    try {
      new BigDecimal(number.toString());
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static String substitute(String template, Map<String, String> substitutions) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find())
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(substitutions.get(matcher.group(1))));
    matcher.appendTail(result);
    return result.toString();
  }

  static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Prompt value is not JSON serializable", ex);
    }
  }

  private static void requireName(String value, String label) {
    if (value == null || !NAME.matcher(value).matches())
      throw new IllegalArgumentException("Invalid prompt " + label);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public record RenderedPrompt(
      String id,
      String version,
      String fingerprint,
      String system,
      String user,
      String effectiveInputsJson) {}
}
