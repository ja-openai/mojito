#!/usr/bin/env python3
"""Generate native-safe Foundation device-owned presentation-width fixtures."""

from __future__ import annotations

import html
import json
import plistlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
STEM = "stringsdict-device-width"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def escape(value: str) -> str:
    return html.escape(value, quote=False).replace("\n", "&#10;")


def normalized_xml(values: dict[str, object]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]

    def dictionary(depth: int, fields: dict[str, object]) -> None:
        lines.append("  " * depth + "<dict>")
        for key, value in sorted(fields.items()):
            lines.append("  " * (depth + 1) + f"<key>{escape(key)}</key>")
            if isinstance(value, dict):
                dictionary(depth + 1, value)
            elif isinstance(value, str):
                lines.append("  " * (depth + 1) + f"<string>{value}</string>")
            else:
                raise RuntimeError(f"Unsupported Foundation value at {key}")
        lines.append("  " * depth + "</dict>")

    for identifier, message in sorted(values.items()):
        lines.append(f"  <key>{escape(identifier)}</key>")
        dictionary(1, message)
    lines.extend(["</dict>", "</plist>", ""])
    return "\n".join(lines)


def main() -> None:
    values = {
        "iphone": {"5": "Tap%n cove", "040": "Tap%%n open tide"},
        "mac": {"5": "Mac%n near", "040": "Mac\nopen coast"},
    }
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0"><dict>',
        "  <key>neutral.width🧭</key><dict>",
        "    <key>NSStringDeviceSpecificRuleType</key><dict>",
    ]
    for device, widths in values.items():
        lines.extend(
            [
                f"      <key>{device}</key><dict>",
                "        <key>NSStringVariableWidthRuleType</key><dict>",
            ]
        )
        for width, text in widths.items():
            lines.append(f"          <key>{width}</key><string>{escape(text)}</string>")
        lines.extend(["        </dict>", "      </dict>"])
    lines.extend(["    </dict>", "  </dict>", "</dict></plist>", ""])
    source = "\n".join(lines)
    source_path = APPLE / f"{STEM}.stringsdict"
    source_path.write_text(source, encoding="utf-8")
    native = plistlib.loads(source.encode())
    write_json(APPLE / f"{STEM}.compiled.json", native)
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        normalized_xml(native), encoding="utf-8"
    )

    expected = {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": {
            "neutral.width🧭": {
                "defaultMessage": "Tap%n open tide",
                "metadata": {
                    "widthVariants": values["iphone"],
                    "defaultWidth": 40,
                    "defaultWidthKey": "040",
                    "defaultDevice": "iphone",
                    "deviceWidthVariants": native["neutral.width🧭"][
                        "NSStringDeviceSpecificRuleType"
                    ],
                },
            }
        },
    }
    write_json(APPLE / f"{STEM}.expected.json", expected)

    translations = {
        "neutral.width🧭#@device=iphone#5": "Sud quai",
        "neutral.width🧭#@device=iphone#040": "Sud%n vaste rive",
        "neutral.width🧭#@device=mac#5": "Sud doux",
        "neutral.width🧭#@device=mac#040": "Sud\nouest calme",
    }
    localized_native = {
        "iphone": {"5": "Sud%n quai", "040": "Sud%%n vaste rive"},
        "mac": {"5": "Sud%n doux", "040": "Sud\nouest calme"},
    }
    slots = []
    replacements = []
    cursor = 0
    for device, widths in values.items():
        for width, text in widths.items():
            match = re.search(
                r"<string>(" + re.escape(escape(text)) + r")</string>",
                source[cursor:],
            )
            if match is None:
                raise RuntimeError(f"Missing {device}/{width} source")
            start = cursor + match.start(1)
            end = cursor + match.end(1)
            cursor += match.end()
            slots.append(
                {
                    "id": "neutral.width🧭",
                    "selector": f"@device={device}",
                    "variant": width,
                    "start": len(source[:start].encode()),
                    "end": len(source[:end].encode()),
                }
            )
            replacements.append((start, end, escape(localized_native[device][width])))
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        owned = []
        for slot in slots:
            if encoding == "UTF-8":
                start, end = slot["start"], slot["end"]
            else:
                before = source.encode()[: slot["start"]].decode()
                value = source.encode()[slot["start"] : slot["end"]].decode()
                start = 2 + len(before.encode("utf-16-le"))
                end = start + len(value.encode("utf-16-le"))
            owned.append({**slot, "start": start, "end": end})
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "apple_stringsdict",
                "encoding": encoding,
                "source": source,
                "slots": owned,
            },
        )
    write_json(APPLE / f"{STEM}.translations.json", translations)
    localized = source
    for start, end, replacement in reversed(replacements):
        localized = localized[:start] + replacement + localized[end:]
    (APPLE / f"{STEM}.localized.stringsdict").write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", plistlib.loads(localized.encode())
    )

    reverse = {
        "neutral.unsafe.width": {
            "NSStringVariableWidthRuleType": {
                "5": {
                    "NSStringDeviceSpecificRuleType": {
                        "iphone": "Touch near",
                        "mac": "Click near",
                    }
                }
            }
        }
    }
    reverse_path = APPLE / f"{STEM}.unsafe-reversed.stringsdict"
    reverse_path.write_bytes(
        plistlib.dumps(reverse, fmt=plistlib.FMT_XML, sort_keys=False)
    )

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    valid_id = "apple-stringsdict-native-device-owned-padded-width-dictionaries"
    unsafe_id = (
        "apple-stringsdict-rejects-foundation-crashing-width-owned-device-dictionaries"
    )
    manifest["cases"] = [
        case for case in manifest["cases"] if case["id"] not in {valid_id, unsafe_id}
    ]
    manifest["cases"].extend(
        [
            {
                "id": valid_id,
                "format": "apple_stringsdict",
                "input": f"fixtures/apple/{STEM}.stringsdict",
                "expected": f"fixtures/apple/{STEM}.expected.json",
                "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
                "appleStringsdictNormalized": f"fixtures/apple/{STEM}.normalized.stringsdict",
            },
            {
                "id": unsafe_id,
                "format": "apple_stringsdict",
                "input": f"fixtures/apple/{STEM}.unsafe-reversed.stringsdict",
                "error": "INVALID_APPLE_STRINGSDICT",
                "appleOracle": "accept",
            },
        ]
    )
    skeleton_id = (
        "apple-stringsdict-source-skeleton-translates-device-owned-width-thresholds"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if case["id"] not in {skeleton_id, skeleton_id + "-utf16le"}
    ]
    samples = [
        {
            "message": "neutral.width🧭",
            "presentationWidth": width,
            "arguments": [],
            "expected": expected,
        }
        for width, expected in [
            (0, "Mac near"),
            (5, "Mac near"),
            (12, "Mac near"),
            (40, "Mac\nopen coast"),
        ]
    ]
    translated_samples = [
        {
            "message": "neutral.width🧭",
            "presentationWidth": width,
            "arguments": [],
            "expected": expected,
        }
        for width, expected in [
            (0, "Sud doux"),
            (5, "Sud doux"),
            (12, "Sud doux"),
            (40, "Sud\nouest calme"),
        ]
    ]
    case = {
        "id": skeleton_id,
        "format": "apple_stringsdict",
        "appleAllVariationSlots": True,
        "appleDeviceWidthSlots": True,
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.stringsdict",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": samples,
        "appleLocalizedRuntimeSamples": translated_samples,
    }
    manifest["sourceSkeletons"].append(case)
    manifest["sourceSkeletons"].append(
        {
            **case,
            "id": skeleton_id + "-utf16le",
            "encoding": "UTF-16LE-BOM",
            "expected": f"fixtures/apple/{STEM}.utf16le.expected.skeleton.json",
        }
    )
    MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
