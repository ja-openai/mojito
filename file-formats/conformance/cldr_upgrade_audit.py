#!/usr/bin/env python3
"""Audit one explicitly pinned Unicode CLDR cardinal-rule upgrade candidate."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PINNED = ROOT / "cldr-cardinal-categories.v1.json"
ALLOWED_CATEGORY = {"zero", "one", "two", "few", "many", "other"}
SUPPORTED_LOCALE = re.compile(r"[a-z]{2,3}(?:-[A-Z]{2})?")


def audit(candidate: Path, *, expected_sha256: str | None = None) -> dict[str, object]:
    payload = candidate.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256:
        raise ValueError(
            f"Candidate SHA-256 mismatch: expected {expected_sha256}, got {digest}"
        )

    upstream = json.loads(payload)
    supplemental = upstream.get("supplemental", {})
    rules = supplemental.get("plurals-type-cardinal")
    version = supplemental.get("version", {})
    if not isinstance(rules, dict) or not isinstance(version, dict):
        raise ValueError("Candidate lacks versioned Unicode cardinal plural rules")
    cldr_version = version.get("_cldrVersion")
    unicode_version = version.get("_unicodeVersion")
    if not isinstance(cldr_version, str) or not isinstance(unicode_version, str):
        raise ValueError("Candidate lacks its original CLDR/Unicode version provenance")

    pinned = json.loads(PINNED.read_text(encoding="utf-8"))
    current = pinned["cardinalCategories"]
    categories: dict[str, list[str]] = {}
    invalid_locales: list[str] = []
    invalid_categories: dict[str, list[str]] = {}
    missing_other: list[str] = []
    for locale, definitions in rules.items():
        if not isinstance(locale, str) or not isinstance(definitions, dict):
            raise ValueError("Candidate contains malformed cardinal rule records")
        names = sorted(
            name.removeprefix("pluralRule-count-")
            for name in definitions
            if name.startswith("pluralRule-count-")
        )
        if not SUPPORTED_LOCALE.fullmatch(locale):
            invalid_locales.append(locale)
        unexpected = sorted(set(names) - ALLOWED_CATEGORY)
        if unexpected:
            invalid_categories[locale] = unexpected
        if "other" not in names:
            missing_other.append(locale)
        categories[locale] = names

    added = {
        locale: categories[locale] for locale in sorted(set(categories) - set(current))
    }
    removed = {
        locale: current[locale] for locale in sorted(set(current) - set(categories))
    }
    changed = {
        locale: {"before": current[locale], "after": categories[locale]}
        for locale in sorted(set(categories) & set(current))
        if current[locale] != categories[locale]
    }
    blockers = []
    if invalid_locales:
        blockers.append("unsupported_locale_shape")
    if invalid_categories:
        blockers.append("unsupported_plural_category")
    if missing_other:
        blockers.append("missing_other_category")
    if added:
        blockers.append("new_locales_require_platform_oracles")
    if removed:
        blockers.append("removed_locales_require_migration_review")
    if changed:
        blockers.append("changed_categories_require_translation_review")
    return {
        "schemaVersion": 1,
        "pinned": {
            "cldrVersion": pinned["cldrVersion"],
            "unicodeVersion": pinned["unicodeVersion"],
            "locales": len(current),
        },
        "candidate": {
            "cldrVersion": cldr_version,
            "unicodeVersion": unicode_version,
            "locales": len(categories),
            "sha256": digest,
        },
        "addedLocales": added,
        "removedLocales": removed,
        "changedCategories": changed,
        "unsupportedLocaleShapes": sorted(invalid_locales),
        "unsupportedCategories": invalid_categories,
        "missingOtherCategories": sorted(missing_other),
        "blockers": blockers,
        "safeToApply": not blockers,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path, help="original stable plurals.json")
    parser.add_argument("--sha256", help="expected original upstream SHA-256")
    parser.add_argument(
        "--require-safe", action="store_true", help="reject pending blockers"
    )
    args = parser.parse_args()
    try:
        result = audit(args.candidate, expected_sha256=args.sha256)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"CLDR upgrade audit failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if args.require_safe and not result["safeToApply"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
