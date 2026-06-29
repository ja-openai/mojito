#!/usr/bin/env python3
"""Generate a small Arabic explicit-form compiled term-pack fixture."""

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


GENERATOR = "dev-docs/experiments/mf2-inflection/ar_explicit_form_pack.py"
SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0"
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2}
NUMBER_BITS = {"singular": 1, "plural": 2, "dual": 4}
TERM_ID = "ar.explicit.mother"
TERM_TEXT = "أم"
TERM_SENSE = "inflection=c2"
DEFINITENESS_VALUES = ("indefinite", "construct")
CASE_VALUES = ("nominative", "accusative", "genitive")
NUMBER_VALUES = ("singular", "dual", "plural")


@dataclass(frozen=True)
class FormSpec:
    key: str
    surface: str
    grammatical_case: str
    number: str
    definiteness: str


@dataclass(frozen=True)
class TermSpec:
    term_id: str
    text: str
    sense: str
    inflection: str
    gender: str
    form_specs: tuple[FormSpec, ...]


REVIEW_REQUIRED_FORM_SPECS = (
    FormSpec("indefinite.nominative.singular", "أُمٌّ", "nominative", "singular", "indefinite"),
    FormSpec("indefinite.accusative.singular", "أُمًّا", "accusative", "singular", "indefinite"),
    FormSpec("indefinite.genitive.singular", "أُمٍّ", "genitive", "singular", "indefinite"),
    FormSpec("construct.nominative.singular", "أُمُّ", "nominative", "singular", "construct"),
    FormSpec("construct.accusative.singular", "أُمَّ", "accusative", "singular", "construct"),
    FormSpec("construct.genitive.singular", "أُمِّ", "genitive", "singular", "construct"),
    FormSpec("indefinite.nominative.dual", "أُمَّانِ", "nominative", "dual", "indefinite"),
    FormSpec("indefinite.accusative.dual", "أُمَّيْنِ", "accusative", "dual", "indefinite"),
    FormSpec("construct.nominative.dual", "أُمَّا", "nominative", "dual", "construct"),
    FormSpec("construct.accusative.dual", "أُمَّيْ", "accusative", "dual", "construct"),
    FormSpec("indefinite.nominative.plural", "أُمَّهَٰتٌ", "nominative", "plural", "indefinite"),
    FormSpec("indefinite.accusative.plural", "أُمَّهَٰتٍ", "accusative", "plural", "indefinite"),
    FormSpec("construct.nominative.plural", "أُمَّهَٰتُ", "nominative", "plural", "construct"),
    FormSpec("construct.accusative.plural", "أُمَّهَٰتِ", "accusative", "plural", "construct"),
)


APPROVED_FORM_SPECS = (
    FormSpec("indefinite.nominative.singular", "رسالة", "nominative", "singular", "indefinite"),
    FormSpec("indefinite.accusative.singular", "رسالة", "accusative", "singular", "indefinite"),
    FormSpec("indefinite.genitive.singular", "رسالة", "genitive", "singular", "indefinite"),
    FormSpec("construct.nominative.singular", "رسالة", "nominative", "singular", "construct"),
    FormSpec("construct.accusative.singular", "رسالة", "accusative", "singular", "construct"),
    FormSpec("construct.genitive.singular", "رسالة", "genitive", "singular", "construct"),
    FormSpec("indefinite.nominative.dual", "رسالتان", "nominative", "dual", "indefinite"),
    FormSpec("indefinite.accusative.dual", "رسالتين", "accusative", "dual", "indefinite"),
    FormSpec("indefinite.genitive.dual", "رسالتين", "genitive", "dual", "indefinite"),
    FormSpec("construct.nominative.dual", "رسالتا", "nominative", "dual", "construct"),
    FormSpec("construct.accusative.dual", "رسالتي", "accusative", "dual", "construct"),
    FormSpec("construct.genitive.dual", "رسالتي", "genitive", "dual", "construct"),
    FormSpec("indefinite.nominative.plural", "رسائل", "nominative", "plural", "indefinite"),
    FormSpec("indefinite.accusative.plural", "رسائل", "accusative", "plural", "indefinite"),
    FormSpec("indefinite.genitive.plural", "رسائل", "genitive", "plural", "indefinite"),
    FormSpec("construct.nominative.plural", "رسائل", "nominative", "plural", "construct"),
    FormSpec("construct.accusative.plural", "رسائل", "accusative", "plural", "construct"),
    FormSpec("construct.genitive.plural", "رسائل", "genitive", "plural", "construct"),
)


TERM_SPECS = {
    "review-required": TermSpec(
        TERM_ID,
        TERM_TEXT,
        TERM_SENSE,
        "c2",
        "feminine",
        REVIEW_REQUIRED_FORM_SPECS,
    ),
    "approved": TermSpec(
        "ar.explicit.message",
        "رسالة",
        "inflection=17a",
        "17a",
        "feminine",
        APPROVED_FORM_SPECS,
    ),
}


def required_form_keys() -> list[str]:
    return [
        f"{definiteness}.{grammatical_case}.{number}"
        for definiteness in DEFINITENESS_VALUES
        for grammatical_case in CASE_VALUES
        for number in NUMBER_VALUES
    ]


def entry_cases(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"accusative", "genitive", "nominative"}}


def entry_numbers(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"dual", "plural", "singular"}}


def entry_definiteness(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"construct", "definite", "indefinite"}}


def entry_genders(entry: DictionaryEntry) -> set[str]:
    return {grammeme for grammeme in entry.grammemes if grammeme in {"feminine", "masculine"}}


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
        and spec.definiteness in entry_definiteness(entry)
        and entry_genders(entry) == {term_spec.gender}
        and entry_pos(entry) == {"noun"}
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one Arabic dictionary row for {spec}, got {len(matches)}")
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
        "runtimeExport": "closed-world-explicit-forms",
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
        "locale": "ar",
        "provenance": provenance(dictionary),
        "strings": strings,
        "terms": terms,
        "formSets": form_sets,
        "diagnostics": [],
        "generationSummary": {
            "policy": "closed-world explicit-form pack",
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
        help="Select the deterministic Arabic term fixture to emit.",
    )
    args = parser.parse_args()

    write_json(args.out, build_pack(args.dictionary, args.fixture))


if __name__ == "__main__":
    main()
