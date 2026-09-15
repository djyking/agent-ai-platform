"""Validate the design contract and examples; does not test an HTTP service."""

from __future__ import annotations

import copy
import json
from pathlib import Path
import sys

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec


ROOT = Path(__file__).resolve().parent


def reject_non_json(value: str) -> None:
    raise ValueError(f"Non-JSON numeric literal: {value}")


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_non_json)


def local_refs(value: object, root: dict) -> None:
    if isinstance(value, dict):
        if "$ref" in value:
            reference = value["$ref"]
            assert reference.startswith("#/"), f"Remote reference prohibited: {reference}"
            target = root
            for part in reference[2:].split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
        for child in value.values():
            local_refs(child, root)
    elif isinstance(value, list):
        for child in value:
            local_refs(child, root)


def request_shape(value: object, depth: int = 0) -> None:
    if depth > 16:
        raise ValueError("Request JSON nesting exceeds 16")
    if isinstance(value, dict):
        for child in value.values():
            request_shape(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            request_shape(child, depth + 1)


def main() -> int:
    spec = load_json(ROOT / "openapi.json")
    validate_spec(spec)
    local_refs(spec, spec)
    assert spec["security"] == [{"AccessToken": []}]
    operations = []
    for path, item in spec["paths"].items():
        assert path.startswith("/v1/projects/{projectId}/runs")
        for method in ("get", "post"):
            if method not in item:
                continue
            operation = item[method]
            operations.append(operation["operationId"])
            assert "security" not in operation, "Operations must inherit authentication"
            for code in ("401", "403", "404", "429", "503", "default"):
                assert code in operation["responses"], (path, code)
            if method == "post":
                parameters = {x["$ref"].split("/")[-1] for x in operation["parameters"]}
                assert "Idempotency" in parameters
                assert "202" in operation["responses"]
                if path != "/v1/projects/{projectId}/runs":
                    assert "IfMatch" in parameters
                    assert {"412", "428"} <= operation["responses"].keys()
    assert len(operations) == len(set(operations)), "Duplicate operationId"
    assert len(operations) == 11, "Review endpoint scope before changing the operation count"

    def validator(name: str) -> Draft202012Validator:
        document = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$ref": f"#/components/schemas/{name}",
            "components": spec["components"],
        }
        return Draft202012Validator(document, format_checker=FormatChecker())

    examples = 0
    for name, schema in spec["components"]["schemas"].items():
        Draft202012Validator.check_schema(schema)
        for example in schema.get("examples", []):
            validator(name).validate(example)
            examples += 1

    media_examples = 0

    def validate_media_examples(value: object) -> None:
        nonlocal media_examples
        if isinstance(value, dict):
            for media in value.get("content", {}).values():
                if "example" in media:
                    schema = media["schema"]
                    assert "$ref" in schema, "Review inline media schema validation"
                    validator(schema["$ref"].split("/")[-1]).validate(media["example"])
                    media_examples += 1
            for child in value.values():
                validate_media_examples(child)
        elif isinstance(value, list):
            for child in value:
                validate_media_examples(child)

    validate_media_examples(spec["paths"])
    validate_media_examples(spec["components"]["responses"])

    cases = load_json(ROOT / "contract-cases.json")["cases"]
    for case in cases:
        value = copy.deepcopy(case["value"])
        errors = list(validator(case["schema"]).iter_errors(value))
        valid = not errors
        assert valid == case["valid"], (
            case["name"],
            "expected valid=" + str(case["valid"]),
            [error.message for error in errors],
        )

    # These HTTP limits supplement JSON Schema. Their actual enforcement still
    # requires service tests; here we prevent the documented policy from drifting.
    bounded = {"inputs": {"value": {}}}
    current = bounded["inputs"]["value"]
    for _ in range(17):
        current["child"] = {}
        current = current["child"]
    try:
        request_shape(bounded)
    except ValueError:
        pass
    else:
        raise AssertionError("Depth guard accepted an oversized tree")
    request_shape(spec["components"]["schemas"]["CreateRunRequest"]["examples"][0])
    oversized = {"inputs": {f"v{i}": "x" * 16384 for i in range(5)}}
    assert len(json.dumps(oversized).encode("utf-8")) > 65536
    print(
        f"PASS: OpenAPI 3.1; {len(operations)} operations; "
        f"{examples} schema examples; {media_examples} HTTP media examples; "
        f"{len(cases)} positive/negative cases; local references and policy guards."
    )
    print("Design validation only: HTTP authorization, SQL atomicity and remote behavior are not implemented or proved here.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, KeyError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
