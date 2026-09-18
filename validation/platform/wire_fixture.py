"""Loopback-only synthetic MCP/model fixture; never connects to an external service."""
from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def utc():
    return datetime.now(timezone.utc).isoformat()


class WireFixture:
    def __init__(self, port: int, directory: Path, tokens: dict[str, str], verifier_token: str):
        self.directory = directory
        self.journal = directory / "fixture-journal.private.jsonl"
        self.journal.touch(exist_ok=False)
        self.tokens = {value: label for label, value in tokens.items()}
        self.verifier_token = verifier_token
        self.lock = threading.RLock()
        self.holds: dict[str, threading.Event] = {}
        self.active = 0
        self.max_active = 0
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def handle(self):
                try:
                    super().handle()
                except (ConnectionResetError, BrokenPipeError):
                    # Owned Java workers are deliberately terminated by the acceptance runner.
                    pass

            def log_message(self, *_):
                pass

            def respond(self, status, body=None, session=False):
                encoded = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                if session:
                    self.send_header("Mcp-Session-Id", "isolated-platform-fixture")
                self.end_headers()
                if encoded:
                    self.wfile.write(encoded)

            def do_GET(self):
                if self.path == "/health":
                    return self.respond(200, {"status": "UP", "synthetic": True})
                if self.path.startswith("/evidence/") and self.headers.get("Authorization") == "Bearer " + fixture.verifier_token:
                    key = self.path.removeprefix("/evidence/")
                    if not re.fullmatch(r"[a-z0-9_-]{8,120}", key):
                        return self.respond(400)
                    return self.respond(200, {"effects": fixture.rows("effect", key)})
                self.respond(405)

            def do_DELETE(self):
                self.respond(204)

            def do_POST(self):
                worker = fixture.tokens.get(self.headers.get("Authorization", "").removeprefix("Bearer "))
                if worker is None:
                    return self.respond(401)
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > 65536:
                    return self.respond(413)
                try:
                    request = json.loads(self.rfile.read(length))
                except (ValueError, UnicodeError):
                    return self.respond(400)
                if self.path == "/chat/completions":
                    fixture.append({"kind": "model", "worker": worker, "chargedTokens": 22})
                    return self.respond(200, {"id": "synthetic-model-response", "object": "chat.completion", "model": "synthetic-process-acceptance",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "synthetic-process-budget-ok"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 17, "completion_tokens": 5, "total_tokens": 22}})
                if self.path != "/mcp":
                    return self.respond(404)
                if "id" not in request:
                    return self.respond(202)
                method = request.get("method")
                if method == "initialize":
                    result = {"protocolVersion": request.get("params", {}).get("protocolVersion", "2025-03-26"),
                        "capabilities": {"tools": {}}, "serverInfo": {"name": "platform-isolated-fixture", "version": "1"}}
                    return self.respond(200, {"jsonrpc": "2.0", "id": request["id"], "result": result}, session=True)
                if method == "tools/list":
                    result = {"tools": [fixture.tool(name) for name in ("probe_read", "probe_hold_read", "probe_write", "probe_unknown_write")]}
                elif method == "ping":
                    result = {}
                elif method == "tools/call":
                    params = request.get("params", {})
                    name, args = params.get("name"), params.get("arguments", {})
                    key = args.get("caseKey", "")
                    if name not in ("probe_read", "probe_hold_read", "probe_write", "probe_unknown_write") or set(args) != {"caseKey"} or not re.fullmatch(r"[a-z0-9_-]{8,120}", key):
                        return self.respond(200, {"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32602, "message": "Invalid synthetic scope"}})
                    with fixture.lock:
                        fixture.active += 1
                        fixture.max_active = max(fixture.max_active, fixture.active)
                    fixture.append({"kind": "dispatch", "remoteName": name, "caseKey": key, "worker": worker})
                    try:
                        if name == "probe_hold_read":
                            with fixture.lock:
                                gate = fixture.holds.setdefault(key, threading.Event())
                            if not gate.wait(8):
                                raise TimeoutError("Fixture hold not released")
                        elif name == "probe_read":
                            time.sleep(0.45)
                        if name in ("probe_write", "probe_unknown_write"):
                            fixture.append({"kind": "effect", "remoteName": name, "caseKey": key, "worker": worker,
                                "receipt": "synthetic-effect:" + key, "result": {"synthetic": True, "caseKey": key}})
                        if name == "probe_unknown_write":
                            # Effect was fsynced. Close the real local socket before any response.
                            self.close_connection = True
                            try:
                                self.connection.shutdown(socket.SHUT_RDWR)
                            except OSError:
                                pass
                            self.connection.close()
                            return
                        result = {"content": [{"type": "text", "text": json.dumps({"synthetic": True, "caseKey": key})}],
                            "structuredContent": {"synthetic": True, "caseKey": key}, "isError": False}
                    finally:
                        with fixture.lock:
                            fixture.active -= 1
                else:
                    return self.respond(200, {"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": "Unsupported fixture method"}})
                self.respond(200, {"jsonrpc": "2.0", "id": request["id"], "result": result})

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True, name="synthetic-wire-fixture")

    @staticmethod
    def tool(name):
        return {"name": name, "description": "Synthetic isolated process acceptance " + name,
            "inputSchema": {"type": "object", "required": ["caseKey"], "additionalProperties": False,
                "properties": {"caseKey": {"type": "string", "pattern": "^[a-z0-9_-]{8,120}$"}}},
            "annotations": {"readOnlyHint": name in ("probe_read", "probe_hold_read"), "idempotentHint": False}}

    def append(self, row):
        with self.lock:
            with self.journal.open("a", encoding="utf-8") as output:
                output.write(json.dumps({"at": utc(), **row}, separators=(",", ":")) + "\n")
                output.flush()
                os.fsync(output.fileno())

    def rows(self, kind=None, case_key=None):
        with self.lock:
            rows = [json.loads(line) for line in self.journal.read_text(encoding="utf-8").splitlines()]
        return [row for row in rows if (kind is None or row["kind"] == kind) and (case_key is None or row.get("caseKey") == case_key)]

    def release(self, key):
        with self.lock:
            self.holds.setdefault(key, threading.Event()).set()
        self.append({"kind": "hold_released", "caseKey": key})

    def start(self):
        self.thread.start()

    def close(self):
        for gate in self.holds.values():
            gate.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
