#!/usr/bin/env python3
"""Survey Turkish suffix data before choosing a compact MF2 term pack."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, normalize_surface, parse_dictionary, source_metadata
from fr_noun_pack_report import UNICODE_DICTIONARY_RESOURCE_PREFIX, pack_source_metadata


SCHEMA = "mojito-mf2-inflection/tr-suffix-pack-survey/v0"
SOURCE_LICENSE = "CC0-1.0 dictionary data; Unicode-3.0 repository packaging"
SUPPORTED_POS = {"noun", "proper-noun"}
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
NUMBER_BITS = {"singular": 1, "plural": 2}
CASE_GRAMMEMES = {
    "ablative",
    "absolutive",
    "accusative",
    "dative",
    "genitive",
    "locative",
    "nominative",
}
NUMBER_GRAMMEMES = {"dual", "plural", "singular"}
DEFAULT_INFLECTION = "1"
PACK_METADATA_BITS = [
    "vowelEnd",
    "frontVowel",
    "roundedVowel",
    "foreign",
    "exception",
    "hardConsonant",
    "softConsonant",
    "compound",
]
RENDERER_SCOPE = ["plural", "nominative", "accusative", "dative", "locative", "ablative"]
EXPLICIT_REVIEW_FLAGS = ["exception", "foreign", "soft-consonant"]
MUTATION_STRATEGY = "explicit-template-forms"


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


@dataclass(frozen=True)
class ExplicitTemplateTerm:
    term_id: str
    text: str
    part_of_speech: str
    number: str
    inflection_pattern: str
    stem: str
    forms: tuple[tuple[str, str], ...]


def parse_patterns(path: Path) -> dict[str, InflectionPattern]:
    root = ET.parse(path).getroot()
    patterns: dict[str, InflectionPattern] = {}
    for pattern_node in root.findall("pattern"):
        name = required_attr(pattern_node, "name")
        part_of_speech = tuple(text_or_empty(node) for node in pattern_node.findall("pos"))
        suffix = text_or_empty(pattern_node.find("suffix"))
        words = int(pattern_node.attrib.get("words", "0"))
        slots = []
        inflections = pattern_node.find("inflections")
        if inflections is not None:
            for inflection in inflections.findall("inflection"):
                slots.append(PatternSlot(tuple(sorted(inflection.attrib.items())), template_text(inflection.find("t"))))
        patterns[name] = InflectionPattern(name, part_of_speech, suffix, words, tuple(slots))
    return patterns


def required_attr(node: ET.Element, name: str) -> str:
    value = node.attrib.get(name)
    if value is None:
        raise ValueError(f"Missing XML attribute {name}")
    return value


def text_or_empty(node: ET.Element | None) -> str:
    return "" if node is None or node.text is None else node.text.strip()


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


def parse_supplemental(path: Path) -> tuple[dict[str, tuple[str, ...]], list[dict]]:
    entries: dict[str, tuple[str, ...]] = {}
    skipped = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            skipped.append({"line": line_number, "reason": "blank"})
            continue
        if line.startswith("#"):
            skipped.append({"line": line_number, "reason": "comment"})
            continue
        if ":" not in line:
            skipped.append({"line": line_number, "reason": "missing-colon", "value": line})
            continue
        surface, raw_flags = line.split(":", 1)
        surface = surface.strip()
        flags = tuple(token for token in raw_flags.strip().split() if token)
        if not surface or not flags:
            skipped.append({"line": line_number, "reason": "empty-surface-or-flags", "value": line})
            continue
        entries[normalize_surface(surface)] = flags
    return entries, skipped


def supported_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry.parts_of_speech()) & SUPPORTED_POS)


def count_values(entries: list[DictionaryEntry], extractor) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for entry in entries:
        values = extractor(entry)
        if values:
            counter.update(values)
        else:
            counter["<missing>"] += 1
    return dict(sorted(counter.items()))


def entry_cases(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in CASE_GRAMMEMES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_GRAMMEMES)


def pattern_slot_stats(patterns: list[InflectionPattern]) -> dict:
    slot_cases: Counter[str] = Counter()
    slot_numbers: Counter[str] = Counter()
    slot_other_attrs: Counter[str] = Counter()
    slots_per_pattern: Counter[int] = Counter()
    template_rows = 0

    for pattern in patterns:
        slots_per_pattern[len(pattern.slots)] += 1
        for slot in pattern.slots:
            template_rows += 1
            attrs = dict(slot.attrs)
            slot_cases[attrs.get("case", "<missing>")] += 1
            slot_numbers[attrs.get("number", "<missing>")] += 1
            for key, value in slot.attrs:
                if key not in {"case", "number"}:
                    slot_other_attrs[f"{key}={value}"] += 1

    return {
        "patternSlotCases": dict(sorted(slot_cases.items())),
        "patternSlotNumbers": dict(sorted(slot_numbers.items())),
        "patternSlotOtherAttributes": dict(sorted(slot_other_attrs.items())),
        "slotsPerPattern": {str(key): value for key, value in sorted(slots_per_pattern.items())},
        "templateRows": template_rows,
    }


def composition_policy(
    patterns: list[InflectionPattern],
    supplemental: dict[str, tuple[str, ...]],
    pattern_surfaces: dict[str, list[str]],
    max_samples: int,
) -> tuple[dict, list[dict]]:
    case_template_rows = 0
    suffix_preserving_case_template_rows = 0
    empty_suffix_case_template_rows = 0
    consonant_mutation_template_rows = 0
    case_template_patterns: set[str] = set()
    consonant_mutation_patterns: set[str] = set()
    plural_template_rows = 0
    mutation_samples = []

    for pattern in patterns:
        for slot in pattern.slots:
            attrs = dict(slot.attrs)
            if "number" in attrs:
                plural_template_rows += 1
            if "case" not in attrs:
                continue

            case_template_rows += 1
            case_template_patterns.add(pattern.name)
            if not pattern.suffix:
                empty_suffix_case_template_rows += 1
                continue

            if slot.template.startswith(f"{{stem}}{pattern.suffix}"):
                suffix_preserving_case_template_rows += 1
                continue

            consonant_mutation_template_rows += 1
            consonant_mutation_patterns.add(pattern.name)
            if len(mutation_samples) < max_samples:
                mutation_samples.append(
                    {
                        "pattern": pattern.name,
                        "suffix": pattern.suffix,
                        "attrs": attrs,
                        "template": slot.template,
                        "surfaces": pattern_surfaces.get(pattern.name, [])[:max_samples],
                    }
                )

    explicit_review_flags = set(EXPLICIT_REVIEW_FLAGS)
    supplemental_rows_requiring_explicit_review = sum(
        1 for flags in supplemental.values() if explicit_review_flags & set(flags)
    )
    policy = {
        "ruleSafeInflection": DEFAULT_INFLECTION,
        "rendererScope": RENDERER_SCOPE,
        "mutationStrategy": MUTATION_STRATEGY,
        "mutationStrategyReason": "Only a small set of non-default XML templates requires stem mutation; explicit templates are safer than extending the compact renderer with under-specified Turkish mutation classes.",
        "requiresExplicitFormFlags": EXPLICIT_REVIEW_FLAGS,
        "supplementalRowsRequiringExplicitReview": supplemental_rows_requiring_explicit_review,
        "caseTemplatePatterns": len(case_template_patterns),
        "caseTemplateRows": case_template_rows,
        "caseTemplateRowBytes": case_template_rows * 12,
        "emptySuffixCaseTemplateRows": empty_suffix_case_template_rows,
        "suffixPreservingCaseTemplateRows": suffix_preserving_case_template_rows,
        "consonantMutationPatterns": len(consonant_mutation_patterns),
        "consonantMutationTemplateRows": consonant_mutation_template_rows,
        "consonantMutationTemplateRowBytes": consonant_mutation_template_rows * 12,
        "pluralTemplateRows": plural_template_rows,
        "pluralTemplateRowBytes": plural_template_rows * 12,
    }
    return policy, mutation_samples


def supplemental_stats(supplemental: dict[str, tuple[str, ...]]) -> dict:
    flag_counts: Counter[str] = Counter()
    combinations: Counter[tuple[str, ...]] = Counter()
    for flags in supplemental.values():
        flag_counts.update(flags)
        combinations[flags] += 1
    return {
        "supplementalFlags": dict(sorted(flag_counts.items())),
        "supplementalCombinations": {
            " ".join(key): value for key, value in combinations.most_common(20)
        },
    }


def compact_entry(entry: DictionaryEntry) -> dict:
    return {
        "surface": entry.surface,
        "line": entry.line,
        "partOfSpeech": list(entry.parts_of_speech()),
        "case": list(entry_cases(entry)),
        "number": list(entry_numbers(entry)),
        "inflections": list(entry.inflections),
    }


def supplemental_sample(surface: str, flags: tuple[str, ...]) -> dict:
    return {"surface": surface, "flags": list(flags)}


def stable_source(path: Path) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    if path.name in {"dictionary_tr.lst", "inflectional_tr.xml", "supplemental_tr.lst"}:
        metadata["path"] = f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{path.name}"
    return metadata


def pack_provenance(paths: list[Path], generator: str) -> dict:
    return {
        "license": SOURCE_LICENSE,
        "generator": generator,
        "sources": [stable_source(path) for path in paths],
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


def render_template(template: str, stem: str) -> str:
    return template.replace("{stem}", stem)


def term_id_for(entry: DictionaryEntry) -> str:
    normalized = entry.normalized_surface.replace(" ", "_")
    return f"tr.explicit.{normalized}"


def form_keys_for_slot(slot: PatternSlot) -> tuple[str, ...]:
    attrs = dict(slot.attrs)
    grammatical_case = attrs.get("case")
    number = attrs.get("number")
    if grammatical_case == "absolutive":
        return ("bare.singular", "count.one")
    if grammatical_case in CASE_GRAMMEMES:
        return (f"{grammatical_case}.{number or 'singular'}",)
    if number == "plural":
        return ("bare.plural", "count.other")
    if number == "singular" or not attrs:
        return ("bare.singular", "count.one")
    return ()


def explicit_template_term(
    entry: DictionaryEntry,
    patterns: dict[str, InflectionPattern],
) -> tuple[ExplicitTemplateTerm | None, str | None]:
    if not supported_entry(entry):
        return None, "unsupported-part-of-speech"
    numbers = set(entry_numbers(entry))
    if numbers & {"dual", "plural"}:
        return None, "non-singular-dictionary-surface"
    cases = set(entry_cases(entry))
    if cases - {"absolutive", "nominative"}:
        return None, "non-base-case-dictionary-surface"
    if len(entry.inflections) != 1:
        return None, "missing-or-ambiguous-inflection"
    pattern_id = entry.inflections[0]
    pattern = patterns.get(pattern_id)
    if pattern is None:
        return None, "missing-pattern"
    if not (set(pattern.part_of_speech) & SUPPORTED_POS):
        return None, "unsupported-pattern-part-of-speech"
    stem = stem_for(entry.surface, pattern)
    if stem is None:
        return None, "suffix-mismatch"

    forms: dict[str, str] = {}
    for slot in pattern.slots:
        form_value = render_template(slot.template, stem)
        for form_key in form_keys_for_slot(slot):
            previous = forms.get(form_key)
            if previous is not None and previous != form_value:
                return None, "conflicting-form-key"
            forms[form_key] = form_value

    if "bare.singular" not in forms:
        return None, "missing-bare-singular-form"

    part_of_speech = sorted(set(entry.parts_of_speech()) & SUPPORTED_POS)[0]
    return (
        ExplicitTemplateTerm(
            term_id_for(entry),
            entry.surface,
            part_of_speech,
            "singular",
            pattern_id,
            stem,
            tuple(sorted(forms.items())),
        ),
        None,
    )


def feature_bits(term: ExplicitTemplateTerm) -> int:
    bits = PART_OF_SPEECH_BITS.get(term.part_of_speech, 0)
    bits |= NUMBER_BITS.get(term.number, 0) << 8
    return bits


def build_explicit_template_pack(
    dictionary: Path,
    inflectional: Path,
    requested_surfaces: list[str],
    limit: int,
    provenance: dict,
) -> dict:
    entries, _ = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    terms: list[ExplicitTemplateTerm] = []
    skipped: Counter[str] = Counter()
    seen_term_ids: set[str] = set()

    normalized_requests = [normalize_surface(surface) for surface in requested_surfaces]
    requested_set = set(normalized_requests)
    for entry in entries:
        if requested_set and entry.normalized_surface not in requested_set:
            continue
        term, reason = explicit_template_term(entry, patterns)
        if reason is not None:
            skipped[reason] += 1
            continue
        assert term is not None
        if term.term_id in seen_term_ids:
            skipped["duplicate-term-id"] += 1
            continue
        seen_term_ids.add(term.term_id)
        terms.append(term)

    by_term_id = {term.term_id: term for term in terms}
    exported_terms: list[ExplicitTemplateTerm] = []
    for surface in normalized_requests:
        term = by_term_id.get(f"tr.explicit.{surface.replace(' ', '_')}")
        if term is None:
            skipped[f"requested-surface-unavailable:{surface}"] += 1
            continue
        if term not in exported_terms:
            exported_terms.append(term)

    if not normalized_requests:
        exported_terms = terms[:limit] if limit > 0 else terms
    elif limit > 0:
        exported_terms = exported_terms[:limit]

    strings: list[str] = []
    string_indexes: dict[str, int] = {}

    def string_index(value: str) -> int:
        if value not in string_indexes:
            string_indexes[value] = len(strings)
            strings.append(value)
        return string_indexes[value]

    form_sets = []
    term_rows = []
    for term in exported_terms:
        form_set_index = len(form_sets)
        form_sets.append(
            {
                "term": string_index(term.term_id),
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
        term_rows.append(
            {
                "id": string_index(term.term_id),
                "text": string_index(term.text),
                "featureBits": feature_bits(term),
                "sense": string_index(f"inflection={term.inflection_pattern}"),
                "formSet": form_set_index,
            }
        )

    form_row_count = sum(len(form_set["forms"]) for form_set in form_sets)
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in strings)
    binary_estimate = {
        "stringPoolBytes": string_pool_bytes,
        "termRowBytes": len(term_rows) * 20,
        "formRowBytes": form_row_count * 12,
        "bindingReferenceBytes": 0,
    }
    binary_estimate["totalBytes"] = sum(binary_estimate.values())

    return {
        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
        "locale": "tr",
        "provenance": provenance,
        "strings": strings,
        "terms": term_rows,
        "formSets": form_sets,
        "diagnostics": [],
        "generationSummary": {
            "candidateTerms": len(terms),
            "exportedTerms": len(exported_terms),
            "skippedTerms": dict(sorted(skipped.items())),
            "formRows": form_row_count,
        },
        "sizeEstimates": {
            "compactJsonBytes": 0,
            "binaryLowerBoundBytes": binary_estimate,
        },
    }


def build_report(dictionary: Path, inflectional: Path, supplemental_path: Path, max_samples: int) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    supplemental, supplemental_skipped = parse_supplemental(supplemental_path)
    full_explicit_template_pack = build_explicit_template_pack(
        dictionary,
        inflectional,
        [],
        0,
        pack_provenance(
            [dictionary, inflectional],
            "dev-docs/experiments/mf2-inflection/tr_suffix_pack_survey.py",
        ),
    )
    update_json_bytes(full_explicit_template_pack, ["sizeEstimates", "compactJsonBytes"])
    full_explicit_template_summary = full_explicit_template_pack["generationSummary"]
    full_explicit_template_size = full_explicit_template_pack["sizeEstimates"]
    full_explicit_template_binary_size = full_explicit_template_size["binaryLowerBoundBytes"]

    supported_entries = [entry for entry in entries if supported_entry(entry)]
    supported_surfaces = {entry.normalized_surface for entry in supported_entries}
    inflection_ids = {inflection for entry in supported_entries for inflection in entry.inflections}
    missing_patterns = sorted(inflection for inflection in inflection_ids if inflection not in patterns)
    used_patterns = [patterns[inflection] for inflection in sorted(inflection_ids) if inflection in patterns]
    noun_patterns = [pattern for pattern in patterns.values() if set(pattern.part_of_speech) & SUPPORTED_POS]

    default_inflection_entries = [entry for entry in supported_entries if DEFAULT_INFLECTION in entry.inflections]
    explicit_inflection_entries = [
        entry
        for entry in supported_entries
        if entry.inflections and any(inflection != DEFAULT_INFLECTION for inflection in entry.inflections)
    ]
    supported_without_inflection = [entry for entry in supported_entries if not entry.inflections]
    supported_with_supplemental = [
        entry for entry in supported_entries if entry.normalized_surface in supplemental
    ]
    default_with_supplemental = [
        entry for entry in default_inflection_entries if entry.normalized_surface in supplemental
    ]
    supplemental_only = sorted(set(supplemental) - supported_surfaces)

    supplemental_string_pool_bytes = sum(len(surface.encode("utf-8")) + 1 for surface in supplemental)
    supplemental_row_bytes = len(supplemental) * 8
    explicit_template_rows = sum(len(pattern.slots) for pattern in used_patterns if pattern.name != DEFAULT_INFLECTION)
    explicit_template_row_bytes = explicit_template_rows * 12
    pattern_surfaces: dict[str, list[str]] = {}
    for entry in supported_entries:
        for inflection in entry.inflections:
            pattern_surfaces.setdefault(inflection, []).append(entry.surface)
    policy, mutation_samples = composition_policy(used_patterns, supplemental, pattern_surfaces, max_samples)

    pattern_samples = []
    for pattern in used_patterns[:max_samples]:
        pattern_samples.append(
            {
                "name": pattern.name,
                "partOfSpeech": list(pattern.part_of_speech),
                "suffix": pattern.suffix,
                "words": pattern.words,
                "slots": [
                    {
                        "attrs": dict(slot.attrs),
                        "template": slot.template,
                    }
                    for slot in pattern.slots[:max_samples]
                ],
            }
        )

    return {
        "schema": SCHEMA,
        "locale": "tr",
        "sources": [stable_source(dictionary), stable_source(inflectional), stable_source(supplemental_path)],
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/tr_suffix_pack_survey.py",
            "sourceLabels": [
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}",
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{inflectional.name}",
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{supplemental_path.name}",
            ],
        },
        "counts": {
            "dictionaryEntries": len(entries),
            "dictionarySkippedLines": len(skipped),
            "supportedEntries": len(supported_entries),
            "uniqueSupportedSurfaces": len(supported_surfaces),
            "supportedWithoutInflection": len(supported_without_inflection),
            "defaultInflectionEntries": len(default_inflection_entries),
            "explicitInflectionEntries": len(explicit_inflection_entries),
            "dictionaryInflectionPatterns": len(inflection_ids),
            "missingInflectionPatterns": len(missing_patterns),
            "inflectionalPatterns": len(patterns),
            "nounInflectionalPatterns": len(noun_patterns),
            "usedInflectionalPatterns": len(used_patterns),
            "supplementalEntries": len(supplemental),
            "supplementalSkippedLines": len(supplemental_skipped),
            "supportedSurfacesCoveredBySupplemental": len(supported_with_supplemental),
            "defaultInflectionEntriesCoveredBySupplemental": len(default_with_supplemental),
            "supplementalOnlySurfaces": len(supplemental_only),
        },
        "features": {
            "partOfSpeech": count_values(supported_entries, DictionaryEntry.parts_of_speech),
            "case": count_values(supported_entries, entry_cases),
            "number": count_values(supported_entries, entry_numbers),
            "inflectionPatterns": dict(Counter(inflection for entry in supported_entries for inflection in entry.inflections).most_common()),
            **supplemental_stats(supplemental),
            **pattern_slot_stats(used_patterns),
        },
        "packShape": {
            "recommendation": "rule-plus-exception suffix pack",
            "metadataBits": PACK_METADATA_BITS,
            "reason": "Turkish default noun pattern has no explicit XML rows; suffix rendering needs vowel harmony and consonant alternation metadata, with supplemental rows mostly covering foreign/exception surfaces.",
            "supplementalExceptionRows": len(supplemental),
            "supplementalStringPoolBytes": supplemental_string_pool_bytes,
            "supplementalRowBytes": supplemental_row_bytes,
            "supplementalMetadataLowerBoundBytes": supplemental_string_pool_bytes + supplemental_row_bytes,
            "explicitTemplateRows": explicit_template_rows,
            "explicitTemplateRowBytes": explicit_template_row_bytes,
            "explicitTemplateBaseCandidateTerms": full_explicit_template_summary["candidateTerms"],
            "explicitTemplateCompiledFormRows": full_explicit_template_summary["formRows"],
            "explicitTemplateCompiledStringPoolBytes": full_explicit_template_binary_size["stringPoolBytes"],
            "explicitTemplateCompiledTermRowBytes": full_explicit_template_binary_size["termRowBytes"],
            "explicitTemplateCompiledFormRowBytes": full_explicit_template_binary_size["formRowBytes"],
            "explicitTemplateCompiledLowerBoundBytes": full_explicit_template_binary_size["totalBytes"],
            "explicitTemplateCompiledJsonBytes": full_explicit_template_size["compactJsonBytes"],
        },
        "compositionPolicy": policy,
        "samples": {
            "defaultInflectionCoveredBySupplemental": [
                {**compact_entry(entry), "supplementalFlags": list(supplemental[entry.normalized_surface])}
                for entry in default_with_supplemental[:max_samples]
            ],
            "defaultInflectionMissingSupplemental": [
                compact_entry(entry)
                for entry in default_inflection_entries
                if entry.normalized_surface not in supplemental
            ][:max_samples],
            "explicitInflectionEntries": [compact_entry(entry) for entry in explicit_inflection_entries[:max_samples]],
            "supportedWithoutInflection": [compact_entry(entry) for entry in supported_without_inflection[:max_samples]],
            "supplementalOnlySurfaces": [
                supplemental_sample(surface, supplemental[surface]) for surface in supplemental_only[:max_samples]
            ],
            "inflectionPatterns": pattern_samples,
            "consonantMutationTemplates": mutation_samples,
            "missingInflectionPatterns": missing_patterns[:max_samples],
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode Turkish dictionary .lst file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode Turkish inflectional XML file.")
    parser.add_argument("--supplemental", required=True, type=Path, help="Unicode Turkish supplemental .lst file.")
    parser.add_argument("--max-samples", type=int, default=8, help="Maximum sample rows to include.")
    parser.add_argument("--explicit-template-pack-out", type=Path, help="Write generated Turkish explicit XML templates in compiled term-pack shape.")
    parser.add_argument("--explicit-template-pack-limit", type=int, default=8, help="Maximum explicit-template terms to export when no surfaces are requested. Use 0 for all.")
    parser.add_argument(
        "--explicit-template-surface",
        action="append",
        default=[],
        help="Prioritize a dictionary surface in the explicit-template compiled pack.",
    )
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    args = parser.parse_args()

    report = build_report(args.dictionary, args.inflectional, args.supplemental, args.max_samples)
    if args.explicit_template_pack_out:
        explicit_template_pack = build_explicit_template_pack(
            args.dictionary,
            args.inflectional,
            args.explicit_template_surface,
            args.explicit_template_pack_limit,
            pack_provenance(
                [args.dictionary, args.inflectional],
                "dev-docs/experiments/mf2-inflection/tr_suffix_pack_survey.py",
            ),
        )
        update_json_bytes(explicit_template_pack, ["sizeEstimates", "compactJsonBytes"])
        write_json(args.explicit_template_pack_out, explicit_template_pack)
    if args.out:
        write_json(args.out, report)
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
