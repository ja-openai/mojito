#!/usr/bin/env python3
"""Generate a small Malayalam multi-case compiled term-pack fixture."""

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


GENERATOR = "dev-docs/experiments/mf2-inflection/ml_case_form_pack.py"
SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0"
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2, "neuter": 3}
NUMBER_BITS = {"singular": 1, "plural": 2}
CASE_VALUES = (
    "nominative",
    "accusative",
    "dative",
    "genitive",
    "instrumental",
    "locative",
    "sociative",
    "vocative",
)
NUMBER_VALUES = ("singular", "plural")


@dataclass(frozen=True)
class FormSpec:
    key: str
    surface: str
    grammatical_case: str
    number: str


@dataclass(frozen=True)
class TermSpec:
    term_id: str
    text: str
    sense: str
    inflection: str
    gender: str
    form_specs: tuple[FormSpec, ...]


TERM_SPECS = {
    "review-required": TermSpec(
        term_id="ml.case.disciple",
        text="ശിഷ്യൻ",
        sense="inflection=110",
        inflection="110",
        gender="masculine",
        form_specs=(
            FormSpec("nominative.singular", "ശിഷ്യൻ", "nominative", "singular"),
            FormSpec("accusative.singular", "ശിഷ്യനെ", "accusative", "singular"),
            FormSpec("dative.singular", "ശിഷ്യന്", "dative", "singular"),
            FormSpec("genitive.singular", "ശിഷ്യന്റെ", "genitive", "singular"),
            FormSpec("instrumental.singular", "ശിഷ്യനാൽ", "instrumental", "singular"),
            FormSpec("locative.singular", "ശിഷ്യനിൽ", "locative", "singular"),
            FormSpec("sociative.singular", "ശിഷ്യനോട്", "sociative", "singular"),
            FormSpec("nominative.plural", "ശിഷ്യന്മാർ", "nominative", "plural"),
            FormSpec("accusative.plural", "ശിഷ്യന്മാരെ", "accusative", "plural"),
            FormSpec("dative.plural", "ശിഷ്യന്മാർക്ക്", "dative", "plural"),
            FormSpec("genitive.plural", "ശിഷ്യന്മാരുടെ", "genitive", "plural"),
            FormSpec("instrumental.plural", "ശിഷ്യന്മാരാൽ", "instrumental", "plural"),
            FormSpec("locative.plural", "ശിഷ്യന്മാരിൽ", "locative", "plural"),
            FormSpec("sociative.plural", "ശിഷ്യന്മാരോട്", "sociative", "plural"),
        ),
    ),
    "approved": TermSpec(
        term_id="ml.case.father",
        text="പിതാവ്",
        sense="inflection=cb",
        inflection="cb",
        gender="masculine",
        form_specs=(
            FormSpec("nominative.singular", "പിതാവ്", "nominative", "singular"),
            FormSpec("nominative.plural", "പിതാക്കന്മാർ", "nominative", "plural"),
            FormSpec("accusative.singular", "പിതാവിനെ", "accusative", "singular"),
            FormSpec("accusative.plural", "പിതാവുകളെ", "accusative", "plural"),
            FormSpec("dative.singular", "പിതാവിനു്", "dative", "singular"),
            FormSpec("dative.plural", "പിതാവുകൾക്കു്", "dative", "plural"),
            FormSpec("genitive.singular", "പിതാവിന്റെ", "genitive", "singular"),
            FormSpec("genitive.plural", "പിതാവുകളുടെ", "genitive", "plural"),
            FormSpec("instrumental.singular", "പിതാവാൽ", "instrumental", "singular"),
            FormSpec("instrumental.plural", "പിതാവുകളാൽ", "instrumental", "plural"),
            FormSpec("locative.singular", "പിതാവിങ്കൽ", "locative", "singular"),
            FormSpec("locative.plural", "പിതാവുകളിങ്കൽ", "locative", "plural"),
            FormSpec("sociative.singular", "പിതാവിനോടു്", "sociative", "singular"),
            FormSpec("sociative.plural", "പിതാക്കന്മാരോട്", "sociative", "plural"),
            FormSpec("vocative.singular", "പിതാവേ", "vocative", "singular"),
            FormSpec("vocative.plural", "പിതാക്കന്മാരേ", "vocative", "plural"),
        ),
    ),
}


def required_form_keys() -> list[str]:
    return [f"{grammatical_case}.{number}" for grammatical_case in CASE_VALUES for number in NUMBER_VALUES]


def entry_cases(entry: DictionaryEntry) -> set[str]:
    return {
        grammeme
        for grammeme in entry.grammemes
        if grammeme
        in {
            "accusative",
            "dative",
            "genitive",
            "instrumental",
            "locative",
            "nominative",
            "sociative",
            "vocative",
        }
    }


def entry_numbers(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"plural", "singular"}}


def entry_genders(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"feminine", "masculine", "neuter"}}


def entry_pos(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"noun", "proper-noun"}}


def matching_entry(entries: list[DictionaryEntry], term_spec: TermSpec, spec: FormSpec) -> DictionaryEntry:
    matches = [
        entry
        for entry in entries
        if entry.surface == spec.surface
        and entry.inflections == (term_spec.inflection,)
        and spec.grammatical_case in entry_cases(entry)
        and spec.number in entry_numbers(entry)
        and entry_genders(entry) == {term_spec.gender}
        and entry_pos(entry) == {"noun"}
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one Malayalam dictionary row for {spec}, got {len(matches)}")
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


def feature_bits(gender: str) -> int:
    bits = PART_OF_SPEECH_BITS["noun"]
    bits |= GENDER_BITS[gender] << 4
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
        "runtimeExport": "closed-world-multi-case-explicit-forms",
        "compositionMode": "explicit-form-rows-v0",
        "deferredComposition": [],
        "automaticExportTerms": 0 if missing_form_keys else 1,
        "reviewRequiredTerms": review_required_terms,
        "blockedTerms": 0,
        "reviewRequiredReasons": {"missing-form-cell": review_required_terms} if missing_form_keys else {},
        "blockedReasons": {},
    }


def build_pack(dictionary: Path, fixture: str = "review-required") -> dict:
    entries, _ = parse_dictionary(dictionary)
    term_spec = TERM_SPECS[fixture]
    source_entries = [matching_entry(entries, term_spec, spec) for spec in term_spec.form_specs]
    exported_form_keys = [spec.key for spec in term_spec.form_specs]
    missing_form_keys = [
        form_key for form_key in required_form_keys() if form_key not in exported_form_keys
    ]
    pool = StringPool()
    term_index = pool.add(term_spec.term_id)
    text_index = pool.add(term_spec.text)
    sense_index = pool.add(term_spec.sense)
    form_set = {
        "term": term_index,
        "forms": [
            {
                "key": pool.add(spec.key),
                "value": pool.add(entry.surface),
                "kind": "literal",
            }
            for spec, entry in zip(term_spec.form_specs, source_entries, strict=True)
        ],
    }
    terms = [
        {
            "id": term_index,
            "text": text_index,
            "featureBits": feature_bits(term_spec.gender),
            "sense": sense_index,
            "formSet": 0,
        }
    ]
    form_sets = [form_set]
    strings = pool.values()
    payload = {
        "schema": SCHEMA,
        "locale": "ml",
        "provenance": provenance(dictionary),
        "strings": strings,
        "terms": terms,
        "formSets": form_sets,
        "diagnostics": [],
        "generationSummary": {
            "policy": "closed-world multi-case explicit-form pack",
            "exportPolicy": export_policy(missing_form_keys),
            "candidateTerms": 1,
            "exportedTerms": 1,
            "requiredFormRows": len(required_form_keys()),
            "formRows": len(term_spec.form_specs),
            "exportedFormKeys": exported_form_keys,
            "missingFormKeys": missing_form_keys,
            "reviewDiagnostics": [
                {
                    "termId": term_spec.term_id,
                    "reason": "missing-form-cell",
                    "formKey": form_key,
                }
                for form_key in missing_form_keys
            ],
            "sourceRows": [entry.line for entry in source_entries],
            "sourceRowsByFormKey": {
                spec.key: entry.line for spec, entry in zip(term_spec.form_specs, source_entries, strict=True)
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
    parser.add_argument(
        "--fixture",
        choices=sorted(TERM_SPECS),
        default="review-required",
        help="Select the deterministic Malayalam term fixture to emit.",
    )
    args = parser.parse_args()

    write_json(args.out, build_pack(args.dictionary, args.fixture))


if __name__ == "__main__":
    main()
