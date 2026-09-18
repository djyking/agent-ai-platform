package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** HTTP-independent application layer. Public methods require a freshly authenticated principal. */
public final class PlatformService {
  public record View(JsonNode body, String etag) {}

  private final Deployment config;
  private final JdbcRunStore store;
  private final PlatformRepository repository;
  private final Harness harness;
  private final RuntimeAccess access;
  private final IdentityProvider identities;
  private final ProtectedOutputPolicy outputPolicy;
  private final SignedTokens tokens;
  private final Clock clock;
  private CatalogService catalog;
  private StudioService studio;

  public void studio(StudioService studio) {
    this.studio = Objects.requireNonNull(studio);
  }

  public void catalog(CatalogService catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  public PlatformService(
      Deployment config,
      JdbcRunStore store,
      PlatformRepository repository,
      Harness harness,
      RuntimeAccess access,
      IdentityProvider identities,
      SignedTokens tokens,
      Clock clock) {
    this(
        config,
        store,
        repository,
        harness,
        access,
        identities,
        tokens,
        clock,
        ProtectedOutputPolicy.legacyIdentity(identities));
  }

  public PlatformService(
      Deployment config,
      JdbcRunStore store,
      PlatformRepository repository,
      Harness harness,
      RuntimeAccess access,
      IdentityProvider identities,
      SignedTokens tokens,
      Clock clock,
      ProtectedOutputPolicy outputPolicy) {
    this.config = config;
    this.store = store;
    this.repository = repository;
    this.harness = harness;
    this.access = access;
    this.identities = identities;
    this.outputPolicy = Objects.requireNonNull(outputPolicy);
    this.tokens = tokens;
    this.clock = clock;
  }

  public IdentityProvider.Principal authenticate(
      String project, String appCredential, String userToken) {
    ApiJson.identifier(project);
    if (appCredential == null
        || appCredential.isBlank()
        || userToken == null
        || userToken.isBlank()
        || appCredential.length() > 8192
        || userToken.length() > 8192) throw new ApiFailure(401, "UNAUTHENTICATED");
    var p = identities.authenticate(project, appCredential, userToken);
    if (!p.project().equals(project)
        || !config.project(project).applications().contains(p.application()))
      throw ApiFailure.hidden();
    return p;
  }

  private void require(IdentityProvider.Principal p, String permission) {
    if (!p.permits(permission)) throw ApiFailure.denied();
  }

  private void owner(IdentityProvider.Principal p, Owned owned) {
    if (!owned.project().equals(p.project())
        || (!p.permits("runs:admin")
            && !(owned.application().equals(p.application())
                && owned.subject().equals(p.subject())))) throw ApiFailure.hidden();
  }

  private void assigned(IdentityProvider.Principal p, Owned owned) {
    if (!owned.project().equals(p.project())
        || !owned.release().approvers().contains(p.assignment())) throw ApiFailure.hidden();
  }

  private Owned visible(IdentityProvider.Principal p, String id, String permission) {
    ApiJson.uuid(id);
    Owned owned = repository.owned(id);
    owner(p, owned);
    require(p, permission);
    return owned;
  }

  private String commandHash(IdentityProvider.Principal p, String route, String key) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
      throw ApiFailure.invalid();
    return Json.hash(List.of(p.project(), p.application(), p.subject(), "POST", route, key));
  }

  private String digest(JsonNode body) {
    return Json.hash(body);
  }

  private JsonNode replay(Command previous, String digest) {
    if (!previous.digest().equals(digest)) throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
    return previous.response();
  }

  public JsonNode create(
      IdentityProvider.Principal p,
      String appCredential,
      String userToken,
      String key,
      JsonNode body) {
    require(p, "runs:create");
    ApiJson.validate("CreateRunRequest", body);
    String hash = commandHash(p, "/runs", key), digest = digest(body);
    Command previous = repository.transaction(c -> repository.command(c, hash));
    if (previous != null) {
      owner(p, repository.owned(previous.runId()));
      return replay(previous, digest);
    }
    JsonNode ref = body.path("releaseRef");
    Deployment.Release release =
        catalog == null
            ? config.release(p.project(), ref.path("releaseId").asText())
            : catalog.releaseForRun(p.project(), ref.path("releaseId").asText());
    if (!release.agentId().equals(ref.path("agentId").asText()))
      throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
    if (!release.digest().equals(ref.path("digest").asText()))
      throw new ApiFailure(409, "RELEASE_DIGEST_MISMATCH");
    if (config.disabled(release)) throw new ApiFailure(404, "RELEASE_UNAVAILABLE");
    release.validateInputs(body.path("inputs"));
    for (String permission : release.executionPermissions()) require(p, permission);
    Deployment.Limits limits = release.limits().lower(body.path("limits"));
    String id = UUID.randomUUID().toString();
    // Identity I/O precedes the SQL transaction. A concurrent losing delegation expires unused.
    Instant delegationDeadline =
        clock
            .instant()
            .plusSeconds(limits.lifetimeSeconds())
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    String delegation = identities.delegate(p, appCredential, userToken, id, delegationDeadline);
    Set<String> capabilities = new HashSet<>(release.executionPermissions());
    capabilities.add("run:create");
    Actor actor = new Actor("run:" + id, p.project(), capabilities);
    return repository.transaction(
        c -> {
          repository.lockProject(c, p.project());
          Command race = repository.command(c, hash);
          if (race != null) return replay(race, digest);
          if (catalog != null) catalog.requireNewRun(c, p.project(), release.releaseId());
          if (studio != null) studio.requireNewRun(c, p, release.releaseId());
          repository.admit(c, config.project(p.project()), p.application(), limits.maxTokens());
          Duration remaining =
              Duration.between(clock.instant(), delegationDeadline).minusMillis(50);
          if (remaining.isNegative() || remaining.isZero())
            throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
          RunState run =
              access.command(
                  actor,
                  capabilities,
                  () ->
                      harness.startWithId(
                          id,
                          release.definition(body.path("inputs")),
                          actor,
                          release.toolKeys(),
                          limits.budget(),
                          remaining,
                          hash));
          Owned owned =
              new Owned(
                  run.id,
                  p.project(),
                  p.application(),
                  p.subject(),
                  delegation,
                  release,
                  limits,
                  body.has("clientReference") ? body.get("clientReference").asText() : null,
                  run.createdAt.toEpochMilli());
          repository.insert(c, owned);
          JsonNode response = accepted(run);
          repository.command(c, hash, digest, owned, response, p.assignment(), "create", null);
          return response;
        });
  }

  private JsonNode accepted(RunState run) {
    ObjectNode checkpoint =
        Json.object()
            .put("id", run.id)
            .put("status", run.status.name())
            .put("revision", Long.toString(run.revision));
    checkpoint.set("controls", controls(run));
    ObjectNode result =
        Json.object()
            .put("acceptedAt", clock.instant().toString())
            .put("statusUrl", "/v1/projects/" + run.actor.project() + "/runs/" + run.id);
    result.set("run", checkpoint);
    return result;
  }

  private JsonNode controls(RunState run) {
    return Json.object()
        .put("pauseRequested", run.pauseRequested)
        .put("cancelRequested", run.cancelRequested);
  }

  public View get(IdentityProvider.Principal p, String id) {
    Owned owned = visible(p, id, "runs:read");
    RunState run = store.get(id);
    JsonNode body = runView(p, owned, run, protectedOutputAllowed(p, owned, run));
    return new View(body, etag(p, run, "run", body));
  }

  private ObjectNode summary(Owned owned, RunState run) {
    ObjectNode out =
        Json.object()
            .put("id", run.id)
            .put("status", run.status.name())
            .put("revision", Long.toString(run.revision))
            .put("createdAt", run.createdAt.toString())
            .put("deadline", run.deadline.toString());
    out.set("releaseRef", owned.release().reference());
    if (owned.clientReference() != null) out.put("clientReference", owned.clientReference());
    return out;
  }

  private boolean protectedOutputAllowed(IdentityProvider.Principal p, Owned owned, RunState run) {
    if (run.output == null
        || owned.release().publicOutput()
        || !p.permits("runs:output:read")
        || !p.project().equals(owned.project())
        || !p.application().equals(owned.application())
        || !p.subject().equals(owned.subject())) return false;
    try {
      return outputPolicy.canRead(p, owned, run.output);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private JsonNode runView(
      IdentityProvider.Principal p, Owned owned, RunState run, boolean protectedAllowed) {
    ObjectNode out = summary(owned, run);
    out.put("projectId", owned.project());
    out.set("controls", controls(run));
    out.set(
        "usage",
        Json.object()
            .put("chargedTokens", run.chargedTokens)
            .put("modelCalls", run.modelCalls)
            .put("toolCalls", run.toolCalls)
            .put("steps", run.steps)
            .put("accounting", "CONSERVATIVE_LEDGER"));
    out.set("limits", Json.tree(owned.limits()));
    ObjectNode output = Json.object().put("visibility", "OMITTED");
    // Public/synthetic output is an explicit publisher assertion, not a client-controlled flag.
    // Protected retrieval requires current ACL on every citation and the exact original owner.
    if (run.output != null
        && (owned.release().publicOutput() || protectedAllowed)
        && p.permits("runs:output:read")) {
      if (Json.write(run.output).getBytes(StandardCharsets.UTF_8).length <= 65536
          && SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(owned.release().outputSchema())
              .validate(run.output)
              .isEmpty()) {
        output.put("visibility", "AVAILABLE");
        output.set("value", run.output.deepCopy());
      }
    }
    out.set("output", output);
    if (run.status == RunStatus.NEEDS_ATTENTION) {
      ObjectNode attention =
          Json.object()
              .put(
                  "code",
                  run.pending != null && run.pending.phase == InvocationPhase.UNKNOWN
                      ? "OUTCOME_UNKNOWN"
                      : "OTHER_REQUIRES_OPERATOR");
      if (run.pending != null && Set.of("MODEL", "TOOL").contains(run.pending.kind)) {
        attention.put("invocationKind", run.pending.kind);
        attention.put("invocationRef", invocationRef(run.id, run.pending.id));
      }
      out.set("attention", attention);
    }
    return out;
  }

  public JsonNode list(IdentityProvider.Principal p, String cursor, int limit, String status) {
    require(p, "runs:list");
    if (limit < 1 || limit > 100) throw ApiFailure.invalid();
    if (status != null)
      try {
        RunStatus.valueOf(status);
      } catch (IllegalArgumentException ex) {
        throw ApiFailure.invalid();
      }
    long watermark = clock.millis(), last = Long.MAX_VALUE;
    String lastId = "~";
    if (cursor != null) {
      JsonNode token = cursor(p, cursor, "list");
      if (!token.path("filter").asText().equals(Objects.toString(status, "")))
        throw new ApiFailure(400, "CURSOR_INVALID");
      watermark = token.path("watermark").asLong();
      last = token.path("time").asLong();
      lastId = token.path("id").asText();
    }
    List<Owned> rows = repository.list(p, status, watermark, last, lastId, limit + 1);
    var items = Json.MAPPER.createArrayNode();
    for (Owned row : rows.stream().limit(limit).toList())
      items.add(summary(row, store.get(row.runId())));
    ObjectNode result = Json.object();
    result.set("items", items);
    result.putNull("nextCursor");
    if (rows.size() > limit) {
      Owned end = rows.get(limit - 1);
      ObjectNode token =
          cursorBody(p, "list")
              .put("watermark", watermark)
              .put("time", end.createdAt())
              .put("id", end.runId())
              .put("filter", Objects.toString(status, ""));
      result.put("nextCursor", tokens.sign(token));
    }
    return result;
  }

  public JsonNode events(IdentityProvider.Principal p, String id, String cursor, int limit) {
    visible(p, id, "runs:events:read");
    if (limit < 1 || limit > 200) throw ApiFailure.invalid();
    long after = 0;
    if (cursor != null) {
      JsonNode token = cursor(p, cursor, "events");
      if (!token.path("run").asText().equals(id)) throw new ApiFailure(400, "CURSOR_INVALID");
      after = token.path("after").asLong();
    }
    long first = repository.firstEvent(id);
    if (first > after + 1) throw new ApiFailure(410, "CURSOR_EXPIRED");
    List<StoredEvent> rows = store.events(id, after, limit + 1);
    var items = Json.MAPPER.createArrayNode();
    for (StoredEvent row : rows.stream().limit(limit).toList()) {
      after = row.sequence();
      String type = eventType(row.event());
      if (type == null) continue;
      ObjectNode item =
          Json.object()
              .put("sequence", Long.toString(row.sequence()))
              .put("type", type)
              .put("at", row.event().at().toString());
      if (row.event().invocationId() != null && !row.event().invocationId().isBlank())
        item.put("invocationRef", invocationRef(id, row.event().invocationId()));
      items.add(item);
    }
    ObjectNode out =
        Json.object()
            .put(
                "nextCursor",
                tokens.sign(cursorBody(p, "events").put("run", id).put("after", after)))
            .put("hasMore", rows.size() > limit);
    out.set("items", items);
    return out;
  }

  private String eventType(RunEvent event) {
    return switch (event.type()) {
      case "RUN_CREATED" -> "RUN_CREATED";
      case "PAUSE_REQUESTED", "CANCEL_REQUESTED", "RUN_RESUMED" -> "CONTROL_ACCEPTED";
      case "APPROVAL_REQUESTED", "INPUT_REQUESTED" -> "APPROVAL_REQUESTED";
      case "APPROVAL_GRANTED", "APPROVAL_REJECTED" -> "APPROVAL_DECIDED";
      case "INVOCATION_STARTED", "ATTEMPT_STARTED", "TOOL_STARTED", "MODEL_STARTED" ->
          "INVOCATION_STARTED";
      case "TOOL_COMPLETED", "MODEL_COMPLETED" -> "INVOCATION_FINISHED";
      case "INVOCATION_UNKNOWN" -> "INVOCATION_UNKNOWN";
      case "RUN_NEEDS_ATTENTION" ->
          Objects.toString(event.attributes().get("reason"), "").startsWith("OUTCOME_UNKNOWN")
              ? "INVOCATION_UNKNOWN"
              : "RUN_STATUS_CHANGED";
      case "TOOL_RECONCILED" -> "TOOL_RECONCILED";
      case "RUN_COMPLETED",
          "RUN_FAILED",
          "RUN_EXPIRED",
          "RUN_CANCELLED",
          "RUN_PAUSED",
          "RUN_STOPPED",
          "RUN_BUDGET_EXCEEDED" ->
          "RUN_STATUS_CHANGED";
      default -> null;
    };
  }

  private ObjectNode cursorBody(IdentityProvider.Principal p, String kind) {
    return Json.object()
        .put("scope", p.scope())
        .put("kind", kind)
        .put("expires", clock.millis() + 3600000);
  }

  private JsonNode cursor(IdentityProvider.Principal p, String value, String kind) {
    JsonNode token = tokens.verify(value, "CURSOR_INVALID");
    if (!token.path("scope").asText().equals(p.scope())
        || !token.path("kind").asText().equals(kind)) throw new ApiFailure(400, "CURSOR_INVALID");
    if (token.path("expires").asLong() <= clock.millis())
      throw new ApiFailure(410, "CURSOR_EXPIRED");
    return token;
  }

  public View approval(IdentityProvider.Principal p, String id) {
    ApiJson.uuid(id);
    Owned owned = repository.owned(id);
    assigned(p, owned);
    require(p, "approvals:read");
    RunState run = store.get(id);
    JsonNode body = approvalView(p, owned, run);
    return new View(body, etag(p, run, "approval", body));
  }

  private String approvalId(RunState run) {
    return UUID.nameUUIDFromBytes(
            (run.id + "|" + run.approval.invocationId() + "|" + run.approval.digest())
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private JsonNode approvalView(IdentityProvider.Principal p, Owned owned, RunState run) {
    if (run.approval == null || run.pending == null) throw ApiFailure.hidden();
    boolean human = "HUMAN".equals(run.pending.kind);
    boolean complete = p.permits("approvals:review");
    var fields = Json.MAPPER.createArrayNode();
    if (!human && run.pending.arguments != null) {
      Iterator<String> names = run.pending.arguments.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        boolean show = complete && owned.release().reviewFields().contains(name);
        String value = show ? Json.write(run.pending.arguments.get(name)) : "[redacted]";
        if (value.length() > 2000) {
          show = false;
          value = "[redacted]";
        }
        if (!show) complete = false;
        fields.add(Json.object().put("name", name).put("value", value).put("masked", !show));
      }
      if (fields.size() > 64) {
        fields.removeAll();
        complete = false;
      }
    }
    String status = run.approval.status();
    if (status.equals("PENDING") && !run.approval.expiresAt().isAfter(clock.instant()))
      status = "EXPIRED";
    String summary = "Review the exact published tool arguments";
    if (human) {
      if (complete
          && run.pending.prompt != null
          && !run.pending.prompt.isBlank()
          && run.pending.prompt.length() <= 2000) summary = run.pending.prompt;
      else {
        summary = "Human input prompt is unavailable to this reviewer";
        complete = false;
      }
    }
    ObjectNode out =
        Json.object()
            .put("id", approvalId(run))
            .put("runId", run.id)
            .put("runRevision", Long.toString(run.revision))
            .put("kind", human ? "HUMAN_INPUT" : "TOOL")
            .put("status", status)
            .put("digest", "sha256:" + run.approval.digest())
            .put("expiresAt", run.approval.expiresAt().toString())
            .put("reviewComplete", complete)
            .put("summary", summary)
            .put("inputRequired", human);
    out.set("fields", fields);
    return out;
  }

  public View unknown(IdentityProvider.Principal p, String id) {
    visible(p, id, "runs:reconcile:read");
    RunState run = store.get(id);
    JsonNode body = unknownView(run);
    return new View(body, etag(p, run, "unknown", body));
  }

  private JsonNode unknownView(RunState run) {
    if (run.pending == null || run.pending.phase != InvocationPhase.UNKNOWN)
      throw ApiFailure.hidden();
    return Json.object()
        .put("runId", run.id)
        .put("runRevision", Long.toString(run.revision))
        .put("invocationRef", invocationRef(run.id, run.pending.id))
        .put("kind", run.pending.kind)
        .put("invocationDigest", invocationDigest(run))
        .put("reconcilable", "TOOL".equals(run.pending.kind))
        .put("cancelRequested", run.cancelRequested)
        .put("summary", "Outcome requires independent evidence; do not repeat the operation");
  }

  private String invocationRef(String runId, String localId) {
    return "inv_" + Json.hash(List.of(runId, localId));
  }

  private String etag(
      IdentityProvider.Principal p, RunState run, String kind, JsonNode projection) {
    return "\"r1."
        + tokens.sign(
            Json.object()
                .put("run", run.id)
                .put("project", p.project())
                .put("revision", Long.toString(run.revision))
                .put("scope", p.scope())
                .put("kind", kind)
                .put("projection", Json.hash(projection)))
        + "\"";
  }

  private long expected(
      IdentityProvider.Principal p,
      Owned owned,
      RunState run,
      String supplied,
      String requiredView,
      boolean protectedAllowed) {
    if (supplied == null) throw new ApiFailure(428, "PRECONDITION_REQUIRED");
    if (!supplied.matches("\"r1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\"")) throw ApiFailure.invalid();
    JsonNode token =
        tokens.verify(supplied.substring(4, supplied.length() - 1), "PRECONDITION_FAILED");
    String kind = token.path("kind").asText();
    if (requiredView != null && !requiredView.equals(kind))
      throw new ApiFailure(412, "PRECONDITION_FAILED");
    if (!token.path("run").asText().equals(run.id)
        || !token.path("project").asText().equals(p.project())
        || !token.path("scope").asText().equals(p.scope())
        || !token.path("revision").asText().equals(Long.toString(run.revision)))
      throw new ApiFailure(412, "PRECONDITION_FAILED");
    JsonNode projection;
    switch (kind) {
      case "run" -> {
        require(p, "runs:read");
        projection = runView(p, owned, run, protectedAllowed);
      }
      case "approval" -> {
        assigned(p, owned);
        require(p, "approvals:read");
        projection = approvalView(p, owned, run);
      }
      case "unknown" -> {
        require(p, "runs:reconcile:read");
        projection = unknownView(run);
      }
      default -> throw new ApiFailure(412, "PRECONDITION_FAILED");
    }
    if (!token.path("projection").asText().equals(Json.hash(projection)))
      throw new ApiFailure(412, "PRECONDITION_FAILED");
    return run.revision;
  }

  public JsonNode control(
      IdentityProvider.Principal p,
      String id,
      String operation,
      String key,
      String ifMatch,
      JsonNode body) {
    if (!Set.of("pause", "cancel", "resume").contains(operation)) throw ApiFailure.hidden();
    return command(p, id, operation, null, key, ifMatch, body);
  }

  public JsonNode decide(
      IdentityProvider.Principal p,
      String id,
      String approval,
      String key,
      String ifMatch,
      JsonNode body) {
    ApiJson.uuid(approval);
    return command(p, id, "decision", approval, key, ifMatch, body);
  }

  public JsonNode reconcile(
      IdentityProvider.Principal p, String id, String key, String ifMatch, JsonNode body) {
    return command(p, id, "reconcile", null, key, ifMatch, body);
  }

  private JsonNode command(
      IdentityProvider.Principal p,
      String id,
      String operation,
      String approval,
      String key,
      String ifMatch,
      JsonNode body) {
    ApiJson.uuid(id);
    boolean decision = operation.equals("decision"), reconcile = operation.equals("reconcile");
    Owned owned = repository.owned(id);
    if (decision) {
      assigned(p, owned);
      require(p, "approvals:decide");
    } else {
      owner(p, owned);
      require(p, reconcile ? "runs:reconcile" : "runs:control");
    }
    ApiJson.validate(
        decision
            ? "ApprovalDecisionRequest"
            : reconcile ? "ToolReconciliationRequest" : "ControlRequest",
        body);
    String route = "/runs/" + id + "/" + operation + (approval == null ? "" : "/" + approval);
    String hash = commandHash(p, route, key), digest = digest(body);
    // Domain authorization is I/O and must precede the atomic SQL command. A changed revision
    // is rejected below before the captured projection decision can be used.
    RunState projectionRun =
        (!decision && !reconcile && !owned.release().publicOutput()) ? store.get(id) : null;
    boolean protectedAllowed =
        projectionRun != null && protectedOutputAllowed(p, owned, projectionRun);
    return repository.transaction(
        c -> {
          repository.lockProject(c, p.project());
          Command previous = repository.command(c, hash);
          if (previous != null) return replay(previous, digest);
          RunState run = store.get(id);
          long revision =
              expected(
                  p,
                  owned,
                  run,
                  ifMatch,
                  decision ? "approval" : reconcile ? "unknown" : null,
                  protectedAllowed && projectionRun.revision == run.revision);
          if (TERMINAL.contains(run.status)) throw new ApiFailure(409, "INVALID_STATE");
          String permission =
              decision ? "approval:decide" : reconcile ? "run:reconcile" : "run:control";
          Actor actor =
              new Actor(
                  decision ? "approver:" + p.assignment() : run.actor.subject(),
                  p.project(),
                  Set.of(permission));
          RunState updated;
          if (decision) {
            if (run.approval == null
                || !approvalId(run).equals(approval)
                || !("sha256:" + run.approval.digest()).equals(body.path("digest").asText()))
              throw new ApiFailure(409, "APPROVAL_STALE");
            boolean approved = body.path("decision").asText().equals("APPROVE"),
                human = "HUMAN".equals(run.pending.kind);
            if (approved && !approvalView(p, owned, run).path("reviewComplete").asBoolean())
              throw new ApiFailure(409, "APPROVAL_REVIEW_INCOMPLETE");
            if ((!approved || !human) && body.has("input"))
              throw new ApiFailure(422, "INPUT_INVALID");
            if (approved
                && human
                && (!body.has("input")
                    || !SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(owned.release().humanInputSchema())
                        .validate(body.get("input"))
                        .isEmpty())) throw new ApiFailure(422, "INPUT_INVALID");
            updated =
                access.command(
                    actor,
                    Set.of(permission),
                    () ->
                        harness.decide(
                            id,
                            actor,
                            revision,
                            run.approval.digest(),
                            approved,
                            body.has("input") ? body.get("input").asText() : null));
          } else if (reconcile) {
            if (run.pending == null
                || !"TOOL".equals(run.pending.kind)
                || run.pending.phase != InvocationPhase.UNKNOWN)
              throw new ApiFailure(409, "OUTCOME_UNKNOWN");
            Evidence evidence = repository.evidence(c, body.path("evidenceRef").asText());
            if (!evidence.project().equals(p.project()) || !evidence.runId().equals(id))
              throw new ApiFailure(409, "EVIDENCE_NOT_VERIFIED");
            String invocation = body.path("invocationRef").asText();
            String expectedDigest = body.path("invocationDigest").asText();
            if (!evidence.invocation().equals(run.pending.id)
                || !invocationRef(id, run.pending.id).equals(invocation)
                || !evidence.digest().equals(expectedDigest)
                || !invocationDigest(run).equals(expectedDigest))
              throw new ApiFailure(409, "EVIDENCE_MISMATCH");
            updated =
                access.command(
                    actor,
                    Set.of(permission),
                    () ->
                        harness.reconcileTool(
                            id, actor, revision, run.pending.id, evidence.result()));
          } else {
            updated =
                access.command(
                    actor,
                    Set.of(permission),
                    () ->
                        switch (operation) {
                          case "pause" -> harness.pause(id, actor, revision);
                          case "cancel" -> harness.cancel(id, actor, revision);
                          default -> harness.resume(id, actor, revision);
                        });
          }
          JsonNode response = accepted(updated);
          repository.command(
              c,
              hash,
              digest,
              owned,
              response,
              p.assignment(),
              operation,
              body.has("reason") ? body.get("reason").asText() : null);
          return response;
        });
  }
}
