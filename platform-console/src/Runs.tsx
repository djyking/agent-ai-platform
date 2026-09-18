import { useEffect, useState } from "react";
import {
  Activity,
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronRight,
  Clock3,
  ExternalLink,
  FileCheck2,
  GitBranch,
  History,
  LockKeyhole,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  Square,
  Terminal,
  X,
} from "lucide-react";
import { ApiError, commandKey, projectPath, request } from "./api";
import { navigate, useRemote } from "./hooks";
import type {
  Approval,
  Event,
  Json,
  Page,
  Principal,
  ReleaseRef,
  Run,
  RunSummary,
  Spec,
  UnknownInvocation,
} from "./types";
import {
  Badge,
  CopyValue,
  Empty,
  ErrorNotice,
  Field,
  formatDate,
  JsonView,
  labels,
  Loading,
  Modal,
  Notice,
  PageHeading,
  short,
  Success,
} from "./ui";
import { JsonField } from "./SpecEditor";

export const statuses = [
  "QUEUED",
  "RUNNING",
  "PAUSED",
  "WAITING_APPROVAL",
  "WAITING_INPUT",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "EXPIRED",
  "BUDGET_EXCEEDED",
  "NEEDS_ATTENTION",
];
const terminal = new Set([
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "EXPIRED",
  "BUDGET_EXCEEDED",
]);
const can = (principal: Principal, permission: string) =>
  principal.permissions.includes(permission) ||
  principal.permissions.includes("*");
export function RunList({
  project,
  principal,
  inbox = false,
}: {
  project: string;
  principal: Principal;
  inbox?: boolean;
}) {
  const [status, setStatus] = useState(inbox ? "WAITING_APPROVAL" : "");
  const [cursor, setCursor] = useState("");
  const [previous, setPrevious] = useState<string[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const query = `?limit=20${status ? `&status=${status}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
  const remote = useRemote<Page<RunSummary>>(
    `${projectPath(project)}/runs${query}`,
  );
  return (
    <>
      <PageHeading
        eyebrow={inbox ? "HUMAN OVERSIGHT" : "EXECUTION LEDGER"}
        title={inbox ? "审批与对账" : "运行记录"}
        description={
          inbox
            ? "核对精确的待执行内容，分别处理工具审批、人工输入与结果不确定的调用。"
            : "从发布版本到每一次调用，运行状态来自同一份执行账本。"
        }
        actions={
          <>
            <button className="button" onClick={remote.reload}>
              <RefreshCw size={16} />
              刷新
            </button>
            {can(principal, "runs:create") && (
              <button
                className="button primary"
                onClick={() => setShowCreate(true)}
              >
                <Plus size={17} />
                创建运行
              </button>
            )}
          </>
        }
      />
      {inbox && (
        <div className="inbox-tabs">
          {[
            ["WAITING_APPROVAL", "工具审批", ShieldAlert],
            ["WAITING_INPUT", "人工输入", FileCheck2],
            ["NEEDS_ATTENTION", "UNKNOWN / 需要处理", Activity],
          ].map(([value, label, Icon]) => {
            const Symbol = Icon as typeof Activity;
            return (
              <button
                key={String(value)}
                className={status === value ? "active" : ""}
                onClick={() => {
                  setStatus(String(value));
                  setCursor("");
                  setPrevious([]);
                }}
              >
                <Symbol size={19} />
                <span>{String(label)}</span>
                <ChevronRight size={16} />
              </button>
            );
          })}
        </div>
      )}
      <div className="panel">
        <div className="panel-heading">
          <div className="section-title">
            <h3>{inbox ? labels[status] : "执行账本"}</h3>
            <span className="count-label">
              {remote.data?.items.length ?? 0} 条 · 当前页
            </span>
          </div>
          {!inbox && (
            <select
              className="compact-select"
              aria-label="运行状态筛选"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setCursor("");
                setPrevious([]);
              }}
            >
              <option value="">全部状态</option>
              {statuses.map((value) => (
                <option key={value} value={value}>
                  {labels[value] ?? value}
                </option>
              ))}
            </select>
          )}
        </div>
        {remote.loading ? (
          <Loading />
        ) : remote.error ? (
          <ErrorNotice error={remote.error} onRetry={remote.reload} />
        ) : remote.data?.items.length ? (
          <RunTable items={remote.data.items} />
        ) : (
          <Empty
            title={inbox ? "当前没有待处理记录" : "没有符合条件的运行"}
            description={
              inbox
                ? "这里仅显示当前身份可见的运行。指定审批人的授权仍由服务端核验。"
                : "选择一个已发布的 Agent 版本，即可创建新的异步运行。"
            }
          />
        )}
        <div className="pagination">
          <span>按创建时间倒序 · 使用项目范围分页游标</span>
          <div>
            <button
              className="button small"
              disabled={!previous.length || remote.loading}
              onClick={() => {
                setCursor(previous.at(-1) ?? "");
                setPrevious(previous.slice(0, -1));
              }}
            >
              上一页
            </button>
            <button
              className="button small"
              disabled={!remote.data?.nextCursor || remote.loading}
              onClick={() => {
                setPrevious([...previous, cursor]);
                setCursor(remote.data!.nextCursor!);
              }}
            >
              下一页
            </button>
          </div>
        </div>
      </div>
      {showCreate && (
        <CreateRun project={project} onClose={() => setShowCreate(false)} />
      )}
    </>
  );
}
export function RunTable({ items }: { items: RunSummary[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>运行 / Agent</th>
            <th>状态</th>
            <th>发布版本</th>
            <th>创建时间</th>
            <th>修订</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.map((run) => (
            <tr key={run.id}>
              <td>
                <a className="resource-link" href={`#/runs/${run.id}`}>
                  <span className="table-icon">
                    <Activity size={18} />
                  </span>
                  <span>
                    <strong>{run.releaseRef.agentId}</strong>
                    <small>{short(run.id, 24)}</small>
                  </span>
                </a>
              </td>
              <td>
                <Badge value={run.status} />
              </td>
              <td>
                <code title={run.releaseRef.releaseId}>
                  {short(run.releaseRef.releaseId)}
                </code>
              </td>
              <td>{formatDate(run.createdAt)}</td>
              <td>
                <code>r{run.revision}</code>
              </td>
              <td>
                <a
                  href={`#/runs/${run.id}`}
                  className="icon-button"
                  aria-label={`查看运行 ${run.id}`}
                >
                  <ChevronRight size={17} />
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

type ReleaseOption = {
  agentId?: string;
  name?: string;
  agentName?: string;
  version?: number;
  releaseRef: ReleaseRef;
  inputSchema?: Spec;
  disabled?: boolean;
};
export function CreateRun({
  project,
  onClose,
}: {
  project: string;
  onClose: () => void;
}) {
  const releases = useRemote<Page<ReleaseOption>>(
    `${projectPath(project)}/catalog/releases`,
  );
  const [selected, setSelected] = useState("");
  const [inputs, setInputs] = useState<Spec>({});
  const [inputValid, setInputValid] = useState(true);
  const [reference, setReference] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [review, setReview] = useState(false);
  const release = releases.data?.items.find(
    (item) => item.releaseRef.releaseId === selected,
  );
  const uncertain = error instanceof ApiError && error.uncertain;
  async function create() {
    if (!release) return;
    setBusy(true);
    setError(undefined);
    try {
      const body = {
        releaseRef: release.releaseRef,
        inputs,
        ...(reference.trim() ? { clientReference: reference.trim() } : {}),
      };
      const reply = await request<{ run: { id: string } }>(
        `${projectPath(project)}/runs`,
        { method: "POST", key: commandKey(), body },
      );
      onClose();
      navigate(`/runs/${reply.data.run.id}`);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="创建运行"
      description="选择已发布的完整契约。新运行将固定版本和预算，执行身份来自当前会话。"
      onClose={onClose}
      busy={busy}
    >
      {releases.loading ? (
        <Loading />
      ) : releases.error ? (
        <ErrorNotice error={releases.error} onRetry={releases.reload} />
      ) : !releases.data?.items.length ? (
        <Empty
          title="暂无可用 Agent 发布"
          description="先配置并发布 Agent，确保资源及依赖处于可用状态。"
        />
      ) : (
        <>
          <Field label="Agent 发布版本" required>
            <select
              value={selected}
              onChange={(event) => {
                setSelected(event.target.value);
                setInputs({});
                setInputValid(true);
                setReview(false);
                setError(undefined);
              }}
            >
              <option value="">选择已发布版本</option>
              {releases.data.items
                .filter((item) => !item.disabled)
                .map((item) => (
                  <option
                    key={item.releaseRef.releaseId}
                    value={item.releaseRef.releaseId}
                  >
                    {item.name ?? item.agentName ?? item.releaseRef.agentId} ·{" "}
                    {item.version
                      ? `v${item.version}`
                      : short(item.releaseRef.releaseId)}
                  </option>
                ))}
            </select>
          </Field>
          {release && (
            <>
              <div className="compact-contract">
                <span>固定摘要</span>
                <CopyValue value={release.releaseRef.digest} />
              </div>
              <JsonField
                label="运行输入"
                objectOnly
                onValidity={setInputValid}
                value={inputs}
                onChange={(next) => {
                  if (
                    next &&
                    typeof next === "object" &&
                    !Array.isArray(next)
                  ) {
                    setInputs(next);
                    setReview(false);
                  }
                }}
                hint="仅填写发布 Schema 中声明的业务变量。"
                rows={6}
              />
              {release.inputSchema && (
                <details className="details">
                  <summary>查看发布的输入契约</summary>
                  <JsonView value={release.inputSchema} />
                </details>
              )}
              <Field label="业务关联标识（可选）">
                <input
                  maxLength={128}
                  value={reference}
                  onChange={(event) => {
                    setReference(event.target.value);
                    setReview(false);
                  }}
                  placeholder="例如 sandbox-check-001"
                />
              </Field>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={review}
                  onChange={(event) => setReview(event.target.checked)}
                />
                已核对版本、业务输入和当前项目
              </label>
            </>
          )}
        </>
      )}
      {Boolean(error) && <ErrorNotice error={error} />}
      {uncertain && (
        <Notice>
          关闭后在运行列表按业务关联标识核对。此窗口不会自动重发创建请求。
        </Notice>
      )}
      <div className="modal-actions">
        <button className="button" disabled={busy} onClick={onClose}>
          取消
        </button>
        <button
          className="button primary"
          disabled={busy || !release || !review || !inputValid || uncertain}
          onClick={create}
        >
          <Play size={16} />
          {busy ? "提交中…" : "创建并执行"}
        </button>
      </div>
    </Modal>
  );
}

export function RunDetail({
  project,
  id,
  principal,
}: {
  project: string;
  id: string;
  principal: Principal;
}) {
  const base = `${projectPath(project)}/runs/${encodeURIComponent(id)}`;
  const run = useRemote<Run>(base);
  const [eventCursor, setEventCursor] = useState("");
  const events = useRemote<Page<Event>>(
    `${base}/events?limit=50${eventCursor ? `&cursor=${encodeURIComponent(eventCursor)}` : ""}`,
  );
  const [tab, setTab] = useState("output");
  const [control, setControl] = useState<"pause" | "cancel" | "resume">();
  const [showApproval, setShowApproval] = useState(false);
  const [showReconcile, setShowReconcile] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [message, setMessage] = useState("");
  const refresh = () => {
    run.reload();
    events.reload();
    setError(undefined);
  };
  async function executeControl() {
    if (!control || !run.etag) return;
    setBusy(true);
    setError(undefined);
    try {
      await request(`${base}/${control}`, {
        method: "POST",
        body: {},
        etag: run.etag,
        key: commandKey(),
      });
      setMessage("控制请求已受理，请刷新查看最终执行状态。");
      setControl(undefined);
      run.reload();
      events.reload();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  if (run.loading) return <Loading />;
  if (run.error) return <ErrorNotice error={run.error} onRetry={run.reload} />;
  if (!run.data) return null;
  const data = run.data;
  const pending = ["WAITING_APPROVAL", "WAITING_INPUT"].includes(data.status);
  const unknown = data.attention?.code === "OUTCOME_UNKNOWN";
  return (
    <>
      <button className="back-link" onClick={() => navigate("/runs")}>
        <ArrowLeft size={15} />
        返回运行记录
      </button>
      <PageHeading
        eyebrow="RUN DETAIL"
        title={data.releaseRef.agentId}
        description={`运行 ${id}`}
        actions={
          <>
            <Badge value={data.status} />
            <button className="button" onClick={refresh}>
              <RefreshCw size={16} />
              刷新
            </button>
          </>
        }
      />
      {message && <Success>{message}</Success>}
      {Boolean(error) && !control && (
        <ErrorNotice error={error} onRetry={refresh} />
      )}
      {pending && (
        <div className="action-banner warning">
          <ShieldAlert size={25} />
          <div>
            <strong>
              {data.status === "WAITING_INPUT"
                ? "此运行需要人工输入"
                : "此运行正在等待精确审批"}
            </strong>
            <p>先查看待执行内容与摘要，再由指定审批人作出决定。</p>
          </div>
          <button
            className="button"
            disabled={!can(principal, "approvals:read")}
            onClick={() => setShowApproval(true)}
          >
            查看待处理内容 <ArrowRight size={15} />
          </button>
        </div>
      )}
      {unknown && (
        <div className="action-banner warning">
          <Activity size={25} />
          <div>
            <strong>调用结果不确定 · UNKNOWN</strong>
            <p>远端可能已产生副作用。请核验独立证据，保持原调用不重放。</p>
          </div>
          <button
            className="button"
            disabled={!can(principal, "runs:reconcile:read")}
            onClick={() => setShowReconcile(true)}
          >
            查看并对账 <ArrowRight size={15} />
          </button>
        </div>
      )}
      <div className="run-metrics">
        {[
          ["执行步数", data.usage.steps, data.limits.maxSteps],
          ["工具调用", data.usage.toolCalls, data.limits.maxToolCalls],
          ["模型调用", data.usage.modelCalls, data.limits.maxModelCalls],
          ["Token 账本", data.usage.chargedTokens, data.limits.maxTokens],
        ].map(([label, used, budget]) => (
          <div className="panel metric-card" key={String(label)}>
            <span>{label}</span>
            <strong>
              {Number(used).toLocaleString()}{" "}
              <small>/ {Number(budget).toLocaleString()}</small>
            </strong>
            <div className="meter">
              <i
                style={{
                  width: `${Math.min(100, (Number(used) / Math.max(1, Number(budget))) * 100)}%`,
                }}
              />
            </div>
          </div>
        ))}
      </div>
      <div className="run-layout">
        <div className="panel">
          <div className="tabs">
            {[
              ["output", "运行结果"],
              ["events", "审计事件"],
              ["trace", "调用轨迹"],
              ["snapshot", "执行契约"],
            ].map(([value, label]) => (
              <button
                className={tab === value ? "active" : ""}
                key={value}
                onClick={() => setTab(value)}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="run-content">
            {tab === "output" &&
              (data.output?.visibility === "AVAILABLE" ? (
                <>
                  <div className="section-title">
                    <h3>授权可见的输出</h3>
                    <span className="tag">实时权限核验</span>
                  </div>
                  <OutputView value={data.output.value} />
                </>
              ) : (
                <Empty
                  title={
                    terminal.has(data.status)
                      ? "当前输出不可见"
                      : "执行结果尚未就绪"
                  }
                  description={
                    terminal.has(data.status)
                      ? "发布策略、当前知识权限或原委托有效期决定结果可见性。平台不会返回不可授权的历史内容。"
                      : "刷新运行状态查看结果。暂停、审批或人工输入期间不会生成最终输出。"
                  }
                />
              ))}
            {tab === "events" && (
              <>
                <div className="section-title">
                  <h3>事件时间线</h3>
                  <button className="text-button" onClick={events.reload}>
                    <RefreshCw size={14} />
                    刷新事件
                  </button>
                </div>
                {events.loading ? (
                  <Loading />
                ) : events.error ? (
                  <ErrorNotice error={events.error} onRetry={events.reload} />
                ) : events.data?.items.length ? (
                  <ol className="event-timeline">
                    {events.data.items.map((event) => (
                      <li key={event.sequence}>
                        <span className="event-dot" />
                        <div className="event-top">
                          <strong>{event.type}</strong>
                          <span>{formatDate(event.at)}</span>
                        </div>
                        <span className="event-sequence">
                          序号 {event.sequence}
                        </span>
                        {event.invocationRef && (
                          <div className="event-ref">
                            <GitBranch size={13} />
                            <CopyValue value={event.invocationRef} />
                          </div>
                        )}
                        {event.traceId && (
                          <div className="event-ref">
                            <span>Trace</span>
                            <CopyValue value={event.traceId} />
                          </div>
                        )}
                      </li>
                    ))}
                  </ol>
                ) : (
                  <Empty
                    title="此页没有事件"
                    description="事件只包含当前身份可读取的安全投影。"
                  />
                )}
                <div className="pagination">
                  <button
                    className="text-button"
                    onClick={() => setEventCursor("")}
                    disabled={!eventCursor}
                  >
                    回到首批
                  </button>
                  <button
                    className="button small"
                    disabled={!events.data?.hasMore}
                    onClick={() =>
                      setEventCursor(events.data?.nextCursor ?? "")
                    }
                  >
                    读取后续事件
                  </button>
                </div>
              </>
            )}
            {tab === "trace" && <TraceView base={base} />}
            {tab === "snapshot" && (
              <>
                <h3>不可变发布引用</h3>
                <JsonView value={data.releaseRef} />
                <h3>本次运行有效预算</h3>
                <JsonView value={data.limits} />
                <Notice>
                  此页仅显示执行 API
                  返回的公开契约，不暴露原始检查点、密钥或未授权输出。Token
                  账本含保守预留，不等于供应商最终账单。
                </Notice>
              </>
            )}
          </div>
        </div>
        <aside>
          <div className="panel side-card">
            <h3>运行信息</h3>
            <dl className="metadata-list">
              <dt>项目</dt>
              <dd>{project}</dd>
              <dt>创建时间</dt>
              <dd>{formatDate(data.createdAt)}</dd>
              <dt>截止时间</dt>
              <dd>{formatDate(data.deadline)}</dd>
              <dt>当前修订</dt>
              <dd>r{data.revision}</dd>
              <dt>业务关联</dt>
              <dd>{data.clientReference ?? "—"}</dd>
              {data.reasonCode && (
                <>
                  <dt>状态原因</dt>
                  <dd>
                    <code>{data.reasonCode}</code>
                  </dd>
                </>
              )}
            </dl>
            <div className="contract-hash">
              <span>发布摘要</span>
              <CopyValue
                value={data.releaseRef.digest}
                label={short(data.releaseRef.digest, 20)}
              />
            </div>
          </div>
          <div className="panel side-card">
            <h3>执行控制</h3>
            {data.controls.pauseRequested && (
              <Notice>暂停请求已记录，等待安全边界生效。</Notice>
            )}
            {data.controls.cancelRequested && (
              <Notice tone="warning">
                取消已请求。未决的远端结果仍需核验。
              </Notice>
            )}
            <button
              className="button full"
              disabled={
                terminal.has(data.status) ||
                data.status === "PAUSED" ||
                data.controls.pauseRequested ||
                !can(principal, "runs:control")
              }
              onClick={() => setControl("pause")}
            >
              <Pause size={16} />
              请求暂停
            </button>
            <button
              className="button full"
              disabled={
                terminal.has(data.status) ||
                unknown ||
                (!data.controls.pauseRequested && data.status !== "PAUSED") ||
                !can(principal, "runs:control")
              }
              onClick={() => setControl("resume")}
            >
              <Play size={16} />
              恢复执行
            </button>
            <button
              className="button danger-outline full"
              disabled={
                terminal.has(data.status) ||
                data.controls.cancelRequested ||
                !can(principal, "runs:control")
              }
              onClick={() => setControl("cancel")}
            >
              <Square size={15} />
              请求取消
            </button>
          </div>
        </aside>
      </div>
      {control && (
        <Modal
          title={
            {
              pause: "请求暂停运行",
              cancel: "请求取消运行",
              resume: "恢复运行",
            }[control]
          }
          description="操作会绑定当前读取的运行修订。已变化的状态需要重新读取后确认。"
          busy={busy}
          onClose={() => setControl(undefined)}
        >
          <div className="review-summary">
            <div>
              <span>运行</span>
              <code>{id}</code>
            </div>
            <div>
              <span>当前修订</span>
              <strong>r{data.revision}</strong>
            </div>
            <div>
              <span>当前状态</span>
              <Badge value={data.status} />
            </div>
          </div>
          <Notice tone={control === "cancel" ? "warning" : "info"}>
            {control === "cancel"
              ? "取消不会撤回已发送的远端副作用。结果不确定的调用仍需独立对账。"
              : control === "pause"
                ? "暂停在安全执行边界生效；正在等待的调用结果仍会进入账本。"
                : "恢复沿用此运行原有版本、预算与审批状态，不会重新解释为新运行。"}
          </Notice>
          {Boolean(error) && <ErrorNotice error={error} />}
          <div className="modal-actions">
            <button
              className="button"
              disabled={busy}
              onClick={() => setControl(undefined)}
            >
              返回
            </button>
            <button
              className={`button ${control === "cancel" ? "danger" : "primary"}`}
              disabled={busy || (error instanceof ApiError && error.uncertain)}
              onClick={executeControl}
            >
              确认
              {control === "pause"
                ? "暂停"
                : control === "cancel"
                  ? "取消"
                  : "恢复"}
            </button>
          </div>
        </Modal>
      )}
      {showApproval && (
        <ApprovalDialog
          project={project}
          id={id}
          principal={principal}
          onClose={() => setShowApproval(false)}
          onDone={() => {
            setShowApproval(false);
            refresh();
          }}
        />
      )}
      {showReconcile && (
        <ReconcileDialog
          project={project}
          id={id}
          principal={principal}
          onClose={() => setShowReconcile(false)}
          onDone={() => {
            setShowReconcile(false);
            refresh();
          }}
        />
      )}
    </>
  );
}

function OutputView({ value }: { value?: Json }) {
  if (
    value &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    typeof value.evidence === "string"
  )
    return (
      <div className="evidence-output">
        <div className="evidence-text">{value.evidence}</div>
        {Array.isArray(value.citations) && (
          <>
            <h4>引用来源 · {value.citations.length}</h4>
            {value.citations.map((citation, index) => {
              const item = citation as Spec;
              return (
                <div className="citation" key={index}>
                  <span>{String(item.sourceId ?? index + 1)}</span>
                  <div>
                    <strong>{String(item.documentName ?? "知识文档")}</strong>
                    <small>
                      文档 {String(item.documentId)} · 分块{" "}
                      {String(item.chunkId)} · v{String(item.version ?? "—")}
                    </small>
                  </div>
                </div>
              );
            })}
          </>
        )}
      </div>
    );
  return <JsonView value={value} />;
}

export function approvalBody(
  approval: Approval,
  decision: "APPROVE" | "REJECT",
  reason: string,
  input: string,
) {
  return {
    digest: approval.digest,
    decision,
    ...(reason ? { reason } : {}),
    ...(decision === "APPROVE" && approval.kind === "HUMAN_INPUT"
      ? { input }
      : {}),
  };
}
function ApprovalDialog({
  project,
  id,
  principal,
  onClose,
  onDone,
}: {
  project: string;
  id: string;
  principal: Principal;
  onClose: () => void;
  onDone: () => void;
}) {
  const base = `${projectPath(project)}/runs/${id}`;
  const remote = useRemote<Approval>(`${base}/approval`);
  const [reason, setReason] = useState("");
  const [input, setInput] = useState("");
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  async function decide(decision: "APPROVE" | "REJECT") {
    if (!remote.data) return;
    setBusy(true);
    setError(undefined);
    try {
      await request(`${base}/approvals/${remote.data.id}/decision`, {
        method: "POST",
        etag: remote.etag,
        key: commandKey(),
        body: approvalBody(remote.data, decision, reason, input),
      });
      onDone();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  const approval = remote.data;
  const available =
    approval?.status === "PENDING" &&
    Date.parse(approval.expiresAt) > Date.now() &&
    can(principal, "approvals:decide");
  return (
    <Modal
      title="核对审批内容"
      description="决定只对当前摘要和修订有效。审批理由与提供给工作流的人工输入分别保存。"
      onClose={onClose}
      busy={busy}
    >
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        approval && (
          <>
            <div className="review-summary">
              <div>
                <span>类型</span>
                <strong>
                  {approval.kind === "HUMAN_INPUT"
                    ? "人工输入"
                    : "工具执行审批"}
                </strong>
              </div>
              <div>
                <span>修订 / 状态</span>
                <strong>
                  r{approval.runRevision} ·{" "}
                  {labels[approval.status] ?? approval.status}
                </strong>
              </div>
              <div>
                <span>有效期至</span>
                <strong>{formatDate(approval.expiresAt)}</strong>
              </div>
              <div>
                <span>审批摘要</span>
                <CopyValue value={approval.digest} />
              </div>
            </div>
            <p className="approval-summary">{approval.summary}</p>
            {approval.fields.length > 0 && (
              <div className="review-fields">
                {approval.fields.map((field) => (
                  <div key={field.name}>
                    <strong>{field.name}</strong>
                    <pre className={field.masked ? "masked" : ""}>
                      {field.masked ? "此字段未授权展示" : field.value}
                    </pre>
                  </div>
                ))}
              </div>
            )}
            {!approval.reviewComplete && (
              <Notice tone="warning">
                待执行内容未完整展示。当前身份不能批准，请由具备完整审阅权限的指定审批人处理。
              </Notice>
            )}
            {approval.kind === "HUMAN_INPUT" && (
              <Field
                label="提供给工作流的人工输入"
                required
                hint="原样传给已发布工作流；不是审批理由。"
              >
                <textarea
                  rows={4}
                  value={input}
                  maxLength={10000}
                  onChange={(event) => setInput(event.target.value)}
                />
              </Field>
            )}
            <Field label="审批理由" hint="用于审计记录，不替代人工输入。">
              <textarea
                rows={3}
                maxLength={1000}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={reviewed}
                onChange={(event) => setReviewed(event.target.checked)}
              />
              已核对以上精确内容、摘要和有效期
            </label>
          </>
        )
      )}
      {Boolean(error) && <ErrorNotice error={error} />}
      <div className="modal-actions">
        <button
          className="button danger-outline"
          disabled={busy || !available || !reviewed || !!error}
          onClick={() => decide("REJECT")}
        >
          <X size={16} />
          拒绝
        </button>
        <button
          className="button primary"
          disabled={
            busy ||
            !available ||
            !reviewed ||
            !approval?.reviewComplete ||
            (approval?.kind === "HUMAN_INPUT" && !input) ||
            !!error
          }
          onClick={() => decide("APPROVE")}
        >
          <Check size={16} />
          {approval?.kind === "HUMAN_INPUT" ? "批准并提交输入" : "批准此次执行"}
        </button>
      </div>
      {Boolean(error) && (
        <button
          className="text-button"
          onClick={() => {
            setError(undefined);
            setReviewed(false);
            remote.reload();
          }}
        >
          重新读取审批内容
        </button>
      )}
    </Modal>
  );
}

function ReconcileDialog({
  project,
  id,
  principal,
  onClose,
  onDone,
}: {
  project: string;
  id: string;
  principal: Principal;
  onClose: () => void;
  onDone: () => void;
}) {
  const base = `${projectPath(project)}/runs/${id}`;
  const remote = useRemote<UnknownInvocation>(`${base}/unknown-invocation`);
  const [evidence, setEvidence] = useState("");
  const [reason, setReason] = useState("");
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  async function reconcile() {
    if (!remote.data) return;
    setBusy(true);
    setError(undefined);
    try {
      await request(`${base}/tool-reconciliations`, {
        method: "POST",
        etag: remote.etag,
        key: commandKey(),
        body: {
          invocationRef: remote.data.invocationRef,
          invocationDigest: remote.data.invocationDigest,
          evidenceRef: evidence.trim(),
          reason: reason.trim(),
        },
      });
      onDone();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title="核验 UNKNOWN 调用"
      description="对账仅引用平台已导入并独立验证的证据。此操作不会重放远端调用。"
      busy={busy}
      onClose={onClose}
    >
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        remote.data && (
          <>
            <Notice tone="warning">
              远端结果不确定时，不能通过再次执行来推断是否成功。请先通过业务系统核查真实回执。
            </Notice>
            <div className="review-summary">
              <div>
                <span>调用类型</span>
                <strong>{remote.data.kind}</strong>
              </div>
              <div>
                <span>当前修订</span>
                <strong>r{remote.data.runRevision}</strong>
              </div>
              <div>
                <span>调用关联</span>
                <CopyValue value={remote.data.invocationRef} />
              </div>
              <div>
                <span>精确调用摘要</span>
                <CopyValue value={remote.data.invocationDigest} />
              </div>
            </div>
            {!remote.data.reconcilable && (
              <Notice>
                此调用类型不支持工具证据对账，请交由平台运维处理。
              </Notice>
            )}
            <Field
              label="已验证 evidenceRef"
              required
              hint="填写平台证据记录的标识，不接受 URL、手填成功结果或原始工具响应。"
            >
              <input
                value={evidence}
                maxLength={128}
                onChange={(event) => setEvidence(event.target.value)}
                placeholder="evidence_verified_receipt_001"
              />
            </Field>
            <Field label="核验依据与对账理由" required>
              <textarea
                rows={4}
                value={reason}
                maxLength={1000}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={checked}
                onChange={(event) => setChecked(event.target.checked)}
              />
              已独立核验该运行、调用、目标与证据回执一致
            </label>
          </>
        )
      )}
      {Boolean(error) && <ErrorNotice error={error} />}
      <div className="modal-actions">
        <button className="button" disabled={busy} onClick={onClose}>
          返回
        </button>
        <button
          className="button primary"
          disabled={
            busy ||
            !remote.data?.reconcilable ||
            !can(principal, "runs:reconcile") ||
            !evidence.trim() ||
            !reason.trim() ||
            !checked ||
            !!error
          }
          onClick={reconcile}
        >
          提交已验证证据对账
        </button>
      </div>
      {Boolean(error) && (
        <button
          className="text-button"
          onClick={() => {
            setError(undefined);
            setChecked(false);
            remote.reload();
          }}
        >
          重新读取当前调用
        </button>
      )}
    </Modal>
  );
}

type Trace = {
  sequence: number;
  traceId: string | null;
  invocationId: string | null;
  attemptId: string | null;
  nodeId: string | null;
  operation: string;
  target: string;
  outcome: string;
  startedAt: string;
  durationMs: number;
};
function TraceView({ base }: { base: string }) {
  const [after, setAfter] = useState(0);
  const remote = useRemote<{
    items: Trace[];
    nextAfter: number;
    hasMore: boolean;
    source: string;
    auditSource: string;
  }>(`${base}/trace?after=${after}&limit=100`);
  return (
    <>
      <div className="section-title">
        <h3>持久化调用轨迹</h3>
        <button className="text-button" onClick={remote.reload}>
          <RefreshCw size={14} />
          刷新
        </button>
      </div>
      <Notice>
        轨迹提供节点、调用、尝试与 Trace
        的关联。执行审计以运行事件账本为准；采集故障不会被呈现为成功记录。
      </Notice>
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : !remote.data?.items.length ? (
        <Empty
          title="此页暂无调用轨迹"
          description="新调用完成后刷新。若采集暂不可用，请检查审计事件与实际运行状态。"
        />
      ) : (
        <div className="trace-list">
          {remote.data.items.map((trace) => (
            <div className="trace-card" key={trace.sequence}>
              <div className="section-title">
                <strong>
                  {trace.operation} · {trace.target}
                </strong>
                <Badge value={trace.outcome} />
              </div>
              <div className="trace-meta">
                <span>节点 {trace.nodeId ?? "—"}</span>
                <span>{formatDate(trace.startedAt)}</span>
                <span>{trace.durationMs.toLocaleString()} ms</span>
              </div>
              <dl>
                {[
                  ["Trace", trace.traceId],
                  ["Invocation", trace.invocationId],
                  ["Attempt", trace.attemptId],
                ].map(([label, value]) => (
                  <div key={label}>
                    <dt>{label}</dt>
                    <dd>
                      {value ? (
                        <CopyValue value={value} label={value} />
                      ) : (
                        "未提供"
                      )}
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          ))}
        </div>
      )}
      <div className="pagination">
        <button
          className="text-button"
          disabled={after === 0}
          onClick={() => setAfter(0)}
        >
          回到首批
        </button>
        <button
          className="button small"
          disabled={!remote.data?.hasMore}
          onClick={() => setAfter(remote.data?.nextAfter ?? 0)}
        >
          读取后续轨迹
        </button>
      </div>
    </>
  );
}
