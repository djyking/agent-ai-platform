"""Build a sanitized report from Surefire and recheck only the whitelisted source files."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--maven-exit", type=int, required=True)
    args = parser.parse_args()
    snapshot = args.snapshot.resolve(strict=True)
    allowed = Path(__file__).resolve().parents[2] / ".work" / "opsagent-isolated"
    if not snapshot.is_relative_to(allowed.resolve()):
        raise ValueError("Snapshot is outside the dedicated isolated workspace")
    manifest_bytes = (snapshot / "source-manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    source = Path(manifest["sourceRoot"]).resolve(strict=True)
    changed = []
    for item in manifest["copied"]:
        path = (source / item["path"]).resolve(strict=True)
        if not path.is_relative_to(source):
            raise ValueError("Source verification escaped the original source root")
        if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            changed.append(item["path"])
    cases = []
    for report in (snapshot / "isolation-tests" / "target" / "surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for case in suite.findall("testcase"):
            status = "passed"
            if case.find("failure") is not None or case.find("error") is not None:
                status = "failed"
            elif case.find("skipped") is not None:
                status = "skipped"
            cases.append({"name": case.get("name"), "status": status})
    report = {
        "schemaVersion": 1,
        "at": datetime.now(timezone.utc).isoformat(),
        "validationKind": "isolated-source-backed-opsagent-pilot",
        "status": "PASSED" if args.maven_exit == 0 and cases and not changed
            and all(case["status"] == "passed" for case in cases) else "FAILED",
        "synthetic": True,
        "testUserIds": [10, 20],
        "deployedOpsAgentValidated": False,
        "mavenExitCode": args.maven_exit,
        "sourceManifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
        "sourceFileCount": len(manifest["copied"]),
        "originalRepositoryWritten": False,
        "whitelistedSourceBytesUnchanged": not changed,
        "changedSourcePaths": changed,
        "realImplementations": [
            "InternalAgentController", "InternalActorTokens", "InternalActorAccess",
            "InternalAgentSearchService", "RerankService + NoOpRerankProvider", "ContextAssembler",
            "KnowledgeInternalAgentController", "KnowledgeService", "KnowledgeRepository",
            "GlobalExceptionHandler", "Harness + WorkflowProgram + OpsAgentRagTool",
        ],
        "fixturesAndBoundaries": [
            "Manual loopback Spring MVC/Tomcat host; original application boot/configuration not loaded",
            "Auth user storage is an independent two-user fixture; real signature, issuer and auth audience verification",
            "InternalKnowledgeClient uses explicit loopback HTTP instead of Feign/service discovery",
            "H2 synthetic seven-document database; real SQL visibility/publication/owner filtering",
            "KnowledgeIndexService disabled mock selects real SQL fallback; no Elasticsearch/embedding calls",
            "RagRateLimiter mock; distributed rate limit is not covered",
            "Model/usage/file/parser/MQ dependencies are unused mocks; test asserts no model generation or write dependencies",
            "Routing policy case, if present, is only an in-memory fixture; no deployed ingress migration",
        ],
        "externalCalls": 0,
        "externalCallsEvidence": "Configured call graph uses loopback URLs only; remote rerank disabled and index mocked. This is not a packet-captured traffic count.",
        "runtimeExternalServicesConfigured": False,
        "networkTrafficCaptured": False,
        "operatingSystemNetworkSandbox": False,
        "dependencyDownloadsExcludedFromRuntimeClaim": True,
        "secretHandling": "Fresh random signing key per test process; no original config/credentials copied; ledger assertions exclude key and generated bearer credentials",
        "cases": cases,
        "summary": {name: sum(case["status"] == name for case in cases)
                    for name in ["passed", "failed", "skipped"]},
    }
    target = snapshot / "isolation-tests" / "target" / "isolation-report.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['status']}: {len(cases)} cases; whitelisted source unchanged={not changed}")
    print(target)
    if report["status"] != "PASSED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
