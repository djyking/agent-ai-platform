"""Local synthetic UNKNOWN preparation; never alters active config or restarts services.

prepare stages additive config; serve starts only a loopback MCP fixture; create
uses explicit platform commands; evidence independently verifies the fsynced
fixture receipt and DB invocation, then optionally imports it. Reconciliation
is deliberately left to the real console UI.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHASE = ROOT / ".work/phase3"
WORK = PHASE / "unknown-fixture"
sys.path.insert(0, str(ROOT / "validation/platform"))
from wire_fixture import WireFixture, utc

TOOL = "mcp:phase3-unknown-fixture:probe_unknown_write"
AGENT = "phase3-unknown-synthetic"
RELEASE_ID = str(uuid.uuid5(uuid.NAMESPACE_URL, "harness/phase3/unknown-synthetic/20260918"))
PROJECT = "support-pilot"
JAR = ROOT / "harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar"


class Failure(RuntimeError):
    pass


def require(value, code):
    if not value:
        raise Failure(code)


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(NoRedirect())


def request(method, path, body=None, *, subject="10", etag=None, key=None):
    meta = read(PHASE / "ops-metadata.private.json")
    credentials = read(PHASE / "credentials.private.json")
    headers = {"Accept": "application/json", "Authorization": "Bearer " + credentials["consoleApplication"],
               "X-Harness-User-Token": meta["user" + subject + "Token"]}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, separators=(",", ":")).encode()
    if etag:
        headers["If-Match"] = etag
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request("http://127.0.0.1:8099" + path, data=data, headers=headers, method=method)
    try:
        response = HTTP.open(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    except Exception:
        raise Failure("COMMAND_OUTCOME_UNCONFIRMED_NO_AUTOMATIC_RETRY" if method == "POST" else "READ_FAILED")
    with response:
        raw = response.read(1048577)
        require(len(raw) <= 1048576, "RESPONSE_TOO_LARGE")
        value = json.loads(raw)
        require(200 <= response.status < 300, "HTTP_" + str(response.status) + "_" + str(value.get("code", "ERROR")))
        return value, response.headers.get("ETag")


def route(run=None):
    return "/v1/projects/" + PROJECT + "/runs" + ("/" + run if run else "")


def prepare(args):
    require(not WORK.exists(), "PREPARATION_ALREADY_EXISTS_DO_NOT_OVERWRITE")
    require(1024 <= args.port <= 65535, "INVALID_FIXTURE_PORT")
    with socket.socket() as probe:
        require(probe.connect_ex(("127.0.0.1", args.port)) != 0, "FIXTURE_PORT_IN_USE")
    manifest = read(PHASE / "platform.private.json")
    host = read(PHASE / "ops-host.private.json")
    require(not any(t.get("serverId") == "phase3-unknown-fixture" for t in manifest["tools"]), "FIXTURE_ALREADY_CONFIGURED")
    require(not any(r["releaseId"] == RELEASE_ID or r["agentId"] == AGENT for r in manifest["releases"]), "SEED_ALREADY_CONFIGURED")
    schema = {"type": "object", "required": ["caseKey"], "additionalProperties": False,
              "properties": {"caseKey": {"type": "string", "pattern": "^phase3-unknown-[a-z0-9_-]{8,80}$"}}}
    WORK.mkdir()
    (WORK / "worker.secret").write_text(secrets.token_urlsafe(32))
    (WORK / "verifier.secret").write_text(secrets.token_urlsafe(32))
    manifest["tools"].append({"kind": "mcp", "serverId": "phase3-unknown-fixture",
        "endpoint": f"http://127.0.0.1:{args.port}/mcp", "secretRef": "file:" + (WORK / "worker.secret").as_posix(),
        "bindings": [{"remoteName": "probe_unknown_write", "readOnly": False, "argumentSchema": schema}]})
    manifest["releases"].append({"projectId": PROJECT, "agentId": AGENT, "releaseId": RELEASE_ID,
        "workflow": {"id": AGENT, "version": "1", "start": "effect", "maxTransitions": 6,
            "nodes": {"effect": {"kind": "tool", "toolName": TOOL, "argumentsJson": "{}",
                "argumentBindings": {"caseKey": "caseKey"}, "output": "result", "next": "end"},
                "end": {"kind": "end", "output": "result"}}},
        "model": None, "inputSchema": schema, "outputSchema": {"type": "object"}, "toolKeys": [TOOL],
        "executionPermissions": ["tool:" + TOOL], "approvers": ["platform-console/10", "platform-console/20"],
        "reviewFields": ["caseKey"], "humanInputSchema": {"type": "string", "maxLength": 200},
        "publicOutput": True, "limits": {"maxTokens": 1000, "maxModelCalls": 1, "maxToolCalls": 1,
            "maxSteps": 12, "lifetimeSeconds": 7200}})
    grants = [g for a in host["additionalApplications"] if a["id"] == "platform-console"
              for g in a["grants"] if g["project"] == PROJECT and str(g["user"]) == args.subject]
    require(len(grants) == 1, "EXACT_CONSOLE_GRANT_REQUIRED")
    grants[0]["permissions"] = sorted(set(grants[0]["permissions"] + ["tool:" + TOOL]))
    write(WORK / "platform.staged.private.json", manifest)
    write(WORK / "ops-host.staged.private.json", host)
    (WORK / "add-console-tool-grant.sql").write_text(
        "START TRANSACTION;\n"
        "SELECT application_id,project_id,user_id,enabled FROM ops_harness_grant "
        f"WHERE application_id='platform-console' AND project_id='{PROJECT}' AND user_id={args.subject} FOR UPDATE;\n"
        "UPDATE ops_harness_grant SET permissions_json=JSON_ARRAY_APPEND(CAST(permissions_json AS JSON),'$',"
        f"'tool:{TOOL}') WHERE application_id='platform-console' AND project_id='{PROJECT}' AND user_id={args.subject} "
        f"AND enabled=1 AND NOT JSON_CONTAINS(CAST(permissions_json AS JSON),JSON_QUOTE('tool:{TOOL}'));\n"
        "SELECT ROW_COUNT() AS added_permission_rows;\n"
        "COMMIT;\n", encoding="utf-8")
    changes = {"preparedAt": utc(), "project": PROJECT, "subject": args.subject, "port": args.port,
        "toolKey": TOOL, "agentId": AGENT, "releaseId": RELEASE_ID,
        "permissionAdded": "tool:" + TOOL, "sourceManifestSha256": sha(PHASE / "platform.private.json"),
        "sourceHostConfigSha256": sha(PHASE / "ops-host.private.json"), "sourceJarSha256": sha(JAR),
        "existingReleasesUnchanged": manifest["releases"][:-1] == read(PHASE / "platform.private.json")["releases"],
        "activeConfigurationChanged": False, "servicesRestarted": False}
    write(WORK / "prepared.json", changes)
    print(json.dumps(changes))


def serve(args):
    config = read(WORK / "prepared.json")
    runtime = WORK / ("runtime-" + time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6])
    runtime.mkdir()
    fixture = WireFixture(config["port"], runtime,
                          {"phase3-unknown-console": (WORK / "worker.secret").read_text()},
                          (WORK / "verifier.secret").read_text())
    fixture.start()
    write(WORK / "fixture.ready.json", {"pid": os.getpid(), "port": config["port"], "runtime": str(runtime), "startedAt": utc()})
    print("SYNTHETIC_LOOPBACK_FIXTURE_READY", flush=True)
    try:
        while not (WORK / "fixture.stop").exists():
            time.sleep(0.25)
    finally:
        fixture.close()


def java(*args, env=None):
    result = subprocess.run([shutil.which("java"), "-jar", str(JAR), *map(str, args)],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        (WORK / "java-command.private.log").write_bytes(result.stdout + result.stderr)
    require(result.returncode == 0, "JAVA_COMMAND_FAILED_PRIVATE_LOG_AVAILABLE")
    return result.stdout.decode("utf-8-sig").strip()


def wait_state(run, expected, subject):
    end = time.monotonic() + 90
    while time.monotonic() < end:
        body, etag = request("GET", route(run), subject=subject)
        if body["status"] in expected:
            return body, etag
        require(body["status"] not in {"FAILED", "CANCELLED", "EXPIRED", "BUDGET_EXCEEDED"}, "RUN_TERMINATED_" + body["status"])
        time.sleep(0.5)
    raise Failure("RUN_STATE_WAIT_TIMED_OUT")


def create(args):
    config = read(WORK / "prepared.json")
    subject = config["subject"]
    require(not (WORK / "run.json").exists(), "RUN_ALREADY_CREATED_INSPECT_BEFORE_NEW_COMMAND")
    require(not (WORK / "create-command.json").exists(), "PRIOR_CREATE_ATTEMPT_EXISTS_RECONCILE_OR_REUSE_EXACT_KEY_MANUALLY")
    active = read(PHASE / "platform.private.json")
    require(any(r["releaseId"] == RELEASE_ID for r in active["releases"]), "ROOT_MUST_APPLY_STAGED_CONFIG_FIRST")
    refs = json.loads(java("releases", PHASE / "platform.private.json"))
    ref = next((r["releaseRef"] for r in refs if r["releaseRef"]["releaseId"] == RELEASE_ID), None)
    require(ref is not None, "SEED_RELEASE_REFERENCE_MISSING")
    case_key, key = "phase3-unknown-" + uuid.uuid4().hex, "unknown-create-" + uuid.uuid4().hex
    command = {"releaseRef": ref, "inputs": {"caseKey": case_key}, "clientReference": key}
    write(WORK / "create-command.json", {"key": key, "body": command, "subject": subject})
    created, _ = request("POST", route(), command, key=key, subject=subject)
    run = created["run"]["id"]
    state = {"runId": run, "caseKey": case_key, "project": PROJECT, "subject": subject, "releaseRef": ref, "createdAt": utc()}
    write(WORK / "run.json", state)
    waiting, _ = wait_state(run, {"WAITING_APPROVAL"}, subject)
    state["status"] = waiting["status"]
    if args.approve:
        approval, etag = request("GET", route(run) + "/approval", subject="20")
        require(approval["reviewComplete"] and approval["kind"] == "TOOL" and approval["status"] == "PENDING", "COMPLETE_TOOL_APPROVAL_REQUIRED")
        require(any(field.get("name") == "caseKey" and field.get("value") == json.dumps(case_key) and not field.get("masked") for field in approval["fields"]), "APPROVAL_CASE_KEY_MISMATCH")
        key = "unknown-approval-" + uuid.uuid4().hex
        body = {"digest": approval["digest"], "decision": "APPROVE", "reason": "Approve only the local synthetic fsynced receipt fixture; no external business write"}
        write(WORK / "approval-command.json", {"key": key, "etag": etag, "body": body, "approvalId": approval["id"]})
        request("POST", route(run) + "/approvals/" + approval["id"] + "/decision", body, subject="20", etag=etag, key=key)
        state["status"] = wait_state(run, {"NEEDS_ATTENTION"}, subject)[0]["status"]
    write(WORK / "run.json", state)
    print(json.dumps(state))


def evidence(args):
    state = read(WORK / "run.json")
    config = read(WORK / "prepared.json")
    run = str(uuid.UUID(state["runId"]))
    unknown, etag = request("GET", route(run) + "/unknown-invocation", subject=state["subject"])
    require(unknown["reconcilable"] and unknown["kind"] == "TOOL", "RECONCILABLE_TOOL_UNKNOWN_REQUIRED")
    req = urllib.request.Request(f"http://127.0.0.1:{config['port']}/evidence/{state['caseKey']}",
        headers={"Authorization": "Bearer " + (WORK / "verifier.secret").read_text()})
    with HTTP.open(req, timeout=5) as response:
        receipt = json.load(response)
    require(len(receipt["effects"]) == 1, "EXACTLY_ONE_SYNTHETIC_EFFECT_REQUIRED")
    effect = receipt["effects"][0]
    require(effect["caseKey"] == state["caseKey"] and effect["remoteName"] == "probe_unknown_write", "RECEIPT_SCOPE_MISMATCH")
    sql = "SELECT JSON_OBJECT('project',p.project_id,'application',p.application_id,'snapshot',r.snapshot_json) FROM platform_runs p JOIN harness_runs r ON r.run_id=p.run_id WHERE p.run_id='" + run + "'"
    result = subprocess.run(["C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe",
        "--defaults-extra-file=" + str(PHASE / "platformDb-client.private.ini"), "--database=harness_platform_phase3",
        "--batch", "--raw", "--skip-column-names", "--execute=" + sql], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=15, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    require(result.returncode == 0, "INVOCATION_DB_VERIFICATION_FAILED")
    persisted = json.loads(result.stdout.decode("utf-8"))
    snapshot = persisted["snapshot"]
    if isinstance(snapshot, str):
        snapshot = json.loads(snapshot)
    pending = snapshot["pending"]
    require(persisted["project"] == PROJECT and persisted["application"] == "platform-console", "PERSISTED_OWNER_SCOPE_MISMATCH")
    require(snapshot["status"] == "NEEDS_ATTENTION" and pending["phase"] == "UNKNOWN" and pending["kind"] == "TOOL", "PERSISTED_UNKNOWN_REQUIRED")
    require(pending["toolKey"] == TOOL and pending["arguments"] == {"caseKey": state["caseKey"]}, "PERSISTED_INVOCATION_MISMATCH")
    evidence_path = WORK / "verified-evidence.private.json"
    if evidence_path.exists():
        verified = read(evidence_path)
        require(verified["runId"] == run and verified["invocation"] == pending["id"], "EXISTING_EVIDENCE_MISMATCH")
    else:
        verified = {"id": "evidence_" + uuid.uuid4().hex, "project": PROJECT, "runId": run,
            "invocation": pending["id"], "digest": unknown["invocationDigest"],
            "result": {"output": effect["result"], "error": False, "receipt": effect["receipt"]},
            "verifier": "phase3-independent-loopback-fixture-receipt-v1", "verifiedAt": int(time.time() * 1000)}
        write(evidence_path, verified)
    if args.import_verified:
        credentials = read(PHASE / "credentials.private.json")
        env = {**os.environ, "HARNESS_JDBC_URL": "jdbc:mysql://127.0.0.1:53955/harness_platform_phase3?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
               "HARNESS_JDBC_USER": "harness_p3", "HARNESS_JDBC_PASSWORD": credentials["platformDb"]}
        java("import-evidence", evidence_path, env=env)
    output = {"runId": run, "project": PROJECT, "evidenceRef": verified["id"], "invocationRef": unknown["invocationRef"],
        "invocationDigest": unknown["invocationDigest"], "etag": etag, "independentEffectCount": 1,
        "receiptSha256": hashlib.sha256(json.dumps(effect, sort_keys=True).encode()).hexdigest(),
        "imported": args.import_verified, "reconciliationSubmitted": False,
        "consoleUrl": "http://127.0.0.1:8099/console/#/runs/" + run}
    write(WORK / "reconciliation-ready.json", output)
    print(json.dumps(output))


def inspect(args):
    state = read(WORK / "run.json")
    body, _ = request("GET", route(state["runId"]), subject=state["subject"])
    ready = read(WORK / "fixture.ready.json")
    journal = Path(ready["runtime"]) / "fixture-journal.private.jsonl"
    rows = [json.loads(line) for line in journal.read_text().splitlines()]
    scoped = [row for row in rows if row.get("caseKey") == state["caseKey"]]
    dispatches = sum(row["kind"] == "dispatch" for row in scoped)
    effects = sum(row["kind"] == "effect" for row in scoped)
    require(dispatches == 1 and effects == 1, "SYNTHETIC_INVOCATION_NOT_EXACTLY_ONCE")
    result = {"runId": state["runId"], "status": body["status"], "revision": body["revision"],
        "toolCalls": body["usage"]["toolCalls"], "output": body.get("output"), "dispatchCount": dispatches,
        "effectCount": effects, "checkedAt": utc(), "reconciliationCompleted": body["status"] == "COMPLETED"}
    write(WORK / "final-inspection.json", result)
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--port", type=int, default=53966)
    prep.add_argument("--subject", choices=["10", "20"], default="10")
    sub.add_parser("serve")
    creator = sub.add_parser("create")
    creator.add_argument("--approve", action="store_true")
    verifier = sub.add_parser("evidence")
    verifier.add_argument("--import-verified", action="store_true")
    sub.add_parser("inspect")
    args = parser.parse_args()
    try:
        {"prepare": prepare, "serve": serve, "create": create, "evidence": evidence, "inspect": inspect}[args.command](args)
        return 0
    except Exception as failure:
        print(str(failure) if isinstance(failure, Failure) else type(failure).__name__, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
