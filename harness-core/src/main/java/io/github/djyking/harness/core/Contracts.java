package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Stable, provider-independent contracts. Hosts authenticate actors and resolve credentials. */
public final class Contracts {
  private Contracts() {}

  public record Actor(String subject, String project, Set<String> permissions) {
    public Actor {
      Objects.requireNonNull(subject);
      Objects.requireNonNull(project);
      permissions = Collections.unmodifiableSortedSet(new TreeSet<>(permissions));
      if (subject.isBlank() || project.isBlank())
        throw new IllegalArgumentException("Actor scope required");
    }
  }

  public record Budget(long tokenLimit, int modelCalls, int toolCalls, int maxSteps) {
    public Budget {
      if (tokenLimit < 1 || modelCalls < 1 || toolCalls < 1 || maxSteps < 1)
        throw new IllegalArgumentException("Positive budgets required");
    }

    public static Budget defaults() {
      return new Budget(100_000, 12, 24, 150);
    }
  }

  public record ModelProfile(
      String id,
      String provider,
      String model,
      int contextTokens,
      int maxOutputTokens,
      long timeoutMillis,
      JsonNode parameters) {
    public ModelProfile {
      if (id == null
          || provider == null
          || model == null
          || contextTokens < 1
          || maxOutputTokens < 1
          || maxOutputTokens >= contextTokens
          || timeoutMillis < 1) throw new IllegalArgumentException("Invalid model profile");
      parameters = parameters == null ? Json.object() : parameters.deepCopy();
    }
  }

  public record ToolPolicy(
      boolean readOnly,
      boolean approvalRequired,
      boolean retrySafe,
      int maxAttempts,
      long timeoutMillis,
      Set<String> requiredPermissions) {
    public ToolPolicy {
      requiredPermissions = Collections.unmodifiableSortedSet(new TreeSet<>(requiredPermissions));
      if (maxAttempts < 1 || maxAttempts > 5 || timeoutMillis < 1)
        throw new IllegalArgumentException("Invalid tool policy");
    }

    public static ToolPolicy readOnlyPolicy() {
      return new ToolPolicy(true, false, true, 2, 10_000, Set.of());
    }

    public static ToolPolicy approvedWrite() {
      return new ToolPolicy(false, true, false, 1, 10_000, Set.of());
    }
  }

  public record ToolDescriptor(
      String key,
      String modelName,
      String description,
      String adapter,
      String remoteName,
      String version,
      JsonNode inputSchema,
      ToolPolicy policy) {
    public ToolDescriptor {
      Objects.requireNonNull(key);
      Objects.requireNonNull(adapter);
      Objects.requireNonNull(remoteName);
      Objects.requireNonNull(version);
      Objects.requireNonNull(policy);
      if (key.isBlank()
          || version.isBlank()
          || modelName == null
          || !modelName.matches("[a-zA-Z0-9_-]{1,64}"))
        throw new IllegalArgumentException("Invalid tool identity");
      inputSchema = Objects.requireNonNull(inputSchema).deepCopy();
      description = Objects.requireNonNullElse(description, "");
    }

    public String digest() {
      return Json.hash(this);
    }
  }

  public record ToolCall(String id, String name, JsonNode arguments) {
    public ToolCall {
      Objects.requireNonNull(id);
      Objects.requireNonNull(name);
      arguments = Objects.requireNonNull(arguments).deepCopy();
    }
  }

  public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public Message {
      Objects.requireNonNull(role);
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message text(String role, String content) {
      return new Message(role, content, List.of(), null);
    }

    public static Message tool(String callId, String content) {
      return new Message("tool", content, List.of(), callId);
    }
  }

  public record Usage(long inputTokens, long outputTokens, boolean known) {
    public Usage {
      if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Negative usage");
    }

    public long total() {
      return Math.addExact(inputTokens, outputTokens);
    }

    public static Usage unknown() {
      return new Usage(0, 0, false);
    }
  }

  public enum FinishReason {
    FINAL,
    TOOL_CALLS,
    LENGTH,
    REFUSED,
    UNKNOWN
  }

  public record ModelRequest(
      String invocationId,
      ModelProfile profile,
      List<Message> messages,
      List<ToolDescriptor> tools) {
    public ModelRequest {
      Objects.requireNonNull(invocationId);
      Objects.requireNonNull(profile);
      messages = List.copyOf(messages);
      tools = List.copyOf(tools);
    }
  }

  public record ModelResponse(Message message, FinishReason finishReason, Usage usage) {
    public ModelResponse {
      Objects.requireNonNull(message);
      Objects.requireNonNull(finishReason);
      usage = usage == null ? Usage.unknown() : usage;
    }
  }

  /** output may contain structured data, typed MCP content blocks and resource references. */
  public record ToolResult(JsonNode output, boolean error, String receipt) {
    public ToolResult {
      output = output == null ? Json.object() : output.deepCopy();
    }

    public static ToolResult success(JsonNode output) {
      return new ToolResult(output, false, null);
    }
  }

  public record ExecutionContext(
      String runId,
      String nodeId,
      String invocationId,
      String attemptId,
      Actor actor,
      Instant deadline,
      String traceId) {
    public Duration remaining(Clock clock) {
      Duration value = Duration.between(clock.instant(), deadline);
      return value.isNegative() ? Duration.ZERO : value;
    }
  }

  public enum FailureKind {
    INVALID,
    DENIED,
    TRANSIENT,
    UNKNOWN,
    PERMANENT
  }

  public static class InvocationException extends RuntimeException {
    private final FailureKind kind;

    public InvocationException(FailureKind kind, String safeCode) {
      super(safeCode);
      this.kind = Objects.requireNonNull(kind);
    }

    public InvocationException(FailureKind kind, String safeCode, Throwable cause) {
      super(safeCode, cause);
      this.kind = Objects.requireNonNull(kind);
    }

    public FailureKind kind() {
      return kind;
    }
  }

  @FunctionalInterface
  public interface ModelGateway {
    ModelResponse invoke(ModelRequest request, ExecutionContext context);
  }

  @FunctionalInterface
  public interface ToolHandler {
    ToolResult invoke(ToolDescriptor tool, JsonNode arguments, ExecutionContext context);
  }

  @FunctionalInterface
  public interface AccessPolicy {
    void check(Actor actor, String permission, String resource);

    static AccessPolicy actorPermissions() {
      return (actor, permission, resource) -> {
        if (!actor.permissions().contains("*") && !actor.permissions().contains(permission))
          throw new InvocationException(FailureKind.DENIED, "PERMISSION_DENIED");
      };
    }
  }

  public record ProgramDefinition(String id, String version, String program, JsonNode spec) {
    public ProgramDefinition {
      Objects.requireNonNull(id);
      Objects.requireNonNull(version);
      Objects.requireNonNull(program);
      spec = Objects.requireNonNull(spec).deepCopy();
    }
  }

  @FunctionalInterface
  public interface Program {
    Action next(RunState run);
  }

  /** Programs only update detached memory and return commands; the harness owns all effects. */
  public sealed interface Action
      permits ModelAction, ToolAction, WaitAction, CompleteAction, ContinueAction, FailAction {}

  public record ModelAction(String id, String nodeId, ModelRequest request) implements Action {}

  public record ToolAction(String id, String nodeId, String toolKey, JsonNode arguments)
      implements Action {}

  public record WaitAction(String id, String nodeId, String prompt) implements Action {}

  public record CompleteAction(JsonNode output) implements Action {}

  public record ContinueAction() implements Action {}

  public record FailAction(String code) implements Action {}

  public enum RunStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_APPROVAL,
    WAITING_INPUT,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    BUDGET_EXCEEDED,
    NEEDS_ATTENTION
  }

  public enum InvocationPhase {
    PREPARED,
    IN_FLIGHT,
    UNKNOWN
  }

  public record StepResult(String kind, JsonNode value, boolean error) {}

  public record Approval(
      String invocationId,
      String digest,
      Instant expiresAt,
      String status,
      String decidedBy,
      String reason) {}

  public record RunEvent(
      String type, String nodeId, String invocationId, Instant at, Map<String, String> attributes) {
    public RunEvent {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  public record StoredEvent(long sequence, RunEvent event) {}

  @FunctionalInterface
  public interface Telemetry {
    Span start(ExecutionContext context, String operation, String target);

    interface Span extends AutoCloseable {
      void outcome(String code);

      /**
       * Carry tracing context across the harness's bounded executor without a core tracing
       * dependency.
       */
      default <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        return task;
      }

      void close();
    }

    static Telemetry noop() {
      return (context, operation, target) ->
          new Span() {
            public void outcome(String code) {}

            public void close() {}
          };
    }
  }
}
