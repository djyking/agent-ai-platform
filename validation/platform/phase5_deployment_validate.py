"""Run safe fixture tests and optional Docker Compose parsing; never starts containers."""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "deploy/platform/phase5"


def validate_template_contracts(jar):
    """Load public templates using the supplied build's actual Java contracts, entirely offline."""
    jar_bytes = jar.read_bytes()
    java_source = """
import java.nio.file.Path;
import io.github.djyking.harness.platform.Deployment;
import io.github.djyking.harness.platform.ExecutionGovernanceConfig;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.core.ToolRegistry;

class Phase5ConfigCheck {
    public static void main(String[] args) {
        var deployment = Deployment.load(Path.of(args[0]));
        var governance = ExecutionGovernanceConfig.load(Path.of(args[1]));
        for (var release : deployment.releases()) {
            deployment.validateRelease(release, new ToolRegistry());
        }
        System.out.println(Json.write(Json.object()
            .put("deploymentLoad", "PASS")
            .put("releaseCapabilityValidation", "PASS")
            .put("governanceLoad", "PASS")
            .put("projects", deployment.projects().size())
            .put("releases", deployment.releases().size())
            .put("modelConcurrency", governance.modelConcurrency())));
    }
}
"""
    with tempfile.TemporaryDirectory(prefix="harness-template-contracts-") as directory:
        staging = Path(directory)
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            for item in archive.infolist():
                name = PurePosixPath(item.filename)
                if name.parts[:2] == ("BOOT-INF", "classes") and not item.is_dir():
                    relative = PurePosixPath("classes", *name.parts[2:])
                elif name.parts[:2] == ("BOOT-INF", "lib") and len(name.parts) == 3 and item.filename.endswith(".jar"):
                    relative = PurePosixPath("lib", name.name)
                else:
                    continue
                if ".." in relative.parts or "\\" in str(relative) or ":" in str(relative):
                    raise ValueError("Unsafe path in platform JAR")
                destination = staging.joinpath(*relative.parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(item))
        source = staging / "Phase5ConfigCheck.java"
        source.write_text(java_source, encoding="utf-8")
        classpath = os.pathsep.join((str(staging / "classes"), str(staging / "lib" / "*")))
        result = subprocess.run(["java", "--class-path", classpath, str(source),
                                 str(PACKAGE / "deployment.example.json"),
                                 str(PACKAGE / "governance.example.json")],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError("Java deployment/governance template contracts rejected: " + result.stderr)
        evidence = json.loads(result.stdout)
        evidence["jarSha256"] = hashlib.sha256(jar_bytes).hexdigest()
        evidence["scope"] = "Java load and release constraints only; no runtime, secrets, database or model connections"
        return evidence


def validate(compose=False, jar=None):
    command = [sys.executable, "-m", "unittest", "discover", "-s", "validation/platform",
               "-p", "phase5_deployment_test.py", "-v"]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    print(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError("Deployment fixture tests failed")
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "local fixtures and configuration parsing only",
              "fixtureTests": "PASS", "dockerComposeParse": "NOT_RUN",
              "javaTemplateContracts": "NOT_RUN",
              "linuxDeployment": "PENDING_ENVIRONMENT", "crossHostRestore": "PENDING_ENVIRONMENT",
              "rpo15Minutes": "NOT_MEASURED", "rto60Minutes": "NOT_MEASURED",
              "sevenDayObservation": "NOT_STARTED", "realNotifications": "NOT_SENT"}
    if jar is not None:
        report["javaTemplateContracts"] = validate_template_contracts(jar)
    if compose:
        with tempfile.TemporaryDirectory(prefix="harness-compose-") as directory:
            staging = Path(directory)
            shutil.copyfile(PACKAGE / "compose.yaml", staging / "compose.yaml")
            shutil.copyfile(PACKAGE / "recovery.compose.yaml", staging / "recovery.compose.yaml")
            (staging / "private").mkdir()
            for name in ("runtime.env", "migration.env"):
                (staging / "private" / name).write_text("HARNESS_JDBC_USER=synthetic_fixture\n", encoding="utf-8")
            environment = dict(os.environ, HARNESS_IMAGE="harness-platform:synthetic-fixture")
            environment.pop("HARNESS_MIGRATION_OFFLINE_ACK", None)
            for filename in ("compose.yaml", "recovery.compose.yaml"):
                result = subprocess.run(["docker", "compose", "-f", str(staging / filename), "config", "--quiet"],
                                        capture_output=True, text=True, env=environment)
                if result.returncode:
                    raise RuntimeError("Docker Compose configuration rejected: " + result.stderr)
            result = subprocess.run(["docker", "compose", "-f", str(staging / "compose.yaml"), "--profile", "migration", "config", "--format", "json"],
                                    capture_output=True, text=True, env=environment)
            if result.returncode:
                raise RuntimeError("Could not inspect migration profile defaults")
            services = json.loads(result.stdout)["services"]
            if services["migrate"]["environment"].get("HARNESS_MIGRATION_OFFLINE_ACK"):
                raise RuntimeError("Compose must not claim an existing cluster has been stopped by default")
            if any("HARNESS_MIGRATION_OFFLINE_ACK" in services[name]["environment"] for name in ("api", "worker1", "worker2")):
                raise RuntimeError("Runtime containers must not inherit the migration assertion")
            report["dockerComposeParse"] = "PASS_NO_DAEMON_REQUIRED"
    files = sorted(path for path in PACKAGE.rglob("*") if path.is_file()
                   and "private" not in path.relative_to(PACKAGE).parts and "__pycache__" not in path.parts)
    report["packageFilesSha256"] = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                                    for path in files}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose", action="store_true")
    parser.add_argument("--jar", type=Path, help="Built Spring Boot JAR for offline Java template contract checks (JDK 17+ required)")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.compose, args.jar)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Deployment package checks passed; external operational targets remain unmeasured.")
