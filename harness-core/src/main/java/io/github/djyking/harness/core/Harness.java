package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Embedded durable execution engine. Each tick claims one lease and commits a bounded step. */
public final class Harness implements AutoCloseable {
  private final RunStore store;
  private final ModelGateway models;
  private final ToolRegistry tools;
  private final AccessPolicy access;
  private final Telemetry telemetry;
  private final Clock clock;
  private final Duration lease;
  private final InvocationExecutor executor;
  private final Map<String, Program> programs = new ConcurrentHashMap<>();

  public Harness(RunStore store, ModelGateway models, ToolRegistry tools) {
    this(
        store,
        models,
        tools,
        AccessPolicy.actorPermissions(),
        new StructuredTelemetry(),
        Clock.systemUTC(),
        Duration.ofSeconds(150));
  }

  public Harness(
      RunStore store,
      ModelGateway models,
      ToolRegistry tools,
      AccessPolicy access,
      Telemetry telemetry,
      Clock clock,
      Duration lease) {
    this(store, models, tools, access, telemetry, clock, lease, new InvocationExecutor(4, 32));
  }

  public Harness(
      RunStore store,
      ModelGateway models,
      ToolRegistry tools,
      AccessPolicy access,
      Telemetry telemetry,
      Clock clock,
      Duration lease,
      InvocationExecutor executor) {
    this.store = Objects.requireNonNull(store);
    this.models = Objects.requireNonNull(models);
    this.tools = Objects.requireNonNull(tools);
    this.access = Objects.requireNonNull(access);
    this.telemetry = Objects.requireNonNull(telemetry);
    this.clock = Objects.requireNonNull(clock);
    this.lease = Objects.requireNonNull(lease);
    if (lease.compareTo(Duration.ofSeconds(2)) < 0)
      throw new IllegalArgumentException("Lease must leave transport and commit time");
    this.executor = Objects.requireNonNull(executor);
    programs.put("agent", new AgentProgram());
  }

  public void registerProgram(String name, Program program) {
    if (programs.putIfAbsent(name, Objects.requireNonNull(program)) != null)
      throw new IllegalArgumentException("Program already registered");
  }

  public RunState start(
      ProgramDefinition definition,
      Actor actor,
      Collection<String> toolKeys,
      Budget budget,
      Duration lifetime,
      String creationKey) {
    access.check(actor, "run:create", definition.id());
    if (!programs.containsKey(definition.program()))
      throw new IllegalArgumentException("Unknown program");
    if (creationKey == null
        || creationKey.isBlank()
        || creationKey.length() > 160
        || lifetime.isNegative()
        || lifetime.isZero()
        || lifetime.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException("Invalid run key/lifetime");
    RunState run = new RunState();
    run.id = UUID.randomUUID().toString();
    run.creationKey = creationKey;
    run.actor = actor;
    run.definition = Json.copy(definition, ProgramDefinition.class);
    run.tools = new ArrayList<>(tools.snapshot(toolKeys));
    run.budget = budget;
    run.createdAt = clock.instant();
    run.deadline = clock.instant().plus(lifetime);
    run.nextAttemptAt = clock.instant();
    run.tools.forEach(tool -> authorizeTool(run, tool));
    run.creationDigest =
        Json.hash(
            Map.of(
                "definition",
                definition,
                "actor",
                actor,
                "tools",
                run.tools,
                "budget",
                budget,
                "lifetimeMillis",
                lifetime.toMillis()));
    return store.create(run);
  }

  public RunState get(String id, Actor actor) {
    RunState run = store.get(id);
    checkRun(actor, "run:read", run);
    return run;
  }

  public List<StoredEvent> events(String id, Actor actor, long after, int limit) {
    checkRun(actor, "run:read", store.get(id));
    return store.events(id, after, Math.min(Math.max(limit, 1), 1000));
  }

  public RunState tick(String id) {
    RunState run = store.claim(id, lease);
    if (run == null) return store.get(id);
    try {
      if (run.schemaVersion != 1 || !"1".equals(run.runtimeVersion))
        return finish(run, RunStatus.NEEDS_ATTENTION, "STATE_VERSION_INCOMPATIBLE");
      // Uncertain effects remain visible and reconcilable even after cancellation or expiry.
      if (run.pending != null
          && (run.pending.phase == InvocationPhase.IN_FLIGHT
              || run.pending.phase == InvocationPhase.UNKNOWN)) {
        if (run.pending.phase == InvocationPhase.IN_FLIGHT
            && !run.cancelRequested
            && !run.pauseRequested
            && run.deadline.isAfter(clock.instant())
            && "TOOL".equals(run.pending.kind)) {
          try {
            return failedAttempt(
                run,
                currentTool(run, run.pending),
                FailureKind.UNKNOWN,
                "WORKER_LOST_AFTER_DISPATCH_BARRIER");
          } catch (InvocationException changed) {
            /* Even a revoked tool can have an uncertain prior effect. */
          }
        }
        run.pending.phase = InvocationPhase.UNKNOWN;
        return finish(run, RunStatus.NEEDS_ATTENTION, "OUTCOME_UNKNOWN");
      }
      if (run.cancelRequested) return finish(run, RunStatus.CANCELLED, "CANCELLED_AT_BOUNDARY");
      if (run.pauseRequested) return finish(run, RunStatus.PAUSED, "PAUSED_AT_BOUNDARY");
      if (!run.deadline.isAfter(clock.instant()))
        return finish(run, RunStatus.EXPIRED, "RUN_DEADLINE_EXCEEDED");
      if (run.pending != null) return advancePending(run);
      if (++run.steps > run.budget.maxSteps())
        return finish(run, RunStatus.BUDGET_EXCEEDED, "STEP_BUDGET_EXCEEDED");
      Program program = programs.get(run.definition.program());
      if (program == null) return finish(run, RunStatus.NEEDS_ATTENTION, "PROGRAM_UNAVAILABLE");
      Action action = program.next(run);
      if (action instanceof CompleteAction complete) {
        run.output = complete.output();
        return finish(run, RunStatus.COMPLETED, "PROGRAM_COMPLETED");
      }
      if (action instanceof FailAction failure)
        return finish(run, RunStatus.FAILED, failure.code());
      if (action instanceof ContinueAction) return checkpoint(run, "STEP_ADVANCED");
      RunState.Pending pending = new RunState.Pending();
      if (action instanceof ModelAction model) {
        pending.id = model.id();
        pending.nodeId = model.nodeId();
        pending.kind = "MODEL";
        pending.modelRequest =
            Json.copy(
                new ModelRequest(
                    run.id + ":" + model.id(),
                    model.request().profile(),
                    model.request().messages(),
                    validateModelTools(run, model.request().tools())),
                ModelRequest.class);
        ContextWindow.validatePairing(pending.modelRequest.messages());
        pending.contractDigest = Json.hash(pending.modelRequest);
      } else if (action instanceof ToolAction tool) {
        pending.id = tool.id();
        pending.nodeId = tool.nodeId();
        pending.kind = "TOOL";
        pending.toolKey = tool.toolKey();
        pending.arguments = tool.arguments().deepCopy();
        ToolDescriptor descriptor = frozenTool(run, tool.toolKey());
        ToolRegistry.validate(descriptor, pending.arguments);
        pending.contractDigest = descriptor.digest();
      } else if (action instanceof WaitAction human) {
        pending.id = human.id();
        pending.nodeId = human.nodeId();
        pending.kind = "HUMAN";
        pending.prompt = human.prompt();
        pending.contractDigest = Json.hash(human.prompt());
      } else throw new IllegalArgumentException("Unsupported step action");
      if (pending.id == null
          || pending.id.isBlank()
          || pending.id.length() > 240
          || run.results.containsKey(pending.id))
        throw new InvocationException(FailureKind.INVALID, "STEP_ID_REUSED");
      pending.approvalDigest =
          Json.hash(
              Map.of(
                  "run",
                  run.id,
                  "actor",
                  run.actor,
                  "id",
                  pending.id,
                  "node",
                  Objects.requireNonNullElse(pending.nodeId, ""),
                  "contract",
                  pending.contractDigest,
                  "args",
                  pending.arguments == null ? Json.object() : pending.arguments));
      run.pending = pending;
      return checkpoint(run, "INVOCATION_PREPARED");
    } catch (RunStore.Conflict lost) {
      return store.get(id);
    } catch (InvocationException failure) {
      return safeFinish(run, RunStatus.NEEDS_ATTENTION, failure.getMessage());
    } catch (RuntimeException failure) {
      return safeFinish(run, RunStatus.FAILED, "PROGRAM_OR_STATE_ERROR");
    }
  }

  public int tickReady(int maximum) {
    List<String> ids = store.ready(maximum);
    ids.forEach(this::tick);
    return ids.size();
  }

  private RunState advancePending(RunState run) {
    RunState.Pending p = run.pending;
    ToolDescriptor descriptor = "TOOL".equals(p.kind) ? currentTool(run, p) : null;
    if (p.phase == InvocationPhase.UNKNOWN)
      return finish(run, RunStatus.NEEDS_ATTENTION, "OUTCOME_UNKNOWN_REQUIRES_RECONCILIATION");
    if (p.phase == InvocationPhase.IN_FLIGHT)
      return failedAttempt(
          run, descriptor, FailureKind.UNKNOWN, "WORKER_LOST_AFTER_DISPATCH_BARRIER");
    if ("HUMAN".equals(p.kind) || descriptor != null && descriptor.policy().approvalRequired()) {
      if (run.approval == null) {
        Instant expiry = min(run.deadline, clock.instant().plus(Duration.ofMinutes(30)));
        run.approval = new Approval(p.id, p.approvalDigest, expiry, "PENDING", null, null);
        run.status = "HUMAN".equals(p.kind) ? RunStatus.WAITING_INPUT : RunStatus.WAITING_APPROVAL;
        return store.save(
            run, event(run, "APPROVAL_REQUESTED", Map.of("digest", p.approvalDigest)), true);
      }
      if (!run.approval.invocationId().equals(p.id)
          || !run.approval.digest().equals(p.approvalDigest)
          || !"APPROVED".equals(run.approval.status())
          || !run.approval.expiresAt().isAfter(clock.instant()))
        return finish(run, RunStatus.FAILED, "APPROVAL_NOT_VALID");
      if ("HUMAN".equals(p.kind))
        return commitResult(
            run,
            new StepResult(
                "HUMAN",
                Json.object().put("input", Objects.requireNonNullElse(run.approval.reason(), "")),
                false),
            "INPUT_ACCEPTED");
    }
    if (descriptor != null) {
      authorizeTool(run, descriptor);
      if (run.toolCalls >= run.budget.toolCalls())
        return finish(run, RunStatus.BUDGET_EXCEEDED, "TOOL_BUDGET_EXCEEDED");
      if (p.attempts >= descriptor.policy().maxAttempts())
        return finish(run, RunStatus.NEEDS_ATTENTION, "TOOL_ATTEMPT_LIMIT");
      ToolRegistry.validate(descriptor, p.arguments);
    } else {
      access.check(run.actor, "model:invoke", p.modelRequest.profile().id());
      if (run.modelCalls >= run.budget.modelCalls())
        return finish(run, RunStatus.BUDGET_EXCEEDED, "MODEL_CALL_BUDGET_EXCEEDED");
      p.tokenReservation = ContextWindow.reserve(p.modelRequest);
      if (p.tokenReservation > p.modelRequest.profile().contextTokens())
        return finish(run, RunStatus.BUDGET_EXCEEDED, "CONTEXT_CAPACITY_EXCEEDED");
      if (p.tokenReservation > run.budget.tokenLimit() - run.chargedTokens)
        return finish(run, RunStatus.BUDGET_EXCEEDED, "TOKEN_BUDGET_EXCEEDED");
    }
    store.assertLease(run);
    long timeoutMillis =
        descriptor == null
            ? p.modelRequest.profile().timeoutMillis()
            : descriptor.policy().timeoutMillis();
    Instant deadline =
        min(
            min(run.deadline, clock.instant().plusMillis(timeoutMillis)),
            run.leaseUntil.minusMillis(500));
    if (!deadline.isAfter(clock.instant()))
      return finish(run, RunStatus.EXPIRED, "NO_EXECUTION_TIME_REMAINING");
    p.attempts++;
    p.phase = InvocationPhase.IN_FLIGHT;
    if (descriptor != null) run.toolCalls++;
    else {
      run.modelCalls++;
      run.chargedTokens += p.tokenReservation;
    }
    run =
        store.save(
            run,
            event(run, "ATTEMPT_STARTED", Map.of("attempt", Integer.toString(p.attempts))),
            false);
    p = run.pending;
    ExecutionContext context =
        new ExecutionContext(
            run.id,
            p.nodeId,
            run.id + ":" + p.id,
            run.id + ":" + p.id + ":attempt:" + p.attempts,
            run.actor,
            deadline,
            Json.hash(run.id).substring(0, 32));
    Telemetry.Span span;
    try {
      span =
          safeSpan(
              telemetry.start(
                  context,
                  p.kind,
                  descriptor == null ? p.modelRequest.profile().id() : descriptor.key()));
    } catch (RuntimeException ignored) {
      span = Telemetry.noop().start(context, p.kind, "");
    }
    try {
      store.assertLease(run);
      if (descriptor != null) {
        final RunState dispatch = run;
        JsonNode arguments = p.arguments.deepCopy();
        ToolResult result =
            executor.invoke(
                span.wrap(
                    () -> {
                      checkDispatch(dispatch);
                      ToolRegistry.Entry entry = tools.require(descriptor.key());
                      if (!entry.descriptor().digest().equals(dispatch.pending.contractDigest))
                        throw new InvocationException(FailureKind.DENIED, "TOOL_CONTRACT_CHANGED");
                      authorizeTool(dispatch, entry.descriptor());
                      return entry.handler().invoke(entry.descriptor(), arguments, context);
                    }),
                context,
                clock);
        if (result == null)
          throw new InvocationException(FailureKind.UNKNOWN, "TOOL_RETURNED_NO_RESULT");
        run = mergeControls(run);
        span.outcome(result.error() ? "TOOL_ERROR" : "SUCCESS");
        if (result.receipt() != null) run.receipts.put(run.pending.id, result.receipt());
        return commitResult(
            run,
            new StepResult("TOOL", result.output(), result.error()),
            result.error() ? "TOOL_ERROR" : "TOOL_COMPLETED");
      }
      access.check(run.actor, "model:invoke", p.modelRequest.profile().id());
      ModelRequest request = Json.copy(p.modelRequest, ModelRequest.class);
      final RunState dispatch = run;
      ModelResponse response =
          executor.invoke(
              span.wrap(
                  () -> {
                    checkDispatch(dispatch);
                    access.check(dispatch.actor, "model:invoke", request.profile().id());
                    validateModelTools(dispatch, request.tools());
                    return models.invoke(request, context);
                  }),
              context,
              clock);
      if (response == null)
        throw new InvocationException(FailureKind.UNKNOWN, "MODEL_RETURNED_NO_RESULT");
      run = mergeControls(run);
      if (response.usage().known())
        run.chargedTokens =
            Math.addExact(
                run.chargedTokens - run.pending.tokenReservation, response.usage().total());
      span.outcome("SUCCESS");
      return commitResult(
          run, new StepResult("MODEL", Json.tree(response), false), "MODEL_COMPLETED");
    } catch (RunStore.Conflict lost) {
      span.outcome("LEASE_LOST");
      return store.get(run.id);
    } catch (InvocationException failure) {
      span.outcome(failure.kind().name());
      return failedAttempt(mergeControls(run), descriptor, failure.kind(), failure.getMessage());
    } catch (RuntimeException failure) {
      span.outcome("UNKNOWN");
      return failedAttempt(
          mergeControls(run), descriptor, FailureKind.UNKNOWN, "CALL_RESULT_UNKNOWN");
    } finally {
      try {
        span.close();
      } catch (RuntimeException ignored) {
        /* Telemetry cannot change execution outcome. */
      }
    }
  }

  private RunState failedAttempt(
      RunState run, ToolDescriptor descriptor, FailureKind kind, String code) {
    RunState.Pending p = run.pending;
    p.failureCode = safeCode(code);
    if (kind != FailureKind.UNKNOWN && (run.cancelRequested || run.pauseRequested)) {
      p.phase = InvocationPhase.PREPARED;
      return finish(
          run,
          run.cancelRequested ? RunStatus.CANCELLED : RunStatus.PAUSED,
          "CONTROL_APPLIED_AFTER_KNOWN_FAILURE");
    }
    boolean retry =
        descriptor != null
            && descriptor.policy().retrySafe()
            && !run.cancelRequested
            && !run.pauseRequested
            && run.deadline.isAfter(clock.instant())
            && p.attempts < descriptor.policy().maxAttempts()
            && (kind == FailureKind.TRANSIENT
                || kind == FailureKind.UNKNOWN && descriptor.policy().readOnly());
    if (retry) {
      p.phase = InvocationPhase.PREPARED;
      run.nextAttemptAt =
          clock.instant().plusMillis(Math.min(10_000, 200L << Math.min(p.attempts, 5)));
      run.status = RunStatus.QUEUED;
      return store.save(run, event(run, "RETRY_SCHEDULED", Map.of("reason", safeCode(code))), true);
    }
    p.phase = kind == FailureKind.UNKNOWN ? InvocationPhase.UNKNOWN : InvocationPhase.PREPARED;
    return finish(
        run,
        RunStatus.NEEDS_ATTENTION,
        kind == FailureKind.UNKNOWN ? "OUTCOME_UNKNOWN" : safeCode(code));
  }

  private RunState commitResult(RunState run, StepResult result, String event) {
    String id = run.pending.id;
    run.results.put(id, result);
    run.pending = null;
    run.approval = null;
    if (run.cancelRequested)
      return finish(run, RunStatus.CANCELLED, "CANCELLED_AFTER_RESULT_COMMITTED");
    if (run.pauseRequested) return finish(run, RunStatus.PAUSED, "PAUSED_AFTER_RESULT_COMMITTED");
    return checkpoint(run, event, Map.of("invocationId", id));
  }

  private List<ToolDescriptor> validateModelTools(RunState run, List<ToolDescriptor> requested) {
    Set<String> names = new HashSet<>();
    for (ToolDescriptor tool : requested) {
      if (!frozenTool(run, tool.key()).digest().equals(tool.digest())
          || !names.add(tool.modelName())
          || !tools.require(tool.key()).descriptor().digest().equals(tool.digest()))
        throw new InvocationException(FailureKind.DENIED, "MODEL_TOOLS_OUTSIDE_SNAPSHOT");
      authorizeTool(run, tool);
    }
    return requested;
  }

  private ToolDescriptor frozenTool(RunState run, String key) {
    return run.tools.stream()
        .filter(t -> t.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new InvocationException(FailureKind.DENIED, "TOOL_NOT_GRANTED"));
  }

  private ToolDescriptor currentTool(RunState run, RunState.Pending p) {
    ToolDescriptor frozen = frozenTool(run, p.toolKey);
    ToolDescriptor current = tools.require(p.toolKey).descriptor();
    if (!frozen.digest().equals(p.contractDigest) || !current.digest().equals(p.contractDigest))
      throw new InvocationException(FailureKind.DENIED, "TOOL_CONTRACT_CHANGED");
    return frozen;
  }

  private void authorizeTool(RunState run, ToolDescriptor tool) {
    access.check(run.actor, "tool:" + tool.key(), tool.key());
    for (String permission : tool.policy().requiredPermissions())
      access.check(run.actor, permission, tool.key());
  }

  private RunState mergeControls(RunState run) {
    RunState current = store.get(run.id);
    if (current.fence != run.fence
        || current.pending == null
        || !current.pending.id.equals(run.pending.id))
      throw new RunStore.Conflict("Invocation ownership changed");
    run.revision = current.revision;
    run.leaseUntil = current.leaseUntil;
    run.pauseRequested = current.pauseRequested;
    run.cancelRequested = current.cancelRequested;
    store.assertLease(run);
    return run;
  }

  /**
   * Check at actual worker dispatch, after any queue delay. Controls cannot undo a call already
   * dispatched.
   */
  private void checkDispatch(RunState snapshot) {
    RunState current = store.get(snapshot.id);
    if (current.fence != snapshot.fence
        || current.pending == null
        || !current.pending.id.equals(snapshot.pending.id))
      throw new InvocationException(FailureKind.TRANSIENT, "CALL_NOT_DISPATCHED_LEASE_LOST");
    try {
      store.assertLease(current);
    } catch (RunStore.Conflict lost) {
      throw new InvocationException(FailureKind.TRANSIENT, "CALL_NOT_DISPATCHED_LEASE_LOST");
    }
    if (current.cancelRequested || current.pauseRequested)
      throw new InvocationException(FailureKind.TRANSIENT, "CALL_NOT_DISPATCHED_CONTROL_CHANGED");
    if (!current.deadline.isAfter(clock.instant()))
      throw new InvocationException(FailureKind.TRANSIENT, "CALL_NOT_DISPATCHED_DEADLINE");
    if ("TOOL".equals(current.pending.kind)
        && frozenTool(current, current.pending.toolKey).policy().approvalRequired()) {
      Approval approval = current.approval;
      if (approval == null
          || !"APPROVED".equals(approval.status())
          || !approval.invocationId().equals(current.pending.id)
          || !approval.digest().equals(current.pending.approvalDigest)
          || !approval.expiresAt().isAfter(clock.instant()))
        throw new InvocationException(FailureKind.DENIED, "APPROVAL_NOT_VALID_AT_DISPATCH");
    }
  }

  private RunState checkpoint(RunState run, String type) {
    return checkpoint(run, type, Map.of());
  }

  private RunState checkpoint(RunState run, String type, Map<String, String> attributes) {
    run.status = RunStatus.QUEUED;
    run.nextAttemptAt = clock.instant();
    return store.save(run, event(run, type, attributes), true);
  }

  private RunState finish(RunState run, RunStatus status, String code) {
    run.status = status;
    run.stopReason = safeCode(code);
    return store.save(run, event(run, "RUN_" + status, Map.of("reason", safeCode(code))), true);
  }

  private RunState safeFinish(RunState run, RunStatus status, String code) {
    try {
      return finish(run, status, code);
    } catch (RunStore.Conflict lost) {
      return store.get(run.id);
    }
  }

  private RunEvent event(RunState run, String type, Map<String, String> attributes) {
    return new RunEvent(
        type,
        run.pending == null ? "" : run.pending.nodeId,
        run.pending == null ? "" : run.pending.id,
        clock.instant(),
        attributes);
  }

  private static String safeCode(String code) {
    return code != null && code.matches("[A-Z0-9_]{1,100}") ? code : "EXECUTION_FAILED";
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Telemetry.Span safeSpan(Telemetry.Span delegate) {
    return new Telemetry.Span() {
      public void outcome(String code) {
        try {
          delegate.outcome(code);
        } catch (RuntimeException ignored) {
        }
      }

      public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        try {
          return delegate.wrap(task);
        } catch (RuntimeException ignored) {
          return task;
        }
      }

      public void close() {
        try {
          delegate.close();
        } catch (RuntimeException ignored) {
        }
      }
    };
  }

  public void close() {
    executor.close();
  }

  private void checkRun(Actor actor, String permission, RunState run) {
    if (!actor.project().equals(run.actor.project()))
      throw new InvocationException(FailureKind.DENIED, "PROJECT_SCOPE_MISMATCH");
    access.check(actor, permission, run.id);
    if (!actor.subject().equals(run.actor.subject()) && !"approval:decide".equals(permission))
      access.check(actor, "run:admin", run.id);
  }

  public RunState pause(String id, Actor actor) {
    return control(id, actor, true, false);
  }

  public RunState cancel(String id, Actor actor) {
    return control(id, actor, false, true);
  }

  private RunState control(String id, Actor actor, boolean pause, boolean cancel) {
    RunState current = store.get(id);
    checkRun(actor, "run:control", current);
    if (Set.of(
            RunStatus.COMPLETED,
            RunStatus.CANCELLED,
            RunStatus.EXPIRED,
            RunStatus.FAILED,
            RunStatus.BUDGET_EXCEEDED)
        .contains(current.status)) throw new RunStore.Conflict("Run is terminal");
    return store.update(
        id,
        current.revision,
        run -> {
          run.pauseRequested = pause || run.pauseRequested;
          run.cancelRequested = cancel || run.cancelRequested;
          if (run.status != RunStatus.RUNNING) {
            if (run.pending != null && run.pending.phase != InvocationPhase.PREPARED) {
              run.status = RunStatus.NEEDS_ATTENTION;
              run.stopReason = "OUTCOME_UNKNOWN";
            } else run.status = cancel ? RunStatus.CANCELLED : RunStatus.PAUSED;
          }
          return run;
        },
        new RunEvent(
            cancel ? "CANCEL_REQUESTED" : "PAUSE_REQUESTED", "", "", clock.instant(), Map.of()));
  }

  public RunState resume(String id, Actor actor) {
    RunState current = store.get(id);
    checkRun(actor, "run:control", current);
    if (current.cancelRequested
        || !current.deadline.isAfter(clock.instant())
        || (current.status != RunStatus.PAUSED && current.status != RunStatus.NEEDS_ATTENTION))
      throw new RunStore.Conflict("Run cannot resume");
    if (current.pending != null
        && (current.pending.phase == InvocationPhase.UNKNOWN
            || current.pending.phase == InvocationPhase.IN_FLIGHT))
      throw new RunStore.Conflict("Unknown outcome must be reconciled first");
    return store.update(
        id,
        current.revision,
        run -> {
          run.pauseRequested = false;
          run.stopReason = null;
          run.status = RunStatus.QUEUED;
          run.nextAttemptAt = clock.instant();
          return run;
        },
        new RunEvent("RUN_RESUMED", "", "", clock.instant(), Map.of()));
  }

  public RunState decide(
      String id, Actor actor, String expectedDigest, boolean approved, String input) {
    RunState current = store.get(id);
    checkRun(actor, "approval:decide", current);
    if (current.cancelRequested
        || current.pauseRequested
        || !Set.of(RunStatus.WAITING_APPROVAL, RunStatus.WAITING_INPUT).contains(current.status)
        || current.pending == null
        || current.approval == null
        || !"PENDING".equals(current.approval.status())
        || !current.approval.digest().equals(expectedDigest)
        || !current.approval.expiresAt().isAfter(clock.instant()))
      throw new RunStore.Conflict("Approval is stale or unavailable");
    if (input != null && input.length() > 10_000)
      throw new IllegalArgumentException("Approval input too long");
    return store.update(
        id,
        current.revision,
        run -> {
          run.approval =
              new Approval(
                  run.pending.id,
                  expectedDigest,
                  run.approval.expiresAt(),
                  approved ? "APPROVED" : "REJECTED",
                  actor.subject(),
                  input);
          run.status = approved ? RunStatus.QUEUED : RunStatus.FAILED;
          run.stopReason = approved ? null : "APPROVAL_REJECTED";
          run.nextAttemptAt = clock.instant();
          return run;
        },
        new RunEvent(
            approved ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED",
            current.pending.nodeId,
            current.pending.id,
            clock.instant(),
            Map.of("actor", actor.subject(), "digest", expectedDigest)));
  }

  /**
   * Trusted host supplies an externally verified receipt; this method never repeats a remote write.
   */
  public RunState reconcileTool(String id, Actor actor, String invocationId, ToolResult receipt) {
    RunState current = store.get(id);
    checkRun(actor, "run:reconcile", current);
    if (current.status != RunStatus.NEEDS_ATTENTION
        || current.pending == null
        || !"TOOL".equals(current.pending.kind)
        || current.pending.phase != InvocationPhase.UNKNOWN
        || !current.pending.id.equals(invocationId)
        || receipt.receipt() == null
        || receipt.receipt().isBlank())
      throw new RunStore.Conflict(
          "Reconciliation requires matching unknown invocation and receipt");
    return store.update(
        id,
        current.revision,
        run -> {
          run.results.put(invocationId, new StepResult("TOOL", receipt.output(), receipt.error()));
          run.receipts.put(invocationId, receipt.receipt());
          run.pending = null;
          run.approval = null;
          run.stopReason = null;
          run.status = run.cancelRequested ? RunStatus.CANCELLED : RunStatus.PAUSED;
          run.pauseRequested = !run.cancelRequested;
          return run;
        },
        new RunEvent(
            "TOOL_RECONCILED",
            current.pending.nodeId,
            invocationId,
            clock.instant(),
            Map.of("receiptDigest", Json.hash(receipt.receipt()))));
  }
}
