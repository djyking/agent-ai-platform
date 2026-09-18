package io.github.djyking.harness.validation;

import static io.github.djyking.harness.validation.ValidationMain.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.adapters.mcp.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit, scoped GitHub MCP writes with independent read-only REST reconciliation. */
final class GitHubWriteAcceptance {
  static final URI MCP_ENDPOINT = URI.create("https://api.githubcopilot.com/mcp/");
  static final Set<String> WRITE_TOOLS = Set.of("create_branch", "create_or_update_file");

  private GitHubWriteAcceptance() {}

  record Plan(String repository, String tag, String sourceBranch) {
    Plan {
      require(repository != null && repository.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}"), "GITHUB_REPOSITORY_INVALID");
      require(tag != null && tag.matches("[a-z0-9][a-z0-9-]{7,48}"), "GITHUB_ACCEPTANCE_TAG_INVALID");
      require(sourceBranch != null && sourceBranch.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,100}"), "GITHUB_SOURCE_BRANCH_INVALID");
    }

    String owner() { return repository.split("/", 2)[0]; }
    String repo() { return repository.split("/", 2)[1]; }
    String branch() { return "codex/harness-write-acceptance-" + tag; }
    String path(boolean lost) { return "validation/sandbox/" + tag + "/" + (lost ? "lost-response.txt" : "approved.txt"); }
    String content(boolean lost) {
      return "Synthetic harness GitHub MCP acceptance\nrun=" + tag + "\nscenario="
          + (lost ? "controlled-response-loss" : "approved-write") + "\n";
    }
    ObjectNode branchArguments() {
      return common().put("from_branch", sourceBranch);
    }
    ObjectNode fileArguments(boolean lost) {
      return common().put("path", path(lost)).put("content", content(lost))
          .put("message", "test(harness): synthetic " + (lost ? "lost-response" : "approved") + " acceptance " + tag);
    }
    private ObjectNode common() {
      return Json.object().put("owner", owner()).put("repo", repo()).put("branch", branch());
    }
    ObjectNode summary() {
      return Json.object().put("repository", repository).put("branch", branch())
          .put("sourceBranch", sourceBranch).put("approvedPath", path(false)).put("lostResponsePath", path(true));
    }
  }

  static Plan plan(LiveSettings settings) {
    return new Plan(settings.required("HARNESS_GITHUB_REPOSITORY"), settings.required("HARNESS_GITHUB_ACCEPTANCE_TAG"), settings.optional("HARNESS_GITHUB_SOURCE_BRANCH", "main"));
  }

  private static McpConnection connect(LiveSettings settings) {
    settings.bearer("HARNESS_MCP_TOKEN");
    return McpConnection.connect(new McpConnectionConfig("github-write-acceptance", MCP_ENDPOINT,
        Duration.ofSeconds(5), Duration.ofSeconds(30), 2 * 1024 * 1024, 8, 256,
        endpoint -> settings.bearer("HARNESS_MCP_TOKEN")));
  }

  static ObjectNode discover(LiveSettings settings) throws Exception {
    Plan plan = plan(settings);
    GitHubReads reads = new GitHubReads(plan, settings);
    ObjectNode report = plan.summary().put("remoteWriteCalls", 0).put("mcpEndpoint", MCP_ENDPOINT.toString());
    reads.preflight(report);
    try (McpConnection connection = connect(settings)) {
      ArrayNode catalog = report.putArray("writeTools");
      for (var tool : connection.discover()) {
        if (WRITE_TOOLS.contains(tool.name())) {
          catalog.add(Json.object().put("remoteName", tool.name()).put("remoteContractDigest", Json.hash(tool))
              .set("inputSchema", Json.tree(tool.inputSchema())));
        }
      }
      require(catalog.size() == 2, "GITHUB_MCP_WRITE_TOOLS_MISSING");
    }
    return report;
  }

  static ObjectNode run(LiveSettings settings, ObjectNode envelope) throws Exception {
    Plan plan = plan(settings);
    require(settings.required("HARNESS_GITHUB_WRITE_CONFIRM").equals(plan.repository() + "@" + plan.branch()), "GITHUB_WRITE_CONFIRM_MISMATCH");
    ObjectNode report = plan.summary().put("mcpEndpoint", MCP_ENDPOINT.toString())
        .put("simulationBoundary", "Real MCP response deliberately discarded after successful return; not a physical network disconnect")
        .put("storeKind", "InMemoryRunStore; same-process Harness reconstruction, not process restart")
        .put("approvalMode", "Explicitly authorized host applies exact Harness approval digest")
        .put("maxRemoteWriteCalls", 3).put("modelCalls", 0);
    envelope.set("details", report); // Preserve partial, sanitized evidence if any later step fails.
    GitHubReads reads = new GitHubReads(plan, settings);
    reads.preflight(report);
    try (McpConnection connection = connect(settings)) {
      McpToolAdapter adapter = new McpToolAdapter(connection);
      ToolPolicy policy = new ToolPolicy(false, true, false, 1, 30000, Set.of("github:write:" + plan.repository()));
      List<ToolDescriptor> descriptors = adapter.bind(Map.of("create_branch", policy, "create_or_update_file", policy));
      return exercise(plan, descriptors, adapter, reads, report);
    }
  }

  interface ReadVerifier {
    String sourceHead() throws Exception;
    void assertAbsent(boolean branch, boolean lost) throws Exception;
    String branchHead() throws Exception;
    ObjectNode verifyFile(boolean lost, String expectedParent) throws Exception;
  }

  static ObjectNode exercise(Plan plan, List<ToolDescriptor> descriptors, ToolHandler remote,
      ReadVerifier reads, ObjectNode report) throws Exception {
    ScopedWriter writer = new ScopedWriter(plan, remote, report);
    ToolRegistry registry = new ToolRegistry();
    Map<String, ToolDescriptor> byName = new HashMap<>();
    var permissions = new HashSet<>(Set.of("run:create", "run:read", "run:control", "approval:decide", "run:reconcile", "github:write:" + plan.repository()));
    ArrayNode contracts = report.putArray("localPolicies");
    for (ToolDescriptor descriptor : descriptors) {
      require(WRITE_TOOLS.contains(descriptor.remoteName()) && !descriptor.policy().readOnly()
          && descriptor.policy().approvalRequired() && !descriptor.policy().retrySafe()
          && descriptor.policy().maxAttempts() == 1, "GITHUB_WRITE_POLICY_INVALID");
      registry.register(descriptor, writer);
      byName.put(descriptor.remoteName(), descriptor);
      permissions.add("tool:" + descriptor.key());
      contracts.add(Json.object().put("remoteName", descriptor.remoteName()).put("digest", descriptor.digest())
          .put("approvalRequired", true).put("readOnly", false).put("retrySafe", false).put("maxAttempts", 1));
    }
    require(byName.size() == 2, "GITHUB_MCP_WRITE_TOOLS_MISSING");
    Actor actor = new Actor("github-acceptance-host", "harness-validation", permissions);
    InMemoryRunStore store = new InMemoryRunStore();
    ArrayNode operations = report.putArray("operations");
    ArrayNode checks = report.putArray("checks");
    String parent = reads.sourceHead();
    report.put("sourceHeadBeforeWrites", parent);
    try (Harness harness = runtime(store, registry)) {
      for (int index = 0; index < 3; index++) {
        boolean branch = index == 0, lost = index == 2;
        ToolDescriptor tool = byName.get(branch ? "create_branch" : "create_or_update_file");
        JsonNode args = branch ? plan.branchArguments() : plan.fileArguments(lost);
        String program = "github-acceptance-" + index;
        harness.registerProgram(program, state -> state.results.containsKey("write")
            ? new CompleteAction(state.results.get("write").value()) : new ToolAction("write", program, tool.key(), args));
        RunState run = harness.start(new ProgramDefinition(program, "1", program, Json.object()), actor,
            List.of(tool.key()), new Budget(1, 1, 1, 10), Duration.ofMinutes(3), plan.tag() + ":" + index);
        ObjectNode operation = Json.object().put("runId", run.id).put("remoteName", tool.remoteName())
            .put("argumentsDigest", Json.hash(args)).put("scenario", branch ? "create-isolated-branch" : lost ? "controlled-response-loss" : "approved-write");
        operations.add(operation);
        harness.tick(run.id);
        RunState waiting = harness.tick(run.id);
        require(waiting.status == RunStatus.WAITING_APPROVAL && writer.dispatched.get() == index, "GITHUB_WRITE_BEFORE_APPROVAL");
        reads.assertAbsent(branch, lost);
        harness.tick(run.id);
        require(writer.dispatched.get() == index, "GITHUB_WAITING_TICK_DISPATCHED");
        expectConflict(() -> harness.decide(run.id, actor, "invalid-approval-digest", true, null));
        require(writer.dispatched.get() == index, "GITHUB_STALE_APPROVAL_DISPATCHED");
        operation.put("unapprovedRemoteAbsenceVerified", true).put("approvalDigest", waiting.approval.digest());
        harness.decide(run.id, actor, waiting.approval.digest(), true, "User authorized the exact synthetic acceptance scope");
        RunState called = harness.tick(run.id);
        operation.put("statusAfterCall", called.status.name()).put("toolCalls", called.toolCalls);
        require(writer.dispatched.get() == index + 1, "GITHUB_WRITE_COUNT_MISMATCH");
        if (branch) {
          require(called.results.containsKey("write") && !called.results.get("write").error(), "GITHUB_BRANCH_WRITE_FAILED");
          require(reads.branchHead().equals(parent), "GITHUB_BRANCH_BASE_MISMATCH");
          require(harness.tick(run.id).status == RunStatus.COMPLETED, "GITHUB_BRANCH_RUN_INCOMPLETE");
          operation.put("verifiedCommitSha", parent);
        } else {
          ObjectNode receipt = reads.verifyFile(lost, parent);
          parent = receipt.path("commitSha").asText();
          operation.set("independentReceipt", receipt);
          if (!lost) {
            require(called.results.containsKey("write") && !called.results.get("write").error(), "GITHUB_APPROVED_WRITE_FAILED");
            require(harness.tick(run.id).status == RunStatus.COMPLETED, "GITHUB_WRITE_RUN_INCOMPLETE");
          } else {
            require(called.status == RunStatus.NEEDS_ATTENTION && called.pending.phase == InvocationPhase.UNKNOWN, "GITHUB_LOST_RESPONSE_NOT_UNKNOWN");
            require(called.pending.attempts == 1 && called.toolCalls == 1, "GITHUB_UNKNOWN_ATTEMPT_COUNT_MISMATCH");
            expectConflict(() -> harness.resume(run.id, actor));
            harness.tick(run.id);
            harness.cancel(run.id, actor);
            expectConflict(() -> harness.resume(run.id, actor));
            // Recreate the worker against the same store. No external write is allowed here.
            try (Harness reconstructed = runtime(store, registry)) {
              reconstructed.tick(run.id);
              require(writer.dispatched.get() == 3 && reads.branchHead().equals(parent), "GITHUB_UNKNOWN_WAS_REDISPATCHED");
              String verifiedReceipt = "github:" + plan.repository() + ":" + parent + ":" + receipt.path("blobSha").asText();
              RunState reconciled = reconstructed.reconcileTool(run.id, actor, called.pending.id,
                  new ToolResult(receipt, false, verifiedReceipt));
              require(reconciled.status == RunStatus.CANCELLED && reconciled.pending == null
                  && verifiedReceipt.equals(reconciled.receipts.get("write")), "GITHUB_RECONCILIATION_FAILED");
              reconstructed.tick(run.id);
              expectConflict(() -> reconstructed.resume(run.id, actor));
              operation.put("finalStatus", reconciled.status.name()).put("unknownResumeRejected", true)
                  .put("cancelledResumeRejected", true).put("receiptReconciled", true);
            }
          }
        }
        operation.set("eventTypes", Json.tree(harness.events(run.id, actor, 0, 100).stream().map(e -> e.event().type()).toList()));
      }
    }
    require(writer.dispatched.get() == 3 && writer.successes.get() == 3 && writer.discarded.get() == 1
        && reads.branchHead().equals(parent), "GITHUB_FINAL_WRITE_COUNT_MISMATCH");
    report.put("finalHead", parent).put("passedChecks", 7).put("remoteWriteCalls", writer.dispatched.get())
        .put("successfulMcpResponses", writer.successes.get()).put("controlledDiscardedResponses", writer.discarded.get())
        .put("independentVerification", "GitHub REST GET: ref, contents, commit parents and exact changed-file list")
        .put("knownLimit", "No process kill, physical network partition, or remote idempotency claim");
    for (String check : List.of("exact_remote_scope_and_one_attempt_policy", "unapproved_remote_absence", "wrong_digest_rejected",
        "approved_mcp_real_writes", "controlled_lost_response_unknown", "cancel_resume_reconstruction_no_redispatch", "independent_receipt_reconcile_no_rewrite")) checks.add(check);
    return report;
  }

  static final class ScopedWriter implements ToolHandler {
    private final Plan plan;
    private final ToolHandler remote;
    private final ObjectNode report;
    private final Set<String> dispatchedArguments = ConcurrentHashMap.newKeySet();
    final AtomicInteger dispatched = new AtomicInteger(), successes = new AtomicInteger(), discarded = new AtomicInteger();
    ScopedWriter(Plan plan, ToolHandler remote, ObjectNode report) { this.plan = plan; this.remote = remote; this.report = report; }
    @Override public ToolResult invoke(ToolDescriptor tool, JsonNode arguments, ExecutionContext context) {
      assertExactScope(plan, tool.remoteName(), arguments);
      require(dispatchedArguments.add(tool.remoteName() + ":" + Json.hash(arguments)), "GITHUB_DUPLICATE_WRITE_BLOCKED");
      report.put("remoteWriteCalls", dispatched.incrementAndGet());
      ToolResult result = remote.invoke(tool, arguments, context);
      require(!result.error(), "GITHUB_MCP_WRITE_RETURNED_ERROR");
      report.put("successfulMcpResponses", successes.incrementAndGet());
      if ("create_or_update_file".equals(tool.remoteName()) && arguments.path("path").asText().equals(plan.path(true))) {
        report.put("controlledDiscardedResponses", discarded.incrementAndGet());
        throw new InvocationException(FailureKind.UNKNOWN, "CONTROLLED_GITHUB_RESPONSE_DISCARDED");
      }
      return result;
    }
  }

  static void assertExactScope(Plan plan, String remoteName, JsonNode arguments) {
    boolean allowed = "create_branch".equals(remoteName) && plan.branchArguments().equals(arguments)
        || "create_or_update_file".equals(remoteName) && (plan.fileArguments(false).equals(arguments) || plan.fileArguments(true).equals(arguments));
    require(allowed, "GITHUB_WRITE_OUTSIDE_EXACT_SCOPE");
  }

  private static Harness runtime(InMemoryRunStore store, ToolRegistry tools) {
    return new Harness(store, (r, c) -> { throw new AssertionError("No model in GitHub write acceptance"); },
        tools, AccessPolicy.actorPermissions(), Telemetry.noop(), Clock.systemUTC(), Duration.ofSeconds(60));
  }

  private static void expectConflict(Runnable action) {
    try { action.run(); } catch (RunStore.Conflict expected) { return; }
    require(false, "GITHUB_CONTROL_SHOULD_HAVE_BEEN_REJECTED");
  }

  private static final class GitHubReads implements ReadVerifier {
    private final Plan plan;
    private final LiveSettings settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    GitHubReads(Plan plan, LiveSettings settings) { this.plan = plan; this.settings = settings; }
    void preflight(ObjectNode report) throws Exception {
      JsonNode repo = get("", false);
      require(repo.path("full_name").asText().equalsIgnoreCase(plan.repository()) && repo.path("permissions").path("push").asBoolean(), "GITHUB_PUSH_PERMISSION_REQUIRED");
      require(!plan.branch().equals(repo.path("default_branch").asText()) && !plan.branch().equals(plan.sourceBranch()), "GITHUB_BRANCH_NOT_ISOLATED");
      assertAbsent(true, false);
      report.put("pushPermissionVerified", true).put("branchAbsentBeforeWrites", true)
          .put("sourceHeadAtPreflight", sourceHead()).put("defaultBranch", repo.path("default_branch").asText());
    }
    @Override public String sourceHead() throws Exception { return sha(get("/git/ref/heads/" + encoded(plan.sourceBranch()), false).path("object").path("sha")); }
    @Override public String branchHead() throws Exception { return sha(get("/git/ref/heads/" + encoded(plan.branch()), false).path("object").path("sha")); }
    @Override public void assertAbsent(boolean branch, boolean lost) throws Exception {
      require(get(branch ? "/git/ref/heads/" + encoded(plan.branch()) : contentsPath(lost), true) == null, "GITHUB_ACCEPTANCE_TARGET_ALREADY_EXISTS");
    }
    @Override public ObjectNode verifyFile(boolean lost, String expectedParent) throws Exception {
      JsonNode file = get(contentsPath(lost), false);
      require("file".equals(file.path("type").asText()) && "base64".equals(file.path("encoding").asText()), "GITHUB_RECEIPT_NOT_A_FILE");
      String content = new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8);
      require(plan.content(lost).equals(content), "GITHUB_RECEIPT_CONTENT_MISMATCH");
      String blob = sha(file.path("sha")), commit = branchHead();
      require(blob.equals(gitBlobSha(content)), "GITHUB_RECEIPT_BLOB_MISMATCH");
      JsonNode details = get("/commits/" + commit, false);
      require(details.path("parents").size() == 1 && details.path("parents").get(0).path("sha").asText().equals(expectedParent), "GITHUB_UNEXPECTED_COMMIT_PARENT");
      JsonNode changes = details.path("files");
      require(changes.size() == 1 && changes.get(0).path("filename").asText().equals(plan.path(lost))
          && changes.get(0).path("sha").asText().equals(blob) && changes.get(0).path("status").asText().equals("added"), "GITHUB_UNEXPECTED_CHANGED_FILES");
      return Json.object().put("path", plan.path(lost)).put("blobSha", blob).put("commitSha", commit)
          .put("parentCommitSha", expectedParent).put("verifiedContentDigest", Json.hash(content))
          .put("url", "https://github.com/" + plan.repository() + "/blob/" + commit + "/" + plan.path(lost));
    }
    private String contentsPath(boolean lost) { return "/contents/" + plan.path(lost) + "?ref=" + encoded(plan.branch()); }
    private JsonNode get(String suffix, boolean allowAbsent) throws Exception {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + plan.repository() + suffix))
          .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").header("User-Agent", "harness-write-acceptance")
          .header("X-GitHub-Api-Version", "2022-11-28").GET();
      settings.bearer("HARNESS_MCP_TOKEN").forEach(request::header);
      var response = client.send(request.build(), new LimitedBodyHandler(2 * 1024 * 1024));
      if (allowAbsent && response.statusCode() == 404) return null;
      require(response.statusCode() == 200, "GITHUB_READ_VERIFICATION_HTTP_FAILED");
      return Json.read(new String(response.body(), StandardCharsets.UTF_8));
    }
  }

  static String gitBlobSha(String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest(bytes));
  }
  private static String sha(JsonNode value) { String sha = value.asText(); require(sha.matches("[a-f0-9]{40,64}"), "GITHUB_INVALID_OBJECT_SHA"); return sha; }
  private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
