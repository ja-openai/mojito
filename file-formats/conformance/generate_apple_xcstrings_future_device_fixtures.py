#!/usr/bin/env python3
"""Generate native Xcode future-device ownership and Foundation fallback fixtures."""

from __future__ import annotations

import copy
import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_target_substitution_fixtures import APPLE, ROOT


STEM = "catalog-future-device-ownership"
DEVICE_ID = "harbor.future.device🧭"
UNKNOWN_ID = "harbor.future.device.only🧭"
FALLBACK_ID = "harbor.future.device.fallback🧭"
PROTECTED_ID = "Private future-device harbor"
UNKNOWN = "futurecar"
PRIVATE = "\ue000raft"
SUPPLEMENTARY = "🧭raft"


def unit(value: str) -> dict[str, object]:
    return {"stringUnit": {"state": "translated", "value": value}}


def document() -> dict[str, object]:
    devices = {
        "iphone": unit("Phone %@ harbor"),
        "mac": unit("Mac %@ harbor"),
        UNKNOWN: unit("Future %@ harbor"),
        PRIVATE: unit("Private scalar %@ harbor"),
        SUPPLEMENTARY: unit("Compass %@ harbor"),
        "other": unit("Fallback %@ harbor"),
    }
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            DEVICE_ID: {"localizations": {"en": {"variations": {"device": devices}}}},
            UNKNOWN_ID: {
                "localizations": {
                    "en": {"variations": {"device": {UNKNOWN: unit("Future only %@")}}}
                }
            },
            FALLBACK_ID: {
                "localizations": {
                    "en": {
                        "variations": {
                            "device": {
                                UNKNOWN: unit("Future fallback %@"),
                                "other": unit("Ordinary fallback %@"),
                            }
                        }
                    }
                }
            },
            PROTECTED_ID: {
                "shouldTranslate": False,
                "localizations": {
                    "en": {"variations": {"device": {UNKNOWN: unit("Protected %@")}}}
                },
            },
        },
    }


def expected(root: dict[str, object]) -> dict[str, object]:
    defaults = {
        DEVICE_ID: "iphone",
        UNKNOWN_ID: UNKNOWN,
        FALLBACK_ID: "other",
    }
    messages = {}
    for identifier, device in defaults.items():
        localization = root["strings"][identifier]["localizations"]["en"]
        value = localization["variations"]["device"][device]["stringUnit"]["value"]
        messages[identifier] = {
            "defaultMessage": value.replace("%@", "{arg0}"),
            "placeholders": [
                {"name": "arg0", "source": "%@", "kind": "string", "position": 1}
            ],
            "metadata": {
                "appleSourceLocalization": localization,
                "sourceVariationAxes": localization["variations"],
                "defaultDevice": device,
                "sourceState": "translated",
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
        f"{DEVICE_ID}#@device#iphone": "Téléphone {arg0} quai",
        f"{DEVICE_ID}#@device#mac": "Bureau {arg0} quai",
        f"{DEVICE_ID}#@device#{UNKNOWN}": "Vaisseau {arg0} quai",
        f"{DEVICE_ID}#@device#{PRIVATE}": "Privé {arg0} quai",
        f"{DEVICE_ID}#@device#{SUPPLEMENTARY}": "Boussole {arg0} quai",
        f"{DEVICE_ID}#@device#other": "Repli {arg0} quai",
        f"{UNKNOWN_ID}#@device#{UNKNOWN}": "Futur seul {arg0}",
        f"{FALLBACK_ID}#@device#{UNKNOWN}": "Futur repli {arg0}",
        f"{FALLBACK_ID}#@device#other": "Repli ordinaire {arg0}",
    }


def skeleton(source: str, root: dict[str, object], encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in root["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        cursor = source.index(json.dumps(identifier, ensure_ascii=False), cursor)
        for device, branch in entry["localizations"]["en"]["variations"]["device"].items():
            value = branch["stringUnit"]["value"]
            pattern = re.compile(
                r'"value"\s*:\s*"('
                + re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
                + r')"'
            )
            match = pattern.search(source, cursor)
            if match is None:
                raise RuntimeError(f"Missing future-device source slot: {identifier}/{device}")
            start, end = match.span(1)
            slots.append(
                {
                    "id": identifier,
                    "selector": "@device",
                    "variant": device,
                    "start": bom + len(source[:start].encode(codec)),
                    "end": bom + len(source[:end].encode(codec)),
                }
            )
            cursor = end
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "slots": slots,
    }


def localized(root: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(root)
    for key, value in translations().items():
        identifier, _, device = key.split("#", 2)
        result["strings"][identifier]["localizations"]["en"]["variations"]["device"][
            device
        ]["stringUnit"]["value"] = value.replace("{arg0}", "%@")
    return result


def runtime_samples(translated: bool) -> list[dict[str, object]]:
    return [
        {
            "message": DEVICE_ID,
            "arguments": ["Rowan"],
            "expected": "Bureau Rowan quai" if translated else "Mac Rowan harbor",
        },
        {
            "message": FALLBACK_ID,
            "arguments": ["Rowan"],
            "expected": (
                "Repli ordinaire Rowan" if translated else "Ordinary fallback Rowan"
            ),
        },
        {
            "message": UNKNOWN_ID,
            "arguments": [],
            "expected": "__MOJITO_FOUNDATION_FALLBACK__",
            "fallback": True,
        },
    ]


def main() -> None:
    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-future-device-ownership-and-native-mac-fallback"
    skeleton_prefix = "apple-xcstrings-source-skeleton-future-device-ownership-"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != case_id]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]

    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    original = APPLE / f"{STEM}.xcstrings"
    original.write_text(source, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", expected(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(original))
    write_json(APPLE / f"{STEM}.translations.json", translations())

    translated = localized(root)
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    localized_path.write_text(
        json.dumps(translated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_json(APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(localized_path))
    normalized = copy.deepcopy(root)
    normalized["strings"].pop(PROTECTED_ID)
    normalized_path = APPLE / f"{STEM}.normalized.xcstrings"
    normalized_path.write_text(
        json.dumps(normalized, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    write_json(APPLE / f"{STEM}.normalized.compiled.json", compiled_xcatalog(normalized_path))

    manifest["cases"].append(
        {
            "id": case_id,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "xcstringsNormalized": f"fixtures/apple/{STEM}.normalized.xcstrings",
            "xcstringsNormalizedCompiled": f"fixtures/apple/{STEM}.normalized.compiled.json",
            "xcstringsFutureDevices": True,
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": (
                    "Legacy routing cannot preserve unknown/supplementary future Xcode "
                    "device branches or distinguish real Mac/other fallback from "
                    "compiler-accepted but Foundation-unavailable device-only strings."
                ),
            },
        }
    )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, root, encoding),
        )
        manifest["sourceSkeletons"].append(
            {
                "id": skeleton_prefix + ("utf16" if suffix else "utf8"),
                **({"encoding": encoding} if suffix else {}),
                "format": "apple_xcstrings",
                "xcstringsAllDeviceSlots": True,
                "xcstringsFutureDevices": True,
                "input": f"fixtures/apple/{STEM}.xcstrings",
                "expected": f"fixtures/apple/{STEM}{suffix}.expected.skeleton.json",
                "translations": f"fixtures/apple/{STEM}.translations.json",
                "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
                "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
                "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
                "xcstringsOriginalRuntimeSamples": runtime_samples(False),
                "xcstringsLocalizedRuntimeSamples": runtime_samples(True),
            }
        )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
