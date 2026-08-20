#!/usr/bin/env python3
"""Verify localized YAML strings with PyYAML's actual safe loader."""

from __future__ import annotations

import json
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parent


def main() -> int:
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    checked = 0
    for case in manifest["workflowCases"]:
        if not case.get("yamlRuntime"):
            continue
        localized = yaml.safe_load((ROOT / case["localized"]).read_text(encoding="utf-8"))
        expected_values = case.get("yamlRuntimeExpected", case["translations"])
        for path, expected in expected_values.items():
            actual = yaml_path(localized, path)
            if type(actual) is not str or actual != expected:
                raise AssertionError(
                    f"{case['id']}/{path}: expected YAML string {expected!r}, "
                    f"got {type(actual).__name__} {actual!r}"
                )
            checked += 1
    if checked == 0:
        raise AssertionError("No YAML runtime cases were declared")
    print(f"PyYAML verified {checked} localized scalar values remain exact strings.")
    return 0


def yaml_path(root: object, path: str) -> object:
    current = root
    for component in path.split("/"):
        name, indexes = split_indexes(component)
        if name:
            if not isinstance(current, dict):
                raise AssertionError(f"{path}: {name!r} is not inside a mapping")
            current = current[name]
        for index in indexes:
            if not isinstance(current, list):
                raise AssertionError(f"{path}: index {index} is not inside a sequence")
            current = current[index]
    return current


def split_indexes(component: str) -> tuple[str, list[int]]:
    name = component.split("[", maxsplit=1)[0]
    indexes: list[int] = []
    suffix = component[len(name) :]
    while suffix:
        close = suffix.index("]")
        indexes.append(int(suffix[1:close]))
        suffix = suffix[close + 1 :]
    return name, indexes


if __name__ == "__main__":
    raise SystemExit(main())
