#!/usr/bin/env python3
"""Pin Java's comment-only Character.isWhitespace and native source ownership."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PROPERTIES = ROOT / "fixtures" / "properties"
MANIFEST = ROOT / "manifest.json"
PREFIX = "properties-jdk-comment-whitespace-"
SOURCE_PREFIX = "properties-source-skeleton-preserves-comment-whitespace-"
BOUNDARIES = (0x001C, 0x001D, 0x001E, 0x001F, 0x0085, 0x00A0, 0x2007, 0x202F)
REFERENCE = (
    0x0009,
    0x000B,
    0x000C,
    0x0020,
    0x1680,
    *range(0x2000, 0x2007),
    *range(0x2008, 0x200B),
    0x2028,
    0x2029,
    0x205F,
    0x3000,
)


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def java_whitespace(character: str) -> bool:
    scalar = ord(character)
    return 0x001C <= scalar <= 0x001F or (
        character.isspace() and scalar not in {0x0085, 0x00A0, 0x2007, 0x202F}
    )


def java_strip(source: str) -> str:
    start = 0
    end = len(source)
    while start < end and java_whitespace(source[start]):
        start += 1
    while end > start and java_whitespace(source[end - 1]):
        end -= 1
    return source[start:end]


def shape_values(character: str) -> dict[str, str]:
    return {
        "left": character + "neutral note",
        "right": "neutral note" + character,
        "both": character + "neutral note" + character,
        "inner": "first" + character + "last",
        "only": character,
    }


def record(manifest: dict, name: str, entries: list[tuple[str, str, str]]) -> None:
    stem = f"comment-whitespace-{name}"
    lines = []
    messages = {}
    compiled = {}
    for identity, marker, comment in entries:
        lines.extend((marker + comment, identity + "=Quiet bay"))
        value = {"defaultMessage": "Quiet bay"}
        description = java_strip(comment)
        if description and not all(
            java_whitespace(character) for character in description
        ):
            value["description"] = description
        messages[identity] = value
        compiled[identity] = "Quiet bay"

    source = "\n".join(lines) + "\n"
    (PROPERTIES / f"{stem}.properties").write_text(source, encoding="utf-8")
    write_json(
        PROPERTIES / f"{stem}.expected.json",
        {"schemaVersion": 1, "sourceFormat": "java_properties", "messages": messages},
    )
    write_json(PROPERTIES / f"{stem}.compiled.json", compiled)
    manifest["cases"].append(
        {
            "id": PREFIX + name,
            "format": "java_properties",
            "input": f"fixtures/properties/{stem}.properties",
            "expected": f"fixtures/properties/{stem}.expected.json",
            "propertiesCompiled": f"fixtures/properties/{stem}.compiled.json",
        }
    )


def source_template(
    manifest: dict,
    name: str,
    source: str,
    values: dict[str, str],
    translations: dict[str, str],
    encoding: str,
) -> None:
    stem = f"comment-whitespace-source-{name}"
    localized = source
    for original, translated in zip(
        values.values(), translations.values(), strict=True
    ):
        localized = localized.replace("=" + original, "=" + translated, 1)
    (PROPERTIES / f"{stem}.properties").write_bytes(source.encode("utf-8"))
    (PROPERTIES / f"{stem}.localized.properties").write_bytes(localized.encode("utf-8"))
    write_json(PROPERTIES / f"{stem}.translations.json", translations)
    slots = []
    for identity, original in values.items():
        position = source.index("=" + original) + 1
        slots.append(
            {
                "id": identity,
                "start": len(source[:position].encode(encoding)),
                "end": len(source[: position + len(original)].encode(encoding)),
            }
        )
    write_json(
        PROPERTIES / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "java_properties",
            "encoding": encoding,
            "source": source,
            "slots": slots,
        },
    )
    write_json(PROPERTIES / f"{stem}.compiled.json", values)
    write_json(PROPERTIES / f"{stem}.localized.compiled.json", translations)
    case = {
        "id": SOURCE_PREFIX + name,
        "format": "java_properties",
        "input": f"fixtures/properties/{stem}.properties",
        "expected": f"fixtures/properties/{stem}.expected.skeleton.json",
        "translations": f"fixtures/properties/{stem}.translations.json",
        "localized": f"fixtures/properties/{stem}.localized.properties",
        "propertiesCompiled": f"fixtures/properties/{stem}.compiled.json",
        "propertiesLocalizedCompiled": f"fixtures/properties/{stem}.localized.compiled.json",
    }
    if encoding != "UTF-8":
        case["encoding"] = encoding
    if "\r\n" in source:
        case["lineEndings"] = "CRLF"
    manifest["sourceSkeletons"].append(case)


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(PREFIX)
    ]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SOURCE_PREFIX)
    ]

    for scalar in BOUNDARIES:
        entries = [
            (f"{kind}.{shape}", marker, comment)
            for kind, marker in (("hash", "#"), ("bang", "!"))
            for shape, comment in shape_values(chr(scalar)).items()
        ]
        record(manifest, f"u{scalar:04x}", entries)

    record(
        manifest,
        "java-strip-reference",
        [
            (f"space.u{scalar:04x}", "#", chr(scalar) + "neutral note" + chr(scalar))
            for scalar in REFERENCE
        ],
    )
    record(
        manifest,
        "mixed-consecutive-comments",
        [
            ("keep.nel", "#", "\u0085"),
            ("keep.nbsp", "!", "\u00a0"),
            ("keep.figure", "#", "\u2007"),
            ("keep.narrow", "!", "\u202f"),
            ("trim.file", "#", "\u001cnote\u001c"),
            ("trim.unit", "!", "\u001fnote\u001f"),
        ],
    )

    source_template(
        manifest,
        "utf8-no-break-comments",
        "#\u0085\nroute=Quiet bay\n!\u00a0\nanchor=East quay\n"
        "#\u2007\npier=North pier\n!\u202f\nbeacon=Low light\n",
        {
            "route": "Quiet bay",
            "anchor": "East quay",
            "pier": "North pier",
            "beacon": "Low light",
        },
        {
            "route": "Marée calme",
            "anchor": "Quai sûr",
            "pier": "Jetée nord",
            "beacon": "Lueur basse",
        },
        "UTF-8",
    )
    source_template(
        manifest,
        "utf8-java-control-comments",
        "#\u001c note\u001c\nroute=Quiet bay\n!\u001d\nanchor=East quay\n"
        "#\u001e note\u001f\npier=North pier\n",
        {"route": "Quiet bay", "anchor": "East quay", "pier": "North pier"},
        {"route": "Marée calme", "anchor": "Quai sûr", "pier": "Jetée nord"},
        "UTF-8",
    )
    source_template(
        manifest,
        "latin1-no-break-comments",
        "#\u0085\nroute=Quiet bay\n!\u00a0\nanchor=East quay\n",
        {"route": "Quiet bay", "anchor": "East quay"},
        {"route": "Marée calme", "anchor": "Quai sûr"},
        "ISO-8859-1",
    )
    source_template(
        manifest,
        "crlf-mixed-comments",
        "#\u2007 north\u2007\r\nroute=Quiet bay\r\n!\u001f clear\u001f\r\n"
        "anchor=East quay\r\n",
        {"route": "Quiet bay", "anchor": "East quay"},
        {"route": "Marée calme", "anchor": "Quai sûr"},
        "UTF-8",
    )

    write_json(MANIFEST, manifest)
    print(
        f"Generated {len(BOUNDARIES) + 2} native JDK comment-boundary fixtures, "
        "including 106 independent descriptions and four byte-preserving source templates."
    )


if __name__ == "__main__":
    main()
