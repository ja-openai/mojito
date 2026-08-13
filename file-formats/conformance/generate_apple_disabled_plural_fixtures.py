#!/usr/bin/env python3
"""Generate original neutral Foundation disabled-printf plural contracts."""

from __future__ import annotations

import html
import json
import plistlib
import re
import shutil
import subprocess
from pathlib import Path

from generate_apple_binary_source_skeleton_fixtures import generate as generate_binary


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "stringsdict-disabled-printf"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def escaped(value: str) -> str:
    return (
        html.escape(value, quote=False)
        .replace("\n", "&#10;")
        .replace("\r", "&#13;")
        .replace("\t", "&#9;")
    )


def main() -> None:
    plutil = shutil.which("plutil")
    if plutil is None:
        raise SystemExit("Apple plutil is required for genuine Foundation fixtures")

    source_path = APPLE / f"{STEM}.stringsdict"
    source = source_path.read_text(encoding="utf-8")
    definitions = plistlib.loads(source.encode("utf-8"))
    compiled_path = APPLE / f"{STEM}.compiled.json"
    write_json(compiled_path, definitions)

    messages = {}
    for identifier, definition in definitions.items():
        variants = {}
        disabled = {}
        for category in ("one", "other"):
            native = definition["count"][category]
            protected = native.replace("%%n", "\0")
            conversion_count = protected.count("%n")
            visible = protected.replace("%n", "").replace("\0", "%n")
            canonical = visible.replace("%d", "{count}")
            variants[category] = canonical
            if conversion_count:
                disabled[category] = [
                    {"position": len("{count}"), "source": "%n"}
                    for _ in range(conversion_count)
                ]
        metadata = {
            "appleLocalizedFormat": definition["NSStringLocalizedFormatKey"],
            "applePluralRules": {
                "count": {
                    "valueType": "d",
                    "variants": {
                        category: definition["count"][category]
                        for category in ("one", "other")
                    },
                }
            },
            "pluralVariable": "count",
            "valueType": "d",
        }
        if disabled:
            metadata["applePluralDisabledPrintfConversions"] = {"count": disabled}
        messages[identifier] = {
            "defaultMessage": "{count, plural, "
            + " ".join(
                f"{category} {{{value}}}" for category, value in variants.items()
            )
            + "} visible",
            "variants": variants,
            "placeholders": [
                {"name": "count", "source": "%d", "kind": "integer", "position": 1}
            ],
            "metadata": metadata,
        }
    write_json(
        APPLE / f"{STEM}.expected.json",
        {"schemaVersion": 1, "sourceFormat": "apple_stringsdict", "messages": messages},
    )

    legacy = []
    for identifier, definition in definitions.items():
        for category in ("zero", "one", "two", "few", "many", "other"):
            native = definition["count"]["one" if category == "one" else "other"]
            legacy.append(
                {
                    "name": f"{identifier}_count_{category}",
                    "source": native.replace("\n", " "),
                    "pluralForm": category,
                    "pluralFormOther": f"{identifier}_count_other",
                }
            )
    write_json(
        ROOT / "fixtures" / "okapi" / "apple-stringsdict-disabled-printf.json",
        {"filterConfigId": "okf_macStringdict@mojito", "units": legacy},
    )
    write_json(
        ROOT / "fixtures" / "shadow" / "apple-stringsdict-disabled-printf.json",
        {
            "sourceFormat": "apple_stringsdict",
            "canonicalUnits": len(legacy),
            "legacyUnits": len(legacy),
            "outcome": "mismatch",
            "differences": [
                {"category": "source_mismatch", "id": unit["name"]}
                for unit in sorted(legacy, key=lambda item: item["name"])
            ],
        },
    )

    normalized = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]
    for identifier, definition in definitions.items():
        normalized.extend(
            [
                f"  <key>{escaped(identifier)}</key>",
                "  <dict>",
                "    <key>NSStringLocalizedFormatKey</key>",
                f"    <string>{escaped(definition['NSStringLocalizedFormatKey'])}</string>",
                "    <key>count</key>",
                "    <dict>",
                "      <key>NSStringFormatSpecTypeKey</key>",
                "      <string>NSStringPluralRuleType</string>",
                "      <key>NSStringFormatValueTypeKey</key>",
                "      <string>d</string>",
            ]
        )
        for category in ("one", "other"):
            normalized.extend(
                [
                    f"      <key>{category}</key>",
                    f"      <string>{html.escape(definition['count'][category], quote=False)}</string>",
                ]
            )
        normalized.extend(["    </dict>", "  </dict>"])
    normalized.extend(["</dict>", "</plist>", ""])
    (APPLE / f"{STEM}.normalized.stringsdict").write_text(
        "\n".join(normalized), encoding="utf-8"
    )

    slots = []
    translations = {}
    replacements = []
    current = None
    pattern = re.compile(r"<key>([^<]+)</key>(?:<string>([^<]*)</string>)?")
    for match in pattern.finditer(source):
        key = match.group(1)
        if key.startswith("harbor."):
            current = key
        if key not in {"one", "other"} or current is None or match.group(2) is None:
            continue
        native = html.unescape(match.group(2))
        start = len(source[: match.start(2)].encode("utf-8"))
        end = len(source[: match.end(2)].encode("utf-8"))
        slots.append({"id": current, "variant": key, "start": start, "end": end})
        noun = "balise" if key == "one" else "balises"
        separator = "\n" if current == "harbor.mixed.🧭" else " "
        translated = (
            f"{{count}}{'%n' if current == 'harbor.literal' else ''}{separator}{noun}"
        )
        translations[f"{current}#{key}"] = translated
        conversion = (
            "%%n"
            if current == "harbor.literal"
            else "%n%n"
            if current == "harbor.repeated"
            else "%n"
        )
        native_translated = f"%d{conversion}{separator}{noun}"
        replacements.append((match.start(2), match.end(2), escaped(native_translated)))
    sidecar_path = APPLE / f"{STEM}.expected.skeleton.json"
    write_json(
        sidecar_path,
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_stringsdict",
            "encoding": "UTF-8",
            "source": source,
            "slots": slots,
        },
    )
    write_json(APPLE / f"{STEM}.translations.json", translations)
    localized = source
    for start, end, value in reversed(replacements):
        localized = localized[:start] + value + localized[end:]
    localized_path = APPLE / f"{STEM}.localized.stringsdict"
    localized_path.write_text(localized, encoding="utf-8")
    localized_compiled = plistlib.loads(localized.encode("utf-8"))
    localized_compiled_path = APPLE / f"{STEM}.localized.compiled.json"
    write_json(localized_compiled_path, localized_compiled)

    binary_path = APPLE / f"{STEM}.binary.stringsdict"
    subprocess.run(
        [plutil, "-convert", "binary1", "-o", str(binary_path), str(source_path)],
        check=True,
    )
    encoded = binary_path.read_bytes().hex()
    (APPLE / f"{STEM}.binary.hex").write_text(
        "\n".join(
            encoded[offset : offset + 64] for offset in range(0, len(encoded), 64)
        )
        + "\n",
        encoding="ascii",
    )
    generate_binary(
        binary_path,
        "apple_stringsdict",
        compiled_path,
        localized_compiled_path,
        sidecar_path,
        APPLE / f"{STEM}.binary.expected.skeleton.json",
        APPLE / f"{STEM}.binary.localized.stringsdict",
    )


if __name__ == "__main__":
    main()
