package com.opsagent.isolation;

import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import com.opsagent.auth.IsolatedSqlAuthHost;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.knowledge.IsolatedKnowledgeHost;
import com.opsagent.rag.IsolatedRagHost;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.integrations.opsagent.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real HTTP identity delegation, SQL auth, signed internal identity, RAG and knowledge SQL scopes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsolatedIdentityBridgeTest {
    @TempDir static Path directory;
    private final String secret = random();
    private final String app = random();
    private final String loginSecret = random();
    private DriverManagerDataSource ds;
    private IsolatedSqlAuthHost auth;
    private IsolatedKnowledgeHost knowledge;
    private IsolatedRagHost rag;
    private OpsAgentIdentityClient client;

    @BeforeAll void start() throws Exception {
        ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("auth").toAbsolutePath()
                + ";MODE=MySQL", "sa", random());
        auth = new IsolatedSqlAuthHost(ds, secret, app, loginSecret);
        knowledge = new IsolatedKnowledgeHost(secret, auth.origin());
        rag = new IsolatedRagHost(secret, auth.origin(), knowledge.origin(), ingress("LEGACY"));
        client = new OpsAgentIdentityClient(auth.origin(), Duration.ofSeconds(5));
    }

    @BeforeEach void reset() { auth.active(10, true); auth.grantEnabled(10, true); }
    @AfterAll void close() {
        if (rag != null) rag.close();
        if (knowledge != null) knowledge.close();
        if (auth != null) auth.close();
    }

    @Test void existingJwtAndSqlIdentityProduceDistinctAuthorizedKnowledgeScopes() {
        assertEquals(Set.of(1L, 4L), run(10));
        assertEquals(Set.of(1L, 2L), run(20));
    }

    @Test void currentSqlAccountAndGrantRevocationStopsTokenMinting() {
        String run = UUID.randomUUID().toString();
        var binding = client.bind("Bearer " + app, "ops-dev", auth.login(10), run, Instant.now().plusSeconds(300));
        auth.active(10, false);
        assertThrows(OpsAgentIdentityClient.IdentityFailure.class,
                () -> client.token("Bearer " + app, binding.delegationId(), run, "rag"));
        auth.active(10, true);
        auth.grantEnabled(10, false);
        assertThrows(OpsAgentIdentityClient.IdentityFailure.class,
                () -> client.current("Bearer " + app, binding.delegationId(), run));
    }

    @Test void storedCitationsRequireCurrentAclPublishedVersionAndMatchingChunk() {
        String run = UUID.randomUUID().toString();
        var binding = client.bind("Bearer " + app, "ops-dev", auth.login(20), run, Instant.now().plusSeconds(300));
        var token = client.token("Bearer " + app, binding.delegationId(), run, "rag").authorization();
        var projection = new OpsAgentProjectionClient(rag.origin(), Duration.ofSeconds(5));
        int searches = rag.knowledgeCalls();
        assertTrue(projection.allowed(token, refs(1, 1, 1)));
        assertTrue(projection.allowed(token, refs(2, 2, 1)));
        for (int denied : new int[] {3, 4, 5, 6, 7})
            assertFalse(projection.allowed(token, refs(denied, denied, 1)), "document " + denied);
        assertFalse(projection.allowed(token, refs(1, 2, 1)));
        assertFalse(projection.allowed(token, refs(1, 1, 2)));
        assertFalse(projection.allowed(token, Json.MAPPER.createArrayNode()));
        knowledge.makePrivate(1);
        try { assertFalse(projection.allowed(token, refs(1, 1, 1))); }
        finally { knowledge.makePublic(1); }
        assertEquals(searches, rag.knowledgeCalls());
        rag.assertNoModelGeneration();
    }

    private com.fasterxml.jackson.databind.JsonNode refs(long document, long chunk, int version) {
        return Json.MAPPER.createArrayNode().add(Json.object().put("documentId", document).put("chunkId", chunk).put("version", version));
    }

    @Test void delegatedRolesCannotGrowWhenCurrentUserGetsAdmin() {
        String run = UUID.randomUUID().toString();
        var binding = client.bind("Bearer " + app, "ops-dev", auth.login(10), run, Instant.now().plusSeconds(300));
        auth.addAdmin(10);
        var minted = client.token("Bearer " + app, binding.delegationId(), run, "rag");
        assertEquals(List.of("USER"), new InternalActorTokens(secret).verify(minted.authorization(), "rag").roles());
    }

    @Test void publicIngressUsesPersistentOwnerAcrossRagRestartAndSwitch() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var first = search(requestId, "Redis");
        assertEquals(200, first.statusCode());
        var body = Json.read(first.body()).path("data");
        assertEquals("LEGACY", body.path("owner").asText());
        assertTrue(body.path("result").path("evidence").asText().contains("SYNTH_PUBLIC"));
        String route = body.path("routeId").asText();
        rag.close();
        rag = new IsolatedRagHost(secret, auth.origin(), knowledge.origin(), ingress("HARNESS"));
        var retry = search(requestId, "Redis");
        assertEquals(200, retry.statusCode());
        assertEquals(route, Json.read(retry.body()).path("data").path("routeId").asText());
        assertEquals("LEGACY", Json.read(retry.body()).path("data").path("owner").asText());
        assertEquals(409, search(requestId, "Changed").statusCode());
        int before = rag.knowledgeCalls();
        // New request chooses HARNESS; unreachable platform must not fall back to legacy knowledge.
        assertEquals(503, search(UUID.randomUUID().toString(), "Redis").statusCode());
        assertEquals(before, rag.knowledgeCalls());
    }

    private Set<Long> run(long user) {
        var identity = client.introspect("Bearer " + app, "ops-dev", auth.login(user));
        var store = new InMemoryRunStore();
        var registry = new ToolRegistry();
        var binding = new HashMap<String, String>();
        var tool = new OpsAgentRagTool(OpsAgentRagConfig.defaults(rag.origin()),
                (endpoint, audience, context) -> client.token("Bearer " + app,
                        binding.get(context.runId()), context.runId(), audience).authorization());
        registry.register(tool.descriptor(), tool);
        try (var harness = new Harness(store, (request, context) -> { throw new AssertionError("No model"); }, registry)) {
            harness.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
            var actor = new Actor(identity.subject(), identity.projectId(), Set.of("run:create", "run:read",
                    "tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION));
            var current = harness.start(OpsAgentRetrievalPilot.definition("Redis", 20), actor,
                    List.of(OpsAgentRagTool.KEY), new Budget(100, 1, 1, 20), Duration.ofSeconds(60), UUID.randomUUID().toString());
            var delegated = client.bind("Bearer " + app, "ops-dev", auth.login(user), current.id, current.deadline);
            binding.put(current.id, delegated.delegationId());
            for (int i = 0; i < 20 && !current.status.equals(RunStatus.COMPLETED); i++) current = harness.tick(current.id);
            assertEquals(RunStatus.COMPLETED, current.status, current.stopReason);
            String persisted = Json.write(store.get(current.id));
            assertFalse(persisted.contains(app));
            assertFalse(persisted.contains(secret));
            assertFalse(persisted.contains("Bearer "));
            Set<Long> ids = new HashSet<>();
            current.output.path("citations").forEach(c -> ids.add(c.path("documentId").asLong()));
            return ids;
        }
    }

    private HttpResponse<String> search(String id, String query) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(rag.origin().resolve("/api/rag/harness-search"))
                .header("Authorization", "Bearer " + auth.login(10)).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(Json.object().put("requestId", id).put("query", query).put("topK", 20))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private IsolatedRagHost.Ingress ingress(String owner) {
        return new IsolatedRagHost.Ingress(ds, loginSecret, app, URI.create("http://127.0.0.1:1/"), owner,
                "00000000-0000-4000-8000-000000000001", "sha256:" + "a".repeat(64));
    }

    private static String random() { return UUID.randomUUID().toString() + UUID.randomUUID(); }
}
