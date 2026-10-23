import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Bot,
  CheckCheck,
  ChevronRight,
  FileCode2,
  GitCompareArrows,
  Layers3,
  MoreHorizontal,
  Plus,
  RefreshCw,
  Rocket,
  Save,
  Search,
  ShieldAlert,
  SlidersHorizontal,
} from "lucide-react";
import { ApiError, commandKey, projectPath, request } from "./api";
import { navigate, useRemote } from "./hooks";
import type {
  Capabilities,
  Json,
  Page,
  Principal,
  Resource,
  ResourceType,
  Spec,
  Version,
} from "./types";
import { RESOURCE_TYPES } from "./types";
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
import { JsonField, RegressionEditor, SpecEditor } from "./SpecEditor";

export const resourcePath = (project: string, type: ResourceType, id: string) =>
  `${projectPath(project)}/catalog/resources/${type}/${encodeURIComponent(id)}`;

export function CatalogList({
  project,
  agents = false,
}: {
  project: string;
  agents?: boolean;
}) {
  const [filter, setFilter] = useState<string>(agents ? "Agent" : "");
  const [query, setQuery] = useState("");
  const remote = useRemote<Page<Resource>>(
    `${projectPath(project)}/catalog/resources${filter ? `?type=${filter}` : ""}`,
  );
  const resources = (remote.data?.items ?? []).filter((resource) =>
    `${resource.name} ${resource.id}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  return (
    <>
      <PageHeading
        eyebrow={agents ? "AGENT WORKSPACE" : "RESOURCE CATALOG"}
        title={agents ? "Agent 工作台" : "资源目录"}
        description={
          agents
            ? "组织执行能力，用经过校验的版本交付每一次运行。"
            : "统一管理模型、提示词、工具与执行策略。发布版本固定，依赖清晰可追溯。"
        }
        actions={
          <button
            className="button primary"
            onClick={() =>
              navigate(
                `/resources/new/${agents ? "Agent" : filter || "Workflow"}`,
              )
            }
          >
            <Plus size={17} />
            {agents ? "创建 Agent" : "创建资源"}
          </button>
        }
      />
      {agents && (
        <div className="workspace-banner">
          <div className="banner-orbit">
            <Bot size={35} />
            <span />
          </div>
          <div>
            <span className="eyebrow">从配置到可靠执行</span>
            <h2>一次发布，固定完整执行契约。</h2>
            <p>组合工作流与策略，用回归验证变更，保留每一次运行的版本依据。</p>
          </div>
          <button
            className="text-button"
            onClick={() => navigate("/resources")}
          >
            浏览资源目录 <ArrowRight size={16} />
          </button>
        </div>
      )}
      <div className="list-toolbar">
        <div className="search-field">
          <Search size={17} />
          <input
            aria-label="搜索资源"
            placeholder="搜索名称或资源标识…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="toolbar-right">
          {!agents && (
            <label className="select-inline">
              <SlidersHorizontal size={15} />
              <select
                aria-label="资源类型筛选"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              >
                <option value="">全部类型</option>
                {RESOURCE_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {labels[type]}
                  </option>
                ))}
              </select>
            </label>
          )}
          <span className="muted">{resources.length} 项资源</span>
          <button
            className="icon-button"
            aria-label="刷新资源目录"
            onClick={remote.reload}
          >
            <RefreshCw size={17} />
          </button>
        </div>
      </div>
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : resources.length === 0 ? (
        <div className="panel">
          <Empty
            title={
              query
                ? "没有匹配的资源"
                : agents
                  ? "从你的第一个 Agent 开始"
                  : "项目尚无配置资源"
            }
            description={
              query
                ? "尝试其他名称或标识。"
                : "创建草稿，完成配置与回归校验后发布。此处仅显示当前项目的真实资源。"
            }
            action={
              !query && (
                <button
                  className="button"
                  onClick={() =>
                    navigate(`/resources/new/${agents ? "Agent" : "Workflow"}`)
                  }
                >
                  <Plus size={16} />
                  创建草稿
                </button>
              )
            }
          />
        </div>
      ) : agents ? (
        <div className="agent-grid">
          {resources.map((resource) => (
            <button
              key={resource.id}
              className="agent-card"
              onClick={() =>
                navigate(`/resources/${resource.type}/${resource.id}`)
              }
            >
              <div className="agent-card-top">
                <span className="agent-icon">
                  <Bot size={25} />
                </span>
                <Badge
                  value={resource.disabled ? "DISABLED" : resource.status}
                />
              </div>
              <h3>{resource.name}</h3>
              <p className="resource-id">{resource.id}</p>
              <div className="card-meta">
                <span>
                  <Layers3 size={14} />
                  {resource.versions.length} 个发布版本
                </span>
                <span>草稿 r{resource.revision}</span>
              </div>
              <div className="card-bottom">
                <span>
                  {resource.defaultVersion
                    ? `默认版本 v${resource.defaultVersion}`
                    : "尚未指定默认版本"}
                </span>
                <ArrowUpIcon />
              </div>
            </button>
          ))}
        </div>
      ) : (
        <div className="panel table-panel">
          <table>
            <thead>
              <tr>
                <th>资源</th>
                <th>类型</th>
                <th>当前状态</th>
                <th>发布版本</th>
                <th>草稿修订</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {resources.map((resource) => (
                <tr key={`${resource.type}/${resource.id}`}>
                  <td>
                    <a
                      className="resource-link"
                      href={`#/resources/${resource.type}/${resource.id}`}
                    >
                      <span className="table-icon">
                        <FileCode2 size={18} />
                      </span>
                      <span>
                        <strong>{resource.name}</strong>
                        <small>{resource.id}</small>
                      </span>
                    </a>
                  </td>
                  <td>{labels[resource.type]}</td>
                  <td>
                    <Badge
                      value={resource.disabled ? "DISABLED" : resource.status}
                    />
                  </td>
                  <td>
                    {resource.versions.length
                      ? `v${Math.max(...resource.versions.map((version) => version.version))}`
                      : "未发布"}
                  </td>
                  <td>
                    <code>r{resource.revision}</code>
                  </td>
                  <td>
                    <a
                      className="icon-button"
                      aria-label={`编辑 ${resource.name}`}
                      href={`#/resources/${resource.type}/${resource.id}`}
                    >
                      <ChevronRight size={17} />
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
function ArrowUpIcon() {
  return <ArrowRight size={16} />;
}

type Action = {
  kind: "publish" | "disable" | "default" | "revoke" | "version-disable";
  title: string;
  body: Record<string, unknown>;
  version?: number;
};
export function ResourceEditor({
  project,
  type,
  id,
  principal,
}: {
  project: string;
  type: ResourceType;
  id?: string;
  principal: Principal;
}) {
  const isNew = !id;
  const base = `${projectPath(project)}/catalog`;
  const remote = useRemote<Resource>(
    id ? resourcePath(project, type, id) : null,
  );
  const catalog = useRemote<Page<Resource>>(`${base}/resources`);
  const capabilities = useRemote<Capabilities>(`${base}/capabilities`);
  const [saved, setSaved] = useState<Resource>();
  const [etag, setEtag] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [identifier, setIdentifier] = useState(id ?? "");
  const [spec, setSpec] = useState<Spec>({});
  const [cases, setCases] = useState<Json[]>([]);
  const [tab, setTab] = useState("configuration");
  const [mode, setMode] = useState("form");
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const [readRequired, setReadRequired] = useState(false);
  const [message, setMessage] = useState("");
  const [action, setAction] = useState<Action>();
  const [compare, setCompare] = useState<[number, number]>();
  const [readVersion, setReadVersion] = useState<number>();
  const form = useRef<HTMLFormElement>(null);
  const templateLoaded = useRef(false);
  const commandResource = useRef(id ?? "");
  useEffect(() => {
    if (remote.data) {
      setSaved(remote.data);
      setEtag(remote.etag ?? `"c${remote.data.revision}"`);
      setName(remote.data.name);
      setIdentifier(remote.data.id);
      setSpec(remote.data.spec);
      setCases(remote.data.regressionCases ?? []);
      setDirty(false);
    }
  }, [remote.data, remote.etag]);
  useEffect(() => {
    if (isNew && capabilities.data && !templateLoaded.current) {
      setSpec(capabilities.data.templates[type] ?? {});
      setName(`新建${labels[type]}`);
      templateLoaded.current = true;
    }
  }, [isNew, capabilities.data, type]);
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", handler);
    return () => removeEventListener("beforeunload", handler);
  }, [dirty]);
  const update = (reply: { data: Resource; etag: string | null }) => {
    setSaved(reply.data);
    setEtag(reply.etag ?? `"c${reply.data.revision}"`);
    setName(reply.data.name);
    setIdentifier(reply.data.id);
    setSpec(reply.data.spec);
    setCases(reply.data.regressionCases ?? []);
    setDirty(false);
    setReadRequired(false);
  };
  const failed = (failure: unknown) => {
    setError(failure);
    if (
      failure instanceof ApiError &&
      (failure.uncertain || [409, 412, 428].includes(failure.status))
    )
      setReadRequired(true);
  };
  async function readCurrent() {
    const target = commandResource.current || id || identifier;
    if (!target || busy) return;
    setBusy(true);
    try {
      const reply = await request<Resource>(
        resourcePath(project, type, target),
      );
      update(reply);
      setError(undefined);
      setAction(undefined);
      setMessage("已读取当前资源，请核对最新配置与操作记录后继续。");
      if (isNew) navigate(`/resources/${type}/${reply.data.id}`);
    } catch (failure) {
      // A failed read does not resolve an uncertain command or a stale view.
      setError(failure);
    } finally {
      setBusy(false);
    }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (busy || readRequired || !form.current?.reportValidity()) return;
    commandResource.current = identifier;
    setBusy(true);
    setError(undefined);
    setMessage("");
    try {
      const reply = await request<Resource>(
        resourcePath(project, type, identifier),
        {
          method: "PUT",
          etag: etag ?? '"c0"',
          key: commandKey(),
          body: { name, spec, regressionCases: cases },
        },
      );
      update(reply);
      setMessage("草稿已保存。请运行校验后再发布。");
      if (isNew) navigate(`/resources/${type}/${identifier}`);
    } catch (failure) {
      failed(failure);
    } finally {
      setBusy(false);
    }
  }
  async function validate() {
    if (busy || readRequired || dirty || !saved) return;
    commandResource.current = saved.id;
    setBusy(true);
    setError(undefined);
    setMessage("");
    try {
      const reply = await request<Resource>(
        `${resourcePath(project, type, saved.id)}/validate`,
        { method: "POST", etag, key: commandKey(), body: {} },
      );
      update(reply);
      setTab("validation");
      setMessage("校验已完成，请查看逐项结果。");
    } catch (failure) {
      failed(failure);
    } finally {
      setBusy(false);
    }
  }
  async function execute() {
    if (busy || readRequired || !action || !saved) return;
    commandResource.current = saved.id;
    setBusy(true);
    setError(undefined);
    setMessage("");
    const target = `${resourcePath(project, type, saved.id)}/${action.kind === "version-disable" ? `versions/${action.version}/disable` : action.kind}`;
    try {
      const reply = await request<Resource>(target, {
        method: "POST",
        etag,
        key: commandKey(),
        body: action.body,
      });
      update(reply);
      setMessage(`${action.title}已完成。`);
      setAction(undefined);
      catalog.reload();
    } catch (failure) {
      failed(failure);
    } finally {
      setBusy(false);
    }
  }
  const permits = (permission: string) =>
    principal.permissions.includes(permission) ||
    principal.permissions.includes("*");
  const readOnly = !permits("catalog:write");
  const canValidate = permits("catalog:validate");
  const canPublish = permits("catalog:publish");
  const blocked = readRequired;
  if (!isNew && remote.loading) return <Loading />;
  if (!isNew && remote.error)
    return <ErrorNotice error={remote.error} onRetry={remote.reload} />;
  const revisions = [...(saved?.versions ?? [])].sort(
    (left, right) => left.version - right.version,
  );
  return (
    <>
      <button
        className="back-link"
        onClick={() => navigate(type === "Agent" ? "/agents" : "/resources")}
      >
        <ArrowLeft size={15} />
        返回{type === "Agent" ? "Agent 工作台" : "资源目录"}
      </button>
      <PageHeading
        eyebrow={`${type.toUpperCase()} / ${isNew ? "NEW DRAFT" : identifier}`}
        title={isNew ? `创建${labels[type]}` : name}
        description={
          isNew
            ? "从草稿开始，校验通过后生成不可覆盖的发布版本。"
            : `草稿修订 r${saved?.revision} · ${revisions.length} 个发布版本${saved?.defaultVersion ? ` · 默认 v${saved.defaultVersion}` : ""}`
        }
        actions={
          <>
            {saved && (
              <Badge value={saved.disabled ? "DISABLED" : saved.status} />
            )}
            {dirty && <span className="unsaved-dot">有未保存修改</span>}
          </>
        }
      />
      {readOnly && (
        <Notice>当前身份可查看配置。写入仍以平台实时授权为准。</Notice>
      )}
      {Boolean(error) && <ErrorNotice error={error} onRetry={readCurrent} />}
      {message && <Success>{message}</Success>}
      <div className="editor-layout">
        <div className="panel editor-main">
          <div className="tabs">
            {[
              ["configuration", "配置"],
              ["regression", "回归用例"],
              ["validation", "校验结果"],
              ["versions", "版本与发布"],
            ].map(([key, label]) => (
              <button
                key={key}
                className={tab === key ? "active" : ""}
                onClick={() => setTab(key)}
              >
                {label}
                {key === "versions" && <span>{revisions.length}</span>}
              </button>
            ))}
          </div>
          <form
            ref={form}
            onSubmit={save}
            onChange={() => {
              setDirty(true);
              setMessage("");
            }}
          >
            <div
              className={tab === "configuration" ? "editor-content" : "hidden"}
            >
              <div className="form-grid">
                <Field label="显示名称" required>
                  <input
                    required
                    maxLength={160}
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                  />
                </Field>
                <Field
                  label="资源标识"
                  required
                  hint="发布后通过此标识与固定版本引用。"
                >
                  <input
                    required
                    disabled={!isNew}
                    pattern="[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"
                    maxLength={128}
                    value={identifier}
                    placeholder="例如 ops-knowledge"
                    onChange={(event) => setIdentifier(event.target.value)}
                  />
                </Field>
              </div>
              <div className="section-title">
                <h3>{labels[type]}配置</h3>
                <div className="segmented">
                  <button
                    type="button"
                    className={mode === "form" ? "active" : ""}
                    onClick={() => setMode("form")}
                  >
                    结构化表单
                  </button>
                  <button
                    type="button"
                    className={mode === "json" ? "active" : ""}
                    onClick={() => setMode("json")}
                  >
                    JSON
                  </button>
                </div>
              </div>
              {capabilities.loading ? (
                <Loading label="正在读取资源模板" />
              ) : capabilities.error ? (
                <ErrorNotice
                  error={capabilities.error}
                  onRetry={capabilities.reload}
                />
              ) : mode === "form" ? (
                <SpecEditor
                  type={type}
                  value={spec}
                  onChange={(next) => {
                    setSpec(next);
                    setDirty(true);
                  }}
                  resources={catalog.data?.items ?? []}
                  capabilities={capabilities.data}
                />
              ) : (
                <JsonField
                  label="完整资源配置"
                  objectOnly
                  value={spec}
                  onChange={(next) => {
                    if (
                      next &&
                      typeof next === "object" &&
                      !Array.isArray(next)
                    ) {
                      setSpec(next);
                      setDirty(true);
                    }
                  }}
                  rows={24}
                />
              )}
            </div>
            <div className={tab === "regression" ? "editor-content" : "hidden"}>
              <RegressionEditor
                value={cases}
                onChange={(next) => {
                  setCases(next);
                  setDirty(true);
                }}
              />
            </div>
            {tab === "validation" && (
              <div className="editor-content">
                {saved?.validation ? (
                  <>
                    <div className="section-title">
                      <h3>当前草稿的校验记录</h3>
                      <Badge
                        value={saved.validation.passed ? "COMPLETED" : "FAILED"}
                      />
                    </div>
                    <JsonView value={saved.validation} />
                  </>
                ) : (
                  <Empty
                    title="尚无校验记录"
                    description="先保存草稿，然后运行校验。发布会固定依赖版本并执行回归检查。"
                  />
                )}
              </div>
            )}
            {tab === "versions" && (
              <div className="editor-content">
                <div className="section-title">
                  <h3>不可变发布版本</h3>
                  {revisions.length > 1 && (
                    <button
                      type="button"
                      className="button small"
                      onClick={() =>
                        setCompare([
                          revisions.at(-2)!.version,
                          revisions.at(-1)!.version,
                        ])
                      }
                    >
                      <GitCompareArrows size={15} />
                      比较版本
                    </button>
                  )}
                </div>
                {revisions.length === 0 ? (
                  <Empty
                    title="尚无发布版本"
                    description="校验当前草稿后，即可发布第一个版本。"
                  />
                ) : (
                  revisions
                    .slice()
                    .reverse()
                    .map((version) => (
                      <div className="version-row" key={version.version}>
                        <div>
                          <div className="version-title">
                            <strong>v{version.version}</strong>
                            {saved?.defaultVersion === version.version && (
                              <span className="tag">默认版本</span>
                            )}
                            <Badge
                              value={
                                version.disabled ? "DISABLED" : "PUBLISHED"
                              }
                            />
                          </div>
                          <p>{formatDate(version.publishedAt)}</p>
                          <CopyValue value={version.digest} />
                        </div>
                        <div className="version-actions">
                          <button
                            type="button"
                            className="button small"
                            onClick={() => setReadVersion(version.version)}
                          >
                            查看版本
                          </button>
                          {type === "Agent" &&
                            saved?.defaultVersion !== version.version &&
                            !version.disabled && (
                              <button
                                type="button"
                                className="button small"
                                disabled={
                                  busy || dirty || !canPublish || blocked
                                }
                                onClick={() =>
                                  setAction({
                                    kind: "default",
                                    title: `切换默认版本至 v${version.version}`,
                                    body: { version: version.version },
                                  })
                                }
                              >
                                设为默认
                              </button>
                            )}
                          <button
                            type="button"
                            className="button small"
                            disabled={busy || dirty || !canPublish || blocked}
                            onClick={() =>
                              setAction({
                                kind: "version-disable",
                                version: version.version,
                                title: `${version.disabled ? "启用" : "停用"}版本 v${version.version}`,
                                body: { disabled: !version.disabled },
                              })
                            }
                          >
                            {version.disabled ? "启用版本" : "停用版本"}
                          </button>
                        </div>
                      </div>
                    ))
                )}
              </div>
            )}
            <div className="editor-footer">
              <span>
                {dirty
                  ? "修改尚未保存"
                  : saved
                    ? `当前读取修订 r${saved.revision}`
                    : "新草稿"}
              </span>
              <button
                className="button primary"
                type="submit"
                disabled={busy || readOnly || blocked || (!dirty && !isNew)}
              >
                <Save size={16} />
                {busy ? "处理中…" : "保存草稿"}
              </button>
            </div>
          </form>
        </div>
        <aside className="editor-sidebar">
          <div className="panel side-card">
            <span className="eyebrow">RELEASE CHECKLIST</span>
            <h3>发布准备</h3>
            <ol className="release-steps">
              <li className={saved ? "done" : ""}>
                <span>1</span>
                <div>
                  <strong>保存配置</strong>
                  <p>提交资源与精确依赖版本</p>
                </div>
              </li>
              <li className={saved?.validation?.passed ? "done" : ""}>
                <span>2</span>
                <div>
                  <strong>校验与回归</strong>
                  <p>检查契约、授权和冻结用例</p>
                </div>
              </li>
              <li>
                <span>3</span>
                <div>
                  <strong>发布不可变版本</strong>
                  <p>旧运行继续使用原快照</p>
                </div>
              </li>
            </ol>
            <button
              className="button full"
              disabled={!saved || busy || dirty || !canValidate || blocked}
              onClick={validate}
            >
              <CheckCheck size={16} />
              校验草稿
            </button>
            <button
              className="button primary full"
              disabled={
                !saved ||
                busy ||
                dirty ||
                !canPublish ||
                blocked ||
                !saved.validation?.passed
              }
              onClick={() =>
                setAction({ kind: "publish", title: "发布当前草稿", body: {} })
              }
            >
              <Rocket size={16} />
              发布新版本
            </button>
            {dirty && <p className="field-hint">保存修改后才能校验或发布。</p>}
          </div>
          {saved && (
            <div className="panel side-card">
              <h3>可用性管理</h3>
              <p className="muted">
                停用阻止新运行。紧急撤权还会阻止已有运行的后续调用。
              </p>
              <button
                className="button full"
                disabled={busy || dirty || !canPublish || blocked}
                onClick={() =>
                  setAction({
                    kind: "disable",
                    title: saved.disabled ? "恢复资源可用性" : "停用资源",
                    body: { disabled: !saved.disabled },
                  })
                }
              >
                {saved.disabled ? "恢复资源" : "停用资源"}
              </button>
              <button
                className="button danger-outline full"
                disabled={busy || dirty || !canPublish || blocked}
                onClick={() =>
                  setAction({
                    kind: "revoke",
                    title: "紧急撤销资源授权",
                    body: { revoked: true },
                  })
                }
              >
                <ShieldAlert size={16} />
                紧急撤权
              </button>
              <button
                className="text-button"
                disabled={busy || dirty || !canPublish || blocked}
                onClick={() =>
                  setAction({
                    kind: "revoke",
                    title: "解除资源紧急撤权",
                    body: { revoked: false },
                  })
                }
              >
                解除紧急撤权
              </button>
            </div>
          )}
        </aside>
      </div>
      {action && saved && (
        <Modal
          title={action.title}
          description="请核对当前目标。此操作使用刚读取的修订，版本已变化时平台会拒绝提交。"
          busy={busy}
          onClose={() => {
            if (!busy) setAction(undefined);
          }}
        >
          <div className="review-summary">
            <div>
              <span>项目</span>
              <strong>{project}</strong>
            </div>
            <div>
              <span>资源</span>
              <strong>
                {labels[type]} / {saved.id}
              </strong>
            </div>
            <div>
              <span>读取修订</span>
              <strong>r{saved.revision}</strong>
            </div>
            <div>
              <span>操作内容</span>
              <code>{JSON.stringify(action.body)}</code>
            </div>
          </div>
          {action.kind === "publish" && (
            <Notice>
              将创建新的不可变版本。已有版本与运行快照不受此次发布覆盖。
            </Notice>
          )}
          {action.kind === "revoke" && action.body.revoked === true && (
            <Notice tone="warning">
              已有运行的后续调用也会被拒绝。已产生的外部副作用不会撤回。
            </Notice>
          )}
          {Boolean(error) && <ErrorNotice error={error} />}
          <div className="modal-actions">
            <button
              className="button"
              disabled={busy}
              onClick={() => setAction(undefined)}
            >
              返回检查
            </button>
            <button
              className={`button ${action.kind === "revoke" ? "danger" : "primary"}`}
              disabled={busy || blocked || !canPublish}
              onClick={execute}
            >
              {busy ? "提交中…" : `确认${action.title}`}
            </button>
          </div>
        </Modal>
      )}
      {readVersion && saved && (
        <VersionDetails
          project={project}
          resource={saved}
          version={readVersion}
          onClose={() => setReadVersion(undefined)}
        />
      )}
      {compare && saved && (
        <VersionCompare
          project={project}
          resource={saved}
          initial={compare}
          onClose={() => setCompare(undefined)}
        />
      )}
    </>
  );
}

function VersionCompare({
  project,
  resource,
  initial,
  onClose,
}: {
  project: string;
  resource: Resource;
  initial: [number, number];
  onClose: () => void;
}) {
  const [from, setFrom] = useState(initial[0]);
  const [to, setTo] = useState(initial[1]);
  const remote = useRemote<{
    fromVersion: number;
    toVersion: number;
    changes: { path: string; before: Json; after: Json }[];
  }>(
    `${resourcePath(project, resource.type, resource.id)}/versions/${to}/diff?against=${from}`,
  );
  return (
    <Modal
      title="比较发布版本"
      description={`${resource.name} · ${resource.id}`}
      onClose={onClose}
    >
      <div className="form-grid">
        <Field label="原版本">
          <select
            value={from}
            onChange={(event) => setFrom(Number(event.target.value))}
          >
            {resource.versions.map((version) => (
              <option value={version.version} key={version.version}>
                v{version.version}
              </option>
            ))}
          </select>
        </Field>
        <Field label="目标版本">
          <select
            value={to}
            onChange={(event) => setTo(Number(event.target.value))}
          >
            {resource.versions.map((version) => (
              <option value={version.version} key={version.version}>
                v{version.version}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : remote.data?.changes.length ? (
        <div className="diff-list">
          {remote.data.changes.map((change) => (
            <div key={change.path} className="diff-entry">
              <code>{change.path}</code>
              <div className="diff-grid">
                <pre className="removed">
                  {JSON.stringify(change.before, null, 2)}
                </pre>
                <pre className="added">
                  {JSON.stringify(change.after, null, 2)}
                </pre>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <Empty title="两个版本没有配置差异" />
      )}
    </Modal>
  );
}

function VersionDetails({
  project,
  resource,
  version,
  onClose,
}: {
  project: string;
  resource: Resource;
  version: number;
  onClose: () => void;
}) {
  const remote = useRemote<{
    spec: Spec;
    regressionCases: Json[];
    validation: Json;
    digest: string;
    releaseRef?: Json;
  }>(
    `${resourcePath(project, resource.type, resource.id)}/versions/${version}`,
  );
  return (
    <Modal
      title={`${resource.name} · v${version}`}
      description="以下内容来自已发布的不可变版本，修改草稿不会覆盖此记录。"
      onClose={onClose}
    >
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        remote.data && (
          <>
            <div className="compact-contract">
              <span>发布摘要</span>
              <CopyValue value={remote.data.digest} />
            </div>
            <h3>已发布配置</h3>
            <JsonView value={remote.data.spec} />
            {remote.data.releaseRef && (
              <>
                <h3>Agent 发布引用</h3>
                <JsonView value={remote.data.releaseRef} />
              </>
            )}
            <h3>发布校验</h3>
            <JsonView value={remote.data.validation} />
            <details className="details">
              <summary>冻结的回归用例</summary>
              <JsonView value={remote.data.regressionCases} />
            </details>
          </>
        )
      )}
    </Modal>
  );
}
