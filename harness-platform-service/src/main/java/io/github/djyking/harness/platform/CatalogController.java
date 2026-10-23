package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/catalog")
public final class CatalogController {
  private final PlatformService platform;
  private final CatalogService catalog;

  public CatalogController(PlatformService platform, CatalogService catalog) {
    this.platform = platform;
    this.catalog = catalog;
  }

  private String header(HttpServletRequest request, String name) {
    List<String> values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) throw ApiFailure.invalid();
    return values.isEmpty() ? null : values.get(0);
  }

  private IdentityProvider.Principal principal(String project, HttpServletRequest request) {
    String auth = header(request, "Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) throw new ApiFailure(401, "UNAUTHENTICATED");
    return platform.authenticate(
        project, auth.substring(7), header(request, "X-Harness-User-Token"));
  }

  private JsonNode body(HttpServletRequest request) {
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

  private ResponseEntity<JsonNode> resource(JsonNode value) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(CatalogService.etag(value))
        .body(value);
  }

  @GetMapping("/capabilities")
  public JsonNode capabilities(
      @PathVariable("project") String project, HttpServletRequest request) {
    return catalog.capabilities(principal(project, request));
  }

  @GetMapping("/resources")
  public JsonNode list(
      @PathVariable("project") String project,
      @RequestParam(value = "type", required = false) String type,
      HttpServletRequest request) {
    return catalog.list(principal(project, request), type);
  }

  @GetMapping("/resources/{type}/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return resource(catalog.get(principal(project, request), type, id));
  }

  @PutMapping("/resources/{type}/{id}")
  public ResponseEntity<JsonNode> save(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return resource(
        catalog.mutate(
            principal(project, request),
            type,
            id,
            "save",
            null,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @PostMapping("/resources/{type}/{id}/{operation:validate|publish|disable|default|revoke}")
  public ResponseEntity<JsonNode> mutate(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      @PathVariable("operation") String operation,
      HttpServletRequest request) {
    return resource(
        catalog.mutate(
            principal(project, request),
            type,
            id,
            operation,
            null,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/resources/{type}/{id}/versions/{version}")
  public JsonNode version(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      @PathVariable("version") int version,
      HttpServletRequest request) {
    return catalog.version(principal(project, request), type, id, version);
  }

  @PostMapping("/resources/{type}/{id}/versions/{version}/disable")
  public ResponseEntity<JsonNode> disableVersion(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      @PathVariable("version") int version,
      HttpServletRequest request) {
    return resource(
        catalog.mutate(
            principal(project, request),
            type,
            id,
            "disable",
            version,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/resources/{type}/{id}/versions/{version}/diff")
  public JsonNode diff(
      @PathVariable("project") String project,
      @PathVariable("type") String type,
      @PathVariable("id") String id,
      @PathVariable("version") int version,
      @RequestParam("against") int against,
      HttpServletRequest request) {
    return catalog.diff(principal(project, request), type, id, version, against);
  }

  @GetMapping("/releases")
  public JsonNode releases(@PathVariable("project") String project, HttpServletRequest request) {
    return catalog.releases(principal(project, request));
  }

  @GetMapping("/agents/{id}/default")
  public JsonNode defaultRelease(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return catalog.defaultRelease(principal(project, request), id);
  }
}
