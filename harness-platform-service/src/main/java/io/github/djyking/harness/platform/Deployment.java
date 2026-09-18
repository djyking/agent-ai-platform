package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.capabilities.workflow.*;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.nio.file.*;
import java.util.*;

/** Trusted deployment manifests. They are never accepted from the public Run API. */
public record Deployment(
    String identityOrigin,
    String signingSecret,
    Map<String, String> applicationSecrets,
    List<Project> projects,
    List<Release> releases,
    Set<String> disabledReleases,
    List<JsonNode> tools,
    List<JsonNode> models,
    int concurrency,
    long leaseMillis,
    boolean workerEnabled) {
  public Deployment {
    applicationSecrets = Map.copyOf(applicationSecrets);
    projects = List.copyOf(projects);
    releases = List.copyOf(releases);
    disabledReleases = disabledReleases == null ? Set.of() : Set.copyOf(disabledReleases);
    tools = tools == null ? List.of() : List.copyOf(tools);
    models = models == null ? List.of() : List.copyOf(models);
    if (concurrency < 1 || concurrency > 32 || leaseMillis < 3000 || leaseMillis > 300000)
      throw new IllegalArgumentException("Invalid worker limits");
    Set<String> seen = new HashSet<>();
    for (Project p : projects) {
      if (!seen.add(p.id())) throw new IllegalArgumentException("Duplicate project");
      if (!applicationSecrets.keySet().containsAll(p.applications()))
        throw new IllegalArgumentException("Project application secret reference missing");
    }
    seen.clear();
    for (Release r : releases) {
      if (!seen.add(r.projectId() + "/" + r.releaseId()))
        throw new IllegalArgumentException("Duplicate release");
      if (projects.stream().noneMatch(p -> p.id().equals(r.projectId())))
        throw new IllegalArgumentException("Release project missing");
    }
  }

  public static Deployment load(Path file) {
    try {
      return Json.MAPPER
          .copy()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(Files.readAllBytes(file), Deployment.class);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid deployment manifest", ex);
    }
  }

  public Project project(String id) {
    return projects.stream()
        .filter(p -> p.id.equals(id))
        .findFirst()
        .orElseThrow(ApiFailure::hidden);
  }

  public Release release(String project, String id) {
    return releases.stream()
        .filter(r -> r.projectId.equals(project) && r.releaseId.equals(id))
        .findFirst()
        .orElseThrow(() -> new ApiFailure(404, "RELEASE_UNAVAILABLE"));
  }

  public boolean disabled(Release r) {
    return disabledReleases.contains(r.projectId() + "/" + r.releaseId());
  }

  public record Project(
      String id,
      Set<String> applications,
      int maxActiveRuns,
      long maxReservedTokens,
      int maxActiveRunsPerApplication,
      long maxReservedTokensPerApplication,
      int concurrency) {
    public Project {
      ApiJson.identifier(id);
      applications = Set.copyOf(applications);
      if (applications.isEmpty()
          || maxActiveRuns < 1
          || maxReservedTokens < 1
          || maxActiveRunsPerApplication < 1
          || maxReservedTokensPerApplication < 1
          || concurrency < 1) throw new IllegalArgumentException("Invalid project limits");
    }
  }

  public record Limits(
      long maxTokens, int maxModelCalls, int maxToolCalls, int maxSteps, int lifetimeSeconds) {
    public Limits {
      if (maxTokens < 1
          || maxTokens > 10000000
          || maxModelCalls < 1
          || maxModelCalls > 1000
          || maxToolCalls < 1
          || maxToolCalls > 5000
          || maxSteps < 1
          || maxSteps > 10000
          || lifetimeSeconds < 1
          || lifetimeSeconds > 86400) throw new IllegalArgumentException("Invalid release limits");
    }

    public Budget budget() {
      return new Budget(maxTokens, maxModelCalls, maxToolCalls, maxSteps);
    }

    public Limits lower(JsonNode node) {
      Limits result =
          new Limits(
              node.path("maxTokens").asLong(maxTokens),
              node.path("maxModelCalls").asInt(maxModelCalls),
              node.path("maxToolCalls").asInt(maxToolCalls),
              node.path("maxSteps").asInt(maxSteps),
              node.path("lifetimeSeconds").asInt(lifetimeSeconds));
      if (result.maxTokens > maxTokens
          || result.maxModelCalls > maxModelCalls
          || result.maxToolCalls > maxToolCalls
          || result.maxSteps > maxSteps
          || result.lifetimeSeconds > lifetimeSeconds) throw new ApiFailure(422, "INPUT_INVALID");
      return result;
    }
  }

  public record Release(
      String projectId,
      String agentId,
      String releaseId,
      WorkflowDefinition workflow,
      ModelProfile model,
      JsonNode inputSchema,
      JsonNode outputSchema,
      Set<String> toolKeys,
      Set<String> executionPermissions,
      Set<String> approvers,
      List<String> reviewFields,
      JsonNode humanInputSchema,
      boolean publicOutput,
      Limits limits) {
    public Release {
      ApiJson.identifier(projectId);
      ApiJson.identifier(agentId);
      ApiJson.uuid(releaseId);
      Objects.requireNonNull(workflow);
      Objects.requireNonNull(inputSchema);
      Objects.requireNonNull(outputSchema);
      Objects.requireNonNull(limits);
      // Release digests must remain identical across JVMs, whose immutable Set iteration
      // varies.
      toolKeys = Collections.unmodifiableSortedSet(new TreeSet<>(toolKeys));
      executionPermissions = Collections.unmodifiableSortedSet(new TreeSet<>(executionPermissions));
      approvers = Collections.unmodifiableSortedSet(new TreeSet<>(approvers));
      reviewFields = List.copyOf(reviewFields);
      if (executionPermissions.contains("*")
          || executionPermissions.stream()
              .anyMatch(
                  p -> p.startsWith("run:") || p.startsWith("runs:") || p.startsWith("approvals:")))
        throw new IllegalArgumentException("Release permissions must not grant platform control");
      if (humanInputSchema == null)
        humanInputSchema = Json.object().put("type", "string").put("maxLength", 10000);
      inputSchema = inputSchema.deepCopy();
      outputSchema = outputSchema.deepCopy();
      humanInputSchema = humanInputSchema.deepCopy();
      // Reuse the core's local-only schema inspection, including rejection of remote $refs.
      new ToolRegistry()
          .register(
              new ToolDescriptor(
                  "schema-check",
                  "schema_check",
                  "",
                  "internal",
                  "schema",
                  "1",
                  inputSchema,
                  ToolPolicy.readOnlyPolicy()),
              (t, a, c) -> ToolResult.success(a));
      for (JsonNode schema : List.of(outputSchema, humanInputSchema))
        new ToolRegistry()
            .register(
                new ToolDescriptor(
                    "schema-check",
                    "schema_check",
                    "",
                    "internal",
                    "schema",
                    "1",
                    schema,
                    ToolPolicy.readOnlyPolicy()),
                (t, a, c) -> ToolResult.success(a));
    }

    public String digest() {
      return "sha256:" + Json.hash(ApiJson.canonical(Json.tree(this)));
    }

    public JsonNode reference() {
      return Json.object()
          .put("agentId", agentId)
          .put("releaseId", releaseId)
          .put("digest", digest());
    }

    public ProgramDefinition definition(JsonNode inputs) {
      return new WorkflowProgram.Spec(workflow, model, inputs).definition();
    }

    public void validateInputs(JsonNode inputs) {
      try {
        ToolRegistry.validate(
            new ToolDescriptor(
                "input",
                "input",
                "",
                "internal",
                "input",
                "1",
                inputSchema,
                ToolPolicy.readOnlyPolicy()),
            inputs);
      } catch (RuntimeException ex) {
        throw new ApiFailure(422, "INPUT_INVALID");
      }
    }
  }
}
