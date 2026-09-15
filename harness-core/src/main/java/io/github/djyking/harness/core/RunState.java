package io.github.djyking.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Contracts.*;
import java.time.Instant;
import java.util.*;

/** Detached versioned persistence DTO. Stores must copy it at every public boundary. */
public final class RunState {
  public int schemaVersion = 1;
  public String runtimeVersion = "1";
  public String id;
  public String creationKey;
  public String creationDigest;
  public Actor actor;
  public ProgramDefinition definition;
  public RunStatus status = RunStatus.QUEUED;
  public Budget budget;
  public Instant createdAt;
  public Instant deadline;
  public Instant nextAttemptAt;
  public long chargedTokens;
  public int modelCalls;
  public int toolCalls;
  public int steps;
  public ObjectNode memory = Json.object();
  public List<ToolDescriptor> tools = new ArrayList<>();
  public Map<String, StepResult> results = new LinkedHashMap<>();
  public Map<String, String> receipts = new LinkedHashMap<>();
  public Pending pending;
  public Approval approval;
  public JsonNode output;
  public String stopReason;
  public boolean pauseRequested;
  public boolean cancelRequested;
  public long revision;
  public long fence;
  public Instant leaseUntil;

  public RunState copy() {
    return Json.copy(this, RunState.class);
  }

  public static final class Pending {
    public String id;
    public String nodeId;
    public String kind;
    public String toolKey;
    public String contractDigest;
    public JsonNode arguments;
    public ModelRequest modelRequest;
    public String prompt;
    public InvocationPhase phase = InvocationPhase.PREPARED;
    public int attempts;
    public long tokenReservation;
    public String failureCode;
    public String approvalDigest;
  }
}
