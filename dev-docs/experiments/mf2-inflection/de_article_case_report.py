#!/usr/bin/env python3
"""Estimate German article/case term packs from Unicode dictionary and pattern data."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata
from fr_noun_pack_report import SOURCE_LICENSE, UNICODE_DICTIONARY_RESOURCE_PREFIX, pack_source_metadata


CASE_VALUES = {"accusative", "dative", "genitive", "nominative"}
GENDER_VALUES = {"feminine", "masculine", "neuter"}
NUMBER_VALUES = {"plural", "singular"}
SUPPORTED_POS = {"noun", "proper-noun"}
ARTICLE_VALUES = {"definite", "indefinite"}
ARTICLE_FORMS = {
    ("definite", "masculine", "singular", "nominative"): "der",
    ("definite", "masculine", "singular", "accusative"): "den",
    ("definite", "masculine", "singular", "dative"): "dem",
    ("definite", "masculine", "singular", "genitive"): "des",
    ("definite", "feminine", "singular", "nominative"): "die",
    ("definite", "feminine", "singular", "accusative"): "die",
    ("definite", "feminine", "singular", "dative"): "der",
    ("definite", "feminine", "singular", "genitive"): "der",
    ("definite", "neuter", "singular", "nominative"): "das",
    ("definite", "neuter", "singular", "accusative"): "das",
    ("definite", "neuter", "singular", "dative"): "dem",
    ("definite", "neuter", "singular", "genitive"): "des",
    ("definite", "*", "plural", "nominative"): "die",
    ("definite", "*", "plural", "accusative"): "die",
    ("definite", "*", "plural", "dative"): "den",
    ("definite", "*", "plural", "genitive"): "der",
    ("indefinite", "masculine", "singular", "nominative"): "ein",
    ("indefinite", "masculine", "singular", "accusative"): "einen",
    ("indefinite", "masculine", "singular", "dative"): "einem",
    ("indefinite", "masculine", "singular", "genitive"): "eines",
    ("indefinite", "feminine", "singular", "nominative"): "eine",
    ("indefinite", "feminine", "singular", "accusative"): "eine",
    ("indefinite", "feminine", "singular", "dative"): "einer",
    ("indefinite", "feminine", "singular", "genitive"): "einer",
    ("indefinite", "neuter", "singular", "nominative"): "ein",
    ("indefinite", "neuter", "singular", "accusative"): "ein",
    ("indefinite", "neuter", "singular", "dative"): "einem",
    ("indefinite", "neuter", "singular", "genitive"): "eines",
    ("indefinite", "*", "plural", "nominative"): "",
    ("indefinite", "*", "plural", "accusative"): "",
    ("indefinite", "*", "plural", "dative"): "",
    ("indefinite", "*", "plural", "genitive"): "",
}


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
class ArticleCaseTerm:
    term_id: str
    text: str
    gender: str
    part_of_speech: str
    inflection_pattern: str
    stem: str
    noun_forms: tuple[tuple[str, str], ...]
    phrase_forms: tuple[tuple[str, str], ...]


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
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in CASE_VALUES)


def entry_genders(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in GENDER_VALUES)


def entry_numbers(entry: DictionaryEntry) -> tuple[str, ...]:
    return tuple(grammeme for grammeme in entry.grammemes if grammeme in NUMBER_VALUES)


def supported_entry(entry: DictionaryEntry) -> bool:
    return bool(set(entry.parts_of_speech()) & SUPPORTED_POS)


def signature(entry: DictionaryEntry) -> tuple:
    return (
        tuple(sorted(entry.parts_of_speech())),
        tuple(sorted(entry_cases(entry))),
        tuple(sorted(entry_genders(entry))),
        tuple(sorted(entry_numbers(entry))),
        tuple(sorted(entry.inflections)),
    )


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    signatures = {signature(entry) for entry in entries}
    cases = {case for entry in entries for case in entry_cases(entry)}
    genders = {gender for entry in entries for gender in entry_genders(entry)}
    numbers = {number for entry in entries for number in entry_numbers(entry)}
    inflections = {inflection for entry in entries for inflection in entry.inflections}
    parts_of_speech = {pos for entry in entries for pos in entry.parts_of_speech()}

    reasons = []
    if len(signatures) > 1:
        reasons.append("multiple-analyses")
    if len(cases) > 1 or any(len(entry_cases(entry)) > 1 for entry in entries):
        reasons.append("multiple-cases")
    if len(genders) > 1 or any(len(entry_genders(entry)) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if len(numbers) > 1 or any(len(entry_numbers(entry)) > 1 for entry in entries):
        reasons.append("multiple-numbers")
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
        "inflections": list(entry.inflections),
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


def render_template(template: str, stem: str) -> str:
    return template.replace("{stem}", stem)


def article_for(article: str, gender: str, number: str, grammatical_case: str) -> str | None:
    key_gender = "*" if number == "plural" else gender
    return ARTICLE_FORMS.get((article, key_gender, number, grammatical_case))


def phrase(article: str, noun_form: str) -> str:
    return noun_form if article == "" else f"{article} {noun_form}"


def term_id_for(entry: DictionaryEntry) -> str:
    normalized = entry.normalized_surface.replace(" ", "_")
    return f"de.article-case.{normalized}"


def article_case_term(
    entry: DictionaryEntry, patterns: dict[str, InflectionPattern]
) -> tuple[ArticleCaseTerm | None, str | None]:
    if not supported_entry(entry):
        return None, "unsupported-part-of-speech"
    if "nominative" not in entry_cases(entry):
        return None, "not-nominative"
    numbers = set(entry_numbers(entry))
    if "singular" not in numbers:
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
    noun_forms: dict[str, str] = {}
    for slot in pattern.slots:
        if slot.case not in CASE_VALUES or slot.number not in NUMBER_VALUES:
            continue
        if slot.gender is not None and slot.gender != gender:
            continue
        form_key = f"{slot.case}.{slot.number}"
        form_value = render_template(slot.template, stem)
        previous = noun_forms.get(form_key)
        if previous is not None and previous != form_value:
            return None, "conflicting-noun-form-key"
        noun_forms[form_key] = form_value

    required_noun_keys = {f"{case}.{number}" for case in CASE_VALUES for number in NUMBER_VALUES}
    missing_noun_keys = sorted(required_noun_keys - set(noun_forms))
    if missing_noun_keys:
        return None, "missing-noun-case-forms"
    if "plural" in numbers and any(
        noun_forms[f"{grammatical_case}.singular"] != noun_forms[f"{grammatical_case}.plural"]
        for grammatical_case in CASE_VALUES
    ):
        return None, "singular-plural-surface-not-invariant"

    phrase_forms = {}
    for article in sorted(ARTICLE_VALUES):
        for grammatical_case in sorted(CASE_VALUES):
            for number in sorted(NUMBER_VALUES):
                article_form = article_for(article, gender, number, grammatical_case)
                if article_form is None:
                    return None, "missing-article-form"
                noun_form = noun_forms[f"{grammatical_case}.{number}"]
                phrase_forms[f"{article}.{grammatical_case}.{number}"] = phrase(article_form, noun_form)

    part_of_speech = sorted(set(entry.parts_of_speech()) & SUPPORTED_POS)[0]
    return (
        ArticleCaseTerm(
            term_id_for(entry),
            entry.surface,
            gender,
            part_of_speech,
            pattern_id,
            stem,
            tuple(sorted(noun_forms.items())),
            tuple(sorted(phrase_forms.items())),
        ),
        None,
    )


def estimate_runtime_pack_bytes(terms: list[ArticleCaseTerm]) -> dict:
    strings = set()
    form_rows = 0
    for term in terms:
        strings.add(term.term_id)
        strings.add(term.text)
        strings.add(term.gender)
        strings.add(term.inflection_pattern)
        for key, value in term.phrase_forms:
            strings.add(key)
            strings.add(value)
            form_rows += 1
    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in strings)
    term_row_bytes = len(terms) * 20
    form_row_bytes = form_rows * 12
    return {
        "stringPoolBytes": string_pool_bytes,
        "termRowBytes": term_row_bytes,
        "formRows": form_rows,
        "formRowBytes": form_row_bytes,
        "binaryLowerBoundBytes": string_pool_bytes + term_row_bytes + form_row_bytes,
    }


def review_policy(
    candidate_terms: list[ArticleCaseTerm],
    skipped_candidate_terms: Counter[str],
    ambiguous_surfaces: list[dict],
    ambiguity_reason_counts: Counter[str],
) -> dict:
    return {
        "runtimeExport": "closed-world-article-case-forms",
        "automaticExportTerms": len(candidate_terms),
        "reviewRequiredSurfaces": len(ambiguous_surfaces),
        "blockedDictionaryEntries": sum(skipped_candidate_terms.values()),
        "reviewRequiredReasons": dict(sorted(ambiguity_reason_counts.items())),
        "blockedReasons": dict(sorted(skipped_candidate_terms.items())),
    }


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

    candidate_terms: list[ArticleCaseTerm] = []
    skipped_candidate_terms: Counter[str] = Counter()
    seen_term_ids: set[str] = set()
    for entry in entries:
        term, reason = article_case_term(entry, patterns)
        if reason is not None:
            skipped_candidate_terms[reason] += 1
            continue
        assert term is not None
        if term.term_id in seen_term_ids:
            skipped_candidate_terms["duplicate-term-id"] += 1
            continue
        seen_term_ids.add(term.term_id)
        candidate_terms.append(term)

    slot_cases = Counter(slot.case or "<missing>" for pattern in used_patterns for slot in pattern.slots)
    slot_genders = Counter(slot.gender or "<missing>" for pattern in used_patterns for slot in pattern.slots)
    slot_numbers = Counter(slot.number or "<missing>" for pattern in used_patterns for slot in pattern.slots)

    return {
        "schema": "mojito-mf2-inflection/de-article-case-pack-report/v0",
        "locale": "de",
        "sources": [pack_source(dictionary), pack_source(inflectional)],
        "provenance": pack_provenance(
            [dictionary, inflectional],
            "dev-docs/experiments/mf2-inflection/de_article_case_report.py",
        ),
        "articleForms": [
            {
                "article": article,
                "gender": gender,
                "number": number,
                "case": grammatical_case,
                "form": form,
            }
            for (article, gender, number, grammatical_case), form in sorted(ARTICLE_FORMS.items())
        ],
        "counts": {
            "dictionaryEntries": len(entries),
            "supportedEntries": len(supported_entries),
            "uniqueSupportedSurfaces": len(by_surface),
            "skippedLines": len(skipped),
            "ambiguousSupportedSurfaces": len(ambiguous_surfaces),
            "dictionaryInflectionPatterns": len(pattern_ids),
            "missingInflectionPatterns": len(missing_patterns),
            "inflectionalPatterns": len(patterns),
            "nounInflectionalPatterns": len(noun_patterns),
            "usedInflectionalPatterns": len(used_patterns),
            "articleCaseCandidateTerms": len(candidate_terms),
        },
        "features": {
            "case": count_values(supported_entries, entry_cases),
            "gender": count_values(supported_entries, entry_genders),
            "number": count_values(supported_entries, entry_numbers),
            "partOfSpeech": count_values(supported_entries, DictionaryEntry.parts_of_speech),
            "patternSlotCases": dict(sorted(slot_cases.items())),
            "patternSlotGenders": dict(sorted(slot_genders.items())),
            "patternSlotNumbers": dict(sorted(slot_numbers.items())),
            "ambiguityReasons": dict(sorted(ambiguity_reason_counts.items())),
            "candidateSkipReasons": dict(sorted(skipped_candidate_terms.items())),
        },
        "reviewPolicy": review_policy(
            candidate_terms,
            skipped_candidate_terms,
            ambiguous_surfaces,
            ambiguity_reason_counts,
        ),
        "sizeEstimates": estimate_runtime_pack_bytes(candidate_terms),
        "samples": {
            "articleCaseCandidates": [
                {
                    "termId": term.term_id,
                    "text": term.text,
                    "gender": term.gender,
                    "partOfSpeech": term.part_of_speech,
                    "inflectionPattern": term.inflection_pattern,
                    "stem": term.stem,
                    "nounForms": dict(term.noun_forms),
                    "phraseForms": dict(term.phrase_forms),
                }
                for term in candidate_terms[:max_samples]
            ],
            "ambiguousSurfaces": ambiguous_surfaces[:max_samples],
            "missingInflectionPatterns": missing_patterns[:max_samples],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", required=True, type=Path, help="Unicode German dictionary .lst file.")
    parser.add_argument("--inflectional", required=True, type=Path, help="Unicode German inflectional XML file.")
    parser.add_argument("--max-samples", type=int, default=20, help="Maximum sample rows to include.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    args = parser.parse_args()

    report = build_report(args.dictionary, args.inflectional, args.max_samples)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
