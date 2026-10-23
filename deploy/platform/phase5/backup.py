"""Encrypted full/binlog backups, verified SSH copies and retention preview. Linux only.

No delete operation exists. Plaintext temporary data is confined to a private tmpfs.
External commands receive credential file paths, never passwords in argv or logs.
"""
import argparse
import contextlib
import hashlib
import json
import os
import re
import shlex
import subprocess
import tarfile
import tempfile
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


def utcnow():
    return datetime.now(timezone.utc)


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as value:
        for chunk in iter(lambda: value.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked(command, *, output=None, stdin=None):
    result = subprocess.run(command, input=stdin, stdout=output or subprocess.PIPE,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise RuntimeError(Path(command[0]).name + " failed; sensitive diagnostics suppressed")
    return result.stdout


def identifier(value):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", value):
        raise ValueError("Invalid database identifier")
    return value


def mysql(config, statement):
    return checked(["mysql", "--defaults-extra-file=" + str(Path(config["mysqlDefaults"]).resolve()),
                    "--batch", "--skip-column-names"], stdin=statement.encode()).decode().strip()


def source(config):
    identifier(config["database"])
    values = mysql(config, "SELECT @@server_uuid,@@log_bin,@@binlog_format,UTC_TIMESTAMP(6)").split("\t")
    if len(values) != 4 or values[1:3] != ["1", "ROW"]:
        raise ValueError("PITR requires enabled MySQL ROW binary logging")
    return {"serverUuid": values[0], "database": config["database"],
            "observedAtUtc": values[3] + "Z"}


def full_coordinates(dump):
    expression = re.compile(r"(?:SOURCE|MASTER)_LOG_FILE='([^']+)', (?:SOURCE|MASTER)_LOG_POS=(\d+)")
    with Path(dump).open(encoding="utf-8", errors="strict") as content:
        for line in content:
            match = expression.search(line)
            if match:
                name, position = match.groups()
                if not re.fullmatch(r"[A-Za-z0-9_.-]+\.\d{6,}", name):
                    raise ValueError("Unexpected binary log name")
                return {"file": name, "position": int(position)}
    raise ValueError("Consistent binary-log coordinates were not present in full dump")


@contextlib.contextmanager
def plaintext_workspace(config):
    if os.name != "posix":
        raise ValueError("Backup execution requires a Linux host with private tmpfs")
    root = Path(config["tmpfs"]).resolve()
    if not root.is_relative_to(Path("/dev/shm")) or root == Path("/dev/shm"):
        raise ValueError("Plaintext temporary files must stay below /dev/shm")
    mounts = Path("/proc/mounts").read_text().splitlines()
    if not any(row.split()[1:3] == ["/dev/shm", "tmpfs"] for row in mounts):
        raise ValueError("/dev/shm is not a verified tmpfs mount")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.stat().st_mode & 0o077:
        raise ValueError("Temporary directory must be mode 0700")
    with tempfile.TemporaryDirectory(prefix="backup-", dir=root) as directory:
        yield Path(directory)


def add_private_state(archive, state):
    state = Path(state).resolve(strict=True)
    if not state.is_dir():
        raise ValueError("Private recovery state directory is required")
    files = sorted(state.rglob("*"))
    if not any(item.is_file() for item in files):
        raise ValueError("Private state must include signing/configuration/identity recovery material")
    records = []
    for path in files:
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError("Private state cannot contain links or special files")
        before = file_hash(path) if path.is_file() else None
        archive.add(path, arcname="private-state/" + path.relative_to(state).as_posix(), recursive=False)
        if before is not None:
            if file_hash(path) != before:
                raise ValueError("Private state changed during backup; coordinate rotation and retry")
            records.append({"name": "private-state/" + path.relative_to(state).as_posix(), "sha256": before})
    return records


def encrypt_bundle(config, workspace, metadata, payloads, include_state=False):
    bundle = workspace / "bundle.tar.gz"
    metadata["payloads"] = [{"name": name, "sha256": file_hash(path)} for name, path in payloads]
    inner = workspace / "manifest.json"
    with tarfile.open(bundle, "w:gz") as archive:
        for name, path in payloads:
            archive.add(path, arcname=name)
        if include_state:
            metadata["privateState"] = add_private_state(archive, config["privateState"])
        inner.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        archive.add(inner, arcname="manifest.json")
    destination = Path(config["destination"]).resolve()
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = utcnow().strftime("%Y%m%dT%H%M%SZ")
    name = metadata["kind"] + "-" + stamp + "-" + uuid.uuid4().hex[:8] + ".tar.gz.age"
    encrypted = destination / name
    partial = encrypted.with_suffix(encrypted.suffix + ".partial")
    checked(["age", "--recipients-file", str(Path(config["ageRecipients"]).resolve()),
             "--output", str(partial), str(bundle)])
    os.replace(partial, encrypted)
    receipt = {"format": "harness-encrypted-backup-v1", "kind": metadata["kind"],
               "file": name, "sha256": file_hash(encrypted), "bytes": encrypted.stat().st_size,
               "createdAt": utcnow().isoformat(), "source": metadata["source"],
               "recoveryExecution": "DISABLED", "offsiteVerified": False}
    if "coverageLowerBoundUtc" in metadata:
        receipt["coverageLowerBoundUtc"] = metadata["coverageLowerBoundUtc"]
    if "coordinates" in metadata:
        receipt["coordinates"] = metadata["coordinates"]
    encrypted.with_suffix(encrypted.suffix + ".json").write_text(
        json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


def metric(config, kind, timestamp):
    directory = Path(config["metricsDirectory"])
    directory.mkdir(mode=0o755, parents=True, exist_ok=True)
    # Only scalar timestamps live here. The unprivileged textfile exporter needs read access.
    os.chmod(directory, 0o755)
    path = directory / (kind + ".prom")
    staging = directory / (kind + ".prom.partial")
    name = "harness_" + kind + "_last_success_timestamp_seconds"
    staging.write_text(f"# TYPE {name} gauge\n{name} {timestamp}\n", encoding="ascii")
    os.chmod(staging, 0o644)
    os.replace(staging, path)


def full_backup(config):
    info = source(config)
    with plaintext_workspace(config) as workspace:
        dump = workspace / "database.sql"
        with dump.open("wb") as output:
            checked(["mysqldump", "--defaults-extra-file=" + str(Path(config["mysqlDefaults"]).resolve()),
                     "--single-transaction", "--source-data=2", "--no-tablespaces",
                     "--set-gtid-purged=OFF", "--hex-blob", "--default-character-set=utf8mb4",
                     config["database"]], output=output)
        metadata = {"format": "harness-backup-v1", "kind": "full", "source": info,
                    "coordinates": full_coordinates(dump), "createdAt": utcnow().isoformat(),
                    "privateStateConsistency": "operator-quiesced-or-versioned"}
        result = encrypt_bundle(config, workspace, metadata, [("database.sql", dump)], True)
    metric(config, "backup", utcnow().timestamp())
    return result


def archive_binlogs(config):
    if config.get("dedicatedInstanceConfirmed") is not True:
        raise ValueError("Binary logs include the whole MySQL instance; confirm dedicated platform scope first")
    info = source(config)
    # Closing the active file creates a conservatively timed, finite archive boundary.
    lower_bound = mysql(config, "SELECT UTC_TIMESTAMP(6)") + "Z"
    mysql(config, "FLUSH BINARY LOGS")
    rows = mysql(config, "SHOW BINARY LOGS").splitlines()
    names = [row.split("\t")[0] for row in rows[:-1]]
    if not names or any(not re.fullmatch(r"[A-Za-z0-9_.-]+\.\d{6,}", n) for n in names):
        raise ValueError("No closed binary logs available")
    destination = Path(config["destination"])
    seen = set()
    for path in destination.glob("binlog-*.tar.gz.age.json"):
        receipt = json.loads(path.read_text(encoding="utf-8"))
        if receipt.get("source", {}).get("serverUuid") == info["serverUuid"]:
            # A deleted/corrupt ciphertext cannot count as a previously archived log.
            ciphertext = destination / receipt["file"]
            if ciphertext.is_file() and file_hash(ciphertext) == receipt["sha256"]:
                seen.update(receipt.get("logNames", []))
    pending = [name for name in names if name not in seen]
    if not pending:
        raise ValueError("Rotation produced no newly closed log; investigate archive state")
    with plaintext_workspace(config) as workspace:
        for name in pending:
            checked(["mysqlbinlog", "--defaults-extra-file=" + str(Path(config["mysqlDefaults"]).resolve()),
                     "--read-from-remote-server", "--raw", "--result-file=" + str(workspace) + "/", name])
        metadata = {"format": "harness-backup-v1", "kind": "binlog", "source": info,
                    "logNames": pending, "coverageLowerBoundUtc": lower_bound,
                    "createdAt": utcnow().isoformat()}
        result = encrypt_bundle(config, workspace, metadata,
                                [("binlogs/" + name, workspace / name) for name in pending])
    result["logNames"] = pending
    receipt_path = destination / (result["file"] + ".json")
    receipt_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    metric(config, "binlog", utcnow().timestamp())
    return result


def copy_offsite(config, receipt, verify_only=False):
    offsite = config["offsite"]
    host, directory = offsite["host"], offsite["directory"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+", host):
        raise ValueError("Use a fixed user@host SSH destination")
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", directory) or ".." in Path(directory).parts:
        raise ValueError("Use a fixed absolute offsite directory")
    if offsite.get("confirmedIndependentHost") is not True:
        raise ValueError("Operator must identify an independently hosted backup target")
    path = Path(config["destination"]) / receipt["file"]
    if file_hash(path) != receipt["sha256"]:
        raise ValueError("Ciphertext checksum changed before upload")
    target = directory.rstrip("/") + "/" + path.name
    identity, known_hosts = offsite["sshIdentity"], offsite["sshKnownHosts"]
    if any(not re.fullmatch(r"/[A-Za-z0-9_./-]+", value) or ".." in Path(value).parts
           for value in (identity, known_hosts)):
        raise ValueError("SSH identity and known-hosts files require fixed absolute paths")
    options = ["-oBatchMode=yes", "-oStrictHostKeyChecking=yes", "-oConnectTimeout=15",
               "-oIdentitiesOnly=yes", "-i", identity, "-oUserKnownHostsFile=" + known_hosts]
    if not verify_only:
        checked(["scp", *options, str(path), host + ":" + target])
    program = ("import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); "
               "h=hashlib.file_digest(p.open('rb'),'sha256').hexdigest(); "
               "sys.exit(0 if h==sys.argv[2] else 1)")
    remote = " ".join(shlex.quote(value) for value in ["python3", "-c", program, target, receipt["sha256"]])
    checked(["ssh", *options, host, remote])
    receipt = dict(receipt, offsiteVerified=True, offsiteHost=host,
                   offsiteVerifiedAt=utcnow().isoformat())
    sidecar = path.with_suffix(path.suffix + ".json")
    sidecar.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    checked(["scp", *options, str(sidecar), host + ":" + target + ".json"])
    return receipt


def sync_offsite(config):
    receipts = []
    for path in sorted(Path(config["destination"]).glob("*.tar.gz.age.json")):
        receipt = json.loads(path.read_text(encoding="utf-8"))
        receipts.append(copy_offsite(config, receipt, verify_only=receipt.get("offsiteVerified", False)))
    if not any(row["kind"] == "full" for row in receipts) or not any(row["kind"] == "binlog" for row in receipts):
        raise ValueError("Offsite freshness needs at least a full backup and a binlog chain")
    newest_log = max((row for row in receipts if row["kind"] == "binlog"), key=lambda row: row["createdAt"])
    # observedAtUtc is per operation, so compare the stable source identity below.
    related = [row for row in receipts if row["source"]["serverUuid"] == newest_log["source"]["serverUuid"]
               and row["source"]["database"] == newest_log["source"]["database"]]
    fulls = [row for row in related if row["kind"] == "full"]
    if not fulls:
        raise ValueError("Newest log source has no independently copied full backup")
    full = max(fulls, key=lambda row: row["createdAt"])
    prefix, first = full["coordinates"]["file"].rsplit(".", 1)
    numbers = sorted({int(name.rsplit(".", 1)[1]) for row in related if row["kind"] == "binlog"
                      for name in row.get("logNames", []) if name.rsplit(".", 1)[0] == prefix
                      and int(name.rsplit(".", 1)[1]) >= int(first)})
    if not numbers or numbers != list(range(int(first), max(numbers) + 1)):
        raise ValueError("Offsite log chain is incomplete from the newest full snapshot")
    # Conservative: freshness belongs to the latest archived coverage, never the retry time.
    latest = max(row["createdAt"] for row in receipts if row["kind"] == "binlog")
    metric(config, "backup_offsite", datetime.fromisoformat(latest).timestamp())
    return {"verifiedCiphertexts": len(receipts), "lastArchiveCreatedAt": latest}


def retention_preview(config, now=None):
    now = now or utcnow()
    days = int(config.get("retentionDays", 30))
    if days < 30:
        raise ValueError("This approved policy requires at least 30 backup days")
    cutoff = now - timedelta(days=days)
    candidates = []
    for path in sorted(Path(config["destination"]).glob("*.tar.gz.age.json")):
        row = json.loads(path.read_text(encoding="utf-8"))
        if datetime.fromisoformat(row["createdAt"]) < cutoff:
            candidates.append({"file": row["file"], "kind": row["kind"],
                               "createdAt": row["createdAt"], "offsiteVerified": row.get("offsiteVerified", False)})
    return {"mode": "DRY_RUN_ONLY", "retentionDays": days, "deleteCount": 0,
            "requiresChainReview": True, "candidates": candidates,
            "note": "Keep an earlier full backup plus every required binlog across the cutoff; no files removed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["full", "binlog", "offsite", "retention-preview"])
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    functions = {"full": full_backup, "binlog": archive_binlogs, "offsite": sync_offsite,
                 "retention-preview": retention_preview}
    try:
        print(json.dumps(functions[args.command](config), indent=2))
    except (OSError, ValueError, RuntimeError, KeyError):
        raise SystemExit("Backup operation failed. No success metric advanced; inspect private configuration and prerequisites.")


if __name__ == "__main__":
    main()
