#!/usr/bin/env python3
"""Generate original Foundation device/width zero-width conversion contracts."""

from __future__ import annotations

import html
import json
import plistlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "stringsdict-disabled-variations"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def xml(value: str) -> str:
    return html.escape(value, quote=False).replace("\n", "&#10;")


def source_xml(values: dict[str, dict[str, dict[str, str]]]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0"><dict>',
    ]
    for identifier, message in values.items():
        lines.append(f"  <key>{xml(identifier)}</key><dict>")
        for rule, branches in message.items():
            lines.append(f"    <key>{rule}</key><dict>")
            for branch, value in branches.items():
                lines.append(
                    f"      <key>{xml(branch)}</key><string>{xml(value)}</string>"
                )
            lines.append("    </dict>")
        lines.append("  </dict>")
    lines.extend(["</dict></plist>", ""])
    return "\n".join(lines)


def normalized_xml(values: dict[str, dict[str, dict[str, str]]]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]
    for identifier in sorted(values):
        lines.extend([f"  <key>{xml(identifier)}</key>", "  <dict>"])
        for rule in (
            "NSStringVariableWidthRuleType",
            "NSStringDeviceSpecificRuleType",
        ):
            if rule not in values[identifier]:
                continue
            branches = values[identifier][rule]
            ordering = (
                sorted(branches, key=lambda key: (int(key), key))
                if rule == "NSStringVariableWidthRuleType"
                else sorted(branches)
            )
            lines.extend([f"    <key>{rule}</key>", "    <dict>"])
            for branch in ordering:
                lines.extend(
                    [
                        f"      <key>{xml(branch)}</key>",
                        f"      <string>{html.escape(branches[branch], quote=False)}</string>",
                    ]
                )
            lines.append("    </dict>")
        lines.append("  </dict>")
    lines.extend(["</dict>", "</plist>", ""])
    return "\n".join(lines)


def main() -> None:
    values = {
        "harbor.device.🧭": {
            "NSStringDeviceSpecificRuleType": {
                "iphone": "Touch %@%n quay",
                "mac": "Click %@%n protected dock",
                "applewatch": "Watch %@ protected pier",
            }
        },
        "harbor.device.literal": {
            "NSStringDeviceSpecificRuleType": {
                "iphone": "Touch %@%%n quay",
                "mac": "Click %@%%n protected dock",
            }
        },
        "harbor.width": {
            "NSStringVariableWidthRuleType": {
                "8": "Near%n",
                "040": "Follow%n%n the open tide",
                "80": "Follow%n%n the wider shoreline",
            }
        },
        "harbor.width.line": {
            "NSStringVariableWidthRuleType": {
                "5": "Low\ntide",
                "18": "Clear\nwater ahead",
            }
        },
        "harbor.width.literal": {
            "NSStringVariableWidthRuleType": {
                "4": "A%%n",
                "12": "A%%n sheltered bay",
            }
        },
    }
    source = source_xml(values)
    path = APPLE / f"{STEM}.stringsdict"
    path.write_text(source, encoding="utf-8")
    write_json(APPLE / f"{STEM}.compiled.json", plistlib.loads(source.encode()))
    normalized = normalized_xml(values)
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(normalized, encoding="utf-8")

    descriptor = {
        "harbor.device.🧭": {
            "defaultMessage": "Touch {arg0} quay",
            "placeholders": [
                {"name": "arg0", "source": "%@", "kind": "string", "position": 1}
            ],
            "metadata": {
                "appleDisabledPrintfConversions": [{"position": 12, "source": "%n"}],
                "defaultDevice": "iphone",
                "deviceVariants": values["harbor.device.🧭"][
                    "NSStringDeviceSpecificRuleType"
                ],
            },
        },
        "harbor.device.literal": {
            "defaultMessage": "Touch {arg0}%n quay",
            "placeholders": [
                {"name": "arg0", "source": "%@", "kind": "string", "position": 1}
            ],
            "metadata": {
                "defaultDevice": "iphone",
                "deviceVariants": values["harbor.device.literal"][
                    "NSStringDeviceSpecificRuleType"
                ],
            },
        },
        "harbor.width": {
            "defaultMessage": "Follow the wider shoreline",
            "metadata": {
                "appleDisabledPrintfConversions": [
                    {"position": 6, "source": "%n"},
                    {"position": 6, "source": "%n"},
                ],
                "defaultWidth": 80,
                "widthVariants": values["harbor.width"][
                    "NSStringVariableWidthRuleType"
                ],
            },
        },
        "harbor.width.line": {
            "defaultMessage": "Clear\nwater ahead",
            "metadata": {
                "defaultWidth": 18,
                "widthVariants": values["harbor.width.line"][
                    "NSStringVariableWidthRuleType"
                ],
            },
        },
        "harbor.width.literal": {
            "defaultMessage": "A%n sheltered bay",
            "metadata": {
                "defaultWidth": 12,
                "widthVariants": values["harbor.width.literal"][
                    "NSStringVariableWidthRuleType"
                ],
            },
        },
    }
    write_json(
        APPLE / f"{STEM}.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_stringsdict",
            "messages": {key: descriptor[key] for key in sorted(descriptor)},
        },
    )

    translations = {
        "harbor.device.🧭": "Touchez {arg0} quai tranquille",
        "harbor.device.literal": "Touchez {arg0}%n quai",
        "harbor.width": "Suivez la vaste rive claire",
        "harbor.width.line": "Eau\ncalme devant",
        "harbor.width.literal": "A%n baie calme",
    }
    native = {
        "harbor.device.🧭": "Touchez %@%n quai tranquille",
        "harbor.device.literal": "Touchez %@%%n quai",
        "harbor.width": "Suivez%n%n la vaste rive claire",
        "harbor.width.line": "Eau\ncalme devant",
        "harbor.width.literal": "A%%n baie calme",
    }
    selected = {
        "harbor.device.🧭": "Touch %@%n quay",
        "harbor.device.literal": "Touch %@%%n quay",
        "harbor.width": "Follow%n%n the wider shoreline",
        "harbor.width.line": "Clear\nwater ahead",
        "harbor.width.literal": "A%%n sheltered bay",
    }
    slots = []
    replacements = []
    for identifier, original in selected.items():
        escaped_original = xml(original)
        pattern = re.compile(r"<string>(" + re.escape(escaped_original) + r")</string>")
        match = pattern.search(source)
        if match is None:
            raise RuntimeError(f"Missing selected Foundation branch for {identifier}")
        slots.append(
            {
                "id": identifier,
                "start": len(source[: match.start(1)].encode()),
                "end": len(source[: match.end(1)].encode()),
            }
        )
        replacements.append((match.start(1), match.end(1), xml(native[identifier])))
    slots.sort(key=lambda slot: slot["start"])
    write_json(
        APPLE / f"{STEM}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_stringsdict",
            "encoding": "UTF-8",
            "source": source,
            "slots": slots,
        },
    )
    write_json(APPLE / f"{STEM}.translations.json", translations)
    localized = source
    for start, end, replacement in sorted(replacements, reverse=True):
        localized = localized[:start] + replacement + localized[end:]
    (APPLE / f"{STEM}.localized.stringsdict").write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json",
        plistlib.loads(localized.encode()),
    )

    branch_translations = {
        "harbor.device.🧭#@device#iphone": "Touchez {arg0} quai tranquille",
        "harbor.device.🧭#@device#mac": "Cliquez {arg0} quai profond",
        "harbor.device.🧭#@device#applewatch": "Suivez {arg0} montre",
        "harbor.device.literal#@device#iphone": "Touchez {arg0}%n quai",
        "harbor.device.literal#@device#mac": "Cliquez {arg0}%n port",
        "harbor.width#@width#8": "Près",
        "harbor.width#@width#040": "Suivez la rive calme",
        "harbor.width#@width#80": "Suivez la vaste rive claire",
        "harbor.width.line#@width#5": "Bas\nquai",
        "harbor.width.line#@width#18": "Eau\ncalme devant",
        "harbor.width.literal#@width#4": "B%n",
        "harbor.width.literal#@width#12": "B%n baie calme",
    }
    branch_native = {
        "harbor.device.🧭#@device#iphone": "Touchez %@%n quai tranquille",
        "harbor.device.🧭#@device#mac": "Cliquez %@%n quai profond",
        "harbor.device.🧭#@device#applewatch": "Suivez %@ montre",
        "harbor.device.literal#@device#iphone": "Touchez %@%%n quai",
        "harbor.device.literal#@device#mac": "Cliquez %@%%n port",
        "harbor.width#@width#8": "Près%n",
        "harbor.width#@width#040": "Suivez%n%n la rive calme",
        "harbor.width#@width#80": "Suivez%n%n la vaste rive claire",
        "harbor.width.line#@width#5": "Bas\nquai",
        "harbor.width.line#@width#18": "Eau\ncalme devant",
        "harbor.width.literal#@width#4": "B%%n",
        "harbor.width.literal#@width#12": "B%%n baie calme",
    }
    all_slots = []
    all_replacements = []
    cursor = 0
    for identifier, message in values.items():
        for rule, branches in message.items():
            selector = (
                "@device" if rule == "NSStringDeviceSpecificRuleType" else "@width"
            )
            for branch, original in branches.items():
                escaped_original = xml(original)
                pattern = re.compile(
                    r"<string>(" + re.escape(escaped_original) + r")</string>"
                )
                match = pattern.search(source, cursor)
                if match is None:
                    raise RuntimeError(
                        f"Missing Foundation branch {identifier}/{branch}"
                    )
                identity = f"{identifier}#{selector}#{branch}"
                all_slots.append(
                    {
                        "id": identifier,
                        "selector": selector,
                        "variant": branch,
                        "start": len(source[: match.start(1)].encode()),
                        "end": len(source[: match.end(1)].encode()),
                    }
                )
                all_replacements.append(
                    (match.start(1), match.end(1), xml(branch_native[identity]))
                )
                cursor = match.end()
    write_json(
        APPLE / f"{STEM}.all.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_stringsdict",
            "encoding": "UTF-8",
            "source": source,
            "slots": all_slots,
        },
    )
    write_json(APPLE / f"{STEM}.all.translations.json", branch_translations)
    all_localized = source
    for start, end, replacement in reversed(all_replacements):
        all_localized = all_localized[:start] + replacement + all_localized[end:]
    (APPLE / f"{STEM}.all.localized.stringsdict").write_text(
        all_localized, encoding="utf-8"
    )
    write_json(
        APPLE / f"{STEM}.all.localized.compiled.json",
        plistlib.loads(all_localized.encode()),
    )


if __name__ == "__main__":
    main()
