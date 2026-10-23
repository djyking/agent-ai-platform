package com.opsagent.isolation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit Auth fixture: verifies the real signed auth token, then consults independent test users. */
public final class IsolatedAuthHost implements AutoCloseable {
    public record User(boolean active, List<String> roles) {}
    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "isolated-auth"); thread.setDaemon(true); return thread;
    });
    private final InternalActorTokens tokens;
    private final AtomicInteger verified = new AtomicInteger();
    private volatile boolean disableAfterNextVerification;

    public IsolatedAuthHost(String secret) throws Exception {
        tokens = new InternalActorTokens(secret);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/internal/agent/actors/", exchange -> {
            int status = 200;
            Map<String, Object> body;
            try {
                if (!"GET".equals(exchange.getRequestMethod())) throw new IllegalArgumentException();
                var actor = tokens.verify(exchange.getRequestHeaders().getFirst("Authorization"), "auth");
                long target = Long.parseLong(exchange.getRequestURI().getPath().substring("/internal/agent/actors/".length()));
                if (target != actor.userId()) throw new IllegalArgumentException();
                User current = users.get(target);
                if (current == null) throw new IllegalArgumentException();
                verified.incrementAndGet();
                body = Map.of("code", 0, "data", Map.of("userId", target,
                        "username", "synthetic-user-" + target, "active", current.active(),
                        "roles", current.roles(), "reasonCode", current.active() ? "" : "ACTOR_DISABLED"));
                if (disableAfterNextVerification) {
                    disableAfterNextVerification = false;
                    users.put(target, new User(false, current.roles()));
                }
            } catch (RuntimeException denied) {
                status = 401;
                body = Map.of("code", 40100, "message", "ISOLATED_AUTH_DENIED");
            }
            byte[] bytes = new ObjectMapper().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) { response.write(bytes); }
        });
        reset();
        server.start();
    }

    public URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
    public int verifiedCalls() { return verified.get(); }
    public void user(long id, boolean active, List<String> roles) { users.put(id, new User(active, roles)); }
    public void disableAfterNextVerification() { disableAfterNextVerification = true; }
    public void reset() {
        users.clear(); user(10, true, List.of("USER")); user(20, true, List.of("USER"));
        verified.set(0); disableAfterNextVerification = false;
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
