import { useRef, useState } from "react";
import { Activity, Bell, Database, RefreshCw, ShieldCheck } from "lucide-react";
import { ApiError, commandKey, projectPath, request } from "./api";
import { useRemote } from "./hooks";
import { clearPending, persistPending, readPending } from "./pending-commands";
import { Empty, ErrorNotice, Loading, Notice, PageHeading } from "./ui";
import type { Principal } from "./types";

type Alert = {
  id: string;
  state: string;
  severity: string;
  value: number;
  revision: number;
  observedAt: number;
  acknowledged: boolean;
  acknowledgedBy?: string;
  occurrences: number;
};
type Policy = {
  payloadDays: number;
  traceDays: number;
  auditDays: number;
  backupDays: number;
  archiveDays: number;
};
type Execution = {
  clusterId?: string;
  generation?: string;
  databaseTime?: number;
  modelConcurrency?: number;
  calendarBudgetConfigured?: boolean;
  available?: boolean;
  workers?: {
    workerId: string;
    state: string;
    heartbeatAt: number;
    activeCount: number;
    fingerprintMatches: boolean;
  }[];
  pools?: {
    poolId: string;
    requestsInMinute: number;
    openUntil: number;
    occupied: number;
    unknown: number;
    concurrencyLimit?: number;
    requestsPerMinuteLimit?: number;
    countsScope?: string;
    limitScope?: string;
  }[];
  budgets?: {
    scope: string;
    period: string;
    reservedTokens: number;
    chargedTokens: number;
    scopeLabel?: string;
    scopeType?: string;
    limitTokens?: number;
  }[];
  unknownPermits?: {
    permitId: string;
    runId: string;
    poolId: string;
    updatedAt: number;
  }[];
};
type Overview = {
  project: string;
  scope: string;
  generatedAt: number;
  statusCounts: Record<string, number>;
  queue: { queued: number; oldestAgeMs: number };
  usage: {
    reservedTokens: number;
    chargedTokens: number;
    modelCalls: number;
    toolCalls: number;
  };
  capabilities: { admin: boolean };
  retention: Policy;
  alerts: Alert[];
  execution?: Execution;
  backup?: { status: string };
};
type Plan = {
  planId: string;
  digest: string;
  createdAt: number;
  expiresAt: number;
  policy: Policy;
  candidates: {
    runId: string;
    revision: number;
    terminalAt: number;
    action: string;
    traceCount: number;
    pruneAudit: boolean;
  }[];
  traceCount: number;
  pinnedCount: number;
  hasMore: boolean;
  archiveAvailable: boolean;
  expiredArchives: string[];
  expiredAudit: string[];
  expiredInbox: string[];
  scannedCount: number;
  nextCursor?: string;
};
const labels: Record<string, string> = {
  QUEUED: "排队",
  RUNNING: "执行中",
  WAITING_INPUT: "等待补充",
  WAITING_APPROVAL: "等待审批",
  PAUSED: "已暂停",
  NEEDS_ATTENTION: "需要处理",
  COMPLETED: "已完成",
  FAILED: "已失败",
  CANCELLED: "已取消",
  EXPIRED: "已到期",
  BUDGET_EXCEEDED: "额度不足",
};
const time = (value: number | undefined) =>
  value ? new Date(value).toLocaleString() : "—";
const number = (value: number | undefined) =>
  value == null ? "—" : value.toLocaleString();
const alertLabels: Record<string, string> = {
  UNRESOLVED_EXECUTION: "任务需要核验",
  QUEUE_DELAY: "排队时间过长",
  WORKER_UNAVAILABLE: "执行节点不可用",
  UNRESOLVED_PROVIDER_PERMIT: "远端调用结果未知",
};
const workerLabels: Record<string, string> = {
  RUNNING: "运行中",
  DRAINING: "正在排空",
  STOPPED: "已停止",
};

export function Operations({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  return (
    <OperationsView
      key={`${project}/${principal.application}/${principal.subject}`}
      project={project}
      principal={principal}
    />
  );
}

function OperationsView({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const base = `${projectPath(project)}/operations`;
  const remote = useRemote<Overview>(base);
  const scope = `${project}/${principal.application}/${principal.subject}`;
  const [pending, setPending] = useState(() =>
    readPending("operations", scope, "operations"),
  );
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [plan, setPlan] = useState<Plan>();
  const [reviewed, setReviewed] = useState(false);
  const [receipt, setReceipt] = useState<Record<string, unknown>>();
  const mutationInFlight = useRef(false);
  async function recover() {
    if (!pending?.key) return;
    setBusy(true);
    setError(undefined);
    try {
      const result = await request<Record<string, unknown>>(
        `${base}/commands/${encodeURIComponent(pending.key)}`,
      );
      setReceipt(result.data);
      clearPending("operations", scope);
      setPending(undefined);
      setPlan(undefined);
      setReviewed(false);
      remote.reload();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  async function mutate(path: string, body: unknown, etag?: string) {
    if (
      pending ||
      busy ||
      mutationInFlight.current ||
      !remote.data?.capabilities.admin
    )
      return;
    mutationInFlight.current = true;
    setBusy(true);
    setError(undefined);
    setReceipt(undefined);
    const item = { key: commandKey(), resource: "operations" };
    try {
      persistPending("operations", scope, item);
      setPending(item);
      const result = await request<Record<string, unknown>>(base + path, {
        method: "POST",
        body,
        etag,
        key: item.key,
      });
      setReceipt(result.data);
      clearPending("operations", scope);
      setPending(undefined);
      setPlan(undefined);
      setReviewed(false);
      remote.reload();
    } catch (failure) {
      setError(failure);
      if (failure instanceof ApiError && !failure.uncertain) {
        clearPending("operations", scope);
        setPending(undefined);
      }
    } finally {
      mutationInFlight.current = false;
      setBusy(false);
    }
  }
  async function preview(after?: string) {
    if (pending || busy || !remote.data?.capabilities.admin) return;
    setBusy(true);
    setError(undefined);
    setReviewed(false);
    setPlan(undefined);
    try {
      setPlan(
        (
          await request<Plan>(`${base}/retention/preview`, {
            method: "POST",
            body: { limit: 100, ...(after ? { after } : {}) },
          })
        ).data,
      );
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  const data = remote.data;
  const blocked = busy || Boolean(pending);
  return (
    <>
      <PageHeading
        eyebrow="PLATFORM OPERATIONS"
        title="运行治理"
        description="查看运行容量、异常与数据保留，依据当前项目权限处理告警。"
        actions={
          <button className="button" onClick={remote.reload}>
            <RefreshCw size={16} />
            刷新
          </button>
        }
      />
      {Boolean(remote.error) && (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      )}
      {Boolean(error) && <ErrorNotice error={error} />}
      {pending && (
        <Notice tone="warning">
          有一项操作结果尚未确认，新的管理操作已暂停。原请求标识：
          <code>{pending.key || "无法读取"}</code>。
          <button
            className="button"
            disabled={busy || !pending.key}
            onClick={recover}
          >
            核对原操作回执
          </button>
          查询不到回执时仍需保留此状态，不自动重新提交。
        </Notice>
      )}
      {receipt && (
        <Notice>
          操作结果已确认：
          {receipt.status === "APPLIED" ? "计划已执行" : "已记录"}。
          {typeof receipt.archivedRuns === "number" &&
            `已归档 ${receipt.archivedRuns} 个任务。`}
          {typeof receipt.deletedTraces === "number" &&
            `已清理 ${receipt.deletedTraces} 条诊断记录。`}
          {typeof receipt.deletedArchives === "number" &&
            `已删除 ${receipt.deletedArchives} 份到期加密归档。`}
          {typeof receipt.deletedAuditRows === "number" &&
            `已清理 ${receipt.deletedAuditRows} 条审计与通知记录。`}
        </Notice>
      )}
      {remote.loading && !data ? (
        <Loading />
      ) : (
        data && (
          <>
            <p className="operations-scope">
              {data.capabilities.admin
                ? "当前项目治理视图"
                : "当前应用与用户的运行视图"}{" "}
              · 更新于 {time(data.generatedAt)}
            </p>
            <div className="operations-metrics">
              <Metric
                title="排队任务"
                value={number(data.queue.queued)}
                detail={`最长等待 ${Math.round(data.queue.oldestAgeMs / 1000)} 秒`}
                icon={<Activity />}
              />
              <Metric
                title="执行中的任务"
                value={number(data.statusCounts.RUNNING ?? 0)}
                detail="人工等待与暂停单独统计"
                icon={<RefreshCw />}
              />
              <Metric
                title="需要处理"
                value={number(data.statusCounts.NEEDS_ATTENTION ?? 0)}
                detail="包含结果未知等异常状态"
                icon={<Bell />}
              />
              <Metric
                title="预留 Token"
                value={number(data.usage.reservedTokens)}
                detail="预留额度不等于实际账单"
                icon={<ShieldCheck />}
              />
            </div>
            <div className="operations-columns">
              <section className="studio-section">
                <h3>任务状态</h3>
                <div className="operations-states">
                  {Object.entries(data.statusCounts).map(([state, count]) => (
                    <div key={state}>
                      <span>{labels[state] ?? state}</span>
                      <strong>{number(count)}</strong>
                    </div>
                  ))}
                </div>
                <p>
                  模型调用 {number(data.usage.modelCalls)} 次 · 工具调用{" "}
                  {number(data.usage.toolCalls)} 次
                </p>
                <p>
                  保守用量账本 {number(data.usage.chargedTokens)}{" "}
                  Token；结果未知时可能仍包含预留。
                </p>
              </section>
              <section className="studio-section">
                <h3>数据保留策略</h3>
                <dl className="operations-policy">
                  <dt>输入、结果与导出</dt>
                  <dd>{data.retention.payloadDays} 天</dd>
                  <dt>加密归档（自归档起）</dt>
                  <dd>再保留 {data.retention.archiveDays} 天</dd>
                  <dt>诊断记录</dt>
                  <dd>{data.retention.traceDays} 天</dd>
                  <dt>审计元数据</dt>
                  <dd>{data.retention.auditDays} 天</dd>
                  <dt>备份</dt>
                  <dd>{data.retention.backupDays} 天</dd>
                </dl>
                <p>
                  在线正文到期后，经核对转为加密归档；归档再到期后仍须核对计划才删除。未决任务、审批、未知结果与必要防重记录按安全依赖保留。在用知识和发布不按任务期限删除。
                </p>
                {data.capabilities.admin && (
                  <button
                    className="button"
                    disabled={blocked}
                    onClick={() => preview()}
                  >
                    <Database size={16} />
                    预览本批清理计划
                  </button>
                )}
              </section>
            </div>
            {data.capabilities.admin && data.backup && (
              <Notice>
                备份状态：需要备份作业与隔离恢复证据。当前页面不能据此确认异机副本、15
                分钟数据损失窗口或 60 分钟安全恢复目标达标。
              </Notice>
            )}
            {data.capabilities.admin && (
              <section className="studio-section operations-section">
                <h3>项目告警</h3>
                {data.alerts.length === 0 ? (
                  <Empty title="当前没有告警" />
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>规则</th>
                          <th>状态</th>
                          <th>当前值</th>
                          <th>最近观察</th>
                          <th>处理</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.alerts.map((alert) => (
                          <tr key={alert.id}>
                            <td>
                              <strong>
                                {alertLabels[alert.id] ?? alert.id}
                              </strong>
                              <small className="operations-subtext">
                                {alert.severity === "ACTION_REQUIRED"
                                  ? "需要处理"
                                  : "提醒"}{" "}
                                · 发生 {alert.occurrences} 次
                              </small>
                            </td>
                            <td>
                              {alert.state === "FIRING" ? "触发中" : "已恢复"}
                            </td>
                            <td>{number(alert.value)}</td>
                            <td>{time(alert.observedAt)}</td>
                            <td>
                              {alert.acknowledged ? (
                                `已确认 · ${alert.acknowledgedBy ?? ""}`
                              ) : (
                                <button
                                  className="button"
                                  disabled={blocked}
                                  onClick={() =>
                                    mutate(
                                      `/alerts/${encodeURIComponent(alert.id)}/ack`,
                                      {},
                                      `"o${alert.revision}"`,
                                    )
                                  }
                                >
                                  确认已知晓
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <p>
                  确认表示已知晓告警，不会将异常任务标记为成功，也不会重放未知调用。
                </p>
              </section>
            )}
            {data.capabilities.admin && plan && (
              <section className="studio-section operations-section">
                <h3>清理前核对</h3>
                <p>
                  本批扫描 {number(plan.scannedCount)} 个任务，涉及{" "}
                  {plan.candidates.length} 个任务、{plan.traceCount}{" "}
                  条诊断记录、{plan.expiredArchives.length} 份到期加密归档、
                  {plan.expiredAudit.length} 条保留审计记录、
                  {plan.expiredInbox.length} 条通知记录；受保护记录{" "}
                  {plan.pinnedCount} 条。计划有效至 {time(plan.expiresAt)}。
                </p>
                {plan.hasMore && (
                  <Notice>
                    还有后续任务批次。本次仅处理以下明确列出的候选。
                    <button
                      className="button"
                      disabled={blocked || !plan.nextCursor}
                      onClick={() => preview(plan.nextCursor)}
                    >
                      预览下一批
                    </button>
                    切换批次只生成新预览，不执行当前计划。
                  </Notice>
                )}
                {!plan.archiveAvailable && (
                  <Notice tone="warning">
                    归档密钥尚未配置。含正文归档的计划不能执行。
                  </Notice>
                )}
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>任务</th>
                        <th>动作</th>
                        <th>终态时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {plan.candidates.map((candidate) => (
                        <tr key={candidate.runId}>
                          <td>
                            <code>{candidate.runId}</code>
                          </td>
                          <td>
                            <ul className="operations-actions">
                              {candidate.action === "ARCHIVE_PAYLOAD" && (
                                <li>
                                  加密归档并清理在线正文；归档再保留{" "}
                                  {plan.policy.archiveDays} 天
                                </li>
                              )}
                              {candidate.pruneAudit && (
                                <li>清理该任务到期审计与执行事件</li>
                              )}
                              {candidate.traceCount > 0 && (
                                <li>
                                  清理 {candidate.traceCount} 条到期诊断记录
                                </li>
                              )}
                              {![
                                "ARCHIVE_PAYLOAD",
                                "AUDIT_ONLY",
                                "TRACE_ONLY",
                              ].includes(candidate.action) && (
                                <li>无法识别的动作，计划不可执行</li>
                              )}
                            </ul>
                          </td>
                          <td>{time(candidate.terminalAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <ReviewedIds
                  title="删除到期加密归档"
                  ids={plan.expiredArchives}
                />
                <ReviewedIds
                  title="清理到期保留审计记录"
                  ids={plan.expiredAudit}
                />
                <ReviewedIds title="清理到期通知记录" ids={plan.expiredInbox} />
                <label className="operations-confirm">
                  <input
                    type="checkbox"
                    checked={reviewed}
                    disabled={blocked}
                    onChange={(e) => setReviewed(e.target.checked)}
                  />
                  我已核对上述全部动作与记录标识，理解在线正文将不可读取，到期加密归档与清理记录将删除。
                </label>
                <button
                  className="button danger-outline"
                  disabled={
                    blocked ||
                    !reviewed ||
                    Date.now() >= plan.expiresAt ||
                    plan.candidates.some(
                      (c) =>
                        ![
                          "ARCHIVE_PAYLOAD",
                          "AUDIT_ONLY",
                          "TRACE_ONLY",
                        ].includes(c.action),
                    ) ||
                    (!plan.archiveAvailable &&
                      plan.candidates.some(
                        (c) => c.action === "ARCHIVE_PAYLOAD",
                      ))
                  }
                  onClick={() =>
                    mutate("/retention/apply", {
                      planId: plan.planId,
                      digest: plan.digest,
                    })
                  }
                >
                  执行已核对的计划
                </button>
                <p>
                  提交时重新核验任务状态与版本；候选变化时整批拒绝并重新预览。必要防重摘要继续保留。此操作不删除备份文件。
                </p>
              </section>
            )}
            {data.capabilities.admin && data.execution && (
              <ExecutionView value={data.execution} />
            )}
          </>
        )
      )}
    </>
  );
}

function Metric({
  title,
  value,
  detail,
  icon,
}: {
  title: string;
  value: string;
  detail: string;
  icon: React.ReactNode;
}) {
  return (
    <section className="operations-metric">
      <div>
        {icon}
        <span>{title}</span>
      </div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </section>
  );
}

function ReviewedIds({ title, ids }: { title: string; ids: string[] }) {
  if (ids.length === 0) return null;
  return (
    <details className="operations-reviewed-ids">
      <summary>
        {title} · {ids.length} 条（查看完整标识）
      </summary>
      <ul>
        {ids.map((id) => (
          <li key={id}>
            <code>{id}</code>
          </li>
        ))}
      </ul>
    </details>
  );
}

function ExecutionView({ value }: { value: Execution }) {
  const workers = value.workers ?? [];
  const pools = value.pools ?? [];
  const budgets = value.budgets ?? [];
  const unknown = value.unknownPermits ?? [];
  const now = value.databaseTime ?? Date.now();
  const applications = [
    ...new Set(
      budgets
        .filter((item) => item.scope.startsWith("application:"))
        .map((item) => item.scope),
    ),
  ];
  return (
    <>
      <section className="studio-section operations-section">
        <h3>执行节点</h3>
        {workers.length === 0 ? (
          <Empty title="尚无已登记执行节点" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>节点</th>
                  <th>状态</th>
                  <th>进行中任务</th>
                  <th>最近心跳</th>
                  <th>配置</th>
                </tr>
              </thead>
              <tbody>
                {workers.map((worker) => (
                  <tr key={worker.workerId}>
                    <td>
                      <code>{worker.workerId}</code>
                    </td>
                    <td>
                      {workerLabels[worker.state] ?? worker.state}
                      {worker.state !== "STOPPED" &&
                        now - worker.heartbeatAt > 45000 && (
                          <small className="operations-subtext">
                            心跳已过期
                          </small>
                        )}
                    </td>
                    <td>{number(worker.activeCount)}</td>
                    <td>{time(worker.heartbeatAt)}</td>
                    <td>
                      {worker.fingerprintMatches
                        ? "与当前配置一致"
                        : "配置不一致"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p>节点心跳用于判断本地进程是否响应，不能证明远端调用已停止。</p>
      </section>
      <section className="studio-section operations-section">
        <h3>模型与工具容量</h3>
        <p>
          平台模型并发上限：{number(value.modelConcurrency)}
          。下表统计当前项目的占用与请求；实际限额在多个 worker 间共享。
        </p>
        {pools.length === 0 ? (
          <Empty title="当前项目尚无容量池调用记录" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>容量池</th>
                  <th>当前项目占用</th>
                  <th>其中结果未知</th>
                  <th>本分钟项目请求</th>
                  <th>集群共享上限</th>
                  <th>供应商恢复状态</th>
                </tr>
              </thead>
              <tbody>
                {pools.map((pool) => (
                  <tr key={pool.poolId}>
                    <td>
                      <code>{pool.poolId}</code>
                    </td>
                    <td>{number(pool.occupied)}</td>
                    <td>{number(pool.unknown)}</td>
                    <td>{number(pool.requestsInMinute)}</td>
                    <td>
                      并发 {number(pool.concurrencyLimit)} · 每分钟{" "}
                      {number(pool.requestsPerMinuteLimit)} 次
                    </td>
                    <td>
                      {pool.openUntil > now
                        ? `暂缓派发至 ${time(pool.openUntil)}`
                        : "未处于熔断等待"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p>
          占用包含已预留、已派发和未知调用。未知调用在核验前保守占用，取消本地任务不会证明远端已经停止。
        </p>
      </section>
      <section className="studio-section operations-section">
        <h3>日月额度账本</h3>
        {value.calendarBudgetConfigured === false && (
          <Notice>
            当前项目尚未配置日月 Token
            额度，不能据此认定已有费用硬上限。正式运行预算仍需在启用前落实。
          </Notice>
        )}
        {budgets.length === 0 ? (
          <Empty title="当前项目尚无日月额度记录" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>范围</th>
                  <th>周期</th>
                  <th>预留 Token</th>
                  <th>已计入 Token</th>
                  <th>周期上限</th>
                </tr>
              </thead>
              <tbody>
                {budgets.map((budget) => (
                  <tr key={`${budget.scope}/${budget.period}`}>
                    <td>
                      {budget.scope.startsWith("project:")
                        ? "当前项目"
                        : budget.scopeLabel
                          ? `应用 ${budget.scopeLabel}`
                          : `应用额度 ${applications.indexOf(budget.scope) + 1}`}
                    </td>
                    <td>{budget.period}</td>
                    <td>{number(budget.reservedTokens)}</td>
                    <td>{number(budget.chargedTokens)}</td>
                    <td>
                      {budget.limitTokens === 0
                        ? "未启用"
                        : number(budget.limitTokens)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p>
          日与月是同一用量的不同周期视图，不应相加；项目与应用额度同理。此处是平台保守账本，不等于供应商最终账单。
        </p>
      </section>
      <section className="studio-section operations-section">
        <h3>待核验的远端调用</h3>
        {unknown.length === 0 ? (
          <Empty title="当前没有结果未知的供应商调用" />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>关联任务</th>
                  <th>容量池</th>
                  <th>调用占用标识</th>
                  <th>最近变化</th>
                </tr>
              </thead>
              <tbody>
                {unknown.map((permit) => (
                  <tr key={permit.permitId}>
                    <td>
                      <a href={`#/runs/${encodeURIComponent(permit.runId)}`}>
                        {permit.runId}
                      </a>
                    </td>
                    <td>
                      <code>{permit.poolId}</code>
                    </td>
                    <td>
                      <code>{permit.permitId}</code>
                    </td>
                    <td>{time(permit.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p>
          需依据供应商回执和外部结果核验；本页不会自动重试、释放占用或把任务改为成功。
        </p>
      </section>
    </>
  );
}
