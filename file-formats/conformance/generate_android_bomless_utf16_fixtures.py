#!/usr/bin/env python3
"""Record AAPT2's BOM-less UTF-16 transport and exact source-byte ownership."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from generate_xml_encoding_boundary_fixtures import (
    APPLE,
    APPLE_ROOT,
    ANDROID,
    MANIFEST,
    STRINGSDICT_ROOT,
    android_native,
    apple_native,
    load_oracles,
    write_json,
)


PREFIX = "portable-android-bomless-utf16-"
BODY = (
    "<resources>\n"
    "  <!-- Original neutral navigation note. -->\n"
    '  <string name="signal">Café 🧭</string>\n'
    '  <string name="route">East 🌊 shore</string>\n'
    "</resources>\n"
)
EXPECTED = {
    "schemaVersion": 1,
    "sourceFormat": "android",
    "messages": {
        "signal": {
            "defaultMessage": "Café 🧭",
            "description": "Original neutral navigation note.",
        },
        "route": {"defaultMessage": "East 🌊 shore"},
    },
}
TRANSLATIONS = {"signal": "Côte sûre", "route": "Rive 🚢 calme"}


def scenarios(endian: str):
    opposite = "UTF-16BE" if endian == "UTF-16LE" else "UTF-16LE"
    return [
        ("root-without-declaration", "", None),
        ("leading-xml-whitespace", " \t\n", None),
        ("leading-comment", "<!-- Original harbor 🧭 -->\n", None),
        ("leading-processing-instruction", "<?navigation north?>\n", None),
        (
            "generic-declaration",
            '<?xml version="1.0" encoding="UTF-16"?>\n',
            None,
        ),
        (
            "lowercase-generic-declaration",
            '<?xml version="1.0" encoding="utf-16"?>\n',
            None,
        ),
        (
            "explicit-endian-declaration",
            f'<?xml version="1.0" encoding="{endian}"?>\n',
            None,
        ),
        (
            "opposite-endian-declaration",
            f'<?xml version="1.0" encoding="{opposite}"?>\n',
            "INVALID_XML",
        ),
        (
            "false-utf8-declaration",
            '<?xml version="1.0" encoding="UTF-8"?>\n',
            "INVALID_XML",
        ),
        (
            "false-latin1-declaration",
            '<?xml version="1.0" encoding="ISO-8859-1"?>\n',
            "INVALID_XML",
        ),
        (
            "unsupported-utf8-alias",
            '<?xml version="1.0" encoding="UTF8"?>\n',
            "INVALID_XML",
        ),
        (
            "unsupported-encoding-label",
            '<?xml version="1.0" encoding="X-NEUTRAL"?>\n',
            "INVALID_XML",
        ),
    ]


def source_template(
    manifest: dict,
    name: str,
    source: str,
    encoding: str,
    module,
    executable: Path,
    line_endings: str | None = None,
) -> None:
    stem = f"android-bomless-utf16-{name}"
    source_path = ANDROID / f"{stem}.xml"
    source_path.write_text(source, encoding="utf-8")
    localized = source.replace("Café 🧭", '"Côte sûre"').replace(
        "East 🌊 shore", '"Rive 🚢 calme"'
    )
    localized_path = ANDROID / f"{stem}.localized.xml"
    localized_path.write_text(localized, encoding="utf-8")
    write_json(ANDROID / f"{stem}.translations.json", TRANSLATIONS)
    original = source.replace("\n", "\r\n") if line_endings == "CRLF" else source
    translated = (
        localized.replace("\n", "\r\n") if line_endings == "CRLF" else localized
    )
    codec = "utf-16-le" if encoding == "UTF-16LE" else "utf-16-be"
    slots = []
    for identifier, text in (("signal", "Café 🧭"), ("route", "East 🌊 shore")):
        position = original.index(text)
        start = len(original[:position].encode(codec))
        slots.append(
            {"id": identifier, "start": start, "end": start + len(text.encode(codec))}
        )
    write_json(
        ANDROID / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "encoding": encoding,
            "source": original,
            "slots": slots,
        },
    )
    original_native = android_native(original, encoding, module, executable)
    localized_native = android_native(translated, encoding, module, executable)
    assert original_native is not None and localized_native is not None
    write_json(ANDROID / f"{stem}.compiled.json", original_native)
    write_json(ANDROID / f"{stem}.localized.compiled.json", localized_native)
    case = {
        "id": f"android-source-{PREFIX}{name}",
        "format": "android",
        "input": f"fixtures/android/{stem}.xml",
        "expected": f"fixtures/android/{stem}.expected.skeleton.json",
        "translations": f"fixtures/android/{stem}.translations.json",
        "localized": f"fixtures/android/{stem}.localized.xml",
        "androidCompiled": f"fixtures/android/{stem}.compiled.json",
        "androidLocalizedCompiled": (
            f"fixtures/android/{stem}.localized.compiled.json"
        ),
        "encoding": encoding,
    }
    if line_endings is not None:
        case["lineEndings"] = line_endings
    manifest["sourceSkeletons"].append(case)


def overlay(manifest: dict, module, executable: Path) -> None:
    folder = ANDROID / "overlays"
    sources = (
        (
            "library",
            "UTF-16LE",
            '<?xml version="1.0" encoding="UTF-16LE"?>\n'
            '<resources><string name="signal">Library café</string>'
            '<string name="library_only">Distant 🧭</string></resources>\n',
        ),
        (
            "main",
            "UTF-16BE",
            "<!-- Keep source-set bytes independent. -->\n"
            '<resources><string name="signal">Main 🚢</string>'
            '<string name="route">Quiet café</string></resources>\n',
        ),
        (
            "build_type",
            None,
            '<resources><string name="signal">Debug 🌊</string></resources>\n',
        ),
    )
    inputs = []
    for source_set, encoding, source in sources:
        name = f"bomless-utf16-{source_set}.xml"
        (folder / name).write_text(source, encoding="utf-8")
        resource_path = {
            "library": "libraries/neutral/res/values/strings.xml",
            "main": "src/main/res/values/strings.xml",
            "build_type": "src/debug/res/values/strings.xml",
        }[source_set]
        value = {
            "sourceSet": source_set,
            "resourcePath": resource_path,
            "input": f"fixtures/android/overlays/{name}",
        }
        if encoding is not None:
            value["encoding"] = encoding
        inputs.append(value)
    case = {
        "id": f"android-overlay-{PREFIX}mixed-source-set-endianness",
        "inputs": inputs,
        "expected": "fixtures/android/overlays/bomless-utf16.expected.json",
        "androidLinked": "fixtures/android/overlays/bomless-utf16.linked.json",
    }
    write_json(
        folder / "bomless-utf16.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "messages": {
                "signal": {
                    "defaultMessage": "Debug 🌊",
                    "metadata": {
                        "androidResourcePath": "src/debug/res/values/strings.xml",
                        "androidResourceQualifiers": [],
                        "androidOverlaySourceSet": "build_type",
                    },
                },
                "library_only": {
                    "defaultMessage": "Distant 🧭",
                    "metadata": {
                        "androidResourcePath": "libraries/neutral/res/values/strings.xml",
                        "androidResourceQualifiers": [],
                        "androidOverlaySourceSet": "library",
                    },
                },
                "route": {
                    "defaultMessage": "Quiet café",
                    "metadata": {
                        "androidResourcePath": "src/main/res/values/strings.xml",
                        "androidResourceQualifiers": [],
                        "androidOverlaySourceSet": "main",
                    },
                },
            },
        },
    )
    with tempfile.TemporaryDirectory(prefix="mojito-bomless-overlay-") as value:
        code, linked, diagnostic = module.compile_android_overlay(
            executable, case, Path(value)
        )
    assert code == 0 and linked is not None, diagnostic
    write_json(folder / "bomless-utf16.linked.json", linked)
    manifest["androidOverlays"].append(case)


def main() -> None:
    android, apple, executable = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for field in (
        "cases",
        "sourceSkeletons",
        "sourceSkeletonErrors",
        "androidOverlays",
    ):
        manifest[field] = [case for case in manifest[field] if PREFIX not in case["id"]]

    write_json(ANDROID / "android-bomless-utf16.expected.json", EXPECTED)
    for encoding in ("UTF-16LE", "UTF-16BE"):
        endian = encoding.lower().replace("-", "")
        for name, prefix, error in scenarios(encoding):
            stem = f"android-bomless-utf16-{endian}-{name}"
            source = prefix + BODY
            (ANDROID / f"{stem}.xml").write_text(source, encoding="utf-8")
            native = android_native(source, encoding, android, executable)
            case = {
                "id": f"android-{PREFIX}{endian}-{name}",
                "format": "android",
                "input": f"fixtures/android/{stem}.xml",
                "encoding": encoding,
            }
            if error is None:
                assert native is not None, (encoding, name)
                case["expected"] = (
                    "fixtures/android/android-bomless-utf16.expected.json"
                )
            else:
                assert native is None, (encoding, name)
                case.update({"error": error, "androidOracle": "reject"})
            if native is not None:
                compiled = f"{stem}.compiled.json"
                write_json(ANDROID / compiled, native)
                case["androidCompiled"] = f"fixtures/android/{compiled}"
            manifest["cases"].append(case)

            if name in {"opposite-endian-declaration", "false-utf8-declaration"}:
                manifest["sourceSkeletonErrors"].append(
                    {
                        "id": f"android-source-{PREFIX}{endian}-{name}",
                        "format": "android",
                        "input": case["input"],
                        "encoding": encoding,
                        "error": "INVALID_XML",
                    }
                )

        for name, malformed in (
            ("odd-trailing-byte", "ODD"),
            ("unpaired-surrogate", "UNPAIRED"),
        ):
            source = BODY
            stem = f"android-bomless-utf16-{endian}-{name}"
            (ANDROID / f"{stem}.xml").write_text(source, encoding="utf-8")
            native_encoding = f"{malformed}_{encoding.replace('-', '')}"
            native = android_native(source, native_encoding, android, executable)
            assert native is not None, (native_encoding, name)
            compiled = f"{stem}.compiled.json"
            write_json(ANDROID / compiled, native)
            manifest["cases"].append(
                {
                    "id": f"android-{PREFIX}{endian}-{name}",
                    "format": "android",
                    "input": f"fixtures/android/{stem}.xml",
                    "encoding": native_encoding,
                    "error": "INVALID_ENCODING",
                    "androidOracle": "accept",
                    "androidCompiled": f"fixtures/android/{compiled}",
                }
            )

        for format_name, root, suffix in (
            ("apple_strings", APPLE_ROOT, "strings"),
            ("apple_stringsdict", STRINGSDICT_ROOT, "stringsdict"),
        ):
            stem = f"android-bomless-utf16-{endian}-foundation-rejects"
            (APPLE / f"{stem}.{suffix}").write_text(root, encoding="utf-8")
            assert apple_native(root, encoding, suffix, apple) is None
            manifest["cases"].append(
                {
                    "id": f"{format_name.replace('_', '-')}-{PREFIX}{endian}-foundation-rejects",
                    "format": format_name,
                    "input": f"fixtures/apple/{stem}.{suffix}",
                    "encoding": encoding,
                    "error": "INVALID_ENCODING",
                    "appleOracle": "reject",
                }
            )

        source_template(
            manifest,
            f"{endian}-explicit-endian",
            f'<?xml version="1.0" encoding="{encoding}"?>\n{BODY}',
            encoding,
            android,
            executable,
        )
        source_template(
            manifest,
            f"{endian}-leading-comment-crlf",
            "<!-- Preserve 🧭 before the root. -->\n" + BODY,
            encoding,
            android,
            executable,
            "CRLF",
        )

    overlay(manifest, android, executable)
    write_json(MANIFEST, manifest)
    print(
        "Generated compiler-verified BOM-less UTF-16LE/BE resources, safe "
        "malformed-byte boundaries, source templates, and mixed-endian overlays."
    )


if __name__ == "__main__":
    main()
