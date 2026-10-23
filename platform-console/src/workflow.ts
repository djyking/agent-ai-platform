import type { Json, Spec } from "./types";
export type Workflow = {
  id: string;
  version: string;
  start: string;
  maxTransitions: number;
  nodes: Record<string, Spec>;
};
export function nodeTemplate(kind: string): Spec {
  switch (kind) {
    case "tool":
      return {
        kind,
        toolName: "",
        argumentsJson: "{}",
        argumentBindings: {},
        output: "result",
        next: "end",
      };
    case "model":
      return {
        kind,
        promptRef: { id: "", version: 1 },
        inputBindings: {},
        output: "result",
        next: "end",
      };
    case "agent":
      return {
        kind,
        promptRef: { id: "", version: 1 },
        inputBindings: {},
        allowedTools: [],
        maxTurns: 3,
        output: "result",
        next: "end",
      };
    case "condition":
      return {
        kind,
        variable: "result",
        equalsJson: "true",
        whenTrue: "end",
        whenFalse: "end",
      };
    case "human":
      return {
        kind,
        message: "请提供继续执行所需的信息",
        output: "humanInput",
        next: "end",
      };
    default:
      return { kind: "end", output: "result" };
  }
}
export function renameNode(
  workflow: Workflow,
  from: string,
  to: string,
): Workflow {
  if (!to || (from !== to && workflow.nodes[to]))
    throw new Error("节点标识不能为空或重复。");
  return {
    ...workflow,
    start: workflow.start === from ? to : workflow.start,
    nodes: Object.fromEntries(
      Object.entries(workflow.nodes).map(([key, node]) => [
        key === from ? to : key,
        Object.fromEntries(
          Object.entries(node).map(([field, value]) => [
            field,
            ["next", "whenTrue", "whenFalse"].includes(field) && value === from
              ? to
              : value,
          ]),
        ),
      ]),
    ),
  };
}
export function workflowIssues(workflow: Workflow): string[] {
  const issues: string[] = [];
  if (!workflow.nodes[workflow.start]) issues.push("起始节点不存在。");
  for (const [name, node] of Object.entries(workflow.nodes)) {
    if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/.test(name))
      issues.push(`节点 ${name} 的标识格式无效。`);
    for (const field of ["next", "whenTrue", "whenFalse"])
      if (node[field] !== undefined && !workflow.nodes[String(node[field])])
        issues.push(`节点 ${name} 的 ${field} 指向不存在的节点。`);
  }
  return issues;
}
export function isRecord(value: Json | undefined): value is Spec {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
