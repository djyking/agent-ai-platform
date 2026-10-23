package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.CapabilityHttp.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/capabilities")
public final class CapabilityController {
  private final PlatformService platform;
  private final CapabilityService capabilities;

  public CapabilityController(PlatformService platform, CapabilityService capabilities) {
    this.platform = platform;
    this.capabilities = capabilities;
  }

  @GetMapping("/connections")
  public ResponseEntity<JsonNode> list(
      @PathVariable("project") String project, HttpServletRequest request) {
    return response(capabilities.list(principal(platform, project, request)));
  }

  @GetMapping("/commands/{key}")
  public ResponseEntity<JsonNode> command(
      @PathVariable("project") String project,
      @PathVariable("key") String key,
      HttpServletRequest request) {
    return response(capabilities.command(principal(platform, project, request), key));
  }

  @GetMapping("/connections/{kind}/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("kind") String kind,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(capabilities.get(principal(platform, project, request), kind, id));
  }

  @PutMapping("/connections/{kind}/{id}")
  public ResponseEntity<JsonNode> save(
      @PathVariable("project") String project,
      @PathVariable("kind") String kind,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        capabilities.save(
            principal(platform, project, request),
            kind,
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @PostMapping("/connections/{kind}/{id}/diagnose")
  public ResponseEntity<JsonNode> diagnose(
      @PathVariable("project") String project,
      @PathVariable("kind") String kind,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        capabilities.diagnose(
            principal(platform, project, request),
            kind,
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }
}
