"""Real-time local observation against the real identity and execution APIs.

Only a deployment-owned, no-model/no-tool synthetic release is accepted. Credentials live
in explicitly supplied private files. Every invocation creates a new observation; a stopped
process cannot resume its predecessor's clock or claim that several short runs lasted 24h.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import errno as errno_codes
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
MAX_CONNECTION_IDLE_SECONDS = 5
KNOWN_REQUEST_PHASES = frozenset({
    "VALIDATE", "ENCODE_BODY", "AUTH_HEADERS", "CONNECT", "SEND",
    "RESPONSE_HEADERS", "RESPONSE_BODY", "DECODE_JSON", "RESPONSE_VALIDATION",
    "PERSIST_IDENTITY", "PREFLIGHT", "OBSERVER",
})
# Fixed numeric allowlists, never exception text or arbitrary exception attributes.
KNOWN_WINSOCK_ERRORS = frozenset({
    10004, 10009, 10013, 10014, 10022, 10024, 10035, 10036, 10037, 10038,
    10039, 10040, 10041, 10042, 10043, 10044, 10045, 10046, 10047, 10048,
    10049, 10050, 10051, 10052, 10053, 10054, 10055, 10056, 10057, 10058,
    10060, 10061, 10064, 10065, 10067, 10091, 10092, 10093, 10101,
    11001, 11002, 11003, 11004,
})
KNOWN_ERRNOS = KNOWN_WINSOCK_ERRORS | frozenset(
    getattr(errno_codes, name) for name in (
        "EACCES", "EPERM", "EADDRINUSE", "EADDRNOTAVAIL", "ECONNABORTED",
        "ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "EINTR", "EINVAL",
        "EISCONN", "EMFILE", "ENFILE", "ENETDOWN", "ENETRESET", "ENETUNREACH",
        "ENOBUFS", "ENOMEM", "ENOTCONN", "EPIPE", "ETIMEDOUT", "EWOULDBLOCK",
        "EINPROGRESS", "EALREADY",
    ) if hasattr(errno_codes, name)
)
KNOWN_WINERRORS = KNOWN_WINSOCK_ERRORS | frozenset({64, 121, 995, 996, 997, 1225, 1231, 1232})
KNOWN_FAILURE_CODES = frozenset({
    "LOCAL_LOOPBACK_ORIGIN_REQUIRED", "RELATIVE_HTTP_REQUEST_TARGET_REQUIRED",
    "RESPONSE_TOO_LARGE", "OFFICIAL_TOKEN_REFRESH_FAILED",
    "SYNTHETIC_RELEASE_NOT_IN_DEPLOYMENT", "NO_EXTERNAL_INVOCATIONS_RELEASE_REQUIRED",
    "MONOTONIC_SCHEDULER_GAP", "WALL_MONOTONIC_DIVERGENCE", "LOAD_GENERATOR_BACKLOG",
    "OFFERED_LOAD_SCHEDULER_GAP", "DEPLOYMENT_PROCESS_BINDING_CHANGED",
    "RESPONSE_RECORD_FAILED", "SERVICE_RESPONSE_INVALID_SHAPE",
    "TRANSPORT_CLOSED",
})
KNOWN_EXCEPTION_CLASSES = frozenset({
    "TimeoutError", "ConnectionError", "ConnectionRefusedError", "ConnectionResetError",
    "ConnectionAbortedError", "BrokenPipeError", "OSError", "HTTPException",
    "BadStatusLine", "IncompleteRead", "RemoteDisconnected", "LineTooLong",
    "InvalidURL", "ResponseNotReady", "CannotSendRequest", "CannotSendHeader",
    "NotConnected", "JSONDecodeError", "UnicodeDecodeError", "ValueError",
    "TypeError", "AttributeError", "RuntimeError", "KeyboardInterrupt",
})
KNOWN_DIAGNOSTIC_CODES = KNOWN_FAILURE_CODES | frozenset({
    "TRANSPORT_TIMEOUT", "RESPONSE_TRUNCATED", "HTTP_PROTOCOL_ERROR",
    "RESPONSE_INVALID_JSON", "TRANSPORT_ERROR", "OBSERVER_EXCEPTION",
    "HTTP_UNEXPECTED_STATUS", "RESPONSE_INVALID_SHAPE", "RESPONSE_RUN_ID_MISSING",
})


def safe_exception(failure):
    """Never serialize exception text, response bodies, paths or arbitrary server error codes."""
    name = type(failure).__name__
    name = name if name in KNOWN_EXCEPTION_CLASSES else "Exception"
    message = str(failure)
    if message in KNOWN_FAILURE_CODES:
        code = message
    elif isinstance(failure, TimeoutError):
        code = "TRANSPORT_TIMEOUT"
    elif isinstance(failure, IncompleteRead):
        code = "RESPONSE_TRUNCATED"
    elif isinstance(failure, HTTPException):
        code = "HTTP_PROTOCOL_ERROR"
    elif isinstance(failure, (json.JSONDecodeError, UnicodeError)):
        code = "RESPONSE_INVALID_JSON"
    elif isinstance(failure, OSError):
        code = "TRANSPORT_ERROR"
    else:
        code = "OBSERVER_EXCEPTION"
    return name, code


def safe_error_number(value, allowed):
    return value if type(value) is int and value in allowed else None


def safe_error_numbers(failure):
    return {
        "errno": safe_error_number(getattr(failure, "errno", None), KNOWN_ERRNOS),
        "winerror": safe_error_number(getattr(failure, "winerror", None), KNOWN_WINERRORS),
    }


def close_connection(connection):
    try:
        connection.close()
        return True
    except Exception:
        # Cleanup must not replace the original request error or its phase.
        return False


class LoopbackTransport:
    """One connection per origin per thread; never resend a failed sample.

    Five seconds is a conservative client idle ceiling, not a promise that servers
    cannot close sooner. A stale socket failure still counts as a failed sample.
    opened counts allocated single-connect attempts, including failed connects.
    discarded includes failures, idle expiry, server close and final cleanup.
    """
    def __init__(self):
        self.local = threading.local()
        self.lock = threading.Lock()
        self.connections = set()
        self.closed = False
        self.opened = self.reused = self.discarded = 0

    def acquire(self, origin, host, port, timeout):
        entries = getattr(self.local, "entries", None)
        if entries is None:
            entries = self.local.entries = {}
        entry = entries.get(origin)
        if entry is not None:
            connection, finished_at, previous_timeout = entry
            # Retire idle connections before sending; this is not a request retry.
            if (connection.sock is None or timeout != previous_timeout
                    or time.monotonic() - finished_at >= MAX_CONNECTION_IDLE_SECONDS):
                self.discard(origin, connection)
                entry = None
        with self.lock:
            if self.closed:
                raise RuntimeError("TRANSPORT_CLOSED")
            if entry is not None:
                self.reused += 1
                return entry[0]
            connection = HTTPConnection(host, port, timeout=timeout)
            # http() connects explicitly once. Never let send() reconnect implicitly.
            connection.auto_open = 0
            self.connections.add(connection)
            self.opened += 1
        entries[origin] = (connection, time.monotonic(), timeout)
        return connection

    def release(self, origin, connection):
        entries = self.local.entries
        if entries.get(origin, (None,))[0] is connection:
            entries[origin] = (connection, time.monotonic(), entries[origin][2])

    def discard(self, origin, connection):
        entries = getattr(self.local, "entries", {})
        if entries.get(origin, (None,))[0] is connection:
            del entries[origin]
        with self.lock:
            if connection in self.connections:
                self.connections.remove(connection)
                self.discarded += 1
        close_connection(connection)

    def snapshot(self):
        with self.lock:
            return {
                "policy": "THREAD_LOCAL_ORIGIN_KEEP_ALIVE", "threadLocal": True,
                "noRetry": True, "maxIdleSeconds": MAX_CONNECTION_IDLE_SECONDS,
                "opened": self.opened, "reused": self.reused,
                "discarded": self.discarded, "active": len(self.connections),
            }

    def close_all(self):
        # The owner calls this only after all worker tasks have stopped.
        with self.lock:
            self.closed = True
            connections = tuple(self.connections)
            self.connections.clear()
            self.discarded += len(connections)
        for connection in connections:
            close_connection(connection)
        self.local.entries = {}


def sample_failed(value):
    return (value["status"] != (202 if value["method"] == "POST" else 200)
            or value.get("code") is not None
            or value["method"] == "POST" and not value["runId"])


def window_failure(window):
    if window["errors"]:
        return "REQUEST_ERRORS"
    if max(window["p95Millis"], window["getP95Millis"], window["postP95Millis"]) > 500:
        return "WINDOW_P95_EXCEEDED"
    service = window["service"]
    if not service.get("available"):
        return "SERVICE_OBSERVATION_UNAVAILABLE"
    if len(service.get("workers", [])) < 2:
        return "INSUFFICIENT_COMPATIBLE_WORKERS"
    if service.get("workerSetChanged"):
        return "WORKER_SET_CHANGED"
    if service.get("unresolvedPermits"):
        return "UNRESOLVED_EXTERNAL_PERMITS"
    return None


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


def http(origin: str, method: str, path: str, headers=None, body=None, timeout=15, metadata=None,
         transport=None):
    metadata = {} if metadata is None else metadata
    metadata["phase"] = "VALIDATE"
    host, port = loopback_address(origin)
    if not path.startswith("/") or path.startswith("//") or any(c in path for c in "\r\n#"):
        raise ValueError("RELATIVE_HTTP_REQUEST_TARGET_REQUIRED")
    metadata["phase"] = "ENCODE_BODY"
    encoded = None if body is None else json.dumps(body).encode()
    # HTTPConnection neither consults proxies nor
    # constructs TLS contexts/loads Windows trust stores, and never follows redirects.
    metadata["phase"] = "CONNECT"
    connection = (HTTPConnection(host, port, timeout=timeout) if transport is None
                  else transport.acquire(origin, host, port, timeout))
    connection.auto_open = 0
    reusable = False
    try:
        if connection.sock is None:
            connection.connect()
        metadata["phase"] = "SEND"
        connection.request(
            method, path, body=encoded,
            headers={"Content-Type": "application/json", "Accept": "application/json", **(headers or {})},
        )
        metadata["phase"] = "RESPONSE_HEADERS"
        response = connection.getresponse()
        try:
            metadata["status"] = response.status
            metadata["phase"] = "RESPONSE_BODY"
            if response.length is not None and response.length > MAX_RESPONSE_BYTES:
                raise ValueError("RESPONSE_TOO_LARGE")
            # One sentinel byte detects oversized bodies without unbounded reads.
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ValueError("RESPONSE_TOO_LARGE")
            if response.length not in (None, 0):
                raise IncompleteRead(raw, response.length)
            metadata["phase"] = "DECODE_JSON"
            result = json.loads(raw) if raw else {}
        finally:
            response_closed = close_connection(response)
        reusable = response_closed and not response.will_close and connection.sock is not None
        return response.status, result
    finally:
        if transport is None:
            close_connection(connection)
        elif reusable:
            transport.release(origin, connection)
        else:
            transport.discard(origin, connection)


def atomic_json(path: Path, value):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


class Identity:
    """Normal OpsAgent refresh. No test endpoint, captcha bypass or forged JWT."""
    def __init__(self, origin: str, token_file: Path, transport=None):
        self.origin = origin
        self.path = token_file
        self.tokens = json.loads(token_file.read_text(encoding="utf-8-sig"))
        self.lock = threading.Lock()
        self.refreshed_at = time.monotonic()
        self.refresh_count = 0
        self.transport = transport
        if not self.tokens.get("accessToken") or not self.tokens.get("refreshToken"):
            raise ValueError("OFFICIAL_LOGIN_TOKENS_REQUIRED")

    def access(self) -> str:
        with self.lock:
            return self.tokens["accessToken"]

    def refresh(self, metadata=None):
        metadata = {} if metadata is None else metadata
        with self.lock:
            status, reply = http(
                self.origin, "POST", "/api/auth/refresh",
                body={"refreshToken": self.tokens["refreshToken"], "lastActivityAt": utc()},
                metadata=metadata, transport=self.transport,
            )
            metadata["phase"] = "RESPONSE_VALIDATION"
            if (status != 200 or not isinstance(reply, dict) or reply.get("code") != 0
                    or not isinstance(reply.get("data"), dict)
                    or any(not isinstance(reply["data"].get(key), str) or not reply["data"][key]
                           for key in ("accessToken", "refreshToken"))):
                raise RuntimeError("OFFICIAL_TOKEN_REFRESH_FAILED")
            next_tokens = reply["data"]
            # Write the rotated token before publishing it to worker threads.
            metadata["phase"] = "PERSIST_IDENTITY"
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
        self.transport = LoopbackTransport()
        self.identity = Identity(self.config["authOrigin"].rstrip("/"), Path(self.config["tokenFile"]),
                                 self.transport)
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
            "continuous": True, "qualityPassed": None, "firstQualityFailure": None,
            "processBindings": self.process_bindings,
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
            name, code = safe_exception(failure)
            self.report["failureCode"] = code
            self.record_diagnostic(utc(), None, None, 0, name, 0, code,
                                   phase="PREFLIGHT", **safe_error_numbers(failure))
            self.transport.close_all()
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
                           completedWindows=len(self.windows),
                           completedMinuteWindows=sum(v.get("completeMinute", False) for v in self.windows),
                           transport=self.transport.snapshot())
        atomic_json(self.directory / "report.json", self.report)

    def fail_quality(self, code, at, sequence=None, window=None):
        self.report["qualityPassed"] = False
        if self.report.get("firstQualityFailure") is None:
            self.report["firstQualityFailure"] = {
                "at": at, "code": code, "sequence": sequence, "window": window,
            }
            self.save()

    def record_diagnostic(self, at, sequence, method, status, exception_class, latency, code,
                          phase="OBSERVER", errno=None, winerror=None):
        # A single collector thread writes only this fixed set of scalar fields.
        code = code if code in KNOWN_DIAGNOSTIC_CODES else "OBSERVER_EXCEPTION"
        if exception_class is not None and exception_class not in KNOWN_EXCEPTION_CLASSES:
            exception_class = "Exception"
        evidence = {"at": at, "sequence": sequence, "method": method, "status": status,
                    "exceptionClass": exception_class, "latencyMillis": latency, "code": code,
                    "phase": phase if phase in KNOWN_REQUEST_PHASES else "OBSERVER",
                    "errno": safe_error_number(errno, KNOWN_ERRNOS),
                    "winerror": safe_error_number(winerror, KNOWN_WINERRORS)}
        with (self.directory / "diagnostics.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(evidence, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        self.fail_quality(code, at, sequence)

    def check_continuity(self):
        now, wall = time.monotonic(), time.time()
        reason = continuity_failure(self.last_wall, self.last_mono, wall, now, 5)
        if reason:
            self.report["continuous"] = False
            self.report["gap"] = {"reason": reason, "at": utc(),
                                  "monotonicGapSeconds": now - self.last_mono,
                                  "wallGapSeconds": wall - self.last_wall}
            raise RuntimeError(reason)
        self.last_mono, self.last_wall = now, wall
        return now

    def measure(self, sequence: int):
        method = "POST" if sequence % 10 == 0 else "GET"
        key = self.tag + "-" + str(sequence)
        before = time.perf_counter()
        response_metadata = {"phase": "AUTH_HEADERS"}
        error_numbers = {"errno": None, "winerror": None}
        status, run_id, exception_class, code = 0, None, None, None
        try:
            body = None
            path = self.route + "?limit=20"
            if method == "POST":
                path = self.route
                body = {"releaseRef": self.ref, "inputs": {"value": "SYNTHETIC_PHASE6_OBSERVATION"},
                        "clientReference": key}
            status, reply = http(self.origin, method, path, self.headers(key if method == "POST" else None),
                                 body, metadata=response_metadata, transport=self.transport)
            response_metadata["phase"] = "RESPONSE_VALIDATION"
            if status != (202 if method == "POST" else 200):
                code = "HTTP_UNEXPECTED_STATUS"
            elif not isinstance(reply, dict):
                code = "RESPONSE_INVALID_SHAPE"
            elif method == "POST":
                run = reply.get("run")
                if isinstance(run, dict) and isinstance(run.get("id"), str) and run["id"]:
                    run_id = run["id"]
                else:
                    code = "RESPONSE_RUN_ID_MISSING"
            elif not isinstance(reply.get("items"), list):
                code = "RESPONSE_INVALID_SHAPE"
        except (OSError, ValueError, TimeoutError, HTTPException) as failure:
            status = response_metadata.get("status", 0)
            exception_class, code = safe_exception(failure)
            error_numbers = safe_error_numbers(failure)
        return {"sequence": sequence, "method": method, "status": status,
                "latencyMillis": round((time.perf_counter() - before) * 1000, 3),
                "at": utc(), "exceptionClass": exception_class, "code": code,
                "phase": response_metadata["phase"], **error_numbers,
                "key": key if method == "POST" else None, "runId": run_id}

    def collect(self, value):
        self.total["requests"] += 1
        self.total["postRequests" if value["method"] == "POST" else "getRequests"] += 1
        failed = sample_failed(value)
        if failed:
            self.total["errors"] += 1
        if value["method"] == "POST":
            self.db.execute("UPDATE commands SET run_id=?,status=?,latency_ms=? WHERE sequence=? AND request_key=?",
                            (value["runId"], value["status"], value["latencyMillis"],
                             value["sequence"], value["key"]))
        bucket = min(60000, math.ceil(value["latencyMillis"]))
        self.histogram[bucket] = self.histogram.get(bucket, 0) + 1
        self.window.append(value)
        if failed:
            self.record_diagnostic(value["at"], value["sequence"], value["method"], value["status"],
                                   value["exceptionClass"], value["latencyMillis"], value["code"],
                                   phase=value.get("phase", "OBSERVER"),
                                   errno=value.get("errno"), winerror=value.get("winerror"))

    def inspect_service(self):
        before = time.perf_counter()
        metadata = {"phase": "AUTH_HEADERS"}
        try:
            status, value = http(self.origin, "GET", "/v1/projects/" + self.project + "/operations",
                                 self.headers(), metadata=metadata, transport=self.transport)
            metadata["phase"] = "RESPONSE_VALIDATION"
            if status != 200:
                self.record_diagnostic(utc(), None, "GET", status, None,
                                       round((time.perf_counter() - before) * 1000, 3),
                                       "HTTP_UNEXPECTED_STATUS", phase=metadata["phase"])
                return {"status": status, "available": False}
            if not isinstance(value, dict) or not isinstance(value.get("execution"), dict):
                raise ValueError("SERVICE_RESPONSE_INVALID_SHAPE")
            execution = value["execution"]
            now = execution.get("databaseTime", value.get("generatedAt"))
            candidates = execution.get("workers")
            unknown = execution.get("unknownPermitCount")
            if unknown is None and isinstance(execution.get("unknownPermits"), list):
                unknown = len(execution["unknownPermits"])
            if (not isinstance(now, (int, float)) or not isinstance(candidates, list)
                    or any(not isinstance(v, dict) or not isinstance(v.get("heartbeatAt"), (int, float))
                           or not isinstance(v.get("workerId"), str) for v in candidates)
                    or type(unknown) is not int or unknown < 0):
                raise ValueError("SERVICE_RESPONSE_INVALID_SHAPE")
            workers = [
                {key: worker.get(key) for key in ["workerId", "state", "heartbeatAt", "activeCount", "fingerprintMatches"]}
                for worker in candidates
                if worker["heartbeatAt"] >= now - 45000
                and worker.get("state") == "RUNNING" and worker.get("fingerprintMatches") is True
            ]
        except (OSError, ValueError, TimeoutError, HTTPException) as failure:
            name, code = safe_exception(failure)
            self.record_diagnostic(utc(), None, "GET", metadata.get("status", 0), name,
                                   round((time.perf_counter() - before) * 1000, 3), code,
                                   phase=metadata["phase"], **safe_error_numbers(failure))
            return {"status": metadata.get("status", 0), "available": False}
        ids = sorted(worker["workerId"] for worker in workers)
        if self.initial_workers is None:
            self.initial_workers = ids
        return {"status": status, "available": True, "queue": value.get("queue"),
                "statusCounts": value.get("statusCounts"), "usage": value.get("usage"),
                "workers": workers, "workerSetChanged": ids != self.initial_workers,
                "unresolvedPermits": unknown}

    def flush_window(self, complete_minute=False, service=None):
        if not self.window:
            return
        reads = [v["latencyMillis"] for v in self.window if v["method"] == "GET"]
        writes = [v["latencyMillis"] for v in self.window if v["method"] == "POST"]
        errors = sum(sample_failed(v) for v in self.window)
        evidence = {"window": len(self.windows) + 1, "at": utc(),
                    "elapsedMonotonicSeconds": round(time.monotonic() - self.start, 3),
                    "requests": len(self.window), "getRequests": len(reads), "postRequests": len(writes),
                    "errors": errors, "errorRate": errors / len(self.window),
                    "p95Millis": percentile(reads + writes),
                    "getP95Millis": percentile(reads), "postP95Millis": percentile(writes),
                    "completeMinute": complete_minute,
                    "service": self.inspect_service() if service is None else service}
        with (self.directory / "windows.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(evidence, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        self.db.commit()
        self.windows.append(evidence)
        self.window = []
        self.report["latestWindow"] = evidence
        failure = window_failure(evidence)
        if failure:
            self.fail_quality(failure, evidence["at"], window=evidence["window"])
        self.save()

    def run(self):
        pending = set()
        sequence = 0
        next_window = self.start + 60
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=20)
        failure_metadata = {"phase": "OBSERVER"}
        try:
            while True:
                # Check before the deadline: suspension may jump directly beyond it.
                now = self.check_continuity()
                if now - self.start >= self.duration:
                    break
                for future in list(pending):
                    if future.done():
                        pending.remove(future)
                        self.collect(future.result())
                if len(pending) >= 200:
                    raise RuntimeError("LOAD_GENERATOR_BACKLOG")
                if now - self.identity.refreshed_at >= 600:
                    self.identity.refresh(metadata=failure_metadata)
                    failure_metadata = {"phase": "OBSERVER"}
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
                    self.flush_window(complete_minute=True)
                    next_window += 60
            for future in concurrent.futures.as_completed(pending):
                pending.remove(future)
                self.collect(future.result())
            if self.read_process_bindings() != self.process_bindings:
                self.report["continuous"] = False
                raise RuntimeError("DEPLOYMENT_PROCESS_BINDING_CHANGED")
            now = self.check_continuity()
            self.flush_window(complete_minute=now >= next_window)
            self.check_continuity()
            self.report["histogramCeilingMillis"] = self.histogram
            complete_minutes = sum(row.get("completeMinute", False) for row in self.windows)
            commands, accepted_commands = self.db.execute(
                "SELECT COUNT(*),COALESCE(SUM(CASE WHEN status=202 AND run_id IS NOT NULL THEN 1 ELSE 0 END),0) FROM commands"
            ).fetchone()
            if (complete_minutes != self.duration // 60
                    or self.total["requests"] != self.duration * 10
                    or self.total["getRequests"] != self.duration * 9
                    or self.total["postRequests"] != self.duration
                    or commands != self.duration or accepted_commands != self.duration
                    or any(sum(row[key] for row in self.windows) != self.total[key]
                           for key in ("requests", "getRequests", "postRequests", "errors"))):
                self.fail_quality("INCOMPLETE_OBSERVATION_COVERAGE", utc())
            self.report["qualityPassed"] = (self.report.get("qualityPassed") is not False
                                            and self.total["errors"] == 0
                                            and all(window_failure(row) is None for row in self.windows))
            elapsed = time.monotonic() - self.start
            self.report["completed24Hours"] = self.duration >= 86400 and elapsed >= 86400 and self.report["continuous"]
            self.report["status"] = (
                "PASSED" if self.report["completed24Hours"] and self.report["qualityPassed"]
                else "SHORT_RUN_PASSED" if self.report["qualityPassed"]
                else "FAILED_SERVICE_TARGET"
            )
        except BaseException as failure:
            self.report["status"] = "INTERRUPTED" if isinstance(failure, KeyboardInterrupt) else "FAILED"
            name, code = safe_exception(failure)
            self.report["failureCode"] = code
            self.record_diagnostic(utc(), None, None, failure_metadata.get("status", 0), name, 0, code,
                                   phase=failure_metadata["phase"], **safe_error_numbers(failure))
            self.report["completed24Hours"] = False
            for future in pending:
                future.cancel()
        finally:
            pool.shutdown(wait=True, cancel_futures=True)
            self.transport.close_all()
            # Preserve responses completed during failure shutdown; a write is never retried.
            for future in pending:
                if future.done() and not future.cancelled():
                    try:
                        self.collect(future.result())
                    except Exception as failure:
                        self.report["status"] = "FAILED"
                        self.report["failureCode"] = "RESPONSE_RECORD_FAILED"
                        self.report["completed24Hours"] = False
                        name, _ = safe_exception(failure)
                        self.record_diagnostic(utc(), None, None, 0, name, 0, "RESPONSE_RECORD_FAILED")
            self.report["unreconciledCommands"] = self.db.execute(
                "SELECT COUNT(*) FROM commands WHERE run_id IS NULL").fetchone()[0]
            if self.window:
                # Retain partial evidence on interruption; do not invent a healthy service sample.
                self.flush_window(service={"available": False, "status": 0})
            if self.report["unreconciledCommands"]:
                self.fail_quality("UNRECONCILED_COMMANDS", utc())
                if self.report["status"] in ("PASSED", "SHORT_RUN_PASSED"):
                    self.report["status"] = "FAILED_SERVICE_TARGET"
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
