#!/usr/bin/env python3
"""Preserve AAPT2 validation of resources that must never be translated."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
MANIFEST = ROOT / "manifest.json"
STEM = "protected-native-validation"
CASE_ID = "android-aapt2-validates-nontranslatable-native-resource-text"
SKELETON_ID = "android-source-skeleton-preserves-validated-nontranslatable-resources"
ERROR_PREFIX = "android-aapt2-rejects-invalid-nontranslatable-"

SHAPES = {
    "string": '<string name="protected" translatable="false">{value}</string>',
    "generic-string": (
        '<item type="string" name="protected" format="string" '
        'translatable="false">{value}</item>'
    ),
    "array": (
        '<string-array name="protected" translatable="false">'
        "<item>{value}</item></string-array>"
    ),
    "generic-array": (
        '<bag type="string-array" name="protected" translatable="false">'
        "<item>{value}</item></bag>"
    ),
    "plural": (
        '<plurals name="protected" translatable="false">'
        '<item quantity="other">{value}</item></plurals>'
    ),
    "generic-plural": (
        '<bag type="plurals" name="protected" translatable="false">'
        '<item quantity="other">{value}</item></bag>'
    ),
}


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def fixture(resource: str, *, xliff: bool = False) -> str:
    namespace = ' xmlns:x="urn:oasis:names:tc:xliff:document:1.2"' if xliff else ""
    return (
        f'<?xml version="1.0"?>\n<resources{namespace}>\n  {resource}\n</resources>\n'
    )


def main() -> None:
    lines = [
        '<?xml version="1.0"?>',
        '<resources xmlns:x="urn:oasis:names:tc:xliff:document:1.2">',
        '  <string name="visible">Visible harbor</string>',
        '  <string name="protected_scalar" translatable="false">'
        '"Harbor\'s  quiet" %s %d</string>',
        '  <item type="string" name="protected_generic" format="string" '
        'translatable="false">West\\\'s \\u0041 quay</item>',
        '  <string-array name="protected_routes" translatable="false">',
        "    <item>South\\'s  pier</item>",
        '    <item>"East\'s calm"</item>',
        '    <item>North <x:g id="bad id">%1$s</x:g> lane</item>',
        '    <item><x:g id="other"><b>%1$s</b></x:g></item>',
        "  </string-array>",
        '  <bag type="string-array" name="protected_bag_routes" '
        'translatable="false">',
        "    <item>Bay\\'s quiet</item>",
        "  </bag>",
        '  <plurals name="protected_counts" translatable="false">',
        '    <item quantity="one">One signal\\\'s shore</item>',
        '    <item quantity="other">%s then %d</item>',
        "  </plurals>",
        '  <bag type="plurals" name="protected_bag_counts" ' 'translatable="false">',
        '    <item quantity="other">Two beacon\\\'s lane</item>',
        "  </bag>",
        "</resources>",
        "",
    ]
    source = "\n".join(lines)
    (ANDROID / f"{STEM}.xml").write_text(source, encoding="utf-8")
    write_json(
        ANDROID / f"{STEM}.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "messages": {"visible": {"defaultMessage": "Visible harbor"}},
        },
    )
    write_json(ANDROID / f"{STEM}.translations.json", {"visible": "Port visible"})
    localized = source.replace(
        '<string name="visible">Visible harbor</string>',
        '<string name="visible">"Port visible"</string>',
    )
    (ANDROID / f"{STEM}.localized.xml").write_text(localized, encoding="utf-8")

    body_start = source.index("Visible harbor")
    body_end = body_start + len("Visible harbor")
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        if encoding == "UTF-8":
            start, end = body_start, body_end
        else:
            start = 2 + len(source[:body_start].encode("utf-16-le"))
            end = start + len("Visible harbor".encode("utf-16-le"))
        write_json(
            ANDROID / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": source,
                "slots": [{"id": "visible", "start": start, "end": end}],
            },
        )

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if case["id"] != CASE_ID and not case["id"].startswith(ERROR_PREFIX)
    ]
    manifest["cases"].append(
        {
            "id": CASE_ID,
            "format": "android",
            "input": f"fixtures/android/{STEM}.xml",
            "expected": f"fixtures/android/{STEM}.expected.json",
            "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
            "androidStyledSpans": True,
            "okapi": {
                "policy": "different",
                "assetPath": "res/values/strings.xml",
                "expected": f"fixtures/okapi/android-{STEM}.json",
                "reason": (
                    "AAPT2 validates but never translates protected strings, generic "
                    "resources, arrays, plural bags, unsafe XLIFF IDs, and style spans; "
                    "the legacy filter leaks protected array/plural values."
                ),
            },
        }
    )

    for shape, template in SHAPES.items():
        for kind, value, error, diagnostic in (
            (
                "apostrophe",
                "North's quiet",
                "UNESCAPED_APOSTROPHE",
                "unescaped apostrophe",
            ),
            (
                "unicode-escape",
                r"North\uQQQQ",
                "INVALID_UNICODE_ESCAPE",
                "invalid unicode escape sequence",
            ),
        ):
            name = f"{STEM}-{shape}-{kind}"
            (ANDROID / f"{name}.xml").write_text(
                fixture(template.format(value=value)), encoding="utf-8"
            )
            manifest["cases"].append(
                {
                    "id": ERROR_PREFIX + shape + "-" + kind,
                    "format": "android",
                    "input": f"fixtures/android/{name}.xml",
                    "error": error,
                    "androidErrorContains": diagnostic,
                }
            )

    extras = (
        (
            "style-resets-quoted-apostrophe",
            '<string name="protected" translatable="false">'
            '"North <b>it\'s</b> coast"</string>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            False,
            None,
        ),
        (
            "nested-xliff-section",
            '<string name="protected" translatable="false">'
            '<x:g id="outer"><x:g id="inner">%1$s</x:g></x:g></string>',
            "INVALID_ANDROID_MARKUP",
            "illegal nested XLIFF 'g' tag",
            True,
            None,
        ),
        (
            "donottranslate-file-apostrophe",
            '<string name="protected">North\'s quiet</string>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            False,
            "res/values/donottranslate_protected.xml",
        ),
        (
            "donottranslate-file-unicode-escape",
            r'<string name="protected">North\uQQQQ</string>',
            "INVALID_UNICODE_ESCAPE",
            "invalid unicode escape sequence",
            False,
            "res/values/donottranslate_protected.xml",
        ),
    )
    for name, resource, error, diagnostic, xliff, resource_path in extras:
        (ANDROID / f"{STEM}-{name}.xml").write_text(
            fixture(resource, xliff=xliff), encoding="utf-8"
        )
        case = {
            "id": ERROR_PREFIX + name,
            "format": "android",
            "input": f"fixtures/android/{STEM}-{name}.xml",
            "error": error,
            "androidErrorContains": diagnostic,
        }
        if resource_path is not None:
            case["resourcePath"] = resource_path
        manifest["cases"].append(case)

    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if case["id"] not in {SKELETON_ID, SKELETON_ID + "-utf16le"}
    ]
    skeleton = {
        "id": SKELETON_ID,
        "format": "android",
        "input": f"fixtures/android/{STEM}.xml",
        "expected": f"fixtures/android/{STEM}.expected.skeleton.json",
        "translations": f"fixtures/android/{STEM}.translations.json",
        "localized": f"fixtures/android/{STEM}.localized.xml",
        "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
        "androidLocalizedCompiled": f"fixtures/android/{STEM}.localized.compiled.json",
    }
    manifest["sourceSkeletons"].append(skeleton)
    manifest["sourceSkeletons"].append(
        {
            **skeleton,
            "id": SKELETON_ID + "-utf16le",
            "encoding": "UTF-16LE-BOM",
            "expected": f"fixtures/android/{STEM}.utf16le.expected.skeleton.json",
        }
    )
    manifest["shadowComparisons"] = [
        case
        for case in manifest["shadowComparisons"]
        if case["id"] != "shadow-android-nontranslatable-native-resource-leaks"
    ]
    manifest["shadowComparisons"].append(
        {
            "id": "shadow-android-nontranslatable-native-resource-leaks",
            "case": CASE_ID,
            "expected": f"fixtures/shadow/android-{STEM}.json",
        }
    )
    write_json(MANIFEST, manifest)


if __name__ == "__main__":
    main()
