#!/usr/bin/env python3
"""Validate the portable fixture contract without language-specific dependencies."""

from __future__ import annotations

import base64
import datetime
import html
import json
import math
import plistlib
import re
import unicodedata
from urllib.parse import quote
from pathlib import Path

from compatibility_ledger import DOCUMENT as COMPATIBILITY_LEDGER
from compatibility_ledger import document as compatibility_document

ROOT = Path(__file__).resolve().parent
FORMATS = {
    "android",
    "apple_strings",
    "apple_stringsdict",
    "apple_xcstrings",
    "gettext_po",
    "java_properties",
    "formatjs_json",
    "yaml",
    "javascript",
    "typescript",
    "resx",
    "xtb",
}
ENCODINGS = {
    None,
    "UTF-8-BOM",
    "UTF-16LE-BOM",
    "UTF-16BE-BOM",
    "UTF-16LE",
    "UTF-16BE",
    "ISO-8859-1",
    "CP1252",
    "INVALID_UTF8",
    "ODD_UTF16LE_BOM",
    "UNPAIRED_UTF16LE_BOM",
    "ODD_UTF16LE",
    "ODD_UTF16BE",
    "UNPAIRED_UTF16LE",
    "UNPAIRED_UTF16BE",
    "BINARY_PLIST",
}
MESSAGE_FIELDS = {
    "defaultMessage",
    "description",
    "variants",
    "placeholders",
    "metadata",
}
PLACEHOLDER_FIELDS = {"name", "source", "kind", "position", "example"}
ANDROID_COLOR_NAMES = {
    "black": 0xFF000000,
    "darkgray": 0xFF444444,
    "gray": 0xFF888888,
    "lightgray": 0xFFCCCCCC,
    "white": 0xFFFFFFFF,
    "red": 0xFFFF0000,
    "green": 0xFF00FF00,
    "blue": 0xFF0000FF,
    "yellow": 0xFFFFFF00,
    "cyan": 0xFF00FFFF,
    "magenta": 0xFFFF00FF,
    "aqua": 0xFF00FFFF,
    "fuchsia": 0xFFFF00FF,
    "darkgrey": 0xFF444444,
    "grey": 0xFF888888,
    "lightgrey": 0xFFCCCCCC,
    "lime": 0xFF00FF00,
    "maroon": 0xFF800000,
    "navy": 0xFF000080,
    "olive": 0xFF808000,
    "purple": 0xFF800080,
    "silver": 0xFFC0C0C0,
    "teal": 0xFF008080,
}
APPLE_CARDINAL_CATEGORIES = {
    "en": frozenset({"one", "other"}),
    "he": frozenset({"one", "two", "other"}),
    "nb": frozenset({"one", "other"}),
    "nn": frozenset({"one", "other"}),
    "pt": frozenset({"one", "many", "other"}),
    "ru": frozenset({"one", "few", "many", "other"}),
    "sr": frozenset({"one", "few", "other"}),
}


def apple_cardinal_categories(locale: str) -> frozenset[str]:
    language = locale.replace("_", "-").split("-", 1)[0].lower()
    language = {"iw": "he", "no": "nb"}.get(language, language)
    return APPLE_CARDINAL_CATEGORIES.get(language, frozenset())


def validate_gettext_header_fields(fields: object, label: str) -> None:
    assert isinstance(fields, list) and fields, (
        f"{label}: empty GNU native header fields"
    )
    for field in fields:
        assert isinstance(field, dict) and set(field) == {
            "name",
            "value",
        }, f"{label}: invalid GNU native header field"
        name = field["name"]
        value = field["value"]
        assert isinstance(name, str) and not any(char in name for char in ":\r\n\0"), (
            f"{label}: unsafe GNU native header name"
        )
        assert name.lower() not in {
            "content-type",
            "language",
            "plural-forms",
        }, f"{label}: reserved GNU native header field"
        assert isinstance(value, str) and not any(char in value for char in "\r\0"), (
            f"{label}: unsafe GNU native header value"
        )
        assert all(":" not in line for line in value.split("\n")[1:]), (
            f"{label}: GNU native continuation injects a separate field"
        )


def validate_android_percent_occurrences(
    values: object, source: str, label: str
) -> None:
    assert isinstance(values, list) and values, (
        f"{label}: empty Android raw percent metadata"
    )
    assert all(
        isinstance(value, int) and not isinstance(value, bool) for value in values
    ), f"{label}: Android raw percent occurrences must be integers"
    assert values == sorted(set(values)), (
        f"{label}: Android raw percent occurrences must be sorted and unique"
    )
    assert all(
        0 <= value < visible_android_character_count(source, "%") for value in values
    ), f"{label}: Android raw percent occurrence is outside its canonical message"


def validate_android_protected_placeholder_occurrences(
    ownership: object, source: str, placeholders: list[dict[str, object]], label: str
) -> None:
    assert isinstance(ownership, dict) and ownership, (
        f"{label}: empty Android protected placeholder ownership"
    )
    for name, occurrences in ownership.items():
        assert (
            isinstance(name, str) and isinstance(occurrences, list) and occurrences
        ), f"{label}: invalid Android protected placeholder occurrence list"
        assert len(occurrences) == source.count("{" + name + "}"), (
            f"{label}/{name}: protected placeholder occurrence count does not match source"
        )
        assert any(occurrence is not None for occurrence in occurrences), (
            f"{label}/{name}: protected placeholder ownership contains no protected sections"
        )
        for occurrence in occurrences:
            if occurrence is None:
                example = None
            else:
                assert isinstance(occurrence, dict) and set(occurrence) <= {
                    "example"
                }, f"{label}/{name}: invalid protected occurrence metadata"
                example = occurrence.get("example")
                assert example is None or isinstance(example, str), (
                    f"{label}/{name}: invalid protected occurrence example"
                )
            assert any(
                placeholder.get("name") == name
                and placeholder.get("example") == example
                and isinstance(placeholder.get("position"), int)
                and name == "arg" + str(placeholder["position"] - 1)
                for placeholder in placeholders
            ), f"{label}/{name}: unknown conventional protected placeholder"


def visible_android_character_count(source: str, selected: str) -> int:
    source = source.replace("'<'", "<").replace("''", "'")
    count = 0
    index = 0
    while index < len(source):
        if (
            source[index] == "<"
            and index + 1 < len(source)
            and (source[index + 1].isalpha() or source[index + 1] == "/")
        ):
            quoted = False
            end = index + 1
            while end < len(source):
                if source[end] == '"':
                    quoted = not quoted
                elif source[end] == ">" and not quoted:
                    break
                end += 1
            if end < len(source):
                index = end + 1
                continue
        if source[index] == selected:
            count += 1
        index += 1
    return count


def android_runtime_annotations(source: str) -> list[dict[str, object]]:
    source = source.replace("'<'", "<").replace("''", "'")
    result = []
    span = 0
    index = 0
    while index < len(source):
        if (
            source[index] != "<"
            or index + 1 >= len(source)
            or not source[index + 1].isalpha()
        ):
            index += 1
            continue
        quoted = False
        end = index + 1
        while end < len(source):
            if source[end] == '"':
                quoted = not quoted
            elif source[end] == ">" and not quoted:
                break
            end += 1
        if end == len(source):
            break
        tag = source[index + 1 : end]
        name = tag.split(None, 1)[0]
        if name == "annotation":
            original = [
                {"key": match.group(1), "value": html.unescape(match.group(2))}
                for match in re.finditer(
                    r'([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*"([^"]*)"', tag[len(name) :]
                )
            ]
            encoded = name + "".join(
                f";{attribute['key']}={attribute['value']}" for attribute in original
            )
            runtime = []
            position = encoded.find(";")
            while position >= 0 and position < len(encoded):
                equals = encoded.find("=", position)
                if equals < 0:
                    break
                following = encoded.find(";", equals)
                if following < 0:
                    following = len(encoded)
                runtime.append(
                    {
                        "key": encoded[position + 1 : equals],
                        "value": encoded[equals + 1 : following],
                    }
                )
                position = following if following < len(encoded) else -1
            if runtime != original:
                result.append({"span": span, "annotations": runtime})
        span += 1
        index = end + 1
    return result


def android_runtime_styles(source: str) -> list[dict[str, object]]:
    source = source.replace("'<'", "<").replace("''", "'")
    result = []
    span = 0
    index = 0
    while index < len(source):
        if (
            source[index] != "<"
            or index + 1 >= len(source)
            or not source[index + 1].isalpha()
        ):
            index += 1
            continue
        quoted = False
        end = index + 1
        while end < len(source):
            if source[end] == '"':
                quoted = not quoted
            elif source[end] == ">" and not quoted:
                break
            end += 1
        if end == len(source):
            break
        tag = source[index + 1 : end]
        name = tag.split(None, 1)[0]
        if name in {"font", "a"}:
            attributes = [
                (match.group(1), html.unescape(match.group(2)))
                for match in re.finditer(
                    r'([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*"([^"]*)"', tag[len(name) :]
                )
            ]
            encoded = name + "".join(f";{key}={value}" for key, value in attributes)
            supported = (
                (("link", "href"),)
                if name == "a"
                else (
                    ("height", "height"),
                    ("size", "size"),
                    ("foreground", "fgcolor"),
                    ("foreground", "color"),
                    ("background", "bgcolor"),
                    ("face", "face"),
                )
            )
            original = []
            runtime = []
            for kind, attribute in supported:
                direct = next(
                    (value for key, value in attributes if key == attribute), None
                )
                if direct is not None:
                    effect = {"kind": kind, "attribute": attribute, "value": direct}
                    if kind in {"foreground", "background"}:
                        effect["color"] = android_runtime_color(
                            direct, kind == "foreground"
                        )
                    original.append(effect)
                marker = f";{attribute}="
                position = encoded.find(marker)
                if position >= 0:
                    value = encoded[position + len(marker) :].split(";", 1)[0]
                    effect = {"kind": kind, "attribute": attribute, "value": value}
                    if kind in {"foreground", "background"}:
                        effect["color"] = android_runtime_color(
                            value, kind == "foreground"
                        )
                    runtime.append(effect)
            if original != runtime or any("color" in effect for effect in runtime):
                result.append({"span": span, "effects": runtime})
        span += 1
        index = end + 1
    return result


def android_runtime_color(value: str, foreground: bool) -> dict[str, object]:
    if value.startswith("@"):
        package, separator, _ = value[1:].partition(":")
        if separator and package != "android":
            return {"mode": "fallback", "argb": "#ff000000"}
        return {
            "mode": "system",
            "reference": value,
            "fallbackArgb": "#ff000000",
            "stateful": foreground,
        }
    parsed = None
    if value.startswith("#") and len(value) in {7, 9}:
        digits = value[1:]
        negative = digits.startswith("-")
        digits = digits[1:] if digits.startswith(("+", "-")) else digits
        if digits:
            parsed = 0
            for character in digits:
                if ord(character) > 0xFFFF:
                    parsed = None
                    break
                try:
                    digit = unicodedata.decimal(character)
                except ValueError:
                    if "a" <= character.lower() <= "f":
                        digit = ord(character.lower()) - ord("a") + 10
                    elif "ａ" <= character.lower() <= "ｆ":
                        digit = ord(character.lower()) - ord("ａ") + 10
                    else:
                        parsed = None
                        break
                parsed = parsed * 16 + digit
            if parsed is not None:
                if negative:
                    parsed = -parsed
                if len(value) == 7:
                    parsed |= 0xFF000000
                parsed &= 0xFFFFFFFF
    elif not value.startswith("#"):
        parsed = ANDROID_COLOR_NAMES.get(value.lower())
    if parsed is None:
        return {"mode": "fallback", "argb": "#ff000000"}
    return {"mode": "literal", "argb": f"#{parsed:08x}"}


def android_runtime_paragraphs(source: str) -> list[dict[str, object]]:
    source = source.replace("'<'", "<").replace("''", "'")
    visible: list[int] = []
    opened: list[dict[str, object]] = []
    candidates: list[dict[str, object]] = []
    span = 0
    index = 0
    while index < len(source):
        if (
            source[index] != "<"
            or index + 1 >= len(source)
            or not (source[index + 1].isalpha() or source[index + 1] == "/")
        ):
            raw = source[index].encode("utf-16-le")
            visible.extend(
                int.from_bytes(raw[offset : offset + 2], "little")
                for offset in range(0, len(raw), 2)
            )
            index += 1
            continue
        quoted = False
        end = index + 1
        while end < len(source):
            if source[end] == '"':
                quoted = not quoted
            elif source[end] == ">" and not quoted:
                break
            end += 1
        if end == len(source):
            break
        closing = source[index + 1] == "/"
        tag = source[index + (2 if closing else 1) : end]
        name = re.split(r"[\s/]", tag, maxsplit=1)[0]
        if closing:
            while opened:
                current = opened.pop()
                if current["name"] == name:
                    if current["kind"] is not None:
                        current["sourceEnd"] = len(visible)
                        candidates.append(current)
                    break
        else:
            kind = None
            if name == "li" and tag.strip() == "li":
                kind = "bullet"
            elif name == "font":
                attributes = [
                    (match.group(1), html.unescape(match.group(2)))
                    for match in re.finditer(
                        r'([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*"([^"]*)"',
                        tag[len(name) :],
                    )
                ]
                encoded = name + "".join(
                    f";{attribute}={value}" for attribute, value in attributes
                )
                if ";height=" in encoded:
                    kind = "height"
            current = {
                "name": name,
                "span": span,
                "kind": kind,
                "sourceStart": len(visible),
            }
            span += 1
            if tag.endswith("/"):
                if kind is not None:
                    current["sourceEnd"] = len(visible)
                    candidates.append(current)
            else:
                opened.append(current)
        index = end + 1
    while opened:
        current = opened.pop()
        if current["kind"] is not None:
            current["sourceEnd"] = len(visible)
            candidates.append(current)
    result = []
    for current in sorted(candidates, key=lambda candidate: int(candidate["span"])):
        start = int(current["sourceStart"])
        end = int(current["sourceEnd"])
        if start not in {0, len(visible)} and visible[start - 1] != ord("\n"):
            start -= 1
            while start > 0 and visible[start - 1] != ord("\n"):
                start -= 1
        if end not in {0, len(visible)} and visible[end - 1] != ord("\n"):
            end += 1
            while end < len(visible) and visible[end - 1] != ord("\n"):
                end += 1
        if start != current["sourceStart"] or end != current["sourceEnd"]:
            result.append(
                {
                    "span": current["span"],
                    "kind": current["kind"],
                    "sourceStart": current["sourceStart"],
                    "sourceEnd": current["sourceEnd"],
                    "start": start,
                    "end": end,
                }
            )
    return result


def validate_android_line_separator_occurrences(
    values: object, source: str, label: str
) -> None:
    assert isinstance(values, list) and values, (
        f"{label}: empty Android printf line-separator metadata"
    )
    assert all(
        isinstance(value, int) and not isinstance(value, bool) for value in values
    ), f"{label}: Android printf line-separator occurrences must be integers"
    assert values == sorted(set(values)), (
        f"{label}: Android printf line-separator occurrences must be sorted and unique"
    )
    assert all(
        0 <= value < visible_android_character_count(source, "\n") for value in values
    ), (
        f"{label}: Android printf line-separator occurrence is outside its canonical message"
    )


def main() -> None:
    json.loads((ROOT / "catalog.schema.json").read_text(encoding="utf-8"))
    skeleton_schema = json.loads(
        (ROOT / "source-skeleton.schema.json").read_text(encoding="utf-8")
    )
    assert skeleton_schema["properties"]["schemaVersion"] == {"const": 1}
    overlay_skeleton_schema = json.loads(
        (ROOT / "android-overlay-source-skeleton.schema.json").read_text(
            encoding="utf-8"
        )
    )
    assert overlay_skeleton_schema["properties"]["schemaVersion"] == {"const": 1}
    assert overlay_skeleton_schema["properties"]["sourceFormat"] == {"const": "android"}
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["schemaVersion"] == 1, "Unsupported conformance manifest version"
    seen: set[str] = set()
    counts = {name: 0 for name in FORMATS}
    valid_count = 0
    error_count = 0

    workflow_cases = manifest.get("workflowCases", [])
    assert isinstance(workflow_cases, list), "Workflow compatibility contracts must be an array"
    workflow_ids = set()
    for workflow in workflow_cases:
        workflow_id = workflow.get("id")
        assert isinstance(workflow_id, str) and workflow_id, "Workflow fixtures require stable IDs"
        assert workflow_id not in workflow_ids, f"Duplicate workflow fixture ID: {workflow_id}"
        workflow_ids.add(workflow_id)
        assert workflow.get("format") in FORMATS, f"{workflow_id}: unknown workflow format"
        assert (ROOT / workflow["input"]).is_file(), f"{workflow_id}: missing workflow source"
        options = workflow.get("filterOptions")
        assert isinstance(options, list) and all(isinstance(option, str) for option in options), (
            f"{workflow_id}: filter options require strings"
        )
        if "targetLocale" in workflow:
            assert workflow["format"] == "apple_stringsdict", (
                f"{workflow_id}: locale-owned output currently applies to Foundation plural dictionaries"
            )
            assert isinstance(workflow["targetLocale"], str) and workflow["targetLocale"], (
                f"{workflow_id}: locale-owned output requires an explicit target locale"
            )
            assert "translations" in workflow, (
                f"{workflow_id}: locale-owned output requires translated source slots"
            )
        if "importPolicy" in workflow:
            policy = workflow["importPolicy"]
            assert isinstance(policy, dict), f"{workflow_id}: import policy requires an object"
            assert set(policy) == {"targetLocale", "copyFormsOnImport"}, (
                f"{workflow_id}: import policy requires explicit locale and plural-copy ownership"
            )
            assert isinstance(policy["targetLocale"], str) and policy["targetLocale"], (
                f"{workflow_id}: import policy requires a target locale"
            )
            assert isinstance(policy["copyFormsOnImport"], bool), (
                f"{workflow_id}: import plural-copy policy requires a boolean"
            )
            assert "translations" not in workflow, (
                f"{workflow_id}: import contracts must not imply translated-output integration"
            )
        if "importRoundTrip" in workflow:
            assert workflow["importRoundTrip"] is True, (
                f"{workflow_id}: import round trips require explicit true ownership"
            )
            assert "importPolicy" in workflow and "expected" in workflow, (
                f"{workflow_id}: import round trips require a valid import catalog"
            )
            assert workflow["format"] == "apple_stringsdict", (
                f"{workflow_id}: import round trips currently own Apple stringsdict only"
            )
        if "legacyImportFilter" in workflow:
            assert "importPolicy" in workflow, (
                f"{workflow_id}: actual legacy import comparison requires an import contract"
            )
            assert workflow["legacyImportFilter"] == workflow["format"], (
                f"{workflow_id}: actual legacy import filter must own the same source format"
            )
            assert workflow["format"] in {"android", "apple_stringsdict", "gettext_po"}, (
                f"{workflow_id}: unsupported actual legacy import-filter comparison"
            )
            assert "error" not in workflow and "expected" in workflow, (
                f"{workflow_id}: actual legacy import comparison requires a valid snapshot"
            )
        if "legacyMissingUsages" in workflow:
            assert workflow.get("format") == "formatjs_json" and "legacyAssetPath" in workflow, (
                f"{workflow_id}: dropped legacy usages require an actual configured JSON comparison"
            )
            usages = workflow["legacyMissingUsages"]
            assert isinstance(usages, list) and usages and all(
                isinstance(name, str) and name for name in usages
            ), f"{workflow_id}: dropped legacy usages require explicit message identities"
        if "legacyMissingPluralSelectors" in workflow:
            assert workflow.get("legacyImportFilter") == "apple_stringsdict", (
                f"{workflow_id}: missing legacy selectors require an actual Apple comparison"
            )
            selectors = workflow["legacyMissingPluralSelectors"]
            assert isinstance(selectors, list) and selectors and all(
                isinstance(selector, str) and selector for selector in selectors
            ), f"{workflow_id}: missing legacy selectors require explicit nonempty names"
        if "legacyPluralCategories" in workflow:
            assert workflow.get("legacyImportFilter") == "gettext_po", (
                f"{workflow_id}: mismatched legacy categories require an actual gettext comparison"
            )
            categories = workflow["legacyPluralCategories"]
            assert isinstance(categories, list) and categories and all(
                category in {"zero", "one", "two", "few", "many", "other"}
                for category in categories
            ), f"{workflow_id}: legacy categories require explicit CLDR category names"
            assert len(categories) == len(set(categories)), (
                f"{workflow_id}: legacy categories cannot contain duplicates"
            )
        if "legacyPluralValueDifferences" in workflow:
            assert workflow.get("legacyImportFilter") == "gettext_po", (
                f"{workflow_id}: legacy target differences require an actual gettext comparison"
            )
            differences = workflow["legacyPluralValueDifferences"]
            assert isinstance(differences, dict) and differences and all(
                isinstance(category, str) and isinstance(value, str)
                for category, value in differences.items()
            ), f"{workflow_id}: legacy target differences require explicit expected values"
        if "expected" in workflow:
            expected = ROOT / workflow["expected"]
            assert expected.is_file(), f"{workflow_id}: missing expected workflow catalog"
            catalog = json.loads(expected.read_text(encoding="utf-8"))
            assert catalog.get("schemaVersion") == 1, f"{workflow_id}: invalid workflow catalog"
            assert catalog.get("sourceFormat") == workflow["format"], (
                f"{workflow_id}: mismatched workflow catalog format"
            )
        if "localized" in workflow and workflow["localized"]:
            assert (ROOT / workflow["localized"]).is_file(), (
                f"{workflow_id}: missing localized workflow snapshot"
            )
        if "localizedEndsWithNewline" in workflow:
            assert workflow["format"] == "formatjs_json", (
                f"{workflow_id}: source-owned final newline currently applies to JSON output"
            )
            assert isinstance(workflow["localizedEndsWithNewline"], bool), (
                f"{workflow_id}: final-newline ownership requires an explicit boolean"
            )
            assert "translations" in workflow and workflow.get("removeUntranslated") is True, (
                f"{workflow_id}: final-newline ownership requires translated JSON cleanup"
            )
        if "legacyLocalizedOutput" in workflow:
            assert workflow["format"] == "android", (
                f"{workflow_id}: exact legacy postprocessor comparison currently owns Android"
            )
            assert "translations" in workflow and "localized" in workflow, (
                f"{workflow_id}: exact legacy output comparison requires translated snapshots"
            )
            assert isinstance(workflow["legacyLocalizedOutput"], str), (
                f"{workflow_id}: exact legacy output must be a string"
            )
        if "legacyLocalizedOutputContains" in workflow:
            assert workflow["format"] in {"android", "apple_strings", "formatjs_json"}, (
                f"{workflow_id}: legacy postprocessor comparison requires Android, Apple strings, or JSON"
            )
            assert "translations" in workflow and "removeUntranslated" in workflow, (
                f"{workflow_id}: legacy postprocessor comparison requires explicit cleanup policy"
            )
            values = workflow["legacyLocalizedOutputContains"]
            assert isinstance(values, list) and values and all(
                isinstance(value, str) and value for value in values
            ), f"{workflow_id}: legacy output differences require explicit nonempty values"
        if "legacyLocalizedOutputMissing" in workflow:
            assert workflow["format"] in {
                "android",
                "apple_strings",
                "formatjs_json",
                "gettext_po",
            }, (
                f"{workflow_id}: missing legacy translated content requires Android, Apple strings, JSON, or gettext"
            )
            assert "translations" in workflow and "removeUntranslated" in workflow, (
                f"{workflow_id}: missing legacy translated content requires explicit cleanup policy"
            )
            values = workflow["legacyLocalizedOutputMissing"]
            assert isinstance(values, list) and values and all(
                isinstance(value, str) and value for value in values
            ), f"{workflow_id}: missing legacy translated content requires explicit nonempty values"
        if "legacyLocalizedOutputMatchesPortable" in workflow:
            assert workflow["format"] == "gettext_po", (
                f"{workflow_id}: exact successful legacy output parity currently owns gettext"
            )
            assert workflow["legacyLocalizedOutputMatchesPortable"] is True, (
                f"{workflow_id}: successful legacy output parity requires explicit true ownership"
            )
            assert "translations" in workflow and workflow.get("removeUntranslated") is True, (
                f"{workflow_id}: successful legacy output parity requires translated cleanup"
            )
        if "error" in workflow:
            assert workflow["error"] in {
                "INVALID_FILTER_OPTION",
                "UNSUPPORTED_FILTER_OPTION",
                "INVALID_INLINE_CODE",
                "INVALID_IMPORT_LOCALE",
                "INVALID_IMPORT_PLURAL",
                "UNSUPPORTED_IMPORT_POLICY",
            }, (
                f"{workflow_id}: unstable workflow error"
            )

    for case in manifest["cases"]:
        case_id = case["id"]
        assert case_id not in seen, f"Duplicate fixture ID: {case_id}"
        seen.add(case_id)
        assert case["format"] in FORMATS, f"{case_id}: unknown source format"
        assert case.get("encoding") in ENCODINGS, f"{case_id}: unknown fixture encoding"
        if "binaryFixture" in case:
            assert case["format"] in {
                "apple_strings",
                "apple_stringsdict",
            }, f"{case_id}: binary fixtures require an Apple property-list resource"
            assert case.get("encoding") == "BINARY_PLIST", (
                f"{case_id}: binary fixtures require their explicit encoding"
            )
            fixture = (ROOT / case["binaryFixture"]).read_text(encoding="ascii")
            assert re.fullmatch(r"(?:[0-9a-f]{2}[ \n]*)+", fixture), (
                f"{case_id}: binary fixtures require readable, even-length lowercase hexadecimal bytes"
            )
        elif case.get("encoding") == "BINARY_PLIST":
            raise AssertionError(
                f"{case_id}: binary property-list encoding requires a shared byte fixture"
            )
        if "binaryPaddingBytes" in case:
            assert "binaryFixture" in case, (
                f"{case_id}: bounded binary padding requires a binary fixture"
            )
            assert (
                isinstance(case["binaryPaddingBytes"], int)
                and not isinstance(case["binaryPaddingBytes"], bool)
                and 0 < case["binaryPaddingBytes"] <= 16 * 1024 * 1024
            ), (
                f"{case_id}: bounded binary padding requires a positive, at-most-16-MiB byte count"
            )
        assert case.get("lineEndings") in {
            None,
            "CR",
            "CRLF",
        }, f"{case_id}: invalid line-ending override"
        assert case.get("androidOracle") in {
            None,
            "accept",
            "reject",
            "skip",
        }, f"{case_id}: invalid Android oracle policy"
        if "androidFeatureFlags" in case:
            assert case["format"] == "android", (
                f"{case_id}: build feature flags are Android-only"
            )
            assert valid_android_feature_flags(case["androidFeatureFlags"]), (
                f"{case_id}: Android feature flags require named read-only boolean values"
            )
            assert "androidFeatureFlagDefinitions" not in case, (
                f"{case_id}: Android flags use either legacy booleans or ordered definitions"
            )
        if "androidFeatureFlagDefinitions" in case:
            assert case["format"] == "android", (
                f"{case_id}: feature definitions are Android-only"
            )
            assert valid_android_feature_flag_definitions(
                case["androidFeatureFlagDefinitions"]
            ), (
                f"{case_id}: Android feature definitions require named modes and nullable values"
            )
        if "androidSelectedProducts" in case:
            assert case["format"] == "android", (
                f"{case_id}: selected products are Android-only"
            )
            assert valid_android_selected_products(case["androidSelectedProducts"]), (
                f"{case_id}: selected Android products require distinct nonempty names"
            )
        if "androidApplicationPackage" in case:
            assert case["format"] == "android", (
                f"{case_id}: application packages are Android-only"
            )
            assert valid_android_application_package(
                case["androidApplicationPackage"]
            ), (
                f"{case_id}: Android application package requires valid dot-separated identifiers"
            )
        if "androidLinkErrorContains" in case:
            assert case["format"] == "android" and "error" in case, (
                f"{case_id}: native Android link diagnostics require a rejected Android fixture"
            )
            assert case.get("androidOracle") == "accept", (
                f"{case_id}: link rejections must explicitly accept native compilation"
            )
            assert (
                isinstance(case["androidLinkErrorContains"], str)
                and case["androidLinkErrorContains"]
            ), f"{case_id}: native Android link diagnostic must be nonempty"
        if "androidLinkCrashSignal" in case:
            assert case["format"] == "android" and "error" in case, (
                f"{case_id}: native linker crashes require a rejected Android fixture"
            )
            assert case.get("androidOracle") == "accept", (
                f"{case_id}: linker crashes require successfully compiled original resources"
            )
            assert case["androidLinkCrashSignal"] in {
                6,
                11,
            }, f"{case_id}: native crash evidence requires SIGABRT or SIGSEGV"
            assert "androidLinkErrorContains" not in case, (
                f"{case_id}: ordinary native failures and crashes are exclusive"
            )
        if "androidLinkAbortContains" in case:
            assert "androidLinkCrashSignal" in case, (
                f"{case_id}: abort diagnostics require a native linker crash"
            )
            assert (
                isinstance(case["androidLinkAbortContains"], str)
                and case["androidLinkAbortContains"]
            ), f"{case_id}: native linker abort diagnostic must be nonempty"
        if "androidFlatName" in case:
            assert case["format"] == "android" and "expected" in case, (
                f"{case_id}: intermediate filenames require accepted Android resources"
            )
            assert (
                isinstance(case["androidFlatName"], str)
                and case["androidFlatName"].endswith(".arsc.flat")
                and "/" not in case["androidFlatName"]
                and "\\" not in case["androidFlatName"]
            ), f"{case_id}: invalid AAPT2 intermediate resource filename"
        if "androidErrorContains" in case:
            assert case["format"] == "android" and "error" in case, (
                f"{case_id}: native Android diagnostics require a rejected Android fixture"
            )
            assert (
                isinstance(case["androidErrorContains"], str)
                and case["androidErrorContains"]
            ), (
                f"{case_id}: native Android rejection must declare a nonempty diagnostic fragment"
            )
        if "androidWarningContains" in case:
            assert case["format"] == "android" and "expected" in case, (
                f"{case_id}: native compiler warnings require accepted Android resources"
            )
            assert (
                isinstance(case["androidWarningContains"], list)
                and case["androidWarningContains"]
            ), f"{case_id}: native compiler warnings require nonempty fragments"
            assert all(
                isinstance(warning, str) and warning
                for warning in case["androidWarningContains"]
            ), f"{case_id}: every native compiler warning must be nonempty"
        assert case.get("appleOracle") in {
            None,
            "accept",
            "reject",
            "skip",
        }, f"{case_id}: invalid Apple oracle policy"
        if "appleTypedPlist" in case:
            assert (
                case["format"] == "apple_stringsdict"
                and case["appleTypedPlist"] is True
            ), f"{case_id}: typed Foundation snapshots require Apple stringsdict input"
        assert case.get("appleBundleOracle") in {
            None,
            "reject",
        }, f"{case_id}: invalid Apple Bundle runtime policy"
        if case.get("appleBundleOracle") == "reject":
            assert (
                case["format"] == "apple_strings"
                and "error" in case
                and case.get("appleOracle") == "accept"
            ), (
                f"{case_id}: rejected Bundle resources require accepted noncanonical Apple plist input"
            )
            assert (
                isinstance(case.get("appleBundleMessage"), str)
                and case["appleBundleMessage"]
            ), f"{case_id}: rejected Bundle resources require a lookup message"
        assert case.get("xcstringsOracle") in {
            None,
            "accept",
            "reject",
        }, f"{case_id}: invalid Xcode oracle policy"
        if "xcstringsDiagnostic" in case:
            assert (
                case["format"] == "apple_xcstrings"
                and "error" in case
                and case.get("xcstringsOracle") != "accept"
                and isinstance(case["xcstringsDiagnostic"], str)
                and case["xcstringsDiagnostic"]
            ), f"{case_id}: native Xcode diagnostics require a rejected String Catalog"
        assert case.get("gettextOracle") in {
            None,
            "accept",
            "reject",
        }, f"{case_id}: invalid gettext oracle policy"
        assert case.get("propertiesOracle") in {
            None,
            "accept",
            "reject",
        }, f"{case_id}: invalid properties oracle policy"
        for runtime_field, runtime_format, runtime_label in (
            ("xcstringsRuntimeSamples", "apple_xcstrings", "Xcode"),
            ("appleStringsRuntimeSamples", "apple_strings", "Foundation"),
            ("appleStringsdictRuntimeSamples", "apple_stringsdict", "Foundation"),
        ):
            if runtime_field not in case:
                continue
            assert case["format"] == runtime_format and "expected" in case, (
                f"{case_id}: {runtime_label} runtime samples require an accepted Apple resource"
            )
            if runtime_field in {
                "appleStringsRuntimeSamples",
                "appleStringsdictRuntimeSamples",
            }:
                normalized = (
                    "appleNormalized"
                    if runtime_field == "appleStringsRuntimeSamples"
                    else "appleStringsdictNormalized"
                )
                assert "appleCompiled" in case and normalized in case, (
                    f"{case_id}: Foundation runtime samples require original and normalized bundles"
                )
            samples = case[runtime_field]
            assert isinstance(samples, list) and samples, (
                f"{case_id}: {runtime_label} runtime samples must be a nonempty array"
            )
            for sample in samples:
                assert isinstance(sample, dict) and set(sample) == {
                    "message",
                    "values",
                    "expected",
                }, (
                    f"{case_id}: {runtime_label} runtime samples require a message, arguments, and expected text"
                )
                assert isinstance(sample["message"], str) and sample["message"], (
                    f"{case_id}: {runtime_label} runtime sample requires a message ID"
                )
                assert isinstance(sample["values"], dict) and (
                    sample["values"]
                    or runtime_field
                    in {
                        "appleStringsRuntimeSamples",
                        "appleStringsdictRuntimeSamples",
                        "xcstringsRuntimeSamples",
                    }
                ), f"{case_id}: {runtime_label} runtime sample requires an argument map"
                assert all(
                    isinstance(key, str)
                    and key
                    and isinstance(value, (str, int, float))
                    and not isinstance(value, bool)
                    and (not isinstance(value, float) or math.isfinite(value))
                    for key, value in sample["values"].items()
                ), (
                    f"{case_id}: {runtime_label} runtime arguments must be finite numbers or text"
                )
                assert isinstance(sample["expected"], str), (
                    f"{case_id}: {runtime_label} runtime sample requires expected text"
                )
        if "gettextRuntimeSamples" in case:
            assert case["format"] == "gettext_po" and "expected" in case, (
                f"{case_id}: gettext runtime samples require an accepted PO fixture"
            )
            samples = case["gettextRuntimeSamples"]
            assert isinstance(samples, list) and samples, (
                f"{case_id}: gettext runtime samples must be a nonempty array"
            )
            assert all(
                isinstance(sample, int)
                and not isinstance(sample, bool)
                and 1000 < sample <= 1_000_000_000
                for sample in samples
            ), (
                f"{case_id}: extended gettext samples must be bounded integers beyond GNU's window"
            )
            assert len(samples) == len(set(samples)), (
                f"{case_id}: duplicate gettext runtime samples"
            )
        if "gettextFractionalSamples" in case:
            assert case["format"] == "gettext_po" and "expected" in case, (
                f"{case_id}: gettext fractional samples require an accepted PO fixture"
            )
            samples = case["gettextFractionalSamples"]
            assert isinstance(samples, list) and samples, (
                f"{case_id}: gettext fractional samples must be a nonempty array"
            )
            for sample in samples:
                assert isinstance(sample, dict) and set(sample) == {
                    "value",
                    "index",
                }, (
                    f"{case_id}: fractional probes require only a value and native variant index"
                )
                value = sample["value"]
                assert (
                    isinstance(value, (int, float))
                    and not isinstance(value, bool)
                    and math.isfinite(value)
                    and value >= 0
                    and not float(value).is_integer()
                ), (
                    f"{case_id}: fractional runtime samples must be finite noninteger values"
                )
                assert (
                    isinstance(sample["index"], int)
                    and not isinstance(sample["index"], bool)
                    and sample["index"] >= 0
                ), (
                    f"{case_id}: fractional runtime samples require a nonnegative native index"
                )
        if "okapi" in case:
            differential = case["okapi"]
            assert isinstance(differential, dict), (
                f"{case_id}: Okapi comparison must be an object"
            )
            assert differential.get("policy") in {
                "match",
                "different",
                "rejected",
                "unsupported",
                "compiler_rejected",
            }, f"{case_id}: unknown legacy Okapi comparison policy"
            assert (
                isinstance(differential.get("assetPath"), str)
                and differential["assetPath"]
            ), f"{case_id}: legacy Okapi comparison requires the real routed asset path"
            if differential["policy"] == "match":
                assert "reason" not in differential, (
                    f"{case_id}: equivalent extraction has no divergence reason"
                )
            else:
                assert (
                    isinstance(differential.get("reason"), str)
                    and differential["reason"]
                ), f"{case_id}: legacy differences require an explicit migration reason"
            if differential["policy"] == "unsupported":
                assert "expected" not in differential, (
                    f"{case_id}: unsupported legacy routing has no extraction snapshot"
                )
            elif differential["policy"] == "rejected":
                assert "expected" not in differential, (
                    f"{case_id}: rejected legacy extraction has no text-unit snapshot"
                )
                assert isinstance(differential.get("errorClass"), str), (
                    f"{case_id}: rejected legacy extraction requires its exception class"
                )
                assert isinstance(differential.get("errorMessage"), str), (
                    f"{case_id}: rejected legacy extraction requires its stable error message"
                )
            else:
                if differential["policy"] == "compiler_rejected":
                    assert case["format"] == "android" and "error" in case, (
                        f"{case_id}: compiler-rejected legacy extraction requires an Android error"
                    )
                else:
                    assert "expected" in case, (
                        f"{case_id}: legacy extraction requires a canonical snapshot"
                    )
                assert isinstance(differential.get("expected"), str), (
                    f"{case_id}: legacy extraction requires an Okapi snapshot"
                )
                snapshot = ROOT / differential["expected"]
                assert snapshot.is_file(), (
                    f"{case_id}: missing legacy Okapi extraction snapshot"
                )
                observed = json.loads(snapshot.read_text(encoding="utf-8"))
                assert set(observed) == {
                    "filterConfigId",
                    "units",
                }, f"{case_id}: invalid legacy Okapi extraction snapshot"
                assert isinstance(observed["filterConfigId"], str), (
                    f"{case_id}: legacy Okapi snapshot requires its actual filter configuration"
                )
                assert isinstance(observed["units"], list), (
                    f"{case_id}: legacy Okapi snapshot units must be an array"
                )
                for unit in observed["units"]:
                    assert set(unit) <= {
                        "name",
                        "source",
                        "comments",
                        "pluralForm",
                        "pluralFormOther",
                        "usages",
                    }, f"{case_id}: invalid legacy Okapi text-unit field"
                    assert isinstance(unit.get("name"), str) and isinstance(
                        unit.get("source"), str
                    ), (
                        f"{case_id}: legacy Okapi units require their native names and source strings"
                    )
        if "resourcePath" in case:
            assert case["format"] == "android", (
                f"{case_id}: resource paths are Android-only"
            )
            assert isinstance(case["resourcePath"], str), (
                f"{case_id}: resource path must be text"
            )
        if "androidPseudolocalized" in case:
            assert (
                case["format"] == "android"
                and "expected" in case
                and "resourcePath" in case
            ), (
                f"{case_id}: pseudolocalization requires an accepted path-aware Android fixture"
            )
            snapshot = ROOT / case["androidPseudolocalized"]
            assert snapshot.is_file(), (
                f"{case_id}: missing native pseudolocale snapshot"
            )
            generated = json.loads(snapshot.read_text(encoding="utf-8"))
            assert set(generated) == {"resources"} and isinstance(
                generated["resources"], dict
            ), (
                f"{case_id}: pseudolocale snapshot requires native resource configurations"
            )
            for name, configurations in generated["resources"].items():
                assert isinstance(name, str) and name, (
                    f"{case_id}: pseudolocalized Android resources require native names"
                )
                assert configurations == [
                    "ar-rXB",
                    "en-rXA",
                ], (
                    f"{case_id}: expected both native left-to-right and right-to-left pseudolocales"
                )
        if case.get("androidConfiguration"):
            assert "resourcePath" in case and "androidCompiled" in case, (
                f"{case_id}: configuration snapshots require a resource path and compiled snapshot"
            )
        if "androidNormalized" in case:
            assert case["format"] == "android", (
                f"{case_id}: normalized Android XML requires Android"
            )
            assert "expected" in case and "androidCompiled" in case, (
                f"{case_id}: normalized resources require canonical and native snapshots"
            )
            assert (ROOT / case["androidNormalized"]).is_file(), (
                f"{case_id}: missing normalized Android XML snapshot"
            )
        if "androidNormalizedCompiled" in case:
            assert "androidNormalized" in case, (
                f"{case_id}: normalized compiler snapshot requires XML"
            )
            assert (ROOT / case["androidNormalizedCompiled"]).is_file(), (
                f"{case_id}: missing normalized Android compiler snapshot"
            )
        if "androidLinked" in case:
            assert case["format"] == "android" and "expected" in case, (
                f"{case_id}: linked Android snapshots require an accepted Android resource"
            )
            linked = ROOT / case["androidLinked"]
            assert linked.is_file(), (
                f"{case_id}: missing linked Android resource snapshot"
            )
            assert isinstance(json.loads(linked.read_text(encoding="utf-8")), dict), (
                f"{case_id}: linked Android snapshot must be an object"
            )
        if "appleNormalized" in case:
            assert case["format"] == "apple_strings", (
                f"{case_id}: normalized Apple strings require Apple strings input"
            )
            assert "expected" in case and "appleCompiled" in case, (
                f"{case_id}: normalized Apple strings require canonical and Foundation snapshots"
            )
            assert (ROOT / case["appleNormalized"]).is_file(), (
                f"{case_id}: missing normalized Apple strings snapshot"
            )
        if "appleStringsdictNormalized" in case:
            assert case["format"] == "apple_stringsdict", (
                f"{case_id}: normalized Apple stringsdict requires a plist dictionary"
            )
            assert "expected" in case and "appleCompiled" in case, (
                f"{case_id}: normalized Apple stringsdict requires canonical and Foundation snapshots"
            )
            assert (ROOT / case["appleStringsdictNormalized"]).is_file(), (
                f"{case_id}: missing normalized Apple stringsdict snapshot"
            )
        if "xcstringsNormalized" in case:
            assert case["format"] == "apple_xcstrings", (
                f"{case_id}: normalized Xcode resources require a String Catalog"
            )
            assert "expected" in case and "xcstringsCompiled" in case, (
                f"{case_id}: normalized Xcode resources require canonical and compiler snapshots"
            )
            assert (ROOT / case["xcstringsNormalized"]).is_file(), (
                f"{case_id}: missing normalized Xcode String Catalog"
            )
        if "xcstringsNormalizedCompiled" in case:
            assert "xcstringsNormalized" in case, (
                f"{case_id}: normalized Xcode compiler snapshots require normalized catalog input"
            )
            assert (ROOT / case["xcstringsNormalizedCompiled"]).is_file(), (
                f"{case_id}: missing normalized Xcode compiler snapshot"
            )
        if "propertiesNormalized" in case:
            assert case["format"] == "java_properties", (
                f"{case_id}: normalized Java properties require properties input"
            )
            assert "expected" in case and "propertiesCompiled" in case, (
                f"{case_id}: normalized Java properties require canonical and JDK snapshots"
            )
            assert (ROOT / case["propertiesNormalized"]).is_file(), (
                f"{case_id}: missing normalized Java properties snapshot"
            )
        if "gettextNormalized" in case:
            assert case["format"] == "gettext_po", (
                f"{case_id}: normalized GNU gettext requires PO input"
            )
            assert "expected" in case and (
                "gettextCompiled" in case or "gettextDomainCompiled" in case
            ), f"{case_id}: normalized GNU gettext requires canonical and MO snapshots"
            assert (ROOT / case["gettextNormalized"]).is_file(), (
                f"{case_id}: missing normalized GNU gettext snapshot"
            )
        if "resxNormalized" in case:
            assert case["format"] == "resx" and "expected" in case, (
                f"{case_id}: normalized Microsoft resources require a canonical RESX catalog"
            )
            assert (ROOT / case["resxNormalized"]).is_file(), (
                f"{case_id}: missing normalized Microsoft resource snapshot"
            )
        if "xtbNormalized" in case:
            assert case["format"] == "xtb" and "expected" in case, (
                f"{case_id}: normalized Google bundles require a canonical XTB catalog"
            )
            assert (ROOT / case["xtbNormalized"]).is_file(), (
                f"{case_id}: missing normalized Google bundle snapshot"
            )
        if "writerReject" in case:
            rejected = case["writerReject"]
            assert "expected" in case, (
                f"{case_id}: writer rejections require a valid input catalog"
            )
            assert rejected.get("format") in FORMATS, (
                f"{case_id}: unknown writer target format"
            )
            assert re.fullmatch(r"[A-Z][A-Z_]+", rejected.get("error", "")), (
                f"{case_id}: unstable writer error code"
            )
        if "writerMutations" in case:
            assert "expected" in case, (
                f"{case_id}: writer mutations require a valid input catalog"
            )
            assert (
                isinstance(case["writerMutations"], list) and case["writerMutations"]
            ), f"{case_id}: writer mutations must be a nonempty list"
            for mutation in case["writerMutations"]:
                assert isinstance(mutation.get("message"), str), (
                    f"{case_id}: missing mutation ID"
                )
                if "metadata" in mutation:
                    assert isinstance(mutation["metadata"], dict), (
                        f"{case_id}: writer mutation metadata must be an object"
                    )
                assert re.fullmatch(r"[A-Z][A-Z_]+", mutation.get("error", "")), (
                    f"{case_id}: unstable writer mutation error code"
                )
        assert (ROOT / case["input"]).is_file(), f"{case_id}: missing input fixture"
        assert ("expected" in case) ^ ("error" in case), (
            f"{case_id}: expected catalog or error"
        )
        if "androidCompiled" in case:
            assert case["format"] == "android", (
                f"{case_id}: compiled snapshots are Android-only"
            )
            assert "expected" in case or case.get("androidOracle") == "accept", (
                f"{case_id}: compiled snapshots require native compiler acceptance"
            )
            snapshot = ROOT / case["androidCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing Android compiler snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert set(compiled) <= {
                "strings",
                "references",
                "primitiveValues",
                "arrays",
                "arrayReferences",
                "arrayPrimitiveValues",
                "plurals",
                "productVariants",
                "styledSpans",
                "configuration",
                "pluralReferences",
                "macros",
                "attributes",
                "styleables",
                "attributeConfigurations",
                "styleableConfigurations",
            }, f"{case_id}: unknown Android compiled-resource section"
            if "macros" in compiled:
                assert isinstance(compiled["macros"], list) and compiled["macros"], (
                    f"{case_id}: Android build macros require native declaration names"
                )
                assert all(
                    isinstance(name, str) and name for name in compiled["macros"]
                ), (
                    f"{case_id}: Android build macro declarations require nonempty native names"
                )
                assert len(compiled["macros"]) == len(set(compiled["macros"])), (
                    f"{case_id}: Android build macro declarations must remain unique"
                )
            if case.get("androidStyledSpans"):
                linked = (
                    json.loads(
                        (ROOT / case["androidLinked"]).read_text(encoding="utf-8")
                    )
                    if "androidLinked" in case
                    else {}
                )
                assert "styledSpans" in compiled or (
                    "macros" in compiled and "styledSpans" in linked
                ), f"{case_id}: missing Android styled-span snapshot"
            if case.get("androidSpanRuntime"):
                assert case.get("androidStyledSpans"), (
                    f"{case_id}: runtime style projections require styled spans"
                )
                assert any(
                    "runtimeAnnotations" in span or "runtimeStyles" in span
                    for kind in compiled["styledSpans"].values()
                    for spans in kind.values()
                    for span in (
                        spans
                        if isinstance(spans, list)
                        else [item for entries in spans.values() for item in entries]
                    )
                ), f"{case_id}: missing native runtime-style snapshot"
                if case.get("error") == "INVALID_ANDROID_STYLE":
                    assert any(
                        span.get("runtimeError") == "NumberFormatException"
                        for kind in compiled["styledSpans"].values()
                        for spans in kind.values()
                        for span in (
                            spans
                            if isinstance(spans, list)
                            else [
                                item for entries in spans.values() for item in entries
                            ]
                        )
                    ), f"{case_id}: missing Android runtime font-number failure"
            if case.get("androidParagraphRuntime"):
                assert case.get("androidSpanRuntime"), (
                    f"{case_id}: paragraph snapshots require runtime span decoding"
                )
                assert any(
                    "runtimeParagraph" in span
                    for kind in compiled["styledSpans"].values()
                    for spans in kind.values()
                    for span in (
                        spans
                        if isinstance(spans, list)
                        else [item for entries in spans.values() for item in entries]
                    )
                ), f"{case_id}: missing native runtime paragraph snapshot"
            if case.get("androidConfiguration"):
                assert isinstance(compiled.get("configuration"), str), (
                    f"{case_id}: missing compiled Android configuration"
                )
            if case.get("androidPrimitives"):
                top_level = compiled.get("primitiveValues")
                array_values = compiled.get("arrayPrimitiveValues")
                assert (isinstance(top_level, dict) and top_level) or (
                    isinstance(array_values, dict) and array_values
                ), f"{case_id}: missing native Android primitive-value snapshot"
            if case.get("androidAttributes"):
                assert (
                    isinstance(compiled.get("attributes"), dict)
                    and compiled["attributes"]
                ), f"{case_id}: missing native Android attribute-declaration snapshot"
            if case.get("androidStyleables"):
                assert (
                    isinstance(compiled.get("styleables"), dict)
                    and compiled["styleables"]
                ), f"{case_id}: missing native Android styleable-group snapshot"
            if case.get("androidAttributeConfigurations"):
                attributes = compiled.get("attributeConfigurations")
                groups = compiled.get("styleableConfigurations")
                assert isinstance(attributes, dict) and attributes, (
                    f"{case_id}: missing default-only Android attribute configurations"
                )
                assert isinstance(groups, dict) and groups, (
                    f"{case_id}: missing default-only Android styleable configurations"
                )
                assert set(attributes.values()) == {""} and set(groups.values()) == {
                    ""
                }, (
                    f"{case_id}: Android attributes and styleables must ignore qualified directories"
                )
        if "appleCompiled" in case:
            assert case["format"] in {
                "apple_strings",
                "apple_stringsdict",
            }, f"{case_id}: Foundation snapshots require Apple resource files"
            assert "expected" in case or case.get("appleOracle") == "accept", (
                f"{case_id}: Foundation snapshots require native parser acceptance"
            )
            snapshot = ROOT / case["appleCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing Foundation parser snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert isinstance(compiled, dict), (
                f"{case_id}: Foundation snapshot must be a dictionary"
            )
        if "xcstringsCompiled" in case:
            assert case["format"] == "apple_xcstrings", (
                f"{case_id}: Xcode compiled resources require a String Catalog"
            )
            assert "expected" in case or case.get("xcstringsOracle") == "accept", (
                f"{case_id}: compiler snapshots require native Xcode acceptance"
            )
            snapshot = ROOT / case["xcstringsCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing Xcode compiler snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert isinstance(compiled, dict), (
                f"{case_id}: Xcode snapshot must be a resource map"
            )
            if case_id.startswith("apple-xcstrings-native-script-bundle-collision-"):
                assert (
                    case.get("error") == "DUPLICATE_LOCALE"
                    and case.get("xcstringsOracle") == "accept"
                ), (
                    f"{case_id}: native-accepted lossy script aliases must fail portable parsing"
                )
                alternatives_path = ROOT / case["xcstringsCompiledAlternatives"]
                assert alternatives_path.is_file(), (
                    f"{case_id}: missing nondeterministic native collision snapshots"
                )
                alternatives = json.loads(alternatives_path.read_text(encoding="utf-8"))
                assert len(alternatives) == 2 and compiled in alternatives, (
                    f"{case_id}: both native collision winners must be independently recorded"
                )
                bundles = {
                    bundle: values
                    for bundle, values in compiled.items()
                    if not bundle.startswith("en.lproj/")
                }
                assert len(bundles) == 1 and all(
                    len(values) == 1 for values in bundles.values()
                ), f"{case_id}: Xcode no longer silently collapses both script locales"
            if collision := case.get("xcstringsDevelopmentSourceCollision"):
                assert case.get("error") == "DUPLICATE_LOCALE" and case.get(
                    "xcstringsOracle"
                ) == "accept", (
                    f"{case_id}: ambiguous native development owners must fail portable parsing"
                )
                source = json.loads((ROOT / case["input"]).read_text(encoding="utf-8"))
                descriptor = source["strings"]["harbor.competing.development.source🧭"]
                assert len(descriptor["localizations"]) == 2, (
                    f"{case_id}: source-owner collision requires two competing native identities"
                )
                alternatives_path = ROOT / case["xcstringsCompiledAlternatives"]
                assert alternatives_path.is_file(), (
                    f"{case_id}: missing nondeterministic development-source snapshots"
                )
                alternatives = json.loads(alternatives_path.read_text(encoding="utf-8"))
                assert len(alternatives) == 2 and compiled in alternatives, (
                    f"{case_id}: both nondeterministic development-source outcomes are required"
                )
                if collision == "null":
                    assert {} in alternatives and any(alternatives), (
                        f"{case_id}: null collisions must capture active and suppressed outcomes"
                    )
                else:
                    assert all(len(outcome) == 1 for outcome in alternatives), (
                        f"{case_id}: competing source owners no longer share one native bundle"
                    )
                    winners = {
                        next(iter(next(iter(outcome.values())).values()))
                        for outcome in alternatives
                    }
                    assert winners == {"First source owner", "Second source owner"}, (
                        f"{case_id}: Xcode no longer silently discards one development source"
                    )
                if collision == "protected":
                    assert descriptor.get("shouldTranslate") is False, (
                        f"{case_id}: protected source ownership lost its native collision"
                    )
            if case_id.startswith("apple-xcstrings-native-distinct-locale-"):
                bundles = {
                    bundle: values
                    for bundle, values in compiled.items()
                    if not bundle.startswith("en.lproj/")
                }
                assert len(bundles) == 2 and all(
                    len(values) == 1 for values in bundles.values()
                ), (
                    f"{case_id}: Xcode no longer preserves both independent locale bundles"
                )
                source_localizations = json.loads(
                    (ROOT / case["input"]).read_text(encoding="utf-8")
                )["strings"]["harbor.native.script.collision🧭"]["localizations"]
                assert {next(iter(values.values())) for values in bundles.values()} == {
                    localization["stringUnit"]["value"]
                    for locale, localization in source_localizations.items()
                    if locale != "en"
                }, f"{case_id}: independent native bundles lost their own translations"
        if "gettextCompiled" in case:
            assert case["format"] == "gettext_po", (
                f"{case_id}: compiled MO snapshots require gettext PO"
            )
            assert "expected" in case, (
                f"{case_id}: rejected PO files cannot have an MO snapshot"
            )
            snapshot = ROOT / case["gettextCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing GNU MO snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert set(compiled) <= {
                "entries",
                "plurals",
            }, f"{case_id}: unknown GNU MO section"
        if "gettextDomainCompiled" in case:
            assert case["format"] == "gettext_po", (
                f"{case_id}: domain MO snapshots require gettext PO"
            )
            assert "expected" in case or case.get("gettextOracle") == "accept", (
                f"{case_id}: native-only domain catalogs require an explicit acceptance policy"
            )
            snapshot = ROOT / case["gettextDomainCompiled"]
            assert snapshot.is_file(), (
                f"{case_id}: missing split-domain GNU MO snapshot"
            )
            domains = json.loads(snapshot.read_text(encoding="utf-8"))
            assert isinstance(domains, dict) and domains, (
                f"{case_id}: split-domain GNU MO snapshots must contain domains"
            )
            for domain, compiled in domains.items():
                assert (
                    isinstance(domain, str)
                    and domain
                    and "/" not in domain
                    and "\\" not in domain
                    and not any(character.isspace() for character in domain)
                ), f"{case_id}: unsafe split-domain GNU output name"
                assert isinstance(compiled, dict) and set(compiled) <= {
                    "entries",
                    "plurals",
                    "header",
                }, f"{case_id}: invalid split-domain GNU MO snapshot"
                if "header" in compiled:
                    header = compiled["header"]
                    assert isinstance(header, dict) and set(header) <= {
                        "locale",
                        "pluralForms",
                        "fields",
                    }, f"{case_id}: invalid split-domain GNU header"
                    if "fields" in header:
                        validate_gettext_header_fields(header["fields"], case_id)
            if "gettextSingleOutput" in case:
                assert case["gettextSingleOutput"] == "reject", (
                    f"{case_id}: unsupported GNU single-output policy"
                )
        if "gettextDomainRuntimeSamples" in case:
            assert "gettextDomainCompiled" in case and "expected" in case, (
                f"{case_id}: domain runtime samples require canonical and split-MO snapshots"
            )
            for sample in case["gettextDomainRuntimeSamples"]:
                assert set(sample) == {
                    "domain",
                    "message",
                    "plural",
                    "value",
                    "expected",
                }, f"{case_id}: invalid GNU domain runtime sample fields"
                assert all(
                    isinstance(sample[field], str)
                    for field in ("domain", "message", "plural", "expected")
                ), f"{case_id}: GNU domain runtime text must be strings"
                assert isinstance(sample["value"], int) and sample["value"] >= 0, (
                    f"{case_id}: GNU domain runtime count must be nonnegative"
                )
        if "gettextLossyCompiled" in case:
            assert (
                case["format"] == "gettext_po"
                and "error" in case
                and case.get("gettextOracle") == "accept"
            ), (
                f"{case_id}: lossy GNU MO snapshots require an explicitly rejected portable input"
            )
            snapshot = ROOT / case["gettextLossyCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing lossy GNU MO snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert set(compiled) <= {
                "entries",
                "plurals",
            }, f"{case_id}: unknown lossy GNU MO section"
        if "gettextNativeDomains" in case:
            assert (
                case["format"] == "gettext_po" and case.get("gettextOracle") != "reject"
            ), f"{case_id}: native GNU domains require a natively accepted PO resource"
            domains = case["gettextNativeDomains"]
            assert isinstance(domains, dict) and set(domains) <= {
                "source",
                "normalized",
            }, f"{case_id}: invalid GNU domain-snapshot fields"
            assert isinstance(domains.get("source"), list) and all(
                isinstance(domain, str) for domain in domains["source"]
            ), f"{case_id}: invalid original GNU translation domains"
            if "normalized" in domains:
                assert "gettextNormalized" in case, (
                    f"{case_id}: normalized GNU domains require a normalized PO resource"
                )
                assert isinstance(domains["normalized"], list) and all(
                    isinstance(domain, str) for domain in domains["normalized"]
                ), f"{case_id}: invalid normalized GNU translation domains"
        if "propertiesCompiled" in case:
            assert case["format"] == "java_properties", (
                f"{case_id}: JDK snapshots require properties"
            )
            assert "expected" in case, (
                f"{case_id}: rejected properties cannot have a JDK snapshot"
            )
            snapshot = ROOT / case["propertiesCompiled"]
            assert snapshot.is_file(), f"{case_id}: missing JDK properties snapshot"
            compiled = json.loads(snapshot.read_text(encoding="utf-8"))
            assert isinstance(compiled, dict), (
                f"{case_id}: JDK snapshot must be a dictionary"
            )
        counts[case["format"]] += 1

        if "error" in case:
            assert re.fullmatch(r"[A-Z][A-Z_]+", case["error"]), (
                f"{case_id}: unstable error code"
            )
            error_count += 1
            continue

        catalog = json.loads((ROOT / case["expected"]).read_text(encoding="utf-8"))
        assert catalog["schemaVersion"] == 1, f"{case_id}: invalid catalog version"
        assert catalog["sourceFormat"] == case["format"], (
            f"{case_id}: wrong source format"
        )
        assert isinstance(catalog["messages"], dict), (
            f"{case_id}: messages must be a map"
        )
        for sample in case.get("gettextDomainRuntimeSamples", []):
            assert any(
                descriptor.get("metadata", {}).get("gettextDomain", "messages")
                == sample["domain"]
                and descriptor.get("metadata", {}).get("sourceMessage")
                == sample["message"]
                and descriptor.get("metadata", {}).get("sourcePlural")
                == sample["plural"]
                for descriptor in catalog["messages"].values()
            ), (
                f"{case_id}: GNU domain runtime sample references an absent plural message"
            )
        for runtime_field, runtime_label in (
            ("xcstringsRuntimeSamples", "Xcode"),
            ("appleStringsRuntimeSamples", "Foundation"),
            ("appleStringsdictRuntimeSamples", "Foundation"),
            ("androidRuntimeSamples", "Android"),
        ):
            for sample in case.get(runtime_field, []):
                assert sample["message"] in catalog["messages"], (
                    f"{case_id}: {runtime_label} runtime sample references an absent message"
                )
                names = {
                    placeholder["name"]
                    for placeholder in catalog["messages"][sample["message"]].get(
                        "placeholders", []
                    )
                }
                assert set(sample["values"]) == names, (
                    f"{case_id}: {runtime_label} runtime arguments must cover exactly the message placeholders"
                )
        for message_id, descriptor in catalog["messages"].items():
            assert message_id, f"{case_id}: empty message ID"
            assert set(descriptor) <= MESSAGE_FIELDS, (
                f"{case_id}/{message_id}: unknown descriptor field"
            )
            assert isinstance(descriptor["defaultMessage"], str), (
                f"{case_id}/{message_id}: missing message"
            )
            metadata = descriptor.get("metadata", {})
            if "javascriptTemplate" in metadata:
                assert case["format"] in {"javascript", "typescript"}, (
                    f"{case_id}/{message_id}: backtick ownership requires JavaScript or TypeScript"
                )
                assert metadata["javascriptTemplate"] is True, (
                    f"{case_id}/{message_id}: template ownership must be explicit"
                )
            if "androidProtectedPlaceholderOccurrences" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular protected placeholder ownership requires a nonplural Android message"
                )
                validate_android_protected_placeholder_occurrences(
                    metadata["androidProtectedPlaceholderOccurrences"],
                    descriptor["defaultMessage"],
                    descriptor.get("placeholders", []),
                    f"{case_id}/{message_id}",
                )
            if "androidPluralProtectedPlaceholderOccurrences" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural protected placeholder ownership requires Android variants"
                )
                categories = metadata["androidPluralProtectedPlaceholderOccurrences"]
                assert isinstance(categories, dict) and categories, (
                    f"{case_id}/{message_id}: empty plural protected placeholder ownership"
                )
                for category, ownership in categories.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown protected placeholder category"
                    )
                    validate_android_protected_placeholder_occurrences(
                        ownership,
                        descriptor["variants"][category],
                        descriptor.get("placeholders", []),
                        f"{case_id}/{message_id}/{category}",
                    )
            if "androidPluralPlaceholderExamples" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: category-owned placeholder examples require Android plurals"
                )
                scoped = metadata["androidPluralPlaceholderExamples"]
                assert isinstance(scoped, dict) and scoped, (
                    f"{case_id}/{message_id}: empty Android plural placeholder examples"
                )
                placeholders = descriptor.get("placeholders", [])
                for category, names in scoped.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android placeholder category"
                    )
                    assert isinstance(names, dict) and names, (
                        f"{case_id}/{message_id}/{category}: empty protected placeholder names"
                    )
                    for name, examples in names.items():
                        assert isinstance(examples, list) and examples, (
                            f"{case_id}/{message_id}/{category}/{name}: empty protected examples"
                        )
                        for example in examples:
                            assert (
                                example is None or isinstance(example, str)
                            ) and any(
                                placeholder.get("name") == name
                                and placeholder.get("example") == example
                                for placeholder in placeholders
                            ), (
                                f"{case_id}/{message_id}/{category}/{name}: unknown protected example"
                            )
            if "androidRuntimeAnnotations" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular Android runtime annotations require plain messages"
                )
                assert metadata[
                    "androidRuntimeAnnotations"
                ] == android_runtime_annotations(descriptor["defaultMessage"]), (
                    f"{case_id}/{message_id}: inconsistent Android runtime annotation projection"
                )
            if "androidPluralRuntimeAnnotations" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural Android runtime annotations require plural variants"
                )
                projected = metadata["androidPluralRuntimeAnnotations"]
                assert isinstance(projected, dict) and projected, (
                    f"{case_id}/{message_id}: empty Android plural runtime annotation projection"
                )
                for category, annotations in projected.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android runtime annotation category"
                    )
                    assert annotations == android_runtime_annotations(
                        descriptor["variants"][category]
                    ), (
                        f"{case_id}/{message_id}/{category}: inconsistent Android runtime annotation projection"
                    )
            if "androidRuntimeStyles" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular Android runtime styles require plain messages"
                )
                assert metadata["androidRuntimeStyles"] == android_runtime_styles(
                    descriptor["defaultMessage"]
                ), (
                    f"{case_id}/{message_id}: inconsistent Android runtime style projection"
                )
            if "androidPluralRuntimeStyles" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural Android runtime styles require plural variants"
                )
                projected = metadata["androidPluralRuntimeStyles"]
                assert isinstance(projected, dict) and projected, (
                    f"{case_id}/{message_id}: empty Android plural runtime style projection"
                )
                for category, effects in projected.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android runtime style category"
                    )
                    assert effects == android_runtime_styles(
                        descriptor["variants"][category]
                    ), (
                        f"{case_id}/{message_id}/{category}: inconsistent Android runtime style projection"
                    )
            if "androidRuntimeParagraphSpans" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular Android paragraph spans require plain messages"
                )
                assert metadata[
                    "androidRuntimeParagraphSpans"
                ] == android_runtime_paragraphs(descriptor["defaultMessage"]), (
                    f"{case_id}/{message_id}: inconsistent Android runtime paragraph projection"
                )
            if "androidPluralRuntimeParagraphSpans" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural Android paragraph spans require plural variants"
                )
                projected = metadata["androidPluralRuntimeParagraphSpans"]
                assert isinstance(projected, dict) and projected, (
                    f"{case_id}/{message_id}: empty Android plural paragraph projection"
                )
                for category, ranges in projected.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android paragraph category"
                    )
                    assert ranges == android_runtime_paragraphs(
                        descriptor["variants"][category]
                    ), (
                        f"{case_id}/{message_id}/{category}: inconsistent Android runtime paragraph projection"
                    )
            if "androidPrintfLineSeparator" in metadata:
                assert case["format"] == "android", (
                    f"{case_id}/{message_id}: printf line separators require Android resources"
                )
                assert metadata["androidPrintfLineSeparator"] is True, (
                    f"{case_id}/{message_id}: invalid Android printf line-separator flag"
                )
                assert metadata.get("formatted") is not False, (
                    f"{case_id}/{message_id}: unformatted Android text cannot carry printf line separators"
                )
            if "androidPrintfLineSeparators" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular Android printf line separators require plain messages"
                )
                assert metadata.get("androidPrintfLineSeparator") is True, (
                    f"{case_id}/{message_id}: Android printf line-separator provenance requires its legacy flag"
                )
                validate_android_line_separator_occurrences(
                    metadata["androidPrintfLineSeparators"],
                    descriptor["defaultMessage"],
                    f"{case_id}/{message_id}",
                )
            if "androidPluralPrintfLineSeparators" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural Android printf line separators require plural variants"
                )
                assert metadata.get("androidPrintfLineSeparator") is True, (
                    f"{case_id}/{message_id}: Android plural printf line-separator provenance requires its legacy flag"
                )
                occurrences = metadata["androidPluralPrintfLineSeparators"]
                assert isinstance(occurrences, dict) and occurrences, (
                    f"{case_id}/{message_id}: empty Android plural printf line-separator metadata"
                )
                for category, positions in occurrences.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android plural printf line-separator category"
                    )
                    validate_android_line_separator_occurrences(
                        positions,
                        descriptor["variants"][category],
                        f"{case_id}/{message_id}/{category}",
                    )
            if "androidRawPercentOccurrences" in metadata:
                assert case["format"] == "android" and "variants" not in descriptor, (
                    f"{case_id}/{message_id}: singular Android raw percentages require plain messages"
                )
                assert metadata.get("formatted") is not False, (
                    f"{case_id}/{message_id}: unformatted Android text cannot carry raw percent provenance"
                )
                validate_android_percent_occurrences(
                    metadata["androidRawPercentOccurrences"],
                    descriptor["defaultMessage"],
                    f"{case_id}/{message_id}",
                )
            if "androidPluralRawPercentOccurrences" in metadata:
                assert case["format"] == "android" and "variants" in descriptor, (
                    f"{case_id}/{message_id}: plural Android raw percentages require plural variants"
                )
                occurrences = metadata["androidPluralRawPercentOccurrences"]
                assert isinstance(occurrences, dict) and occurrences, (
                    f"{case_id}/{message_id}: empty Android plural raw percent metadata"
                )
                for category, positions in occurrences.items():
                    assert category in descriptor["variants"], (
                        f"{case_id}/{message_id}: unknown Android plural raw percent category"
                    )
                    validate_android_percent_occurrences(
                        positions,
                        descriptor["variants"][category],
                        f"{case_id}/{message_id}/{category}",
                    )
            if "gettextDomainHeader" in metadata:
                assert case["format"] == "gettext_po", (
                    f"{case_id}/{message_id}: GNU domain headers require gettext PO input"
                )
                header = metadata["gettextDomainHeader"]
                assert isinstance(header, dict) and set(header) <= {
                    "locale",
                    "pluralForms",
                    "fields",
                }, f"{case_id}/{message_id}: invalid GNU domain-header metadata"
                if "locale" in header:
                    assert isinstance(header["locale"], str) and header["locale"], (
                        f"{case_id}/{message_id}: invalid GNU domain locale"
                    )
                if "pluralForms" in header:
                    forms = header["pluralForms"]
                    assert isinstance(forms, dict) and set(forms) == {
                        "nplurals",
                        "expression",
                    }, f"{case_id}/{message_id}: invalid GNU domain plural header"
                    assert (
                        isinstance(forms["nplurals"], int)
                        and 1 <= forms["nplurals"] <= 100
                        and isinstance(forms["expression"], str)
                        and forms["expression"]
                    ), f"{case_id}/{message_id}: invalid GNU domain plural formula"
                if "fields" in header:
                    validate_gettext_header_fields(
                        header["fields"], f"{case_id}/{message_id}"
                    )
            if "gettextOriginalId" in metadata:
                assert case["format"] == "gettext_po", (
                    f"{case_id}/{message_id}: domain identities require gettext PO input"
                )
                original = metadata["gettextOriginalId"]
                assert isinstance(original, str) and original, (
                    f"{case_id}/{message_id}: invalid original GNU message identity"
                )
                domain = metadata.get("gettextDomain", "messages")
                assert isinstance(domain, str), (
                    f"{case_id}/{message_id}: invalid GNU identity domain"
                )
                assert (
                    message_id == f"{original}@domain={quote(domain, safe='-_.~')}"
                ), f"{case_id}/{message_id}: unstable GNU domain-qualified identity"
            if "applePlistExtras" in metadata:
                assert case["format"] == "apple_stringsdict", (
                    f"{case_id}/{message_id}: typed plist extras require Apple stringsdict input"
                )
                assert valid_apple_plist_dictionary(metadata["applePlistExtras"]), (
                    f"{case_id}/{message_id}: invalid typed Apple property-list source skeleton"
                )
            if "appleDisabledPrintfConversions" in metadata:
                assert case["format"] in {
                    "apple_strings",
                    "apple_stringsdict",
                    "apple_xcstrings",
                }, (
                    f"{case_id}/{message_id}: disabled Foundation conversions require Apple resource input"
                )
                occurrences = metadata["appleDisabledPrintfConversions"]
                assert isinstance(occurrences, list) and occurrences, (
                    f"{case_id}/{message_id}: empty disabled Foundation conversion metadata"
                )
                previous = 0
                for occurrence in occurrences:
                    assert (
                        isinstance(occurrence, dict)
                        and {"position", "source"}
                        <= set(occurrence)
                        <= {"position", "source", "argumentPosition"}
                        and isinstance(occurrence["position"], int)
                        and previous
                        <= occurrence["position"]
                        <= len(descriptor["defaultMessage"])
                        and isinstance(occurrence["source"], str)
                        and re.fullmatch(r"%(?:[1-9][0-9]*\$)?n", occurrence["source"])
                        and (
                            "argumentPosition" not in occurrence
                            or isinstance(occurrence["argumentPosition"], int)
                            and not isinstance(occurrence["argumentPosition"], bool)
                            and occurrence["argumentPosition"] > 0
                            and (
                                "$" not in occurrence["source"]
                                or int(occurrence["source"][1:-2])
                                == occurrence["argumentPosition"]
                            )
                        )
                    ), (
                        f"{case_id}/{message_id}: invalid disabled Foundation conversion ownership"
                    )
                    previous = occurrence["position"]
            if "applePluralDisabledPrintfConversions" in metadata:
                assert case["format"] in {
                    "apple_stringsdict",
                    "apple_xcstrings",
                }, (
                    f"{case_id}/{message_id}: plural disabled conversions require Foundation plural input"
                )
                selectors = metadata["applePluralDisabledPrintfConversions"]
                assert isinstance(selectors, dict) and selectors, (
                    f"{case_id}/{message_id}: empty selector-owned disabled conversions"
                )
                for selector, categories in selectors.items():
                    assert selector in metadata.get("applePluralRules", {}) or (
                        case["format"] == "apple_xcstrings"
                        and (
                            selector == "count"
                            or selector in metadata.get("sourceSubstitutions", {})
                        )
                    ), (
                        f"{case_id}/{message_id}/{selector}: unknown disabled conversion selector"
                    )
                    assert isinstance(categories, dict) and categories, (
                        f"{case_id}/{message_id}/{selector}: empty disabled conversion categories"
                    )
                    for category, occurrences in categories.items():
                        native = (
                            metadata["applePluralRules"][selector]["variants"]
                            if case["format"] == "apple_stringsdict"
                            else (
                                metadata["sourceSubstitutions"][selector]
                                .get("variations", {})
                                .get("plural", {})
                                if selector in metadata.get("sourceSubstitutions", {})
                                else (
                                    metadata["appleSourceLocalization"]
                                    .get("variations", {})
                                    .get("plural", {})
                                    or metadata["appleSourceLocalization"]
                                    .get("variations", {})
                                    .get("device", {})
                                    .get(metadata.get("defaultDevice"), {})
                                    .get("variations", {})
                                    .get("plural", {})
                                )
                            )
                        )
                        assert (
                            category in native
                            and isinstance(occurrences, list)
                            and occurrences
                        ), (
                            f"{case_id}/{message_id}/{selector}/{category}: unknown disabled category"
                        )
                        previous = 0
                        canonical = descriptor.get("variants", {}).get(category)
                        for occurrence in occurrences:
                            assert (
                                isinstance(occurrence, dict)
                                and {"position", "source"}
                                <= set(occurrence)
                                <= {"position", "source", "argumentPosition"}
                                and isinstance(occurrence["position"], int)
                                and previous <= occurrence["position"]
                                and (
                                    canonical is None
                                    or occurrence["position"] <= len(canonical)
                                )
                                and isinstance(occurrence["source"], str)
                                and re.fullmatch(
                                    r"%(?:[1-9][0-9]*\$)?n", occurrence["source"]
                                )
                                and (
                                    "argumentPosition" not in occurrence
                                    or isinstance(occurrence["argumentPosition"], int)
                                    and not isinstance(
                                        occurrence["argumentPosition"], bool
                                    )
                                    and occurrence["argumentPosition"] > 0
                                    and (
                                        "$" not in occurrence["source"]
                                        or int(occurrence["source"][1:-2])
                                        == occurrence["argumentPosition"]
                                    )
                                )
                            ), (
                                f"{case_id}/{message_id}/{selector}/{category}: invalid disabled conversion"
                            )
                            previous = occurrence["position"]
            for variable, rule in metadata.get("applePluralRules", {}).items():
                assert case["format"] == "apple_stringsdict", (
                    f"{case_id}/{message_id}/{variable}: Apple plural rules require stringsdict input"
                )
                assert isinstance(rule, dict) and isinstance(
                    rule.get("variants"), dict
                ), (
                    f"{case_id}/{message_id}/{variable}: invalid Apple plural-rule metadata"
                )
                if "applePlistExtras" in rule:
                    assert valid_apple_plist_dictionary(rule["applePlistExtras"]), (
                        f"{case_id}/{message_id}/{variable}: invalid typed plural-rule skeleton"
                    )
            if "appleMarkupEscaping" in descriptor.get("metadata", {}):
                assert case["format"] == "apple_strings", (
                    f"{case_id}/{message_id}: Apple literal markup requires Apple strings input"
                )
                assert (
                    descriptor["metadata"]["appleMarkupEscaping"] == "icu-quoted-angle"
                ), (
                    f"{case_id}/{message_id}: unsupported Apple ICU literal markup escaping"
                )
                assert "'<'" in descriptor["defaultMessage"], (
                    f"{case_id}/{message_id}: Apple literal markup metadata requires escaped angles"
                )
            for placeholder in descriptor.get("placeholders", []):
                assert set(placeholder) <= PLACEHOLDER_FIELDS, (
                    f"{case_id}/{message_id}: unknown placeholder field"
                )
                assert placeholder["name"], (
                    f"{case_id}/{message_id}: empty placeholder name"
                )
                assert placeholder["kind"] in {
                    "string",
                    "integer",
                    "number",
                    "character",
                    "value",
                }
                if "position" in placeholder:
                    assert placeholder["position"] >= 1, (
                        f"{case_id}/{message_id}: invalid position"
                    )
            if "variants" in descriptor:
                assert "other" in descriptor["variants"], (
                    f"{case_id}/{message_id}: ICU plural requires other"
                )
                for selector in descriptor["variants"]:
                    assert re.fullmatch(
                        r"zero|one|two|few|many|other|=\d+", selector
                    ), (
                        f"{case_id}/{message_id}: invalid ICU plural selector {selector!r}"
                    )
                indexes = descriptor.get("metadata", {}).get("gettextPluralIndexes", {})
                aliases = descriptor.get("metadata", {}).get(
                    "gettextPluralSelectors", {}
                )
                for sample in case.get("gettextFractionalSamples", []):
                    assert str(sample["index"]) in indexes, (
                        f"{case_id}/{message_id}: fractional probe references an absent native plural index"
                    )
                for index, selector in indexes.items():
                    assert selector in descriptor["variants"], (
                        f"{case_id}/{message_id}: gettext index {index} has no ICU variant"
                    )
                if aliases:
                    assert set(aliases) == set(indexes), (
                        f"{case_id}/{message_id}: gettext selector aliases must cover every index"
                    )
                    for index, selectors in aliases.items():
                        assert selectors and selectors[0] == indexes[index], (
                            f"{case_id}/{message_id}: gettext selector aliases have no primary selector"
                        )
                        assert len(selectors) == len(set(selectors)), (
                            f"{case_id}/{message_id}: duplicate gettext selector aliases"
                        )
                        for selector in selectors:
                            assert (
                                descriptor["variants"].get(selector)
                                == descriptor["variants"][indexes[index]]
                            ), (
                                f"{case_id}/{message_id}: gettext aliases must preserve source text"
                            )
            if "resourcePath" in case:
                metadata = descriptor.get("metadata", {})
                assert metadata.get("androidResourcePath") == case["resourcePath"], (
                    f"{case_id}/{message_id}: missing original Android resource path"
                )
                assert isinstance(metadata.get("androidResourceQualifiers"), list), (
                    f"{case_id}/{message_id}: missing ordered Android resource qualifiers"
                )
            if case["format"] == "android":
                metadata = descriptor.get("metadata", {})
                dependencies = metadata.get("androidAttributeDependencies")
                if dependencies is not None:
                    assert valid_android_attribute_dependencies(dependencies), (
                        f"{case_id}/{message_id}: invalid typed Android attribute dependencies"
                    )
                groups = metadata.get("androidStyleableDependencies")
                if groups is not None:
                    assert valid_android_styleable_dependencies(groups, dependencies), (
                        f"{case_id}/{message_id}: invalid Android styleable dependencies"
                    )
                path_condition = metadata.get("androidPathFeatureFlag")
                if path_condition is not None:
                    flag_values = android_feature_flag_values(case)
                    assert valid_android_feature_condition(
                        path_condition, flag_values
                    ), (
                        f"{case_id}/{message_id}: invalid or disabled Android path feature flag"
                    )
                    assert f"flag({path_condition})" in metadata.get(
                        "androidResourcePath", ""
                    ).split("/"), (
                        f"{case_id}/{message_id}: Android path flag must match its original resource path"
                    )
                    assert "androidFeatureFlag" not in metadata, (
                        f"{case_id}/{message_id}: path and top-level resource flags cannot coexist"
                    )
                    assert (
                        metadata.get("androidPathFeatureFlagMode") == "read_write"
                    ) == android_runtime_feature_condition(
                        path_condition, flag_values
                    ), (
                        f"{case_id}/{message_id}: Android path runtime flag mode must be preserved"
                    )
                if "androidSelectedProducts" in case:
                    assert (
                        "@product=" not in message_id
                        and "androidProduct" not in metadata
                    ), (
                        f"{case_id}/{message_id}: selected Android builds require final runtime identities"
                    )
                condition = metadata.get("androidFeatureFlag")
                if condition is not None:
                    flag_values = android_feature_flag_values(case)
                    assert valid_android_feature_condition(condition, flag_values), (
                        f"{case_id}/{message_id}: invalid or disabled Android resource feature flag"
                    )
                    assert (
                        metadata.get("androidFeatureFlagMode") == "read_write"
                    ) == android_runtime_feature_condition(condition, flag_values), (
                        f"{case_id}/{message_id}: Android runtime resource flag mode must be preserved"
                    )
                runtime_condition = (
                    condition
                    if metadata.get("androidFeatureFlagMode") == "read_write"
                    else (
                        path_condition
                        if metadata.get("androidPathFeatureFlagMode") == "read_write"
                        else None
                    )
                )
                if runtime_condition is None:
                    assert "@flag=" not in message_id, (
                        f"{case_id}/{message_id}: fixed Android messages cannot carry runtime identities"
                    )
                else:
                    native_id = (
                        message_id.rsplit("[", 1)[0]
                        if "arrayIndex" in metadata
                        else message_id
                    )
                    assert native_id.endswith(f"@flag={runtime_condition}"), (
                        f"{case_id}/{message_id}: mutable Android alternatives require stable flag identities"
                    )
                item_flags = metadata.get("androidArrayFeatureFlags")
                if item_flags is not None:
                    assert isinstance(item_flags, dict) and item_flags, (
                        f"{case_id}/{message_id}: Android array feature flags must be a nonempty map"
                    )
                    assert isinstance(metadata.get("arrayIndex"), int), (
                        f"{case_id}/{message_id}: feature-flag slots require a native array position"
                    )
                    for index, item_condition in item_flags.items():
                        assert isinstance(index, str) and index.isdecimal(), (
                            f"{case_id}/{message_id}: invalid Android feature-flag array position"
                        )
                        assert valid_android_feature_condition(
                            item_condition, android_feature_flag_values(case)
                        ), (
                            f"{case_id}/{message_id}: disabled or unknown Android item feature flag"
                        )
                item_modes = metadata.get("androidArrayFeatureFlagModes")
                if item_modes is not None:
                    assert isinstance(item_modes, dict) and item_modes, (
                        f"{case_id}/{message_id}: runtime Android array modes must be a map"
                    )
                    assert all(
                        index in item_flags
                        and mode == "read_write"
                        and android_runtime_feature_condition(
                            item_flags[index], android_feature_flag_values(case)
                        )
                        for index, mode in item_modes.items()
                    ), (
                        f"{case_id}/{message_id}: invalid Android runtime array feature mode"
                    )
                if item_flags is not None:
                    assert {
                        index
                        for index, item_condition in item_flags.items()
                        if android_runtime_feature_condition(
                            item_condition, android_feature_flag_values(case)
                        )
                    } == set(item_modes or {}), (
                        f"{case_id}/{message_id}: runtime Android array flag modes must be preserved"
                    )
                bag_type = metadata.get("androidBagType")
                if bag_type is not None:
                    assert bag_type in {
                        "array",
                        "string-array",
                        "plurals",
                    }, f"{case_id}/{message_id}: unsupported native Android bag type"
                    if "variants" in descriptor:
                        assert bag_type == "plurals", (
                            f"{case_id}/{message_id}: Android plural bag metadata must remain plural"
                        )
                    else:
                        assert isinstance(metadata.get("arrayIndex"), int), (
                            f"{case_id}/{message_id}: Android bag metadata requires an array or plural"
                        )
                        assert (
                            bag_type != "array"
                            or metadata.get("androidGenericArray") is True
                        ), (
                            f"{case_id}/{message_id}: generic array bags require native array metadata"
                        )
                        assert (
                            bag_type != "string-array"
                            or metadata.get("androidGenericArray") is not True
                        ), (
                            f"{case_id}/{message_id}: string-array bags cannot use generic array metadata"
                        )
                generic_format = metadata.get("androidGenericFormat")
                if generic_format is not None:
                    assert (
                        generic_format == "string"
                        and metadata.get("androidGenericString") is True
                    ), (
                        f"{case_id}/{message_id}: invalid native Android generic string format"
                    )
                array_references = metadata.get("androidArrayReferences")
                if array_references is not None:
                    assert isinstance(array_references, dict) and array_references, (
                        f"{case_id}/{message_id}: Android array references must be a nonempty map"
                    )
                    assert isinstance(metadata.get("arrayIndex"), int), (
                        f"{case_id}/{message_id}: reference slots require a native array position"
                    )
                    for index, reference in array_references.items():
                        assert isinstance(index, str) and index.isdecimal(), (
                            f"{case_id}/{message_id}: invalid Android array reference index"
                        )
                        assert int(index) != metadata["arrayIndex"], (
                            f"{case_id}/{message_id}: reference overlaps a translatable array entry"
                        )
                        assert android_reference(reference), (
                            f"{case_id}/{message_id}: invalid Android array reference"
                        )
                array_primitives = metadata.get("androidArrayPrimitives")
                if array_primitives is not None:
                    assert metadata.get("androidGenericArray") is True, (
                        f"{case_id}/{message_id}: native primitive slots require a generic array"
                    )
                    assert isinstance(array_primitives, dict) and array_primitives, (
                        f"{case_id}/{message_id}: Android primitive slots must be a nonempty map"
                    )
                    for index, primitive in array_primitives.items():
                        assert isinstance(index, str) and index.isdecimal(), (
                            f"{case_id}/{message_id}: invalid Android primitive slot index"
                        )
                        assert int(index) != metadata.get("arrayIndex"), (
                            f"{case_id}/{message_id}: primitive overlaps a translatable array entry"
                        )
                        assert index not in (array_references or {}), (
                            f"{case_id}/{message_id}: primitive overlaps an Android reference"
                        )
                        assert isinstance(primitive, str) and primitive, (
                            f"{case_id}/{message_id}: invalid native Android primitive value"
                        )
                if "androidArrayFormat" in metadata:
                    assert (
                        metadata.get("androidGenericArray") is True
                        and metadata["androidArrayFormat"] == "string"
                    ), (
                        f"{case_id}/{message_id}: invalid generic Android array string format"
                    )
                plural_references = metadata.get("androidPluralReferences")
                if plural_references is not None:
                    assert isinstance(plural_references, dict) and plural_references, (
                        f"{case_id}/{message_id}: Android plural references must be a nonempty map"
                    )
                    assert (
                        "variants" in descriptor and "other" in descriptor["variants"]
                    ), (
                        f"{case_id}/{message_id}: plural references require a real ICU fallback"
                    )
                    for quantity, reference in plural_references.items():
                        assert quantity in {
                            "zero",
                            "one",
                            "two",
                            "few",
                            "many",
                        }, (
                            f"{case_id}/{message_id}: invalid Android plural reference quantity"
                        )
                        assert quantity not in descriptor["variants"], (
                            f"{case_id}/{message_id}: reference overlaps a translatable plural branch"
                        )
                        assert android_reference(reference), (
                            f"{case_id}/{message_id}: invalid Android plural reference"
                        )
        valid_count += 1

    overlays = manifest.get("androidOverlays", [])
    assert isinstance(overlays, list), "Android overlays must be an array"
    for overlay in overlays:
        overlay_id = overlay.get("id")
        assert isinstance(overlay_id, str) and overlay_id, (
            "Missing Android overlay identifier"
        )
        assert overlay_id not in seen, f"Duplicate fixture ID: {overlay_id}"
        seen.add(overlay_id)
        assert overlay.get("androidOverlayOracle") in {
            None,
            "skip",
        }, f"{overlay_id}: invalid Android overlay oracle policy"
        if "androidFeatureFlags" in overlay:
            assert valid_android_feature_flags(overlay["androidFeatureFlags"]), (
                f"{overlay_id}: Android overlay feature flags require read-only boolean values"
            )
            assert "androidFeatureFlagDefinitions" not in overlay, (
                f"{overlay_id}: overlay flags use either booleans or ordered definitions"
            )
        if "androidFeatureFlagDefinitions" in overlay:
            assert valid_android_feature_flag_definitions(
                overlay["androidFeatureFlagDefinitions"]
            ), f"{overlay_id}: Android overlay feature definitions require valid modes"
        if "androidSelectedProducts" in overlay:
            assert valid_android_selected_products(
                overlay["androidSelectedProducts"]
            ), f"{overlay_id}: selected Android overlay products require distinct names"
            assert "androidProducts" not in overlay, (
                f"{overlay_id}: selected builds cannot also request separate product snapshots"
            )
        if "androidApplicationPackage" in overlay:
            assert valid_android_application_package(
                overlay["androidApplicationPackage"]
            ), (
                f"{overlay_id}: Android application package requires valid dot-separated identifiers"
            )
        assert isinstance(overlay.get("inputs"), list), (
            f"{overlay_id}: Android overlay inputs must be an array"
        )
        assert ("expected" in overlay) ^ ("error" in overlay), (
            f"{overlay_id}: expected overlay catalog or stable error"
        )
        for resource in overlay["inputs"]:
            assert isinstance(resource.get("sourceSet"), str), (
                f"{overlay_id}: Android overlay source requires its source-set priority"
            )
            assert isinstance(resource.get("resourcePath"), str), (
                f"{overlay_id}: Android overlay source requires its original resource path"
            )
            assert (
                isinstance(resource.get("input"), str)
                and (ROOT / resource["input"]).is_file()
            ), f"{overlay_id}: missing original Android overlay source"
            assert resource.get("encoding") in {
                None,
                "UTF-8-BOM",
                "UTF-16LE-BOM",
                "UTF-16BE-BOM",
                "UTF-16LE",
                "UTF-16BE",
                "ISO-8859-1",
            }, f"{overlay_id}: unsupported native Android overlay encoding"
        if "error" in overlay:
            assert re.fullmatch(r"[A-Z][A-Z_]+", overlay["error"]), (
                f"{overlay_id}: unstable Android overlay error code"
            )
            if "androidOverlayErrorContains" in overlay:
                assert (
                    isinstance(overlay["androidOverlayErrorContains"], str)
                    and overlay["androidOverlayErrorContains"]
                ), f"{overlay_id}: native overlay diagnostic fragment must be nonempty"
            assert "androidLinked" not in overlay, (
                f"{overlay_id}: rejected Android overlays cannot have linked snapshots"
            )
            continue
        assert "androidOverlayErrorContains" not in overlay, (
            f"{overlay_id}: native overlay diagnostics require a rejected source-set overlay"
        )
        expected = ROOT / overlay["expected"]
        assert expected.is_file(), (
            f"{overlay_id}: missing canonical Android overlay catalog"
        )
        catalog = json.loads(expected.read_text(encoding="utf-8"))
        assert (
            catalog.get("schemaVersion") == 1
            and catalog.get("sourceFormat") == "android"
        ), f"{overlay_id}: invalid canonical Android overlay catalog"
        assert isinstance(catalog.get("messages"), dict), (
            f"{overlay_id}: canonical Android overlay messages must be a map"
        )
        for message_id, message in catalog["messages"].items():
            assert set(message) <= MESSAGE_FIELDS, (
                f"{overlay_id}/{message_id}: invalid descriptor"
            )
            assert isinstance(message.get("defaultMessage"), str), (
                f"{overlay_id}/{message_id}: missing canonical Android overlay message"
            )
            metadata = message.get("metadata", {})
            dependencies = metadata.get("androidAttributeDependencies")
            if dependencies is not None:
                assert valid_android_attribute_dependencies(dependencies), (
                    f"{overlay_id}/{message_id}: invalid typed Android attribute dependencies"
                )
            groups = metadata.get("androidStyleableDependencies")
            if groups is not None:
                assert valid_android_styleable_dependencies(groups, dependencies), (
                    f"{overlay_id}/{message_id}: invalid Android styleable dependencies"
                )
            path_condition = metadata.get("androidPathFeatureFlag")
            if path_condition is not None:
                flag_values = android_feature_flag_values(overlay)
                assert valid_android_feature_condition(path_condition, flag_values), (
                    f"{overlay_id}/{message_id}: invalid or disabled Android overlay path flag"
                )
                assert f"flag({path_condition})" in metadata.get(
                    "androidResourcePath", ""
                ).split("/"), (
                    f"{overlay_id}/{message_id}: overlay path flag must match winning resource path"
                )
                assert "androidFeatureFlag" not in metadata, (
                    f"{overlay_id}/{message_id}: overlay path and resource flags cannot coexist"
                )
                assert (
                    metadata.get("androidPathFeatureFlagMode") == "read_write"
                ) == android_runtime_feature_condition(path_condition, flag_values), (
                    f"{overlay_id}/{message_id}: overlay path runtime mode must be preserved"
                )
            condition = metadata.get("androidFeatureFlag")
            if condition is not None:
                flag_values = android_feature_flag_values(overlay)
                assert valid_android_feature_condition(condition, flag_values), (
                    f"{overlay_id}/{message_id}: unknown Android overlay resource feature flag"
                )
                assert (
                    metadata.get("androidFeatureFlagMode") == "read_write"
                ) == android_runtime_feature_condition(condition, flag_values), (
                    f"{overlay_id}/{message_id}: overlay runtime resource mode must be preserved"
                )
            runtime_condition = (
                condition
                if metadata.get("androidFeatureFlagMode") == "read_write"
                else (
                    path_condition
                    if metadata.get("androidPathFeatureFlagMode") == "read_write"
                    else None
                )
            )
            if runtime_condition is None:
                assert "@flag=" not in message_id, (
                    f"{overlay_id}/{message_id}: fixed overlay messages cannot carry runtime identities"
                )
            else:
                native_id = (
                    message_id.rsplit("[", 1)[0]
                    if "arrayIndex" in metadata
                    else message_id
                )
                assert native_id.endswith(f"@flag={runtime_condition}"), (
                    f"{overlay_id}/{message_id}: mutable overlay alternatives require stable flag identities"
                )
            if "androidSelectedProducts" in overlay:
                assert (
                    "@product=" not in message_id and "androidProduct" not in metadata
                ), (
                    f"{overlay_id}/{message_id}: selected overlays require final runtime identities"
                )
            assert metadata.get("androidOverlaySourceSet") in {
                "library",
                "main",
                "product_flavor",
                "build_type",
                "build_variant",
            }, (
                f"{overlay_id}/{message_id}: missing winning Android source-set provenance"
            )
            assert isinstance(metadata.get("androidResourcePath"), str), (
                f"{overlay_id}/{message_id}: missing winning Android resource path"
            )
        linked = ROOT / overlay.get("androidLinked", "")
        assert linked.is_file(), (
            f"{overlay_id}: missing linked native Android resource snapshot"
        )
        assert isinstance(json.loads(linked.read_text(encoding="utf-8")), dict), (
            f"{overlay_id}: linked native Android snapshot must be an object"
        )
        if "androidProducts" in overlay:
            assert (
                isinstance(overlay["androidProducts"], list)
                and overlay["androidProducts"]
                and all(
                    isinstance(product, str) for product in overlay["androidProducts"]
                )
            ), f"{overlay_id}: Android overlay products must be nonempty text values"

    overlay_skeletons = manifest.get("androidOverlaySourceSkeletons", [])
    assert isinstance(overlay_skeletons, list) and overlay_skeletons, (
        "Multi-file Android source-template contracts must not be silently skipped"
    )
    for case in overlay_skeletons:
        case_id = case.get("id")
        assert isinstance(case_id, str) and case_id, (
            "Missing multi-file Android source-template identifier"
        )
        assert case_id not in seen, f"Duplicate fixture ID: {case_id}"
        seen.add(case_id)
        inputs = case.get("inputs")
        assert isinstance(inputs, list) and len(inputs) > 1, (
            f"{case_id}: overlay source templates require multiple source files"
        )
        skeleton = json.loads((ROOT / case["expected"]).read_text(encoding="utf-8"))
        catalog = json.loads((ROOT / case["catalog"]).read_text(encoding="utf-8"))
        translations = json.loads(
            (ROOT / case["translations"]).read_text(encoding="utf-8")
        )
        selected_products = case.get("androidSelectedProducts")
        expected_fields = {"schemaVersion", "sourceFormat", "sources"}
        if "androidApplicationPackage" in case:
            expected_fields.add("androidApplicationPackage")
            assert valid_android_application_package(
                case["androidApplicationPackage"]
            ), f"{case_id}: invalid local Android macro package"
            assert (
                skeleton.get("androidApplicationPackage")
                == case["androidApplicationPackage"]
            )
        if case.get("androidExternalMacros"):
            expected_fields.add("androidMacroOwners")
            assert (
                isinstance(skeleton.get("androidMacroOwners"), dict)
                and skeleton["androidMacroOwners"]
            ), f"{case_id}: cross-file macros require winning definition ownership"
        if selected_products is not None:
            assert valid_android_selected_products(selected_products), (
                f"{case_id}: invalid selected Android source-template products"
            )
            expected_fields.update(
                {"androidSelectedProducts", "androidRuntimeSlotOwners"}
            )
            assert skeleton.get("androidSelectedProducts") == selected_products, (
                f"{case_id}: selected product context was lost"
            )
            runtime_owners = skeleton.get("androidRuntimeSlotOwners")
            assert isinstance(runtime_owners, dict) and runtime_owners, (
                f"{case_id}: selected runtime identities require source owners"
            )
            assert len(set(runtime_owners.values())) == len(runtime_owners), (
                f"{case_id}: selected runtime identities share a source slot"
            )
            source_to_runtime = {
                source: runtime for runtime, source in runtime_owners.items()
            }
        else:
            runtime_owners = None
            source_to_runtime = {}
        assert set(skeleton) == expected_fields
        assert skeleton["schemaVersion"] == 1 and skeleton["sourceFormat"] == "android"
        assert isinstance(translations, dict) and translations
        assert len(skeleton["sources"]) == len(inputs), (
            f"{case_id}: source-file order or ownership was lost"
        )
        known: set[str] = set()
        source_slots: set[str] = set()
        source_identities: set[tuple[str, str]] = set()
        for resource, source in zip(inputs, skeleton["sources"], strict=True):
            source_set = resource["sourceSet"]
            resource_path = resource["resourcePath"]
            assert source_set in {
                "library",
                "main",
                "product_flavor",
                "build_type",
                "build_variant",
            }, f"{case_id}: unsupported Android source-set priority"
            assert source == {
                "sourceSet": source_set,
                "resourcePath": resource_path,
                "skeleton": source["skeleton"],
            }, f"{case_id}: unexpected source-file sidecar fields"
            identity = (source_set, resource_path)
            assert identity not in source_identities, (
                f"{case_id}: duplicate source-set/resource-path identity"
            )
            source_identities.add(identity)
            path = Path(resource_path)
            assert not path.is_absolute() and ".." not in path.parts, (
                f"{case_id}: unsafe original resource path"
            )
            encoding = resource.get("encoding") or "UTF-8"
            codecs = {
                "UTF-8": ("utf-8", b""),
                "UTF-8-BOM": ("utf-8", b"\xef\xbb\xbf"),
                "UTF-16LE": ("utf-16-le", b""),
                "UTF-16BE": ("utf-16-be", b""),
                "UTF-16LE-BOM": ("utf-16-le", b"\xff\xfe"),
                "UTF-16BE-BOM": ("utf-16-be", b"\xfe\xff"),
                "ISO-8859-1": ("iso-8859-1", b""),
            }
            assert encoding in codecs, f"{case_id}: unsupported source-file encoding"
            codec, bom = codecs[encoding]
            source_text = (ROOT / resource["input"]).read_text(encoding="utf-8")
            localized_text = (ROOT / resource["localized"]).read_text(encoding="utf-8")
            original = bom + source_text.encode(codec)
            localized = bom + localized_text.encode(codec)
            nested = source["skeleton"]
            assert nested["schemaVersion"] == 1 and nested["sourceFormat"] == "android"
            assert nested["androidResourcePath"] == resource_path
            assert nested["encoding"] == encoding, (
                f"{case_id}: source encoding was lost"
            )
            assert nested["source"] == source_text, f"{case_id}: source text was lost"
            assert isinstance(nested["slots"], list)
            if source_set == "library":
                assert not nested["slots"] and localized == original, (
                    f"{case_id}: fully shadowed dependency must remain byte-identical"
                )
            cursor = 0
            for slot in nested["slots"]:
                variant = slot.get("variant")
                source_key = (
                    slot["id"] if variant is None else f"{slot['id']}#{variant}"
                )
                runtime_key = source_to_runtime.get(source_key, source_key)
                runtime_id = (
                    runtime_key
                    if variant is None
                    else runtime_key.removesuffix(f"#{variant}")
                )
                message = catalog["messages"].get(runtime_id)
                assert message is not None, (
                    f"{case_id}: source slot points to an overridden resource"
                )
                metadata = message.get("metadata", {})
                assert metadata.get("androidOverlaySourceSet") == source_set
                assert metadata.get("androidResourcePath") == resource_path
                assert variant is None or variant in message.get("variants", {})
                assert source_key not in source_slots, (
                    f"{case_id}: duplicate winning source slot"
                )
                source_slots.add(source_key)
                assert runtime_key not in known, (
                    f"{case_id}: duplicate selected Android runtime identity"
                )
                known.add(runtime_key)
                assert cursor <= slot["start"] <= slot["end"] <= len(original), (
                    f"{case_id}: invalid or overlapping original byte ownership"
                )
                cursor = slot["end"]
            if (
                source_set == "main"
                and not case.get("androidExternalMacros")
                and not case_id.startswith(
                    "android-overlay-source-portable-android-product-unicode-whitespace-"
                )
            ):
                assert ">Main tablet beacon</string>" in localized_text, (
                    f"{case_id}: a shadowed product body was rewritten"
                )
                assert "<item>Main north</item>" in localized_text, (
                    f"{case_id}: a shadowed array body was rewritten"
                )
                assert ">%1$d main light</item>" in localized_text, (
                    f"{case_id}: a shadowed plural body was rewritten"
                )
                assert ">Lower coast</string>" in localized_text, (
                    f"{case_id}: a nontranslatable tombstone rewrote its shadowed source"
                )
                if selected_products is not None and "tablet" in selected_products:
                    assert ">Main default beacon</string>" in localized_text, (
                        f"{case_id}: unselected default product source was rewritten"
                    )
            if (
                source_set == "build_type"
                and not case.get("androidExternalMacros")
                and selected_products is not None
                and "tablet" not in selected_products
            ):
                assert ">Upper tablet beacon</string>" in localized_text, (
                    f"{case_id}: unselected tablet product source was rewritten"
                )
        if runtime_owners is not None:
            assert set(runtime_owners.values()) == source_slots, (
                f"{case_id}: selected runtime identities do not own every source slot"
            )
        if case.get("androidExternalMacros"):
            for name, owner in skeleton["androidMacroOwners"].items():
                assert isinstance(name, str) and name and isinstance(owner, dict)
                assert set(owner) == {"sourceSet", "resourcePath"}
                assert (
                    owner["sourceSet"],
                    owner["resourcePath"],
                ) in source_identities, (
                    f"{case_id}: macro definition owner is not an original source file"
                )
                assert name not in known, (
                    f"{case_id}: build macro declarations must not become translation slots"
                )
            assert skeleton["androidMacroOwners"]["harbor_phrase"] == {
                "sourceSet": "build_type",
                "resourcePath": "src/debug/res/values/definitions.xml",
            }, f"{case_id}: higher-priority macro definition lost provenance"
        assert known == set(translations), (
            f"{case_id}: source ownership differs from supplied translation values"
        )
        expected_keys = {
            f"{identity}#{quantity}"
            for identity, message in catalog["messages"].items()
            for quantity in message.get("variants", {})
        } | {
            identity
            for identity, message in catalog["messages"].items()
            if "variants" not in message
        }
        assert known == expected_keys, (
            f"{case_id}: winning resources do not own exactly one original source slot"
        )
        for key in ("androidLinked", "androidLocalizedLinked"):
            linked = json.loads((ROOT / case[key]).read_text(encoding="utf-8"))
            if selected_products is None:
                assert valid_android_selected_products(case.get("androidProducts")), (
                    f"{case_id}: linked product snapshots require distinct native products"
                )
                assert set(linked.get("products", {})) == set(
                    case["androidProducts"]
                ), (
                    f"{case_id}: native source ownership must cover all selected products"
                )
            else:
                assert "products" not in linked and "strings" in linked, (
                    f"{case_id}: selected Android products require one real runtime APK"
                )
        for rejected in case.get("reject", []):
            assert rejected["error"] == "UNKNOWN_OVERLAY_SKELETON_SLOT", (
                f"{case_id}: unstable multi-file source-template rejection"
            )
            assert set(rejected["translations"]) - known, (
                f"{case_id}: rejected source template must target an unknown/shadowed slot"
            )
        for rejected in case.get("rejectMarkup", []):
            assert rejected["error"] == "INVALID_SKELETON_MARKUP", (
                f"{case_id}: changed macro-expanded protected markup must fail safely"
            )
            assert set(rejected["translations"]) <= known, (
                f"{case_id}: macro markup mutation targets an unknown runtime slot"
            )
        for rejected in case.get("rejectBuilds", []):
            assert not valid_android_selected_products(
                rejected.get("androidSelectedProducts")
            ), f"{case_id}: invalid selected build must violate native product grammar"
            assert rejected["error"] == "INVALID_ANDROID_PRODUCT", (
                f"{case_id}: invalid selected Android products must fail natively"
            )

    shadow_comparisons = manifest.get("shadowComparisons", [])
    assert isinstance(shadow_comparisons, list), "Shadow comparisons must be an array"
    fixtures = {case["id"]: case for case in manifest["cases"]}
    for comparison in shadow_comparisons:
        comparison_id = comparison.get("id")
        assert isinstance(comparison_id, str) and comparison_id, (
            "Missing shadow comparison ID"
        )
        assert comparison_id not in seen, f"Duplicate fixture ID: {comparison_id}"
        seen.add(comparison_id)
        fixture = fixtures.get(comparison.get("case"))
        assert fixture is not None, f"{comparison_id}: unknown canonical fixture"
        differential = fixture.get("okapi", {})
        assert differential.get("policy") in {
            "match",
            "different",
        }, f"{comparison_id}: shadow comparison requires an executable legacy filter"
        assert isinstance(comparison.get("expected"), str), (
            f"{comparison_id}: missing implementation-neutral report snapshot"
        )
        report_path = ROOT / comparison["expected"]
        assert report_path.is_file(), (
            f"{comparison_id}: missing shared shadow report snapshot"
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))
        assert set(report) == {
            "sourceFormat",
            "canonicalUnits",
            "legacyUnits",
            "outcome",
            "differences",
        }, f"{comparison_id}: unexpected shared shadow report fields"
        assert report["sourceFormat"] in FORMATS, (
            f"{comparison_id}: invalid shadow source format"
        )
        assert report["outcome"] in {
            "match",
            "mismatch",
        }, f"{comparison_id}: invalid shadow comparison outcome"
        assert (
            isinstance(report["canonicalUnits"], int) and report["canonicalUnits"] >= 0
        )
        assert isinstance(report["legacyUnits"], int) and report["legacyUnits"] >= 0
        assert isinstance(report["differences"], list), (
            f"{comparison_id}: shared shadow differences must be an array"
        )
        assert bool(report["differences"]) == (report["outcome"] == "mismatch"), (
            f"{comparison_id}: shadow outcome must agree with its differences"
        )
        catalog = json.loads((ROOT / fixture["expected"]).read_text(encoding="utf-8"))
        for difference in report["differences"]:
            assert set(difference) <= {
                "category",
                "id",
                "count",
                "canonicalIds",
            }, f"{comparison_id}: invalid bounded-cardinality difference"
            assert difference.get("category") in {
                "legacy_projection_collision",
                "duplicate_legacy",
                "missing_legacy",
                "unexpected_legacy",
                "source_mismatch",
                "comment_mismatch",
                "plural_mismatch",
                "usage_mismatch",
            }, f"{comparison_id}: unstable shadow difference category"
            assert isinstance(difference.get("id"), str), (
                f"{comparison_id}: missing comparison message identity"
            )
            if "count" in difference:
                assert (
                    isinstance(difference["count"], int) and difference["count"] > 0
                ), f"{comparison_id}: invalid duplicated/missing message count"
            if difference["category"] == "legacy_projection_collision":
                identities = difference.get("canonicalIds")
                assert (
                    fixture["format"] == "android"
                    and isinstance(identities, list)
                    and len(identities) == difference.get("count")
                    and len(identities) > 1
                    and identities == sorted(set(identities))
                ), f"{comparison_id}: invalid native-qualified projection identities"
                for identity in identities:
                    assert isinstance(identity, str), (
                        f"{comparison_id}: native-qualified projection identity must be text"
                    )
                    identifier, separator, category = identity.rpartition("#")
                    if separator and category in {
                        "zero",
                        "one",
                        "two",
                        "few",
                        "many",
                        "other",
                    }:
                        assert identifier in catalog["messages"] and isinstance(
                            catalog["messages"][identifier].get("variants"), dict
                        ), f"{comparison_id}: unknown native-qualified plural identity"
                    else:
                        assert identity in catalog["messages"], (
                            f"{comparison_id}: unknown native-qualified message identity"
                        )
            else:
                assert "canonicalIds" not in difference, (
                    f"{comparison_id}: native-qualified identities require a projection collision"
                )

    skeletons = manifest.get("sourceSkeletons", [])
    assert skeletons, (
        "Source-preserving skeleton contracts must not be silently skipped"
    )
    skeleton_ids: set[str] = set()
    for case in skeletons:
        case_id = case["id"]
        assert case_id not in seen and case_id not in skeleton_ids, (
            f"{case_id}: duplicate portable source-skeleton contract"
        )
        skeleton_ids.add(case_id)
        assert case["format"] in {
            "android",
            "apple_strings",
            "apple_stringsdict",
            "apple_xcstrings",
            "gettext_po",
            "java_properties",
            "yaml",
            "javascript",
            "typescript",
            "resx",
            "xtb",
        }, f"{case_id}: unsupported source-skeleton format"
        assert case.get("encoding") in {
            None,
            "UTF-8-BOM",
            "UTF-16LE-BOM",
            "UTF-16BE-BOM",
            "UTF-16LE",
            "UTF-16BE",
            "ISO-8859-1",
            "CP1252",
            "US-ASCII",
        }, f"{case_id}: unsupported source-skeleton encoding"
        if case.get("encoding") in {"UTF-16LE", "UTF-16BE"}:
            assert case["format"] == "android", (
                f"{case_id}: BOM-less UTF-16 source templates require Android compiler ownership"
            )
        if case.get("encoding") in {"CP1252", "US-ASCII"}:
            assert case["format"] == "gettext_po", (
                f"{case_id}: legacy code-page source templates currently belong only to gettext"
            )
        assert case.get("lineEndings") in {
            None,
            "CR",
            "CRLF",
        }, f"{case_id}: unsupported source-skeleton line endings"
        if "androidFeatureFlagDefinitions" in case:
            assert case[
                "format"
            ] == "android" and valid_android_feature_flag_definitions(
                case["androidFeatureFlagDefinitions"]
            ), f"{case_id}: source feature flags require valid Android definitions"
        if "resourcePath" in case:
            path = case["resourcePath"]
            assert case["format"] == "android" and isinstance(path, str) and path, (
                f"{case_id}: source resource paths require Android XML"
            )
            assert not Path(path).is_absolute() and ".." not in Path(path).parts, (
                f"{case_id}: source resource paths must remain relative and safe"
            )
        if "androidConfiguration" in case:
            assert case["format"] == "android" and isinstance(
                case["androidConfiguration"], bool
            ), f"{case_id}: native source configuration belongs only to Android"
        if "androidReorderableInline" in case:
            assert (
                case["format"] == "android" and case["androidReorderableInline"] is True
            ), f"{case_id}: source-owned inline reordering belongs only to Android"
        if "androidDecoratedInline" in case:
            assert (
                case["format"] == "android"
                and case.get("androidReorderableInline")
                and case["androidDecoratedInline"] is True
            ), (
                f"{case_id}: interleaved Android decorations require reorderable inline ownership"
            )
        if "xcstringsSubstitutionSlots" in case:
            assert (
                case["format"] == "apple_xcstrings"
                and case["xcstringsSubstitutionSlots"] is True
            ), f"{case_id}: substitution trees belong only to Xcode String Catalogs"
        if "xcstringsDeviceSubstitutionSlots" in case:
            assert (
                case["format"] == "apple_xcstrings"
                and case.get("xcstringsSubstitutionSlots")
                and case["xcstringsDeviceSubstitutionSlots"] is True
            ), f"{case_id}: device-owned roots require Xcode substitution slots"
        if "xcstringsSubstitutionArgumentSlots" in case:
            assert (
                case["format"] == "apple_xcstrings"
                and case.get("xcstringsSubstitutionSlots")
                and case["xcstringsSubstitutionArgumentSlots"] is True
            ), f"{case_id}: mixed native arguments require Xcode substitution slots"
        for rejected in case.get("androidSkeletonReject", []):
            assert case["format"] == "android" and case.get(
                "androidReorderableInline"
            ), f"{case_id}: unsafe inline mutations require an Android inline contract"
            assert set(rejected) == {
                "translations",
                "error",
            }, f"{case_id}: unstable Android source-markup rejection contract"
            assert (
                isinstance(rejected["translations"], dict) and rejected["translations"]
            ), (
                f"{case_id}: rejected Android source mutations must own translation slots"
            )
            assert rejected["error"] == "INVALID_SKELETON_MARKUP", (
                f"{case_id}: unsafe Android inline mutations need a stable diagnostic"
            )
        for rejected in case.get("xcstringsSkeletonReject", []):
            assert case["format"] == "apple_xcstrings" and case.get(
                "xcstringsSubstitutionSlots"
            ), (
                f"{case_id}: unsafe substitution mutations require an Xcode selector contract"
            )
            assert set(rejected) == {
                "translations",
                "error",
            }, f"{case_id}: unstable Xcode source-substitution rejection contract"
            assert (
                isinstance(rejected["translations"], dict) and rejected["translations"]
            ), f"{case_id}: rejected Xcode mutations must own translation slots"
            assert rejected["error"] == "INVALID_SKELETON_SUBSTITUTION", (
                f"{case_id}: missing/duplicated Xcode selectors need a stable diagnostic"
            )
        if "androidFlatName" in case:
            assert (
                case["format"] == "android"
                and isinstance(case["androidFlatName"], str)
                and case["androidFlatName"]
            ), f"{case_id}: native intermediate identity belongs only to Android"
        if products := case.get("androidProductLinks", []):
            assert case["format"] == "android", (
                f"{case_id}: native product selection belongs only to Android"
            )
            assert isinstance(products, list) and products, (
                f"{case_id}: native product source checks require linked selections"
            )
            selected: set[str] = set()
            for product in products:
                assert set(product) == {
                    "product",
                    "original",
                    "localized",
                }, f"{case_id}: unstable native source-product contract"
                assert isinstance(product["product"], str) and product["product"]
                assert product["product"] not in selected, (
                    f"{case_id}: repeated native source-product selection"
                )
                selected.add(product["product"])
                for state in ("original", "localized"):
                    assert (
                        isinstance(product[state], str)
                        and (ROOT / product[state]).is_file()
                    ), f"{case_id}: missing linked {state} product snapshot"
        if case["format"] == "apple_xcstrings":
            for snapshot in ("xcstringsCompiled", "xcstringsLocalizedCompiled"):
                assert (
                    isinstance(case.get(snapshot), str)
                    and (ROOT / case[snapshot]).is_file()
                ), (
                    f"{case_id}: missing native Xcode source-skeleton snapshot {snapshot}"
                )
            for field in (
                "xcstringsOriginalRuntimeSamples",
                "xcstringsLocalizedRuntimeSamples",
            ):
                for sample in case.get(field, []):
                    assert set(sample) in (
                        {"message", "arguments", "expected"},
                        {"message", "arguments", "expected", "fallback"},
                    ), f"{case_id}: unstable native Xcode source-runtime sample"
                    assert isinstance(sample["message"], str) and sample["message"]
                    assert isinstance(sample["arguments"], list)
                    assert isinstance(sample["expected"], str)
                    if "fallback" in sample:
                        assert sample["fallback"] is True and sample["expected"] == (
                            "__MOJITO_FOUNDATION_FALLBACK__"
                        ), f"{case_id}: unavailable Xcode device must use explicit native fallback"
        if case["format"] == "apple_stringsdict":
            for snapshot in ("appleCompiled", "appleLocalizedCompiled"):
                assert (
                    isinstance(case.get(snapshot), str)
                    and (ROOT / case[snapshot]).is_file()
                ), (
                    f"{case_id}: missing native Foundation stringsdict snapshot {snapshot}"
                )
            for field in (
                "appleOriginalRuntimeSamples",
                "appleLocalizedRuntimeSamples",
            ):
                for sample in case.get(field, []):
                    assert set(sample) in (
                        {"message", "arguments", "expected"},
                        {"message", "arguments", "expected", "presentationWidth"},
                    ), f"{case_id}: unstable native Foundation source-runtime sample"
                    assert isinstance(sample["message"], str) and sample["message"]
                    assert isinstance(sample["arguments"], list)
                    assert isinstance(sample["expected"], str)
                    if "presentationWidth" in sample:
                        assert (
                            isinstance(sample["presentationWidth"], int)
                            and not isinstance(sample["presentationWidth"], bool)
                            and sample["presentationWidth"] >= 0
                        ), f"{case_id}: invalid Foundation presentation width"
        if case["format"] == "gettext_po":
            single_domain = "gettextCompiled" in case
            split_domains = "gettextDomainCompiled" in case
            assert single_domain != split_domains, (
                f"{case_id}: GNU source templates require exactly one native MO output contract"
            )
            snapshots = (
                ("gettextCompiled", "gettextLocalizedCompiled")
                if single_domain
                else ("gettextDomainCompiled", "gettextLocalizedDomainCompiled")
            )
            for snapshot in snapshots:
                assert (
                    isinstance(case.get(snapshot), str)
                    and (ROOT / case[snapshot]).is_file()
                ), (
                    f"{case_id}: missing original/localized GNU source MO snapshot {snapshot}"
                )
            if split_domains:
                compiled = [
                    json.loads((ROOT / case[snapshot]).read_text(encoding="utf-8"))
                    for snapshot in snapshots
                ]
                assert all(
                    isinstance(domains, dict) and domains for domains in compiled
                ), (
                    f"{case_id}: GNU source-domain snapshots must contain native catalogs"
                )
                assert set(compiled[0]) == set(compiled[1]), (
                    f"{case_id}: localized GNU templates changed their native domain ownership"
                )
                assert "messages" in compiled[0] and len(compiled[0]) > 1, (
                    f"{case_id}: source-owned GNU domain templates must cover implicit and named domains"
                )
                for domains in compiled:
                    for domain, catalog in domains.items():
                        assert (
                            isinstance(domain, str)
                            and domain
                            and "/" not in domain
                            and "\\" not in domain
                            and not any(character.isspace() for character in domain)
                        ), f"{case_id}: unsafe source-owned GNU domain output name"
                        assert isinstance(catalog, dict) and set(catalog) <= {
                            "entries",
                            "plurals",
                            "header",
                        }, f"{case_id}: invalid source-owned GNU domain catalog"
                        header = catalog.get("header", {})
                        assert isinstance(header, dict) and set(header) <= {
                            "locale",
                            "pluralForms",
                            "fields",
                        }, f"{case_id}: invalid source-owned GNU domain header"
                        if "fields" in header:
                            validate_gettext_header_fields(header["fields"], case_id)
                domains = case.get("gettextNativeDomains")
                assert isinstance(domains, dict) and set(domains) == {
                    "source",
                    "localized",
                }, (
                    f"{case_id}: GNU source templates require original/localized native domains"
                )
                for state in ("source", "localized"):
                    assert isinstance(domains[state], list) and all(
                        isinstance(domain, str) for domain in domains[state]
                    ), f"{case_id}: invalid {state} source-owned GNU domains"
                    assert set(domains[state]) == set(compiled[0]) - {"messages"}, (
                        f"{case_id}: {state} GNU domain directives disagree with compiled MO ownership"
                    )
                assert case.get("gettextSingleOutput") == "reject", (
                    f"{case_id}: source-owned GNU domains must reject merged single-output compilation"
                )
                for field in (
                    "gettextOriginalRuntimeSamples",
                    "gettextLocalizedRuntimeSamples",
                ):
                    samples = case.get(field)
                    assert isinstance(samples, list) and samples, (
                        f"{case_id}: source-owned GNU domains require original/localized plural samples"
                    )
                    for sample in samples:
                        assert set(sample) == {
                            "domain",
                            "message",
                            "plural",
                            "value",
                            "expected",
                        }, f"{case_id}: invalid source-owned GNU runtime sample"
                        assert sample["domain"] in compiled[0], (
                            f"{case_id}: GNU source runtime references an unknown native domain"
                        )
                        assert all(
                            isinstance(sample[name], str)
                            for name in ("domain", "message", "plural", "expected")
                        ), f"{case_id}: GNU source runtime text must be strings"
                        assert (
                            isinstance(sample["value"], int) and sample["value"] >= 0
                        ), f"{case_id}: GNU source runtime count must be nonnegative"
        encoding = case.get("encoding") or "UTF-8"
        codec = {
            "UTF-8": "utf-8",
            "UTF-8-BOM": "utf-8",
            "UTF-16LE-BOM": "utf-16-le",
            "UTF-16BE-BOM": "utf-16-be",
            "UTF-16LE": "utf-16-le",
            "UTF-16BE": "utf-16-be",
            "ISO-8859-1": "iso-8859-1",
            "CP1252": "cp1252",
            "US-ASCII": "ascii",
        }[encoding]
        bom = {
            "UTF-8": b"",
            "UTF-8-BOM": b"\xef\xbb\xbf",
            "UTF-16LE-BOM": b"\xff\xfe",
            "UTF-16BE-BOM": b"\xfe\xff",
            "UTF-16LE": b"",
            "UTF-16BE": b"",
            "ISO-8859-1": b"",
            "CP1252": b"",
            "US-ASCII": b"",
        }[encoding]
        source = (ROOT / case["input"]).read_text(encoding="utf-8")
        localized = (ROOT / case["localized"]).read_text(encoding="utf-8")
        if case.get("lineEndings") == "CRLF":
            source = source.replace("\r\n", "\n").replace("\n", "\r\n")
            localized = localized.replace("\r\n", "\n").replace("\n", "\r\n")
        elif case.get("lineEndings") == "CR":
            source = source.replace("\r\n", "\n").replace("\n", "\r")
            localized = localized.replace("\r\n", "\n").replace("\n", "\r")
        original_bytes = bom + source.encode(codec)
        localized_bytes = bom + localized.encode(codec)
        skeleton = json.loads((ROOT / case["expected"]).read_text(encoding="utf-8"))
        required_skeleton = {
            "schemaVersion",
            "sourceFormat",
            "encoding",
            "source",
            "slots",
        }
        assert (
            required_skeleton
            <= set(skeleton)
            <= required_skeleton
            | {"androidFeatureFlags", "androidResourcePath", "appleTargetLocale"}
        ), f"{case_id}: unstable source-skeleton fields"
        if requested_locale := case.get("xcstringsTargetLocale"):
            assert case["format"] == "apple_xcstrings"
            target = skeleton["appleTargetLocale"].replace("_", "-")
            requested = requested_locale.replace("_", "-")
            if case.get("xcstringsDeprecatedLocale"):
                language, *suffix = target.split("-")
                aliases = {"iw": "he", "in": "id", "ji": "yi"}
                target = "-".join([aliases.get(language, language), *suffix])
            if case.get("xcstringsTerritoryLocale") == "british" and target == "en-UK":
                target = "en-GB"
            if grandfathered := case.get("xcstringsGrandfatheredLocale"):
                target = {"bokmal": "nb", "nynorsk": "nn"}[grandfathered]
            assert target == requested, (
                f"{case_id}: target locale lost its native identity"
            )
        else:
            assert "appleTargetLocale" not in skeleton, (
                f"{case_id}: target locale must not appear without explicit opt-in"
            )
        if resource_path := case.get("resourcePath"):
            assert skeleton.get("androidResourcePath") == resource_path, (
                f"{case_id}: source resource path does not match original Android context"
            )
        else:
            assert "androidResourcePath" not in skeleton, (
                f"{case_id}: source resource path must not appear without Android context"
            )
        definitions = case.get("androidFeatureFlagDefinitions")
        if definitions:
            assert skeleton.get("androidFeatureFlags") == [
                {
                    "name": flag["name"],
                    "readOnly": flag["mode"] == "read_only",
                    "value": flag["value"],
                }
                for flag in definitions
            ], f"{case_id}: source feature context does not match AAPT2 declarations"
        else:
            assert "androidFeatureFlags" not in skeleton, (
                f"{case_id}: source feature context must not appear without declarations"
            )
        assert (
            skeleton["schemaVersion"] == 1
            and skeleton["sourceFormat"] == case["format"]
        )
        assert skeleton["encoding"] == encoding and skeleton["source"] == source, (
            f"{case_id}: source skeleton must retain exact decoded source and encoding"
        )
        assert isinstance(skeleton["slots"], list) and skeleton["slots"]
        translations = json.loads(
            (ROOT / case["translations"]).read_text(encoding="utf-8")
        )
        if target_locale := skeleton.get("appleTargetLocale"):
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            declared_source_locale = original_root["sourceLanguage"]
            for identifier, original_entry in original_root["strings"].items():
                localized_entry = localized_root["strings"][identifier]
                if original_entry.get("shouldTranslate") is False:
                    assert localized_entry == original_entry, (
                        f"{case_id}/{identifier}: protected target descriptor changed"
                    )
                    continue
                source_locale = declared_source_locale
                if source_locale not in original_entry["localizations"]:
                    if case.get("xcstringsSourceAliasTargetSubstitutions"):
                        owners = set(original_entry["localizations"]) - {target_locale}
                        assert len(owners) == 1, (
                            f"{case_id}/{identifier}: ambiguous aliased development ownership"
                        )
                        (source_locale,) = owners
                    elif case.get("xcstringsSourceAliasFirstLocaleSubstitutions"):
                        owners = set(original_entry["localizations"]) - {
                            target_locale,
                            "de",
                        }
                        assert len(owners) == 1, (
                            f"{case_id}/{identifier}: ambiguous first-locale development ownership"
                        )
                        (source_locale,) = owners
                assert (
                    localized_entry["localizations"][source_locale]
                    == original_entry["localizations"][source_locale]
                ), (
                    f"{case_id}/{identifier}: source locale changed during target insertion"
                )
                original_target = original_entry["localizations"].get(target_locale)
                localized_target = localized_entry["localizations"][target_locale]
                if original_target is not None and "substitutions" in original_target:
                    original_substitutions = original_target["substitutions"]
                    localized_substitutions = localized_target["substitutions"]
                    assert (
                        original_substitutions.keys() == localized_substitutions.keys()
                    ), f"{case_id}/{identifier}: target substitution selectors changed"
                    for selector, definition in original_substitutions.items():
                        localized_definition = localized_substitutions[selector]
                        for field in ("argNum", "formatSpecifier"):
                            assert localized_definition[field] == definition[field], (
                                f"{case_id}/{identifier}/{selector}: target substitution {field} changed"
                            )
                        original_plural = definition["variations"]["plural"]
                        localized_plural = localized_definition["variations"]["plural"]
                        assert original_plural.keys() == localized_plural.keys(), (
                            f"{case_id}/{identifier}/{selector}: target substitution categories changed"
                        )
                        for category, branch in original_plural.items():
                            assert (
                                localized_plural[category]["stringUnit"]["state"]
                                == branch["stringUnit"]["state"]
                            ), (
                                f"{case_id}/{identifier}/{selector}/{category}: target substitution review state changed"
                            )
                if original_target is None:
                    if "substitutions" in localized_target:
                        assert case.get("xcstringsTargetSubstitutionInsertion"), (
                            f"{case_id}/{identifier}: target substitution insertion requires explicit opt-in"
                        )
                        original_source = original_entry["localizations"][source_locale]
                        original_substitutions = original_source["substitutions"]
                        localized_substitutions = localized_target["substitutions"]
                        assert (
                            original_substitutions.keys()
                            == localized_substitutions.keys()
                        ), f"{case_id}/{identifier}: inserted target selectors changed"
                        for selector, definition in localized_substitutions.items():
                            source_definition = original_substitutions[selector]
                            assert all(
                                definition[field] == source_definition[field]
                                for field in ("argNum", "formatSpecifier")
                            ), (
                                f"{case_id}/{identifier}/{selector}: inserted target selector arguments changed"
                            )
                            plural = definition["variations"]["plural"]
                            evidence = set().union(
                                *(
                                    candidate["localizations"][target_locale][
                                        "substitutions"
                                    ][selector]["variations"]["plural"].keys()
                                    for candidate in original_root["strings"].values()
                                    if candidate.get("shouldTranslate") is not False
                                    and isinstance(
                                        candidate["localizations"].get(target_locale),
                                        dict,
                                    )
                                    and selector
                                    in candidate["localizations"][target_locale].get(
                                        "substitutions", {}
                                    )
                                )
                            )
                            if not evidence and case.get(
                                "xcstringsFirstLocaleCategories"
                            ):
                                evidence = set(apple_cardinal_categories(target_locale))
                                assert all(
                                    not isinstance(
                                        candidate["localizations"].get(target_locale),
                                        dict,
                                    )
                                    for candidate in original_root["strings"].values()
                                    if candidate.get("shouldTranslate") is not False
                                ), (
                                    f"{case_id}/{identifier}/{selector}: first-locale "
                                    "substitutions cannot bypass existing target ownership"
                                )
                            assert evidence and set(plural) == evidence, (
                                f"{case_id}/{identifier}/{selector}: inserted target categories differ from native evidence"
                            )
                            assert "other" in plural, (
                                f"{case_id}/{identifier}/{selector}: inserted target selector lacks other"
                            )
                            assert all(
                                branch["stringUnit"]["state"] == "translated"
                                for branch in plural.values()
                            ), (
                                f"{case_id}/{identifier}/{selector}: inserted target selector lacks translated state"
                            )
                        if "device" in original_source.get("variations", {}):
                            assert translations[identifier].startswith(
                                "{device, select,"
                            ), (
                                f"{case_id}/{identifier}: target substitution devices require one complete ICU select"
                            )
                            source_devices = original_source["variations"]["device"]
                            localized_devices = localized_target["variations"]["device"]
                            assert source_devices.keys() == localized_devices.keys(), (
                                f"{case_id}/{identifier}: inserted target substitution devices changed"
                            )
                            assert all(
                                branch["stringUnit"]["state"] == "translated"
                                for branch in localized_devices.values()
                            ), (
                                f"{case_id}/{identifier}: inserted target device roots lack translated state"
                            )
                        else:
                            assert all(
                                f"{{{selector}, plural," in translations[identifier]
                                for selector in original_substitutions
                            ), (
                                f"{case_id}/{identifier}: target substitutions require one complete ICU message"
                            )
                            assert (
                                localized_target["stringUnit"]["state"] == "translated"
                            ), (
                                f"{case_id}/{identifier}: inserted target root lacks translated state"
                            )
                    elif "device" in localized_target.get("variations", {}):
                        assert case.get("xcstringsTargetDeviceInsertion"), (
                            f"{case_id}/{identifier}: target device insertion requires explicit opt-in"
                        )
                        original_devices = original_entry["localizations"][
                            source_locale
                        ]["variations"]["device"]
                        localized_devices = localized_target["variations"]["device"]
                        assert original_devices.keys() == localized_devices.keys(), (
                            f"{case_id}/{identifier}: inserted target device branches changed"
                        )
                        for device, branch in localized_devices.items():
                            if "stringUnit" in branch:
                                assert branch["stringUnit"]["state"] == "translated", (
                                    f"{case_id}/{identifier}/{device}: inserted target device lacks translated state"
                                )
                                continue
                            plural = branch["variations"]["plural"]
                            assert "other" in plural, (
                                f"{case_id}/{identifier}/{device}: inserted target device plural lacks other"
                            )
                            if case.get("xcstringsFirstLocaleDevices"):
                                required = set(apple_cardinal_categories(target_locale))
                                assert set(plural) == required, (
                                    f"{case_id}/{identifier}/{device}: first-locale "
                                    "device plural differs from expected ICU categories"
                                )
                            assert all(
                                category["stringUnit"]["state"] == "translated"
                                for category in plural.values()
                            ), (
                                f"{case_id}/{identifier}/{device}: inserted target device plural lacks translated state"
                            )
                    elif "variations" in localized_target:
                        plural = localized_target["variations"]["plural"]
                        assert "other" in plural, (
                            f"{case_id}/{identifier}: inserted target plural lacks other"
                        )
                        assert all(
                            branch["stringUnit"]["state"] == "translated"
                            for branch in plural.values()
                        ), (
                            f"{case_id}/{identifier}: inserted target plural lacks translated state"
                        )
                    else:
                        assert (
                            localized_target["stringUnit"]["state"] == "translated"
                        ), (
                            f"{case_id}/{identifier}: inserted target lacks translated state"
                        )
                elif "device" in original_target.get("variations", {}):
                    original_devices = original_target["variations"]["device"]
                    localized_devices = localized_target["variations"]["device"]
                    assert original_devices.keys() == localized_devices.keys(), (
                        f"{case_id}/{identifier}: target device branches changed"
                    )
                    for device, branch in original_devices.items():
                        localized_branch = localized_devices[device]
                        if "stringUnit" in branch:
                            assert (
                                localized_branch["stringUnit"]["state"]
                                == branch["stringUnit"]["state"]
                            ), (
                                f"{case_id}/{identifier}/{device}: target device review state changed"
                            )
                            continue
                        original_plural = branch["variations"]["plural"]
                        localized_plural = localized_branch["variations"]["plural"]
                        assert original_plural.keys() == localized_plural.keys(), (
                            f"{case_id}/{identifier}/{device}: target device plural categories changed"
                        )
                        for category, variation in original_plural.items():
                            assert (
                                localized_plural[category]["stringUnit"]["state"]
                                == variation["stringUnit"]["state"]
                            ), (
                                f"{case_id}/{identifier}/{device}/{category}: target device review state changed"
                            )
                elif "variations" in original_target:
                    original_plural = original_target["variations"]["plural"]
                    localized_plural = localized_target["variations"]["plural"]
                    assert original_plural.keys() == localized_plural.keys(), (
                        f"{case_id}/{identifier}: target plural categories changed"
                    )
                    for category, branch in original_plural.items():
                        assert (
                            localized_plural[category]["stringUnit"]["state"]
                            == branch["stringUnit"]["state"]
                        ), (
                            f"{case_id}/{identifier}/{category}: target review state changed"
                        )
                else:
                    assert (
                        localized_target["stringUnit"]["state"]
                        == original_target["stringUnit"]["state"]
                    ), f"{case_id}/{identifier}: existing target review state changed"
                for locale, value in original_entry["localizations"].items():
                    if locale not in {source_locale, target_locale}:
                        assert localized_entry["localizations"][locale] == value, (
                            f"{case_id}/{identifier}: unrelated target locale changed"
                        )
            if case.get("xcstringsTargetPluralInsertion"):
                evidence = set().union(
                    *(
                        entry["localizations"][target_locale]["variations"]["plural"]
                        for entry in original_root["strings"].values()
                        if entry.get("shouldTranslate") is not False
                        and isinstance(entry["localizations"].get(target_locale), dict)
                        and "variations" in entry["localizations"][target_locale]
                        and "plural"
                        in entry["localizations"][target_locale]["variations"]
                    )
                )
                if not evidence and case.get("xcstringsFirstLocaleCategories"):
                    evidence = set(apple_cardinal_categories(target_locale))
                    assert all(
                        not isinstance(entry["localizations"].get(target_locale), dict)
                        for entry in original_root["strings"].values()
                        if entry.get("shouldTranslate") is not False
                    ), (
                        f"{case_id}: first-locale categories cannot bypass existing target ownership"
                    )
                assert evidence, (
                    f"{case_id}: missing native target plural category evidence"
                )
                inserted = {
                    identifier
                    for identifier, entry in original_root["strings"].items()
                    if entry.get("shouldTranslate") is not False
                    and entry["localizations"].get(target_locale) is None
                    and "variations" in entry["localizations"][source_locale]
                }
                assert inserted, (
                    f"{case_id}: missing atomic target plural insertion cases"
                )
                for identifier in inserted:
                    assert translations[identifier].startswith("{count, plural,"), (
                        f"{case_id}/{identifier}: target plural requires one complete ICU message"
                    )
                    assert (
                        localized_root["strings"][identifier]["localizations"][
                            target_locale
                        ]["variations"]["plural"].keys()
                        == evidence
                    ), (
                        f"{case_id}/{identifier}: target plural categories differ from native evidence"
                    )
                    owned = [
                        slot for slot in skeleton["slots"] if slot["id"] == identifier
                    ]
                    assert len(owned) == 1 and "variant" not in owned[0], (
                        f"{case_id}/{identifier}: target plural insertion requires one atomic slot"
                    )
        if case.get("xcstringsInsertSourceLocale"):
            assert case["format"] == "apple_xcstrings", (
                f"{case_id}: nullable source insertion belongs only to Xcode catalogs"
            )
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            language = original_root["sourceLanguage"]
            missing = {
                identifier
                for identifier, entry in original_root["strings"].items()
                if entry.get("shouldTranslate") is not False
                and language not in entry.get("localizations", {})
            }
            inserted = {
                identifier
                for identifier, entry in original_root["strings"].items()
                if entry.get("shouldTranslate") is not False
                and entry.get("localizations", {}).get(language) is None
            }
            assert inserted, (
                f"{case_id}: source insertion requires missing or explicit-null values"
            )
            if case.get("xcstringsMissingSourceLocale"):
                assert missing, (
                    f"{case_id}: zero-width insertion requires genuinely absent source locales"
                )
            for identifier in inserted:
                original_entry = original_root["strings"][identifier]
                localized_entry = localized_root["strings"][identifier]
                assert localized_entry["localizations"][language]["stringUnit"][
                    "state"
                ] == ("translated"), (
                    f"{case_id}/{identifier}: materialized source state would be omitted by Xcode"
                )
                for locale, original_locale in original_entry["localizations"].items():
                    if locale != language:
                        assert (
                            localized_entry["localizations"][locale] == original_locale
                        ), f"{case_id}/{identifier}: protected target locale changed"
            for identifier, entry in original_root["strings"].items():
                if entry.get("shouldTranslate") is False:
                    assert localized_root["strings"][identifier] == entry, (
                        f"{case_id}/{identifier}: protected Xcode descriptor changed"
                    )
        known: set[str] = set()
        original_cursor = 0
        localized_cursor = 0
        for index, slot in enumerate(skeleton["slots"]):
            assert set(slot) <= {"id", "selector", "variant", "start", "end"} and {
                "id",
                "start",
                "end",
            } <= set(slot), f"{case_id}: unstable source-slot fields"
            assert isinstance(slot["id"], str) and slot["id"]
            if case["format"] == "gettext_po" and "gettextDomainCompiled" in case:
                assert any(
                    slot["id"].endswith("@domain=" + quote(domain, safe=""))
                    for domain in compiled[0]
                ), (
                    f"{case_id}: GNU source slot lost its reversible native domain identity"
                )
            if "selector" in slot:
                assert case["format"] in {
                    "apple_stringsdict",
                    "apple_xcstrings",
                }, (
                    f"{case_id}: plural selectors belong only to Apple source dictionaries"
                )
                assert isinstance(slot["selector"], str) and slot["selector"]
                assert "variant" in slot, (
                    f"{case_id}: scoped Apple selectors require a category or variation branch"
                )
            selector = slot.get("selector", "")
            if selector in {"@device", "@width"} or selector.startswith("@device="):
                if case["format"] == "apple_stringsdict":
                    assert case.get("appleAllVariationSlots") is True, (
                        f"{case_id}: Foundation variation slots require explicit opt-in"
                    )
                    if selector.startswith("@device="):
                        assert (
                            case.get("appleDevicePluralSlots") is True
                            or case.get("appleDeviceWidthSlots") is True
                        ), (
                            f"{case_id}: Foundation nested device slots require explicit opt-in"
                        )
                else:
                    assert (
                        case["format"] == "apple_xcstrings"
                        and (
                            case.get("xcstringsAllDeviceSlots") is True
                            or case.get("xcstringsTargetDeviceSlots") is True
                        )
                        and (
                            selector == "@device"
                            or selector.startswith("@device=")
                            and (
                                case.get("xcstringsDevicePluralSlots") is True
                                or case.get("xcstringsTargetDevicePluralSlots") is True
                            )
                        )
                    ), (
                        f"{case_id}: Xcode device variation slots require explicit opt-in"
                    )
                assert isinstance(slot.get("variant"), str) and slot["variant"], (
                    f"{case_id}: Foundation variation slot has no branch identity"
                )
                if selector == "@width":
                    assert slot["variant"].isdigit(), (
                        f"{case_id}: Foundation width identity must retain numeric source spelling"
                    )
                if selector.startswith("@device="):
                    assert selector.removeprefix("@device="), (
                        f"{case_id}: nested Apple selector lost its native device identity"
                    )
                    if slot["variant"].isdigit():
                        if case["format"] == "apple_stringsdict":
                            assert case.get("appleDeviceWidthSlots") is True, (
                                f"{case_id}: Foundation device widths require explicit opt-in"
                            )
                        assert slot["variant"].isdigit(), (
                            f"{case_id}: Foundation device width lost its numeric threshold"
                        )
                    else:
                        if case["format"] == "apple_stringsdict":
                            assert case.get("appleDevicePluralSlots") is True, (
                                f"{case_id}: Foundation device plurals require explicit opt-in"
                            )
                        assert slot["variant"] in {
                            "zero",
                            "one",
                            "two",
                            "few",
                            "many",
                            "other",
                        }, (
                            f"{case_id}: Apple device plural selector lost its native category"
                        )
            else:
                assert slot.get("variant") in {
                    None,
                    "zero",
                    "one",
                    "two",
                    "few",
                    "many",
                    "other",
                }
            assert isinstance(slot["start"], int) and isinstance(slot["end"], int)
            assert (
                original_cursor <= slot["start"] <= slot["end"] <= len(original_bytes)
            ), f"{case_id}: overlapping or invalid source-byte ownership"
            key = slot["id"]
            if "selector" in slot:
                key += "#" + slot["selector"]
            if "variant" in slot:
                key += "#" + slot["variant"]
            assert key not in known, f"{case_id}: duplicate source-slot identity"
            known.add(key)
            unchanged = original_bytes[original_cursor : slot["start"]]
            assert localized_bytes.startswith(unchanged, localized_cursor), (
                f"{case_id}/{key}: nontranslatable bytes changed before a source slot"
            )
            localized_cursor += len(unchanged)
            next_start = (
                skeleton["slots"][index + 1]["start"]
                if index + 1 < len(skeleton["slots"])
                else len(original_bytes)
            )
            boundary = original_bytes[slot["end"] : next_start]
            if boundary:
                following = localized_bytes.find(boundary, localized_cursor)
                assert following >= localized_cursor, (
                    f"{case_id}/{key}: localized output changed a protected source boundary"
                )
            else:
                assert (
                    case["format"] == "java_properties"
                    and index + 1 == len(skeleton["slots"])
                    and slot["end"] == len(original_bytes)
                ), f"{case_id}/{key}: source slots require a nonempty lexical boundary"
                following = len(localized_bytes)
            original_body = original_bytes[slot["start"] : slot["end"]].decode(codec)
            localized_body = localized_bytes[localized_cursor:following].decode(codec)
            if target_locale := skeleton.get("appleTargetLocale"):
                if not original_body:
                    assert slot["start"] == slot["end"]
                    assert localized_body.startswith(",")
                    inserted_unit = json.loads("{" + localized_body[1:] + "}")[
                        target_locale
                    ]
                    if "device" in inserted_unit.get("variations", {}):
                        for branch in inserted_unit["variations"]["device"].values():
                            if "stringUnit" in branch:
                                assert branch["stringUnit"]["state"] == "translated"
                            else:
                                assert all(
                                    category["stringUnit"]["state"] == "translated"
                                    for category in branch["variations"][
                                        "plural"
                                    ].values()
                                )
                    elif "variations" in inserted_unit:
                        assert all(
                            branch["stringUnit"]["state"] == "translated"
                            for branch in inserted_unit["variations"]["plural"].values()
                        )
                    else:
                        assert inserted_unit["stringUnit"]["state"] == "translated"
                elif original_body == "null":
                    inserted_unit = json.loads(localized_body)
                    if "device" in inserted_unit.get("variations", {}):
                        for branch in inserted_unit["variations"]["device"].values():
                            if "stringUnit" in branch:
                                assert branch["stringUnit"]["state"] == "translated"
                            else:
                                assert all(
                                    category["stringUnit"]["state"] == "translated"
                                    for category in branch["variations"][
                                        "plural"
                                    ].values()
                                )
                    elif "variations" in inserted_unit:
                        assert all(
                            branch["stringUnit"]["state"] == "translated"
                            for branch in inserted_unit["variations"]["plural"].values()
                        )
                    else:
                        assert inserted_unit["stringUnit"]["state"] == "translated"
            if case.get("xcstringsInsertSourceLocale") and slot["id"] in inserted:
                if slot["id"] in missing:
                    assert not original_body and slot["start"] == slot["end"], (
                        f"{case_id}/{key}: absent source slots require zero-width ownership"
                    )
                    assert localized_body.startswith(","), (
                        f"{case_id}/{key}: absent source insertion lost its JSON field boundary"
                    )
                    inserted_unit = json.loads("{" + localized_body[1:] + "}")[language]
                else:
                    assert original_body == "null", (
                        f"{case_id}/{key}: nullable source slot must own its original null"
                    )
                    inserted_unit = json.loads(localized_body)
                assert inserted_unit["stringUnit"]["state"] == "translated", (
                    f"{case_id}/{key}: source insertion did not create a translated native unit"
                )
            if case["format"] == "android":
                if original_body.startswith("/") and original_body.endswith(">"):
                    prefix = original_bytes[: slot["start"]].decode(codec)
                    tag = re.search(
                        r"<([A-Za-z_][A-Za-z0-9_.:-]*)(?:\s|$)[^<]*$", prefix
                    )
                    assert (
                        tag
                        and localized_body.startswith(">")
                        and localized_body.endswith("</" + tag.group(1) + ">")
                    ), (
                        f"{case_id}/{key}: self-closing Android expansion changed its source element"
                    )
                else:

                    def strip_decorations(value: str) -> str:
                        return re.sub(
                            r"<!--.*?-->|<!\[CDATA\[.*?\]\]>",
                            "",
                            value,
                            flags=re.DOTALL,
                        )

                    source_tags = re.findall(
                        r"</?[A-Za-z_][^>]*>", strip_decorations(original_body)
                    )
                    localized_tags = re.findall(
                        r"</?[A-Za-z_][^>]*>", strip_decorations(localized_body)
                    )
                    if case.get("androidReorderableInline"):
                        assert sorted(source_tags) == sorted(localized_tags), (
                            f"{case_id}/{key}: reordered Android inline tags lost original lexemes"
                        )
                    else:
                        assert source_tags == localized_tags, (
                            f"{case_id}/{key}: original inline-tag lexical spellings changed"
                        )
                    source_comments = re.findall(
                        r"<!--.*?-->", original_body, flags=re.DOTALL
                    )
                    localized_comments = re.findall(
                        r"<!--.*?-->", localized_body, flags=re.DOTALL
                    )
                    if case.get("androidDecoratedInline"):
                        assert sorted(source_comments) == sorted(localized_comments), (
                            f"{case_id}/{key}: reordered Android comments lost original lexemes"
                        )
                    else:
                        assert source_comments == localized_comments, (
                            f"{case_id}/{key}: original Android inline comments changed"
                        )
                    assert original_body.count("<![CDATA[") <= localized_body.count(
                        "<![CDATA["
                    ), f"{case_id}/{key}: original Android CDATA wrappers were lost"
            original_cursor = slot["end"]
            localized_cursor = following
        if case.get("xcstringsSubstitutionSlots"):
            assert any("selector" in slot for slot in skeleton["slots"]), (
                f"{case_id}: Xcode substitution contracts require selector-qualified slots"
            )
        if case.get("xcstringsTargetSubstitutionSlots"):
            assert case.get("xcstringsTargetLocale"), (
                f"{case_id}: target substitution slots require an explicit target language"
            )
            assert any(
                slot.get("selector") not in {None, "@device"}
                and not slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), f"{case_id}: target substitutions require independent selector ownership"
        if case.get("xcstringsTargetSubstitutionInsertion"):
            assert case.get("xcstringsTargetSubstitutionSlots") or case.get(
                "xcstringsFirstLocaleSubstitutions"
            ), (
                f"{case_id}: target substitution insertion requires existing selector or first-locale CLDR evidence"
            )
            assert any(
                slot.get("selector") is None and slot["start"] == slot["end"]
                for slot in skeleton["slots"]
            ), (
                f"{case_id}: missing target substitution insertion requires zero-width ownership"
            )
        if case.get("xcstringsFirstLocaleSubstitutions"):
            assert case.get("xcstringsFirstLocaleCategories") and case.get(
                "xcstringsTargetSubstitutionInsertion"
            ), (
                f"{case_id}: first-locale substitutions require pinned categories and atomic ownership"
            )
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            target_locale = case["xcstringsTargetLocale"]
            source_language = original_root["sourceLanguage"]
            first = 0
            devices = 0
            for identifier, descriptor in original_root["strings"].items():
                if descriptor.get("shouldTranslate") is False:
                    continue
                assert not isinstance(
                    descriptor["localizations"].get(target_locale), dict
                ), (
                    f"{case_id}/{identifier}: first-locale substitutions reused a target exemplar"
                )
                if case.get("xcstringsSourceAliasFirstLocaleSubstitutions"):
                    owners = set(descriptor["localizations"]) - {target_locale, "de"}
                    assert len(owners) == 1, (
                        f"{case_id}/{identifier}: ambiguous compiler-owned first-locale source"
                    )
                    (source_locale,) = owners
                    assert source_locale != source_language, (
                        f"{case_id}/{identifier}: development alias was not independently owned"
                    )
                else:
                    source_locale = source_language
                source_localization = descriptor["localizations"][source_locale]
                target = localized_root["strings"][identifier]["localizations"][
                    target_locale
                ]
                assert localized_root["strings"][identifier]["localizations"][source_locale] == (
                    source_localization
                ), f"{case_id}/{identifier}: first-locale insertion rewrote its source"
                assert localized_root["strings"][identifier]["localizations"]["de"] == (
                    descriptor["localizations"]["de"]
                ), f"{case_id}/{identifier}: first-locale insertion changed unrelated German"
                for selector, definition in target["substitutions"].items():
                    original_definition = source_localization["substitutions"][selector]
                    assert all(
                        definition[field] == original_definition[field]
                        for field in ("argNum", "formatSpecifier")
                    ), (
                        f"{case_id}/{identifier}/{selector}: first locale changed native argument ownership"
                    )
                    assert all(
                        "%4$n" not in category["stringUnit"]["value"]
                        for category in definition["variations"]["plural"].values()
                    ), (
                        f"{case_id}/{identifier}/{selector}: target locale invented a source-absent hidden argument"
                    )
                first += 1
                devices += "device" in source_localization.get("variations", {})
            assert first and devices, (
                f"{case_id}: first-locale substitutions need scalar and device trees"
            )
        if case.get("xcstringsFirstLocaleDevices"):
            assert case.get("xcstringsFirstLocaleCategories") and case.get(
                "xcstringsTargetDeviceInsertion"
            ), (
                f"{case_id}: first-locale devices require pinned categories and atomic ownership"
            )
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            target_locale = case["xcstringsTargetLocale"]
            scalar = 0
            plural = 0
            for identifier, descriptor in original_root["strings"].items():
                if descriptor.get("shouldTranslate") is False:
                    continue
                assert not isinstance(
                    descriptor["localizations"].get(target_locale), dict
                ), (
                    f"{case_id}/{identifier}: first-locale devices reused a target exemplar"
                )
                devices = localized_root["strings"][identifier]["localizations"][
                    target_locale
                ]["variations"]["device"]
                if case.get("xcstringsFirstLocaleFutureDevices"):
                    original_devices = descriptor["localizations"][
                        original_root["sourceLanguage"]
                    ]["variations"]["device"]
                    assert set(devices) == set(original_devices), (
                        f"{case_id}/{identifier}: first-locale opaque device identity changed"
                    )
                    assert {"futurecar", "\ue000raft", "🧭raft"}.issubset(devices), (
                        f"{case_id}/{identifier}: future/private-use/supplementary device disappeared"
                    )
                    if "other" in original_devices:
                        assert "stringUnit" in devices["other"], (
                            f"{case_id}/{identifier}: native scalar fallback became a varied tree"
                        )
                        assert devices["other"]["stringUnit"]["value"] != (
                            devices["iphone"]["stringUnit"]["value"]
                        ), (
                            f"{case_id}/{identifier}: explicit native scalar fallback was collapsed"
                        )
                    else:
                        assert "other" not in devices, (
                            f"{case_id}/{identifier}: synthetic ICU fallback became a native plural device"
                        )
                for branch in devices.values():
                    if "stringUnit" in branch:
                        assert "%2$n" in branch["stringUnit"]["value"], (
                            f"{case_id}/{identifier}: first-locale scalar lost its "
                            "source-owned hidden argument"
                        )
                        scalar += 1
                    else:
                        assert all(
                            "%3$n" in category["stringUnit"]["value"]
                            for category in branch["variations"]["plural"].values()
                        ), (
                            f"{case_id}/{identifier}: first-locale plural lost its "
                            "source-owned hidden argument"
                        )
                        plural += 1
            assert scalar and plural, (
                f"{case_id}: first-locale devices require scalar and plural trees"
            )
        if case.get("xcstringsFirstLocaleCategories"):
            locale = case["xcstringsTargetLocale"]
            assert apple_cardinal_categories(locale), (
                f"{case_id}: first-locale insertion lacks expected plural categories"
            )
        if separator := case.get("xcstringsRegionSeparator"):
            assert separator in {
                "underscore",
                "hyphen",
            }, f"{case_id}: unsupported Xcode regional separator contract"
            target = "pt_BR" if separator == "underscore" else "pt-BR"
            assert (
                skeleton["appleTargetLocale"]
                == case.get("xcstringsTargetLocale")
                == case.get("xcstringsRuntimeLocale")
                == target
            ), f"{case_id}: physical Portuguese regional bundle identity changed"
            original_bundles = json.loads(
                (ROOT / case["xcstringsCompiled"]).read_text(encoding="utf-8")
            )
            assert "pt_BR.lproj/catalog.strings" in original_bundles and (
                "pt-BR.lproj/catalog.strings" in original_bundles
            ), f"{case_id}: compiler-distinct Portuguese regional bundles collapsed"
            assert len(skeleton["slots"]) == 1 and all(
                slot["start"] < slot["end"] for slot in skeleton["slots"]
            ), (
                f"{case_id}: regional separator must own exactly one existing target value"
            )
            assert (
                case["xcstringsOriginalRuntimeSamples"]
                and case["xcstringsLocalizedRuntimeSamples"]
            ), (
                f"{case_id}: both physical bundles require original/localized Foundation proof"
            )
        if deprecated := case.get("xcstringsDeprecatedLocale"):
            assert deprecated in {
                "language",
                "region",
            }, f"{case_id}: unsupported deprecated Hebrew locale contract"
            target = "iw" if deprecated == "language" else "iw-IL"
            canonical = "he" if deprecated == "language" else "he-IL"
            assert skeleton["appleTargetLocale"] == target, (
                f"{case_id}: deprecated Hebrew catalog-owned spelling changed"
            )
            assert case.get("xcstringsTargetLocale") == canonical, (
                f"{case_id}: modern Hebrew request no longer resolves source-owned alias"
            )
            assert (
                case.get("xcstringsRuntimeLocale")
                == case.get("xcstringsFormattingLocale")
                == canonical
            ), f"{case_id}: native canonical Hebrew bundle or locale changed"
            assert any(slot["start"] == slot["end"] for slot in skeleton["slots"])
            assert any(slot["start"] < slot["end"] for slot in skeleton["slots"])
            expected = {0: "other", 1: "one", 2: "two", 3: "other"}
            assert case["xcstringsLocalizedRuntimeSamples"] and all(
                sample["expected"].endswith(f" {expected[sample['arguments'][0]]}")
                for sample in case["xcstringsLocalizedRuntimeSamples"]
            ), f"{case_id}: Hebrew two-category Foundation selection was lost"
        if territory := case.get("xcstringsTerritoryLocale"):
            assert territory in {
                "british",
                "world",
            }, f"{case_id}: unsupported English territory contract"
            target = "en-UK" if territory == "british" else "en-001"
            native = "en-GB" if territory == "british" else "en-001"
            assert skeleton["appleTargetLocale"] == target, (
                f"{case_id}: Xcode-owned obsolete/numeric territory spelling changed"
            )
            assert (
                case.get("xcstringsTargetLocale")
                == case.get("xcstringsRuntimeLocale")
                == case.get("xcstringsFormattingLocale")
                == native
            ), f"{case_id}: Xcode-owned native territory bundle or runtime changed"
            assert any(slot["start"] == slot["end"] for slot in skeleton["slots"])
            assert any(slot["start"] < slot["end"] for slot in skeleton["slots"])
            expected = {0: "other", 1: "one", 2: "other", 1_000_000: "other"}
            samples = case["xcstringsLocalizedRuntimeSamples"]
            assert samples and all(
                sample["expected"].endswith(f" {expected[sample['arguments'][0]]}")
                for sample in samples
            ), f"{case_id}: English territory category selection was lost"
            assert any("1,000,000" in sample["expected"] for sample in samples), (
                f"{case_id}: English territory native numeric formatting was lost"
            )
        if grandfathered := case.get("xcstringsGrandfatheredLocale"):
            assert grandfathered in {
                "bokmal",
                "nynorsk",
            }, f"{case_id}: unsupported grandfathered Norwegian locale contract"
            target = "no-bok" if grandfathered == "bokmal" else "no-nyn"
            native = "nb" if grandfathered == "bokmal" else "nn"
            assert skeleton["appleTargetLocale"] == target, (
                f"{case_id}: grandfathered Norwegian catalog spelling changed"
            )
            assert (
                case.get("xcstringsTargetLocale")
                == case.get("xcstringsRuntimeLocale")
                == case.get("xcstringsFormattingLocale")
                == native
            ), f"{case_id}: modern Norwegian native bundle or locale changed"
            assert any(slot["start"] == slot["end"] for slot in skeleton["slots"])
            assert any(slot["start"] < slot["end"] for slot in skeleton["slots"])
            expected = {0: "other", 1: "one", 2: "other", 1_000_000: "other"}
            samples = case["xcstringsLocalizedRuntimeSamples"]
            assert samples and all(
                sample["expected"].endswith(f" {expected[sample['arguments'][0]]}")
                for sample in samples
            ), f"{case_id}: Norwegian plural selection was lost"
            assert any(
                "1\u00a0000\u00a0000" in sample["expected"] for sample in samples
            ), f"{case_id}: Norwegian native numeric grouping was lost"
        if script := case.get("xcstringsScriptLocale"):
            assert script in {
                "latin",
                "cyrillic",
            }, f"{case_id}: unsupported Serbian script contract"
            target = "sr_Latn" if script == "latin" else "sr-Cyrl"
            runtime = "sr-Latn" if script == "latin" else "sr"
            assert case.get("xcstringsFirstLocaleCategories") and case.get(
                "xcstringsTargetPluralInsertion"
            ), f"{case_id}: Serbian scripts require first-locale category ownership"
            assert skeleton["appleTargetLocale"] == target, (
                f"{case_id}: catalog-owned Serbian script spelling changed"
            )
            assert case.get("xcstringsRuntimeLocale") == runtime, (
                f"{case_id}: native minimized Serbian bundle identity changed"
            )
            assert case.get("xcstringsFormattingLocale") == target.replace("_", "-"), (
                f"{case_id}: script-qualified Foundation formatting locale changed"
            )
            assert any(slot["start"] == slot["end"] for slot in skeleton["slots"])
            assert any(slot["start"] < slot["end"] for slot in skeleton["slots"])
            samples = case["xcstringsLocalizedRuntimeSamples"]
            expected = {
                0: "other",
                1: "one",
                2: "few",
                5: "other",
                21: "one",
                22: "few",
            }
            assert samples and all(
                sample["expected"].endswith(f" {expected[sample['arguments'][0]]}")
                for sample in samples
            ), f"{case_id}: Serbian script-native plural selection was lost"
        if region := case.get("xcstringsRegionalLocale"):
            assert region in {
                "brazil",
                "portugal",
            }, f"{case_id}: unsupported regional Portuguese contract"
            target = "pt_BR" if region == "brazil" else "pt-PT"
            assert case.get("xcstringsFirstLocaleCategories") and case.get(
                "xcstringsTargetPluralInsertion"
            ), f"{case_id}: regional plurals require first-locale category ownership"
            assert (
                skeleton["appleTargetLocale"]
                == case.get("xcstringsRuntimeLocale")
                == case.get("xcstringsFormattingLocale")
                == target
            ), f"{case_id}: regional target spelling or native runtime identity changed"
            assert any(slot["start"] == slot["end"] for slot in skeleton["slots"])
            assert any(slot["start"] < slot["end"] for slot in skeleton["slots"])
            samples = case["xcstringsLocalizedRuntimeSamples"]
            zeros = [sample for sample in samples if sample["arguments"][0] == 0]
            millions = [
                sample for sample in samples if sample["arguments"][0] == 1_000_000
            ]
            category = "one" if region == "brazil" else "other"
            grouping = "1.000.000" if region == "brazil" else "1\u00a0000\u00a0000"
            assert zeros and all(
                sample["expected"].endswith(f" {category}") for sample in zeros
            ), f"{case_id}: distinct regional zero selection was lost"
            assert millions and all(
                grouping in sample["expected"] and sample["expected"].endswith(" many")
                for sample in millions
            ), f"{case_id}: regional native number formatting was lost"
        if variation := case.get("xcstringsSourceAliasTargetSubstitutions"):
            assert case.get("xcstringsTargetSubstitutionSlots") and case.get(
                "xcstringsTargetDeviceSlots"
            ), (
                f"{case_id}: aliased development substitutions require explicit target/device ownership"
            )
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            declared = original_root["sourceLanguage"]
            for identifier in (
                "harbor.target.substitution.scalar🧭",
                "harbor.target.substitution.device🧭",
            ):
                original = original_root["strings"][identifier]
                updated = localized_root["strings"][identifier]
                owners = set(original["localizations"]) - {"ru"}
                assert len(owners) == 1, (
                    f"{case_id}/{identifier}: ambiguous compiler-owned development source"
                )
                (owned,) = owners
                assert owned != declared and original["localizations"][owned] == updated[
                    "localizations"
                ][owned], (
                    f"{case_id}/{identifier}: alias-owned source tree was rewritten"
                )
                assert set(original["localizations"][owned]["substitutions"]) == {
                    "lanes",
                    "lights",
                }, f"{case_id}/{identifier}: aliased development selectors were lost"
                assert set(updated["localizations"]["ru"]["substitutions"]) == {
                    "lanes",
                    "lights",
                }, f"{case_id}/{identifier}: Russian target selectors were lost"
            protected = "Private target Russian substitution branches"
            assert original_root["strings"][protected] == localized_root["strings"][protected], (
                f"{case_id}: protected aliased development/target substitution changed"
            )
            if case.get("xcstringsSourceAliasAtomicSubstitutions"):
                assert case.get("xcstringsTargetSubstitutionInsertion"), (
                    f"{case_id}: atomic development-alias substitutions require insertion ownership"
                )
                for identifier in (
                    "harbor.target.substitution.missing.scalar🧭",
                    "harbor.target.substitution.null.scalar🧭",
                    "harbor.target.substitution.missing.device🧭",
                    "harbor.target.substitution.null.device🧭",
                ):
                    original = original_root["strings"][identifier]
                    updated = localized_root["strings"][identifier]
                    owners = set(original["localizations"]) - {"ru"}
                    assert len(owners) == 1, (
                        f"{case_id}/{identifier}: atomic development source ownership is ambiguous"
                    )
                    (owned,) = owners
                    assert owned != declared and updated["localizations"][owned] == original[
                        "localizations"
                    ][owned], (
                        f"{case_id}/{identifier}: atomic insertion rewrote the aliased source"
                    )
                    if ".missing." in identifier:
                        assert "ru" not in original["localizations"], (
                            f"{case_id}/{identifier}: missing target unexpectedly existed"
                        )
                    else:
                        assert original["localizations"]["ru"] is None, (
                            f"{case_id}/{identifier}: null target lost its original shape"
                        )
                    inserted = updated["localizations"]["ru"]
                    assert set(inserted["substitutions"]) == {"lanes", "lights"}, (
                        f"{case_id}/{identifier}: atomic target selectors were lost"
                    )
                    for selector, definition in inserted["substitutions"].items():
                        source_definition = original["localizations"][owned]["substitutions"][
                            selector
                        ]
                        assert all(
                            definition[field] == source_definition[field]
                            for field in ("argNum", "formatSpecifier")
                        ), (
                            f"{case_id}/{identifier}/{selector}: source-owned arguments changed"
                        )
                        categories = definition["variations"]["plural"]
                        assert set(categories) == {"one", "few", "many", "other"}, (
                            f"{case_id}/{identifier}/{selector}: Russian categories are incomplete"
                        )
                        assert all(
                            branch["stringUnit"]["state"] == "translated"
                            and "%4$n" in branch["stringUnit"]["value"]
                            for branch in categories.values()
                        ), (
                            f"{case_id}/{identifier}/{selector}: hidden arguments or state changed"
                        )
                    if ".device" in identifier:
                        assert set(inserted["variations"]["device"]) == {"iphone", "mac"}, (
                            f"{case_id}/{identifier}: source-owned device choices changed"
                        )
                for protected in (
                    "Private missing Russian substitution tree",
                    "Private null Russian substitution tree",
                ):
                    assert original_root["strings"][protected] == localized_root["strings"][
                        protected
                    ], f"{case_id}: protected atomic substitution descriptor changed"
                assert any(slot["start"] == slot["end"] for slot in skeleton["slots"]), (
                    f"{case_id}: missing alias-owned targets require zero-width slots"
                )
            assert case.get("xcstringsOriginalRuntimeSamples") and case.get(
                "xcstringsLocalizedRuntimeSamples"
            ), f"{case_id}: {variation} requires native Mac substitution execution"
        if variation := case.get("xcstringsSourceLocaleAlias"):
            assert case["format"] == "apple_xcstrings" and not skeleton.get(
                "appleTargetLocale"
            ), f"{case_id}: development aliases own source rather than target slots"
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            scalar = "harbor.development.source.scalar🧭"
            plural = "harbor.development.source.plural🧭"
            declared = original_root["sourceLanguage"]
            owners = {
                locale
                for identifier in (scalar, plural)
                for locale in original_root["strings"][identifier]["localizations"]
                if locale != "de"
            }
            assert len(owners) == 1, (
                f"{case_id}: all development values require one compiler-equivalent owner"
            )
            (owned,) = owners
            assert owned != declared, (
                f"{case_id}: source aliases must differ from declared development spelling"
            )
            assert len(skeleton["slots"]) == 3 and {
                (slot["id"], slot.get("variant")) for slot in skeleton["slots"]
            } == {(scalar, None), (plural, "one"), (plural, "other")}, (
                f"{case_id}: development source scalar/plural ownership was lost"
            )
            for identifier in (scalar, plural):
                before = original_root["strings"][identifier]["localizations"]
                after = localized_root["strings"][identifier]["localizations"]
                assert before["de"] == after["de"], (
                    f"{case_id}/{identifier}: unrelated German localization changed"
                )
                assert owned in after and declared not in after, (
                    f"{case_id}/{identifier}: source localization spelling was rewritten"
                )
            protected = "Private development-source harbor"
            assert original_root["strings"][protected] == localized_root["strings"][protected], (
                f"{case_id}: nontranslatable development source was changed"
            )
            assert case.get("xcstringsOriginalRuntimeSamples") and case.get(
                "xcstringsLocalizedRuntimeSamples"
            ), f"{case_id}: {variation} requires real Foundation source/runtime evidence"
        if case.get("xcstringsOpaqueReviewStates"):
            assert case["format"] == "apple_xcstrings" and skeleton.get(
                "appleTargetLocale"
            ), f"{case_id}: opaque review states require existing target ownership"
            original_root = json.loads(source)
            localized_root = json.loads(localized)
            source_locale = original_root["sourceLanguage"]
            target_locale = skeleton["appleTargetLocale"]
            states = set()
            for identifier, entry in original_root["strings"].items():
                updated = localized_root["strings"][identifier]
                if entry.get("shouldTranslate") is False:
                    assert updated == entry, (
                        f"{case_id}: protected review state changed"
                    )
                    continue
                assert entry.get("extractionState") == updated.get("extractionState"), (
                    f"{case_id}/{identifier}: extraction ownership state changed"
                )
                for locale in (source_locale, target_locale, "de"):
                    original_unit = entry["localizations"][locale]["stringUnit"]
                    localized_unit = updated["localizations"][locale]["stringUnit"]
                    assert original_unit["state"] == localized_unit["state"], (
                        f"{case_id}/{identifier}/{locale}: original review state changed"
                    )
                    if locale != target_locale:
                        assert original_unit == localized_unit, (
                            f"{case_id}/{identifier}/{locale}: unowned localization changed"
                        )
                    states.add(original_unit["state"])
            assert {
                "new",
                "needs_review",
                "translated",
                "machine_translated",
                "stale",
                "future_review",
                "untranslated",
                "invalid_future_state",
            } <= states, f"{case_id}: incomplete known/future review state matrix"
            compiled = json.loads((ROOT / case["xcstringsCompiled"]).read_text())
            english = compiled["en.lproj/catalog.strings"]
            target = compiled["fr_CA.lproj/catalog.strings"]
            manual = "harbor.review.0.new🧭"
            automatic = "harbor.review.source.new.automatic🧭"
            assert manual in english and automatic not in english, (
                f"{case_id}: manual versus automatic new source compilation changed"
            )
            assert manual in target and automatic in target, (
                f"{case_id}: target review states unexpectedly suppressed native output"
            )
        if case.get("appleAllVariationSlots"):
            assert case["format"] == "apple_stringsdict", (
                f"{case_id}: expanded variations belong only to Foundation stringsdict"
            )
            assert all(
                slot.get("selector") in {"@device", "@width"}
                or (
                    case.get("appleDevicePluralSlots") is True
                    or case.get("appleDeviceWidthSlots") is True
                )
                and slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), (
                f"{case_id}: expanded Foundation variations require explicit branch ownership"
            )
        if case.get("appleDevicePluralSlots"):
            assert case["format"] == "apple_stringsdict" and any(
                slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), f"{case_id}: Foundation device plurals require combined-axis ownership"
        if case.get("appleDeviceWidthSlots"):
            assert case["format"] == "apple_stringsdict" and any(
                slot.get("selector", "").startswith("@device=")
                and slot.get("variant", "").isdigit()
                for slot in skeleton["slots"]
            ), f"{case_id}: Foundation device widths require combined-axis ownership"
        if case.get("xcstringsAllDeviceSlots"):
            assert case["format"] == "apple_xcstrings", (
                f"{case_id}: expanded device slots belong only to Xcode String Catalogs"
            )
            assert any(
                slot.get("selector") == "@device"
                or slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), (
                f"{case_id}: expanded Xcode device slots require explicit branch ownership"
            )
            if case.get("xcstringsFutureDevices"):
                devices = {
                    slot.get("variant")
                    for slot in skeleton["slots"]
                    if slot.get("selector") == "@device"
                }
                assert {"futurecar", "\ue000raft", "🧭raft", "mac", "other"} <= devices, (
                    f"{case_id}: future/private/supplementary device ownership changed"
                )
                assert any(
                    sample.get("fallback")
                    for sample in case.get("xcstringsOriginalRuntimeSamples", [])
                ), f"{case_id}: unavailable future-only Foundation device lacks native proof"
        if case.get("xcstringsDevicePluralSlots"):
            assert any(
                slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), (
                f"{case_id}: nested Xcode device plurals require explicit combined-axis ownership"
            )
        if case.get("xcstringsTargetDeviceSlots"):
            assert case.get("xcstringsTargetLocale"), (
                f"{case_id}: target Xcode device slots require an explicit target language"
            )
            assert any(
                slot.get("selector") == "@device"
                or slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), f"{case_id}: target Xcode devices require explicit branch ownership"
        if case.get("xcstringsTargetDevicePluralSlots"):
            assert any(
                slot.get("selector", "").startswith("@device=")
                for slot in skeleton["slots"]
            ), f"{case_id}: target Xcode device plurals require combined-axis ownership"
        assert localized_bytes[localized_cursor:] == original_bytes[original_cursor:], (
            f"{case_id}: untranslated trailing source bytes changed"
        )
        assert set(translations) == known, (
            f"{case_id}: translation keys do not match source slots"
        )
        for rejected in case.get("androidSkeletonReject", []):
            assert set(rejected["translations"]) <= known, (
                f"{case_id}: rejected inline mutation references an unknown source slot"
            )
        for rejected in case.get("xcstringsSkeletonReject", []):
            assert set(rejected["translations"]) <= known, (
                f"{case_id}: rejected substitution mutation references an unknown source slot"
            )

    binary_skeletons = manifest.get("appleBinarySourceSkeletons", [])
    assert binary_skeletons, (
        "Binary Foundation source contracts must not be silently skipped"
    )
    for case in binary_skeletons:
        case_id = case["id"]
        assert case_id not in seen and case_id not in skeleton_ids, (
            f"{case_id}: duplicate binary Foundation source contract"
        )
        skeleton_ids.add(case_id)
        assert case["format"] in {
            "apple_strings",
            "apple_stringsdict",
        }, f"{case_id}: binary source ownership requires Apple Foundation"
        assert case.get("encoding") == "BINARY_PLIST", (
            f"{case_id}: binary source ownership requires explicit binary encoding"
        )
        for field in (
            "input",
            "expected",
            "translations",
            "localized",
            "appleCompiled",
            "appleLocalizedCompiled",
        ):
            assert (
                isinstance(case.get(field), str) and (ROOT / case[field]).is_file()
            ), f"{case_id}: missing binary source fixture {field}"
        validate_binary_apple_source(case)
        for field in ("appleOriginalRuntimeSamples", "appleLocalizedRuntimeSamples"):
            samples = case.get(field)
            assert isinstance(samples, list) and samples, (
                f"{case_id}: missing actual Foundation binary runtime samples"
            )
            for sample in samples:
                assert set(sample) == {
                    "message",
                    "arguments",
                    "expected",
                }, f"{case_id}: unstable binary Foundation runtime contract"
                assert isinstance(sample["message"], str) and sample["message"]
                assert isinstance(sample["arguments"], list)
                assert isinstance(sample["expected"], str)

    skeleton_errors = manifest.get("sourceSkeletonErrors", [])
    for case in skeleton_errors:
        case_id = case["id"]
        assert case_id not in seen and case_id not in skeleton_ids, (
            f"{case_id}: duplicate fail-closed source-skeleton contract"
        )
        skeleton_ids.add(case_id)
        assert case["format"] in {
            "android",
            "apple_xcstrings",
            "apple_strings",
            "apple_stringsdict",
        }, f"{case_id}: unsupported fail-closed source-skeleton format"
        assert case["error"] in {
            "UNSUPPORTED_SKELETON_SOURCE",
            "INVALID_XML",
            "DUPLICATE_LOCALE",
        }, f"{case_id}: unstable source-skeleton error"
        assert case.get("encoding") in ENCODINGS, (
            f"{case_id}: unsupported fail-closed source encoding"
        )
        assert (ROOT / case["input"]).is_file(), (
            f"{case_id}: missing fail-closed source-skeleton fixture"
        )

    missing = sorted(name for name, count in counts.items() if count == 0)
    assert not missing, f"Missing format coverage: {', '.join(missing)}"
    assert COMPATIBILITY_LEDGER.is_file(), "Missing portable compatibility ledger"
    assert COMPATIBILITY_LEDGER.read_text(encoding="utf-8") == compatibility_document(
        manifest
    ), (
        "Portable compatibility ledger is stale; run "
        "python3 file-formats/conformance/compatibility_ledger.py --write"
    )
    print(
        f"Validated {len(manifest['cases'])} portable fixtures "
        f"({valid_count} catalogs, {error_count} stable errors) across {len(FORMATS)} formats "
        f"plus {len(overlays)} Android resource-overlay contracts, "
        f"{len(overlay_skeletons)} multi-file Android source templates, "
        f"{len(skeletons)} byte-preserving source skeletons, "
        f"{len(binary_skeletons)} lossless binary Foundation source skeletons, "
        f"{len(skeleton_errors)} fail-closed source contracts, "
        f"{len(shadow_comparisons)} shared migration-shadow reports, "
        f"and {len(workflow_cases)} configured Mojito workflow contracts."
    )


def validate_binary_apple_source(case: dict[str, object]) -> None:
    """Prove every unowned object byte survives while structural offsets remain valid."""
    case_id = case["id"]
    original = (ROOT / case["input"]).read_bytes()
    localized = (ROOT / case["localized"]).read_bytes()
    original_layout = binary_apple_layout(original, case_id)
    localized_layout = binary_apple_layout(localized, case_id)
    sidecar = json.loads((ROOT / case["expected"]).read_text(encoding="utf-8"))
    assert set(sidecar) == {
        "schemaVersion",
        "sourceFormat",
        "encoding",
        "source",
        "slots",
    }, f"{case_id}: unstable binary source-sidecar fields"
    assert sidecar["schemaVersion"] == 1 and sidecar["sourceFormat"] == case["format"]
    assert (
        sidecar["encoding"] == "BINARY_PLIST" and sidecar["source"] == original.hex()
    ), f"{case_id}: binary sidecar lost exact original source bytes"
    assert original_layout["top"] == localized_layout["top"]
    translations = json.loads((ROOT / case["translations"]).read_text(encoding="utf-8"))
    if case.get("appleBinaryOffsetPromotion"):
        assert original_layout["width"] == 1 and localized_layout["width"] == 2, (
            f"{case_id}: the Foundation offset integer width did not promote"
        )
    assert original[-32:-26] == localized[-32:-26], (
        f"{case_id}: protected Foundation trailer flags changed"
    )
    assert original[-16:-8] == localized[-16:-8], (
        f"{case_id}: binary root ownership changed"
    )
    expected = json.loads((ROOT / case["appleCompiled"]).read_text(encoding="utf-8"))
    translated = json.loads(
        (ROOT / case["appleLocalizedCompiled"]).read_text(encoding="utf-8")
    )
    assert plistlib.loads(original) == expected, (
        f"{case_id}: original binary property-list snapshot differs"
    )
    assert plistlib.loads(localized) == translated, (
        f"{case_id}: localized binary property-list snapshot differs"
    )
    assert isinstance(sidecar["slots"], list) and sidecar["slots"]
    known: set[str] = set()
    shared: dict[int, int] = {}
    unique: dict[int, dict[str, object]] = {}
    clone_indices: dict[int, int] = {}
    extended_utf16 = False
    for slot in sidecar["slots"]:
        assert (
            {"id", "start", "end"}
            <= set(slot)
            <= {
                "id",
                "selector",
                "variant",
                "start",
                "end",
                "appleObjectIndex",
            }
        ), f"{case_id}: invalid binary source-object slot"
        assert 0 <= slot["start"] < slot["end"] <= original_layout["end"], (
            f"{case_id}: invalid binary source ownership"
        )
        identity = "#".join(
            slot[name] for name in ("id", "selector", "variant") if name in slot
        )
        assert identity not in known, (
            f"{case_id}: duplicate binary source-object identity"
        )
        known.add(identity)
        if "appleObjectIndex" in slot:
            object_index = slot["appleObjectIndex"]
            assert (
                isinstance(object_index, int)
                and 0 <= object_index < original_layout["count"]
                and slot["end"] - slot["start"] == original_layout["referenceWidth"]
                and int.from_bytes(original[slot["start"] : slot["end"]], "big")
                == object_index
            ), f"{case_id}/{identity}: invalid shared Foundation reference ownership"
            shared[slot["start"]] = object_index
            if identity in translations:
                clone_indices[slot["start"]] = original_layout["count"] + len(
                    clone_indices
                )
        else:
            assert slot["start"] in original_layout["offsets"], (
                f"{case_id}: binary slot does not own a real Foundation object"
            )
            assert (
                binary_apple_string_end(original, slot["start"], case_id) == slot["end"]
            ), f"{case_id}: binary slot does not own exactly one string object"
            if identity in translations:
                unique[slot["start"]] = slot
    assert set(translations) <= known, (
        f"{case_id}: binary translations reference unknown source objects"
    )
    assert original_layout["count"] + len(clone_indices) == localized_layout["count"], (
        f"{case_id}: unexpected binary clone object count"
    )
    if case.get("appleBinaryCopyOnWrite"):
        assert shared and clone_indices, (
            f"{case_id}: binary copy-on-write fixture owns no shared values"
        )
    if case.get("appleBinaryReferencePromotion"):
        assert (
            original_layout["referenceWidth"] == 1
            and localized_layout["referenceWidth"] == 2
            and original_layout["count"] == 254
            and localized_layout["count"] == 256
        ), f"{case_id}: clone object count did not promote every Foundation reference"
    elif not case.get("appleBinaryCopyOnWrite"):
        assert original_layout["referenceWidth"] == localized_layout["referenceWidth"]

    old_references = binary_apple_references(original, original_layout, case_id)
    replacements: list[tuple[int, int, bytes | None]] = []
    for start, object_index in old_references.items():
        clone = clone_indices.get(start)
        if clone is not None or (
            original_layout["referenceWidth"] != localized_layout["referenceWidth"]
        ):
            replacement = (clone if clone is not None else object_index).to_bytes(
                localized_layout["referenceWidth"], "big"
            )
            replacements.append(
                (start, start + original_layout["referenceWidth"], replacement)
            )
    for start, slot in unique.items():
        replacements.append((start, slot["end"], None))
    replacements.sort()
    original_cursor = localized_cursor = 0
    shifts: list[tuple[int, int, int]] = []
    for start, end, expected_reference in replacements:
        assert original_cursor <= start < end, (
            f"{case_id}: overlapping binary object/reference ownership"
        )
        protected = original[original_cursor:start]
        assert (
            localized[localized_cursor : localized_cursor + len(protected)] == protected
        ), f"{case_id}: protected binary objects or container prefixes changed"
        localized_cursor += len(protected)
        if expected_reference is not None:
            replacement_end = localized_cursor + len(expected_reference)
            assert localized[localized_cursor:replacement_end] == expected_reference, (
                f"{case_id}: localized container reference points at the wrong object"
            )
        else:
            extended_utf16 |= (
                localized[localized_cursor] & 0xF0 == 0x60
                and localized[localized_cursor] & 15 == 15
                and localized[localized_cursor + 1] & 15 >= 1
            )
            replacement_end = binary_apple_string_end(
                localized, localized_cursor, case_id
            )
        shifts.append((start, end, replacement_end - localized_cursor))
        original_cursor = end
        localized_cursor = replacement_end
    protected_tail = original[original_cursor : original_layout["end"]]
    assert (
        localized[localized_cursor : localized_cursor + len(protected_tail)]
        == protected_tail
    ), f"{case_id}: protected binary trailing objects changed"
    localized_cursor += len(protected_tail)
    for index, old_offset in enumerate(original_layout["offsets"]):
        delta = sum(
            replacement - (end - start)
            for start, end, replacement in shifts
            if end <= old_offset
        )
        assert localized_layout["offsets"][index] == old_offset + delta, (
            f"{case_id}: Foundation object {index} has an invalid rebuilt offset"
        )
    for index in range(original_layout["count"], localized_layout["count"]):
        assert localized_layout["offsets"][index] == localized_cursor, (
            f"{case_id}: cloned Foundation object has an invalid offset"
        )
        localized_cursor = binary_apple_string_end(localized, localized_cursor, case_id)
    assert localized_cursor == localized_layout["end"], (
        f"{case_id}: unexpected unowned cloned Foundation object bytes"
    )
    if case.get("appleBinaryOffsetPromotion"):
        assert extended_utf16, (
            f"{case_id}: offset promotion must also own an extended UTF-16 string length"
        )


def binary_apple_layout(source: bytes, label: str) -> dict[str, object]:
    assert source.startswith(b"bplist0") and len(source) >= 41, (
        f"{label}: missing genuine Foundation binary property-list header"
    )
    trailer = source[-32:]
    width = trailer[6]
    references = trailer[7]
    count = int.from_bytes(trailer[8:16], "big")
    top = int.from_bytes(trailer[16:24], "big")
    end = int.from_bytes(trailer[24:32], "big")
    assert width and references and end + width * count == len(source) - 32, (
        f"{label}: invalid Foundation binary offset table"
    )
    offsets = [
        int.from_bytes(source[end + index * width : end + (index + 1) * width], "big")
        for index in range(count)
    ]
    assert all(8 <= offset < end for offset in offsets), (
        f"{label}: Foundation binary object is outside its table"
    )
    return {
        "width": width,
        "referenceWidth": references,
        "count": count,
        "top": top,
        "end": end,
        "offsets": offsets,
    }


def binary_apple_references(
    source: bytes, layout: dict[str, object], label: str
) -> dict[int, int]:
    references = {}
    width = layout["referenceWidth"]
    for offset in layout["offsets"]:
        marker = source[offset]
        kind = marker & 0xF0
        if kind not in {0xA0, 0xD0}:
            continue
        count = marker & 15
        content = offset + 1
        if count == 15:
            integer = source[content]
            assert integer & 0xF0 == 0x10, (
                f"{label}: malformed Foundation collection length"
            )
            length_width = 1 << (integer & 15)
            count = int.from_bytes(
                source[content + 1 : content + 1 + length_width], "big"
            )
            content += length_width + 1
        for position in range(count * (2 if kind == 0xD0 else 1)):
            start = content + position * width
            reference = int.from_bytes(source[start : start + width], "big")
            assert reference < layout["count"], (
                f"{label}: Foundation container reference exceeds the object table"
            )
            references[start] = reference
    return references


def binary_apple_string_end(source: bytes, start: int, label: str) -> int:
    marker = source[start]
    assert marker & 0xF0 in {
        0x50,
        0x60,
    }, f"{label}: binary source slot does not own a Foundation string"
    count = marker & 15
    content = start + 1
    if count == 15:
        integer = source[content]
        assert integer & 0xF0 == 0x10, f"{label}: malformed Foundation extended string"
        width = 1 << (integer & 15)
        count = int.from_bytes(source[content + 1 : content + 1 + width], "big")
        content += width + 1
    return content + count * (2 if marker & 0xF0 == 0x60 else 1)


def valid_android_application_package(value: object) -> bool:
    return isinstance(value, str) and bool(
        re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", value)
    )


def valid_android_attribute_dependencies(value: object) -> bool:
    if not isinstance(value, list) or not value:
        return False
    formats = [
        "reference",
        "string",
        "integer",
        "boolean",
        "color",
        "float",
        "dimension",
        "fraction",
        "enum",
        "flags",
    ]
    names = set()
    for dependency in value:
        if (
            not isinstance(dependency, dict)
            or not set(dependency)
            <= {"name", "format", "min", "max", "symbols", "generic", "weak"}
            or not isinstance(dependency.get("name"), str)
            or not dependency["name"]
            or dependency["name"] in names
            or ("generic" in dependency and dependency["generic"] is not True)
            or ("weak" in dependency and dependency["weak"] is not True)
            or "weak" in dependency
            and "generic" in dependency
        ):
            return False
        names.add(dependency["name"])
        tokens = (
            dependency.get("format", "").split("|") if "format" in dependency else []
        )
        if (
            any(token not in formats for token in tokens)
            or len(tokens) != len(set(tokens))
            or tokens != [name for name in formats if name in tokens]
        ):
            return False
        for bound in ("min", "max"):
            if bound in dependency and (
                "integer" not in tokens
                or not isinstance(dependency[bound], int)
                or isinstance(dependency[bound], bool)
                or not -2147483648 <= dependency[bound] <= 2147483647
            ):
                return False
        symbols = dependency.get("symbols")
        if symbols is not None:
            if not isinstance(symbols, list) or not symbols:
                return False
            symbol_names = set()
            symbol_kind = None
            for symbol in symbols:
                if (
                    not isinstance(symbol, dict)
                    or set(symbol) != {"kind", "name", "value"}
                    or symbol.get("kind") not in {"enum", "flag"}
                    or not isinstance(symbol.get("name"), str)
                    or not symbol["name"]
                    or symbol["name"] in symbol_names
                    or not isinstance(symbol.get("value"), int)
                    or isinstance(symbol["value"], bool)
                    or not -2147483648 <= symbol["value"] <= 2147483647
                    or symbol_kind is not None
                    and symbol_kind != symbol["kind"]
                ):
                    return False
                symbol_names.add(symbol["name"])
                symbol_kind = symbol["kind"]
            if ("enum" if symbol_kind == "enum" else "flags") not in tokens:
                return False
    return True


def valid_android_styleable_dependencies(value: object, dependencies: object) -> bool:
    if (
        not isinstance(value, list)
        or not value
        or not valid_android_attribute_dependencies(dependencies)
    ):
        return False
    dependency_names = {dependency["name"] for dependency in dependencies}
    groups = set()
    represented = set()
    for group in value:
        if (
            not isinstance(group, dict)
            or not set(group) <= {"name", "generic", "attributes"}
            or not isinstance(group.get("name"), str)
            or not group["name"]
            or group["name"] in groups
            or "generic" in group
            and group["generic"] is not True
            or not isinstance(group.get("attributes"), list)
            or not group["attributes"]
        ):
            return False
        groups.add(group["name"])
        shared = False
        for attribute in group["attributes"]:
            if (
                not isinstance(attribute, dict)
                or "weak" in attribute
                or "generic" in attribute
                or not isinstance(attribute.get("name"), str)
                or not attribute["name"]
                or not valid_android_attribute_dependencies([attribute])
            ):
                return False
            if attribute["name"] in dependency_names:
                represented.add(attribute["name"])
                shared = True
        if not shared:
            return False
    return all(
        dependency.get("weak") is not True or dependency["name"] in represented
        for dependency in dependencies
    )


def valid_android_selected_products(values: object) -> bool:
    return (
        isinstance(values, list)
        and bool(values)
        and all(
            isinstance(product, str)
            and bool(product)
            and product == product.strip("".join(map(chr, range(33))))
            and "," not in product
            for product in values
        )
        and len(values) == len(set(values))
    )


def valid_android_feature_flags(values: object) -> bool:
    return (
        isinstance(values, dict)
        and bool(values)
        and all(
            isinstance(name, str)
            and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", name)
            and isinstance(value, bool)
            for name, value in values.items()
        )
    )


def valid_android_feature_flag_definitions(values: object) -> bool:
    return (
        isinstance(values, list)
        and bool(values)
        and all(
            isinstance(definition, dict)
            and set(definition) == {"name", "mode", "value"}
            and isinstance(definition["name"], str)
            and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", definition["name"])
            and definition["mode"] in {"read_only", "read_write"}
            and (definition["value"] is None or isinstance(definition["value"], bool))
            for definition in values
        )
    )


def android_feature_flag_values(resource: dict[str, object]) -> dict[str, object]:
    definitions = resource.get("androidFeatureFlagDefinitions")
    if definitions is not None:
        return {definition["name"]: definition for definition in definitions}
    return dict(resource.get("androidFeatureFlags", {}))


def valid_apple_plist_dictionary(value: object, depth: int = 0) -> bool:
    if not isinstance(value, dict) or depth > 64:
        return False
    for key, field in value.items():
        if not isinstance(key, str):
            return False
        if not valid_apple_plist_value(field, depth + 1):
            return False
    return True


def valid_apple_plist_value(value: object, depth: int) -> bool:
    if depth > 64:
        return False
    if isinstance(value, bool | str):
        return True
    if isinstance(value, int):
        return -(1 << 63) <= value <= (1 << 64) - 1
    if isinstance(value, list):
        return all(valid_apple_plist_value(item, depth + 1) for item in value)
    if not isinstance(value, dict):
        return False
    if "$applePlistType" not in value:
        return valid_apple_plist_dictionary(value, depth)
    kind = value["$applePlistType"]
    if kind == "data" and set(value) == {"$applePlistType", "base64"}:
        if not isinstance(value["base64"], str):
            return False
        try:
            decoded = base64.b64decode(value["base64"], validate=True)
        except (ValueError, TypeError):
            return False
        return (
            len(decoded) <= 1_000_000
            and base64.b64encode(decoded).decode() == value["base64"]
        )
    if kind == "date" and set(value) == {"$applePlistType", "value"}:
        try:
            parsed = datetime.datetime.strptime(value["value"], "%Y-%m-%dT%H:%M:%SZ")
        except (ValueError, TypeError):
            return False
        return parsed.strftime("%Y-%m-%dT%H:%M:%SZ") == value["value"]
    if kind == "real" and set(value) == {"$applePlistType", "bits"}:
        return isinstance(value["bits"], str) and bool(
            re.fullmatch(r"[0-9a-f]{16}", value["bits"])
        )
    if kind == "dictionary" and set(value) == {"$applePlistType", "entries"}:
        entries = value["entries"]
        if not isinstance(entries, list):
            return False
        keys = set()
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {"key", "value"}:
                return False
            key = entry["key"]
            if not isinstance(key, str) or key in keys:
                return False
            keys.add(key)
            if not valid_apple_plist_value(entry["value"], depth + 1):
                return False
        return True
    return False


def valid_android_feature_condition(
    condition: object, flags: dict[str, object]
) -> bool:
    if not isinstance(condition, str):
        return False
    negated = condition.startswith("!")
    name = condition[1:] if negated else condition
    if name not in flags:
        return False
    definition = flags[name]
    if isinstance(definition, dict):
        return definition["mode"] == "read_write" or definition["value"] is not negated
    return definition is not negated


def android_runtime_feature_condition(condition: str, flags: dict[str, object]) -> bool:
    definition = flags.get(condition.removeprefix("!"))
    return isinstance(definition, dict) and definition["mode"] == "read_write"


def android_reference(value: object) -> bool:
    if not isinstance(value, str):
        return False
    value = value.strip(" \t\r\n\f\v")
    if value in {"@null", "@empty"}:
        return True
    if (
        value.startswith("@@")
        and not value.startswith(("@@@", "@@+"))
        and "/" in value[2:]
    ):
        return android_reference(value[1:])
    types = {
        "anim",
        "animator",
        "array",
        "attr",
        "^attr-private",
        "bool",
        "color",
        "configVarying",
        "dimen",
        "drawable",
        "font",
        "fraction",
        "id",
        "integer",
        "interpolator",
        "layout",
        "macro",
        "menu",
        "mipmap",
        "navigation",
        "plurals",
        "raw",
        "string",
        "style",
        "styleable",
        "transition",
        "xml",
    }
    if value.startswith("@"):
        native = value[1:]
        create = native.startswith("+")
        if create:
            native = native[1:]
        private = native.startswith("*")
        if private:
            native = native[1:]
        if create and private:
            return False
        qualified, separator, entry = native.partition("/")
        if not separator or not qualified or not entry:
            return False
        package, colon, kind = qualified.partition(":")
        if colon and (not package or not kind):
            return False
        kind = kind if colon else qualified
        return kind in types and (not create or kind == "id")
    if not value.startswith("?"):
        return False
    native = value[1:].removeprefix("*")
    qualified, separator, entry = native.partition("/")
    if separator:
        package, colon, kind = qualified.partition(":")
        return bool(
            entry
            and (not colon or package and kind)
            and (kind if colon else qualified) == "attr"
        )
    package, colon, entry = qualified.partition(":")
    return bool(qualified and (not colon or package and entry))


if __name__ == "__main__":
    main()
