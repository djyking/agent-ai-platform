"""Real-time local observation against the real identity and execution APIs.

Only a deployment-owned, no-model/no-tool synthetic release is accepted. Credentials live
in explicitly supplied private files. Every invocation creates a new observation; a stopped
process cannot resume its predecessor's clock or claim that several short runs lasted 24h.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
from http.client import HTTPConnection, HTTPException, IncompleteRead
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timezone


def utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def percentile(values: list[float], fraction: float = .95) -> float:
    if not values:
        return 0.0
    return round(sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)], 3)


def continuity_failure(previous_wall: float, previous_mono: float,
                       current_wall: float, current_mono: float, max_gap: float) -> str | None:
    mono_delta = current_mono - previous_mono
    wall_delta = current_wall - previous_wall
    if mono_delta < 0 or mono_delta > max_gap:
        return "MONOTONIC_SCHEDULER_GAP"
    if abs(wall_delta - mono_delta) > max_gap:
        return "WALL_MONOTONIC_DIVERGENCE"
    return None


MAX_RESPONSE_BYTES = 2 * 1024 * 1024


def loopback_address(origin: str) -> tuple[str, int]:
    # Validate the original string, not a URL parser's normalized form: parsers may
    # discard control characters or accept userinfo, paths and ambiguous authorities.
    match = re.fullmatch(r"http://(127\.0\.0\.1|localhost)(?::([0-9]{1,5}))?", origin)
    if not match:
        raise ValueError("LOCAL_LOOPBACK_ORIGIN_REQUIRED")
    port = int(match.group(2)) if match.group(2) else 80
    if not 1 <= port <= 65535:
        raise ValueError("LOCAL_LOOPBACK_ORIGIN_REQUIRED")
    return match.group(1), port


def http(origin: str, method: str, path: str, headers=None, body=None, timeout=15):
    host, port = loopback_address(origin)
    if not path.startswith("/") or path.startswith("//") or any(c in path for c in "\r\n#"):
        raise ValueError("RELATIVE_HTTP_REQUEST_TARGET_REQUIRED")
    # Each sample has its own connection. HTTPConnection neither consults proxies nor
    # constructs TLS contexts/loads Windows trust stores, and never follows redirects.
    connection = HTTPConnection(host, port, timeout=timeout)
    try:
        connection.request(
            method, path,
            body=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json", **(headers or {})},
        )
        with connection.getresponse() as response:
            if response.length is not None and response.length > MAX_RESPONSE_BYTES:
                raise ValueError("RESPONSE_TOO_LARGE")
            # One sentinel byte detects oversized bodies without unbounded reads.
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ValueError("RESPONSE_TOO_LARGE")
            if response.length not in (None, 0):
                raise IncompleteRead(raw, response.length)
            return response.status, json.loads(raw) if raw else {}
    finally:
        connection.close()


def atomic_json(path: Path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


class Identity:
    """Normal OpsAgent refresh. No test endpoint, captcha bypass or forged JWT."""
    def __init__(self, origin: str, token_file: Path):
        self.origin = origin
        self.path = token_file
        self.tokens = json.loads(token_file.read_text(encoding="utf-8-sig"))
        self.lock = threading.Lock()
        self.refreshed_at = time.monotonic()
        self.refresh_count = 0
        if not self.tokens.get("accessToken") or not self.tokens.get("refreshToken"):
            raise ValueError("OFFICIAL_LOGIN_TOKENS_REQUIRED")

    def access(self) -> str:
        with self.lock:
            return self.tokens["accessToken"]

    def refresh(self):
        with self.lock:
            status, reply = http(
                self.origin, "POST", "/api/auth/refresh",
                body={"refreshToken": self.tokens["refreshToken"], "lastActivityAt": utc()},
            )
            if status != 200 or reply.get("code") != 0 or not reply.get("data", {}).get("accessToken"):
                raise RuntimeError("OFFICIAL_TOKEN_REFRESH_FAILED")
            next_tokens = reply["data"]
            # Write the rotated token before publishing it to worker threads.
            atomic_json(self.path, next_tokens)
            self.tokens = next_tokens
            self.refreshed_at = time.monotonic()
            self.refresh_count += 1


class Observer:
    def __init__(self, config_file: Path, duration: int):
        self.config = json.loads(config_file.read_text(encoding="utf-8-sig"))
        self.duration = duration
        if not 60 <= duration <= 172800:
            raise ValueError("DURATION_MUST_BE_60_TO_172800_SECONDS")
        if self.config.get("project") != "phase6-soak":
            raise ValueError("DEDICATED_SYNTHETIC_PROJECT_REQUIRED")
        for origin in [self.config["origin"], self.config["authOrigin"]]:
            loopback_address(origin)
        self.origin = self.config["origin"].rstrip("/")
        self.identity = Identity(self.config["authOrigin"].rstrip("/"), Path(self.config["tokenFile"]))
        self.credential = Path(self.config["applicationCredentialFile"]).read_text().strip()
        self.project = self.config["project"]
        self.route = "/v1/projects/" + self.project + "/runs"
        self.ref = self.config["releaseRef"]
        self.tag = "soak-" + uuid.uuid4().hex
        self.directory = Path(self.config["reportDirectory"]) / self.tag
        self.directory.mkdir(parents=True, exist_ok=False)
        self.db = sqlite3.connect(self.directory / "commands.sqlite3")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE commands(sequence INTEGER PRIMARY KEY,request_key TEXT NOT NULL UNIQUE,run_id TEXT UNIQUE,status INTEGER NOT NULL,latency_ms REAL NOT NULL)")
        self.db.commit()
        self.start = time.monotonic()
        self.start_wall = time.time()
        self.last_wall = self.start_wall
        self.last_mono = self.start
        self.windows: list[dict] = []
        self.total = {"requests": 0, "getRequests": 0, "postRequests": 0, "errors": 0}
        self.histogram: dict[int, int] = {}
        self.window: list[dict] = []
        self.process_bindings = self.read_process_bindings()
        self.initial_workers = None
        self.report = {
            "status": "RUNNING", "observationId": self.tag, "startedAt": utc(),
            "requestedSeconds": duration, "elapsedMonotonicSeconds": 0,
            "completed24Hours": False, "rps": 10, "readWriteRatio": "90:10",
            "continuous": True, "processBindings": self.process_bindings,
            "boundaries": {"syntheticData": True, "externalModelCalls": 0, "externalToolCalls": 0,
                           "identity": "real OpsAgent authority", "linuxSevenDayObservation": False,
                           "resumeOrConcatenateAllowed": False, "hostSleepNotPrevented": True},
            "thresholds": {"apiP95Millis": 500, "maxSchedulerGapSeconds": 5, "requiredDurationSeconds": 86400},
        }
        self.save()
        try:
            self.validate_release()
        except BaseException as failure:
            self.report["status"] = "FAILED_PREFLIGHT"
            self.report["failureCode"] = str(failure) if str(failure).isupper() else type(failure).__name__
            self.report["finishedAt"] = utc()
            self.save()
            self.db.close()
            raise

    def read_process_bindings(self):
        result = {}
        for item in self.config.get("processRecordFiles", []):
            path = Path(item)
            value = json.loads(path.read_text(encoding="utf-8"))
            result[path.name] = {key: value.get(key) for key in ["pid", "sha256", "port", "startedAt"]}
        return result

    def headers(self, key=None):
        headers = {"Authorization": "Bearer " + self.credential,
                   "X-Harness-User-Token": self.identity.access()}
        if key:
            headers["Idempotency-Key"] = key
        return headers

    def validate_release(self):
        manifest = json.loads(Path(self.config["deploymentFile"]).read_text(encoding="utf-8-sig"))
        matches = [release for release in manifest["releases"]
                   if release["projectId"] == self.project and release["releaseId"] == self.ref["releaseId"]]
        if len(matches) != 1:
            raise ValueError("SYNTHETIC_RELEASE_NOT_IN_DEPLOYMENT")
        release = matches[0]
        workflow = release["workflow"]
        if (release.get("model") is not None or release.get("toolKeys")
                or release.get("executionPermissions")
                or workflow["nodes"] != {"end": {"kind": "end", "output": "value"}}
                or workflow["start"] != "end"):
            raise ValueError("NO_EXTERNAL_INVOCATIONS_RELEASE_REQUIRED")
        self.report["releaseRef"] = self.ref
        self.report["deploymentFileSha256"] = hashlib.sha256(
            Path(self.config["deploymentFile"]).read_bytes()).hexdigest()

    def save(self):
        elapsed = time.monotonic() - self.start
        self.report.update(elapsedMonotonicSeconds=round(elapsed, 3), updatedAt=utc(),
                           requests=dict(self.total), authRefreshes=self.identity.refresh_count,
                           completedWindows=len(self.windows))
        atomic_json(self.directory / "report.json", self.report)

    def measure(self, sequence: int):
        method = "POST" if sequence % 10 == 0 else "GET"
        key = self.tag + "-" + str(sequence)
        before = time.perf_counter()
        try:
            body = None
            path = self.route + "?limit=20"
            if method == "POST":
                path = self.route
                body = {"releaseRef": self.ref, "inputs": {"value": "SYNTHETIC_PHASE6_OBSERVATION"},
                        "clientReference": key}
            status, reply = http(self.origin, method, path, self.headers(key if method == "POST" else None), body)
            run_id = reply.get("run", {}).get("id") if method == "POST" else None
        except (OSError, ValueError, TimeoutError, HTTPException):
            status, run_id = 0, None
        return {"sequence": sequence, "method": method, "status": status,
                "latencyMillis": round((time.perf_counter() - before) * 1000, 3),
                "key": key if method == "POST" else None, "runId": run_id}

    def collect(self, value):
        self.total["requests"] += 1
        self.total["postRequests" if value["method"] == "POST" else "getRequests"] += 1
        expected = 202 if value["method"] == "POST" else 200
        if value["status"] != expected or value["method"] == "POST" and not value["runId"]:
            self.total["errors"] += 1
        if value["method"] == "POST":
            self.db.execute("UPDATE commands SET run_id=?,status=?,latency_ms=? WHERE sequence=? AND request_key=?",
                            (value["runId"], value["status"], value["latencyMillis"],
                             value["sequence"], value["key"]))
        bucket = min(60000, math.ceil(value["latencyMillis"]))
        self.histogram[bucket] = self.histogram.get(bucket, 0) + 1
        self.window.append(value)

    def inspect_service(self):
        status, value = http(self.origin, "GET", "/v1/projects/" + self.project + "/operations", self.headers())
        if status != 200:
            return {"status": status, "available": False}
        execution = value.get("execution", {})
        now = execution.get("databaseTime", value.get("generatedAt", time.time() * 1000))
        workers = [
            {key: worker.get(key) for key in ["workerId", "state", "heartbeatAt", "activeCount", "fingerprintMatches"]}
            for worker in execution.get("workers", [])
            if worker.get("heartbeatAt", 0) >= now - 45000
            and worker.get("state") == "RUNNING" and worker.get("fingerprintMatches") is True
        ]
        ids = sorted(worker["workerId"] for worker in workers)
        if self.initial_workers is None:
            self.initial_workers = ids
        return {"status": status, "available": True, "queue": value.get("queue"),
                "statusCounts": value.get("statusCounts"), "usage": value.get("usage"),
                "workers": workers, "workerSetChanged": ids != self.initial_workers,
                "unresolvedPermits": execution.get("unknownPermitCount", len(execution.get("unknownPermits", [])))}

    def flush_window(self):
        if not self.window:
            return
        reads = [v["latencyMillis"] for v in self.window if v["method"] == "GET"]
        writes = [v["latencyMillis"] for v in self.window if v["method"] == "POST"]
        errors = sum(v["status"] != (202 if v["method"] == "POST" else 200)
                     or v["method"] == "POST" and not v["runId"] for v in self.window)
        evidence = {"window": len(self.windows) + 1, "at": utc(),
                    "elapsedMonotonicSeconds": round(time.monotonic() - self.start, 3),
                    "requests": len(self.window), "getRequests": len(reads), "postRequests": len(writes),
                    "errors": errors, "errorRate": errors / len(self.window),
                    "p95Millis": percentile(reads + writes),
                    "getP95Millis": percentile(reads), "postP95Millis": percentile(writes),
                    "service": self.inspect_service()}
        with (self.directory / "windows.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(evidence, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        self.db.commit()
        self.windows.append(evidence)
        self.window = []
        self.report["latestWindow"] = evidence
        self.save()

    def run(self):
        pending = set()
        sequence = 0
        next_window = self.start + 60
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=20)
        try:
            while time.monotonic() - self.start < self.duration:
                now, wall = time.monotonic(), time.time()
                reason = continuity_failure(self.last_wall, self.last_mono, wall, now, 5)
                if reason:
                    self.report["continuous"] = False
                    self.report["gap"] = {"reason": reason, "at": utc(),
                                          "monotonicGapSeconds": now - self.last_mono,
                                          "wallGapSeconds": wall - self.last_wall}
                    raise RuntimeError(reason)
                self.last_mono, self.last_wall = now, wall
                for future in list(pending):
                    if future.done():
                        pending.remove(future)
                        self.collect(future.result())
                if len(pending) >= 200:
                    raise RuntimeError("LOAD_GENERATOR_BACKLOG")
                if now - self.identity.refreshed_at >= 600:
                    self.identity.refresh()
                target = self.start + sequence / 10
                if now >= target:
                    if now - target > 3:
                        self.report["continuous"] = False
                        raise RuntimeError("OFFERED_LOAD_SCHEDULER_GAP")
                    if sequence % 10 == 0:
                        # Record the command before dispatch: an observer crash cannot erase the
                        # only reconciliation key for a possibly accepted write.
                        self.db.execute("INSERT INTO commands VALUES(?,?,NULL,-1,0)",
                                        (sequence, self.tag + "-" + str(sequence)))
                        self.db.commit()
                    pending.add(pool.submit(self.measure, sequence))
                    sequence += 1
                else:
                    time.sleep(min(.025, target - now))
                if now >= next_window:
                    if self.read_process_bindings() != self.process_bindings:
                        self.report["continuous"] = False
                        raise RuntimeError("DEPLOYMENT_PROCESS_BINDING_CHANGED")
                    self.flush_window()
                    next_window += 60
            for future in concurrent.futures.as_completed(pending):
                pending.remove(future)
                self.collect(future.result())
            if self.read_process_bindings() != self.process_bindings:
                self.report["continuous"] = False
                raise RuntimeError("DEPLOYMENT_PROCESS_BINDING_CHANGED")
            self.flush_window()
            self.report["histogramCeilingMillis"] = self.histogram
            self.report["qualityPassed"] = self.total["errors"] == 0 and all(
                row["errors"] == 0 and max(row["p95Millis"], row["getP95Millis"], row["postP95Millis"]) <= 500
                and row["service"].get("available")
                and len(row["service"].get("workers", [])) >= 2
                and not row["service"].get("workerSetChanged")
                and not row["service"].get("unresolvedPermits")
                for row in self.windows
            )
            elapsed = time.monotonic() - self.start
            self.report["completed24Hours"] = self.duration >= 86400 and elapsed >= 86400 and self.report["continuous"]
            self.report["status"] = (
                "PASSED" if self.report["completed24Hours"] and self.report["qualityPassed"]
                else "SHORT_RUN_PASSED" if self.report["qualityPassed"]
                else "FAILED_SERVICE_TARGET"
            )
        except BaseException as failure:
            self.report["status"] = "INTERRUPTED" if isinstance(failure, KeyboardInterrupt) else "FAILED"
            self.report["failureCode"] = str(failure) if str(failure).isupper() else type(failure).__name__
            self.report["completed24Hours"] = False
            for future in pending:
                future.cancel()
        finally:
            pool.shutdown(wait=True, cancel_futures=True)
            # Preserve responses completed during failure shutdown; a write is never retried.
            for future in pending:
                if future.done() and not future.cancelled():
                    try:
                        self.collect(future.result())
                    except Exception:
                        self.report["status"] = "FAILED"
                        self.report["failureCode"] = "RESPONSE_RECORD_FAILED"
                        self.report["completed24Hours"] = False
            self.report["unreconciledCommands"] = self.db.execute(
                "SELECT COUNT(*) FROM commands WHERE run_id IS NULL").fetchone()[0]
            self.db.commit()
            self.db.close()
            self.report["finishedAt"] = utc()
            self.save()
        print(json.dumps({"status": self.report["status"], "report": str(self.directory / "report.json"),
                          "completed24Hours": self.report["completed24Hours"]}))
        return 0 if self.report["status"] in ["PASSED", "SHORT_RUN_PASSED"] else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=86400)
    args = parser.parse_args()
    observer = Observer(args.config, args.duration)
    return observer.run()


if __name__ == "__main__":
    raise SystemExit(main())
