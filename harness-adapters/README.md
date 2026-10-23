# Provider and MCP adapters

This optional module bridges external protocols into `Contracts.ModelGateway` and `Contracts.ToolHandler`.
It contains no agent loop, approval database, application authentication system or retry scheduler. Those
remain in the common harness and host application.

## DeepSeek model protocol

`DeepSeekChatModel` implements the documented non-streaming Chat Completions wire protocol using Java 17
`HttpClient`. The host selects the model in its immutable `ModelProfile`. Requests include native
function schemas, assistant `tool_calls`, paired tool-message `tool_call_id`, `max_tokens`, and an explicit
`thinking: {"type":"disabled"}`. The provider's call IDs remain separate from harness invocation IDs.

```java
var model = new DeepSeekChatModel(DeepSeekConfig.defaults(endpoint -> {
    String token = System.getenv("DEEPSEEK_API_KEY");
    if (token == null || token.isBlank()) throw new IllegalStateException("Model credential unavailable");
    return Map.of("Authorization", "Bearer " + token);
}));
```

`DeepSeekConfig.endpoint` is the complete `/chat/completions` URL, useful for approved gateways and the
loopback test fixture. Remote endpoints require HTTPS, credentials in URLs are rejected, and redirects
are disabled. Credential callbacks execute for each request; secrets do not belong in `ModelProfile`
parameters or persisted run state. The supported optional parameters are `temperature`, `top_p`,
`frequency_penalty`, `presence_penalty`, `stop`, and `response_format`. Other parameters fail before
network dispatch, so an arbitrary parameter map cannot silently override tools, output limits or messages.

Responses preserve finish reasons and distinguish missing token usage from known zero usage. A transport
failure or malformed successful response reports an unknown outcome/usage. HTTP authorization errors
are denied, HTTP 429 is transient, and no adapter retries a request itself. The caller's deadline and
response-size limit also cover response-body consumption. Raw provider errors are not attached to
framework exceptions. Streaming, thinking-mode continuation, vision and audio are outside this adapter's
first-version scope; they can be separate gateways using the same core contracts.

## MCP Streamable HTTP

The implementation uses the **official MCP Java SDK 2.0.1**, not an in-house JSON-RPC client:

- `io.modelcontextprotocol.sdk:mcp-core:2.0.1`
- `io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1`
- Jackson 2.21.1 and networknt JSON Schema validator 2.0.4, aligned with the SDK's published dependencies.

The SDK's tagged release declares Java 17. Initialization, protocol negotiation, HTTP JSON/SSE content,
session lifecycle and `tools/call` use the SDK. Harness discovery explicitly walks the SDK's paged
`listTools(cursor)` API with bounds on pages/tool count and detection of repeated cursors or duplicate names.

```java
var config = McpConnectionConfig.defaults("knowledge", URI.create("https://mcp.example.com/mcp"),
        endpoint -> Map.of("Authorization", "Bearer " + System.getenv("KNOWLEDGE_MCP_TOKEN")));
try (var connection = McpConnection.connect(config)) {
    var adapter = new McpToolAdapter(connection);
    // Explicit local policy is required. Server readOnlyHint/annotations grant no permission.
    var tools = adapter.bind(Map.of("search", ToolPolicy.readOnlyPolicy()));
    var registry = new ToolRegistry();
    tools.forEach(tool -> registry.register(tool, adapter));
    // Pass this registry to Harness, then select the permitted tool keys when starting a run.
    // The connection must remain open until those runs no longer need to execute MCP tools.
}
```

Each MCP tool has a server-scoped logical key and a model-compatible name. Its version hashes the full
remote contract, including input/output schemas and metadata; the core descriptor digest also includes
the local execution policy. Rebinding replaces the adapter's current catalog atomically. A revoked or
changed binding rejects a stale descriptor. Before each tool call, the adapter rechecks the current
remote catalog hash within the invocation's deadline; detected drift prevents `tools/call`. This costs
one bounded catalog walk per invocation. Publish newly returned descriptors into the registry when
deliberately accepting a catalog change; active frozen runs require their original contract. MCP has no
atomic "call only at this schema version" field, so this check cannot stop a server changing behavior
after the check; trusted server/version pinning remains the host's deployment responsibility.

Result `output` contains the MCP result envelope: typed `content` blocks, `structuredContent`, `isError`
and metadata where present. Images/resource references are preserved without executing or fetching them.
The output schema is pinned in the version digest; input validation is performed by the common tool
registry. This adapter does not additionally enforce remote output schemas. Hosts can project the result
into a smaller context representation rather than exposing every content block to a text-only model.

Generic MCP writes require a single attempt and cannot declare `retrySafe`: MCP does not standardize
remote idempotent execution receipts. A timeout or disconnect after dispatch becomes `UNKNOWN`, with
no automatic wrapper retry and no invented receipt. An application-specific remote idempotency protocol
needs its own explicitly documented adapter. `isError: true` remains a completed tool-error result.

Credentials are resolved per HTTP request, with redirects disabled. Own a connection per credential
and security domain; do not use request-thread-local identity in callbacks because SDK transport work
can execute on another thread. OAuth login/refresh orchestration and stdio process management are
extension work. This module provides a header callback for credentials managed by the host. Local SDK
logging is host-owned: its INFO logs can include server RPC errors and DEBUG logs can include protocol
bodies. Disable protocol logs where those bodies contain sensitive data; use the harness's metadata-only
execution events for auditing. The adapter sanitizes its own exceptions but cannot redact logs emitted
by an externally configured SDK logger.

## OpenTelemetry tracing

`OpenTelemetryTelemetry` implements the core telemetry contract using the official OpenTelemetry API
1.66.0. Inject the host's `Tracer`; this library never installs a global SDK or exporter. The host can
provide an OTLP exporter or another processor using the normal OpenTelemetry SDK configuration.
The official Java API supports Java 8+, and this module is compiled/tested on Java 17.

```java
var telemetry = new OpenTelemetryTelemetry(openTelemetry.getTracer("my-application-harness"));
var tracedCredentials = new OpenTelemetryHeaders(endpoint ->
        Map.of("Authorization", "Bearer " + System.getenv("MY_MCP_TOKEN")));
// Supply telemetry to Harness's full constructor and tracedCredentials to MCP/model configuration.
```

Each attempt emits a separate span with run, node, invocation, attempt, target and safe outcome
attributes. Spans share the run's persisted trace ID across ticks and across fresh telemetry bridge
instances. A deterministic correlation parent enables this without holding an in-memory run span open;
the bridge does not export an invented parent span. Arguments, model output, actor permissions and
credentials are omitted. `Telemetry.Span.wrap` carries the span into the core's bounded execution pool;
both calling-thread and worker scopes are closed explicitly at the invocation boundary.

`OpenTelemetryHeaders` opts into W3C `traceparent`/`tracestate` propagation. Its default propagator
does not forward baggage. For MCP, the current context is captured on the invocation thread and carried
through the SDK's `McpTransportContext`, so an asynchronous HTTP callback does not lose the attempt span.
The default `HeaderProvider` remains independent of tracing. Tests use the real SDK's in-memory span
exporter to verify correlation, outcome status, scope cleanup and the outgoing MCP `traceparent` header.

## Verification and sources

Tests start ephemeral HTTP servers bound to `127.0.0.1`; they never use external API keys or paid calls.
They exercise the real model serializer and official MCP client over the wire: native tool-call pairing,
unknown usage, errors, response-size/timeout handling, initialization, discovery pagination, credential
rotation, content/structured results, local binding policies and single-attempt unknown MCP writes.
These tests establish local protocol compatibility, not successful authentication or behavior of an
unconfigured production DeepSeek account or third-party MCP server.

Dependency and protocol references checked on 2026-09-15:

- [DeepSeek Create Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion)
- [Official MCP Java SDK v2.0.1 POM (Java 17)](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/pom.xml)
- [Official SDK Streamable HTTP transport source](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java)
- [Published MCP Jackson 2 adapter dependencies](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-json-jackson2/2.0.1/mcp-json-jackson2-2.0.1.pom)
- [OpenTelemetry Java API and SDK introduction](https://opentelemetry.io/docs/languages/java/intro/)
- [Published OpenTelemetry API 1.66.0](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-api/1.66.0/opentelemetry-api-1.66.0.pom)

Run from the repository root: `./mvnw -pl harness-adapters -am test` (Windows: `mvnw.cmd`).
