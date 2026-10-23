package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.web.bind.annotation.*;

@RestController
public final class TraceController {
  private final PlatformService service;
  private final PlatformTrace traces;

  public TraceController(PlatformService service, PlatformTrace traces) {
    this.service = service;
    this.traces = traces;
  }

  @GetMapping("/v1/projects/{project}/runs/{id}/trace")
  public JsonNode list(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @RequestParam(value = "after", defaultValue = "0") long after,
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      HttpServletRequest request) {
    for (String header : java.util.List.of("Authorization", "X-Harness-User-Token"))
      if (Collections.list(request.getHeaders(header)).size() != 1)
        throw new ApiFailure(401, "UNAUTHENTICATED");
    String authorization = request.getHeader("Authorization");
    if (!authorization.startsWith("Bearer ")) throw new ApiFailure(401, "UNAUTHENTICATED");
    var principal =
        service.authenticate(
            project, authorization.substring(7), request.getHeader("X-Harness-User-Token"));
    service.events(
        principal, id, null,
        1); // Exactly the same current ownership/permission checks as audit events.
    return traces.list(id, after, limit);
  }
}
