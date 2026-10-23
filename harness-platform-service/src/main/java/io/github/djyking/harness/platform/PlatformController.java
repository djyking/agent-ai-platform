package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/runs")
public final class PlatformController {
  private final PlatformService service;

  public PlatformController(PlatformService service) {
    this.service = service;
  }

  private String header(HttpServletRequest request, String name) {
    List<String> values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) throw ApiFailure.invalid();
    return values.isEmpty() ? null : values.get(0);
  }

  private String credential(HttpServletRequest request) {
    String value = header(request, "Authorization");
    if (value == null || !value.startsWith("Bearer ")) throw new ApiFailure(401, "UNAUTHENTICATED");
    return value.substring(7);
  }

  private IdentityProvider.Principal principal(String project, HttpServletRequest request) {
    return service.authenticate(
        project, credential(request), header(request, "X-Harness-User-Token"));
  }

  private JsonNode body(HttpServletRequest request) {
    String content = request.getContentType();
    if (content == null
        || !content.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    if (request.getContentLengthLong() > 65536) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    try {
      return ApiJson.read(request.getInputStream());
    } catch (java.io.IOException ex) {
      throw ApiFailure.invalid();
    }
  }

  private ResponseEntity<JsonNode> view(PlatformService.View view) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(view.etag())
        .body(view.body());
  }

  private ResponseEntity<JsonNode> accepted(JsonNode response) {
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .header("Location", response.path("statusUrl").asText())
        .header("Retry-After", "1")
        .body(response);
  }

  @PostMapping
  public ResponseEntity<JsonNode> create(
      @PathVariable("project") String project, HttpServletRequest request) {
    var p = principal(project, request);
    return accepted(
        service.create(
            p,
            credential(request),
            header(request, "X-Harness-User-Token"),
            header(request, "Idempotency-Key"),
            body(request)));
  }

  @GetMapping
  public JsonNode list(
      @PathVariable("project") String project,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "status", required = false) String status,
      HttpServletRequest request) {
    return service.list(principal(project, request), cursor, limit, status);
  }

  @GetMapping("/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return view(service.get(principal(project, request), id));
  }

  @GetMapping("/{id}/events")
  public JsonNode events(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      HttpServletRequest request) {
    return service.events(principal(project, request), id, cursor, limit);
  }

  @PostMapping("/{id}/{operation:pause|cancel|resume}")
  public ResponseEntity<JsonNode> control(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("operation") String operation,
      HttpServletRequest request) {
    return accepted(
        service.control(
            principal(project, request),
            id,
            operation,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/{id}/approval")
  public ResponseEntity<JsonNode> approval(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return view(service.approval(principal(project, request), id));
  }

  @PostMapping("/{id}/approvals/{approval}/decision")
  public ResponseEntity<JsonNode> decide(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("approval") String approval,
      HttpServletRequest request) {
    return accepted(
        service.decide(
            principal(project, request),
            id,
            approval,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/{id}/unknown-invocation")
  public ResponseEntity<JsonNode> unknown(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return view(service.unknown(principal(project, request), id));
  }

  @PostMapping("/{id}/tool-reconciliations")
  public ResponseEntity<JsonNode> reconcile(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return accepted(
        service.reconcile(
            principal(project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }
}
