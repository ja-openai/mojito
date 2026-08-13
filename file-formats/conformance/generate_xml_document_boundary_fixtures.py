#!/usr/bin/env python3
"""Record native-permissive XML boundaries while enforcing safe portable documents."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
PREFIX = "portable-xml-document-boundary-"
ANDROID_CASE = "android-xml-document-preserves-valid-preamble-and-reserved-namespace"
SKELETON = "android-source-skeleton-preserves-well-formed-document-envelope"
XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"

ANDROID_ROOT = '<resources><string name="signal">Quiet harbor</string></resources>'
APPLE_ROOTS = {
    "apple_strings": (
        '<plist version="1.0"><dict><key>signal</key>'
        "<string>Quiet harbor</string></dict></plist>"
    ),
    "apple_stringsdict": (
        '<plist version="1.0"><dict><key>signals</key><dict>'
        "<key>NSStringLocalizedFormatKey</key><string>%#@count@</string>"
        "<key>count</key><dict><key>NSStringFormatSpecTypeKey</key>"
        "<string>NSStringPluralRuleType</string>"
        "<key>NSStringFormatValueTypeKey</key><string>d</string>"
        "<key>one</key><string>%d beacon</string>"
        "<key>other</key><string>%d beacons</string>"
        "</dict></dict></dict></plist>"
    ),
}

SOURCE = (
    '<?xml version="1.0" standalone="yes"?>\n'
    "<!-- Original envelope stays unchanged. -->\n"
    '<?navigation mode="before"?>\n'
    f'<resources xmlns:xml="{XML_NAMESPACE}" '
    'xmlns:span="urn:neutral:document-boundary">\n'
    '  <string name="route" xml:lang="en">North pier</string>\n'
    '  <string name="bay">South harbor</string>\n'
    "</resources>\n"
    '<?navigation mode="after"?>\n'
    "<!-- Original trailing envelope stays unchanged. -->\n"
)
TRANSLATIONS = {"route": "Quai nord", "bay": "Havre sud"}
LOCALIZED = SOURCE.replace("North pier", '"Quai nord"').replace(
    "South harbor", '"Havre sud"'
)


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def android_oracle():
    spec = importlib.util.spec_from_file_location(
        "android_aapt2_oracle", ROOT / "android_aapt2_oracle.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, module.locate_aapt2(False)


def android_native(source: str, module, executable: Path) -> dict | None:
    with tempfile.TemporaryDirectory(prefix="mojito-xml-boundary-aapt2-") as value:
        directory = Path(value)
        resource = directory / "res" / "values" / "strings.xml"
        resource.parent.mkdir(parents=True)
        resource.write_text(source, encoding="utf-8")
        output = directory / "compiled"
        output.mkdir()
        result = subprocess.run(
            [str(executable), "compile", str(resource), "-o", str(output)],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            return None
        compiled = next(output.glob("*.arsc.flat"))
        result = subprocess.run(
            [str(executable), "dump", "apc", str(compiled)],
            check=True,
            capture_output=True,
        )
        return module.compiled_catalog(result.stdout.decode("utf-8"))


def apple_native(source: str, suffix: str) -> dict | None:
    with tempfile.TemporaryDirectory(prefix="mojito-xml-boundary-foundation-") as value:
        resource = Path(value) / f"neutral.{suffix}"
        resource.write_text(source, encoding="utf-8")
        result = subprocess.run(
            ["plutil", "-convert", "json", "-o", "-", str(resource)],
            capture_output=True,
            text=True,
        )
        return json.loads(result.stdout) if not result.returncode else None


def variants(root: str, apple: bool) -> dict[str, str]:
    result = {
        "trailing-text": root + "ignored trailer",
        "trailing-character-reference": root + "&#32;",
        "trailing-cdata": root + "<![CDATA[ignored trailer]]>",
        "trailing-nonbreaking-space": root + "\u00a0",
        "duplicate-root": root + root,
        "declaration-after-comment": '<!-- original --><?xml version="1.0"?>' + root,
        "declaration-after-instruction": '<?original keep?><?xml version="1.0"?>'
        + root,
        "declaration-after-whitespace": ' <?xml version="1.0"?>' + root,
        "duplicate-declaration": '<?xml version="1.0"?><?xml version="1.0"?>' + root,
        "declaration-after-root": root + '<?xml version="1.0"?>',
        "declaration-unsupported-version": '<?xml version="1.9"?>' + root,
        "declaration-invalid-standalone": (
            '<?xml version="1.0" standalone="perhaps"?>' + root
        ),
        "declaration-missing-version": '<?xml encoding="UTF-8"?>' + root,
        "declaration-unknown-attribute": '<?xml version="1.0" harbor="quiet"?>' + root,
        "declaration-out-of-order-encoding": (
            '<?xml version="1.0" standalone="yes" encoding="UTF-8"?>' + root
        ),
        "declaration-duplicate-encoding": (
            '<?xml version="1.0" encoding="UTF-8" encoding="UTF-8"?>' + root
        ),
        "namespace-wrong-xml-binding": root.replace(
            "<plist " if apple else "<resources>",
            (
                '<plist xmlns:xml="urn:neutral:wrong" '
                if apple
                else '<resources xmlns:xml="urn:neutral:wrong">'
            ),
            1,
        ),
        "namespace-forbidden-xmlns-prefix": root.replace(
            "<plist " if apple else "<resources>",
            (
                '<plist xmlns:xmlns="urn:neutral:wrong" '
                if apple
                else '<resources xmlns:xmlns="urn:neutral:wrong">'
            ),
            1,
        ),
        "namespace-empty-prefix-binding": root.replace(
            "<plist " if apple else "<resources>",
            '<plist xmlns:route="" ' if apple else '<resources xmlns:route="">',
            1,
        ),
        "namespace-foreign-xml-uri": root.replace(
            "<plist " if apple else "<resources>",
            (
                f'<plist xmlns:route="{XML_NAMESPACE}" '
                if apple
                else f'<resources xmlns:route="{XML_NAMESPACE}">'
            ),
            1,
        ),
        "namespace-foreign-xmlns-uri": root.replace(
            "<plist " if apple else "<resources>",
            (
                f'<plist xmlns:route="{XMLNS_NAMESPACE}" '
                if apple
                else f'<resources xmlns:route="{XMLNS_NAMESPACE}">'
            ),
            1,
        ),
        "namespace-default-xml-uri": root.replace(
            "<plist " if apple else "<resources>",
            (
                f'<plist xmlns="{XML_NAMESPACE}" '
                if apple
                else f'<resources xmlns="{XML_NAMESPACE}">'
            ),
            1,
        ),
        "namespace-default-xmlns-uri": root.replace(
            "<plist " if apple else "<resources>",
            (
                f'<plist xmlns="{XMLNS_NAMESPACE}" '
                if apple
                else f'<resources xmlns="{XMLNS_NAMESPACE}">'
            ),
            1,
        ),
        "namespace-duplicate-expanded-attribute": root.replace(
            "<plist " if apple else "<resources>",
            (
                '<plist xmlns:left="urn:neutral:same" '
                'xmlns:right="urn:neutral:same" left:route="north" right:route="south" '
                if apple
                else '<resources xmlns:left="urn:neutral:same" '
                'xmlns:right="urn:neutral:same" '
                'left:route="north" right:route="south">'
            ),
            1,
        ),
    }
    if not apple:
        result.update(
            {
                "leading-text": "ignored preamble" + root,
                "leading-character-reference": "&#32;" + root,
                "leading-cdata": "<![CDATA[ignored preamble]]>" + root,
                "leading-nonbreaking-space": "\u00a0" + root,
            }
        )
    return result


def main() -> None:
    module, executable = android_oracle()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if PREFIX not in case["id"] and case["id"] != ANDROID_CASE
    ]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SKELETON)
    ]
    manifest["sourceSkeletonErrors"] = [
        case for case in manifest["sourceSkeletonErrors"] if PREFIX not in case["id"]
    ]

    for name, source in variants(ANDROID_ROOT, False).items():
        stem = f"xml-document-boundary-{name}"
        input_path = ANDROID / f"{stem}.xml"
        input_path.write_text(source, encoding="utf-8")
        case = {
            "id": f"android-{PREFIX}{name}",
            "format": "android",
            "input": f"fixtures/android/{stem}.xml",
            "error": "INVALID_XML",
        }
        snapshot = android_native(source, module, executable)
        if snapshot is not None:
            compiled = ANDROID / f"{stem}.compiled.json"
            write_json(compiled, snapshot)
            case.update(
                {
                    "androidOracle": "accept",
                    "androidCompiled": f"fixtures/android/{compiled.name}",
                }
            )
        else:
            case["androidOracle"] = "reject"
        manifest["cases"].append(case)

    for format_name, root in APPLE_ROOTS.items():
        suffix = "strings" if format_name == "apple_strings" else "stringsdict"
        prefix = (
            "apple-strings" if format_name == "apple_strings" else "apple-stringsdict"
        )
        for name, source in variants(root, True).items():
            stem = f"xml-document-boundary-{name}"
            filename = f"{stem}.{suffix}"
            input_path = APPLE / filename
            input_path.write_text(source, encoding="utf-8")
            case = {
                "id": f"{prefix}-{PREFIX}{name}",
                "format": format_name,
                "input": f"fixtures/apple/{filename}",
                "error": "INVALID_XML",
            }
            snapshot = apple_native(source, suffix)
            if snapshot is not None:
                compiled_name = f"{stem}.{suffix}.compiled.json"
                write_json(APPLE / compiled_name, snapshot)
                case.update(
                    {
                        "appleOracle": "accept",
                        "appleCompiled": f"fixtures/apple/{compiled_name}",
                    }
                )
            else:
                case["appleOracle"] = "reject"
            manifest["cases"].append(case)

    stem = "xml-document-boundary-valid-envelope"
    input_path = ANDROID / f"{stem}.xml"
    input_path.write_text(SOURCE, encoding="utf-8")
    localized_path = ANDROID / f"{stem}.localized.xml"
    localized_path.write_text(LOCALIZED, encoding="utf-8")
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "android",
        "messages": {
            "route": {"defaultMessage": "North pier"},
            "bay": {"defaultMessage": "South harbor"},
        },
    }
    write_json(ANDROID / f"{stem}.expected.json", expected)
    write_json(ANDROID / f"{stem}.translations.json", TRANSLATIONS)
    write_json(
        ANDROID / f"{stem}.compiled.json", android_native(SOURCE, module, executable)
    )
    write_json(
        ANDROID / f"{stem}.localized.compiled.json",
        android_native(LOCALIZED, module, executable),
    )
    manifest["cases"].append(
        {
            "id": ANDROID_CASE,
            "format": "android",
            "input": f"fixtures/android/{stem}.xml",
            "expected": f"fixtures/android/{stem}.expected.json",
            "androidCompiled": f"fixtures/android/{stem}.compiled.json",
        }
    )
    for encoding, variant in (("UTF-8", "utf8"), ("UTF-16LE-BOM", "utf16le")):
        slots = []
        for identifier, body in (("route", "North pier"), ("bay", "South harbor")):
            position = SOURCE.index(body)
            if encoding == "UTF-8":
                start = len(SOURCE[:position].encode("utf-8"))
                end = start + len(body.encode("utf-8"))
            else:
                start = 2 + len(SOURCE[:position].encode("utf-16-le"))
                end = start + len(body.encode("utf-16-le"))
            slots.append({"id": identifier, "start": start, "end": end})
        write_json(
            ANDROID / f"{stem}.{variant}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": SOURCE,
                "slots": slots,
            },
        )
        skeleton = {
            "id": f"{SKELETON}-{variant}",
            "format": "android",
            "input": f"fixtures/android/{stem}.xml",
            "expected": f"fixtures/android/{stem}.{variant}.expected.skeleton.json",
            "translations": f"fixtures/android/{stem}.translations.json",
            "localized": f"fixtures/android/{stem}.localized.xml",
            "androidCompiled": f"fixtures/android/{stem}.compiled.json",
            "androidLocalizedCompiled": (
                f"fixtures/android/{stem}.localized.compiled.json"
            ),
        }
        if encoding != "UTF-8":
            skeleton["encoding"] = encoding
        manifest["sourceSkeletons"].append(skeleton)

    for format_name, prefix, suffix, variants_to_reject in (
        (
            "android",
            "android",
            "xml",
            (
                "trailing-text",
                "declaration-after-comment",
                "namespace-wrong-xml-binding",
            ),
        ),
        (
            "apple_strings",
            "apple-strings",
            "strings",
            (
                "trailing-cdata",
                "duplicate-declaration",
                "namespace-duplicate-expanded-attribute",
            ),
        ),
        (
            "apple_stringsdict",
            "apple-stringsdict",
            "stringsdict",
            (
                "trailing-character-reference",
                "declaration-invalid-standalone",
                "namespace-empty-prefix-binding",
            ),
        ),
    ):
        folder = "android" if format_name == "android" else "apple"
        for name in variants_to_reject:
            manifest["sourceSkeletonErrors"].append(
                {
                    "id": f"{prefix}-source-{PREFIX}{name}",
                    "format": format_name,
                    "input": f"fixtures/{folder}/xml-document-boundary-{name}.{suffix}",
                    "error": "INVALID_XML",
                }
            )

    write_json(MANIFEST, manifest)
    print(
        "Generated strict XML envelopes, native-permissive Android/Foundation "
        "snapshots, and UTF-8/UTF-16 source-template contracts."
    )


if __name__ == "__main__":
    main()
