#!/usr/bin/env python3
"""Record AAPT2's actual ASCII-only whitespace collapsing boundary."""

from __future__ import annotations

import html
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "fixtures" / "android"
MANIFEST = ROOT / "manifest.json"
STEM = "unicode-whitespace-boundaries"

# Google currently documents all Unicode spaces as collapsible. Real AAPT2 9.3
# preserves each of these values and collapses only ASCII whitespace around it.
UNICODE_SPACES = {
    "next_line": 0x0085,
    "no_break": 0x00A0,
    "ogham": 0x1680,
    "mongolian": 0x180E,
    "en_quad": 0x2000,
    "em_space": 0x2003,
    "figure": 0x2007,
    "punctuation": 0x2008,
    "hair": 0x200A,
    "zero_width": 0x200B,
    "line_separator": 0x2028,
    "paragraph_separator": 0x2029,
    "narrow_no_break": 0x202F,
    "medium_math": 0x205F,
    "ideographic": 0x3000,
    "byte_order_mark": 0xFEFF,
}


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    lines = ['<?xml version="1.0"?>', "<resources>"]
    messages = {}
    for name, codepoint in UNICODE_SPACES.items():
        character = chr(codepoint)
        for kind, spelling in (
            ("entity", f"&#x{codepoint:X};"),
            ("escaped", f"\\u{codepoint:04X}"),
        ):
            identifier = f"{kind}_{name}"
            lines.append(
                f'  <string name="{identifier}">north{spelling}   west</string>'
            )
            messages[identifier] = {"defaultMessage": f"north{character} west"}

        identifier = f"boundary_{name}"
        lines.append(
            f'  <string name="{identifier}">   &amp;#literal {spelling}   '
            f"west   {spelling}   </string>"
        )
        messages[identifier] = {
            "defaultMessage": f"&#literal {character} west {character}"
        }

    lines.extend(
        [
            '  <string name="quoted_unicode">"  &#x2003;   west &#xA0;  "</string>',
            "</resources>",
            "",
        ]
    )
    messages["quoted_unicode"] = {"defaultMessage": "  \u2003   west \u00a0  "}
    source = "\n".join(lines)
    (ANDROID / f"{STEM}.xml").write_text(source, encoding="utf-8")
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "android",
        "messages": messages,
    }
    write_json(ANDROID / f"{STEM}.expected.json", expected)

    normalized = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for identifier, descriptor in sorted(messages.items()):
        value = html.escape(descriptor["defaultMessage"], quote=False)
        normalized.append(f'  <string name="{identifier}">"{value}"</string>')
    normalized.extend(["</resources>", ""])
    (ANDROID / f"{STEM}.normalized.xml").write_text(
        "\n".join(normalized), encoding="utf-8"
    )

    translations = {
        identifier: descriptor["defaultMessage"]
        .replace("north", "sud")
        .replace("west", "ouest")
        .replace("&#literal", "quai &")
        for identifier, descriptor in messages.items()
    }
    slots = []
    replacements = []
    for match in re.finditer(
        r'<string name="(?P<id>[^"]+)">(?P<body>.*?)</string>',
        source,
        flags=re.DOTALL,
    ):
        identifier = match.group("id")
        start, end = match.span("body")
        slots.append(
            {
                "id": identifier,
                "start": len(source[:start].encode()),
                "end": len(source[:end].encode()),
            }
        )
        replacements.append(
            (
                start,
                end,
                '"' + html.escape(translations[identifier], quote=False) + '"',
            )
        )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        encoded_slots = []
        for slot in slots:
            start, end = slot["start"], slot["end"]
            if not isinstance(start, int) or not isinstance(end, int):
                raise RuntimeError("Invalid Android Unicode-space slot")
            if encoding != "UTF-8":
                before = source.encode()[:start].decode()
                body = source.encode()[start:end].decode()
                start = 2 + len(before.encode("utf-16-le"))
                end = start + len(body.encode("utf-16-le"))
            encoded_slots.append({**slot, "start": start, "end": end})
        write_json(
            ANDROID / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "android",
                "encoding": encoding,
                "source": source,
                "slots": encoded_slots,
            },
        )
    write_json(ANDROID / f"{STEM}.translations.json", translations)
    localized = source
    for start, end, replacement in reversed(replacements):
        localized = localized[:start] + replacement + localized[end:]
    (ANDROID / f"{STEM}.localized.xml").write_text(localized, encoding="utf-8")

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    case_id = "android-aapt2-preserves-all-unicode-spaces-despite-documentation"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != case_id]
    manifest["cases"].append(
        {
            "id": case_id,
            "format": "android",
            "input": f"fixtures/android/{STEM}.xml",
            "expected": f"fixtures/android/{STEM}.expected.json",
            "androidCompiled": f"fixtures/android/{STEM}.compiled.json",
            "androidNormalized": f"fixtures/android/{STEM}.normalized.xml",
        }
    )
    skeleton_id = "android-source-skeleton-preserves-native-unicode-whitespace"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if case["id"] not in {skeleton_id, skeleton_id + "-utf16le"}
    ]
    skeleton = {
        "id": skeleton_id,
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
            "id": skeleton_id + "-utf16le",
            "encoding": "UTF-16LE-BOM",
            "expected": f"fixtures/android/{STEM}.utf16le.expected.skeleton.json",
        }
    )
    write_json(MANIFEST, manifest)


if __name__ == "__main__":
    main()
