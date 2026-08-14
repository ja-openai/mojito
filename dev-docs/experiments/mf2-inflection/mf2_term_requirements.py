#!/usr/bin/env python3
"""Expand a small MF2 :term subset into term-pack requirements."""

from __future__ import annotations

import argparse
import json
import re
from collections.abc import Mapping
from pathlib import Path


TERM_EXPRESSION_RE = re.compile(r"\{\$(?P<argument>[A-Za-z_][\w.-]*)\s+:term(?P<options>[^{}]*)\}")
SUPPORTED_OPTIONS = {"article", "case", "count", "definiteness", "number", "preposition"}
SUPPORTED_ARTICLES = {"definite", "indefinite"}
SUPPORTED_CASES = {
    "ablative",
    "accusative",
    "dative",
    "direct",
    "genitive",
    "instrumental",
    "locative",
    "nominative",
    "oblique",
    "prepositional",
    "sociative",
    "vocative",
}
SUPPORTED_DEFINITENESS = {"construct", "definite", "indefinite"}
SUPPORTED_NUMBERS = {"dual", "plural", "singular"}
TURKISH_SUFFIX_CASES = {"ablative", "accusative", "dative", "locative", "nominative"}
SUPPORTED_PREPOSITIONS = {"de", "em", "por"}
VARIABLE_REFERENCE_RE = re.compile(r"\$[A-Za-z_][\w.-]*")


def parse_options(raw_options: str) -> dict[str, str]:
    options: dict[str, str] = {}
    for token in raw_options.split():
        if "=" in token:
            key, value = token.split("=", 1)
            value = value.strip('"')
        else:
            key = token
            value = "true"
        if not key:
            raise ValueError(f"Invalid term option token: {token}")
        if key in options:
            raise ValueError(f"Duplicate term option: {key}")
        options[key] = value
    return options


def validate_term_options(options: Mapping[str, str]) -> None:
    for key, value in options.items():
        if key not in SUPPORTED_OPTIONS:
            raise ValueError(f"Unsupported term option: {key}")
        if not value.strip():
            raise ValueError(f"Term option value must not be blank: {key}")

    article = options.get("article")
    if article is not None and article not in SUPPORTED_ARTICLES:
        raise ValueError(f"Unsupported article option: {article}")

    grammatical_case = options.get("case")
    if grammatical_case is not None and grammatical_case not in SUPPORTED_CASES:
        raise ValueError(f"Unsupported case option: {grammatical_case}")

    definiteness = options.get("definiteness")
    if definiteness is not None and definiteness not in SUPPORTED_DEFINITENESS:
        raise ValueError(f"Unsupported definiteness option: {definiteness}")
    if definiteness is not None and article is not None:
        raise ValueError("Definiteness option cannot be combined with article option")

    preposition = options.get("preposition")
    if preposition is not None and preposition not in SUPPORTED_PREPOSITIONS:
        raise ValueError(f"Unsupported preposition option: {preposition}")
    if preposition is not None and article is None:
        raise ValueError("Preposition option requires article option")
    if preposition is not None and grammatical_case is not None:
        raise ValueError("Preposition option cannot be combined with case option")
    if preposition is not None and not is_portuguese_preposition_article_combination(preposition, article):
        raise ValueError(f"Unsupported preposition/article combination: {preposition} + {article}")

    count = options.get("count")
    if count is not None and not VARIABLE_REFERENCE_RE.fullmatch(count):
        raise ValueError(f"Count option must reference a variable: {count}")

    number = options.get("number")
    if number is not None and number not in SUPPORTED_NUMBERS:
        raise ValueError(f"Unsupported number option: {number}")
    if number is not None and count is not None:
        raise ValueError("Number option cannot be combined with count option")


def count_variable_name(count_reference: str) -> str:
    if not VARIABLE_REFERENCE_RE.fullmatch(count_reference):
        raise ValueError(f"Count option must reference a variable: {count_reference}")
    return count_reference[1:]


def term_usages(message: str) -> list[dict]:
    usages = []
    for match in TERM_EXPRESSION_RE.finditer(message):
        usages.append(
            {
                "argument": match.group("argument"),
                "options": parse_options(match.group("options")),
                "span": [match.start(), match.end()],
            }
        )
    return usages


def is_spanish_locale(locale: str | None) -> bool:
    return locale == "es" or bool(locale and (locale.startswith("es-") or locale.startswith("es_")))


def is_italian_locale(locale: str | None) -> bool:
    return locale == "it" or bool(locale and (locale.startswith("it-") or locale.startswith("it_")))


def is_portuguese_locale(locale: str | None) -> bool:
    return locale == "pt" or bool(locale and (locale.startswith("pt-") or locale.startswith("pt_")))


def is_turkish_locale(locale: str | None) -> bool:
    return locale == "tr" or bool(locale and (locale.startswith("tr-") or locale.startswith("tr_")))


def is_spanish_article_composition(locale: str | None, options: Mapping[str, str]) -> bool:
    return (
        is_spanish_locale(locale)
        and options.get("case") is None
        and options.get("preposition") is None
        and options.get("article") in SUPPORTED_ARTICLES
    )


def is_italian_article_composition(locale: str | None, options: Mapping[str, str]) -> bool:
    return (
        is_italian_locale(locale)
        and options.get("case") is None
        and options.get("preposition") is None
        and options.get("article") in SUPPORTED_ARTICLES
    )


def is_portuguese_preposition_article_combination(preposition: str | None, article: str | None) -> bool:
    if preposition is None:
        return article in SUPPORTED_ARTICLES
    if preposition in {"de", "por"}:
        return article == "definite"
    if preposition == "em":
        return article in SUPPORTED_ARTICLES
    return False


def is_portuguese_article_composition(locale: str | None, options: Mapping[str, str]) -> bool:
    return (
        is_portuguese_locale(locale)
        and options.get("case") is None
        and is_portuguese_preposition_article_combination(options.get("preposition"), options.get("article"))
    )


def is_turkish_suffix_composition(locale: str | None, options: Mapping[str, str]) -> bool:
    grammatical_case = options.get("case")
    return (
        is_turkish_locale(locale)
        and options.get("article") is None
        and options.get("preposition") is None
        and (
            (grammatical_case is None or grammatical_case in TURKISH_SUFFIX_CASES)
            if "count" in options
            else grammatical_case in TURKISH_SUFFIX_CASES
        )
    )


def requirements_for_usage(options: Mapping[str, str], locale: str | None = None) -> list[str]:
    validate_term_options(options)
    requirements = ["partOfSpeech=noun"]
    if not is_turkish_locale(locale):
        requirements.append("gender")
    requirements.append("number")
    article = options.get("article")
    grammatical_case = options.get("case")
    definiteness = options.get("definiteness")
    explicit_number = options.get("number")
    preposition = options.get("preposition")
    selected_numbers = ["singular", "plural"] if explicit_number is None else [explicit_number]
    turkish_suffix_composition = is_turkish_suffix_composition(locale, options)
    spanish_article_composition = is_spanish_article_composition(locale, options)
    italian_article_composition = is_italian_article_composition(locale, options)
    portuguese_article_composition = is_portuguese_article_composition(locale, options)
    if turkish_suffix_composition:
        requirements.extend(
            [
                "turkishSuffix.vowelEnd",
                "turkishSuffix.frontVowel",
                "turkishSuffix.roundedVowel",
                "turkishSuffix.hardConsonant",
                "forms.bare.singular",
            ]
        )
    elif spanish_article_composition:
        requirements.append("stress")
        requirements.append("forms.bare.singular")
        requirements.append("forms.bare.plural")
    elif italian_article_composition:
        requirements.append("articleClass")
        requirements.append("forms.bare.singular")
        requirements.append("forms.bare.plural")
    elif portuguese_article_composition:
        requirements.append("forms.bare.singular")
        requirements.append("forms.bare.plural")
    elif preposition:
        for number in selected_numbers:
            requirements.append(f"forms.preposition.{preposition}.{article}.{number}")
    elif definiteness and grammatical_case:
        for number in selected_numbers:
            requirements.append(f"forms.{definiteness}.{grammatical_case}.{number}")
    elif definiteness:
        for number in selected_numbers:
            requirements.append(f"forms.{definiteness}.{number}")
    elif article and grammatical_case:
        for number in selected_numbers:
            requirements.append(f"forms.{article}.{grammatical_case}.{number}")
    elif article in {"definite", "indefinite"}:
        requirements.append("elision")
        for number in selected_numbers:
            requirements.append(f"forms.{article}.{number}")
    elif grammatical_case:
        for number in selected_numbers:
            requirements.append(f"forms.{grammatical_case}.{number}")
    elif explicit_number:
        requirements.append(f"forms.bare.{explicit_number}")
    if (
        "count" in options
        and not spanish_article_composition
        and not italian_article_composition
        and not portuguese_article_composition
        and not turkish_suffix_composition
    ):
        requirements.append("forms.count.one")
        requirements.append("forms.count.other")
    if (
        not article
        and not grammatical_case
        and not definiteness
        and not explicit_number
        and not preposition
        and "count" not in options
    ):
        requirements.append("forms.bare.singular")
        requirements.append("forms.bare.plural")
    return requirements


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_argument_terms(catalog: dict) -> None:
    messages = catalog.get("messages", {})
    argument_terms = catalog.get("argumentTerms", {})
    for message_id, terms_by_argument in argument_terms.items():
        if message_id not in messages:
            raise ValueError(f"argumentTerms references unknown message: {message_id}")
        if not isinstance(terms_by_argument, dict):
            raise ValueError(f"argumentTerms for message must be an object: {message_id}")
        for argument, term_ids in terms_by_argument.items():
            if not argument:
                raise ValueError(f"argumentTerms contains blank argument for message: {message_id}")
            if not isinstance(term_ids, list):
                raise ValueError(f"argumentTerms values must be arrays: {message_id}.{argument}")
            seen = set()
            for term_id in term_ids:
                if not isinstance(term_id, str) or not term_id:
                    raise ValueError(f"argumentTerms contains blank term id: {message_id}.{argument}")
                if term_id in seen:
                    raise ValueError(f"argumentTerms contains duplicate term id: {message_id}.{argument}.{term_id}")
                seen.add(term_id)


def check_term_requirement(term: dict | None, requirement: str) -> str | None:
    if term is None:
        return "missing-term"

    morphology = term.get("morphology", {})
    forms = term.get("forms", {})

    if requirement == "partOfSpeech=noun":
        return None if morphology.get("partOfSpeech") == "noun" else "partOfSpeech"
    if requirement == "gender":
        return None if morphology.get("gender") else "gender"
    if requirement == "number":
        return None if morphology.get("number") else "number"
    if requirement == "stress":
        return None if "stressed" in morphology else "stressed"
    if requirement == "articleClass":
        return None if morphology.get("articleClass") else "articleClass"
    if requirement == "elision":
        return None if "startsWithVowelSound" in morphology else "startsWithVowelSound"
    if requirement.startswith("turkishSuffix."):
        suffix = morphology.get("turkishSuffix", {})
        suffix_key = requirement.removeprefix("turkishSuffix.")
        return None if suffix_key in suffix else suffix_key
    if requirement.startswith("forms."):
        form_key = requirement.removeprefix("forms.")
        return None if forms.get(form_key) else form_key

    return requirement


def validate_terms(requirements: list[str], term_ids: list[str], term_pack: dict | None) -> list[dict]:
    if term_pack is None or not term_ids:
        return []

    terms = term_pack.get("terms", {})
    validations = []
    for term_id in term_ids:
        term = terms.get(term_id)
        missing = [missing for requirement in requirements if (missing := check_term_requirement(term, requirement))]
        validations.append({"termId": term_id, "status": "ok" if not missing else "missing", "missing": missing})
    return validations


def build_report(catalog: dict, term_pack: dict | None, mode: str = "closed-world") -> dict:
    if mode not in {"closed-world", "open-world"}:
        raise ValueError(f"unsupported validation mode: {mode}")
    validate_argument_terms(catalog)
    messages = catalog.get("messages", {})
    argument_terms = catalog.get("argumentTerms", {})
    locale = catalog.get("locale") or (term_pack or {}).get("locale")
    report_messages = {}
    diagnostics = []

    for message_id, message in messages.items():
        usages = []
        for usage in term_usages(message):
            requirements = requirements_for_usage(usage["options"], locale)
            term_ids = argument_terms.get(message_id, {}).get(usage["argument"], [])
            validations = validate_terms(requirements, term_ids, term_pack)
            usages.append(
                {
                    **usage,
                    "requirements": requirements,
                    "termIds": term_ids,
                    "validations": validations,
                }
            )
            if not term_ids and mode == "closed-world":
                diagnostics.append(
                    {
                        "messageId": message_id,
                        "argument": usage["argument"],
                        "termId": None,
                        "span": usage["span"],
                        "missing": ["missing-argument-terms"],
                    }
                )
            for validation in validations:
                if validation["status"] != "ok":
                    diagnostics.append(
                        {
                            "messageId": message_id,
                            "argument": usage["argument"],
                            "termId": validation["termId"],
                            "span": usage["span"],
                            "missing": validation["missing"],
                        }
                    )
        report_messages[message_id] = {"source": message, "termUsages": usages}

    return {
        "schema": "mojito-mf2-inflection/term-requirement-report/v0",
        "locale": locale,
        "messages": report_messages,
        "diagnostics": diagnostics,
        "summary": {
            "messages": len(messages),
            "termUsages": sum(len(message["termUsages"]) for message in report_messages.values()),
            "diagnostics": len(diagnostics),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path, help="JSON file with message id to MF2 source mappings.")
    parser.add_argument("--term-pack", type=Path, help="Optional readable term pack JSON to validate against.")
    parser.add_argument("--mode", choices=["closed-world", "open-world"], default="closed-world", help="Validation mode for unbound :term usages.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    args = parser.parse_args()

    catalog = load_json(args.catalog)
    term_pack = load_json(args.term_pack) if args.term_pack else None
    payload = json.dumps(build_report(catalog, term_pack, args.mode), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
