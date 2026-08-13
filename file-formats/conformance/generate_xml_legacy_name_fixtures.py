#!/usr/bin/env python3
"""Pin native legacy XML name tables and empty namespace undeclaration."""

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


PREFIX = "portable-xml-legacy-name-"
TRANSLATED = "Marée calme"
LEGACY_START = (
    0x00C0,
    0x02A8,
    0x0386,
    0x0401,
    0x05D0,
    0x0621,
    0x0905,
    0x0E01,
    0x1100,
    0x2126,
    0x3007,
    0x4E00,
    0x9FA5,
    0xD7A3,
)
LEGACY_CONTINUATION = (0x00B7, 0x0300, 0x0345, 0x0387, 0x3005)
FIFTH_EDITION_ONLY = (
    0x02A9,
    0x02FF,
    0x036F,
    0x0370,
    0x037F,
    0x1FFF,
    0x203F,
    0x2070,
    0x218F,
    0x2C00,
    0x3001,
    0x9FA6,
    0xD7FF,
    0xF900,
    0xFDF0,
    0xFFFD,
)


def native(format_name: str, source: str, encoding: str | None, android, apple, aapt):
    if format_name == "android":
        return android_native(source, encoding, android, aapt)
    suffix = "strings" if format_name == "apple_strings" else "stringsdict"
    return apple_native(source, encoding, suffix, apple)


def record(
    manifest: dict,
    format_name: str,
    name: str,
    source: str,
    accepted: bool,
    android,
    apple,
    aapt: Path,
) -> None:
    platform = "android" if format_name == "android" else "apple"
    folder = ANDROID if platform == "android" else APPLE
    suffix = {
        "android": "xml",
        "apple_strings": "strings",
        "apple_stringsdict": "stringsdict",
    }[format_name]
    stem = f"xml-legacy-name-{name}"
    (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
    snapshot = native(format_name, source, None, android, apple, aapt)
    case = {
        "id": f"{format_name.replace('_', '-')}-{PREFIX}{name}",
        "format": format_name,
        "input": f"fixtures/{platform}/{stem}.{suffix}",
    }
    if accepted:
        assert snapshot is not None, (format_name, name)
        expected_name = f"{stem}.{suffix}.expected.json"
        write_json(folder / expected_name, expected(format_name))
        case["expected"] = f"fixtures/{platform}/{expected_name}"
    else:
        case["error"] = "INVALID_XML"
        case[f"{platform}Oracle"] = "accept" if snapshot is not None else "reject"
    if snapshot is not None:
        compiled_name = f"{stem}.{suffix}.compiled.json"
        write_json(folder / compiled_name, snapshot)
        case[f"{platform}Compiled"] = f"fixtures/{platform}/{compiled_name}"
    manifest["cases"].append(case)
    if not accepted and any(value in name for value in ("u02ff", "u0370", "uf900")):
        manifest["sourceSkeletonErrors"].append(
            {
                "id": f"{format_name.replace('_', '-')}-source-{PREFIX}{name}",
                "format": format_name,
                "input": case["input"],
                "error": "INVALID_XML",
            }
        )


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
    platform = "android" if format_name == "android" else "apple"
    folder = ANDROID if platform == "android" else APPLE
    suffix = "xml" if format_name == "android" else "strings"
    stem = f"xml-legacy-name-source-{name}"
    localized = source.replace(
        "Café tide", f'"{TRANSLATED}"' if format_name == "android" else TRANSLATED
    )
    (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
    (folder / f"{stem}.localized.{suffix}").write_text(localized, encoding="utf-8")
    write_json(folder / f"{stem}.translations.json", {"signal": TRANSLATED})
    encoding_name, codec, bom = {
        None: ("UTF-8", "utf-8", 0),
        "UTF-16LE": ("UTF-16LE", "utf-16-le", 0),
        "UTF-16BE-BOM": ("UTF-16BE-BOM", "utf-16-be", 2),
    }[encoding]
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
    assert original is not None and translated is not None, (format_name, name)
    write_json(folder / f"{stem}.compiled.json", original)
    write_json(folder / f"{stem}.localized.compiled.json", translated)
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
        for point in (*LEGACY_START, *LEGACY_CONTINUATION, *FIFTH_EDITION_ONLY):
            character = chr(point)
            label = f"u{point:04x}"
            for kind, name, accepted in (
                ("pi-start", f"{character}route", point in LEGACY_START),
                (
                    "pi-continue",
                    f"r{character}oute",
                    point in LEGACY_START or point in LEGACY_CONTINUATION,
                ),
                ("attribute-start", f"{character}route", point in LEGACY_START),
                (
                    "attribute-continue",
                    f"r{character}oute",
                    point in LEGACY_START or point in LEGACY_CONTINUATION,
                ),
            ):
                if kind.startswith("pi-"):
                    source = f"<?{name} channel?>" + root
                else:
                    old, new = (
                        ("<resources>", f'<resources {name}="north">')
                        if format_name == "android"
                        else ("<plist version=", f'<plist {name}="north" version=')
                    )
                    source = root.replace(old, new, 1)
                record(
                    manifest,
                    format_name,
                    f"{kind}-{label}",
                    source,
                    accepted,
                    android,
                    apple,
                    aapt,
                )

        if format_name == "android":
            values = (
                (
                    "empty-default-root",
                    root.replace("<resources>", '<resources xmlns="">'),
                ),
                (
                    "empty-default-nested",
                    root.replace("<resources>", '<resources xmlns="">').replace(
                        '<string name="signal">', '<string xmlns="" name="signal">'
                    ),
                ),
            )
        else:
            values = (
                (
                    "empty-default-root",
                    root.replace("<plist version=", '<plist xmlns="" version='),
                ),
                (
                    "empty-default-nested-reset",
                    root.replace(
                        "<plist version=", '<plist xmlns="urn:neutral" version='
                    ).replace("<dict>", '<dict xmlns="">', 1),
                ),
            )
        for name, source in values:
            record(manifest, format_name, name, source, True, android, apple, aapt)

    for format_name, name, encoding, declaration, root in (
        (
            "android",
            "android-legacy-greek-empty-default-utf8",
            None,
            '<?xml version="1.0" encoding="UTF-8"?>\n<?\u0386route channel?>\n',
            ANDROID_ROOT.replace("<resources>", '<resources xmlns="">'),
        ),
        (
            "android",
            "android-legacy-cjk-empty-default-utf16le",
            "UTF-16LE",
            '<?xml version="1.0" encoding="UTF-16LE"?>\n<?\u3007route channel?>\n',
            ANDROID_ROOT.replace("<resources>", '<resources xmlns="">'),
        ),
        (
            "apple_strings",
            "apple-hebrew-default-reset-utf8",
            None,
            '<?xml version="1.0" encoding="UTF-8"?>\n<?\u05d0route channel?>\n',
            APPLE_ROOT.replace(
                "<plist version=", '<plist xmlns="urn:neutral" version='
            ).replace("<dict>", '<dict xmlns="">', 1),
        ),
        (
            "apple_strings",
            "apple-legacy-hangul-empty-default-utf16be",
            "UTF-16BE-BOM",
            '<?xml version="1.0" encoding="UTF-16BE"?>\n<?\ud7a3route channel?>\n',
            APPLE_ROOT.replace("<plist version=", '<plist xmlns="" version='),
        ),
    ):
        source_template(
            manifest,
            format_name,
            name,
            declaration + root + "\n",
            encoding,
            android,
            apple,
            aapt,
        )

    write_json(MANIFEST, manifest)
    print(
        "Generated native legacy XML BaseChar/Ideographic/Combining/Extender "
        "boundaries, namespace undeclarations, and exact Unicode source templates."
    )


if __name__ == "__main__":
    main()
