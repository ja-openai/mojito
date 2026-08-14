#!/usr/bin/env python3
"""Encode compiled term-pack JSON fixtures into pinned M2IF hex fixtures."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path


MAGIC = b"M2IF"
VERSION = 0
FLAGS = 0
NULL_INDEX = -1
HEADER_BYTES = 88
SECTION_COUNT = 7
TERM_ROW_BYTES = 20
FORM_SET_ROW_BYTES = 12
FORM_ROW_BYTES = 12
FORM_ROW_PATTERN_FLAG = 1
METADATA_SCHEMA = "mojito-mf2-inflection/compiled-term-pack-metadata/v0"


def align4(value: int) -> int:
    return (value + 3) & ~3


def build_string_table(strings: list[str]) -> tuple[bytes, list[int]]:
    payload = bytearray()
    offsets = []
    for value in strings:
        offsets.append(len(payload))
        payload.extend(value.encode("utf-8"))
        payload.append(0)
    offsets.append(len(payload))
    return bytes(payload), offsets


def locale_index(strings: list[str], locale: str | None) -> int:
    if locale is None:
        return NULL_INDEX
    try:
        return strings.index(locale)
    except ValueError:
        strings.append(locale)
        return len(strings) - 1


def export_policy(pack: dict) -> dict:
    top_level = pack.get("exportPolicy")
    if isinstance(top_level, dict) and top_level:
        return top_level
    generation_summary = pack.get("generationSummary")
    if isinstance(generation_summary, dict):
        value = generation_summary.get("exportPolicy")
        if isinstance(value, dict):
            return value
    return {}


def provenance_metadata(provenance: dict) -> dict:
    payload = {}
    if provenance.get("license") is not None:
        payload["license"] = provenance["license"]
    if provenance.get("generator") is not None:
        payload["generator"] = provenance["generator"]
    payload["sourceLabels"] = provenance.get("sourceLabels", [])
    sources = []
    for source in provenance.get("sources", []):
        sources.append(
            {
                "path": source["path"],
                "byteSize": source["byteSize"],
                "sha256": source["sha256"],
                "gitLfsPointer": source["gitLfsPointer"],
            }
        )
    payload["sources"] = sources
    return payload


def metadata_bytes(pack: dict) -> bytes:
    provenance = pack.get("provenance") or {}
    policy = export_policy(pack)
    if not provenance and not policy:
        return b""

    metadata = {"schema": METADATA_SCHEMA}
    if provenance:
        metadata["provenance"] = provenance_metadata(provenance)
    if policy:
        metadata["exportPolicy"] = {
            "runtimeExport": policy["runtimeExport"],
            "compositionMode": policy["compositionMode"],
            "deferredComposition": policy["deferredComposition"],
            "automaticExportTerms": policy["automaticExportTerms"],
            "reviewRequiredTerms": policy["reviewRequiredTerms"],
            "blockedTerms": policy["blockedTerms"],
            "reviewRequiredReasons": policy["reviewRequiredReasons"],
            "blockedReasons": policy["blockedReasons"],
        }
    return json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def section_offsets(section_lengths: list[int]) -> list[int]:
    offsets = []
    offset = HEADER_BYTES
    for section_length in section_lengths:
        offset = align4(offset)
        offsets.append(offset)
        offset += section_length
    return offsets


def encode(pack: dict) -> bytes:
    strings = list(pack["strings"])
    locale = locale_index(strings, pack.get("locale"))
    string_bytes, string_offsets = build_string_table(strings)

    terms = pack["terms"]
    form_rows = []
    form_set_rows = []
    for form_set in pack["formSets"]:
        first_form_row = len(form_rows)
        form_rows.extend(form_set["forms"])
        form_set_rows.append((form_set["term"], first_form_row, len(form_set["forms"])))

    sections = [
        string_bytes,
        b"".join(struct.pack("<i", offset) for offset in string_offsets),
        b"".join(
            struct.pack(
                "<iiiii",
                term["id"],
                term["text"],
                term["featureBits"],
                NULL_INDEX if term.get("sense") is None else term["sense"],
                term["formSet"],
            )
            for term in terms
        ),
        b"".join(struct.pack("<iii", *row) for row in form_set_rows),
        b"".join(
            struct.pack(
                "<iii",
                form["key"],
                form["value"],
                FORM_ROW_PATTERN_FLAG if form.get("kind") == "pattern" else 0,
            )
            for form in form_rows
        ),
        b"",
        metadata_bytes(pack),
    ]
    lengths = [len(section) for section in sections]
    offsets = section_offsets(lengths)
    payload = bytearray(offsets[-1] + lengths[-1])
    struct.pack_into(
        "<4sHHiiiiii",
        payload,
        0,
        MAGIC,
        VERSION,
        FLAGS,
        locale,
        len(strings),
        len(terms),
        len(form_set_rows),
        len(form_rows),
        0,
    )
    for index, (offset, length) in enumerate(zip(offsets, lengths, strict=True)):
        struct.pack_into("<ii", payload, 32 + index * 8, offset, length)
    for offset, section in zip(offsets, sections, strict=True):
        payload[offset : offset + len(section)] = section
    return bytes(payload)


def write_hex(path: Path, payload: bytes) -> None:
    hex_payload = payload.hex()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(hex_payload[index : index + 96] for index in range(0, len(hex_payload), 96))
        + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    pack = json.loads(args.json.read_text(encoding="utf-8"))
    write_hex(args.out, encode(pack))


if __name__ == "__main__":
    main()
