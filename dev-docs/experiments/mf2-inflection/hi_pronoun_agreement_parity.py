#!/usr/bin/env python3
"""Validate the pinned Hindi pronoun agreement fixture without using Java."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parents[2]
DEFAULT_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json"
)

EXPECTED_SCHEMA = "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0"
EXPECTED_PACK_SHAPE = "dependency-pronoun-agreement-rows-v0"
EXPECTED_JSON_BYTES = 9927
EXPECTED_JSON_SHA256 = "585a4213c32b9f44efee42a0946b9333fee610e7c5d305b469ad946c55491a59"
EXPECTED_SOURCE = "inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv"
EXPECTED_SOURCE_SHA256 = "586446abbbdc6d8466912941ac2609d7bdac072af5c3422bf575a8715b1bf810"
EXPECTED_SOURCE_BYTES = 2433
PRONOUN_ROW_BYTES = 8

PERSON_VALUES = {"first", "second", "third"}
ROW_NUMBER_VALUES = {"any", "plural", "singular"}
REQUEST_NUMBER_VALUES = {"plural", "singular"}
CASE_VALUES = {"accusative", "direct", "ergative", "genitive"}
REGISTER_VALUES = {"formal", "informal", "intimate"}
GENDER_VALUES = {"feminine", "masculine"}


def read_fixture(path: Path) -> tuple[bytes, dict[str, Any]]:
    payload = path.read_bytes()
    return payload, json.loads(payload.decode("utf-8"))


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AssertionError(f"Expected object: {label}")
    return value


def require_array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise AssertionError(f"Expected array: {label}")
    return value


def require_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise AssertionError(f"Expected non-empty text: {label}")
    return value


def require_int(value: Any, label: str) -> int:
    if not isinstance(value, int):
        raise AssertionError(f"Expected int: {label}")
    return value


def optional_text(value: Any, label: str) -> str | None:
    if value is None:
        return None
    return require_text(value, label)


def require_one_of(value: Any, label: str, allowed: set[str]) -> str:
    text = require_text(value, label)
    if text not in allowed:
        raise AssertionError(f"Unsupported {label}: {text}")
    return text


def optional_one_of(value: Any, label: str, allowed: set[str]) -> str | None:
    text = optional_text(value, label)
    if text is not None and text not in allowed:
        raise AssertionError(f"Unsupported {label}: {text}")
    return text


def expanded_numbers(number: str) -> list[str]:
    if number == "any":
        return ["plural", "singular"]
    return [number]


def selector_key(
    person: str,
    number: str,
    grammatical_case: str,
    register: str | None,
    dependency_gender: str | None,
    dependency_number: str | None,
) -> tuple[str, str, str, str | None, str | None, str | None]:
    return (person, number, grammatical_case, register, dependency_gender, dependency_number)


def validate_row(raw_row: Any) -> dict[str, Any]:
    row = require_object(raw_row, "row")
    value = require_text(row.get("value"), "value")
    line = require_int(row.get("line"), "line")
    if line <= 0:
        raise AssertionError("Hindi pronoun line must be positive")
    person = require_one_of(row.get("person"), "person", PERSON_VALUES)
    number = require_one_of(row.get("number"), "number", ROW_NUMBER_VALUES)
    grammatical_case = require_one_of(row.get("case"), "case", CASE_VALUES)
    register = optional_one_of(row.get("register"), "register", REGISTER_VALUES)
    dependency_gender = optional_one_of(row.get("dependencyGender"), "dependencyGender", GENDER_VALUES)
    dependency_number = optional_one_of(row.get("dependencyNumber"), "dependencyNumber", REQUEST_NUMBER_VALUES)

    if grammatical_case == "genitive":
        if dependency_gender is None or dependency_number is None:
            raise AssertionError("Hindi genitive pronoun row requires dependency")
    elif dependency_gender is not None or dependency_number is not None:
        raise AssertionError("Hindi non-genitive pronoun row cannot include dependency")

    if person == "second" and register is None:
        raise AssertionError("Hindi second-person pronoun row requires register")
    if person != "second" and register is not None:
        raise AssertionError("Hindi non-second pronoun row cannot include register")

    return {
        "value": value,
        "line": line,
        "person": person,
        "number": number,
        "case": grammatical_case,
        "register": register,
        "dependencyGender": dependency_gender,
        "dependencyNumber": dependency_number,
    }


def render_pronoun(
    rows: list[dict[str, Any]],
    person: str,
    number: str,
    grammatical_case: str,
    register: str | None = None,
    dependency_gender: str | None = None,
    dependency_number: str | None = None,
) -> str:
    if number not in REQUEST_NUMBER_VALUES:
        raise AssertionError(f"Unsupported request number: {number}")
    matches = [
        row
        for row in rows
        if row["person"] == person
        and row["case"] == grammatical_case
        and row["number"] in {number, "any"}
        and row["register"] == register
        and row["dependencyGender"] == dependency_gender
        and row["dependencyNumber"] == dependency_number
    ]
    if len(matches) != 1:
        selector = selector_key(
            person, number, grammatical_case, register, dependency_gender, dependency_number
        )
        raise AssertionError(f"Expected one Hindi pronoun match for {selector}, got {len(matches)}")
    return matches[0]["value"]


def assert_hindi_pronoun_fixture(path: Path) -> dict[str, Any]:
    payload, pack = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != EXPECTED_JSON_BYTES:
        raise AssertionError(f"Expected {EXPECTED_JSON_BYTES} fixture bytes, got {len(payload)}")
    if digest != EXPECTED_JSON_SHA256:
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    if pack.get("schema") != EXPECTED_SCHEMA:
        raise AssertionError(f"Unexpected schema: {pack.get('schema')!r}")
    if pack.get("locale") != "hi":
        raise AssertionError(f"Unexpected locale: {pack.get('locale')!r}")
    if pack.get("packShape") != EXPECTED_PACK_SHAPE:
        raise AssertionError(f"Unexpected pack shape: {pack.get('packShape')!r}")

    provenance = require_object(pack.get("provenance"), "provenance")
    if provenance.get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if provenance.get("generator") != "dev-docs/experiments/mf2-inflection/hi_pack_survey.py":
        raise AssertionError("Unexpected Hindi pronoun generator")
    source_labels = [require_text(value, "sourceLabels") for value in require_array(provenance.get("sourceLabels"), "sourceLabels")]
    if source_labels != [EXPECTED_SOURCE]:
        raise AssertionError(f"Unexpected source labels: {source_labels}")
    sources = [require_object(value, "source") for value in require_array(provenance.get("sources"), "sources")]
    if len(sources) != 1:
        raise AssertionError(f"Expected one source, got {len(sources)}")
    source = sources[0]
    if source.get("path") != EXPECTED_SOURCE:
        raise AssertionError(f"Unexpected source path: {source.get('path')!r}")
    if source.get("sha256") != EXPECTED_SOURCE_SHA256:
        raise AssertionError(f"Unexpected source SHA-256: {source.get('sha256')!r}")
    if source.get("byteSize") != EXPECTED_SOURCE_BYTES:
        raise AssertionError(f"Unexpected source byte size: {source.get('byteSize')!r}")
    if source.get("gitLfsPointer") is not False:
        raise AssertionError("Hindi pronoun source must not be a Git LFS pointer")

    rows = [validate_row(raw_row) for raw_row in require_array(pack.get("rows"), "rows")]
    summary = require_object(pack.get("summary"), "summary")
    binary_lower_bound = require_object(
        summary.get("binaryLowerBoundBytes"), "summary.binaryLowerBoundBytes"
    )

    expected_summary = {
        "dependencyRows": sum(
            1 for row in rows if row["dependencyGender"] is not None or row["dependencyNumber"] is not None
        ),
        "genitiveRows": sum(1 for row in rows if row["case"] == "genitive"),
        "invariantNumberRows": sum(1 for row in rows if row["number"] == "any"),
        "rows": len(rows),
        "uniqueValues": len({row["value"] for row in rows}),
    }
    for key, expected in expected_summary.items():
        if summary.get(key) != expected:
            raise AssertionError(f"Hindi pronoun summary mismatch for {key}: {summary.get(key)}")
    if expected_summary != {
        "dependencyRows": 20,
        "genitiveRows": 20,
        "invariantNumberRows": 14,
        "rows": 38,
        "uniqueValues": 30,
    }:
        raise AssertionError(f"Unexpected pinned Hindi pronoun summary: {expected_summary}")

    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in {row["value"] for row in rows})
    row_bytes = len(rows) * PRONOUN_ROW_BYTES
    if binary_lower_bound.get("stringPoolBytes") != string_pool_bytes:
        raise AssertionError("Hindi pronoun string-pool byte estimate mismatch")
    if binary_lower_bound.get("rowBytes") != row_bytes:
        raise AssertionError("Hindi pronoun row byte estimate mismatch")
    if binary_lower_bound.get("totalBytes") != row_bytes + string_pool_bytes:
        raise AssertionError("Hindi pronoun total byte estimate mismatch")
    if binary_lower_bound.get("totalBytes") != 730:
        raise AssertionError("Unexpected Hindi pronoun total byte estimate")

    selectors: set[tuple[str, str, str, str | None, str | None, str | None]] = set()
    for row in rows:
        for number in expanded_numbers(row["number"]):
            selector = selector_key(
                row["person"],
                number,
                row["case"],
                row["register"],
                row["dependencyGender"],
                row["dependencyNumber"],
            )
            if selector in selectors:
                raise AssertionError(f"Ambiguous Hindi pronoun selector: {selector}")
            selectors.add(selector)

    rendered = {
        "first.singular.direct": render_pronoun(rows, "first", "singular", "direct"),
        "first.plural.genitive.masculine.plural": render_pronoun(
            rows, "first", "plural", "genitive", dependency_gender="masculine", dependency_number="plural"
        ),
        "first.singular.genitive.feminine.plural": render_pronoun(
            rows, "first", "singular", "genitive", dependency_gender="feminine", dependency_number="plural"
        ),
        "second.singular.direct.intimate": render_pronoun(
            rows, "second", "singular", "direct", register="intimate"
        ),
        "second.plural.direct.informal": render_pronoun(
            rows, "second", "plural", "direct", register="informal"
        ),
        "second.plural.genitive.formal.masculine.plural": render_pronoun(
            rows,
            "second",
            "plural",
            "genitive",
            register="formal",
            dependency_gender="masculine",
            dependency_number="plural",
        ),
    }
    expected_rendered = {
        "first.singular.direct": "मैं",
        "first.plural.genitive.masculine.plural": "हमारे",
        "first.singular.genitive.feminine.plural": "मेरी",
        "second.singular.direct.intimate": "तू",
        "second.plural.direct.informal": "तुम",
        "second.plural.genitive.formal.masculine.plural": "आपके",
    }
    if rendered != expected_rendered:
        raise AssertionError(f"Unexpected Hindi pronoun renders: {rendered}")

    return {
        "bytes": len(payload),
        "locale": pack["locale"],
        "packShape": pack["packShape"],
        "rendered": rendered,
        "schema": pack["schema"],
        "sha256": digest,
        "summary": expected_summary,
        "totalBytes": binary_lower_bound["totalBytes"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    args = parser.parse_args()
    print(json.dumps(assert_hindi_pronoun_fixture(args.fixture), ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
