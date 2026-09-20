package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.CapabilityHttp.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/operations")
public final class OperationsController {
  private static void fields(JsonNode body, Set<String> allowed, Set<String> required) {
    if (!body.isObject()) throw ApiFailure.invalid();
    body.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw ApiFailure.invalid();
            });
    for (String name : required) if (!body.has(name)) throw ApiFailure.invalid();
  }

  private final PlatformService platform;
  private final OperationsService operations;
  private final RetentionService retention;

  public OperationsController(
      PlatformService platform, OperationsService operations, RetentionService retention) {
    this.platform = platform;
    this.operations = operations;
    this.retention = retention;
  }

  @GetMapping
  public ResponseEntity<JsonNode> overview(
      @PathVariable("project") String project, HttpServletRequest request) {
    return response(operations.overview(principal(platform, project, request)));
  }

  @GetMapping("/inbox")
  public ResponseEntity<JsonNode> inbox(
      @PathVariable("project") String project,
      @RequestParam(value = "after", defaultValue = "0") long after,
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      HttpServletRequest request) {
    return response(operations.inbox(principal(platform, project, request), after, limit));
  }

  @PostMapping("/alerts/{id}/ack")
  public ResponseEntity<JsonNode> acknowledge(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    var p = principal(platform, project, request);
    fields(body(request), Set.of(), Set.of());
    JsonNode result =
        operations.acknowledge(
            p, id, header(request, "Idempotency-Key"), header(request, "If-Match"));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag("\"o" + result.path("revision").asLong() + "\"")
        .body(result);
  }

  @GetMapping("/commands/{key}")
  public ResponseEntity<JsonNode> command(
      @PathVariable("project") String project,
      @PathVariable("key") String key,
      HttpServletRequest request) {
    return response(operations.command(principal(platform, project, request), key));
  }

  @PostMapping("/retention/preview")
  public ResponseEntity<JsonNode> preview(
      @PathVariable("project") String project, HttpServletRequest request) {
    var p = principal(platform, project, request);
    JsonNode input = body(request);
    fields(input, Set.of("limit", "after"), Set.of());
    if (input.has("limit")
        && (!input.path("limit").isIntegralNumber() || !input.path("limit").canConvertToInt()))
      throw ApiFailure.invalid();
    if (input.has("after") && !input.path("after").isTextual()) throw ApiFailure.invalid();
    return response(
        retention.preview(
            p,
            input.path("limit").asInt(100),
            input.has("after") ? input.path("after").asText() : null));
  }

  @GetMapping("/retention/plans/{id}")
  public ResponseEntity<JsonNode> plan(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(retention.plan(principal(platform, project, request), id));
  }

  @PostMapping("/retention/apply")
  public ResponseEntity<JsonNode> apply(
      @PathVariable("project") String project, HttpServletRequest request) {
    var p = principal(platform, project, request);
    JsonNode input = body(request);
    fields(input, Set.of("planId", "digest"), Set.of("planId", "digest"));
    if (!input.path("planId").isTextual() || !input.path("digest").isTextual())
      throw ApiFailure.invalid();
    return response(
        retention.apply(
            p,
            header(request, "Idempotency-Key"),
            input.path("planId").asText(),
            input.path("digest").asText()));
  }
}
