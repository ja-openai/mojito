#!/usr/bin/env python3
"""Generate original Foundation mixed-shape device source contracts."""

from __future__ import annotations

import html
import json
import plistlib
import re
from pathlib import Path

from generate_apple_device_plural_stringsdict_fixtures import normalized_xml


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
STEM = "stringsdict-device-mixed"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def plural(one: str, other: str) -> dict[str, object]:
    return {
        "NSStringLocalizedFormatKey": "%#@lights@",
        "lights": {
            "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
            "NSStringFormatValueTypeKey": "lld",
            "one": one,
            "other": other,
        },
    }


def main() -> None:
    scalar_plural = "neutral.scalar-plural🧭"
    plural_scalar = "neutral.plural-scalar🧭"
    scalar_width = "neutral.scalar-width🧭"
    width_scalar = "neutral.width-scalar🧭"
    plural_width = "neutral.plural-width🧭"
    width_plural = "neutral.width-plural🧭"
    three_shapes = "neutral.three-shapes🧭"
    entries: dict[str, dict[str, object]] = {
        scalar_plural: {
            "iphone": "Tap mobile coast",
            "mac": plural("%lld desktop beacon", "%lld desktop beacons"),
        },
        plural_scalar: {
            "iphone": plural("%lld mobile lamp", "%lld mobile lamps"),
            "mac": "Click desktop pier",
        },
        scalar_width: {
            "iphone": "Tap mobile quay",
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "Mac%n near",
                    "040": "Mac\nopen coast",
                }
            },
        },
        width_scalar: {
            "iphone": {
                "NSStringVariableWidthRuleType": {
                    "5": "Tap%n cove",
                    "040": "Tap%%n open tide",
                }
            },
            "mac": "Click desktop harbor",
        },
        plural_width: {
            "iphone": plural("%lld mobile signal", "%lld mobile signals"),
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "West%n cove",
                    "040": "West\nopen shore",
                }
            },
        },
        width_plural: {
            "iphone": {
                "NSStringVariableWidthRuleType": {
                    "5": "East%n point",
                    "040": "East%%n open bay",
                }
            },
            "mac": plural("%lld desktop buoy", "%lld desktop buoys"),
        },
        three_shapes: {
            "iphone": plural("%lld mobile trail", "%lld mobile trails"),
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "Bay%n near",
                    "040": "Bay\nopen water",
                }
            },
            "other": "Plain fallback harbor",
        },
    }
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0"><dict>',
    ]

    def emit_mapping(depth: int, fields: dict[str, object]) -> None:
        for key, value in fields.items():
            escaped_key = html.escape(key, quote=False)
            prefix = "  " * depth + f"<key>{escaped_key}</key>"
            if isinstance(value, dict):
                lines.append(prefix + "<dict>")
                emit_mapping(depth + 1, value)
                lines.append("  " * depth + "</dict>")
            elif isinstance(value, str):
                lines.append(
                    prefix + f"<string>{html.escape(value, quote=False)}</string>"
                )
            else:
                raise RuntimeError(f"Invalid mixed Foundation fixture: {key}")

    emit_mapping(
        1,
        {
            key: {"NSStringDeviceSpecificRuleType": devices}
            for key, devices in entries.items()
        },
    )
    lines.extend(["</dict></plist>", ""])
    source = "\n".join(lines)
    (APPLE / f"{STEM}.stringsdict").write_text(source, encoding="utf-8")
    native = plistlib.loads(source.encode())
    write_json(APPLE / f"{STEM}.compiled.json", native)
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        normalized_xml(native), encoding="utf-8"
    )

    plural_iphone = entries[plural_scalar]["iphone"]
    if not isinstance(plural_iphone, dict):
        raise RuntimeError("Missing native iPhone plural")
    plural_rule = plural_iphone["lights"]
    if not isinstance(plural_rule, dict):
        raise RuntimeError("Missing native plural rule")
    widths = entries[width_scalar]["iphone"]
    if not isinstance(widths, dict):
        raise RuntimeError("Missing native iPhone widths")
    iphone_widths = widths["NSStringVariableWidthRuleType"]
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": {
            scalar_plural: {
                "defaultMessage": "Tap mobile coast",
                "metadata": {
                    "deviceVariants": {"iphone": "Tap mobile coast"},
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[scalar_plural],
                },
            },
            plural_scalar: {
                "defaultMessage": (
                    "{lights, plural, one {{lights} mobile lamp} "
                    "other {{lights} mobile lamps}}"
                ),
                "variants": {
                    "one": "{lights} mobile lamp",
                    "other": "{lights} mobile lamps",
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
                                "one": plural_rule["one"],
                                "other": plural_rule["other"],
                            },
                        }
                    },
                    "pluralVariable": "lights",
                    "valueType": "lld",
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[plural_scalar],
                },
            },
            scalar_width: {
                "defaultMessage": "Tap mobile quay",
                "metadata": {
                    "deviceVariants": {"iphone": "Tap mobile quay"},
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[scalar_width],
                },
            },
            width_scalar: {
                "defaultMessage": "Tap%n open tide",
                "metadata": {
                    "widthVariants": iphone_widths,
                    "defaultWidth": 40,
                    "defaultWidthKey": "040",
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[width_scalar],
                },
            },
            plural_width: {
                "defaultMessage": (
                    "{lights, plural, one {{lights} mobile signal} "
                    "other {{lights} mobile signals}}"
                ),
                "variants": {
                    "one": "{lights} mobile signal",
                    "other": "{lights} mobile signals",
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
                                "one": "%lld mobile signal",
                                "other": "%lld mobile signals",
                            },
                        }
                    },
                    "pluralVariable": "lights",
                    "valueType": "lld",
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[plural_width],
                },
            },
            width_plural: {
                "defaultMessage": "East%n open bay",
                "metadata": {
                    "widthVariants": {
                        "5": "East%n point",
                        "040": "East%%n open bay",
                    },
                    "defaultWidth": 40,
                    "defaultWidthKey": "040",
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[width_plural],
                },
            },
            three_shapes: {
                "defaultMessage": (
                    "{lights, plural, one {{lights} mobile trail} "
                    "other {{lights} mobile trails}}"
                ),
                "variants": {
                    "one": "{lights} mobile trail",
                    "other": "{lights} mobile trails",
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
                                "one": "%lld mobile trail",
                                "other": "%lld mobile trails",
                            },
                        }
                    },
                    "pluralVariable": "lights",
                    "valueType": "lld",
                    "defaultDevice": "iphone",
                    "deviceMixedVariants": entries[three_shapes],
                },
            },
        },
    }
    write_json(APPLE / f"{STEM}.expected.json", expected)

    translations = {
        f"{scalar_plural}#@device#iphone": "Touchez & la rive",
        f"{scalar_plural}#@device=mac#one": "{lights} balise bureau",
        f"{scalar_plural}#@device=mac#other": "{lights} balises bureau",
        f"{plural_scalar}#@device=iphone#one": "{lights} lampe mobile",
        f"{plural_scalar}#@device=iphone#other": "{lights} lampes mobiles",
        f"{plural_scalar}#@device#mac": "Cliquez sur la jetée",
        f"{scalar_width}#@device#iphone": "Touchez le quai",
        f"{scalar_width}#@device=mac#5": "Sud doux",
        f"{scalar_width}#@device=mac#040": "Sud\nouest calme",
        f"{width_scalar}#@device=iphone#5": "Est quai",
        f"{width_scalar}#@device=iphone#040": "Nord%n vaste rive",
        f"{width_scalar}#@device#mac": "Cliquez sur le port",
        f"{plural_width}#@device=iphone#one": "{lights} signal mobile",
        f"{plural_width}#@device=iphone#other": "{lights} signaux mobiles",
        f"{plural_width}#@device=mac#5": "Nord quai",
        f"{plural_width}#@device=mac#040": "Sud\nrive ouverte",
        f"{width_plural}#@device=iphone#5": "Nord pointe",
        f"{width_plural}#@device=iphone#040": "Nord%n baie vaste",
        f"{width_plural}#@device=mac#one": "{lights} bouée bureau",
        f"{width_plural}#@device=mac#other": "{lights} bouées bureau",
        f"{three_shapes}#@device=iphone#one": "{lights} sentier mobile",
        f"{three_shapes}#@device=iphone#other": "{lights} sentiers mobiles",
        f"{three_shapes}#@device=mac#5": "Cap doux",
        f"{three_shapes}#@device=mac#040": "Cap\neau claire",
        f"{three_shapes}#@device#other": "Port de repli",
    }
    localized = {
        scalar_plural: {
            "iphone": "Touchez & la rive",
            "mac": plural("%lld balise bureau", "%lld balises bureau"),
        },
        plural_scalar: {
            "iphone": plural("%lld lampe mobile", "%lld lampes mobiles"),
            "mac": "Cliquez sur la jetée",
        },
        scalar_width: {
            "iphone": "Touchez le quai",
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "Sud%n doux",
                    "040": "Sud\nouest calme",
                }
            },
        },
        width_scalar: {
            "iphone": {
                "NSStringVariableWidthRuleType": {
                    "5": "Est%n quai",
                    "040": "Nord%%n vaste rive",
                }
            },
            "mac": "Cliquez sur le port",
        },
        plural_width: {
            "iphone": plural("%lld signal mobile", "%lld signaux mobiles"),
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "Nord%n quai",
                    "040": "Sud\nrive ouverte",
                }
            },
        },
        width_plural: {
            "iphone": {
                "NSStringVariableWidthRuleType": {
                    "5": "Nord%n pointe",
                    "040": "Nord%%n baie vaste",
                }
            },
            "mac": plural("%lld bouée bureau", "%lld bouées bureau"),
        },
        three_shapes: {
            "iphone": plural("%lld sentier mobile", "%lld sentiers mobiles"),
            "mac": {
                "NSStringVariableWidthRuleType": {
                    "5": "Cap%n doux",
                    "040": "Cap\neau claire",
                }
            },
            "other": "Port de repli",
        },
    }
    slots: list[dict[str, object]] = []
    replacements: list[tuple[int, int, str]] = []
    cursor = 0

    def add_slot(
        identifier: str,
        selector: str,
        variant: str,
        value: str,
        translated: str,
    ) -> None:
        nonlocal cursor
        match = re.search(
            r"<string>(" + re.escape(html.escape(value, quote=False)) + r")</string>",
            source[cursor:],
        )
        if match is None:
            raise RuntimeError(
                f"Missing mixed source: {identifier}/{selector}/{variant}"
            )
        start = cursor + match.start(1)
        end = cursor + match.end(1)
        cursor += match.end()
        slots.append(
            {
                "id": identifier,
                "selector": selector,
                "variant": variant,
                "start": len(source[:start].encode()),
                "end": len(source[:end].encode()),
            }
        )
        replacements.append(
            (start, end, html.escape(translated, quote=False).replace("\n", "&#10;"))
        )

    for identifier, devices in entries.items():
        for device, branch in devices.items():
            translated_branch = localized[identifier][device]
            if isinstance(branch, str) and isinstance(translated_branch, str):
                add_slot(identifier, "@device", device, branch, translated_branch)
            elif isinstance(branch, dict) and isinstance(translated_branch, dict):
                if "NSStringVariableWidthRuleType" in branch:
                    widths = branch["NSStringVariableWidthRuleType"]
                    translated_widths = translated_branch[
                        "NSStringVariableWidthRuleType"
                    ]
                    if not isinstance(widths, dict) or not isinstance(
                        translated_widths, dict
                    ):
                        raise RuntimeError("Invalid mixed device width fixture")
                    for width, value in widths.items():
                        if not isinstance(value, str):
                            raise RuntimeError("Invalid mixed device width text")
                        add_slot(
                            identifier,
                            f"@device={device}",
                            width,
                            value,
                            translated_widths[width],
                        )
                else:
                    values = branch["lights"]
                    translated_values = translated_branch["lights"]
                    if not isinstance(values, dict) or not isinstance(
                        translated_values, dict
                    ):
                        raise RuntimeError("Invalid mixed device plural fixture")
                    for category in ("one", "other"):
                        add_slot(
                            identifier,
                            f"@device={device}",
                            category,
                            values[category],
                            translated_values[category],
                        )

    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        owned = []
        for slot in slots:
            start = slot["start"]
            end = slot["end"]
            if not isinstance(start, int) or not isinstance(end, int):
                raise RuntimeError("Invalid mixed byte offsets")
            if encoding != "UTF-8":
                before = source.encode()[:start].decode()
                value = source.encode()[start:end].decode()
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
    localized_source = source
    for start, end, value in reversed(replacements):
        localized_source = localized_source[:start] + value + localized_source[end:]
    (APPLE / f"{STEM}.localized.stringsdict").write_text(
        localized_source, encoding="utf-8"
    )
    write_json(
        APPLE / f"{STEM}.localized.compiled.json",
        plistlib.loads(localized_source.encode()),
    )

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    case_id = "apple-stringsdict-native-mixed-scalar-device-variation-dictionaries"
    skeleton_id = "apple-stringsdict-source-skeleton-translates-mixed-device-branches"
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
    samples = [
        {
            "message": scalar_plural,
            "arguments": [1],
            "expected": "1 desktop beacon",
        },
        {
            "message": scalar_plural,
            "arguments": [3],
            "expected": "3 desktop beacons",
        },
        {"message": plural_scalar, "arguments": [], "expected": "Click desktop pier"},
        {
            "message": scalar_width,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Mac near",
        },
        {
            "message": scalar_width,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "Mac\nopen coast",
        },
        {
            "message": width_scalar,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Click desktop harbor",
        },
        {
            "message": plural_width,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "West cove",
        },
        {
            "message": plural_width,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "West\nopen shore",
        },
        {"message": width_plural, "arguments": [1], "expected": "1 desktop buoy"},
        {"message": width_plural, "arguments": [4], "expected": "4 desktop buoys"},
        {
            "message": three_shapes,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Bay near",
        },
        {
            "message": three_shapes,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "Bay\nopen water",
        },
    ]
    translated_samples = [
        {
            "message": scalar_plural,
            "arguments": [1],
            "expected": "1 balise bureau",
        },
        {
            "message": scalar_plural,
            "arguments": [3],
            "expected": "3 balises bureau",
        },
        {
            "message": plural_scalar,
            "arguments": [],
            "expected": "Cliquez sur la jetée",
        },
        {
            "message": scalar_width,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Sud doux",
        },
        {
            "message": scalar_width,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "Sud\nouest calme",
        },
        {
            "message": width_scalar,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Cliquez sur le port",
        },
        {
            "message": plural_width,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Nord quai",
        },
        {
            "message": plural_width,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "Sud\nrive ouverte",
        },
        {"message": width_plural, "arguments": [1], "expected": "1 bouée bureau"},
        {"message": width_plural, "arguments": [4], "expected": "4 bouées bureau"},
        {
            "message": three_shapes,
            "arguments": [],
            "presentationWidth": 5,
            "expected": "Cap doux",
        },
        {
            "message": three_shapes,
            "arguments": [],
            "presentationWidth": 40,
            "expected": "Cap\neau claire",
        },
    ]
    case = {
        "id": skeleton_id,
        "format": "apple_stringsdict",
        "appleAllVariationSlots": True,
        "appleDevicePluralSlots": True,
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
    write_json(MANIFEST, manifest)


if __name__ == "__main__":
    main()
