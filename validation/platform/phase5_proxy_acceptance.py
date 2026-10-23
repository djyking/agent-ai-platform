"""Real-JVM HTTPS/forwarded-header acceptance through an isolated loopback TLS proxy.

The proxy models the deployment's fixed Host/X-Forwarded-* overwrite. It does not
claim that nginx, Linux networking, public DNS or production certificates ran.
No model or tool invocation is made. Credentials and cookie values never enter
the report. Requires an already-running local-test API and a trusted SAN cert.
"""
from __future__ import annotations

import argparse
import datetime as dt
import http.client
import http.server
import json
import ssl
import threading
import time
from pathlib import Path
from urllib.parse import urlsplit


class AcceptanceFailure(Exception):
    pass


def resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path


def run(config_file: Path, report_file: Path) -> dict:
    config = json.loads(config_file.read_text(encoding="utf-8-sig"))
    base = config_file.resolve().parent
    credentials = json.loads(resolve(base, config["credentialsFile"]).read_text(encoding="utf-8-sig"))
    upstream = urlsplit(config["upstream"])
    public = urlsplit(config["publicOrigin"])
    if upstream.scheme != "http" or upstream.hostname != "127.0.0.1" or not upstream.port:
        raise AcceptanceFailure("UPSTREAM_MUST_BE_EXPLICIT_LOOPBACK_HTTP")
    if public.scheme != "https" or public.hostname not in ("localhost", "127.0.0.1") or not public.port:
        raise AcceptanceFailure("PUBLIC_ORIGIN_MUST_BE_EXPLICIT_LOOPBACK_HTTPS")
    cert = resolve(base, config["certificateFile"])
    key = resolve(base, config["privateKeyFile"])
    context = ssl.create_default_context(cafile=str(cert))
    started = time.monotonic()
    checks = []
    report = {
        "suite": "phase5_https_proxy_acceptance",
        "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "RUNNING",
        "topology": "loopback TLS reverse proxy -> real JVM native RemoteIpValve",
        "scope": "synthetic local-test identity, no external model/tool calls",
        "limitations": ["nginx container and Linux host not executed", "self-signed local SAN certificate, not public certificate issuance"],
        "checks": checks,
    }

    def check(name, condition, **details):
        checks.append({"name": name, "status": "PASS" if condition else "FAIL", **details})
        if not condition:
            raise AcceptanceFailure(name)

    class Proxy(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def dispatch(self):
            if self.path.startswith("/internal/"):
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 65536:
                self.send_response(413)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = self.rfile.read(length)
            omitted = {"host", "forwarded", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto", "x-forwarded-for", "connection", "proxy-connection", "transfer-encoding", "content-length", "accept-encoding"}
            connection = http.client.HTTPConnection(upstream.hostname, upstream.port, timeout=20, source_address=("127.0.0.1", 0))
            try:
                connection.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
                # Preserve duplicate Origin headers, so the JVM can reject them explicitly.
                for name, value in self.headers.raw_items():
                    if name.lower() not in omitted:
                        connection.putheader(name, value)
                connection.putheader("Host", public.netloc)
                connection.putheader("X-Forwarded-Host", public.hostname)
                connection.putheader("X-Forwarded-Port", str(public.port))
                connection.putheader("X-Forwarded-Proto", "https")
                connection.putheader("X-Forwarded-For", self.client_address[0])
                connection.putheader("Content-Length", str(len(body)))
                connection.endheaders(body)
                response = connection.getresponse()
                payload = response.read()
                self.send_response(response.status)
                for name, value in response.getheaders():
                    if name.lower() not in {"connection", "transfer-encoding", "content-length", "server", "date"}:
                        self.send_header(name, value)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (OSError, http.client.HTTPException):
                self.send_response(502)
                self.send_header("Content-Length", "0")
                self.end_headers()
            finally:
                connection.close()

        do_GET = dispatch
        do_POST = dispatch
        do_DELETE = dispatch
        do_PUT = dispatch

    def request(method, path, body=None, headers=None, *, direct=False, source=None):
        target = upstream if direct else public
        arguments = {"timeout": 20}
        if source is not None:
            arguments["source_address"] = (source, 0)
        connection = http.client.HTTPConnection(target.hostname, target.port, **arguments) if direct else http.client.HTTPSConnection(target.hostname, target.port, context=context, **arguments)
        wire = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        values = list(headers or [])
        if wire is not None:
            values.extend([("Content-Type", "application/json"), ("Content-Length", str(len(wire)))])
        try:
            connection.putrequest(method, path, skip_host=any(n.lower() == "host" for n, _ in values))
            for name, value in values:
                connection.putheader(name, value)
            connection.endheaders(wire)
            response = connection.getresponse()
            raw = response.read()
            try:
                parsed = json.loads(raw) if raw else {}
            except (ValueError, UnicodeDecodeError):
                parsed = {}
            return response.status, response.getheaders(), parsed
        finally:
            connection.close()

    server = http.server.ThreadingHTTPServer(("127.0.0.1", public.port), Proxy)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(str(cert), str(key))
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    origin = config["publicOrigin"]
    login = {"username": credentials.get("username", "operator"), "password": credentials["password"], "projectId": config["project"]}
    cookie = None
    csrf = None
    try:
        status, _, identity = request("GET", "/console/auth/config")
        check("tls_chain_hostname_and_local_identity", status == 200 and identity.get("provider") == "local-test", httpStatus=status, certificateVerification=True)
        status, _, _ = request("POST", "/console/login", login)
        check("missing_origin_rejected", status == 403, httpStatus=status)
        status, _, _ = request("POST", "/console/login", login, [("Origin", "https://untrusted.invalid")])
        check("foreign_origin_rejected", status == 403, httpStatus=status)
        status, _, _ = request("POST", "/console/login", login, [("Origin", origin), ("Origin", origin)])
        check("duplicate_origin_rejected", status == 403, httpStatus=status)
        status, headers, session = request("POST", "/console/login", login, [("Origin", origin), ("Host", "untrusted.invalid"), ("X-Forwarded-Proto", "http"), ("X-Forwarded-Host", "untrusted.invalid"), ("X-Forwarded-Port", "80"), ("Forwarded", "proto=http;host=untrusted.invalid")])
        check("trusted_proxy_overwrites_client_forwarded_headers", status == 200 and session.get("authenticated") is True, httpStatus=status)
        cookies = [v for n, v in headers if n.lower() == "set-cookie" and v.startswith("harness_console=")]
        check("one_session_cookie", len(cookies) == 1)
        flags = [s.strip().lower() for s in cookies[0].split(";")[1:]]
        check("secure_httponly_samesite_cookie", "secure" in flags and "httponly" in flags and "samesite=strict" in flags and "path=/console" in flags, secure="secure" in flags, httpOnly="httponly" in flags, sameSite="Strict", path="/console")
        cookie = cookies[0].split(";", 1)[0]
        csrf = session.get("csrfToken")
        check("csrf_token_delivered_in_authenticated_session", isinstance(csrf, str) and len(csrf) >= 20)
        authorized = [("Origin", origin), ("Cookie", cookie)]
        status, _, operations = request("GET", "/console/api/projects/" + config["project"] + "/operations", headers=authorized)
        check("authorized_bff_project_read_over_https", status == 200 and operations.get("project") == config["project"], httpStatus=status)
        status, _, _ = request("DELETE", "/console/session", headers=authorized)
        check("missing_csrf_rejected", status == 403, httpStatus=status)
        status, _, _ = request("DELETE", "/console/session", headers=authorized + [("X-CSRF-Token", "synthetic-invalid-token")])
        check("wrong_csrf_rejected", status == 403, httpStatus=status)
        status, _, _ = request("POST", "/console/login", login, authorized)
        check("existing_session_login_requires_csrf", status == 403, httpStatus=status)
        status, _, _ = request("GET", "/internal/metrics")
        check("public_proxy_does_not_expose_internal_metrics", status == 404, httpStatus=status)
        spoof = [("Origin", origin), ("Host", public.netloc), ("X-Forwarded-Proto", "https"), ("X-Forwarded-Host", public.hostname), ("X-Forwarded-Port", str(public.port)), ("X-Forwarded-For", "127.0.0.1")]
        status, headers, _ = request("POST", "/console/login", login, spoof, direct=True, source="127.0.0.2")
        check("untrusted_loopback_peer_cannot_spoof_forwarded_origin", status == 403 and not any(n.lower() == "set-cookie" for n, _ in headers), httpStatus=status, sourceAddress="127.0.0.2")
        status, _, session = request("DELETE", "/console/session", headers=authorized + [("X-CSRF-Token", csrf)])
        check("valid_origin_csrf_logout_succeeds", status == 200 and session.get("authenticated") is False, httpStatus=status)
        status, _, session = request("GET", "/console/session", headers=authorized)
        check("logged_out_session_not_reusable", status == 200 and session.get("authenticated") is False, httpStatus=status)
        cookie = None
        report["status"] = "PASS"
    except Exception as failure:
        report["status"] = "FAIL"
        # Exception content may include credentials in third-party implementations; report type only.
        report["failureType"] = type(failure).__name__
        if isinstance(failure, AcceptanceFailure):
            report["failedCheck"] = str(failure)
        raise
    finally:
        if cookie and csrf:
            try:
                request("DELETE", "/console/session", headers=[("Origin", origin), ("Cookie", cookie), ("X-CSRF-Token", csrf)])
            except Exception:
                pass
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        report_file.parent.mkdir(parents=True, exist_ok=True)
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        outcome = run(args.config, args.report)
        print(json.dumps({"status": outcome["status"], "checks": len(outcome["checks"]), "report": str(args.report)}, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"status": "FAIL", "failureType": type(error).__name__, "report": str(args.report)}, ensure_ascii=False))
        raise SystemExit(1)
