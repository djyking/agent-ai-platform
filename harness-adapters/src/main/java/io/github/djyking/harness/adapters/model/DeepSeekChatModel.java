package io.github.djyking.harness.adapters.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.adapters.http.LimitedBodyHandler;
import io.github.djyking.harness.core.Contracts.*;
import io.github.djyking.harness.core.Json;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek's documented chat completions API, text + native function tools, thinking explicitly
 * disabled.
 */
public final class DeepSeekChatModel implements ModelGateway {
  private final DeepSeekConfig config;
  private final HttpClient client;

  public DeepSeekChatModel(DeepSeekConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public ModelResponse invoke(ModelRequest request, ExecutionContext context) {
    long remaining = Duration.between(Instant.now(), context.deadline()).toMillis();
    long timeout =
        Math.min(
            remaining,
            Math.min(config.requestTimeout().toMillis(), request.profile().timeoutMillis()));
    if (timeout < 1)
      throw new InvocationException(FailureKind.PERMANENT, "MODEL_DEADLINE_EXCEEDED");
    ObjectNode body = encode(request);
    final HttpRequest httpRequest;
    try {
      var builder =
          HttpRequest.newBuilder(config.endpoint())
              .timeout(Duration.ofMillis(timeout))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
      config.headers().forCurrentCall().headers(config.endpoint()).forEach(builder::setHeader);
      httpRequest = builder.build();
    } catch (RuntimeException invalidConfiguration) {
      throw new InvocationException(FailureKind.INVALID, "MODEL_REQUEST_CONFIGURATION_INVALID");
    }

    CompletableFuture<HttpResponse<byte[]>> pending = null;
    final HttpResponse<byte[]> response;
    try {
      pending = client.sendAsync(httpRequest, new LimitedBodyHandler(config.maxResponseBytes()));
      response = pending.get(timeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      if (pending != null) pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new InvocationException(FailureKind.UNKNOWN, "MODEL_CALL_INTERRUPTED_USAGE_UNKNOWN");
    } catch (Exception transportFailure) {
      if (pending != null) pending.cancel(true);
      // Billing and generation may have completed even if the client lost the response.
      throw new InvocationException(FailureKind.UNKNOWN, "MODEL_CALL_OUTCOME_UNKNOWN");
    }
    int status = response.statusCode();
    if (status == 401 || status == 403)
      throw new InvocationException(FailureKind.DENIED, "MODEL_AUTHORIZATION_FAILED");
    if (status == 429) throw new InvocationException(FailureKind.TRANSIENT, "MODEL_RATE_LIMITED");
    if (status >= 500)
      throw new InvocationException(FailureKind.UNKNOWN, "MODEL_SERVER_ERROR_USAGE_UNKNOWN");
    if (status < 200 || status >= 300)
      throw new InvocationException(FailureKind.PERMANENT, "MODEL_HTTP_" + status);
    try {
      return decode(Json.MAPPER.readTree(response.body()), request);
    } catch (Exception malformedResponse) {
      // Do not attach exceptions containing raw model content or credential-bearing response
      // bodies.
      throw new InvocationException(FailureKind.UNKNOWN, "MODEL_RESPONSE_INVALID_USAGE_UNKNOWN");
    }
  }

  private ObjectNode encode(ModelRequest request) {
    ObjectNode body = Json.object();
    body.put("model", request.profile().model());
    body.put("stream", false);
    body.put("max_tokens", request.profile().maxOutputTokens());
    body.set("thinking", Json.object().put("type", "disabled"));
    var parameters = request.profile().parameters();
    if (!parameters.isObject())
      throw new InvocationException(FailureKind.INVALID, "MODEL_PARAMETERS_MUST_BE_OBJECT");
    Set<String> supported =
        Set.of(
            "temperature",
            "top_p",
            "frequency_penalty",
            "presence_penalty",
            "stop",
            "response_format");
    parameters
        .properties()
        .forEach(
            entry -> {
              if (!supported.contains(entry.getKey()))
                throw new InvocationException(FailureKind.INVALID, "UNSUPPORTED_MODEL_PARAMETER");
              body.set(entry.getKey(), entry.getValue());
            });
    var messages = body.putArray("messages");
    for (var message : request.messages()) {
      if (!Set.of("system", "user", "assistant", "tool").contains(message.role())) {
        throw new InvocationException(FailureKind.INVALID, "UNSUPPORTED_MESSAGE_ROLE");
      }
      var entry = messages.addObject().put("role", message.role());
      if (message.content() != null) entry.put("content", message.content());
      else entry.putNull("content");
      if (message.role().equals("tool")) {
        if (message.toolCallId() == null || message.toolCallId().isBlank()) {
          throw new InvocationException(FailureKind.INVALID, "MISSING_PROVIDER_TOOL_CALL_ID");
        }
        entry.put("tool_call_id", message.toolCallId());
      }
      if (!message.toolCalls().isEmpty()) {
        if (!message.role().equals("assistant"))
          throw new InvocationException(FailureKind.INVALID, "TOOL_CALLS_REQUIRE_ASSISTANT");
        var calls = entry.putArray("tool_calls");
        for (var call : message.toolCalls()) {
          var item = calls.addObject().put("id", call.id()).put("type", "function");
          item.putObject("function")
              .put("name", call.name())
              .put("arguments", Json.write(call.arguments()));
        }
      }
    }
    if (!request.tools().isEmpty()) {
      var tools = body.putArray("tools");
      var names = new HashSet<String>();
      for (var tool : request.tools()) {
        if (!names.add(tool.modelName()))
          throw new InvocationException(FailureKind.INVALID, "DUPLICATE_MODEL_TOOL_NAME");
        var function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", tool.modelName()).put("description", tool.description());
        function.set("parameters", tool.inputSchema());
      }
      body.put("tool_choice", "auto");
    }
    return body;
  }

  private ModelResponse decode(JsonNode response, ModelRequest request) {
    var choices = response.path("choices");
    if (!choices.isArray() || choices.size() != 1)
      throw new IllegalArgumentException("Expected one choice");
    var choice = choices.get(0);
    var message = choice.path("message");
    if (!message.isObject() || !message.path("role").asText().equals("assistant")) {
      throw new IllegalArgumentException("Expected assistant message");
    }
    var content = message.get("content");
    if (content != null && !content.isNull() && !content.isTextual())
      throw new IllegalArgumentException("Text content required");
    var calls = new ArrayList<ToolCall>();
    var ids = new HashSet<String>();
    var allowedNames = new HashSet<String>();
    request.tools().forEach(tool -> allowedNames.add(tool.modelName()));
    var rawCalls = message.get("tool_calls");
    if (rawCalls != null && !rawCalls.isNull()) {
      if (!rawCalls.isArray()) throw new IllegalArgumentException("Tool calls must be array");
      for (var call : rawCalls) {
        String id = requiredText(call, "id");
        if (!ids.add(id) || !requiredText(call, "type").equals("function"))
          throw new IllegalArgumentException("Invalid tool call");
        var function = call.path("function");
        String name = requiredText(function, "name");
        if (!allowedNames.contains(name)) throw new IllegalArgumentException("Undisclosed tool");
        var arguments = Json.read(requiredText(function, "arguments"));
        if (!arguments.isObject())
          throw new IllegalArgumentException("Tool arguments must be object");
        calls.add(new ToolCall(id, name, arguments));
      }
    }
    String finish = requiredText(choice, "finish_reason");
    FinishReason reason =
        switch (finish) {
          case "stop" -> FinishReason.FINAL;
          case "tool_calls" -> FinishReason.TOOL_CALLS;
          case "length" -> FinishReason.LENGTH;
          case "content_filter" -> FinishReason.REFUSED;
          default -> FinishReason.UNKNOWN;
        };
    if ((reason == FinishReason.TOOL_CALLS) != !calls.isEmpty())
      throw new IllegalArgumentException("Tool call finish mismatch");
    if (reason == FinishReason.FINAL && (content == null || content.isNull())) {
      throw new IllegalArgumentException("A final answer must contain text");
    }
    var rawUsage = response.get("usage");
    Usage usage = Usage.unknown();
    if (rawUsage != null && !rawUsage.isNull()) {
      var input = rawUsage.get("prompt_tokens");
      var output = rawUsage.get("completion_tokens");
      if (input == null
          || output == null
          || !input.isIntegralNumber()
          || !output.isIntegralNumber()
          || !input.canConvertToLong()
          || !output.canConvertToLong()) throw new IllegalArgumentException("Invalid usage");
      usage = new Usage(input.longValue(), output.longValue(), true);
      usage.total();
    }
    return new ModelResponse(
        new Message(
            "assistant",
            content == null || content.isNull() ? null : content.textValue(),
            List.copyOf(calls),
            null),
        reason,
        usage);
  }

  private String requiredText(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank())
      throw new IllegalArgumentException("Required text missing");
    return value.textValue();
  }
}
