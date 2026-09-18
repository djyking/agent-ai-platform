import { describe, expect, it } from "vitest";
import {
  nodeTemplate,
  renameNode,
  workflowIssues,
  type Workflow,
} from "./workflow";

describe("structured workflow editing", () => {
  const workflow: Workflow = {
    id: "workflow",
    version: "1",
    start: "ask",
    maxTransitions: 10,
    nodes: {
      ask: {
        kind: "human",
        message: "Prompt refers to ask as content",
        output: "answer",
        next: "check",
      },
      check: {
        kind: "condition",
        variable: "answer",
        equalsJson: "true",
        whenTrue: "end",
        whenFalse: "ask",
      },
      end: { kind: "end", output: "answer" },
    },
  };
  it("renames every graph reference without rewriting literal prompt content", () => {
    const result = renameNode(workflow, "ask", "review");
    expect(result.start).toBe("review");
    expect(result.nodes.check.whenFalse).toBe("review");
    expect(result.nodes.review.message).toBe("Prompt refers to ask as content");
    expect(workflow.start).toBe("ask");
    expect(workflowIssues(result)).toEqual([]);
  });
  it("rejects duplicate node names and detects removed targets", () => {
    expect(() => renameNode(workflow, "ask", "end")).toThrow();
    const { ask, ...remaining } = workflow.nodes;
    expect(workflowIssues({ ...workflow, nodes: remaining })).toEqual(
      expect.arrayContaining([
        "起始节点不存在。",
        "节点 check 的 whenFalse 指向不存在的节点。",
      ]),
    );
  });
  it("creates model and agent nodes with precise prompt version references", () => {
    expect(nodeTemplate("model").promptRef).toEqual({ id: "", version: 1 });
    expect(nodeTemplate("agent").promptRef).toEqual({ id: "", version: 1 });
    expect(nodeTemplate("agent").prompt).toBeUndefined();
  });
});
