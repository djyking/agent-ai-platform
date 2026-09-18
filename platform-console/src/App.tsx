import { useEffect, useState, type FormEvent } from "react";
import {
  ArrowRight,
  Blocks,
  Bot,
  ChevronDown,
  CircleHelp,
  FileCode2,
  Fingerprint,
  FolderKanban,
  GitBranch,
  KeyRound,
  LockKeyhole,
  LogOut,
  Menu,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { request, setSessionToken } from "./api";
import { navigate, useHashRoute } from "./hooks";
import { CatalogList, ResourceEditor } from "./Catalog";
import { RunDetail, RunList } from "./Runs";
import {
  ApplicationHome,
  ApplicationStudio,
  SharedCapabilities,
  TaskWorkspace,
  PlatformManagement,
} from "./Studio";
import type { ResourceType, Session } from "./types";
import { RESOURCE_TYPES } from "./types";
import {
  Breadcrumb,
  Empty,
  ErrorNotice,
  Field,
  Loading,
  Modal,
  Notice,
  PageHeading,
} from "./ui";

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand-dark" : ""}`}>
      <span className="brand-symbol">
        <span />
        <span />
        <span />
      </span>
      <strong>
        harness<span> studio</span>
      </strong>
      <span className="brand-caption">BUILD · RUN · DELIVER</span>
    </div>
  );
}

export default function App() {
  const [session, setSession] = useState<Session>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [identity, setIdentity] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  const [switching, setSwitching] = useState(false);
  const route = useHashRoute();
  function accept(next: Session) {
    setSessionToken(next);
    setSession(next);
    setError(undefined);
  }
  async function load() {
    setLoading(true);
    try {
      accept((await request<Session>("", { session: true })).data);
    } catch (failure) {
      setError(failure);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    setMobileNav(false);
  }, [route]);
  async function logout() {
    try {
      await request("", { session: true, method: "DELETE" });
      accept({ authenticated: false });
      navigate("/");
    } catch (failure) {
      setError(failure);
    }
  }
  async function switchProject(projectId: string) {
    setSwitching(true);
    try {
      accept(
        (
          await request<Session>("", {
            session: true,
            method: "POST",
            body: { projectId },
          })
        ).data,
      );
      navigate("/");
    } catch (failure) {
      setError(failure);
    } finally {
      setSwitching(false);
    }
  }
  if (loading)
    return (
      <div className="boot-screen">
        <Brand compact />
        <Loading label="正在验证平台会话" />
      </div>
    );
  if (!session?.authenticated || !session.principal)
    return <Login onLogin={accept} initialError={error} />;
  const principal = session.principal;
  const project = principal.project;
  const projects = session.projects ?? [{ id: project }];
  const parts = route.split("?")[0].split("/").filter(Boolean);
  const nav = [
    ["/", "应用", Bot],
    ["/capabilities", "共享能力", Blocks],
    ["/tasks", "任务工作台", FolderKanban],
    ["/manage", "平台管理", ShieldCheck],
  ] as const;
  const active = ["apps", "agents"].includes(parts[0] ?? "")
    ? "/"
    : ["resources", "runs", "inbox", "access"].includes(parts[0] ?? "")
      ? "/manage"
      : `/${parts[0] ?? ""}`;
  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileNav ? "is-open" : ""}`}>
        <Brand />
        <div className="workspace-select">
          <span className="workspace-avatar">H</span>
          <div>
            <strong>Agent 工作空间</strong>
            <span>构建 · 体验 · 发布</span>
          </div>
          <ChevronDown size={15} />
        </div>
        <span className="nav-label">WORKSPACE</span>
        <nav aria-label="主导航">
          {nav.map(([path, name, Icon]) => (
            <a
              href={`#${path}`}
              key={path}
              className={active === path ? "active" : ""}
            >
              <Icon size={19} />
              <span>{name}</span>
            </a>
          ))}
        </nav>
        <div className="sidebar-note">
          <GitBranch size={19} />
          <strong>从想法到可用的助手</strong>
          <p>组合获批能力，在同一个工作台里试用、评测和交付。</p>
        </div>
        <div className="sidebar-bottom">
          <button onClick={() => setIdentity(true)}>
            <Fingerprint size={18} />
            <span>身份与项目授权</span>
            <ChevronDown size={13} />
          </button>
          <span>
            <span className="live-dot" />
            连接当前平台会话
          </span>
        </div>
      </aside>
      {mobileNav && (
        <button
          className="nav-overlay"
          aria-label="收起导航"
          onClick={() => setMobileNav(false)}
        />
      )}
      <div className="main-shell">
        <header className="topbar">
          <div className="topbar-left">
            <button
              className="icon-button mobile-menu"
              aria-label="打开导航"
              onClick={() => setMobileNav(true)}
            >
              <Menu size={19} />
            </button>
            <Breadcrumb
              items={[
                "工作空间",
                nav.find(([path]) => path === active)?.[1] ?? "资源详情",
              ]}
            />
          </div>
          <div className="topbar-right">
            <label className="project-picker">
              <FolderKanban size={16} />
              <select
                aria-label="当前项目"
                value={project}
                disabled={switching}
                onChange={(event) => switchProject(event.target.value)}
              >
                {projects.map((item) => {
                  const id = typeof item === "string" ? item : item.id;
                  return (
                    <option value={id} key={id}>
                      {id}
                    </option>
                  );
                })}
              </select>
              <ChevronDown size={12} />
            </label>
            <span className="header-separator" />
            <button
              className="icon-button"
              aria-label="查看当前身份"
              onClick={() => setIdentity(true)}
            >
              <CircleHelp size={19} />
            </button>
            <button
              className="user-avatar"
              title={`当前主体 ${principal.subject}`}
              onClick={() => setIdentity(true)}
            >
              {principal.subject.slice(0, 2).toUpperCase()}
            </button>
          </div>
        </header>
        <main className="workspace-content" key={project}>
          {Boolean(error) && (
            <ErrorNotice error={error} onRetry={() => setError(undefined)} />
          )}
          {route === "/" || route === "/agents" ? (
            <ApplicationHome project={project} principal={principal} />
          ) : parts[0] === "apps" && parts[1] ? (
            <ApplicationStudio
              key={route}
              project={project}
              principal={principal}
              id={decodeURIComponent(parts[1])}
            />
          ) : route === "/capabilities" ? (
            <SharedCapabilities project={project} principal={principal} />
          ) : parts[0] === "tasks" ? (
            <TaskWorkspace
              key={route}
              project={project}
              principal={principal}
              id={parts[1] ? decodeURIComponent(parts[1]) : undefined}
            />
          ) : route === "/manage" ? (
            <PlatformManagement project={project} principal={principal} />
          ) : route === "/resources" ? (
            <CatalogList project={project} />
          ) : parts[0] === "resources" &&
            parts[1] === "new" &&
            RESOURCE_TYPES.includes(parts[2] as ResourceType) ? (
            <ResourceEditor
              key={route}
              project={project}
              type={parts[2] as ResourceType}
              principal={principal}
            />
          ) : parts[0] === "resources" &&
            RESOURCE_TYPES.includes(parts[1] as ResourceType) &&
            parts[2] ? (
            <ResourceEditor
              key={route}
              project={project}
              type={parts[1] as ResourceType}
              id={decodeURIComponent(parts[2])}
              principal={principal}
            />
          ) : route === "/runs" ? (
            <RunList project={project} principal={principal} />
          ) : parts[0] === "runs" && parts[1] ? (
            <RunDetail
              key={route}
              project={project}
              id={decodeURIComponent(parts[1])}
              principal={principal}
            />
          ) : route === "/inbox" ? (
            <RunList project={project} principal={principal} inbox />
          ) : route === "/access" ? (
            <AccessPage
              session={session}
              switching={switching}
              onSwitch={switchProject}
            />
          ) : (
            <Empty
              title="页面不存在"
              action={
                <button className="button" onClick={() => navigate("/")}>
                  返回应用
                </button>
              }
            />
          )}
        </main>
        <footer className="workspace-footer">
          <span>Harness Agent Platform</span>
          <span>构建应用 → 立即体验 → 评测发布 → 任务交付</span>
        </footer>
      </div>
      {identity && (
        <Modal
          title="身份与项目授权"
          description="会话复用现有账号体系；权限在每次服务端请求时重新核验。"
          onClose={() => setIdentity(false)}
        >
          <div className="review-summary">
            <div>
              <span>应用</span>
              <strong>{principal.application}</strong>
            </div>
            <div>
              <span>项目</span>
              <strong>{principal.project}</strong>
            </div>
            <div>
              <span>有效主体</span>
              <strong>{principal.subject}</strong>
            </div>
            <div>
              <span>会话方式</span>
              <strong>服务端 HttpOnly 会话</strong>
            </div>
          </div>
          <h3>当前授权</h3>
          <div className="permission-list">
            {principal.permissions.map((permission) => (
              <code key={permission}>{permission}</code>
            ))}
          </div>
          <Notice>
            账号令牌与应用凭据不会写入浏览器本地存储。切换项目需要服务端重新验证项目访问权。
          </Notice>
          <div className="modal-actions">
            <button className="button" onClick={() => setIdentity(false)}>
              关闭
            </button>
            <button
              className="button danger-outline"
              onClick={() => {
                setIdentity(false);
                void logout();
              }}
            >
              <LogOut size={16} />
              退出会话
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function Login({
  onLogin,
  initialError,
}: {
  onLogin: (session: Session) => void;
  initialError?: unknown;
}) {
  const [project, setProject] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState("");
  const [captchaCode, setCaptchaCode] = useState("");
  const [captcha, setCaptcha] = useState<{
    captchaId?: string;
    imageDataUrl?: string;
    expiresInSeconds?: number;
    required?: boolean;
    provider?: string;
    notice?: string;
  }>();
  const [captchaError, setCaptchaError] = useState<unknown>();
  const [mode, setMode] = useState<"password" | "token">("password");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError);
  async function refreshCaptcha() {
    setCaptcha(undefined);
    setCaptchaError(undefined);
    setCaptchaCode("");
    try {
      const reply = await request<{
        captchaId?: string;
        imageDataUrl?: string;
        expiresInSeconds?: number;
        required?: boolean;
        provider?: string;
        notice?: string;
      }>("/auth/captcha", { consoleEndpoint: true });
      if (
        reply.data.required !== false &&
        !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(
          reply.data.imageDataUrl ?? "",
        )
      )
        throw new Error("验证码响应格式无效。");
      setCaptcha(reply.data);
    } catch (failure) {
      setCaptchaError(failure);
    }
  }
  useEffect(() => {
    if (mode === "password") void refreshCaptcha();
  }, [mode]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const reply =
        mode === "token"
          ? await request<Session>("", {
              session: true,
              method: "POST",
              body: {
                ...(project.trim() ? { projectId: project.trim() } : {}),
                userToken: token.trim(),
              },
            })
          : await request<Session>("/login", {
              consoleEndpoint: true,
              method: "POST",
              body: {
                ...(project.trim() ? { projectId: project.trim() } : {}),
                username,
                password,
                captchaId: captcha?.captchaId,
                captchaCode,
              },
            });
      onLogin(reply.data);
    } catch (failure) {
      setError(failure);
      if (mode === "password") void refreshCaptcha();
    } finally {
      setBusy(false);
      setPassword("");
      setToken("");
    }
  }
  return (
    <div className="login-shell">
      <div className="login-story">
        <Brand />
        <div className="login-story-main">
          <span className="eyebrow">TURN IDEAS INTO WORKING AGENTS</span>
          <h1>
            把一个想法，
            <br />
            变成可用的 Agent。
          </h1>
          <p>
            组合模型、知识和工具，
            <br />
            在同一处构建、体验和交付。
          </p>
          <div className="login-diagram">
            <div className="diagram-line" />
            <span>
              <FileCode2 size={24} />
              <strong>构建</strong>
              <small>组合能力</small>
            </span>
            <span>
              <GitBranch size={24} />
              <strong>体验</strong>
              <small>立即试用</small>
            </span>
            <span>
              <ShieldCheck size={24} />
              <strong>交付</strong>
              <small>评测发布</small>
            </span>
          </div>
        </div>
        <div className="login-story-footer">
          <span className="live-dot" /> Harness · Agent 应用平台
        </div>
      </div>
      <div className="login-form-area">
        <div className="login-form">
          <span className="login-icon">
            <Fingerprint size={29} />
          </span>
          <span className="eyebrow">WELCOME TO YOUR WORKSPACE</span>
          <h2>登录应用工作台</h2>
          <p>使用已接入的身份，进入你有权访问的工作空间。</p>
          <form onSubmit={submit}>
            <details className="login-workspace-option">
              <summary>指定工作空间（可选）</summary>
              <Field
                label="工作空间标识"
                hint="留空自动进入获授权的工作空间，登录后可切换。"
              >
                <input
                  autoComplete="off"
                  value={project}
                  onChange={(event) => setProject(event.target.value)}
                />
              </Field>
            </details>
            {mode === "password" ? (
              <>
                <Field label="账号" required>
                  <input
                    required
                    autoComplete="username"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                    placeholder="已有账号名称"
                  />
                </Field>
                <Field label="密码" required>
                  <input
                    required
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                </Field>
                {captcha?.required === false ? (
                  <Notice>
                    {captcha.notice ?? "当前使用本机隔离验收身份。"}
                  </Notice>
                ) : (
                  <Field label="图形验证码" required>
                    <div className="captcha-field">
                      <input
                        required
                        value={captchaCode}
                        onChange={(event) => setCaptchaCode(event.target.value)}
                        autoComplete="off"
                      />
                      {captcha ? (
                        <button
                          type="button"
                          aria-label="刷新验证码"
                          className="captcha-image"
                          onClick={refreshCaptcha}
                        >
                          <img src={captcha.imageDataUrl} alt="登录验证码" />
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="button"
                          onClick={refreshCaptcha}
                        >
                          <RefreshCw size={16} />
                          刷新
                        </button>
                      )}
                    </div>
                  </Field>
                )}
                {Boolean(captchaError) && (
                  <ErrorNotice error={captchaError} onRetry={refreshCaptcha} />
                )}
              </>
            ) : (
              <>
                <Notice>
                  使用已有账号的有效访问令牌换取会话。令牌仅随本次请求发送，不会保留在本地存储。
                </Notice>
                <Field label="现有用户访问令牌" required>
                  <textarea
                    required
                    className="token-input"
                    rows={4}
                    autoComplete="off"
                    spellCheck={false}
                    value={token}
                    onChange={(event) => setToken(event.target.value)}
                    placeholder="粘贴现有账号的 JWT"
                  />
                </Field>
              </>
            )}
            {Boolean(error) && <ErrorNotice error={error} />}
            <button
              className="button primary full login-submit"
              disabled={busy || (mode === "password" && !captcha)}
              type="submit"
            >
              {busy ? "正在验证…" : "进入工作空间"}
              <ArrowRight size={18} />
            </button>
          </form>
          <button
            className="text-button advanced-login"
            onClick={() => {
              setMode(mode === "password" ? "token" : "password");
              setError(undefined);
              setToken("");
              setPassword("");
            }}
          >
            <KeyRound size={14} />
            {mode === "password"
              ? "高级接入：使用已有访问令牌"
              : "返回账号密码登录"}
          </button>
          <div className="login-security">
            <LockKeyhole size={14} />
            现有身份体系 · 项目权限实时验证
          </div>
        </div>
      </div>
    </div>
  );
}

function AccessPage({
  session,
  switching,
  onSwitch,
}: {
  session: Session;
  switching: boolean;
  onSwitch: (project: string) => void;
}) {
  const principal = session.principal!;
  const groups = [
    {
      label: "资源配置与发布",
      prefix: "catalog:",
      description: "读取、编辑、校验及管理已发布的精确版本。",
    },
    {
      label: "运行与结果访问",
      prefix: "runs:",
      description: "创建、读取与控制运行；结果可见性还受业务域当前权限约束。",
    },
    {
      label: "审批与人工决定",
      prefix: "approvals:",
      description: "具体审批还要求属于该运行的指定审批身份。",
    },
    {
      label: "工具与领域能力",
      prefix: "",
      description:
        "资源声明不自动授予能力；执行前仍取当前权限与运行上限的交集。",
    },
  ];
  return (
    <>
      <PageHeading
        eyebrow="IDENTITY & PROJECT ACCESS"
        title="项目与权限"
        description="查看当前身份、接入应用与工作空间的生效授权。"
      />
      <div className="access-identity-grid">
        {[
          ["当前项目", principal.project, FolderKanban],
          ["应用身份", principal.application, Blocks],
          ["有效用户主体", principal.subject, Fingerprint],
        ].map(([label, value, Icon]) => {
          const Symbol = Icon as typeof Fingerprint;
          return (
            <div className="panel identity-card" key={String(label)}>
              <Symbol size={22} />
              <span>{String(label)}</span>
              <strong>{String(value)}</strong>
            </div>
          );
        })}
      </div>
      <Notice>
        授权以每次请求的服务端核验为准，界面按钮只是操作提示。账号、角色与工作空间授权由当前接入的身份提供方维护。
      </Notice>
      <div className="panel access-projects">
        <div className="panel-heading">
          <h3>可访问项目</h3>
          <span className="muted">切换将重新核验身份并更新会话</span>
        </div>
        <div className="project-cards">
          {session.projects?.map((item) => {
            const id = typeof item === "string" ? item : item.id;
            return (
              <button
                className={`project-card ${id === principal.project ? "selected" : ""}`}
                key={id}
                disabled={switching || id === principal.project}
                onClick={() => onSwitch(id)}
              >
                <FolderKanban size={20} />
                <strong>{id}</strong>
                <span>
                  {id === principal.project ? "当前项目" : "切换项目"}
                </span>
                <ArrowRight size={16} />
              </button>
            );
          })}
        </div>
      </div>
      <div className="section-title large">
        <h2>当前生效授权</h2>
        <span className="muted">{principal.permissions.length} 项精确权限</span>
      </div>
      <div className="permission-grid">
        {groups.map((group) => {
          const permissions = principal.permissions.filter((permission) =>
            group.prefix
              ? permission.startsWith(group.prefix)
              : !["catalog:", "runs:", "approvals:"].some((prefix) =>
                  permission.startsWith(prefix),
                ),
          );
          return (
            <div className="panel permission-card" key={group.label}>
              <h3>{group.label}</h3>
              <p>{group.description}</p>
              <div className="permission-list">
                {permissions.length ? (
                  permissions.map((permission) => (
                    <code key={permission}>{permission}</code>
                  ))
                ) : (
                  <span className="muted">当前身份未获此类授权</span>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <div className="access-boundary">
        <ShieldCheck size={20} />
        <div>
          <h3>执行和审批的权限边界</h3>
          <p>
            跨项目、跨应用或跨用户访问不会仅凭资源标识获得授权。发布只能引用已登记的模型与工具；审批必须匹配精确参数摘要；UNKNOWN
            对账必须引用已经独立验证的证据。
          </p>
        </div>
      </div>
    </>
  );
}
