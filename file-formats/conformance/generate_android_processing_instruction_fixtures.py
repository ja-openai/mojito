#!/usr/bin/env python3
"""Pin invisible AAPT2 instructions and byte-preserving Android source ownership."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures" / "android"
MANIFEST = ROOT / "manifest.json"
PREFIX = "android-aapt2-processing-instructions-"
SKELETON_PREFIX = "android-source-skeleton-preserves-processing-instructions-"
STEM = "processing-instructions"
SOURCE = """<?xml version="1.0"?>
<resources xmlns:x="urn:oasis:names:tc:xliff:document:1.2">
  <?root original="kept"?>
  <string name="plain">North<?lane mode="quiet"?> port</string>
  <string name="mixed">East<!-- original comment --><?mark value="1"?><![CDATA[ west]]></string>
  <string name="quoted">"North<?quote keep?>  south"</string>
  <string name="escaped">\\u0<?unicode split?>041</string>
  <string name="styled">Move <b lane="east">north<?style hold?>ward</b> now</string>
  <string name="pilot">Hi <x:g id="pilot">%1<?protected hold?>$s</x:g>!</string>
  <item type="string" name="generic" format="string">Open<?generic keep?> road</item>
  <string-array name="routes">
    <item>East<?array one?> pier</item>
    <item>West<?array two?> pier</item>
  </string-array>
  <plurals name="signals">
    <item quantity="one">%1$d<?plural one?> signal</item>
    <item quantity="other">%1$d<?plural other?> signals</item>
  </plurals>
</resources>
"""
LOCALIZED = """<?xml version="1.0"?>
<resources xmlns:x="urn:oasis:names:tc:xliff:document:1.2">
  <?root original="kept"?>
  <string name="plain">"Quie<?lane mode="quiet"?>t marina"</string>
  <string name="mixed">"Cle<!-- original comment --><?mark value="1"?><![CDATA[ar inlet"]]></string>
  <string name="quoted">"South<?quote keep?>  bay"</string>
  <string name="escaped">"Be<?unicode split?>acon"</string>
  <string name="styled">"Take "<b lane="east">"harb<?style hold?>orward"</b>" slowly"</string>
  <string name="pilot">"Welcome "<x:g id="pilot">%1<?protected hold?>$s</x:g>"."</string>
  <item type="string" name="generic" format="string">"She<?generic keep?>ltered road"</item>
  <string-array name="routes">
    <item>"Inn<?array one?>er quay"</item>
    <item>"Out<?array two?>er quay"</item>
  </string-array>
  <plurals name="signals">
    <item quantity="one">"%1$<?plural one?>d beacon"</item>
    <item quantity="other">"%1$<?plural other?>d beacons"</item>
  </plurals>
</resources>
"""


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def catalog() -> dict[str, object]:
    string_placeholder = {
        "name": "pilot",
        "source": "%1$s",
        "kind": "string",
        "position": 1,
    }
    integer_placeholder = {
        "name": "arg0",
        "source": "%1$d",
        "kind": "integer",
        "position": 1,
    }
    return {
        "schemaVersion": 1,
        "sourceFormat": "android",
        "messages": {
            "plain": {"defaultMessage": "North port"},
            "mixed": {"defaultMessage": "East west"},
            "quoted": {"defaultMessage": "North  south"},
            "escaped": {"defaultMessage": "A"},
            "styled": {
                "defaultMessage": "Move '<'b lane=\"east\">northward'<'/b> now",
                "metadata": {"androidMarkupEscaping": "icu-quoted-angle"},
            },
            "pilot": {
                "defaultMessage": "Hi {pilot}!",
                "placeholders": [string_placeholder],
            },
            "generic": {
                "defaultMessage": "Open road",
                "metadata": {
                    "androidGenericString": True,
                    "androidGenericFormat": "string",
                },
            },
            "routes[0]": {
                "defaultMessage": "East pier",
                "metadata": {"arrayName": "routes", "arrayIndex": 0},
            },
            "routes[1]": {
                "defaultMessage": "West pier",
                "metadata": {"arrayName": "routes", "arrayIndex": 1},
            },
            "signals": {
                "defaultMessage": (
                    "{arg0, plural, one {{arg0} signal} other {{arg0} signals}}"
                ),
                "variants": {"one": "{arg0} signal", "other": "{arg0} signals"},
                "placeholders": [integer_placeholder],
            },
        },
    }


def native(source: str) -> dict[str, object]:
    spec = importlib.util.spec_from_file_location(
        "android_aapt2_oracle", ROOT / "android_aapt2_oracle.py"
    )
    assert spec is not None and spec.loader is not None
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    executable = oracle.locate_aapt2(False)
    with tempfile.TemporaryDirectory(
        prefix="mojito-processing-instructions-"
    ) as directory:
        resource = Path(directory) / "res" / "values" / "strings.xml"
        resource.parent.mkdir(parents=True)
        resource.write_text(source, encoding="utf-8")
        output = Path(directory) / "compiled"
        output.mkdir()
        subprocess.run(
            [str(executable), "compile", str(resource), "-o", str(output)],
            check=True,
            capture_output=True,
            text=True,
        )
        result = subprocess.run(
            [str(executable), "dump", "apc", str(next(output.glob("*.flat")))],
            check=True,
            capture_output=True,
            text=True,
        )
        return oracle.compiled_catalog(result.stdout, include_spans=True)


def source_skeleton(source: str, encoding: str) -> dict[str, object]:
    slots = []
    for identity, marker, variant in (
        ("plain", 'North<?lane mode="quiet"?> port', None),
        (
            "mixed",
            'East<!-- original comment --><?mark value="1"?><![CDATA[ west]]>',
            None,
        ),
        ("quoted", '"North<?quote keep?>  south"', None),
        ("escaped", "\\u0<?unicode split?>041", None),
        ("styled", 'Move <b lane="east">north<?style hold?>ward</b> now', None),
        ("pilot", 'Hi <x:g id="pilot">%1<?protected hold?>$s</x:g>!', None),
        ("generic", "Open<?generic keep?> road", None),
        ("routes[0]", "East<?array one?> pier", None),
        ("routes[1]", "West<?array two?> pier", None),
        ("signals", "%1$d<?plural one?> signal", "one"),
        ("signals", "%1$d<?plural other?> signals", "other"),
    ):
        start = source.index(marker)
        end = start + len(marker)
        if encoding == "UTF-16LE-BOM":
            start = 2 + len(source[:start].encode("utf-16-le"))
            end = 2 + len(source[:end].encode("utf-16-le"))
        slot = {"id": identity, "start": start, "end": end}
        if variant is not None:
            slot["variant"] = variant
        slots.append(slot)
    return {
        "schemaVersion": 1,
        "sourceFormat": "android",
        "encoding": encoding,
        "source": source,
        "slots": slots,
    }


def main() -> None:
    expected = catalog()
    write_json(FIXTURES / f"{STEM}.expected.json", expected)
    (FIXTURES / f"{STEM}.xml").write_text(SOURCE, encoding="utf-8")
    write_json(FIXTURES / f"{STEM}.compiled.json", native(SOURCE))

    translations = {
        "plain": "Quiet marina",
        "mixed": "Clear inlet",
        "quoted": "South  bay",
        "escaped": "Beacon",
        "styled": 'Take <b lane="east">harborward</b> slowly',
        "pilot": "Welcome {pilot}.",
        "generic": "Sheltered road",
        "routes[0]": "Inner quay",
        "routes[1]": "Outer quay",
        "signals#one": "{arg0} beacon",
        "signals#other": "{arg0} beacons",
    }
    (FIXTURES / f"{STEM}.localized.xml").write_text(LOCALIZED, encoding="utf-8")
    write_json(FIXTURES / f"{STEM}.translations.json", translations)
    write_json(FIXTURES / f"{STEM}.localized.compiled.json", native(LOCALIZED))

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(PREFIX)
    ]
    manifest["cases"].append(
        {
            "id": PREFIX
            + "native-scalar-style-placeholder-array-and-plural-boundaries",
            "format": "android",
            "input": f"fixtures/android/{STEM}.xml",
            "expected": f"fixtures/android/{STEM}.expected.json",
            "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
            "androidStyledSpans": True,
        }
    )
    invalid = {
        "reserved-xml-target": (
            '<resources><string name="route">north<?xml nope?>south</string></resources>',
            "XML or text declaration not at start of entity",
        ),
        "unterminated-instruction": (
            '<resources><string name="route">north<?note south</string></resources>',
            "unclosed token",
        ),
    }
    for name, (source, diagnostic) in invalid.items():
        stem = f"{STEM}-invalid-{name}"
        (FIXTURES / f"{stem}.xml").write_text(source, encoding="utf-8")
        manifest["cases"].append(
            {
                "id": PREFIX + "rejects-" + name,
                "format": "android",
                "input": f"fixtures/android/{stem}.xml",
                "error": "INVALID_XML",
                "androidOracle": "reject",
                "androidErrorContains": diagnostic,
            }
        )

    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SKELETON_PREFIX)
    ]
    for encoding, suffix in (("UTF-8", "utf8"), ("UTF-16LE-BOM", "utf16le")):
        expected_path = f"fixtures/android/{STEM}.{suffix}.expected.skeleton.json"
        write_json(
            FIXTURES / f"{STEM}.{suffix}.expected.skeleton.json",
            source_skeleton(SOURCE, encoding),
        )
        case = {
            "id": SKELETON_PREFIX + suffix,
            "format": "android",
            "input": f"fixtures/android/{STEM}.xml",
            "expected": expected_path,
            "translations": f"fixtures/android/{STEM}.translations.json",
            "localized": f"fixtures/android/{STEM}.localized.xml",
            "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
            "androidLocalizedCompiled": f"fixtures/android/{STEM}.localized.compiled.json",
        }
        if encoding != "UTF-8":
            case["encoding"] = encoding
        manifest["sourceSkeletons"].append(case)
    write_json(MANIFEST, manifest)
    print(
        "Generated one AAPT2-valid instruction catalog, two invalid native cases, and two byte-preserving source templates."
    )


if __name__ == "__main__":
    main()
