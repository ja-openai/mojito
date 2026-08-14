#!/usr/bin/env python3
"""Estimate Spanish noun metadata packs from Unicode dictionary and pattern data."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import SOURCE_LICENSE, UNICODE_DICTIONARY_RESOURCE_PREFIX, pack_source_metadata


GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"plural", "singular"}
SUPPORTED_POS = {"noun", "proper-noun"}
ARTICLE_VALUES = {"definite", "indefinite"}
STRESSED_GRAMMEME = "stressed"
ARTICLE_FORMS = {
    ("definite", "masculine", "singular"): "el",
    ("definite", "masculine", "plural"): "los",
    ("definite", "feminine", "singular"): "la",
    ("definite", "feminine", "plural"): "las",
    ("indefinite", "masculine", "singular"): "un",
    ("indefinite", "masculine", "plural"): "unos",
    ("indefinite", "feminine", "singular"): "una",
    ("indefinite", "feminine", "plural"): "unas",
}
STRESSED_FEMININE_SINGULAR_ARTICLE_FORMS = {
    "definite": "el",
    "indefinite": "un",
}


@dataclass(frozen=True)
class PatternSlot:
    gender: str | None
    number: str | None
    template: str


@dataclass(frozen=True)
class InflectionPattern:
    name: str
    part_of_speech: str
    suffix: str
    words: int
    slots: tuple[PatternSlot, ...]


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


def required_attr(node: ET.Element, name: str) -> str:
    value = node.attrib.get(name)
    if value is None:
        raise ValueError(f"Missing XML attribute {name}")
    return value


def text_or_empty(node: ET.Element | None) -> str:
    return "" if node is None or node.text is None else node.text


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


def signature(entry: DictionaryEntry) -> tuple:
    return (
        tuple(sorted(entry.parts_of_speech())),
        tuple(sorted(entry_genders(entry))),
        tuple(sorted(entry_numbers(entry))),
        tuple(sorted(entry.inflections)),
    )


def surface_record(surface: str, entries: list[DictionaryEntry]) -> dict:
    genders = {gender for entry in entries for gender in entry_genders(entry)}
    numbers = {number for entry in entries for number in entry_numbers(entry)}
    parts_of_speech = {pos for entry in entries for pos in entry.parts_of_speech()}
    inflections = {inflection for entry in entries for inflection in entry.inflections}
    signatures = {signature(entry) for entry in entries}

    reasons = []
    if len(signatures) > 1:
        reasons.append("multiple-analyses")
    if not genders:
        reasons.append("missing-gender")
    if len(genders) > 1 or any(len(entry_genders(entry)) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if not numbers:
        reasons.append("missing-number")
    if len(numbers) > 1 or any(len(entry_numbers(entry)) > 1 for entry in entries):
        reasons.append("multiple-numbers")
    if len(parts_of_speech) > 1 or any(len(entry.parts_of_speech()) > 1 for entry in entries):
        reasons.append("multiple-parts-of-speech")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")

    return {
        "surface": surface,
        "genders": sorted(genders),
        "numbers": sorted(numbers),
        "partsOfSpeech": sorted(parts_of_speech),
        "inflections": sorted(inflections),
        "stressed": any(STRESSED_GRAMMEME in entry.grammemes for entry in entries),
        "reasons": reasons,
        "entries": len(entries),
    }


def compact_entry(entry: DictionaryEntry) -> dict:
    return {
        "surface": entry.surface,
        "line": entry.line,
        "partOfSpeech": list(entry.parts_of_speech()),
        "gender": list(entry_genders(entry)),
        "number": list(entry_numbers(entry)),
        "inflections": list(entry.inflections),
    }


def feature_map(entries: list[DictionaryEntry], extractor) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for entry in entries:
        values = extractor(entry)
        if values:
            counter.update(values)
        else:
            counter["<missing>"] += 1
    return dict(sorted(counter.items()))


def pattern_feature_counts(patterns: dict[str, InflectionPattern]) -> dict:
    part_of_speech = Counter(pattern.part_of_speech or "<missing>" for pattern in patterns.values())
    slot_genders: Counter[str] = Counter()
    slot_numbers: Counter[str] = Counter()
    for pattern in patterns.values():
        for slot in pattern.slots:
            slot_genders.update([slot.gender or "<missing>"])
            slot_numbers.update([slot.number or "<missing>"])

    return {
        "patternPartOfSpeech": dict(sorted(part_of_speech.items())),
        "patternSlotGenders": dict(sorted(slot_genders.items())),
        "patternSlotNumbers": dict(sorted(slot_numbers.items())),
    }


def estimate_metadata_pack(records: list[dict]) -> dict:
    string_pool_bytes = sum(len(record["surface"].encode("utf-8")) + 1 for record in records)
    row_bytes = len(records) * 8
    return {
        "stringPoolBytes": string_pool_bytes,
        "rowBytes": row_bytes,
        "binaryLowerBoundBytes": string_pool_bytes + row_bytes,
    }


def article_for(article: str, gender: str, number: str, stressed: bool) -> str:
    if gender == "feminine" and number == "singular" and stressed:
        return STRESSED_FEMININE_SINGULAR_ARTICLE_FORMS[article]
    return ARTICLE_FORMS[(article, gender, number)]


def phrase(article: str, noun_form: str) -> str:
    return f"{article} {noun_form}"


def article_form_rows() -> list[dict]:
    rows = [
        {
            "article": article,
            "gender": gender,
            "number": number,
            "stressed": False,
            "form": form,
        }
        for (article, gender, number), form in sorted(ARTICLE_FORMS.items())
    ]
    rows.extend(
        {
            "article": article,
            "gender": "feminine",
            "number": "singular",
            "stressed": True,
            "form": form,
        }
        for article, form in sorted(STRESSED_FEMININE_SINGULAR_ARTICLE_FORMS.items())
    )
    return rows


def article_phrase_forms(record: dict) -> dict[str, str]:
    gender = record["genders"][0]
    number = record["numbers"][0]
    return {
        article: phrase(article_for(article, gender, number, record["stressed"]), record["surface"])
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


def article_strategy(records: list[dict], max_samples: int) -> dict:
    override_records = [
        record
        for record in records
        if record["genders"] == ["feminine"] and record["numbers"] == ["singular"] and record["stressed"]
    ]
    sample_records = records[:max_samples]
    override_sample_records = override_records[:max_samples]
    return {
        "articleForms": article_form_rows(),
        "counts": {
            "articleCandidateSurfaces": len(records),
            "stressedFeminineSingularOverrides": len(override_records),
        },
        "sizeEstimates": {
            "eagerPhrasePack": estimate_article_phrases(records),
        },
        "samples": {
            "articleCandidates": [
                {
                    "surface": record["surface"],
                    "gender": record["genders"][0],
                    "number": record["numbers"][0],
                    "stressed": record["stressed"],
                    "phraseForms": article_phrase_forms(record),
                }
                for record in sample_records
            ],
            "stressedFeminineSingularOverrides": [
                {
                    "surface": record["surface"],
                    "phraseForms": article_phrase_forms(record),
                }
                for record in override_sample_records
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
        "compactRuntime": "article-shell-composition",
        "automaticExportSurfaces": len(exact_records),
        "reviewRequiredSurfaces": len(review_records),
        "blockedSurfaces": len(blocked_records),
        "reviewRequiredReasons": dict(sorted(review_reason_counts.items())),
        "blockedReasons": dict(sorted(blocked_reason_counts.items())),
    }


def pack_source(path: Path) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    return metadata


def pack_provenance(paths: list[Path], generator: str) -> dict:
    return {
        "license": SOURCE_LICENSE,
        "generator": generator,
        "sources": [pack_source(path) for path in paths],
        "sourceLabels": [
            f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{path.name}"
            for path in paths
        ],
    }


def build_report(dictionary: Path, inflectional: Path, max_samples: int) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)

    supported_entries = [entry for entry in entries if supported_entry(entry)]
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in supported_entries:
        by_surface[entry.normalized_surface].append(entry)

    records = [surface_record(surface, surface_entries) for surface, surface_entries in sorted(by_surface.items())]
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
        "schema": "mojito-mf2-inflection/es-noun-pack-report/v0",
        "locale": "es",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": pack_provenance(
            [dictionary, inflectional],
            "dev-docs/experiments/mf2-inflection/es_noun_pack_report.py",
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
            "missingInflectionPatterns": missing_inflections[:max_samples],
            "entries": [compact_entry(entry) for entry in supported_entries[:max_samples]],
        },
    }


def write_json(path: Path | None, value: dict) -> str:
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Spanish dictionary .lst file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Spanish inflectional XML file.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    parser.add_argument("--max-samples", type=int, default=20)
    args = parser.parse_args()

    payload = write_json(args.out, build_report(args.dictionary, args.inflectional, args.max_samples))
    if args.out:
        return
    print(payload, end="")


if __name__ == "__main__":
    main()
