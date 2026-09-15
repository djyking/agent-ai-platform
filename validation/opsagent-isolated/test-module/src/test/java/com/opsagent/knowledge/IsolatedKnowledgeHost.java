package com.opsagent.knowledge;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.isolation.LocalMvcServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Same-package constructor bridge; actual controller, service and repository execute unchanged. */
public final class IsolatedKnowledgeHost implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final LocalMvcServer http;
    private final KnowledgeIndexService index;
    private final DocumentParsePublisher publisher;
    private final FileStorageService storage;
    private final DocumentParserService parser;

    public IsolatedKnowledgeHost(String secret, URI auth) throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:opsagent-isolated-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", UUID.randomUUID().toString()));
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,original_name VARCHAR(255),"
                + "version INT,visibility VARCHAR(16),review_status VARCHAR(16),create_by BIGINT,"
                + "update_time TIMESTAMP,deleted TINYINT,ticket_id BIGINT)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,"
                + "chunk_index INT,content VARCHAR(2000),page_number INT)");
        document(1, "public-synthetic.md", "PUBLIC", "PUBLISHED", 10, 0, null, "Redis SYNTH_PUBLIC");
        document(2, "private-user20.md", "PRIVATE", "PUBLISHED", 20, 0, null, "Redis SYNTH_PRIVATE_20");
        document(3, "draft-synthetic.md", "PUBLIC", "DRAFT", 10, 0, null, "Redis SYNTH_DRAFT");
        document(4, "private-user10.md", "PRIVATE", "PUBLISHED", 10, 0, null, "Redis SYNTH_PRIVATE_10");
        document(5, "deleted-synthetic.md", "PUBLIC", "PUBLISHED", 10, 1, null, "Redis SYNTH_DELETED");
        document(6, "ticket-synthetic.md", "PUBLIC", "PUBLISHED", 10, 0, 42L, "Redis SYNTH_TICKET_DENIED");
        document(7, "experience-synthetic.md", "PUBLIC", "EXPERIENCE", 10, 0, null, "Redis SYNTH_EXPERIENCE");
        index = mock(KnowledgeIndexService.class);
        when(index.enabled()).thenReturn(false); // Explicit SQL fallback, no ES or embedding request.
        publisher = mock(DocumentParsePublisher.class);
        storage = mock(FileStorageService.class);
        parser = mock(DocumentParserService.class);
        var tickets = mock(TicketAccessClient.class);
        when(tickets.visibleTicketIds()).thenReturn(Set.of()); // No linked ticket authorization.
        var service = new KnowledgeService(new KnowledgeRepository(jdbc, new ObjectMapper()),
                storage, parser, publisher, index, mock(KnowledgeIndexCompensationService.class),
                new SimpleMeterRegistry(), new KnowledgeProperties(), tickets);
        http = new LocalMvcServer(new KnowledgeInternalAgentController(service, secret, auth.toString()));
    }

    private void document(long id, String name, String visibility, String review, long owner,
                          int deleted, Long ticket, String content) {
        jdbc.update("INSERT INTO knowledge_document VALUES(?,?,1,?,?,?,NOW(),?,?)",
                id, name, visibility, review, owner, deleted, ticket);
        jdbc.update("INSERT INTO knowledge_chunk VALUES(?,?,0,?,NULL)", id, id, content);
    }

    public URI origin() { return http.origin(); }

    public void makePrivate(long documentId) {
        jdbc.update("UPDATE knowledge_document SET visibility='PRIVATE' WHERE id=?", documentId);
    }

    public void makePublic(long documentId) {
        jdbc.update("UPDATE knowledge_document SET visibility='PUBLIC' WHERE id=?", documentId);
    }

    public void assertNoSideEffectDependencies() {
        verifyNoInteractions(storage, parser, publisher);
        verify(index, never()).candidateRows(any());
    }

    @Override public void close() {
        http.close();
        jdbc.execute("SHUTDOWN");
    }
}
