#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_LOCALE_FIELDS = (
    "requestedLocale",
    "sourceLocale",
    "rawEntries",
    "normalizedEntries",
    "candidateEntries",
    "supportedEntries",
    "duplicateCandidateEntries",
    "filteredEntries",
    "filteredByReason",
    "supportedSkeletonFields",
    "unsupportedSkeletonFields",
    "unsupportedPatternFields",
    "unsupportedExamples",
)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    require_no_unsupported_fields = False
    if "--require-no-unsupported-fields" in args:
        require_no_unsupported_fields = True
        args = [arg for arg in args if arg != "--require-no-unsupported-fields"]
    if len(args) != 2:
        raise SystemExit(
            "Usage: validate_datetime_skeleton_coverage.py "
            "[--require-no-unsupported-fields] "
            "path/to/date_time_data.json path/to/skeleton_coverage.json"
        )
    data_path = Path(args[0])
    coverage_path = Path(args[1])
    data = json.loads(data_path.read_text(encoding="utf-8"))
    coverage = json.loads(coverage_path.read_text(encoding="utf-8"))
    validate(data, coverage, require_no_unsupported_fields=require_no_unsupported_fields)
    print(
        "Validated experimental CLDR date/time skeleton coverage "
        f"locales={len(coverage['locales'])}"
    )
    return 0


def validate(
    data: dict[str, Any],
    coverage: dict[str, Any],
    *,
    require_no_unsupported_fields: bool = False,
) -> None:
    metadata = require_dict(coverage, "metadata")
    require_true(metadata, "experimental")
    require_true(metadata, "dropWithoutMigration")
    if metadata.get("calendar") != "gregorian":
        raise ValueError("coverage metadata.calendar must be gregorian")
    for field in ("supportedSkeletonFields", "supportedPatternFields"):
        value = metadata.get(field)
        if not isinstance(value, str) or not value:
            raise ValueError(f"coverage metadata.{field} must be a non-empty string")

    data_locales = require_dict(data, "locales")
    coverage_locales = require_dict(coverage, "locales")
    if set(data_locales) != set(coverage_locales):
        missing = sorted(set(data_locales) - set(coverage_locales))
        extra = sorted(set(coverage_locales) - set(data_locales))
        raise ValueError(f"coverage locale mismatch missing={missing} extra={extra}")

    for locale, locale_data in data_locales.items():
        validate_locale(
            locale,
            locale_data,
            coverage_locales[locale],
            require_no_unsupported_fields=require_no_unsupported_fields,
        )


def validate_locale(
    locale: str,
    locale_data: Any,
    coverage: Any,
    *,
    require_no_unsupported_fields: bool,
) -> None:
    if not isinstance(locale_data, dict):
        raise ValueError(f"{locale} data must be an object")
    if not isinstance(coverage, dict):
        raise ValueError(f"{locale} coverage must be an object")
    for field in REQUIRED_LOCALE_FIELDS:
        if field not in coverage:
            raise ValueError(f"{locale} coverage missing {field}")
    if coverage["requestedLocale"] != locale_data.get("requestedLocale"):
        raise ValueError(f"{locale} coverage requestedLocale mismatch")
    if coverage["sourceLocale"] != locale_data.get("sourceLocale"):
        raise ValueError(f"{locale} coverage sourceLocale mismatch")

    available_formats = require_dict(locale_data, "availableFormats")
    raw_entries = require_int(coverage, "rawEntries")
    normalized_entries = require_int(coverage, "normalizedEntries")
    candidate_entries = require_int(coverage, "candidateEntries")
    supported_entries = require_int(coverage, "supportedEntries")
    duplicate_candidate_entries = require_int(coverage, "duplicateCandidateEntries")
    filtered_entries = require_int(coverage, "filteredEntries")
    filtered_by_reason = require_int_map(coverage, "filteredByReason")
    require_int_map(coverage, "supportedSkeletonFields")
    unsupported_skeleton_fields = require_int_map(coverage, "unsupportedSkeletonFields")
    unsupported_pattern_fields = require_int_map(coverage, "unsupportedPatternFields")
    unsupported_examples = coverage["unsupportedExamples"]

    if supported_entries != len(available_formats):
        raise ValueError(
            f"{locale} coverage supportedEntries={supported_entries} "
            f"does not match availableFormats={len(available_formats)}"
        )
    if raw_entries != candidate_entries + filtered_entries:
        raise ValueError(f"{locale} coverage rawEntries does not balance")
    if filtered_entries != sum(filtered_by_reason.values()):
        raise ValueError(f"{locale} coverage filteredEntries does not match reasons")
    if normalized_entries > raw_entries:
        raise ValueError(f"{locale} coverage normalizedEntries exceeds rawEntries")
    if candidate_entries < supported_entries:
        raise ValueError(f"{locale} coverage candidateEntries is below supportedEntries")
    if duplicate_candidate_entries != candidate_entries - supported_entries:
        raise ValueError(f"{locale} coverage duplicateCandidateEntries mismatch")
    if bool(unsupported_skeleton_fields or unsupported_pattern_fields) != (
        filtered_by_reason.get("unsupportedFields", 0) > 0
    ):
        raise ValueError(f"{locale} coverage unsupported field accounting mismatch")
    if not isinstance(unsupported_examples, list):
        raise ValueError(f"{locale} coverage unsupportedExamples must be a list")
    if filtered_by_reason.get("unsupportedFields", 0) > 0 and not unsupported_examples:
        raise ValueError(f"{locale} coverage must include unsupportedExamples")
    if require_no_unsupported_fields:
        unsupported_count = filtered_by_reason.get("unsupportedFields", 0)
        if unsupported_count > 0 or unsupported_skeleton_fields or unsupported_pattern_fields:
            raise ValueError(
                f"{locale} coverage has unsupported skeleton fields "
                f"count={unsupported_count} "
                f"skeleton={sorted(unsupported_skeleton_fields)} "
                f"pattern={sorted(unsupported_pattern_fields)}"
            )
    for example in unsupported_examples:
        validate_unsupported_example(locale, example)


def validate_unsupported_example(locale: str, value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{locale} unsupported example must be an object")
    for field in (
        "skeleton",
        "canonicalSkeleton",
        "unsupportedSkeletonFields",
        "unsupportedPatternFields",
    ):
        if field not in value:
            raise ValueError(f"{locale} unsupported example missing {field}")
    if not isinstance(value["skeleton"], str) or not value["skeleton"]:
        raise ValueError(f"{locale} unsupported example has invalid skeleton")
    if not isinstance(value["canonicalSkeleton"], str) or not value["canonicalSkeleton"]:
        raise ValueError(f"{locale} unsupported example has invalid canonicalSkeleton")
    for field in ("unsupportedSkeletonFields", "unsupportedPatternFields"):
        fields = value[field]
        if not isinstance(fields, list) or not all(isinstance(item, str) for item in fields):
            raise ValueError(f"{locale} unsupported example has invalid {field}")
    if not value["unsupportedSkeletonFields"] and not value["unsupportedPatternFields"]:
        raise ValueError(f"{locale} unsupported example has no unsupported fields")


def require_dict(data: dict[str, Any], key: str) -> dict[str, Any]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"{key} must be an object")
    return value


def require_int(data: dict[str, Any], key: str) -> int:
    value = data.get(key)
    if not isinstance(value, int) or value < 0:
        raise ValueError(f"{key} must be a non-negative integer")
    return value


def require_int_map(data: dict[str, Any], key: str) -> dict[str, int]:
    value = require_dict(data, key)
    for item_key, item_value in value.items():
        if not isinstance(item_key, str) or not item_key:
            raise ValueError(f"{key} has invalid key {item_key!r}")
        if not isinstance(item_value, int) or item_value <= 0:
            raise ValueError(f"{key}.{item_key} must be a positive integer")
    return value


def require_true(data: dict[str, Any], key: str) -> None:
    if data.get(key) is not True:
        raise ValueError(f"{key} must be true")


if __name__ == "__main__":
    raise SystemExit(main())
