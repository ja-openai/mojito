#!/usr/bin/env python3
"""Audit Germanic/Nordic Unicode data before choosing MF2 runtime pack shapes."""

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


SCHEMA = "mojito-mf2-inflection/germanic-nordic-pack-audit/v0"
DEFAULT_LOCALES = ("da", "nb", "nl", "sv")
DEFAULT_CACHE_DIR = Path("/Users/ja/.cache/mf2-inflection-data")
DEFAULT_UNICODE_ROOT = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection"
)
INFLECTION_RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/inflection"

TERM_POS = {"noun", "proper-noun"}
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
    "particle",
    "pronoun",
    "proper-noun",
    "verb",
}
CASE_VALUES = {"accusative", "dative", "genitive", "nominative"}
GENDER_VALUES = {"common", "feminine", "masculine", "neuter"}
NUMBER_VALUES = {"plural", "singular"}
DEFINITENESS_VALUES = {"definite", "indefinite"}
PERSON_VALUES = {"first", "second", "third"}
DEGREE_VALUES = {"comparative", "positive", "superlative"}
REGISTER_VALUES = {"formal", "informal"}
POLARITY_VALUES = {"negative"}
OTHER_KNOWN_VALUES = {
    "abbreviation",
    "animate",
    "diminutive",
    "exclusive",
    "inclusive",
    "invariant",
    "participle",
    "passive",
    "past",
    "present",
    "reflexive",
    "stressed",
    "unstressed",
}
PREFIXED_KNOWN_VALUES = ("dependency=", "determination=", "sizeness=")


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


def entry_degree(entry: DictionaryEntry) -> tuple[str, ...]:
    return entry_values(entry, DEGREE_VALUES)


def term_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry_pos(entry)) & TERM_POS)


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
        "degree": list(entry_degree(entry)),
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
) -> tuple[list[dict], Counter[str], int]:
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in entries:
        by_surface[entry.normalized_surface].append(entry)

    ambiguous = []
    reason_counts: Counter[str] = Counter()
    ambiguous_count = 0
    for surface, group in sorted(by_surface.items()):
        reasons = ambiguity_reasons(group)
        if not reasons:
            continue
        ambiguous_count += 1
        reason_counts.update(reasons)
        if len(ambiguous) < max_samples:
            ambiguous.append(
                {
                    "surface": surface,
                    "entries": len(group),
                    "reasons": reasons,
                    "sampleEntries": [compact_entry(entry) for entry in group[:max_samples]],
                }
            )
    return ambiguous, reason_counts, ambiguous_count


def pattern_slot_stats(patterns: list[InflectionPattern]) -> dict:
    slot_attrs: dict[str, Counter[str]] = defaultdict(Counter)
    slots_per_pattern: Counter[int] = Counter()
    duplicate_slot_patterns = 0
    duplicate_slot_rows = 0
    case_slots = 0
    definiteness_slots = 0
    gender_slots = 0
    number_slots = 0

    for pattern in patterns:
        slots_per_pattern[len(pattern.slots)] += 1
        seen: Counter[tuple[tuple[str, str], ...]] = Counter()
        for slot in pattern.slots:
            attrs = dict(slot.attrs)
            seen[slot.attrs] += 1
            for key, value in slot.attrs:
                slot_attrs[key][value] += 1
            if "case" in attrs:
                case_slots += 1
            if "definiteness" in attrs:
                definiteness_slots += 1
            if "gender" in attrs:
                gender_slots += 1
            if "number" in attrs:
                number_slots += 1
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
        "caseSlots": case_slots,
        "definitenessSlots": definiteness_slots,
        "genderSlots": gender_slots,
        "numberSlots": number_slots,
    }


def pronoun_stats(pronoun_rows: list[dict]) -> dict:
    feature_counts: Counter[str] = Counter()
    case_counts: Counter[str] = Counter()
    gender_counts: Counter[str] = Counter()
    number_counts: Counter[str] = Counter()
    person_counts: Counter[str] = Counter()
    definiteness_counts: Counter[str] = Counter()
    dependency_counts: Counter[str] = Counter()
    for row in pronoun_rows:
        features = set(row["features"])
        feature_counts.update(features)
        case_counts.update(features & CASE_VALUES)
        gender_counts.update(features & GENDER_VALUES)
        number_counts.update(features & NUMBER_VALUES)
        person_counts.update(features & PERSON_VALUES)
        definiteness_counts.update(features & DEFINITENESS_VALUES)
        dependency_counts.update(
            feature for feature in features if feature.startswith("dependency=")
        )
    return {
        "rows": len(pronoun_rows),
        "uniqueValues": len({row["value"] for row in pronoun_rows}),
        "features": dict(sorted(feature_counts.items())),
        "cases": dict(sorted(case_counts.items())),
        "genders": dict(sorted(gender_counts.items())),
        "numbers": dict(sorted(number_counts.items())),
        "persons": dict(sorted(person_counts.items())),
        "definiteness": dict(sorted(definiteness_counts.items())),
        "dependencies": dict(sorted(dependency_counts.items())),
    }


def stable_source(path: Path, resource_prefix: str) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    metadata["path"] = f"{resource_prefix}/{path.name}"
    return metadata


def pack_provenance(locale: str, dictionary: Path, inflectional: Path, pronouns: Path) -> dict:
    return {
        "license": SOURCE_LICENSE,
        "generator": "dev-docs/experiments/mf2-inflection/germanic_nordic_pack_audit.py",
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
        "locale": locale,
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


def unknown_grammeme_counts(entries: list[DictionaryEntry]) -> dict[str, int]:
    known = (
        PART_OF_SPEECH_VALUES
        | CASE_VALUES
        | GENDER_VALUES
        | NUMBER_VALUES
        | DEFINITENESS_VALUES
        | PERSON_VALUES
        | DEGREE_VALUES
        | REGISTER_VALUES
        | POLARITY_VALUES
        | OTHER_KNOWN_VALUES
    )
    unknown: Counter[str] = Counter()
    for entry in entries:
        unknown.update(
            grammeme
            for grammeme in entry.grammemes
            if grammeme not in known
            and not any(grammeme.startswith(prefix) for prefix in PREFIXED_KNOWN_VALUES)
        )
    return dict(sorted(unknown.items()))


def pack_policy(locale: str) -> dict:
    policies = {
        "da": {
            "recommendation": "closed-world explicit genitive/definiteness pack first",
            "reason": "Danish data has broad nominative/genitive, number, common/neuter gender, and definite/indefinite coverage; runtime should validate explicit product terms before trying article or suffix composition.",
            "runtimeOptions": ["case", "number", "definiteness"],
            "metadataBits": ["gender", "partOfSpeech"],
            "caseMode": "nominative-genitive-explicit-form-key",
            "articleMode": "defer-composition-until-product-term-policy",
            "openWorldGeneration": False,
        },
        "nb": {
            "recommendation": "closed-world definiteness/gender metadata pack first",
            "reason": "Norwegian Bokmål has useful masculine/feminine/neuter, singular/plural, and definite/indefinite data, but almost no noun case coverage.",
            "runtimeOptions": ["number", "definiteness"],
            "metadataBits": ["gender", "partOfSpeech"],
            "caseMode": "unsupported-for-nouns-v0",
            "articleMode": "defer-article-and-suffix-composition",
            "openWorldGeneration": False,
        },
        "nl": {
            "recommendation": "metadata-and-diminutive audit before case runtime",
            "reason": "Dutch data is much smaller, noun case and definiteness are sparse, and the interesting dictionary signal is gender/number plus diminutive metadata.",
            "runtimeOptions": ["number"],
            "metadataBits": ["gender", "partOfSpeech", "sizeness"],
            "caseMode": "inventory-only-v0",
            "articleMode": "defer-composition",
            "openWorldGeneration": False,
        },
        "sv": {
            "recommendation": "closed-world explicit genitive/definiteness pack first",
            "reason": "Swedish data has broad nominative/genitive, common/neuter, singular/plural, and definite/indefinite coverage; it resembles Danish more than the no-case Bokmål path.",
            "runtimeOptions": ["case", "number", "definiteness"],
            "metadataBits": ["gender", "partOfSpeech"],
            "caseMode": "nominative-genitive-explicit-form-key",
            "articleMode": "defer-composition-until-product-term-policy",
            "openWorldGeneration": False,
        },
    }
    return policies[locale] | {"termScope": sorted(TERM_POS), "pronounScope": "inventory-only-v0"}


def build_locale_report(
    locale: str, dictionary: Path, inflectional: Path, pronouns: Path, max_samples: int
) -> dict:
    entries, skipped_lines = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    pronoun_rows = parse_pronouns(pronouns)

    term_entries = [entry for entry in entries if term_entry(entry)]
    agreement_entries = [entry for entry in entries if agreement_entry(entry)]
    case_entries = [entry for entry in agreement_entries if entry_cases(entry)]
    definite_entries = [
        entry for entry in agreement_entries if "definite" in entry_definiteness(entry)
    ]
    genitive_entries = [entry for entry in agreement_entries if "genitive" in entry_cases(entry)]
    diminutive_entries = [
        entry
        for entry in entries
        if "diminutive" in entry.grammemes or "sizeness=diminutive" in entry.grammemes
    ]
    ambiguous_terms, ambiguity_reason_counts, ambiguous_term_count = ambiguous_surfaces(
        term_entries, max_samples
    )

    agreement_pattern_ids = set(pattern_id_counts(agreement_entries)) - {"<missing>"}
    used_agreement_patterns = [
        patterns[pattern_id]
        for pattern_id in sorted(agreement_pattern_ids)
        if pattern_id in patterns
    ]
    term_pattern_ids = set(pattern_id_counts(term_entries)) - {"<missing>"}
    used_term_patterns = [
        patterns[pattern_id] for pattern_id in sorted(term_pattern_ids) if pattern_id in patterns
    ]
    missing_patterns = sorted(
        pattern_id for pattern_id in agreement_pattern_ids if pattern_id not in patterns
    )

    return {
        "locale": locale,
        "description": "Generator-side audit for Unicode Inflection data before choosing an MF2 runtime pack family.",
        "provenance": pack_provenance(locale, dictionary, inflectional, pronouns),
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
            "caseTaggedAgreementEntries": len(case_entries),
            "definiteAgreementEntries": len(definite_entries),
            "genitiveAgreementEntries": len(genitive_entries),
            "diminutiveEntries": len(diminutive_entries),
            "inflectionPatterns": len(patterns),
            "usedAgreementPatterns": len(used_agreement_patterns),
            "usedTermPatterns": len(used_term_patterns),
            "missingAgreementPatterns": len(missing_patterns),
            "ambiguousTermSurfaces": ambiguous_term_count,
        },
        "features": {
            "partOfSpeech": count_values(entries, entry_pos),
            "termPartOfSpeech": count_values(term_entries, entry_pos),
            "agreementPartOfSpeech": count_values(agreement_entries, entry_pos),
            "case": count_values(agreement_entries, entry_cases),
            "gender": count_values(agreement_entries, entry_genders),
            "number": count_values(agreement_entries, entry_numbers),
            "definiteness": count_values(agreement_entries, entry_definiteness),
            "degree": count_values(agreement_entries, entry_degree),
            "agreementInflections": dict(sorted(pattern_id_counts(agreement_entries).items())),
            "unknownGrammemes": unknown_grammeme_counts(entries),
            "ambiguityReasons": dict(sorted(ambiguity_reason_counts.items())),
        },
        "patterns": {
            "all": pattern_slot_stats(list(patterns.values())),
            "usedAgreement": pattern_slot_stats(used_agreement_patterns),
            "usedTerms": pattern_slot_stats(used_term_patterns),
        },
        "packPolicy": pack_policy(locale),
        "pronouns": pronoun_stats(pronoun_rows),
        "samples": {
            "caseTaggedAgreementEntries": sample(case_entries, max_samples),
            "definiteAgreementEntries": sample(definite_entries, max_samples),
            "genitiveAgreementEntries": sample(genitive_entries, max_samples),
            "diminutiveEntries": sample(diminutive_entries, max_samples),
            "ambiguousTermSurfaces": ambiguous_terms[:max_samples],
            "missingAgreementPatterns": missing_patterns[:max_samples],
        },
    }


def build_report(
    locales: tuple[str, ...], cache_dir: Path, unicode_root: Path, max_samples: int
) -> dict:
    locale_reports = []
    for locale in locales:
        dictionary = cache_dir / f"dictionary_{locale}.lst"
        inflectional = cache_dir / f"inflectional_{locale}.xml"
        pronouns = unicode_root / "inflection" / f"pronoun_{locale}.csv"
        locale_reports.append(
            build_locale_report(locale, dictionary, inflectional, pronouns, max_samples)
        )
    return {
        "schema": SCHEMA,
        "description": "Cross-locale Germanic/Nordic data audit for Mojito MF2 native inflection planning.",
        "cacheDir": str(cache_dir),
        "unicodeRoot": str(unicode_root),
        "summary": {
            "localeCount": len(locale_reports),
            "locales": list(locales),
            "caseRuntimeCandidateLocales": [
                report["locale"]
                for report in locale_reports
                if report["packPolicy"]["caseMode"]
                == "nominative-genitive-explicit-form-key"
            ],
            "metadataFirstLocales": [
                report["locale"]
                for report in locale_reports
                if report["packPolicy"]["recommendation"].startswith("closed-world definiteness")
                or report["packPolicy"]["recommendation"].startswith("metadata-and-diminutive")
            ],
        },
        "locales": locale_reports,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--locale", action="append", choices=DEFAULT_LOCALES)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--unicode-root", type=Path, default=DEFAULT_UNICODE_ROOT)
    parser.add_argument("--out", type=Path, help="Write JSON report to this path.")
    parser.add_argument("--max-samples", type=int, default=8)
    args = parser.parse_args()

    locales = tuple(args.locale) if args.locale else DEFAULT_LOCALES
    report = build_report(locales, args.cache_dir, args.unicode_root, args.max_samples)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
