export const RESOURCE_TYPES = [
  "Agent",
  "ModelProfile",
  "Prompt",
  "ToolConnection",
  "ToolPolicy",
  "RetrievalProfile",
  "Workflow",
  "RunPolicy",
] as const;
export type ResourceType = (typeof RESOURCE_TYPES)[number];
export type Json =
  null | boolean | number | string | Json[] | { [key: string]: Json };
export type Spec = Record<string, Json>;
export type Principal = {
  application: string;
  project: string;
  subject: string;
  permissions: string[];
};
export type Session = {
  authenticated: boolean;
  csrfToken?: string;
  principal?: Principal;
  projects?: (string | { id: string; name?: string })[];
};
export type ReleaseRef = { agentId: string; releaseId: string; digest: string };
export type Version = {
  version: number;
  digest: string;
  publishedAt: string;
  disabled: boolean;
  releaseRef?: ReleaseRef;
  spec?: Spec;
};
export type Validation = {
  passed?: boolean;
  errors?: Json[];
  warnings?: Json[];
  [key: string]: Json | undefined;
};
export type Resource = {
  type: ResourceType;
  id: string;
  name: string;
  revision: number;
  status: string;
  spec: Spec;
  regressionCases: Json[];
  validation?: Validation | null;
  versions: Version[];
  defaultVersion?: number | null;
  disabled: boolean;
  revoked?: boolean;
  audit?: { actor: string; operation: string; at: string }[];
};
export type Capabilities = {
  types: ResourceType[];
  templates: Partial<Record<ResourceType, Spec>>;
  [key: string]: unknown;
};
export type RunSummary = {
  id: string;
  releaseRef: ReleaseRef;
  status: string;
  revision: string;
  createdAt: string;
  deadline: string;
  clientReference?: string;
};
export type Run = RunSummary & {
  projectId: string;
  controls: { pauseRequested: boolean; cancelRequested: boolean };
  usage: {
    chargedTokens: number;
    modelCalls: number;
    toolCalls: number;
    steps: number;
  };
  limits: Record<string, number>;
  reasonCode?: string;
  attention?: { code: string; invocationKind?: string; invocationRef?: string };
  output?: { visibility: string; value?: Json };
};
export type Event = {
  sequence: string;
  type: string;
  at: string;
  invocationRef?: string;
  traceId?: string;
};
export type Page<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};
export type Approval = {
  id: string;
  runId: string;
  runRevision: string;
  kind: "TOOL" | "HUMAN_INPUT";
  status: string;
  digest: string;
  expiresAt: string;
  reviewComplete: boolean;
  summary: string;
  inputRequired: boolean;
  fields: { name: string; value: string; masked: boolean }[];
};
export type UnknownInvocation = {
  runId: string;
  runRevision: string;
  invocationRef: string;
  invocationDigest: string;
  kind: string;
  reconcilable: boolean;
  cancelRequested: boolean;
  summary: string;
};
