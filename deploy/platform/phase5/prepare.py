"""Render private instance manifests and trusted proxy config; never start services."""
import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit


def render(manifest, origin):
    uri = urlsplit(origin)
    if (uri.scheme != "https" or not uri.hostname or uri.username or uri.password
            or uri.path not in ("", "/") or uri.query or uri.fragment
            or not re.fullmatch(r"[A-Za-z0-9.-]+", uri.hostname)):
        raise ValueError("HTTPS origin with a DNS name or IPv4 address required")
    port = uri.port or 443
    if not 1 <= port <= 65535:
        raise ValueError("Invalid origin port")
    if any(not str(value).startswith("env:") for value in manifest["applicationSecrets"].values()):
        raise ValueError("Application credentials must use env references")
    if not manifest["signingSecret"].startswith("env:"):
        raise ValueError("Signing key must use an env reference")
    api = dict(manifest, workerEnabled=False)
    worker = dict(manifest, workerEnabled=True)
    nginx = f'''events {{ worker_connections 1024; }}
http {{
  server_tokens off;
  access_log off;
  error_log /dev/stderr warn;
  client_max_body_size 64k;
  server {{
    listen 443 ssl;
    server_name {uri.hostname};
    ssl_certificate /run/tls/fullchain.pem;
    ssl_certificate_key /run/tls/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    if ($host != "{uri.hostname}") {{ return 421; }}
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options nosniff always;
    location /internal/ {{ return 404; }}
    location /health {{ return 404; }}
    location / {{
      proxy_pass http://api:8097;
      proxy_set_header Host {uri.hostname}:{port};
      proxy_set_header Forwarded "";
      proxy_set_header X-Forwarded-For $remote_addr;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host {uri.hostname};
      proxy_set_header X-Forwarded-Port {port};
      proxy_read_timeout 90s;
      proxy_send_timeout 90s;
      proxy_buffering off;
    }}
  }}
}}
'''
    return api, worker, nginx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--private", type=Path, required=True)
    args = parser.parse_args()
    api, worker, nginx = render(json.loads(args.manifest.read_text(encoding="utf-8")), args.origin)
    os.umask(0o077)
    args.private.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = args.private / "config"
    config.mkdir(mode=0o700, exist_ok=True)
    for name, value in (("api", api), ("worker", worker)):
        (config / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    (args.private / "nginx.conf").write_text(nginx, encoding="utf-8")
    for name in ("tls", "alert-evidence", "backup-metrics"):
        (args.private / name).mkdir(mode=0o700, exist_ok=True)
    print("Rendered API/worker manifests and trusted HTTPS proxy. No services started.")


if __name__ == "__main__":
    main()
