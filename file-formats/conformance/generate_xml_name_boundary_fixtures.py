#!/usr/bin/env python3
"""Capture native XML processing-instruction and namespace name boundaries."""

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


PREFIX = "portable-xml-name-boundary-"
TRANSLATED = "Marée calme"
TARGETS = {
    "ascii": "route",
    "underscore": "_route",
    "accent": "éclair",
    "cjk": "航路",
    "combining-after": "e\u0301",
    "middle-dot-after": "r\u00b7oute",
    "xml-prefix": "xml-route",
    "emoji": "🧭",
    "joiner": "\u200croute",
    "joiner-after": "r\u200coute",
    "combining-start": "\u0301route",
    "middle-dot-start": "\u00b7route",
    "digit-start": "1route",
    "hyphen-start": "-route",
    "period-start": ".route",
    "colon-start": ":route",
    "colon-end": "route:",
    "colon-middle": "route:bay",
    "colon-double": "route:bay:light",
    "empty": "",
    "whitespace": " route",
    "reserved-lower": "xml",
    "reserved-mixed": "XmL",
    "reserved-upper": "XML",
}
VALID_TARGETS = {
    "ascii",
    "underscore",
    "accent",
    "cjk",
    "combining-after",
    "middle-dot-after",
    "xml-prefix",
}
COLON_TARGETS = {"colon-start", "colon-end", "colon-middle", "colon-double"}
ATTRIBUTES = {
    "bound": "a:route",
    "accent": "éclair",
    "cjk": "航路",
    "combining-after": "e\u0301",
    "middle-dot-after": "r\u00b7oute",
    "multiple-colons": "a:route:bay",
    "colon-start": ":route",
    "colon-end": "route:",
    "digit-start": "1route",
    "hyphen-start": "-route",
    "combining-start": "\u0301route",
    "emoji": "🧭",
    "xmlns-empty-local": "xmlns:",
    "xmlns-multiple": "xmlns:a:b",
}
VALID_ATTRIBUTES = {"bound", "accent", "cjk", "combining-after", "middle-dot-after"}
ELEMENTS = {
    "multiple-colons": "a:route:bay",
    "colon-start": ":route",
    "colon-end": "route:",
    "digit-start": "1route",
    "hyphen-start": "-route",
    "combining-start": "\u0301route",
    "emoji": "🧭",
}


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
    stem = f"xml-name-boundary-{name}"
    resource = folder / f"{stem}.{suffix}"
    resource.write_text(source, encoding="utf-8")
    snapshot = native(format_name, source, None, android, apple, aapt)
    case = {
        "id": f"{format_name.replace('_', '-')}-{PREFIX}{name}",
        "format": format_name,
        "input": f"fixtures/{platform}/{resource.name}",
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
    if not accepted and (
        name.endswith("emoji")
        or name.endswith("colon-start")
        or name.endswith("multiple-colons")
        or name.endswith("reserved-mixed")
    ):
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
    stem = f"xml-name-boundary-source-{name}"
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
        for name, target in TARGETS.items():
            valid = name in VALID_TARGETS or (
                format_name != "android" and name in COLON_TARGETS
            )
            instruction = f"<?{target} channel?>"
            record(
                manifest,
                format_name,
                f"pi-before-{name}",
                instruction + root,
                valid,
                android,
                apple,
                aapt,
            )
            if format_name == "android":
                record(
                    manifest,
                    format_name,
                    f"pi-inside-{name}",
                    root.replace("Café tide", f"Café{instruction} tide"),
                    valid,
                    android,
                    apple,
                    aapt,
                )

        for name, attribute in ATTRIBUTES.items():
            old, new = (
                (
                    "<resources>",
                    f'<resources xmlns:a="urn:neutral" {attribute}="north">',
                )
                if format_name == "android"
                else (
                    "<plist version=",
                    f'<plist xmlns:a="urn:neutral" {attribute}="north" version=',
                )
            )
            record(
                manifest,
                format_name,
                f"attribute-{name}",
                root.replace(old, new, 1),
                name in VALID_ATTRIBUTES,
                android,
                apple,
                aapt,
            )

        if format_name == "android":
            for name, element in ELEMENTS.items():
                source = root.replace(
                    "<resources>", '<resources xmlns:a="urn:neutral">', 1
                ).replace("Café tide", f"<{element}>Café tide</{element}>")
                record(
                    manifest,
                    format_name,
                    f"element-{name}",
                    source,
                    False,
                    android,
                    apple,
                    aapt,
                )

    for format_name, name, encoding, target in (
        ("android", "android-accent-utf8", None, "éclair"),
        ("android", "android-cjk-utf16le", "UTF-16LE", "航路"),
        ("apple_strings", "apple-colon-utf8", None, "route:bay:light"),
        ("apple_strings", "apple-cjk-utf16be", "UTF-16BE-BOM", "航路"),
    ):
        declaration = (
            '<?xml version="1.0" encoding="UTF-16"?>\n'
            if encoding is not None
            else '<?xml version="1.0" encoding="UTF-8"?>\n'
        )
        source_template(
            manifest,
            format_name,
            name,
            declaration + f"<?{target} channel?>\n" + roots[format_name] + "\n",
            encoding,
            android,
            apple,
            aapt,
        )

    write_json(MANIFEST, manifest)
    print(
        "Generated native XML processing-instruction/QName boundaries, explicit "
        "Foundation-permissive rejections, and byte-preserving Unicode templates."
    )


if __name__ == "__main__":
    main()
