#!/usr/bin/env python3
"""Audit Arabic dictionary, pattern, and pronoun data before runtime design."""

from __future__ import annotations

import argparse
import csv
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import (
    SOURCE_LICENSE,
    UNICODE_DICTIONARY_RESOURCE_PREFIX,
    pack_source_metadata,
)


SCHEMA = "mojito-mf2-inflection/ar-pack-audit/v0"
INFLECTION_RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/inflection"
SUPPORTED_TERM_POS = {"noun", "proper-noun"}
AGREEMENT_POS = {"adjective", "noun", "proper-noun"}
PART_OF_SPEECH_VALUES = {"adjective", "adverb", "noun", "proper-noun", "verb"}
CASE_VALUES = {"accusative", "genitive", "nominative"}
GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"dual", "plural", "singular"}
DEFINITENESS_VALUES = {"construct", "definite", "indefinite"}
ANIMACY_VALUES = {"animate", "inanimate"}
PERSON_VALUES = {"first", "second", "third"}
PRONOUN_CASE_VALUES = {"accusative", "genitive", "nominative", "reflexive"}


@dataclass(frozen=True)
class PatternSlot:
    attrs: tuple[tuple[str, str], ...]
    template: str


@dataclass(frozen=True)
class InflectionPattern:
    name: str
    part_of_speech: tuple[str, ...]
    suffix: str
    words: int
    slots: tuple[PatternSlot, ...]


def parse_patterns(path: Path) -> dict[str, InflectionPattern]:
    root = ET.parse(path).getroot()
    patterns: dict[str, InflectionPattern] = {}
    for pattern_node in root.findall("pattern"):
        name = required_attr(pattern_node, "name")
        part_of_speech = tuple(
            value for value in (element_text(node) for node in pattern_node.findall("pos")) if value
        )
        suffix = element_text(pattern_node.find("suffix"))
        words = int(pattern_node.attrib.get("words", "0"))
        slots = []
        inflections = pattern_node.find("inflections")
        if inflections is not None:
            for inflection in inflections.findall("inflection"):
                slots.append(
                    PatternSlot(
                        tuple(sorted(inflection.attrib.items())),
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


def element_text(node: ET.Element | None) -> str:
    if node is None:
        return ""
    parts = []
    if node.text:
        parts.append(node.text)
    for child in node:
        if child.tail:
            parts.append(child.tail)
    return "".join(parts).strip()


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


def parse_pronouns(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8", newline="") as pronoun_file:
        for line_number, row in enumerate(csv.reader(pronoun_file), start=1):
            if not row:
                continue
            rows.append(
                {
                    "line": line_number,
                    "value": row[0],
                    "features": tuple(token for token in row[1:] if token),
                }
            )
    return rows


def entry_pos(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in PART_OF_SPEECH_VALUES)


def entry_cases(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in CASE_VALUES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_VALUES)


def entry_definiteness(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in DEFINITENESS_VALUES)


def entry_animacy(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in ANIMACY_VALUES)


def term_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry_pos(entry)) & SUPPORTED_TERM_POS)


def agreement_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry_pos(entry)) & AGREEMENT_POS)


def count_values(entries: list[DictionaryEntry], extractor) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for entry in entries:
        values = extractor(entry)
        if values:
            counter.update(values)
        else:
            counter["<missing>"] += 1
    return dict(sorted(counter.items()))


def compact_entry(entry: DictionaryEntry) -> dict:
    return {
        "surface": entry.surface,
        "line": entry.line,
        "partOfSpeech": list(entry_pos(entry)),
        "case": list(entry_cases(entry)),
        "gender": list(entry_genders(entry)),
        "number": list(entry_numbers(entry)),
        "definiteness": list(entry_definiteness(entry)),
        "animacy": list(entry_animacy(entry)),
        "inflections": list(entry.inflections),
    }


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    cases = {value for entry in entries for value in entry_cases(entry)}
    genders = {value for entry in entries for value in entry_genders(entry)}
    numbers = {value for entry in entries for value in entry_numbers(entry)}
    definiteness = {value for entry in entries for value in entry_definiteness(entry)}
    animacy = {value for entry in entries for value in entry_animacy(entry)}
    inflections = {value for entry in entries for value in entry.inflections}
    poses = {value for entry in entries for value in entry_pos(entry)}
    reasons = []
    if len(entries) > 1:
        reasons.append("multiple-entries")
    if len(cases) > 1 or any(len(entry_cases(entry)) > 1 for entry in entries):
        reasons.append("multiple-cases")
    if len(genders) > 1 or any(len(entry_genders(entry)) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if len(numbers) > 1 or any(len(entry_numbers(entry)) > 1 for entry in entries):
        reasons.append("multiple-numbers")
    if len(definiteness) > 1 or any(len(entry_definiteness(entry)) > 1 for entry in entries):
        reasons.append("multiple-definiteness")
    if len(animacy) > 1 or any(len(entry_animacy(entry)) > 1 for entry in entries):
        reasons.append("multiple-animacy")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")
    if len(poses) > 1 or any(len(entry_pos(entry)) > 1 for entry in entries):
        reasons.append("multiple-parts-of-speech")
    return reasons


def ambiguous_surfaces(entries: list[DictionaryEntry], max_samples: int) -> tuple[list[dict], Counter[str]]:
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in entries:
        by_surface[entry.normalized_surface].append(entry)

    ambiguous = []
    reason_counts: Counter[str] = Counter()
    for surface, group in sorted(by_surface.items()):
        reasons = ambiguity_reasons(group)
        if not reasons:
            continue
        reason_counts.update(reasons)
        ambiguous.append(
            {
                "surface": surface,
                "entries": len(group),
                "reasons": reasons,
                "sampleEntries": [compact_entry(entry) for entry in group[:max_samples]],
            }
        )
    return ambiguous, reason_counts


def pattern_slot_stats(patterns: list[InflectionPattern]) -> dict:
    slot_attrs: dict[str, Counter[str]] = defaultdict(Counter)
    slots_per_pattern: Counter[int] = Counter()
    duplicate_slot_patterns = 0
    duplicate_slot_rows = 0
    fully_specified_case_number_gender_definiteness = 0
    construct_slots = 0
    dual_slots = 0

    for pattern in patterns:
        slots_per_pattern[len(pattern.slots)] += 1
        seen: Counter[tuple[tuple[str, str], ...]] = Counter()
        for slot in pattern.slots:
            attrs = dict(slot.attrs)
            seen[slot.attrs] += 1
            for key, value in slot.attrs:
                slot_attrs[key][value] += 1
            if attrs.get("number") == "dual":
                dual_slots += 1
            if attrs.get("definiteness") == "construct":
                construct_slots += 1
            if all(key in attrs for key in ("case", "number", "gender", "definiteness")):
                fully_specified_case_number_gender_definiteness += 1
        duplicate_rows = sum(count - 1 for count in seen.values() if count > 1)
        if duplicate_rows:
            duplicate_slot_patterns += 1
            duplicate_slot_rows += duplicate_rows

    return {
        "slotAttributes": {
            key: dict(sorted(counter.items())) for key, counter in sorted(slot_attrs.items())
        },
        "slotsPerPattern": {str(key): value for key, value in sorted(slots_per_pattern.items())},
        "patternsWithDuplicateSlots": duplicate_slot_patterns,
        "duplicateSlotRows": duplicate_slot_rows,
        "dualSlots": dual_slots,
        "constructSlots": construct_slots,
        "fullySpecifiedCaseNumberGenderDefinitenessSlots": fully_specified_case_number_gender_definiteness,
    }


def pronoun_stats(pronoun_rows: list[dict]) -> dict:
    feature_counts: Counter[str] = Counter()
    case_counts: Counter[str] = Counter()
    gender_counts: Counter[str] = Counter()
    number_counts: Counter[str] = Counter()
    person_counts: Counter[str] = Counter()
    for row in pronoun_rows:
        features = set(row["features"])
        feature_counts.update(features)
        case_counts.update(features & PRONOUN_CASE_VALUES)
        gender_counts.update(features & GENDER_VALUES)
        number_counts.update(features & NUMBER_VALUES)
        person_counts.update(features & PERSON_VALUES)
    return {
        "rows": len(pronoun_rows),
        "uniqueValues": len({row["value"] for row in pronoun_rows}),
        "features": dict(sorted(feature_counts.items())),
        "cases": dict(sorted(case_counts.items())),
        "genders": dict(sorted(gender_counts.items())),
        "numbers": dict(sorted(number_counts.items())),
        "persons": dict(sorted(person_counts.items())),
    }


def stable_source(path: Path, resource_prefix: str) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    metadata["path"] = f"{resource_prefix}/{path.name}"
    return metadata


def pack_provenance(dictionary: Path, inflectional: Path, pronouns: Path) -> dict:
    return {
        "license": SOURCE_LICENSE,
        "generator": "dev-docs/experiments/mf2-inflection/ar_pack_audit.py",
        "sources": [
            stable_source(dictionary, UNICODE_DICTIONARY_RESOURCE_PREFIX),
            stable_source(inflectional, UNICODE_DICTIONARY_RESOURCE_PREFIX),
            stable_source(pronouns, INFLECTION_RESOURCE_PREFIX),
        ],
        "sourceLabels": [
            f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}",
            f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{inflectional.name}",
            f"{INFLECTION_RESOURCE_PREFIX}/{pronouns.name}",
        ],
    }


def pattern_id_counts(entries: list[DictionaryEntry]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for entry in entries:
        if entry.inflections:
            counts.update(entry.inflections)
        else:
            counts["<missing>"] += 1
    return counts


def build_report(
    dictionary: Path, inflectional: Path, pronouns: Path, max_samples: int
) -> dict:
    entries, skipped_lines = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    pronoun_rows = parse_pronouns(pronouns)

    term_entries = [entry for entry in entries if term_entry(entry)]
    agreement_entries = [entry for entry in entries if agreement_entry(entry)]
    base_candidates = [
        entry
        for entry in term_entries
        if "singular" in entry_numbers(entry) and "nominative" in entry_cases(entry)
    ]
    construct_entries = [
        entry for entry in agreement_entries if "construct" in entry_definiteness(entry)
    ]
    dual_entries = [entry for entry in agreement_entries if "dual" in entry_numbers(entry)]
    fully_specified_entries = [
        entry
        for entry in agreement_entries
        if entry_cases(entry)
        and entry_genders(entry)
        and entry_numbers(entry)
        and entry_definiteness(entry)
    ]
    missing_or_ambiguous_gender_entries = [
        entry for entry in agreement_entries if len(entry_genders(entry)) != 1
    ]
    agreement_ambiguous, ambiguity_reason_counts = ambiguous_surfaces(
        agreement_entries, max_samples
    )
    pattern_ids = set(pattern_id_counts(agreement_entries)) - {"<missing>"}
    missing_patterns = sorted(pattern_id for pattern_id in pattern_ids if pattern_id not in patterns)
    used_patterns = [
        patterns[pattern_id] for pattern_id in sorted(pattern_ids) if pattern_id in patterns
    ]
    term_pattern_ids = set(pattern_id_counts(term_entries)) - {"<missing>"}
    term_patterns = [
        patterns[pattern_id] for pattern_id in sorted(term_pattern_ids) if pattern_id in patterns
    ]
    all_pattern_stats = pattern_slot_stats(list(patterns.values()))
    used_agreement_pattern_stats = pattern_slot_stats(used_patterns)
    used_term_pattern_stats = pattern_slot_stats(term_patterns)
    verb_bearing_term_pattern_slots = sum(
        used_term_pattern_stats["slotAttributes"].get("verb-type", {}).values()
    )

    known_grammemes = (
        PART_OF_SPEECH_VALUES
        | CASE_VALUES
        | GENDER_VALUES
        | NUMBER_VALUES
        | DEFINITENESS_VALUES
        | ANIMACY_VALUES
        | {
            "active",
            "adjective",
            "adverb",
            "collective",
            "countable",
            "dual",
            "first",
            "future",
            "imperative",
            "imperfect",
            "passive",
            "past",
            "perfect",
            "second",
            "third",
        }
    )
    unknown_grammemes: Counter[str] = Counter()
    for entry in entries:
        unknown_grammemes.update(
            grammeme for grammeme in entry.grammemes if grammeme not in known_grammemes
        )

    return {
        "schema": SCHEMA,
        "locale": "ar",
        "description": "Generator-side audit for Arabic Unicode Inflection data before choosing a runtime pack family.",
        "provenance": pack_provenance(dictionary, inflectional, pronouns),
        "counts": {
            "dictionaryEntries": len(entries),
            "skippedDictionaryLines": len(skipped_lines),
            "termEntries": len(term_entries),
            "agreementEntries": len(agreement_entries),
            "baseCandidateEntries": len(base_candidates),
            "dualAgreementEntries": len(dual_entries),
            "constructAgreementEntries": len(construct_entries),
            "fullySpecifiedAgreementEntries": len(fully_specified_entries),
            "inflectionPatterns": len(patterns),
            "usedAgreementPatterns": len(used_patterns),
            "usedTermPatterns": len(term_patterns),
            "missingAgreementPatterns": len(missing_patterns),
            "ambiguousAgreementSurfaces": len(agreement_ambiguous),
        },
        "features": {
            "partOfSpeech": count_values(entries, entry_pos),
            "termPartOfSpeech": count_values(term_entries, entry_pos),
            "case": count_values(agreement_entries, entry_cases),
            "gender": count_values(agreement_entries, entry_genders),
            "number": count_values(agreement_entries, entry_numbers),
            "definiteness": count_values(agreement_entries, entry_definiteness),
            "animacy": count_values(agreement_entries, entry_animacy),
            "agreementInflections": dict(sorted(pattern_id_counts(agreement_entries).items())),
            "unknownGrammemes": dict(sorted(unknown_grammemes.items())),
            "ambiguityReasons": dict(sorted(ambiguity_reason_counts.items())),
        },
        "patterns": {
            "all": all_pattern_stats,
            "usedAgreement": used_agreement_pattern_stats,
            "usedTerms": used_term_pattern_stats,
        },
        "packPolicy": {
            "recommendation": "closed-world explicit-form pack",
            "reason": "Arabic audit has high surface ambiguity, low nominative base coverage, construct-state rows, dual forms, and pronoun attachment questions; V0 should validate and ship product-approved forms instead of generating open-world stems.",
            "termScope": ["noun", "proper-noun"],
            "runtimeOptions": ["case", "number", "definiteness"],
            "metadataBits": ["gender", "animacy", "partOfSpeech"],
            "caseMode": "explicit-form-key",
            "numberMode": "explicit-number-option",
            "constructMode": "explicit-form-key",
            "countMode": "no-implicit-count-to-dual",
            "pronounScope": "inventory-only-v0",
            "pronounAttachmentPolicy": "separate-arabic-attachment-profile-later",
            "adjectiveAgreementPolicy": "out-of-scope-for-v0",
            "openWorldGeneration": False,
            "baseCandidateEntries": len(base_candidates),
            "explicitFormCandidateRows": len(fully_specified_entries),
            "ambiguousSurfaceRows": len(agreement_ambiguous),
            "verbBearingTermPatternSlots": verb_bearing_term_pattern_slots,
            "reviewRequiredReasons": [
                "ambiguous-surface",
                "construct-state",
                "dual-number",
                "verb-bearing-pattern-slot",
                "missing-or-ambiguous-gender",
                "pronoun-attachment",
            ],
            "reviewRequiredEvidence": {
                "ambiguous-surface": len(agreement_ambiguous),
                "construct-state": len(construct_entries),
                "dual-number": len(dual_entries),
                "verb-bearing-pattern-slot": verb_bearing_term_pattern_slots,
                "missing-or-ambiguous-gender": len(missing_or_ambiguous_gender_entries),
                "pronoun-attachment": len(pronoun_rows),
            },
        },
        "pronouns": pronoun_stats(pronoun_rows),
        "samples": {
            "baseCandidates": [compact_entry(entry) for entry in base_candidates[:max_samples]],
            "dualAgreementEntries": [compact_entry(entry) for entry in dual_entries[:max_samples]],
            "constructAgreementEntries": [
                compact_entry(entry) for entry in construct_entries[:max_samples]
            ],
            "ambiguousAgreementSurfaces": agreement_ambiguous[:max_samples],
            "missingAgreementPatterns": missing_patterns[:max_samples],
            "pronouns": pronoun_rows[:max_samples],
        },
        "policyQuestions": [
            "Should Arabic product V0 render explicit case endings, or should fully vocalized case be authoring/prefill only?",
            "Should construct state be modeled as a first-class MF2 term option or only as explicit term forms?",
            "Should dual be selected from count categories, an explicit number option, or product-specific term forms?",
            "Should attached/object pronouns share Hindi's dependency-aware renderer or use a separate Arabic attachment profile?",
            "Should adjective agreement be in scope for V0, or should Arabic V0 stay noun/proper-noun only?",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path)
    parser.add_argument("--inflectional", required=True, type=Path)
    parser.add_argument("--pronouns", required=True, type=Path)
    parser.add_argument("--out", type=Path, help="Write JSON report to this path.")
    parser.add_argument("--max-samples", type=int, default=8)
    args = parser.parse_args()

    report = build_report(args.dictionary, args.inflectional, args.pronouns, args.max_samples)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
