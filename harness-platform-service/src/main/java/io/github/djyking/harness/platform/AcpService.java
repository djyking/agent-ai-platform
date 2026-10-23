package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ACP 0.2 text/JSON profile over the existing durable Run ledger, never another executor. */
final class AcpService {
  private final PlatformService runs;
  private final CatalogService catalog;

  AcpService(PlatformService runs, CatalogService catalog) {
    this.runs = runs;
    this.catalog = catalog;
  }

  JsonNode agents(IdentityProvider.Principal p, int limit, int offset) {
    if (limit < 1 || limit > 1000 || offset < 0 || offset > 10000) throw ApiFailure.invalid();
    var out = Json.object();
    var items = out.putArray("agents");
    var releases = new ArrayList<JsonNode>();
    catalog.releases(p).path("items").forEach(releases::add);
    releases.sort(Comparator.comparing(r -> r.path("releaseRef").path("releaseId").asText()));
    releases.stream().skip(offset).limit(limit).map(AcpService::manifest).forEach(items::add);
    return out;
  }

  JsonNode agent(IdentityProvider.Principal p, String name) {
    return manifest(release(p, name));
  }

  private JsonNode release(IdentityProvider.Principal p, String name) {
    ApiJson.uuid(name);
    for (var release : catalog.releases(p).path("items"))
      if (release.path("releaseRef").path("releaseId").asText().equals(name)) return release;
    throw ApiFailure.hidden();
  }

  private static JsonNode manifest(JsonNode release) {
    var out =
        Json.object()
            .put("name", release.path("releaseRef").path("releaseId").asText())
            .put("description", release.path("name").asText());
    out.putArray("input_content_types").add("application/json").add("text/plain");
    out.putArray("output_content_types").add("application/json").add("text/plain");
    var metadata = out.putObject("metadata").putObject("agent_platform");
    metadata.put("protocol_version", "0.2.0");
    metadata.put("session_support", false);
    metadata.put("idempotency_key_required", true);
    metadata.put("agent_id", release.path("agentId").asText());
    metadata.set("release_ref", release.path("releaseRef"));
    metadata.set("input_schema", release.path("inputSchema"));
    return out;
  }

  static void fields(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject()) throw ApiFailure.invalid();
    input
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k)) throw ApiFailure.invalid();
            });
  }

  static String mode(JsonNode input) {
    String mode = input.path("mode").asText("sync");
    if (!Set.of("sync", "async", "stream").contains(mode)) throw ApiFailure.invalid();
    return mode;
  }

  String create(
      IdentityProvider.Principal p, String credential, String token, String key, JsonNode request) {
    fields(request, Set.of("agent_name", "input", "mode", "session_id", "session"));
    mode(request);
    if (request.hasNonNull("session") || request.hasNonNull("session_id"))
      throw new ApiFailure(422, "ACP_SESSION_NOT_SUPPORTED");
    if (!request.path("agent_name").isTextual()) throw ApiFailure.invalid();
    // Reconstruct the exact original command from immutable history before the Run service
    // checks its receipt. Discovery deliberately hides retired releases; using it here would
    // prevent recovering an accepted write whose response was lost before retirement.
    var release = catalog.immutableProtocolBinding(p, request.path("agent_name").asText());
    var messages = request.path("input");
    if (!messages.isArray() || messages.size() != 1)
      throw new ApiFailure(422, "ACP_ONE_MESSAGE_REQUIRED");
    var part = part(messages.get(0));
    JsonNode inputs;
    if (part.path("content_type").asText("text/plain").equals("application/json")) {
      inputs = json(part.path("content").asText());
      if (!inputs.isObject()) throw ApiFailure.invalid();
    } else {
      var properties = release.inputSchema().path("properties");
      if (properties.size() != 1) throw new ApiFailure(422, "ACP_JSON_INPUT_REQUIRED");
      String field = properties.fieldNames().next();
      if (!properties.path(field).path("type").asText().equals("string"))
        throw new ApiFailure(422, "ACP_JSON_INPUT_REQUIRED");
      inputs = Json.object().put(field, part.path("content").asText());
    }
    var body = Json.object();
    body.set("releaseRef", release.reference());
    body.set("inputs", inputs);
    return runs.create(p, credential, token, key, body, true).path("run").path("id").asText();
  }

  static JsonNode part(JsonNode message) {
    fields(message, Set.of("role", "parts", "created_at", "completed_at"));
    if (!message.path("role").asText("user").equals("user"))
      throw new ApiFailure(422, "ACP_USER_MESSAGE_REQUIRED");
    JsonNode parts = message.path("parts");
    if (!parts.isArray() || parts.size() != 1) throw new ApiFailure(422, "ACP_ONE_PART_REQUIRED");
    JsonNode part = parts.get(0);
    fields(
        part,
        Set.of("name", "content", "content_type", "content_encoding", "content_url", "metadata"));
    if (!part.path("content").isTextual()
        || part.path("content").asText().length() > 50000
        || part.hasNonNull("content_url")
        || part.hasNonNull("metadata")
        || !part.path("content_encoding").asText("plain").equals("plain")
        || !Set.of("text/plain", "application/json")
            .contains(part.path("content_type").asText("text/plain")))
      throw new ApiFailure(422, "ACP_UNSUPPORTED_MESSAGE_PART");
    return part;
  }

  private static JsonNode json(String value) {
    return ApiJson.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
  }

  JsonNode get(IdentityProvider.Principal p, String id) {
    var view = runs.get(p, id);
    JsonNode original = view.body();
    String state = original.path("status").asText();
    String status =
        switch (state) {
          case "QUEUED" -> "created";
          case "RUNNING" ->
              original.path("controls").path("cancelRequested").asBoolean()
                  ? "cancelling"
                  : "in-progress";
          case "WAITING_APPROVAL", "WAITING_INPUT", "PAUSED", "NEEDS_ATTENTION" -> "awaiting";
          case "COMPLETED" -> "completed";
          case "CANCELLED" -> "cancelled";
          default -> "failed";
        };
    var result =
        Json.object()
            .put("run_id", id)
            .put("agent_name", original.path("releaseRef").path("releaseId").asText())
            .put("status", status)
            .put("created_at", original.path("createdAt").asText());
    var output = result.putArray("output");
    if (original.path("output").path("visibility").asText().equals("AVAILABLE")) {
      JsonNode value = original.path("output").path("value");
      output.add(
          message(
              "agent",
              value.isTextual() ? "text/plain" : "application/json",
              value.isTextual() ? value.asText() : Json.write(value)));
    }
    if (status.equals("failed")) result.set("error", error(409, state));
    if (status.equals("awaiting")) {
      var detail = Json.object().put("platform_status", state);
      if (state.equals("PAUSED")) {
        detail.put("operation", "resume").put("etag", view.etag());
      } else if (state.equals("NEEDS_ATTENTION")) {
        detail.put("operation", "external-reconciliation-required");
      } else {
        detail.put("operation", "review-in-platform");
        try {
          var approval = runs.approval(p, id);
          detail.put("operation", "approval").put("etag", approval.etag());
          detail.set("approval", approval.body());
        } catch (ApiFailure denied) {
          if (denied.status != 403 && denied.status != 404) throw denied;
        }
      }
      result.set(
          "await_request",
          Json.object()
              .put("type", "message")
              .set("message", message("agent", "application/json", Json.write(detail))));
    }
    return result;
  }

  void cancel(IdentityProvider.Principal p, String id, String key, String etag) {
    // The caller's explicit cancellation intent is safe to use with a freshly read revision.
    var view = runs.get(p, id);
    runs.control(p, id, "cancel", key, etag == null ? view.etag() : etag, Json.object());
  }

  void resume(IdentityProvider.Principal p, String id, String key, JsonNode request) {
    // ACP resume returns a Run representation. Reviewers who cannot read that Run must use
    // the platform approval interface; reject before recording any approval or control.
    runs.get(p, id);
    fields(request, Set.of("run_id", "await_resume", "mode"));
    mode(request);
    // The frozen OpenAPI lists run_id, while its official Python client only sends the path id.
    if (request.hasNonNull("run_id") && !request.path("run_id").asText().equals(id))
      throw ApiFailure.invalid();
    JsonNode resume = request.path("await_resume");
    fields(resume, Set.of("type", "message"));
    if (!resume.path("type").asText().equals("message")) throw ApiFailure.invalid();
    JsonNode part = part(resume.path("message"));
    if (!part.path("content_type").asText().equals("application/json")) throw ApiFailure.invalid();
    JsonNode decision = json(part.path("content").asText());
    fields(decision, Set.of("operation", "etag", "approvalId", "decision"));
    if (!decision.path("etag").isTextual()) throw ApiFailure.invalid();
    // Approval identifiers, digest and ETag come from the reviewed await request. Never
    // auto-approve.
    if (decision.path("operation").asText().equals("approval")) {
      runs.decide(
          p,
          id,
          decision.path("approvalId").asText(),
          key,
          decision.path("etag").asText(),
          decision.path("decision"));
    } else if (decision.path("operation").asText().equals("resume")) {
      if (decision.has("approvalId") || decision.has("decision")) throw ApiFailure.invalid();
      runs.control(p, id, "resume", key, decision.path("etag").asText(), Json.object());
    } else throw new ApiFailure(409, "ACP_RECONCILIATION_REQUIRES_EVIDENCE");
  }

  JsonNode events(IdentityProvider.Principal p, String id, String cursor) {
    var events = runs.events(p, id, cursor, 100);
    var result = Json.object();
    var items = result.putArray("events");
    for (var event : events.path("items"))
      items.add(Json.object().put("type", "generic").set("generic", event));
    var run = get(p, id);
    items.add(event(run));
    result.set("next_cursor", events.path("nextCursor"));
    return result;
  }

  static JsonNode event(JsonNode run) {
    String status = run.path("status").asText();
    if (status.equals("cancelling")) status = "in-progress";
    return Json.object().put("type", "run." + status).set("run", run);
  }

  static JsonNode message(String role, String type, String content) {
    var message = Json.object().put("role", role);
    message.putArray("parts").add(Json.object().put("content_type", type).put("content", content));
    return message;
  }

  static boolean settled(JsonNode run) {
    return Set.of("completed", "cancelled", "failed", "awaiting")
        .contains(run.path("status").asText());
  }

  static ObjectNode error(int status, String code) {
    return Json.object()
        .put("code", status == 404 ? "not_found" : status >= 500 ? "server_error" : "invalid_input")
        .put("message", code);
  }
}
