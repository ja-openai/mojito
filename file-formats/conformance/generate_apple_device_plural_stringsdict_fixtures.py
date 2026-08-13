#!/usr/bin/env python3
"""Generate original Foundation device-specific plural source contracts."""

from __future__ import annotations

import html
import json
import plistlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
STEM = "stringsdict-device-plural"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def normalized_xml(values: dict[str, object]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]

    def element(depth: int, tag: str, text: str) -> None:
        lines.append("  " * depth + f"<{tag}>{html.escape(text, quote=False)}</{tag}>")

    def dictionary(depth: int, fields: dict[str, object]) -> None:
        lines.append("  " * depth + "<dict>")
        for key, value in sorted(fields.items()):
            element(depth + 1, "key", key)
            if isinstance(value, dict):
                dictionary(depth + 1, value)
            elif isinstance(value, str):
                element(depth + 1, "string", value)
            else:
                raise RuntimeError(f"Unsupported Foundation fixture value for {key}")
        lines.append("  " * depth + "</dict>")

    for identifier, message in sorted(values.items()):
        element(1, "key", identifier)
        dictionary(1, message)
    lines.extend(["</dict>", "</plist>", ""])
    return "\n".join(lines)


def main() -> None:
    values = {
        "iphone": {"one": "%lld mobile lantern", "other": "%lld mobile lanterns"},
        "mac": {"one": "%lld desktop beacon", "other": "%lld desktop beacons"},
    }
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0"><dict>',
        "  <key>neutral.harbor🧭</key><dict>",
        "    <key>NSStringDeviceSpecificRuleType</key><dict>",
    ]
    for device, branches in values.items():
        lines.extend(
            [
                f"      <key>{device}</key><dict>",
                "        <key>NSStringLocalizedFormatKey</key><string>%#@lights@</string>",
                "        <key>lights</key><dict>",
                "          <key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>",
                "          <key>NSStringFormatValueTypeKey</key><string>lld</string>",
            ]
        )
        for category, value in branches.items():
            lines.append(f"          <key>{category}</key><string>{value}</string>")
        lines.extend(["        </dict>", "      </dict>"])
    lines.extend(["    </dict>", "  </dict>", "</dict></plist>", ""])
    source = "\n".join(lines)
    path = APPLE / f"{STEM}.stringsdict"
    path.write_text(source, encoding="utf-8")
    native = plistlib.loads(source.encode())
    write_json(APPLE / f"{STEM}.compiled.json", native)
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        normalized_xml(native), encoding="utf-8"
    )

    selected = native["neutral.harbor🧭"]["NSStringDeviceSpecificRuleType"]["iphone"]
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": {
            "neutral.harbor🧭": {
                "defaultMessage": "{lights, plural, one {{lights} mobile lantern} other {{lights} mobile lanterns}}",
                "variants": {
                    "one": "{lights} mobile lantern",
                    "other": "{lights} mobile lanterns",
                },
                "placeholders": [
                    {
                        "name": "lights",
                        "source": "%lld",
                        "kind": "integer",
                        "position": 1,
                    }
                ],
                "metadata": {
                    "appleLocalizedFormat": "%#@lights@",
                    "applePluralRules": {
                        "lights": {
                            "valueType": "lld",
                            "variants": {
                                category: selected["lights"][category]
                                for category in ("one", "other")
                            },
                        }
                    },
                    "pluralVariable": "lights",
                    "valueType": "lld",
                    "defaultDevice": "iphone",
                    "devicePluralVariants": native["neutral.harbor🧭"][
                        "NSStringDeviceSpecificRuleType"
                    ],
                },
            }
        },
    }
    write_json(APPLE / f"{STEM}.expected.json", expected)

    translations = {
        "neutral.harbor🧭#@device=iphone#one": "{lights} lanterne mobile",
        "neutral.harbor🧭#@device=iphone#other": "{lights} lanternes mobiles",
        "neutral.harbor🧭#@device=mac#one": "{lights} balise bureau",
        "neutral.harbor🧭#@device=mac#other": "{lights} balises bureau",
    }
    localized_native = {
        "iphone": {"one": "%lld lanterne mobile", "other": "%lld lanternes mobiles"},
        "mac": {"one": "%lld balise bureau", "other": "%lld balises bureau"},
    }
    slots = []
    replacements = []
    cursor = 0
    for device, branches in values.items():
        for category, value in branches.items():
            match = re.search(
                r"<string>(" + re.escape(html.escape(value)) + r")</string>",
                source[cursor:],
            )
            if match is None:
                raise RuntimeError(f"Missing {device}/{category} source")
            start = cursor + match.start(1)
            end = cursor + match.end(1)
            cursor = cursor + match.end()
            slots.append(
                {
                    "id": "neutral.harbor🧭",
                    "selector": f"@device={device}",
                    "variant": category,
                    "start": len(source[:start].encode()),
                    "end": len(source[:end].encode()),
                }
            )
            replacements.append((start, end, localized_native[device][category]))
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        owned = []
        for slot in slots:
            if encoding == "UTF-8":
                start, end = slot["start"], slot["end"]
            else:
                prefix = source.encode()[: slot["start"]].decode()
                value = source.encode()[slot["start"] : slot["end"]].decode()
                start = 2 + len(prefix.encode("utf-16-le"))
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
    for start, end, value in reversed(replacements):
        localized = localized[:start] + value + localized[end:]
    (APPLE / f"{STEM}.localized.stringsdict").write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", plistlib.loads(localized.encode())
    )

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    case_id = "apple-stringsdict-native-device-owned-plural-dictionaries"
    skeleton_id = (
        "apple-stringsdict-source-skeleton-translates-device-owned-plural-branches"
    )
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != case_id]
    manifest["cases"].append(
        {
            "id": case_id,
            "format": "apple_stringsdict",
            "input": f"fixtures/apple/{STEM}.stringsdict",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "appleStringsdictNormalized": f"fixtures/apple/{STEM}.normalized.stringsdict",
        }
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if case["id"] not in {skeleton_id, skeleton_id + "-utf16le"}
    ]
    case = {
        "id": skeleton_id,
        "format": "apple_stringsdict",
        "appleAllVariationSlots": True,
        "appleDevicePluralSlots": True,
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.stringsdict",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": [
            {
                "message": "neutral.harbor🧭",
                "arguments": [1],
                "expected": "1 desktop beacon",
            },
            {
                "message": "neutral.harbor🧭",
                "arguments": [3],
                "expected": "3 desktop beacons",
            },
        ],
        "appleLocalizedRuntimeSamples": [
            {
                "message": "neutral.harbor🧭",
                "arguments": [1],
                "expected": "1 balise bureau",
            },
            {
                "message": "neutral.harbor🧭",
                "arguments": [3],
                "expected": "3 balises bureau",
            },
        ],
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
