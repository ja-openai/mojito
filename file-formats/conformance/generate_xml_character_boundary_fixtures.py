#!/usr/bin/env python3
"""Pin XML 1.0 raw/reference character safety across native localization formats."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
APPLE = ROOT / "fixtures" / "apple"
MANIFEST = ROOT / "manifest.json"
PREFIX = "portable-xml-character-boundary-"
SOURCE_CASE = "android-xml-character-boundary-accepts-safe-xml11-declaration"
SKELETON = "android-source-skeleton-preserves-safe-xml11-character-boundary"
INVALID_CHARACTERS = {
    "nul": "\x00",
    "start-of-heading": "\x01",
    "start-of-text": "\x02",
    "bell": "\x07",
    "backspace": "\x08",
    "vertical-tab": "\x0b",
    "form-feed": "\x0c",
    "shift-out": "\x0e",
    "shift-in": "\x0f",
    "substitute": "\x1a",
    "escape": "\x1b",
    "unit-separator": "\x1f",
    "noncharacter-fffe": "\ufffe",
    "noncharacter-ffff": "\uffff",
}
INVALID_XML11_REFERENCES = (1, 8, 11, 12, 31)
ANDROID_ROOT = '<resources><string name="signal">North harbor</string></resources>'
APPLE_ROOTS = {
    "apple_strings": (
        '<plist version="1.0"><dict><key>signal</key>'
        "<string>North harbor</string></dict></plist>"
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
SAFE_SOURCE = (
    '<?xml version="1.1" standalone="yes"?>\n'
    "<!-- Legal document characters remain supported. -->\n"
    '<?route direction="north"?>\n'
    "<resources>\n"
    '  <string name="signal">North harbor</string>\n'
    "</resources>\n"
)
SAFE_LOCALIZED = SAFE_SOURCE.replace("North harbor", '"Havre nord"')


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def native_helpers():
    spec = importlib.util.spec_from_file_location(
        "xml_document_boundaries", ROOT / "generate_xml_document_boundary_fixtures.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    android_module, executable = module.android_oracle()
    return module, android_module, executable


def character_source(root: str, character: str, location: str, apple: bool) -> str:
    if location == "text":
        anchor = "North harbor" if "North harbor" in root else "%d beacon"
        return root.replace(anchor, "North" + character + "harbor", 1)
    if location == "attribute":
        if apple:
            return root.replace("<plist ", f'<plist note="north{character}harbor" ', 1)
        return root.replace(
            "<resources>", f'<resources note="north{character}harbor">', 1
        )
    if location == "comment":
        anchor = "<dict>" if apple else "<resources>"
        return root.replace(anchor, anchor + f"<!--north{character}harbor-->", 1)
    if location == "cdata":
        anchor = "North harbor" if "North harbor" in root else "%d beacon"
        return root.replace(anchor, f"<![CDATA[North{character}harbor]]>", 1)
    if location == "instruction":
        anchor = "<dict>" if apple else "<resources>"
        return root.replace(anchor, anchor + f'<?route value="{character}"?>', 1)
    raise AssertionError(location)


def reference_source(root: str, character: int) -> str:
    anchor = "North harbor" if "North harbor" in root else "%d beacon"
    return '<?xml version="1.1"?>' + root.replace(
        anchor, f"North&#{character};harbor", 1
    )


def main() -> None:
    helpers, android_module, executable = native_helpers()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if PREFIX not in case["id"] and case["id"] != SOURCE_CASE
    ]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SKELETON)
    ]
    manifest["sourceSkeletonErrors"] = [
        case for case in manifest["sourceSkeletonErrors"] if PREFIX not in case["id"]
    ]

    formats = (
        ("android", "android", "xml", ANDROID_ROOT),
        ("apple_strings", "apple-strings", "strings", APPLE_ROOTS["apple_strings"]),
        (
            "apple_stringsdict",
            "apple-stringsdict",
            "stringsdict",
            APPLE_ROOTS["apple_stringsdict"],
        ),
    )
    for format_name, identifier, suffix, root in formats:
        folder = ANDROID if format_name == "android" else APPLE
        for name, character in INVALID_CHARACTERS.items():
            for location in ("text", "attribute", "comment", "cdata", "instruction"):
                short_name = f"{name}-{location}"
                stem = f"xml-character-boundary-{short_name}"
                filename = f"{stem}.{suffix}"
                source = character_source(
                    root, character, location, format_name != "android"
                )
                (folder / filename).write_text(source, encoding="utf-8")
                case = {
                    "id": f"{identifier}-{PREFIX}{short_name}",
                    "format": format_name,
                    "input": f"fixtures/{folder.name}/{filename}",
                    "error": "INVALID_XML",
                }
                if format_name == "android":
                    snapshot = helpers.android_native(
                        source, android_module, executable
                    )
                    if snapshot is not None:
                        snapshot_name = f"{stem}.compiled.json"
                        write_json(folder / snapshot_name, snapshot)
                        case.update(
                            {
                                "androidOracle": "accept",
                                "androidCompiled": f"fixtures/android/{snapshot_name}",
                            }
                        )
                    else:
                        case["androidOracle"] = "reject"
                else:
                    snapshot = helpers.apple_native(source, suffix)
                    if snapshot is not None:
                        snapshot_name = f"{stem}.{suffix}.compiled.json"
                        write_json(folder / snapshot_name, snapshot)
                        case.update(
                            {
                                "appleOracle": "accept",
                                "appleCompiled": f"fixtures/apple/{snapshot_name}",
                            }
                        )
                    else:
                        case["appleOracle"] = "reject"
                manifest["cases"].append(case)

        for code in INVALID_XML11_REFERENCES:
            short_name = f"xml11-control-reference-{code:02d}"
            stem = f"xml-character-boundary-{short_name}"
            filename = f"{stem}.{suffix}"
            source = reference_source(root, code)
            (folder / filename).write_text(source, encoding="utf-8")
            case = {
                "id": f"{identifier}-{PREFIX}{short_name}",
                "format": format_name,
                "input": f"fixtures/{folder.name}/{filename}",
                "error": "INVALID_XML",
            }
            if format_name == "android":
                snapshot = helpers.android_native(source, android_module, executable)
                if snapshot is not None:
                    snapshot_name = f"{stem}.compiled.json"
                    write_json(folder / snapshot_name, snapshot)
                    case.update(
                        {
                            "androidOracle": "accept",
                            "androidCompiled": f"fixtures/android/{snapshot_name}",
                        }
                    )
                else:
                    case["androidOracle"] = "reject"
            else:
                snapshot = helpers.apple_native(source, suffix)
                if snapshot is not None:
                    snapshot_name = f"{stem}.{suffix}.compiled.json"
                    write_json(folder / snapshot_name, snapshot)
                    case.update(
                        {
                            "appleOracle": "accept",
                            "appleCompiled": f"fixtures/apple/{snapshot_name}",
                        }
                    )
                else:
                    case["appleOracle"] = "reject"
            manifest["cases"].append(case)

    stem = "xml-character-boundary-safe-xml11"
    (ANDROID / f"{stem}.xml").write_text(SAFE_SOURCE, encoding="utf-8")
    (ANDROID / f"{stem}.localized.xml").write_text(SAFE_LOCALIZED, encoding="utf-8")
    write_json(
        ANDROID / f"{stem}.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "messages": {"signal": {"defaultMessage": "North harbor"}},
        },
    )
    write_json(ANDROID / f"{stem}.translations.json", {"signal": "Havre nord"})
    compiled = helpers.android_native(SAFE_SOURCE, android_module, executable)
    localized = helpers.android_native(SAFE_LOCALIZED, android_module, executable)
    assert compiled is not None and localized is not None
    write_json(ANDROID / f"{stem}.compiled.json", compiled)
    write_json(ANDROID / f"{stem}.localized.compiled.json", localized)
    manifest["cases"].append(
        {
            "id": SOURCE_CASE,
            "format": "android",
            "input": f"fixtures/android/{stem}.xml",
            "expected": f"fixtures/android/{stem}.expected.json",
            "androidCompiled": f"fixtures/android/{stem}.compiled.json",
        }
    )
    for encoding, name in (("UTF-8", "utf8"), ("UTF-16LE-BOM", "utf16le")):
        position = SAFE_SOURCE.index("North harbor")
        if encoding == "UTF-8":
            start = len(SAFE_SOURCE[:position].encode("utf-8"))
            end = start + len("North harbor".encode("utf-8"))
        else:
            start = 2 + len(SAFE_SOURCE[:position].encode("utf-16-le"))
            end = start + len("North harbor".encode("utf-16-le"))
        write_json(
            ANDROID / f"{stem}.{name}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": SAFE_SOURCE,
                "slots": [{"id": "signal", "start": start, "end": end}],
            },
        )
        skeleton = {
            "id": f"{SKELETON}-{name}",
            "format": "android",
            "input": f"fixtures/android/{stem}.xml",
            "expected": f"fixtures/android/{stem}.{name}.expected.skeleton.json",
            "translations": f"fixtures/android/{stem}.translations.json",
            "localized": f"fixtures/android/{stem}.localized.xml",
            "androidCompiled": f"fixtures/android/{stem}.compiled.json",
            "androidLocalizedCompiled": f"fixtures/android/{stem}.localized.compiled.json",
        }
        if encoding != "UTF-8":
            skeleton["encoding"] = encoding
        manifest["sourceSkeletons"].append(skeleton)

    for format_name, identifier, suffix, rejected in (
        (
            "android",
            "android",
            "xml",
            (
                "nul-instruction",
                "noncharacter-fffe-cdata",
                "xml11-control-reference-01",
            ),
        ),
        (
            "apple_strings",
            "apple-strings",
            "strings",
            (
                "nul-attribute",
                "noncharacter-ffff-comment",
                "xml11-control-reference-11",
            ),
        ),
        (
            "apple_stringsdict",
            "apple-stringsdict",
            "stringsdict",
            (
                "start-of-heading-text",
                "vertical-tab-cdata",
                "xml11-control-reference-31",
            ),
        ),
    ):
        directory = "android" if format_name == "android" else "apple"
        for name in rejected:
            manifest["sourceSkeletonErrors"].append(
                {
                    "id": f"{identifier}-source-{PREFIX}{name}",
                    "format": format_name,
                    "input": f"fixtures/{directory}/xml-character-boundary-{name}.{suffix}",
                    "error": "INVALID_XML",
                }
            )

    write_json(MANIFEST, manifest)
    print(
        "Generated raw XML C0/noncharacter boundaries, XML 1.1 control-reference "
        "rejections, real Foundation/AAPT2 evidence, and UTF-8/UTF-16 templates."
    )


if __name__ == "__main__":
    main()
