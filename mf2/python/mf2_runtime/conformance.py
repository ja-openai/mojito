from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from .locale_key import canonical_locale_key, locale_lookup_chain
from .model import MF2Error, format_message


class ConformanceFailure(Exception):
    pass


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    fixture_dir = (
        Path(args[0])
        if args
        else Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "source-to-model"
    )

    checked_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        for format_case in fixture.get("formatCases", []):
            actual = format_message(
                fixture["expectedModel"],
                format_case.get("arguments", {}),
                format_case.get("locale", "en"),
            )
            expected = format_case["expected"]
            if actual != expected:
                print(
                    f"{fixture_path.name}: expected {expected!r}, got {actual!r}",
                    file=sys.stderr,
                )
                return 1
            checked_cases += 1

    try:
        checked_error_cases = _check_format_error_fixtures(fixture_dir.parent)
        checked_locale_key_cases = _check_locale_key_fixtures(fixture_dir.parent)
    except ConformanceFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    print(
        "Python MF2 conformance runner passed "
        f"{checked_cases} format cases, {checked_error_cases} format error cases, "
        f"and {checked_locale_key_cases} locale key cases."
    )
    return 0


def _check_format_error_fixtures(fixture_root: Path) -> int:
    fixture_dir = fixture_root / "format-errors"
    if not fixture_dir.exists():
        return 0

    checked_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        try:
            actual = format_message(
                fixture["model"],
                fixture.get("arguments", {}),
                fixture.get("locale", "en"),
            )
        except MF2Error as error:
            expected_code = fixture["expectedError"]["code"]
            if error.code != expected_code:
                raise ConformanceFailure(
                    f"{fixture_path.name}: expected error {expected_code!r}, "
                    f"got {error.code!r}"
                )
        else:
            raise ConformanceFailure(f"{fixture_path.name}: expected format error, got {actual!r}")
        checked_cases += 1

    return checked_cases


def _check_locale_key_fixtures(fixture_root: Path) -> int:
    fixture_path = fixture_root / "locale-key" / "cases.json"
    if not fixture_path.exists():
        return 0

    checked_cases = 0
    fixture = _read_json(fixture_path)
    for item in fixture.get("canonical", []):
        actual = canonical_locale_key(item["source"])
        if actual != item["expected"]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected canonical {item['expected']!r}, got {actual!r}"
            )
        checked_cases += 1

    for item in fixture.get("lookupChains", []):
        actual = locale_lookup_chain(item["source"])
        if actual != item["expected"]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected lookup chain {item['expected']!r}, got {actual!r}"
            )
        checked_cases += 1

    return checked_cases


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


if __name__ == "__main__":
    raise SystemExit(main())
