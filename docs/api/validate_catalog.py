"""Validate the added catalog contract independently of the eleven existing Run operations."""
import json
from pathlib import Path
from openapi_spec_validator import validate

contract = json.loads(Path(__file__).with_name("catalog-openapi.json").read_text(encoding="utf-8-sig"))
validate(contract)
operations = [(path, method) for path, item in contract["paths"].items()
              for method in item if method in {"get", "post", "put", "delete", "patch"}]
assert len(operations) >= 12, "Catalog lifecycle/discovery contract is incomplete"
assert all(path.startswith("/v1/projects/{project}/catalog/") for path, _ in operations)
print(json.dumps({"catalogOperations": len(operations), "openapiValid": True}))
