#!/usr/bin/env python3
"""Generate independent Xcode underscore/hyphen regional ownership fixtures."""

from __future__ import annotations

import json

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_target_plural_fixtures import APPLE, ROOT


STEM = "catalog-portuguese-independent-region-separators"
IDENTIFIER = "harbor.portuguese.region.separator🧭"
PROTECTED = "Private independent Portuguese separator"
VALUES = {
    "pt_BR": "Sinal sublinhado %@",
    "pt-BR": "Sinal hífen %@",
}
TRANSLATIONS = {
    "underscore": "Sinal sublinhado traduzido {arg0}",
    "hyphen": "Sinal hífen traduzido {arg0}",
}


def document() -> dict[str, object]:
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            IDENTIFIER: {
                "comment": "Underscore and hyphen regional bundles stay independent",
                "localizations": {
                    "en": {
                        "stringUnit": {
                            "state": "translated",
                            "value": "Source beacon %@",
                        }
                    },
                    "de": {
                        "stringUnit": {"state": "needs_review", "value": "Signal %@"}
                    },
                    **{
                        locale: {"stringUnit": {"state": "translated", "value": value}}
                        for locale, value in VALUES.items()
                    },
                },
            },
            PROTECTED: {
                "shouldTranslate": False,
                "localizations": {
                    "en": {
                        "stringUnit": {
                            "state": "translated",
                            "value": "Private source %@",
                        }
                    },
                    **{
                        locale: {
                            "stringUnit": {
                                "state": "translated",
                                "value": "Private " + value,
                            }
                        }
                        for locale, value in VALUES.items()
                    },
                },
            },
        },
    }


def catalog(root: dict[str, object]) -> dict[str, object]:
    localizations = root["strings"][IDENTIFIER]["localizations"]
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": {
            IDENTIFIER: {
                "defaultMessage": "Source beacon {arg0}",
                "description": root["strings"][IDENTIFIER]["comment"],
                "placeholders": [
                    {"name": "arg0", "source": "%@", "kind": "string", "position": 1}
                ],
                "metadata": {
                    "appleSourceLocalization": localizations["en"],
                    "sourceState": "translated",
                    "localizations": {
                        "de": {"state": "needs_review", "value": "Signal %@"},
                        "pt-BR": {"state": "translated", "value": VALUES["pt-BR"]},
                        "pt_BR": {"state": "translated", "value": VALUES["pt_BR"]},
                    },
                    "appleLocalizationSources": {
                        "de": localizations["de"],
                        "pt-BR": localizations["pt-BR"],
                        "pt_BR": localizations["pt_BR"],
                    },
                },
            }
        },
    }


def skeleton(source: str, encoding: str, locale: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    descriptor = source.index(json.dumps(IDENTIFIER, ensure_ascii=False))
    localizations = source.index('"localizations"', descriptor)
    language = source.index(json.dumps(locale), localizations)
    value = json.dumps(VALUES[locale], ensure_ascii=False)
    start = source.index(value, language)
    end = start + len(value) - 1
    start += 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": locale,
        "slots": [
            {
                "id": IDENTIFIER,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        ],
    }


def localized(source: str, locale: str, label: str) -> str:
    native = TRANSLATIONS[label].replace("{arg0}", "%@")
    old = json.dumps(VALUES[locale], ensure_ascii=False)
    new = json.dumps(native, ensure_ascii=False)
    descriptor = source.index(json.dumps(IDENTIFIER, ensure_ascii=False))
    start = source.index(old, descriptor)
    return source[:start] + new + source[start + len(old) :]


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    path = APPLE / f"{STEM}.xcstrings"
    path.write_text(source, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(path))

    historical_path = APPLE / "catalog-duplicate-locales.xcstrings"
    historical = json.loads(historical_path.read_text(encoding="utf-8"))
    historical_localizations = historical["strings"]["duplicate"]["localizations"]
    write_json(
        APPLE / "catalog-duplicate-locales.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_xcstrings",
            "locale": "en",
            "messages": {
                "duplicate": {
                    "defaultMessage": "Source",
                    "metadata": {
                        "appleSourceLocalization": historical_localizations["en"],
                        "sourceState": "translated",
                        "localizations": {
                            "fr-CA": {"state": "translated", "value": "Second"},
                            "fr_CA": {"state": "translated", "value": "First"},
                        },
                        "appleLocalizationSources": {
                            "fr-CA": historical_localizations["fr-CA"],
                            "fr_CA": historical_localizations["fr_CA"],
                        },
                    },
                }
            },
        },
    )
    write_json(
        APPLE / "catalog-duplicate-locales.compiled.json",
        compiled_xcatalog(historical_path),
    )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-independent-portuguese-underscore-hyphen-regions"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != identifier]
    manifest["cases"].append(
        {
            "id": identifier,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": (
                    "Legacy extraction cannot preserve compiler-distinct Portuguese "
                    "underscore/hyphen regional identities or their independent source slots."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-independent-regional-separator-"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    for label, locale in (("underscore", "pt_BR"), ("hyphen", "pt-BR")):
        translated = localized(source, locale, label)
        translated_path = APPLE / f"{STEM}.{label}.localized.xcstrings"
        translated_path.write_text(translated, encoding="utf-8")
        write_json(
            APPLE / f"{STEM}.{label}.localized.compiled.json",
            compiled_xcatalog(translated_path),
        )
        write_json(
            APPLE / f"{STEM}.{label}.translations.json",
            {IDENTIFIER: TRANSLATIONS[label]},
        )
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            write_json(
                APPLE / f"{STEM}.{label}{suffix}.expected.skeleton.json",
                skeleton(source, encoding, locale),
            )
            manifest["sourceSkeletons"].append(
                {
                    "id": prefix + label + ("-utf8" if not suffix else "-utf16"),
                    **({"encoding": encoding} if suffix else {}),
                    "expected": (
                        f"fixtures/apple/{STEM}.{label}{suffix}.expected.skeleton.json"
                    ),
                    "format": "apple_xcstrings",
                    "xcstringsTargetLocale": locale,
                    "xcstringsTargetInsertion": True,
                    "xcstringsRegionSeparator": label,
                    "xcstringsRuntimeLocale": locale,
                    "xcstringsFormattingLocale": "pt-BR",
                    "input": f"fixtures/apple/{STEM}.xcstrings",
                    "translations": f"fixtures/apple/{STEM}.{label}.translations.json",
                    "localized": (f"fixtures/apple/{STEM}.{label}.localized.xcstrings"),
                    "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{STEM}.{label}.localized.compiled.json"
                    ),
                    "xcstringsOriginalRuntimeSamples": [
                        {
                            "message": IDENTIFIER,
                            "arguments": ["Rowan"],
                            "expected": VALUES[locale].replace("%@", "Rowan"),
                        }
                    ],
                    "xcstringsLocalizedRuntimeSamples": [
                        {
                            "message": IDENTIFIER,
                            "arguments": ["Rowan"],
                            "expected": TRANSLATIONS[label].replace("{arg0}", "Rowan"),
                        }
                    ],
                }
            )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
