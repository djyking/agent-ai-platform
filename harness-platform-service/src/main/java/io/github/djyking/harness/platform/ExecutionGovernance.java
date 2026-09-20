package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import io.github.djyking.harness.core.Contracts.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Database-owned admission, calendar reservations and dispatch permits. No remote call is inside
 * SQL.
 */
public final class ExecutionGovernance implements InvocationGate {
  private final PlatformRepository repository;
  private final ExecutionGovernanceConfig config;
  private final Deployment deployment;
  private final String fingerprint;
  private final String workerId = UUID.randomUUID().toString();

  public ExecutionGovernance(
      PlatformRepository repository, ExecutionGovernanceConfig config, Deployment deployment) {
    this(repository, config, deployment, "");
  }

  public ExecutionGovernance(
      PlatformRepository repository,
      ExecutionGovernanceConfig config,
      Deployment deployment,
      String bindings) {
    this.repository = repository;
    this.config = config;
    this.deployment = deployment;
    var runtime = Json.tree(deployment).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) runtime).remove("workerEnabled");
    // Set serialization order is not stable across JVMs. Model/tool array ordering is significant
    // only
    // to configuration authors; project applications are normalized here.
    for (JsonNode project : runtime.path("projects")) {
      TreeSet<String> names = new TreeSet<>();
      project.path("applications").forEach(n -> names.add(n.asText()));
      ((com.fasterxml.jackson.databind.node.ObjectNode) project)
          .set("applications", Json.tree(names));
    }
    TreeSet<String> disabled = new TreeSet<>();
    runtime.path("disabledReleases").forEach(n -> disabled.add(n.asText()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) runtime)
        .set("disabledReleases", Json.tree(disabled));
    fingerprint = Json.hash(List.of("execution-governance-v1", config, runtime, bindings));
  }

  @Override
  public boolean permitsUnknownRetry() {
    return false;
  }

  /** Read-only readiness probe. A failed database/configuration check never repairs state. */
  public boolean ready(boolean requireWorker) {
    try {
      return repository.transaction(
          c -> {
            try (var query =
                    prepare(
                        c,
                        "SELECT fingerprint FROM gov_config WHERE cluster_id=?",
                        config.clusterId());
                var rows = query.executeQuery()) {
              if (!rows.next() || !fingerprint.equals(rows.getString(1))) return false;
            }
            return !requireWorker
                || scalar(
                        c,
                        "SELECT COUNT(*) FROM gov_worker WHERE state='RUNNING' AND fingerprint=?"
                            + " AND heartbeat_at>?",
                        fingerprint,
                        now(c) - 5000)
                    > 0;
          });
    } catch (RuntimeException failure) {
      return false;
    }
  }

  public String fingerprint() {
    return fingerprint;
  }

  public String workerId() {
    return workerId;
  }

  public void initialize() {
    repository.transaction(
        c -> {
          if (repository.bootstrapSchema()) {
            String engine =
                c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
                    ? " ENGINE=InnoDB"
                    : "";
            String[] ddl = {
              "CREATE TABLE IF NOT EXISTS gov_config(cluster_id VARCHAR(128) PRIMARY"
                  + " KEY,fingerprint VARCHAR(64) NOT NULL,generation VARCHAR(128) NOT"
                  + " NULL,cursor_value BIGINT NOT NULL)",
              "CREATE TABLE IF NOT EXISTS gov_worker(worker_id VARCHAR(36) PRIMARY KEY,fingerprint"
                  + " VARCHAR(64) NOT NULL,heartbeat_at BIGINT NOT NULL,active_count INTEGER NOT"
                  + " NULL,state VARCHAR(20) NOT NULL)",
              "CREATE TABLE IF NOT EXISTS gov_pool(pool_id VARCHAR(200) PRIMARY KEY,minute_bucket"
                  + " BIGINT NOT NULL,request_count INTEGER NOT NULL,failures INTEGER NOT"
                  + " NULL,open_until BIGINT NOT NULL)",
              "CREATE TABLE IF NOT EXISTS gov_permit(permit_id VARCHAR(36) PRIMARY KEY,run_id"
                  + " VARCHAR(128) NOT NULL,project_id VARCHAR(128) NOT NULL,invocation_id"
                  + " VARCHAR(240) NOT NULL,fence BIGINT NOT NULL,pool_id VARCHAR(200) NOT"
                  + " NULL,model_call BOOLEAN NOT NULL,state VARCHAR(20) NOT NULL,created_at BIGINT"
                  + " NOT NULL,updated_at BIGINT NOT NULL,lease_until BIGINT NOT NULL,resolution"
                  + " VARCHAR(1000),INDEX gov_permit_capacity(pool_id,state),INDEX"
                  + " gov_permit_run(run_id,state),INDEX gov_permit_model(state,model_call),INDEX"
                  + " gov_permit_project(project_id,pool_id,created_at),FOREIGN KEY(run_id)"
                  + " REFERENCES harness_runs(run_id))",
              "CREATE TABLE IF NOT EXISTS gov_budget(scope_key VARCHAR(200) NOT NULL,period_key"
                  + " VARCHAR(10) NOT NULL,reserved_tokens BIGINT NOT NULL,charged_tokens BIGINT"
                  + " NOT NULL,PRIMARY KEY(scope_key,period_key))",
              "CREATE TABLE IF NOT EXISTS gov_reservation(run_id VARCHAR(128) NOT NULL,scope_key"
                  + " VARCHAR(200) NOT NULL,period_key VARCHAR(10) NOT NULL,reserved_tokens BIGINT"
                  + " NOT NULL,settled BOOLEAN NOT NULL,PRIMARY"
                  + " KEY(run_id,scope_key,period_key),INDEX"
                  + " gov_reservation_pending(scope_key,settled,run_id),INDEX"
                  + " gov_reservation_settle(settled,run_id),FOREIGN KEY(run_id) REFERENCES"
                  + " harness_runs(run_id))"
            };
            for (String sql : ddl) {
              if (!engine.isEmpty())
                sql =
                    sql.replaceAll(
                        "(\\b(?:cluster_id|project_id|pool_id|scope_key) VARCHAR\\(\\d+\\))",
                        "$1 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
              execute(c, sql + engine);
            }
            try (var query =
                    prepare(
                        c,
                        "SELECT schema_version FROM harness_schema WHERE"
                            + " component='execution-governance'");
                var result = query.executeQuery()) {
              if (result.next()) {
                if (result.getInt(1) != 1)
                  throw new IllegalStateException("Unsupported governance schema");
              } else
                execute(
                    c,
                    "INSERT INTO harness_schema(component,schema_version)"
                        + " VALUES('execution-governance',1)");
            }
            try (var query =
                    prepare(
                        c,
                        "SELECT fingerprint FROM gov_config WHERE cluster_id=?",
                        config.clusterId());
                var result = query.executeQuery()) {
              if (!result.next())
                execute(
                    c,
                    "INSERT INTO gov_config VALUES(?,?,?,0)",
                    config.clusterId(),
                    fingerprint,
                    config.generation());
            }
          }
          SchemaValidation.require(
              c,
              "execution-governance",
              1,
              Map.of(
                  "gov_config", "cluster_id,fingerprint,generation,cursor_value",
                  "gov_worker", "worker_id,fingerprint,heartbeat_at,active_count,state",
                  "gov_pool", "pool_id,minute_bucket,request_count,failures,open_until",
                  "gov_permit",
                      "permit_id,run_id,project_id,invocation_id,fence,pool_id,model_call,state,created_at,updated_at,lease_until,resolution",
                  "gov_budget", "scope_key,period_key,reserved_tokens,charged_tokens",
                  "gov_reservation", "run_id,scope_key,period_key,reserved_tokens,settled"));
          if (scalar(c, "SELECT COUNT(*) FROM gov_config") != 1)
            throw new IllegalStateException("One governance cluster per database is required");
          lockConfiguration(c);
          return null;
        });
    repository.governance(this);
  }

  /** Explicit offline migration: no active Runs, permits, or recently heartbeating workers. */
  public void migrateConfiguration(String expectedFingerprint) {
    if (expectedFingerprint == null || !expectedFingerprint.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Expected fingerprint required");
    repository.transaction(
        c -> {
          try (var query =
                  prepare(
                      c,
                      "SELECT fingerprint FROM gov_config WHERE cluster_id=? FOR UPDATE",
                      config.clusterId());
              var rows = query.executeQuery()) {
            if (!rows.next() || !expectedFingerprint.equals(rows.getString(1)))
              throw new IllegalStateException("GOVERNANCE_MIGRATION_CAS_CONFLICT");
          }
          long recent = now(c) - Math.max(30000, deployment.leaseMillis() + 10000);
          if (scalar(
                      c,
                      "SELECT COUNT(*) FROM gov_worker WHERE state<>'STOPPED' AND heartbeat_at>?",
                      recent)
                  > 0
              || scalar(c, "SELECT COUNT(*) FROM harness_run_index WHERE terminal_at IS NULL") > 0
              || scalar(
                      c,
                      "SELECT COUNT(*) FROM gov_permit WHERE state IN"
                          + " ('RESERVED','DISPATCHED','UNKNOWN')")
                  > 0)
            throw new IllegalStateException(
                "GOVERNANCE_MIGRATION_REQUIRES_DRAINED_OFFLINE_CLUSTER");
          execute(
              c,
              "UPDATE gov_config SET fingerprint=?,generation=? WHERE cluster_id=?",
              fingerprint,
              config.generation(),
              config.clusterId());
          return null;
        });
  }

  private void lockConfiguration(Connection c) throws SQLException {
    try (var query =
            prepare(
                c,
                "SELECT fingerprint FROM gov_config WHERE cluster_id=? FOR UPDATE",
                config.clusterId());
        var result = query.executeQuery()) {
      if (!result.next() || !fingerprint.equals(result.getString(1)))
        throw new IllegalStateException(
            "EXECUTION_CONFIG_MISMATCH: stop workers and use an explicit reviewed migration");
    }
  }

  public void heartbeat(int active, boolean closing) {
    repository.transaction(
        c -> {
          lockConfiguration(c);
          long current = now(c);
          if (execute(
                  c,
                  "UPDATE gov_worker SET heartbeat_at=?,active_count=?,state=? WHERE worker_id=?",
                  current,
                  active,
                  closing ? "STOPPED" : "RUNNING",
                  workerId)
              == 0)
            execute(
                c,
                "INSERT INTO gov_worker VALUES(?,?,?,?,?)",
                workerId,
                fingerprint,
                current,
                active,
                closing ? "STOPPED" : "RUNNING");
          // An abandoned reservation is known not to have crossed dispatch; dispatched calls remain
          // quarantined after owner death. They are never freed merely because a lease elapsed.
          execute(
              c,
              "UPDATE gov_permit SET state='RELEASED',updated_at=?,resolution='never dispatched"
                  + " before lease expiry' WHERE state='RESERVED' AND lease_until<=?",
              current,
              current);
          execute(
              c,
              "UPDATE gov_permit SET state='UNKNOWN',updated_at=? WHERE state='DISPATCHED' AND"
                  + " lease_until<=?",
              current,
              current);
          return null;
        });
  }

  public int nextProject(int count) {
    if (count == 0) return 0;
    return repository.transaction(
        c -> {
          lockConfiguration(c);
          long cursor;
          try (var query =
                  prepare(
                      c,
                      "SELECT cursor_value FROM gov_config WHERE cluster_id=?",
                      config.clusterId());
              var result = query.executeQuery()) {
            result.next();
            cursor = result.getLong(1);
          }
          execute(
              c,
              "UPDATE gov_config SET cursor_value=? WHERE cluster_id=?",
              (cursor + 1) % count,
              config.clusterId());
          return (int) (cursor % count);
        });
  }

  @Override
  public Reservation reserve(RunState claimed, long timeoutMillis) {
    String pool =
        claimed.pending.kind.equals("MODEL")
            ? "provider:" + claimed.pending.modelRequest.profile().provider()
            : "tool:" + claimed.pending.toolKey;
    boolean model = claimed.pending.kind.equals("MODEL");
    ExecutionGovernanceConfig.Pool policy =
        model
            ? config
                .providers()
                .getOrDefault(
                    claimed.pending.modelRequest.profile().provider(), config.defaultProvider())
            : config.tools().getOrDefault(claimed.pending.toolKey, config.defaultTool());
    return repository.transaction(
        c -> {
          lockConfiguration(c);
          verifyFence(c, claimed.id, claimed.fence);
          long current = now(c), minute = current / 60000;
          ensurePool(c, pool, minute);
          long openUntil;
          int requests;
          try (var query =
                  prepare(
                      c,
                      "SELECT minute_bucket,request_count,open_until FROM gov_pool WHERE pool_id=?",
                      pool);
              var rows = query.executeQuery()) {
            rows.next();
            requests = rows.getLong(1) == minute ? rows.getInt(2) : 0;
            openUntil = rows.getLong(3);
          }
          long count =
              scalar(
                  c,
                  "SELECT COUNT(*) FROM gov_permit WHERE pool_id=? AND state IN"
                      + " ('RESERVED','DISPATCHED','UNKNOWN')",
                  pool);
          long allModels =
              model
                  ? scalar(
                      c,
                      "SELECT COUNT(*) FROM gov_permit WHERE model_call=TRUE AND state IN"
                          + " ('RESERVED','DISPATCHED','UNKNOWN')")
                  : 0;
          if (openUntil > current
              || requests >= policy.requestsPerMinute()
              || count >= policy.concurrency()
              || (model && allModels >= config.modelConcurrency())) {
            long retry =
                openUntil > current
                    ? openUntil
                    : requests >= policy.requestsPerMinute()
                        ? (minute + 1) * 60000
                        : current + 1000;
            return new Permit(
                null, Instant.ofEpochMilli(retry), claimed.id, claimed.fence, pool, policy);
          }
          String id = UUID.randomUUID().toString();
          var owned = repository.owned(c, claimed.id);
          execute(
              c,
              "INSERT INTO"
                  + " gov_permit(permit_id,run_id,project_id,invocation_id,fence,pool_id,model_call,state,created_at,updated_at,lease_until)"
                  + " VALUES(?,?,?,?,?,?,?,'RESERVED',?,?,?)",
              id,
              claimed.id,
              owned.project(),
              claimed.pending.id,
              claimed.fence,
              pool,
              model,
              current,
              current,
              claimed.leaseUntil.toEpochMilli());
          execute(
              c,
              "UPDATE gov_pool SET minute_bucket=?,request_count=? WHERE pool_id=?",
              minute,
              requests + 1,
              pool);
          return new Permit(id, null, claimed.id, claimed.fence, pool, policy);
        });
  }

  private void ensurePool(Connection c, String pool, long minute) throws SQLException {
    if (scalar(c, "SELECT COUNT(*) FROM gov_pool WHERE pool_id=?", pool) == 0)
      execute(c, "INSERT INTO gov_pool VALUES(?,?,0,0,0)", pool, minute);
  }

  private static long scalar(Connection c, String sql, Object... args) throws SQLException {
    try (var query = prepare(c, sql, args);
        var rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private void verifyFence(Connection c, String run, long fence) throws SQLException {
    try (var query =
            prepare(
                c, "SELECT fence,lease_until FROM harness_runs WHERE run_id=? FOR UPDATE", run);
        var result = query.executeQuery()) {
      if (!result.next() || result.getLong(1) != fence || result.getLong(2) <= now(c))
        throw new RunStore.Conflict("Governance fence expired");
    }
  }

  private final class Permit implements Reservation {
    private final String id, run, pool;
    private final long fence;
    private final Instant retry;
    private final ExecutionGovernanceConfig.Pool policy;

    private Permit(
        String id,
        Instant retry,
        String run,
        long fence,
        String pool,
        ExecutionGovernanceConfig.Pool policy) {
      this.id = id;
      this.retry = retry;
      this.run = run;
      this.fence = fence;
      this.pool = pool;
      this.policy = policy;
    }

    public boolean granted() {
      return id != null;
    }

    public Instant retryAt() {
      return retry;
    }

    public <T> T invoke(Callable<T> operation) throws Exception {
      repository.transaction(
          c -> {
            lockConfiguration(c);
            verifyFence(c, run, fence);
            if (execute(
                    c,
                    "UPDATE gov_permit SET state='DISPATCHED',updated_at=? WHERE permit_id=? AND"
                        + " state='RESERVED'",
                    now(c),
                    id)
                != 1) throw new RunStore.Conflict("Dispatch permit is no longer owned");
            return null;
          });
      try {
        T result = operation.call();
        if (result == null) {
          complete(true, true);
          return null;
        }
        complete(false, result instanceof ToolResult tool && tool.error());
        return result;
      } catch (InvocationException failure) {
        complete(
            failure.kind() == FailureKind.UNKNOWN,
            failure instanceof InvocationGate.NotDispatched ? null : true);
        throw failure;
      } catch (Exception | Error failure) {
        complete(true, true);
        throw failure;
      }
    }

    private void complete(boolean unknown, Boolean failure) {
      repository.transaction(
          c -> {
            lockConfiguration(c);
            long current = now(c);
            int changed =
                execute(
                    c,
                    "UPDATE gov_permit SET state=?,updated_at=? WHERE permit_id=? AND state IN"
                        + " ('DISPATCHED','UNKNOWN')",
                    unknown ? "UNKNOWN" : "RELEASED",
                    current,
                    id);
            if (changed > 0 && failure != null) {
              if (!failure)
                execute(c, "UPDATE gov_pool SET failures=0,open_until=0 WHERE pool_id=?", pool);
              else {
                long failures =
                    scalar(c, "SELECT failures FROM gov_pool WHERE pool_id=?", pool) + 1;
                execute(
                    c,
                    "UPDATE gov_pool SET failures=?,open_until=? WHERE pool_id=?",
                    failures,
                    failures >= policy.failuresBeforeOpen() ? current + policy.cooldownMillis() : 0,
                    pool);
              }
            }
            return null;
          });
    }

    public void abandon() {
      if (id == null) return;
      repository.transaction(
          c -> {
            lockConfiguration(c);
            execute(
                c,
                "UPDATE gov_permit SET state='RELEASED',updated_at=?,resolution='not dispatched'"
                    + " WHERE permit_id=? AND state='RESERVED'",
                now(c),
                id);
            return null;
          });
    }
  }

  /** Trusted operator action after independently establishing the external call has stopped. */
  public void resolveUnknownPermit(String permit, String evidence) {
    if (evidence == null || evidence.isBlank() || evidence.length() > 1000)
      throw new IllegalArgumentException("Independent termination evidence required");
    repository.transaction(
        c -> {
          lockConfiguration(c);
          try (var query =
                  prepare(
                      c,
                      "SELECT state,resolution FROM gov_permit WHERE permit_id=? FOR UPDATE",
                      permit);
              var row = query.executeQuery()) {
            if (!row.next()) throw new ApiFailure(409, "PERMIT_NOT_UNKNOWN");
            if ("RELEASED".equals(row.getString(1)) && evidence.equals(row.getString(2)))
              return null;
            if (!"UNKNOWN".equals(row.getString(1)))
              throw new ApiFailure(409, "PERMIT_NOT_UNKNOWN");
          }
          if (execute(
                  c,
                  "UPDATE gov_permit SET state='RELEASED',updated_at=?,resolution=? WHERE"
                      + " permit_id=? AND state='UNKNOWN'",
                  now(c),
                  evidence,
                  permit)
              != 1) throw new ApiFailure(409, "PERMIT_NOT_UNKNOWN");
          return null;
        });
  }

  private record Bucket(String scope, String period, long limit) {}

  private List<Bucket> buckets(String project, String application, long instant) {
    var budget = config.budgets().get(project);
    if (budget == null) return List.of();
    LocalDate date = Instant.ofEpochMilli(instant).atZone(ZoneOffset.UTC).toLocalDate();
    String day = date.toString(), month = date.toString().substring(0, 7);
    String app = "application:" + Json.hash(List.of(project, application));
    return List.of(
        new Bucket("project:" + project, day, budget.dailyTokens()),
        new Bucket("project:" + project, month, budget.monthlyTokens()),
        new Bucket(app, day, budget.applicationDailyTokens()),
        new Bucket(app, month, budget.applicationMonthlyTokens()));
  }

  void admitBudget(Connection c, String project, String application, long requested)
      throws SQLException {
    lockConfiguration(c);
    settle(c, project);
    for (Bucket bucket : buckets(project, application, now(c))) {
      ensureBudget(c, bucket);
      try (var query =
              prepare(
                  c,
                  "SELECT reserved_tokens,charged_tokens FROM gov_budget WHERE scope_key=? AND"
                      + " period_key=? FOR UPDATE",
                  bucket.scope,
                  bucket.period);
          var rows = query.executeQuery()) {
        rows.next();
        long used = Math.addExact(rows.getLong(1), rows.getLong(2));
        if (bucket.limit > 0 && requested > bucket.limit - used)
          throw new ApiFailure(429, "CALENDAR_TOKEN_QUOTA_EXCEEDED");
      }
    }
  }

  void reserveBudget(Connection c, Owned run) throws SQLException {
    for (Bucket bucket : buckets(run.project(), run.application(), now(c))) {
      ensureBudget(c, bucket);
      long used =
          scalar(
              c,
              "SELECT reserved_tokens+charged_tokens FROM gov_budget WHERE scope_key=? AND"
                  + " period_key=? FOR UPDATE",
              bucket.scope,
              bucket.period);
      if (bucket.limit > 0 && run.limits().maxTokens() > bucket.limit - used)
        throw new ApiFailure(429, "CALENDAR_TOKEN_QUOTA_EXCEEDED");
      execute(
          c,
          "UPDATE gov_budget SET reserved_tokens=reserved_tokens+? WHERE scope_key=? AND"
              + " period_key=?",
          run.limits().maxTokens(),
          bucket.scope,
          bucket.period);
      execute(
          c,
          "INSERT INTO gov_reservation VALUES(?,?,?,?,FALSE)",
          run.runId(),
          bucket.scope,
          bucket.period,
          run.limits().maxTokens());
    }
  }

  private void ensureBudget(Connection c, Bucket bucket) throws SQLException {
    if (scalar(
            c,
            "SELECT COUNT(*) FROM gov_budget WHERE scope_key=? AND period_key=?",
            bucket.scope,
            bucket.period)
        == 0) execute(c, "INSERT INTO gov_budget VALUES(?,?,0,0)", bucket.scope, bucket.period);
  }

  public void settle(String project) {
    repository.transaction(
        c -> {
          repository.lockProject(c, project);
          settle(c, project);
          return null;
        });
  }

  private void settle(Connection c, String project) throws SQLException {
    record Item(String run, String scope, String period, long reserved, long charged) {}
    List<Item> items = new ArrayList<>();
    try (var query =
            prepare(
                c,
                "SELECT b.run_id,b.scope_key,b.period_key,b.reserved_tokens,i.charged_tokens FROM"
                    + " gov_reservation b JOIN harness_run_index i ON i.run_id=b.run_id JOIN"
                    + " platform_runs p ON p.run_id=b.run_id WHERE p.project_id=? AND"
                    + " b.settled=FALSE AND i.terminal_at IS NOT NULL LIMIT 800 FOR UPDATE",
                project);
        var rows = query.executeQuery()) {
      while (rows.next())
        items.add(
            new Item(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getLong(4),
                rows.getLong(5)));
    }
    for (Item item : items) {
      execute(
          c,
          "UPDATE gov_budget SET reserved_tokens=reserved_tokens-?,charged_tokens=charged_tokens+?"
              + " WHERE scope_key=? AND period_key=?",
          item.reserved,
          item.charged,
          item.scope,
          item.period);
      execute(
          c,
          "UPDATE gov_reservation SET settled=TRUE WHERE run_id=? AND scope_key=? AND period_key=?",
          item.run,
          item.scope,
          item.period);
    }
  }

  /** Project-scoped operational metadata only. No prompt, output, secret or remote credential. */
  public JsonNode overview(String project) {
    deployment.project(project);
    return repository.transaction(
        c -> {
          var out =
              Json.object()
                  .put("fingerprint", fingerprint)
                  .put("generation", config.generation())
                  .put("clusterId", config.clusterId())
                  .put("databaseTime", now(c));
          var workers = out.putArray("workers");
          try (var query =
                  prepare(
                      c,
                      "SELECT worker_id,heartbeat_at,active_count,state,fingerprint FROM gov_worker"
                          + " ORDER BY heartbeat_at DESC LIMIT 100");
              var rows = query.executeQuery()) {
            while (rows.next())
              workers.add(
                  Json.object()
                      .put("workerId", rows.getString(1))
                      .put("heartbeatAt", rows.getLong(2))
                      .put("activeCount", rows.getInt(3))
                      .put("state", rows.getString(4))
                      .put("fingerprintMatches", fingerprint.equals(rows.getString(5))));
          }
          var pools = out.putArray("pools");
          try (var query =
                  prepare(
                      c,
                      "SELECT pool_id,request_count,minute_bucket,failures,open_until FROM gov_pool"
                          + " WHERE pool_id IN (SELECT pool_id FROM gov_permit WHERE project_id=?)"
                          + " ORDER BY pool_id",
                      project);
              var rows = query.executeQuery()) {
            while (rows.next()) {
              String pool = rows.getString(1);
              boolean model = pool.startsWith("provider:");
              var policy =
                  model
                      ? config.providers().getOrDefault(pool.substring(9), config.defaultProvider())
                      : config.tools().getOrDefault(pool.substring(5), config.defaultTool());
              pools.add(
                  Json.object()
                      .put("poolId", pool)
                      .put("concurrencyLimit", policy.concurrency())
                      .put("requestsPerMinuteLimit", policy.requestsPerMinute())
                      .put("countsScope", "project")
                      .put("limitScope", "cluster")
                      .put(
                          "requestsInMinute",
                          scalar(
                              c,
                              "SELECT COUNT(*) FROM gov_permit WHERE pool_id=? AND project_id=? AND"
                                  + " created_at>=?",
                              pool,
                              project,
                              now(c) / 60000 * 60000))
                      .put("minuteBucket", rows.getLong(3))
                      .put("openUntil", rows.getLong(5))
                      .put(
                          "occupied",
                          scalar(
                              c,
                              "SELECT COUNT(*) FROM gov_permit WHERE pool_id=? AND project_id=? AND"
                                  + " state IN ('RESERVED','DISPATCHED','UNKNOWN')",
                              pool,
                              project))
                      .put(
                          "unknown",
                          scalar(
                              c,
                              "SELECT COUNT(*) FROM gov_permit WHERE pool_id=? AND project_id=? AND"
                                  + " state='UNKNOWN'",
                              pool,
                              project)));
            }
          }
          var budgets = out.putArray("budgets");
          Set<String> scopes = new TreeSet<>();
          scopes.add("project:" + project);
          for (String app : deployment.project(project).applications())
            scopes.add("application:" + Json.hash(List.of(project, app)));
          Map<String, String> labels = new HashMap<>();
          labels.put("project:" + project, project);
          for (String application : deployment.project(project).applications())
            labels.put("application:" + Json.hash(List.of(project, application)), application);
          var budgetPolicy = config.budgets().get(project);
          for (String scope : scopes)
            try (var query =
                    prepare(
                        c,
                        "SELECT period_key,reserved_tokens,charged_tokens FROM gov_budget WHERE"
                            + " scope_key=? ORDER BY period_key DESC LIMIT 64",
                        scope);
                var rows = query.executeQuery()) {
              while (rows.next())
                budgets.add(
                    Json.object()
                        .put("scope", scope)
                        .put("scopeLabel", labels.get(scope))
                        .put("scopeType", scope.startsWith("project:") ? "project" : "application")
                        .put(
                            "limitTokens",
                            budgetPolicy == null
                                ? 0
                                : scope.startsWith("project:")
                                    ? (rows.getString(1).length() == 10
                                        ? budgetPolicy.dailyTokens()
                                        : budgetPolicy.monthlyTokens())
                                    : (rows.getString(1).length() == 10
                                        ? budgetPolicy.applicationDailyTokens()
                                        : budgetPolicy.applicationMonthlyTokens()))
                        .put("period", rows.getString(1))
                        .put("reservedTokens", rows.getLong(2))
                        .put("chargedTokens", rows.getLong(3)));
            }
          out.put(
              "unknownPermitCount",
              scalar(
                  c,
                  "SELECT COUNT(*) FROM gov_permit WHERE project_id=? AND state='UNKNOWN'",
                  project));
          var unknown = out.putArray("unknownPermits");
          try (var query =
                  prepare(
                      c,
                      "SELECT permit_id,run_id,pool_id,updated_at FROM gov_permit WHERE"
                          + " project_id=? AND state='UNKNOWN' ORDER BY updated_at LIMIT 100",
                      project);
              var rows = query.executeQuery()) {
            while (rows.next())
              unknown.add(
                  Json.object()
                      .put("permitId", rows.getString(1))
                      .put("runId", rows.getString(2))
                      .put("poolId", rows.getString(3))
                      .put("updatedAt", rows.getLong(4)));
          }
          out.put("modelConcurrency", config.modelConcurrency());
          out.put("calendarBudgetConfigured", config.budgets().containsKey(project));
          return out;
        });
  }
}
