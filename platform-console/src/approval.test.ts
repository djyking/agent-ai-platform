import { describe, expect, it } from "vitest";
import { approvalBody } from "./Runs";
import type { Approval } from "./types";

const base: Approval = {
  id: "a",
  runId: "r",
  runRevision: "3",
  kind: "TOOL",
  status: "PENDING",
  digest: "sha256:exact",
  expiresAt: "2030-01-01T00:00:00Z",
  reviewComplete: true,
  summary: "",
  inputRequired: false,
  fields: [],
};
describe("approval decision contract", () => {
  it("never sends human input with a tool approval", () =>
    expect(
      approvalBody(
        base,
        "APPROVE",
        "Reviewed exact arguments",
        "unrelated text",
      ),
    ).toEqual({
      digest: "sha256:exact",
      decision: "APPROVE",
      reason: "Reviewed exact arguments",
    }));
  it("keeps human input and audit reason separate without parsing literal input", () =>
    expect(
      approvalBody(
        { ...base, kind: "HUMAN_INPUT" },
        "APPROVE",
        "Audit only",
        '{"answer": "literal text"}',
      ),
    ).toEqual({
      digest: "sha256:exact",
      decision: "APPROVE",
      reason: "Audit only",
      input: '{"answer": "literal text"}',
    }));
  it("never supplies workflow input when rejecting a human input request", () =>
    expect(
      approvalBody(
        { ...base, kind: "HUMAN_INPUT" },
        "REJECT",
        "Not approved",
        "must not enter workflow",
      ),
    ).not.toHaveProperty("input"));
});
