"""Local test receiver. Persist only low-cardinality alert metadata, never annotations."""
import argparse
import json
import re
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def project_event(payload):
    if payload.get("status") not in ("firing", "resolved"):
        raise ValueError("Invalid alert status")
    alerts = payload.get("alerts", [])
    if not isinstance(alerts, list) or len(alerts) > 100:
        raise ValueError("Invalid alert batch")
    projected = []
    for item in alerts:
        labels = item.get("labels", {})
        row = {}
        for key in ("alertname", "severity", "project", "instance"):
            value = labels.get(key)
            if value is not None:
                if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,128}", value):
                    raise ValueError("Invalid label")
                row[key] = value
        projected.append({"status": item.get("status", payload["status"]), "labels": row})
    return {"receivedAt": datetime.now(timezone.utc).isoformat(),
            "status": payload["status"], "alerts": projected}


def server(bind, port, destination):
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/alerts":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 2 or length > 65536:
                    raise ValueError("Invalid size")
                event = project_event(json.loads(self.rfile.read(length)))
                with lock, destination.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(event, separators=(",", ":")) + "\n")
                self.send_response(202)
                self.end_headers()
            except (ValueError, TypeError, AttributeError):
                self.send_error(400)

        def log_message(self, *_):
            pass

    return ThreadingHTTPServer((bind, port), Handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    server(args.bind, args.port, args.destination).serve_forever()
