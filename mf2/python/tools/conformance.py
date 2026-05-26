from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from mojito_mf2._locale_key import canonical_locale_key, locale_lookup_chain
from mojito_mf2 import (
    MF2Error,
    format_message_to_parts,
    format_message,
)
from mojito_mf2.parser import parse_to_model


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
    checked_source_cases = 0
    checked_parts_cases = 0
    checked_fallback_cases = 0
    checked_fallback_parts_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        source_result = parse_to_model(fixture["source"])
        if source_result.model != fixture["expectedModel"]:
            print(
                f"{fixture_path.name}: expected parsed model {fixture['expectedModel']!r}, "
                f"got {source_result.model!r}; diagnostics={source_result.diagnostics!r}",
                file=sys.stderr,
            )
            return 1
        checked_source_cases += 1
        for format_case in fixture.get("formatCases", []):
            actual = format_message(
                fixture["expectedModel"],
                format_case.get("arguments", {}),
                format_case.get("locale", "en"),
                bidi_isolation=format_case.get("bidiIsolation", "none"),
            )
            expected = format_case["expected"]
            if actual.value != expected or actual.errors:
                print(
                    f"{fixture_path.name}: expected {expected!r}, got {actual.value!r}; "
                    f"errors={actual.errors!r}",
                    file=sys.stderr,
                )
                return 1
            checked_cases += 1
        for parts_case in fixture.get("partsCases", []):
            actual = format_message_to_parts(
                fixture["expectedModel"],
                parts_case.get("arguments", {}),
                parts_case.get("locale", "en"),
            )
            expected = parts_case["expected"]
            if actual.parts != expected or actual.errors:
                print(
                    f"{fixture_path.name}: expected parts {expected!r}, got {actual.parts!r}; "
                    f"errors={actual.errors!r}",
                    file=sys.stderr,
                )
                return 1
            checked_parts_cases += 1
        for fallback_case in fixture.get("fallbackCases", []):
            actual = format_message(
                fixture["expectedModel"],
                fallback_case.get("arguments", {}),
                fallback_case.get("locale", "en"),
                bidi_isolation=fallback_case.get("bidiIsolation", "none"),
            )
            expected = fallback_case["expected"]
            if actual.value != expected:
                print(
                    f"{fixture_path.name}: expected fallback {expected!r}, got {actual.value!r}",
                    file=sys.stderr,
                )
                return 1
            _assert_error_codes(fixture_path, "fallback errors", actual.errors, fallback_case)
            checked_fallback_cases += 1
        for parts_case in fixture.get("fallbackPartsCases", []):
            actual = format_message_to_parts(
                fixture["expectedModel"],
                parts_case.get("arguments", {}),
                parts_case.get("locale", "en"),
            )
            expected = parts_case["expected"]
            if actual.parts != expected:
                print(
                    f"{fixture_path.name}: expected fallback parts {expected!r}, got {actual.parts!r}",
                    file=sys.stderr,
                )
                return 1
            _assert_error_codes(fixture_path, "fallback parts errors", actual.errors, parts_case)
            checked_fallback_parts_cases += 1

    try:
        checked_invalid_source_cases = _check_invalid_source_fixtures(fixture_dir.parent)
        checked_error_cases = _check_format_error_fixtures(fixture_dir.parent)
        checked_locale_key_cases = _check_locale_key_fixtures(fixture_dir.parent)
    except ConformanceFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    print(
        "Python MF2 conformance runner passed "
        f"{checked_source_cases} source models, "
        f"{checked_cases} format cases, {checked_parts_cases} parts cases, "
        f"{checked_fallback_cases} fallback cases, "
        f"{checked_fallback_parts_cases} fallback parts cases, "
        f"{checked_invalid_source_cases} invalid source cases, "
        f"{checked_error_cases} format error cases, "
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
            actual_codes = [error.code]
        else:
            actual_codes = [error.code for error in actual.errors]
        expected_code = fixture["expectedError"]["code"]
        if actual_codes[:1] != [expected_code]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected error {expected_code!r}, "
                f"got {actual_codes!r}"
            )
        checked_cases += 1

    return checked_cases


def _check_invalid_source_fixtures(fixture_root: Path) -> int:
    fixture_dir = fixture_root / "invalid-source"
    if not fixture_dir.exists():
        return 0

    checked_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        result = parse_to_model(fixture["source"])
        actual_codes = [diagnostic.code for diagnostic in result.diagnostics]
        expected_codes = [diagnostic["code"] for diagnostic in fixture.get("expectedDiagnostics", [])]
        if actual_codes != expected_codes:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected diagnostics {expected_codes!r}, got {actual_codes!r}"
            )
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


def _assert_error_codes(
    fixture_path: Path,
    label: str,
    actual_errors: list[MF2Error],
    item: dict[str, Any],
) -> None:
    actual_codes = [error.code for error in actual_errors]
    expected_codes = [error["code"] for error in item.get("expectedErrors", [])]
    if actual_codes != expected_codes:
        raise ConformanceFailure(
            f"{fixture_path.name}: expected {label} {expected_codes!r}, got {actual_codes!r}"
        )


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


if __name__ == "__main__":
    raise SystemExit(main())
