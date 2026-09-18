package com.opsagent.rag;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.isolation.LocalMvcServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real controller/search/context/reranker; explicit loopback transport replaces Feign discovery. */
public final class IsolatedRagHost implements AutoCloseable {
    private final LocalMvcServer http;
    private final InternalAgentModelService models = mock(InternalAgentModelService.class);
    private final InternalAgentUsageService usage = mock(InternalAgentUsageService.class);
    private final RagRateLimiter rate = mock(RagRateLimiter.class);
    private final AtomicInteger knowledgeCalls = new AtomicInteger();

    public IsolatedRagHost(String secret, URI auth, URI knowledge) throws Exception {
        this(secret, auth, knowledge, null);
    }

    public IsolatedRagHost(String secret, URI auth, URI knowledge, Ingress ingress) throws Exception {
        this(secret, auth, knowledge, ingress, 0);
    }

    public IsolatedRagHost(String secret, URI auth, URI knowledge, Ingress ingress, int port) throws Exception {
        if (!"127.0.0.1".equals(knowledge.getHost())) throw new IllegalArgumentException("Loopback only");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var json = new ObjectMapper();
        InternalKnowledgeClient transport = (authorization, request) -> {
            knowledgeCalls.incrementAndGet();
            try {
                var response = client.send(HttpRequest.newBuilder(knowledge.resolve("/internal/agent/search"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .header("Authorization", authorization)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("Knowledge HTTP denied");
                return json.readValue(response.body(),
                        new TypeReference<KnowledgeClient.Envelope<List<Map<String, Object>>>>() {});
            } catch (Exception failure) {
                throw new IllegalStateException("Isolated knowledge transport failed");
            }
        };
        var properties = new RagProperties();
        properties.setRerankEnabled(false);
        properties.setRerankTopN(20);
        var metrics = new SimpleMeterRegistry();
        var rerank = new RerankService(properties, new BgeRemoteRerankProvider(properties),
                new NoOpRerankProvider(), metrics);
        var search = new InternalAgentSearchService(transport, rerank,
                new ContextAssembler(properties, metrics), rate);
        var internal = new InternalAgentController(models, search, usage, secret, auth.toString());
        var projection = new HarnessCitationAccessController(secret, auth.toString(), knowledge.toString());
        if (ingress == null) http = new LocalMvcServer(port, null, new Object[] {internal, projection});
        else {
            var props = new com.opsagent.common.security.JwtProperties();
            props.setSecret(ingress.loginSecret());
            var jwtFilter = new com.opsagent.common.security.JwtAuthenticationFilter(
                    new com.opsagent.common.security.JwtService(props),
                    com.opsagent.common.security.VisitorSessionVerifier.remote(secret, auth.toString()));
            jakarta.servlet.Filter security = (request, response, chain) -> {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                try { jwtFilter.doFilter(request, response, chain); }
                finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            };
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("harness-route-schema.sql")).execute(ingress.dataSource());
            var publicSearch = new HarnessSearchController(new org.springframework.jdbc.core.JdbcTemplate(ingress.dataSource()),
                    internal, auth.toString(), ingress.platform().toString(), ingress.applicationCredential(), "ops-dev",
                    ingress.owner(), "ops-readonly", ingress.releaseId(), ingress.releaseDigest());
            http = new LocalMvcServer(port, security, new Object[] {internal, publicSearch, projection});
        }
    }

    public URI origin() { return http.origin(); }
    public int knowledgeCalls() { return knowledgeCalls.get(); }
    public void assertNoModelGeneration() { verifyNoInteractions(models, usage); }
    @Override public void close() { http.close(); }
    public record Ingress(javax.sql.DataSource dataSource, String loginSecret, String applicationCredential,
            URI platform, String owner, String releaseId, String releaseDigest) {}
}
