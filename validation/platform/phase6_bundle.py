"""Package two clean develop commits and explicitly verified Phase 6 artifacts."""
import argparse
import hashlib
import io
import json
import math
import os
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

import phase5_bundle as previous
from phase6_continuous_observation import window_failure

MANIFEST_ENTRY = "bundle-manifest.json"
PHASE6_PREFIX = "deploy/platform/phase6/"
PHASE6_DEPLOY = frozenset({
    "README.md", "support.service", "support.env.example", "support.nginx.example.conf",
    "backup-tools.Dockerfile", "ops-runtime.Dockerfile",
    "ai-minimal.prepare.py", "ai-minimal.inputs.example.json", "ai-minimal.md",
    "ai-minimal.nginx.locations.conf", "internal-tls.nginx.conf", "support.Dockerfile",
    "prepare-internal-tls.py", "internal-tls.md", "tests/test_ai_minimal.py",
    "tests/run_spring_binding.py", "tests/test_spring_binding_runner.py",
    "tests/test_nginx_integration.py",
})
PHASE6_DOCS = frozenset({
    "docs/phase6-onboarding.md", "docs/phase6-acp.md", "docs/phase6-human-review.md",
    "docs/phase6-studio-pagination.md", "docs/phase6-continuous-observation.md",
    "docs/phase6-bundle.md",
})
OPS_FILES = frozenset({
    "README.md", "docs/harness-phase6.md",
    "ops-auth-service/src/main/resources/harness-identity-schema.sql",
    "ops-auth-service/src/main/resources/harness-identity-upgrade-v2.sql",
    "ops-rag-service/src/main/resources/harness-route-schema.sql",
    "ops-rag-service/src/main/resources/harness-answer-schema.sql",
    "ops-rag-service/src/main/resources/harness-answer-recovery.sql",
    "ops-rag-service/src/main/resources/harness-answer-retention.sql",
    "ops-rag-service/src/main/resources/internal-agent-schema.sql",
})
ARTIFACTS = {
    "platform": ("platform", "harness-platform-service", previous.JAR_ENTRY),
    "support": ("platform", "support-pilot", PHASE6_PREFIX + "support.jar"),
    "ops-auth": ("opsagent", "ops-auth-service", "opsagent/services/ops-auth.jar"),
    "ops-knowledge": ("opsagent", "ops-knowledge-service", "opsagent/services/ops-knowledge.jar"),
    "ops-ticket": ("opsagent", "ops-ticket-service", "opsagent/services/ops-ticket.jar"),
    "ops-rag": ("opsagent", "ops-rag-service", "opsagent/services/ops-rag.jar"),
    "ops-agent": ("opsagent", "ops-agent-service", "opsagent/services/ops-agent.jar"),
    "ops-platform": ("opsagent", "ops-platform-service", "opsagent/services/ops-platform.jar"),
    "ops-gateway": ("opsagent", "ops-gateway", "opsagent/services/ops-gateway.jar"),
}
PLATFORM_PACKAGE = "BOOT-INF/classes/io/github/djyking/harness/platform/"
REQUIRED_CLASSES = {
    "platform": {PLATFORM_PACKAGE + name + ".class" for name in previous.REQUIRED_JAR_CLASSES | {
        "AcpController", "AcpService", "OnboardingController", "OnboardingProvisioner",
        "OnboardingRepository", "OnboardingService", "StudioRepository", "HumanReviewFields",
    }} | {"BOOT-INF/classes/static/console/index.html"},
    "support": {
        "io/github/djyking/harness/support/SupportPilot.class",
        "io/github/djyking/harness/support/SupportIdentity.class", "web/index.html", "web/app.js",
    },
    "ops-auth": {
        "BOOT-INF/classes/com/opsagent/auth/OpsAuthApplication.class",
        "BOOT-INF/classes/com/opsagent/auth/HarnessIdentityController.class",
        "BOOT-INF/classes/com/opsagent/auth/HarnessIdentityService.class",
    },
    "ops-knowledge": {
        "BOOT-INF/classes/com/opsagent/knowledge/OpsKnowledgeApplication.class",
        "BOOT-INF/classes/com/opsagent/knowledge/KnowledgeInternalAgentController.class",
    },
    "ops-ticket": {
        "BOOT-INF/classes/com/opsagent/ticket/OpsTicketApplication.class",
        "BOOT-INF/classes/com/opsagent/ticket/KnowledgeTicketAccessController.class",
    },
    "ops-rag": {
        "BOOT-INF/classes/com/opsagent/rag/OpsRagApplication.class",
        "BOOT-INF/classes/com/opsagent/rag/HarnessAnswerService.class",
        "BOOT-INF/classes/com/opsagent/rag/HarnessAnswerRepository.class",
        "BOOT-INF/classes/com/opsagent/rag/HarnessCitationAccessController.class",
    },
    "ops-agent": {"BOOT-INF/classes/com/opsagent/agent/AgentApplication.class"},
    "ops-platform": {"BOOT-INF/classes/com/opsagent/platform/OpsPlatformApplication.class"},
    "ops-gateway": {"BOOT-INF/classes/com/opsagent/gateway/OpsGatewayApplication.class"},
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_member(name):
    path = PurePosixPath(name)
    return (bool(name) and not previous.secret_path(name) and ":" not in name
            and not any(part in {"", "."} for part in name.split("/")) and not path.is_absolute())


def regular_path(root, path, directory=False):
    candidate = path if path.is_absolute() else root / path
    candidate = candidate.absolute()
    for ancestor in (candidate, *candidate.parents):
        if ancestor.is_symlink() or getattr(ancestor, "is_junction", lambda: False)():
            raise ValueError("Artifact symlinks and junctions are forbidden")
    resolved = candidate.resolve(strict=True)
    if not resolved.is_relative_to(root) or (not resolved.is_dir() if directory else not resolved.is_file()):
        raise ValueError("Artifact must be a regular path inside its exact source repository")
    return resolved


def reject_output_redirects(path):
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink() or getattr(ancestor, "is_junction", lambda: False)():
            raise ValueError("Bundle output cannot traverse a symlink or junction")


def platform_files(entries):
    selected = previous.selected_files(entries)
    for name, mode, object_id in entries:
        if name.startswith(PHASE6_PREFIX):
            if name[len(PHASE6_PREFIX):] not in PHASE6_DEPLOY or not safe_member(name):
                raise ValueError("Unreviewed Phase 6 deployment file: " + name)
            if mode not in {"100644", "100755"}:
                raise ValueError("Non-regular deployment file: " + name)
            selected[name] = object_id
    missing = {PHASE6_PREFIX + name for name in PHASE6_DEPLOY} | PHASE6_DOCS
    if not missing <= selected.keys():
        raise ValueError("Required Phase 6 deployment files and documentation are not committed")
    return selected


def ops_files(entries):
    selected = {}
    for name, mode, object_id in entries:
        if name not in OPS_FILES:
            continue
        if mode not in {"100644", "100755"} or not safe_member(name):
            raise ValueError("Non-regular or private OpsAgent delivery file")
        selected["opsagent/" + name] = object_id
    if set(selected) != {"opsagent/" + name for name in OPS_FILES}:
        raise ValueError("Required OpsAgent explicit SQL and runbook are not committed")
    return selected


def checked_jar(root, role, spec):
    if set(spec) != {"path", "sha256"}:
        raise ValueError("Each artifact needs exactly path and an explicitly verified sha256")
    artifact = regular_path(root, Path(spec["path"]))
    module = ARTIFACTS[role][1]
    if (artifact.parent.name != "target" or artifact.parent.parent.name != module
            or artifact.suffix != ".jar"):
        raise ValueError("JAR must be from the matching module target directory")
    data = artifact.read_bytes()
    if (not isinstance(spec["sha256"], str)
            or not re.fullmatch(r"[a-f0-9]{64}", spec["sha256"]) or sha(data) != spec["sha256"]):
        raise ValueError("Artifact differs from caller-verified SHA-256: " + role)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or not REQUIRED_CLASSES[role] <= set(names):
            raise ValueError("Artifact lacks the required Phase 6 classes or contains duplicate entries")
        for name in names:
            if name.endswith("/"):
                continue
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
                raise ValueError("Unsafe JAR member")
            if not name.endswith((".class", ".jar")) and previous.secret_path(name):
                raise ValueError("Private resource is forbidden in delivered JAR")
        if role == "platform":
            for cls, marker in {
                "StudioRepository": b"VALUES('studio',2)",
                "OnboardingRepository": b"VALUES('onboarding',2)",
            }.items():
                if marker not in archive.read(PLATFORM_PACKAGE + cls + ".class"):
                    raise ValueError("Platform JAR does not contain the reviewed schema 2 migrations")
    return data


def frontend_files(root, path):
    directory = regular_path(root, path, directory=True)
    if directory != (root / "ops-web/dist").resolve():
        raise ValueError("Only the isolated OpsAgent ops-web/dist artifact is eligible")
    result = {}
    for candidate in sorted(directory.rglob("*")):
        # Check directories too: a junction may otherwise hide a secret tree.
        checked = regular_path(root, candidate, directory=candidate.is_dir())
        if checked.is_dir():
            continue
        relative = checked.relative_to(directory).as_posix()
        allowed = relative in {"index.html", "favicon.svg"} or re.fullmatch(
            r"assets/[A-Za-z0-9_.-]+\.(?:js|css|svg|png|jpg|jpeg|gif|webp|ico|woff|woff2|ttf)", relative)
        if not allowed or not safe_member(relative):
            raise ValueError("Unreviewed or private frontend artifact: " + relative)
        result[relative] = checked.read_bytes()
    if "index.html" not in result or not any(name.endswith(".js") for name in result):
        raise ValueError("A complete built Ops frontend is required")
    if len({name.casefold() for name in result}) != len(result):
        raise ValueError("Case-colliding frontend paths are forbidden")
    return result


def frontend_digest(payloads):
    manifest = {name: {"sha256": sha(data), "bytes": len(data)}
                for name, data in sorted(payloads.items())}
    return sha(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode())


def observation_summary(path):
    """Export only bounded non-secret state, never the source report or its private paths."""
    data = path.read_bytes()
    report = json.loads(data)
    status = report.get("status")
    allowed = {"RUNNING", "PASSED", "SHORT_RUN_PASSED", "FAILED_SERVICE_TARGET",
               "FAILED", "INTERRUPTED", "FAILED_PREFLIGHT"}
    if status not in allowed:
        raise ValueError("Unsupported continuous observation evidence state")
    elapsed = report.get("elapsedMonotonicSeconds", 0)
    if (not isinstance(elapsed, (int, float)) or isinstance(elapsed, bool)
            or not math.isfinite(elapsed) or elapsed < 0):
        raise ValueError("Invalid actual elapsed observation time")
    complete = report.get("completed24Hours") is True
    evidence = {}
    if status == "PASSED":
        duration = report.get("requestedSeconds")
        requests = report.get("requests", {})
        if not (type(duration) is int and 86400 <= duration <= 172800
                and complete and elapsed >= duration
                and report.get("continuous") is True and report.get("qualityPassed") is True
                and report.get("firstQualityFailure") is None
                and report.get("completedWindows") == duration // 60
                and report.get("completedMinuteWindows") == duration // 60
                and report.get("unreconciledCommands") == 0
                and requests == {"requests": duration * 10, "getRequests": duration * 9,
                                 "postRequests": duration, "errors": 0}):
            raise ValueError("PASSED requires actual continuous 24-hour evidence and passing windows")
        windows_path = path.with_name("windows.jsonl")
        if (not windows_path.is_file() or windows_path.is_symlink()
                or windows_path.stat().st_size > 16 * 1024 * 1024):
            raise ValueError("PASSED requires bounded complete minute evidence")
        raw_windows = windows_path.read_bytes()
        try:
            windows = [json.loads(line) for line in raw_windows.splitlines()]
            if len(windows) != duration // 60:
                raise ValueError("Incomplete minute evidence")
            totals = dict.fromkeys(requests, 0)
            last_elapsed = 0
            for sequence, window in enumerate(windows, 1):
                observed = window["elapsedMonotonicSeconds"]
                latencies = [window[key] for key in ("p95Millis", "getP95Millis", "postP95Millis")]
                if (window["window"] != sequence or window["completeMinute"] is not True
                        or not isinstance(observed, (int, float)) or isinstance(observed, bool)
                        or not math.isfinite(observed) or observed < sequence * 60
                        or observed <= last_elapsed or observed > elapsed
                        or any(type(value) not in (int, float) or not math.isfinite(value)
                               or value < 0 for value in latencies)
                        or window_failure(window) is not None):
                    raise ValueError("Invalid or failing minute evidence")
                for key in requests:
                    count = window[key]
                    if type(count) is not int or count < 0:
                        raise ValueError("Invalid minute counts")
                    totals[key] += count
                if window["requests"] != window["getRequests"] + window["postRequests"]:
                    raise ValueError("Inconsistent minute request totals")
                last_elapsed = observed
            if totals != requests or windows[-1] != report.get("latestWindow"):
                raise ValueError("Minute evidence does not match final report")
        except (KeyError, TypeError, json.JSONDecodeError) as error:
            raise ValueError("Malformed complete minute evidence") from error
        evidence = {"completeMinuteWindowsVerified": len(windows),
                    "minuteEvidenceSha256": sha(raw_windows),
                    "verifiedRequests": requests}
    elif complete:
        raise ValueError("Incomplete observation cannot claim completed24Hours")
    if status == "RUNNING":
        updated = datetime.fromisoformat(report["updatedAt"].replace("Z", "+00:00"))
        age = (datetime.now(timezone.utc) - updated).total_seconds()
        if age < -5 or age > 180:
            raise ValueError("Stale RUNNING observation evidence cannot be packaged as active")
    observation_id = report.get("observationId")
    if not isinstance(observation_id, str) or not re.fullmatch(r"soak-[a-f0-9]{32}", observation_id):
        raise ValueError("Expected a real observer invocation identifier")
    return {
        "status": status, "observationId": observation_id, "completed24Hours": complete,
        "elapsedMonotonicSeconds": elapsed, "reportSha256AtPackaging": sha(data),
        "evidenceScope": "Local loopback synthetic load against real identity and runtime",
        **evidence,
    }


def verify_bundle(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) != len({n.casefold() for n in names}):
            raise ValueError("Duplicate or case-colliding bundle entries")
        if any(not safe_member(name) for name in names):
            raise ValueError("Unsafe or private bundle entry")
        manifest = json.loads(archive.read(MANIFEST_ENTRY))
        records = manifest["files"]
        if set(names) != set(records) | {MANIFEST_ENTRY}:
            raise ValueError("Manifest and bundle contents differ")
        for name, record in records.items():
            data = archive.read(name)
            if len(data) != record["bytes"] or sha(data) != record["sha256"]:
                raise ValueError("Bundle read-back verification failed")
        for artifact in manifest["artifacts"].values():
            if artifact["sha256"] != records[artifact["entry"]]["sha256"]:
                raise ValueError("Artifact provenance differs from bundle payload")
        if "opsFrontendManifestSha256" in manifest:
            prefix = "opsagent/ops-web/dist/"
            frontend = {name[len(prefix):]: archive.read(name) for name in names if name.startswith(prefix)}
            if frontend_digest(frontend) != manifest["opsFrontendManifestSha256"]:
                raise ValueError("Frontend provenance differs from bundle payload")
        return manifest


def build(repository, ops_repository, artifact_manifest, observation_report):
    root = repository.resolve(strict=True)
    ops = ops_repository.resolve(strict=True)
    if ops != (root / ".work/opsagent-phase6").resolve(strict=True):
        raise ValueError("Use the explicitly isolated OpsAgent Phase 6 worktree")
    provenance = {name: previous.clean_commit(repo) for name, repo in [("platform", root), ("opsagent", ops)]}
    specs = json.loads(artifact_manifest.read_text(encoding="utf-8-sig"))
    if set(specs) != set(ARTIFACTS) | {"ops-web"}:
        raise ValueError("Exactly platform, support, seven Ops service JARs, and ops-web are required")
    payloads = {}
    for repo_name, repo, selector in [("platform", root, platform_files), ("opsagent", ops, ops_files)]:
        selected = selector(previous.tree_entries(repo, provenance[repo_name][0]))
        payloads.update({name: previous.git(repo, "cat-file", "blob", object_id)
                         for name, object_id in selected.items()})
    artifacts = {}
    for role, (repo_name, _, entry) in ARTIFACTS.items():
        content = checked_jar(root if repo_name == "platform" else ops, role, specs[role])
        payloads[entry] = content
        artifacts[role] = {"entry": entry, "sha256": sha(content), "repository": repo_name}
    front_spec = specs["ops-web"]
    if set(front_spec) != {"path", "sha256"}:
        raise ValueError("Frontend needs exactly path and a verified canonical manifest sha256")
    frontend = frontend_files(ops, Path(front_spec["path"]))
    front_hash = frontend_digest(frontend)
    if (not isinstance(front_spec["sha256"], str)
            or not re.fullmatch(r"[a-f0-9]{64}", front_spec["sha256"]) or front_hash != front_spec["sha256"]):
        raise ValueError("Ops frontend differs from caller-verified canonical manifest SHA-256")
    payloads.update({"opsagent/ops-web/dist/" + name: content for name, content in frontend.items()})
    local_observation = observation_summary(observation_report)
    if len(payloads) != len({n.casefold() for n in payloads}) or any(not safe_member(n) for n in payloads):
        raise ValueError("Unsafe or colliding package payload paths")
    manifest = {
        "format": "harness-phase6-engineering-bundle-v1",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "repositories": {name: {"gitCommit": commit, "gitBranch": branch, "worktreeClean": True}
                         for name, (commit, branch) in provenance.items()},
        "artifacts": artifacts, "opsFrontendManifestSha256": front_hash,
        "local24HourObservation": local_observation,
        "linux7DayObservation": "PENDING_TARGET_ENVIRONMENT",
        "crossHostRecovery": "PENDING_TARGET_ENVIRONMENT",
        "productionAcceptance": False, "privateStateIncluded": False, "testReportsIncluded": False,
        "acceptanceScope": "Local engineering; authorized VPS installation and public access require separate target acceptance",
        "artifactProvenance": "Caller-verified SHA-256; no embedded Git compilation attestation",
        "gitCommitScope": "Exact committed deployment files and docs, not independent proof of artifact compilation",
        "fileHashScope": "All payload files; external ZIP SHA-256 protects the manifest",
        "files": {name: {"sha256": sha(data), "bytes": len(data)}
                  for name, data in sorted(payloads.items())},
    }
    destination = root / ".work/phase6/dist"
    reject_output_redirects(destination)
    destination.mkdir(parents=True, exist_ok=True)
    regular_path(root, destination, directory=True)
    output = destination / ("harness-phase6-" + provenance["platform"][0][:12] + "-" + provenance["opsagent"][0][:12] + ".zip")
    partial = output.with_suffix(".zip.partial")
    checksum = output.with_suffix(".zip.sha256")
    if any(p.exists() for p in [output, partial, checksum]):
        raise ValueError("Existing delivery artifacts cannot be overwritten")
    try:
        with zipfile.ZipFile(partial, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name, data in sorted(payloads.items()):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
            archive.writestr(MANIFEST_ENTRY, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        verify_bundle(partial)
        for name, repo in [("platform", root), ("opsagent", ops)]:
            if previous.clean_commit(repo) != provenance[name]:
                raise ValueError("Git provenance changed during packaging")
        # Recheck source artifacts, including untracked build outputs, before finalizing.
        for role, (repo_name, _, _) in ARTIFACTS.items():
            checked_jar(root if repo_name == "platform" else ops, role, specs[role])
        if frontend_digest(frontend_files(ops, Path(front_spec["path"]))) != front_hash:
            raise ValueError("Frontend artifact changed during packaging")
        # Same-directory hard-link promotion is atomic and never replaces an existing path.
        os.link(partial, output)
        partial.unlink()
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    digest = sha(output.read_bytes())
    with checksum.open("x", encoding="ascii") as stream:
        stream.write(digest + "  " + output.name + "\n")
    return {"bundle": str(output), "sha256": digest, "repositories": manifest["repositories"],
            "fileCount": len(payloads), "readBackVerified": True,
            "local24HourObservation": local_observation, "productionAcceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ops-repository", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--observation-report", type=Path)
    parser.add_argument("--inspect-frontend", action="store_true",
                        help="Print the built ops-web canonical manifest digest without packaging")
    args = parser.parse_args()
    if args.inspect_frontend:
        ops = args.ops_repository.resolve(strict=True)
        print(json.dumps({"opsFrontendManifestSha256": frontend_digest(frontend_files(ops, Path("ops-web/dist")))}))
        return
    if not args.artifacts or not args.observation_report:
        parser.error("--artifacts and --observation-report are required for packaging")
    print(json.dumps(build(args.repository, args.ops_repository, args.artifacts, args.observation_report), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as failure:
        raise SystemExit(str(failure))
