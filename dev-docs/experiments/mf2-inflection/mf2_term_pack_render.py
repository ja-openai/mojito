#!/usr/bin/env python3
"""Render the tiny MF2 :term subset from a compiled term pack."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

from mf2_term_requirements import (
    TERM_EXPRESSION_RE,
    count_variable_name,
    is_portuguese_preposition_article_combination,
    parse_options,
    validate_term_options,
)


PLACEHOLDER_RE = re.compile(r"\{\$(?P<name>[A-Za-z_][\w.-]*)\}")
GENDER_SHIFT = 4
GENDER_MASK = 0xF
MASCULINE_GENDER = 1
FEMININE_GENDER = 2
STRESSED_BIT = 1 << 14
ARTICLE_CLASS_SHIFT = 16
ARTICLE_CLASS_MASK = 0xF
STANDARD_ARTICLE_CLASS = 1
LO_ARTICLE_CLASS = 2
ELISION_ARTICLE_CLASS = 3
TURKISH_SUFFIX_METADATA_BIT = 1 << 20
TURKISH_VOWEL_END_BIT = 1 << 21
TURKISH_FRONT_VOWEL_BIT = 1 << 22
TURKISH_ROUNDED_VOWEL_BIT = 1 << 23
TURKISH_HARD_CONSONANT_BIT = 1 << 24


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_assignment(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError(f"Expected name=value, got {value!r}")
    key, raw = value.split("=", 1)
    return key, raw


def plural_category(value: str) -> str:
    try:
        parsed = float(value)
    except ValueError as error:
        raise ValueError(f"Count variable must be numeric: {value}") from error
    if not math.isfinite(parsed):
        raise ValueError(f"Count variable must be finite: {value}")
    return "one" if parsed == 1 else "other"


def build_runtime(pack: dict) -> dict:
    diagnostics = pack.get("diagnostics", [])
    if diagnostics:
        raise ValueError("Compiled term pack contains diagnostics")

    strings = pack["strings"]
    runtime = {"locale": pack.get("locale"), "forms": {}, "terms": {}}
    for term in pack.get("terms", []):
        runtime["terms"][strings[term["id"]]] = term
    for form_set in pack["formSets"]:
        term_id = strings[form_set["term"]]
        runtime["forms"][term_id] = {
            strings[row["key"]]: {"value": strings[row["value"]], "kind": row["kind"]}
            for row in form_set["forms"]
        }
    return runtime


def select_form(options: dict[str, str], variables: dict[str, str]) -> tuple[str, str]:
    validate_term_options(options)
    count_reference = options.get("count")
    count_value = None
    if count_reference:
        count_name = count_variable_name(count_reference)
        if count_name not in variables:
            raise ValueError(f"Missing count variable: {count_name}")
        count_value = variables[count_name]
    explicit_number = options.get("number")
    number = explicit_number or (
        "singular" if count_reference is None or plural_category(count_value) == "one" else "plural"
    )

    article = options.get("article")
    grammatical_case = options.get("case")
    definiteness = options.get("definiteness")
    preposition = options.get("preposition")
    if preposition:
        return f"preposition.{preposition}.{article}.{number}", number
    if definiteness and grammatical_case:
        return f"{definiteness}.{grammatical_case}.{number}", number
    if definiteness:
        return f"{definiteness}.{number}", number
    if article and grammatical_case:
        return f"{article}.{grammatical_case}.{number}", number
    if article in {"definite", "indefinite"}:
        return f"{article}.{number}", number
    if grammatical_case:
        return f"{grammatical_case}.{number}", number
    if explicit_number:
        return f"bare.{number}", number
    if count_reference:
        return f"count.{plural_category(count_value)}", number
    return "bare.singular", "singular"


def select_form_key(options: dict[str, str], variables: dict[str, str]) -> str:
    return select_form(options, variables)[0]


def is_spanish_locale(locale: str | None) -> bool:
    return locale == "es" or bool(locale and (locale.startswith("es-") or locale.startswith("es_")))


def is_italian_locale(locale: str | None) -> bool:
    return locale == "it" or bool(locale and (locale.startswith("it-") or locale.startswith("it_")))


def is_portuguese_locale(locale: str | None) -> bool:
    return locale == "pt" or bool(locale and (locale.startswith("pt-") or locale.startswith("pt_")))


def is_turkish_locale(locale: str | None) -> bool:
    return locale == "tr" or bool(locale and (locale.startswith("tr-") or locale.startswith("tr_")))


def is_spanish_article_composition(runtime: dict, options: dict[str, str]) -> bool:
    return (
        is_spanish_locale(runtime.get("locale"))
        and options.get("case") is None
        and options.get("preposition") is None
        and options.get("article") in {"definite", "indefinite"}
    )


def is_italian_article_composition(runtime: dict, options: dict[str, str]) -> bool:
    return (
        is_italian_locale(runtime.get("locale"))
        and options.get("case") is None
        and options.get("preposition") is None
        and options.get("article") in {"definite", "indefinite"}
    )


def is_portuguese_article_composition(runtime: dict, options: dict[str, str]) -> bool:
    return (
        is_portuguese_locale(runtime.get("locale"))
        and options.get("case") is None
        and is_portuguese_preposition_article_combination(options.get("preposition"), options.get("article"))
    )


def is_turkish_suffix_composition(runtime: dict, options: dict[str, str]) -> bool:
    grammatical_case = options.get("case")
    return (
        is_turkish_locale(runtime.get("locale"))
        and options.get("article") is None
        and options.get("preposition") is None
        and (
            grammatical_case in {None, "ablative", "accusative", "dative", "locative", "nominative"}
            if "count" in options
            else grammatical_case in {"ablative", "accusative", "dative", "locative", "nominative"}
        )
    )


def grammatical_gender(term_id: str, locale_name: str, feature_bits: int) -> str:
    gender = (feature_bits >> GENDER_SHIFT) & GENDER_MASK
    if gender == MASCULINE_GENDER:
        return "masculine"
    if gender == FEMININE_GENDER:
        return "feminine"
    raise ValueError(
        f"{locale_name} article composition requires masculine or feminine gender for term {term_id}"
    )


def spanish_article(article: str, gender: str, number: str, stressed: bool) -> str:
    if article == "definite":
        if number == "plural":
            return "los" if gender == "masculine" else "las"
        if gender == "feminine" and not stressed:
            return "la"
        return "el"

    if number == "plural":
        return "unos" if gender == "masculine" else "unas"
    if gender == "feminine" and not stressed:
        return "una"
    return "un"


def italian_article_class(term_id: str, feature_bits: int) -> str:
    article_class = (feature_bits >> ARTICLE_CLASS_SHIFT) & ARTICLE_CLASS_MASK
    if article_class == STANDARD_ARTICLE_CLASS:
        return "standard"
    if article_class == LO_ARTICLE_CLASS:
        return "lo"
    if article_class == ELISION_ARTICLE_CLASS:
        return "elision"
    raise ValueError(f"Italian article composition requires articleClass metadata for term {term_id}")


def italian_article(article: str, gender: str, number: str, article_class: str) -> str:
    if article == "definite":
        if gender == "masculine" and number == "singular":
            return {"standard": "il", "lo": "lo", "elision": "l'"}[article_class]
        if gender == "masculine" and number == "plural":
            return {"standard": "i", "lo": "gli", "elision": "gli"}[article_class]
        if gender == "feminine" and number == "singular":
            return "l'" if article_class == "elision" else "la"
        return "le"

    if gender == "masculine" and number == "singular":
        return "uno" if article_class == "lo" else "un"
    if gender == "masculine" and number == "plural":
        return "dei" if article_class == "standard" else "degli"
    if gender == "feminine" and number == "singular":
        return "un'" if article_class == "elision" else "una"
    return "delle"


def gender_number_form(
    gender: str,
    number: str,
    masculine_singular: str,
    feminine_singular: str,
    masculine_plural: str,
    feminine_plural: str,
) -> str:
    if number == "plural":
        return masculine_plural if gender == "masculine" else feminine_plural
    return masculine_singular if gender == "masculine" else feminine_singular


def portuguese_article(article: str, preposition: str | None, gender: str, number: str) -> str:
    key = f"{preposition or 'article'}.{article}"
    if key == "article.definite":
        return gender_number_form(gender, number, "o", "a", "os", "as")
    if key == "article.indefinite":
        return gender_number_form(gender, number, "um", "uma", "uns", "umas")
    if key == "de.definite":
        return gender_number_form(gender, number, "do", "da", "dos", "das")
    if key == "em.definite":
        return gender_number_form(gender, number, "no", "na", "nos", "nas")
    if key == "em.indefinite":
        return gender_number_form(gender, number, "num", "numa", "nuns", "numas")
    if key == "por.definite":
        return gender_number_form(gender, number, "pelo", "pela", "pelos", "pelas")
    raise ValueError(f"Unsupported Portuguese article composition: {preposition} + {article}")


def article_phrase(article: str, bare_value: str) -> str:
    separator = "" if article.endswith("'") else " "
    return f"{article}{separator}{bare_value}"


def turkish_suffix_metadata(feature_bits: int) -> dict[str, bool]:
    return {
        "vowelEnd": bool(feature_bits & TURKISH_VOWEL_END_BIT),
        "frontVowel": bool(feature_bits & TURKISH_FRONT_VOWEL_BIT),
        "roundedVowel": bool(feature_bits & TURKISH_ROUNDED_VOWEL_BIT),
        "hardConsonant": bool(feature_bits & TURKISH_HARD_CONSONANT_BIT),
    }


def turkish_four_way_vowel(metadata: dict[str, bool]) -> str:
    if metadata["frontVowel"]:
        return "ü" if metadata["roundedVowel"] else "i"
    return "u" if metadata["roundedVowel"] else "ı"


def turkish_case_suffix(grammatical_case: str, metadata: dict[str, bool]) -> str:
    two_way_vowel = "e" if metadata["frontVowel"] else "a"
    consonant = "t" if metadata["hardConsonant"] else "d"
    if grammatical_case == "accusative":
        return ("y" if metadata["vowelEnd"] else "") + turkish_four_way_vowel(metadata)
    if grammatical_case == "dative":
        return ("y" if metadata["vowelEnd"] else "") + two_way_vowel
    if grammatical_case == "locative":
        return consonant + two_way_vowel
    if grammatical_case == "ablative":
        return consonant + two_way_vowel + "n"
    raise ValueError(f"Unsupported Turkish case: {grammatical_case}")


def turkish_inflect(stem: str, grammatical_case: str | None, number: str, metadata: dict[str, bool]) -> str:
    inflected_stem = stem
    suffix_metadata = metadata
    if number == "plural":
        plural_suffix = "ler" if metadata["frontVowel"] else "lar"
        inflected_stem += plural_suffix
        suffix_metadata = {
            "vowelEnd": False,
            "frontVowel": metadata["frontVowel"],
            "roundedVowel": False,
            "hardConsonant": False,
        }
    if grammatical_case is None or grammatical_case == "nominative":
        return inflected_stem
    return inflected_stem + turkish_case_suffix(grammatical_case, suffix_metadata)


def render_pattern(value: str, variables: dict[str, str]) -> str:
    def replace(match: re.Match) -> str:
        name = match.group("name")
        if name not in variables:
            raise ValueError(f"Missing pattern variable: {name}")
        return variables[name]

    return PLACEHOLDER_RE.sub(replace, value)


def render_form(form: dict, variables: dict[str, str]) -> str:
    value = form["value"]
    return render_pattern(value, variables) if form["kind"] == "pattern" else value


def maybe_render_composed_spanish_article_term(
    runtime: dict,
    term_id: str,
    forms: dict[str, dict],
    options: dict[str, str],
    number: str,
    variables: dict[str, str],
) -> str | None:
    if not is_spanish_article_composition(runtime, options):
        return None

    term = runtime["terms"].get(term_id)
    if term is None:
        raise ValueError(f"Missing Spanish term metadata for term {term_id}")
    bare_key = f"bare.{number}"
    bare_form = forms.get(bare_key)
    if bare_form is None:
        raise ValueError(f"Missing Spanish bare form {bare_key} for term {term_id}")

    feature_bits = term["featureBits"]
    article = spanish_article(
        options["article"],
        grammatical_gender(term_id, "Spanish", feature_bits),
        number,
        bool(feature_bits & STRESSED_BIT),
    )
    return f"{article} {render_form(bare_form, variables)}"


def maybe_render_composed_italian_article_term(
    runtime: dict,
    term_id: str,
    forms: dict[str, dict],
    options: dict[str, str],
    number: str,
    variables: dict[str, str],
) -> str | None:
    if not is_italian_article_composition(runtime, options):
        return None

    term = runtime["terms"].get(term_id)
    if term is None:
        raise ValueError(f"Missing Italian term metadata for term {term_id}")
    bare_key = f"bare.{number}"
    bare_form = forms.get(bare_key)
    if bare_form is None:
        raise ValueError(f"Missing Italian bare form {bare_key} for term {term_id}")

    feature_bits = term["featureBits"]
    article = italian_article(
        options["article"],
        grammatical_gender(term_id, "Italian", feature_bits),
        number,
        italian_article_class(term_id, feature_bits),
    )
    return article_phrase(article, render_form(bare_form, variables))


def maybe_render_composed_portuguese_article_term(
    runtime: dict,
    term_id: str,
    forms: dict[str, dict],
    options: dict[str, str],
    number: str,
    variables: dict[str, str],
) -> str | None:
    if not is_portuguese_article_composition(runtime, options):
        return None

    term = runtime["terms"].get(term_id)
    if term is None:
        raise ValueError(f"Missing Portuguese term metadata for term {term_id}")
    bare_key = f"bare.{number}"
    bare_form = forms.get(bare_key)
    if bare_form is None:
        raise ValueError(f"Missing Portuguese bare form {bare_key} for term {term_id}")

    feature_bits = term["featureBits"]
    article = portuguese_article(
        options["article"],
        options.get("preposition"),
        grammatical_gender(term_id, "Portuguese", feature_bits),
        number,
    )
    return f"{article} {render_form(bare_form, variables)}"


def maybe_render_composed_turkish_suffix_term(
    runtime: dict,
    term_id: str,
    forms: dict[str, dict],
    options: dict[str, str],
    number: str,
    variables: dict[str, str],
) -> str | None:
    if not is_turkish_suffix_composition(runtime, options):
        return None

    term = runtime["terms"].get(term_id)
    if term is None:
        raise ValueError(f"Missing Turkish term metadata for term {term_id}")
    feature_bits = term["featureBits"]
    if not feature_bits & TURKISH_SUFFIX_METADATA_BIT:
        raise ValueError(f"Turkish suffix composition requires turkishSuffix metadata for term {term_id}")

    bare_form = forms.get("bare.singular")
    if bare_form is None:
        raise ValueError(f"Missing Turkish bare form bare.singular for term {term_id}")

    return turkish_inflect(
        render_form(bare_form, variables),
        options.get("case"),
        number,
        turkish_suffix_metadata(feature_bits),
    )


def render_message(message: str, runtime: dict, term_args: dict[str, str], variables: dict[str, str]) -> str:
    def replace(match: re.Match) -> str:
        argument = match.group("argument")
        term_id = term_args.get(argument)
        if term_id is None:
            raise ValueError(f"Missing term argument: {argument}")
        forms = runtime["forms"].get(term_id)
        if forms is None:
            raise ValueError(f"Missing compiled term: {term_id}")

        options = parse_options(match.group("options"))
        form_key, number = select_form(options, variables)
        form = forms.get(form_key)
        if form is None:
            composed = maybe_render_composed_spanish_article_term(
                runtime, term_id, forms, options, number, variables
            )
            if composed is not None:
                return composed
            composed = maybe_render_composed_italian_article_term(
                runtime, term_id, forms, options, number, variables
            )
            if composed is not None:
                return composed
            composed = maybe_render_composed_portuguese_article_term(
                runtime, term_id, forms, options, number, variables
            )
            if composed is not None:
                return composed
            composed = maybe_render_composed_turkish_suffix_term(
                runtime, term_id, forms, options, number, variables
            )
            if composed is not None:
                return composed
            raise ValueError(f"Missing form {form_key!r} for term {term_id!r}")
        return render_form(form, variables)

    rendered = TERM_EXPRESSION_RE.sub(replace, message)
    return render_pattern(rendered, variables)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path, help="JSON catalog with messages.")
    parser.add_argument("--compiled-pack", required=True, type=Path, help="Compiled term pack JSON.")
    parser.add_argument("--message", required=True, help="Message id to render.")
    parser.add_argument("--term", action="append", default=[], type=parse_assignment, help="Argument-to-term binding, e.g. item=item.iron_sword.")
    parser.add_argument("--arg", action="append", default=[], type=parse_assignment, help="Runtime variable, e.g. count=2.")
    args = parser.parse_args()

    catalog = load_json(args.catalog)
    pack = load_json(args.compiled_pack)
    message = catalog.get("messages", {}).get(args.message)
    if message is None:
        raise SystemExit(f"Unknown message id: {args.message}")

    output = render_message(message, build_runtime(pack), dict(args.term), dict(args.arg))
    print(output)


if __name__ == "__main__":
    main()
