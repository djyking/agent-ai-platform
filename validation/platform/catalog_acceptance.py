"""Local stage-3 catalog acceptance and reusable synthetic Agent seed.

Uses the running platform and existing identity tokens from private files/environment.
It never clears a database, changes grants, prints credentials, or retries a model call.
Each invocation uses a fresh resource prefix. The two named Agent defaults are advanced
only after their immutable resources have passed the published frozen replay gate.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4


class AcceptanceFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise AcceptanceFailure(code)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def digest(value):
    return "sha256:" + hashlib.sha256(encoded(value)).hexdigest()


def utc():
    return datetime.now(timezone.utc).isoformat()


def message(role, content):
    return {"role": role, "content": content, "toolCalls": [], "toolCallId": None}


def render_prompt(template, inputs):
    def render(value):
        def replace(match):
            name = match.group(1)
            item = inputs[name]
            return item if template["variables"][name] == "STRING" else encoded(item).decode("utf-8")
        return re.sub(r"\{\{\s*([A-Za-z][A-Za-z0-9_.-]{0,79})\s*}}", replace, value)
    return [message("system", render(template["system"])), message("user", render(template["user"]))]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.origin = args.origin.rstrip("/")
        origin = urlparse(self.origin)
        require(origin.scheme in {"http", "https"} and origin.hostname in {"127.0.0.1", "localhost", "::1"}
                and not origin.username and not origin.password and not origin.query and not origin.fragment
                and origin.path in {"", "/"}, "LOCAL_PLATFORM_REQUIRED")
        self.credentials = read_json(args.credentials_file)
        self.metadata = read_json(args.identity_metadata)
        self.deployment = read_json(args.deployment_file)
        require(self.metadata.get("mysqlBacked") is True, "MYSQL_IDENTITY_REQUIRED")
        require(self.metadata.get("originalProductionBootConfiguration") is False, "ISOLATED_IDENTITY_REQUIRED")
        self.label = args.label or datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-") + uuid4().hex[:6]
        require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,55}", self.label)), "INVALID_LABEL")
        self.prefix = "cat-" + self.label
        self.opener = build_opener(NoRedirect())
        self.calls = 0
        self.current_step = "initialization"
        self.report = {"schemaVersion": 1, "kind": "local-phase3-catalog-http-acceptance", "startedAt": utc(),
                       "label": self.label, "status": "running", "cases": [], "runs": {}, "releases": {},
                       "scope": {"mysqlBacked": True, "existingIdentity": True, "syntheticKnowledgeOnly": True,
                                 "liveModelRunCountPlanned": 1, "productionCutover": False,
                                 "originalProductionBootConfiguration": False, "databaseCleared": False,
                                 "frozenReplayHasExternalEffects": False}}
        if args.platform_jar:
            self.report["suppliedPlatformJarSha256"] = hashlib.sha256(Path(args.platform_jar).read_bytes()).hexdigest()
        self.secrets = [value for value in self.credentials.values() if isinstance(value, str) and len(value) >= 8]
        self.secrets += [value for key, value in self.metadata.items()
                         if ("Token" in key or "Credential" in key) and isinstance(value, str)]
        self.publisher = ("console", 10)
        self.reviewer = ("console", 20)
        self.support_owner = ("support", 10)
        self.ops_owner = ("ops", 10)

    def credential(self, actor):
        kind, user = actor
        app = os.environ.get("HARNESS_CATALOG_" + kind.upper() + "_CREDENTIAL")
        if not app:
            app = self.credentials[{"console": "consoleApplication", "support": "supportApplication", "ops": "opsApplication"}[kind]]
        token = os.environ.get("HARNESS_CATALOG_USER_" + str(user) + "_TOKEN", self.metadata["user" + str(user) + "Token"])
        require(isinstance(app, str) and app and isinstance(token, str) and token, "MISSING_PRIVATE_CREDENTIAL")
        return app, token

    def save_report(self):
        self.report["httpRequests"] = self.calls
        self.report["updatedAt"] = utc()
        serialized = json.dumps(self.report, ensure_ascii=False, indent=2)
        for secret in self.secrets:
            require(secret not in serialized, "REPORT_SECRET_DETECTED")
        target = Path(self.args.report)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(serialized + "\n", encoding="utf-8")

    def record(self, name, **evidence):
        if "status" in evidence:
            evidence["runStatus"] = evidence.pop("status")
        self.report["cases"].append({"name": name, "status": "passed", **evidence})
        self.save_report()
        print("PASS " + name, flush=True)

    def call(self, actor, method, path, body=None, headers=None, expected=(200,)):
        app, token = self.credential(actor)
        h = {"Authorization": "Bearer " + app, "X-Harness-User-Token": token, "Accept": "application/json"}
        if headers:
            h.update(headers)
        data = None if body is None else encoded(body)
        if data is not None:
            h["Content-Type"] = "application/json"
        self.calls += 1
        request = Request(self.origin + path, data=data, headers=h, method=method)
        try:
            response = self.opener.open(request, timeout=30)
        except HTTPError as error:
            response = error
        except (OSError, URLError):
            raise AcceptanceFailure("PLATFORM_TRANSPORT_UNAVAILABLE") from None
        with response:
            status = response.status
            raw = response.read(2 * 1024 * 1024 + 1)
            require(len(raw) <= 2 * 1024 * 1024, "PLATFORM_RESPONSE_TOO_LARGE")
            try:
                value = json.loads(raw) if raw else {}
            except (ValueError, UnicodeDecodeError):
                raise AcceptanceFailure("PLATFORM_RESPONSE_NOT_JSON") from None
            if status not in expected:
                code = value.get("error", {}).get("code", "UNEXPECTED_HTTP_STATUS") if isinstance(value, dict) else "UNEXPECTED_HTTP_STATUS"
                require(bool(re.fullmatch(r"[A-Z0-9_]{1,80}", code)), "UNEXPECTED_HTTP_STATUS")
                raise AcceptanceFailure(f"HTTP_{status}_{code}")
            return value, {key.lower(): item for key, item in response.headers.items()}, status

    def cat(self, project, suffix):
        return "/v1/projects/" + project + "/catalog" + suffix

    def resource(self, project, kind, name):
        return self.cat(project, "/resources/" + kind + "/" + name)

    def get(self, project, kind, name):
        return self.call(self.publisher, "GET", self.resource(project, kind, name))[0]

    def command(self, project, kind, name, operation, body, *, current=None, key=None, version=None, expected=(200,)):
        if current is None:
            current = self.get(project, kind, name)
        path = self.resource(project, kind, name)
        method = "PUT" if operation == "save" else "POST"
        if operation != "save":
            path += ("/versions/" + str(version) if version is not None else "") + "/" + operation
        key = key or self.prefix + "-" + uuid4().hex
        result = self.call(self.publisher, method, path, body,
                           {"Idempotency-Key": key, "If-Match": '"c' + str(current["revision"]) + '"'}, expected)
        return result

    def publish(self, project, kind, name, spec, cases=None, *, existing=False, idempotency_probe=False):
        self.current_step = "publish_" + project + "_" + kind
        current, _, status = self.call(self.publisher, "GET", self.resource(project, kind, name), expected=(200, 404))
        require(existing or status == 404, "RESOURCE_PREFIX_ALREADY_EXISTS_USE_NEW_LABEL")
        if status == 404:
            current = {"revision": 0}
        body = {"name": name, "spec": spec, "regressionCases": cases or []}
        key = self.prefix + "-save-" + uuid4().hex
        saved, _, _ = self.command(project, kind, name, "save", body, current=current, key=key)
        if idempotency_probe:
            replay, _, _ = self.command(project, kind, name, "save", body, current=current, key=key)
            require(replay == saved, "CATALOG_SAVE_REPLAY_CHANGED")
            changed = {**body, "name": name + "-different"}
            conflict, _, _ = self.command(project, kind, name, "save", changed, current=current, key=key, expected=(409,))
            require(conflict["error"]["code"] == "IDEMPOTENCY_CONFLICT", "CATALOG_KEY_CONFLICT_MISSING")
            stale, _, _ = self.command(project, kind, name, "save", body, current=current, expected=(412,))
            require(stale["error"]["code"] == "PRECONDITION_FAILED", "CATALOG_STALE_REVISION_ACCEPTED")
        checked, _, _ = self.command(project, kind, name, "validate", {}, current=saved)
        require(checked["validation"].get("passed") is True,
                "CATALOG_VALIDATION_FAILED_" + kind + "_" + str(checked["validation"].get("code", "CASE_FAILED")))
        key = self.prefix + "-publish-" + uuid4().hex
        published, _, _ = self.command(project, kind, name, "publish", {}, current=checked, key=key)
        if idempotency_probe:
            replay, _, _ = self.command(project, kind, name, "publish", {}, current=checked, key=key)
            require(replay == published, "CATALOG_PUBLISH_REPLAY_CHANGED")
        version = published["versions"][0]["version"]
        return {"id": name, "version": version}, published

    def make_run(self, project, actor, release, inputs, key=None, expected=(202,)):
        key = key or self.prefix + "-run-" + uuid4().hex
        body = {"releaseRef": release, "inputs": inputs, "clientReference": self.prefix}
        result = self.call(actor, "POST", "/v1/projects/" + project + "/runs", body,
                           {"Idempotency-Key": key}, expected)
        if result[2] == 202:
            return result[0]["run"]["id"], body, key, result[0]
        return result[0], body, key, result[0]

    def run(self, project, actor, run_id):
        return self.call(actor, "GET", "/v1/projects/" + project + "/runs/" + run_id)

    def await_status(self, project, actor, run_id, target, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value, headers, _ = self.run(project, actor, run_id)
            if value["status"] in target:
                return value, headers
            if value["status"] in {"COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "BUDGET_EXCEEDED", "NEEDS_ATTENTION"}:
                raise AcceptanceFailure("UNEXPECTED_RUN_STATE_" + value["status"])
            time.sleep(.25)
        raise AcceptanceFailure("RUN_STATUS_TIMEOUT")

    def approval(self, project, run_id):
        return self.call(self.reviewer, "GET", "/v1/projects/" + project + "/runs/" + run_id + "/approval")

    def approve(self, project, run_id):
        approval, headers, _ = self.approval(project, run_id)
        require(approval["kind"] == "HUMAN_INPUT" and approval["reviewComplete"], "APPROVAL_NOT_REVIEWABLE")
        return self.call(self.reviewer, "POST", "/v1/projects/" + project + "/runs/" + run_id + "/approvals/" + approval["id"] + "/decision",
                         {"digest": approval["digest"], "decision": "APPROVE", "input": "Approved synthetic FAQ request", "reason": "Local synthetic phase-three acceptance"},
                         {"Idempotency-Key": self.prefix + "-approve-" + uuid4().hex, "If-Match": headers["etag"]}, (202,))[0]

    def trace(self, project, actor, run_id):
        return self.call(actor, "GET", "/v1/projects/" + project + "/runs/" + run_id + "/trace")[0]

    def support(self):
        project = "support-pilot"
        seed = next(r for r in self.deployment["releases"] if r["projectId"] == project and r.get("model", {}).get("id") == "deepseek-support")
        capabilities = self.call(self.publisher, "GET", self.cat(project, "/capabilities"))[0]
        require(set(capabilities["types"]) == {"Agent", "ModelProfile", "Prompt", "ToolConnection", "ToolPolicy", "RetrievalProfile", "Workflow", "RunPolicy"}, "EIGHT_TYPES_NOT_EXPOSED")
        require("secretRef" not in encoded(capabilities).decode() and "endpoint" not in encoded(capabilities).decode(), "UNSAFE_CAPABILITY_PROJECTION")
        names = {kind: self.prefix + "-" + kind.lower() for kind in capabilities["types"]}
        refs = {}
        refs["ToolConnection"], _ = self.publish(project, "ToolConnection", names["ToolConnection"], {"toolKey": "support:faq"}, idempotency_probe=True)
        refs["RetrievalProfile"], _ = self.publish(project, "RetrievalProfile", names["RetrievalProfile"], {"toolConnection": refs["ToolConnection"]})
        refs["ModelProfile"], _ = self.publish(project, "ModelProfile", names["ModelProfile"], {"trustedProfile": "deepseek-support"})
        refs["RunPolicy"], _ = self.publish(project, "RunPolicy", names["RunPolicy"], seed["limits"])
        policy = {"toolConnections": [refs["ToolConnection"]], "executionPermissions": ["tool:support:faq", "model:invoke"],
                  "approvers": ["platform-console/20", "support-pilot/20"], "reviewFields": ["query", "maxResults"], "publicOutput": True}
        refs["ToolPolicy"], _ = self.publish(project, "ToolPolicy", names["ToolPolicy"], policy)
        prompt = copy.deepcopy(next(node["prompt"] for node in seed["workflow"]["nodes"].values() if node.get("kind") == "model"))
        refs["Prompt"], _ = self.publish(project, "Prompt", names["Prompt"], prompt)
        workflow = copy.deepcopy(seed["workflow"])
        workflow["id"] = self.prefix + "-support-flow"
        workflow["start"] = "review"
        workflow["nodes"]["review"] = {"kind": "human", "message": "Approve this synthetic FAQ request using the original version.", "output": "review", "next": "search"}
        workflow["nodes"]["answer"].pop("prompt")
        workflow["nodes"]["answer"]["promptRef"] = refs["Prompt"]
        refs["Workflow"], _ = self.publish(project, "Workflow", names["Workflow"], {"definition": workflow})
        question = "订单什么时候发货 shipping"
        frozen_knowledge = {"evidence": "Synthetic orders ship within two business days.", "citations": []}
        case = {"name": "synthetic-faq-prompt-and-tool", "inputs": {"question": question},
                "humanInputs": {"review": "Approved synthetic FAQ request"}, "toolResults": {"search": frozen_knowledge},
                "expectedToolArguments": {"search": {"maxResults": 3, "query": question}}, "expectedToolKeys": ["support:faq"],
                "modelResults": {"answer": "Frozen support answer"}, "expectedOutput": "Frozen support answer",
                "expectedPrompts": {"answer": render_prompt(prompt, {"question": question, "knowledge": frozen_knowledge})}}
        agent = {"workflow": refs["Workflow"], "modelProfile": refs["ModelProfile"], "runPolicy": refs["RunPolicy"],
                 "toolPolicy": refs["ToolPolicy"], "retrievalProfile": refs["RetrievalProfile"],
                 "inputSchema": seed["inputSchema"], "outputSchema": seed["outputSchema"], "humanInputSchema": seed["humanInputSchema"]}
        agent_id = "support-assistant"
        first_ref, first = self.publish(project, "Agent", agent_id, agent, [case], existing=True, idempotency_probe=True)
        release1 = first["versions"][0]["releaseRef"]
        self.command(project, "Agent", agent_id, "default", {"version": first_ref["version"]})
        self.record("eight_types_publish_with_cas_idempotency_and_frozen_gate", resourceTypes=sorted(refs) + ["Agent"],
                    validationMode=first["validation"]["mode"], caseCount=first["validation"]["caseCount"], resultDigest=first["validation"]["resultDigest"])
        self.current_step = "support_waiting_original_run"
        run_id, body, key, created = self.make_run(project, self.support_owner, release1, {"question": question})
        replay = self.call(self.support_owner, "POST", "/v1/projects/" + project + "/runs", body, {"Idempotency-Key": key}, (202,))[0]
        require(replay == created, "RUN_CREATE_REPLAY_CHANGED")
        waiting, _ = self.await_status(project, self.support_owner, run_id, {"WAITING_INPUT"})
        approval_before = self.approval(project, run_id)[0]
        require(waiting["usage"]["modelCalls"] == 0 and waiting["usage"]["toolCalls"] == 0, "EFFECT_BEFORE_HUMAN_APPROVAL")
        self.report["runs"]["supportOriginal"] = run_id
        self.save_report()
        # An unexpected prompt update must not pass the old frozen expectations.
        prompt2 = copy.deepcopy(prompt)
        prompt2["system"] += "\n版本二要求：回答开头标注合成试点。"
        prompt2_ref, _ = self.publish(project, "Prompt", names["Prompt"], prompt2, existing=True)
        workflow2 = copy.deepcopy(workflow)
        workflow2["nodes"]["answer"]["promptRef"] = prompt2_ref
        workflow2["nodes"]["review"]["message"] = "Approve this synthetic FAQ request using version two."
        workflow2_ref, _ = self.publish(project, "Workflow", names["Workflow"], {"definition": workflow2}, existing=True)
        agent2 = {**agent, "workflow": workflow2_ref}
        current = self.get(project, "Agent", agent_id)
        bad_draft, _, _ = self.command(project, "Agent", agent_id, "save", {"name": agent_id, "spec": agent2, "regressionCases": [case]}, current=current)
        bad_checked, _, _ = self.command(project, "Agent", agent_id, "validate", {}, current=bad_draft)
        require(bad_checked["validation"].get("passed") is False, "BAD_PROMPT_PASSED_FROZEN_GATE")
        require(bad_checked["validation"]["results"][0]["code"] == "PROMPT_MISMATCH", "BAD_PROMPT_GATE_REASON_CHANGED")
        rejected, _, _ = self.command(project, "Agent", agent_id, "publish", {}, current=bad_checked, expected=(409,))
        require(rejected["error"]["code"] == "VALIDATION_REQUIRED", "BAD_PROMPT_PUBLISHED")
        self.record("unexpected_prompt_is_blocked_before_publication")
        case2 = copy.deepcopy(case)
        case2["expectedPrompts"]["answer"] = render_prompt(prompt2, {"question": question, "knowledge": frozen_knowledge})
        second_ref, second = self.publish(project, "Agent", agent_id, agent2, [case2], existing=True)
        release2 = second["versions"][0]["releaseRef"]
        self.command(project, "Agent", agent_id, "default", {"version": second_ref["version"]})
        self.command(project, "Agent", agent_id, "disable", {"disabled": True}, version=first_ref["version"])
        default = self.call(self.support_owner, "GET", self.cat(project, "/agents/" + agent_id + "/default"))[0]
        require(default["releaseRef"] == release2, "DEFAULT_RELEASE_NOT_CHANGED")
        denied, _, _, _ = self.make_run(project, self.support_owner, release1, {"question": question}, expected=(404,))
        require(denied["error"]["code"] == "RELEASE_UNAVAILABLE", "DISABLED_RELEASE_ACCEPTED_NEW_RUN")
        after, _ = self.await_status(project, self.support_owner, run_id, {"WAITING_INPUT"})
        approval_after = self.approval(project, run_id)[0]
        require(after["releaseRef"] == release1 and after["deadline"] == waiting["deadline"] and after["limits"] == waiting["limits"], "EXISTING_RUN_BASELINE_CHANGED")
        require(approval_before == approval_after, "EXISTING_APPROVAL_CHANGED")
        first_version = self.call(self.publisher, "GET", self.resource(project, "Agent", agent_id) + "/versions/" + str(first_ref["version"]))[0]
        require(first_version["spec"] == agent, "IMMUTABLE_AGENT_VERSION_CHANGED")
        difference = self.call(self.publisher, "GET", self.resource(project, "Prompt", names["Prompt"]) + "/versions/" + str(prompt2_ref["version"]) + "/diff?against=" + str(refs["Prompt"]["version"]))[0]
        require(any(change["path"] == "/spec/system" for change in difference["changes"]), "PROMPT_DIFF_MISSING")
        self.record("new_default_and_retirement_preserve_waiting_run_and_approval", originalReleaseRef=release1,
                    currentReleaseRef=release2, approvalDigest=approval_before["digest"], deadlinePreserved=True, limitsPreserved=True)
        self.current_step = "support_live_model_after_original_approval"
        self.approve(project, run_id)
        completed, _ = self.await_status(project, self.support_owner, run_id, {"COMPLETED"})
        require(completed["releaseRef"] == release1, "OLD_RUN_CHANGED_RELEASE_AFTER_APPROVAL")
        require(completed["usage"]["modelCalls"] == 1 and completed["usage"]["toolCalls"] == 1, "LIVE_CALL_COUNTS_CHANGED")
        require(completed["output"]["visibility"] == "AVAILABLE" and isinstance(completed["output"].get("value"), str)
                and completed["output"]["value"].strip(), "LIVE_SUPPORT_OUTPUT_UNAVAILABLE")
        trace = self.trace(project, self.support_owner, run_id)
        require({item["operation"] for item in trace["items"]} >= {"TOOL", "MODEL"}, "LIVE_TRACE_MISSING")
        self.record("existing_waiting_run_completes_real_faq_and_model_once", runId=run_id, status=completed["status"],
                    releaseRef=completed["releaseRef"], usage=completed["usage"], outputDigest=digest(completed["output"]["value"]),
                    traceCount=len(trace["items"]), traceOutcomes=[item["outcome"] for item in trace["items"]])
        self.report["releases"]["support"] = {"projectId": project, "agentId": agent_id, "releaseRef": release2,
                                                 "version": second_ref["version"], "inputSchema": seed["inputSchema"]}
        self.permission_checks(project, agent_id, run_id)
        self.revocation(project, refs["RunPolicy"], question)

    def permission_checks(self, project, agent_id, run_id):
        self.current_step = "project_and_actor_isolation"
        self.call(self.support_owner, "GET", self.cat(project, "/resources"), expected=(403,))
        self.call(self.publisher, "GET", self.resource("ops-dev", "Agent", agent_id), expected=(404,))
        self.call(("support", 20), "GET", "/v1/projects/" + project + "/runs/" + run_id, expected=(403, 404))
        self.call(("support", 20), "GET", "/v1/projects/" + project + "/runs/" + run_id + "/trace", expected=(403, 404))
        self.call(self.publisher, "GET", "/v1/projects/ops-dev/runs/" + run_id + "/trace", expected=(403, 404))
        self.record("directory_and_trace_enforce_project_application_subject_permissions")

    def revocation(self, project, limits, question):
        self.current_step = "independent_resource_emergency_revocation"
        connection, _ = self.publish(project, "ToolConnection", self.prefix + "-revocation-connection", {"toolKey": "support:faq"})
        policy, _ = self.publish(project, "ToolPolicy", self.prefix + "-revocation-policy",
                                 {"toolConnections": [connection], "executionPermissions": ["tool:support:faq"],
                                  "approvers": ["platform-console/20"], "reviewFields": ["query"], "publicOutput": True})
        flow = {"id": self.prefix + "-revocation-flow", "version": "1", "start": "review", "maxTransitions": 20,
                "nodes": {"review": {"kind": "human", "message": "Independent revoke test", "output": "review", "next": "search"},
                          "search": {"kind": "tool", "toolName": "support:faq", "argumentsJson": "{}", "argumentBindings": {"query": "question"}, "output": "knowledge", "next": "end"},
                          "end": {"kind": "end", "output": "knowledge"}}}
        workflow, _ = self.publish(project, "Workflow", self.prefix + "-revocation-workflow", {"definition": flow})
        spec = {"workflow": workflow, "toolPolicy": policy, "runPolicy": limits,
                "inputSchema": {"type": "object", "properties": {"question": {"type": "string"}}, "required": ["question"], "additionalProperties": False}, "outputSchema": {"type": "object"}}
        case = {"name": "revocation-frozen", "inputs": {"question": question}, "humanInputs": {"review": "Approved synthetic FAQ request"},
                "toolResults": {"search": {"synthetic": True}}, "expectedToolArguments": {"search": {"query": question}},
                "expectedToolKeys": ["support:faq"], "expectedOutput": {"synthetic": True}}
        name = self.prefix + "-revocation-agent"
        _, published = self.publish(project, "Agent", name, spec, [case])
        run_id, _, _, _ = self.make_run(project, self.support_owner, published["versions"][0]["releaseRef"], {"question": question})
        self.report["runs"]["revocation"] = run_id
        self.await_status(project, self.support_owner, run_id, {"WAITING_INPUT"})
        self.command(project, "ToolConnection", connection["id"], "revoke", {"revoked": True})
        try:
            self.approve(project, run_id)
            stopped, _ = self.await_status(project, self.support_owner, run_id, {"FAILED", "NEEDS_ATTENTION"})
            trace = self.trace(project, self.support_owner, run_id)
            require(stopped["usage"]["toolCalls"] == 0 and stopped["usage"]["modelCalls"] == 0 and not trace["items"], "REVOKED_RESOURCE_EXECUTED")
            self.record("explicit_revocation_stops_existing_run_before_tool_dispatch", runId=run_id, status=stopped["status"], usage=stopped["usage"], traceCount=0)
        finally:
            self.command(project, "ToolConnection", connection["id"], "revoke", {"revoked": False})
            self.command(project, "Agent", name, "disable", {"disabled": True})

    def ops(self):
        project = "ops-dev"
        seed = next(r for r in self.deployment["releases"] if r["projectId"] == project and "opsagent/rag-search" in r["toolKeys"])
        connection, _ = self.publish(project, "ToolConnection", self.prefix + "-ops-connection", {"toolKey": "opsagent/rag-search"})
        retrieval, _ = self.publish(project, "RetrievalProfile", self.prefix + "-ops-retrieval", {"toolConnection": connection})
        policy, _ = self.publish(project, "ToolPolicy", self.prefix + "-ops-policy",
                                 {"toolConnections": [connection], "executionPermissions": seed["executionPermissions"],
                                  "approvers": ["platform-console/20", self.metadata["applicationId"] + "/20"],
                                  "reviewFields": ["query", "topK"], "publicOutput": False})
        limits, _ = self.publish(project, "RunPolicy", self.prefix + "-ops-limits", seed["limits"])
        workflow = copy.deepcopy(seed["workflow"])
        workflow["id"] = self.prefix + "-ops-flow"
        workflow_ref, _ = self.publish(project, "Workflow", self.prefix + "-ops-workflow", {"definition": workflow})
        tool_node_id, tool_node = next((key, value) for key, value in workflow["nodes"].items() if value["kind"] == "tool")
        inputs = {"query": "Redis", "topK": 3}
        arguments = json.loads(tool_node["argumentsJson"])
        arguments.update({name: inputs[var] for name, var in tool_node["argumentBindings"].items()})
        frozen = {"evidence": "Synthetic Redis operational procedure", "citations": []}
        case = {"name": "ops-retrieval-frozen", "inputs": inputs, "toolResults": {tool_node_id: frozen},
                "expectedToolArguments": {tool_node_id: arguments}, "expectedToolKeys": ["opsagent/rag-search"], "expectedOutput": frozen}
        spec = {"workflow": workflow_ref, "toolPolicy": policy, "retrievalProfile": retrieval, "runPolicy": limits,
                "inputSchema": seed["inputSchema"], "outputSchema": seed["outputSchema"]}
        name = "ops-knowledge-agent"
        ref, published = self.publish(project, "Agent", name, spec, [case], existing=True)
        release = published["versions"][0]["releaseRef"]
        self.command(project, "Agent", name, "default", {"version": ref["version"]})
        self.current_step = "live_opsagent_directory_release"
        run_id, _, _, _ = self.make_run(project, self.ops_owner, release, inputs)
        self.report["runs"]["ops"] = run_id
        completed, _ = self.await_status(project, self.ops_owner, run_id, {"COMPLETED"})
        require(completed["usage"]["toolCalls"] == 1 and completed["usage"]["modelCalls"] == 0, "OPS_LIVE_CALL_COUNTS_CHANGED")
        require(completed["output"]["visibility"] == "AVAILABLE", "OPS_OWNER_PROTECTED_OUTPUT_UNAVAILABLE")
        output = completed["output"]["value"]
        require(isinstance(output.get("citations"), list) and len(output["citations"]) > 0 and output.get("evidence"), "OPS_LIVE_RETRIEVAL_EMPTY")
        trace = self.trace(project, self.ops_owner, run_id)
        require(len(trace["items"]) == 1 and trace["items"][0]["outcome"] == "SUCCESS", "OPS_TRACE_MISSING")
        self.report["releases"]["ops"] = {"projectId": project, "agentId": name, "version": ref["version"], "releaseRef": release, "inputSchema": seed["inputSchema"]}
        self.record("ops_catalog_release_executes_real_identity_and_protected_retrieval", runId=run_id, releaseRef=release,
                    status=completed["status"], usage=completed["usage"], citationCount=len(output["citations"]), outputDigest=digest(output), traceCount=len(trace["items"]))

    def execute(self):
        self.save_report()
        try:
            self.support()
            self.ops()
            self.report["status"] = "passed"
            self.report["passed"] = len(self.report["cases"])
            self.report["completedAt"] = utc()
            self.save_report()
            print("CATALOG_ACCEPTANCE_PASSED " + str(self.report["passed"]), flush=True)
            return 0
        except Exception as error:
            code = str(error) if isinstance(error, AcceptanceFailure) else type(error).__name__
            if not re.fullmatch(r"[A-Za-z0-9_]{1,200}", code):
                code = "SANITIZED_ACCEPTANCE_FAILURE"
            self.report["status"] = "failed"
            self.report["failure"] = {"step": self.current_step, "code": code}
            self.save_report()
            print("CATALOG_ACCEPTANCE_FAILED " + code, flush=True)
            return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="http://127.0.0.1:8099")
    parser.add_argument("--credentials-file", type=Path, required=True)
    parser.add_argument("--identity-metadata", type=Path, required=True)
    parser.add_argument("--deployment-file", type=Path, required=True)
    parser.add_argument("--platform-jar", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--label")
    args = parser.parse_args()
    try:
        return Acceptance(args).execute()
    except Exception as error:
        code = str(error) if isinstance(error, AcceptanceFailure) else type(error).__name__
        print("CATALOG_ACCEPTANCE_SETUP_FAILED " + code if re.fullmatch(r"[A-Za-z0-9_]{1,200}", code) else "CATALOG_ACCEPTANCE_SETUP_FAILED", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
