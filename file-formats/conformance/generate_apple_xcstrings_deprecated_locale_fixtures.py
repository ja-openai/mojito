#!/usr/bin/env python3
"""Generate native-verified deprecated Hebrew locale ownership and plural fixtures."""

from __future__ import annotations

import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_missing_target_plural_fixtures import (
    APPLE,
    GERMAN_STATES,
    GERMAN_VALUES,
    INSERT_SOURCE,
    ROOT,
    SOURCE_STATES,
    plural,
)


STEM = "catalog-first-hebrew-deprecated-locales"
LEGACY_ID = "harbor.first.hebrew.deprecated🧭"
REGIONAL_ID = "harbor.first.hebrew.deprecated.region🧭"
PROTECTED_ID = "Private deprecated Hebrew harbor"
LOCALES = {LEGACY_ID: "iw", REGIONAL_ID: "iw-IL"}
CATEGORIES = ("one", "other", "two")
LABELS = {LEGACY_ID: "עברית", REGIONAL_ID: "ישראלית"}


def document() -> dict[str, object]:
    strings = {
        identifier: {
            "comment": "Deprecated Hebrew locale spelling remains source-owned",
            "localizations": {
                "en": plural(INSERT_SOURCE, SOURCE_STATES),
                "de": plural(GERMAN_VALUES, GERMAN_STATES),
                locale: None,
            },
        }
        for identifier, locale in LOCALES.items()
    }
    strings[PROTECTED_ID] = {
        "shouldTranslate": False,
        "localizations": {
            "en": plural(INSERT_SOURCE, SOURCE_STATES),
            "iw": None,
            "iw-IL": None,
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": strings}


def catalog(root: dict[str, object]) -> dict[str, object]:
    conversions = [{"position": 8, "source": "%3$n", "argumentPosition": 3}]
    messages = {}
    for identifier in LOCALES:
        entry = root["strings"][identifier]
        messages[identifier] = {
            "defaultMessage": (
                "{count, plural, one {{count}  beacon {arg1}} "
                "other {{count}  beacons {arg1}}}"
            ),
            "description": entry["comment"],
            "variants": {
                "one": "{count}  beacon {arg1}",
                "other": "{count}  beacons {arg1}",
            },
            "placeholders": [
                {
                    "name": "count",
                    "source": "%1$lld",
                    "kind": "integer",
                    "position": 1,
                },
                {"name": "arg1", "source": "%2$@", "kind": "string", "position": 2},
            ],
            "metadata": {
                "appleSourceLocalization": entry["localizations"]["en"],
                "sourcePluralStates": SOURCE_STATES,
                "applePluralDisabledPrintfConversions": {
                    "count": {"one": conversions, "other": conversions}
                },
                "localizations": {
                    "de": {"variants": GERMAN_VALUES, "variantStates": GERMAN_STATES}
                },
                "appleLocalizationSources": {"de": entry["localizations"]["de"]},
            },
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(sorted(messages.items())),
    }


def translations() -> dict[str, str]:
    return {
        identifier: "{count, plural, "
        + " ".join(
            f"{category} {{{{arg1}} {{count}}  {LABELS[identifier]} {category}}}"
            for category in CATEGORIES
        )
        + "}"
        for identifier in LOCALES
    }


def inserted(identifier: str) -> dict[str, object]:
    return plural(
        {
            category: f"%2$@ %1$lld %3$n {LABELS[identifier]} {category}"
            for category in CATEGORIES
        },
        {category: "translated" for category in CATEGORIES},
    )


def skeleton(source: str, encoding: str, locale: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        start = source.index(key, cursor)
        localizations = source.index('"localizations"', start + len(key))
        opening = source.index("{", localizations)
        closing = object_end(source, opening)
        if locale in entry["localizations"]:
            match = re.compile(re.escape(json.dumps(locale)) + r"\s*:\s*(null)").search(
                source, opening, closing
            )
            if match is None:
                raise RuntimeError(f"Missing deprecated locale null: {identifier}")
            beginning, end = match.span(1)
        else:
            beginning = end = closing
            while source[beginning - 1] in " \t\r\n":
                beginning -= 1
            end = beginning
        slots.append(
            {
                "id": identifier,
                "start": bom + len(source[:beginning].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        )
        cursor = closing + 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": locale,
        "slots": slots,
    }


def localized(source: str, locale: str) -> str:
    original = source.encode("utf-8")
    result = bytearray()
    previous = 0
    for slot in skeleton(source, "UTF-8", locale)["slots"]:
        result.extend(original[previous : slot["start"]])
        value = json.dumps(
            inserted(slot["id"]), ensure_ascii=False, separators=(",", ":")
        )
        if slot["start"] == slot["end"]:
            value = "," + json.dumps(locale) + ":" + value
        result.extend(value.encode("utf-8"))
        previous = slot["end"]
    result.extend(original[previous:])
    return result.decode("utf-8")


def runtime_samples() -> list[dict[str, object]]:
    return [
        {
            "message": identifier,
            "arguments": [count, "Rowan", 0],
            "expected": f"\u2068Rowan\u2069 {count}  {LABELS[identifier]} {category}",
        }
        for identifier in LOCALES
        for count, category in ((0, "other"), (1, "one"), (2, "two"), (3, "other"))
    ]


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    path = APPLE / f"{STEM}.xcstrings"
    path.write_text(source, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(path))
    write_json(APPLE / f"{STEM}.translations.json", translations())

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-first-hebrew-deprecated-regional-locales"
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
                    "Legacy routing cannot resolve deprecated Hebrew language ownership, "
                    "its modern compiler bundle, region-specific null slots, or native plurals."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-first-deprecated-hebrew-"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    for label, locale, requested, runtime in (
        ("language", "iw", "he", "he"),
        ("region", "iw-IL", "he-IL", "he-IL"),
    ):
        translated = localized(source, locale)
        localized_path = APPLE / f"{STEM}.{label}.localized.xcstrings"
        localized_path.write_text(translated, encoding="utf-8")
        write_json(
            APPLE / f"{STEM}.{label}.localized.compiled.json",
            compiled_xcatalog(localized_path),
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
                    "xcstringsTargetLocale": requested,
                    "xcstringsTargetPlural": True,
                    "xcstringsTargetPluralInsertion": True,
                    "xcstringsFirstLocaleCategories": True,
                    "xcstringsDeprecatedLocale": label,
                    "xcstringsRuntimeLocale": runtime,
                    "xcstringsFormattingLocale": runtime,
                    "input": f"fixtures/apple/{STEM}.xcstrings",
                    "translations": f"fixtures/apple/{STEM}.translations.json",
                    "localized": f"fixtures/apple/{STEM}.{label}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{STEM}.{label}.localized.compiled.json"
                    ),
                    "xcstringsOriginalRuntimeSamples": [],
                    "xcstringsLocalizedRuntimeSamples": runtime_samples(),
                }
            )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
