"""Build a clean-commit Linux engineering bundle; private state and test reports are excluded."""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

DEPLOY_PREFIX = "deploy/platform/phase5/"
DEPLOY_ALLOWLIST = frozenset({
    ".dockerignore", ".env.example", ".gitignore", "Dockerfile", "compose.yaml",
    "recovery.compose.yaml", "runtime.env.example", "mysql.production.cnf",
    "prepare.py", "alert_sink.py", "db_accounts.py", "backup.py", "restore.py",
    "backup.example.json", "monitoring/prometheus.yml", "monitoring/rules.yml",
    "deployment.example.json", "governance.example.json",
    "monitoring/alertmanager.yml", "monitoring/grafana/dashboards/harness.json",
    "monitoring/grafana/provisioning/dashboards/default.yml",
    "monitoring/grafana/provisioning/datasources/default.yml",
    "systemd/harness-backup@.service", "systemd/harness-backup-full.timer",
    "systemd/harness-backup-binlog.timer", "systemd/harness-backup-offsite.timer",
    "systemd/harness-backup-retention-preview.timer",
})
API_DOC_ALLOWLIST = frozenset({
    "docs/api/openapi.json", "docs/api/catalog-openapi.json", "docs/api/contract-cases.json",
    "docs/api/requirements.txt", "docs/api/validate_catalog.py", "docs/api/validate_contract.py",
})
REQUIRED_DOCS = frozenset({
    "docs/phase5-deployment.md", "docs/phase5-execution-governance.md",
    "docs/phase5-operations-and-retention.md", "docs/phase5-plan-20260920.md",
})
JAR_ENTRY = DEPLOY_PREFIX + "platform.jar"
MANIFEST_ENTRY = "bundle-manifest.json"
REQUIRED_JAR_CLASSES = frozenset({
    "PlatformApplication", "ExecutionGovernance", "RuntimeSettings", "SchemaMigration",
    "PermitResolution", "RetentionService", "OperationsController",
})


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Git provenance check failed")
    return result.stdout


def clean_commit(repo):
    if git(repo, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("A clean committed worktree is required; no production or release label is permitted for a dirty tree")
    commit = git(repo, "rev-parse", "HEAD").decode().strip()
    branch = git(repo, "symbolic-ref", "--quiet", "--short", "HEAD").decode().strip()
    if not branch.startswith("develop/"):
        raise ValueError("This engineering bundle must be produced from a develop/ branch")
    return commit, branch


def secret_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        return True
    for component in path.parts:
        lower = component.lower()
        if lower in {"private", ".work", "__pycache__", "node_modules"}:
            return True
        if (lower == ".env" or lower.startswith(".env.")) and lower != ".env.example":
            return True
        if ("private" in lower or any(value in lower for value in ("password", "credential", "secret", "certificate"))) and ".example." not in lower:
            return True
        if lower.endswith((".pem", ".key", ".p12", ".pfx", ".agekey", ".keystore")):
            return True
    return False


def selected_files(entries):
    selected = {}
    for name, mode, object_id in entries:
        if name.startswith(DEPLOY_PREFIX):
            relative = name[len(DEPLOY_PREFIX):]
            if relative not in DEPLOY_ALLOWLIST or secret_path(name):
                raise ValueError("Unreviewed or private deployment file is tracked: " + name)
        elif name.startswith("docs/"):
            if name.startswith("docs/validation/"):
                continue
            if secret_path(name):
                raise ValueError("Private-looking documentation is not eligible for a bundle: " + name)
            if PurePosixPath(name).suffix != ".md" and name not in API_DOC_ALLOWLIST:
                continue
        elif name != "README.md":
            continue
        if mode not in ("100644", "100755"):
            raise ValueError("Symlinks and non-regular Git entries cannot be bundled: " + name)
        selected[name] = object_id
    missing = {DEPLOY_PREFIX + name for name in DEPLOY_ALLOWLIST} - selected.keys()
    missing |= REQUIRED_DOCS - selected.keys()
    if missing:
        raise ValueError("Required deployment/document files are not committed: " + ", ".join(sorted(missing)))
    if len({name.casefold() for name in selected}) != len(selected):
        raise ValueError("Case-colliding bundle paths are not portable")
    return selected


def tree_entries(repo, commit):
    entries = []
    for row in git(repo, "ls-tree", "-r", "-z", commit).split(b"\0"):
        if not row:
            continue
        header, name = row.split(b"\t", 1)
        mode, kind, object_id = header.decode("ascii").split(" ")
        entries.append((name.decode("utf-8"), mode if kind == "blob" else kind, object_id))
    return entries


def checked_jar(repo, jar, expected_sha256):
    candidate = jar.absolute()
    for path in (candidate, *candidate.parents):
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
            raise ValueError("Artifact symlinks/junctions are not accepted")
    artifact = candidate.resolve(strict=True)
    if not artifact.is_relative_to(repo) or artifact.suffix != ".jar":
        raise ValueError("Specify the tested service JAR inside this repository")
    content = artifact.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha256) or digest != expected_sha256:
        raise ValueError("The supplied JAR differs from the explicitly verified SHA-256")
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        names = set(archive.namelist())
        required = {"BOOT-INF/classes/io/github/djyking/harness/platform/" + name + ".class"
                    for name in REQUIRED_JAR_CLASSES} | {"BOOT-INF/classes/static/console/index.html"}
        if not required <= names:
            raise ValueError("Artifact must contain the Phase 5 service classes and built console")
        for name in names:
            # Never inspect or unpack nested third-party libraries; only application resources.
            if name.startswith("BOOT-INF/classes/") and not name.endswith(".class") and secret_path(name):
                raise ValueError("Private application resource found in JAR: " + name)
    return content, digest


def verify_bundle(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate bundle entries")
        manifest = json.loads(archive.read(MANIFEST_ENTRY))
        records = manifest["files"]
        if set(names) != set(records) | {MANIFEST_ENTRY}:
            raise ValueError("Manifest and bundle contents differ")
        for name, record in records.items():
            data = archive.read(name)
            if len(data) != record["bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
                raise ValueError("Bundle read-back verification failed: " + name)
        if records[JAR_ENTRY]["sha256"] != manifest["jarSha256"]:
            raise ValueError("JAR provenance differs from bundle payload")
        return manifest


def build(repo, jar, expected_sha256):
    repo = repo.resolve(strict=True)
    commit, branch = clean_commit(repo)
    selected = selected_files(tree_entries(repo, commit))
    jar_bytes, jar_hash = checked_jar(repo, jar, expected_sha256)
    payloads = {name: git(repo, "cat-file", "blob", object_id) for name, object_id in selected.items()}
    payloads[JAR_ENTRY] = jar_bytes
    manifest = {
        "format": "harness-phase5-engineering-bundle-v1", "gitCommit": commit, "gitBranch": branch,
        "worktreeClean": True, "createdAt": datetime.now(timezone.utc).isoformat(),
        "acceptanceScope": "local engineering; Linux pending", "productionAcceptance": False,
        "local24HourObservation": "NOT_COMPLETED", "linux7DayObservation": "PENDING_ENVIRONMENT",
        "jarSha256": jar_hash,
        "jarProvenance": "Caller-supplied validated artifact SHA-256; no embedded Git build attestation",
        "gitCommitScope": "Committed deployment package and public documentation; does not independently attest JAR compilation",
        "privateStateIncluded": False, "testReportsIncluded": False,
        "fileHashScope": "All payload files; manifest integrity is covered by the external ZIP SHA-256",
        "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                  for name, data in sorted(payloads.items())},
    }
    directory = (repo / ".work/phase5/dist").resolve()
    if not directory.is_relative_to(repo / ".work/phase5"):
        raise ValueError("Bundle output must remain inside the intended .work/phase5 directory")
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / ("harness-phase5-" + commit[:12] + ".zip")
    partial = output.with_suffix(".zip.partial")
    if output.exists() or partial.exists():
        raise ValueError("Bundle destination already exists; existing delivery artifacts are not overwritten")
    try:
        with zipfile.ZipFile(partial, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name, data in sorted(payloads.items()):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
            archive.writestr(MANIFEST_ENTRY, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        verify_bundle(partial)
        if clean_commit(repo) != (commit, branch):
            raise ValueError("Git provenance changed while building the bundle")
        os.replace(partial, output)
    except Exception:
        partial.unlink(missing_ok=True)
        raise
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".zip.sha256").write_text(digest + "  " + output.name + "\n", encoding="ascii")
    return {"bundle": str(output), "sha256": digest, "gitCommit": commit, "gitBranch": branch,
            "jarSha256": jar_hash, "fileCount": len(payloads), "readBackVerified": True,
            "acceptanceScope": manifest["acceptanceScope"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--jar-sha256", required=True, help="SHA-256 of the artifact validated by the caller")
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.repository, args.jar, args.jar_sha256), indent=2))
    except (ValueError, OSError, zipfile.BadZipFile) as failure:
        raise SystemExit(str(failure))
