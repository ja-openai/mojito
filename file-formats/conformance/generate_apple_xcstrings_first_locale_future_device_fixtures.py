#!/usr/bin/env python3
"""Generate native-proven first-target opaque Xcode device and fallback fixtures."""

from __future__ import annotations

import copy
import json

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_first_locale_device_fixtures import (
    APPLE,
    CATEGORIES,
    ENDINGS,
    MISSING_PLURAL,
    MISSING_SCALAR,
    NULL_PLURAL,
    NULL_SCALAR,
    PLURAL_SUFFIXES,
    ROOT,
    SCALAR_SUFFIXES,
    catalog as original_catalog,
    document as original_document,
    runtime_samples as original_runtime_samples,
    skeleton as original_skeleton,
)


STEM = "catalog-first-russian-future-device-locale"
UNKNOWN = "futurecar"
PRIVATE = "\ue000raft"
SUPPLEMENTARY = "🧭raft"
FUTURE_DEVICES = (UNKNOWN, PRIVATE, SUPPLEMENTARY)
SCALAR_DEVICES = ("iphone", "mac", *FUTURE_DEVICES, "other")
PLURAL_DEVICES = ("iphone", "mac", *FUTURE_DEVICES)
DEVICE_NOUNS = {
    "iphone": "мобильный",
    "mac": "настольный",
    UNKNOWN: "будущий",
    PRIVATE: "частный",
    SUPPLEMENTARY: "компасный",
    "other": "запасной",
}


def document() -> dict[str, object]:
    root = original_document()
    for identifier, record in root["strings"].items():
        if record.get("shouldTranslate") is False:
            continue
        for locale in ("en", "de"):
            devices = record["localizations"][locale]["variations"]["device"]
            original = devices["iphone"]
            identities = SCALAR_DEVICES if identifier in SCALAR_SUFFIXES else PLURAL_DEVICES
            for device in identities[2:]:
                branch = copy.deepcopy(original)
                if "stringUnit" in branch:
                    branch["stringUnit"]["value"] = branch["stringUnit"][
                        "value"
                    ].replace("Touch ", f"Source {device} ")
                else:
                    for category in branch["variations"]["plural"].values():
                        category["stringUnit"]["value"] = category["stringUnit"][
                            "value"
                        ].replace("mobile", f"source-{device}")
                devices[device] = branch
    return root


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


def translations() -> dict[str, str]:
    result = {}
    for identifier, suffix in SCALAR_SUFFIXES.items():
        branches = {
            device: f"На {device} {{arg0}}  {suffix}" for device in SCALAR_DEVICES
        }
        result[identifier] = (
            "{device, select, "
            + " ".join(f"{device} {{{branches[device]}}}" for device in SCALAR_DEVICES)
            + "}"
        )
    for identifier, suffix in PLURAL_SUFFIXES.items():
        branches = {device: plural(device, suffix) for device in PLURAL_DEVICES}
        result[identifier] = (
            "{device, select, "
            + " ".join(f"{device} {{{branches[device]}}}" for device in PLURAL_DEVICES)
            + f" other {{{branches['iphone']}}}"
            + "}"
        )
    return result


def catalog(root: dict[str, object]) -> dict[str, object]:
    expected = original_catalog(original_document())
    for identifier, descriptor in expected["messages"].items():
        localizations = root["strings"][identifier]["localizations"]
        source = localizations["en"]
        descriptor["metadata"]["sourceVariationAxes"] = source["variations"]
        descriptor["metadata"]["appleSourceLocalization"] = source
        descriptor["metadata"]["localizations"]["de"]["variationAxes"] = (
            localizations["de"]["variations"]
        )
        descriptor["metadata"]["appleLocalizationSources"]["de"] = (
            localizations["de"]
        )
    return expected


def target(identifier: str) -> dict[str, object]:
    result = {"variations": {"device": {}}}
    identities = SCALAR_DEVICES if identifier in SCALAR_SUFFIXES else PLURAL_DEVICES
    for device in sorted(identities):
        if identifier in SCALAR_SUFFIXES:
            result["variations"]["device"][device] = {
                "stringUnit": {
                    "state": "translated",
                    "value": (
                        f"На {device} %1$@ %2$n {SCALAR_SUFFIXES[identifier]}"
                    ),
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
    return original_skeleton(source, encoding)


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


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized(source)
    original = APPLE / f"{STEM}.xcstrings"
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    original.write_text(source, encoding="utf-8")
    localized_path.write_text(translated, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.translations.json", translations())
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(original))
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
    identifier = "apple-xcstrings-cldr-first-russian-future-device-locale"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != identifier]
    manifest["cases"].append(
        {
            "id": identifier,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "xcstringsFutureDevices": True,
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": (
                    "Legacy routing cannot atomically insert a first target locale "
                    "with unknown/private-use/supplementary scalar/plural device "
                    "identities and an independently translatable native scalar "
                    "other fallback while preserving unrelated source bytes."
                ),
            },
        }
    )
    rejected_identifier = "apple-xcstrings-rejects-varied-other-device-fallback"
    manifest["cases"] = [
        case for case in manifest["cases"] if case["id"] != rejected_identifier
    ]
    rejected_root = copy.deepcopy(root)
    source_devices = rejected_root["strings"][MISSING_PLURAL]["localizations"][
        "en"
    ]["variations"]["device"]
    source_devices["other"] = copy.deepcopy(source_devices["iphone"])
    rejected_path = APPLE / "catalog-invalid-varied-other-device-fallback.xcstrings"
    rejected_path.write_text(
        json.dumps(rejected_root, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    manifest["cases"].append(
        {
            "id": rejected_identifier,
            "format": "apple_xcstrings",
            "input": (
                "fixtures/apple/catalog-invalid-varied-other-device-fallback.xcstrings"
            ),
            "error": "INVALID_XCSTRINGS",
            "xcstringsOracle": "reject",
            "xcstringsDiagnostic": "Fallback value cannot be further varied",
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-first-russian-future-devices"
    manifest["sourceSkeletons"] = [
        case for case in manifest["sourceSkeletons"] if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetDeviceInsertion": True,
        "xcstringsFirstLocaleCategories": True,
        "xcstringsFirstLocaleDevices": True,
        "xcstringsFirstLocaleFutureDevices": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [],
        "xcstringsLocalizedRuntimeSamples": original_runtime_samples(),
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
