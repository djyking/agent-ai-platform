import { useRef, useState } from "react";
import { ClipboardCheck, RefreshCw } from "lucide-react";
import { ApiError, commandKey, projectPath, request } from "./api";
import { useRemote } from "./hooks";
import { clearPending, persistPending, readPending } from "./pending-commands";
import { Empty, ErrorNotice, Loading, Notice, PageHeading } from "./ui";
import type { Principal } from "./types";

type Entry = {
  id: string;
  revision: number;
  targetProject: string;
  applicationId: string;
  name: string;
  purpose: string;
  profile: string;
  requester: string;
  subject: string;
  state: string;
  generation: number;
  plan?: { digest: string; permissions: string[] };
  provisioned?: { identityDisabled?: boolean };
  audit?: { actor: string; operation: string; at: number }[];
};
const states: Record<string, string> = {
  REQUESTED: "等待审批",
  APPROVED: "已批准，等待配置",
  REJECTED: "已拒绝",
  PROVISIONED: "配置已准备，等待生效验证",
  ACTIVE: "已启用",
  ROTATION_APPROVED: "凭据轮换中，调用已暂停",
  DISABLED: "已停用",
};
const profiles: Record<string, string> = {
  assistant: "助手调用",
  "knowledge-reader": "知识问答（含 OpsAgent 检索）",
  builder: "应用构建与发布",
};

export function Onboarding({
  project,
  principal,
}: {
  project: string;
  principal: Principal;
}) {
  const base = projectPath(project) + "/onboarding";
  const scope = `${project}/${principal.application}/${principal.subject}`;
  const [cursor, setCursor] = useState("");
  const remote = useRemote<{ items: Entry[]; nextCursor?: string }>(
    base + "?limit=50&after=" + encodeURIComponent(cursor),
  );
  const [name, setName] = useState("");
  const [application, setApplication] = useState("");
  const [target, setTarget] = useState(project);
  const [purpose, setPurpose] = useState("");
  const [profile, setProfile] = useState("assistant");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [error, setError] = useState<unknown>();
  const [message, setMessage] = useState("");
  const [pending, setPending] = useState(() =>
    readPending("onboarding", scope, "onboarding"),
  );
  const globalAdmin =
    principal.permissions.includes("*") ||
    principal.permissions.includes("onboarding:platform-admin");
  const admin =
    globalAdmin || principal.permissions.includes("onboarding:admin");

  async function mutate(path: string, body: unknown, revision: number) {
    if (lock.current || pending) return;
    lock.current = true;
    setBusy(true);
    setError(undefined);
    setMessage("");
    const key = commandKey();
    try {
      const item = { key, resource: "onboarding" };
      persistPending("onboarding", scope, item);
      setPending(item);
      await request<Entry>(path, {
        method: "POST",
        body,
        etag: `"k${revision}"`,
        key,
      });
      clearPending("onboarding", scope);
      setPending(undefined);
      remote.reload();
      setMessage("操作已保存。凭据仅通过管理员的私密交付渠道提供。");
    } catch (failure) {
      setError(failure);
      if (failure instanceof ApiError && !failure.uncertain) {
        clearPending("onboarding", scope);
        setPending(undefined);
      }
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }

  async function reconcile() {
    if (!pending?.key || lock.current) return;
    setBusy(true);
    lock.current = true;
    try {
      const reply = await request<{ status: string }>(
        base + "/commands/" + encodeURIComponent(pending.key),
      );
      if (reply.data.status === "COMPLETED") {
        clearPending("onboarding", scope);
        setPending(undefined);
        setError(undefined);
        setMessage("原操作已完成，可以继续。");
        remote.reload();
      } else {
        setMessage("尚未查到原操作结果，请稍后再核对；暂不提交新操作。");
      }
    } catch (failure) {
      setError(failure);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }

  const disabled = busy || !!pending;
  return (
    <div className="page">
      <PageHeading
        eyebrow="Access"
        title="应用接入"
        description="申请项目资源和应用身份，跟踪审批、配置、生效与停用。"
        actions={
          <button className="button secondary" onClick={remote.reload}>
            <RefreshCw size={16} /> 刷新
          </button>
        }
      />
      <Notice>
        审批通过后仍需管理员完成部署配置和现有身份授权。只有实际身份验证通过的应用才能开始调用。
      </Notice>
      {error ? <ErrorNotice error={error} /> : null}
      {message ? <Notice>{message}</Notice> : null}
      {pending ? (
        <div className="notice warning" role="status">
          原操作结果待核对，已暂停新的提交。
          <button disabled={busy || !pending.key} onClick={reconcile}>
            核对原操作结果
          </button>
        </div>
      ) : null}
      <section className="panel">
        <h2>申请接入</h2>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void mutate(
              base + "/" + crypto.randomUUID(),
              {
                targetProject: target,
                applicationId: application,
                name,
                purpose,
                profile,
              },
              0,
            );
          }}
        >
          <div className="form-grid">
            <label>
              应用名称
              <input
                value={name}
                maxLength={100}
                required
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <label>
              应用标识
              <input
                value={application}
                maxLength={64}
                pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
                required
                onChange={(e) => setApplication(e.target.value)}
              />
            </label>
            <label>
              目标项目
              <input
                value={target}
                disabled={!globalAdmin}
                maxLength={64}
                pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
                required
                onChange={(e) => setTarget(e.target.value)}
              />
            </label>
            <label>
              使用范围
              <select
                value={profile}
                onChange={(e) => setProfile(e.target.value)}
              >
                {Object.entries(profiles).map(([key, label]) => (
                  <option key={key} value={key}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label>
            业务用途与维护责任
            <textarea
              value={purpose}
              maxLength={600}
              required
              onChange={(e) => setPurpose(e.target.value)}
            />
          </label>
          <p>接入绑定当前用户。请勿在用途说明中填写密码、令牌或其他凭据。</p>
          <button className="button primary" type="submit" disabled={disabled}>
            <ClipboardCheck size={16} /> 提交申请
          </button>
        </form>
      </section>
      {admin ? (
        <section className="panel">
          <label>
            本次审批或生命周期操作的理由
            <input
              value={reason}
              maxLength={300}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <p>
            轮换会暂停新调用，管理员完成凭据交付、排空迁移和重启后重新验证。停用立即阻止新调用及后续执行授权，历史任务归属保留。
          </p>
        </section>
      ) : null}
      {remote.loading ? (
        <Loading />
      ) : remote.error ? (
        <ErrorNotice error={remote.error} onRetry={remote.reload} />
      ) : (
        <section aria-label="接入申请">
          {!remote.data?.items.length ? (
            <Empty title="当前页没有可见申请" />
          ) : null}
          {remote.data?.items.map((entry) => (
            <article className="panel" key={entry.id}>
              <h2>{entry.name}</h2>
              <p>
                <strong>{states[entry.state] ?? entry.state}</strong> ·{" "}
                {entry.targetProject} / {entry.applicationId}
              </p>
              <p>{entry.purpose}</p>
              <p>
                范围：{profiles[entry.profile] ?? entry.profile}；维护用户：
                {entry.subject}；凭据版本：{entry.generation}
              </p>
              {entry.state === "DISABLED" ? (
                <p>
                  {entry.provisioned?.identityDisabled
                    ? "中台与身份源均已停用。"
                    : "中台已停用，身份源停用仍需管理员执行。"}{" "}
                </p>
              ) : null}
              {entry.plan ? (
                <details>
                  <summary>已审批配置计划</summary>
                  <p>计划摘要：{entry.plan.digest}</p>
                  <p>
                    申请编号：{entry.id}；修订：{entry.revision}
                  </p>
                  <p>已批准权限：{entry.plan.permissions.join("、")}</p>
                  <p>
                    管理员依据计划完成受控配置，交付凭据后由申请用户验证生效。页面不显示应用凭据。
                  </p>
                </details>
              ) : null}
              <div className="heading-actions">
                {admin && entry.state === "REQUESTED" ? (
                  <>
                    <button
                      disabled={disabled || !reason.trim()}
                      onClick={() =>
                        void mutate(
                          base + "/" + entry.id + "/decision",
                          { decision: "APPROVE", reason },
                          entry.revision,
                        )
                      }
                    >
                      批准
                    </button>
                    <button
                      disabled={disabled || !reason.trim()}
                      onClick={() =>
                        void mutate(
                          base + "/" + entry.id + "/decision",
                          { decision: "REJECT", reason },
                          entry.revision,
                        )
                      }
                    >
                      拒绝
                    </button>
                  </>
                ) : null}
                {entry.state === "PROVISIONED" &&
                entry.subject === principal.subject ? (
                  <button
                    disabled={disabled}
                    onClick={() =>
                      void mutate(
                        base + "/" + entry.id + "/verify",
                        {},
                        entry.revision,
                      )
                    }
                  >
                    验证配置与身份并启用
                  </button>
                ) : null}
                {admin && entry.state === "ACTIVE" ? (
                  <button
                    disabled={disabled || !reason.trim()}
                    onClick={() =>
                      void mutate(
                        base + "/" + entry.id + "/lifecycle",
                        { operation: "ROTATE", reason },
                        entry.revision,
                      )
                    }
                  >
                    轮换凭据并暂停调用
                  </button>
                ) : null}
                {admin &&
                ["ACTIVE", "PROVISIONED", "ROTATION_APPROVED"].includes(
                  entry.state,
                ) ? (
                  <button
                    disabled={disabled || !reason.trim()}
                    onClick={() =>
                      void mutate(
                        base + "/" + entry.id + "/lifecycle",
                        { operation: "DISABLE", reason },
                        entry.revision,
                      )
                    }
                  >
                    停用
                  </button>
                ) : null}
              </div>
              <details>
                <summary>操作记录</summary>
                <ul>
                  {entry.audit?.map((item, index) => (
                    <li key={index}>
                      {new Date(item.at).toLocaleString()} · {item.actor} ·{" "}
                      {item.operation}
                    </li>
                  ))}
                </ul>
              </details>
            </article>
          ))}
          <div className="heading-actions">
            <button disabled={!cursor} onClick={() => setCursor("")}>
              第一页
            </button>
            <button
              disabled={!remote.data?.nextCursor}
              onClick={() => setCursor(remote.data?.nextCursor ?? "")}
            >
              下一页
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
