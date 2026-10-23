# Optional capabilities

This module adds prompt management, retrieval, and bounded workflows to the shared harness runtime. Applications that only need the execution kernel do not depend on it.

## Prompt files

`FilePromptRepository` stores immutable JSON versions at `<root>/<id>/<version>.json`. `publish` uses create-new semantics and refuses to overwrite a version. `load` verifies the identity in the file and rejects paths outside the repository. Operators should still protect the repository directory from concurrent, untrusted filesystem writers.

```java
var template = new PromptTemplate("answer", "1", "Answer using the supplied context.",
    "Question: {{question}}\nContext: {{context}}",
    Map.of("question", PromptTemplate.VariableType.STRING,
           "context", PromptTemplate.VariableType.STRING));
var rendered = template.render(Map.of("question", "How do I deploy?", "context", excerpts));
```

Templates only interpolate declared variables, without evaluating code, expressions, or placeholders introduced by an input value. Missing or extra inputs, incorrect types, malformed placeholders, and non-finite numbers are rejected. `RenderedPrompt` contains the rendered messages, template SHA-256, and the effective inputs as JSON. Pin that value when creating a run. Rendering a newer template does not mutate an existing snapshot. Effective inputs may contain private business data: persist them only in an appropriately protected run store, and do not put them in trace attributes or application logs.

## Retrieval

`Retrieval.Retriever` is the extension point for an existing search index or vector store. `InMemoryRetriever` is a runnable deterministic lexical baseline for small development corpora. It does not provide embeddings, document ingestion, reranking, or language-specific segmentation. For example, contiguous Chinese phrases are treated as one token; production Chinese retrieval should supply an appropriate adapter.

Authorized scope IDs are supplied by the authenticated host. The in-memory retriever filters candidates before scoring; `RetrievalContext.format` checks scopes again before rendering any excerpts. Empty scope sets return no documents. The formatter enforces an exact Java-character bound and emits citations only for excerpts actually included. It is not a tokenizer, and a model's token budget is a separate control. Treat returned document text as untrusted source material when composing system instructions.

`RagTool` exposes this retrieval as a normal `ToolHandler`; register its descriptor and handler in the runtime's tool registry. Its default scope is the authenticated actor's project. A custom `ScopeResolver` queries the host's current document grants before retrieval and again before returning results, so a grant revoked during a slow lookup does not expose a returned excerpt. Tool arguments contain only `query` and optional `maxResults`; callers cannot supply a scope. The handler enforces the configured count and context bounds even when a retriever adapter returns excess or cross-scope results.

## Workflow

`WorkflowDefinition` describes a graph of `Tool`, `Model`, `Agent`, `Condition`, `Human`, and `End` nodes. A `next` edge expresses sequence; `Condition` selects one of two edges using exact JSON equality. Named-variable bindings copy JSON values into tool arguments or typed prompt variables. There is no embedded expression language or arbitrary script evaluator.

```java
var workflow = new WorkflowDefinition("review", "1", "read", 10, Map.of(
    "read", new WorkflowDefinition.Tool("catalog/read", "{}",
        Map.of("resource", "target"), "result", "review"),
    "review", new WorkflowDefinition.Human("Review the lookup result", "response", "done"),
    "done", new WorkflowDefinition.End("result")));
var definition = new WorkflowProgram.Spec(workflow, null,
    Json.read("{\"target\":\"sandbox\"}")).definition();
```

Register `WorkflowProgram` under program name `workflow` and start this `ProgramDefinition` using the same runtime as an agent. A model profile is required only for workflows containing `Model` or `Agent` nodes. A `Model` node performs one model call with no tools and accepts only a final answer. An `Agent` node delegates to the core `AgentProgram` under an isolated namespace, uses the parent run's budget, and restricts tools to the intersection of its declared keys and the run's registered tools.

```java
// registry already contains the host-authorized "catalog/read" tool.
var runtime = new Harness(store, models, registry);
runtime.registerProgram(WorkflowProgram.PROGRAM, new WorkflowProgram());
var run = runtime.start(definition, authenticatedActor, List.of("catalog/read"),
    Budget.defaults(), Duration.ofMinutes(10), "host-request-123");
// The host's worker calls tick for eligible runs, or uses tickReady for a batch.
run = runtime.tick(run.id);
// On WAITING_INPUT, expose run.pending.prompt and the approval digest to the user.
// Submit only after receiving the user's decision; the digest binds this exact wait.
runtime.decide(waitingRun.id, approver, waitingRun.approval.digest(), true, suppliedText);
```

`store`, `models`, `registry`, authenticated actors, and the worker scheduler are supplied by the host. A single `tick` advances one bounded phase, so callers should continue while the run is eligible and stop at waits or terminal states. `tickReady` honors persisted retry times. Do not automatically approve a real user's write request; the example API call only illustrates how an already obtained decision reaches the runtime.

Workflow has no executor, network client, approval store, or separate execution ledger. It returns actions to the harness. The harness commits progress in `RunState.memory` and results in `RunState.results` under the same lease used for agents. Stable action IDs allow a restored workflow to consume an already committed tool result without issuing the operation again. Human nodes use the runtime's durable input-wait mechanism; they are separate from the runtime's parameter-bound tool approvals.

A human node stores the runtime's complete value (`{"input":"the supplied text"}`) in its output variable. To branch on a response, compare that variable to `{"input":"yes"}` using a `Condition` node. Input text is not implicitly parsed as JSON or converted to a boolean.

Definitions, model profiles, initial inputs, and rendered prompt snapshots are pinned in run state. A mismatching configuration fingerprint or unsupported workflow state version fails explicitly. Cycles are allowed but bounded by `maxTransitions` (1–10,000) and the shared run's step, call, token, and time budgets. The first release does not include parallel branches, compensation transactions, a visual editor, automatic graph migration, or a separate child-run service.

## Verification

Run `./mvnw -pl harness-capabilities -am test` (Windows: `mvnw.cmd`). Tests cover prompt input types and immutable file versions, literal interpolation, scope isolation and current grant changes, hard context bounds, graph validation, transition limits, pinned configurations, restored result reuse, and nested native tool-call pairing.

The integration tests use the real core `Harness` with an in-memory store, scripted model responses, and controlled tool handlers. They exercise a human wait followed by a separate exact write approval, replacement of the worker object without resetting the run, and an agent using RAG through the shared call/token accounting. Durable SQL process recovery is the storage/runtime integration layer's responsibility; no live search service or embedding provider is claimed to be tested here.
