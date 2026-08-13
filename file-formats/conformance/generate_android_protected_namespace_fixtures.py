#!/usr/bin/env python3
"""Capture AAPT2 quote state across ignored native XML namespaces."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
MANIFEST = ROOT / "manifest.json"
STEM = "protected-namespace-quotes"
CASE_ID = "android-aapt2-ignored-namespaces-preserve-protected-quote-state"
PRIVATE_CASE_ID = "android-aapt2-donottranslate-ignored-namespaces-preserve-quote-state"
SKELETON_ID = "android-source-skeleton-preserves-transparent-protected-namespaces"
ERROR_PREFIX = "android-aapt2-protected-namespace-rejects-"
SHADOW_ID = "shadow-android-transparent-protected-namespace-resource-leaks"

SOURCE = """<?xml version="1.0"?>
<resources xmlns:f="urn:neutral:foreign-protected"
    xmlns:x="urn:oasis:names:tc:xliff:document:1.2">
  <string name="visible">Visible northern pier</string>
  <string name="protected_scalar" translatable="false">"North <f:veil>it's</f:veil> coast"</string>
  <item name="protected_generic" type="string" format="string" translatable="false">"East <x:note>it's</x:note> inlet"</item>
  <string-array name="protected_routes" translatable="false">
    <item>"West <f:g>it's</f:g> passage"</item>
    <item>"South <x:note>it's</x:note> bay"</item>
    <item>"Near <f:veil><x:g id="unsafe id">it's</x:g></f:veil> shore"</item>
    <item>"Far <x:g id="unsafe id"><f:veil>it's</f:veil></x:g> lane"</item>
    <item>"Old <f:first>it's</f:first><f:second> bay's</f:second> harbor"</item>
    <item>"Clear <f:veil><b>quiet</b></f:veil> passage"</item>
  </string-array>
  <bag type="string-array" name="protected_bag_routes" translatable="false">
    <item>"Inner <x:note>it's</x:note> route"</item>
    <item>"Outer <f:veil>it's</f:veil> route"</item>
  </bag>
  <plurals name="protected_counts" translatable="false">
    <item quantity="one">"One <f:veil>beacon's</f:veil> signal"</item>
    <item quantity="other">"Many <x:note>beacon's</x:note> signals"</item>
  </plurals>
  <bag type="plurals" name="protected_bag_counts" translatable="false">
    <item quantity="other">"Two <f:veil>beacon's</f:veil> routes"</item>
  </bag>
</resources>
"""

PRIVATE_SOURCE = """<?xml version="1.0"?>
<resources xmlns:f="urn:neutral:foreign-protected"
    xmlns:x="urn:oasis:names:tc:xliff:document:1.2">
  <string name="private_scalar">"Private <f:veil>it's</f:veil> coast"</string>
  <item name="private_generic" type="string" format="string">"Private <x:note>it's</x:note> route"</item>
  <string-array name="private_routes"><item>"Private <f:veil>it's</f:veil> lane"</item></string-array>
  <plurals name="private_counts"><item quantity="other">"Private <x:note>it's</x:note> signals"</item></plurals>
</resources>
"""


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def wrapped(resource: str) -> str:
    return (
        '<?xml version="1.0"?>\n'
        '<resources xmlns:f="urn:neutral:foreign-protected" '
        'xmlns:x="urn:oasis:names:tc:xliff:document:1.2">\n'
        f"  {resource}\n"
        "</resources>\n"
    )


def main() -> None:
    (ANDROID / f"{STEM}.xml").write_text(SOURCE, encoding="utf-8")
    (ANDROID / f"{STEM}-donottranslate.xml").write_text(
        PRIVATE_SOURCE, encoding="utf-8"
    )
    write_json(
        ANDROID / f"{STEM}.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "messages": {"visible": {"defaultMessage": "Visible northern pier"}},
        },
    )
    write_json(
        ANDROID / f"{STEM}-donottranslate.expected.json",
        {"schemaVersion": 1, "sourceFormat": "android", "messages": {}},
    )
    write_json(ANDROID / f"{STEM}.translations.json", {"visible": "Quai du nord"})
    localized = SOURCE.replace(
        '<string name="visible">Visible northern pier</string>',
        '<string name="visible">"Quai du nord"</string>',
    )
    (ANDROID / f"{STEM}.localized.xml").write_text(localized, encoding="utf-8")

    body_start = SOURCE.index("Visible northern pier")
    body_end = body_start + len("Visible northern pier")
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        if encoding == "UTF-8":
            start, end = body_start, body_end
        else:
            start = 2 + len(SOURCE[:body_start].encode("utf-16-le"))
            end = start + len("Visible northern pier".encode("utf-16-le"))
        write_json(
            ANDROID / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": SOURCE,
                "slots": [{"id": "visible", "start": start, "end": end}],
            },
        )

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if case["id"] not in {CASE_ID, PRIVATE_CASE_ID}
        and not case["id"].startswith(ERROR_PREFIX)
    ]
    manifest["cases"].extend(
        [
            {
                "id": CASE_ID,
                "format": "android",
                "input": f"fixtures/android/{STEM}.xml",
                "expected": f"fixtures/android/{STEM}.expected.json",
                "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
                "androidStyledSpans": True,
                "androidWarningContains": [
                    "ignoring element 'veil' with unknown namespace "
                    "'urn:neutral:foreign-protected'"
                ],
                "okapi": {
                    "policy": "different",
                    "assetPath": "res/values/strings.xml",
                    "expected": f"fixtures/okapi/android-{STEM}.json",
                    "reason": (
                        "AAPT2 treats foreign namespaces and unknown XLIFF elements as "
                        "transparent while preserving quote state and protected resource "
                        "ownership; legacy Okapi extracts forbidden array/plural values."
                    ),
                },
            },
            {
                "id": PRIVATE_CASE_ID,
                "format": "android",
                "input": f"fixtures/android/{STEM}-donottranslate.xml",
                "expected": f"fixtures/android/{STEM}-donottranslate.expected.json",
                "resourcePath": "res/values/donottranslate_foreign.xml",
                "androidCompiled": (
                    f"fixtures/android/{STEM}-donottranslate.compiled.json"
                ),
                "androidWarningContains": [
                    "ignoring element 'veil' with unknown namespace "
                    "'urn:neutral:foreign-protected'"
                ],
            },
        ]
    )

    errors = (
        (
            "foreign-style-string",
            '<string name="protected" translatable="false">'
            '"North <f:veil><b>it\'s</b></f:veil> coast"</string>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "unknown-xliff-style-generic",
            '<item name="protected" type="string" format="string" '
            'translatable="false">"North <x:note><b>it\'s</b>'
            '</x:note> coast"</item>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "foreign-style-array",
            '<string-array name="protected" translatable="false"><item>'
            '"North <f:veil><b>it\'s</b></f:veil> coast"</item></string-array>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "protected-style-plural",
            '<plurals name="protected" translatable="false"><item quantity="other">'
            '"North <x:g id="a"><b>it\'s</b></x:g> coast"</item></plurals>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "foreign-wrapper-nested-xliff",
            '<string name="protected" translatable="false"><f:veil>'
            '<x:g id="a"><x:g id="b">%1$s</x:g></x:g></f:veil></string>',
            "INVALID_ANDROID_MARKUP",
            "illegal nested XLIFF 'g' tag",
            None,
        ),
        (
            "unknown-xliff-wrapper-nested-xliff",
            '<string name="protected" translatable="false"><x:note>'
            '<x:g id="a"><x:g id="b">%1$s</x:g></x:g></x:note></string>',
            "INVALID_ANDROID_MARKUP",
            "illegal nested XLIFF 'g' tag",
            None,
        ),
        (
            "foreign-unquoted-apostrophe",
            '<string name="protected" translatable="false">'
            "North <f:veil>it's</f:veil> coast</string>",
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "unknown-xliff-unquoted-apostrophe",
            '<string name="protected" translatable="false">'
            "North <x:note>it's</x:note> coast</string>",
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            None,
        ),
        (
            "donottranslate-foreign-style",
            '<string name="protected">"North <f:veil><b>it\'s</b>'
            '</f:veil> coast"</string>',
            "UNESCAPED_APOSTROPHE",
            "unescaped apostrophe",
            "res/values/donottranslate_foreign.xml",
        ),
    )
    for name, resource, error, diagnostic, resource_path in errors:
        (ANDROID / f"{STEM}-{name}.xml").write_text(wrapped(resource), encoding="utf-8")
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
        case for case in manifest["shadowComparisons"] if case["id"] != SHADOW_ID
    ]
    manifest["shadowComparisons"].append(
        {
            "id": SHADOW_ID,
            "case": CASE_ID,
            "expected": f"fixtures/shadow/android-{STEM}.json",
        }
    )
    write_json(MANIFEST, manifest)


if __name__ == "__main__":
    main()
