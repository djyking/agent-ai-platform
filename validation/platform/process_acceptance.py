"""Real local Java process / MySQL / authenticated HTTP acceptance.

Only child Java PIDs created here are terminated. MySQL restart is an explicit
file handshake with its owning controller. Secrets never enter the public report.
"""
from __future__ import annotations

import argparse
import concurrent.futures
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
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from wire_fixture import WireFixture, utc


class CheckFailure(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def digest(value):
    raw = value if isinstance(value, bytes) else json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def write_json(path, value, new=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    if new:
        with path.open("x", encoding="utf-8") as output:
            json.dump(value, output, indent=2, ensure_ascii=False)
    else:
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")
        temporary.replace(path)


def under(path, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    require(path.is_relative_to(root), "PRIVATE_PATH_OUTSIDE_WORKSPACE_WORK")
    return path


def manifest(config, metadata):
    project, application = config["project"], metadata["applicationId"]
    tools = ["probe_read", "probe_hold_read", "probe_write", "probe_unknown_write"]
    schema = {"type": "object", "required": ["caseKey"], "additionalProperties": False,
        "properties": {"caseKey": {"type": "string", "pattern": "^platform-[a-z0-9_-]{1,110}$"}}}
    releases = []
    names = {"read": "probe_read", "hold-read": "probe_hold_read", "approved-write": "probe_write", "unknown-write": "probe_unknown_write"}
    for kind in [*names, "human", "model-then-human"]:
        nodes = {"end": {"kind": "end", "output": "result"}}
        model = None
        keys, permissions = [], []
        if kind in names:
            key = "mcp:acceptance-fixture:" + names[kind]
            nodes["start"] = {"kind": "tool", "toolName": key, "argumentsJson": "{}", "argumentBindings": {"caseKey": "caseKey"}, "output": "result", "next": "end"}
            keys, permissions = [key], ["tool:" + key]
        else:
            nodes["human"] = {"kind": "human", "message": "Enter the synthetic acceptance marker after review", "output": "result", "next": "end"}
            if kind == "model-then-human":
                model = {"id": "fixture-process-budget", "provider": "fixture-model", "model": "synthetic-process-acceptance", "contextTokens": 16000, "maxOutputTokens": 128, "timeoutMillis": 10000, "parameters": {}}
                nodes["start"] = {"kind": "model", "prompt": {"id": "fixture-budget", "version": "1", "system": "Synthetic acceptance only", "user": "Return a marker for {{caseKey}}", "variables": {"caseKey": "STRING"}}, "inputBindings": {"caseKey": "caseKey"}, "output": "modelResult", "next": "human"}
                permissions = ["model:invoke"]
        start = "human" if kind == "human" else "start"
        releases.append({"projectId": project, "agentId": "acceptance-" + kind,
            "releaseId": str(uuid.uuid5(uuid.NAMESPACE_URL, config["runTag"] + "/" + kind)),
            "workflow": {"id": "acceptance-" + kind, "version": "1", "start": start, "maxTransitions": 12, "nodes": nodes},
            "model": model, "inputSchema": schema, "outputSchema": {"type": "object"}, "toolKeys": keys,
            "executionPermissions": permissions, "approvers": [application + "/" + config["reviewerSubject"]],
            "reviewFields": ["caseKey"], "humanInputSchema": {"type": "string", "minLength": 1, "maxLength": 200},
            "publicOutput": True, "limits": {"maxTokens": 2000, "maxModelCalls": 1, "maxToolCalls": 2, "maxSteps": 30, "lifetimeSeconds": 1800}})
    return {"identityOrigin": metadata["authOrigin"], "signingSecret": "env:ACCEPTANCE_SIGNING_KEY",
        "applicationSecrets": {application: "env:ACCEPTANCE_APPLICATION_TOKEN"},
        "projects": [{"id": project, "applications": [application], "maxActiveRuns": 2, "maxReservedTokens": 4000,
            "maxActiveRunsPerApplication": 2, "maxReservedTokensPerApplication": 4000, "concurrency": 1}],
        "releases": releases, "disabledReleases": [],
        "tools": [{"kind": "mcp", "serverId": "acceptance-fixture", "endpoint": "http://127.0.0.1:53957/mcp",
            "secretRef": "env:ACCEPTANCE_FIXTURE_TOKEN", "bindings": [{"remoteName": name,
                "readOnly": name in ("probe_read", "probe_hold_read"), "argumentSchema": schema} for name in tools]}],
        "models": [{"kind": "deepseek", "provider": "fixture-model", "endpoint": "http://127.0.0.1:53957/chat/completions", "secretRef": "env:ACCEPTANCE_FIXTURE_TOKEN"}],
        "concurrency": 1, "leaseMillis": 90000, "workerEnabled": True}


def prepare(args):
    workspace = Path(args.workspace).resolve()
    tag = args.tag or "platform-" + time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:8]
    directory = under(workspace / ".work" / tag, workspace / ".work")
    require(not directory.exists(), "CHOOSE_NEW_ACCEPTANCE_DIRECTORY")
    jar = Path(args.jar).resolve() if args.jar else workspace / "harness-platform-service/target/harness-platform-service-0.1.0-SNAPSHOT.jar"
    require(jar.is_file(), "PLATFORM_JAR_NOT_BUILT")
    source_jar = jar
    metadata_file = workspace / ".work/mysql-phase12/ops-host-metadata.private.json"
    metadata = json.loads(metadata_file.read_text(encoding="utf-8-sig"))
    directory.mkdir(parents=True, exist_ok=False)
    jar = directory / "platform-service.frozen.jar"
    shutil.copyfile(source_jar, jar)
    config = {"schemaVersion": 1, "workspace": str(workspace), "workDir": str(directory), "runTag": tag,
        "java": str(Path(args.java).resolve()), "jar": str(jar), "sourceJar": str(source_jar), "jarSha256": digest(jar.read_bytes()),
        "project": args.project, "reviewerSubject": "20", "ownerTokenField": "user10Token", "reviewerTokenField": "user20Token",
        "identityMetadataFile": str(metadata_file), "ports": {"A": 53958, "B": 53959}, "fixturePort": 53957,
        "mysql": {"client": "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe", "host": "127.0.0.1", "port": 53955,
            "database": "harness_platform_phase2", "user": "harness_platform", "passwordDpapi": str(workspace / ".work/mysql-phase12/platform.dpapi")},
        "signingKeyDpapi": str(workspace / ".work/mysql-phase12/signing.dpapi"),
        "manifest": str(directory / "manifest.private.json"), "mysqlRestartHandshakeDirectory": str(directory / "mysql-restart"),
        "report": str(workspace / "docs/validation/20260918" / (tag + "-report.json"))}
    write_json(directory / "config.private.json", config, new=True)
    write_json(Path(config["manifest"]), manifest(config, metadata), new=True)
    print(json.dumps({"prepared": True, "config": str(directory / "config.private.json"), "project": args.project,
        "jarSha256": config["jarSha256"], "identityOrigin": metadata["authOrigin"], "noServicesStarted": True}))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


class Runner:
    def __init__(self, config_path):
        self.config = json.loads(Path(config_path).read_text(encoding="utf-8-sig"))
        c = self.config
        self.workspace = Path(c["workspace"]).resolve()
        self.work = under(c["workDir"], self.workspace / ".work")
        require(re.fullmatch(r"platform-[a-z0-9_-]{8,70}", c["runTag"]), "INVALID_RUN_TAG")
        require(c["mysql"]["host"] == "127.0.0.1" and c["mysql"]["port"] == 53955 and c["mysql"]["database"] == "harness_platform_phase2", "MYSQL_SCOPE_MUST_BE_DEDICATED")
        require(c["project"] == "platform-acceptance", "DEDICATED_AUTH_PROJECT_REQUIRED")
        require(c["fixturePort"] == 53957 and set(c["ports"].values()) == {53958, 53959}, "UNEXPECTED_LOCAL_PORTS")
        require(Path(c["jar"]).is_file() and digest(Path(c["jar"]).read_bytes()) == c["jarSha256"], "JAR_DIGEST_CHANGED")
        self.metadata = json.loads(under(c["identityMetadataFile"], self.workspace / ".work").read_text(encoding="utf-8-sig"))
        origin = urllib.parse.urlparse(self.metadata["authOrigin"])
        require(origin.scheme == "http" and origin.hostname == "127.0.0.1", "ISOLATED_IDENTITY_ORIGIN_REQUIRED")
        deployment = json.loads(Path(c["manifest"]).read_text(encoding="utf-8"))
        require(deployment["identityOrigin"] == self.metadata["authOrigin"], "IDENTITY_ORIGIN_CHANGED_REPREPARE")
        self.report_path = Path(c["report"]).resolve()
        require(not self.report_path.exists(), "REPORT_ALREADY_EXISTS")
        self.report = {"schemaVersion": 1, "command": "local-platform-process-acceptance", "startedAt": utc(), "status": "RUNNING",
            "runTag": c["runTag"], "project": c["project"], "jarSha256": c["jarSha256"], "processes": [], "httpEvidence": [], "checks": [],
            "runnerSha256": digest(Path(__file__).read_bytes()), "fixtureSha256": digest(Path(__file__).with_name("wire_fixture.py").read_bytes()),
            "manifestSha256": digest(Path(c["manifest"]).read_bytes()),
            "boundaries": {"twoRealJavaApiWorkerProcesses": True, "realMySql": True, "identityMysqlBacked": bool(self.metadata.get("mysqlBacked")),
                "originalProductionIdentityBootConfiguration": bool(self.metadata.get("originalProductionBootConfiguration")),
                "modelWasScripted": True, "toolsWereLocalSyntheticFixture": True, "githubWrites": 0,
                "mysqlRestartOwner": "external owning controller; this runner never terminates mysqld"}}
        self.processes, self.logs, self.runs = {}, [], []
        self.http = urllib.request.build_opener(NoRedirect())
        self.tokens = {name: secrets.token_urlsafe(32) for name in ("A", "B")}
        self.verifier_token = secrets.token_urlsafe(32)
        self.fixture = None
        self.base_env = os.environ.copy()
        self.base_env.update({"HARNESS_CONFIG": c["manifest"], "HARNESS_JDBC_URL": "jdbc:mysql://127.0.0.1:53955/harness_platform_phase2?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000",
            "HARNESS_JDBC_USER": c["mysql"]["user"], "HARNESS_JDBC_PASSWORD": self.dpapi(c["mysql"]["passwordDpapi"]),
            "ACCEPTANCE_SIGNING_KEY": self.dpapi(c["signingKeyDpapi"]), "ACCEPTANCE_APPLICATION_TOKEN": self.metadata["applicationCredential"]})
        self.refs = {}
        write_json(self.report_path, self.report, new=True)

    def save(self):
        write_json(self.report_path, self.report)

    def dpapi(self, path):
        secret_path = under(path, self.workspace / ".work")
        secret_env = {key: value for key, value in os.environ.items() if key.upper() != "PSMODULEPATH"}
        result = subprocess.run([shutil.which("pwsh") or "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(Path(__file__).with_name("read-dpapi.ps1")), "-Path", str(secret_path)],
            env=secret_env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=15)
        require(result.returncode == 0, "DPAPI_SECRET_UNAVAILABLE")
        value = result.stdout.decode("utf-8-sig").strip()
        require(bool(value), "DPAPI_SECRET_EMPTY")
        return value

    def java_command(self, *args, env=None):
        result = subprocess.run([self.config["java"], "-jar", self.config["jar"], *map(str, args)], env=env or self.base_env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=45)
        if result.returncode:
            (self.work / ("java-command-" + uuid.uuid4().hex[:8] + ".private.log")).write_bytes(result.stdout + b"\n" + result.stderr)
        require(result.returncode == 0, "PRIVATE_JAVA_COMMAND_FAILED")
        return result.stdout.decode("utf-8", errors="replace")

    def start(self, label):
        require(label not in self.processes or self.processes[label].poll() is not None, "OWNED_PROCESS_ALREADY_RUNNING")
        port = self.config["ports"][label]
        with socket.socket() as probe:
            require(probe.connect_ex(("127.0.0.1", port)) != 0, "TARGET_PORT_ALREADY_IN_USE")
        env = {**self.base_env, "HARNESS_PORT": str(port), "ACCEPTANCE_FIXTURE_TOKEN": self.tokens[label]}
        log_path = self.work / ("worker-" + label + "-" + uuid.uuid4().hex[:6] + ".private.log")
        output = log_path.open("wb"); self.logs.append(output)
        process = subprocess.Popen([self.config["java"], "-jar", self.config["jar"]], env=env, stdout=output, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.processes[label] = process
        row = {"label": label, "pid": process.pid, "startedAt": utc(), "port": port}
        self.report["processes"].append(row); self.save()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            require(process.poll() is None, "JAVA_PROCESS_EXITED_DURING_STARTUP")
            if self.request(label, "GET", "/health", authenticate=False, record=False)[0] == 200:
                row["healthUpAt"] = utc(); self.save(); return
            time.sleep(0.4)
        raise CheckFailure("JAVA_HEALTH_STARTUP_TIMEOUT")

    def stop(self, label):
        process = self.processes.get(label)
        if process is None or process.poll() is not None:
            return
        process.terminate()  # Only this Popen child; never process-name or tree termination.
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)
        for row in self.report["processes"]:
            if row["pid"] == process.pid and "stoppedAt" not in row:
                row.update({"stoppedAt": utc(), "exitCode": process.returncode, "terminationMode": "owned child terminate; abrupt on Windows"})
        self.save()

    def request(self, worker, method, path, body=None, actor="owner", headers=None, authenticate=True, record=True):
        origin = "http://127.0.0.1:" + str(self.config["ports"][worker])
        supplied = {"Content-Type": "application/json"}
        if authenticate:
            supplied.update({"Authorization": "Bearer " + self.metadata["applicationCredential"],
                "X-Harness-User-Token": self.metadata[self.config["reviewerTokenField" if actor == "reviewer" else "ownerTokenField"]]})
        supplied.update(headers or {})
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request = urllib.request.Request(origin + path, data=data, headers=supplied, method=method)
        try:
            response = self.http.open(request, timeout=12)
        except urllib.error.HTTPError as response_error:
            response = response_error
        except (urllib.error.URLError, TimeoutError, OSError):
            return 0, {}, {}
        with response:
            status = response.status
            raw = response.read(1024 * 1024)
            received_headers = dict(response.headers)
        try:
            result = json.loads(raw) if raw else {}
        except ValueError:
            result = {}
        if record:
            row = {"at": utc(), "worker": worker, "actor": actor if authenticate else "none", "method": method,
                "path": path.split("?", 1)[0], "status": status, "bodyDigest": digest(result)}
            if "code" in result: row["errorCode"] = result["code"]
            if "ETag" in received_headers: row["etagDigest"] = digest(received_headers["ETag"])
            self.report["httpEvidence"].append(row)
        return status, result, {key.lower(): value for key, value in received_headers.items()}

    def route(self, run=None):
        return "/v1/projects/" + self.config["project"] + "/runs" + ("/" + run if run else "")

    def case_key(self, label):
        return self.config["runTag"] + "-" + label

    def create(self, worker, kind, label, expected=202, key=None):
        case_key = self.case_key(label)
        body = {"releaseRef": self.refs[kind], "inputs": {"caseKey": case_key}, "clientReference": case_key}
        status, result, _ = self.request(worker, "POST", self.route(), body, headers={"Idempotency-Key": key or case_key})
        require(status == expected, "CREATE_" + kind.upper().replace("-", "_") + "_HTTP_" + str(status))
        if status != 202:
            return result
        run = result["run"]["id"]; uuid.UUID(run)
        if run not in self.runs: self.runs.append(run)
        return run

    def get(self, run, worker="A"):
        status, view, headers = self.request(worker, "GET", self.route(run))
        require(status == 200, "GET_RUN_HTTP_" + str(status))
        return view, headers.get("etag")

    def wait_state(self, run, states, worker="A", seconds=60):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            view, tag = self.get(run, worker)
            if view["status"] in states:
                return view, tag
            if view["status"] in {"FAILED", "EXPIRED", "BUDGET_EXCEEDED", "CANCELLED"}:
                raise CheckFailure("UNEXPECTED_RUN_STATE_" + view["status"])
            time.sleep(0.2)
        raise CheckFailure("RUN_STATE_TIMEOUT")

    def control(self, run, operation, worker="A", expected=202, etag=None):
        if etag is None: _, etag = self.get(run, worker)
        status, result, _ = self.request(worker, "POST", self.route(run) + "/" + operation, {},
            headers={"Idempotency-Key": "control-" + uuid.uuid4().hex, "If-Match": etag})
        require(status == expected, "CONTROL_" + operation.upper() + "_HTTP_" + str(status))
        return result

    def approve(self, run, worker="A", decision="APPROVE"):
        status, approval, headers = self.request(worker, "GET", self.route(run) + "/approval", actor="reviewer")
        require(status == 200 and approval["reviewComplete"], "APPROVAL_NOT_REVIEWABLE")
        body = {"digest": approval["digest"], "decision": decision, "reason": "Independently reviewed isolated synthetic acceptance"}
        if decision == "APPROVE" and approval["inputRequired"]: body["input"] = "synthetic-approved-input"
        status, _, _ = self.request(worker, "POST", self.route(run) + "/approvals/" + approval["id"] + "/decision", body,
            actor="reviewer", headers={"Idempotency-Key": "approve-" + uuid.uuid4().hex, "If-Match": headers["etag"]})
        require(status == 202, "APPROVAL_DECISION_HTTP_" + str(status))
        return {"approvalId": approval["id"], "digest": approval["digest"], "kind": approval["kind"]}

    def sql(self, query, allow_failure=False):
        db = self.config["mysql"]
        result = subprocess.run([db["client"], "--protocol=TCP", "--host=" + db["host"], "--port=" + str(db["port"]),
            "--user=" + db["user"], "--database=" + db["database"], "--connect-timeout=3", "--batch", "--raw", "--skip-column-names", "--execute=" + query],
            env={**os.environ, "MYSQL_PWD": self.base_env["HARNESS_JDBC_PASSWORD"]}, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=15)
        if allow_failure: return result.returncode == 0
        if result.returncode:
            (self.work / ("mysql-query-" + uuid.uuid4().hex[:8] + ".private.log")).write_bytes(result.stderr)
        require(result.returncode == 0, "MYSQL_EVIDENCE_QUERY_FAILED")
        return result.stdout.decode("utf-8", errors="strict").strip()

    def db_run(self, run):
        uuid.UUID(run)
        query = "SELECT JSON_OBJECT('runId',run_id,'status',run_status,'revision',revision,'fence',fence,'eventSequence',event_sequence," \
            "'deadline',JSON_EXTRACT(snapshot_json,'$.deadline'),'budget',JSON_EXTRACT(snapshot_json,'$.budget')," \
            "'chargedTokens',JSON_EXTRACT(snapshot_json,'$.chargedTokens'),'modelCalls',JSON_EXTRACT(snapshot_json,'$.modelCalls')," \
            "'toolCalls',JSON_EXTRACT(snapshot_json,'$.toolCalls'),'stopReason',JSON_EXTRACT(snapshot_json,'$.stopReason'),'pendingId',JSON_EXTRACT(snapshot_json,'$.pending.id')," \
            "'pendingPhase',JSON_EXTRACT(snapshot_json,'$.pending.phase')) FROM harness_runs WHERE run_id='" + run + "'"
        row = json.loads(self.sql(query))
        counts = self.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.type')),COUNT(*) FROM harness_run_events WHERE run_id='" + run + "' GROUP BY 1")
        row["eventCounts"] = {event_type: int(count) for event_type, count in (line.split("\t") for line in counts.splitlines())}
        return row

    def snapshot(self, run, worker="A"):
        view, _ = self.get(run, worker)
        return {"http": {key: view[key] for key in ("id", "status", "revision", "deadline", "limits", "usage", "controls")}, "db": self.db_run(run)}

    def check(self, name, action):
        row = {"name": name, "startedAt": utc(), "status": "RUNNING"}
        self.report["checks"].append(row); self.save()
        try:
            row["evidence"] = action()
            row["status"] = "PASSED"
            print("Platform process check " + name + ": PASSED", flush=True)
        except Exception as failure:
            row["status"] = "FAILED"
            row["failureCode"] = str(failure) if isinstance(failure, CheckFailure) else type(failure).__name__
            raise
        finally:
            row["finishedAt"] = utc(); self.save()

    def both_workers(self):
        self.start("A")
        first = self.create("A", "read", "worker-a")
        self.wait_state(first, {"COMPLETED"})
        require([r["worker"] for r in self.fixture.rows("dispatch", self.case_key("worker-a"))] == ["A"], "WORKER_A_NOT_OBSERVED")
        self.start("B"); self.stop("A")
        second = self.create("B", "read", "worker-b")
        self.wait_state(second, {"COMPLETED"}, worker="B")
        require([r["worker"] for r in self.fixture.rows("dispatch", self.case_key("worker-b"))] == ["B"], "WORKER_B_NOT_OBSERVED")
        self.start("A")
        return {"workerARun": self.snapshot(first), "workerBRun": self.snapshot(second), "independentWorkerLabelsObserved": ["A", "B"]}

    def http_surface(self):
        key = "create-replay-" + uuid.uuid4().hex
        run = self.create("A", "human", "http-controls", key=key)
        require(self.create("B", "human", "http-controls", key=key) == run, "CROSS_PROCESS_CREATE_REPLAY_CHANGED_ID")
        self.wait_state(run, {"WAITING_INPUT"})
        status, listing, _ = self.request("B", "GET", self.route() + "?limit=100")
        require(status == 200 and any(item["id"] == run for item in listing["items"]), "RUN_MISSING_FROM_LIST")
        status, approval_before, _ = self.request("A", "GET", self.route(run) + "/approval", actor="reviewer")
        require(status == 200, "PRE_PAUSE_APPROVAL_UNAVAILABLE")
        _, stale = self.get(run)
        self.control(run, "pause")
        self.wait_state(run, {"PAUSED"})
        self.control(run, "resume", worker="B", etag=stale, expected=412)
        self.control(run, "resume", worker="B")
        self.wait_state(run, {"WAITING_INPUT"})
        status, approval_after, _ = self.request("B", "GET", self.route(run) + "/approval", actor="reviewer")
        require(status == 200 and all(approval_before[field] == approval_after[field] for field in ("id", "digest", "expiresAt")), "RESUME_CHANGED_PENDING_APPROVAL")
        approval = self.approve(run, worker="B")
        self.wait_state(run, {"COMPLETED"})
        status, events, _ = self.request("A", "GET", self.route(run) + "/events?limit=100")
        require(status == 200 and any(item["type"] == "APPROVAL_DECIDED" for item in events["items"]), "APPROVAL_EVENT_MISSING")
        sequence = [int(row["sequence"]) for row in events["items"]]
        require(sequence == sorted(set(sequence)), "EVENT_SEQUENCE_NOT_MONOTONIC")
        return {"run": self.snapshot(run), "approval": approval, "events": events["items"], "createReplaySameRun": True, "staleEtagRejected": True,
            "sameApprovalPreservedAcrossPauseResume": {field: approval_after[field] for field in ("id", "digest", "expiresAt")}}

    def pause_inflight(self):
        key = self.case_key("held-call")
        run = self.create("A", "hold-read", "held-call")
        end = time.monotonic() + 30
        while not self.fixture.rows("dispatch", key) and time.monotonic() < end: time.sleep(0.05)
        require(len(self.fixture.rows("dispatch", key)) == 1, "HELD_CALL_DID_NOT_DISPATCH")
        self.control(run, "pause", worker="B")
        self.fixture.release(key)
        paused, _ = self.wait_state(run, {"PAUSED"})
        require(paused["usage"]["toolCalls"] == 1, "INFLIGHT_PAUSE_LOST_TOOL_BUDGET")
        self.control(run, "resume")
        self.wait_state(run, {"COMPLETED"})
        require(len(self.fixture.rows("dispatch", key)) == 1, "RESUME_REDISPATCHED_COMMITTED_TOOL")
        return {"run": self.snapshot(run), "dispatchCount": 1}

    def quotas(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(self.create, worker, "human", "quota-" + worker.lower()) for worker in ("A", "B")]
            runs = [future.result() for future in futures]
        for run in runs: self.wait_state(run, {"WAITING_INPUT"})
        denied = self.create("B", "human", "quota-excess", expected=429)
        count = int(self.sql("SELECT COUNT(*) FROM platform_runs p JOIN harness_runs r ON r.run_id=p.run_id WHERE p.project_id='platform-acceptance' AND r.run_status NOT IN ('COMPLETED','FAILED','CANCELLED','EXPIRED','BUDGET_EXCEEDED')"))
        require(count == 2, "SHARED_ACTIVE_QUOTA_INCORRECT")
        before = [self.snapshot(run) for run in runs]
        for run in runs: self.control(run, "cancel"); self.wait_state(run, {"CANCELLED"})
        require(all(self.db_run(run)["status"] == "CANCELLED" for run in runs), "CANCEL_NOT_PERSISTED")
        return {"activeRunCountAtLimit": count, "quotaDeniedHttp": 429, "quotaErrorCode": denied.get("code"), "atLimit": before, "cancelledRunIds": runs}

    def concurrency(self):
        self.fixture.max_active = 0
        runs = []
        # Admission cap is 2, so run 3 pairs through both API processes.
        for batch in range(3):
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(self.create, worker, "read", "concurrent-" + str(batch) + "-" + worker.lower()) for worker in ("A", "B")]
                pair = [future.result() for future in futures]
            for run in pair: self.wait_state(run, {"COMPLETED"})
            runs.extend(pair)
        details = []
        for batch in range(3):
            for worker in ("a", "b"):
                key = self.case_key("concurrent-" + str(batch) + "-" + worker)
                dispatches = self.fixture.rows("dispatch", key)
                require(len(dispatches) == 1, "TWO_WORKERS_DUPLICATED_DISPATCH")
                details.append({"caseKey": key, "dispatchCount": len(dispatches), "worker": dispatches[0]["worker"]})
        require(self.fixture.max_active == 1, "SHARED_CONCURRENCY_SLOT_EXCEEDED")
        return {"maxConcurrentFixtureCalls": self.fixture.max_active, "configuredGlobalAndProjectConcurrency": 1,
            "dispatches": details, "runs": [self.snapshot(run) for run in runs]}

    def approved_write(self):
        key = self.case_key("approved-write")
        run = self.create("A", "approved-write", "approved-write")
        self.wait_state(run, {"WAITING_APPROVAL"})
        require(not self.fixture.rows("effect", key), "EFFECT_BEFORE_HTTP_APPROVAL")
        approval = self.approve(run, worker="B")
        self.wait_state(run, {"COMPLETED"})
        require(len(self.fixture.rows("effect", key)) == 1, "APPROVED_EFFECT_COUNT_INCORRECT")
        return {"approval": approval, "preApprovalEffects": 0, "postApprovalEffects": 1, "run": self.snapshot(run)}

    def java_restart(self):
        run = self.create("A", "model-then-human", "restart-budget")
        self.wait_state(run, {"WAITING_INPUT"})
        before = self.snapshot(run)
        require(before["http"]["usage"]["chargedTokens"] == 22 and before["http"]["usage"]["modelCalls"] == 1, "SCRIPTED_NONZERO_USAGE_NOT_RECORDED")
        prior_pids = {label: process.pid for label, process in self.processes.items()}
        self.stop("A"); self.stop("B")
        self.start("A"); self.start("B")
        after = self.snapshot(run, "B")
        for field in ("deadline", "limits", "usage"):
            require(before["http"][field] == after["http"][field], "JAVA_RESTART_CHANGED_" + field.upper())
        require(all(self.processes[label].pid != pid for label, pid in prior_pids.items()), "JAVA_PID_DID_NOT_CHANGE")
        self.approve(run); self.wait_state(run, {"COMPLETED"})
        require(len(self.fixture.rows("model")) == 1, "JAVA_RESTART_REPEATED_MODEL_CALL")
        return {"before": before, "after": after, "beforePids": prior_pids,
            "afterPids": {label: process.pid for label, process in self.processes.items()}, "modelFixtureCalls": 1,
            "modelWasScripted": True, "actualJavaProcessRestart": True}

    def unknown_reconcile(self):
        key = self.case_key("unknown-write")
        run = self.create("A", "unknown-write", "unknown-write")
        self.wait_state(run, {"WAITING_APPROVAL"})
        require(not self.fixture.rows("effect", key), "UNKNOWN_EFFECT_BEFORE_APPROVAL")
        self.approve(run)
        self.wait_state(run, {"NEEDS_ATTENTION"})
        self.control(run, "resume", expected=409)
        self.control(run, "cancel", worker="B")
        before = self.snapshot(run)
        self.stop("A"); self.stop("B"); self.start("A"); self.start("B")
        require(len(self.fixture.rows("effect", key)) == 1, "UNKNOWN_RESTART_DUPLICATED_EFFECT")
        status, unknown, headers = self.request("A", "GET", self.route(run) + "/unknown-invocation")
        require(status == 200 and unknown["reconcilable"], "UNKNOWN_VIEW_UNAVAILABLE")
        evidence_id = "evidence_" + uuid.uuid4().hex
        body = {"invocationRef": unknown["invocationRef"], "invocationDigest": unknown["invocationDigest"],
            "evidenceRef": evidence_id, "reason": "Independent local synthetic journal receipt verified"}
        status, rejected, _ = self.request("A", "POST", self.route(run) + "/tool-reconciliations", body,
            headers={"Idempotency-Key": "unverified-" + uuid.uuid4().hex, "If-Match": headers["etag"]})
        require(status == 409, "UNVERIFIED_EVIDENCE_ACCEPTED")
        request = urllib.request.Request("http://127.0.0.1:53957/evidence/" + key, headers={"Authorization": "Bearer " + self.verifier_token})
        with self.http.open(request, timeout=5) as response: verified = json.load(response)
        require(len(verified["effects"]) == 1 and verified["effects"][0]["caseKey"] == key, "INDEPENDENT_FIXTURE_RECEIPT_INVALID")
        effect = verified["effects"][0]
        persisted = self.db_run(run)
        require(persisted["pendingPhase"] == "UNKNOWN", "DB_UNKNOWN_NOT_PERSISTED")
        evidence = {"id": evidence_id, "project": self.config["project"], "runId": run, "invocation": persisted["pendingId"],
            "digest": unknown["invocationDigest"], "result": {"output": effect["result"], "error": False, "receipt": effect["receipt"]},
            "verifier": "independent-local-fixture-journal-v1", "verifiedAt": int(time.time() * 1000)}
        evidence_file = self.work / "verified-evidence.private.json"
        write_json(evidence_file, evidence, new=True)
        require(digest(Path(self.config["jar"]).read_bytes()) == self.config["jarSha256"], "JAR_CHANGED_BEFORE_EVIDENCE_IMPORT")
        self.java_command("import-evidence", evidence_file)
        status, _, _ = self.request("A", "POST", self.route(run) + "/tool-reconciliations", body,
            headers={"Idempotency-Key": "verified-" + uuid.uuid4().hex, "If-Match": headers["etag"]})
        require(status == 202, "VERIFIED_RECONCILIATION_HTTP_" + str(status))
        self.wait_state(run, {"CANCELLED"})
        self.control(run, "resume", expected=409)
        require(len(self.fixture.rows("effect", key)) == 1, "RECONCILIATION_REPEATED_EFFECT")
        return {"beforeProcessRestart": before, "afterReconciliation": self.snapshot(run), "effects": 1,
            "independentReceiptDigest": digest(effect), "unverifiedEvidenceHttp": 409, "reconciliationHttp": 202,
            "faultBoundary": "Local synthetic MCP effect fsynced then actual local HTTP connection closed before response",
            "trustedEvidenceImport": "Offline import-evidence CLI after independent fixture GET and database invocation check"}

    def mysql_restart(self):
        run = self.create("A", "model-then-human", "mysql-restart")
        self.wait_state(run, {"WAITING_INPUT"})
        before = self.snapshot(run)
        require(before["http"]["usage"]["chargedTokens"] == 22 and before["http"]["usage"]["modelCalls"] == 1, "MYSQL_RESTART_REQUIRES_NONZERO_USAGE")
        model_calls_before = len(self.fixture.rows("model"))
        directory = under(self.config["mysqlRestartHandshakeDirectory"], self.workspace / ".work")
        directory.mkdir(parents=True, exist_ok=True)
        request_id = uuid.uuid4().hex
        request = {"requestId": request_id, "phase": "ready-for-normal-stop", "at": utc(), "mysqlPort": 53955,
            "database": "harness_platform_phase2", "runId": run, "runnerNeverKillsMysql": True}
        write_json(directory / "stop.request.json", request, new=True)
        print("MYSQL_NORMAL_STOP_READY " + str(directory / "stop.request.json"), flush=True)
        stopped = self.wait_ack(directory / "stop.ack.json", request_id, "stopped")
        health = {worker: self.request(worker, "GET", "/health", authenticate=False)[0] for worker in ("A", "B")}
        require(all(status == 503 for status in health.values()), "MYSQL_OUTAGE_NOT_OBSERVED_BY_BOTH_APIS")
        require(not self.sql("SELECT 1", allow_failure=True), "MYSQL_STILL_ACCEPTS_CONNECTIONS")
        write_json(directory / "start.request.json", {"requestId": request_id, "phase": "ready-for-restart", "at": utc(), "outageHealthStatuses": health}, new=True)
        print("MYSQL_NORMAL_RESTART_READY " + str(directory / "start.request.json"), flush=True)
        started = self.wait_ack(directory / "start.ack.json", request_id, "started")
        require(stopped.get("mysqlPid") != started.get("mysqlPid") and stopped.get("mysqlPid") and started.get("mysqlPid"), "MYSQL_PROCESS_RESTART_NOT_ATTESTED")
        end = time.monotonic() + 90
        while time.monotonic() < end:
            if all(self.request(worker, "GET", "/health", authenticate=False, record=False)[0] == 200 for worker in ("A", "B")): break
            time.sleep(0.5)
        after = self.snapshot(run, "B")
        for field in ("deadline", "limits", "usage"):
            require(before["http"][field] == after["http"][field], "MYSQL_RESTART_CHANGED_" + field.upper())
        self.approve(run, worker="B"); self.wait_state(run, {"COMPLETED"})
        require(len(self.fixture.rows("model")) == model_calls_before, "MYSQL_RESTART_REPEATED_MODEL_CALL")
        return {"before": before, "after": after, "outageHealthStatuses": health,
            "controllerStop": stopped, "controllerStart": started, "recoveredRun": self.snapshot(run), "mysqlProcessRestart": True}

    def wait_ack(self, path, request_id, phase):
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            if path.exists():
                value = json.loads(path.read_text(encoding="utf-8-sig"))
                require(value.get("requestId") == request_id and value.get("phase") == phase, "MYSQL_CONTROLLER_ACK_MISMATCH")
                # Copy only defined safe controller evidence, never arbitrary ack fields.
                return {key: value[key] for key in ("requestId", "phase", "at", "mysqlPid", "normalShutdown", "exitCode") if key in value}
            time.sleep(0.25)
        raise CheckFailure("MYSQL_CONTROLLER_ACK_TIMEOUT")

    def run(self, skip_checks=()):
        try:
            for port in [53957, 53958, 53959]:
                with socket.socket() as probe: require(probe.connect_ex(("127.0.0.1", port)) != 0, "ACCEPTANCE_PORT_ALREADY_IN_USE")
            self.fixture = WireFixture(53957, self.work, self.tokens, self.verifier_token); self.fixture.start()
            refs = json.loads(self.java_command("releases", self.config["manifest"]))
            self.refs = {row["releaseRef"]["agentId"].removeprefix("acceptance-"): row["releaseRef"] for row in refs if row["projectId"] == self.config["project"]}
            require(len(self.refs) == 6, "RELEASE_REFERENCE_COUNT_MISMATCH")
            for name, method in (("two_independent_workers_observed", self.both_workers), ("http_surface_and_cas", self.http_surface),
                ("pause_during_real_inflight_call", self.pause_inflight), ("shared_sql_admission_quota", self.quotas),
                ("two_worker_concurrency_and_no_duplicates", self.concurrency), ("http_approval_gates_synthetic_write", self.approved_write),
                ("real_java_restart_preserves_nonzero_budget", self.java_restart), ("unknown_real_restart_and_verified_reconciliation", self.unknown_reconcile),
                ("mysql_normal_stop_restart_recovery", self.mysql_restart)):
                if name in skip_checks:
                    self.report["checks"].append({"name": name, "status": "NOT_RUN", "reason": "Explicit subset execution; no acceptance claim"})
                else:
                    self.check(name, method)
            self.report["status"] = "SUBSET_PASSED" if skip_checks else "PASSED"
        except Exception as failure:
            self.report["status"] = "FAILED"
            self.report["failureCode"] = str(failure) if isinstance(failure, CheckFailure) else type(failure).__name__
            print("Platform process acceptance FAILED: " + self.report["failureCode"], flush=True)
        finally:
            for label in list(self.processes): self.stop(label)
            if self.fixture:
                self.report["fixtureEvidence"] = {"dispatches": self.fixture.rows("dispatch"), "effectCount": len(self.fixture.rows("effect")),
                    "modelCalls": len(self.fixture.rows("model")), "journalDigest": digest(self.fixture.journal.read_bytes())}
                self.fixture.close()
            for output in self.logs: output.close()
            self.report["finishedAt"] = utc(); self.report["createdRunIds"] = self.runs
            self.save()
        print(json.dumps({"status": self.report["status"], "report": str(self.report_path)}), flush=True)
        return 0 if self.report["status"] in ("PASSED", "SUBSET_PASSED") else 1


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    preparer = sub.add_parser("prepare")
    preparer.add_argument("--workspace", required=True); preparer.add_argument("--java", required=True)
    preparer.add_argument("--jar"); preparer.add_argument("--tag"); preparer.add_argument("--project", default="platform-acceptance")
    runner = sub.add_parser("run"); runner.add_argument("--config", required=True)
    runner.add_argument("--skip-check", action="append", default=[])
    args = parser.parse_args()
    try:
        if args.command == "prepare": prepare(args); return 0
        return Runner(args.config).run(args.skip_check)
    except Exception as failure:
        # Never print arbitrary exception strings that may include a secret-bearing config.
        print("Platform runner failed: " + (str(failure) if isinstance(failure, CheckFailure) else type(failure).__name__), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
