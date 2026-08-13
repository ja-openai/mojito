#!/usr/bin/env python3
"""Generate native-safe hidden arguments in Foundation presentation-width rules."""

from __future__ import annotations

import html
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from generate_apple_disabled_argument_fixtures import descriptor, write_json


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "width-hidden-printf-arguments"
DEVICES = ("iphone", "mac")
WIDTHS = ("5", "040")
PATTERNS = {
    "after🧭": "%n %@",
    "middle": "%@ %n %@",
    "repeated": "%n%n %@",
    "explicit": "%2$n %1$@",
    "escaped": "%%n %@",
}


def normalized_width_xml(resources: dict[str, object]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]

    def dictionary(
        depth: int, fields: dict[str, object], numeric: bool = False
    ) -> None:
        lines.append("  " * depth + "<dict>")
        keys = sorted(fields, key=int) if numeric else sorted(fields)
        for key in keys:
            value = fields[key]
            lines.append("  " * (depth + 1) + f"<key>{html.escape(key)}</key>")
            if isinstance(value, dict):
                dictionary(
                    depth + 1,
                    value,
                    numeric=depth == 1 and key == "NSStringVariableWidthRuleType",
                )
            elif isinstance(value, str):
                lines.append(
                    "  " * (depth + 1)
                    + f"<string>{html.escape(value, quote=False)}</string>"
                )
            else:
                raise RuntimeError(f"Unsupported Foundation value at {key}")
        lines.append("  " * depth + "</dict>")

    for identifier, rule in sorted(resources.items()):
        lines.append(f"  <key>{html.escape(identifier)}</key>")
        dictionary(1, rule)
    lines.extend(["</dict>", "</plist>", ""])
    return "\n".join(lines)


def native(pattern: str, width: str, device: str | None, localized: bool) -> str:
    extent = "near" if width == "5" else "open"
    pieces = [pattern]
    if device is not None:
        pieces.append("pocket" if device == "iphone" else "desktop")
    pieces.extend((extent, "marker"))
    value = " ".join(pieces)
    if localized:
        value = (
            value.replace("marker", "repère")
            .replace("near", "proche")
            .replace("open", "large")
            .replace("pocket", "mobile")
            .replace("desktop", "bureau")
        )
    return value


def definitions(localized: bool = False) -> dict[str, object]:
    resources: dict[str, object] = {}
    for suffix, pattern in PATTERNS.items():
        resources[f"width.{suffix}"] = {
            "NSStringVariableWidthRuleType": {
                width: native(pattern, width, None, localized) for width in WIDTHS
            }
        }
    for suffix, pattern in PATTERNS.items():
        resources[f"device.{suffix}"] = {
            "NSStringDeviceSpecificRuleType": {
                device: {
                    "NSStringVariableWidthRuleType": {
                        width: native(pattern, width, device, localized)
                        for width in WIDTHS
                    }
                }
                for device in DEVICES
            }
        }
    return resources


def catalog(resources: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, rule in sorted(resources.items()):
        devices = rule.get("NSStringDeviceSpecificRuleType")
        widths = (
            devices["iphone"]["NSStringVariableWidthRuleType"]
            if devices
            else rule["NSStringVariableWidthRuleType"]
        )
        selected = descriptor(widths["040"])
        metadata = selected.setdefault("metadata", {})
        metadata.update(
            {
                "widthVariants": widths,
                "defaultWidth": 40,
                "defaultWidthKey": "040",
            }
        )
        if devices:
            metadata.update({"defaultDevice": "iphone", "deviceWidthVariants": devices})
        messages[identifier] = selected
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": messages,
    }


def slots(source: str, *, encoding: str = "UTF-8") -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    owned = []
    cursor = 0
    for identifier, rule in definitions().items():
        devices = rule.get("NSStringDeviceSpecificRuleType")
        branches = devices.items() if devices else ((None, rule),)
        for device, branch in branches:
            for width, original in branch["NSStringVariableWidthRuleType"].items():
                expression = re.compile(
                    r"<string>("
                    + re.escape(html.escape(original, quote=False))
                    + r")</string>"
                )
                match = expression.search(source, cursor)
                if match is None:
                    raise RuntimeError(
                        f"Missing Foundation width source {identifier}/{device}/{width}"
                    )
                cursor = match.end()
                owned.append(
                    {
                        "id": identifier,
                        "selector": f"@device={device}" if device else "@width",
                        "variant": width,
                        "start": bom + len(source[: match.start(1)].encode(codec)),
                        "end": bom + len(source[: match.end(1)].encode(codec)),
                    }
                )
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "encoding": encoding,
        "source": source,
        "slots": owned,
    }


def translations() -> dict[str, str]:
    values = {}
    for suffix, pattern in PATTERNS.items():
        for width in WIDTHS:
            values[f"width.{suffix}#@width#{width}"] = descriptor(
                native(pattern, width, None, True)
            )["defaultMessage"]
            for device in DEVICES:
                values[f"device.{suffix}#@device={device}#{width}"] = descriptor(
                    native(pattern, width, device, True)
                )["defaultMessage"]
    return values


def runtime_samples(localized: bool) -> list[dict[str, object]]:
    results = []
    for suffix, pattern in PATTERNS.items():
        for kind, device in (("width", None), ("device", "mac")):
            for width in WIDTHS:
                text = native(pattern, width, device, localized)
                message = descriptor(text)
                substitutions = {
                    placeholder["name"]: (
                        "Sky"
                        if placeholder["name"] == "arg0" and suffix == "middle"
                        else "Rowan"
                    )
                    for placeholder in message.get("placeholders", [])
                }
                positions = {
                    placeholder["position"]: substitutions[placeholder["name"]]
                    for placeholder in message.get("placeholders", [])
                }
                for hidden in message.get("metadata", {}).get(
                    "appleDisabledPrintfConversions", []
                ):
                    positions.setdefault(hidden["argumentPosition"], 0)
                expected = re.sub(
                    r"\{(arg\d+)\}",
                    lambda match: str(substitutions[match.group(1)]),
                    message["defaultMessage"],
                )
                results.append(
                    {
                        "message": f"{kind}.{suffix}",
                        "presentationWidth": int(width),
                        "arguments": [
                            positions[index] for index in range(1, max(positions) + 1)
                        ],
                        "expected": expected,
                    }
                )
    return results


def main() -> None:
    original = definitions()
    localized = definitions(True)
    source = plistlib.dumps(original, fmt=plistlib.FMT_XML, sort_keys=False).decode()
    target = plistlib.dumps(localized, fmt=plistlib.FMT_XML, sort_keys=False).decode()
    source_path = APPLE / f"{STEM}.stringsdict"
    source_path.write_text(source, encoding="utf-8")
    (APPLE / f"{STEM}.localized.stringsdict").write_text(target, encoding="utf-8")
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        normalized_width_xml(original), encoding="utf-8"
    )
    write_json(APPLE / f"{STEM}.compiled.json", original)
    write_json(APPLE / f"{STEM}.localized.compiled.json", localized)
    expected = catalog(original)
    write_json(APPLE / f"{STEM}.expected.json", expected)
    write_json(APPLE / f"{STEM}.translations.json", translations())
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            slots(source, encoding=encoding),
        )

    executable = shutil.which("plutil")
    if executable is None:
        raise SystemExit(
            "Apple plutil is required for presentation-width binary fixtures"
        )
    with tempfile.TemporaryDirectory(prefix="mojito-width-hidden-binary-") as directory:
        binary = Path(directory) / "resource.binary"
        subprocess.run(
            [executable, "-convert", "binary1", "-o", str(binary), str(source_path)],
            check=True,
        )
        encoded = binary.read_bytes().hex()
    (APPLE / f"{STEM}.binary.hex").write_text(
        "\n".join(encoded[index : index + 64] for index in range(0, len(encoded), 64))
        + "\n",
        encoding="ascii",
    )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    prefix = "apple-foundation-presentation-width-hidden-native-argument-positions"
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(prefix)
    ]
    common_case = {
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "expected": f"fixtures/apple/{STEM}.expected.json",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleStringsdictNormalized": f"fixtures/apple/{STEM}.normalized.stringsdict",
    }
    manifest["cases"].extend(
        [
            {
                "id": prefix + "-xml",
                **common_case,
                "okapi": {
                    "policy": "different",
                    "assetPath": "en.lproj/Localizable.stringsdict",
                    "expected": f"fixtures/okapi/apple-{STEM}.json",
                    "reason": "Legacy extraction invents standalone width-threshold identities, omits device-owned width resources entirely, and cannot preserve hidden native argument positions.",
                },
            },
            {
                "id": prefix + "-binary",
                **common_case,
                "encoding": "BINARY_PLIST",
                "binaryFixture": f"fixtures/apple/{STEM}.binary.hex",
            },
        ]
    )
    skeleton_prefix = (
        "apple-source-skeleton-preserves-width-owned-hidden-foundation-arguments"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]
    common_skeleton = {
        "format": "apple_stringsdict",
        "appleAllVariationSlots": True,
        "appleDeviceWidthSlots": True,
        "appleWidthHiddenArgumentSlots": True,
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.stringsdict",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": runtime_samples(False),
        "appleLocalizedRuntimeSamples": runtime_samples(True),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": skeleton_prefix + "-xml",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                **common_skeleton,
            },
            {
                "id": skeleton_prefix + "-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                **common_skeleton,
            },
        ]
    )
    legacy = [
        {
            "name": f"width.{suffix}_NSStringVariableWidthRuleType_{width}",
            "source": native(pattern, width, None, False),
        }
        for suffix, pattern in PATTERNS.items()
        for width in WIDTHS
    ]
    write_json(
        ROOT / "fixtures" / "okapi" / f"apple-{STEM}.json",
        {"filterConfigId": "okf_macStringdict@mojito", "units": legacy},
    )
    missing = [
        {"category": "missing_legacy", "id": identifier, "count": 1}
        for identifier in sorted(expected["messages"])
    ]
    unexpected = [
        {"category": "unexpected_legacy", "id": unit["name"], "count": 1}
        for unit in sorted(legacy, key=lambda value: value["name"])
    ]
    write_json(
        ROOT / "fixtures" / "shadow" / f"apple-{STEM}.json",
        {
            "sourceFormat": "apple_stringsdict",
            "canonicalUnits": len(missing),
            "legacyUnits": len(legacy),
            "outcome": "mismatch",
            "differences": missing + unexpected,
        },
    )
    shadow_id = "shadow-apple-presentation-width-hidden-foundation-arguments"
    manifest["shadowComparisons"] = [
        case for case in manifest["shadowComparisons"] if case["id"] != shadow_id
    ]
    manifest["shadowComparisons"].append(
        {
            "id": shadow_id,
            "case": prefix + "-xml",
            "expected": f"fixtures/shadow/apple-{STEM}.json",
        }
    )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
