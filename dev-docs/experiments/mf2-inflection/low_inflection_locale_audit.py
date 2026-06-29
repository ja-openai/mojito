#!/usr/bin/env python3
"""Audit low-inflection locale groups for profile-only MF2 support."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path

from fr_dictionary_report import source_metadata
from fr_noun_pack_report import (
    SOURCE_LICENSE,
    UNICODE_DICTIONARY_RESOURCE_PREFIX,
    pack_source_metadata,
)


SCHEMA = "mojito-mf2-inflection/low-inflection-locale-audit/v0"
GENERATOR = "dev-docs/experiments/mf2-inflection/low_inflection_locale_audit.py"
DEFAULT_LOCALES = ("en", "id", "ja", "ko", "ms", "th", "vi", "zh", "yue")
DEFAULT_UNICODE_ROOT = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection"
)
INFLECTION_RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/inflection"

CASE_VALUES = {"accusative", "dative", "genitive", "locative", "nominative", "oblique", "vocative"}
GENDER_VALUES = {"feminine", "gender", "human", "masculine", "neuter", "nonhuman"}
NUMBER_VALUES = {"dual", "plural", "singular"}
PERSON_VALUES = {"first", "second", "third"}
REGISTER_VALUES = {"casual", "formal", "informal"}


def file_metadata(path: Path, resource_prefix: str) -> dict:
    if not path.exists():
        return {
            "path": f"{resource_prefix}/{path.name}",
            "exists": False,
            "gitLfsPointer": False,
        }
    metadata = pack_source_metadata(path)
    metadata["exists"] = True
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    metadata["path"] = f"{resource_prefix}/{path.name}"
    return metadata


def data_state(metadata: dict) -> str:
    if not metadata["exists"]:
        return "missing"
    if metadata["gitLfsPointer"]:
        return "git-lfs-pointer"
    if metadata.get("byteSize", 0) == 0:
        return "empty-placeholder"
    return "materialized"


def parse_pronouns(path: Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    with path.open(encoding="utf-8", newline="") as pronoun_file:
        for line_number, row in enumerate(csv.reader(pronoun_file), start=1):
            if not row:
                continue
            value = row[0]
            features = tuple(token for token in row[1:] if token)
            rows.append({"line": line_number, "value": value, "features": features})
    return rows


def pronoun_stats(rows: list[dict]) -> dict:
    feature_counts: Counter[str] = Counter()
    case_counts: Counter[str] = Counter()
    gender_counts: Counter[str] = Counter()
    number_counts: Counter[str] = Counter()
    person_counts: Counter[str] = Counter()
    register_counts: Counter[str] = Counter()
    for row in rows:
        features = set(row["features"])
        feature_counts.update(features)
        case_counts.update(features & CASE_VALUES)
        gender_counts.update(features & GENDER_VALUES)
        number_counts.update(features & NUMBER_VALUES)
        person_counts.update(features & PERSON_VALUES)
        register_counts.update(features & REGISTER_VALUES)

    return {
        "rows": len(rows),
        "uniqueValues": len({row["value"] for row in rows}),
        "features": dict(sorted(feature_counts.items())),
        "cases": dict(sorted(case_counts.items())),
        "genders": dict(sorted(gender_counts.items())),
        "numbers": dict(sorted(number_counts.items())),
        "persons": dict(sorted(person_counts.items())),
        "registers": dict(sorted(register_counts.items())),
        "samples": [
            {
                "line": row["line"],
                "value": row["value"],
                "features": list(row["features"]),
            }
            for row in rows[:8]
        ],
    }


def recommendation(locale: str, dictionary_state: str, inflectional_state: str, pronoun_rows: int) -> dict:
    if dictionary_state == "git-lfs-pointer" or inflectional_state == "git-lfs-pointer":
        return {
            "mode": "data-materialization-required",
            "runtimeTermInflection": False,
            "profileOnly": True,
            "reason": "Dictionary or inflectional data is a Git LFS pointer; materialize it before deciding whether this locale needs a runtime term-inflection pack.",
            "nextAction": "materialize-lfs-data-if-product-use-case-appears",
        }
    if dictionary_state in {"empty-placeholder", "missing"} and inflectional_state == "missing":
        return {
            "mode": "profile-only-noop",
            "runtimeTermInflection": False,
            "profileOnly": True,
            "reason": "The Unicode checkout has no noun dictionary or inflectional XML rows for this locale group; term rendering should pass through known glossary text and keep only profile/pronoun metadata.",
            "nextAction": "do-not-build-term-inflection-pack-until-source-data-or-product-need-exists",
        }
    if dictionary_state == "materialized" and inflectional_state == "missing":
        return {
            "mode": "dictionary-inventory-only",
            "runtimeTermInflection": False,
            "profileOnly": True,
            "reason": "Dictionary data exists without inflectional XML; treat it as inventory/profile metadata until a concrete transform is justified.",
            "nextAction": "audit-dictionary-before-runtime-rendering",
        }
    return {
        "mode": "manual-review",
        "runtimeTermInflection": False,
        "profileOnly": pronoun_rows > 0,
        "reason": f"Unexpected low-inflection data state dictionary={dictionary_state}, inflectional={inflectional_state}.",
        "nextAction": "review-locale-data-state",
    }


def locale_report(locale: str, unicode_root: Path) -> dict:
    dictionary = unicode_root / "dictionary" / f"dictionary_{locale}.lst"
    inflectional = unicode_root / "dictionary" / f"inflectional_{locale}.xml"
    pronouns = unicode_root / "inflection" / f"pronoun_{locale}.csv"

    dictionary_metadata = file_metadata(dictionary, UNICODE_DICTIONARY_RESOURCE_PREFIX)
    inflectional_metadata = file_metadata(inflectional, UNICODE_DICTIONARY_RESOURCE_PREFIX)
    pronoun_metadata = file_metadata(pronouns, INFLECTION_RESOURCE_PREFIX)
    pronoun_rows = parse_pronouns(pronouns)
    dictionary_state = data_state(dictionary_metadata)
    inflectional_state = data_state(inflectional_metadata)

    return {
        "locale": locale,
        "dataState": {
            "dictionary": dictionary_state,
            "inflectional": inflectional_state,
            "pronouns": data_state(pronoun_metadata),
        },
        "sources": {
            "dictionary": dictionary_metadata,
            "inflectional": inflectional_metadata,
            "pronouns": pronoun_metadata,
        },
        "pronouns": pronoun_stats(pronoun_rows),
        "recommendation": recommendation(
            locale, dictionary_state, inflectional_state, len(pronoun_rows)
        ),
    }


def build_report(locales: tuple[str, ...], unicode_root: Path) -> dict:
    reports = [locale_report(locale, unicode_root) for locale in locales]
    mode_counts = Counter(report["recommendation"]["mode"] for report in reports)
    return {
        "schema": SCHEMA,
        "description": "Audit of low-inflection Unicode locale groups for Mojito MF2 profile-only or no-op support.",
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": GENERATOR,
            "unicodeRoot": str(unicode_root),
        },
        "summary": {
            "localeCount": len(reports),
            "locales": list(locales),
            "modeCounts": dict(sorted(mode_counts.items())),
            "dataMaterializationRequiredLocales": [
                report["locale"]
                for report in reports
                if report["recommendation"]["mode"] == "data-materialization-required"
            ],
            "profileOnlyNoopLocales": [
                report["locale"]
                for report in reports
                if report["recommendation"]["mode"] == "profile-only-noop"
            ],
            "pronounInventoryLocales": [
                report["locale"] for report in reports if report["pronouns"]["rows"] > 0
            ],
        },
        "locales": reports,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--locale", action="append", choices=DEFAULT_LOCALES)
    parser.add_argument("--unicode-root", type=Path, default=DEFAULT_UNICODE_ROOT)
    parser.add_argument("--out", type=Path, help="Write JSON report to this path.")
    args = parser.parse_args()

    locales = tuple(args.locale) if args.locale else DEFAULT_LOCALES
    payload = json.dumps(build_report(locales, args.unicode_root), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
