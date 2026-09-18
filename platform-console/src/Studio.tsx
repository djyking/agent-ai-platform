import { useEffect, useState } from "react";
import {
  Activity,
  ArrowLeft,
  ArrowRight,
  BookOpen,
  Boxes,
  Download,
  FileSearch,
  FileText,
  Fingerprint,
  GitBranch,
  Layers3,
  Play,
  Plus,
  RefreshCw,
  Save,
  Send,
  ShieldCheck,
  Sparkles,
  Trash2,
  Wrench,
} from "lucide-react";
import { request, type Reply } from "./api";
import { navigate, useRemote } from "./hooks";
import { RunDetail } from "./Runs";
import { ConnectionManager, KnowledgeManager } from "./Shared";
import { listPending } from "./pending-commands";
import type { Json, Page, Principal } from "./types";
import {
  Badge,
  CopyValue,
  Empty,
  ErrorNotice,
  Field,
  formatDate,
  JsonView,
  Loading,
  Modal,
  Notice,
  PageHeading,
} from "./ui";
import { studioPath, useStudioCommand } from "./studio-api";
import {
  editable,
  experimentId,
  type StudioApplication,
  type StudioCapabilities,
  type StudioDraft,
  type StudioExperiment,
  type StudioInput,
  type StudioSample,
  type StudioTask,
  type StudioTemplate,
  type TaskLink,
  type TaskResult,
} from "./studio-types";
const can = (principal: Principal, permission: string) =>
  principal.permissions.includes(permission) ||
  principal.permissions.includes("*");
const displayTaskTitle = (value: string) =>
  value.length > 100 ? value.slice(0, 100) + "…" : value;
const appPath = (id: string) => `/applications/${encodeURIComponent(id)}`;
const modes: Record<string, string> = {
  PREVIEW: "草稿体验",
  RELEASE: "已发布任务",
  EVALUATION: "样本评测",
};
const templateName = (id: string) =>
  ({
    knowledge: "知识问答",
    research: "资料研究与报告",
    structured: "结构化资料处理",
  })[id] ?? id;
const templateIcon = (id: string) =>
  id === "knowledge" ? BookOpen : id === "structured" ? GitBranch : FileSearch;
const outputText = (value?: Json): string =>
  typeof value === "string"
    ? value
    : value && typeof value === "object" && !Array.isArray(value)
      ? typeof value.answer === "string"
        ? value.answer
        : typeof value.text === "string"
          ? value.text
          : typeof value.evidence === "string"
            ? value.evidence
            : JSON.stringify(value, null, 2)
      : value === undefined || value === null
        ? ""
        : JSON.stringify(value, null, 2);
function CommandNotice({
  command,
}: {
  command: ReturnType<typeof useStudioCommand>;
}) {
  return (
    <>
      {Boolean(command.error) && <ErrorNotice error={command.error} />}
      {command.pending && (
        <div className="studio-error-recovery">
          <Notice tone="warning">
            原操作尚未确认，当前应用的写入已锁定。先读取同一操作的持久记录，再核对应用；重新打开页面不会自动重发。
          </Notice>
          <button
            className="button"
            disabled={command.busy}
            onClick={command.recover}
          >
            <RefreshCw size={15} />
            {command.busy ? "正在核对…" : "读取原操作结果"}
          </button>
        </div>
      )}
    </>
  );
}
export function ApplicationHome({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const apps = useRemote<Page<StudioApplication>>(
    `${studioPath(project)}/applications`,
  );
  const templates = useRemote<Page<StudioTemplate>>(
    `${studioPath(project)}/templates`,
  );
  const capabilities = useRemote<StudioCapabilities>(
    `${studioPath(project)}/capabilities`,
  );
  const [create, setCreate] = useState<StudioTemplate>();
  const unresolved = listPending(
    "studio",
    `${project}/${principal.application}/${principal.subject}/`,
  );
  return (
    <>
      <PageHeading
        eyebrow="BUILD WITH HARNESS"
        title="应用"
        description="从一个任务开始，把模型、知识与工具组合成可以交付的助手。"
        actions={
          <button
            className="button"
            onClick={() => {
              apps.reload();
              templates.reload();
              capabilities.reload();
            }}
          >
            <RefreshCw size={16} />
            刷新
          </button>
        }
      />
      {unresolved.length > 0 && (
        <section className="studio-section">
          <h3>需要核对的上次操作</h3>
          <p>
            页面刷新前的请求结果尚未确认。先读取原操作，避免重新创建同一应用或任务。
          </p>
          {unresolved.map((item) => (
            <div className="capability-row" key={item.resource}>
              <span>应用 {item.resource}</span>
              <button
                className="button small"
                onClick={() =>
                  navigate(`/apps/${encodeURIComponent(item.resource)}`)
                }
              >
                核对原操作 <ArrowRight size={14} />
              </button>
            </div>
          ))}
        </section>
      )}
      <section className="studio-intro">
        <div>
          <span className="eyebrow">YOUR NEXT AGENT STARTS HERE</span>
          <h2>
            先让它完成一件事，
            <br />
            再把它交给更多人。
          </h2>
          <p>选模板、写目标、立即体验。评测和发布，也在同一个工作台。</p>
        </div>
        <div className="studio-intro-mark" aria-hidden="true">
          <Sparkles size={47} />
        </div>
      </section>
      <div className="section-title">
        <h2>从你要完成的事情开始</h2>
        <span className="muted">模板生成可执行配置，资源版本由服务端固定</span>
      </div>
      {templates.loading ? (
        <Loading />
      ) : templates.error ? (
        <ErrorNotice error={templates.error} onRetry={templates.reload} />
      ) : (
        <div className="template-grid">
          {templates.data?.items.map((t) => {
            const Icon = templateIcon(t.id);
            return (
              <button
                className="template-card"
                key={t.id}
                disabled={!can(principal, "catalog:write")}
                onClick={() => setCreate(t)}
              >
                <Icon size={23} />
                <strong>{t.name}</strong>
                <span>{t.description}</span>
              </button>
            );
          })}
        </div>
      )}
      <div className="section-title large">
        <h2>我的应用</h2>
        <span className="muted">当前工作空间 · {project}</span>
      </div>
      {apps.loading ? (
        <Loading />
      ) : apps.error ? (
        <ErrorNotice error={apps.error} onRetry={apps.reload} />
      ) : apps.data?.items.length ? (
        <div className="app-grid">
          {apps.data.items.map((app) => {
            const Icon = templateIcon(app.templateId);
            return (
              <article className="app-card" key={app.id}>
                <Icon className="app-icon" size={22} />
                <div className="app-card-content">
                  <h3>{app.name}</h3>
                  <Badge
                    value={
                      app.disabled
                        ? "DISABLED"
                        : app.publishedVersions.length
                          ? "PUBLISHED"
                          : "DRAFT"
                    }
                  />
                  <p>
                    {templateName(app.templateId)} · 更新于{" "}
                    {formatDate(app.updatedAt)}
                  </p>
                  <div className="app-card-bottom">
                    <span className="muted">
                      {app.publishedVersions.length
                        ? `${app.publishedVersions.length} 个发布版本`
                        : "尚未对外发布"}
                    </span>
                    <button
                      className="text-button"
                      onClick={() =>
                        navigate(`/apps/${encodeURIComponent(app.id)}`)
                      }
                    >
                      进入 Studio <ArrowRight size={15} />
                    </button>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      ) : (
        <Empty
          title="开始构建你的第一个助手"
          description="从上方模板开始。你不需要先创建模型、工作流和策略资源。"
        />
      )}
      <div className="studio-meta">
        <a href="#/resources">
          已有高级资源与兼容 Agent <ArrowRight size={12} />
        </a>
      </div>
      {create && (
        <CreateApplication
          key={create.id}
          project={project}
          principal={principal}
          template={create}
          capabilities={capabilities.data}
          onClose={() => setCreate(undefined)}
        />
      )}
    </>
  );
}
function CreateApplication({
  project,
  principal,
  template,
  capabilities,
  onClose,
}: {
  project: string;
  principal: Principal;
  template: StudioTemplate;
  capabilities?: StudioCapabilities;
  onClose: () => void;
}) {
  const [id] = useState(() => `app-${crypto.randomUUID()}`);
  const [draft, setDraft] = useState<StudioDraft>(() => ({
    ...editable(template.defaultDraft),
    templateId: template.id,
    name: template.defaultDraft.name || template.name,
    outputFormat:
      template.defaultDraft.outputFormat ??
      (template.id === "knowledge" ? "text" : "markdown"),
    modelProfileId:
      template.defaultDraft.modelProfileId || capabilities?.models[0]?.id || "",
  }));
  const command = useStudioCommand(
    project,
    `${principal.application}/${principal.subject}`,
    id,
    () => navigate(`/apps/${encodeURIComponent(id)}`),
  );
  return (
    <Modal
      title={`创建${template.name}`}
      description="先选名称和已获准的模型，创建后即可在 Studio 修改和试用。"
      onClose={onClose}
      busy={command.busy || command.blocked}
    >
      <Field label="应用名称" required>
        <input
          value={draft.name}
          disabled={command.busy || command.blocked}
          onChange={(e) => setDraft({ ...draft, name: e.target.value })}
        />
      </Field>
      <Field label="模型连接" required>
        <select
          value={draft.modelProfileId}
          disabled={command.busy || command.blocked}
          onChange={(e) =>
            setDraft({ ...draft, modelProfileId: e.target.value })
          }
        >
          <option value="">选择可用模型</option>
          {capabilities?.models.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name || m.model} · {m.provider}
            </option>
          ))}
        </select>
      </Field>
      {!capabilities?.models.length && (
        <Notice>
          当前空间没有可选的已获准模型，请先由管理员配置共享能力。
        </Notice>
      )}
      <CommandNotice command={command} />
      <div className="modal-actions">
        <button
          className="button"
          onClick={onClose}
          disabled={command.busy || command.blocked}
        >
          取消
        </button>
        <button
          className="button primary"
          disabled={
            command.busy ||
            command.blocked ||
            !draft.name.trim() ||
            !draft.modelProfileId
          }
          onClick={() => command.execute(appPath(id), draft, '"s0"', "PUT")}
        >
          <Plus size={16} />
          {command.busy ? "正在创建…" : "创建并进入 Studio"}
        </button>
      </div>
    </Modal>
  );
}
export function ApplicationStudio({
  project,
  principal,
  id,
}: {
  project: string;
  principal: Principal;
  id: string;
}) {
  const remote = useRemote<StudioApplication>(
    `${studioPath(project)}${appPath(id)}`,
  );
  const capabilities = useRemote<StudioCapabilities>(
    `${studioPath(project)}/capabilities`,
  );
  const [app, setApp] = useState<StudioApplication>();
  const [etag, setEtag] = useState<string>();
  const [draft, setDraft] = useState<StudioDraft>();
  const [tab, setTab] = useState("build");
  const [importedSample, setImportedSample] = useState(false);
  const [question, setQuestion] = useState("");
  const [fields, setFields] = useState<{ name: string; value: string }[]>([]);
  const [preview, setPreview] = useState<TaskLink>();
  const [note, setNote] = useState("");
  const accept = (reply: Reply<StudioApplication>) => {
    setApp(reply.data);
    setEtag(reply.etag ?? `"s${reply.data.revision}"`);
    setDraft(editable(reply.data));
    if (reply.data.lastTask) setPreview(reply.data.lastTask);
  };
  useEffect(() => {
    if (remote.data)
      accept({ data: remote.data, etag: remote.etag ?? null, requestId: null });
  }, [remote.data, remote.etag]);
  const command = useStudioCommand(
    project,
    `${principal.application}/${principal.subject}`,
    id,
    accept,
  );
  useEffect(() => {
    const sampleId = new URLSearchParams(location.hash.split("?")[1] ?? "").get(
      "sample",
    );
    if (
      !sampleId ||
      !remote.data ||
      importedSample ||
      !can(principal, "catalog:write")
    )
      return;
    setImportedSample(true);
    void request<StudioTask>(
      `${studioPath(project)}/tasks/${encodeURIComponent(sampleId)}`,
    )
      .then((reply) => {
        if (reply.data.applicationId !== id || !reply.data.input)
          throw new Error("原任务输入不属于此应用或当前不可读取。");
        setDraft((current) =>
          current
            ? {
                ...current,
                samples: [
                  ...current.samples,
                  {
                    id: crypto.randomUUID(),
                    name: `任务反馈 ${current.samples.length + 1}`,
                    input: reply.data.input!,
                    expectedContains: [],
                  },
                ],
              }
            : current,
        );
        setTab("evaluate");
        setNote(
          "已复制授权可见的任务输入，请填写预期检查项后保存。原任务没有被重试或修改。",
        );
      })
      .catch((error) =>
        setNote(error instanceof Error ? error.message : "无法读取任务输入。"),
      );
  }, [remote.data, importedSample, id, project, principal]);
  const experiment = useRemote<StudioExperiment>(
    experimentId(app)
      ? `${studioPath(project)}/experiments/${encodeURIComponent(experimentId(app)!)}`
      : null,
  );
  if (remote.loading && !app) return <Loading />;
  if (!app || !draft)
    return (
      <>
        {Boolean(remote.error) && (
          <ErrorNotice error={remote.error} onRetry={remote.reload} />
        )}
        <CommandNotice command={command} />
      </>
    );
  const dirty = JSON.stringify(editable(app)) !== JSON.stringify(draft);
  const locked = command.busy || command.blocked;
  const write = can(principal, "catalog:write");
  const invoke = can(principal, "runs:create");
  const input: StudioInput = {
    question: question.trim(),
    ...(draft.templateId === "structured" && fields.some((f) => f.name.trim())
      ? {
          fields: Object.fromEntries(
            fields
              .filter((f) => f.name.trim())
              .map((f) => [f.name.trim(), f.value]),
          ),
        }
      : {}),
  };
  const invalidFields =
    fields.some((f) => !f.name.trim()) ||
    new Set(fields.map((f) => f.name.trim())).size !== fields.length;
  async function act(operation: string, body: unknown) {
    setNote("");
    const result = await command.execute(
      `${appPath(id)}/${operation}`,
      body,
      etag!,
    );
    if (result) {
      if (operation === "evaluate") experiment.reload();
      setNote(
        operation === "preview"
          ? "已创建独立草稿快照，右侧读取真实执行结果。"
          : operation === "publish"
            ? "发布已完成，新任务使用此固定版本。"
            : "操作已保存。",
      );
    }
  }
  return (
    <>
      <button className="back-link" onClick={() => navigate("/")}>
        <ArrowLeft size={15} />
        应用
      </button>
      <PageHeading
        eyebrow="APPLICATION STUDIO"
        title={draft.name || app.name}
        description={`${templateName(app.templateId)} · ${app.defaultVersion ? `默认发布 v${app.defaultVersion}` : "尚未发布"}`}
        actions={
          <>
            <Badge
              value={
                app.disabled
                  ? "DISABLED"
                  : dirty
                    ? "DRAFT"
                    : app.publishedVersions.length
                      ? "PUBLISHED"
                      : "DRAFT"
              }
            />
            <button
              className="button"
              disabled={locked}
              onClick={() => {
                remote.reload();
                experiment.reload();
              }}
            >
              <RefreshCw size={15} />
              读取已保存配置
            </button>
            <button
              className="button primary"
              disabled={
                !write ||
                locked ||
                !dirty ||
                !draft.name.trim() ||
                !draft.modelProfileId ||
                draft.samples.length > 10 ||
                draft.samples.some(
                  (s) =>
                    !s.input.question.trim() ||
                    !s.expectedContains.some((t) => t.trim()),
                )
              }
              onClick={async () => {
                const saved = await command.execute(
                  appPath(id),
                  {
                    ...draft,
                    samples: draft.samples.map((s) => ({
                      ...s,
                      expectedContains: s.expectedContains
                        .map((x) => x.trim())
                        .filter(Boolean),
                    })),
                    approvers: draft.approvers
                      .map((x) => x.trim())
                      .filter(Boolean),
                  },
                  etag!,
                  "PUT",
                );
                if (saved)
                  setNote("草稿已保存。下一次体验将使用新的固定快照。");
              }}
            >
              <Save size={15} />
              {command.busy ? "处理中…" : "保存草稿"}
            </button>
          </>
        }
      />
      <CommandNotice command={command} />
      {Boolean(remote.error) && (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      )}{" "}
      {note && <Notice tone="success">{note}</Notice>}
      {dirty && (
        <Notice>
          有尚未保存的修改。保存后再体验、评测或发布；已有任务继续使用原快照。
        </Notice>
      )}
      <nav className="tabs studio-tabs" aria-label="应用生命周期">
        {[
          ["build", "构建与体验"],
          ["evaluate", "评测"],
          ["publish", "发布与接入"],
          ["feedback", "运行与反馈"],
        ].map(([key, label]) => (
          <button
            key={key}
            className={tab === key ? "active" : ""}
            onClick={() => setTab(key)}
            aria-current={tab === key ? "page" : undefined}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === "build" && (
        <div className="studio-layout">
          <section className="studio-editor" aria-label="应用配置">
            <h3>目标与能力</h3>
            <Field label="应用名称" required>
              <input
                disabled={!write || locked}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </Field>
            <Field
              label="目标与行为"
              hint="描述助手要完成什么、依据什么资料，以及信息不足时如何回应。"
              required
            >
              <textarea
                rows={7}
                disabled={!write || locked}
                value={draft.instructions}
                onChange={(e) =>
                  setDraft({ ...draft, instructions: e.target.value })
                }
              />
            </Field>
            <Field label="模型连接" required>
              <select
                disabled={!write || locked}
                value={draft.modelProfileId}
                onChange={(e) =>
                  setDraft({ ...draft, modelProfileId: e.target.value })
                }
              >
                <option value="">选择已获准模型</option>
                {capabilities.data?.models.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name || m.model} · {m.provider}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="知识来源">
              <select
                disabled={!write || locked}
                value={draft.knowledgeId}
                onChange={(e) =>
                  setDraft({ ...draft, knowledgeId: e.target.value })
                }
              >
                <option value="">不使用知识集合</option>
                {capabilities.data?.knowledge.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.name} · {k.visibility}
                  </option>
                ))}
              </select>
            </Field>
            <h3>工具能力</h3>
            <div className="studio-checkbox-list">
              {capabilities.data?.tools.length ? (
                capabilities.data.tools.map((t) => (
                  <label className="checkbox-label" key={t.id}>
                    <input
                      type="checkbox"
                      disabled={!write || locked}
                      checked={draft.toolIds.includes(t.id)}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          toolIds: e.target.checked
                            ? [...draft.toolIds, t.id]
                            : draft.toolIds.filter((id) => id !== t.id),
                        })
                      }
                    />
                    <span>
                      {t.name || t.modelName || t.key}
                      <small className="muted">
                        {" "}
                        {t.description || t.effect || "已登记工具"}
                      </small>
                    </span>
                  </label>
                ))
              ) : (
                <p className="studio-empty-capability">
                  当前空间没有可选工具。知识与模型任务仍可独立构建。
                </p>
              )}
            </div>
            {Boolean(capabilities.error) && (
              <ErrorNotice
                error={capabilities.error}
                onRetry={capabilities.reload}
              />
            )}
            <Field label="交付格式">
              <select
                disabled={!write || locked}
                value={draft.outputFormat}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    outputFormat: e.target.value as "text" | "markdown",
                  })
                }
              >
                <option value="text">文本答复</option>
                <option value="markdown">Markdown 报告与下载文件</option>
              </select>
            </Field>
            <details>
              <summary>运行边界与人工审核</summary>
              <div className="form-grid">
                {(
                  [
                    ["maxTokens", "Token 上限"],
                    ["maxModelCalls", "模型调用上限"],
                    ["maxToolCalls", "工具调用上限"],
                    ["maxSteps", "步骤上限"],
                    ["lifetimeSeconds", "最长执行秒数"],
                  ] as const
                ).map(([key, label]) => (
                  <Field key={key} label={label}>
                    <input
                      type="number"
                      min={key === "maxToolCalls" ? 0 : 1}
                      disabled={!write || locked}
                      value={draft.limits[key]}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          limits: {
                            ...draft.limits,
                            [key]: Number(e.target.value),
                          },
                        })
                      }
                    />
                  </Field>
                ))}
              </div>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  disabled={!write || locked}
                  checked={draft.requireReview}
                  onChange={(e) =>
                    setDraft({ ...draft, requireReview: e.target.checked })
                  }
                />
                执行前需要指定审核人确认
              </label>
              {draft.requireReview && (
                <>
                  <Notice>
                    填写已授权审核身份，格式为应用标识/用户主体。评测中的人工请求也必须由指定审核人逐项处理。
                  </Notice>
                  <Field
                    label="指定审核人"
                    hint="每行一个 applicationId/subjectId"
                  >
                    <textarea
                      rows={3}
                      disabled={!write || locked}
                      value={draft.approvers.join("\n")}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          approvers: e.target.value.split("\n"),
                        })
                      }
                    />
                  </Field>
                </>
              )}

              <div className="studio-meta">
                应用标识 <CopyValue value={app.id} /> · 草稿修订 {app.revision}
              </div>
            </details>
          </section>
          <section className="studio-preview" aria-label="同页体验">
            <div className="studio-preview-head">
              <h3>立即体验</h3>
              <span className="tag">
                {preview ? (modes[preview.mode] ?? "任务结果") : "草稿体验"}
              </span>
            </div>
            <div className="studio-preview-body">
              {preview ? (
                <PreviewResult
                  key={preview.id}
                  project={project}
                  link={preview}
                  canAddSample={write && !locked}
                  onSample={(input) => {
                    if (draft.samples.length >= 10) {
                      setNote("每个应用最多 10 个固定样本，请先整理现有样本。");
                      return;
                    }
                    setDraft({
                      ...draft,
                      samples: [
                        ...draft.samples,
                        {
                          id: crypto.randomUUID(),
                          name: `运行反馈 ${draft.samples.length + 1}`,
                          input,
                          expectedContains: [],
                        },
                      ],
                    });
                    setTab("evaluate");
                    setNote(
                      "已将输入加入草稿样本。请填写预期检查项并保存，不会把现有输出自动判为正确。",
                    );
                  }}
                />
              ) : (
                <div className="studio-placeholder">
                  <strong>先试一次，再决定如何发布</strong>
                  在左侧写好指令，右侧提交真实输入。
                  <div className="studio-step">
                    <span>1</span>保存当前草稿
                  </div>
                  <div className="studio-step">
                    <span>2</span>固定模型、知识和工具快照
                  </div>
                  <div className="studio-step">
                    <span>3</span>使用同一运行账本执行
                  </div>
                </div>
              )}
            </div>
            <Field label="测试问题或任务" required>
              <textarea
                rows={4}
                value={question}
                disabled={locked}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder={
                  draft.templateId === "research"
                    ? "希望研究或比较什么？说明你的资料和关注点。"
                    : "输入一个真实问题，看看助手如何回应。"
                }
              />
            </Field>
            {draft.templateId === "structured" && (
              <InputFields
                fields={fields}
                setFields={setFields}
                disabled={locked}
              />
            )}
            <button
              className="button primary full"
              disabled={
                !write ||
                !invoke ||
                locked ||
                dirty ||
                !input.question ||
                invalidFields
              }
              onClick={() => act("preview", { input })}
            >
              <Play size={16} />
              {command.busy ? "正在提交…" : "体验当前草稿"}
            </button>
            <p className="studio-meta">
              每次点击创建一次新预览；结果不确定时不自动重试。
            </p>
          </section>
        </div>
      )}
      {tab === "evaluate" && (
        <>
          <SampleEditor
            samples={draft.samples}
            disabled={!write || locked}
            onChange={(samples) => setDraft({ ...draft, samples })}
          />
          <section className="studio-section">
            <h3>对比候选与已发布版本</h3>
            <p>
              使用已保存的固定样本创建真实调用。候选和基线分别记账；规则包含检查与人工审阅分开，不自动把一次回答当作质量证明。
            </p>
            <div className="studio-row">
              <button
                className="button primary"
                disabled={
                  locked ||
                  dirty ||
                  !write ||
                  !invoke ||
                  !can(principal, "catalog:validate") ||
                  !draft.samples.length
                }
                onClick={() => act("evaluate", {})}
              >
                <Play size={16} />
                运行样本评测
              </button>
              <button
                className="button"
                disabled={experiment.loading}
                onClick={experiment.reload}
              >
                <RefreshCw size={15} />
                读取评测结果
              </button>
            </div>
          </section>
          {experiment.loading && <Loading />}
          {Boolean(experiment.error) && (
            <ErrorNotice error={experiment.error} onRetry={experiment.reload} />
          )}{" "}
          {experiment.data && <ExperimentView experiment={experiment.data} />}
        </>
      )}
      {tab === "publish" && (
        <PublishPanel
          app={app}
          experiment={experiment.data}
          dirty={dirty}
          disabled={locked || !can(principal, "catalog:publish")}
          onPublish={(evaluationId) =>
            act("publish", { reviewConfirmed: true, evaluationId })
          }
          onDefault={(version) => act("default", { version })}
          onDisable={() => act("disable", { disabled: !app.disabled })}
          project={project}
        />
      )}
      {tab === "feedback" && (
        <AppFeedback
          project={project}
          app={app}
          canAddSample={write && !locked}
          onSample={(input) => {
            if (draft.samples.length >= 10) {
              setNote("每个应用最多 10 个固定样本，请先整理现有样本。");
              return;
            }
            setDraft({
              ...draft,
              samples: [
                ...draft.samples,
                {
                  id: crypto.randomUUID(),
                  name: `反馈样例 ${draft.samples.length + 1}`,
                  input,
                  expectedContains: [],
                },
              ],
            });
            setTab("evaluate");
          }}
        />
      )}
    </>
  );
}
function InputFields({
  fields,
  setFields,
  disabled,
}: {
  fields: { name: string; value: string }[];
  setFields: (value: { name: string; value: string }[]) => void;
  disabled: boolean;
}) {
  return (
    <div>
      <h3>补充结构化字段</h3>
      {fields.map((field, index) => (
        <div className="removable-row" key={index}>
          <Field label={`字段名称 ${index + 1}`}>
            <input
              value={field.name}
              disabled={disabled}
              onChange={(e) =>
                setFields(
                  fields.map((f, i) =>
                    i === index ? { ...f, name: e.target.value } : f,
                  ),
                )
              }
            />
          </Field>
          <Field label={`字段内容 ${index + 1}`}>
            <input
              value={field.value}
              disabled={disabled}
              onChange={(e) =>
                setFields(
                  fields.map((f, i) =>
                    i === index ? { ...f, value: e.target.value } : f,
                  ),
                )
              }
            />
          </Field>
          <button
            className="icon-button"
            disabled={disabled}
            aria-label={`删除字段 ${index + 1}`}
            onClick={() => setFields(fields.filter((_, i) => i !== index))}
          >
            <Trash2 size={15} />
          </button>
        </div>
      ))}
      <button
        className="button small"
        disabled={disabled}
        onClick={() => setFields([...fields, { name: "", value: "" }])}
      >
        <Plus size={14} />
        添加字段
      </button>
    </div>
  );
}
function PreviewResult({
  project,
  link,
  canAddSample,
  onSample,
}: {
  project: string;
  link: TaskLink;
  canAddSample: boolean;
  onSample: (input: StudioInput) => void;
}) {
  const task = useRemote<StudioTask>(
    `${studioPath(project)}/tasks/${encodeURIComponent(link.id)}`,
  );
  const run = task.data?.run;
  useEffect(() => {
    if (run && ["QUEUED", "RUNNING"].includes(run.status)) {
      const timer = setTimeout(task.reload, 2000);
      return () => clearTimeout(timer);
    }
  }, [run, task.reload]);
  if (task.loading && !task.data) return <Loading label="正在读取任务结果" />;
  if (task.error)
    return <ErrorNotice error={task.error} onRetry={task.reload} />;
  return (
    <>
      {task.data?.input && (
        <div className="studio-question">{task.data.input.question}</div>
      )}
      <div className="studio-row">
        <Badge value={run?.status || "QUEUED"} />
        <button className="text-button" onClick={task.reload}>
          <RefreshCw size={13} />
          刷新
        </button>
      </div>
      {run?.output?.visibility === "AVAILABLE" ? (
        <div className="studio-response">{outputText(run.output.value)}</div>
      ) : (
        <p className="studio-placeholder">
          {["WAITING_APPROVAL", "WAITING_INPUT"].includes(run?.status ?? "")
            ? "需要人工处理，请进入原任务查看精确请求。"
            : run?.status === "NEEDS_ATTENTION"
              ? "执行结果需要核对，请在任务中查看原因与证据；不要重复提交原任务。"
              : run?.status === "COMPLETED"
                ? "当前身份无法读取此任务输出。"
                : "执行结果尚未就绪。"}
        </p>
      )}
      <div className="studio-row">
        <button
          className="button small"
          onClick={() => navigate(`/tasks/${encodeURIComponent(link.id)}`)}
        >
          查看任务与活动 <ArrowRight size={13} />
        </button>
        {task.data?.input && (
          <button
            className="button small"
            disabled={!canAddSample}
            onClick={() => onSample(task.data!.input!)}
          >
            加入评测样例
          </button>
        )}
      </div>
      {run?.usage && (
        <div className="studio-meta">
          模型 {run.usage.modelCalls} 次 · 工具 {run.usage.toolCalls} 次 · Token
          账本 {run.usage.chargedTokens}
        </div>
      )}
    </>
  );
}
function SampleEditor({
  samples,
  disabled,
  onChange,
}: {
  samples: StudioSample[];
  disabled: boolean;
  onChange: (samples: StudioSample[]) => void;
}) {
  function update(index: number, changes: Partial<StudioSample>) {
    onChange(
      samples.map((sample, i) =>
        i === index ? { ...sample, ...changes } : sample,
      ),
    );
  }
  return (
    <section className="studio-section">
      <div className="section-title">
        <h3>固定样本与检查项</h3>
        <button
          className="button small"
          disabled={disabled || samples.length >= 10}
          onClick={() =>
            onChange([
              ...samples,
              {
                id: crypto.randomUUID(),
                name: `样例 ${samples.length + 1}`,
                input: { question: "" },
                expectedContains: [],
              },
            ])
          }
        >
          <Plus size={14} />
          添加样例
        </button>
      </div>
      <p>
        预期包含项是可解释的文本规则。请基于正确答案填写；这不等同于人工质量评分或语义正确性。
      </p>
      {!samples.length && (
        <Empty
          title="还没有评测样本"
          description="添加一个固定问题与预期检查项，也可以从预览或历史任务加入。"
        />
      )}
      {samples.map((sample, index) => (
        <div className="studio-sample" key={sample.id}>
          <div className="studio-sample-top">
            <strong>样例 {index + 1}</strong>
            <button
              className="icon-button"
              disabled={disabled}
              aria-label={`删除样例 ${index + 1}`}
              onClick={() => onChange(samples.filter((_, i) => i !== index))}
            >
              <Trash2 size={16} />
            </button>
          </div>
          <Field label={`样例名称 ${index + 1}`}>
            <input
              value={sample.name}
              disabled={disabled}
              onChange={(e) => update(index, { name: e.target.value })}
            />
          </Field>
          <Field label={`样例问题 ${index + 1}`} required>
            <textarea
              rows={3}
              value={sample.input.question}
              disabled={disabled}
              onChange={(e) =>
                update(index, {
                  input: { ...sample.input, question: e.target.value },
                })
              }
            />
          </Field>
          <Field
            label={`预期包含项 ${index + 1}`}
            hint="每行一个明确应出现在回答中的词句。至少填写一项，避免空规则通过。"
            required
          >
            <textarea
              rows={3}
              value={sample.expectedContains.join("\n")}
              disabled={disabled}
              onChange={(e) =>
                update(index, { expectedContains: e.target.value.split("\n") })
              }
            />
          </Field>
        </div>
      ))}
    </section>
  );
}
function ExperimentView({ experiment }: { experiment: StudioExperiment }) {
  return (
    <section className="studio-section">
      <div className="studio-row">
        <h3>评测结果</h3>
        <Badge value={experiment.status} />
        <span className="tag">
          {experiment.passed ? "全部规则通过" : "尚未通过发布门槛"}
        </span>
      </div>
      <div className="studio-meta">
        实验 {experiment.id} · 配置 {experiment.draftDigest.slice(0, 18)}
      </div>
      <details>
        <summary>模板契约检查（与模型质量分开）</summary>
        <JsonView value={experiment.contract} />
      </details>
      {experiment.rows.map((row) => (
        <article key={row.sampleId} className="studio-sample">
          <h3>{row.name}</h3>
          <p>{row.input?.question ?? "当前身份无权读取此样本输入。"}</p>
          <div className="studio-meta">
            规则：
            {row.expectedContains
              ? row.expectedContains.join("；") || "未配置"
              : "当前身份无权读取"}
          </div>
          <div className="studio-compare">
            <div>
              <h3>已发布基线</h3>
              {row.baseline ? (
                <EvaluationResult result={row.baseline} />
              ) : (
                <p className="muted">
                  首次发布，没有已发布基线。此处不生成对比结果。
                </p>
              )}
            </div>
            <div>
              <h3>当前候选</h3>
              <EvaluationResult result={row.candidate} />
            </div>
          </div>
        </article>
      ))}
    </section>
  );
}
function EvaluationResult({ result }: { result: TaskResult }) {
  return (
    <>
      <Badge value={result.status} />
      <p>
        {result.visibility === "AVAILABLE"
          ? outputText(result.output) || "当前返回无输出文本"
          : "当前结果未就绪或不可授权读取"}
      </p>
      <small>
        {result.passed === true
          ? "文本规则通过"
          : result.passed === false
            ? `未通过：${result.reason || "未满足规则"}`
            : "等待执行结果"}
      </small>
      {result.usage && (
        <small>
          Token 账本 {result.usage.chargedTokens} · 模型{" "}
          {result.usage.modelCalls} 次 · 工具 {result.usage.toolCalls} 次
        </small>
      )}
      <button
        className="text-button"
        onClick={() => navigate(`/tasks/${encodeURIComponent(result.taskId)}`)}
      >
        查看原任务 <ArrowRight size={13} />
      </button>
    </>
  );
}
function PublishPanel({
  app,
  experiment,
  dirty,
  disabled,
  onPublish,
  onDefault,
  onDisable,
  project,
}: {
  app: StudioApplication;
  experiment?: StudioExperiment;
  dirty: boolean;
  disabled: boolean;
  onPublish: (id: string) => void;
  onDefault: (version: number) => void;
  onDisable: () => void;
  project: string;
}) {
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => setConfirmed(false), [app.draftDigest, experiment?.id]);
  const ready =
    experiment?.status === "COMPLETED" &&
    experiment.passed &&
    experiment.draftDigest === app.draftDigest;
  const latest = app.publishedVersions.find(
    (v) => v.version === app.defaultVersion,
  );
  return (
    <>
      <section className="studio-section">
        <h3>将经过检查的版本交给业务使用</h3>
        <p>
          发布会固定指令、能力和运行策略。默认版本变化只影响新任务，等待审批与执行中的任务保持原快照。
        </p>
        <div className="studio-step">
          <span>1</span>
          {dirty ? "草稿修改尚未保存" : "当前草稿已保存"}
        </div>
        <div className="studio-step">
          <span>2</span>
          {ready ? "当前配置的样本评测已经通过" : "需要当前配置的完整通过评测"}
        </div>
        <div className="studio-step">
          <span>3</span>人工核对候选输出、工具范围和运行边界
        </div>
        <details className="publication-config">
          <summary>核对本次发布配置</summary>
          <div className="review-summary">
            <div>
              <span>模型</span>
              <strong>{app.modelProfileId}</strong>
            </div>
            <div>
              <span>知识集合</span>
              <strong>{app.knowledgeId || "未绑定"}</strong>
            </div>
            <div>
              <span>工具范围</span>
              <strong>{app.toolIds.join("、") || "无额外工具"}</strong>
            </div>
            <div>
              <span>人工审核</span>
              <strong>
                {app.requireReview ? app.approvers.join("、") : "未启用"}
              </strong>
            </div>
          </div>
          <div className="studio-response">{app.instructions}</div>
          <div className="studio-meta">
            Token 上限 {app.limits.maxTokens} · 模型 {app.limits.maxModelCalls}{" "}
            次 · 工具 {app.limits.maxToolCalls} 次 · 最长{" "}
            {app.limits.lifetimeSeconds} 秒
          </div>
        </details>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={confirmed}
            disabled={disabled || dirty || !ready}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          我已审阅本次候选结果与配置，确认发布当前版本
        </label>
        <button
          className="button primary"
          disabled={disabled || dirty || !ready || !confirmed}
          onClick={() => onPublish(experiment!.id)}
        >
          <Send size={16} />
          确认发布当前草稿
        </button>
      </section>
      <section className="studio-section">
        <div className="section-title">
          <h3>已发布版本</h3>
          <button
            className="button small"
            disabled={disabled}
            onClick={onDisable}
          >
            {app.disabled ? "允许创建新任务" : "停用新任务入口"}
          </button>
        </div>
        {!app.publishedVersions.length ? (
          <Empty
            title="还没有发布版本"
            description="完成样本评测并人工审阅后，生成首个可接入版本。"
          />
        ) : (
          <table className="studio-table">
            <thead>
              <tr>
                <th>版本</th>
                <th>发布时间</th>
                <th>变更与默认</th>
              </tr>
            </thead>
            <tbody>
              {[...app.publishedVersions].reverse().map((v) => (
                <tr key={v.version}>
                  <td>
                    <strong>v{v.version}</strong>
                    <CopyValue value={v.releaseRef.releaseId} />
                  </td>
                  <td>{formatDate(v.publishedAt)}</td>
                  <td>
                    {v.draftDigest === app.draftDigest ? (
                      <span className="tag">与当前草稿一致</span>
                    ) : (
                      <span className="muted">历史配置</span>
                    )}
                    <div style={{ marginTop: 8 }}>
                      {app.defaultVersion === v.version ? (
                        <Badge value="PUBLISHED">默认版本</Badge>
                      ) : (
                        <button
                          className="button small"
                          disabled={disabled}
                          onClick={() => onDefault(v.version)}
                        >
                          设为默认版本
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {latest && (
        <section className="studio-section">
          <h3>Web 体验与 API 接入</h3>
          <p>
            任务工作台与业务 API
            均可调用默认发布。调用者使用自己的有效授权，页面不会生成或展示服务端凭据。
          </p>
          <button
            className="button"
            onClick={() => navigate(`/tasks?app=${encodeURIComponent(app.id)}`)}
          >
            <Play size={15} />
            打开已发布助手
          </button>
          <h3 style={{ marginTop: 24 }}>固定发布版本 · Run API</h3>
          <pre className="studio-code">{`POST /v1/projects/${encodeURIComponent(project)}/runs\nAuthorization: Bearer <业务应用凭据>\nX-Harness-User-Token: <当前用户的有效委托令牌>\nIdempotency-Key: <每个新请求的唯一标识>\nContent-Type: application/json\n\n${JSON.stringify({ releaseRef: latest.releaseRef, inputs: { question: "你的问题或任务" } }, null, 2)}`}</pre>
          <details className="publication-config">
            <summary>Java SDK 接入示例</summary>
            <pre className="studio-code">{`import io.github.djyking.harness.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;

var client = new PlatformClient(ClientConfiguration.defaults(
    URI.create(${JSON.stringify(location.origin)}), ${JSON.stringify(project)},
    SecretProvider.environment("HARNESS_APP_CREDENTIAL"),
    SecretProvider.environment("HARNESS_USER_TOKEN")));
var json = new ObjectMapper();
var body = json.createObjectNode();
body.set("releaseRef", json.readTree(${JSON.stringify(JSON.stringify(latest.releaseRef))}));
body.putObject("inputs").put("question", "你的问题或任务");
var accepted = client.createRun(body, UUID.randomUUID().toString());
// 保存 accepted 中的 Run 标识，再通过 getRun 查询；不要自动重放未知请求。`}</pre>
          </details>
          <Notice>
            上方示例固定当前版本。业务集成还需遵循平台应用身份与委托鉴权契约。重复提交不确定的请求前，先读取原操作状态。
          </Notice>
        </section>
      )}
    </>
  );
}
function AppFeedback({
  project,
  app,
  canAddSample,
  onSample,
}: {
  project: string;
  app: StudioApplication;
  canAddSample: boolean;
  onSample: (input: StudioInput) => void;
}) {
  const tasks = useRemote<Page<StudioTask>>(
    `${studioPath(project)}/tasks?applicationId=${encodeURIComponent(app.id)}`,
  );
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  async function sample(id: string) {
    setBusy(true);
    setError(undefined);
    try {
      const reply = await request<StudioTask>(
        `${studioPath(project)}/tasks/${encodeURIComponent(id)}`,
      );
      if (!reply.data.input)
        throw new Error("当前输入不可读取，不能复制到样本。");
      onSample(reply.data.input);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeading
        title="运行与反馈"
        description="从真实任务沉淀样例，修改指令后比较效果。原运行和输出不会被改写。"
        actions={
          <button className="button" onClick={tasks.reload}>
            <RefreshCw size={15} />
            刷新
          </button>
        }
      />
      {Boolean(error) && <ErrorNotice error={error} />}{" "}
      {tasks.loading ? (
        <Loading />
      ) : tasks.error ? (
        <ErrorNotice error={tasks.error} onRetry={tasks.reload} />
      ) : tasks.data?.items.length ? (
        tasks.data.items.map((task) => (
          <TaskCard
            key={task.id}
            task={task}
            actions={
              <button
                className="button small"
                disabled={busy || !canAddSample}
                onClick={() => sample(task.id)}
              >
                加入评测样例
              </button>
            }
          />
        ))
      ) : (
        <Empty
          title="还没有任务反馈"
          description="先在构建页试用当前草稿，或运行已发布的助手。"
        />
      )}
    </>
  );
}
function TaskCard({
  task,
  actions,
}: {
  task: StudioTask;
  actions?: React.ReactNode;
}) {
  return (
    <article className="task-card">
      <div className="task-card-head">
        <div>
          <strong>
            {task.name ||
              task.input?.question ||
              modes[task.mode] ||
              "应用任务"}
          </strong>
          <p>
            {modes[task.mode]} · {task.applicationId} ·{" "}
            {formatDate(task.createdAt)}
          </p>
        </div>
        <div className="studio-row">
          {(task.run?.status || task.status) && (
            <Badge value={task.run?.status || task.status!} />
          )}
          <button
            className="button small"
            onClick={() => navigate(`/tasks/${encodeURIComponent(task.id)}`)}
          >
            查看任务 <ArrowRight size={14} />
          </button>
          {actions}
        </div>
      </div>
      <div className="studio-meta">
        会话 {task.sessionId} · 任务 {task.id}
      </div>
    </article>
  );
}
export function TaskWorkspace({
  project,
  principal,
  id,
}: {
  project: string;
  principal: Principal;
  id?: string;
}) {
  const tasks = useRemote<Page<StudioTask>>(
    id ? null : `${studioPath(project)}/tasks`,
  );
  const task = useRemote<StudioTask>(
    id ? `${studioPath(project)}/tasks/${encodeURIComponent(id)}` : null,
  );
  const apps = useRemote<Page<StudioApplication>>(
    `${studioPath(project)}/applications`,
  );
  const [selected, setSelected] = useState(
    () =>
      new URLSearchParams(location.hash.split("?")[1] ?? "").get("app") ?? "",
  );
  const [continueSession, setContinueSession] = useState<string>();
  if (id) {
    if (task.loading) return <Loading />;
    if (task.error)
      return <ErrorNotice error={task.error} onRetry={task.reload} />;
    if (!task.data) return null;
    const data = task.data;
    const attention = [
      "WAITING_INPUT",
      "WAITING_APPROVAL",
      "NEEDS_ATTENTION",
    ].includes(data.run?.status ?? "");
    return (
      <>
        <button className="back-link" onClick={() => navigate("/tasks")}>
          <ArrowLeft size={15} />
          任务工作台
        </button>
        <PageHeading
          eyebrow="TASK WORKSPACE"
          title={displayTaskTitle(
            data.name || data.input?.question || "应用任务",
          )}
          description={`${modes[data.mode]} · ${apps.data?.items.find((a) => a.id === data.applicationId)?.name ?? "应用助手"}`}
          actions={
            <button className="button" onClick={task.reload}>
              <RefreshCw size={15} />
              刷新任务
            </button>
          }
        />
        <section className="studio-section">
          <div className="studio-row">
            <Badge value={data.run?.status ?? "QUEUED"} />
            {data.run?.usage && (
              <span className="muted">
                模型 {data.run.usage.modelCalls} 次 · 工具{" "}
                {data.run.usage.toolCalls} 次 · Token 账本{" "}
                {data.run.usage.chargedTokens}
              </span>
            )}
          </div>
          {attention && (
            <Notice tone="warning">
              当前任务需要人工处理。请在下方「执行活动与高级控制」核对具体请求，继续原任务。
            </Notice>
          )}
          {data.input && (
            <div className="studio-question">{data.input.question}</div>
          )}
          <h3 style={{ marginTop: 24 }}>任务结果</h3>
          {data.run?.output?.visibility === "AVAILABLE" ? (
            <div className="studio-response">
              {outputText(data.run.output.value)}
            </div>
          ) : (
            <p className="studio-placeholder">
              {data.run?.status === "COMPLETED"
                ? "当前身份无法读取此任务输出。"
                : attention
                  ? "完成下方人工处理后，结果会回到当前任务。"
                  : "结果尚未就绪。刷新任务可查看最新状态。"}
            </p>
          )}
          {data.artifacts?.map((artifact) => (
            <div className="task-artifact" key={artifact.id}>
              <FileText size={23} />
              <div>
                <strong>
                  {artifact.name || artifact.fileName || "任务结果文件"}
                </strong>
                <small>
                  {artifact.contentType ||
                    artifact.mediaType ||
                    "text/markdown"}{" "}
                  · 来自当前任务
                </small>
              </div>
              <a
                className="button small"
                href={`/console/api${studioPath(project)}/tasks/${encodeURIComponent(data.id)}/artifacts/${encodeURIComponent(artifact.id)}`}
              >
                <Download size={15} />
                下载
              </a>
            </div>
          ))}
          <div className="studio-row">
            <button
              className="button"
              onClick={() =>
                navigate(`/apps/${encodeURIComponent(data.applicationId)}`)
              }
            >
              进入应用 Studio
            </button>
            {data.input && can(principal, "catalog:write") && (
              <button
                className="button"
                onClick={() =>
                  navigate(
                    `/apps/${encodeURIComponent(data.applicationId)}?sample=${encodeURIComponent(data.id)}`,
                  )
                }
              >
                加入应用评测样本
              </button>
            )}
            {data.mode === "RELEASE" && can(principal, "runs:create") && (
              <button
                className="button"
                onClick={() => setContinueSession(data.sessionId)}
              >
                <Plus size={15} />
                在此会话提交新任务
              </button>
            )}
          </div>
          {continueSession && (
            <>
              <Notice>
                新提交会创建新的任务与
                Run，只保留会话关联。此入口不会恢复或重试原运行，也不会自动携带历史答案作为模型上下文。
              </Notice>
              <PublishedRunner
                key={data.applicationId}
                project={project}
                principal={principal}
                id={data.applicationId}
                sessionId={continueSession}
              />
            </>
          )}
        </section>
        <details className="task-run-embedded" open={attention}>
          <summary>执行活动与高级控制</summary>
          <div className="studio-meta">
            应用 <CopyValue value={data.applicationId} /> · 会话{" "}
            <CopyValue value={data.sessionId} /> · 任务{" "}
            <CopyValue value={data.id} />
          </div>
          <RunDetail
            key={data.runId}
            project={project}
            principal={principal}
            id={data.runId}
          />
        </details>
      </>
    );
  }
  return (
    <>
      <PageHeading
        eyebrow="USE YOUR AGENTS"
        title="任务工作台"
        description="使用已发布助手，处理人工请求，查看原任务的结果与文件。"
        actions={
          <button className="button" onClick={tasks.reload}>
            <RefreshCw size={15} />
            刷新
          </button>
        }
      />
      <section className="studio-section">
        <h3>开始一项新任务</h3>
        <Field label="选择已发布助手">
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
          >
            <option value="">选择应用</option>
            {apps.data?.items
              .filter((a) => a.defaultVersion && !a.disabled)
              .map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} · v{a.defaultVersion}
                </option>
              ))}
          </select>
        </Field>
        {Boolean(apps.error) && (
          <ErrorNotice error={apps.error} onRetry={apps.reload} />
        )}{" "}
        {selected ? (
          <PublishedRunner
            key={selected}
            project={project}
            principal={principal}
            id={selected}
          />
        ) : (
          <p>先选择一个有权使用的发布版本。草稿体验在应用 Studio 中进行。</p>
        )}
      </section>
      <div className="section-title large">
        <h2>我的任务</h2>
        <a className="text-button" href="#/inbox">
          待处理审批与对账 <ArrowRight size={15} />
        </a>
      </div>
      {tasks.loading ? (
        <Loading />
      ) : tasks.error ? (
        <ErrorNotice error={tasks.error} onRetry={tasks.reload} />
      ) : tasks.data?.items.length ? (
        tasks.data.items.map((t) => <TaskCard key={t.id} task={t} />)
      ) : (
        <Empty
          title="还没有任务"
          description="提交上方的新任务后，真实执行状态和产物会在这里显示。"
        />
      )}
    </>
  );
}
function PublishedRunner({
  project,
  principal,
  id,
  sessionId,
}: {
  project: string;
  principal: Principal;
  id: string;
  sessionId?: string;
}) {
  const remote = useRemote<StudioApplication>(
    `${studioPath(project)}${appPath(id)}`,
  );
  const [question, setQuestion] = useState("");
  const [fields, setFields] = useState<{ name: string; value: string }[]>([]);
  const command = useStudioCommand(
    project,
    `${principal.application}/${principal.subject}`,
    id,
    (reply) => {
      if (reply.data.lastTask)
        navigate(`/tasks/${encodeURIComponent(reply.data.lastTask.id)}`);
      else remote.reload();
    },
  );
  if (remote.loading) return <Loading />;
  if (remote.error)
    return <ErrorNotice error={remote.error} onRetry={remote.reload} />;
  const app = remote.data;
  if (!app) return null;
  const invalid =
    fields.some((f) => !f.name.trim()) ||
    new Set(fields.map((f) => f.name.trim())).size !== fields.length;
  return (
    <>
      <Field label="你希望助手完成什么" required>
        <textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          disabled={command.busy || command.blocked}
          rows={4}
        />
      </Field>
      {app.templateId === "structured" && (
        <InputFields
          fields={fields}
          setFields={setFields}
          disabled={command.busy || command.blocked}
        />
      )}
      <CommandNotice command={command} />
      <button
        className="button primary"
        disabled={
          !can(principal, "runs:create") ||
          command.busy ||
          command.blocked ||
          !question.trim() ||
          invalid ||
          !app.defaultVersion ||
          app.disabled
        }
        onClick={() =>
          command.execute(
            `${appPath(id)}/run`,
            {
              input: {
                question: question.trim(),
                ...(fields.length
                  ? {
                      fields: Object.fromEntries(
                        fields.map((f) => [f.name.trim(), f.value]),
                      ),
                    }
                  : {}),
              },
              ...(sessionId ? { sessionId } : {}),
            },
            remote.etag ?? `"s${app.revision}"`,
          )
        }
      >
        <Send size={15} />
        {command.busy ? "正在创建…" : "提交新任务"}
      </button>
      <div className="studio-meta">
        使用默认版本 v{app.defaultVersion} · 不自动重试或模型降级
      </div>
    </>
  );
}
export function SharedCapabilities({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const remote = useRemote<StudioCapabilities>(
    `${studioPath(project)}/capabilities`,
  );
  const [tab, setTab] = useState("available");
  return (
    <>
      <PageHeading
        eyebrow="SHARED CAPABILITIES"
        title="共享能力"
        description="这里展示服务端允许当前空间选择的连接。应用只装配获批能力，凭据由服务端保管。"
        actions={
          <button className="button" onClick={remote.reload}>
            <RefreshCw size={15} />
            刷新
          </button>
        }
      />
      <nav className="tabs studio-tabs" aria-label="共享能力分类">
        {[
          ["available", "可用能力"],
          ["knowledge", "知识资料管理"],
          ["connections", "连接与诊断"],
        ].map(([value, label]) => (
          <button
            className={tab === value ? "active" : ""}
            key={value}
            onClick={() => setTab(value)}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === "knowledge" ? (
        <KnowledgeManager project={project} principal={principal} />
      ) : tab === "connections" ? (
        <ConnectionManager project={project} principal={principal} />
      ) : remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        <div className="capability-grid">
          <section className="capability-card">
            <div className="capability-card-header">
              <Sparkles size={23} />
              <h3>模型连接</h3>
            </div>
            {remote.data?.models.length ? (
              remote.data.models.map((m) => (
                <div className="capability-row" key={m.id}>
                  <div>
                    <strong>{m.name || m.model}</strong>
                    <small>
                      {m.provider} · {m.id}
                    </small>
                  </div>
                  <span className="tag">已获准</span>
                </div>
              ))
            ) : (
              <p className="studio-empty-capability">当前空间没有可用模型。</p>
            )}
          </section>
          <section className="capability-card">
            <div className="capability-card-header">
              <BookOpen size={23} />
              <h3>知识集合</h3>
            </div>
            {remote.data?.knowledge.length ? (
              remote.data.knowledge.map((k) => (
                <div className="capability-row" key={k.id}>
                  <div>
                    <strong>{k.name}</strong>
                    <small>
                      {k.description || k.id}{" "}
                      {k.version ? `· v${k.version}` : ""}
                    </small>
                  </div>
                  <span className="tag">{k.visibility}</span>
                </div>
              ))
            ) : (
              <p className="studio-empty-capability">
                当前空间没有可用知识集合。
              </p>
            )}
          </section>
          <section className="capability-card">
            <div className="capability-card-header">
              <Wrench size={23} />
              <h3>工具与连接器</h3>
            </div>
            {remote.data?.tools.length ? (
              remote.data.tools.map((t) => (
                <div className="capability-row" key={t.id}>
                  <div>
                    <strong>{t.name || t.modelName || t.key}</strong>
                    <small>{t.description || t.effect || t.id}</small>
                  </div>
                  <span className="tag">已登记</span>
                </div>
              ))
            ) : (
              <p className="studio-empty-capability">当前空间没有可用工具。</p>
            )}
          </section>
          <section className="capability-card">
            <div className="capability-card-header">
              <Layers3 size={23} />
              <h3>高级可复用资源</h3>
            </div>
            <p>
              查看已发布的指令、工作流和运行策略。普通应用由 Studio
              自动生成执行快照。
            </p>
            <button
              className="button"
              style={{ marginTop: 18 }}
              disabled={!can(principal, "catalog:read")}
              onClick={() => navigate("/resources")}
            >
              打开高级资源目录 <ArrowRight size={15} />
            </button>
          </section>
        </div>
      )}
    </>
  );
}
export function PlatformManagement({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const entries = [
    {
      icon: Fingerprint,
      title: "身份与工作空间",
      text: "查看当前身份提供方授予的空间、应用与用户权限。",
      path: "/access",
    },
    {
      icon: Boxes,
      title: "资源与版本",
      text: "高级配置、固定依赖版本、撤权与变更记录。",
      path: "/resources",
    },
    {
      icon: Activity,
      title: "执行账本",
      text: "精确运行状态、模型和工具用量、事件与调用轨迹。",
      path: "/runs",
    },
    {
      icon: ShieldCheck,
      title: "人工请求与异常对账",
      text: "审批具体执行内容、补充输入，并核对结果不确定的调用。",
      path: "/inbox",
    },
  ];
  return (
    <>
      <PageHeading
        eyebrow="PLATFORM MANAGEMENT"
        title="平台管理"
        description={`工作空间 ${project} 的授权、连接和执行治理。管理入口使用当前身份的实际权限。`}
      />
      <div className="manage-grid">
        {entries.map((entry) => (
          <button
            key={entry.path}
            className="manage-card"
            onClick={() => navigate(entry.path)}
          >
            <entry.icon size={25} />
            <div>
              <strong>{entry.title}</strong>
              <p>{entry.text}</p>
            </div>
            <ArrowRight size={17} />
          </button>
        ))}
      </div>
      <section className="studio-section" style={{ marginTop: 24 }}>
        <h3>当前授权</h3>
        <div className="permission-list">
          {principal.permissions.map((p) => (
            <code key={p}>{p}</code>
          ))}
        </div>
        <p style={{ marginTop: 16 }}>
          页面操作在服务端重新核验身份和工作空间权限。账号来源由部署的身份适配器决定。
        </p>
      </section>
    </>
  );
}
