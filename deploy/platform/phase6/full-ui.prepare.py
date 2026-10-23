#!/usr/bin/env python3
"""Render the current OpsAgent page workflows and Harness without changing the minimal profile."""
import importlib.util
from pathlib import Path


def main() -> int:
    source = Path(__file__).with_name("ai-minimal.prepare.py")
    spec = importlib.util.spec_from_file_location("full_ui_shared_prepare", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.main(profile="full-ui")


if __name__ == "__main__":
    raise SystemExit(main())
