package com.opsagent.isolation;

import com.opsagent.auth.IsolatedSqlAuthHost;
import com.opsagent.knowledge.IsolatedKnowledgeHost;
import com.opsagent.rag.IsolatedRagHost;
import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Local acceptance host for selected real Auth/RAG/Knowledge controllers and SQL repositories.
 * Deliberately does not load the original production resource files or service discovery configuration.
 */
public final class IsolatedBridgeMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected private config file path");
        var config = Json.read(Files.readString(Path.of(args[0])));
        String url = config.path("jdbcUrl").asText();
        if (!url.startsWith("jdbc:mysql://127.0.0.1:")
                || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/opsagent_harness_pilot[a-z0-9_]*(\\?.*)?")) {
            throw new IllegalArgumentException("Dedicated local pilot MySQL schema required");
        }
        var ds = new DriverManagerDataSource(url, config.path("jdbcUser").asText(), config.path("jdbcPassword").asText());
        String internalSecret = random();
        String loginSecret = random();
        String application = config.path("applicationCredential").asText(random());
        Path metadata = Path.of(config.path("metadataFile").asText()).toAbsolutePath();
        Path stop = Path.of(config.path("stopFile").asText()).toAbsolutePath();
        if (Files.exists(metadata) || Files.exists(stop)) throw new IllegalArgumentException("Fresh runtime paths required");
        try (var auth = new IsolatedSqlAuthHost(ds, internalSecret, application, loginSecret,
                     config.path("browserPassword").isTextual() ? config.path("browserPassword").asText() : null);
             var knowledge = new IsolatedKnowledgeHost(internalSecret, auth.origin(), ds)) {
            prepareAcceptanceGrants(auth.jdbc());
            prepareConfiguredGrants(auth.jdbc(), config);
            IsolatedRagHost rag = new IsolatedRagHost(internalSecret, auth.origin(), knowledge.origin(),
                    ingress(config, ds, loginSecret, application));
            try {
            var info = Json.object().put("authOrigin", auth.origin().toString()).put("ragOrigin", rag.origin().toString())
                    .put("knowledgeOrigin", knowledge.origin().toString()).put("applicationId", "opsagent-pilot")
                    .put("projectId", "ops-dev").put("applicationCredential", application)
                    .put("user10Token", auth.login(10)).put("user20Token", auth.login(20))
                    .put("mysqlBacked", true).put("originalProductionBootConfiguration", false)
                    .put("scope", "Selected real controllers/services/mappers; SQL synthetic identities and documents; no model generation");
            Files.writeString(metadata, Json.write(info), StandardOpenOption.CREATE_NEW);
            var configTime = Files.getLastModifiedTime(Path.of(args[0]));
            while (!Files.exists(stop)) {
                Thread.sleep(250);
                var updated = Files.getLastModifiedTime(Path.of(args[0]));
                if (!updated.equals(configTime)) {
                    var next = Json.read(Files.readString(Path.of(args[0])));
                    int port = rag.origin().getPort();
                    rag.close();
                    rag = new IsolatedRagHost(internalSecret, auth.origin(), knowledge.origin(),
                            ingress(next, ds, loginSecret, application), port);
                    configTime = updated;
                    info.put("ingressOwner", next.path("newOwner").asText("LEGACY"));
                    info.put("ingressReloadedAt", java.time.Instant.now().toString());
                    Files.writeString(metadata, Json.write(info));
                }
            }
            knowledge.assertNoSideEffectDependencies();
            rag.assertNoModelGeneration();
            } finally { rag.close(); }
        }
    }

    private static IsolatedRagHost.Ingress ingress(com.fasterxml.jackson.databind.JsonNode config,
            javax.sql.DataSource dataSource, String loginSecret, String application) {
        return new IsolatedRagHost.Ingress(dataSource, loginSecret, application,
                URI.create(config.path("platformOrigin").asText("http://127.0.0.1:1/")),
                config.path("newOwner").asText("LEGACY"),
                config.path("releaseId").asText("00000000-0000-4000-8000-000000000001"),
                config.path("releaseDigest").asText("sha256:" + "a".repeat(64)));
    }

    private static String random() { return UUID.randomUUID().toString() + UUID.randomUUID(); }

    private static void prepareConfiguredGrants(org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.fasterxml.jackson.databind.JsonNode config) {
        for (var app : config.path("additionalApplications")) {
            String id = app.path("id").asText(), credential = app.path("credential").asText();
            if (!id.matches("[a-z][a-z0-9-]{0,63}") || credential.length() < 32)
                throw new IllegalArgumentException("Invalid isolated application configuration");
            String hash = java.util.HexFormat.of().formatHex(sha256(credential));
            int count = jdbc.update("UPDATE ops_harness_application SET credential_hash=?,enabled=1 WHERE application_id=?", hash, id);
            if (count == 0) jdbc.update("INSERT INTO ops_harness_application VALUES(?,?,1)", id, hash);
            for (var grant : app.path("grants")) {
                String project = grant.path("project").asText();
                long user = grant.path("user").asLong();
                if (!project.matches("[a-z][a-z0-9-]{0,63}") || (user != 10 && user != 20) || !grant.path("permissions").isArray())
                    throw new IllegalArgumentException("Invalid isolated application grant");
                String scopes = Json.write(grant.path("permissions"));
                int changed = jdbc.update("UPDATE ops_harness_grant SET permissions_json=?,enabled=1 WHERE application_id=? AND project_id=? AND user_id=?",
                        scopes, id, project, user);
                if (changed == 0) jdbc.update("INSERT INTO ops_harness_grant VALUES(?,?,?,'USER',?,1)", id, project, user, scopes);
            }
        }
    }

    private static byte[] sha256(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void prepareAcceptanceGrants(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        String owner = "[\"runs:create\",\"runs:read\",\"runs:list\",\"runs:events:read\",\"runs:control\","
                + "\"runs:output:read\",\"runs:reconcile:read\",\"runs:reconcile\",\"model:invoke\","
                + "\"tool:mcp:acceptance-fixture:probe_read\",\"tool:mcp:acceptance-fixture:probe_hold_read\","
                + "\"tool:mcp:acceptance-fixture:probe_write\",\"tool:mcp:acceptance-fixture:probe_unknown_write\"]";
        String reviewer = "[\"approvals:read\",\"approvals:decide\",\"approvals:review\"]";
        for (long user : java.util.List.of(10L, 20L)) {
            String scopes = user == 10 ? owner : reviewer;
            int updated = jdbc.update("UPDATE ops_harness_grant SET permissions_json=?,enabled=1 "
                    + "WHERE application_id='opsagent-pilot' AND project_id='platform-acceptance' AND user_id=?", scopes, user);
            if (updated == 0) jdbc.update("INSERT INTO ops_harness_grant VALUES('opsagent-pilot','platform-acceptance',?,'USER',?,1)", user, scopes);
        }
        String pilot = "[\"runs:create\",\"runs:read\",\"runs:list\",\"runs:events:read\",\"runs:control\","
                + "\"runs:output:read\",\"runs:reconcile:read\",\"runs:reconcile\",\"model:invoke\","
                + "\"approvals:read\",\"approvals:decide\",\"approvals:review\","
                + "\"tool:opsagent/rag-search\",\"opsagent:rag:search\",\"tool:docs:search\"]";
        jdbc.update("UPDATE ops_harness_grant SET permissions_json=?,enabled=1 "
                + "WHERE application_id='opsagent-pilot' AND project_id='ops-dev' AND user_id IN (10,20)", pilot);
    }
}
