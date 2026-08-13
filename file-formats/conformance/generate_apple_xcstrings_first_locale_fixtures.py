#!/usr/bin/env python3
"""Generate native-verified first-locale Russian CLDR plural insertion."""

from __future__ import annotations

import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_missing_target_plural_fixtures import (
    APPLE,
    ENDINGS,
    GERMAN_STATES,
    GERMAN_VALUES,
    INSERT_SOURCE,
    ROOT,
    SOURCE_STATES,
    plural,
)


STEM = "catalog-first-russian-plural-locale"
MISSING_ID = "harbor.first.russian.missing🧭"
NULL_ID = "harbor.first.russian.null🧭"
PROTECTED_MISSING = "Private first missing Russian plural"
PROTECTED_NULL = "Private first null Russian plural"
SUFFIXES = {MISSING_ID: "в проливе", NULL_ID: "у маяка"}
CATEGORIES = ("few", "many", "one", "other")
TRANSLATIONS = {
    identifier: "{count, plural, "
    + " ".join(
        category + " {{arg1} {count}  " + ENDINGS[category] + " " + suffix + "}"
        for category in CATEGORIES
    )
    + "}"
    for identifier, suffix in SUFFIXES.items()
}


def document() -> dict[str, object]:
    entries = {}
    for identifier in SUFFIXES:
        localizations = {
            "en": plural(INSERT_SOURCE, SOURCE_STATES),
            "de": plural(GERMAN_VALUES, GERMAN_STATES),
        }
        if identifier == NULL_ID:
            localizations["ru"] = None
        entries[identifier] = {
            "comment": "Pinned CLDR owns the first Russian plural localization",
            "localizations": localizations,
        }
    entries[PROTECTED_MISSING] = {
        "shouldTranslate": False,
        "localizations": {"en": plural(INSERT_SOURCE, SOURCE_STATES)},
    }
    entries[PROTECTED_NULL] = {
        "shouldTranslate": False,
        "localizations": {"en": plural(INSERT_SOURCE, SOURCE_STATES), "ru": None},
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    conversions = [{"position": 8, "source": "%3$n", "argumentPosition": 3}]
    messages = {}
    for identifier in SUFFIXES:
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


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        beginning = source.index(key, cursor)
        field = source.index('"localizations"', beginning + len(key))
        opening = source.index("{", field)
        closing = object_end(source, opening)
        if "ru" in entry["localizations"]:
            match = re.compile(r'"ru"\s*:\s*(null)').search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing first-locale null: {identifier}")
            start, end = match.span(1)
        else:
            start = end = closing
            while source[start - 1] in " \t\r\n":
                start -= 1
            end = start
        slots.append(
            {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        )
        cursor = closing + 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": "ru",
        "slots": slots,
    }


def inserted(identifier: str) -> dict[str, object]:
    suffix = SUFFIXES[identifier]
    return plural(
        {
            category: "%2$@ %1$lld %3$n " + ENDINGS[category] + " " + suffix
            for category in CATEGORIES
        },
        {category: "translated" for category in CATEGORIES},
    )


def localized(source: str) -> str:
    result = source
    for value in reversed(skeleton(source, "UTF-8")["slots"]):
        start = len(source.encode()[: value["start"]].decode())
        end = len(source.encode()[: value["end"]].decode())
        replacement = json.dumps(
            inserted(value["id"]), ensure_ascii=False, separators=(",", ":")
        )
        if start == end:
            replacement = ',"ru":' + replacement
        result = result[:start] + replacement + result[end:]
    return result


def runtime_samples() -> list[dict[str, object]]:
    return [
        {
            "message": identifier,
            "arguments": [count, "Rowan", 0],
            "expected": f"Rowan {count}  {ENDINGS[category]} {suffix}",
        }
        for identifier, suffix in SUFFIXES.items()
        for count, category in (
            (0, "many"),
            (1, "one"),
            (2, "few"),
            (5, "many"),
            (21, "one"),
            (22, "few"),
        )
    ]


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    translated_path = APPLE / f"{STEM}.localized.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    translated_path.write_text(translated, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.translations.json", TRANSLATIONS)
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(translated_path)
    )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-cldr-first-russian-target-plural-locale"
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
                    "Legacy routing cannot discover first-locale plural categories from "
                    "ICU rules, materialize Russian branches, preserve protected "
                    "entries, native hidden arguments, and exact original source bytes."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-first-russian-cldr-locale"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetPlural": True,
        "xcstringsTargetPluralInsertion": True,
        "xcstringsFirstLocaleCategories": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [],
        "xcstringsLocalizedRuntimeSamples": runtime_samples(),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": prefix + "-utf8",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                **common,
            },
            {
                "id": prefix + "-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                **common,
            },
        ]
    )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
