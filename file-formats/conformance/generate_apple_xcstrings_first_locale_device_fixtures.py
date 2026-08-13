#!/usr/bin/env python3
"""Generate native-verified first-locale scalar/plural Xcode device trees."""

from __future__ import annotations

import copy
import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_missing_target_device_fixtures import select
from generate_apple_xcstrings_target_device_fixtures import (
    APPLE,
    DEVICES,
    DEVICE_NOUNS,
    ENDINGS,
    PLURAL_ID,
    ROOT,
    SCALAR_ID,
    catalog as existing_catalog,
    document as existing_document,
)


STEM = "catalog-first-russian-device-locale"
MISSING_SCALAR = "harbor.first.device.missing.scalar🧭"
NULL_SCALAR = "harbor.first.device.null.scalar🧭"
MISSING_PLURAL = "harbor.first.device.missing.plural🧭"
NULL_PLURAL = "harbor.first.device.null.plural🧭"
PROTECTED_MISSING = "Private first missing Russian device"
PROTECTED_NULL = "Private first null Russian device"
SCALAR_SUFFIXES = {MISSING_SCALAR: "в проливе", NULL_SCALAR: "у маяка"}
PLURAL_SUFFIXES = {MISSING_PLURAL: "в гавани", NULL_PLURAL: "у причала"}
CATEGORIES = ("few", "many", "one", "other")


def plural(device: str, suffix: str) -> str:
    return (
        "{count, plural, "
        + " ".join(
            f"{category} {{{{arg1}}  {{count}} {DEVICE_NOUNS[device]} "
            f"{ENDINGS[category]} {suffix}}}"
            for category in CATEGORIES
        )
        + "}"
    )


TRANSLATIONS = {
    **{
        identifier: select(
            {device: f"На {device} {{arg0}}  {suffix}" for device in DEVICES}
        )
        for identifier, suffix in SCALAR_SUFFIXES.items()
    },
    **{
        identifier: select({device: plural(device, suffix) for device in DEVICES})
        for identifier, suffix in PLURAL_SUFFIXES.items()
    },
}


def document() -> dict[str, object]:
    originals = existing_document()["strings"]
    entries = {}
    for identifier in (*SCALAR_SUFFIXES, *PLURAL_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_SUFFIXES else PLURAL_ID
        localizations = {
            "en": copy.deepcopy(originals[template]["localizations"]["en"]),
            "de": copy.deepcopy(originals[template]["localizations"]["en"]),
        }
        if identifier in {NULL_SCALAR, NULL_PLURAL}:
            localizations["ru"] = None
        entries[identifier] = {
            "comment": "Pinned CLDR owns first-locale Xcode device variations",
            "localizations": localizations,
        }
    entries[PROTECTED_MISSING] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(originals[SCALAR_ID]["localizations"]["en"])
        },
    }
    entries[PROTECTED_NULL] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(originals[PLURAL_ID]["localizations"]["en"]),
            "ru": None,
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    originals = existing_catalog(existing_document())["messages"]
    messages = {}
    for identifier in (*SCALAR_SUFFIXES, *PLURAL_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_SUFFIXES else PLURAL_ID
        descriptor = copy.deepcopy(originals[template])
        descriptor["description"] = root["strings"][identifier]["comment"]
        source = root["strings"][identifier]["localizations"]
        descriptor["metadata"]["localizations"] = {
            "de": {"variationAxes": {"device": source["de"]["variations"]["device"]}}
        }
        descriptor["metadata"]["appleLocalizationSources"] = {"de": source["de"]}
        messages[identifier] = descriptor
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(sorted(messages.items())),
    }


def target(identifier: str) -> dict[str, object]:
    result = {"variations": {"device": {}}}
    for device in sorted(DEVICES):
        if identifier in SCALAR_SUFFIXES:
            result["variations"]["device"][device] = {
                "stringUnit": {
                    "state": "translated",
                    "value": f"На {device} %1$@ %2$n {SCALAR_SUFFIXES[identifier]}",
                }
            }
        else:
            result["variations"]["device"][device] = {
                "variations": {
                    "plural": {
                        category: {
                            "stringUnit": {
                                "state": "translated",
                                "value": (
                                    f"%2$@  %1$lld %3$n"
                                    f"{DEVICE_NOUNS[device]} {ENDINGS[category]} "
                                    f"{PLURAL_SUFFIXES[identifier]}"
                                ),
                            }
                        }
                        for category in sorted(CATEGORIES)
                    }
                }
            }
    return result


def skeleton(source: str, encoding: str) -> dict[str, object]:
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
        if "ru" in entry["localizations"]:
            match = re.compile(r'"ru"\s*:\s*(null)').search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing first-locale device null: {identifier}")
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
        "appleTargetLocale": "ru",
        "slots": slots,
    }


def localized(source: str) -> str:
    original = source.encode("utf-8")
    result = bytearray()
    previous = 0
    for slot in skeleton(source, "UTF-8")["slots"]:
        result.extend(original[previous : slot["start"]])
        value = json.dumps(
            target(slot["id"]), ensure_ascii=False, separators=(",", ":")
        )
        if slot["start"] == slot["end"]:
            value = ',"ru":' + value
        result.extend(value.encode("utf-8"))
        previous = slot["end"]
    result.extend(original[previous:])
    return result.decode("utf-8")


def runtime_samples() -> list[dict[str, object]]:
    result = [
        {
            "message": identifier,
            "arguments": ["Rowan", 0],
            "expected": f"На mac Rowan  {suffix}",
        }
        for identifier, suffix in SCALAR_SUFFIXES.items()
    ]
    for identifier, suffix in PLURAL_SUFFIXES.items():
        for count, category in (
            (0, "many"),
            (1, "one"),
            (2, "few"),
            (5, "many"),
            (21, "one"),
            (22, "few"),
            (25, "many"),
        ):
            result.append(
                {
                    "message": identifier,
                    "arguments": [count, "Rowan", 0],
                    "expected": (
                        f"Rowan  {count} {DEVICE_NOUNS['mac']} "
                        f"{ENDINGS[category]} {suffix}"
                    ),
                }
            )
    return result


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    localized_path.write_text(translated, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.translations.json", TRANSLATIONS)
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(localized_path)
    )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-cldr-first-russian-target-device-locale"
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
                    "Legacy routing cannot derive first-locale device-owned scalar/plural "
                    "trees from ICU rules, preserve native hidden arguments, protect "
                    "unrelated locales, and keep every original source byte unchanged."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-first-russian-cldr-devices"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetDeviceInsertion": True,
        "xcstringsFirstLocaleCategories": True,
        "xcstringsFirstLocaleDevices": True,
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
