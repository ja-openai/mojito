#!/usr/bin/env python3
"""Generate native-verified, independently owned target-language Xcode devices."""

from __future__ import annotations

import copy
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
STEM = "catalog-target-russian-devices"
SCALAR_ID = "harbor.target.device.scalar🧭"
PLURAL_ID = "harbor.target.device.plural🧭"
PROTECTED_ID = "Private target Russian device branches"
DEVICES = ("iphone", "mac")
CATEGORIES = ("one", "few", "many", "other")
TARGET_STATES = {
    "one": "needs_review",
    "few": "new",
    "many": "future_review",
    "other": "translated",
}
ENDINGS = {"one": "маяк", "few": "маяка", "many": "маяков", "other": "маяка"}
SOURCE_SCALARS = {
    "iphone": "Touch %1$@ %2$n beacon",
    "mac": "Click %1$@ %2$n beacon",
}
TARGET_SCALARS = {
    "iphone": "Коснитесь %1$@ %2$n маяка",
    "mac": "Выберите %1$@ %2$n маяк",
}
SCALAR_TRANSLATIONS = {
    "iphone": "На iPhone {arg0}  у маяка",
    "mac": "На Mac {arg0}  у маяка",
}
DEVICE_NOUNS = {"iphone": "мобильный", "mac": "настольный"}
TRANSLATIONS = {
    **{
        f"{SCALAR_ID}#@device#{device}": SCALAR_TRANSLATIONS[device]
        for device in DEVICES
    },
    **{
        f"{PLURAL_ID}#@device={device}#{category}": (
            f"{{arg1}}  {{count}} обновлённый {DEVICE_NOUNS[device]} {ENDINGS[category]}"
        )
        for device in DEVICES
        for category in CATEGORIES
    },
}


def unit(value: str, state: str) -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def source_plural(device: str) -> dict[str, object]:
    noun = "mobile" if device == "iphone" else "desktop"
    return {
        "variations": {
            "plural": {
                "one": unit(f"%1$lld %3$n {noun} beacon %2$@", "needs_review"),
                "other": unit(f"%1$lld %3$n {noun} beacons %2$@", "translated"),
            }
        }
    }


def target_plural(device: str) -> dict[str, object]:
    return {
        "variations": {
            "plural": {
                category: unit(
                    f"%2$@ %3$n %1$lld {DEVICE_NOUNS[device]} {ENDINGS[category]}",
                    TARGET_STATES[category],
                )
                for category in CATEGORIES
            }
        }
    }


def device_tree(branches: dict[str, object]) -> dict[str, object]:
    return {"variations": {"device": branches}}


def document() -> dict[str, object]:
    scalar_source = device_tree(
        {
            device: unit(
                SOURCE_SCALARS[device],
                "needs_review" if device == "iphone" else "translated",
            )
            for device in DEVICES
        }
    )
    scalar_target = device_tree(
        {
            device: unit(
                TARGET_SCALARS[device],
                "needs_review" if device == "iphone" else "future_review",
            )
            for device in DEVICES
        }
    )
    plural_source = device_tree({device: source_plural(device) for device in DEVICES})
    plural_target = device_tree({device: target_plural(device) for device in DEVICES})
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            SCALAR_ID: {
                "comment": "Target languages own device-specific scalar text independently",
                "localizations": {"en": scalar_source, "ru": scalar_target},
            },
            PLURAL_ID: {
                "comment": "Each target device independently owns its language-specific plurals",
                "localizations": {"en": plural_source, "ru": plural_target},
            },
            PROTECTED_ID: {
                "shouldTranslate": False,
                "localizations": {
                    "en": copy.deepcopy(plural_source),
                    "ru": copy.deepcopy(plural_target),
                },
            },
        },
    }


def catalog(root: dict[str, object]) -> dict[str, object]:
    scalar = root["strings"][SCALAR_ID]
    scalar_source = scalar["localizations"]["en"]
    scalar_target = scalar["localizations"]["ru"]
    scalar_message = descriptor(SOURCE_SCALARS["iphone"])
    scalar_message["description"] = scalar["comment"]
    for conversion in scalar_message.get("metadata", {}).get(
        "appleDisabledPrintfConversions", []
    ):
        conversion.pop("argumentPosition", None)
    scalar_message.setdefault("metadata", {}).update(
        {
            "sourceVariationAxes": {"device": scalar_source["variations"]["device"]},
            "defaultDevice": "iphone",
            "appleSourceLocalization": scalar_source,
            "sourceState": "needs_review",
            "localizations": {
                "ru": {
                    "variationAxes": {"device": scalar_target["variations"]["device"]}
                }
            },
            "appleLocalizationSources": {"ru": scalar_target},
        }
    )

    plural = root["strings"][PLURAL_ID]
    plural_source = plural["localizations"]["en"]
    plural_target = plural["localizations"]["ru"]
    conversion = [{"position": 8, "source": "%3$n", "argumentPosition": 3}]
    plural_message = {
        "defaultMessage": (
            "{count, plural, one {{count}  mobile beacon {arg1}} "
            "other {{count}  mobile beacons {arg1}}}"
        ),
        "description": plural["comment"],
        "variants": {
            "one": "{count}  mobile beacon {arg1}",
            "other": "{count}  mobile beacons {arg1}",
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
            "applePluralDisabledPrintfConversions": {
                "count": {"one": conversion, "other": conversion}
            },
            "sourceVariationAxes": {"device": plural_source["variations"]["device"]},
            "defaultDevice": "iphone",
            "appleSourceLocalization": plural_source,
            "sourcePluralStates": {"one": "needs_review", "other": "translated"},
            "localizations": {
                "ru": {
                    "variationAxes": {"device": plural_target["variations"]["device"]}
                }
            },
            "appleLocalizationSources": {"ru": plural_target},
        },
    }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(
            sorted({SCALAR_ID: scalar_message, PLURAL_ID: plural_message}.items())
        ),
    }


def localized(root: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(root)
    scalar = result["strings"][SCALAR_ID]["localizations"]["ru"]["variations"]["device"]
    plural = result["strings"][PLURAL_ID]["localizations"]["ru"]["variations"]["device"]
    for device in DEVICES:
        scalar[device]["stringUnit"]["value"] = (
            SCALAR_TRANSLATIONS[device]
            .replace("{arg0}", "%1$@")
            .replace("  ", " %2$n ")
        )
        for category in CATEGORIES:
            plural[device]["variations"]["plural"][category]["stringUnit"]["value"] = (
                TRANSLATIONS[f"{PLURAL_ID}#@device={device}#{category}"]
                .replace("{arg1}", "%2$@")
                .replace("{count}", "%1$lld")
                .replace("  ", " %3$n ")
            )
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
        target = source.index('"ru"', opening, closing)
        target_opening = source.index("{", target)
        target_closing = object_end(source, target_opening)
        branch_cursor = target_opening
        for device, branch in entry["localizations"]["ru"]["variations"][
            "device"
        ].items():
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
                        f"Missing Russian device target: {identifier}/{device}/{category}"
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


def runtime_samples(translated: bool) -> list[dict[str, object]]:
    scalar_prefix = "На Mac" if translated else "Выберите"
    scalar_suffix = "у маяка" if translated else "маяк"
    samples = [
        {
            "message": SCALAR_ID,
            "arguments": ["Rowan", 0],
            "expected": f"{scalar_prefix} Rowan  {scalar_suffix}",
        }
    ]
    for count, category in (
        (0, "many"),
        (1, "one"),
        (2, "few"),
        (5, "many"),
        (21, "one"),
        (22, "few"),
        (25, "many"),
    ):
        adjective = "обновлённый " if translated else ""
        samples.append(
            {
                "message": PLURAL_ID,
                "arguments": [count, "Rowan", 0],
                "expected": (
                    f"Rowan  {count} {adjective}{DEVICE_NOUNS['mac']} "
                    f"{ENDINGS[category]}"
                ),
            }
        )
    return samples


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = json.dumps(localized(root), ensure_ascii=False, indent=2) + "\n"
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

    path = ROOT / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-independent-russian-device-and-nested-plural-branches"
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
                "reason": (
                    "Legacy routing rejects Xcode String Catalogs and cannot preserve "
                    "independently target-owned device/plural branches, review states, "
                    "hidden Foundation arguments, or untouched source bytes."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-target-russian-device-and-nested-plural"
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
    write_json(path, manifest)


if __name__ == "__main__":
    main()
