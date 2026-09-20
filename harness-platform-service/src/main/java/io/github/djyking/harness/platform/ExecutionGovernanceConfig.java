package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.Json;
import java.nio.file.*;
import java.util.*;

/** Trusted operator policy, separate from release and user-editable Studio configuration. */
public record ExecutionGovernanceConfig(
    String clusterId,
    String generation,
    int modelConcurrency,
    Pool defaultProvider,
    Pool defaultTool,
    Map<String, Pool> providers,
    Map<String, Pool> tools,
    Map<String, Budget> budgets) {
  public record Pool(
      int concurrency, int requestsPerMinute, int failuresBeforeOpen, long cooldownMillis) {
    public Pool {
      if (concurrency < 1
          || concurrency > 256
          || requestsPerMinute < 1
          || failuresBeforeOpen < 1
          || cooldownMillis < 1000
          || cooldownMillis > 3600000)
        throw new IllegalArgumentException("Invalid invocation pool policy");
    }
  }

  /** Zero means unmetered. It is not an implicit unlimited-production-budget approval. */
  public record Budget(
      long dailyTokens,
      long monthlyTokens,
      long applicationDailyTokens,
      long applicationMonthlyTokens) {
    public Budget {
      if (dailyTokens < 0
          || monthlyTokens < 0
          || applicationDailyTokens < 0
          || applicationMonthlyTokens < 0)
        throw new IllegalArgumentException("Negative calendar budget");
    }
  }

  public ExecutionGovernanceConfig {
    ApiJson.identifier(clusterId);
    ApiJson.identifier(generation);
    if (modelConcurrency < 1 || modelConcurrency > 256)
      throw new IllegalArgumentException("Invalid model concurrency");
    Objects.requireNonNull(defaultProvider);
    Objects.requireNonNull(defaultTool);
    providers = providers == null ? Map.of() : Map.copyOf(providers);
    tools = tools == null ? Map.of() : Map.copyOf(tools);
    budgets = budgets == null ? Map.of() : Map.copyOf(budgets);
  }

  public static ExecutionGovernanceConfig defaults() {
    return new ExecutionGovernanceConfig(
        "platform",
        "phase5-v1",
        5,
        new Pool(5, 120, 5, 30000),
        new Pool(10, 300, 5, 30000),
        Map.of(),
        Map.of(),
        Map.of());
  }

  public static ExecutionGovernanceConfig load(Path path) {
    try {
      return Json.MAPPER
          .copy()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(Files.readAllBytes(path), ExecutionGovernanceConfig.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Invalid governance configuration", failure);
    }
  }
}
