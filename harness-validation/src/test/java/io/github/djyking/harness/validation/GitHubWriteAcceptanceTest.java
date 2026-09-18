package io.github.djyking.harness.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitHubWriteAcceptanceTest {
  private static final GitHubWriteAcceptance.Plan PLAN =
      new GitHubWriteAcceptance.Plan("test-owner/test-repo", "20260918-test1234", "main");

  @Test
  void exactWriteEnvelopeRejectsOtherRepositoryBranchPathContentAndExtraFields() {
    GitHubWriteAcceptance.assertExactScope(PLAN, "create_branch", PLAN.branchArguments());
    GitHubWriteAcceptance.assertExactScope(PLAN, "create_or_update_file", PLAN.fileArguments(false));
    for (ObjectNode args : List.of(
        PLAN.fileArguments(false).put("repo", "production"),
        PLAN.fileArguments(false).put("branch", "main"),
        PLAN.fileArguments(false).put("path", "src/main.java"),
        PLAN.fileArguments(false).put("content", "unexpected"),
        PLAN.fileArguments(false).put("sha", "unexpected-update"),
        PLAN.fileArguments(false).put("allow_symlink_write", true))) {
      assertThrows(InvocationException.class,
          () -> GitHubWriteAcceptance.assertExactScope(PLAN, "create_or_update_file", args));
    }
    assertThrows(InvocationException.class,
        () -> GitHubWriteAcceptance.assertExactScope(PLAN, "delete_file", PLAN.fileArguments(false)));
    assertThrows(InvocationException.class,
        () -> new GitHubWriteAcceptance.Plan("test-owner/repo?redirect=x", "20260918-test1234", "main"));
    assertThrows(InvocationException.class,
        () -> new GitHubWriteAcceptance.Plan("test-owner/repo", "../traversal", "main"));
  }

  @Test
  void guardRejectsDuplicateEnvelopeBeforeCallingRemoteAgain() {
    AtomicInteger writes = new AtomicInteger();
    var writer = new GitHubWriteAcceptance.ScopedWriter(PLAN, (t, a, c) -> {
      writes.incrementAndGet();
      return ToolResult.success(Json.object());
    }, Json.object());
    ToolDescriptor descriptor = descriptor("create_branch");
    ExecutionContext context = new ExecutionContext("run", "node", "invocation", "attempt",
        new Actor("test", "test", Set.of()), Instant.now().plusSeconds(10), "trace");
    writer.invoke(descriptor, PLAN.branchArguments(), context);
    assertThrows(InvocationException.class, () -> writer.invoke(descriptor, PLAN.branchArguments(), context));
    assertEquals(1, writes.get());
  }

  @Test
  void offlineAcceptanceExercisesApprovalUnknownAndReconcileWithoutAdditionalWrites() throws Exception {
    FakeGitHub remote = new FakeGitHub();
    ObjectNode report = GitHubWriteAcceptance.exercise(PLAN,
        List.of(descriptor("create_branch"), descriptor("create_or_update_file")), remote, remote, Json.object());
    assertEquals(3, remote.writes);
    assertEquals(3, report.path("successfulMcpResponses").asInt());
    assertEquals(1, report.path("controlledDiscardedResponses").asInt());
    assertEquals("CANCELLED", report.path("operations").get(2).path("finalStatus").asText());
    assertEquals(3, remote.absenceChecks);
    assertEquals(7, report.path("passedChecks").asInt());
    assertTrue(report.path("operations").get(2).path("eventTypes").toString().contains("TOOL_RECONCILED"));
  }

  @Test
  void blobReceiptUsesGitObjectHeaderAndUtf8Bytes() throws Exception {
    assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitHubWriteAcceptance.gitBlobSha(""));
    assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubWriteAcceptance.gitBlobSha("hello\n"));
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor("test/" + name, name, "Offline MCP fixture", "test", name, "1",
        Json.read("{\"type\":\"object\"}"),
        new ToolPolicy(false, true, false, 1, 10000, Set.of("github:write:" + PLAN.repository())));
  }

  private static final class FakeGitHub implements ToolHandler, GitHubWriteAcceptance.ReadVerifier {
    int writes, absenceChecks;
    boolean branch;
    String head = "a".repeat(40);
    final Map<String, String> files = new HashMap<>(), parents = new HashMap<>();
    @Override public ToolResult invoke(ToolDescriptor descriptor, com.fasterxml.jackson.databind.JsonNode args, ExecutionContext context) {
      writes++;
      if (descriptor.remoteName().equals("create_branch")) branch = true;
      else {
        assertTrue(branch);
        String path = args.path("path").asText();
        assertFalse(files.containsKey(path));
        files.put(path, args.path("content").asText());
        String next = Integer.toString(writes).repeat(40);
        parents.put(next, head);
        head = next;
      }
      return ToolResult.success(Json.object().put("synthetic", true));
    }
    @Override public String sourceHead() { return "a".repeat(40); }
    @Override public String branchHead() { assertTrue(branch); return head; }
    @Override public void assertAbsent(boolean checkBranch, boolean lost) {
      absenceChecks++;
      if (checkBranch) assertFalse(branch); else assertFalse(files.containsKey(PLAN.path(lost)));
    }
    @Override public ObjectNode verifyFile(boolean lost, String expectedParent) throws Exception {
      assertEquals(PLAN.content(lost), files.get(PLAN.path(lost)));
      assertEquals(expectedParent, parents.get(head));
      return Json.object().put("path", PLAN.path(lost)).put("commitSha", head)
          .put("blobSha", GitHubWriteAcceptance.gitBlobSha(files.get(PLAN.path(lost))));
    }
  }
}
