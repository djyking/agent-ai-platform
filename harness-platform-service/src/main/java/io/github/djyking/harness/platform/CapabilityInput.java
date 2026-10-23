package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

final class CapabilityInput {
  private CapabilityInput() {}

  static void require(Deployment config, IdentityProvider.Principal p, String permission) {
    if (!config.project(p.project()).applications().contains(p.application()))
      throw ApiFailure.hidden();
    if (!p.permits(permission)) throw ApiFailure.denied();
  }

  static void fields(JsonNode body, Set<String> allowed, Set<String> required) {
    if (body == null || !body.isObject()) throw ApiFailure.invalid();
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw ApiFailure.invalid();
            });
    for (String name : required) if (!body.has(name)) throw ApiFailure.invalid();
  }

  static String text(JsonNode body, String name, int max) {
    JsonNode v = body.path(name);
    if (!v.isTextual()
        || v.asText().isBlank()
        || v.asText().length() > max
        || v.asText().indexOf('\0') >= 0) throw ApiFailure.invalid();
    return v.asText();
  }

  static boolean bool(JsonNode body, String name) {
    if (!body.path(name).isBoolean()) throw ApiFailure.invalid();
    return body.path(name).asBoolean();
  }
}
