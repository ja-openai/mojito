#!/usr/bin/env python3
"""Survey Hindi dictionary, pattern, and pronoun data for MF2 term inflection."""

from __future__ import annotations

import argparse
import csv
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import SOURCE_LICENSE, UNICODE_DICTIONARY_RESOURCE_PREFIX, pack_source_metadata


SCHEMA = "mojito-mf2-inflection/hi-pack-survey/v0"
PRONOUN_AGREEMENT_SCHEMA = "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0"
PRONOUN_AGREEMENT_PACK_SHAPE = "dependency-pronoun-agreement-rows-v0"
RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/inflection"
SUPPORTED_TERM_POS = {"noun", "proper-noun"}
AGREEMENT_POS = {"adjective", "noun", "proper-noun"}
PART_OF_SPEECH_VALUES = {"adjective", "adposition", "adverb", "noun", "numeral", "proper-noun", "verb"}
CASE_VALUES = {"accusative", "direct", "ergative", "genitive", "oblique", "vocative"}
TERM_CASE_VALUES = {"direct", "oblique", "vocative"}
GENDER_VALUES = {"feminine", "masculine"}
NUMBER_VALUES = {"plural", "singular"}
ANIMACY_VALUES = {"animate", "inanimate"}
PERSON_VALUES = {"first", "second", "third"}
REGISTER_VALUES = {"formal", "informal", "intimate"}
PRONOUN_EXTRA_VALUES = {"exclusive", "inclusive", "independent", "number"}
COMPILED_TERM_ROW_BYTES = 20
COMPILED_FORM_ROW_BYTES = 12
PRONOUN_ROW_BYTES = 8
PART_OF_SPEECH_BITS = {"noun": 1, "proper-noun": 2}
GENDER_BITS = {"masculine": 1, "feminine": 2}
NUMBER_BITS = {"singular": 1, "plural": 2}


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
class CaseFormTerm:
    term_id: str
    text: str
    part_of_speech: str
    gender: str
    number: str
    animacy: str | None
    pattern: str
    forms: tuple[tuple[str, str], ...]


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
                slots.append(PatternSlot(tuple(sorted(inflection.attrib.items())), template_text(inflection.find("t"))))
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
            value = row[0]
            features = tuple(token for token in row[1:] if token)
            rows.append({"line": line_number, "value": value, "features": features})
    return rows


def pronoun_pack_row(row: dict) -> dict:
    features = set(row["features"])
    dependencies = {
        feature.removeprefix("dependency=")
        for feature in features
        if feature.startswith("dependency=")
    }
    person = sorted(features & PERSON_VALUES)
    numbers = sorted((features & NUMBER_VALUES) or (features & {"number"}))
    cases = sorted(features & CASE_VALUES)
    registers = sorted(features & REGISTER_VALUES)
    dependency_genders = sorted(dependencies & GENDER_VALUES)
    dependency_numbers = sorted(dependencies & NUMBER_VALUES)

    if len(person) != 1:
        raise ValueError(f"Expected exactly one Hindi pronoun person at line {row['line']}")
    if len(numbers) != 1:
        raise ValueError(f"Expected exactly one Hindi pronoun number at line {row['line']}")
    if len(cases) != 1:
        raise ValueError(f"Expected exactly one Hindi pronoun case at line {row['line']}")
    if len(registers) > 1:
        raise ValueError(f"Expected at most one Hindi pronoun register at line {row['line']}")
    if bool(dependency_genders) != bool(dependency_numbers):
        raise ValueError(f"Expected complete Hindi pronoun dependency at line {row['line']}")
    if cases[0] == "genitive" and (len(dependency_genders) != 1 or len(dependency_numbers) != 1):
        raise ValueError(f"Expected Hindi genitive pronoun dependency at line {row['line']}")
    if cases[0] != "genitive" and (dependency_genders or dependency_numbers):
        raise ValueError(f"Unexpected Hindi non-genitive pronoun dependency at line {row['line']}")

    return {
        "line": row["line"],
        "value": row["value"],
        "person": person[0],
        "number": "any" if numbers[0] == "number" else numbers[0],
        "case": cases[0],
        "register": registers[0] if registers else None,
        "dependencyGender": dependency_genders[0] if dependency_genders else None,
        "dependencyNumber": dependency_numbers[0] if dependency_numbers else None,
    }


def entry_pos(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in PART_OF_SPEECH_VALUES)


def entry_cases(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in CASE_VALUES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_VALUES)


def entry_animacy(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in ANIMACY_VALUES)


def supported_term_entry(entry: DictionaryEntry) -> bool:
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


def count_pattern_slots(patterns: list[InflectionPattern]) -> dict:
    slot_cases: Counter[str] = Counter()
    slot_genders: Counter[str] = Counter()
    slot_numbers: Counter[str] = Counter()
    slot_animacy: Counter[str] = Counter()
    slot_other_attrs: Counter[str] = Counter()
    slots_per_pattern: Counter[int] = Counter()
    template_rows = 0
    for pattern in patterns:
        slots_per_pattern[len(pattern.slots)] += 1
        for slot in pattern.slots:
            template_rows += 1
            attrs = dict(slot.attrs)
            slot_cases[attrs.get("case", "<missing>")] += 1
            slot_genders[attrs.get("gender", "<missing>")] += 1
            slot_numbers[attrs.get("number", "<missing>")] += 1
            slot_animacy[attrs.get("animacy", "<missing>")] += 1
            for key, value in slot.attrs:
                if key not in {"animacy", "case", "gender", "number"}:
                    slot_other_attrs[f"{key}={value}"] += 1
    return {
        "patternSlotCases": dict(sorted(slot_cases.items())),
        "patternSlotGenders": dict(sorted(slot_genders.items())),
        "patternSlotNumbers": dict(sorted(slot_numbers.items())),
        "patternSlotAnimacy": dict(sorted(slot_animacy.items())),
        "patternSlotOtherAttributes": dict(sorted(slot_other_attrs.items())),
        "slotsPerPattern": {str(key): value for key, value in sorted(slots_per_pattern.items())},
        "templateRows": template_rows,
    }


def pronoun_features(pronouns: list[dict]) -> dict:
    features: Counter[str] = Counter()
    dependency_features: Counter[str] = Counter()
    for pronoun in pronouns:
        for feature in pronoun["features"]:
            if feature.startswith("dependency="):
                dependency_features[feature.removeprefix("dependency=")] += 1
            else:
                features[feature] += 1
    return {
        "pronounFeatures": dict(sorted(features.items())),
        "pronounDependencyFeatures": dict(sorted(dependency_features.items())),
    }


def build_pronoun_agreement_pack(pronouns: Path) -> dict:
    rows = [pronoun_pack_row(row) for row in parse_pronouns(pronouns)]
    values = {row["value"] for row in rows}
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in values)
    row_bytes = len(rows) * PRONOUN_ROW_BYTES
    genitive_rows = [row for row in rows if row["case"] == "genitive"]
    dependency_rows = [
        row
        for row in rows
        if row["dependencyGender"] is not None or row["dependencyNumber"] is not None
    ]
    invariant_number_rows = [row for row in rows if row["number"] == "any"]
    return {
        "locale": "hi",
        "packShape": PRONOUN_AGREEMENT_PACK_SHAPE,
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/hi_pack_survey.py",
            "sources": [stable_source(pronouns, RESOURCE_PREFIX)],
            "sourceLabels": [f"{RESOURCE_PREFIX}/{pronouns.name}"],
        },
        "schema": PRONOUN_AGREEMENT_SCHEMA,
        "summary": {
            "rows": len(rows),
            "uniqueValues": len(values),
            "genitiveRows": len(genitive_rows),
            "dependencyRows": len(dependency_rows),
            "invariantNumberRows": len(invariant_number_rows),
            "binaryLowerBoundBytes": {
                "stringPoolBytes": string_pool_bytes,
                "rowBytes": row_bytes,
                "totalBytes": string_pool_bytes + row_bytes,
            },
        },
        "rows": rows,
    }


def surface_groups(entries: list[DictionaryEntry]) -> dict[str, list[DictionaryEntry]]:
    groups: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in entries:
        groups[entry.normalized_surface].append(entry)
    return groups


def ambiguous_surfaces(entries: list[DictionaryEntry]) -> list[dict]:
    ambiguous = []
    for normalized_surface, group in surface_groups(entries).items():
        signatures = {
            (
                tuple(sorted(entry_pos(entry))),
                tuple(sorted(entry_cases(entry))),
                tuple(sorted(entry_genders(entry))),
                tuple(sorted(entry_numbers(entry))),
                tuple(sorted(entry_animacy(entry))),
                tuple(sorted(entry.inflections)),
            )
            for entry in group
        }
        has_multi_feature_entry = any(
            len(entry_cases(entry)) > 1
            or len(entry_genders(entry)) > 1
            or len(entry_numbers(entry)) > 1
            or len(entry_animacy(entry)) > 1
            or len(entry.inflections) > 1
            for entry in group
        )
        if len(signatures) > 1 or has_multi_feature_entry:
            ambiguous.append(
                {
                    "surface": group[0].surface,
                    "entries": len(group),
                    "reasons": ambiguity_reasons(group),
                }
            )
    return sorted(ambiguous, key=lambda item: item["surface"])


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    cases = {value for entry in entries for value in entry_cases(entry)}
    genders = {value for entry in entries for value in entry_genders(entry)}
    numbers = {value for entry in entries for value in entry_numbers(entry)}
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
    if len(animacy) > 1 or any(len(entry_animacy(entry)) > 1 for entry in entries):
        reasons.append("multiple-animacy")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")
    if len(poses) > 1 or any(len(entry_pos(entry)) > 1 for entry in entries):
        reasons.append("multiple-parts-of-speech")
    return reasons


def stem_for(surface: str, pattern: InflectionPattern) -> str | None:
    if not pattern.suffix:
        return surface
    if not surface.endswith(pattern.suffix):
        return None
    return surface[: -len(pattern.suffix)]


def render_template(template: str, stem: str) -> str:
    return template.replace("{stem}", stem)


def term_id_for(entry: DictionaryEntry) -> str:
    return f"hi.case.{entry.normalized_surface.replace(' ', '_')}"


def case_form_term(entry: DictionaryEntry, patterns: dict[str, InflectionPattern]) -> tuple[CaseFormTerm | None, str | None]:
    if not supported_term_entry(entry):
        return None, "unsupported-part-of-speech"
    cases = set(entry_cases(entry))
    numbers = set(entry_numbers(entry))
    genders = set(entry_genders(entry))
    if "direct" not in cases or "singular" not in numbers:
        return None, "non-direct-singular-surface"
    if len(genders) != 1:
        return None, "missing-or-ambiguous-gender"
    if len(entry.inflections) != 1:
        return None, "missing-or-ambiguous-inflection"
    pattern = patterns.get(entry.inflections[0])
    if pattern is None:
        return None, "missing-pattern"
    if not (set(pattern.part_of_speech) & SUPPORTED_TERM_POS):
        return None, "unsupported-pattern-part-of-speech"
    stem = stem_for(entry.surface, pattern)
    if stem is None:
        return None, "suffix-mismatch"

    term_gender = next(iter(genders))
    term_animacy_values = set(entry_animacy(entry))
    term_animacy = sorted(term_animacy_values)[0] if len(term_animacy_values) == 1 else None
    forms: dict[str, str] = {}
    for slot in pattern.slots:
        attrs = dict(slot.attrs)
        slot_case = attrs.get("case")
        slot_number = attrs.get("number")
        slot_gender = attrs.get("gender")
        slot_animacy = attrs.get("animacy")
        if slot_case not in TERM_CASE_VALUES or slot_number not in NUMBER_VALUES:
            continue
        if slot_gender is not None and slot_gender != term_gender:
            continue
        if term_animacy is not None and slot_animacy is not None and slot_animacy != term_animacy:
            continue
        key = f"{slot_case}.{slot_number}"
        value = render_template(slot.template, stem)
        previous = forms.get(key)
        if previous is not None and previous != value:
            return None, "conflicting-form-key"
        forms[key] = value
    if "direct.singular" not in forms:
        return None, "missing-direct-singular-form"
    return (
        CaseFormTerm(
            term_id_for(entry),
            entry.surface,
            sorted(set(entry_pos(entry)) & SUPPORTED_TERM_POS)[0],
            term_gender,
            "singular",
            term_animacy,
            entry.inflections[0],
            tuple(sorted(forms.items())),
        ),
        None,
    )


def collect_case_form_terms(
    entries: list[DictionaryEntry], patterns: dict[str, InflectionPattern]
) -> tuple[list[CaseFormTerm], Counter[str]]:
    terms: list[CaseFormTerm] = []
    skipped: Counter[str] = Counter()
    seen_terms = set()
    for entry in entries:
        term, reason = case_form_term(entry, patterns)
        if reason is not None:
            skipped[reason] += 1
            continue
        assert term is not None
        if term.term_id in seen_terms:
            skipped["duplicate-term-id"] += 1
            continue
        seen_terms.add(term.term_id)
        terms.append(term)
    return terms, skipped


def estimate_case_form_pack(entries: list[DictionaryEntry], patterns: dict[str, InflectionPattern]) -> tuple[dict, list[dict]]:
    terms, skipped = collect_case_form_terms(entries, patterns)

    strings = set()
    for term in terms:
        strings.add(term.term_id)
        strings.add(term.text)
        strings.add(f"inflection={term.pattern}")
        for key, value in term.forms:
            strings.add(key)
            strings.add(value)
    form_rows = sum(len(term.forms) for term in terms)
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in strings)
    lower_bound = {
        "stringPoolBytes": string_pool_bytes,
        "termRowBytes": len(terms) * COMPILED_TERM_ROW_BYTES,
        "formRowBytes": form_rows * COMPILED_FORM_ROW_BYTES,
    }
    lower_bound["totalBytes"] = sum(lower_bound.values())
    summary = {
        "candidateTerms": len(terms),
        "formRows": form_rows,
        "skippedTerms": dict(sorted(skipped.items())),
        "binaryLowerBoundBytes": lower_bound,
    }
    samples = [
        {
            "termId": term.term_id,
            "text": term.text,
            "gender": term.gender,
            "animacy": term.animacy,
            "pattern": term.pattern,
            "forms": dict(term.forms),
        }
        for term in terms[:8]
    ]
    return summary, samples


def feature_bits(term: CaseFormTerm) -> int:
    bits = PART_OF_SPEECH_BITS.get(term.part_of_speech, 0)
    bits |= GENDER_BITS.get(term.gender, 0) << 4
    bits |= NUMBER_BITS.get(term.number, 0) << 8
    return bits


def selected_case_form_terms(
    terms: list[CaseFormTerm], skipped: Counter[str], requested_surfaces: list[str], limit: int
) -> list[CaseFormTerm]:
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

    return exported_terms


def build_compiled_case_form_pack(
    dictionary: Path,
    inflectional: Path,
    limit: int,
    requested_surfaces: list[str],
) -> dict:
    entries, _ = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    terms, skipped = collect_case_form_terms(
        [entry for entry in entries if supported_term_entry(entry)], patterns
    )
    exported_terms = selected_case_form_terms(terms, skipped, requested_surfaces, limit)

    strings: list[str] = []
    string_indexes: dict[str, int] = {}

    def string_index(value: str) -> int:
        if value not in string_indexes:
            string_indexes[value] = len(strings)
            strings.append(value)
        return string_indexes[value]

    term_rows = []
    form_sets = []
    for term in exported_terms:
        term_id = string_index(term.term_id)
        form_set_index = len(form_sets)
        form_sets.append(
            {
                "term": term_id,
                "forms": [
                    {
                        "key": string_index(key),
                        "value": string_index(value),
                        "kind": "literal",
                    }
                    for key, value in term.forms
                ],
            }
        )
        term_rows.append(
            {
                "id": term_id,
                "text": string_index(term.text),
                "featureBits": feature_bits(term),
                "sense": None,
                "formSet": form_set_index,
            }
        )

    form_row_count = sum(len(form_set["forms"]) for form_set in form_sets)
    binary_estimate = {
        "stringPoolBytes": sum(len(value.encode("utf-8")) + 1 for value in strings),
        "termRowBytes": len(term_rows) * COMPILED_TERM_ROW_BYTES,
        "formRowBytes": form_row_count * COMPILED_FORM_ROW_BYTES,
        "bindingReferenceBytes": 0,
    }
    binary_estimate["totalBytes"] = sum(binary_estimate.values())

    return {
        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
        "locale": "hi",
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/hi_pack_survey.py",
            "sources": [
                stable_source(dictionary, UNICODE_DICTIONARY_RESOURCE_PREFIX),
                stable_source(inflectional, UNICODE_DICTIONARY_RESOURCE_PREFIX),
            ],
            "sourceLabels": [
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}",
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{inflectional.name}",
            ],
        },
        "strings": strings,
        "terms": term_rows,
        "formSets": form_sets,
        "diagnostics": [],
        "sizeEstimates": {
            "compactJsonBytes": 0,
            "binaryLowerBoundBytes": binary_estimate,
        },
    }


def compact_entry(entry: DictionaryEntry) -> dict:
    return {
        "surface": entry.surface,
        "line": entry.line,
        "partOfSpeech": list(entry_pos(entry)),
        "case": list(entry_cases(entry)),
        "gender": list(entry_genders(entry)),
        "number": list(entry_numbers(entry)),
        "animacy": list(entry_animacy(entry)),
        "inflections": list(entry.inflections),
    }


def stable_source(path: Path, resource_prefix: str) -> dict:
    metadata = pack_source_metadata(path)
    metadata["gitLfsPointer"] = source_metadata(path)["gitLfsPointer"]
    metadata["path"] = f"{resource_prefix}/{path.name}"
    return metadata


def build_report(dictionary: Path, inflectional: Path, pronouns: Path, max_samples: int) -> dict:
    entries, skipped = parse_dictionary(dictionary)
    patterns = parse_patterns(inflectional)
    pronoun_rows = parse_pronouns(pronouns)

    term_entries = [entry for entry in entries if supported_term_entry(entry)]
    agreement_entries = [entry for entry in entries if agreement_entry(entry)]
    used_pattern_ids = {inflection for entry in agreement_entries for inflection in entry.inflections}
    used_patterns = [patterns[pattern_id] for pattern_id in sorted(used_pattern_ids) if pattern_id in patterns]
    missing_patterns = sorted(pattern_id for pattern_id in used_pattern_ids if pattern_id not in patterns)
    ambiguous = ambiguous_surfaces(agreement_entries)
    case_form_pack, case_form_samples = estimate_case_form_pack(term_entries, patterns)

    return {
        "schema": SCHEMA,
        "locale": "hi",
        "sources": [
            stable_source(dictionary, UNICODE_DICTIONARY_RESOURCE_PREFIX),
            stable_source(inflectional, UNICODE_DICTIONARY_RESOURCE_PREFIX),
            stable_source(pronouns, RESOURCE_PREFIX),
        ],
        "provenance": {
            "license": SOURCE_LICENSE,
            "generator": "dev-docs/experiments/mf2-inflection/hi_pack_survey.py",
            "sourceLabels": [
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{dictionary.name}",
                f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/{inflectional.name}",
                f"{RESOURCE_PREFIX}/{pronouns.name}",
            ],
        },
        "counts": {
            "dictionaryEntries": len(entries),
            "dictionarySkippedLines": len(skipped),
            "termEntries": len(term_entries),
            "termSurfaces": len({entry.normalized_surface for entry in term_entries}),
            "agreementEntries": len(agreement_entries),
            "agreementSurfaces": len({entry.normalized_surface for entry in agreement_entries}),
            "ambiguousAgreementSurfaces": len(ambiguous),
            "pronounRows": len(pronoun_rows),
            "inflectionalPatterns": len(patterns),
            "usedAgreementPatterns": len(used_patterns),
            "missingInflectionPatterns": len(missing_patterns),
        },
        "features": {
            "partOfSpeech": count_values(entries, entry_pos),
            "termCase": count_values(term_entries, entry_cases),
            "termGender": count_values(term_entries, entry_genders),
            "termNumber": count_values(term_entries, entry_numbers),
            "termAnimacy": count_values(term_entries, entry_animacy),
            "inflectionPatterns": dict(Counter(inflection for entry in agreement_entries for inflection in entry.inflections).most_common()),
            **count_pattern_slots(used_patterns),
            **pronoun_features(pronoun_rows),
        },
        "packShape": {
            "recommendation": "case-form rows plus pronoun agreement table",
            "reason": "Hindi product terms need direct/oblique/vocative noun forms and gender/number agreement; pronoun genitives also depend on the referenced noun's gender/number.",
            "termCases": sorted(TERM_CASE_VALUES),
            "metadataBits": ["gender", "number", "animacy", "partOfSpeech"],
            "caseFormPack": case_form_pack,
            "pronounTableRows": len(pronoun_rows),
            "pronounDependencyKeys": ["gender", "number"],
        },
        "samples": {
            "caseFormTerms": case_form_samples,
            "termEntries": [compact_entry(entry) for entry in term_entries[:max_samples]],
            "ambiguousAgreementSurfaces": ambiguous[:max_samples],
            "missingInflectionPatterns": missing_patterns[:max_samples],
            "pronouns": pronoun_rows[:max_samples],
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
    parser.add_argument("--dictionary", type=Path)
    parser.add_argument("--inflectional", type=Path)
    parser.add_argument("--pronouns", type=Path)
    parser.add_argument("--max-samples", type=int, default=8)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--compiled-case-form-pack-out", type=Path)
    parser.add_argument("--pronoun-agreement-pack-out", type=Path)
    parser.add_argument("--case-form-pack-limit", type=int, default=8, help="Maximum Hindi case-form terms to export. Use 0 for all.")
    parser.add_argument(
        "--case-form-surface",
        action="append",
        default=[],
        help="Prioritize an available direct singular surface in the exported Hindi compiled case-form pack.",
    )
    args = parser.parse_args()

    needs_report = args.out is not None or not (
        args.compiled_case_form_pack_out or args.pronoun_agreement_pack_out
    )
    needs_dictionary = needs_report or args.compiled_case_form_pack_out is not None
    if needs_dictionary and (args.dictionary is None or args.inflectional is None):
        parser.error("--dictionary and --inflectional are required for survey and case-form output")
    if (needs_report or args.pronoun_agreement_pack_out) and args.pronouns is None:
        parser.error("--pronouns is required for survey and pronoun-agreement output")

    if needs_report:
        report = build_report(args.dictionary, args.inflectional, args.pronouns, args.max_samples)
    if args.out:
        write_json(args.out, report)
    elif needs_report:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))

    if args.compiled_case_form_pack_out:
        compiled_case_form_pack = build_compiled_case_form_pack(
            args.dictionary,
            args.inflectional,
            args.case_form_pack_limit,
            args.case_form_surface,
        )
        update_json_bytes(compiled_case_form_pack, ["sizeEstimates", "compactJsonBytes"])
        write_json(args.compiled_case_form_pack_out, compiled_case_form_pack)

    if args.pronoun_agreement_pack_out:
        write_json(args.pronoun_agreement_pack_out, build_pronoun_agreement_pack(args.pronouns))


if __name__ == "__main__":
    main()
