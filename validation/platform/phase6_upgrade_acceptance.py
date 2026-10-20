"""Real MySQL schema 1 -> 2 upgrade and old-binary refusal on an isolated local fixture."""
import argparse
import hashlib
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from phase5_execution_acceptance import Client


def run(config_path, report_path):
    config = json.loads(Path(config_path).read_text(encoding="utf-8-sig"))
    root = Path(__file__).resolve().parents[2]
    work = Path(config["workDirectory"]).resolve()
    if not work.is_relative_to(root / ".work/phase6") or work.exists():
        raise ValueError("Fresh private phase-six work directory required")
    work.mkdir(parents=True)
    env = {**os.environ, **json.loads(Path(config["environmentFile"]).read_text(encoding="utf-8-sig"))}
    database_uri = urlsplit(env.get("HARNESS_JDBC_URL", "").removeprefix("jdbc:"))
    if (env.get("HARNESS_IDENTITY_PROVIDER") != "local-test"
            or database_uri.scheme != "mysql" or database_uri.hostname != "127.0.0.1"
            or database_uri.path not in ("/harness_upgrade_phase6", "/harness_upgrade_phase6_final")
            or env.get("HARNESS_JDBC_URL") != env.get("HARNESS_MIGRATION_JDBC_URL")):
        raise ValueError("Only the explicitly isolated local-test upgrade schema is allowed")
    env["HARNESS_PORT"] = "8138"
    path = Path(report_path)
    if path.exists():
        raise ValueError("Report exists")
    report = {"status": "RUNNING", "format": "harness-phase6-upgrade-v1", "checks": [],
              "database": database_uri.path[1:], "externalCalls": 0, "productionRollback": False,
              "startedAt": datetime.now(timezone.utc).isoformat()}
    process = None
    log = None

    def save():
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    def cli(jar, *args):
        result = subprocess.run([config["java"], "-jar", jar, *args], env=env, capture_output=True,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=90)
        if result.returncode:
            (work / ("cli-" + uuid.uuid4().hex + ".private.log")).write_bytes(result.stdout + result.stderr)
            raise RuntimeError("UPGRADE_CLI_FAILED")
        return result.stdout

    def start(jar, reject=False):
        nonlocal process, log
        label = "old-rejected" if reject else ("old" if jar == config["oldJar"] else "new")
        log = (work / (label + ".private.log")).open("wb")
        process = subprocess.Popen([config["java"], "-jar", jar], env=env, stdout=log,
                                   stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        for _ in range(120):
            if process.poll() is not None:
                if reject and process.returncode != 0:
                    log.close()
                    raw = (work / (label + ".private.log")).read_text(encoding="utf-8", errors="replace")
                    if "Schema migration required for studio" not in raw:
                        raise RuntimeError("OLD_BINARY_FAILED_FOR_UNRELATED_REASON")
                    report["refusalReason"] = "Schema migration required for studio"
                    process = None
                    return
                raise RuntimeError("UPGRADE_PROCESS_EXITED")
            try:
                with urllib.request.urlopen("http://127.0.0.1:8138/health/ready", timeout=1) as response:
                    if response.status == 200:
                        if reject:
                            raise RuntimeError("OLD_BINARY_ACCEPTED_NEW_SCHEMA")
                        return
            except (OSError, urllib.error.HTTPError):
                pass
            time.sleep(.25)
        raise RuntimeError("UPGRADE_START_TIMEOUT")

    def stop():
        nonlocal process, log
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=15)
        if log:
            log.close()
        process = None

    try:
        for kind in ("old", "new"):
            digest = hashlib.sha256(Path(config[kind + "Jar"]).read_bytes()).hexdigest()
            if digest != config[kind + "JarSha256"]:
                raise RuntimeError("ARTIFACT_HASH_MISMATCH")
            report[kind + "JarSha256"] = digest
        save()
        cli(config["oldJar"], "migrate")
        refs = json.loads(cli(config["oldJar"], "releases", env["HARNESS_CONFIG"]))
        if len(refs) != 1:
            raise RuntimeError("ONE_SYNTHETIC_RELEASE_REQUIRED")
        reference = refs[0]["releaseRef"]
        start(config["oldJar"])
        client = Client("http://127.0.0.1:8138", config["username"], config["password"], config["project"])
        route = "/console/api/projects/" + config["project"] + "/runs"
        key = "synthetic-upgrade-" + uuid.uuid4().hex
        body = {"releaseRef": reference, "inputs": {"value": "SYNTHETIC_UPGRADE_PERSISTENCE"}}
        status, created, _ = client.request("POST", route, body, key=key)
        if status != 202:
            raise RuntimeError("OLD_VERSION_CREATE_FAILED")
        run_id = created["run"]["id"]
        for _ in range(100):
            status, result, _ = client.request("GET", route + "/" + run_id)
            if status == 200 and result["status"] == "COMPLETED":
                break
            time.sleep(.1)
        else:
            raise RuntimeError("OLD_VERSION_RUN_NOT_COMPLETED")
        report["runId"] = run_id
        report["checks"].append({"name": "old-version-real-run-completed", "passed": True})
        stop()
        # Offline guard uses max(30 sec, lease+10 sec); do not edit worker heartbeats.
        time.sleep(31)
        cli(config["newJar"], "migrate")
        report["checks"].append({"name": "explicit-offline-upgrade", "passed": True})
        start(config["oldJar"], reject=True)
        report["checks"].append({"name": "schema-one-binary-refuses-schema-two", "passed": True})
        start(config["newJar"])
        client = Client("http://127.0.0.1:8138", config["username"], config["password"], config["project"])
        status, result, _ = client.request("GET", route + "/" + run_id)
        if status != 200 or result["status"] != "COMPLETED" or result["output"]["value"] != body["inputs"]["value"]:
            raise RuntimeError("UPGRADE_LOST_DURABLE_RESULT")
        status, replay, _ = client.request("POST", route, body, key=key)
        if status not in (200, 202) or replay["run"]["id"] != run_id:
            raise RuntimeError("UPGRADE_IDEMPOTENCY_CHANGED")
        report["checks"].append({"name": "result-and-create-idempotency-preserved", "passed": True})
        stop()
        start(config["newJar"])
        client = Client("http://127.0.0.1:8138", config["username"], config["password"], config["project"])
        status, result, _ = client.request("GET", route + "/" + run_id)
        if status != 200 or result["status"] != "COMPLETED":
            raise RuntimeError("RESTART_LOST_RESULT")
        report["checks"].append({"name": "new-version-restart-preserves-run", "passed": True})
        report["status"] = "PASSED"
    except Exception as failure:
        report["status"] = "FAILED"
        report["failureType"] = type(failure).__name__
        if isinstance(failure, RuntimeError):
            report["failureCode"] = str(failure)
        raise
    finally:
        stop()
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    try:
        run(args.config, args.report)
    except Exception as failure:
        raise SystemExit("Isolated upgrade validation failed: " + type(failure).__name__)
