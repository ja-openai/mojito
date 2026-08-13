#!/usr/bin/env python3
"""Generate neutral, native-verified Xcode target-language plural fixtures."""

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
STEM = "catalog-target-russian-plurals"
PLURAL_ID = "harbor.target.russian🧭"
SCALAR_ID = "harbor.target.russian.scalar"
PROTECTED_ID = "Private target Russian plural"
SOURCE_VALUES = {
    "one": "%1$lld beacon %2$@",
    "other": "%1$lld beacons %2$@",
}
TARGET_VALUES = {
    "one": "%2$@ %3$n %1$lld маяк",
    "few": "%2$@ %3$n %1$lld маяка",
    "many": "%2$@ %3$n %1$lld маяков",
    "other": "%2$@ %3$n %1$lld маяка",
}
TARGET_STATES = {
    "one": "needs_review",
    "few": "new",
    "many": "future_review",
    "other": "translated",
}
TRANSLATIONS = {
    f"{PLURAL_ID}#one": "{arg1}  {count} маяк у причала",
    f"{PLURAL_ID}#few": "{arg1}  {count} маяка у причала",
    f"{PLURAL_ID}#many": "{arg1}  {count} маяков у причала",
    f"{PLURAL_ID}#other": "{arg1}  {count} маяка у причала",
    SCALAR_ID: "У причала {arg0}",
}
NATIVE_TRANSLATIONS = {
    f"{PLURAL_ID}#{category}": value + " у причала"
    for category, value in TARGET_VALUES.items()
}
NATIVE_TRANSLATIONS[SCALAR_ID] = "У причала %@"


def unit(value: str, state: str) -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def plural(values: dict[str, str], states: dict[str, str]) -> dict[str, object]:
    return {
        "variations": {
            "plural": {
                category: unit(value, states[category])
                for category, value in values.items()
            }
        }
    }


def document() -> dict[str, object]:
    source_states = {"one": "needs_review", "other": "translated"}
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            PLURAL_ID: {
                "comment": "Target languages independently own their native plural categories",
                "localizations": {
                    "en": plural(SOURCE_VALUES, source_states),
                    "ru": plural(TARGET_VALUES, TARGET_STATES),
                    "de": plural(
                        {"one": "%1$lld Signal %2$@", "other": "%1$lld Signale %2$@"},
                        {"one": "translated", "other": "future_review"},
                    ),
                },
            },
            SCALAR_ID: {
                "localizations": {
                    "en": unit("Steady %@ shore", "needs_review"),
                    "ru": unit("Старый %@ берег", "future_review"),
                }
            },
            PROTECTED_ID: {
                "shouldTranslate": False,
                "localizations": {
                    "en": plural(SOURCE_VALUES, source_states),
                    "ru": plural(TARGET_VALUES, TARGET_STATES),
                },
            },
        },
    }


def catalog(root: dict[str, object]) -> dict[str, object]:
    entries = root["strings"]
    source = entries[PLURAL_ID]["localizations"]["en"]
    translations = {}
    originals = {}
    for language, localization in entries[PLURAL_ID]["localizations"].items():
        if language == "en":
            continue
        branches = localization["variations"]["plural"]
        translations[language] = {
            "variants": {
                category: branch["stringUnit"]["value"]
                for category, branch in sorted(branches.items())
            },
            "variantStates": {
                category: branch["stringUnit"]["state"]
                for category, branch in sorted(branches.items())
            },
        }
        originals[language] = localization
    plural_message = {
        "defaultMessage": (
            "{count, plural, one {{count} beacon {arg1}} "
            "other {{count} beacons {arg1}}}"
        ),
        "description": entries[PLURAL_ID]["comment"],
        "variants": {
            "one": "{count} beacon {arg1}",
            "other": "{count} beacons {arg1}",
        },
        "placeholders": [
            {
                "name": "count",
                "source": "%1$lld",
                "kind": "integer",
                "position": 1,
            },
            {
                "name": "arg1",
                "source": "%2$@",
                "kind": "string",
                "position": 2,
            },
        ],
        "metadata": {
            "appleSourceLocalization": source,
            "sourcePluralStates": {
                category: branch["stringUnit"]["state"]
                for category, branch in sorted(source["variations"]["plural"].items())
            },
            "localizations": translations,
            "appleLocalizationSources": originals,
        },
    }
    scalar_entry = entries[SCALAR_ID]["localizations"]
    scalar = descriptor(scalar_entry["en"]["stringUnit"]["value"])
    scalar["metadata"] = {
        "appleSourceLocalization": scalar_entry["en"],
        "sourceState": scalar_entry["en"]["stringUnit"]["state"],
        "localizations": {
            "ru": {
                "value": scalar_entry["ru"]["stringUnit"]["value"],
                "state": scalar_entry["ru"]["stringUnit"]["state"],
            }
        },
        "appleLocalizationSources": {"ru": scalar_entry["ru"]},
    }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(
            sorted({PLURAL_ID: plural_message, SCALAR_ID: scalar}.items())
        ),
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
        target_start = source.index('"ru"', opening, closing)
        target_opening = source.index("{", target_start)
        target_closing = object_end(source, target_opening)
        target = entry["localizations"]["ru"]
        values = (
            {
                category: branch["stringUnit"]["value"]
                for category, branch in target["variations"]["plural"].items()
            }
            if "variations" in target
            else {None: target["stringUnit"]["value"]}
        )
        branch_cursor = target_opening
        for category, value in values.items():
            escaped = re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
            match = re.compile(r'"value"\s*:\s*"(' + escaped + r')"').search(
                source, branch_cursor, target_closing
            )
            if match is None:
                raise RuntimeError(
                    f"Missing target plural ownership: {identifier}/{category}"
                )
            start, end = match.span(1)
            slot = {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
            if category is not None:
                slot["variant"] = category
            slots.append(slot)
            branch_cursor = match.end()
        cursor = closing + 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": "ru",
        "slots": slots,
    }


def localized(source: str) -> str:
    result = source
    for slot in reversed(skeleton(source, "UTF-8")["slots"]):
        beginning = len(source.encode()[: slot["start"]].decode())
        end = len(source.encode()[: slot["end"]].decode())
        key = slot["id"] + ("#" + slot["variant"] if "variant" in slot else "")
        replacement = json.dumps(NATIVE_TRANSLATIONS[key], ensure_ascii=False)[1:-1]
        result = result[:beginning] + replacement + result[end:]
    return result


def runtime_samples(localized_values: bool) -> list[dict[str, object]]:
    values = []
    for count, category in (
        (0, "many"),
        (1, "one"),
        (2, "few"),
        (5, "many"),
        (21, "one"),
        (22, "few"),
        (25, "many"),
    ):
        ending = {"one": "маяк", "few": "маяка", "many": "маяков"}[category]
        suffix = " у причала" if localized_values else ""
        values.append(
            {
                "message": PLURAL_ID,
                "arguments": [count, "Rowan", 0],
                "expected": f"Rowan  {count} {ending}{suffix}",
            }
        )
    values.append(
        {
            "message": SCALAR_ID,
            "arguments": ["Rowan"],
            "expected": "У причала Rowan" if localized_values else "Старый Rowan берег",
        }
    )
    return values


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
    identifier = (
        "apple-xcstrings-russian-target-only-plural-categories-and-hidden-arguments"
    )
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
                "reason": "Legacy routing rejects Xcode catalogs and cannot preserve target-only Russian plural categories, native hidden arguments, or independent review states.",
            },
        }
    )
    prefix = (
        "apple-xcstrings-source-skeleton-translates-russian-target-only-plural-branches"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetPlural": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": runtime_samples(False),
        "xcstringsLocalizedRuntimeSamples": runtime_samples(True),
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
