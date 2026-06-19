#!/usr/bin/env python3
"""Validate fixture `expects` blocks against locale profile requirements."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


EXPRESSION_RE = re.compile(r"\{\$(?P<arg>[A-Za-z_][\w.-]*)(?:\s+:(?P<fn>[A-Za-z_][\w.-]*)(?P<opts>[^{}]*))?\}")
OPTION_RE = re.compile(r"(?P<key>[A-Za-z_][\w.-]*)=(?:\$(?P<ref>[A-Za-z_][\w.-]*)|(?P<value>[^\s]+))")


def parse_options(options: str) -> dict[str, str]:
    return {
        match.group("key"): match.group("ref") or match.group("value")
        for match in OPTION_RE.finditer(options)
    }


def load_profile(root: Path, profile_id: str) -> dict[str, Any]:
    return json.loads((root / "profiles" / f"{profile_id}.json").read_text(encoding="utf-8"))


def requirements_for(function_name: str, options: dict[str, str], profile: dict[str, Any]) -> set[str]:
    function_profile = profile.get("functions", {}).get(function_name)
    if not function_profile:
        return set()

    requirements = set(function_profile.get("requires", []))
    option_requirements = function_profile.get("options", {})
    for key, value in options.items():
        requirements.update(option_requirements.get(f"{key}={value}", []))
    return requirements


def expression_requirements(pattern: str, profile: dict[str, Any]) -> dict[str, set[str]]:
    by_arg: dict[str, set[str]] = {}
    for match in EXPRESSION_RE.finditer(pattern):
        function_name = match.group("fn")
        if not function_name:
            continue
        arg = match.group("arg")
        options = parse_options(match.group("opts") or "")
        by_arg.setdefault(arg, set()).update(requirements_for(function_name, options, profile))

        target_arg = options.get("with") or options.get("of")
        if target_arg:
            by_arg.setdefault(target_arg, set()).update(requirements_for(function_name, options, profile))
    return by_arg


def validate_fixture(root: Path, fixture: Path) -> list[str]:
    data = json.loads(fixture.read_text(encoding="utf-8"))
    profile = load_profile(root, data.get("profile", data["locale"]))
    failures: list[str] = []

    for message_id, message in data.get("messages", {}).items():
        expected = message.get("expects", {})
        if not expected:
            continue
        actual = expression_requirements(message["value"], profile)
        for arg, expectation in expected.items():
            required = set(expectation.get("requires", []))
            if expectation.get("type") == "context":
                required.update(requirements_for("context", {}, profile))
                actual.setdefault(arg, set()).update(requirements_for("context", {}, profile))
            missing = required - actual.get(arg, set())
            if missing:
                failures.append(
                    f"{fixture}:{message_id}:{arg}: expects {sorted(required)}, profile inferred {sorted(actual.get(arg, set()))}, missing {sorted(missing)}"
                )
    return failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("dev-docs/experiments/mf2-grammar-agreement"),
    )
    args = parser.parse_args()

    failures: list[str] = []
    for fixture in sorted(args.root.glob("fixtures/**/*.json")):
        try:
            failures.extend(validate_fixture(args.root, fixture))
        except FileNotFoundError as exc:
            failures.append(f"{fixture}: missing profile ({exc})")

    if failures:
        print("\n".join(failures))
        raise SystemExit(1)

    print("profile requirements ok")


if __name__ == "__main__":
    main()
