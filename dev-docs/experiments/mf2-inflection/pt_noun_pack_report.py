#!/usr/bin/env python3
"""Estimate Portuguese noun metadata and agreement-form packs from Unicode data."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

from es_noun_pack_report import (
    InflectionPattern,
    PatternSlot,
    compact_entry,
    estimate_metadata_pack,
    feature_map,
    pack_provenance,
    pack_source,
    pattern_feature_counts,
    required_attr,
    surface_record,
    text_or_empty,
)
from fr_dictionary_report import DictionaryEntry, parse_dictionary


GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"plural", "singular"}
SUPPORTED_POS = {"noun", "proper-noun"}
ARTICLE_VALUES = {"definite", "indefinite"}
TARGET_SAMPLE_SURFACES = ("campo", "casa", "campos", "casas", "primo", "prima")

AGREEMENT_FORM_TABLE = {
    "definiteArticle": {
        ("masculine", "singular"): "o",
        ("feminine", "singular"): "a",
        ("masculine", "plural"): "os",
        ("feminine", "plural"): "as",
    },
    "indefiniteArticle": {
        ("masculine", "singular"): "um",
        ("feminine", "singular"): "uma",
        ("masculine", "plural"): "uns",
        ("feminine", "plural"): "umas",
    },
    "deDefiniteArticle": {
        ("masculine", "singular"): "do",
        ("feminine", "singular"): "da",
        ("masculine", "plural"): "dos",
        ("feminine", "plural"): "das",
    },
    "emDefiniteArticle": {
        ("masculine", "singular"): "no",
        ("feminine", "singular"): "na",
        ("masculine", "plural"): "nos",
        ("feminine", "plural"): "nas",
    },
    "emIndefiniteArticle": {
        ("masculine", "singular"): "num",
        ("feminine", "singular"): "numa",
        ("masculine", "plural"): "nuns",
        ("feminine", "plural"): "numas",
    },
    "porDefiniteArticle": {
        ("masculine", "singular"): "pelo",
        ("feminine", "singular"): "pela",
        ("masculine", "plural"): "pelos",
        ("feminine", "plural"): "pelas",
    },
    "possessiveArticle": {
        ("masculine", "singular"): "seu",
        ("feminine", "singular"): "sua",
        ("masculine", "plural"): "seus",
        ("feminine", "plural"): "suas",
    },
    "demonstrativeProximal": {
        ("masculine", "singular"): "este",
        ("feminine", "singular"): "esta",
        ("masculine", "plural"): "estes",
        ("feminine", "plural"): "estas",
    },
    "emDemonstrativeProximal": {
        ("masculine", "singular"): "neste",
        ("feminine", "singular"): "nesta",
        ("masculine", "plural"): "nestes",
        ("feminine", "plural"): "nestas",
    },
    "deDemonstrativeProximal": {
        ("masculine", "singular"): "deste",
        ("feminine", "singular"): "desta",
        ("masculine", "plural"): "destes",
        ("feminine", "plural"): "destas",
    },
    "demonstrativeMedial": {
        ("masculine", "singular"): "esse",
        ("feminine", "singular"): "essa",
        ("masculine", "plural"): "esses",
        ("feminine", "plural"): "essas",
    },
    "emDemonstrativeMedial": {
        ("masculine", "singular"): "nesse",
        ("feminine", "singular"): "nessa",
        ("masculine", "plural"): "nesses",
        ("feminine", "plural"): "nessas",
    },
    "deDemonstrativeMedial": {
        ("masculine", "singular"): "desse",
        ("feminine", "singular"): "dessa",
        ("masculine", "plural"): "desses",
        ("feminine", "plural"): "dessas",
    },
    "demonstrativeDistal": {
        ("masculine", "singular"): "aquele",
        ("feminine", "singular"): "aquela",
        ("masculine", "plural"): "aqueles",
        ("feminine", "plural"): "aquelas",
    },
    "emDemonstrativeDistal": {
        ("masculine", "singular"): "naquele",
        ("feminine", "singular"): "naquela",
        ("masculine", "plural"): "naqueles",
        ("feminine", "plural"): "naquelas",
    },
    "deDemonstrativeDistal": {
        ("masculine", "singular"): "daquele",
        ("feminine", "singular"): "daquela",
        ("masculine", "plural"): "daqueles",
        ("feminine", "plural"): "daquelas",
    },
}


def parse_patterns(path: Path) -> dict[str, InflectionPattern]:
    root = ET.parse(path).getroot()
    patterns: dict[str, InflectionPattern] = {}
    for pattern_node in root.findall("pattern"):
        name = required_attr(pattern_node, "name")
        part_of_speech = text_or_empty(pattern_node.find("pos"))
        suffix = text_or_empty(pattern_node.find("suffix"))
        words = int(pattern_node.attrib.get("words", "0"))
        slots = []
        inflections = pattern_node.find("inflections")
        if inflections is not None:
            for inflection in inflections.findall("inflection"):
                slots.append(
                    PatternSlot(
                        inflection.attrib.get("gender"),
                        inflection.attrib.get("number"),
                        template_text(inflection.find("t")),
                    )
                )
        patterns[name] = InflectionPattern(name, part_of_speech, suffix, words, tuple(slots))
    return patterns


def template_text(node: ET.Element | None) -> str:
    if node is None:
        return ""
    parts = []
    if node.text:
        parts.append(node.text)
    for child in node:
        if child.tag == "stem":
            parts.append("{stem}")
        elif child.text:
            parts.append(child.text)
        if child.tail:
            parts.append(child.tail)
    return "".join(parts)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_VALUES)


def supported_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry.parts_of_speech()) & SUPPORTED_POS)


def agreement_form_rows() -> list[dict]:
    rows = []
    for category, forms in sorted(AGREEMENT_FORM_TABLE.items()):
        for (gender, number), form in sorted(forms.items()):
            rows.append(
                {
                    "category": category,
                    "gender": gender,
                    "number": number,
                    "form": form,
                }
            )
    return rows


def form_for(category: str, gender: str, number: str) -> str:
    return AGREEMENT_FORM_TABLE[category][(gender, number)]


def phrase(prefix: str, noun_form: str) -> str:
    return f"{prefix} {noun_form}"


def agreement_phrase_forms(record: dict) -> dict[str, str]:
    gender = record["genders"][0]
    number = record["numbers"][0]
    return {
        category: phrase(form_for(category, gender, number), record["surface"])
        for category in sorted(AGREEMENT_FORM_TABLE)
    }


def estimate_agreement_phrases(records: list[dict]) -> dict:
    phrase_values = {
        phrase
        for record in records
        for phrase in agreement_phrase_forms(record).values()
    }
    phrase_rows = len(records) * len(AGREEMENT_FORM_TABLE)
    phrase_row_bytes = phrase_rows * 12
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in phrase_values)
    return {
        "phraseRows": phrase_rows,
        "stringPoolBytes": string_pool_bytes,
        "phraseRowBytes": phrase_row_bytes,
        "binaryLowerBoundBytes": string_pool_bytes + phrase_row_bytes,
    }


def agreement_sample(record: dict) -> dict:
    return {
        "surface": record["surface"],
        "gender": record["genders"][0],
        "number": record["numbers"][0],
        "phraseForms": agreement_phrase_forms(record),
    }


def agreement_strategy(records: list[dict], max_samples: int) -> dict:
    targeted_records_by_surface = {record["surface"]: record for record in records}
    return {
        "agreementForms": agreement_form_rows(),
        "counts": {
            "agreementCandidateSurfaces": len(records),
            "agreementFormCategories": len(AGREEMENT_FORM_TABLE),
        },
        "sizeEstimates": {
            "eagerPhrasePack": estimate_agreement_phrases(records),
        },
        "samples": {
            "agreementCandidates": [
                agreement_sample(record)
                for record in records[:max_samples]
            ],
            "targetedAgreementCandidates": [
                agreement_sample(targeted_records_by_surface[surface])
                for surface in TARGET_SAMPLE_SURFACES
                if surface in targeted_records_by_surface
            ],
        },
    }


def review_policy(records: list[dict], gender_number_records: list[dict], exact_records: list[dict]) -> dict:
    candidate_surfaces = {record["surface"] for record in gender_number_records}
    review_records = [record for record in gender_number_records if record["reasons"]]
    blocked_records = [record for record in records if record["surface"] not in candidate_surfaces]
    review_reason_counts: Counter[str] = Counter()
    blocked_reason_counts: Counter[str] = Counter()
    for record in review_records:
        review_reason_counts.update(record["reasons"])
    for record in blocked_records:
        blocked_reason_counts.update(record["reasons"])
    return {
        "compactRuntime": "agreement-shell-composition",
        "automaticExportSurfaces": len(exact_records),
        "reviewRequiredSurfaces": len(review_records),
        "blockedSurfaces": len(blocked_records),
        "reviewRequiredReasons": dict(sorted(review_reason_counts.items())),
        "blockedReasons": dict(sorted(blocked_reason_counts.items())),
    }


def build_report(dictionary: Path, inflectional: Path, max_samples: int) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)

    supported_entries = [entry for entry in entries if supported_entry(entry)]
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in supported_entries:
        by_surface[entry.normalized_surface].append(entry)

    records = [
        surface_record(surface, surface_entries)
        for surface, surface_entries in sorted(by_surface.items())
    ]
    reason_counts: Counter[str] = Counter()
    for record in records:
        reason_counts.update(record["reasons"])

    exact_records = [
        record
        for record in records
        if len(record["genders"]) == 1
        and len(record["numbers"]) == 1
        and not record["reasons"]
    ]
    gender_number_records = [
        record
        for record in records
        if len(record["genders"]) == 1
        and "missing-gender" not in record["reasons"]
        and "multiple-genders" not in record["reasons"]
        and len(record["numbers"]) == 1
        and "missing-number" not in record["reasons"]
        and "multiple-numbers" not in record["reasons"]
    ]

    dictionary_inflections = {
        inflection for entry in supported_entries for inflection in entry.inflections
    }
    missing_inflections = sorted(
        inflection for inflection in dictionary_inflections if inflection not in patterns
    )
    used_patterns = [
        patterns[inflection] for inflection in dictionary_inflections if inflection in patterns
    ]
    noun_patterns = [pattern for pattern in patterns.values() if pattern.part_of_speech == "noun"]

    return {
        "schema": "mojito-mf2-inflection/pt-noun-pack-report/v0",
        "locale": "pt",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": pack_provenance(
            [dictionary, inflectional],
            "dev-docs/experiments/mf2-inflection/pt_noun_pack_report.py",
        ),
        "counts": {
            "dictionaryEntries": len(entries),
            "supportedEntries": len(supported_entries),
            "uniqueSupportedSurfaces": len(records),
            "skippedLines": len(skipped),
            "ambiguousSupportedSurfaces": sum(1 for record in records if record["reasons"]),
            "exactGenderNumberSurfaces": len(exact_records),
            "genderNumberCandidateSurfaces": len(gender_number_records),
            "dictionaryInflectionPatterns": len(dictionary_inflections),
            "missingInflectionPatterns": len(missing_inflections),
            "inflectionalPatterns": len(patterns),
            "nounInflectionalPatterns": len(noun_patterns),
            "usedInflectionalPatterns": len(used_patterns),
        },
        "features": {
            "gender": feature_map(supported_entries, entry_genders),
            "number": feature_map(supported_entries, entry_numbers),
            "partOfSpeech": feature_map(supported_entries, DictionaryEntry.parts_of_speech),
            "ambiguityReasons": dict(sorted(reason_counts.items())),
            **pattern_feature_counts(patterns),
        },
        "sizeEstimates": {
            "genderNumberMetadataPack": estimate_metadata_pack(gender_number_records),
        },
        "agreementStrategy": agreement_strategy(gender_number_records, max_samples),
        "reviewPolicy": review_policy(records, gender_number_records, exact_records),
        "samples": {
            "genderNumberCandidates": gender_number_records[:max_samples],
            "blockingAmbiguities": [record for record in records if record["reasons"]][:max_samples],
            "entries": [compact_entry(entry) for entry in supported_entries[:max_samples]],
            "missingInflectionPatterns": missing_inflections[:max_samples],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Portuguese dictionary file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Portuguese inflectional XML file.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    parser.add_argument("--max-samples", type=int, default=10, help="Maximum sample rows to include per section.")
    args = parser.parse_args()

    payload = build_report(args.dictionary, args.inflectional, args.max_samples)
    output = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(output, encoding="utf-8")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
