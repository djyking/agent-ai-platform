package io.github.djyking.harness.platform;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.djyking.harness.core.Json;
import java.io.*;
import java.util.*;

/** Shared request parser and frozen OpenAPI schema validation. */
public final class ApiJson {
  private ApiJson() {}

  public static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(65536)
                          .maxNumberLength(32)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final JsonNode COMPONENTS;

  static {
    try (InputStream in = ApiJson.class.getResourceAsStream("/api/openapi.json")) {
      COMPONENTS = Json.MAPPER.readTree(Objects.requireNonNull(in)).path("components");
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  public static JsonNode read(InputStream input) {
    try {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
      JsonNode value = MAPPER.readTree(bytes);
      if (value == null || !value.isObject()) throw ApiFailure.invalid();
      return value;
    } catch (ApiFailure ex) {
      throw ex;
    } catch (IOException | IllegalArgumentException ex) {
      throw ApiFailure.invalid();
    }
  }

  public static void validate(String schemaName, JsonNode value) {
    ObjectNode schema = Json.object().put("$ref", "#/components/schemas/" + schemaName);
    schema.set("components", COMPONENTS);
    if (!SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(schema)
        .validate(value)
        .isEmpty()) throw new ApiFailure(422, "INPUT_INVALID");
  }

  public static void identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      throw ApiFailure.invalid();
  }

  public static void uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw ApiFailure.invalid();
    } catch (IllegalArgumentException ex) {
      throw ApiFailure.invalid();
    }
  }

  public static String canonical(JsonNode node) {
    return Json.write(sorted(node));
  }

  private static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = Json.object();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String key : names) out.set(key, sorted(node.get(key)));
      return out;
    }
    if (node.isArray()) {
      var out = Json.MAPPER.createArrayNode();
      node.forEach(v -> out.add(sorted(v)));
      return out;
    }
    return node;
  }
}
