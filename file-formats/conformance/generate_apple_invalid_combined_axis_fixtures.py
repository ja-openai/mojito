#!/usr/bin/env python3
"""Generate original Xcode-rejected plural/substitution combination fixtures."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
DIAGNOSTIC = "Cannot reference substitution 'lanterns' from here because it is not a plain string"


def unit(value: str) -> dict[str, object]:
    return {"stringUnit": {"state": "translated", "value": value}}


def plural(include_substitution: bool) -> dict[str, object]:
    ending = " beside %#@lanterns@" if include_substitution else ""
    return {
        "one": unit(f"%1$lld quiet inlet{ending}"),
        "other": unit(f"%1$lld quiet inlets{ending}"),
    }


def substitutions() -> dict[str, object]:
    return {
        "lanterns": {
            "argNum": 2,
            "formatSpecifier": "lld",
            "variations": {
                "plural": {
                    "one": unit("%2$lld silver lantern"),
                    "other": unit("%2$lld silver lanterns"),
                }
            },
        }
    }


def catalog(localizations: dict[str, object]) -> dict[str, object]:
    return {
        "sourceLanguage": "en",
        "strings": {
            "neutral.inlet": {
                "comment": "Independent neutral evidence for Xcode variation boundaries.",
                "localizations": localizations,
            }
        },
        "version": "1.0",
    }


def main() -> None:
    cases = {
        "top-level": catalog(
            {
                "en": {
                    "substitutions": substitutions(),
                    "variations": {"plural": plural(True)},
                }
            }
        ),
        "nested-device": catalog(
            {
                "en": {
                    "substitutions": substitutions(),
                    "variations": {
                        "device": {
                            "iphone": {"variations": {"plural": plural(True)}},
                            "mac": {"variations": {"plural": plural(True)}},
                        }
                    },
                }
            }
        ),
        "target-locale": catalog(
            {
                "en": {"variations": {"plural": plural(False)}},
                "fr": {
                    "substitutions": substitutions(),
                    "variations": {"plural": plural(True)},
                },
            }
        ),
    }

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    prefix = "apple-xcstrings-rejects-native-invalid-plural-substitution-reference-"
    manifest["cases"] = [
        entry for entry in manifest["cases"] if not entry["id"].startswith(prefix)
    ]
    for variation, source in cases.items():
        fixture = f"fixtures/apple/catalog-invalid-combined-{variation}.xcstrings"
        (ROOT / fixture).write_text(
            json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        manifest["cases"].append(
            {
                "id": prefix + variation,
                "format": "apple_xcstrings",
                "input": fixture,
                "error": "INVALID_XCSTRINGS",
                "xcstringsOracle": "reject",
                "xcstringsDiagnostic": DIAGNOSTIC,
            }
        )
    MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
