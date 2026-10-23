package io.github.djyking.harness.evals;

import static io.github.djyking.harness.core.Contracts.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.util.*;

/** Explicit offline fixtures. There is deliberately no delegate, network or fallback path. */
public final class RecordedExchanges {
  private RecordedExchanges() {}

  public enum Classification {
    SYNTHETIC,
    REDACTED
  }

  /** A declaration of review, not an automatic redactor. Review fixture payloads before saving. */
  public record Metadata(
      String datasetId, String project, Classification classification, String redactionReview) {
    public Metadata {
      if (datasetId == null
          || datasetId.isBlank()
          || project == null
          || project.isBlank()
          || classification == null
          || redactionReview == null
          || redactionReview.isBlank())
        throw new IllegalArgumentException("Fixture provenance and redaction review required");
    }
  }

  public record ModelExchange(Metadata metadata, String fingerprint, ModelResponse response) {
    public ModelExchange {
      Objects.requireNonNull(metadata);
      requireFingerprint(fingerprint);
      response = Json.copy(Objects.requireNonNull(response), ModelResponse.class);
    }
  }

  public record ToolExchange(Metadata metadata, String fingerprint, ToolResult response) {
    public ToolExchange {
      Objects.requireNonNull(metadata);
      requireFingerprint(fingerprint);
      response = Json.copy(Objects.requireNonNull(response), ToolResult.class);
    }
  }

  /** Runtime ids/deadlines are excluded; actor, node and every semantic request field are bound. */
  public static String modelFingerprint(ModelRequest request, ExecutionContext context) {
    return Json.hash(
        Map.of(
            "actor",
            context.actor(),
            "nodeId",
            context.nodeId(),
            "profile",
            request.profile(),
            "messages",
            request.messages(),
            "tools",
            request.tools()));
  }

  public static String toolFingerprint(
      ToolDescriptor tool, JsonNode arguments, ExecutionContext context) {
    return Json.hash(
        Map.of(
            "actor",
            context.actor(),
            "nodeId",
            context.nodeId(),
            "tool",
            tool,
            "arguments",
            arguments));
  }

  public static ModelExchange model(
      Metadata metadata, ModelRequest request, ExecutionContext context, ModelResponse response) {
    requireScope(metadata, context);
    return new ModelExchange(metadata, modelFingerprint(request, context), response);
  }

  public static ToolExchange tool(
      Metadata metadata,
      ToolDescriptor descriptor,
      JsonNode arguments,
      ExecutionContext context,
      ToolResult response) {
    requireScope(metadata, context);
    return new ToolExchange(metadata, toolFingerprint(descriptor, arguments, context), response);
  }

  public static final class RecordedModel implements ModelGateway {
    private final Map<String, ModelExchange> entries = new HashMap<>();

    public RecordedModel(List<ModelExchange> exchanges) {
      for (ModelExchange exchange : exchanges) {
        ModelExchange frozen = Json.copy(exchange, ModelExchange.class);
        if (entries.putIfAbsent(frozen.fingerprint(), frozen) != null)
          throw new IllegalArgumentException("Duplicate model fingerprint");
      }
    }

    @Override
    public ModelResponse invoke(ModelRequest request, ExecutionContext context) {
      ModelExchange exchange = entries.get(modelFingerprint(request, context));
      if (exchange == null) throw new InvocationException(FailureKind.PERMANENT, "REPLAY_MISS");
      requireScope(exchange.metadata(), context);
      return Json.copy(exchange.response(), ModelResponse.class);
    }
  }

  public static final class RecordedTool implements ToolHandler {
    private final Map<String, ToolExchange> entries = new HashMap<>();

    public RecordedTool(List<ToolExchange> exchanges) {
      for (ToolExchange exchange : exchanges) {
        ToolExchange frozen = Json.copy(exchange, ToolExchange.class);
        if (entries.putIfAbsent(frozen.fingerprint(), frozen) != null)
          throw new IllegalArgumentException("Duplicate tool fingerprint");
      }
    }

    @Override
    public ToolResult invoke(ToolDescriptor tool, JsonNode arguments, ExecutionContext context) {
      ToolExchange exchange = entries.get(toolFingerprint(tool, arguments, context));
      if (exchange == null) throw new InvocationException(FailureKind.PERMANENT, "REPLAY_MISS");
      requireScope(exchange.metadata(), context);
      return Json.copy(exchange.response(), ToolResult.class);
    }
  }

  private static void requireFingerprint(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 fingerprint required");
  }

  private static void requireScope(Metadata metadata, ExecutionContext context) {
    if (!metadata.project().equals(context.actor().project()))
      throw new InvocationException(FailureKind.DENIED, "REPLAY_SCOPE_MISMATCH");
  }
}
