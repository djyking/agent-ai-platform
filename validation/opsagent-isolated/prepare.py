"""Prepare a source-only isolated OpsAgent reactor. Never copy runtime configuration."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import uuid
import xml.etree.ElementTree as ET


MODULES = [
    "ops-common",
    "ops-common/ops-common-core",
    "ops-common/ops-common-web",
    "ops-common/ops-common-security",
    "ops-common/ops-common-mybatis",
    "ops-common/ops-common-redis",
    "ops-common/ops-common-mq",
    "ops-common/ops-common-observability",
    "ops-rag-service",
    "ops-knowledge-service",
    "ops-auth-service",
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--opsagent-root", type=Path, required=True)
    args = parser.parse_args()
    source = args.opsagent_root.resolve(strict=True)
    template = Path(__file__).resolve().parent
    harness = template.parent.parent
    output_parent = harness / ".work" / "opsagent-isolated"
    output_parent.mkdir(parents=True, exist_ok=True)
    destination = output_parent / str(uuid.uuid4())
    destination.mkdir()
    copied = []

    def copy_source(relative: Path) -> None:
        candidate = source
        for segment in relative.parts:
            candidate = candidate / segment
            if candidate.is_symlink() or getattr(candidate, "is_junction", lambda: False)():
                raise ValueError("Symlinked or junction source is not permitted")
        original = candidate.resolve(strict=True)
        if not original.is_relative_to(source) or not original.is_file():
            raise ValueError("Whitelisted source escapes the explicitly provided source root")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        data = original.read_bytes()
        target.write_bytes(data)
        copied.append({"path": relative.as_posix(), "sha256": hashlib.sha256(data).hexdigest()})

    copy_source(Path("pom.xml"))
    for module in MODULES:
        copy_source(Path(module) / "pom.xml")
        java_root = source / module / "src" / "main" / "java"
        if java_root.exists():
            for java in sorted(java_root.rglob("*.java")):
                copy_source(java.relative_to(source))

    # These two explicit migrations contain no runtime config, credentials or seed identities.
    for migration in [
        "ops-auth-service/src/main/resources/harness-identity-schema.sql",
        "ops-rag-service/src/main/resources/harness-route-schema.sql",
    ]:
        copy_source(Path(migration))

    namespace = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", namespace)
    ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
    root = ET.parse(destination / "pom.xml")
    modules = root.getroot().find(f"{{{namespace}}}modules")
    if modules is None:
        raise ValueError("OpsAgent parent POM has no modules")
    modules.clear()
    for name in ["ops-common", "ops-auth-service", "ops-rag-service", "ops-knowledge-service", "isolation-tests"]:
        ET.SubElement(modules, f"{{{namespace}}}module").text = name
    root.write(destination / "pom.xml", encoding="utf-8", xml_declaration=True)
    shutil.copytree(template / "test-module", destination / "isolation-tests")
    manifest = {
        "sourceRoot": str(source),
        "kind": "isolated-source-integration-not-a-deployed-environment",
        "copied": copied,
        "excluded": ["resources", "test resources", ".env", "application config", "target", "data", "credentials"],
        "generatedChanges": ["parent module list", "new isolation-tests module"],
    }
    (destination / "source-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(destination)


if __name__ == "__main__":
    main()
