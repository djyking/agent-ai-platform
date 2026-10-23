"""Recover encrypted full+binary logs into the dedicated isolated MySQL instance.

Default is a verified plan. Applying requires the explicit isolated-target guard.
No application process, external side effect, identity import or key installation is started.
"""
import argparse
import configparser
import json
import os
import re
import subprocess
import tarfile
import time
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from backup import checked, file_hash, identifier, mysql, plaintext_workspace


def unpack(bundle, target, max_bytes=10 * 1024**3):
    with tarfile.open(bundle, "r:gz") as archive:
        members = archive.getmembers()
        total = 0
        names = set()
        for member in members:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or ".." in path.parts or "\\" in member.name or ":" in member.name
                    or not (member.isfile() or member.isdir()) or member.name in names):
                raise ValueError("Unsafe or duplicate recovery archive member")
            names.add(member.name)
            total += member.size
            if total > max_bytes:
                raise ValueError("Recovery archive exceeds the configured plaintext limit")
        # Manual extraction: no symlink/device semantics, even on older Python runtimes.
        target.mkdir(mode=0o700)
        for member in members:
            path = target / member.name
            if member.isdir():
                path.mkdir(mode=0o700, parents=True, exist_ok=True)
            else:
                path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                with archive.extractfile(member) as source, path.open("xb") as output:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
        manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("format") != "harness-backup-v1" or manifest.get("kind") not in ("full", "binlog"):
            raise ValueError("Unsupported recovery format")
        if manifest["kind"] == "full" and [p["name"] for p in manifest["payloads"]] != ["database.sql"]:
            raise ValueError("Full recovery must authenticate exactly one database payload")
        for payload in [*manifest["payloads"], *manifest.get("privateState", [])]:
            path = target / payload["name"]
            if not path.resolve().is_relative_to(target.resolve()) or file_hash(path) != payload["sha256"]:
                raise ValueError("Recovery payload hash mismatch")
        return manifest


def recovery_plan(full, archives, stop_at):
    if full["kind"] != "full":
        raise ValueError("First bundle must be a full backup")
    source = full["source"]
    identifier(source["database"])
    # UTC timestamps accepted at second resolution to match mysqlbinlog stop-datetime.
    stop = datetime.strptime(stop_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    files = {}
    coverage = []
    for archive in archives:
        if archive["kind"] != "binlog" or archive["source"]["serverUuid"] != source["serverUuid"]:
            raise ValueError("Binary logs must belong to the full backup source server")
        if archive["source"]["database"] != source["database"]:
            raise ValueError("Binary log archive database differs")
        for payload in archive["payloads"]:
            name = Path(payload["name"]).name
            if not re.fullmatch(r"[A-Za-z0-9_.-]+\.\d{6,}", name):
                raise ValueError("Invalid log filename")
            if name in files and files[name] != payload["sha256"]:
                raise ValueError("Conflicting binary-log archives")
            files[name] = payload["sha256"]
        coverage.append(datetime.fromisoformat(archive["coverageLowerBoundUtc"].replace("Z", "+00:00")))
    first = full["coordinates"]["file"]
    prefix, number = first.rsplit(".", 1)
    if first not in files:
        raise ValueError("The log containing full snapshot coordinates is missing")
    selected = sorted(name for name in files if name >= first)
    numbers = []
    for name in selected:
        stem, index = name.rsplit(".", 1)
        if stem != prefix:
            raise ValueError("Unexpected binlog series")
        numbers.append(int(index))
    if numbers != list(range(int(number), max(numbers) + 1)):
        raise ValueError("Binary-log sequence has gaps")
    if not coverage or max(coverage) < stop:
        raise ValueError("Requested recovery time exceeds conservatively archived coverage")
    full_time = datetime.fromisoformat(full["createdAt"])
    if stop < full_time:
        raise ValueError("Recovery time precedes completion of the full snapshot")
    return {"source": source, "database": source["database"], "logFiles": selected,
            "startPosition": full["coordinates"]["position"], "stopAtUtcExclusive": stop_at,
            "workersEnabled": False, "externalEffectsReconciled": False,
            "privateStateAction": "Retained encrypted; restore matching identity/key generations after review"}


def isolated_target(defaults, source_uuid):
    config = configparser.ConfigParser(interpolation=None)
    config.read(defaults, encoding="utf-8")
    client = config["client"]
    if client.get("host") not in ("127.0.0.1", "localhost") or client.get("port") != "13306":
        raise ValueError("Restore accepts only the isolated loopback recovery endpoint on port 13306")
    options = {"mysqlDefaults": str(defaults)}
    result = mysql(options, "SELECT @@server_uuid,@@event_scheduler,@@log_bin").split("\t")
    if len(result) != 3 or result[0] == source_uuid or result[1] != "OFF" or result[2] != "0":
        raise ValueError("Target must be a distinct recovery server with events and binary logging off")
    return options, result[0]


def execute_restore(defaults, plan, full_dir, log_paths):
    options, target_uuid = isolated_target(defaults, plan["source"]["serverUuid"])
    db = identifier(plan["database"])
    exists = mysql(options, f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{db}'")
    if exists != "0":
        raise ValueError("Target database already exists; restore never overwrites it")
    mysql(options, f"CREATE DATABASE `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
    mysql_command = ["mysql", "--defaults-extra-file=" + str(Path(defaults).resolve()), "--binary-mode", db]
    with (full_dir / "database.sql").open("rb") as dump:
        result = subprocess.run(mysql_command, stdin=dump, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode:
        raise RuntimeError("Full restore failed; isolated partial database retained")
    stop = plan["stopAtUtcExclusive"].replace("T", " ").replace("Z", "")
    # One mysqlbinlog process is essential: start-position applies only to first file,
    # preserving transaction context across log boundaries. TZ fixes time interpretation.
    command = ["mysqlbinlog", "--skip-gtids", "--database=" + db,
               "--start-position=" + str(plan["startPosition"]), "--stop-datetime=" + stop,
               *[str(log_paths[name]) for name in plan["logFiles"]]]
    env = dict(os.environ, TZ="UTC")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
    consumer = subprocess.run(mysql_command, stdin=process.stdout, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL)
    process.stdout.close()
    status = process.wait()
    if status or consumer.returncode:
        raise RuntimeError("PITR replay failed; isolated partial database retained")
    table_count = int(mysql(options, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + db + "' AND table_type='BASE TABLE'"))
    return {"targetServerUuid": target_uuid, "tableCount": table_count,
            "databaseRestored": True, "applicationStarted": False,
            "rtoVerified": False, "rpoVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", type=Path, required=True)
    parser.add_argument("--binlog", type=Path, action="append", required=True)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--stop-at", required=True, help="Exclusive UTC boundary: YYYY-MM-DDTHH:MM:SSZ")
    parser.add_argument("--tmpfs", default="/dev/shm/harness-recovery")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-defaults", type=Path)
    parser.add_argument("--apply-isolated", action="store_true")
    parser.add_argument("--isolation-ack", choices=["workers-absent-network-isolated"])
    args = parser.parse_args()
    if args.apply_isolated and (not args.target_defaults or not args.isolation_ack):
        parser.error("Applying requires target defaults and the explicit isolation acknowledgement")
    os.umask(0o077)
    started = time.monotonic()
    try:
        with plaintext_workspace({"tmpfs": args.tmpfs}) as workspace:
            metadata, paths = [], []
            for index, ciphertext in enumerate([args.full, *args.binlog]):
                bundle = workspace / f"{index}.tar.gz"
                checked(["age", "--decrypt", "--identity", str(args.identity), "--output", str(bundle), str(ciphertext)])
                extracted = workspace / f"extracted-{index}"
                metadata.append(unpack(bundle, extracted))
                paths.append(extracted)
            plan = recovery_plan(metadata[0], metadata[1:], args.stop_at)
            report = {"mode": "PLAN", "plan": plan, "rpoVerified": False, "rtoVerified": False}
            if args.apply_isolated:
                logs = {}
                for meta, path in zip(metadata[1:], paths[1:]):
                    for payload in meta["payloads"]:
                        logs[Path(payload["name"]).name] = path / payload["name"]
                report.update(execute_restore(args.target_defaults, plan, paths[0], logs))
                report["mode"] = "ISOLATED_DATABASE_RESTORED"
            report["databaseProcedureSeconds"] = round(time.monotonic() - started, 3)
            report["rtoBoundary"] = "Database procedure only; incident detection, verification, effects and service resumption excluded"
            args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print("Recovery evidence written; workers remain absent. Full RPO/RTO is not inferred.")
    except (OSError, ValueError, RuntimeError, KeyError, tarfile.TarError):
        raise SystemExit("Recovery failed or was refused. No workers started; inspect isolated target privately.")


if __name__ == "__main__":
    main()
