package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Explicit reviewed retention. No scheduled destructive job and no online archive-body endpoint.
 */
public final class RetentionService {
  private static final long DAY = 86_400_000L;
  private final PlatformRepository platform;
  private final OperationsRepository operations;
  private final RetentionArchive archive;

  public RetentionService(PlatformRepository platform, RetentionArchive archive) {
    this.platform = platform;
    this.operations = new OperationsRepository(platform);
    this.archive = Objects.requireNonNull(archive);
  }

  public static ObjectNode policy() {
    return Json.object()
        .put("payloadDays", 30)
        .put("traceDays", 14)
        .put("auditDays", 180)
        .put("backupDays", 30)
        .put("archiveDays", 30)
        .put("mode", "REVIEWED_PLAN_ONLY")
        .put("unresolved", "PINNED")
        .put("idempotency", "PERMANENT_TOMBSTONES")
        .put("configuration", "ACTIVE_RELEASES_AND_KNOWLEDGE_EXEMPT");
  }

  public JsonNode preview(IdentityProvider.Principal p, int limit) {
    return preview(p, limit, null);
  }

  public JsonNode preview(IdentityProvider.Principal p, int limit, String after) {
    OperationsService.require(p, "operations:admin");
    if (limit < 1 || limit > 100) throw ApiFailure.invalid();
    long cursorTime = 0;
    String cursorId = "";
    if (after != null) {
      try {
        String[] parts =
            new String(
                    Base64.getUrlDecoder().decode(after), java.nio.charset.StandardCharsets.UTF_8)
                .split(":", 2);
        if (parts.length != 2 || after.length() > 200) throw ApiFailure.invalid();
        cursorTime = Long.parseLong(parts[0]);
        cursorId = parts[1];
        ApiJson.uuid(cursorId);
        if (cursorTime < 0) throw ApiFailure.invalid();
      } catch (IllegalArgumentException e) {
        throw ApiFailure.invalid();
      }
    }
    final long fromTime = cursorTime;
    final String fromId = cursorId;
    return platform.transaction(
        c -> {
          platform.lockProject(c, p.project());
          long now = now(c);
          ObjectNode plan =
              Json.object()
                  .put("planId", UUID.randomUUID().toString())
                  .put("project", p.project())
                  .put("actor", p.assignment())
                  .put("createdAt", now)
                  .put("expiresAt", now + 900_000)
                  .put("archiveAvailable", archive.available());
          plan.set("policy", policy());
          ArrayNode candidates = plan.putArray("candidates");
          int pinned = 0;
          boolean hasMore = false;
          int scanned = 0, scanLimit = Math.min(1000, limit * 10);
          String nextCursor = null;
          List<ObjectNode> documents = studioDocuments(c, p.project());
          try (var s =
                  prepare(
                      c,
                      "SELECT r.run_id,r.revision,r.lease_until,r.snapshot_json,i.terminal_at FROM"
                          + " harness_runs r JOIN platform_runs p ON p.run_id=r.run_id JOIN"
                          + " harness_run_index i ON i.run_id=r.run_id LEFT JOIN"
                          + " operations_retained_runs a ON a.run_id=r.run_id WHERE p.project_id=?"
                          + " AND (i.terminal_at>? OR (i.terminal_at=? AND r.run_id>?)) AND"
                          + " i.terminal_at IS NOT NULL AND i.terminal_at<? AND ((a.run_id IS NULL"
                          + " AND i.terminal_at<?) OR (i.terminal_at<? AND a.audit_pruned=0) OR"
                          + " EXISTS(SELECT 1 FROM harness_platform_trace t WHERE t.run_id=r.run_id"
                          + " AND t.started_at<?)) ORDER BY i.terminal_at,r.run_id LIMIT ?",
                      p.project(),
                      fromTime,
                      fromTime,
                      fromId,
                      now - 14 * DAY,
                      now - 30 * DAY,
                      now - 180 * DAY,
                      now - 14 * DAY,
                      scanLimit + 1);
              var r = s.executeQuery()) {
            while (r.next()) {
              if (candidates.size() >= limit || scanned >= scanLimit) {
                hasMore = true;
                break;
              }
              scanned++;
              nextCursor =
                  Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(
                          (r.getLong(5) + ":" + r.getString(1))
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
              RunState run = Json.convert(Json.read(r.getString(4)), RunState.class);
              long terminalAt = r.getLong(5);
              if (!safe(run, r.getObject(3), now)
                  || hasUnresolvedPermit(c, run.id)
                  || linkedToUnresolvedExperiment(c, run.id, documents)) {
                pinned++;
                continue;
              }
              long traces = traceCount(c, run.id, now - 14 * DAY);
              boolean body = !run.payloadArchived && terminalAt < now - 30 * DAY;
              boolean pruneAudit = terminalAt < now - 180 * DAY;
              if (!body && traces == 0 && !pruneAudit) continue;
              candidates
                  .addObject()
                  .put("runId", run.id)
                  .put("revision", r.getLong(2))
                  .put("terminalAt", terminalAt)
                  .put("snapshotDigest", Json.hash(Json.read(r.getString(4))))
                  .put("traceCount", traces)
                  .put("pruneAudit", pruneAudit)
                  .put(
                      "action",
                      body ? "ARCHIVE_PAYLOAD" : pruneAudit ? "AUDIT_ONLY" : "TRACE_ONLY");
            }
          }
          long traceCount = 0;
          for (JsonNode candidate : candidates) traceCount += candidate.path("traceCount").asLong();
          plan.put("traceCount", traceCount).put("pinnedCount", pinned).put("hasMore", hasMore);
          plan.put("scannedCount", scanned);
          if (hasMore) plan.put("nextCursor", nextCursor);
          else plan.putNull("nextCursor");
          plan.set(
              "expiredArchives",
              expired(c, "operations_archives", "archive_id", "expires_at", p.project(), now, 100));
          plan.set(
              "expiredAudit",
              expired(
                  c,
                  "operations_retention_audit",
                  "audit_id",
                  "created_at",
                  p.project(),
                  now - 180 * DAY,
                  100));
          plan.set(
              "expiredInbox",
              expired(
                  c,
                  "operations_inbox",
                  "event_id",
                  "created_at",
                  p.project(),
                  now - 180 * DAY,
                  100));
          plan.put("digest", Json.hash(plan));
          execute(
              c,
              "INSERT INTO operations_retention_plans VALUES(?,?,?,?,?)",
              plan.path("planId").asText(),
              p.project(),
              p.assignment(),
              Json.write(plan),
              now);
          return publicPlan(plan);
        });
  }

  public JsonNode plan(IdentityProvider.Principal p, String id) {
    OperationsService.require(p, "operations:admin");
    ApiJson.uuid(id);
    return platform.transaction(c -> publicPlan(loadPlan(c, p, id)));
  }

  private static ObjectNode publicPlan(ObjectNode plan) {
    var out = plan.deepCopy();
    out.remove("actor");
    return out;
  }

  private ObjectNode loadPlan(Connection c, IdentityProvider.Principal p, String id)
      throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT plan_json FROM operations_retention_plans WHERE plan_id=?"
                    + " AND project_id=? AND actor_scope=?",
                id,
                p.project(),
                p.assignment());
        var r = s.executeQuery()) {
      if (!r.next()) throw ApiFailure.hidden();
      return (ObjectNode) Json.read(r.getString(1));
    }
  }

  public JsonNode apply(IdentityProvider.Principal p, String key, String id, String digest) {
    OperationsService.require(p, "operations:admin");
    ApiJson.uuid(id);
    if (digest == null || !digest.matches("[a-f0-9]{64}")) throw ApiFailure.invalid();
    String hash = OperationsRepository.commandHash(p, key),
        commandDigest = Json.hash(List.of("retention", id, digest));
    return platform.transaction(
        c -> {
          platform.lockProject(c, p.project());
          JsonNode previous = operations.receipt(c, hash, commandDigest);
          if (previous != null) return previous;
          ObjectNode plan = loadPlan(c, p, id);
          long now = now(c);
          if (!digest.equals(plan.path("digest").asText()))
            throw new ApiFailure(412, "RETENTION_PLAN_CHANGED");
          if (now >= plan.path("expiresAt").asLong())
            throw new ApiFailure(409, "RETENTION_PLAN_EXPIRED");
          int archived = 0, traces = 0, runAudit = 0;
          List<ObjectNode> documents = studioDocuments(c, p.project());
          // All rows are rechecked and all effects use the same transaction. Any stale
          // candidate rolls back the whole plan.
          for (JsonNode candidate : plan.path("candidates")) {
            String runId = candidate.path("runId").asText();
            try (var s =
                    prepare(
                        c,
                        "SELECT"
                            + " r.snapshot_json,r.revision,r.lease_until,i.terminal_at"
                            + " FROM harness_runs r JOIN platform_runs p ON"
                            + " p.run_id=r.run_id JOIN harness_run_index i"
                            + " ON i.run_id=r.run_id WHERE r.run_id=? AND"
                            + " p.project_id=? FOR UPDATE",
                        runId,
                        p.project());
                var r = s.executeQuery()) {
              if (!r.next()) throw new ApiFailure(412, "RETENTION_PLAN_CHANGED");
              JsonNode original = Json.read(r.getString(1));
              RunState run = Json.convert(original, RunState.class);
              if (r.getLong(2) != candidate.path("revision").asLong()
                  || !Json.hash(original).equals(candidate.path("snapshotDigest").asText())
                  || r.getLong(4) != candidate.path("terminalAt").asLong()
                  || !safe(run, r.getObject(3), now)
                  || hasUnresolvedPermit(c, runId)
                  || linkedToUnresolvedExperiment(c, runId, documents))
                throw new ApiFailure(412, "RETENTION_PLAN_CHANGED");
              if (candidate.path("action").asText().equals("ARCHIVE_PAYLOAD")) {
                if (run.payloadArchived || r.getLong(4) >= now - 30 * DAY)
                  throw new ApiFailure(412, "RETENTION_PLAN_CHANGED");
                archiveAndCompact(c, p, run, original, documents, now);
                archived++;
              }
              if (candidate.path("pruneAudit").asBoolean()) {
                if (!run.payloadArchived || r.getLong(4) >= now - 180 * DAY)
                  throw new ApiFailure(412, "RETENTION_PLAN_CHANGED");
                runAudit += execute(c, "DELETE FROM harness_run_events WHERE run_id=?", runId);
                runAudit += execute(c, "DELETE FROM platform_command_audit WHERE run_id=?", runId);
                execute(
                    c,
                    "UPDATE operations_retained_runs SET audit_pruned=1 WHERE" + " run_id=?",
                    runId);
              }
            }
            traces +=
                execute(
                    c,
                    "DELETE FROM harness_platform_trace WHERE run_id=? AND" + " started_at<?",
                    runId,
                    plan.path("createdAt").asLong() - 14 * DAY);
          }
          int deletedArchives =
              deleteReviewed(
                  c,
                  "operations_archives",
                  "archive_id",
                  "expires_at",
                  p.project(),
                  plan.path("expiredArchives"),
                  plan.path("createdAt").asLong());
          int deletedAudit =
              runAudit
                  + deleteReviewed(
                      c,
                      "operations_retention_audit",
                      "audit_id",
                      "created_at",
                      p.project(),
                      plan.path("expiredAudit"),
                      plan.path("createdAt").asLong() - 180 * DAY);
          deletedAudit +=
              deleteReviewed(
                  c,
                  "operations_inbox",
                  "event_id",
                  "created_at",
                  p.project(),
                  plan.path("expiredInbox"),
                  plan.path("createdAt").asLong() - 180 * DAY);
          var result =
              Json.object()
                  .put("planId", id)
                  .put("status", "APPLIED")
                  .put("archivedRuns", archived)
                  .put("deletedTraces", traces)
                  .put("deletedArchives", deletedArchives)
                  .put("deletedAuditRows", deletedAudit)
                  .put("appliedAt", now)
                  .put("archiveKeyId", archive.keyId());
          execute(
              c,
              "INSERT INTO operations_retention_audit VALUES(?,?,?,?)",
              UUID.randomUUID().toString(),
              p.project(),
              Json.write(result),
              now);
          operations.receipt(c, hash, commandDigest, result);
          return result;
        });
  }

  private static boolean safe(RunState run, Object lease, long now) {
    // Do not infer safety from status alone: terminal snapshots may still contain uncertain
    // calls.
    return TERMINAL.contains(run.status)
        && run.pending == null
        && run.approval == null
        && (lease == null || ((Number) lease).longValue() <= now);
  }

  private static boolean hasUnresolvedPermit(Connection c, String runId) throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT COUNT(*) FROM gov_permit WHERE run_id=? AND state IN"
                    + " ('RESERVED','DISPATCHED','UNKNOWN')",
                runId);
        var r = s.executeQuery()) {
      r.next();
      return r.getLong(1) != 0;
    }
  }

  private long traceCount(Connection c, String runId, long before) throws SQLException {
    try (var s =
            prepare(
                c,
                "SELECT COUNT(*) FROM harness_platform_trace WHERE run_id=? AND" + " started_at<?",
                runId,
                before);
        var r = s.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }

  private static ArrayNode expired(
      Connection c,
      String table,
      String key,
      String timestamp,
      String project,
      long before,
      int limit)
      throws SQLException {
    ArrayNode out = Json.MAPPER.createArrayNode();
    try (var s =
            prepare(
                c,
                "SELECT "
                    + key
                    + " FROM "
                    + table
                    + " WHERE project_id=? AND "
                    + timestamp
                    + "<? ORDER BY "
                    + timestamp
                    + ","
                    + key
                    + " LIMIT ?",
                project,
                before,
                limit);
        var r = s.executeQuery()) {
      while (r.next()) out.add(r.getString(1));
    }
    return out;
  }

  private static int deleteReviewed(
      Connection c,
      String table,
      String key,
      String timestamp,
      String project,
      JsonNode ids,
      long before)
      throws SQLException {
    int changed = 0;
    for (JsonNode id : ids)
      changed +=
          execute(
              c,
              "DELETE FROM "
                  + table
                  + " WHERE "
                  + key
                  + "=? AND project_id=? AND "
                  + timestamp
                  + "<?",
              id.asText(),
              project,
              before);
    return changed;
  }

  private List<ObjectNode> studioDocuments(Connection c, String project) throws SQLException {
    List<ObjectNode> result = new ArrayList<>();
    try (var s =
            prepare(
                c,
                "SELECT document_kind,document_id,document_json FROM"
                    + " studio_documents WHERE project_id=? AND document_kind IN"
                    + " ('TASK','EXPERIMENT') LIMIT 10001",
                project);
        var r = s.executeQuery()) {
      while (r.next()) {
        if (result.size() >= 10000)
          throw new ApiFailure(409, "RETENTION_STUDIO_SCOPE_REQUIRES_PARTITION");
        result.add(
            Json.object()
                .put("kind", r.getString(1))
                .put("id", r.getString(2))
                .set("body", Json.read(r.getString(3))));
      }
    }
    return result;
  }

  private boolean linkedToUnresolvedExperiment(Connection c, String id, List<ObjectNode> documents)
      throws SQLException {
    for (var document : documents)
      if (document.path("kind").asText().equals("EXPERIMENT")) {
        for (JsonNode row : document.path("body").path("rows")) {
          boolean linked = false;
          for (String side : List.of("candidate", "baseline"))
            linked |= id.equals(row.path(side).path("runId").asText());
          if (!linked) continue;
          for (String side : List.of("candidate", "baseline")) {
            String other = row.path(side).path("runId").asText();
            if (other.isBlank() || other.equals(id)) continue;
            try (var s =
                    prepare(
                        c,
                        "SELECT snapshot_json,lease_until FROM harness_runs" + " WHERE run_id=?",
                        other);
                var r = s.executeQuery()) {
              if (!r.next()
                  || hasUnresolvedPermit(c, other)
                  || !safe(
                      Json.convert(Json.read(r.getString(1)), RunState.class),
                      r.getObject(2),
                      now(c))) return true;
            }
          }
        }
      }
    return false;
  }

  private ArrayNode rows(Connection c, String sql, Object... args) throws SQLException {
    ArrayNode out = Json.MAPPER.createArrayNode();
    try (var s = prepare(c, sql, args);
        var r = s.executeQuery()) {
      ResultSetMetaData meta = r.getMetaData();
      while (r.next()) {
        if (out.size() >= 10000) throw new ApiFailure(409, "RETENTION_ARCHIVE_REQUIRES_PARTITION");
        ObjectNode row = out.addObject();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          String value = r.getString(i);
          if (value == null) row.putNull(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
          else row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
        }
      }
    }
    return out;
  }

  private void archiveAndCompact(
      Connection c,
      IdentityProvider.Principal p,
      RunState run,
      JsonNode original,
      List<ObjectNode> documents,
      long now)
      throws SQLException {
    String archiveId = UUID.randomUUID().toString();
    ObjectNode payload =
        Json.object()
            .put("runId", run.id)
            .put("project", p.project())
            .put("format", "RETENTION_ARCHIVE_V1");
    payload.set("snapshot", original);
    payload.set(
        "events",
        rows(c, "SELECT * FROM harness_run_events WHERE run_id=? ORDER BY event_sequence", run.id));
    payload.set("commands", rows(c, "SELECT * FROM platform_commands WHERE run_id=?", run.id));
    payload.set("audit", rows(c, "SELECT * FROM platform_command_audit WHERE run_id=?", run.id));
    payload.set("evidence", rows(c, "SELECT * FROM platform_evidence WHERE run_id=?", run.id));
    payload.set(
        "ownership", rows(c, "SELECT client_reference FROM platform_runs WHERE run_id=?", run.id));
    var studio = payload.putArray("studio");
    List<ObjectNode> replacements = new ArrayList<>();
    for (var document : documents) {
      var body = (ObjectNode) document.path("body");
      boolean affected = false;
      ObjectNode replacement = body.deepCopy();
      if (document.path("kind").asText().equals("TASK")
          && run.id.equals(body.path("runId").asText())) {
        replacement.remove("input");
        replacement.put("payloadArchived", true);
        affected = true;
      } else if (document.path("kind").asText().equals("EXPERIMENT")) {
        for (JsonNode row : replacement.path("rows")) {
          boolean linked = false;
          for (String side : List.of("candidate", "baseline"))
            linked |= run.id.equals(row.path(side).path("runId").asText());
          if (linked) {
            ((ObjectNode) row).remove(List.of("input", "expectedContains"));
            for (String side : List.of("candidate", "baseline")) {
              if (!row.has(side)) continue;
              ObjectNode reference = Json.object();
              for (String field :
                  List.of(
                      "id",
                      "runId",
                      "sessionId",
                      "applicationId",
                      "mode",
                      "snapshotId",
                      "createdAt"))
                if (row.path(side).has(field)) reference.set(field, row.path(side).get(field));
              ((ObjectNode) row).set(side, reference);
            }
            ((ObjectNode) row).put("payloadArchived", true);
            affected = true;
          }
        }
        if (affected) replacement.put("payloadArchived", true);
      }
      if (affected) {
        studio.add(document);
        replacements.add(
            Json.object()
                .put("kind", document.path("kind").asText())
                .put("id", document.path("id").asText())
                .set("body", replacement));
      }
    }
    // Historical Studio command responses are app snapshots. Only old responses are compacted;
    // live APPLICATION/SNAPSHOT/RELEASE configuration remains immutable and exempt from task
    // expiry.
    ArrayNode studioCommands =
        rows(
            c,
            "SELECT command_hash,response_json FROM studio_commands WHERE project_id=?"
                + " AND accepted_at<?",
            p.project(),
            now - 30 * DAY);
    payload.set("studioCommands", studioCommands);
    String encrypted = archive.encrypt(archiveId, payload);
    execute(
        c,
        "INSERT INTO operations_archives VALUES(?,?,?,?,?,?,?,?)",
        archiveId,
        p.project(),
        run.id,
        archive.keyId(),
        Json.hash(payload),
        encrypted,
        now,
        now + 30 * DAY);
    run.payloadArchived = true;
    run.definition =
        new ProgramDefinition(
            run.definition.id(),
            run.definition.version(),
            run.definition.program(),
            Json.object().put("payloadArchived", true));
    run.tools.clear();
    run.memory.removeAll();
    run.results.clear();
    run.receipts.clear();
    run.output = null;
    run.stopReason = "PAYLOAD_ARCHIVED";
    execute(c, "UPDATE harness_runs SET snapshot_json=? WHERE run_id=?", Json.write(run), run.id);
    execute(c, "INSERT INTO operations_retained_runs VALUES(?,?,?)", run.id, now, 0);
    // Preserve sequence/type/at and hashed external references, never arbitrary event
    // attributes.
    for (JsonNode row : payload.path("events")) {
      ObjectNode event = (ObjectNode) Json.read(row.path("event_json").asText());
      event.set("attributes", Json.object().put("payloadArchived", "true"));
      execute(
          c,
          "UPDATE harness_run_events SET event_json=? WHERE run_id=? AND" + " event_sequence=?",
          Json.write(event),
          run.id,
          row.path("event_sequence").asLong());
    }
    for (JsonNode row : payload.path("commands")) {
      JsonNode response = Json.read(row.path("response_json").asText());
      ObjectNode tombstone =
          Json.object()
              .put("payloadArchived", true)
              .put("statusUrl", "/v1/projects/" + p.project() + "/runs/" + run.id);
      if (response.has("acceptedAt")) tombstone.set("acceptedAt", response.get("acceptedAt"));
      tombstone
          .putObject("run")
          .put("id", run.id)
          .put("status", run.status.name())
          .put("revision", Long.toString(run.revision))
          .put("payloadArchived", true)
          .putObject("controls")
          .put("pauseRequested", run.pauseRequested)
          .put("cancelRequested", run.cancelRequested);
      execute(
          c,
          "UPDATE platform_commands SET response_json=? WHERE scope_hash=?",
          Json.write(tombstone),
          row.path("scope_hash").asText());
    }
    execute(c, "UPDATE platform_command_audit SET reason_text=NULL WHERE run_id=?", run.id);
    execute(c, "UPDATE platform_runs SET client_reference=NULL WHERE run_id=?", run.id);
    execute(
        c,
        "UPDATE platform_evidence SET result_json=? WHERE run_id=?",
        Json.write(Json.object().put("payloadArchived", true)),
        run.id);
    for (ObjectNode replacement : replacements) {
      execute(
          c,
          "UPDATE studio_documents SET document_json=? WHERE project_id=? AND"
              + " document_kind=? AND document_id=?",
          Json.write(replacement.path("body")),
          p.project(),
          replacement.path("kind").asText(),
          replacement.path("id").asText());
      for (ObjectNode document : documents)
        if (document.path("id").equals(replacement.path("id"))
            && document.path("kind").equals(replacement.path("kind")))
          document.set("body", replacement.path("body"));
    }
    for (JsonNode row : studioCommands) {
      JsonNode response = Json.read(row.path("response_json").asText());
      ObjectNode tombstone = Json.object().put("payloadArchived", true);
      for (String field :
          List.of(
              "id",
              "revision",
              "defaultVersion",
              "disabled",
              "lastTask",
              "lastExperiment",
              "updatedAt")) if (response.has(field)) tombstone.set(field, response.get(field));
      execute(
          c,
          "UPDATE studio_commands SET response_json=? WHERE command_hash=?",
          Json.write(tombstone),
          row.path("command_hash").asText());
    }
  }
}
