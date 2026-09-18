import type { Json, ReleaseRef, Run, Spec } from "./types";
export type StudioInput = { question: string; fields?: Record<string, string> };
export type StudioSample = {
  id: string;
  name: string;
  input: StudioInput;
  expectedContains: string[];
};
export type StudioDraft = {
  name: string;
  templateId: string;
  instructions: string;
  modelProfileId: string;
  knowledgeId: string;
  toolIds: string[];
  outputFormat: "text" | "markdown";
  requireReview: boolean;
  approvers: string[];
  limits: {
    maxTokens: number;
    maxModelCalls: number;
    maxToolCalls: number;
    maxSteps: number;
    lifetimeSeconds: number;
  };
  samples: StudioSample[];
};
export type StudioTemplate = {
  id: string;
  name: string;
  description: string;
  defaultDraft: StudioDraft;
};
export type StudioVersion = {
  version: number;
  releaseRef: ReleaseRef;
  draftDigest: string;
  publishedAt: string;
};
export type TaskLink = {
  id: string;
  runId: string;
  sessionId: string;
  applicationId: string;
  mode: "PREVIEW" | "RELEASE" | "EVALUATION";
  snapshotId: string;
};
export type StudioApplication = StudioDraft & {
  id: string;
  revision: number;
  draftDigest: string;
  updatedAt: string;
  publishedVersions: StudioVersion[];
  defaultVersion?: number;
  disabled: boolean;
  lastTask?: TaskLink;
  lastExperiment?: { id: string } | string;
};
export type StudioCapabilities = {
  models: { id: string; name?: string; provider: string; model: string }[];
  tools: {
    id: string;
    key: string;
    modelName?: string;
    name?: string;
    description?: string;
    inputSchema?: Spec;
    effect?: string;
  }[];
  knowledge: {
    id: string;
    name: string;
    visibility: string;
    version?: number;
    description?: string;
  }[];
};
export type TaskResult = {
  taskId: string;
  runId: string;
  status: string;
  output?: Json;
  visibility: string;
  usage?: Run["usage"];
  passed?: boolean;
  reason?: string;
};
export type StudioExperiment = {
  id: string;
  applicationId: string;
  draftDigest: string;
  status: string;
  passed: boolean;
  contract: unknown;
  rows: {
    sampleId: string;
    name: string;
    input?: StudioInput;
    expectedContains?: string[];
    candidate: TaskResult;
    baseline?: TaskResult;
  }[];
};
export type StudioTask = TaskLink & {
  createdAt?: string;
  name?: string;
  status?: string;
  input?: StudioInput;
  run?: Run;
  artifacts?: {
    id: string;
    name?: string;
    fileName?: string;
    contentType?: string;
    mediaType?: string;
    sourceRunId?: string;
    digest?: string;
  }[];
};
export function editable(application: StudioDraft): StudioDraft {
  return {
    name: application.name,
    templateId: application.templateId,
    instructions: application.instructions,
    modelProfileId: application.modelProfileId,
    knowledgeId: application.knowledgeId ?? "",
    toolIds: application.toolIds ?? [],
    outputFormat: application.outputFormat ?? "text",
    requireReview: application.requireReview ?? false,
    approvers: application.approvers ?? [],
    limits: application.limits,
    samples: application.samples ?? [],
  };
}
export const experimentId = (app?: StudioApplication) =>
  typeof app?.lastExperiment === "string"
    ? app.lastExperiment
    : app?.lastExperiment?.id;
