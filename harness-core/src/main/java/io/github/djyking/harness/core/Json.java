package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** JSON wire/state codec. No credentials belong in persisted state. */
public final class Json {
  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private Json() {}

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  public static JsonNode read(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static JsonNode tree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode state", e);
    }
  }

  public static <T> T convert(Object value, Class<T> type) {
    return MAPPER.convertValue(value, type);
  }

  public static <T> T copy(T value, Class<T> type) {
    return convert(tree(value), type);
  }

  public static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(write(canonical(tree(value))).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode canonical(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = object();
      java.util.TreeSet<String> names = new java.util.TreeSet<>();
      value.fieldNames().forEachRemaining(names::add);
      names.forEach(n -> sorted.set(n, canonical(value.get(n))));
      return sorted;
    }
    if (value.isArray()) {
      var result = MAPPER.createArrayNode();
      value.forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value;
  }
}
