#!/usr/bin/env python3
"""Decode the pinned M2IF binary fixture without using Java."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from dataclasses import dataclass
from pathlib import Path

from mf2_term_pack_render import build_runtime, render_message


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parents[2]
DEFAULT_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.m2if.hex"
)
DEFAULT_SPANISH_ARTICLE_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.m2if.hex"
)
DEFAULT_ITALIAN_ARTICLE_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.m2if.hex"
)
DEFAULT_DANISH_EXPORT_POLICY_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.m2if.hex"
)
DEFAULT_EXPORT_POLICY_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.m2if.hex"
)
DEFAULT_REVIEW_REQUIRED_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.m2if.hex"
)
DEFAULT_ARABIC_APPROVED_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.m2if.hex"
)
DEFAULT_HEBREW_REVIEW_REQUIRED_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.m2if.hex"
)
DEFAULT_MALAYALAM_REVIEW_REQUIRED_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.m2if.hex"
)
DEFAULT_MALAYALAM_APPROVED_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.m2if.hex"
)
DEFAULT_GERMAN_ARTICLE_CASE_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.m2if.hex"
)
DEFAULT_HINDI_CASE_FORM_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.m2if.hex"
)
DEFAULT_RUSSIAN_CASE_FORM_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.m2if.hex"
)
DEFAULT_SERBIAN_CASE_FORM_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.m2if.hex"
)
DEFAULT_TURKISH_SUFFIX_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.m2if.hex"
)
DEFAULT_TURKISH_EXPLICIT_TEMPLATE_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.m2if.hex"
)
DEFAULT_TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE = (
    REPO_ROOT
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.m2if.hex"
)

MAGIC = b"M2IF"
VERSION = 0
FLAGS = 0
NULL_INDEX = -1
HEADER_BYTES = 88
SECTION_DIRECTORY_OFFSET = 32
SECTION_DIRECTORY_ENTRY_BYTES = 8
TERM_ROW_BYTES = 20
FORM_SET_ROW_BYTES = 12
FORM_ROW_BYTES = 12
BINDING_ROW_BYTES = 12
FORM_ROW_PATTERN_FLAG = 1
METADATA_SCHEMA = "mojito-mf2-inflection/compiled-term-pack-metadata/v0"
SECTION_NAMES = [
    "strings",
    "stringOffsets",
    "terms",
    "formSets",
    "formRows",
    "bindings",
    "metadata",
]
EXPECTED_FIXTURE_SECTIONS = {
    "strings": (88, 87),
    "stringOffsets": (176, 48),
    "terms": (224, 40),
    "formSets": (264, 24),
    "formRows": (288, 48),
    "bindings": (336, 0),
    "metadata": (336, 589),
}
EXPECTED_SPANISH_ARTICLE_FIXTURE_SECTIONS = {
    "strings": (88, 116),
    "stringOffsets": (204, 64),
    "terms": (268, 60),
    "formSets": (328, 36),
    "formRows": (364, 72),
    "bindings": (436, 0),
    "metadata": (436, 591),
}
EXPECTED_ITALIAN_ARTICLE_FIXTURE_SECTIONS = {
    "strings": (88, 135),
    "stringOffsets": (224, 80),
    "terms": (304, 80),
    "formSets": (384, 48),
    "formRows": (432, 96),
    "bindings": (528, 0),
    "metadata": (528, 591),
}
EXPECTED_DANISH_EXPORT_POLICY_FIXTURE_SECTIONS = {
    "strings": (88, 544),
    "stringOffsets": (632, 128),
    "terms": (760, 40),
    "formSets": (800, 24),
    "formRows": (824, 240),
    "bindings": (1064, 0),
    "metadata": (1064, 817),
}
EXPECTED_EXPORT_POLICY_FIXTURE_SECTIONS = {
    "strings": (88, 483),
    "stringOffsets": (572, 128),
    "terms": (700, 40),
    "formSets": (740, 24),
    "formRows": (764, 240),
    "bindings": (1004, 0),
    "metadata": (1004, 817),
}
EXPECTED_REVIEW_REQUIRED_FIXTURE_SECTIONS = {
    "strings": (88, 656),
    "stringOffsets": (744, 132),
    "terms": (876, 20),
    "formSets": (896, 12),
    "formRows": (908, 168),
    "bindings": (1076, 0),
    "metadata": (1076, 747),
}
EXPECTED_ARABIC_APPROVED_FIXTURE_SECTIONS = {
    "strings": (88, 617),
    "stringOffsets": (708, 112),
    "terms": (820, 20),
    "formSets": (840, 12),
    "formRows": (852, 216),
    "bindings": (1068, 0),
    "metadata": (1068, 726),
}
EXPECTED_HEBREW_REVIEW_REQUIRED_FIXTURE_SECTIONS = {
    "strings": (88, 121),
    "stringOffsets": (212, 44),
    "terms": (256, 20),
    "formSets": (276, 12),
    "formRows": (288, 48),
    "bindings": (336, 0),
    "metadata": (336, 765),
}
EXPECTED_MALAYALAM_REVIEW_REQUIRED_FIXTURE_SECTIONS = {
    "strings": (88, 718),
    "stringOffsets": (808, 128),
    "terms": (936, 20),
    "formSets": (956, 12),
    "formRows": (968, 168),
    "bindings": (1136, 0),
    "metadata": (1136, 756),
}
EXPECTED_MALAYALAM_APPROVED_FIXTURE_SECTIONS = {
    "strings": (88, 823),
    "stringOffsets": (912, 144),
    "terms": (1056, 20),
    "formSets": (1076, 12),
    "formRows": (1088, 192),
    "bindings": (1280, 0),
    "metadata": (1280, 735),
}
EXPECTED_GERMAN_ARTICLE_CASE_FIXTURE_SECTIONS = {
    "strings": (88, 590),
    "stringOffsets": (680, 156),
    "terms": (836, 60),
    "formSets": (896, 36),
    "formRows": (932, 288),
    "bindings": (1220, 0),
    "metadata": (1220, 591),
}
EXPECTED_HINDI_CASE_FORM_FIXTURE_SECTIONS = {
    "strings": (88, 353),
    "stringOffsets": (444, 88),
    "terms": (532, 60),
    "formSets": (592, 36),
    "formRows": (628, 216),
    "bindings": (844, 0),
    "metadata": (844, 755),
}
EXPECTED_RUSSIAN_CASE_FORM_FIXTURE_SECTIONS = {
    "strings": (88, 795),
    "stringOffsets": (884, 184),
    "terms": (1068, 60),
    "formSets": (1128, 36),
    "formRows": (1164, 432),
    "bindings": (1596, 0),
    "metadata": (1596, 652),
}
EXPECTED_SERBIAN_CASE_FORM_FIXTURE_SECTIONS = {
    "strings": (88, 1402),
    "stringOffsets": (1492, 432),
    "terms": (1924, 240),
    "formSets": (2164, 144),
    "formRows": (2308, 1260),
    "bindings": (3568, 0),
    "metadata": (3568, 649),
}
EXPECTED_TURKISH_SUFFIX_FIXTURE_SECTIONS = {
    "strings": (88, 115),
    "stringOffsets": (204, 68),
    "terms": (272, 100),
    "formSets": (372, 60),
    "formRows": (432, 60),
    "bindings": (492, 0),
    "metadata": (492, 777),
}
EXPECTED_TURKISH_EXPLICIT_TEMPLATE_FIXTURE_SECTIONS = {
    "strings": (88, 354),
    "stringOffsets": (444, 120),
    "terms": (564, 80),
    "formSets": (644, 48),
    "formRows": (692, 216),
    "bindings": (908, 0),
    "metadata": (908, 806),
}
EXPECTED_TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE_SECTIONS = {
    "strings": (88, 454),
    "stringOffsets": (544, 156),
    "terms": (700, 160),
    "formSets": (860, 96),
    "formRows": (956, 372),
    "bindings": (1328, 0),
    "metadata": (1328, 806),
}
FIXTURE_KINDS = [
    "pt-agreement",
    "es-article",
    "it-article",
    "da-export-policy",
    "sv-export-policy",
    "ar-review-required",
    "ar-approved",
    "he-review-required",
    "ml-review-required",
    "ml-approved",
    "de-article-case",
    "hi-case-form",
    "ru-case-form",
    "sr-case-form",
    "tr-suffix",
    "tr-explicit-template",
    "tr-explicit-template-auto",
]


@dataclass(frozen=True)
class Section:
    name: str
    offset: int
    length: int


def read_fixture(path: Path) -> bytes:
    return bytes.fromhex("".join(path.read_text(encoding="utf-8").split()))


def u16(payload: bytes, offset: int) -> int:
    return struct.unpack_from("<H", payload, offset)[0]


def i32(payload: bytes, offset: int) -> int:
    return struct.unpack_from("<i", payload, offset)[0]


def section_bytes(count: int, row_bytes: int, name: str) -> int:
    if count < 0:
        raise ValueError(f"{name} count must be non-negative")
    return count * row_bytes


def require_section_length(section: Section, expected: int) -> None:
    if section.length != expected:
        raise ValueError(
            f"Unexpected section length for {section.name}: expected {expected}, got {section.length}"
        )


def validate_section_bounds(sections: list[Section], payload_size: int) -> None:
    non_empty = []
    max_end = HEADER_BYTES
    for section in sections:
        if section.offset < HEADER_BYTES:
            raise ValueError(f"Section offset overlaps header: {section.name}")
        if section.offset % 4 != 0:
            raise ValueError(f"Section offset is not 4-byte aligned: {section.name}")
        if section.length < 0 or section.offset > payload_size - section.length:
            raise ValueError(f"Section out of bounds: {section.name}")
        max_end = max(max_end, section.offset + section.length)
        if section.length > 0:
            non_empty.append(section)

    previous_end = HEADER_BYTES
    for section in sorted(non_empty, key=lambda item: item.offset):
        if section.offset < previous_end:
            raise ValueError(f"Section overlaps previous section: {section.name}")
        previous_end = section.offset + section.length

    if max_end != payload_size:
        raise ValueError(f"M2IF payload contains trailing bytes: expected {max_end}, got {payload_size}")


def section_payload(payload: bytes, section: Section) -> bytes:
    return payload[section.offset : section.offset + section.length]


def read_strings(strings_payload: bytes, offsets_payload: bytes, string_count: int) -> list[str]:
    offsets = [i32(offsets_payload, index * 4) for index in range(string_count + 1)]
    if offsets[0] != 0:
        raise ValueError("First string offset must be zero")
    if offsets[-1] != len(strings_payload):
        raise ValueError("Final string offset does not match string section length")

    strings = []
    for index, start in enumerate(offsets[:-1]):
        end_with_terminator = offsets[index + 1]
        if start < 0 or end_with_terminator <= start or end_with_terminator > len(strings_payload):
            raise ValueError(f"Invalid string offset range at index: {index}")
        if strings_payload[end_with_terminator - 1] != 0:
            raise ValueError(f"String is not NUL-terminated at index: {index}")
        strings.append(strings_payload[start : end_with_terminator - 1].decode("utf-8", errors="strict"))
    return strings


def require_string_index(index: int, strings: list[str], field: str) -> None:
    if index < 0 or index >= len(strings):
        raise ValueError(f"String index out of bounds for {field}: {index}")


def read_form_rows(payload: bytes, count: int) -> list[dict]:
    rows = []
    for row_index in range(count):
        offset = row_index * FORM_ROW_BYTES
        key = i32(payload, offset)
        value = i32(payload, offset + 4)
        flags = i32(payload, offset + 8)
        if flags & ~FORM_ROW_PATTERN_FLAG:
            raise ValueError(f"Unsupported form row flags: {flags}")
        rows.append(
            {
                "key": key,
                "value": value,
                "kind": "pattern" if flags & FORM_ROW_PATTERN_FLAG else "literal",
            }
        )
    return rows


def read_form_sets(payload: bytes, count: int, form_rows: list[dict]) -> list[dict]:
    form_sets = []
    for row_index in range(count):
        offset = row_index * FORM_SET_ROW_BYTES
        term = i32(payload, offset)
        first_form_row = i32(payload, offset + 4)
        form_row_count = i32(payload, offset + 8)
        if first_form_row < 0 or form_row_count < 0:
            raise ValueError("Form-set row range must be non-negative")
        end_form_row = first_form_row + form_row_count
        if end_form_row > len(form_rows):
            raise ValueError(f"Form set row range is out of bounds: {row_index}")
        form_sets.append({"term": term, "forms": form_rows[first_form_row:end_form_row]})
    return form_sets


def read_terms(payload: bytes, count: int) -> list[dict]:
    terms = []
    for row_index in range(count):
        offset = row_index * TERM_ROW_BYTES
        sense = i32(payload, offset + 12)
        if sense < NULL_INDEX:
            raise ValueError(f"Invalid sense string index: {sense}")
        terms.append(
            {
                "id": i32(payload, offset),
                "text": i32(payload, offset + 4),
                "featureBits": i32(payload, offset + 8),
                "sense": None if sense == NULL_INDEX else sense,
                "formSet": i32(payload, offset + 16),
            }
        )
    return terms


def read_metadata(payload: bytes) -> dict:
    if not payload:
        return {}
    metadata = json.loads(payload.decode("utf-8", errors="strict"))
    if not isinstance(metadata, dict):
        raise ValueError("M2IF metadata must be a JSON object")
    if metadata.get("schema") != METADATA_SCHEMA:
        raise ValueError(f"Expected M2IF metadata schema: {METADATA_SCHEMA}")
    provenance = metadata.get("provenance")
    if provenance is not None and not isinstance(provenance, dict):
        raise ValueError("M2IF metadata requires provenance object")
    export_policy = read_export_policy(metadata.get("exportPolicy"))
    if provenance is None and not export_policy:
        raise ValueError("M2IF metadata requires provenance or exportPolicy object")
    if export_policy:
        metadata["exportPolicy"] = export_policy
    return metadata


def read_export_policy(value: object) -> dict:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError("M2IF exportPolicy must be a JSON object")

    runtime_export = required_text(value, "runtimeExport")
    composition_mode = required_text(value, "compositionMode")
    deferred_composition = required_text_array(value, "deferredComposition")
    if len(set(deferred_composition)) != len(deferred_composition):
        raise ValueError("M2IF exportPolicy has duplicate deferredComposition value")

    review_required_terms = required_non_negative_int(value, "reviewRequiredTerms")
    blocked_terms = required_non_negative_int(value, "blockedTerms")
    review_required_reasons = required_reason_counts(value, "reviewRequiredReasons")
    blocked_reasons = required_reason_counts(value, "blockedReasons")
    if review_required_terms == 0 and review_required_reasons:
        raise ValueError("M2IF exportPolicy reviewRequiredReasons require reviewRequiredTerms")
    if blocked_terms == 0 and blocked_reasons:
        raise ValueError("M2IF exportPolicy blockedReasons require blockedTerms")

    return {
        "runtimeExport": runtime_export,
        "compositionMode": composition_mode,
        "deferredComposition": deferred_composition,
        "automaticExportTerms": required_non_negative_int(value, "automaticExportTerms"),
        "reviewRequiredTerms": review_required_terms,
        "blockedTerms": blocked_terms,
        "reviewRequiredReasons": review_required_reasons,
        "blockedReasons": blocked_reasons,
    }


def required_text(payload: dict, field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"M2IF exportPolicy requires non-blank text field: {field}")
    return value


def required_text_array(payload: dict, field: str) -> list[str]:
    value = payload.get(field)
    if not isinstance(value, list):
        raise ValueError(f"M2IF exportPolicy requires array field: {field}")
    values = []
    for item in value:
        if not isinstance(item, str) or not item.strip():
            raise ValueError(f"M2IF exportPolicy requires non-blank text values in: {field}")
        values.append(item)
    return values


def required_non_negative_int(payload: dict, field: str) -> int:
    value = payload.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"M2IF exportPolicy requires non-negative integer field: {field}")
    return value


def required_reason_counts(payload: dict, field: str) -> dict[str, int]:
    value = payload.get(field)
    if not isinstance(value, dict):
        raise ValueError(f"M2IF exportPolicy requires object field: {field}")
    counts = {}
    for reason, count in value.items():
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(f"M2IF exportPolicy requires non-blank reason keys in: {field}")
        if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
            raise ValueError(f"M2IF exportPolicy requires positive reason counts in: {field}")
        counts[reason] = count
    return counts


def validate_model(pack: dict) -> None:
    strings = pack["strings"]
    for value in strings:
        if not value:
            raise ValueError("M2IF string pool values must not be blank")

    term_ids = set()
    form_set_terms = set()
    for form_set in pack["formSets"]:
        require_string_index(form_set["term"], strings, "formSet.term")
        if not form_set["forms"]:
            raise ValueError(f"Compiled form set requires forms: {strings[form_set['term']]}")
        if form_set["term"] in form_set_terms:
            raise ValueError(f"Duplicate form set for term: {strings[form_set['term']]}")
        form_set_terms.add(form_set["term"])
        form_keys = set()
        for form in form_set["forms"]:
            require_string_index(form["key"], strings, "form.key")
            require_string_index(form["value"], strings, "form.value")
            if form["key"] in form_keys:
                raise ValueError(
                    f"Duplicate compiled form key for term {strings[form_set['term']]}: {strings[form['key']]}"
                )
            form_keys.add(form["key"])

    for term in pack["terms"]:
        require_string_index(term["id"], strings, "term.id")
        require_string_index(term["text"], strings, "term.text")
        if term["sense"] is not None:
            require_string_index(term["sense"], strings, "term.sense")
        if term["formSet"] < 0 or term["formSet"] >= len(pack["formSets"]):
            raise ValueError(f"Term form set index out of bounds: {term['formSet']}")
        if pack["formSets"][term["formSet"]]["term"] != term["id"]:
            raise ValueError(f"Term row id does not match form set term: {strings[term['id']]}")
        if term["id"] in term_ids:
            raise ValueError(f"Duplicate compiled term row: {strings[term['id']]}")
        term_ids.add(term["id"])

    for form_set_term in form_set_terms:
        if form_set_term not in term_ids:
            raise ValueError(f"Compiled form set has no term row: {strings[form_set_term]}")


def decode_m2if(payload: bytes) -> tuple[dict, list[Section]]:
    if len(payload) < HEADER_BYTES:
        raise ValueError("M2IF payload is smaller than the header")
    if payload[:4] != MAGIC:
        raise ValueError("Invalid M2IF magic")
    version = u16(payload, 4)
    if version != VERSION:
        raise ValueError(f"Unsupported M2IF version: {version}")
    flags = u16(payload, 6)
    if flags != FLAGS:
        raise ValueError(f"Unsupported M2IF flags: {flags}")

    locale_index = i32(payload, 8)
    string_count = i32(payload, 12)
    term_count = i32(payload, 16)
    form_set_count = i32(payload, 20)
    form_row_count = i32(payload, 24)
    binding_row_count = i32(payload, 28)
    counts = {
        "stringCount": string_count,
        "termCount": term_count,
        "formSetCount": form_set_count,
        "formRowCount": form_row_count,
        "bindingRowCount": binding_row_count,
    }
    for field, count in counts.items():
        if count < 0:
            raise ValueError(f"{field} must be non-negative: {count}")

    sections = []
    for index, name in enumerate(SECTION_NAMES):
        offset = SECTION_DIRECTORY_OFFSET + index * SECTION_DIRECTORY_ENTRY_BYTES
        sections.append(Section(name, i32(payload, offset), i32(payload, offset + 4)))

    sections_by_name = {section.name: section for section in sections}
    require_section_length(
        sections_by_name["stringOffsets"],
        section_bytes(string_count + 1, 4, "stringOffsets"),
    )
    require_section_length(sections_by_name["terms"], section_bytes(term_count, TERM_ROW_BYTES, "terms"))
    require_section_length(
        sections_by_name["formSets"],
        section_bytes(form_set_count, FORM_SET_ROW_BYTES, "formSets"),
    )
    require_section_length(
        sections_by_name["formRows"],
        section_bytes(form_row_count, FORM_ROW_BYTES, "formRows"),
    )
    require_section_length(
        sections_by_name["bindings"],
        section_bytes(binding_row_count, BINDING_ROW_BYTES, "bindings"),
    )
    if binding_row_count != 0:
        raise ValueError("M2IF bindings section is reserved and must be empty")

    validate_section_bounds(sections, len(payload))

    strings = read_strings(
        section_payload(payload, sections_by_name["strings"]),
        section_payload(payload, sections_by_name["stringOffsets"]),
        string_count,
    )
    if locale_index != NULL_INDEX:
        require_string_index(locale_index, strings, "localeString")
    form_rows = read_form_rows(section_payload(payload, sections_by_name["formRows"]), form_row_count)
    form_sets = read_form_sets(
        section_payload(payload, sections_by_name["formSets"]),
        form_set_count,
        form_rows,
    )
    terms = read_terms(section_payload(payload, sections_by_name["terms"]), term_count)
    metadata = read_metadata(section_payload(payload, sections_by_name["metadata"]))
    pack = {
        "locale": None if locale_index == NULL_INDEX else strings[locale_index],
        "strings": strings,
        "terms": terms,
        "formSets": form_sets,
        "provenance": metadata.get("provenance", {}),
        "exportPolicy": metadata.get("exportPolicy", {}),
        "diagnostics": [],
    }
    validate_model(pack)
    return pack, sections


def assert_portuguese_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 925:
        raise AssertionError(f"Expected 925 fixture bytes, got {len(payload)}")
    if digest != "87e6c93ca75f3a8b0bfb75c974a581c375a997ed00483a6affddbe148577a5b0":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "pt":
        raise AssertionError(f"Expected locale 'pt', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Portuguese fixture should stay provenance-only")

    rendered = render_message(
        "Disponível {$item :term preposition=em article=definite count=$count}.",
        build_runtime(pack),
        {"item": "item.house"},
        {"count": "2"},
    )
    if rendered != "Disponível nas casas.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_spanish_article_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1027:
        raise AssertionError(f"Expected 1027 fixture bytes, got {len(payload)}")
    if digest != "e1005077d10897b432bd11cca24b8fae5532119b2c0d0415dc2b07c2d353fbdc":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_SPANISH_ARTICLE_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "es":
        raise AssertionError(f"Expected locale 'es', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Spanish fixture should stay provenance-only")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Has eliminado {$item :term article=definite count=$count}.",
        runtime,
        {"item": "item.water"},
        {"count": "1"},
    )
    if rendered != "Has eliminado el agua.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "Has encontrado {$item :term article=definite count=$count}.",
        runtime,
        {"item": "item.water"},
        {"count": "2"},
    )
    if rendered_plural != "Has encontrado las aguas.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_italian_article_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1119:
        raise AssertionError(f"Expected 1119 fixture bytes, got {len(payload)}")
    if digest != "75c95f06aef86b51b39ec9fc38a2540169bee20f5b43613a52bcf01c8a8806b9":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_ITALIAN_ARTICLE_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "it":
        raise AssertionError(f"Expected locale 'it', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Italian fixture should stay provenance-only")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Hai eliminato {$item :term article=definite count=$count}.",
        runtime,
        {"item": "item.gnome"},
        {"count": "1"},
    )
    if rendered != "Hai eliminato lo gnomo.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "Hai eliminato {$item :term article=definite count=$count}.",
        runtime,
        {"item": "item.gnome"},
        {"count": "2"},
    )
    if rendered_plural != "Hai eliminato gli gnomi.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    rendered_elision = render_message(
        "Hai trovato {$item :term article=indefinite count=$count}.",
        runtime,
        {"item": "item.bee"},
        {"count": "1"},
    )
    if rendered_elision != "Hai trovato un'ape.":
        raise AssertionError(f"Unexpected elision render output: {rendered_elision}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "renderedElision": rendered_elision,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_swedish_export_policy_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1821:
        raise AssertionError(f"Expected 1821 fixture bytes, got {len(payload)}")
    if digest != "766fcf012af7386e09c723002022746594ecf2e195c51b32497f01136e737a7d":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_EXPORT_POLICY_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "sv":
        raise AssertionError(f"Expected locale 'sv', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-genitive-definiteness-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 2:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 0 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected no review-required or blocked Swedish fixture terms")
    if export_policy["deferredComposition"] != [
        "article-selection",
        "definiteness-suffix",
        "genitive-suffix",
    ]:
        raise AssertionError("Unexpected embedded exportPolicy deferred composition list")

    rendered = render_message(
        "Ägare: {$item :term definiteness=definite case=genitive number=plural}.",
        build_runtime(pack),
        {"item": "sv.definiteness.bostad"},
        {},
    )
    if rendered != "Ägare: bostädernas.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_danish_export_policy_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1881:
        raise AssertionError(f"Expected 1881 fixture bytes, got {len(payload)}")
    if digest != "2c4149893f8a04ebe36d47a7ac78d46d6bac567b7a3e4ccaec29400e9103d10a":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_DANISH_EXPORT_POLICY_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "da":
        raise AssertionError(f"Expected locale 'da', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-genitive-definiteness-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 2:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 0 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected no review-required or blocked Danish fixture terms")
    if export_policy["reviewRequiredReasons"] or export_policy["blockedReasons"]:
        raise AssertionError("Expected no Danish fixture export-policy reason counts")
    if export_policy["deferredComposition"] != [
        "article-selection",
        "definiteness-suffix",
        "genitive-suffix",
    ]:
        raise AssertionError("Unexpected embedded exportPolicy deferred composition list")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Ejer: {$item :term definiteness=definite case=genitive number=plural}.",
        runtime,
        {"item": "da.definiteness.franskmand"},
        {},
    )
    if rendered != "Ejer: franskmændenes.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_definite = render_message(
        "Valgt {$item :term definiteness=definite case=nominative}.",
        runtime,
        {"item": "da.definiteness.barnebarn"},
        {},
    )
    if rendered_definite != "Valgt barnebarnet.":
        raise AssertionError(f"Unexpected definite render output: {rendered_definite}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedDefinite": rendered_definite,
        "reviewRequiredReasons": export_policy["reviewRequiredReasons"],
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_arabic_review_required_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1823:
        raise AssertionError(f"Expected 1823 fixture bytes, got {len(payload)}")
    if digest != "1306a96f4d5fc2b6aefa4a4155f99ba07925b72004f4e9f6c38c9effeea668b9":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_REVIEW_REQUIRED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "ar":
        raise AssertionError(f"Expected locale 'ar', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 0:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 1 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected one review-required Arabic fixture term and no blocked terms")
    if export_policy["reviewRequiredReasons"] != {"missing-form-cell": 1}:
        raise AssertionError("Unexpected embedded exportPolicy review-required reason counts")
    if export_policy["blockedReasons"] != {}:
        raise AssertionError("Expected no blocked Arabic fixture reasons")

    rendered = render_message(
        "مع {$item :term definiteness=construct case=genitive}.",
        build_runtime(pack),
        {"item": "ar.explicit.mother"},
        {},
    )
    if rendered != "مع أُمِّ.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "reviewRequiredReasons": export_policy["reviewRequiredReasons"],
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_arabic_approved_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1794:
        raise AssertionError(f"Expected 1794 fixture bytes, got {len(payload)}")
    if digest != "cabd16085dc4b121afd4a0ab7a596990bb8d4f98d3a34b095fa247f802955e36":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_ARABIC_APPROVED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "ar":
        raise AssertionError(f"Expected locale 'ar', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 1:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 0 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected no review-required or blocked Arabic fixture terms")
    if export_policy["reviewRequiredReasons"] != {}:
        raise AssertionError("Expected no Arabic approved fixture review-required reasons")
    if export_policy["blockedReasons"] != {}:
        raise AssertionError("Expected no blocked Arabic approved fixture reasons")

    runtime = build_runtime(pack)
    rendered = render_message(
        "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
        runtime,
        {"item": "ar.explicit.message"},
        {},
    )
    if rendered != "اختيرت رسالتي.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "حُذفت {$item :term definiteness=indefinite case=genitive number=plural}.",
        runtime,
        {"item": "ar.explicit.message"},
        {},
    )
    if rendered_plural != "حُذفت رسائل.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_hebrew_review_required_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1101:
        raise AssertionError(f"Expected 1101 fixture bytes, got {len(payload)}")
    if digest != "d9d980a9b6fefbed71cf520c4e2d399278bae693ef14e60db2de5a7688c2fa51":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_HEBREW_REVIEW_REQUIRED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "he":
        raise AssertionError(f"Expected locale 'he', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-construct-state-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 0:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 1 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected one review-required Hebrew fixture term and no blocked terms")
    if export_policy["reviewRequiredReasons"] != {"missing-form-cell": 1}:
        raise AssertionError("Unexpected embedded exportPolicy review-required reason counts")
    if export_policy["blockedReasons"] != {}:
        raise AssertionError("Expected no blocked Hebrew fixture reasons")

    rendered = render_message(
        "נבחרו {$item :term definiteness=construct number=plural}.",
        build_runtime(pack),
        {"item": "he.construct.house"},
        {},
    )
    if rendered != "נבחרו בתי.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "reviewRequiredReasons": export_policy["reviewRequiredReasons"],
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_malayalam_review_required_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1892:
        raise AssertionError(f"Expected 1892 fixture bytes, got {len(payload)}")
    if digest != "c3796eb005a9bbecc73709a506b68d69aa9c4d3156327c30a7287381370e7aaa":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_MALAYALAM_REVIEW_REQUIRED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "ml":
        raise AssertionError(f"Expected locale 'ml', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-multi-case-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 0:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 1 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected one review-required Malayalam fixture term and no blocked terms")
    if export_policy["reviewRequiredReasons"] != {"missing-form-cell": 1}:
        raise AssertionError("Unexpected embedded exportPolicy review-required reason counts")
    if export_policy["blockedReasons"] != {}:
        raise AssertionError("Expected no blocked Malayalam fixture reasons")

    rendered = render_message(
        "തിരഞ്ഞെടുത്തത് {$item :term case=genitive}.",
        build_runtime(pack),
        {"item": "ml.case.disciple"},
        {},
    )
    if rendered != "തിരഞ്ഞെടുത്തത് ശിഷ്യന്റെ.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "reviewRequiredReasons": export_policy["reviewRequiredReasons"],
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_malayalam_approved_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 2015:
        raise AssertionError(f"Expected 2015 fixture bytes, got {len(payload)}")
    if digest != "56f3c79cf45c7a0aff0d5ecef82d035d94d11cce552b0f30c66a525b2124b0fe":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_MALAYALAM_APPROVED_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "ml":
        raise AssertionError(f"Expected locale 'ml', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")

    export_policy = pack["exportPolicy"]
    if not export_policy:
        raise AssertionError("Expected embedded exportPolicy metadata")
    if export_policy["runtimeExport"] != "closed-world-multi-case-explicit-forms":
        raise AssertionError("Unexpected embedded exportPolicy runtime export")
    if export_policy["compositionMode"] != "explicit-form-rows-v0":
        raise AssertionError("Unexpected embedded exportPolicy composition mode")
    if export_policy["automaticExportTerms"] != 1:
        raise AssertionError("Unexpected embedded exportPolicy automatic export count")
    if export_policy["reviewRequiredTerms"] != 0 or export_policy["blockedTerms"] != 0:
        raise AssertionError("Expected no review-required or blocked Malayalam fixture terms")
    if export_policy["reviewRequiredReasons"] != {}:
        raise AssertionError("Expected no Malayalam approved fixture review-required reasons")
    if export_policy["blockedReasons"] != {}:
        raise AssertionError("Expected no blocked Malayalam approved fixture reasons")

    runtime = build_runtime(pack)
    rendered = render_message(
        "വിളിച്ചത് {$item :term case=vocative}.",
        runtime,
        {"item": "ml.case.father"},
        {},
    )
    if rendered != "വിളിച്ചത് പിതാവേ.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "കൂടെ {$item :term case=sociative number=plural}.",
        runtime,
        {"item": "ml.case.father"},
        {},
    )
    if rendered_plural != "കൂടെ പിതാക്കന്മാരോട്.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "runtimeExport": export_policy["runtimeExport"],
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_german_article_case_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1811:
        raise AssertionError(f"Expected 1811 fixture bytes, got {len(payload)}")
    if digest != "f7ad4866b16605ae3a5af0efcb0a3280543e5363f53c6d77aad28faf6d91cdef":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_GERMAN_ARTICLE_CASE_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "de":
        raise AssertionError(f"Expected locale 'de', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned German fixture should stay provenance-only")

    rendered = render_message(
        "Mit {$item :term article=definite case=dative count=$count}.",
        build_runtime(pack),
        {"item": "de.article_case.katze"},
        {"count": "2"},
    )
    if rendered != "Mit den Katzen.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_hindi_case_form_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1599:
        raise AssertionError(f"Expected 1599 fixture bytes, got {len(payload)}")
    if digest != "99b2392f63f18508daeabc133dc64389176d678b6350fd955186e809680a03b8":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_HINDI_CASE_FORM_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "hi":
        raise AssertionError(f"Expected locale 'hi', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Hindi fixture should stay provenance-only")

    rendered = render_message(
        "में {$item :term case=oblique count=$count}.",
        build_runtime(pack),
        {"item": "hi.case.आँख"},
        {"count": "2"},
    )
    if rendered != "में आँखों.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_russian_case_form_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 2248:
        raise AssertionError(f"Expected 2248 fixture bytes, got {len(payload)}")
    if digest != "c98e970f2636d387501cf00e6e4303a4b56a3e3ddd5cf05aa462bdb6c630898a":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_RUSSIAN_CASE_FORM_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "ru":
        raise AssertionError(f"Expected locale 'ru', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Russian fixture should stay provenance-only")

    rendered = render_message(
        "Удалено {$item :term case=accusative count=$count}.",
        build_runtime(pack),
        {"item": "ru.case.кошка"},
        {"count": "2"},
    )
    if rendered != "Удалено кошек.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_serbian_case_form_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 4217:
        raise AssertionError(f"Expected 4217 fixture bytes, got {len(payload)}")
    if digest != "44d3b8f2441fc1f883118ec4230ce8b0e10c223392c08a3fb3addfb8a070b9e6":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_SERBIAN_CASE_FORM_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "sr":
        raise AssertionError(f"Expected locale 'sr', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Serbian fixture should stay provenance-only")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Obrisano je {$item :term case=accusative count=$count}.",
        runtime,
        {"item": "sr.case.mačka"},
        {"count": "1"},
    )
    if rendered != "Obrisano je mačku.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "Dodato je {$item :term case=dative count=$count}.",
        runtime,
        {"item": "sr.case.izuzetak"},
        {"count": "2"},
    )
    if rendered_plural != "Dodato je izuzecima.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_turkish_suffix_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1269:
        raise AssertionError(f"Expected 1269 fixture bytes, got {len(payload)}")
    if digest != "71e1c3ab6d03392448dc0baf81b9e816b6ab420c39161a8d57be639f0aa9b785":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_TURKISH_SUFFIX_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "tr":
        raise AssertionError(f"Expected locale 'tr', got {pack['locale']!r}")
    if pack["provenance"].get("license") != "Unicode-3.0":
        raise AssertionError("Expected Unicode-3.0 provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Turkish fixture should stay provenance-only")

    rendered = render_message(
        "Silindi {$item :term case=accusative count=$count}.",
        build_runtime(pack),
        {"item": "item.house"},
        {"count": "2"},
    )
    if rendered != "Silindi evleri.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_turkish_explicit_template_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 1714:
        raise AssertionError(f"Expected 1714 fixture bytes, got {len(payload)}")
    if digest != "53f2d51cf36aedeea768cdb55acbc1864900ffe2c892e9b6b3b2af66ec3f0f2a":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_TURKISH_EXPLICIT_TEMPLATE_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "tr":
        raise AssertionError(f"Expected locale 'tr', got {pack['locale']!r}")
    if (
        pack["provenance"].get("license")
        != "CC0-1.0 dictionary data; Unicode-3.0 repository packaging"
    ):
        raise AssertionError("Expected Turkish explicit-template provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Turkish explicit-template fixture should stay provenance-only")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Silindi {$item :term case=accusative}.",
        runtime,
        {"item": "tr.explicit.çakmak"},
        {},
    )
    if rendered != "Silindi çakmağı.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "Listelendi {$item :term count=$count}.",
        runtime,
        {"item": "tr.explicit.amel"},
        {"count": "2"},
    )
    if rendered_plural != "Listelendi aʼmal.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_turkish_explicit_template_auto_fixture(path: Path) -> dict:
    payload = read_fixture(path)
    digest = hashlib.sha256(payload).hexdigest()
    if len(payload) != 2134:
        raise AssertionError(f"Expected 2134 fixture bytes, got {len(payload)}")
    if digest != "652079f23fd47c6202f7e6de86748fd44c4f28dbc5b7e91ab88e468b4baf2840":
        raise AssertionError(f"Unexpected fixture SHA-256: {digest}")

    pack, sections = decode_m2if(payload)
    actual_sections = {section.name: (section.offset, section.length) for section in sections}
    if actual_sections != EXPECTED_TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE_SECTIONS:
        raise AssertionError(actual_sections)
    if pack["locale"] != "tr":
        raise AssertionError(f"Expected locale 'tr', got {pack['locale']!r}")
    if (
        pack["provenance"].get("license")
        != "CC0-1.0 dictionary data; Unicode-3.0 repository packaging"
    ):
        raise AssertionError("Expected Turkish explicit-template auto provenance")
    if pack["exportPolicy"]:
        raise AssertionError("Pinned Turkish explicit-template auto fixture should stay provenance-only")

    runtime = build_runtime(pack)
    rendered = render_message(
        "Silindi {$item :term case=accusative}.",
        runtime,
        {"item": "tr.explicit.baklava"},
        {},
    )
    if rendered != "Silindi baklavayı.":
        raise AssertionError(f"Unexpected render output: {rendered}")
    rendered_plural = render_message(
        "Listelendi {$item :term count=$count}.",
        runtime,
        {"item": "tr.explicit.cetvel"},
        {"count": "2"},
    )
    if rendered_plural != "Listelendi cedâvil.":
        raise AssertionError(f"Unexpected plural render output: {rendered_plural}")
    return {
        "bytes": len(payload),
        "exportPolicyPresent": bool(pack["exportPolicy"]),
        "locale": pack["locale"],
        "rendered": rendered,
        "renderedPlural": rendered_plural,
        "sections": actual_sections,
        "sha256": digest,
    }


def assert_fixture(fixture_kind: str, fixture: Path | None = None) -> dict:
    if fixture_kind == "pt-agreement":
        return assert_portuguese_fixture(fixture or DEFAULT_FIXTURE)
    if fixture_kind == "es-article":
        return assert_spanish_article_fixture(fixture or DEFAULT_SPANISH_ARTICLE_FIXTURE)
    if fixture_kind == "it-article":
        return assert_italian_article_fixture(fixture or DEFAULT_ITALIAN_ARTICLE_FIXTURE)
    if fixture_kind == "da-export-policy":
        return assert_danish_export_policy_fixture(fixture or DEFAULT_DANISH_EXPORT_POLICY_FIXTURE)
    if fixture_kind == "sv-export-policy":
        return assert_swedish_export_policy_fixture(fixture or DEFAULT_EXPORT_POLICY_FIXTURE)
    if fixture_kind == "ar-review-required":
        return assert_arabic_review_required_fixture(fixture or DEFAULT_REVIEW_REQUIRED_FIXTURE)
    if fixture_kind == "ar-approved":
        return assert_arabic_approved_fixture(fixture or DEFAULT_ARABIC_APPROVED_FIXTURE)
    if fixture_kind == "he-review-required":
        return assert_hebrew_review_required_fixture(fixture or DEFAULT_HEBREW_REVIEW_REQUIRED_FIXTURE)
    if fixture_kind == "ml-review-required":
        return assert_malayalam_review_required_fixture(fixture or DEFAULT_MALAYALAM_REVIEW_REQUIRED_FIXTURE)
    if fixture_kind == "ml-approved":
        return assert_malayalam_approved_fixture(fixture or DEFAULT_MALAYALAM_APPROVED_FIXTURE)
    if fixture_kind == "de-article-case":
        return assert_german_article_case_fixture(fixture or DEFAULT_GERMAN_ARTICLE_CASE_FIXTURE)
    if fixture_kind == "hi-case-form":
        return assert_hindi_case_form_fixture(fixture or DEFAULT_HINDI_CASE_FORM_FIXTURE)
    if fixture_kind == "ru-case-form":
        return assert_russian_case_form_fixture(fixture or DEFAULT_RUSSIAN_CASE_FORM_FIXTURE)
    if fixture_kind == "sr-case-form":
        return assert_serbian_case_form_fixture(fixture or DEFAULT_SERBIAN_CASE_FORM_FIXTURE)
    if fixture_kind == "tr-suffix":
        return assert_turkish_suffix_fixture(fixture or DEFAULT_TURKISH_SUFFIX_FIXTURE)
    if fixture_kind == "tr-explicit-template":
        return assert_turkish_explicit_template_fixture(
            fixture or DEFAULT_TURKISH_EXPLICIT_TEMPLATE_FIXTURE
        )
    if fixture_kind == "tr-explicit-template-auto":
        return assert_turkish_explicit_template_auto_fixture(
            fixture or DEFAULT_TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE
        )
    raise ValueError(f"Unsupported fixture kind: {fixture_kind}")


def assert_all_fixtures() -> dict:
    return {fixture_kind: assert_fixture(fixture_kind) for fixture_kind in FIXTURE_KINDS}


def assert_export_policy_metadata_contract() -> None:
    metadata = read_metadata(
        json.dumps(
            {
                "schema": METADATA_SCHEMA,
                "exportPolicy": {
                    "runtimeExport": "closed-world-genitive-definiteness-explicit-forms",
                    "compositionMode": "explicit-form-rows-v0",
                    "deferredComposition": [
                        "article-selection",
                        "definiteness-suffix",
                        "genitive-suffix",
                    ],
                    "automaticExportTerms": 2,
                    "reviewRequiredTerms": 0,
                    "blockedTerms": 1,
                    "reviewRequiredReasons": {},
                    "blockedReasons": {"disabled-profile": 1},
                },
            }
        ).encode("utf-8")
    )
    export_policy = metadata["exportPolicy"]
    if export_policy["runtimeExport"] != "closed-world-genitive-definiteness-explicit-forms":
        raise AssertionError("M2IF exportPolicy runtime export was not preserved")
    if export_policy["blockedReasons"] != {"disabled-profile": 1}:
        raise AssertionError("M2IF exportPolicy reason counts were not preserved")

    invalid_metadata = dict(metadata)
    invalid_metadata["exportPolicy"] = dict(export_policy, blockedTerms=0)
    try:
        read_metadata(json.dumps(invalid_metadata).encode("utf-8"))
    except ValueError as error:
        if "blockedReasons require blockedTerms" not in str(error):
            raise
    else:
        raise AssertionError("Expected invalid M2IF exportPolicy reason counts to fail")

    invalid_metadata["exportPolicy"] = dict(export_policy, blockedReasons={"disabled-profile": 0})
    try:
        read_metadata(json.dumps(invalid_metadata).encode("utf-8"))
    except ValueError as error:
        if "requires positive reason counts" not in str(error):
            raise
    else:
        raise AssertionError("Expected zero M2IF exportPolicy reason counts to fail")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument(
        "--fixture-kind",
        choices=[*FIXTURE_KINDS, "all"],
        default="pt-agreement",
    )
    args = parser.parse_args()
    assert_export_policy_metadata_contract()

    if args.fixture_kind == "all":
        if args.fixture is not None:
            parser.error("--fixture cannot be used with --fixture-kind all")
        result = {
            "fixtureCount": len(FIXTURE_KINDS),
            "fixtures": assert_all_fixtures(),
        }
    else:
        result = assert_fixture(args.fixture_kind, args.fixture)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
