"""Offline nginx -t regression, with no listeners or target-private config.

Set HARNESS_NGINX_BINARY to a native nginx executable, or set
HARNESS_NGINX_INTEGRATION=1 to use the pinned, already-cached Docker image.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


IMAGE = "nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10"
LOCATIONS = Path(__file__).resolve().parents[1] / "ai-minimal.nginx.locations.conf"
BINARY = os.environ.get("HARNESS_NGINX_BINARY")


@unittest.skipUnless(BINARY or os.environ.get("HARNESS_NGINX_INTEGRATION") == "1",
                     "Set HARNESS_NGINX_BINARY or HARNESS_NGINX_INTEGRATION=1")
class OpsNginxIntegrationTest(unittest.TestCase):
    def test_existing_ops_server_resolver_with_harness_locations(self):
        with tempfile.TemporaryDirectory(prefix="harness-nginx-") as temporary:
            fixture = Path(temporary).resolve()
            fixture.chmod(0o755)
            (fixture / "locations.conf").write_bytes(LOCATIONS.read_bytes())
            include_root = fixture.as_posix() if BINARY else "/fixture"
            temp_root = fixture.as_posix() if BINARY else "/tmp"
            config = fixture / "nginx.conf"
            config.write_text(f"""worker_processes 1;
pid "{temp_root}/nginx.pid";
error_log stderr warn;
events {{ worker_connections 64; }}
http {{
    access_log off;
    client_body_temp_path "{temp_root}/client-body";
    proxy_temp_path "{temp_root}/proxy";
    fastcgi_temp_path "{temp_root}/fastcgi";
    uwsgi_temp_path "{temp_root}/uwsgi";
    scgi_temp_path "{temp_root}/scgi";
    server {{
        listen 8080;
        server_name opsagent.cloud;
        # The existing Ops app-locations.conf owns this server-level resolver.
        resolver 127.0.0.11 valid=10s ipv6=off;
        include "{include_root}/locations.conf";
    }}
}}
""", encoding="utf-8")
            for path in fixture.iterdir():
                path.chmod(0o644)
            command = ([BINARY, "-t", "-c", str(config)] if BINARY else [
                "docker", "run", "--rm", "--pull", "never", "--network", "none",
                "--memory", "128m", "--memory-swap", "128m", "--cpus", "0.25",
                "--pids-limit", "64", "--read-only", "--user", "101:101",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--tmpfs", "/tmp:rw,size=16m,mode=1777", "--entrypoint", "nginx",
                "--mount", f"type=bind,source={fixture},target=/fixture,readonly",
                IMAGE, "-t", "-c", "/fixture/nginx.conf",
            ])
            result = subprocess.run(command, capture_output=True, text=True, timeout=45)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
