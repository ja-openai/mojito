#!/usr/bin/env python3
"""Generate native Xcode target-locale insertion and locale-alias fixtures."""

from __future__ import annotations

import json
import re
from pathlib import Path

from generate_apple_disabled_argument_fixtures import (
    compiled_xcatalog,
    descriptor,
    write_json,
)
from generate_apple_xcstrings_missing_source_fixtures import object_end


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "catalog-target-locale-insertion"
TARGET_LOCALE = "fr-CA"
ORIGINAL_LOCALE = "fr_CA"

TARGETS = {
    "harbor.target.missing🧭": 'Abri "paisible" 🧭',
    "North %n %@ 🧭": "Ouest  {arg1} 🧭",
    "%@ %n %@ anchorage": "{arg0}  {arg2} mouillage",
    "West %2$n %1$@ pier": "Ouest  {arg0} jetée",
    "Tide %%n %@ marker": "Marée %n {arg0} balise",
    "owned.scalar": "Stable {arg0} rive",
}

NATIVE_TARGETS = {
    "harbor.target.missing🧭": 'Abri "paisible" 🧭',
    "North %n %@ 🧭": "Ouest %n %@ 🧭",
    "%@ %n %@ anchorage": "%@ %n %@ mouillage",
    "West %2$n %1$@ pier": "Ouest %2$n %1$@ jetée",
    "Tide %%n %@ marker": "Marée %%n %@ balise",
    "owned.scalar": "Stable %@ rive",
}

SOURCES = {
    "harbor.target.missing🧭": "Calm harbor 🧭",
    "North %n %@ 🧭": "North %n %@ 🧭",
    "%@ %n %@ anchorage": "%@ %n %@ anchorage",
    "West %2$n %1$@ pier": "West %2$n %1$@ pier",
    "Tide %%n %@ marker": "Tide %%n %@ marker",
    "owned.scalar": "Steady %@ shore",
}


def unit(value: str, state: str) -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def document() -> dict[str, object]:
    entries: dict[str, object] = {
        "harbor.target.missing🧭": {
            "comment": "A neutral target missing from its active localization map",
            "extractionState": "stale",
            "localizations": {
                "en": unit(SOURCES["harbor.target.missing🧭"], "needs_review"),
                "de": unit("Ruhiger Hafen", "machine_translated"),
            },
        },
        "North %n %@ 🧭": {
            "localizations": {
                "en": unit(SOURCES["North %n %@ 🧭"], "translated"),
                ORIGINAL_LOCALE: None,
                "de": unit("Nord %@ geschützt", "future_review"),
            }
        },
        "%@ %n %@ anchorage": {
            "localizations": {
                "en": unit(SOURCES["%@ %n %@ anchorage"], "needs_review"),
                "fr": unit("%@ %@ mouillage distinct", "translated"),
            }
        },
        "West %2$n %1$@ pier": {
            "localizations": {
                "en": unit(SOURCES["West %2$n %1$@ pier"], "machine_translated"),
                ORIGINAL_LOCALE: unit("Ancien %2$n %1$@ quai", "needs_review"),
            }
        },
        "Tide %%n %@ marker": {
            "localizations": {
                "en": unit(SOURCES["Tide %%n %@ marker"], "translated"),
                ORIGINAL_LOCALE: unit("Marée %%n %@ existante", "new"),
            }
        },
        "owned.scalar": {
            "localizations": {
                "en": unit(SOURCES["owned.scalar"], "needs_review"),
                ORIGINAL_LOCALE: unit("Rive %@ existante", "future_review"),
            }
        },
        "Private target null pier": {
            "shouldTranslate": False,
            "localizations": {
                "en": unit("Private %@ source", "translated"),
                ORIGINAL_LOCALE: None,
            },
        },
        "Private target missing pier": {
            "shouldTranslate": False,
            "localizations": {"en": unit("Private %@ missing source", "translated")},
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, entry in sorted(root["strings"].items()):
        if entry.get("shouldTranslate") is False:
            continue
        source = entry["localizations"]["en"]
        message = descriptor(source["stringUnit"]["value"])
        metadata = message.setdefault("metadata", {})
        metadata["appleSourceLocalization"] = source
        metadata["sourceState"] = source["stringUnit"]["state"]
        if entry.get("extractionState"):
            metadata["extractionState"] = entry["extractionState"]
        translations = {}
        originals = {}
        identifiers = {}
        for language, localization in entry["localizations"].items():
            if language == "en" or localization is None:
                continue
            normalized = language.replace("_", "-")
            translations[normalized] = {
                "value": localization["stringUnit"]["value"],
                "state": localization["stringUnit"]["state"],
            }
            originals[normalized] = localization
            if normalized != language:
                identifiers[normalized] = language
        if translations:
            metadata["localizations"] = translations
            metadata["appleLocalizationSources"] = originals
        if identifiers:
            metadata["appleLocalizationIdentifiers"] = identifiers
        if entry.get("comment"):
            message["description"] = entry["comment"]
        messages[identifier] = message
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": messages,
    }


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    owned = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        beginning = source.index(key, cursor)
        field = source.index('"localizations"', beginning + len(key))
        opening = source.index("{", field)
        closing = object_end(source, opening)
        original = entry["localizations"].get(ORIGINAL_LOCALE)
        if ORIGINAL_LOCALE not in entry["localizations"]:
            position = closing
            while source[position - 1] in " \t\r\n":
                position -= 1
            start = end = position
        else:
            expression = (
                re.compile(r'"fr_CA"\s*:\s*(null)')
                if original is None
                else re.compile(
                    r'"value"\s*:\s*"('
                    + re.escape(
                        json.dumps(original["stringUnit"]["value"], ensure_ascii=False)[
                            1:-1
                        ]
                    )
                    + r')"'
                )
            )
            match = expression.search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing Xcode target ownership for {identifier}")
            start, end = match.span(1)
        cursor = closing + 1
        owned.append(
            {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        )
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": ORIGINAL_LOCALE,
        "slots": owned,
    }


def localized(source: str) -> str:
    result = source
    for slot in reversed(skeleton(source, "UTF-8")["slots"]):
        start = len(source.encode()[: slot["start"]].decode())
        end = len(source.encode()[: slot["end"]].decode())
        native = NATIVE_TARGETS[slot["id"]]
        if start == end or source[start:end] == "null":
            unit_value = json.dumps(
                {"stringUnit": {"state": "translated", "value": native}},
                ensure_ascii=False,
                separators=(",", ":"),
            )
            replacement = (
                ',"' + ORIGINAL_LOCALE + '":' + unit_value
                if start == end
                else unit_value
            )
        else:
            replacement = json.dumps(native, ensure_ascii=False)[1:-1]
        result = result[:start] + replacement + result[end:]
    return result


def runtime_samples() -> list[dict[str, object]]:
    values = []
    for identifier, native in NATIVE_TARGETS.items():
        message = descriptor(native)
        substitutions = {
            placeholder["name"]: (
                "Sky"
                if identifier == "%@ %n %@ anchorage" and placeholder["name"] == "arg0"
                else "Rowan"
            )
            for placeholder in message.get("placeholders", [])
        }
        arguments = {
            placeholder["position"]: substitutions[placeholder["name"]]
            for placeholder in message.get("placeholders", [])
        }
        for hidden in message.get("metadata", {}).get(
            "appleDisabledPrintfConversions", []
        ):
            arguments.setdefault(hidden["argumentPosition"], 0)
        expected = re.sub(
            r"\{(arg\d+)\}",
            lambda match: str(substitutions[match.group(1)]),
            message["defaultMessage"],
        )
        values.append(
            {
                "message": identifier,
                "arguments": [
                    arguments[index]
                    for index in range(1, max(arguments, default=0) + 1)
                ],
                "expected": expected,
            }
        )
    return values


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    target = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    target_path = APPLE / f"{STEM}.localized.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    target_path.write_text(target, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(target_path)
    )
    write_json(APPLE / f"{STEM}.translations.json", TARGETS)
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-target-locale-spelling-state-and-runtime-boundaries"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != case_id]
    manifest["cases"].append(
        {
            "id": case_id,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": "Legacy extension routing rejects Xcode catalogs and cannot add or safely update target-locale values while preserving locale spelling, source values, and review states.",
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-target-locales"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": TARGET_LOCALE,
        "xcstringsRuntimeLocale": ORIGINAL_LOCALE,
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [
            {
                "message": "owned.scalar",
                "arguments": ["Rowan"],
                "expected": "Rive Rowan existante",
            },
            {
                "message": "Tide %%n %@ marker",
                "arguments": ["Rowan"],
                "expected": "Marée %n Rowan existante",
            },
        ],
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
