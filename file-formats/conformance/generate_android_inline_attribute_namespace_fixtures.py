#!/usr/bin/env python3
"""Pin AAPT2's namespace-erasing inline-style attribute contract."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
MANIFEST = ROOT / "manifest.json"
STEM = "inline-attribute-namespaces"
CASE_ID = "android-aapt2-erases-inline-style-attribute-namespaces"
REJECTED_CASE_ID = "android-aapt2-accepts-xml-space-values-rejected-by-okapi"
SKELETON_ID = "android-source-skeleton-preserves-namespaced-inline-attributes"
ERROR_PREFIX = "android-portable-rejects-ambiguous-native-style-attribute-"
SHADOW_ID = "shadow-android-namespaced-inline-style-attribute-runtime-loss"

SOURCE = """<?xml version="1.0"?>
<resources xmlns:f="urn:neutral:span-attributes"
    xmlns:android="http://schemas.android.com/apk/res/android"
    xml:space="preserve" xml:lang="en">
  <string name="scalar_space" xml:space="preserve">  north   west  </string>
  <string name="scalar_default_space" xml:space="default">  north   east  </string>
  <string name="space_style"><b xml:space="preserve">  northern   passage  </b></string>
  <string name="language_style"><b xml:lang="fr">Harbor signal</b></string>
  <string name="foreign_annotation"><annotation f:route="north&#9;dock">Harbor signal</annotation></string>
  <string name="font_effect"><font android:size="12" f:color="red">Harbor glow</font></string>
  <string name="ordered_namespaces"><b alpha="A" android:bravo="B" xml:charlie="C" f:delta="D">Harbor order</b></string>
  <string name="inline_namespace"><b xmlns:inner="urn:neutral:inline" inner:route="quiet">Harbor route</b></string>
  <item name="generic_style" type="string" format="string"><font android:size="8">Harbor guide</font></item>
  <string-array name="routes">
    <item><annotation f:key="private">Harbor route</annotation></item>
    <item><font f:color="#112233">Harbor light</font></item>
  </string-array>
  <plurals name="signals">
    <item quantity="one"><font android:size="9">%1$d harbor beacon</font></item>
    <item quantity="other"><annotation f:key="beacon">%1$d harbor beacons</annotation></item>
  </plurals>
  <string name="protected" translatable="false"><b f:route="protected">Private harbor</b></string>
</resources>
"""

BODY_SLOTS = (
    ("scalar_space", None, "  north   west  "),
    ("scalar_default_space", None, "  north   east  "),
    ("space_style", None, '<b xml:space="preserve">  northern   passage  </b>'),
    ("language_style", None, '<b xml:lang="fr">Harbor signal</b>'),
    (
        "foreign_annotation",
        None,
        '<annotation f:route="north&#9;dock">Harbor signal</annotation>',
    ),
    (
        "font_effect",
        None,
        '<font android:size="12" f:color="red">Harbor glow</font>',
    ),
    (
        "ordered_namespaces",
        None,
        '<b alpha="A" android:bravo="B" xml:charlie="C" '
        'f:delta="D">Harbor order</b>',
    ),
    (
        "inline_namespace",
        None,
        '<b xmlns:inner="urn:neutral:inline" inner:route="quiet">Harbor route</b>',
    ),
    ("generic_style", None, '<font android:size="8">Harbor guide</font>'),
    ("routes[0]", None, '<annotation f:key="private">Harbor route</annotation>'),
    ("routes[1]", None, '<font f:color="#112233">Harbor light</font>'),
    ("signals", "one", '<font android:size="9">%1$d harbor beacon</font>'),
    (
        "signals",
        "other",
        '<annotation f:key="beacon">%1$d harbor beacons</annotation>',
    ),
)

TRANSLATIONS = {
    "scalar_space": "Nord ouest",
    "scalar_default_space": "Nord est",
    "space_style": '<b space="preserve">Passage nord</b>',
    "language_style": '<b lang="fr">Signal du port</b>',
    "foreign_annotation": '<annotation route="north\tdock">Signal du port</annotation>',
    "font_effect": '<font size="12" color="red">Éclat du port</font>',
    "ordered_namespaces": '<b alpha="A" bravo="B" charlie="C" delta="D">Ordre du port</b>',
    "inline_namespace": '<b route="quiet">Route du port</b>',
    "generic_style": '<font size="8">Guide du port</font>',
    "routes[0]": '<annotation key="private">Route du port</annotation>',
    "routes[1]": '<font color="#112233">Lumière du port</font>',
    "signals#one": '<font size="9">{arg0} balise du port</font>',
    "signals#other": '<annotation key="beacon">{arg0} balises du port</annotation>',
}


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    (ANDROID / f"{STEM}.xml").write_text(SOURCE, encoding="utf-8")
    (ANDROID / f"{STEM}-invalid-xml-space.xml").write_text(
        '<?xml version="1.0"?>\n'
        '<resources xml:space="sideways">\n'
        '  <string name="native_signal">  north   east  </string>\n'
        "</resources>\n",
        encoding="utf-8",
    )
    write_json(
        ANDROID / f"{STEM}-invalid-xml-space.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "messages": {"native_signal": {"defaultMessage": "north east"}},
        },
    )
    write_json(ANDROID / f"{STEM}.translations.json", TRANSLATIONS)
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        slots = []
        position = 0
        for identifier, variant, body in BODY_SLOTS:
            start = SOURCE.index(body, position)
            end = start + len(body)
            position = end
            if encoding == "UTF-8":
                offset = len(SOURCE[:start].encode("utf-8"))
                finish = offset + len(body.encode("utf-8"))
            else:
                offset = 2 + len(SOURCE[:start].encode("utf-16-le"))
                finish = offset + len(body.encode("utf-16-le"))
            slot = {"id": identifier, "start": offset, "end": finish}
            if variant is not None:
                slot["variant"] = variant
            slots.append(slot)
        write_json(
            ANDROID / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": SOURCE,
                "slots": slots,
            },
        )

    duplicates = {
        "foreign-android": (
            '<annotation f:key="foreign" android:key="system">Signal</annotation>'
        ),
        "foreign-xml": (
            '<annotation f:lang="foreign" xml:lang="fr">Signal</annotation>'
        ),
    }
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if case["id"] not in {CASE_ID, REJECTED_CASE_ID}
        and not case["id"].startswith(ERROR_PREFIX)
    ]
    manifest["cases"].append(
        {
            "id": CASE_ID,
            "format": "android",
            "input": f"fixtures/android/{STEM}.xml",
            "expected": f"fixtures/android/{STEM}.expected.json",
            "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
            "androidStyledSpans": True,
            "androidSpanRuntime": True,
            "androidNormalized": f"fixtures/android/{STEM}.normalized.xml",
            "androidNormalizedCompiled": (
                f"fixtures/android/{STEM}.normalized.compiled.json"
            ),
            "okapi": {
                "policy": "different",
                "assetPath": "res/values/strings.xml",
                "expected": f"fixtures/okapi/android-{STEM}.json",
                "reason": (
                    "AAPT2 erases inline XML/Android/foreign attribute namespaces, "
                    "orders native attributes by namespace URI, and applies real "
                    "font/annotation effects; legacy Okapi preserves invalid prefixes "
                    "and leaks protected array/plural sources."
                ),
            },
        }
    )
    manifest["cases"].append(
        {
            "id": REJECTED_CASE_ID,
            "format": "android",
            "input": f"fixtures/android/{STEM}-invalid-xml-space.xml",
            "expected": f"fixtures/android/{STEM}-invalid-xml-space.expected.json",
            "androidCompiled": f"fixtures/android/{STEM}-invalid-xml-space.compiled.json",
            "okapi": {
                "policy": "rejected",
                "assetPath": "res/values/strings.xml",
                "errorClass": "org.w3c.its.ITSException",
                "errorMessage": "Invalid value for 'xml:space'.",
                "reason": (
                    "AAPT2 ignores all root xml:space values and compiles the native "
                    "resource, but Mojito's configured Okapi ITS filter rejects the "
                    "valid Android file before extracting any translation."
                ),
            },
        }
    )
    for name, body in duplicates.items():
        path = ANDROID / f"{STEM}-duplicate-{name}.xml"
        path.write_text(
            '<?xml version="1.0"?>\n'
            '<resources xmlns:f="urn:neutral:span-attributes" '
            'xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'  <string name="duplicate">{body}</string>\n'
            "</resources>\n",
            encoding="utf-8",
        )
        manifest["cases"].append(
            {
                "id": ERROR_PREFIX + name,
                "format": "android",
                "input": f"fixtures/android/{STEM}-duplicate-{name}.xml",
                "error": "INVALID_ANDROID_MARKUP",
                "androidOracle": "accept",
                "androidCompiled": (
                    f"fixtures/android/{STEM}-duplicate-{name}.compiled.json"
                ),
                "androidStyledSpans": True,
                "androidSpanRuntime": True,
            }
        )

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
        "androidSpanRuntime": True,
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
