#!/usr/bin/env python3
"""Generate native-verified atomic missing/null Xcode target device trees."""

from __future__ import annotations

import copy
import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_target_device_fixtures import (
    APPLE,
    CATEGORIES,
    DEVICES,
    DEVICE_NOUNS,
    ENDINGS,
    PLURAL_ID,
    ROOT,
    SCALAR_ID,
    TRANSLATIONS as EXISTING_TRANSLATIONS,
    catalog as existing_catalog,
    document as existing_document,
    localized as existing_localized,
    runtime_samples as existing_runtime_samples,
)


STEM = "catalog-target-russian-device-insertion"
MISSING_SCALAR = "harbor.target.device.missing.scalar🧭"
NULL_SCALAR = "harbor.target.device.null.scalar🧭"
MISSING_PLURAL = "harbor.target.device.missing.plural🧭"
NULL_PLURAL = "harbor.target.device.null.plural🧭"
PROTECTED_MISSING = "Private missing Russian device tree"
PROTECTED_NULL = "Private null Russian device tree"
SCALAR_SUFFIXES = {MISSING_SCALAR: "в порту", NULL_SCALAR: "у берега"}
PLURAL_SUFFIXES = {MISSING_PLURAL: "в гавани", NULL_PLURAL: "у причала"}


def select(branches: dict[str, str]) -> str:
    fallback = branches["iphone"]
    return (
        "{device, select, "
        + " ".join(f"{device} {{{branches[device]}}}" for device in DEVICES)
        + f" other {{{fallback}}}"
        + "}"
    )


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


ATOMIC_TRANSLATIONS = {
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
TRANSLATIONS = {**EXISTING_TRANSLATIONS, **ATOMIC_TRANSLATIONS}


def document() -> dict[str, object]:
    root = copy.deepcopy(existing_document())
    entries = root["strings"]
    for identifier in (*SCALAR_SUFFIXES, *PLURAL_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_SUFFIXES else PLURAL_ID
        localizations = {"en": copy.deepcopy(entries[template]["localizations"]["en"])}
        if identifier in {NULL_SCALAR, NULL_PLURAL}:
            localizations["ru"] = None
        entries[identifier] = {
            "comment": "One ICU select atomically owns every target device branch",
            "localizations": localizations,
        }
    entries[PROTECTED_MISSING] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(entries[SCALAR_ID]["localizations"]["en"])
        },
    }
    entries[PROTECTED_NULL] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(entries[PLURAL_ID]["localizations"]["en"]),
            "ru": None,
        },
    }
    return root


def catalog(root: dict[str, object]) -> dict[str, object]:
    result = existing_catalog(root)
    for identifier in (*SCALAR_SUFFIXES, *PLURAL_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_SUFFIXES else PLURAL_ID
        descriptor = copy.deepcopy(result["messages"][template])
        descriptor["description"] = root["strings"][identifier]["comment"]
        descriptor["metadata"].pop("localizations", None)
        descriptor["metadata"].pop("appleLocalizationSources", None)
        result["messages"][identifier] = descriptor
    result["messages"] = dict(sorted(result["messages"].items()))
    return result


def localized(root: dict[str, object]) -> dict[str, object]:
    result = existing_localized(root)
    for identifier, suffix in SCALAR_SUFFIXES.items():
        result["strings"][identifier]["localizations"]["ru"] = {
            "variations": {
                "device": {
                    device: {
                        "stringUnit": {
                            "state": "translated",
                            "value": f"На {device} %1$@ %2$n {suffix}",
                        }
                    }
                    for device in sorted(DEVICES)
                }
            }
        }
    for identifier, suffix in PLURAL_SUFFIXES.items():
        result["strings"][identifier]["localizations"]["ru"] = {
            "variations": {
                "device": {
                    device: {
                        "variations": {
                            "plural": {
                                category: {
                                    "stringUnit": {
                                        "state": "translated",
                                        "value": (
                                            f"%2$@  %1$lld %3$n"
                                            f"{DEVICE_NOUNS[device]} "
                                            f"{ENDINGS[category]} {suffix}"
                                        ),
                                    }
                                }
                                for category in sorted(CATEGORIES)
                            }
                        }
                    }
                    for device in sorted(DEVICES)
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
        target = entry["localizations"].get("ru")
        if target is None:
            if "ru" in entry["localizations"]:
                match = re.compile(r'"ru"\s*:\s*(null)').search(
                    source, opening, closing
                )
                if match is None:
                    raise RuntimeError(f"Missing nullable Russian device: {identifier}")
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
            continue
        target_position = source.index('"ru"', opening, closing)
        target_opening = source.index("{", target_position)
        target_closing = object_end(source, target_opening)
        branch_cursor = target_opening
        for device, branch in target["variations"]["device"].items():
            variants = (
                {None: branch["stringUnit"]["value"]}
                if "stringUnit" in branch
                else {
                    category: variant["stringUnit"]["value"]
                    for category, variant in branch["variations"]["plural"].items()
                }
            )
            for category, value in variants.items():
                escaped = re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
                match = re.compile(r'"value"\s*:\s*"(' + escaped + r')"').search(
                    source, branch_cursor, target_closing
                )
                if match is None:
                    raise RuntimeError(
                        f"Missing Russian device branch: {identifier}/{device}/{category}"
                    )
                beginning, end = match.span(1)
                slots.append(
                    {
                        "id": identifier,
                        "selector": (
                            "@device" if category is None else f"@device={device}"
                        ),
                        "variant": device if category is None else category,
                        "start": bom + len(source[:beginning].encode(codec)),
                        "end": bom + len(source[:end].encode(codec)),
                    }
                )
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


def inserted_runtime_samples() -> list[dict[str, object]]:
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


def localized_source(source: str, root: dict[str, object]) -> str:
    target = localized(root)
    original = source.encode("utf-8")
    result = bytearray()
    previous = 0
    for slot in skeleton(source, "UTF-8")["slots"]:
        result.extend(original[previous : slot["start"]])
        localization = target["strings"][slot["id"]]["localizations"]["ru"]
        if "selector" not in slot:
            value = json.dumps(localization, ensure_ascii=False, separators=(",", ":"))
            if slot["start"] == slot["end"]:
                value = ',"ru":' + value
        else:
            device = (
                slot["variant"]
                if slot["selector"] == "@device"
                else slot["selector"].removeprefix("@device=")
            )
            branch = localization["variations"]["device"][device]
            if slot["selector"] == "@device":
                value = branch["stringUnit"]["value"]
            else:
                value = branch["variations"]["plural"][slot["variant"]]["stringUnit"][
                    "value"
                ]
            value = json.dumps(value, ensure_ascii=False)[1:-1]
        result.extend(value.encode("utf-8"))
        previous = slot["end"]
    result.extend(original[previous:])
    return result.decode("utf-8")


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized_source(source, root)
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

    path = ROOT / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-atomic-missing-and-null-russian-target-device-trees"
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
                    "Legacy routing cannot atomically insert absent/null scalar or plural "
                    "target device trees, preserve independently selected native branches, "
                    "review states, hidden arguments, and exact protected source bytes."
                ),
            },
        }
    )
    prefix = (
        "apple-xcstrings-source-skeleton-inserts-missing-russian-target-device-trees"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetDeviceSlots": True,
        "xcstringsTargetDevicePluralSlots": True,
        "xcstringsTargetDeviceInsertion": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": existing_runtime_samples(False),
        "xcstringsLocalizedRuntimeSamples": (
            existing_runtime_samples(True) + inserted_runtime_samples()
        ),
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
    write_json(path, manifest)


if __name__ == "__main__":
    main()
