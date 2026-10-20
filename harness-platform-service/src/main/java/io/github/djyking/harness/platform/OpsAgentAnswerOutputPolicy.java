package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.capabilities.workflow.WorkflowDefinition;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/** Generated Ops answers retain the current ACL of every source sent to the model. */
final class OpsAgentAnswerOutputPolicy implements ProtectedOutputPolicy {
  static final String AGENT = "ops-rag-answer";
  private final JdbcRunStore store;
  private final IdentityProvider identity;
  private final BiPredicate<String, JsonNode> authorize;

  OpsAgentAnswerOutputPolicy(JdbcRunStore store, IdentityProvider identity, URI origin) {
    this(store, identity, client(origin));
  }

  OpsAgentAnswerOutputPolicy(
      JdbcRunStore store, IdentityProvider identity, BiPredicate<String, JsonNode> authorize) {
    this.store = store;
    this.identity = identity;
    this.authorize = authorize;
  }

  static boolean accepts(Deployment.Release release) {
    var workflow = release.workflow();
    if (!AGENT.equals(release.agentId())
        || release.publicOutput()
        || release.model() == null
        || !release.toolKeys().isEmpty()
        || workflow.nodes().size() != 2
        || !(workflow.nodes().get(workflow.start()) instanceof WorkflowDefinition.Model model))
      return false;
    return workflow.nodes().get(model.next()) instanceof WorkflowDefinition.End end
        && end.output().equals(model.output())
        && model.prompt().user().equals("{{prompt}}")
        && !model.prompt().system().contains("{{")
        && model.inputBindings().equals(Map.of("prompt", "prompt"));
  }

  @Override
  public boolean canRead(
      IdentityProvider.Principal reader, PlatformRepository.Owned owned, JsonNode output) {
    if (!accepts(owned.release())
        || output == null
        || !output.isTextual()
        || !reader.project().equals(owned.project())
        || !reader.application().equals(owned.application())
        || !reader.subject().equals(owned.subject())
        || !reader.permits("runs:output:read")
        || !reader.permits("opsagent:rag:search")) return false;
    try {
      var run = store.get(owned.runId());
      if (run.payloadArchived) return false;
      JsonNode inputs = run.definition.spec().path("inputs");
      String context = inputs.path("contextId").asText();
      ApiJson.uuid(context);
      if (!inputs.path("prompt").isTextual()) return false;
      String authorization = identity.outputAuthorization(reader, owned);
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(inputs.path("prompt").asText().getBytes(StandardCharsets.UTF_8)));
      return authorize.test(
          authorization, Json.object().put("contextId", context).put("promptSha256", digest));
    } catch (Exception unavailable) {
      return false;
    }
  }

  static BiPredicate<String, JsonNode> client(URI origin) {
    if (origin == null) return (token, input) -> false;
    if (origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
        || !("https".equals(origin.getScheme())
            || ("http".equals(origin.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost()))))
      throw new IllegalArgumentException("OPSAGENT_RAG_ORIGIN_INVALID");
    var http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return (token, input) -> {
      if (token == null || !token.matches("Bearer [A-Za-z0-9._~-]{1,9990}")) return false;
      CompletableFuture<HttpResponse<byte[]>> pending = null;
      try {
        pending =
            http.sendAsync(
                HttpRequest.newBuilder(origin.resolve("/internal/rag/harness/output-answer-access"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(input)))
                    .build(),
                new LimitedBodyHandler(4096));
        var response = pending.get(5, TimeUnit.SECONDS);
        if (response.statusCode() != 200) return false;
        JsonNode result = Json.read(new String(response.body(), StandardCharsets.UTF_8));
        return result.path("code").isIntegralNumber()
            && result.path("code").canConvertToInt()
            && result.path("code").asInt(-1) == 0
            && result.path("data").path("allowed").isBoolean()
            && result.path("data").path("allowed").asBoolean();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception unavailable) {
        return false;
      } finally {
        if (pending != null && !pending.isDone()) pending.cancel(true);
      }
    };
  }
}
