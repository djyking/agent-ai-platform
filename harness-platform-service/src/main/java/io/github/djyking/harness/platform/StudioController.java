package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/studio")
public final class StudioController {
  private final PlatformService platform;
  private final StudioService studio;

  public StudioController(PlatformService platform, StudioService studio) {
    this.platform = platform;
    this.studio = studio;
  }

  private IdentityProvider.Principal principal(String project, HttpServletRequest request) {
    return CapabilityHttp.principal(platform, project, request);
  }

  private ResponseEntity<JsonNode> response(JsonNode body) {
    var out = ResponseEntity.ok().cacheControl(CacheControl.noStore());
    if (body.has("revision")) out.eTag(StudioService.etag(body));
    return out.body(body);
  }

  @GetMapping("/templates")
  public JsonNode templates(@PathVariable("project") String project, HttpServletRequest r) {
    return studio.templates(principal(project, r));
  }

  @GetMapping("/capabilities")
  public JsonNode capabilities(@PathVariable("project") String project, HttpServletRequest r) {
    return studio.capabilities(principal(project, r));
  }

  @GetMapping("/applications")
  public JsonNode applications(@PathVariable("project") String project, HttpServletRequest r) {
    return studio.applications(principal(project, r));
  }

  @GetMapping("/applications/{id}")
  public ResponseEntity<JsonNode> application(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest r) {
    return response(studio.application(principal(project, r), id));
  }

  @PutMapping("/applications/{id}")
  public ResponseEntity<JsonNode> save(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest r) {
    return response(
        studio.save(
            principal(project, r),
            id,
            CapabilityHttp.header(r, "Idempotency-Key"),
            CapabilityHttp.header(r, "If-Match"),
            CapabilityHttp.body(r)));
  }

  @PostMapping("/applications/{id}/{operation:preview|run|evaluate|publish|default|disable}")
  public ResponseEntity<JsonNode> change(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("operation") String operation,
      HttpServletRequest r) {
    var p = principal(project, r);
    String key = CapabilityHttp.header(r, "Idempotency-Key"),
        match = CapabilityHttp.header(r, "If-Match");
    JsonNode body = CapabilityHttp.body(r);
    return response(
        switch (operation) {
          case "preview", "run" ->
              studio.run(
                  p,
                  CapabilityHttp.header(r, "Authorization").substring(7),
                  CapabilityHttp.header(r, "X-Harness-User-Token"),
                  id,
                  operation,
                  key,
                  match,
                  body);
          case "evaluate" ->
              studio.evaluate(
                  p,
                  CapabilityHttp.header(r, "Authorization").substring(7),
                  CapabilityHttp.header(r, "X-Harness-User-Token"),
                  id,
                  key,
                  match,
                  body);
          case "publish" -> studio.publish(p, id, key, match, body);
          default -> studio.configure(p, id, operation, key, match, body);
        });
  }

  @GetMapping("/commands/{key}")
  public JsonNode command(
      @PathVariable("project") String project,
      @PathVariable("key") String key,
      HttpServletRequest r) {
    return studio.command(principal(project, r), key);
  }

  @GetMapping("/experiments/{id}")
  public JsonNode experiment(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest r) {
    return studio.experiment(principal(project, r), id);
  }

  @GetMapping("/tasks")
  public JsonNode tasks(
      @PathVariable("project") String project,
      @RequestParam(value = "applicationId", required = false) String applicationId,
      HttpServletRequest r) {
    return studio.tasks(principal(project, r), applicationId);
  }

  @GetMapping("/tasks/{id}")
  public JsonNode task(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest r) {
    return studio.task(principal(project, r), id);
  }

  @GetMapping("/tasks/{id}/artifacts/{artifactId}")
  public ResponseEntity<byte[]> artifact(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("artifactId") String artifactId,
      HttpServletRequest r) {
    var artifact = studio.artifact(principal(project, r), id, artifactId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(artifact.mediaType() + ";charset=UTF-8"))
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(artifact.name(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(artifact.content().getBytes(StandardCharsets.UTF_8));
  }
}
