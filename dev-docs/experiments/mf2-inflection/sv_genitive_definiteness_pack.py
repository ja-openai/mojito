#!/usr/bin/env python3
"""Generate a small Swedish genitive/definiteness compiled term-pack fixture."""

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


GENERATOR = "dev-docs/experiments/mf2-inflection/sv_genitive_definiteness_pack.py"
SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0"
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"neuter": 3, "common": 5}
NUMBER_BITS = {"singular": 1, "plural": 2}
TERM_SPECS = (
    {
        "id": "sv.definiteness.bostad",
        "text": "bostad",
        "sense": "inflection=290",
        "inflection": "290",
        "gender": "common",
        "forms": (
            ("indefinite.nominative.singular", "bostad", "nominative", "indefinite", "singular"),
            ("indefinite.nominative.plural", "bostäder", "nominative", "indefinite", "plural"),
            ("definite.nominative.singular", "bostaden", "nominative", "definite", "singular"),
            ("definite.nominative.plural", "bostäderna", "nominative", "definite", "plural"),
            ("indefinite.genitive.singular", "bostads", "genitive", "indefinite", "singular"),
            ("indefinite.genitive.plural", "bostäders", "genitive", "indefinite", "plural"),
            ("definite.genitive.singular", "bostadens", "genitive", "definite", "singular"),
            ("definite.genitive.plural", "bostädernas", "genitive", "definite", "plural"),
        ),
    },
    {
        "id": "sv.definiteness.chassi",
        "text": "chassi",
        "sense": "inflection=2b1",
        "inflection": "2b1",
        "gender": "neuter",
        "forms": (
            ("indefinite.nominative.singular", "chassi", "nominative", "indefinite", "singular"),
            ("indefinite.nominative.plural", "chassier", "nominative", "indefinite", "plural"),
            ("definite.nominative.singular", "chassit", "nominative", "definite", "singular"),
            ("definite.nominative.plural", "chassierna", "nominative", "definite", "plural"),
            ("indefinite.genitive.singular", "chassis", "genitive", "indefinite", "singular"),
            ("indefinite.genitive.plural", "chassiers", "genitive", "indefinite", "plural"),
            ("definite.genitive.singular", "chassits", "genitive", "definite", "singular"),
            ("definite.genitive.plural", "chassiernas", "genitive", "definite", "plural"),
        ),
    },
)


@dataclass(frozen=True)
class FormSpec:
    key: str
    surface: str
    grammatical_case: str
    definiteness: str
    number: str


def required_form_keys() -> list[str]:
    return [
        f"{definiteness}.{grammatical_case}.{number}"
        for definiteness in ("indefinite", "definite")
        for grammatical_case in ("nominative", "genitive")
        for number in ("singular", "plural")
    ] + ["bare.singular", "bare.plural"]


def entry_values(entry: DictionaryEntry, allowed: set[str]) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in allowed}


def entry_cases(entry: DictionaryEntry) -> set[str]:
    return entry_values(entry, {"genitive", "nominative"})


def entry_definiteness(entry: DictionaryEntry) -> set[str]:
    return entry_values(entry, {"definite", "indefinite"})


def entry_numbers(entry: DictionaryEntry) -> set[str]:
    return entry_values(entry, {"plural", "singular"})


def entry_genders(entry: DictionaryEntry) -> set[str]:
    return entry_values(entry, {"common", "neuter"})


def entry_pos(entry: DictionaryEntry) -> set[str]:
    return entry_values(entry, {"noun", "proper-noun"})


def matching_entry(
    entries: list[DictionaryEntry], term_spec: dict, form_spec: FormSpec
) -> DictionaryEntry:
    matches = [
        entry
        for entry in entries
        if entry.surface == form_spec.surface
        and entry.inflections == (term_spec["inflection"],)
        and form_spec.grammatical_case in entry_cases(entry)
        and form_spec.definiteness in entry_definiteness(entry)
        and form_spec.number in entry_numbers(entry)
        and entry_genders(entry) == {term_spec["gender"]}
        and entry_pos(entry) == {"noun"}
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one Swedish dictionary row for {form_spec}, got {len(matches)}")
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


def feature_bits(term_spec: dict) -> int:
    bits = PART_OF_SPEECH_BITS["noun"]
    bits |= GENDER_BITS[term_spec["gender"]] << 4
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


def export_policy() -> dict:
    return {
        "runtimeExport": "closed-world-genitive-definiteness-explicit-forms",
        "compositionMode": "explicit-form-rows-v0",
        "automaticExportTerms": len(TERM_SPECS),
        "reviewRequiredTerms": 0,
        "blockedTerms": 0,
        "reviewRequiredReasons": {},
        "blockedReasons": {},
        "deferredComposition": [
            "article-selection",
            "definiteness-suffix",
            "genitive-suffix",
        ],
    }


def update_json_bytes(payload: dict) -> None:
    payload["sizeEstimates"]["compactJsonBytes"] = 0
    payload["sizeEstimates"]["compactJsonBytes"] = len(
        (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    )


def form_specs(term_spec: dict) -> list[FormSpec]:
    specs = [
        FormSpec(
            key=key,
            surface=surface,
            grammatical_case=grammatical_case,
            definiteness=definiteness,
            number=number,
        )
        for key, surface, grammatical_case, definiteness, number in term_spec["forms"]
    ]
    nominative_indefinite = {
        spec.number: spec for spec in specs if spec.key.startswith("indefinite.nominative.")
    }
    specs.append(
        FormSpec(
            "bare.singular",
            nominative_indefinite["singular"].surface,
            "nominative",
            "indefinite",
            "singular",
        )
    )
    specs.append(
        FormSpec(
            "bare.plural",
            nominative_indefinite["plural"].surface,
            "nominative",
            "indefinite",
            "plural",
        )
    )
    return specs


def build_pack(dictionary: Path) -> dict:
    entries, _ = parse_dictionary(dictionary)
    pool = StringPool()
    terms = []
    form_sets = []
    generation_terms = []

    for term_spec in TERM_SPECS:
        specs = form_specs(term_spec)
        source_entries = [matching_entry(entries, term_spec, spec) for spec in specs]
        term_index = pool.add(term_spec["id"])
        text_index = pool.add(term_spec["text"])
        sense_index = pool.add(term_spec["sense"])
        form_set_index = len(form_sets)
        form_set = {
            "term": term_index,
            "forms": [
                {
                    "key": pool.add(spec.key),
                    "value": pool.add(entry.surface),
                    "kind": "literal",
                }
                for spec, entry in zip(specs, source_entries, strict=True)
            ],
        }
        terms.append(
            {
                "id": term_index,
                "text": text_index,
                "featureBits": feature_bits(term_spec),
                "sense": sense_index,
                "formSet": form_set_index,
            }
        )
        form_sets.append(form_set)
        generation_terms.append(
            {
                "termId": term_spec["id"],
                "gender": term_spec["gender"],
                "sourceRows": [entry.line for entry in source_entries],
                "sourceRowsByFormKey": {
                    spec.key: entry.line
                    for spec, entry in zip(specs, source_entries, strict=True)
                },
            }
        )

    strings = pool.values()
    payload = {
        "schema": SCHEMA,
        "locale": "sv",
        "provenance": provenance(dictionary),
        "strings": strings,
        "terms": terms,
        "formSets": form_sets,
        "diagnostics": [],
        "generationSummary": {
            "policy": "closed-world genitive/definiteness explicit-form pack",
            "exportPolicy": export_policy(),
            "candidateTerms": len(TERM_SPECS),
            "exportedTerms": len(TERM_SPECS),
            "requiredFormRowsPerTerm": len(required_form_keys()),
            "formRows": sum(len(form_set["forms"]) for form_set in form_sets),
            "exportedFormKeys": required_form_keys(),
            "missingFormKeys": [],
            "reviewDiagnostics": [],
            "terms": generation_terms,
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
