#!/usr/bin/env python3
"""Verify unbounded XML declaration whitespace against actual platform parsers."""

from __future__ import annotations

import json
from pathlib import Path

from generate_xml_encoding_boundary_fixtures import (
    ANDROID,
    ANDROID_ROOT,
    APPLE,
    APPLE_ROOT,
    MANIFEST,
    STRINGSDICT_ROOT,
    android_native,
    apple_native,
    expected,
    load_oracles,
    write_json,
)


PREFIX = "portable-xml-long-declaration-"
WIDTHS = (480, 511, 512, 513, 4096, 65536)
TRANSLATED = "Marée calme"


def declaration(label: str, whitespace: str, root: str) -> str:
    return f'<?xml version="1.0"{whitespace}encoding="{label}"?>\n{root}\n'


def native(format_name: str, source: str, encoding: str | None, android, apple, aapt):
    if format_name == "android":
        return android_native(source, encoding, android, aapt)
    suffix = "strings" if format_name == "apple_strings" else "stringsdict"
    return apple_native(source, encoding, suffix, apple)


def source_template(
    manifest: dict,
    format_name: str,
    name: str,
    source: str,
    encoding: str | None,
    android,
    apple,
    aapt: Path,
) -> None:
    folder = ANDROID if format_name == "android" else APPLE
    suffix = "xml" if format_name == "android" else "strings"
    stem = f"xml-long-declaration-{name}"
    localized = source.replace(
        "Café tide", f'"{TRANSLATED}"' if format_name == "android" else TRANSLATED
    )
    (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
    (folder / f"{stem}.localized.{suffix}").write_text(localized, encoding="utf-8")
    write_json(folder / f"{stem}.translations.json", {"signal": TRANSLATED})
    codecs = {
        None: ("UTF-8", "utf-8", 0),
        "ISO-8859-1": ("ISO-8859-1", "iso-8859-1", 0),
        "UTF-16LE": ("UTF-16LE", "utf-16-le", 0),
        "UTF-16LE-BOM": ("UTF-16LE-BOM", "utf-16-le", 2),
        "UTF-16BE-BOM": ("UTF-16BE-BOM", "utf-16-be", 2),
    }
    encoding_name, codec, bom = codecs[encoding]
    position = source.index("Café tide")
    start = bom + len(source[:position].encode(codec))
    write_json(
        folder / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": format_name,
            "encoding": encoding_name,
            "source": source,
            "slots": [
                {
                    "id": "signal",
                    "start": start,
                    "end": start + len("Café tide".encode(codec)),
                }
            ],
        },
    )
    original = native(format_name, source, encoding, android, apple, aapt)
    translated = native(format_name, localized, encoding, android, apple, aapt)
    assert original is not None and translated is not None
    write_json(folder / f"{stem}.compiled.json", original)
    write_json(folder / f"{stem}.localized.compiled.json", translated)
    platform = "android" if format_name == "android" else "apple"
    case = {
        "id": f"{format_name.replace('_', '-')}-source-{PREFIX}{name}",
        "format": format_name,
        "input": f"fixtures/{platform}/{stem}.{suffix}",
        "expected": f"fixtures/{platform}/{stem}.expected.skeleton.json",
        "translations": f"fixtures/{platform}/{stem}.translations.json",
        "localized": f"fixtures/{platform}/{stem}.localized.{suffix}",
        f"{platform}Compiled": f"fixtures/{platform}/{stem}.compiled.json",
        f"{platform}LocalizedCompiled": f"fixtures/{platform}/{stem}.localized.compiled.json",
    }
    if encoding is not None:
        case["encoding"] = encoding
    manifest["sourceSkeletons"].append(case)


def main() -> None:
    android, apple, aapt = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for field in ("cases", "sourceSkeletons", "sourceSkeletonErrors"):
        manifest[field] = [case for case in manifest[field] if PREFIX not in case["id"]]

    roots = {
        "android": ANDROID_ROOT,
        "apple_strings": APPLE_ROOT,
        "apple_stringsdict": STRINGSDICT_ROOT,
    }
    for format_name, root in roots.items():
        folder = ANDROID if format_name == "android" else APPLE
        suffix = {
            "android": "xml",
            "apple_strings": "strings",
            "apple_stringsdict": "stringsdict",
        }[format_name]
        platform = "android" if format_name == "android" else "apple"
        for width in WIDTHS:
            for kind, label, encoding, error in (
                ("canonical-utf8", "UTF-8", None, None),
                ("native-latin1", "ISO-8859-1", "ISO-8859-1", None),
                ("false-utf16", "UTF-16", None, "INVALID_XML"),
                ("unknown-label", "X-NEUTRAL", None, "INVALID_XML"),
            ):
                name = f"{width}-{kind}"
                stem = f"xml-long-declaration-{name}"
                source = declaration(label, " " * width, root)
                (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
                snapshot = native(format_name, source, encoding, android, apple, aapt)
                case = {
                    "id": f"{format_name.replace('_', '-')}-{PREFIX}{name}",
                    "format": format_name,
                    "input": f"fixtures/{platform}/{stem}.{suffix}",
                }
                if encoding is not None:
                    case["encoding"] = encoding
                if error is None:
                    assert snapshot is not None, (format_name, name)
                    expected_name = f"{stem}.{suffix}.expected.json"
                    write_json(folder / expected_name, expected(format_name))
                    case["expected"] = f"fixtures/{platform}/{expected_name}"
                else:
                    assert snapshot is None, (format_name, name)
                    case.update({"error": error, f"{platform}Oracle": "reject"})
                if snapshot is not None:
                    compiled_name = f"{stem}.{suffix}.compiled.json"
                    write_json(folder / compiled_name, snapshot)
                    case[f"{platform}Compiled"] = f"fixtures/{platform}/{compiled_name}"
                manifest["cases"].append(case)
                if error is not None and width in (512, 65536):
                    manifest["sourceSkeletonErrors"].append(
                        {
                            "id": f"{format_name.replace('_', '-')}-source-{PREFIX}{name}",
                            "format": format_name,
                            "input": case["input"],
                            "error": error,
                        }
                    )

        for name, whitespace in (
            ("tab-4096", "\t" * 4096),
            ("newline-2048", "\n" * 2048),
            ("mixed-4096", " \t\r\n" * 1024),
        ):
            stem = f"xml-long-declaration-{name}"
            source = declaration("ISO-8859-1", whitespace, root)
            (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
            snapshot = native(format_name, source, "ISO-8859-1", android, apple, aapt)
            assert snapshot is not None, (format_name, name)
            expected_name = f"{stem}.{suffix}.expected.json"
            compiled_name = f"{stem}.{suffix}.compiled.json"
            write_json(folder / expected_name, expected(format_name))
            write_json(folder / compiled_name, snapshot)
            manifest["cases"].append(
                {
                    "id": f"{format_name.replace('_', '-')}-{PREFIX}{name}",
                    "format": format_name,
                    "input": f"fixtures/{platform}/{stem}.{suffix}",
                    "encoding": "ISO-8859-1",
                    "expected": f"fixtures/{platform}/{expected_name}",
                    f"{platform}Compiled": f"fixtures/{platform}/{compiled_name}",
                }
            )

    for format_name, name, label, encoding, whitespace in (
        ("android", "android-utf8", "UTF-8", None, " " * 4096),
        ("android", "android-latin1", "ISO-8859-1", "ISO-8859-1", "\t" * 4096),
        ("android", "android-bomless", "UTF-16", "UTF-16LE", " " * 4096),
        ("apple_strings", "apple-latin1", "ISO-8859-1", "ISO-8859-1", "\n" * 2048),
        (
            "apple_strings",
            "apple-bom-override",
            "X-NEUTRAL",
            "UTF-16BE-BOM",
            " " * 4096,
        ),
    ):
        source_template(
            manifest,
            format_name,
            name,
            declaration(label, whitespace, roots[format_name]),
            encoding,
            android,
            apple,
            aapt,
        )

    write_json(MANIFEST, manifest)
    print(
        "Generated native XML declaration whitespace beyond 512 bytes, true "
        "Latin-1 decoding, false-label rejection, and exact long-preamble templates."
    )


if __name__ == "__main__":
    main()
