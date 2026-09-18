import { useEffect, useRef, type ReactNode } from "react";
import {
  AlertCircle,
  ArrowUpRight,
  Check,
  ChevronRight,
  Copy,
  Loader2,
  RefreshCw,
  X,
} from "lucide-react";
import { ApiError, errorText } from "./api";

export const labels: Record<string, string> = {
  Agent: "Agent",
  ModelProfile: "模型配置",
  Prompt: "提示词",
  ToolConnection: "工具连接",
  ToolPolicy: "工具策略",
  RetrievalProfile: "检索配置",
  Workflow: "工作流",
  RunPolicy: "运行策略",
  QUEUED: "排队中",
  RUNNING: "运行中",
  COMPLETED: "已完成",
  PAUSED: "已暂停",
  CANCELLED: "已取消",
  FAILED: "失败",
  EXPIRED: "已过期",
  NEEDS_ATTENTION: "需要处理",
  WAITING_APPROVAL: "等待审批",
  WAITING_INPUT: "等待输入",
  STOPPED: "已停止",
  BUDGET_EXCEEDED: "预算耗尽",
  DRAFT: "草稿",
  VALIDATED: "已校验",
  PUBLISHED: "已发布",
  DISABLED: "已停用",
  PENDING: "待处理",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
};
export const formatDate = (value?: string) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "—";
export const short = (value?: string, length = 16) =>
  !value ? "—" : value.length > length ? value.slice(0, length) + "…" : value;
export function Badge({
  value,
  children,
}: {
  value: string;
  children?: ReactNode;
}) {
  return (
    <span className={`badge badge-${value.toLowerCase()}`}>
      <span className="status-dot" />
      {children ?? labels[value] ?? value}
    </span>
  );
}
export function PageHeading({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      <div className="heading-actions">{actions}</div>
    </div>
  );
}
export function ErrorNotice({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: () => void;
}) {
  return (
    <div role="alert" className="notice error">
      <AlertCircle size={19} />
      <div>
        <strong>{errorText(error)}</strong>
        {error instanceof ApiError && (
          <div className="error-meta">
            {error.code}
            {error.requestId && <span> · 请求 {error.requestId}</span>}
            {error.commandKey && <div>操作标识：{error.commandKey}</div>}
          </div>
        )}
      </div>
      {onRetry && (
        <button className="button small" onClick={onRetry}>
          <RefreshCw size={14} />
          重新读取
        </button>
      )}
    </div>
  );
}
export function Notice({
  children,
  tone = "info",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return (
    <div className={`notice ${tone}`}>
      <AlertCircle size={17} />
      <div>{children}</div>
    </div>
  );
}
export function Empty({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <div className="empty-mark">
        <ArrowUpRight size={25} />
      </div>
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  );
}
export function Loading({ label = "正在读取平台数据" }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <Loader2 className="spin" size={21} />
      <span>{label}</span>
    </div>
  );
}
export function JsonView({ value }: { value: unknown }) {
  return <pre className="json-view">{JSON.stringify(value, null, 2)}</pre>;
}
export function Field({
  label,
  hint,
  required,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {required && <i> *</i>}
      </span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}
export function CopyValue({ value, label }: { value: string; label?: string }) {
  return (
    <button
      className="copy-value"
      title="复制完整值"
      onClick={() => navigator.clipboard?.writeText(value)}
    >
      <code>{label ?? short(value, 28)}</code>
      <Copy size={13} />
    </button>
  );
}
export function Modal({
  title,
  description,
  children,
  onClose,
  busy = false,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  onClose: () => void;
  busy?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const current = ref.current;
    current
      ?.querySelector<HTMLElement>("input,textarea,select,button")
      ?.focus();
    function key(event: KeyboardEvent) {
      if (event.key === "Escape" && !busy) onClose();
      if (event.key !== "Tab") return;
      const items = [
        ...(current?.querySelectorAll<HTMLElement>(
          'button:not(:disabled),a[href],input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex="0"]',
        ) ?? []),
      ];
      if (!items.length) return;
      if (event.shiftKey && document.activeElement === items[0]) {
        event.preventDefault();
        items.at(-1)?.focus();
      }
      if (!event.shiftKey && document.activeElement === items.at(-1)) {
        event.preventDefault();
        items[0].focus();
      }
    }
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
      previous?.focus();
    };
  }, [onClose, busy]);
  return (
    <div className="modal-backdrop">
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={ref}
      >
        <div className="modal-heading">
          <div>
            <h2>{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <button
            className="icon-button"
            aria-label="关闭对话框"
            onClick={onClose}
            disabled={busy}
          >
            <X size={20} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
export function Success({ children }: { children: ReactNode }) {
  return (
    <div className="notice success">
      <Check size={18} />
      <div>{children}</div>
    </div>
  );
}
export function Breadcrumb({ items }: { items: string[] }) {
  return (
    <div className="breadcrumb">
      {items.map((item, index) => (
        <span key={index}>
          {index > 0 && <ChevronRight size={12} />}
          {item}
        </span>
      ))}
    </div>
  );
}
