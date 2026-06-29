#!/usr/bin/env python3
"""Generate a small Dutch noun metadata and diminutive fixture."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import (
    SOURCE_LICENSE,
    UNICODE_DICTIONARY_RESOURCE_PREFIX,
    pack_source_metadata,
)


GENERATOR = "dev-docs/experiments/mf2-inflection/nl_noun_metadata_pack.py"
SCHEMA = "mojito-mf2-inflection/nl-noun-metadata-pack/v0"
DEFAULT_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_nl.lst")

PART_OF_SPEECH_VALUES = {"noun", "proper-noun"}
GENDER_VALUES = {"feminine", "masculine", "neuter"}
NUMBER_VALUES = {"plural", "singular"}

PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1 << 4, "feminine": 1 << 5, "neuter": 1 << 6}
NUMBER_BITS = {"singular": 1 << 8, "plural": 1 << 9}
DIMINUTIVE_BIT = 1 << 12

EXPORTED_SURFACES = (
    "boek",
    "boeken",
    "boekje",
    "boekjes",
    "huis",
    "huizen",
    "huisje",
    "huisjes",
    "man",
    "mannen",
    "mannetje",
    "mannetjes",
    "kind",
    "kinderen",
    "kindje",
    "kindjes",
    "vrouw",
    "vrouwen",
    "vrouwtje",
    "vrouwtjes",
)
ROW_WIDTH_BYTES = 12


class StringPool:
    def __init__(self) -> None:
        self._strings: list[str] = []
        self._index: dict[str, int] = {}

    def add(self, value: str) -> int:
        existing = self._index.get(value)
        if existing is not None:
            return existing
        index = len(self._strings)
        self._strings.append(value)
        self._index[value] = index
        return index

    def values(self) -> list[str]:
        return list(self._strings)


def entry_values(entry: DictionaryEntry, allowed: set[str]) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in allowed)


def entry_part_of_speech(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, PART_OF_SPEECH_VALUES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, NUMBER_VALUES)


def is_diminutive(entry: DictionaryEntry) -> bool:
    return "diminutive" in entry.grammemes or "sizeness=diminutive" in entry.grammemes


def term_entry(entry: DictionaryEntry) -> bool:
    return bool(entry_part_of_speech(entry))


def metadata_candidate(entry: DictionaryEntry) -> bool:
    return term_entry(entry) and bool(entry_genders(entry)) and bool(entry_numbers(entry))


def feature_bits(entry: DictionaryEntry) -> int:
    bits = 0
    for part_of_speech in entry_part_of_speech(entry):
        bits |= PART_OF_SPEECH_BITS[part_of_speech]
    for gender in entry_genders(entry):
        bits |= GENDER_BITS[gender]
    for number in entry_numbers(entry):
        bits |= NUMBER_BITS[number]
    if is_diminutive(entry):
        bits |= DIMINUTIVE_BIT
    return bits


def review_diagnostics(entry: DictionaryEntry) -> list[str]:
    diagnostics = []
    values = {
        "part-of-speech": entry_part_of_speech(entry),
        "gender": entry_genders(entry),
        "number": entry_numbers(entry),
    }
    multiple_diagnostic = {
        "part-of-speech": "multiple-parts-of-speech",
        "gender": "multiple-genders",
        "number": "multiple-numbers",
    }
    for name, entry_values_for_name in values.items():
        if not entry_values_for_name:
            diagnostics.append(f"missing-{name}")
        elif len(entry_values_for_name) > 1:
            diagnostics.append(multiple_diagnostic[name])
    if not entry.inflections:
        diagnostics.append("missing-inflection")
    elif len(entry.inflections) > 1:
        diagnostics.append("multiple-inflections")
    return diagnostics


def count_values(entries: list[DictionaryEntry], extractor) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for entry in entries:
        values = extractor(entry)
        if values:
            counter.update(values)
        else:
            counter["<missing>"] += 1
    return dict(sorted(counter.items()))


def stable_source(path: Path) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    metadata["path"] = f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{path.name}"
    return metadata


def provenance(dictionary: Path) -> dict:
    source_label = f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}"
    return {
        "license": SOURCE_LICENSE,
        "generator": GENERATOR,
        "sourceLabels": [source_label],
        "sources": [stable_source(dictionary)],
    }


def matching_entry(entries: list[DictionaryEntry], surface: str) -> DictionaryEntry:
    matches = [entry for entry in entries if entry.surface == surface and metadata_candidate(entry)]
    if len(matches) != 1:
        raise ValueError(f"Expected one Dutch metadata row for {surface}, got {len(matches)}")
    return matches[0]


def size_estimate(strings: list[str], rows: int) -> dict:
    estimate = {
        "stringPoolBytes": sum(len(value.encode("utf-8")) + 1 for value in strings),
        "rowBytes": rows * ROW_WIDTH_BYTES,
    }
    estimate["binaryLowerBoundBytes"] = estimate["stringPoolBytes"] + estimate["rowBytes"]
    return estimate


def update_json_bytes(payload: dict) -> None:
    previous = -1
    while True:
        current = len(
            (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode(
                "utf-8"
            )
        )
        if current == previous:
            break
        payload["sizeEstimates"]["sampleMetadataPack"]["jsonBytes"] = current
        previous = current


def build_pack(dictionary: Path) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    term_entries = [entry for entry in entries if term_entry(entry)]
    metadata_entries = [entry for entry in entries if metadata_candidate(entry)]
    diminutive_entries = [entry for entry in term_entries if is_diminutive(entry)]
    metadata_diminutive_entries = [entry for entry in metadata_entries if is_diminutive(entry)]
    pool = StringPool()
    rows = []

    for surface in EXPORTED_SURFACES:
        entry = matching_entry(entries, surface)
        rows.append(
            {
                "surface": pool.add(entry.surface),
                "sourceRow": entry.line,
                "featureBits": feature_bits(entry),
                "partOfSpeech": list(entry_part_of_speech(entry)),
                "gender": list(entry_genders(entry)),
                "number": list(entry_numbers(entry)),
                "diminutive": is_diminutive(entry),
                "inflectionPatterns": list(entry.inflections),
                "reviewDiagnostics": review_diagnostics(entry),
            }
        )

    strings = pool.values()
    review_rows = sum(1 for row in rows if row["reviewDiagnostics"])
    full_strings = sorted({entry.normalized_surface for entry in metadata_entries})
    payload = {
        "schema": SCHEMA,
        "locale": "nl",
        "description": "Generated Dutch noun metadata fixture for gender, number, and diminutive validation; not a case or definiteness renderer.",
        "provenance": provenance(dictionary),
        "strings": strings,
        "rows": rows,
        "generationSummary": {
            "dictionaryEntries": len(entries),
            "skippedDictionaryLines": len(skipped),
            "termRows": len(term_entries),
            "metadataCandidateRows": len(metadata_entries),
            "metadataCandidateSurfaces": len(full_strings),
            "diminutiveRows": len(diminutive_entries),
            "metadataDiminutiveRows": len(metadata_diminutive_entries),
            "exportedRows": len(rows),
            "exportedDiminutiveRows": sum(1 for row in rows if row["diminutive"]),
            "reviewDiagnosticRows": review_rows,
            "caseTaggedTermRows": sum(
                1 for entry in term_entries if "genitive" in entry.grammemes or "nominative" in entry.grammemes
            ),
            "definitenessTaggedTermRows": sum(
                1 for entry in term_entries if "definite" in entry.grammemes or "indefinite" in entry.grammemes
            ),
            "multiGenderRows": sum(1 for entry in metadata_entries if len(entry_genders(entry)) > 1),
            "multiNumberRows": sum(1 for entry in metadata_entries if len(entry_numbers(entry)) > 1),
        },
        "features": {
            "partOfSpeech": count_values(metadata_entries, entry_part_of_speech),
            "gender": count_values(metadata_entries, entry_genders),
            "number": count_values(metadata_entries, entry_numbers),
            "diminutive": {
                "false": len(metadata_entries) - len(metadata_diminutive_entries),
                "true": len(metadata_diminutive_entries),
            },
        },
        "sizeEstimates": {
            "sampleMetadataPack": size_estimate(strings, len(rows)) | {"jsonBytes": 0},
            "fullMetadataPack": size_estimate(full_strings, len(metadata_entries)),
        },
    }
    update_json_bytes(payload)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, default=DEFAULT_DICTIONARY)
    parser.add_argument("--out", type=Path, help="Write JSON fixture to this path.")
    args = parser.parse_args()

    payload = json.dumps(build_pack(args.dictionary), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
