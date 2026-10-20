#!/usr/bin/env python3
"""Run one offline Spring binding test using dependencies from the built Boot JAR."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import unittest
import zipfile

ARTIFACTS = (
    "spring-boot", "spring-core", "spring-jcl", "spring-context",
    "spring-beans", "spring-expression", "snakeyaml",
)
LIBRARY = re.compile(
    r"BOOT-INF/lib/(" + "|".join(ARTIFACTS) + r")-([0-9][A-Za-z0-9.+-]*)\.jar"
)
MAX_LIBRARY_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


class InvalidArtifact(ValueError):
    pass


def extract_dependencies(jar: Path, destination: Path) -> list[Path]:
    """Validate archive names first, then copy only seven flat, selected libraries."""
    selected = {}
    names = set()
    with zipfile.ZipFile(jar) as archive:
        for member in archive.infolist():
            name = member.orig_filename
            path = name[:-1] if name.endswith("/") else name
            if (not path or name != member.filename or "\\" in name or ":" in name or "\x00" in name
                    or any(part in ("", ".", "..") for part in path.split("/"))
                    or name in names or stat.S_ISLNK(member.external_attr >> 16)):
                raise InvalidArtifact("Archive contains duplicate or unsafe paths")
            names.add(name)
            match = LIBRARY.fullmatch(name)
            if match is None:
                continue
            artifact = match.group(1)
            if artifact in selected:
                raise InvalidArtifact("Multiple versions of a required library")
            if (member.is_dir() or member.flag_bits & 1
                    or not 0 < member.file_size <= MAX_LIBRARY_BYTES):
                raise InvalidArtifact("Invalid required library entry")
            selected[artifact] = member
        if set(selected) != set(ARTIFACTS):
            raise InvalidArtifact("Required Spring binding libraries are absent")
        if sum(value.file_size for value in selected.values()) > MAX_TOTAL_BYTES:
            raise InvalidArtifact("Required libraries exceed the extraction bound")
        outputs = []
        for artifact in ARTIFACTS:
            member = selected[artifact]
            output = destination / member.filename.rsplit("/", 1)[1]
            with archive.open(member) as source, output.open("xb") as target:
                count = 0
                while chunk := source.read(65536):
                    count += len(chunk)
                    if count > member.file_size or count > MAX_LIBRARY_BYTES:
                        raise InvalidArtifact("Required library exceeds its declared bound")
                    target.write(chunk)
            if count != member.file_size:
                raise InvalidArtifact("Required library was truncated")
            outputs.append(output)
    return outputs


def accepted_result(result: unittest.TestResult) -> bool:
    return result.testsRun == 1 and not result.skipped and result.wasSuccessful()


def execute_binding(classpath: str) -> unittest.TestResult:
    previous = os.environ.get("HARNESS_SPRING_BINDING_CLASSPATH")
    os.environ["HARNESS_SPRING_BINDING_CLASSPATH"] = classpath
    try:
        spec = importlib.util.spec_from_file_location(
            "required_spring_binding_check", Path(__file__).with_name("test_ai_minimal.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(module.SpringBindingTests)
        return unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        if previous is None:
            os.environ.pop("HARNESS_SPRING_BINDING_CLASSPATH", None)
        else:
            os.environ["HARNESS_SPRING_BINDING_CLASSPATH"] = previous


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        digest = hashlib.sha256()
        with args.jar.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
        with tempfile.TemporaryDirectory(prefix="harness-spring-binding-") as temporary:
            libraries = extract_dependencies(args.jar, Path(temporary))
            result = execute_binding(os.pathsep.join(map(str, libraries)))
            passed = accepted_result(result)
            print(json.dumps({
                "status": "PASSED" if passed else "FAILED", "tests": result.testsRun,
                "skipped": len(result.skipped), "failures": len(result.failures),
                "errors": len(result.errors), "jarSha256": digest.hexdigest(),
                "dependencyFiles": [path.name for path in libraries],
            }))
            return 0 if passed else 1
    except (InvalidArtifact, OSError, ValueError, zipfile.BadZipFile):
        # Do not echo arbitrary archive names, file content or exception payloads.
        print("Spring binding verification rejected the artifact.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
