package com.opsagent.isolation;

import static io.github.djyking.harness.core.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.knowledge.IsolatedKnowledgeHost;
import com.opsagent.rag.IsolatedRagHost;
import io.github.djyking.harness.capabilities.workflow.WorkflowProgram;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.integrations.opsagent.*;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Source-backed integration: real OpsAgent internal controllers/security/search/SQL, local fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsolatedOpsAgentPilotTest {
    private String secret;
    private InternalActorTokens tokens;
    private IsolatedAuthHost auth;
    private IsolatedKnowledgeHost knowledge;
    private IsolatedRagHost rag;

    @BeforeAll void start() throws Exception {
        byte[] random = new byte[48]; new SecureRandom().nextBytes(random);
        secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tokens = new InternalActorTokens(secret);
        auth = new IsolatedAuthHost(secret);
        knowledge = new IsolatedKnowledgeHost(secret, auth.origin());
        rag = new IsolatedRagHost(secret, auth.origin(), knowledge.origin());
    }

    @BeforeEach void resetIdentity() { auth.reset(); }

    @AfterEach void noGenerationOrWrites() {
        knowledge.assertNoSideEffectDependencies();
        rag.assertNoModelGeneration();
    }

    @AfterAll void close() {
        if (rag != null) rag.close();
        if (knowledge != null) knowledge.close();
        if (auth != null) auth.close();
    }

    @Test void userTenSeesPublicAndOwnPrivateWithRealHttpCitations() {
        RunState run = run(10, List.of("USER"), "rag");
        assertEquals(RunStatus.COMPLETED, run.status, run.stopReason);
        assertEquals(Set.of(1L, 4L), documentIds(run.output));
        assertTrue(run.output.path("evidence").asText().contains("SYNTH_PUBLIC"));
        assertFalse(run.output.path("evidence").asText().contains("SYNTH_PRIVATE_20"));
        assertEquals(2, auth.verifiedCalls(), "RAG and Knowledge each independently revalidate Auth");
    }

    @Test void userTwentySeesDifferentPrivateScopeWithoutChangingHarness() {
        RunState run = run(20, List.of("USER"), "rag");
        assertEquals(RunStatus.COMPLETED, run.status, run.stopReason);
        assertEquals(Set.of(1L, 2L), documentIds(run.output));
        assertFalse(run.output.path("evidence").asText().contains("SYNTH_PRIVATE_10"));
    }

    @Test void claimedAdminIsReducedToCurrentRoleAndDraftsRemainExcluded() {
        RunState reduced = run(10, List.of("ADMIN", "USER"), "rag");
        assertEquals(RunStatus.COMPLETED, reduced.status, reduced.stopReason);
        assertEquals(Set.of(1L, 4L), documentIds(reduced.output));
        auth.user(10, true, List.of("ADMIN", "USER"));
        RunState admin = run(10, List.of("ADMIN", "USER"), "rag");
        assertEquals(RunStatus.COMPLETED, admin.status, admin.stopReason);
        assertEquals(Set.of(1L, 2L, 4L), documentIds(admin.output));
        String evidence = admin.output.path("evidence").asText();
        for (String excluded : List.of("SYNTH_DRAFT", "SYNTH_DELETED", "SYNTH_TICKET_DENIED", "SYNTH_EXPERIENCE"))
            assertFalse(evidence.contains(excluded));
    }

    @Test void currentRoleElevationDoesNotExpandFrozenIdentityCeiling() {
        auth.user(10, true, List.of("ADMIN", "USER"));
        RunState run = run(10, List.of("USER"), "rag");
        assertEquals(RunStatus.COMPLETED, run.status, run.stopReason);
        assertEquals(Set.of(1L, 4L), documentIds(run.output));
    }

    @Test void roleRevocationFailsBeforeKnowledgeTransport() {
        int before = rag.knowledgeCalls();
        auth.user(10, true, List.of("AUDITOR"));
        RunState denied = run(10, List.of("USER"), "rag");
        assertDenied(denied);
        assertEquals(before, rag.knowledgeCalls());
        assertEquals(1, auth.verifiedCalls());
    }

    @Test void disabledUserFailsBeforeKnowledgeTransport() {
        int before = rag.knowledgeCalls();
        auth.user(10, false, List.of("USER"));
        assertDenied(run(10, List.of("USER"), "rag"));
        assertEquals(before, rag.knowledgeCalls());
    }

    @Test void revocationBetweenRagAndKnowledgeIsRevalidatedAtKnowledge() {
        int before = rag.knowledgeCalls();
        auth.disableAfterNextVerification();
        assertDenied(run(10, List.of("USER"), "rag"));
        assertEquals(before + 1, rag.knowledgeCalls());
        assertEquals(2, auth.verifiedCalls());
    }

    @Test void wrongAudienceNeverReachesAuthOrKnowledge() {
        int before = rag.knowledgeCalls();
        assertDenied(run(10, List.of("USER"), "knowledge"));
        assertEquals(0, auth.verifiedCalls());
        assertEquals(before, rag.knowledgeCalls());
    }

    @Test void invalidSignatureIsRejectedByRealInternalController() throws Exception {
        var otherTokens = new InternalActorTokens(UUID.randomUUID() + UUID.randomUUID().toString());
        String token = otherTokens.issue("rag", context(10, List.of("USER"), "signature-check"));
        JsonNode reply = post(rag.origin().resolve("/internal/rag/search"), "Bearer " + token);
        assertEquals(40100, reply.path("code").asInt());
        assertEquals(0, auth.verifiedCalls());
    }

    @Test void currentPublicationVisibilityIsQueriedAgainForNextRun() {
        assertEquals(Set.of(1L, 2L), documentIds(run(20, List.of("USER"), "rag").output));
        knowledge.makePrivate(1);
        try {
            assertEquals(Set.of(2L), documentIds(run(20, List.of("USER"), "rag").output));
        } finally {
            knowledge.makePublic(1);
        }
    }

    @Test void newRequestRoutingFreezesOwnershipAndDoesNotFallbackAfterFailure() {
        var router = new NewRequestOwnership();
        var harnessCalls = new AtomicInteger(); var legacyCalls = new AtomicInteger();
        router.submit("request-1", true, () -> harnessCalls.incrementAndGet(), () -> legacyCalls.incrementAndGet());
        router.submit("request-1", false, () -> harnessCalls.incrementAndGet(), () -> legacyCalls.incrementAndGet());
        router.submit("request-2", false, () -> harnessCalls.incrementAndGet(), () -> legacyCalls.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> router.submit("request-3", true,
                () -> { harnessCalls.incrementAndGet(); throw new IllegalStateException("synthetic dispatch failure"); },
                () -> legacyCalls.incrementAndGet()));
        router.submit("request-3", false, () -> harnessCalls.incrementAndGet(), () -> legacyCalls.incrementAndGet());
        assertEquals(2, harnessCalls.get()); assertEquals(1, legacyCalls.get());
        assertEquals("HARNESS", router.owner("request-1"));
        assertEquals("LEGACY", router.owner("request-2"));
        assertEquals("HARNESS", router.owner("request-3"));
    }

    private RunState run(long user, List<String> claimedRoles, String issuedAudience) {
        var issuedTokens = new ArrayList<String>();
        Actor actor = new Actor("synthetic-user-" + user, "isolated-ops-pilot",
                Set.of("run:create", "run:read", "tool:" + OpsAgentRagTool.KEY, OpsAgentRagTool.PERMISSION));
        var tool = new OpsAgentRagTool(OpsAgentRagConfig.defaults(rag.origin()), (endpoint, audience, execution) -> {
            assertEquals(rag.origin().resolve("/internal/rag/search"), endpoint);
            assertEquals("rag", audience);
            assertEquals(actor, execution.actor());
            String credential = tokens.issue(issuedAudience, context(user, claimedRoles, execution.runId()));
            issuedTokens.add(credential);
            return "Bearer " + credential;
        });
        var registry = new ToolRegistry(); registry.register(tool.descriptor(), tool);
        var store = new InMemoryRunStore();
        try (var harness = new Harness(store, (request, execution) -> {
            throw new AssertionError("No model generation is permitted in this isolated pilot");
        }, registry)) {
            harness.registerProgram("workflow", new WorkflowProgram());
            var started = harness.start(OpsAgentRetrievalPilot.definition("Redis", 20), actor,
                    List.of(OpsAgentRagTool.KEY), new Budget(1000, 1, 1, 20),
                    Duration.ofSeconds(45), UUID.randomUUID().toString());
            RunState current = started;
            for (int index = 0; index < 15 && Set.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(current.status); index++)
                current = harness.tick(current.id);
            assertEquals(0, current.modelCalls); assertEquals(0, current.chargedTokens);
            assertEquals(1, current.toolCalls, "Exactly one HTTP tool dispatch; no retry/fallback");
            String persisted = Json.write(current) + Json.write(store.events(current.id, 0, 1000));
            assertFalse(persisted.contains(secret), "Signing material must not enter the ledger");
            for (String token : issuedTokens)
                assertFalse(persisted.contains(token), "Authorization must not enter the ledger");
            assertFalse(persisted.contains("Bearer "), "Authorization headers must not enter the ledger");
            return current;
        }
    }

    private InternalActorTokens.Context context(long user, List<String> roles, String runId) {
        return new InternalActorTokens.Context(user, "synthetic-user-" + user, roles, runId,
                "isolated-synthetic", Instant.now().plusSeconds(60));
    }

    private static Set<Long> documentIds(JsonNode output) {
        assertNotNull(output);
        Set<Long> ids = new HashSet<>();
        for (JsonNode citation : output.path("citations")) {
            assertFalse(citation.path("sourceId").asText().isBlank());
            assertTrue(citation.path("chunkId").asLong() > 0);
            ids.add(citation.path("documentId").asLong());
        }
        return ids;
    }

    private static void assertDenied(RunState run) {
        assertEquals(RunStatus.NEEDS_ATTENTION, run.status, run.stopReason);
        assertEquals("OPSAGENT_RETRIEVAL_DENIED", run.stopReason);
        assertTrue(run.output == null || run.output.isNull(), "Denied retrieval must have no output");
    }

    private static JsonNode post(URI endpoint, String authorization) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5)).header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"Redis\",\"topK\":20}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        return Json.read(response.body());
    }

    /** Test-only routing policy fixture, not a production durable ingress implementation. */
    static final class NewRequestOwnership {
        private final Map<String, String> owners = new HashMap<>();
        synchronized void submit(String request, boolean enabled, Runnable harness, Runnable legacy) {
            if (owners.containsKey(request)) return;
            owners.put(request, enabled ? "HARNESS" : "LEGACY");
            if (enabled) harness.run(); else legacy.run();
        }
        String owner(String request) { return owners.get(request); }
    }
}
