#!/usr/bin/env python3
"""Generate a small Hebrew construct-state compiled term-pack fixture."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import (
    SOURCE_LICENSE,
    UNICODE_DICTIONARY_RESOURCE_PREFIX,
    pack_source_metadata,
)


GENERATOR = "dev-docs/experiments/mf2-inflection/he_construct_form_pack.py"
SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0"
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2}
NUMBER_BITS = {"singular": 1, "plural": 2, "dual": 4}
TERM_ID = "he.construct.house"
TERM_TEXT = "בית"
TERM_SENSE = "inflection=95"
REQUIRED_FORM_KEYS = ("bare.singular", "bare.plural", "construct.singular", "construct.plural", "construct.dual")


@dataclass(frozen=True)
class FormSpec:
    key: str
    surface: str
    number: str
    definiteness: str | None


FORM_SPECS = (
    FormSpec("bare.singular", "בית", "singular", None),
    FormSpec("bare.plural", "בתים", "plural", None),
    FormSpec("construct.singular", "בית", "singular", "construct"),
    FormSpec("construct.plural", "בתי", "plural", "construct"),
)


def entry_numbers(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"dual", "plural", "singular"}}


def entry_definiteness(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"construct", "definite", "indefinite"}}


def entry_genders(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"feminine", "masculine"}}


def entry_pos(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"noun", "proper-noun"}}


def matching_entry(entries: list[DictionaryEntry], spec: FormSpec) -> DictionaryEntry:
    matches = [
        entry
        for entry in entries
        if entry.surface == spec.surface
        and entry.inflections == ("95",)
        and spec.number in entry_numbers(entry)
        and (spec.definiteness is None or spec.definiteness in entry_definiteness(entry))
        and entry_genders(entry) == {"masculine"}
        and entry_pos(entry) == {"noun"}
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one Hebrew dictionary row for {spec}, got {len(matches)}")
    return matches[0]


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


def feature_bits() -> int:
    bits = PART_OF_SPEECH_BITS["noun"]
    bits |= GENDER_BITS["masculine"] << 4
    bits |= NUMBER_BITS["singular"] << 8
    return bits


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
        "sources": [stable_source(dictionary)],
        "sourceLabels": [source_label],
    }


def binary_size(strings: list[str], terms: list[dict], form_sets: list[dict]) -> dict:
    form_rows = sum(len(form_set["forms"]) for form_set in form_sets)
    estimate = {
        "stringPoolBytes": sum(len(value.encode("utf-8")) + 1 for value in strings),
        "termRowBytes": len(terms) * 20,
        "formRowBytes": form_rows * 12,
        "bindingReferenceBytes": 0,
    }
    estimate["totalBytes"] = sum(estimate.values())
    return estimate


def update_json_bytes(payload: dict) -> None:
    payload["sizeEstimates"]["compactJsonBytes"] = 0
    payload["sizeEstimates"]["compactJsonBytes"] = len(
        (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    )


def export_policy(missing_form_keys: list[str]) -> dict:
    review_required_terms = 1 if missing_form_keys else 0
    return {
        "runtimeExport": "closed-world-construct-state-explicit-forms",
        "compositionMode": "explicit-form-rows-v0",
        "deferredComposition": [],
        "automaticExportTerms": 0 if missing_form_keys else 1,
        "reviewRequiredTerms": review_required_terms,
        "blockedTerms": 0,
        "reviewRequiredReasons": {"missing-form-cell": review_required_terms} if missing_form_keys else {},
        "blockedReasons": {},
    }


def build_pack(dictionary: Path) -> dict:
    entries, _ = parse_dictionary(dictionary)
    source_entries = [matching_entry(entries, spec) for spec in FORM_SPECS]
    exported_form_keys = [spec.key for spec in FORM_SPECS]
    missing_form_keys = [
        form_key for form_key in REQUIRED_FORM_KEYS if form_key not in exported_form_keys
    ]
    pool = StringPool()
    term_index = pool.add(TERM_ID)
    text_index = pool.add(TERM_TEXT)
    sense_index = pool.add(TERM_SENSE)
    form_set = {
        "term": term_index,
        "forms": [
            {
                "key": pool.add(spec.key),
                "value": pool.add(entry.surface),
                "kind": "literal",
            }
            for spec, entry in zip(FORM_SPECS, source_entries, strict=True)
        ],
    }
    terms = [
        {
            "id": term_index,
            "text": text_index,
            "featureBits": feature_bits(),
            "sense": sense_index,
            "formSet": 0,
        }
    ]
    form_sets = [form_set]
    strings = pool.values()
    payload = {
        "schema": SCHEMA,
        "locale": "he",
        "provenance": provenance(dictionary),
        "strings": strings,
        "terms": terms,
        "formSets": form_sets,
        "diagnostics": [],
        "generationSummary": {
            "policy": "closed-world construct-state explicit-form pack",
            "exportPolicy": export_policy(missing_form_keys),
            "candidateTerms": 1,
            "exportedTerms": 1,
            "requiredFormRows": len(REQUIRED_FORM_KEYS),
            "formRows": len(FORM_SPECS),
            "exportedFormKeys": exported_form_keys,
            "missingFormKeys": missing_form_keys,
            "reviewDiagnostics": [
                {
                    "termId": TERM_ID,
                    "reason": "missing-form-cell",
                    "formKey": form_key,
                }
                for form_key in missing_form_keys
            ],
            "sourceRows": [entry.line for entry in source_entries],
            "sourceRowsByFormKey": {
                spec.key: entry.line for spec, entry in zip(FORM_SPECS, source_entries, strict=True)
            },
        },
        "sizeEstimates": {
            "compactJsonBytes": 0,
            "binaryLowerBoundBytes": binary_size(strings, terms, form_sets),
        },
    }
    update_json_bytes(payload)
    return payload


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    write_json(args.out, build_pack(args.dictionary))


if __name__ == "__main__":
    main()
