package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;

final class CapabilityHttp {
  private CapabilityHttp() {}

  static String header(HttpServletRequest request, String name) {
    List<String> values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) throw ApiFailure.invalid();
    return values.isEmpty() ? null : values.get(0);
  }

  static IdentityProvider.Principal principal(
      PlatformService platform, String project, HttpServletRequest request) {
    String auth = header(request, "Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) throw new ApiFailure(401, "UNAUTHENTICATED");
    return platform.authenticate(
        project, auth.substring(7), header(request, "X-Harness-User-Token"));
  }

  static JsonNode body(HttpServletRequest request) {
    String content = request.getContentType();
    if (content == null
        || !content.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    try {
      return ApiJson.read(request.getInputStream());
    } catch (java.io.IOException ex) {
      throw ApiFailure.invalid();
    }
  }

  static ResponseEntity<JsonNode> response(JsonNode value) {
    var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
    if (value.has("revision")) response.eTag(CapabilityRepository.etag(value));
    return response.body(value);
  }
}
