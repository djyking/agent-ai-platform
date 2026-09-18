package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.CapabilityHttp.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{project}/knowledge")
public final class KnowledgeController {
  private final PlatformService platform;
  private final KnowledgeService knowledge;

  public KnowledgeController(PlatformService platform, KnowledgeService knowledge) {
    this.platform = platform;
    this.knowledge = knowledge;
  }

  @GetMapping("/collections")
  public ResponseEntity<JsonNode> list(
      @PathVariable("project") String project, HttpServletRequest request) {
    return response(knowledge.list(principal(platform, project, request)));
  }

  @GetMapping("/commands/{key}")
  public ResponseEntity<JsonNode> command(
      @PathVariable("project") String project,
      @PathVariable("key") String key,
      HttpServletRequest request) {
    return response(knowledge.command(principal(platform, project, request), key));
  }

  @GetMapping("/collections/{id}")
  public ResponseEntity<JsonNode> get(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(knowledge.get(principal(platform, project, request), id));
  }

  @PutMapping("/collections/{id}")
  public ResponseEntity<JsonNode> save(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(
        knowledge.saveCollection(
            principal(platform, project, request),
            id,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/collections/{id}/documents")
  public ResponseEntity<JsonNode> documents(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      HttpServletRequest request) {
    return response(knowledge.documents(principal(platform, project, request), id));
  }

  @PutMapping("/collections/{id}/documents/{document}")
  public ResponseEntity<JsonNode> document(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("document") String document,
      HttpServletRequest request) {
    return response(
        knowledge.saveDocument(
            principal(platform, project, request),
            id,
            document,
            header(request, "Idempotency-Key"),
            header(request, "If-Match"),
            body(request)));
  }

  @GetMapping("/collections/{id}/documents/{document}")
  public ResponseEntity<JsonNode> getDocument(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("document") String document,
      HttpServletRequest request) {
    return response(knowledge.document(principal(platform, project, request), id, document));
  }

  @GetMapping("/collections/{id}/documents/{document}/versions/{version}")
  public ResponseEntity<JsonNode> version(
      @PathVariable("project") String project,
      @PathVariable("id") String id,
      @PathVariable("document") String document,
      @PathVariable("version") long version,
      HttpServletRequest request) {
    return response(
        knowledge.documentVersion(principal(platform, project, request), id, document, version));
  }

  @PostMapping("/query")
  public ResponseEntity<JsonNode> query(
      @PathVariable("project") String project, HttpServletRequest request) {
    var p = principal(platform, project, request);
    JsonNode value = body(request);
    CapabilityInput.fields(value, Set.of("collections", "query"), Set.of("collections", "query"));
    if (!value.path("collections").isArray()) throw ApiFailure.invalid();
    List<String> ids = new ArrayList<>();
    for (JsonNode id : value.path("collections")) {
      if (!id.isTextual()) throw ApiFailure.invalid();
      ids.add(id.asText());
    }
    return response(knowledge.query(p, ids, CapabilityInput.text(value, "query", 8192)));
  }
}
