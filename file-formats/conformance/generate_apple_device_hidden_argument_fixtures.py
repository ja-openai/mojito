#!/usr/bin/env python3
"""Generate native-safe hidden Foundation arguments in device-owned plurals."""

from __future__ import annotations

import html
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from generate_apple_device_plural_stringsdict_fixtures import normalized_xml
from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_disabled_branch_argument_fixtures import branch_descriptor


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "device-hidden-printf-arguments"
DEVICES = ("iphone", "mac")
CATEGORIES = ("one", "other")
PATTERNS = {
    "device.after🧭": "%lld%n %@",
    "device.repeated": "%lld%n%n %@",
    "device.explicit": "%1$lld %3$n %2$@",
    "device.escaped": "%lld%%n %@",
}


def native(device: str, category: str, pattern: str, localized: bool) -> str:
    place = "pocket" if device == "iphone" else "desktop"
    noun = "marker" if category == "one" else "markers"
    value = f"{pattern} {place} {noun}"
    if localized:
        value = (
            value.replace("markers", "repères")
            .replace("marker", "repère")
            .replace("pocket", "mobile")
            .replace("desktop", "bureau")
        )
    return value


def plural_rule(device: str, pattern: str, localized: bool) -> dict[str, object]:
    return {
        "NSStringLocalizedFormatKey": "%#@count@",
        "count": {
            "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
            "NSStringFormatValueTypeKey": "lld",
            **{
                category: native(device, category, pattern, localized)
                for category in CATEGORIES
            },
        },
    }


def definitions(localized: bool = False) -> dict[str, object]:
    return {
        identifier: {
            "NSStringDeviceSpecificRuleType": {
                device: plural_rule(device, pattern, localized) for device in DEVICES
            }
        }
        for identifier, pattern in PATTERNS.items()
    }


def apple_catalog(resource: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, definition in sorted(resource.items()):
        devices = definition["NSStringDeviceSpecificRuleType"]
        selected = devices["iphone"]
        variants = {}
        placeholders = []
        disabled = {}
        for category in CATEGORIES:
            text, current, conversions = branch_descriptor(
                selected["count"][category], 1
            )
            variants[category] = text
            placeholders.extend(
                placeholder
                for placeholder in current
                if placeholder not in placeholders
            )
            if conversions:
                disabled[category] = conversions
        metadata = {
            "appleLocalizedFormat": "%#@count@",
            "applePluralRules": {
                "count": {
                    "valueType": "lld",
                    "variants": {
                        category: selected["count"][category] for category in CATEGORIES
                    },
                }
            },
            "pluralVariable": "count",
            "valueType": "lld",
            "defaultDevice": "iphone",
            "devicePluralVariants": devices,
        }
        if disabled:
            metadata["applePluralDisabledPrintfConversions"] = {"count": disabled}
        messages[identifier] = {
            "defaultMessage": "{count, plural, "
            + " ".join(
                f"{category} {{{variants[category]}}}" for category in CATEGORIES
            )
            + "}",
            "variants": variants,
            "placeholders": placeholders,
            "metadata": metadata,
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": messages,
    }


def xcode_root(localized: bool = False) -> dict[str, object]:
    entries = {}
    for identifier, pattern in PATTERNS.items():
        entries[identifier] = {
            "localizations": {
                "en": {
                    "variations": {
                        "device": {
                            device: {
                                "variations": {
                                    "plural": {
                                        category: {
                                            "stringUnit": {
                                                "state": "translated",
                                                "value": native(
                                                    device, category, pattern, localized
                                                ),
                                            }
                                        }
                                        for category in CATEGORIES
                                    }
                                }
                            }
                            for device in DEVICES
                        }
                    }
                }
            }
        }
    return {"sourceLanguage": "en", "strings": entries, "version": "1.0"}


def xcode_catalog(root: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, descriptor in sorted(root["strings"].items()):
        source = descriptor["localizations"]["en"]
        devices = source["variations"]["device"]
        selected = devices["iphone"]["variations"]["plural"]
        variants = {}
        placeholders = []
        disabled = {}
        for category in CATEGORIES:
            text, current, conversions = branch_descriptor(
                selected[category]["stringUnit"]["value"], 1
            )
            variants[category] = text
            placeholders.extend(
                placeholder
                for placeholder in current
                if placeholder not in placeholders
            )
            if conversions:
                disabled[category] = conversions
        metadata = {
            "appleSourceLocalization": source,
            "defaultDevice": "iphone",
            "sourcePluralStates": {category: "translated" for category in CATEGORIES},
            "sourceVariationAxes": {"device": devices},
        }
        if disabled:
            metadata["applePluralDisabledPrintfConversions"] = {"count": disabled}
        messages[identifier] = {
            "defaultMessage": "{count, plural, "
            + " ".join(
                f"{category} {{{variants[category]}}}" for category in CATEGORIES
            )
            + "}",
            "variants": variants,
            "placeholders": placeholders,
            "metadata": metadata,
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": messages,
    }


def slots(source: str, kind: str, *, encoding: str = "UTF-8") -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    owned = []
    cursor = 0
    for identifier, pattern in PATTERNS.items():
        for device in DEVICES:
            for category in CATEGORIES:
                original = native(device, category, pattern, False)
                if kind == "apple_stringsdict":
                    expression = re.compile(
                        r"<string>(" + re.escape(html.escape(original)) + r")</string>"
                    )
                else:
                    expression = re.compile(
                        r'"value"\s*:\s*"('
                        + re.escape(json.dumps(original, ensure_ascii=False)[1:-1])
                        + r')"'
                    )
                match = expression.search(source, cursor)
                if match is None:
                    raise RuntimeError(
                        f"Missing {kind} source slot {identifier}/{device}/{category}"
                    )
                cursor = match.end()
                owned.append(
                    {
                        "id": identifier,
                        "selector": f"@device={device}",
                        "variant": category,
                        "start": bom + len(source[: match.start(1)].encode(codec)),
                        "end": bom + len(source[: match.end(1)].encode(codec)),
                    }
                )
    return {
        "schemaVersion": 1,
        "sourceFormat": kind,
        "encoding": encoding,
        "source": source,
        "slots": owned,
    }


def translations() -> dict[str, str]:
    return {
        f"{identifier}#@device={device}#{category}": branch_descriptor(
            native(device, category, pattern, True), 1
        )[0]
        for identifier, pattern in PATTERNS.items()
        for device in DEVICES
        for category in CATEGORIES
    }


def platform_samples(localized: bool) -> list[dict[str, object]]:
    results = []
    for identifier, pattern in PATTERNS.items():
        for number in (1, 3):
            category = "one" if number == 1 else "other"
            text, placeholders, disabled = branch_descriptor(
                native("mac", category, pattern, localized), 1
            )
            values = {
                placeholder["name"]: (
                    number if placeholder["name"] == "count" else "Rowan"
                )
                for placeholder in placeholders
            }
            positions = {
                placeholder["position"]: values[placeholder["name"]]
                for placeholder in placeholders
            }
            for conversion in disabled:
                if "argumentPosition" in conversion:
                    positions.setdefault(conversion["argumentPosition"], 0)
            expected = re.sub(
                r"\{([^{}]+)\}",
                lambda match: str(values[match.group(1)]),
                text,
            )
            results.append(
                {
                    "message": identifier,
                    "arguments": [
                        positions[index] for index in range(1, max(positions) + 1)
                    ],
                    "expected": expected,
                }
            )
    return results


def formatjs_samples(catalog: dict[str, object]) -> list[dict[str, object]]:
    results = []
    for identifier, message in catalog["messages"].items():
        for number in (1, 3):
            category = "one" if number == 1 else "other"
            values = {
                placeholder["name"]: (
                    number if placeholder["name"] == "count" else "Rowan"
                )
                for placeholder in message["placeholders"]
            }
            expected = re.sub(
                r"\{([^{}]+)\}",
                lambda match: str(values[match.group(1)]),
                message["variants"][category],
            )
            results.append(
                {"message": identifier, "values": values, "expected": expected}
            )
    return results


def main() -> None:
    original = definitions()
    localized = definitions(localized=True)
    source = plistlib.dumps(original, fmt=plistlib.FMT_XML, sort_keys=False).decode()
    target = plistlib.dumps(localized, fmt=plistlib.FMT_XML, sort_keys=False).decode()
    apple_path = APPLE / f"{STEM}.stringsdict"
    apple_path.write_text(source, encoding="utf-8")
    (APPLE / f"{STEM}.localized.stringsdict").write_text(target, encoding="utf-8")
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        normalized_xml(original), encoding="utf-8"
    )
    write_json(APPLE / f"{STEM}.compiled.json", original)
    write_json(APPLE / f"{STEM}.localized.compiled.json", localized)
    catalog = apple_catalog(original)
    write_json(APPLE / f"{STEM}.expected.json", catalog)
    write_json(APPLE / f"{STEM}.translations.json", translations())
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            slots(source, "apple_stringsdict", encoding=encoding),
        )

    executable = shutil.which("plutil")
    if executable is None:
        raise SystemExit("Apple plutil is required for device binary fixtures")
    with tempfile.TemporaryDirectory(
        prefix="mojito-device-hidden-binary-"
    ) as directory:
        binary = Path(directory) / "resource.binary"
        subprocess.run(
            [executable, "-convert", "binary1", "-o", str(binary), str(apple_path)],
            check=True,
        )
        encoded = binary.read_bytes().hex()
    (APPLE / f"{STEM}.binary.hex").write_text(
        "\n".join(encoded[index : index + 64] for index in range(0, len(encoded), 64))
        + "\n",
        encoding="ascii",
    )

    root = xcode_root()
    target_root = xcode_root(localized=True)
    xcode_path = APPLE / f"catalog-{STEM}.xcstrings"
    localized_xcode = APPLE / f"catalog-{STEM}.localized.xcstrings"
    normalized_xcode = APPLE / f"catalog-{STEM}.normalized.xcstrings"
    write_json(xcode_path, root)
    write_json(localized_xcode, target_root)
    write_json(normalized_xcode, root, sort_keys=True)
    xcatalog = xcode_catalog(root)
    write_json(APPLE / f"catalog-{STEM}.expected.json", xcatalog)
    write_json(APPLE / f"catalog-{STEM}.compiled.json", compiled_xcatalog(xcode_path))
    write_json(
        APPLE / f"catalog-{STEM}.localized.compiled.json",
        compiled_xcatalog(localized_xcode),
    )
    write_json(
        APPLE / f"catalog-{STEM}.normalized.compiled.json",
        compiled_xcatalog(normalized_xcode),
    )
    xsource = xcode_path.read_text(encoding="utf-8")
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"catalog-{STEM}{suffix}.expected.skeleton.json",
            slots(xsource, "apple_xcstrings", encoding=encoding),
        )
    write_json(APPLE / f"catalog-{STEM}.translations.json", translations())

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    prefix = "apple-device-owned-plural-hidden-foundation-argument-positions"
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(prefix)
    ]
    apple_case = {
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "expected": f"fixtures/apple/{STEM}.expected.json",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleStringsdictNormalized": f"fixtures/apple/{STEM}.normalized.stringsdict",
    }
    manifest["cases"].extend(
        [
            {
                "id": prefix + "-stringsdict-xml",
                **apple_case,
                "okapi": {
                    "policy": "different",
                    "assetPath": "en.lproj/Localizable.stringsdict",
                    "expected": f"fixtures/okapi/apple-{STEM}.json",
                    "reason": "Legacy extraction ignores device-owned Foundation plural dictionaries and cannot preserve category-local hidden native arguments or source-template ownership.",
                },
            },
            {
                "id": prefix + "-stringsdict-binary",
                **apple_case,
                "encoding": "BINARY_PLIST",
                "binaryFixture": f"fixtures/apple/{STEM}.binary.hex",
            },
            {
                "id": prefix + "-xcode-device-variations",
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/catalog-{STEM}.xcstrings",
                "expected": f"fixtures/apple/catalog-{STEM}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/catalog-{STEM}.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/catalog-{STEM}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": f"fixtures/apple/catalog-{STEM}.normalized.compiled.json",
                "xcstringsRuntimeSamples": formatjs_samples(xcatalog),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": "Legacy extension routing rejects Xcode catalogs; portable device-owned plural branches preserve hidden Foundation argument slots independently.",
                },
            },
        ]
    )
    skeleton_prefix = (
        "apple-source-skeleton-preserves-device-owned-hidden-foundation-arguments"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]
    common = {
        "format": "apple_stringsdict",
        "appleAllVariationSlots": True,
        "appleDevicePluralSlots": True,
        "appleDeviceHiddenArgumentSlots": True,
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.stringsdict",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": platform_samples(False),
        "appleLocalizedRuntimeSamples": platform_samples(True),
    }
    xcode = {
        "format": "apple_xcstrings",
        "xcstringsAllDeviceSlots": True,
        "xcstringsDevicePluralSlots": True,
        "xcstringsDeviceHiddenArgumentSlots": True,
        "input": f"fixtures/apple/catalog-{STEM}.xcstrings",
        "translations": f"fixtures/apple/catalog-{STEM}.translations.json",
        "localized": f"fixtures/apple/catalog-{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/catalog-{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/catalog-{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": platform_samples(False),
        "xcstringsLocalizedRuntimeSamples": platform_samples(True),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": skeleton_prefix + "-stringsdict-xml",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                **common,
            },
            {
                "id": skeleton_prefix + "-stringsdict-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                **common,
            },
            {
                "id": skeleton_prefix + "-xcode",
                "expected": f"fixtures/apple/catalog-{STEM}.expected.skeleton.json",
                **xcode,
            },
            {
                "id": skeleton_prefix + "-xcode-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/catalog-{STEM}.utf16.expected.skeleton.json",
                **xcode,
            },
        ]
    )
    write_json(
        ROOT / "fixtures" / "okapi" / f"apple-{STEM}.json",
        {"filterConfigId": "okf_macStringdict@mojito", "units": []},
    )
    missing = [
        {
            "category": "missing_legacy",
            "id": f"{identifier}_count_{category}",
            "count": 1,
        }
        for identifier in sorted(PATTERNS)
        for category in ("few", "many", "one", "other", "two", "zero")
    ]
    write_json(
        ROOT / "fixtures" / "shadow" / f"apple-{STEM}.json",
        {
            "sourceFormat": "apple_stringsdict",
            "canonicalUnits": len(missing),
            "legacyUnits": 0,
            "outcome": "mismatch",
            "differences": missing,
        },
    )
    shadow_id = "shadow-apple-device-owned-hidden-foundation-arguments"
    manifest["shadowComparisons"] = [
        case for case in manifest["shadowComparisons"] if case["id"] != shadow_id
    ]
    manifest["shadowComparisons"].append(
        {
            "id": shadow_id,
            "case": prefix + "-stringsdict-xml",
            "expected": f"fixtures/shadow/apple-{STEM}.json",
        }
    )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
