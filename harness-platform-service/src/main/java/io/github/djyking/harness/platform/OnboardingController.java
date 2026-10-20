package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.CapabilityHttp.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/onboarding")
public final class OnboardingController {
  private final PlatformService platform;
  private final OnboardingService onboarding;

  public OnboardingController(PlatformService platform, OnboardingService onboarding) {
    this.platform = platform;
    this.onboarding = onboarding;
  }

  @GetMapping
  public ResponseEntity<JsonNode> list(
      @PathVariable("project") String project,
      @RequestParam(value = "after", defaultValue = "") String after,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      HttpServletRequest request) {
    return response(onboarding.list(principal(platform, project, request), after, limit));
  }

  @GetMapping("/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(onboarding.get(principal(platform, project, request), id));
  }

  @GetMapping("/commands/{key}")
  public ResponseEntity<JsonNode> command(
      @PathVariable("project") String project,
      @PathVariable("key") String key,
      HttpServletRequest request) {
    return response(onboarding.command(principal(platform, project, request), key));
  }

  @PostMapping("/{id}")
  public ResponseEntity<JsonNode> create(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        onboarding.request(
            principal(platform, project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @PostMapping("/{id}/decision")
  public ResponseEntity<JsonNode> decide(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        onboarding.decide(
            principal(platform, project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @PostMapping("/{id}/lifecycle")
  public ResponseEntity<JsonNode> lifecycle(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        onboarding.lifecycle(
            principal(platform, project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @PostMapping("/{id}/verify")
  public ResponseEntity<JsonNode> verify(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    OnboardingService.fields(body(request), java.util.Set.of());
    return response(
        onboarding.verify(
            principal(platform, project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            header(request, "X-Harness-User-Token")));
  }
}
