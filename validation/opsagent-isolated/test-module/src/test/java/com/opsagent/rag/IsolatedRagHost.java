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
        http = new LocalMvcServer(new InternalAgentController(models, search, usage, secret, auth.toString()));
    }

    public URI origin() { return http.origin(); }
    public int knowledgeCalls() { return knowledgeCalls.get(); }
    public void assertNoModelGeneration() { verifyNoInteractions(models, usage); }
    @Override public void close() { http.close(); }
}
