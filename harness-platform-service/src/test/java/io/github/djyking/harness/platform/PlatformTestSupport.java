package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.capabilities.prompt.PromptTemplate;
import io.github.djyking.harness.capabilities.workflow.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.testing.MutableClock;
import io.github.djyking.harness.core.testing.ScriptedModel;
import io.github.djyking.harness.integrations.opsagent.OpsAgentRagTool;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;

/** Explicit test identity authority. It is never installed in the application configuration. */
final class PlatformTestSupport implements AutoCloseable {
  static final Set<String> OWNER_PERMISSIONS =
      Set.of(
          "runs:create",
          "runs:read",
          "runs:list",
          "runs:events:read",
          "runs:control",
          "runs:output:read",
          "runs:reconcile:read",
          "runs:reconcile",
          "tool:test:read",
          "tool:test:write",
          "tool:" + OpsAgentRagTool.KEY,
          OpsAgentRagTool.PERMISSION,
          "model:invoke");
  static final Set<String> REVIEW_PERMISSIONS =
      Set.of("approvals:read", "approvals:decide", "approvals:review");
  final JdbcDataSource dataSource = new JdbcDataSource();
  final JdbcRunStore store;
  final PlatformRepository repository;
  final TestIdentities identities = new TestIdentities();
  final Deployment config;
  final RuntimeAccess access;
  final Harness harness;
  final PlatformService service;
  final MutableClock clock = new MutableClock(Instant.now());
  final AtomicInteger reads = new AtomicInteger(),
      writes = new AtomicInteger(),
      modelCalls = new AtomicInteger();
  final AtomicBoolean uncertainWrite = new AtomicBoolean(), uncertainModel = new AtomicBoolean();
  final Map<String, Deployment.Release> releases = new LinkedHashMap<>();

  PlatformTestSupport() {
    this(30, 1_000_000);
  }

  PlatformTestSupport(int activeLimit, long tokenLimit) {
    this(
        activeLimit,
        tokenLimit,
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
  }

  PlatformTestSupport(String jdbcUrl) {
    this(30, 1_000_000, jdbcUrl);
  }

  private PlatformTestSupport(int activeLimit, long tokenLimit, String jdbcUrl) {
    dataSource.setURL(jdbcUrl);
    dataSource.setUser("platform-test");
    dataSource.setPassword("explicitly-synthetic-test-password");
    store = new JdbcRunStore(dataSource);
    store.initializeSchema();
    List<Deployment.Project> projects = new ArrayList<>();
    for (String project : List.of("project-a", "project-b")) {
      projects.add(
          new Deployment.Project(
              project,
              Set.of("app-a", "app-b"),
              activeLimit,
              tokenLimit,
              activeLimit,
              tokenLimit,
              2));
      for (String app : List.of("app-a", "app-b")) {
        identities.grant(app, project, "alice", OWNER_PERMISSIONS);
        identities.grant(app, project, "bob", OWNER_PERMISSIONS);
      }
      identities.grant("app-a", project, "reviewer", REVIEW_PERMISSIONS);
      identities.grant(
          "app-a", project, "blind-reviewer", Set.of("approvals:read", "approvals:decide"));
      for (String kind : List.of("read", "write", "human", "model", "protected", "protected-model"))
        releases.put(project + "/" + kind, release(project, kind));
    }
    config =
        new Deployment(
            "https://identity.invalid",
            "env:TEST_SIGNING_KEY",
            Map.of("app-a", "env:APP_A", "app-b", "env:APP_B"),
            projects,
            List.copyOf(releases.values()),
            Set.of(),
            List.of(),
            List.of(),
            4,
            10000,
            false);
    repository = new PlatformRepository(store);
    repository.initialize(config);
    access = new RuntimeAccess(repository, identities);
    harness = newHarness(store, access);
    service =
        new PlatformService(
            config,
            store,
            repository,
            harness,
            access,
            identities,
            new SignedTokens("test-only-signing-key-never-used-in-deployment-0123456789"),
            clock);
  }

  Harness newHarness(JdbcRunStore runStore) {
    return newHarness(runStore, new RuntimeAccess(new PlatformRepository(runStore), identities));
  }

  private Harness newHarness(JdbcRunStore runStore, RuntimeAccess runtimeAccess) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(
        tool("read", ToolPolicy.readOnlyPolicy()),
        (tool, arguments, context) -> {
          reads.incrementAndGet();
          return ToolResult.success(arguments);
        });
    tools.register(
        tool("write", ToolPolicy.approvedWrite()),
        (tool, arguments, context) -> {
          writes.incrementAndGet();
          if (uncertainWrite.get())
            throw new InvocationException(FailureKind.UNKNOWN, "WRITE_REPLY_LOST");
          return new ToolResult(arguments, false, "independent-test-receipt");
        });
    tools.register(
        new ToolDescriptor(
            OpsAgentRagTool.KEY,
            "test_protected_retrieval",
            "Synthetic protected retrieval",
            "test-only",
            "retrieve",
            "1",
            valueSchema(),
            new ToolPolicy(true, false, false, 1, 5000, Set.of(OpsAgentRagTool.PERMISSION))),
        (tool, arguments, context) -> ToolResult.success(protectedResult()));
    Harness instance =
        new Harness(
            runStore,
            (request, context) -> {
              modelCalls.incrementAndGet();
              if (uncertainModel.get())
                throw new InvocationException(FailureKind.UNKNOWN, "MODEL_REPLY_LOST");
              return ScriptedModel.answer("A recorded answer");
            },
            tools,
            runtimeAccess,
            Telemetry.noop(),
            clock,
            Duration.ofSeconds(10));
    instance.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
    return instance;
  }

  static ToolDescriptor tool(String name, ToolPolicy policy) {
    return new ToolDescriptor(
        "test:" + name,
        "test_" + name,
        "Test " + name,
        "test-only",
        name,
        "1",
        valueSchema(),
        policy);
  }

  static ObjectNode valueSchema() {
    return (ObjectNode)
        Json.read(
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"],\"additionalProperties\":false}");
  }

  static JsonNode protectedResult() {
    return Json.read(
        """
        {"evidence":"Synthetic restricted excerpts from two documents",
         "citations":[
           {"sourceId":"source-one","chunkId":101,"documentId":11,"version":1,"documentName":"Restricted one"},
           {"sourceId":"source-two","chunkId":202,"documentId":22,"version":1,"documentName":"Restricted two"}
         ]}
        """);
  }

  private static Deployment.Release release(String project, String kind) {
    Map<String, WorkflowDefinition.Node> nodes = new LinkedHashMap<>();
    Set<String> toolKeys = Set.of(), execution = Set.of();
    JsonNode input = valueSchema(), output = valueSchema();
    ModelProfile profile = null;
    switch (kind) {
      case "read", "write" -> {
        String key = "test:" + kind;
        nodes.put(
            "start",
            new WorkflowDefinition.Tool(key, "{}", Map.of("value", "value"), "result", "end"));
        toolKeys = Set.of(key);
        execution = Set.of("tool:" + key);
      }
      case "human" -> {
        input = Json.read("{\"type\":\"object\",\"additionalProperties\":false}");
        output =
            Json.read(
                "{\"type\":\"object\",\"required\":[\"input\"],\"properties\":{\"input\":{\"type\":\"string\"}},\"additionalProperties\":false}");
        nodes.put(
            "start",
            new WorkflowDefinition.Human(
                "Enter the approved literal test answer", "result", "end"));
      }
      case "model" -> {
        input =
            Json.read(
                "{\"type\":\"object\",\"required\":[\"question\"],\"properties\":{\"question\":{\"type\":\"string\"}},\"additionalProperties\":false}");
        output = Json.object().put("type", "string");
        profile = new ModelProfile("test", "test", "recorded", 16000, 128, 5000, Json.object());
        nodes.put(
            "start",
            new WorkflowDefinition.Model(
                new PromptTemplate(
                    "test",
                    "1",
                    "Answer literally",
                    "{{question}}",
                    Map.of("question", PromptTemplate.VariableType.STRING)),
                Map.of("question", "question"),
                "result",
                "end"));
        execution = Set.of("model:invoke");
      }
      case "protected", "protected-model" -> {
        boolean processed = kind.equals("protected-model");
        nodes.put(
            "start",
            new WorkflowDefinition.Tool(
                OpsAgentRagTool.KEY,
                "{}",
                Map.of("value", "value"),
                "result",
                processed ? "summarize" : "end"));
        toolKeys = Set.of(OpsAgentRagTool.KEY);
        execution =
            processed
                ? Set.of("tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION, "model:invoke")
                : Set.of("tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION);
        output =
            Json.read(
                "{\"type\":\"object\",\"required\":[\"evidence\",\"citations\"],\"properties\":{\"evidence\":{\"type\":\"string\"},\"citations\":{\"type\":\"array\"}},\"additionalProperties\":false}");
        if (processed) {
          profile = new ModelProfile("test", "test", "recorded", 16000, 128, 5000, Json.object());
          nodes.put(
              "summarize",
              new WorkflowDefinition.Model(
                  new PromptTemplate(
                      "test",
                      "1",
                      "Recorded transformation",
                      "Inspect the synthetic sources",
                      Map.of()),
                  Map.of(),
                  "summary",
                  "end"));
        }
      }
      default -> throw new IllegalArgumentException(kind);
    }
    nodes.put("end", new WorkflowDefinition.End("result"));
    return new Deployment.Release(
        project,
        kind + "-agent",
        UUID.nameUUIDFromBytes(
                (project + "/" + kind).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString(),
        new WorkflowDefinition(kind, "1", "start", 10, nodes),
        profile,
        input,
        output,
        toolKeys,
        execution,
        Set.of("app-a/reviewer", "app-a/blind-reviewer"),
        List.of("value"),
        Json.object().put("type", "string").put("minLength", 1).put("maxLength", 1000),
        !kind.startsWith("protected"),
        new Deployment.Limits(10000, 4, 8, 40, 3600));
  }

  IdentityProvider.Principal principal() {
    return principal("app-a", "project-a", "alice");
  }

  IdentityProvider.Principal principal(String app, String project, String subject) {
    return service.authenticate(project, app + "-credential", subject + "-token");
  }

  IdentityProvider.Principal reviewer() {
    return principal("app-a", "project-a", "reviewer");
  }

  ObjectNode body(String project, String kind) {
    ObjectNode body = Json.object();
    body.set("releaseRef", releases.get(project + "/" + kind).reference());
    body.set(
        "inputs",
        kind.equals("human")
            ? Json.object()
            : kind.equals("model")
                ? Json.object().put("question", "What does the source say?")
                : Json.object().put("value", 7));
    return body;
  }

  JsonNode create(String kind, String key) {
    return create(principal(), kind, key);
  }

  JsonNode create(IdentityProvider.Principal p, String kind, String key) {
    clock.advance(Duration.ofMillis(1));
    return service.create(
        p, p.application() + "-credential", p.subject() + "-token", key, body(p.project(), kind));
  }

  String id(JsonNode accepted) {
    return accepted.path("run").path("id").asText();
  }

  RunState drive(String id) {
    RunState state = store.get(id);
    for (int i = 0;
        i < 80 && (state.status == RunStatus.QUEUED || state.status == RunStatus.RUNNING);
        i++) state = harness.tick(id);
    return state;
  }

  JsonNode approve(String id, String key) {
    var view = service.approval(reviewer(), id);
    return service.decide(
        reviewer(),
        id,
        view.body().path("id").asText(),
        key,
        view.etag(),
        Json.object()
            .put("digest", view.body().path("digest").asText())
            .put("decision", "APPROVE")
            .put("reason", "Verified isolated test target"));
  }

  long count(String table) {
    if (!Set.of(
            "harness_runs",
            "harness_run_events",
            "platform_runs",
            "platform_commands",
            "platform_command_audit")
        .contains(table)) throw new IllegalArgumentException(table);
    return repository.transaction(
        c -> {
          try (Statement s = c.createStatement();
              ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(r.next());
            return r.getLong(1);
          }
        });
  }

  static ApiFailure error(int status, String code, Runnable operation) {
    ApiFailure failure = assertThrows(ApiFailure.class, operation::run);
    assertEquals(status, failure.status);
    if (code != null) assertEquals(code, failure.code);
    return failure;
  }

  public void close() {
    harness.close();
  }

  static final class TestIdentities implements IdentityProvider {
    @FunctionalInterface
    interface ProtectedReadPolicy {
      boolean allowed(Principal reader, PlatformRepository.Owned owned, JsonNode output);
    }

    private record Delegation(String app, String project, String subject, String run) {}

    private final Map<String, Principal> subjects = new ConcurrentHashMap<>();
    private final Map<String, Delegation> delegations = new ConcurrentHashMap<>();
    final AtomicInteger issued = new AtomicInteger();
    final AtomicInteger protectedChecks = new AtomicInteger();
    volatile ProtectedReadPolicy protectedRead = (reader, owned, output) -> false;
    volatile Runnable beforeDelegate = () -> {};

    private String key(String app, String project, String subject) {
      return app + "/" + project + "/" + subject;
    }

    void grant(String app, String project, String subject, Set<String> permissions) {
      subjects.put(key(app, project, subject), new Principal(app, project, subject, permissions));
    }

    void revoke(String app, String project, String subject, String permission) {
      subjects.compute(
          key(app, project, subject),
          (k, before) -> {
            Set<String> next = new HashSet<>(before.permissions());
            next.remove(permission);
            return new Principal(app, project, subject, next);
          });
    }

    public Principal authenticate(String project, String appCredential, String userToken) {
      if (!appCredential.endsWith("-credential") || !userToken.endsWith("-token"))
        throw new ApiFailure(401, "UNAUTHENTICATED");
      Principal value =
          subjects.get(
              key(
                  appCredential.substring(0, appCredential.length() - 11),
                  project,
                  userToken.substring(0, userToken.length() - 6)));
      if (value == null) throw new ApiFailure(401, "UNAUTHENTICATED");
      return value;
    }

    public String delegate(Principal p, String app, String user, String run, Instant deadline) {
      beforeDelegate.run();
      assertEquals(p, authenticate(p.project(), app, user));
      String id = "test-delegation-" + issued.incrementAndGet();
      delegations.put(id, new Delegation(p.application(), p.project(), p.subject(), run));
      return id;
    }

    public Principal current(String app, String project, String delegation, String run) {
      Delegation d = delegations.get(delegation);
      if (d == null || !d.app.equals(app) || !d.project.equals(project) || !d.run.equals(run))
        throw ApiFailure.denied();
      Principal value = subjects.get(key(app, project, d.subject));
      if (value == null) throw ApiFailure.denied();
      return value;
    }

    public String toolAuthorization(String app, String project, String delegation, String run) {
      current(app, project, delegation, run);
      return "Bearer explicitly-test-only";
    }

    @Override
    public boolean canReadProtectedOutput(
        Principal reader, PlatformRepository.Owned owned, JsonNode output) {
      protectedChecks.incrementAndGet();
      return protectedRead.allowed(reader, owned, output);
    }
  }
}
