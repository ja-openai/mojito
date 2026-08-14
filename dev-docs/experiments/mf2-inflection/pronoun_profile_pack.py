#!/usr/bin/env python3
"""Derive a profile-only pronoun metadata pack from the low-inflection audit."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SCHEMA = "mojito-mf2-inflection/pronoun-profile-pack/v0"
GENERATOR = "dev-docs/experiments/mf2-inflection/pronoun_profile_pack.py"
DEFAULT_AUDIT = ROOT / "low_inflection_locale_audit_fixture.json"


def locale_entry(report: dict) -> dict:
    recommendation = report["recommendation"]
    return {
        "locale": report["locale"],
        "mode": recommendation["mode"],
        "profileOnly": recommendation["profileOnly"],
        "runtimeTermInflection": recommendation["runtimeTermInflection"],
        "dictionaryState": report["dataState"]["dictionary"],
        "inflectionalState": report["dataState"]["inflectional"],
        "pronounSource": report["sources"]["pronouns"],
        "pronouns": report["pronouns"],
    }


def build_pack(audit: dict) -> dict:
    locales = [locale_entry(report) for report in audit["locales"]]
    profile_only_locales = [locale["locale"] for locale in locales if locale["profileOnly"]]
    data_materialization_required_locales = [
        locale["locale"] for locale in locales if locale["mode"] == "data-materialization-required"
    ]
    profile_only_noop_locales = [
        locale["locale"] for locale in locales if locale["mode"] == "profile-only-noop"
    ]
    pronoun_inventory_locales = [
        locale["locale"] for locale in locales if locale["pronouns"]["rows"] > 0
    ]

    return {
        "schema": SCHEMA,
        "description": (
            "Derived profile-only pronoun metadata pack for low-inflection locales; "
            "this is not a runtime term-inflection pack."
        ),
        "provenance": {
            "license": audit["provenance"]["license"],
            "generator": GENERATOR,
            "sourceAudit": "dev-docs/experiments/mf2-inflection/low_inflection_locale_audit_fixture.json",
            "sourceAuditSchema": audit["schema"],
        },
        "summary": {
            "localeCount": len(locales),
            "profileOnlyLocales": profile_only_locales,
            "dataMaterializationRequiredLocales": data_materialization_required_locales,
            "profileOnlyNoopLocales": profile_only_noop_locales,
            "pronounInventoryLocales": pronoun_inventory_locales,
            "runtimeTermInflection": False,
            "totalPronounRows": sum(locale["pronouns"]["rows"] for locale in locales),
            "totalUniquePronounValues": sum(
                locale["pronouns"]["uniqueValues"] for locale in locales
            ),
        },
        "locales": locales,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--out", type=Path, help="Write JSON pack to this path.")
    args = parser.parse_args()

    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    payload = json.dumps(build_pack(audit), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
