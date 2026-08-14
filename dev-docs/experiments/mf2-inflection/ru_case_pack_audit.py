#!/usr/bin/env python3
"""Audit Russian noun/proper-noun case data before choosing a compact pack."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import SOURCE_LICENSE, UNICODE_DICTIONARY_RESOURCE_PREFIX, pack_source_metadata


CASE_GRAMMEMES = {
    "accusative",
    "dative",
    "genitive",
    "instrumental",
    "nominative",
    "prepositional",
}
GENDER_GRAMMEMES = {"masculine", "feminine", "neuter"}
NUMBER_GRAMMEMES = {"singular", "plural"}
ANIMACY_GRAMMEMES = {"animate", "inanimate"}
SUPPORTED_POS = {"noun", "proper-noun"}
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2, "neuter": 3}
NUMBER_BITS = {"singular": 1, "plural": 2}
EXPECTED_CASE_FORM_KEYS = {
    f"{grammatical_case}.{number}"
    for grammatical_case in CASE_GRAMMEMES
    for number in NUMBER_GRAMMEMES
}


@dataclass(frozen=True)
class PatternSlot:
    grammatical_case: str | None
    gender: str | None
    number: str | None
    animacy: str | None
    template: str


@dataclass(frozen=True)
class InflectionPattern:
    name: str
    part_of_speech: str
    suffix: str
    words: int
    slots: tuple[PatternSlot, ...]


@dataclass(frozen=True)
class CaseFormTerm:
    term_id: str
    text: str
    part_of_speech: str
    gender: str
    number: str
    animacy: str | None
    inflection_pattern: str
    stem: str
    forms: tuple[tuple[str, str], ...]


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
                        inflection.attrib.get("case"),
                        inflection.attrib.get("gender"),
                        inflection.attrib.get("number"),
                        inflection.attrib.get("animacy"),
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


def entry_cases(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in CASE_GRAMMEMES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in GENDER_GRAMMEMES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_GRAMMEMES)


def entry_animacy(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in ANIMACY_GRAMMEMES)


def supported_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry.parts_of_speech()) & SUPPORTED_POS)


def signature(entry: DictionaryEntry) -> tuple:
    return (
        tuple(sorted(entry.parts_of_speech())),
        tuple(sorted(entry_cases(entry))),
        tuple(sorted(entry_genders(entry))),
        tuple(sorted(entry_numbers(entry))),
        tuple(sorted(entry_animacy(entry))),
        tuple(sorted(entry.inflections)),
    )


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    signatures = {signature(entry) for entry in entries}
    cases = {value for entry in entries for value in entry_cases(entry)}
    genders = {value for entry in entries for value in entry_genders(entry)}
    numbers = {value for entry in entries for value in entry_numbers(entry)}
    animacy = {value for entry in entries for value in entry_animacy(entry)}
    inflections = {value for entry in entries for value in entry.inflections}
    parts_of_speech = {value for entry in entries for value in entry.parts_of_speech()}

    reasons = []
    if len(signatures) > 1:
        reasons.append("multiple-analyses")
    if len(cases) > 1 or any(len(entry_cases(entry)) > 1 for entry in entries):
        reasons.append("multiple-cases")
    if len(genders) > 1 or any(len(entry_genders(entry)) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if len(numbers) > 1 or any(len(entry_numbers(entry)) > 1 for entry in entries):
        reasons.append("multiple-numbers")
    if len(animacy) > 1 or any(len(entry_animacy(entry)) > 1 for entry in entries):
        reasons.append("multiple-animacy")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")
    if len(parts_of_speech) > 1:
        reasons.append("multiple-parts-of-speech")
    return reasons


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
        "partOfSpeech": list(entry.parts_of_speech()),
        "case": list(entry_cases(entry)),
        "gender": list(entry_genders(entry)),
        "number": list(entry_numbers(entry)),
        "animacy": list(entry_animacy(entry)),
        "inflections": list(entry.inflections),
    }


def slot_key(slot: PatternSlot) -> tuple[str | None, str | None, str | None, str | None]:
    return (slot.grammatical_case, slot.gender, slot.number, slot.animacy)


def pattern_slot_stats(patterns: list[InflectionPattern]) -> dict:
    duplicate_slot_patterns = 0
    duplicate_slot_rows = 0
    slot_cases: Counter[str] = Counter()
    slot_genders: Counter[str] = Counter()
    slot_numbers: Counter[str] = Counter()
    slot_animacy: Counter[str] = Counter()
    max_slots = 0

    for pattern in patterns:
        max_slots = max(max_slots, len(pattern.slots))
        seen: Counter[tuple[str | None, str | None, str | None, str | None]] = Counter()
        for slot in pattern.slots:
            seen[slot_key(slot)] += 1
            slot_cases[slot.grammatical_case or "<missing>"] += 1
            slot_genders[slot.gender or "<missing>"] += 1
            slot_numbers[slot.number or "<missing>"] += 1
            slot_animacy[slot.animacy or "<missing>"] += 1
        duplicate_rows = sum(count - 1 for count in seen.values() if count > 1)
        if duplicate_rows:
            duplicate_slot_patterns += 1
            duplicate_slot_rows += duplicate_rows

    return {
        "patternSlotCases": dict(sorted(slot_cases.items())),
        "patternSlotGenders": dict(sorted(slot_genders.items())),
        "patternSlotNumbers": dict(sorted(slot_numbers.items())),
        "patternSlotAnimacy": dict(sorted(slot_animacy.items())),
        "maxSlotsPerPattern": max_slots,
        "patternsWithDuplicateSlots": duplicate_slot_patterns,
        "duplicateSlotRows": duplicate_slot_rows,
    }


def stem_for(surface: str, pattern: InflectionPattern) -> str | None:
    if not pattern.suffix:
        return surface
    if not surface.endswith(pattern.suffix):
        return None
    return surface[: -len(pattern.suffix)]


def render_template(template: str, stem: str) -> str:
    return template.replace("{stem}", stem)


def form_keys_for_entry(entry: DictionaryEntry, pattern: InflectionPattern) -> tuple[set[str], str | None]:
    genders = entry_genders(entry)
    animacy_values = entry_animacy(entry)
    if len(genders) != 1:
        return set(), "missing-or-ambiguous-gender"
    if len(entry.inflections) != 1:
        return set(), "missing-or-ambiguous-inflection"
    stem = stem_for(entry.surface, pattern)
    if stem is None:
        return set(), "suffix-mismatch"

    gender = genders[0]
    animacy = animacy_values[0] if len(animacy_values) == 1 else None
    forms: dict[str, str] = {}
    for slot in pattern.slots:
        if slot.grammatical_case not in CASE_GRAMMEMES or slot.number not in NUMBER_GRAMMEMES:
            continue
        if slot.gender is not None and slot.gender != gender:
            continue
        if slot.animacy is not None and animacy is not None and slot.animacy != animacy:
            continue
        form_key = f"{slot.grammatical_case}.{slot.number}"
        form_value = render_template(slot.template, stem)
        previous = forms.get(form_key)
        if previous is not None and previous != form_value:
            return set(forms), "conflicting-form-key"
        forms[form_key] = form_value

    missing = EXPECTED_CASE_FORM_KEYS - forms.keys()
    if missing:
        return set(forms), "missing-case-form-keys"
    return set(forms), None


def case_form_term(entry: DictionaryEntry, pattern: InflectionPattern) -> tuple[CaseFormTerm | None, str | None]:
    if not supported_entry(entry):
        return None, "unsupported-part-of-speech"
    if "nominative" not in entry_cases(entry):
        return None, "not-nominative"
    if "singular" not in entry_numbers(entry):
        return None, "not-singular"
    if len(entry_genders(entry)) != 1:
        return None, "missing-or-ambiguous-gender"
    if len(entry.inflections) != 1:
        return None, "missing-or-ambiguous-inflection"
    if pattern.part_of_speech not in SUPPORTED_POS:
        return None, "unsupported-pattern-part-of-speech"

    stem = stem_for(entry.surface, pattern)
    if stem is None:
        return None, "suffix-mismatch"

    gender = entry_genders(entry)[0]
    animacy_values = entry_animacy(entry)
    animacy = animacy_values[0] if len(animacy_values) == 1 else None
    forms: dict[str, str] = {}
    for slot in pattern.slots:
        if slot.grammatical_case not in CASE_GRAMMEMES or slot.number not in NUMBER_GRAMMEMES:
            continue
        if slot.gender is not None and slot.gender != gender:
            continue
        if slot.animacy is not None and animacy is not None and slot.animacy != animacy:
            continue
        form_key = f"{slot.grammatical_case}.{slot.number}"
        form_value = render_template(slot.template, stem)
        previous = forms.get(form_key)
        if previous is not None and previous != form_value:
            return None, "conflicting-form-key"
        forms[form_key] = form_value

    missing = EXPECTED_CASE_FORM_KEYS - forms.keys()
    if missing:
        return None, "missing-case-form-keys"

    part_of_speech = sorted(set(entry.parts_of_speech()) & SUPPORTED_POS)[0]
    return (
        CaseFormTerm(
            term_id_for(entry),
            entry.surface,
            part_of_speech,
            gender,
            "singular",
            animacy,
            pattern.name,
            stem,
            tuple(sorted(forms.items())),
        ),
        None,
    )


def conflicting_form_details(entry: DictionaryEntry, pattern: InflectionPattern, limit: int) -> list[dict]:
    genders = entry_genders(entry)
    stem = stem_for(entry.surface, pattern)
    if len(genders) != 1 or stem is None:
        return []

    animacy_values = entry_animacy(entry)
    gender = genders[0]
    animacy = animacy_values[0] if len(animacy_values) == 1 else None
    forms: dict[str, str] = {}
    conflicts = []
    for slot in pattern.slots:
        if slot.grammatical_case not in CASE_GRAMMEMES or slot.number not in NUMBER_GRAMMEMES:
            continue
        if slot.gender is not None and slot.gender != gender:
            continue
        if slot.animacy is not None and animacy is not None and slot.animacy != animacy:
            continue
        form_key = f"{slot.grammatical_case}.{slot.number}"
        form_value = render_template(slot.template, stem)
        previous = forms.get(form_key)
        if previous is not None and previous != form_value:
            conflicts.append(
                {
                    "formKey": form_key,
                    "firstValue": previous,
                    "variantValue": form_value,
                    "variantTemplate": slot.template,
                }
            )
            if len(conflicts) >= limit:
                break
        forms.setdefault(form_key, form_value)
    return conflicts


def term_id_for(entry: DictionaryEntry) -> str:
    normalized = entry.normalized_surface.replace(" ", "_")
    return f"ru.case.{normalized}"


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


def estimate_bytes(candidate_entries: list[DictionaryEntry], complete_case_form_candidates: int) -> dict:
    string_pool_bytes = sum(len(entry.normalized_surface.encode("utf-8")) + 1 for entry in candidate_entries)
    metadata_row_bytes = len(candidate_entries) * 12
    eager_case_form_rows = complete_case_form_candidates * len(EXPECTED_CASE_FORM_KEYS)
    eager_case_form_row_bytes = eager_case_form_rows * 12
    return {
        "metadataStringPoolBytes": string_pool_bytes,
        "metadataRowBytes": metadata_row_bytes,
        "metadataLowerBoundBytes": string_pool_bytes + metadata_row_bytes,
        "caseFormRowsIfEager": eager_case_form_rows,
        "caseFormRowBytesIfEager": eager_case_form_row_bytes,
    }


def case_form_export_candidates(
    entries: list[DictionaryEntry],
    patterns: dict[str, InflectionPattern],
) -> tuple[list[CaseFormTerm], Counter[str]]:
    terms: list[CaseFormTerm] = []
    skipped: Counter[str] = Counter()
    seen_term_ids: set[str] = set()

    for entry in entries:
        if len(entry.inflections) != 1:
            skipped["missing-or-ambiguous-inflection"] += 1
            continue
        pattern = patterns.get(entry.inflections[0])
        if pattern is None:
            skipped["missing-pattern"] += 1
            continue
        term, reason = case_form_term(entry, pattern)
        if reason is not None:
            skipped[reason] += 1
            continue
        assert term is not None
        if term.term_id in seen_term_ids:
            skipped["duplicate-term-id"] += 1
            continue
        seen_term_ids.add(term.term_id)
        terms.append(term)

    return terms, skipped


def build_case_form_pack(
    dictionary: Path,
    inflectional: Path,
    limit: int,
    requested_surfaces: list[str],
    provenance: dict,
) -> dict:
    entries, _ = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    terms, skipped = case_form_export_candidates(entries, patterns)

    terms_by_surface = {term.text.casefold(): term for term in terms}
    exported_terms: list[CaseFormTerm] = []
    exported_ids: set[str] = set()
    for surface in requested_surfaces:
        term = terms_by_surface.get(surface.casefold())
        if term is None:
            skipped[f"requested-surface-unavailable:{surface}"] += 1
            continue
        exported_terms.append(term)
        exported_ids.add(term.term_id)

    for term in terms:
        if limit > 0 and len(exported_terms) >= limit:
            break
        if term.term_id in exported_ids:
            continue
        exported_terms.append(term)
        exported_ids.add(term.term_id)

    strings: list[str] = []
    string_indexes: dict[str, int] = {}

    def string_index(value: str) -> int:
        if value not in string_indexes:
            string_indexes[value] = len(strings)
            strings.append(value)
        return string_indexes[value]

    term_rows = []
    for term in exported_terms:
        term_rows.append(
            {
                "id": string_index(term.term_id),
                "text": string_index(term.text),
                "partOfSpeech": term.part_of_speech,
                "gender": term.gender,
                "number": term.number,
                "animacy": term.animacy,
                "inflectionPattern": term.inflection_pattern,
                "stem": string_index(term.stem),
                "forms": [
                    {
                        "key": string_index(form_key),
                        "value": string_index(form_value),
                        "kind": "literal",
                    }
                    for form_key, form_value in term.forms
                ],
            }
        )

    form_rows = sum(len(term["forms"]) for term in term_rows)
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in strings)
    binary_lower_bound_bytes = string_pool_bytes + len(term_rows) * 20 + form_rows * 12
    return {
        "schema": "mojito-mf2-inflection/ru-case-form-sample-pack/v0",
        "locale": "ru",
        "description": "Generated unambiguous sample from Unicode Russian noun case patterns; variant-bearing rows are intentionally skipped for V0.",
        "provenance": provenance,
        "strings": strings,
        "terms": term_rows,
        "summary": {
            "candidateTerms": len(terms),
            "exportedTerms": len(exported_terms),
            "skippedTerms": dict(sorted(skipped.items())),
            "strings": len(strings),
            "formRows": form_rows,
            "stringPoolBytes": string_pool_bytes,
            "jsonBytes": 0,
            "binaryLowerBoundBytes": binary_lower_bound_bytes,
        },
    }


def feature_bits(term: dict) -> int:
    bits = PART_OF_SPEECH_BITS.get(term.get("partOfSpeech"), 0)
    bits |= GENDER_BITS.get(term.get("gender"), 0) << 4
    bits |= NUMBER_BITS.get(term.get("number"), 0) << 8
    return bits


def build_compiled_case_form_pack(case_form_pack: dict) -> dict:
    form_sets = []
    term_rows = []

    for term in case_form_pack["terms"]:
        form_set_index = len(form_sets)
        form_sets.append(
            {
                "term": term["id"],
                "forms": [
                    {
                        "key": form["key"],
                        "value": form["value"],
                        "kind": form["kind"],
                    }
                    for form in term["forms"]
                ],
            }
        )
        term_rows.append(
            {
                "id": term["id"],
                "text": term["text"],
                "featureBits": feature_bits(term),
                "sense": None,
                "formSet": form_set_index,
            }
        )

    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in case_form_pack["strings"])
    form_row_count = sum(len(form_set["forms"]) for form_set in form_sets)
    binary_estimate = {
        "stringPoolBytes": string_pool_bytes,
        "termRowBytes": len(term_rows) * 20,
        "formRowBytes": form_row_count * 12,
        "bindingReferenceBytes": 0,
    }
    binary_estimate["totalBytes"] = sum(binary_estimate.values())

    return {
        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
        "locale": "ru",
        "provenance": case_form_pack["provenance"],
        "strings": case_form_pack["strings"],
        "terms": term_rows,
        "formSets": form_sets,
        "diagnostics": [],
        "sizeEstimates": {
            "compactJsonBytes": 0,
            "binaryLowerBoundBytes": binary_estimate,
        },
    }


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def update_json_bytes(value: dict, path: list[str]) -> None:
    target = value
    for key in path[:-1]:
        target = target[key]
    for _ in range(3):
        payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        target[path[-1]] = len(payload.encode("utf-8"))


def build_report(dictionary: Path, inflectional: Path, max_samples: int) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    supported_entries = [entry for entry in entries if supported_entry(entry)]
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in supported_entries:
        by_surface[entry.normalized_surface].append(entry)

    pattern_ids = {inflection for entry in supported_entries for inflection in entry.inflections}
    missing_patterns = sorted(pattern_id for pattern_id in pattern_ids if pattern_id not in patterns)
    used_patterns = [patterns[pattern_id] for pattern_id in sorted(pattern_ids) if pattern_id in patterns]
    noun_patterns = [pattern for pattern in patterns.values() if pattern.part_of_speech in SUPPORTED_POS]

    ambiguous_surfaces = []
    ambiguity_reason_counts: Counter[str] = Counter()
    for surface, surface_entries in sorted(by_surface.items()):
        reasons = ambiguity_reasons(surface_entries)
        if reasons:
            ambiguity_reason_counts.update(reasons)
            ambiguous_surfaces.append(
                {
                    "surface": surface,
                    "reasons": reasons,
                    "entries": [compact_entry(entry) for entry in surface_entries[:max_samples]],
                }
            )

    nominative_singular_candidates = [
        entry
        for entry in supported_entries
        if "nominative" in entry_cases(entry) and "singular" in entry_numbers(entry)
    ]

    case_form_skip_reasons: Counter[str] = Counter()
    conflicting_form_keys: Counter[str] = Counter()
    conflicting_keys_per_term: Counter[int] = Counter()
    conflicting_samples = []
    complete_case_form_candidates = 0
    candidate_form_key_counts: Counter[int] = Counter()
    for entry in nominative_singular_candidates:
        if len(entry.inflections) != 1:
            case_form_skip_reasons["missing-or-ambiguous-inflection"] += 1
            continue
        pattern = patterns.get(entry.inflections[0])
        if pattern is None:
            case_form_skip_reasons["missing-pattern"] += 1
            continue
        if pattern.part_of_speech not in SUPPORTED_POS:
            case_form_skip_reasons["unsupported-pattern-part-of-speech"] += 1
            continue
        form_keys, reason = form_keys_for_entry(entry, pattern)
        candidate_form_key_counts[len(form_keys)] += 1
        if reason is not None:
            case_form_skip_reasons[reason] += 1
            if reason == "conflicting-form-key":
                pattern_conflicts = conflicting_form_details(entry, pattern, 6)
                conflict_keys = {conflict["formKey"] for conflict in pattern_conflicts}
                conflicting_form_keys.update(conflict_keys)
                conflicting_keys_per_term[len(conflict_keys)] += 1
                if len(conflicting_samples) < max_samples:
                    conflicting_samples.append(
                        {
                            **compact_entry(entry),
                            "inflectionPattern": pattern.name,
                            "conflicts": pattern_conflicts,
                        }
                    )
            continue
        complete_case_form_candidates += 1

    samples = {
        "nominativeSingularCandidates": [
            compact_entry(entry) for entry in nominative_singular_candidates[:max_samples]
        ],
        "ambiguousSurfaces": ambiguous_surfaces[:max_samples],
        "conflictingCaseFormCandidates": conflicting_samples,
        "missingInflectionPatterns": missing_patterns[:max_samples],
    }
    case_form_terms, case_form_blocked_reasons = case_form_export_candidates(entries, patterns)
    ambiguity_reason_map = dict(sorted(ambiguity_reason_counts.items()))

    return {
        "schema": "mojito-mf2-inflection/ru-case-pack-audit/v0",
        "locale": "ru",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/ru_case_pack_audit.py",
            "sourceLabels": [
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}",
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{inflectional.name}",
            ],
        },
        "counts": {
            "dictionaryEntries": len(entries),
            "supportedEntries": len(supported_entries),
            "uniqueSupportedSurfaces": len(by_surface),
            "skippedLines": len(skipped),
            "ambiguousSupportedSurfaces": len(ambiguous_surfaces),
            "nominativeSingularCandidates": len(nominative_singular_candidates),
            "completeCaseFormCandidates": complete_case_form_candidates,
            "dictionaryInflectionPatterns": len(pattern_ids),
            "missingInflectionPatterns": len(missing_patterns),
            "inflectionalPatterns": len(patterns),
            "nounInflectionalPatterns": len(noun_patterns),
            "usedInflectionalPatterns": len(used_patterns),
        },
        "features": {
            "case": count_values(supported_entries, entry_cases),
            "gender": count_values(supported_entries, entry_genders),
            "number": count_values(supported_entries, entry_numbers),
            "animacy": count_values(supported_entries, entry_animacy),
            "partOfSpeech": count_values(supported_entries, DictionaryEntry.parts_of_speech),
            "ambiguityReasons": ambiguity_reason_map,
            "caseFormCandidateSkipReasons": dict(sorted(case_form_skip_reasons.items())),
            "caseFormKeyCounts": {str(key): value for key, value in sorted(candidate_form_key_counts.items())},
            "conflictingFormKeys": dict(sorted(conflicting_form_keys.items())),
            "conflictingKeysPerTerm": {
                str(key): value for key, value in sorted(conflicting_keys_per_term.items())
            },
            **pattern_slot_stats(used_patterns),
        },
        "reviewPolicy": {
            "runtimeExport": "closed-world-case-forms",
            "automaticExportTerms": len(case_form_terms),
            "reviewRequiredSurfaces": len(ambiguous_surfaces),
            "blockedDictionaryEntries": sum(case_form_blocked_reasons.values()),
            "reviewRequiredReasons": ambiguity_reason_map,
            "blockedReasons": dict(sorted(case_form_blocked_reasons.items())),
        },
        "sizeEstimates": estimate_bytes(nominative_singular_candidates, complete_case_form_candidates),
        "samples": samples,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Russian dictionary .lst file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Russian inflectional XML file.")
    parser.add_argument("--max-samples", type=int, default=12, help="Maximum sample rows to include.")
    parser.add_argument("--case-form-pack-out", type=Path, help="Write a generated Russian case-form sample pack.")
    parser.add_argument("--case-form-pack-limit", type=int, default=12, help="Maximum case-form terms to export. Use 0 for all.")
    parser.add_argument(
        "--case-form-surface",
        action="append",
        default=[],
        help="Prioritize an unambiguous nominative singular surface in the exported case-form pack.",
    )
    parser.add_argument("--compiled-case-form-pack-out", type=Path, help="Write generated Russian case forms in compiled term-pack shape.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    args = parser.parse_args()

    report = build_report(args.dictionary, args.inflectional, args.max_samples)
    if args.case_form_pack_out or args.compiled_case_form_pack_out:
        case_form_pack = build_case_form_pack(
            args.dictionary,
            args.inflectional,
            args.case_form_pack_limit,
            args.case_form_surface,
            pack_provenance(
                [args.dictionary, args.inflectional],
                "dev-docs/experiments/mf2-inflection/ru_case_pack_audit.py",
            ),
        )
        update_json_bytes(case_form_pack, ["summary", "jsonBytes"])
        if args.case_form_pack_out:
            write_json(args.case_form_pack_out, case_form_pack)
        if args.compiled_case_form_pack_out:
            compiled_case_form_pack = build_compiled_case_form_pack(case_form_pack)
            update_json_bytes(compiled_case_form_pack, ["sizeEstimates", "compactJsonBytes"])
            write_json(args.compiled_case_form_pack_out, compiled_case_form_pack)

    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
