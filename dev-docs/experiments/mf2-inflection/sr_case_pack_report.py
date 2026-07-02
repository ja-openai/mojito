#!/usr/bin/env python3
"""Estimate Serbian case-form packs from Unicode dictionary and pattern data."""

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
    "locative",
    "nominative",
    "vocative",
}
GENDER_GRAMMEMES = {"masculine", "feminine", "neuter"}
NUMBER_GRAMMEMES = {"singular", "plural"}
ANIMACY_GRAMMEMES = {"animate", "inanimate"}
SUPPORTED_POS = {"noun", "proper-noun"}
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2, "neuter": 3}
NUMBER_BITS = {"singular": 1, "plural": 2}


@dataclass(frozen=True)
class PatternSlot:
    case: str | None
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


@dataclass(frozen=True)
class CaseFormTerm:
    term_id: str
    text: str
    part_of_speech: str
    gender: str
    number: str
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
    cases = {case for entry in entries for case in entry_cases(entry)}
    genders = {gender for entry in entries for gender in entry_genders(entry)}
    numbers = {number for entry in entries for number in entry_numbers(entry)}
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


def estimate_bytes(entries: list[DictionaryEntry], patterns: dict[str, InflectionPattern]) -> dict:
    surface_string_bytes = sum(len(entry.normalized_surface.encode("utf-8")) + 1 for entry in entries)
    pattern_ids = sorted({inflection for entry in entries for inflection in entry.inflections})
    pattern_templates = [
        slot.template
        for pattern_id in pattern_ids
        for slot in patterns.get(pattern_id, InflectionPattern(pattern_id, "", "", 0, ())).slots
    ]
    pattern_template_bytes = sum(len(template.encode("utf-8")) + 1 for template in set(pattern_templates))

    # Conservative first shape:
    # - exact surface row: uint32 string offset + uint16 feature bits + uint16 pattern id + uint16 slot mask
    # - pattern slot row: uint16 attrs + uint16 template id
    exact_surface_row_bytes = len(entries) * 10
    pattern_slot_rows = sum(len(patterns[pattern_id].slots) for pattern_id in pattern_ids if pattern_id in patterns)
    pattern_slot_row_bytes = pattern_slot_rows * 4
    return {
        "surfaceStringPoolBytes": surface_string_bytes,
        "patternTemplateStringPoolBytes": pattern_template_bytes,
        "exactSurfaceRowBytes": exact_surface_row_bytes,
        "patternSlotRows": pattern_slot_rows,
        "patternSlotRowBytes": pattern_slot_row_bytes,
        "simpleCasePackBytes": surface_string_bytes
        + pattern_template_bytes
        + exact_surface_row_bytes
        + pattern_slot_row_bytes,
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


def stem_for(surface: str, pattern: InflectionPattern) -> str | None:
    if not pattern.suffix:
        return surface
    if not surface.endswith(pattern.suffix):
        return None
    return surface[: -len(pattern.suffix)]


def term_id_for(entry: DictionaryEntry) -> str:
    normalized = entry.normalized_surface.replace(" ", "_")
    return f"sr.case.{normalized}"


def render_template(template: str, stem: str) -> str:
    return template.replace("{stem}", stem)


def case_form_term(entry: DictionaryEntry, patterns: dict[str, InflectionPattern]) -> tuple[CaseFormTerm | None, str | None]:
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

    pattern_id = entry.inflections[0]
    pattern = patterns.get(pattern_id)
    if pattern is None:
        return None, "missing-pattern"
    if pattern.part_of_speech not in SUPPORTED_POS:
        return None, "unsupported-pattern-part-of-speech"

    stem = stem_for(entry.surface, pattern)
    if stem is None:
        return None, "suffix-mismatch"

    gender = entry_genders(entry)[0]
    forms: dict[str, str] = {}
    for slot in pattern.slots:
        if slot.case not in CASE_GRAMMEMES or slot.number not in NUMBER_GRAMMEMES:
            continue
        if slot.gender is not None and slot.gender != gender:
            continue
        form_key = f"{slot.case}.{slot.number}"
        form_value = render_template(slot.template, stem)
        previous = forms.get(form_key)
        if previous is not None and previous != form_value:
            return None, "conflicting-form-key"
        forms[form_key] = form_value

    if "nominative.singular" not in forms:
        return None, "missing-nominative-singular-form"
    if "accusative.singular" not in forms:
        return None, "missing-accusative-singular-form"

    part_of_speech = sorted(set(entry.parts_of_speech()) & SUPPORTED_POS)[0]
    number = "singular"
    return (
        CaseFormTerm(
            term_id_for(entry),
            entry.surface,
            part_of_speech,
            gender,
            number,
            pattern_id,
            stem,
            tuple(sorted(forms.items())),
        ),
        None,
    )


def case_form_export_candidates(
    entries: list[DictionaryEntry],
    patterns: dict[str, InflectionPattern],
) -> tuple[list[CaseFormTerm], Counter[str]]:
    terms: list[CaseFormTerm] = []
    skipped: Counter[str] = Counter()
    seen_term_ids: set[str] = set()

    for entry in entries:
        term, reason = case_form_term(entry, patterns)
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
    provenance: dict,
) -> dict:
    entries, _ = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    terms, skipped = case_form_export_candidates(entries, patterns)
    exported_terms = terms[:limit] if limit > 0 else terms
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
    pack = {
        "schema": "mojito-mf2-inflection/sr-case-form-sample-pack/v0",
        "locale": "sr",
        "description": "Generated sample from Unicode Serbian noun case patterns; explicit term-form fixture, not a final runtime binary format.",
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
    return pack


def feature_bits(term: dict) -> int:
    bits = PART_OF_SPEECH_BITS.get(term.get("partOfSpeech"), 0)
    bits |= GENDER_BITS.get(term.get("gender"), 0) << 4
    bits |= NUMBER_BITS.get(term.get("number"), 0) << 8
    return bits


def build_compiled_case_form_pack(case_form_pack: dict) -> dict:
    form_sets = []
    term_rows = []
    diagnostics = []

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

    compiled = {
        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
        "locale": "sr",
        "provenance": case_form_pack["provenance"],
        "strings": case_form_pack["strings"],
        "terms": term_rows,
        "formSets": form_sets,
        "diagnostics": diagnostics,
        "sizeEstimates": {
            "compactJsonBytes": 0,
            "binaryLowerBoundBytes": binary_estimate,
        },
    }
    return compiled


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

    nominative_singular = [
        entry
        for entry in supported_entries
        if "nominative" in entry_cases(entry) and "singular" in entry_numbers(entry)
    ]

    slot_cases = Counter(slot.case or "<missing>" for pattern in used_patterns for slot in pattern.slots)
    slot_genders = Counter(slot.gender or "<missing>" for pattern in used_patterns for slot in pattern.slots)
    slot_numbers = Counter(slot.number or "<missing>" for pattern in used_patterns for slot in pattern.slots)
    case_form_terms, case_form_skipped = case_form_export_candidates(entries, patterns)
    ambiguity_reason_map = dict(sorted(ambiguity_reason_counts.items()))

    return {
        "schema": "mojito-mf2-inflection/sr-case-pack-report/v0",
        "locale": "sr",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/sr_case_pack_report.py",
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
            "nominativeSingularCandidates": len(nominative_singular),
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
            "patternSlotCases": dict(sorted(slot_cases.items())),
            "patternSlotGenders": dict(sorted(slot_genders.items())),
            "patternSlotNumbers": dict(sorted(slot_numbers.items())),
            "ambiguityReasons": ambiguity_reason_map,
        },
        "reviewPolicy": {
            "runtimeExport": "closed-world-case-forms",
            "automaticExportTerms": len(case_form_terms),
            "reviewRequiredSurfaces": len(ambiguous_surfaces),
            "blockedDictionaryEntries": sum(case_form_skipped.values()),
            "reviewRequiredReasons": ambiguity_reason_map,
            "blockedReasons": dict(sorted(case_form_skipped.items())),
        },
        "sizeEstimates": estimate_bytes(supported_entries, patterns),
        "samples": {
            "nominativeSingularCandidates": [compact_entry(entry) for entry in nominative_singular[:max_samples]],
            "ambiguousSurfaces": ambiguous_surfaces[:max_samples],
            "missingInflectionPatterns": missing_patterns[:max_samples],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Serbian dictionary .lst file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Serbian inflectional XML file.")
    parser.add_argument("--max-samples", type=int, default=20, help="Maximum sample rows to include.")
    parser.add_argument("--case-form-pack-out", type=Path, help="Write a generated Serbian case-form sample pack.")
    parser.add_argument("--case-form-pack-limit", type=int, default=20, help="Maximum case-form terms to export. Use 0 for all.")
    parser.add_argument("--compiled-case-form-pack-out", type=Path, help="Write generated Serbian case forms in compiled term-pack shape.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    args = parser.parse_args()

    report = build_report(args.dictionary, args.inflectional, args.max_samples)
    if args.case_form_pack_out or args.compiled_case_form_pack_out:
        case_form_pack = build_case_form_pack(
            args.dictionary,
            args.inflectional,
            args.case_form_pack_limit,
            pack_provenance(
                [args.dictionary, args.inflectional],
                "dev-docs/experiments/mf2-inflection/sr_case_pack_report.py",
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
