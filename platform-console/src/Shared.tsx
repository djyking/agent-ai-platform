import { useEffect, useState } from "react";
import {
  ArrowLeft,
  BookOpen,
  ChevronRight,
  Link,
  Plus,
  RefreshCw,
  Save,
  Search,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { ApiError, commandKey, projectPath, request, type Reply } from "./api";
import { useRemote } from "./hooks";
import { readPending, persistPending, clearPending } from "./pending-commands";
import type { Page, Principal } from "./types";
import {
  Badge,
  Empty,
  ErrorNotice,
  Field,
  JsonView,
  Loading,
  Notice,
} from "./ui";
const can = (principal: Principal, permission: string) =>
  principal.permissions.includes(permission) ||
  principal.permissions.includes("*");
type Collection = {
  id: string;
  name: string;
  visibility: "PUBLIC" | "PROJECT";
  allowedSubjects: string[];
  disabled: boolean;
  revision: number;
  retrieval: string;
};
type Document = {
  id: string;
  collectionId: string;
  title: string;
  text?: string;
  revoked: boolean;
  digest: string;
  revision: number;
};
type Connection = {
  id: string;
  kind: "model" | "mcp";
  name: string;
  enabled: boolean;
  revision: number;
  adapter: string;
  credentialConfigured: boolean;
  targetApproved: boolean;
  approvedBindingDigest: string;
  diagnostic?: unknown;
  discovery?: unknown;
};
type SharedDocument = Collection | Document | Connection;
function useSharedCommand<T extends SharedDocument>(
  project: string,
  principal: Principal,
  kind: "knowledge" | "capabilities",
  path: string,
  accept: (reply: Reply<T>) => void,
) {
  const scope = `${project}/${principal.application}/${principal.subject}/${path}`;
  const [pending, setPending] = useState(() => readPending(kind, scope, path));
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  async function execute(
    body: unknown,
    etag: string,
    method = "PUT",
    suffix = "",
  ) {
    if (busy || pending || readPending(kind, scope, path)) return;
    setBusy(true);
    setError(undefined);
    const key = commandKey();
    let dispatched = false;
    try {
      const record = { key, resource: path };
      persistPending(kind, scope, record);
      setPending(record);
      dispatched = true;
      const reply = await request<T>(
        `${projectPath(project)}/${kind}${path}${suffix}`,
        { method, body, etag, key },
      );
      clearPending(kind, scope);
      setPending(undefined);
      accept(reply);
      return reply.data;
    } catch (e) {
      setError(e);
      if (
        dispatched &&
        e instanceof ApiError &&
        !e.uncertain &&
        e.status >= 400 &&
        e.status < 500
      ) {
        try {
          clearPending(kind, scope);
          setPending(undefined);
        } catch (storageError) {
          setError(storageError);
        }
      }
    } finally {
      setBusy(false);
    }
  }
  async function recover() {
    if (!pending || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      if (!pending.key)
        throw new Error(
          "原操作标识损坏，请联系管理员核对服务端命令记录，不要重复提交。",
        );
      const receipt = await request<{
        status: string;
        revision: number;
        kind: string;
        id: string;
      }>(
        `${projectPath(project)}/${kind}/commands/${encodeURIComponent(pending.key)}`,
      );
      if (receipt.data.status !== "COMPLETED")
        throw new Error(
          "原操作尚未完成核验，继续阻止提交。请稍后读取原操作结果。",
        );
      const parts = pending.resource
        .split("/")
        .filter(Boolean)
        .map(decodeURIComponent);
      const expectedKind =
        kind === "capabilities"
          ? `connection-${parts[1]}`
          : parts.length === 4
            ? "knowledge-document"
            : "knowledge-collection";
      const expectedId =
        kind === "capabilities"
          ? parts[2]
          : parts.length === 4
            ? `${parts[1]}~${parts[3]}`
            : parts[1];
      if (receipt.data.kind !== expectedKind || receipt.data.id !== expectedId)
        throw new Error("原操作资源不匹配，继续阻止写入。");
      const fresh = await request<T>(
        `${projectPath(project)}/${kind}${pending.resource}`,
      );
      if (fresh.data.revision < receipt.data.revision)
        throw new Error("当前资源仍早于原操作结果，继续等待核验。");
      clearPending(kind, scope);
      setPending(undefined);
      accept(fresh);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return { execute, recover, pending, error, busy, blocked: !!pending };
}
function SharedCommandNotice({
  command,
}: {
  command: ReturnType<typeof useSharedCommand>;
}) {
  return (
    <>
      {Boolean(command.error) && <ErrorNotice error={command.error} />}{" "}
      {command.pending && (
        <div className="studio-error-recovery">
          <Notice tone="warning">
            请求结果尚未确认，已锁定该资源的写入。不会自动重新提交。
          </Notice>
          <button
            className="button"
            disabled={command.busy}
            onClick={command.recover}
          >
            <RefreshCw size={14} />
            读取原操作结果
          </button>
        </div>
      )}
    </>
  );
}
export function KnowledgeManager({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const list = useRemote<Page<Collection>>(
    `${projectPath(project)}/knowledge/collections`,
  );
  const [selected, setSelected] = useState<string>();
  const [newCollection, setNewCollection] = useState(false);
  if (selected)
    return (
      <CollectionWorkspace
        key={selected}
        id={selected}
        project={project}
        principal={principal}
        isNew={newCollection}
        onBack={() => {
          setSelected(undefined);
          setNewCollection(false);
          list.reload();
        }}
      />
    );
  return (
    <>
      <div className="section-title large">
        <div>
          <h2>知识集合</h2>
          <p className="muted">
            管理文本资料、可见范围和撤销；应用预览与发布固定使用的资料版本。
          </p>
        </div>
        <button
          className="button primary"
          disabled={!can(principal, "catalog:publish")}
          onClick={() => {
            setNewCollection(true);
            setSelected(`knowledge-${crypto.randomUUID()}`);
          }}
        >
          <Plus size={15} />
          创建知识集合
        </button>
      </div>
      {list.loading ? (
        <Loading />
      ) : list.error ? (
        <ErrorNotice error={list.error} onRetry={list.reload} />
      ) : list.data?.items.length ? (
        <div className="app-grid">
          {list.data.items.map((k) => (
            <button
              className="manage-card"
              key={k.id}
              onClick={() => setSelected(k.id)}
            >
              <BookOpen size={24} />
              <div>
                <strong>{k.name}</strong>
                <p>
                  {k.visibility === "PUBLIC"
                    ? "空间内公开资料"
                    : "当前授权主体"}{" "}
                  · {k.disabled ? "已停用" : "可用"}
                </p>
                <small className="muted">文本词法检索 · v{k.revision}</small>
              </div>
              <ChevronRight size={16} />
            </button>
          ))}
        </div>
      ) : (
        <Empty
          title="还没有知识集合"
          description="创建集合后添加公开或已授权的文本资料，助手可以引用同一份内容。"
        />
      )}
    </>
  );
}
function CollectionWorkspace({
  id,
  project,
  principal,
  isNew,
  onBack,
}: {
  id: string;
  project: string;
  principal: Principal;
  isNew: boolean;
  onBack: () => void;
}) {
  const path = `/collections/${encodeURIComponent(id)}`;
  const remote = useRemote<Collection>(
    isNew ? null : `${projectPath(project)}/knowledge${path}`,
  );
  const [collection, setCollection] = useState<Collection>(() => ({
    id,
    name: "",
    visibility: "PROJECT",
    allowedSubjects: [principal.subject],
    disabled: false,
    revision: 0,
    retrieval: "LEXICAL_TEXT_V1",
  }));
  const [etag, setEtag] = useState('"k0"');
  const [dirty, setDirty] = useState(false);
  const [document, setDocument] = useState<{ id: string; isNew: boolean }>();
  const [query, setQuery] = useState("");
  const [answer, setAnswer] = useState<{
    context?: string;
    citations?: {
      sourceId?: string;
      title?: string;
      documentId?: string;
      collectionId?: string;
      version?: number;
    }[];
    truncated?: boolean;
  }>();
  const [queryError, setQueryError] = useState<unknown>();
  const [queryBusy, setQueryBusy] = useState(false);
  const documents = useRemote<Page<Document>>(
    collection.revision
      ? `${projectPath(project)}/knowledge${path}/documents`
      : null,
  );
  const accept = (reply: Reply<Collection>) => {
    setCollection(reply.data);
    setEtag(reply.etag ?? `"k${reply.data.revision}"`);
    setDirty(false);
    documents.reload();
  };
  useEffect(() => {
    if (remote.data)
      accept({ data: remote.data, etag: remote.etag ?? null, requestId: null });
  }, [remote.data, remote.etag]);
  const command = useSharedCommand<Collection>(
    project,
    principal,
    "knowledge",
    path,
    accept,
  );
  const disabled =
    command.busy || command.blocked || !can(principal, "catalog:publish");
  function change(value: Partial<Collection>) {
    setCollection({ ...collection, ...value });
    setDirty(true);
  }
  async function search() {
    setQueryBusy(true);
    setQueryError(undefined);
    setAnswer(undefined);
    try {
      const result = await request<NonNullable<typeof answer>>(
        `${projectPath(project)}/knowledge/query`,
        {
          method: "POST",
          body: { collections: [id], query },
        },
      );
      setAnswer(result.data);
    } catch (e) {
      setQueryError(e);
    } finally {
      setQueryBusy(false);
    }
  }
  if (remote.loading && !isNew) return <Loading />;
  if (remote.error)
    return <ErrorNotice error={remote.error} onRetry={remote.reload} />;
  return (
    <>
      <button
        className="back-link"
        disabled={command.busy || command.blocked}
        onClick={onBack}
      >
        <ArrowLeft size={15} />
        知识集合
      </button>
      <div className="section-title large">
        <h2>{collection.name || "创建知识集合"}</h2>
        <Badge value={collection.disabled ? "DISABLED" : "DRAFT"}>
          {collection.revision ? `修订 ${collection.revision}` : "尚未保存"}
        </Badge>
      </div>
      <SharedCommandNotice command={command} />
      <div className="studio-layout">
        <section className="studio-editor">
          <h3>集合与访问范围</h3>
          <Field label="集合名称" required>
            <input
              disabled={disabled}
              value={collection.name}
              onChange={(e) => change({ name: e.target.value })}
            />
          </Field>
          <Field
            label="资料可见范围"
            hint="PROJECT 集合不允许改为 PUBLIC，避免将受保护资料转为公开。"
          >
            <select
              disabled={
                disabled ||
                (collection.revision > 0 && collection.visibility === "PROJECT")
              }
              value={collection.visibility}
              onChange={(e) =>
                change({ visibility: e.target.value as "PUBLIC" | "PROJECT" })
              }
            >
              <option value="PROJECT">指定授权主体</option>
              <option value="PUBLIC">空间内公开资料</option>
            </select>
          </Field>
          {collection.visibility === "PROJECT" && (
            <Field
              label="允许访问的主体"
              hint="每行一个用户主体 ID；留空表示所有已获项目访问权的主体。"
            >
              <textarea
                rows={4}
                disabled={disabled}
                value={collection.allowedSubjects.join("\n")}
                onChange={(e) =>
                  change({ allowedSubjects: e.target.value.split("\n") })
                }
              />
            </Field>
          )}
          <label className="checkbox-label">
            <input
              type="checkbox"
              disabled={disabled}
              checked={collection.disabled}
              onChange={(e) => change({ disabled: e.target.checked })}
            />
            停用集合，新读取与检索受服务端策略约束
          </label>
          <button
            className="button primary"
            disabled={
              disabled ||
              !collection.name.trim() ||
              (!dirty && collection.revision > 0)
            }
            onClick={() =>
              command.execute(
                {
                  name: collection.name,
                  visibility: collection.visibility,
                  allowedSubjects:
                    collection.visibility === "PUBLIC"
                      ? []
                      : collection.allowedSubjects
                          .map((x) => x.trim())
                          .filter(Boolean),
                  disabled: collection.disabled,
                },
                etag,
              )
            }
          >
            <Save size={15} />
            保存集合
          </button>
        </section>
        <section className="studio-preview">
          <h3>检索测试</h3>
          <p className="muted">
            真实词法检索，不调用模型。此处显示的来源仍受当前权限控制。
          </p>
          <Field label="检索问题">
            <textarea
              rows={3}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </Field>
          <button
            className="button"
            disabled={
              !collection.revision || dirty || queryBusy || !query.trim()
            }
            onClick={search}
          >
            <Search size={15} />
            {queryBusy ? "正在检索…" : "测试当前资料"}
          </button>
          {Boolean(queryError) && <ErrorNotice error={queryError} />}{" "}
          {answer !== undefined && (
            <div className="studio-response">
              <p>{answer.context || "没有找到匹配资料。"}</p>
              {answer.citations?.map((citation, index) => (
                <div className="citation" key={citation.sourceId ?? index}>
                  <span>{index + 1}</span>
                  <div>
                    <strong>
                      {citation.title || citation.documentId || "知识来源"}
                    </strong>
                    <small>
                      {citation.collectionId} · 版本 {citation.version ?? "—"}
                    </small>
                  </div>
                </div>
              ))}
              {answer.truncated && (
                <p className="muted">结果受当前检索长度上限截断。</p>
              )}
            </div>
          )}
        </section>
      </div>
      {collection.revision > 0 && (
        <section className="studio-section" style={{ marginTop: 24 }}>
          <div className="section-title">
            <h3>资料文档</h3>
            <button
              className="button small"
              disabled={!can(principal, "catalog:write")}
              onClick={() =>
                setDocument({
                  id: `document-${crypto.randomUUID()}`,
                  isNew: true,
                })
              }
            >
              <Plus size={15} />
              新增文本资料
            </button>
          </div>
          {documents.loading ? (
            <Loading />
          ) : documents.error ? (
            <ErrorNotice error={documents.error} onRetry={documents.reload} />
          ) : documents.data?.items.length ? (
            documents.data.items.map((d) => (
              <div className="capability-row" key={d.id}>
                <div>
                  <strong>{d.title}</strong>
                  <small>
                    v{d.revision} · {d.revoked ? "已永久撤销" : "有效资料"}
                  </small>
                </div>
                <button
                  className="button small"
                  onClick={() => setDocument({ id: d.id, isNew: false })}
                >
                  查看资料 <ChevronRight size={14} />
                </button>
              </div>
            ))
          ) : (
            <Empty
              title="尚未添加资料"
              description="可直接粘贴文本并保存版本。不会虚构文件解析、向量索引或搜索质量。"
            />
          )}
        </section>
      )}
      {document && (
        <DocumentEditor
          key={document.id}
          project={project}
          principal={principal}
          collectionId={id}
          id={document.id}
          isNew={document.isNew}
          onClose={() => {
            setDocument(undefined);
            documents.reload();
          }}
        />
      )}
    </>
  );
}
function DocumentEditor({
  project,
  principal,
  collectionId,
  id,
  isNew,
  onClose,
}: {
  project: string;
  principal: Principal;
  collectionId: string;
  id: string;
  isNew: boolean;
  onClose: () => void;
}) {
  const path = `/collections/${encodeURIComponent(collectionId)}/documents/${encodeURIComponent(id)}`;
  const remote = useRemote<Document>(
    isNew ? null : `${projectPath(project)}/knowledge${path}`,
  );
  const [document, setDocument] = useState<Document>({
    id,
    collectionId,
    title: "",
    text: "",
    revoked: false,
    revision: 0,
    digest: "",
  });
  const [etag, setEtag] = useState('"k0"');
  const [confirmed, setConfirmed] = useState(false);
  const accept = (reply: Reply<Document>) => {
    setDocument(reply.data);
    setEtag(reply.etag ?? `"k${reply.data.revision}"`);
    setConfirmed(false);
  };
  useEffect(() => {
    if (remote.data)
      accept({ data: remote.data, etag: remote.etag ?? null, requestId: null });
  }, [remote.data, remote.etag]);
  const command = useSharedCommand<Document>(
    project,
    principal,
    "knowledge",
    path,
    accept,
  );
  const disabled =
    command.busy ||
    command.blocked ||
    document.revoked ||
    !can(principal, "catalog:write");
  return (
    <section className="studio-section">
      <div className="section-title">
        <h3>{isNew ? "新增文本资料" : "编辑资料"}</h3>
        <button
          className="button small"
          disabled={command.busy || command.blocked}
          onClick={onClose}
        >
          收起
        </button>
      </div>
      {remote.loading && !isNew ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        <>
          <SharedCommandNotice command={command} />
          {document.revoked ? (
            <Notice>
              这份资料已经永久撤销。此处只保留审计元数据，不显示已撤销正文。
            </Notice>
          ) : (
            <>
              <Field label="资料标题" required>
                <input
                  disabled={disabled}
                  value={document.title}
                  onChange={(e) =>
                    setDocument({ ...document, title: e.target.value })
                  }
                />
              </Field>
              <Field label="资料正文" required>
                <textarea
                  rows={12}
                  disabled={disabled}
                  value={document.text ?? ""}
                  onChange={(e) =>
                    setDocument({ ...document, text: e.target.value })
                  }
                />
              </Field>
              <div className="studio-row">
                <button
                  className="button primary"
                  disabled={
                    disabled || !document.title.trim() || !document.text?.trim()
                  }
                  onClick={() =>
                    command.execute(
                      {
                        title: document.title,
                        text: document.text,
                        revoked: false,
                      },
                      etag,
                    )
                  }
                >
                  <Save size={15} />
                  保存资料版本
                </button>
                {document.revision > 0 && (
                  <>
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        disabled={disabled}
                        checked={confirmed}
                        onChange={(e) => setConfirmed(e.target.checked)}
                      />
                      确认永久撤销此资料
                    </label>
                    <button
                      className="button danger-outline"
                      disabled={disabled || !confirmed}
                      onClick={() =>
                        command.execute(
                          {
                            title: document.title,
                            text: document.text,
                            revoked: true,
                          },
                          etag,
                        )
                      }
                    >
                      <Trash2 size={15} />
                      撤销资料
                    </button>
                  </>
                )}
              </div>
            </>
          )}
          <div className="studio-meta">
            资料 {id} · 修订 {document.revision} · {document.digest}
          </div>
        </>
      )}
    </section>
  );
}
export function ConnectionManager({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const remote = useRemote<Page<Connection> & { targetPolicy?: unknown }>(
    `${projectPath(project)}/capabilities/connections`,
  );
  return (
    <>
      <div className="section-title large">
        <div>
          <h2>受信连接与诊断</h2>
          <p className="muted">
            管理部署审核过的绑定。连接测试与工具发现不会自动授予执行权限。
          </p>
        </div>
        <button className="button" onClick={remote.reload}>
          <RefreshCw size={15} />
          刷新
        </button>
      </div>
      <Notice>
        此界面不接受任意目标地址或凭据。新的提供方先经过部署审核；凭据状态仅显示是否配置。
      </Notice>
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : remote.data?.items.length ? (
        <div className="capability-grid">
          {remote.data.items.map((c) => (
            <ConnectionEditor
              key={`${c.kind}/${c.id}`}
              project={project}
              principal={principal}
              initial={c}
            />
          ))}
        </div>
      ) : (
        <Empty
          title="没有已登记连接"
          description="请先在受信部署配置中登记模型或 MCP 绑定。"
        />
      )}
    </>
  );
}
function ConnectionEditor({
  project,
  principal,
  initial,
}: {
  project: string;
  principal: Principal;
  initial: Connection;
}) {
  const path = `/connections/${initial.kind}/${encodeURIComponent(initial.id)}`;
  const [connection, setConnection] = useState(initial);
  const [name, setName] = useState(initial.name);
  const [etag, setEtag] = useState(`"k${initial.revision}"`);
  const command = useSharedCommand<Connection>(
    project,
    principal,
    "capabilities",
    path,
    (reply) => {
      setConnection((previous) => ({ ...previous, ...reply.data }));
      setName(reply.data.name);
      setEtag(reply.etag ?? `"k${reply.data.revision}"`);
    },
  );
  return (
    <section className="capability-card">
      <div className="capability-card-header">
        <Link size={22} />
        <h3>{connection.name}</h3>
        <Badge value={connection.enabled ? "PUBLISHED" : "DISABLED"}>
          {connection.enabled ? "已启用" : "已停用"}
        </Badge>
      </div>
      <div className="studio-meta">
        {connection.kind} · {connection.adapter} · {connection.id}
      </div>
      <div className="studio-step">
        <ShieldCheck size={15} />
        {connection.targetApproved ? "目标已审核" : "目标未通过审核"} ·{" "}
        {connection.credentialConfigured ? "凭据已绑定" : "无凭据绑定"}
      </div>
      <Field label={`连接名称 ${connection.id}`}>
        <input
          value={name}
          disabled={
            command.busy ||
            command.blocked ||
            !can(principal, "catalog:publish")
          }
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      <SharedCommandNotice command={command} />
      <div className="studio-row">
        <button
          className="button small"
          disabled={
            command.busy ||
            command.blocked ||
            !can(principal, "catalog:publish") ||
            !name.trim()
          }
          onClick={() =>
            command.execute({ name, enabled: connection.enabled }, etag)
          }
        >
          <Save size={14} />
          保存名称
        </button>
        <button
          className="button small"
          disabled={
            command.busy ||
            command.blocked ||
            !can(principal, "catalog:publish")
          }
          onClick={() =>
            command.execute({ name, enabled: !connection.enabled }, etag)
          }
        >
          {connection.enabled ? "停用连接" : "启用连接"}
        </button>
        <button
          className="button small"
          disabled={
            command.busy ||
            command.blocked ||
            !can(principal, "catalog:validate")
          }
          onClick={() => command.execute({}, etag, "POST", "/diagnose")}
        >
          <RefreshCw size={14} />
          {connection.kind === "model" ? "测试模型目录" : "发现与比较工具"}
        </button>
      </div>
      {connection.diagnostic !== undefined && (
        <details style={{ marginTop: 17 }} open>
          <summary>最近诊断 · 不代表模型生成或工具执行通过</summary>
          <JsonView value={connection.diagnostic} />
        </details>
      )}
      {connection.discovery !== undefined && (
        <details>
          <summary>工具发现与契约差异</summary>
          <JsonView value={connection.discovery} />
        </details>
      )}
    </section>
  );
}
