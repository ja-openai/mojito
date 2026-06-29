#!/usr/bin/env python3
"""Estimate Italian noun metadata and article-class packs from Unicode data."""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

from es_noun_pack_report import (
    InflectionPattern,
    PatternSlot,
    compact_entry,
    feature_map,
    pack_provenance,
    pattern_feature_counts,
    required_attr,
    surface_record,
    text_or_empty,
)
from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import pack_source_metadata


GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"plural", "singular"}
SUPPORTED_POS = {"noun", "proper-noun"}
ARTICLE_VALUES = {"definite", "indefinite"}
DICTIONARY_VOWEL_START_GRAMMEME = "vowel-start"
VOWEL_START_RE = re.compile(r"^['’]?[aeiouàèéìòóùh]", re.IGNORECASE)
LO_CLASS_RE = re.compile(r"^['’]?(?:s[^aeiouàèéìòóùh]|z|gn|pn|ps|x|y)", re.IGNORECASE)
TARGET_SAMPLE_SURFACES = ("gnomo", "gnomi", "libro", "cani", "acqua", "ape")


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


def pack_source(path: Path) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    return metadata


def estimate_metadata_pack(records: list[dict]) -> dict:
    string_pool_bytes = sum(len(record["surface"].encode("utf-8")) + 1 for record in records)
    row_bytes = len(records) * 8
    return {
        "stringPoolBytes": string_pool_bytes,
        "rowBytes": row_bytes,
        "binaryLowerBoundBytes": string_pool_bytes + row_bytes,
    }


def surface_vowel_start(surface: str) -> bool:
    return bool(VOWEL_START_RE.match(surface))


def masculine_lo_start(surface: str) -> bool:
    return bool(LO_CLASS_RE.match(surface))


def article_class(record: dict) -> str:
    gender = record["genders"][0]
    surface = record["surface"]
    if surface_vowel_start(surface):
        return "elision"
    if gender == "masculine" and masculine_lo_start(surface):
        return "lo"
    return "standard"


def article_for(article: str, gender: str, number: str, article_class_value: str) -> str:
    if article == "definite":
        if gender == "masculine" and number == "singular":
            return {"standard": "il", "lo": "lo", "elision": "l'"}[article_class_value]
        if gender == "masculine" and number == "plural":
            return {"standard": "i", "lo": "gli", "elision": "gli"}[article_class_value]
        if gender == "feminine" and number == "singular":
          return "l'" if article_class_value == "elision" else "la"
        return "le"

    if gender == "masculine" and number == "singular":
        return "uno" if article_class_value == "lo" else "un"
    if gender == "masculine" and number == "plural":
        return "dei" if article_class_value == "standard" else "degli"
    if gender == "feminine" and number == "singular":
        return "un'" if article_class_value == "elision" else "una"
    return "delle"


def phrase(article: str, noun_form: str) -> str:
    separator = "" if article.endswith("'") else " "
    return f"{article}{separator}{noun_form}"


def article_form_rows() -> list[dict]:
    rows = []
    combinations = [
        ("masculine", "singular", ("standard", "lo", "elision")),
        ("masculine", "plural", ("standard", "lo", "elision")),
        ("feminine", "singular", ("standard", "elision")),
        ("feminine", "plural", ("standard", "elision")),
    ]
    for article in sorted(ARTICLE_VALUES):
        for gender, number, article_classes in combinations:
            for article_class_value in article_classes:
                rows.append(
                    {
                        "article": article,
                        "gender": gender,
                        "number": number,
                        "articleClass": article_class_value,
                        "form": article_for(article, gender, number, article_class_value),
                    }
                )
    return rows


def article_phrase_forms(record: dict) -> dict[str, str]:
    gender = record["genders"][0]
    number = record["numbers"][0]
    article_class_value = article_class(record)
    return {
        article: phrase(article_for(article, gender, number, article_class_value), record["surface"])
        for article in sorted(ARTICLE_VALUES)
    }


def estimate_article_phrases(records: list[dict]) -> dict:
    phrase_values = {
        phrase
        for record in records
        for phrase in article_phrase_forms(record).values()
    }
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in phrase_values)
    phrase_rows = len(records) * len(ARTICLE_VALUES)
    phrase_row_bytes = phrase_rows * 12
    return {
        "phraseRows": phrase_rows,
        "stringPoolBytes": string_pool_bytes,
        "phraseRowBytes": phrase_row_bytes,
        "binaryLowerBoundBytes": string_pool_bytes + phrase_row_bytes,
    }


def article_candidate_sample(record: dict) -> dict:
    return {
        "surface": record["surface"],
        "gender": record["genders"][0],
        "number": record["numbers"][0],
        "articleClass": article_class(record),
        "dictionaryVowelStart": record["dictionaryVowelStart"],
        "surfaceVowelStart": surface_vowel_start(record["surface"]),
        "phraseForms": article_phrase_forms(record),
    }


def article_strategy(records: list[dict], max_samples: int) -> dict:
    article_class_counts = Counter(article_class(record) for record in records)
    dictionary_vowel_start_records = [record for record in records if record["dictionaryVowelStart"]]
    surface_vowel_start_records = [record for record in records if surface_vowel_start(record["surface"])]
    lo_class_records = [record for record in records if article_class(record) == "lo"]
    targeted_records_by_surface = {record["surface"]: record for record in records}

    return {
        "articleForms": article_form_rows(),
        "counts": {
            "articleCandidateSurfaces": len(records),
            "dictionaryVowelStartSurfaces": len(dictionary_vowel_start_records),
            "surfaceVowelStartSurfaces": len(surface_vowel_start_records),
            "masculineLoClassSurfaces": len(lo_class_records),
        },
        "articleClassCounts": dict(sorted(article_class_counts.items())),
        "sizeEstimates": {
            "eagerPhrasePack": estimate_article_phrases(records),
        },
        "samples": {
            "articleCandidates": [
                article_candidate_sample(record)
                for record in records[:max_samples]
            ],
            "loClassCandidates": [
                article_candidate_sample(record)
                for record in lo_class_records[:max_samples]
            ],
            "vowelStartCandidates": [
                article_candidate_sample(record)
                for record in surface_vowel_start_records[:max_samples]
            ],
            "targetedArticleCandidates": [
                article_candidate_sample(targeted_records_by_surface[surface])
                for surface in TARGET_SAMPLE_SURFACES
                if surface in targeted_records_by_surface
            ],
        },
    }


def italian_surface_record(surface: str, entries: list[DictionaryEntry]) -> dict:
    record = surface_record(surface, entries)
    record["dictionaryVowelStart"] = any(
        DICTIONARY_VOWEL_START_GRAMMEME in entry.grammemes for entry in entries
    )
    return record


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
        "compactRuntime": "article-shell-composition",
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
        italian_surface_record(surface, surface_entries)
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

    dictionary_inflections = {inflection for entry in supported_entries for inflection in entry.inflections}
    missing_inflections = sorted(inflection for inflection in dictionary_inflections if inflection not in patterns)
    used_patterns = [patterns[inflection] for inflection in dictionary_inflections if inflection in patterns]
    noun_patterns = [pattern for pattern in patterns.values() if pattern.part_of_speech == "noun"]
    metadata_estimate = estimate_metadata_pack(gender_number_records)

    return {
        "schema": "mojito-mf2-inflection/it-noun-pack-report/v0",
        "locale": "it",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": pack_provenance(
            [dictionary, inflectional],
            "dev-docs/experiments/mf2-inflection/it_noun_pack_report.py",
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
            "genderNumberMetadataPack": metadata_estimate,
        },
        "articleStrategy": article_strategy(gender_number_records, max_samples),
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
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Italian dictionary file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Italian inflectional XML file.")
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
