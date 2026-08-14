#!/usr/bin/env python3
"""Audit Hebrew dictionary, pattern, and pronoun data before runtime design."""

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


SCHEMA = "mojito-mf2-inflection/he-pack-audit/v0"
INFLECTION_RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/inflection"
SUPPORTED_TERM_POS = {"noun", "proper-noun"}
AGREEMENT_POS = {"adjective", "noun", "proper-noun"}
PART_OF_SPEECH_VALUES = {
    "adjective",
    "adposition",
    "adverb",
    "article",
    "conjunction",
    "determiner",
    "interjection",
    "noun",
    "numeral",
    "pronoun",
    "proper-noun",
    "verb",
}
CASE_VALUES = {"accusative", "genitive", "nominative"}
GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"dual", "plural", "singular"}
DEFINITENESS_VALUES = {"construct", "definite", "indefinite"}
PERSON_VALUES = {"first", "second", "third"}
TENSE_VALUES = {"future", "past", "present"}
MOOD_VALUES = {"imperative", "jussive"}
COUNT_VALUES = {"countable", "uncountable"}
PRONOUN_CASE_VALUES = {"genitive", "nominative", "reflexive"}
APPROVED_FIXTURE_REQUIRED_FORM_KEYS = (
    "bare.singular",
    "bare.plural",
    "construct.singular",
    "construct.plural",
    "construct.dual",
)
APPROVED_FIXTURE_EXCLUDED_POS = {"adjective", "verb"}


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
            value
            for value in (element_text(node) for node in pattern_node.findall("pos"))
            if value
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


def entry_values(entry: DictionaryEntry, allowed: set[str]) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in allowed)


def entry_pos(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, PART_OF_SPEECH_VALUES)


def entry_cases(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, CASE_VALUES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, NUMBER_VALUES)


def entry_definiteness(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, DEFINITENESS_VALUES)


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
        "inflections": list(entry.inflections),
    }


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    cases = {value for entry in entries for value in entry_cases(entry)}
    genders = {value for entry in entries for value in entry_genders(entry)}
    numbers = {value for entry in entries for value in entry_numbers(entry)}
    definiteness = {value for entry in entries for value in entry_definiteness(entry)}
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
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")
    if len(poses) > 1 or any(len(entry_pos(entry)) > 1 for entry in entries):
        reasons.append("multiple-parts-of-speech")
    return reasons


def ambiguous_surfaces(
    entries: list[DictionaryEntry], max_samples: int
) -> tuple[list[dict], Counter[str]]:
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
    construct_slots = 0
    dual_slots = 0
    case_slots = 0

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
            if "case" in attrs:
                case_slots += 1
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
        "constructSlots": construct_slots,
        "dualSlots": dual_slots,
        "caseSlots": case_slots,
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
        "generator": "dev-docs/experiments/mf2-inflection/he_pack_audit.py",
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


def sample(entries: list[DictionaryEntry], max_samples: int) -> list[dict]:
    return [compact_entry(entry) for entry in entries[:max_samples]]


def approved_fixture_form_keys(entry: DictionaryEntry) -> set[str]:
    keys = set()
    numbers = set(entry_numbers(entry))
    definiteness = set(entry_definiteness(entry))
    for number in numbers:
        if number in {"singular", "plural"}:
            keys.add(f"bare.{number}")
        if "construct" in definiteness:
            keys.add(f"construct.{number}")
    return keys


def ordered_form_keys(keys: set[str]) -> list[str]:
    known = [key for key in APPROVED_FIXTURE_REQUIRED_FORM_KEYS if key in keys]
    return known + sorted(keys - set(APPROVED_FIXTURE_REQUIRED_FORM_KEYS))


def approved_fixture_group_summary(
    inflections: tuple[str, ...],
    clean_entries: list[DictionaryEntry],
    genders: list[str],
    poses: list[str],
    form_keys: list[str],
    missing_form_keys: list[str],
    entries_by_form_key: dict[str, list[DictionaryEntry]],
    max_samples: int,
) -> dict:
    return {
        "inflections": list(inflections),
        "cleanEntries": len(clean_entries),
        "gender": genders,
        "partOfSpeech": poses,
        "formKeys": form_keys,
        "missingFormKeys": missing_form_keys,
        "sampleEntriesByFormKey": {
            key: sample(entries_by_form_key[key], max_samples)
            for key in form_keys
            if entries_by_form_key[key]
        },
    }


def approved_fixture_candidate_search(
    term_entries: list[DictionaryEntry], max_samples: int
) -> dict:
    groups_by_inflection: dict[tuple[str, ...], list[DictionaryEntry]] = defaultdict(list)
    for entry in term_entries:
        if entry.inflections:
            groups_by_inflection[entry.inflections].append(entry)

    complete_clean_groups = 0
    construct_dual_clean_groups = []
    near_complete_clean_groups = []
    max_observed_clean_form_keys = 0
    required_keys = set(APPROVED_FIXTURE_REQUIRED_FORM_KEYS)

    for inflections, group in sorted(groups_by_inflection.items()):
        clean_entries = [
            entry
            for entry in group
            if set(entry_pos(entry)) & SUPPORTED_TERM_POS
            and not (set(entry_pos(entry)) & APPROVED_FIXTURE_EXCLUDED_POS)
        ]
        if not clean_entries:
            continue

        genders = sorted({value for entry in clean_entries for value in entry_genders(entry)})
        poses = sorted({value for entry in clean_entries for value in entry_pos(entry)})
        entries_by_form_key: dict[str, list[DictionaryEntry]] = defaultdict(list)
        for entry in clean_entries:
            for form_key in approved_fixture_form_keys(entry):
                entries_by_form_key[form_key].append(entry)

        form_keys = ordered_form_keys(set(entries_by_form_key))
        max_observed_clean_form_keys = max(max_observed_clean_form_keys, len(form_keys))
        missing_form_keys = [
            form_key for form_key in APPROVED_FIXTURE_REQUIRED_FORM_KEYS if form_key not in form_keys
        ]
        single_gender_and_pos = len(genders) == 1 and len(poses) == 1
        if single_gender_and_pos and not missing_form_keys:
            complete_clean_groups += 1

        summary = approved_fixture_group_summary(
            inflections,
            clean_entries,
            genders,
            poses,
            form_keys,
            missing_form_keys,
            entries_by_form_key,
            max_samples,
        )
        if single_gender_and_pos and "construct.dual" in form_keys:
            construct_dual_clean_groups.append(summary)
        if single_gender_and_pos and missing_form_keys and len(form_keys) >= 4:
            near_complete_clean_groups.append(summary)

    near_complete_clean_groups.sort(
        key=lambda group: (-len(group["formKeys"]), tuple(group["inflections"]))
    )

    return {
        "requiredFormKeys": list(APPROVED_FIXTURE_REQUIRED_FORM_KEYS),
        "cleanPartOfSpeech": sorted(SUPPORTED_TERM_POS),
        "excludedPartOfSpeech": sorted(APPROVED_FIXTURE_EXCLUDED_POS),
        "singleGenderRequired": True,
        "singlePartOfSpeechRequired": True,
        "completeCleanGroups": complete_clean_groups,
        "constructDualCleanGroups": len(construct_dual_clean_groups),
        "nearCompleteCleanGroups": len(near_complete_clean_groups),
        "maxObservedCleanFormKeys": max_observed_clean_form_keys,
        "constructDualCleanGroupSamples": construct_dual_clean_groups[:max_samples],
        "nearCompleteCleanGroupSamples": near_complete_clean_groups[:max_samples],
    }


def build_report(
    dictionary: Path, inflectional: Path, pronouns: Path, max_samples: int
) -> dict:
    entries, skipped_lines = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    pronoun_rows = parse_pronouns(pronouns)

    term_entries = [entry for entry in entries if term_entry(entry)]
    agreement_entries = [entry for entry in entries if agreement_entry(entry)]
    construct_entries = [
        entry for entry in agreement_entries if "construct" in entry_definiteness(entry)
    ]
    dual_entries = [entry for entry in agreement_entries if "dual" in entry_numbers(entry)]
    case_entries = [entry for entry in agreement_entries if entry_cases(entry)]
    term_ambiguous, ambiguity_reason_counts = ambiguous_surfaces(term_entries, max_samples)
    agreement_pattern_ids = set(pattern_id_counts(agreement_entries)) - {"<missing>"}
    missing_patterns = sorted(
        pattern_id for pattern_id in agreement_pattern_ids if pattern_id not in patterns
    )
    used_agreement_patterns = [
        patterns[pattern_id]
        for pattern_id in sorted(agreement_pattern_ids)
        if pattern_id in patterns
    ]
    term_pattern_ids = set(pattern_id_counts(term_entries)) - {"<missing>"}
    used_term_patterns = [
        patterns[pattern_id] for pattern_id in sorted(term_pattern_ids) if pattern_id in patterns
    ]

    known_grammemes = (
        PART_OF_SPEECH_VALUES
        | CASE_VALUES
        | GENDER_VALUES
        | NUMBER_VALUES
        | DEFINITENESS_VALUES
        | PERSON_VALUES
        | TENSE_VALUES
        | MOOD_VALUES
        | COUNT_VALUES
    )
    unknown_grammemes: Counter[str] = Counter()
    for entry in entries:
        unknown_grammemes.update(
            grammeme for grammeme in entry.grammemes if grammeme not in known_grammemes
        )

    return {
        "schema": SCHEMA,
        "locale": "he",
        "description": "Generator-side audit for Hebrew Unicode Inflection data before choosing a runtime pack family.",
        "provenance": pack_provenance(dictionary, inflectional, pronouns),
        "counts": {
            "dictionaryEntries": len(entries),
            "skippedDictionaryLines": len(skipped_lines),
            "uniqueSurfaces": len({entry.normalized_surface for entry in entries}),
            "termEntries": len(term_entries),
            "termSurfaces": len({entry.normalized_surface for entry in term_entries}),
            "agreementEntries": len(agreement_entries),
            "agreementSurfaces": len(
                {entry.normalized_surface for entry in agreement_entries}
            ),
            "constructAgreementEntries": len(construct_entries),
            "dualAgreementEntries": len(dual_entries),
            "caseTaggedAgreementEntries": len(case_entries),
            "inflectionPatterns": len(patterns),
            "usedAgreementPatterns": len(used_agreement_patterns),
            "usedTermPatterns": len(used_term_patterns),
            "missingAgreementPatterns": len(missing_patterns),
            "ambiguousTermSurfaces": len(term_ambiguous),
        },
        "approvedFixtureCandidateSearch": approved_fixture_candidate_search(
            term_entries, max_samples
        ),
        "features": {
            "partOfSpeech": count_values(entries, entry_pos),
            "termPartOfSpeech": count_values(term_entries, entry_pos),
            "agreementPartOfSpeech": count_values(agreement_entries, entry_pos),
            "case": count_values(agreement_entries, entry_cases),
            "gender": count_values(agreement_entries, entry_genders),
            "number": count_values(agreement_entries, entry_numbers),
            "definiteness": count_values(agreement_entries, entry_definiteness),
            "agreementInflections": dict(sorted(pattern_id_counts(agreement_entries).items())),
            "unknownGrammemes": dict(sorted(unknown_grammemes.items())),
            "ambiguityReasons": dict(sorted(ambiguity_reason_counts.items())),
        },
        "patterns": {
            "all": pattern_slot_stats(list(patterns.values())),
            "usedAgreement": pattern_slot_stats(used_agreement_patterns),
            "usedTerms": pattern_slot_stats(used_term_patterns),
        },
        "packPolicy": {
            "recommendation": "closed-world construct-state explicit-form pack",
            "reason": "Hebrew data is rich in gender/number and construct-state forms but has almost no noun case rows; V0 should not inherit Arabic case handling.",
            "termScope": ["noun", "proper-noun"],
            "runtimeOptions": ["number", "definiteness"],
            "metadataBits": ["gender", "partOfSpeech"],
            "caseMode": "unsupported-for-nouns-v0",
            "constructMode": "explicit-form-key",
            "articleMode": "not-derived-from-dictionary-v0",
            "numberMode": "explicit-number-option",
            "countMode": "singular-plural-only-by-product-policy",
            "pronounScope": "inventory-only-v0",
            "pronounAttachmentPolicy": "separate-hebrew-attachment-profile-later",
            "openWorldGeneration": False,
        },
        "pronouns": pronoun_stats(pronoun_rows),
        "samples": {
            "constructAgreementEntries": sample(construct_entries, max_samples),
            "dualAgreementEntries": sample(dual_entries, max_samples),
            "caseTaggedAgreementEntries": sample(case_entries, max_samples),
            "ambiguousTermSurfaces": term_ambiguous[:max_samples],
            "missingAgreementPatterns": missing_patterns[:max_samples],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--inflectional", type=Path, required=True)
    parser.add_argument("--pronouns", type=Path, required=True)
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
