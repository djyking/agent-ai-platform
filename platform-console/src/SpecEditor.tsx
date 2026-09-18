import { useEffect, useState } from "react";
import { ArrowDown, GitBranch, Plus, Trash2 } from "lucide-react";
import type { Capabilities, Json, Resource, ResourceType, Spec } from "./types";
import { Field, JsonView, Notice } from "./ui";
import {
  isRecord,
  nodeTemplate,
  renameNode,
  workflowIssues,
  type Workflow,
} from "./workflow";

export function JsonField({
  label,
  value,
  onChange,
  hint,
  rows = 7,
  onValidity,
  objectOnly = false,
}: {
  label: string;
  value: Json;
  onChange: (value: Json) => void;
  hint?: string;
  rows?: number;
  onValidity?: (valid: boolean) => void;
  objectOnly?: boolean;
}) {
  const formatted = JSON.stringify(value, null, 2);
  const [text, setText] = useState(formatted);
  const [invalid, setInvalid] = useState(false);
  useEffect(() => {
    setText(formatted);
    setInvalid(false);
  }, [formatted]);
  return (
    <Field
      label={label}
      hint={invalid ? "JSON 格式未完成，请修正后保存。" : hint}
    >
      <textarea
        className="code-input"
        aria-label={label}
        value={text}
        rows={rows}
        aria-invalid={invalid}
        spellCheck={false}
        onChange={(event) => {
          setText(event.target.value);
          try {
            const next = JSON.parse(event.target.value) as Json;
            if (objectOnly && !isRecord(next))
              throw new Error("Object required");
            setInvalid(false);
            event.target.setCustomValidity("");
            onValidity?.(true);
            onChange(next);
          } catch {
            setInvalid(true);
            event.target.setCustomValidity(
              objectOnly ? "请输入有效 JSON 对象" : "请输入有效 JSON",
            );
            onValidity?.(false);
          }
        }}
      />
    </Field>
  );
}

function RefField({
  label,
  value,
  type,
  resources,
  onChange,
}: {
  label: string;
  value: Json | undefined;
  type: ResourceType;
  resources: Resource[];
  onChange: (value: Json) => void;
}) {
  const ref = isRecord(value) ? value : { id: "", version: 1 };
  const choices = resources.filter(
    (resource) =>
      resource.type === type &&
      !resource.disabled &&
      !resource.revoked &&
      resource.versions.some((version) => !version.disabled),
  );
  const current = choices.find((resource) => resource.id === ref.id);
  return (
    <div className="reference-field">
      <Field label={label}>
        <select
          value={String(ref.id ?? "")}
          onChange={(event) => {
            const next = choices.find(
              (resource) => resource.id === event.target.value,
            );
            onChange({
              id: event.target.value,
              version: next
                ? Math.max(
                    ...next.versions
                      .filter((version) => !version.disabled)
                      .map((version) => version.version),
                  )
                : 1,
            });
          }}
        >
          <option value="">选择已发布的资源</option>
          {!current && ref.id && (
            <option value={String(ref.id)}>
              {String(ref.id)} · 当前不可用
            </option>
          )}
          {choices.map((resource) => (
            <option key={resource.id} value={resource.id}>
              {resource.name} · {resource.id}
            </option>
          ))}
        </select>
      </Field>
      <Field label="固定版本">
        <select
          value={Number(ref.version ?? 1)}
          onChange={(event) =>
            onChange({ ...ref, version: Number(event.target.value) })
          }
        >
          {!current && (
            <option value={Number(ref.version ?? 1)}>
              v{String(ref.version ?? 1)}
            </option>
          )}
          {current?.versions.map((version) => (
            <option
              key={version.version}
              value={version.version}
              disabled={version.disabled}
            >
              v{version.version}
              {version.disabled ? " · 已停用" : ""}
            </option>
          ))}
        </select>
      </Field>
    </div>
  );
}

export function SpecEditor({
  type,
  value,
  onChange,
  resources,
  capabilities,
}: {
  type: ResourceType;
  value: Spec;
  onChange: (spec: Spec) => void;
  resources: Resource[];
  capabilities?: Capabilities;
}) {
  const set = (key: string, next: Json) => onChange({ ...value, [key]: next });
  const text = (key: string, label: string, hint?: string, large = false) => (
    <Field key={key} label={label} hint={hint}>
      {large ? (
        <textarea
          value={String(value[key] ?? "")}
          rows={5}
          onChange={(event) => set(key, event.target.value)}
        />
      ) : (
        <input
          value={String(value[key] ?? "")}
          onChange={(event) => set(key, event.target.value)}
        />
      )}
    </Field>
  );
  const number = (key: string, label: string, max?: number) => (
    <Field key={key} label={label}>
      <input
        type="number"
        min={1}
        max={max}
        required
        value={Number(value[key] ?? 1)}
        onChange={(event) => set(key, Number(event.target.value))}
      />
    </Field>
  );
  const optionalNumber = (key: string, label: string, max?: number) => (
    <Field
      key={key}
      label={label}
      hint={
        max == null ? "留空沿用受信配置。" : `留空沿用受信配置，上限 ${max}。`
      }
    >
      <input
        type="number"
        min={1}
        max={max}
        placeholder="沿用受信配置"
        value={value[key] == null ? "" : Number(value[key])}
        onChange={(event) => {
          const next = { ...value };
          if (event.target.value === "") delete next[key];
          else next[key] = Number(event.target.value);
          onChange(next);
        }}
      />
    </Field>
  );
  const strings = (key: string, label: string, hint?: string) => (
    <Field key={key} label={label} hint={hint}>
      <textarea
        rows={3}
        value={
          Array.isArray(value[key]) ? (value[key] as Json[]).join("\n") : ""
        }
        onChange={(event) =>
          set(key, event.target.value.split("\n").filter(Boolean))
        }
      />
    </Field>
  );
  const ref = (
    key: string,
    label: string,
    kind: ResourceType,
    optional = false,
  ) => (
    <div key={key}>
      {optional && (
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={value[key] != null}
            onChange={(event) => {
              const next = { ...value };
              if (event.target.checked) next[key] = { id: "", version: 1 };
              else delete next[key];
              onChange(next);
            }}
          />
          配置{label}
        </label>
      )}
      {(!optional || value[key] != null) && (
        <RefField
          label={label}
          value={value[key]}
          type={kind}
          resources={resources}
          onChange={(next) => set(key, next)}
        />
      )}
    </div>
  );
  if (type === "Workflow") {
    const definition = value.definition as unknown as Workflow;
    return definition?.nodes ? (
      <WorkflowEditor
        value={definition}
        onChange={(next) => set("definition", next as unknown as Json)}
        resources={resources}
      />
    ) : (
      <Notice>工作流定义尚不完整，请在 JSON 编辑中提供 definition。</Notice>
    );
  }
  if (type === "Agent")
    return (
      <div className="editor-fields">
        <div className="form-section-title">
          <span>01</span>
          <h3>固定执行资源</h3>
        </div>
        {ref("workflow", "工作流", "Workflow")}
        {ref("runPolicy", "运行策略", "RunPolicy")}
        {ref("toolPolicy", "工具策略", "ToolPolicy")}
        {ref("modelProfile", "模型配置", "ModelProfile", true)}
        {ref("retrievalProfile", "检索配置", "RetrievalProfile", true)}
        <div className="form-section-title">
          <span>02</span>
          <h3>输入与输出契约</h3>
        </div>
        <JsonField
          label="输入 Schema"
          value={
            value.inputSchema ?? {
              type: "object",
              properties: {},
              additionalProperties: false,
            }
          }
          onChange={(next) => set("inputSchema", next)}
        />
        <JsonField
          label="输出 Schema"
          value={value.outputSchema ?? { type: "object" }}
          onChange={(next) => set("outputSchema", next)}
        />
        <JsonField
          label="人工输入 Schema"
          value={value.humanInputSchema ?? { type: "string", maxLength: 10000 }}
          onChange={(next) => set("humanInputSchema", next)}
          rows={4}
        />
      </div>
    );
  if (type === "ModelProfile") {
    const models = (capabilities?.trustedModels ?? []) as {
      id: string;
      provider?: string;
      model?: string;
      maxOutputTokens?: number;
      timeoutMillis?: number;
    }[];
    const selected = models.find((model) => model.id === value.trustedProfile);
    return (
      <div className="editor-fields">
        <Notice>
          仅可选择平台已登记的模型。凭据由服务端解析；预算和超时只能在受信配置范围内收紧。
        </Notice>
        <Field label="受信模型配置" required>
          <select
            required
            value={String(value.trustedProfile ?? "")}
            onChange={(event) => set("trustedProfile", event.target.value)}
          >
            <option value="">选择模型配置</option>
            {models.map((model) => (
              <option key={model.id} value={model.id}>
                {model.id} · {model.provider} / {model.model}
              </option>
            ))}
          </select>
        </Field>
        <div className="form-grid">
          {optionalNumber(
            "maxOutputTokens",
            "最大输出 Token",
            selected?.maxOutputTokens,
          )}
          {optionalNumber(
            "timeoutMillis",
            "超时（毫秒）",
            selected?.timeoutMillis,
          )}
        </div>
      </div>
    );
  }
  if (type === "Prompt")
    return (
      <div className="editor-fields">
        <div className="form-grid">
          {text("id", "模板标识")}
          {text("version", "模板版本")}
        </div>
        {text(
          "system",
          "系统提示词",
          "声明任务边界、输出要求及工具使用规则。",
          true,
        )}
        {text(
          "user",
          "用户模板",
          "使用模板声明的变量，发布时验证输入绑定。",
          true,
        )}
        <JsonField
          label="变量声明"
          value={value.variables ?? { question: "STRING" }}
          onChange={(next) => set("variables", next)}
          rows={5}
        />
      </div>
    );
  if (type === "ToolConnection") {
    const tools = (capabilities?.trustedTools ?? []) as {
      key: string;
      modelName?: string;
      readOnly?: boolean;
      approvalRequired?: boolean;
      inputSchema?: Json;
    }[];
    const tool = tools.find((item) => item.key === value.toolKey);
    return (
      <div className="editor-fields">
        <Notice>
          连接受信部署中的工具，不接受任意端点、请求头或凭据。启用连接不会自动授予调用权限。
        </Notice>
        <Field label="受信工具" required>
          <select
            required
            value={String(value.toolKey ?? "")}
            onChange={(event) => set("toolKey", event.target.value)}
          >
            <option value="">选择已登记工具</option>
            {tools.map((item) => (
              <option key={item.key} value={item.key}>
                {item.key}
                {item.readOnly ? " · 只读" : " · 有副作用"}
              </option>
            ))}
          </select>
        </Field>
        {tool && (
          <div className="connection-summary">
            <div>
              <span>工具名称</span>
              <strong>{tool.modelName ?? tool.key}</strong>
            </div>
            <div>
              <span>执行类型</span>
              <strong>{tool.readOnly ? "只读" : "可能产生外部副作用"}</strong>
            </div>
            <div>
              <span>审批</span>
              <strong>
                {tool.approvalRequired ? "必须审批" : "依据发布策略"}
              </strong>
            </div>
            {tool.inputSchema && (
              <div>
                <h4>工具输入契约（只读）</h4>
                <JsonView value={tool.inputSchema} />
              </div>
            )}
          </div>
        )}
      </div>
    );
  }
  if (type === "ToolPolicy") {
    const connections = Array.isArray(value.toolConnections)
      ? value.toolConnections
      : [];
    return (
      <div className="editor-fields">
        <h3>允许的工具连接</h3>
        {connections.map((connection, index) => (
          <div className="removable-row" key={index}>
            <RefField
              label={`工具连接 ${index + 1}`}
              value={connection}
              type="ToolConnection"
              resources={resources}
              onChange={(next) =>
                set(
                  "toolConnections",
                  connections.map((item, i) => (i === index ? next : item)),
                )
              }
            />
            <button
              type="button"
              className="icon-button danger-text"
              aria-label={`移除工具连接 ${index + 1}`}
              onClick={() =>
                set(
                  "toolConnections",
                  connections.filter((_, i) => i !== index),
                )
              }
            >
              <Trash2 size={16} />
            </button>
          </div>
        ))}
        <button
          type="button"
          className="button small"
          onClick={() =>
            set("toolConnections", [...connections, { id: "", version: 1 }])
          }
        >
          <Plus size={15} />
          添加工具连接
        </button>
        {strings(
          "executionPermissions",
          "精确执行权限",
          "每行一项。必须与工具权限一致，不得填写通配符或平台控制权限。",
        )}
        {strings(
          "approvers",
          "指定审批身份",
          "每行一个 application/subject。审批仍需当前项目授权。",
        )}
        {strings(
          "reviewFields",
          "审批可审阅字段",
          "每行一个工具参数名。字段未完整展示时不能批准执行。",
        )}
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={value.publicOutput === true}
            onChange={(event) => set("publicOutput", event.target.checked)}
          />
          声明输出为公开或合成内容
        </label>
        {value.publicOutput === true && (
          <Notice tone="warning">
            此声明影响结果可见性。仅在所有输出确实可以公开时启用，受保护知识保持关闭。
          </Notice>
        )}
      </div>
    );
  }
  if (type === "RetrievalProfile")
    return (
      <div className="editor-fields">
        {ref("toolConnection", "授权检索工具", "ToolConnection")}
        <Notice>
          引用精确发布版本。知识访问权限仍由业务系统在每次调用和结果读取时核验。
        </Notice>
      </div>
    );
  return (
    <div className="editor-fields">
      <Notice>每次运行将冻结此预算。运行请求只能进一步降低限制。</Notice>
      <div className="form-grid">
        {number("maxTokens", "Token 总预算", 10000000)}
        {number("maxModelCalls", "模型调用上限", 1000)}
        {number("maxToolCalls", "工具调用上限", 5000)}
        {number("maxSteps", "执行步数上限", 10000)}
        {number("lifetimeSeconds", "最长生命周期（秒）", 86400)}
      </div>
    </div>
  );
}

function WorkflowEditor({
  value,
  onChange,
  resources,
}: {
  value: Workflow;
  onChange: (value: Workflow) => void;
  resources: Resource[];
}) {
  const issues = workflowIssues(value);
  const [error, setError] = useState("");
  const nodes = Object.keys(value.nodes);
  const setNode = (key: string, next: Spec) =>
    onChange({ ...value, nodes: { ...value.nodes, [key]: next } });
  return (
    <div className="workflow-editor">
      <div className="form-grid">
        <Field label="工作流标识">
          <input
            value={value.id}
            onChange={(event) => onChange({ ...value, id: event.target.value })}
          />
        </Field>
        <Field label="定义版本">
          <input
            value={value.version}
            onChange={(event) =>
              onChange({ ...value, version: event.target.value })
            }
          />
        </Field>
        <Field label="起始节点">
          <select
            value={value.start}
            onChange={(event) =>
              onChange({ ...value, start: event.target.value })
            }
          >
            {nodes.map((key) => (
              <option key={key}>{key}</option>
            ))}
          </select>
        </Field>
        <Field label="最大节点转换次数">
          <input
            type="number"
            min={1}
            max={10000}
            value={value.maxTransitions}
            onChange={(event) =>
              onChange({ ...value, maxTransitions: Number(event.target.value) })
            }
          />
        </Field>
      </div>
      <div className="workflow-caption">
        <GitBranch size={16} />
        <span>结构化工作流</span>
        <strong>{nodes.length} 个节点</strong>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {nodes.map((key, index) => {
        const node = value.nodes[key];
        const set = (field: string, next: Json) =>
          setNode(key, { ...node, [field]: next });
        return (
          <div className="node-wrapper" key={key}>
            <div className="workflow-node">
              <div className="node-heading">
                <span className="node-index">
                  {String(index + 1).padStart(2, "0")}
                </span>
                <input
                  aria-label="节点标识"
                  defaultValue={key}
                  onBlur={(event) => {
                    try {
                      onChange(renameNode(value, key, event.target.value));
                      event.target.setCustomValidity("");
                      setError("");
                    } catch {
                      event.target.setCustomValidity("节点标识不能为空或重复");
                      setError("节点标识不能为空或重复。");
                    }
                  }}
                />
                <select
                  aria-label="节点类型"
                  value={String(node.kind)}
                  onChange={(event) =>
                    setNode(key, nodeTemplate(event.target.value))
                  }
                >
                  {[
                    ["tool", "工具调用"],
                    ["model", "模型调用"],
                    ["agent", "子 Agent"],
                    ["condition", "条件分支"],
                    ["human", "人工输入"],
                    ["end", "结束"],
                  ].map(([kind, name]) => (
                    <option value={kind} key={kind}>
                      {name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="icon-button danger-text"
                  aria-label={`删除节点 ${key}`}
                  disabled={nodes.length === 1}
                  onClick={() => {
                    const next = { ...value.nodes };
                    delete next[key];
                    onChange({ ...value, nodes: next });
                  }}
                >
                  <Trash2 size={16} />
                </button>
              </div>
              <div className="node-body">
                {node.kind === "tool" && (
                  <>
                    <Field label="工具键">
                      <input
                        value={String(node.toolName ?? "")}
                        onChange={(event) =>
                          set("toolName", event.target.value)
                        }
                      />
                    </Field>
                    <Field label="固定参数 JSON">
                      <textarea
                        className="code-input"
                        rows={3}
                        value={String(node.argumentsJson ?? "{}")}
                        onChange={(event) => {
                          set("argumentsJson", event.target.value);
                          try {
                            JSON.parse(event.target.value);
                            event.target.setCustomValidity("");
                          } catch {
                            event.target.setCustomValidity("请输入有效 JSON");
                          }
                        }}
                      />
                    </Field>
                    <JsonField
                      label="参数绑定"
                      value={node.argumentBindings ?? {}}
                      onChange={(next) => set("argumentBindings", next)}
                      hint="左侧是工具参数，右侧是已声明的工作流变量。"
                      rows={3}
                    />
                  </>
                )}
                {(node.kind === "model" || node.kind === "agent") && (
                  <>
                    <RefField
                      label="提示词版本"
                      value={node.promptRef}
                      type="Prompt"
                      resources={resources}
                      onChange={(next) => set("promptRef", next)}
                    />
                    <JsonField
                      label="提示词输入绑定"
                      value={node.inputBindings ?? {}}
                      onChange={(next) => set("inputBindings", next)}
                      rows={3}
                    />
                  </>
                )}
                {node.kind === "agent" && (
                  <>
                    <JsonField
                      label="子 Agent 允许工具"
                      value={node.allowedTools ?? []}
                      onChange={(next) => set("allowedTools", next)}
                      rows={3}
                    />
                    <Field label="最大轮次">
                      <input
                        type="number"
                        min={1}
                        max={100}
                        value={Number(node.maxTurns ?? 3)}
                        onChange={(event) =>
                          set("maxTurns", Number(event.target.value))
                        }
                      />
                    </Field>
                  </>
                )}
                {node.kind === "human" && (
                  <Field label="人工输入提示">
                    <textarea
                      rows={3}
                      value={String(node.message ?? "")}
                      onChange={(event) => set("message", event.target.value)}
                    />
                  </Field>
                )}
                {node.kind === "condition" && (
                  <>
                    <Field label="判断变量">
                      <input
                        value={String(node.variable ?? "")}
                        onChange={(event) =>
                          set("variable", event.target.value)
                        }
                      />
                    </Field>
                    <Field label="相等值 JSON">
                      <input
                        value={String(node.equalsJson ?? "true")}
                        onChange={(event) =>
                          set("equalsJson", event.target.value)
                        }
                      />
                    </Field>
                  </>
                )}
                <div className="form-grid">
                  {node.kind !== "condition" && (
                    <Field label="输出变量">
                      <input
                        value={String(node.output ?? "")}
                        onChange={(event) => set("output", event.target.value)}
                      />
                    </Field>
                  )}
                  {(node.kind === "condition"
                    ? ["whenTrue", "whenFalse"]
                    : node.kind === "end"
                      ? []
                      : ["next"]
                  ).map((field) => (
                    <Field
                      label={
                        field === "next"
                          ? "下一个节点"
                          : field === "whenTrue"
                            ? "条件成立"
                            : "条件不成立"
                      }
                      key={field}
                    >
                      <select
                        value={String(node[field] ?? "")}
                        onChange={(event) => set(field, event.target.value)}
                      >
                        <option value="">选择节点</option>
                        {nodes.map((target) => (
                          <option key={target}>{target}</option>
                        ))}
                      </select>
                    </Field>
                  ))}
                </div>
              </div>
            </div>
            {index < nodes.length - 1 && (
              <ArrowDown className="node-arrow" size={16} />
            )}
          </div>
        );
      })}
      <button
        className="button"
        type="button"
        onClick={() => {
          let index = nodes.length + 1;
          while (value.nodes[`step${index}`]) index++;
          setNode(`step${index}`, nodeTemplate("tool"));
        }}
      >
        <Plus size={16} />
        添加节点
      </button>
      {issues.length > 0 && (
        <Notice tone="warning">
          <ul>
            {issues.map((issue) => (
              <li key={issue}>{issue}</li>
            ))}
          </ul>
        </Notice>
      )}
    </div>
  );
}

export function RegressionEditor({
  value,
  onChange,
}: {
  value: Json[];
  onChange: (cases: Json[]) => void;
}) {
  return (
    <div className="editor-fields">
      <Notice>
        回归使用固定工具结果、模型回答与人工输入在本机重放工作流，不访问外部服务。发布前必须通过校验。
      </Notice>
      {value.map((entry, index) => {
        const item = isRecord(entry) ? entry : {};
        const set = (key: string, next: Json) =>
          onChange(
            value.map((current, i) =>
              i === index ? { ...item, [key]: next } : current,
            ),
          );
        return (
          <div className="regression-case" key={index}>
            <div className="section-title">
              <h3>用例 {index + 1}</h3>
              <button
                type="button"
                className="icon-button danger-text"
                aria-label={`删除用例 ${index + 1}`}
                onClick={() => onChange(value.filter((_, i) => i !== index))}
              >
                <Trash2 size={16} />
              </button>
            </div>
            <Field label="用例名称">
              <input
                value={String(item.name ?? "")}
                onChange={(event) => set("name", event.target.value)}
              />
            </Field>
            {[
              ["inputs", "运行输入", {}],
              ["toolResults", "固定工具结果（按节点标识）", {}],
              ["expectedToolArguments", "预期精确工具参数（按节点标识）", {}],
              ["expectedPrompts", "预期完整模型消息（按节点标识）", {}],
              ["modelResults", "固定模型回答（按节点标识）", {}],
              ["humanInputs", "固定人工输入（按节点标识）", {}],
              ["expectedOutput", "预期最终输出", {}],
              ["expectedToolKeys", "预期工具键", []],
            ].map(([key, label, fallback]) => (
              <JsonField
                key={String(key)}
                label={String(label)}
                value={item[String(key)] ?? (fallback as Json)}
                onChange={(next) => set(String(key), next)}
                rows={4}
              />
            ))}
          </div>
        );
      })}
      <button
        type="button"
        className="button"
        onClick={() =>
          onChange([
            ...value,
            {
              name: `case-${value.length + 1}`,
              inputs: {},
              toolResults: {},
              expectedToolArguments: {},
              expectedPrompts: {},
              modelResults: {},
              humanInputs: {},
              expectedOutput: {},
              expectedToolKeys: [],
            },
          ])
        }
      >
        <Plus size={16} />
        添加回归用例
      </button>
    </div>
  );
}
