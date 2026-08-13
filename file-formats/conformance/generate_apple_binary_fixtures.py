#!/usr/bin/env python3
"""Regenerate original, implementation-neutral Apple binary plist hex fixtures."""

from __future__ import annotations

import base64
import copy
import datetime
import json
import math
import plistlib
import re
import struct
import subprocess
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_hex(name: str, data: bytes) -> str:
    path = APPLE / f"plist-binary-{name}.hex"
    path.write_text(
        "\n".join(data[index : index + 32].hex() for index in range(0, len(data), 32))
        + "\n",
        encoding="ascii",
    )
    return f"fixtures/apple/{path.name}"


def binary(source: Path) -> bytes:
    with tempfile.TemporaryDirectory(prefix="mojito-binary-plist-") as directory:
        output = Path(directory) / "Localizable.strings"
        subprocess.run(
            ["/usr/bin/plutil", "-convert", "binary1", "-o", str(output), str(source)],
            check=True,
            capture_output=True,
            text=True,
        )
        return output.read_bytes()


def trailer(data: bytes) -> tuple[int, int, int, int, int]:
    return struct.unpack(">6xBBQQQ", data[-32:])


def replace_trailer(data: bytes, **values: int) -> bytes:
    offset, reference, count, top, table = trailer(data)
    return data[:-32] + struct.pack(
        ">6xBBQQQ",
        values.get("offset", offset),
        values.get("reference", reference),
        values.get("count", count),
        values.get("top", top),
        values.get("table", table),
    )


def normalized(values: dict[str, str]) -> str:
    if not values:
        return "// Empty localization catalog.\n"

    def escape(text: str) -> str:
        return (
            text.replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        )

    return "".join(
        f'"{escape(key)}" = "{escape(values[key])}";\n' for key in sorted(values)
    )


def normalized_stringsdict(values: dict[str, object]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<plist version="1.0">',
        "<dict>",
    ]

    def xml_line(depth: int, tag: str, value: str) -> None:
        lines.append(
            "  " * depth + f"<{tag}>{escape(value).replace(chr(13), '&#xD;')}</{tag}>"
        )

    def append_value(depth: int, value: object) -> None:
        if isinstance(value, bool):
            lines.append("  " * depth + ("<true/>" if value else "<false/>"))
        elif isinstance(value, int):
            xml_line(depth, "integer", str(value))
        elif isinstance(value, float):
            if math.isnan(value):
                rendered = "nan"
            elif math.isinf(value):
                rendered = "infinity" if value > 0 else "-infinity"
            else:
                mantissa, exponent = format(value, ".17e").split("e")
                rendered = f"{mantissa}e{int(exponent):+}"
            xml_line(depth, "real", rendered)
        elif isinstance(value, bytes):
            xml_line(depth, "data", base64.b64encode(value).decode("ascii"))
        elif isinstance(value, datetime.datetime):
            xml_line(depth, "date", value.strftime("%Y-%m-%dT%H:%M:%SZ"))
        elif isinstance(value, str):
            xml_line(depth, "string", value)
        elif isinstance(value, list):
            lines.append("  " * depth + "<array>")
            for child in value:
                append_value(depth + 1, child)
            lines.append("  " * depth + "</array>")
        elif isinstance(value, dict):
            lines.append("  " * depth + "<dict>")
            for key in sorted(value):
                xml_line(depth + 1, "key", key)
                append_value(depth + 1, value[key])
            lines.append("  " * depth + "</dict>")
        else:
            raise AssertionError(f"unsupported original plist value {value!r}")

    for name in sorted(values):
        message = values[name]
        assert isinstance(message, dict)
        xml_line(1, "key", name)
        lines.append("  <dict>")
        localized = message.get("NSStringLocalizedFormatKey")
        if isinstance(localized, str):
            xml_line(2, "key", "NSStringLocalizedFormatKey")
            xml_line(2, "string", localized)
        variables = [
            key
            for key, value in message.items()
            if isinstance(value, dict)
            and value.get("NSStringFormatSpecTypeKey") == "NSStringPluralRuleType"
        ]
        for variable in variables:
            rule = message[variable]
            assert isinstance(rule, dict)
            xml_line(2, "key", variable)
            lines.append("    <dict>")
            xml_line(3, "key", "NSStringFormatSpecTypeKey")
            xml_line(3, "string", "NSStringPluralRuleType")
            if "NSStringFormatValueTypeKey" in rule:
                xml_line(3, "key", "NSStringFormatValueTypeKey")
                xml_line(3, "string", rule["NSStringFormatValueTypeKey"])
            categories = ("zero", "one", "two", "few", "many", "other")
            for category in categories:
                if category in rule:
                    xml_line(3, "key", category)
                    xml_line(3, "string", rule[category])
            rule_extras = set(rule) - {
                "NSStringFormatSpecTypeKey",
                "NSStringFormatValueTypeKey",
                *categories,
            }
            for key in sorted(rule_extras):
                xml_line(3, "key", key)
                append_value(3, rule[key])
            lines.append("    </dict>")
        for field, width in (
            ("NSStringVariableWidthRuleType", True),
            ("NSStringDeviceSpecificRuleType", False),
        ):
            variants = message.get(field)
            if not isinstance(variants, dict):
                continue
            xml_line(2, "key", field)
            lines.append("    <dict>")
            keys = sorted(
                variants,
                key=(lambda key: (int(key), key)) if width else lambda key: key,
            )
            for key in keys:
                xml_line(3, "key", key)
                xml_line(3, "string", variants[key])
            lines.append("    </dict>")
        message_extras = set(message) - {
            "NSStringLocalizedFormatKey",
            "NSStringVariableWidthRuleType",
            "NSStringDeviceSpecificRuleType",
            *variables,
        }
        for key in sorted(message_extras):
            xml_line(2, "key", key)
            append_value(2, message[key])
        lines.append("  </dict>")
    lines.extend(("</dict>", "</plist>", ""))
    return "\n".join(lines)


def stringsdict_catalog(
    original: dict[str, object],
    values: dict[str, object],
) -> dict[str, object]:
    expected = copy.deepcopy(original)
    for message_id, raw_message in values.items():
        assert isinstance(raw_message, dict)
        descriptor = expected["messages"][message_id]
        metadata = descriptor["metadata"]
        rules = metadata.get("applePluralRules", {})
        for variable, rule in rules.items():
            source = raw_message[variable]
            assert isinstance(source, dict)
            extras = {
                key: canonical_plist_value(value)
                for key, value in source.items()
                if key
                not in {
                    "NSStringFormatSpecTypeKey",
                    "NSStringFormatValueTypeKey",
                    "zero",
                    "one",
                    "two",
                    "few",
                    "many",
                    "other",
                }
            }
            if extras:
                rule["applePlistExtras"] = extras
        extras = {
            key: canonical_plist_value(value)
            for key, value in raw_message.items()
            if key
            not in {
                "NSStringLocalizedFormatKey",
                "NSStringVariableWidthRuleType",
                "NSStringDeviceSpecificRuleType",
                *rules,
            }
        }
        if extras:
            metadata["applePlistExtras"] = extras
    return expected


def canonical_plist_value(value: object) -> object:
    if isinstance(value, dict):
        fields = {
            key: canonical_plist_value(field) for key, field in sorted(value.items())
        }
        if "$applePlistType" not in fields:
            return fields
        return {
            "$applePlistType": "dictionary",
            "entries": [{"key": key, "value": field} for key, field in fields.items()],
        }
    if isinstance(value, list):
        return [canonical_plist_value(field) for field in value]
    if isinstance(value, bytes):
        return {
            "$applePlistType": "data",
            "base64": base64.b64encode(value).decode("ascii"),
        }
    if isinstance(value, datetime.datetime):
        return {
            "$applePlistType": "date",
            "value": value.strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
    if isinstance(value, float):
        return {
            "$applePlistType": "real",
            "bits": struct.pack(">d", value).hex(),
        }
    return value


def valid(
    name: str,
    source: str,
    data: bytes,
    expected: str,
    compiled: str,
    normalized_path: str,
    samples: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    case: dict[str, object] = {
        "id": f"apple-strings-binary-{name}",
        "format": "apple_strings",
        "input": source,
        "encoding": "BINARY_PLIST",
        "binaryFixture": write_hex(name, data),
        "expected": expected,
        "appleCompiled": compiled,
        "appleNormalized": normalized_path,
    }
    if samples:
        case["appleStringsRuntimeSamples"] = samples
    return case


def invalid(
    name: str, data: bytes, error: str, accepted: bool = False
) -> dict[str, object]:
    case: dict[str, object] = {
        "id": f"apple-strings-binary-{name}",
        "format": "apple_strings",
        "input": "fixtures/apple/plist-binary-base.strings",
        "encoding": "BINARY_PLIST",
        "binaryFixture": write_hex(name, data),
        "error": error,
    }
    if accepted:
        case["appleOracle"] = "accept"
    return case


def valid_stringsdict(
    name: str,
    original: dict[str, object],
    data: bytes,
    samples: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    case: dict[str, object] = {
        "id": f"apple-stringsdict-binary-{name}",
        "format": "apple_stringsdict",
        "input": original["input"],
        "encoding": "BINARY_PLIST",
        "binaryFixture": write_hex(f"stringsdict-{name}", data),
        "expected": original["expected"],
        "appleCompiled": original["appleCompiled"],
    }
    if "appleStringsdictNormalized" in original:
        case["appleStringsdictNormalized"] = original["appleStringsdictNormalized"]
    if original.get("appleTypedPlist"):
        case["appleTypedPlist"] = True
    if samples:
        case["appleStringsdictRuntimeSamples"] = samples
    return case


def invalid_stringsdict(
    name: str,
    source: str,
    data: bytes,
    error: str,
    accepted: bool = False,
    skip_native: bool = False,
) -> dict[str, object]:
    case: dict[str, object] = {
        "id": f"apple-stringsdict-binary-{name}",
        "format": "apple_stringsdict",
        "input": source,
        "encoding": "BINARY_PLIST",
        "binaryFixture": write_hex(f"stringsdict-{name}", data),
        "error": error,
    }
    if accepted:
        case["appleOracle"] = "accept"
    elif skip_native:
        case["appleOracle"] = "skip"
    return case


def main() -> None:
    APPLE.mkdir(parents=True, exist_ok=True)
    original_manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    original_cases = {case["id"]: case for case in original_manifest["cases"]}
    base_source = APPLE / "plist-binary-base.strings"
    base_source.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<plist version="1.0"><dict><key>route</key><string>north</string></dict></plist>\n',
        encoding="utf-8",
    )
    base = binary(base_source)
    base_values = {"route": "north"}
    write_json(
        APPLE / "plist-binary-base.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_strings",
            "messages": {"route": {"defaultMessage": "north"}},
        },
    )
    write_json(APPLE / "plist-binary-base.compiled.json", base_values)
    (APPLE / "plist-binary-base.normalized.strings").write_text(
        normalized(base_values), encoding="utf-8"
    )

    textual_values = {
        key: f"Plain {key} harbor"
        for key in ("bplist", "bplist0", "bplist00", "bplist10", "bplista")
    }
    textual_source = APPLE / "plist-binary-header-prefixes.strings"
    textual_source.write_text(
        "".join(f'{key} = "{value}";\n' for key, value in textual_values.items()),
        encoding="utf-8",
    )
    (APPLE / "plist-binary-header-prefixes.normalized.strings").write_text(
        normalized(textual_values), encoding="utf-8"
    )
    write_json(APPLE / "plist-binary-header-prefixes.compiled.json", textual_values)
    write_json(
        APPLE / "plist-binary-header-prefixes.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_strings",
            "messages": {
                key: {"defaultMessage": value} for key, value in textual_values.items()
            },
        },
    )

    rich_source = "fixtures/apple/plist-xml.strings"
    rich = binary(ROOT / rich_source)
    rich_expected = json.loads(
        (APPLE / "plist-xml.expected.json").read_text(encoding="utf-8")
    )
    for descriptor in rich_expected["messages"].values():
        descriptor.pop("description", None)
    write_json(APPLE / "plist-binary-rich.expected.json", rich_expected)
    rich_normalized = "".join(
        line + "\n"
        for line in (APPLE / "plist-xml.normalized.strings")
        .read_text(encoding="utf-8")
        .splitlines()
        if not line.startswith("//")
    )
    (APPLE / "plist-binary-rich.normalized.strings").write_text(
        rich_normalized, encoding="utf-8"
    )

    shared_source = APPLE / "plist-binary-shared.strings"
    shared_values = {"east": "steady", "west": "steady"}
    shared_source.write_bytes(
        plistlib.dumps(shared_values, fmt=plistlib.FMT_XML, sort_keys=True)
    )
    shared = binary(shared_source)
    write_json(APPLE / "plist-binary-shared.compiled.json", shared_values)
    write_json(
        APPLE / "plist-binary-shared.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_strings",
            "messages": {
                key: {"defaultMessage": value} for key, value in shared_values.items()
            },
        },
    )
    (APPLE / "plist-binary-shared.normalized.strings").write_text(
        normalized(shared_values), encoding="utf-8"
    )

    wide_source = APPLE / "plist-binary-wide.strings"
    wide_values = {f"signal.{index:03}": f"Beacon {index:03}" for index in range(128)}
    wide_values["signal.format"] = "Follow %@ through 🧭 water"
    wide_source.write_bytes(
        plistlib.dumps(wide_values, fmt=plistlib.FMT_XML, sort_keys=True)
    )
    wide = binary(wide_source)
    assert trailer(wide)[1] == 2, (
        "wide original must exercise two-byte object references"
    )
    wide_messages = {
        key: {"defaultMessage": value} for key, value in wide_values.items()
    }
    wide_messages["signal.format"] = {
        "defaultMessage": "Follow {arg0} through 🧭 water",
        "placeholders": [
            {"name": "arg0", "source": "%@", "kind": "string", "position": 1}
        ],
    }
    write_json(
        APPLE / "plist-binary-wide.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_strings",
            "messages": wide_messages,
        },
    )
    write_json(APPLE / "plist-binary-wide.compiled.json", wide_values)
    (APPLE / "plist-binary-wide.normalized.strings").write_text(
        normalized(wide_values), encoding="utf-8"
    )

    cases = [
        {
            "id": "apple-strings-textual-bplist-header-prefixes",
            "format": "apple_strings",
            "input": "fixtures/apple/plist-binary-header-prefixes.strings",
            "expected": "fixtures/apple/plist-binary-header-prefixes.expected.json",
            "appleCompiled": "fixtures/apple/plist-binary-header-prefixes.compiled.json",
            "appleNormalized": "fixtures/apple/plist-binary-header-prefixes.normalized.strings",
        },
        valid(
            "rich-property-list",
            rich_source,
            rich,
            "fixtures/apple/plist-binary-rich.expected.json",
            "fixtures/apple/plist-xml.compiled.json",
            "fixtures/apple/plist-binary-rich.normalized.strings",
            [
                {
                    "message": "route.format",
                    "values": {"arg0": "Morgan"},
                    "expected": "Guide Morgan through <amber> & quiet\rwater",
                },
                {
                    "message": "route.positioned",
                    "values": {"arg0": 4, "arg1": "River"},
                    "expected": "Guide River past 4 steady signals",
                },
                {
                    "message": "route.literal.apostrophe",
                    "values": {"arg0": "River"},
                    "expected": "It's <quiet> River harbor",
                },
            ],
        ),
        valid(
            "simple-property-list",
            "fixtures/apple/plist-binary-base.strings",
            base,
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        ),
        valid(
            "shared-string-object-references",
            "fixtures/apple/plist-binary-shared.strings",
            shared,
            "fixtures/apple/plist-binary-shared.expected.json",
            "fixtures/apple/plist-binary-shared.compiled.json",
            "fixtures/apple/plist-binary-shared.normalized.strings",
        ),
        valid(
            "two-byte-references-and-extended-dictionary",
            "fixtures/apple/plist-binary-wide.strings",
            wide,
            "fixtures/apple/plist-binary-wide.expected.json",
            "fixtures/apple/plist-binary-wide.compiled.json",
            "fixtures/apple/plist-binary-wide.normalized.strings",
            [
                {
                    "message": "signal.format",
                    "values": {"arg0": "River"},
                    "expected": "Follow River through 🧭 water",
                },
            ],
        ),
        valid(
            "empty-dictionary",
            "fixtures/apple/plist-empty-xml.strings",
            binary(APPLE / "plist-empty-xml.strings"),
            "fixtures/apple/plist-empty-xml.expected.json",
            "fixtures/apple/plist-empty-xml.compiled.json",
            "fixtures/apple/plist-empty-xml.normalized.strings",
        ),
    ]

    for version in ("01", "09", "0a"):
        cases.append(
            valid(
                f"foundation-version-{version}",
                "fixtures/apple/plist-binary-base.strings",
                base[:6] + version.encode("ascii") + base[8:],
                "fixtures/apple/plist-binary-base.expected.json",
                "fixtures/apple/plist-binary-base.compiled.json",
                "fixtures/apple/plist-binary-base.normalized.strings",
            )
        )
    reserved = bytearray(base)
    reserved[-32] = 0x9A
    cases.append(
        valid(
            "ignored-reserved-trailer-bytes",
            "fixtures/apple/plist-binary-base.strings",
            bytes(reserved),
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        )
    )

    _, _, _, _, table = trailer(base)
    odd_offsets = base[:table] + b"".join(
        value.to_bytes(3, "big") for value in base[table:-32]
    )
    odd_offsets += struct.pack(">6xBBQQQ", 3, 1, 3, 0, table)
    cases.append(
        valid(
            "three-byte-offset-integers",
            "fixtures/apple/plist-binary-base.strings",
            odd_offsets,
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        )
    )

    for width in (9, 16, 255):
        values = (8, 11, 17)
        bytes_at_width = base[:table] + b"".join(
            value.to_bytes(width, "big") for value in values
        )
        bytes_at_width += struct.pack(">6xBBQQQ", width, 1, 3, 0, table)
        cases.append(
            valid(
                f"{width}-byte-offset-integers",
                "fixtures/apple/plist-binary-base.strings",
                bytes_at_width,
                "fixtures/apple/plist-binary-base.expected.json",
                "fixtures/apple/plist-binary-base.compiled.json",
                "fixtures/apple/plist-binary-base.normalized.strings",
            )
        )
        if width == 9:
            ignored = bytearray(bytes_at_width)
            for index in range(3):
                ignored[table + index * width] = 0xF1
            cases.append(
                valid(
                    "ignored-high-order-offset-integer-bytes",
                    "fixtures/apple/plist-binary-base.strings",
                    bytes(ignored),
                    "fixtures/apple/plist-binary-base.expected.json",
                    "fixtures/apple/plist-binary-base.compiled.json",
                    "fixtures/apple/plist-binary-base.normalized.strings",
                )
            )

    odd_objects = base[:8] + bytes([0xD1, 0, 0, 1, 0, 0, 2]) + base[11:table]
    odd_table = table + 4
    odd_references = (
        odd_objects
        + bytes([8, 15, 21])
        + struct.pack(">6xBBQQQ", 1, 3, 3, 0, odd_table)
    )
    cases.append(
        valid(
            "three-byte-object-references",
            "fixtures/apple/plist-binary-base.strings",
            odd_references,
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        )
    )

    for width in (9, 16, 255):
        adjustment = (width - 1) * 2
        object_bytes = (
            base[:8]
            + bytes([0xD1])
            + (1).to_bytes(width, "big")
            + (2).to_bytes(width, "big")
            + base[11:table]
        )
        table_offset = table + adjustment
        offset_width = 1 if table_offset < 256 else 2
        offset_bytes = b"".join(
            value.to_bytes(offset_width, "big")
            for value in (8, 11 + adjustment, 17 + adjustment)
        )
        bytes_at_width = (
            object_bytes
            + offset_bytes
            + struct.pack(">6xBBQQQ", offset_width, width, 3, 0, table_offset)
        )
        cases.append(
            valid(
                f"{width}-byte-object-references",
                "fixtures/apple/plist-binary-base.strings",
                bytes_at_width,
                "fixtures/apple/plist-binary-base.expected.json",
                "fixtures/apple/plist-binary-base.compiled.json",
                "fixtures/apple/plist-binary-base.normalized.strings",
            )
        )
        if width == 9:
            ignored = bytearray(bytes_at_width)
            ignored[9] = 0xE3
            ignored[9 + width] = 0xB7
            cases.append(
                valid(
                    "ignored-high-order-object-reference-bytes",
                    "fixtures/apple/plist-binary-base.strings",
                    bytes(ignored),
                    "fixtures/apple/plist-binary-base.expected.json",
                    "fixtures/apple/plist-binary-base.compiled.json",
                    "fixtures/apple/plist-binary-base.normalized.strings",
                )
            )

    for exponent in (4, 5):
        width = 1 << exponent
        objects = (
            base[:17]
            + bytes([0x5F, 0x10 | exponent])
            + (5).to_bytes(width, "big")
            + base[18:table]
        )
        table_offset = table + width + 1
        offset_width = 1 if table_offset < 256 else 2
        offsets = b"".join(value.to_bytes(offset_width, "big") for value in (8, 11, 17))
        encoded = (
            objects
            + offsets
            + struct.pack(">6xBBQQQ", offset_width, 1, 3, 0, table_offset)
        )
        cases.append(
            valid(
                f"{width}-byte-extended-string-length",
                "fixtures/apple/plist-binary-base.strings",
                encoded,
                "fixtures/apple/plist-binary-base.expected.json",
                "fixtures/apple/plist-binary-base.compiled.json",
                "fixtures/apple/plist-binary-base.normalized.strings",
            )
        )

    integer_width = 16
    dictionary_objects = (
        base[:8]
        + bytes([0xDF, 0x14])
        + (1).to_bytes(integer_width, "big")
        + base[9:table]
    )
    dictionary_table = table + integer_width + 1
    dictionary_offsets = bytes([8, 11 + integer_width + 1, 17 + integer_width + 1])
    dictionary_extended = (
        dictionary_objects
        + dictionary_offsets
        + struct.pack(">6xBBQQQ", 1, 1, 3, 0, dictionary_table)
    )
    cases.append(
        valid(
            "sixteen-byte-extended-dictionary-length",
            "fixtures/apple/plist-binary-base.strings",
            dictionary_extended,
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        )
    )

    reordered = bytearray(base)
    reordered[9:11] = bytes([0, 1])
    reordered[table : table + 3] = bytes([11, 17, 8])
    reordered = replace_trailer(bytes(reordered), top=2)
    cases.append(
        valid(
            "nonzero-top-object-index",
            "fixtures/apple/plist-binary-base.strings",
            reordered,
            "fixtures/apple/plist-binary-base.expected.json",
            "fixtures/apple/plist-binary-base.compiled.json",
            "fixtures/apple/plist-binary-base.normalized.strings",
        )
    )

    for value in (0x80, 0x91, 0xA0, 0xFF):
        data = bytearray(base)
        data[12] = value
        key = chr(value) + "oute"
        name = f"ascii-marker-latin1-byte-{value:02x}"
        values = {key: "north"}
        write_json(
            APPLE / f"plist-binary-{name}.expected.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "apple_strings",
                "messages": {key: {"defaultMessage": "north"}},
            },
        )
        write_json(APPLE / f"plist-binary-{name}.compiled.json", values)
        (APPLE / f"plist-binary-{name}.normalized.strings").write_text(
            normalized(values), encoding="utf-8"
        )
        cases.append(
            valid(
                name,
                "fixtures/apple/plist-binary-base.strings",
                bytes(data),
                f"fixtures/apple/plist-binary-{name}.expected.json",
                f"fixtures/apple/plist-binary-{name}.compiled.json",
                f"fixtures/apple/plist-binary-{name}.normalized.strings",
            )
        )

    malformed: list[tuple[str, bytes, str, bool]] = []

    def add(
        name: str,
        data: bytes,
        error: str = "INVALID_APPLE_BINARY_PLIST",
        accepted: bool = False,
    ) -> None:
        malformed.append((name, data, error, accepted))

    add("unsupported-header-version", base[:6] + b"10" + base[8:])
    add("truncated-trailer", base[:22])
    add("zero-offset-integer-width", replace_trailer(base, offset=0))
    add("zero-object-reference-width", replace_trailer(base, reference=0))
    add("inconsistent-offset-integer-width", replace_trailer(base, offset=9))
    add("inconsistent-object-reference-width", replace_trailer(base, reference=9))
    add("zero-object-count", replace_trailer(base, count=0))
    add(
        "unbounded-object-count",
        replace_trailer(base, count=65_537),
        "UNSAFE_APPLE_BINARY_PLIST",
    )
    add(
        "overflowing-object-count",
        replace_trailer(base, count=2**64 - 1),
        "UNSAFE_APPLE_BINARY_PLIST",
    )
    add("out-of-range-top-object", replace_trailer(base, top=3))
    add("offset-table-inside-header", replace_trailer(base, table=8))
    add("offset-table-overlaps-trailer", replace_trailer(base, table=len(base) - 32))
    add("trailing-object-table-padding", base[:-32] + b"\x00" + base[-32:])
    object_outside = bytearray(base)
    object_outside[table + 2] = table
    add("out-of-range-object-offset", bytes(object_outside))
    reference_outside = bytearray(base)
    reference_outside[10] = 3
    add("out-of-range-object-reference", bytes(reference_outside))
    invalid_extended = bytearray(base)
    invalid_extended[11] = 0x5F
    add("invalid-extended-length-marker", bytes(invalid_extended))
    truncated_value = bytearray(base)
    truncated_value[17] = 0x5E
    add("truncated-ascii-string", bytes(truncated_value))
    unsupported_utf8 = bytearray(base)
    unsupported_utf8[17] = 0x75
    add("unsupported-utf8-string-marker", bytes(unsupported_utf8))
    oversized_string = bytearray(base)
    oversized_string[17:23] = bytes([0x5F, 0x12, 0x00, 0x0F, 0x42, 0x41])
    add(
        "unbounded-string-character-count",
        bytes(oversized_string),
        "UNSAFE_APPLE_BINARY_PLIST",
    )
    add(
        "top-level-string-instead-of-dictionary",
        replace_trailer(base, top=1),
        "INVALID_APPLE_STRINGS",
    )

    for label, value in (
        ("integer", 7),
        ("real", 2.75),
        ("boolean", True),
        ("array", ["north"]),
        ("dictionary", {"nested": "north"}),
    ):
        path = APPLE / f"plist-binary-nonstring-{label}.strings"
        path.write_bytes(plistlib.dumps({"route": value}, fmt=plistlib.FMT_XML))
        add(f"nonstring-{label}-value", binary(path), "INVALID_APPLE_STRINGS", True)
    top_array_source = APPLE / "plist-binary-top-array.strings"
    top_array_source.write_bytes(plistlib.dumps(["north"], fmt=plistlib.FMT_XML))
    add(
        "top-level-array-instead-of-dictionary",
        binary(top_array_source),
        "INVALID_APPLE_STRINGS",
        True,
    )
    integer_source = binary(APPLE / "plist-binary-nonstring-integer.strings")
    offset_width, ref_width, _, top, integer_table = trailer(integer_source)
    root_offset = int.from_bytes(
        integer_source[
            integer_table + top * offset_width : integer_table
            + (top + 1) * offset_width
        ],
        "big",
    )
    swapped_key = bytearray(integer_source)
    first_key = root_offset + 1
    first_value = first_key + ref_width
    (
        swapped_key[first_key : first_key + ref_width],
        swapped_key[first_value : first_value + ref_width],
    ) = (
        swapped_key[first_value : first_value + ref_width],
        swapped_key[first_key : first_key + ref_width],
    )
    add("nonstring-dictionary-key", bytes(swapped_key), "INVALID_APPLE_STRINGS")

    duplicate_source = APPLE / "plist-binary-duplicate.strings"
    duplicate_source.write_bytes(
        plistlib.dumps(
            {"east": "one", "west": "two"}, fmt=plistlib.FMT_XML, sort_keys=True
        )
    )
    duplicate = bytearray(binary(duplicate_source))
    offset_width, ref_width, _, top, duplicate_table = trailer(duplicate)
    root_offset = int.from_bytes(
        duplicate[
            duplicate_table + top * offset_width : duplicate_table
            + (top + 1) * offset_width
        ],
        "big",
    )
    duplicate[root_offset + 1 + ref_width : root_offset + 1 + ref_width * 2] = (
        duplicate[root_offset + 1 : root_offset + 1 + ref_width]
    )
    add("duplicate-dictionary-keys", bytes(duplicate), "DUPLICATE_MESSAGE_ID", True)
    add(
        "empty-dictionary-key",
        binary(APPLE / "plist-invalid-empty-key.strings"),
        "INVALID_MESSAGE_ID",
        True,
    )

    unicode_source = APPLE / "plist-binary-unicode.strings"
    unicode_source.write_bytes(plistlib.dumps({"route": "🧭"}, fmt=plistlib.FMT_XML))
    invalid_unicode = bytearray(binary(unicode_source))
    offset_width, _, count, _, unicode_table = trailer(invalid_unicode)
    offsets = [
        int.from_bytes(
            invalid_unicode[
                unicode_table + index * offset_width : unicode_table
                + (index + 1) * offset_width
            ],
            "big",
        )
        for index in range(count)
    ]
    unicode_offset = next(
        offset for offset in offsets if invalid_unicode[offset] & 0xF0 == 0x60
    )
    invalid_unicode[unicode_offset + 3 : unicode_offset + 5] = b"\x00a"
    add("unpaired-utf16-surrogate", bytes(invalid_unicode))

    cases.extend(
        invalid(name, data, error, accepted)
        for name, data, error, accepted in malformed
    )
    oversized_input = invalid(
        "input-exceeds-sixteen-mebibytes", base, "UNSAFE_APPLE_BINARY_PLIST"
    )
    oversized_input["binaryPaddingBytes"] = 16 * 1024 * 1024
    cases.append(oversized_input)

    for case in cases:
        name = case["id"].removeprefix("apple-strings-binary-")
        if name.startswith("nonstring-") and name.endswith("-value"):
            kind = name.removeprefix("nonstring-").removesuffix("-value")
            case["input"] = f"fixtures/apple/plist-binary-nonstring-{kind}.strings"
        elif name == "nonstring-dictionary-key":
            case["input"] = "fixtures/apple/plist-binary-nonstring-integer.strings"
        elif name == "duplicate-dictionary-keys":
            case["input"] = "fixtures/apple/plist-binary-duplicate.strings"
        elif name == "empty-dictionary-key":
            case["input"] = "fixtures/apple/plist-invalid-empty-key.strings"
        elif name == "unpaired-utf16-surrogate":
            case["input"] = "fixtures/apple/plist-binary-unicode.strings"
        elif name == "top-level-array-instead-of-dictionary":
            case["input"] = "fixtures/apple/plist-binary-top-array.strings"

    plural = original_cases["apple-stringsdict-plural"]
    plural_bytes = binary(ROOT / plural["input"])
    plural_samples = [
        {
            "message": "files.remaining",
            "values": {"files": 1},
            "expected": "1 file remaining",
        },
        {
            "message": "files.remaining",
            "values": {"files": 4},
            "expected": "4 files remaining",
        },
    ]
    stringsdict_cases: list[dict[str, object]] = [
        valid_stringsdict("simple-plural", plural, plural_bytes, plural_samples),
    ]
    for identifier, name in (
        ("apple-stringsdict-multiple-plurals", "multiple-independent-plurals"),
        (
            "apple-stringsdict-xcode-positioned-plural-arguments-and-ordinary-formats",
            "positioned-reordered-and-repeated-plurals",
        ),
        (
            "apple-stringsdict-width-device-and-trusted-plist-doctype",
            "width-device-and-supplementary-values",
        ),
        (
            "apple-stringsdict-plural-with-device-variations",
            "plural-and-device-variations",
        ),
        (
            "apple-stringsdict-writer-leading-width-optional-types-and-safe-xml",
            "optional-types-leading-widths-and-xml-text",
        ),
    ):
        original = original_cases[identifier]
        stringsdict_cases.append(
            valid_stringsdict(
                name,
                original,
                binary(ROOT / original["input"]),
                original.get("appleStringsdictRuntimeSamples"),
            )
        )
    for version in ("01", "0a"):
        stringsdict_cases.append(
            valid_stringsdict(
                f"foundation-version-{version}",
                plural,
                plural_bytes[:6] + version.encode("ascii") + plural_bytes[8:],
                plural_samples,
            )
        )

    offset_width, reference_width, count, top, table = trailer(plural_bytes)
    offsets = [
        int.from_bytes(
            plural_bytes[
                table + index * offset_width : table + (index + 1) * offset_width
            ],
            "big",
        )
        for index in range(count)
    ]
    for width in (3, 9, 255):
        widened = plural_bytes[:table] + b"".join(
            offset.to_bytes(width, "big") for offset in offsets
        )
        widened += struct.pack(">6xBBQQQ", width, reference_width, count, top, table)
        stringsdict_cases.append(
            valid_stringsdict(f"{width}-byte-offset-integers", plural, widened)
        )

    shared_rule = plistlib.loads((ROOT / plural["input"]).read_bytes())[
        "files.remaining"
    ]
    shared_values = {"first.route": shared_rule, "second.route": shared_rule}
    shared_source = APPLE / "stringsdict-binary-shared-dictionaries.stringsdict"
    shared_source.write_bytes(
        plistlib.dumps(shared_values, fmt=plistlib.FMT_XML, sort_keys=True)
    )
    shared_binary = bytearray(binary(shared_source))
    shared_width, shared_ref, shared_count, shared_top, shared_table = trailer(
        shared_binary
    )
    shared_root = int.from_bytes(
        shared_binary[
            shared_table + shared_top * shared_width : shared_table
            + (shared_top + 1) * shared_width
        ],
        "big",
    )
    shared_entries = shared_binary[shared_root] & 0x0F
    assert shared_entries == 2, "shared nested resource requires two top-level messages"
    shared_values_offset = shared_root + 1 + shared_entries * shared_ref
    shared_binary[
        shared_values_offset + shared_ref : shared_values_offset + shared_ref * 2
    ] = shared_binary[shared_values_offset : shared_values_offset + shared_ref]
    original_descriptor = json.loads(
        (ROOT / plural["expected"]).read_text(encoding="utf-8")
    )["messages"]["files.remaining"]
    shared_expected = {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": {name: original_descriptor for name in sorted(shared_values)},
    }
    write_json(APPLE / "stringsdict-binary-shared.expected.json", shared_expected)
    write_json(APPLE / "stringsdict-binary-shared.compiled.json", shared_values)
    normalized_plural = (ROOT / plural["appleStringsdictNormalized"]).read_text(
        encoding="utf-8"
    )
    prefix = '<?xml version="1.0" encoding="UTF-8"?>\n<plist version="1.0">\n<dict>\n'
    suffix = "</dict>\n</plist>\n"
    body = normalized_plural.removeprefix(prefix).removesuffix(suffix)
    shared_normalized = (
        prefix
        + "".join(
            body.replace("<key>files.remaining</key>", f"<key>{name}</key>", 1)
            for name in sorted(shared_values)
        )
        + suffix
    )
    (APPLE / "stringsdict-binary-shared.normalized.stringsdict").write_text(
        shared_normalized, encoding="utf-8"
    )
    shared_original = {
        "input": "fixtures/apple/stringsdict-binary-shared-dictionaries.stringsdict",
        "expected": "fixtures/apple/stringsdict-binary-shared.expected.json",
        "appleCompiled": "fixtures/apple/stringsdict-binary-shared.compiled.json",
        "appleStringsdictNormalized": "fixtures/apple/stringsdict-binary-shared.normalized.stringsdict",
    }
    stringsdict_cases.append(
        valid_stringsdict(
            "shared-nested-dictionary-object",
            shared_original,
            bytes(shared_binary),
            [
                {
                    "message": "first.route",
                    "values": {"files": 1},
                    "expected": "1 file remaining",
                },
                {
                    "message": "second.route",
                    "values": {"files": 3},
                    "expected": "3 files remaining",
                },
            ],
        )
    )

    for rejected in original_manifest["cases"]:
        if (
            rejected["format"] == "apple_stringsdict"
            and "error" in rejected
            and rejected.get("appleOracle") == "accept"
            and not rejected["id"].startswith("apple-stringsdict-binary-")
            and not rejected["id"].startswith("apple-stringsdict-generated-")
        ):
            name = rejected["id"].removeprefix("apple-stringsdict-")
            stringsdict_cases.append(
                invalid_stringsdict(
                    f"native-{name}",
                    rejected["input"],
                    binary(ROOT / rejected["input"]),
                    rejected["error"],
                    True,
                )
            )

    root_offset = offsets[top]
    root_entries = plural_bytes[root_offset] & 0x0F
    root_values = root_offset + 1 + root_entries * reference_width
    cyclic = bytearray(plural_bytes)
    cyclic[root_values : root_values + reference_width] = top.to_bytes(
        reference_width, "big"
    )
    stringsdict_cases.append(
        invalid_stringsdict(
            "cyclic-dictionary-reference",
            plural["input"],
            bytes(cyclic),
            "UNSAFE_APPLE_BINARY_PLIST",
        )
    )

    duplicate_nested = bytearray(plural_bytes)
    duplicate_reference = int.from_bytes(
        duplicate_nested[root_values : root_values + reference_width], "big"
    )
    message_offset = offsets[duplicate_reference]
    duplicate_nested[
        message_offset + 1 + reference_width : message_offset + 1 + reference_width * 2
    ] = duplicate_nested[message_offset + 1 : message_offset + 1 + reference_width]
    stringsdict_cases.append(
        invalid_stringsdict(
            "duplicate-nested-dictionary-key",
            plural["input"],
            bytes(duplicate_nested),
            "DUPLICATE_MESSAGE_ID",
            True,
        )
    )

    stringsdict_cases.extend(
        [
            invalid_stringsdict(
                "top-level-string-dictionary-values",
                "fixtures/apple/plist-binary-base.strings",
                base,
                "INVALID_APPLE_STRINGSDICT",
                True,
            ),
            invalid_stringsdict(
                "top-level-array",
                "fixtures/apple/plist-binary-top-array.strings",
                binary(APPLE / "plist-binary-top-array.strings"),
                "INVALID_APPLE_STRINGSDICT",
                True,
            ),
            invalid_stringsdict(
                "truncated-trailer",
                plural["input"],
                plural_bytes[:24],
                "INVALID_APPLE_BINARY_PLIST",
            ),
            invalid_stringsdict(
                "unbounded-object-count",
                plural["input"],
                replace_trailer(plural_bytes, count=65_537),
                "UNSAFE_APPLE_BINARY_PLIST",
            ),
        ]
    )

    for depth in (63, 64):
        deep = "neutral leaf"
        for position in range(depth):
            deep = {f"level{position:02}": deep}
        root_value = plistlib.loads((ROOT / plural["input"]).read_bytes())
        root_value["files.remaining"]["futureNestedMetadata"] = deep
        source = APPLE / f"stringsdict-binary-depth-{depth}.stringsdict"
        source.write_bytes(
            plistlib.dumps(root_value, fmt=plistlib.FMT_XML, sort_keys=True)
        )
        data = binary(source)
        compiled_path = APPLE / f"stringsdict-binary-depth-{depth}.compiled.json"
        write_json(compiled_path, root_value)
        if depth == 63:
            expected_path = APPLE / "stringsdict-binary-depth-63.expected.json"
            write_json(
                expected_path,
                stringsdict_catalog(
                    json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
                    root_value,
                ),
            )
            normalized_path = (
                APPLE / "stringsdict-binary-depth-63.normalized.stringsdict"
            )
            normalized_path.write_text(
                normalized_stringsdict(root_value), encoding="utf-8"
            )
            boundary = {
                "input": f"fixtures/apple/{source.name}",
                "expected": f"fixtures/apple/{expected_path.name}",
                "appleCompiled": f"fixtures/apple/{compiled_path.name}",
                "appleStringsdictNormalized": f"fixtures/apple/{normalized_path.name}",
            }
            stringsdict_cases.append(
                valid_stringsdict("maximum-dictionary-depth", boundary, data)
            )
        else:
            stringsdict_cases.append(
                invalid_stringsdict(
                    "excessive-dictionary-depth",
                    f"fixtures/apple/{source.name}",
                    data,
                    "UNSAFE_APPLE_BINARY_PLIST",
                    True,
                )
            )

    numeric_metadata = plistlib.loads((ROOT / plural["input"]).read_bytes())
    numeric_metadata["files.remaining"]["futureInteger"] = -7
    numeric_metadata["files.remaining"]["futureEnabled"] = True
    numeric_source = APPLE / "stringsdict-binary-integer-boolean-metadata.stringsdict"
    numeric_source.write_bytes(plistlib.dumps(numeric_metadata, fmt=plistlib.FMT_XML))
    numeric_expected = (
        APPLE / "stringsdict-binary-integer-boolean-metadata.expected.json"
    )
    write_json(
        numeric_expected,
        stringsdict_catalog(
            json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
            numeric_metadata,
        ),
    )
    numeric_normalized = (
        APPLE / "stringsdict-binary-integer-boolean-metadata.normalized.stringsdict"
    )
    numeric_normalized.write_text(
        normalized_stringsdict(numeric_metadata), encoding="utf-8"
    )
    write_json(
        APPLE / "stringsdict-binary-integer-boolean-metadata.compiled.json",
        numeric_metadata,
    )
    numeric_case = {
        "input": f"fixtures/apple/{numeric_source.name}",
        "expected": f"fixtures/apple/{numeric_expected.name}",
        "appleCompiled": "fixtures/apple/stringsdict-binary-integer-boolean-metadata.compiled.json",
        "appleStringsdictNormalized": f"fixtures/apple/{numeric_normalized.name}",
    }
    stringsdict_cases.append(
        valid_stringsdict(
            "integer-and-boolean-metadata",
            numeric_case,
            binary(numeric_source),
            [
                {
                    "message": "files.remaining",
                    "values": {"files": 4},
                    "expected": "4 files remaining",
                }
            ],
        )
    )

    skeleton = plistlib.loads((ROOT / plural["input"]).read_bytes())
    skeleton_message = skeleton["files.remaining"]
    skeleton_message.update(
        {
            "futureEnabled": True,
            "futureDisabled": False,
            "futureNegative": -16,
            "futurePositive": 7,
            "futureHexadecimal": 255,
            "futureMinimum": -(1 << 63),
            "futureMaximum": (1 << 64) - 1,
            "futureLabel": "Fresh <amber> & quiet harbor",
            "futureEmptyDictionary": {},
            "futureNestedMetadata": {
                "NSStringLocalizedFormatKey": "reserved-looking nested value",
                "child": {
                    "enabled": False,
                    "count": -12,
                    "notes": "A neutral & original signal",
                },
                "\ue000": "private-use sorting",
                "\U0001f6f0": "supplementary sorting",
            },
            "futureUnknownRule": {
                "NSStringFormatSpecTypeKey": "NSStringFutureRuleType",
                "enabled": True,
            },
        }
    )
    skeleton_message["files"].update(
        {
            "NSStringFutureRuleMetadata": "Fresh <rule> & annotation",
            "futureRuleEnabled": True,
            "futureRuleNegative": -19,
            "futureRuleDictionary": {
                "NSStringFormatSpecTypeKey": "nested lookalike",
                "enabled": False,
                "count": 23,
            },
        }
    )
    skeleton_path = APPLE / "stringsdict-generated-typed-metadata.stringsdict"
    skeleton_xml = plistlib.dumps(
        skeleton, fmt=plistlib.FMT_XML, sort_keys=False
    ).decode()
    for key, spelling in {
        "futureNegative": "-0x10",
        "futurePositive": "+007",
        "futureHexadecimal": "0XfF",
    }.items():
        skeleton_xml = re.sub(
            rf"(<key>{re.escape(key)}</key>\s*<integer>)[^<]+(</integer>)",
            rf"\g<1>{spelling}\g<2>",
            skeleton_xml,
        )
    skeleton_path.write_text(skeleton_xml, encoding="utf-8")
    skeleton_expected = APPLE / "stringsdict-generated-typed-metadata.expected.json"
    write_json(
        skeleton_expected,
        stringsdict_catalog(
            json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
            skeleton,
        ),
    )
    skeleton_compiled = APPLE / "stringsdict-generated-typed-metadata.compiled.json"
    write_json(skeleton_compiled, skeleton)
    skeleton_normalized = (
        APPLE / "stringsdict-generated-typed-metadata.normalized.stringsdict"
    )
    skeleton_normalized.write_text(normalized_stringsdict(skeleton), encoding="utf-8")
    runtime_samples = [
        {
            "message": "files.remaining",
            "values": {"files": count},
            "expected": f"{count} {'file' if count == 1 else 'files'} remaining",
        }
        for count in (1, 5)
    ]
    skeleton_case = {
        "id": "apple-stringsdict-generated-lossless-typed-source-skeleton",
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{skeleton_path.name}",
        "expected": f"fixtures/apple/{skeleton_expected.name}",
        "appleCompiled": f"fixtures/apple/{skeleton_compiled.name}",
        "appleStringsdictNormalized": f"fixtures/apple/{skeleton_normalized.name}",
        "appleStringsdictRuntimeSamples": runtime_samples,
    }
    original_metadata = json.loads(skeleton_expected.read_text(encoding="utf-8"))[
        "messages"
    ]["files.remaining"]["metadata"]
    skeleton_case["writerMutations"] = [
        {
            "message": "files.remaining",
            "metadata": {
                **original_metadata,
                "applePlistExtras": {key: value},
            },
            "error": "INVALID_APPLE_STRINGSDICT_METADATA",
        }
        for key, value in (
            ("NSStringLocalizedFormatKey", "colliding ownership"),
            ("files", {"other": "colliding plural"}),
            ("unsupportedNull", None),
            ("unsupportedArray", [None]),
            ("unsupportedFloat", 1.5),
        )
    ]
    rule_collision = copy.deepcopy(original_metadata)
    rule_collision["applePluralRules"]["files"]["applePlistExtras"] = {
        "other": "colliding plural category"
    }
    skeleton_case["writerMutations"].append(
        {
            "message": "files.remaining",
            "metadata": rule_collision,
            "error": "INVALID_APPLE_STRINGSDICT_METADATA",
        }
    )
    cases.append(skeleton_case)
    stringsdict_cases.append(
        valid_stringsdict(
            "lossless-typed-source-skeleton",
            skeleton_case,
            binary(skeleton_path),
            runtime_samples,
        )
    )

    complete = copy.deepcopy(skeleton)
    complete_message = complete["files.remaining"]
    complete_message.update(
        {
            "futureArray": [
                "first neutral route",
                True,
                -12,
                0.125,
                b"\x00\x0f\x80\xff",
                datetime.datetime(2026, 8, 11, 12, 34, 56),
                {"nested": [False, "quiet harbor", -0.0]},
                [],
            ],
            "futureData": bytes(range(24)),
            "futureEmptyData": b"",
            "futureDate": datetime.datetime(2026, 8, 11, 12, 34, 56),
            "futureDateBeforeAppleEpoch": datetime.datetime(1997, 1, 2, 3, 4, 5),
            "futureReal": 0.125,
            "futurePreciseReal": 1.2345678901234567,
            "futureNegativeZero": -0.0,
            "futureTinyReal": 1e-250,
            "futurePositiveInfinity": float("inf"),
            "futureNegativeInfinity": -float("inf"),
            "futureNotANumber": float("nan"),
            "futureSentinelDictionary": {
                "$applePlistType": "real native field",
                "bits": "not an encoded number",
                "nested": {
                    "$applePlistType": "data native field",
                    "base64": "not encoded metadata",
                },
            },
        }
    )
    complete_message["files"].update(
        {
            "futureRuleArray": ["rule text", False, b"\x00\xff", 2.5],
            "futureRuleDate": datetime.datetime(2004, 5, 6, 7, 8, 9),
            "futureRuleData": b"fresh neutral bytes",
        }
    )
    complete_path = APPLE / "stringsdict-generated-complete-plist-types.stringsdict"
    complete_path.write_bytes(
        plistlib.dumps(complete, fmt=plistlib.FMT_XML, sort_keys=False)
    )
    complete_expected = (
        APPLE / "stringsdict-generated-complete-plist-types.expected.json"
    )
    write_json(
        complete_expected,
        stringsdict_catalog(
            json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
            complete,
        ),
    )
    complete_compiled = (
        APPLE / "stringsdict-generated-complete-plist-types.compiled.json"
    )
    write_json(complete_compiled, canonical_plist_value(complete))
    complete_normalized = (
        APPLE / "stringsdict-generated-complete-plist-types.normalized.stringsdict"
    )
    complete_normalized.write_text(normalized_stringsdict(complete), encoding="utf-8")
    complete_case = {
        "id": "apple-stringsdict-generated-all-native-property-list-value-types",
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{complete_path.name}",
        "expected": f"fixtures/apple/{complete_expected.name}",
        "appleCompiled": f"fixtures/apple/{complete_compiled.name}",
        "appleStringsdictNormalized": f"fixtures/apple/{complete_normalized.name}",
        "appleTypedPlist": True,
        "appleStringsdictRuntimeSamples": runtime_samples,
    }
    full_metadata = json.loads(complete_expected.read_text(encoding="utf-8"))[
        "messages"
    ]["files.remaining"]["metadata"]
    complete_case["writerMutations"] = [
        {
            "message": "files.remaining",
            "metadata": {**full_metadata, "applePlistExtras": {"invalid": value}},
            "error": "INVALID_APPLE_STRINGSDICT_METADATA",
        }
        for value in (
            {"$applePlistType": "data", "base64": "*"},
            {"$applePlistType": "data", "base64": "AB=="},
            {"$applePlistType": "date", "value": "2026-02-30T00:00:00Z"},
            {"$applePlistType": "real", "bits": "not-binary-bits"},
            {"$applePlistType": "real", "bits": "7ff8000000000001"},
            {
                "$applePlistType": "dictionary",
                "entries": [
                    {"key": "duplicate", "value": True},
                    {"key": "duplicate", "value": False},
                ],
            },
        )
    ]
    cases.append(complete_case)
    complete_binary = binary(complete_path)
    stringsdict_cases.append(
        valid_stringsdict(
            "all-native-property-list-value-types",
            complete_case,
            complete_binary,
            runtime_samples,
        )
    )

    complete_offset, complete_ref, complete_count, _, complete_table = trailer(
        complete_binary
    )
    complete_offsets = [
        int.from_bytes(
            complete_binary[
                complete_table + position * complete_offset : complete_table
                + (position + 1) * complete_offset
            ],
            "big",
        )
        for position in range(complete_count)
    ]
    double_offset = next(
        offset
        for offset in complete_offsets
        if complete_binary[offset] == 0x23
        and struct.unpack(">d", complete_binary[offset + 1 : offset + 9])[0] == 0.125
    )
    float_binary = (
        complete_binary[:double_offset]
        + b"\x22"
        + struct.pack(">f", 0.125)
        + complete_binary[double_offset + 9 : complete_table]
    )
    adjusted_offsets = [
        offset - 4 if offset > double_offset else offset for offset in complete_offsets
    ]
    adjusted_table = complete_table - 4
    float_binary += b"".join(
        offset.to_bytes(complete_offset, "big") for offset in adjusted_offsets
    )
    float_binary += struct.pack(
        ">6xBBQQQ",
        complete_offset,
        complete_ref,
        complete_count,
        trailer(complete_binary)[3],
        adjusted_table,
    )
    stringsdict_cases.append(
        valid_stringsdict(
            "single-precision-ieee-float-object",
            complete_case,
            float_binary,
            runtime_samples,
        )
    )
    invalid_float = bytearray(complete_binary)
    invalid_float[double_offset] = 0x21
    invalid_float_case = invalid_stringsdict(
        "invalid-ieee-float-object-width",
        f"fixtures/apple/{complete_path.name}",
        bytes(invalid_float),
        "INVALID_APPLE_BINARY_PLIST",
    )
    invalid_float_case["appleTypedPlist"] = True
    stringsdict_cases.append(invalid_float_case)

    fractional_binary = bytearray(complete_binary)
    target_seconds = (
        datetime.datetime(2026, 8, 11, 12, 34, 56) - datetime.datetime(2001, 1, 1)
    ).total_seconds()
    date_offset = next(
        offset
        for offset in complete_offsets
        if complete_binary[offset] == 0x33
        and struct.unpack(">d", complete_binary[offset + 1 : offset + 9])[0]
        == target_seconds
    )
    fractional_binary[date_offset + 1 : date_offset + 9] = struct.pack(
        ">d", target_seconds + 0.25
    )
    fractional_case = invalid_stringsdict(
        "fractional-date-cannot-round-trip-through-xml",
        f"fixtures/apple/{complete_path.name}",
        bytes(fractional_binary),
        "UNSUPPORTED_APPLE_PLIST_DATE_PRECISION",
        True,
    )
    fractional_case["appleTypedPlist"] = True
    stringsdict_cases.append(fractional_case)

    cyclic_array = bytearray(complete_binary)
    array_index = next(
        index
        for index, offset in enumerate(complete_offsets)
        if 0xA1 <= complete_binary[offset] <= 0xAE
    )
    array_offset = complete_offsets[array_index]
    cyclic_array[array_offset + 1 : array_offset + 1 + complete_ref] = (
        array_index.to_bytes(complete_ref, "big")
    )
    cycle_case = invalid_stringsdict(
        "cyclic-nested-array-reference",
        f"fixtures/apple/{complete_path.name}",
        bytes(cyclic_array),
        "UNSAFE_APPLE_BINARY_PLIST",
        skip_native=True,
    )
    cycle_case["appleTypedPlist"] = True
    stringsdict_cases.append(cycle_case)

    for nesting in (63, 64):
        nested_array: object = "quiet original leaf"
        for _ in range(nesting):
            nested_array = [nested_array]
        nested_values = copy.deepcopy(skeleton)
        nested_values["files.remaining"]["futureDeepArray"] = nested_array
        nested_path = APPLE / f"stringsdict-generated-array-depth-{nesting}.stringsdict"
        nested_path.write_bytes(plistlib.dumps(nested_values, fmt=plistlib.FMT_XML))
        nested_binary = binary(nested_path)
        if nesting == 63:
            nested_expected = (
                APPLE / "stringsdict-generated-array-depth-63.expected.json"
            )
            write_json(
                nested_expected,
                stringsdict_catalog(
                    json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
                    nested_values,
                ),
            )
            nested_compiled = (
                APPLE / "stringsdict-generated-array-depth-63.compiled.json"
            )
            write_json(nested_compiled, canonical_plist_value(nested_values))
            nested_normalized = (
                APPLE / "stringsdict-generated-array-depth-63.normalized.stringsdict"
            )
            nested_normalized.write_text(
                normalized_stringsdict(nested_values), encoding="utf-8"
            )
            nested_case = {
                "input": f"fixtures/apple/{nested_path.name}",
                "expected": f"fixtures/apple/{nested_expected.name}",
                "appleCompiled": f"fixtures/apple/{nested_compiled.name}",
                "appleStringsdictNormalized": f"fixtures/apple/{nested_normalized.name}",
            }
            stringsdict_cases.append(
                valid_stringsdict(
                    "maximum-array-nesting-depth", nested_case, nested_binary
                )
            )
        else:
            stringsdict_cases.append(
                invalid_stringsdict(
                    "excessive-array-nesting-depth",
                    f"fixtures/apple/{nested_path.name}",
                    nested_binary,
                    "UNSAFE_APPLE_BINARY_PLIST",
                    True,
                )
            )

    lenient_xml = complete_path.read_text(encoding="utf-8")
    lenient_xml = re.sub(
        r"(<key>futureData</key>\s*<data>)[^<]*(</data>)",
        r"\g<1> A B ? = = \g<2>",
        lenient_xml,
    )
    lenient_xml = re.sub(
        r"(<key>futureEmptyData</key>\s*<data>)[^<]*(</data>)",
        r"\g<1> AA?A \g<2>",
        lenient_xml,
    )
    lenient_xml = lenient_xml.replace(
        "<date>2026-08-11T12:34:56Z</date>",
        "<date>2026-02-30T99:99:99Z</date>",
        1,
    )
    lenient_xml = lenient_xml.replace("<real>nan</real>", "<real>NaN</real>", 1)
    lenient_xml = lenient_xml.replace("<real>inf</real>", "<real>+INFINITY</real>", 1)
    lenient_xml = lenient_xml.replace("<real>-inf</real>", "<real>-InFiNiTy</real>", 1)
    lenient_path = APPLE / "stringsdict-generated-foundation-lenient-values.stringsdict"
    lenient_path.write_text(lenient_xml, encoding="utf-8")
    lenient_binary = binary(lenient_path)
    lenient_values = plistlib.loads(lenient_binary)
    lenient_expected = (
        APPLE / "stringsdict-generated-foundation-lenient-values.expected.json"
    )
    write_json(
        lenient_expected,
        stringsdict_catalog(
            json.loads((ROOT / plural["expected"]).read_text(encoding="utf-8")),
            lenient_values,
        ),
    )
    lenient_compiled = (
        APPLE / "stringsdict-generated-foundation-lenient-values.compiled.json"
    )
    write_json(lenient_compiled, canonical_plist_value(lenient_values))
    lenient_normalized = (
        APPLE / "stringsdict-generated-foundation-lenient-values.normalized.stringsdict"
    )
    lenient_normalized.write_text(
        normalized_stringsdict(lenient_values), encoding="utf-8"
    )
    lenient_case = {
        "id": "apple-stringsdict-generated-foundation-lenient-base64-and-date-rollover",
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{lenient_path.name}",
        "expected": f"fixtures/apple/{lenient_expected.name}",
        "appleCompiled": f"fixtures/apple/{lenient_compiled.name}",
        "appleStringsdictNormalized": f"fixtures/apple/{lenient_normalized.name}",
        "appleTypedPlist": True,
        "appleStringsdictRuntimeSamples": runtime_samples,
    }
    cases.append(lenient_case)
    stringsdict_cases.append(
        valid_stringsdict(
            "foundation-lenient-base64-and-date-rollover",
            lenient_case,
            lenient_binary,
            runtime_samples,
        )
    )

    complete_xml = complete_path.read_text(encoding="utf-8")
    for suffix, original, replacement in (
        ("positive-nan-spelling", "<real>nan</real>", "<real>+nan</real>"),
        ("negative-nan-spelling", "<real>nan</real>", "<real>-nan</real>"),
        (
            "fractional-xml-date",
            "<date>2026-08-11T12:34:56Z</date>",
            "<date>2026-08-11T12:34:56.5Z</date>",
        ),
        ("non-ascii-base64", "AP8=", "é"),
    ):
        path = APPLE / f"stringsdict-generated-{suffix}.stringsdict"
        path.write_text(
            complete_xml.replace(original, replacement, 1), encoding="utf-8"
        )
        cases.append(
            {
                "id": f"apple-stringsdict-generated-{suffix}",
                "format": "apple_stringsdict",
                "input": f"fixtures/apple/{path.name}",
                "error": "INVALID_APPLE_STRINGSDICT",
                "appleTypedPlist": True,
            }
        )

    invalid_integer_spellings = {
        "integer-negative-overflow": "-9223372036854775809",
        "integer-unsigned-overflow": "18446744073709551616",
        "integer-interior-whitespace": " 7 ",
        "integer-malformed-hexadecimal": "0x",
    }
    integer_marker = "<key>futureNegative</key>"
    for suffix, spelling in invalid_integer_spellings.items():
        path = APPLE / f"stringsdict-generated-{suffix}.stringsdict"
        path.write_text(
            re.sub(
                rf"({integer_marker}\s*<integer>)[^<]+(</integer>)",
                rf"\g<1>{spelling}\g<2>",
                skeleton_xml,
            ),
            encoding="utf-8",
        )
        cases.append(
            {
                "id": f"apple-stringsdict-generated-{suffix}",
                "format": "apple_stringsdict",
                "input": f"fixtures/apple/{path.name}",
                "error": "INVALID_APPLE_STRINGSDICT",
            }
        )

    for suffix, field, replacement in (
        ("typed-format-value", "NSStringFormatValueTypeKey", 7),
        ("typed-plural-category", "one", True),
        ("typed-localized-format", "NSStringLocalizedFormatKey", False),
    ):
        invalid_value = copy.deepcopy(skeleton)
        if field == "NSStringLocalizedFormatKey":
            invalid_value["files.remaining"][field] = replacement
        else:
            invalid_value["files.remaining"]["files"][field] = replacement
        path = APPLE / f"stringsdict-generated-{suffix}.stringsdict"
        path.write_bytes(plistlib.dumps(invalid_value, fmt=plistlib.FMT_XML))
        cases.append(
            {
                "id": f"apple-stringsdict-generated-{suffix}",
                "format": "apple_stringsdict",
                "input": f"fixtures/apple/{path.name}",
                "error": "INVALID_APPLE_STRINGSDICT",
                "appleOracle": "accept",
            }
        )
        stringsdict_cases.append(
            invalid_stringsdict(
                f"{suffix}-preserves-native-types",
                f"fixtures/apple/{path.name}",
                binary(path),
                "INVALID_APPLE_STRINGSDICT",
                True,
            )
        )

    graph: object = "neutral leaf"
    for _ in range(17):
        graph = {"left": graph, "right": "neutral leaf"}
    graph_source = APPLE / "stringsdict-binary-shared-graph.stringsdict"
    graph_source.write_bytes(
        plistlib.dumps(graph, fmt=plistlib.FMT_XML, sort_keys=True)
    )
    graph_binary = bytearray(binary(graph_source))
    graph_width, graph_ref, graph_count, _, graph_table = trailer(graph_binary)
    graph_offsets = [
        int.from_bytes(
            graph_binary[
                graph_table + position * graph_width : graph_table
                + (position + 1) * graph_width
            ],
            "big",
        )
        for position in range(graph_count)
    ]
    for offset in graph_offsets:
        if graph_binary[offset] != 0xD2:
            continue
        references = offset + 1 + 2 * graph_ref
        graph_binary[references : references + graph_ref] = graph_binary[
            references + graph_ref : references + graph_ref * 2
        ]
    stringsdict_cases.append(
        invalid_stringsdict(
            "shared-object-graph-expansion-limit",
            f"fixtures/apple/{graph_source.name}",
            bytes(graph_binary),
            "UNSAFE_APPLE_BINARY_PLIST",
            skip_native=True,
        )
    )

    cases.extend(stringsdict_cases)

    manifest_path = ROOT / "manifest.json"
    lines = manifest_path.read_text(encoding="utf-8").splitlines()
    lines = [
        line
        for line in lines
        if '"id": "apple-strings-binary-' not in line
        and '"id": "apple-strings-textual-bplist-header-prefixes"' not in line
        and '"id": "apple-stringsdict-binary-' not in line
        and '"id": "apple-stringsdict-generated-' not in line
    ]
    anchor = next(
        index
        for index, line in enumerate(lines)
        if '"id": "apple-strings-xml-property-list"' in line
    )
    lines[anchor + 1 : anchor + 1] = [
        "    " + json.dumps(case, ensure_ascii=False) + "," for case in cases
    ]
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"Generated {len(cases)} original Apple binary/prefix fixtures ({sum('expected' in case for case in cases)} accepted, {sum('error' in case for case in cases)} stable errors)."
    )


if __name__ == "__main__":
    main()
