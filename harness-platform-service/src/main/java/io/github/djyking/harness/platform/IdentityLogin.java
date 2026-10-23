package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;

/** Server-side identity login adapter. Tokens returned here never enter a browser response. */
public interface IdentityLogin {
  JsonNode captcha();

  String login(JsonNode credentials);

  default boolean captchaRequired() {
    return true;
  }

  default JsonNode configuration() {
    return Json.object()
        .put("provider", "opsagent")
        .put("localTestOnly", false)
        .put("captchaRequired", captchaRequired());
  }
}
