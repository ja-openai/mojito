#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


NUMBERS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-numbers-full/main/{locale}/numbers.json"
)
CURRENCIES_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-numbers-full/main/{locale}/currencies.json"
)
CURRENCY_DATA_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/currencyData.json"
)
DEFAULT_LOCALES = ("en-US", "fr-FR", "de-DE", "ja-JP", "ar-EG")
DEFAULT_CURRENCIES = ("USD", "EUR", "JPY", "GBP")
SYMBOL_KEYS = ("decimal", "group", "plusSign", "minusSign", "approximatelySign")
FRACTION_KEYS = ("digits", "rounding", "cashDigits", "cashRounding")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate experimental CLDR number/currency data."
    )
    parser.add_argument(
        "--locales",
        default=",".join(DEFAULT_LOCALES),
        help="Comma-separated BCP47-style locales.",
    )
    parser.add_argument(
        "--currencies",
        default=",".join(DEFAULT_CURRENCIES),
        help="Comma-separated ISO 4217 currency codes.",
    )
    parser.add_argument(
        "--out",
        default="generated/experimental-number",
        help="Output directory.",
    )
    parser.add_argument("--cldr-ref", default="main", help="unicode-org/cldr-json git ref.")
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove the output directory before writing generated files.",
    )
    parser.add_argument("--quiet", action="store_true", help="Do not print a summary.")
    args = parser.parse_args(argv)

    locales = parse_locale_list(args.locales)
    currencies = parse_currency_list(args.currencies)
    data = build_generated_data(locales, currencies, args.cldr_ref)

    out = Path(args.out)
    if args.clean:
        shutil.rmtree(out, ignore_errors=True)
    path = out / "number_data.json"
    write_json(path, data)

    if not args.quiet:
        print(
            "Generated experimental CLDR number data "
            f"locales={len(data['locales'])} "
            f"currencies={len(data['currencyFractions']) - 1} "
            f"bytes={{'json': {path.stat().st_size}}}"
        )
    return 0


def parse_locale_list(value: str) -> list[str]:
    locales: list[str] = []
    for raw_locale in value.split(","):
        locale = canonical_locale_key(raw_locale)
        if locale and locale not in locales:
            locales.append(locale)
    if not locales:
        raise SystemExit("At least one locale is required.")
    return locales


def parse_currency_list(value: str) -> list[str]:
    currencies: list[str] = []
    for raw_currency in value.split(","):
        currency = raw_currency.strip().upper()
        if not currency:
            continue
        if len(currency) != 3 or not currency.isalpha():
            raise SystemExit(f"Invalid currency code: {raw_currency!r}")
        if currency not in currencies:
            currencies.append(currency)
    if not currencies:
        raise SystemExit("At least one currency is required.")
    return currencies


def build_generated_data(
    locales: list[str], currencies: list[str], cldr_ref: str
) -> dict[str, Any]:
    currency_data = fetch_json(CURRENCY_DATA_URL.format(ref=cldr_ref))
    fractions = currency_data["supplemental"]["currencyData"]["fractions"]

    generated: dict[str, Any] = {
        "metadata": {
            "cldrRef": cldr_ref,
            "dropWithoutMigration": True,
            "experimental": True,
            "generator": "mf2/cldr/generator/generate_number_data.py",
            "localesRequested": locales,
            "currenciesRequested": currencies,
            "source": (
                "unicode-org/cldr-json cldr-numbers-full main numbers/currencies "
                "+ cldr-core supplemental currencyData"
            ),
        },
        "locales": {},
        "currencyFractions": build_currency_fractions(fractions, currencies),
    }

    for locale in locales:
        generated["locales"][locale] = build_locale_data(locale, currencies, cldr_ref)
    return generated


def build_currency_fractions(
    fractions: dict[str, dict[str, str]], currencies: list[str]
) -> dict[str, dict[str, int | str]]:
    default_fraction = currency_fraction(fractions["DEFAULT"], "DEFAULT")
    result: dict[str, dict[str, int | str]] = {"DEFAULT": default_fraction}
    for currency in currencies:
        source = currency if currency in fractions else "DEFAULT"
        result[currency] = currency_fraction(fractions.get(currency, fractions["DEFAULT"]), source)
    return result


def currency_fraction(raw: dict[str, str], source: str) -> dict[str, int | str]:
    result: dict[str, int | str] = {"source": source}
    for key in FRACTION_KEYS:
        raw_key = f"_{key}"
        if raw_key in raw:
            result[key] = int(raw[raw_key])
    return result


def build_locale_data(locale: str, currencies: list[str], cldr_ref: str) -> dict[str, Any]:
    numbers_source, numbers_data = fetch_main_data(NUMBERS_URL, cldr_ref, locale)
    currencies_source, currencies_data = fetch_main_data(CURRENCIES_URL, cldr_ref, locale)

    numbers = numbers_data["main"][numbers_source]["numbers"]
    currency_entries = currencies_data["main"][currencies_source]["numbers"].get(
        "currencies", {}
    )
    numbering_system = numbers.get("defaultNumberingSystem", "latn")
    symbols = select_number_system_data(numbers, "symbols", numbering_system)
    decimal_formats = select_number_system_data(numbers, "decimalFormats", numbering_system)
    currency_formats = select_number_system_data(numbers, "currencyFormats", numbering_system)

    return {
        "requestedLocale": locale,
        "numbersSourceLocale": numbers_source,
        "currenciesSourceLocale": currencies_source,
        "numberingSystem": numbering_system,
        "symbols": {key: symbols[key] for key in SYMBOL_KEYS if key in symbols},
        "decimalPattern": decimal_formats.get("standard"),
        "currencyPattern": currency_formats.get("standard"),
        "currencySpacing": currency_formats.get("currencySpacing", {}),
        "currencies": build_currency_symbols(currency_entries, currencies),
    }


def build_currency_symbols(
    currency_entries: dict[str, dict[str, str]], currencies: list[str]
) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for currency in currencies:
        entry = currency_entries.get(currency, {})
        currency_data: dict[str, str] = {
            "symbol": entry.get("symbol", currency),
        }
        if "symbol-alt-narrow" in entry:
            currency_data["narrowSymbol"] = entry["symbol-alt-narrow"]
        if "displayName" in entry:
            currency_data["displayName"] = entry["displayName"]
        result[currency] = currency_data
    return result


def select_number_system_data(
    numbers: dict[str, Any], prefix: str, numbering_system: str
) -> dict[str, Any]:
    selected = numbers.get(f"{prefix}-numberSystem-{numbering_system}")
    if selected is not None:
        return selected
    latn = numbers.get(f"{prefix}-numberSystem-latn")
    if latn is not None:
        return latn
    raise SystemExit(f"CLDR numbers data is missing {prefix} for {numbering_system}.")


def fetch_main_data(
    url_template: str, cldr_ref: str, locale: str
) -> tuple[str, dict[str, Any]]:
    for candidate in locale_source_candidates(locale):
        try:
            return candidate, fetch_json(url_template.format(ref=cldr_ref, locale=candidate))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    candidates = ", ".join(locale_source_candidates(locale))
    raise SystemExit(f"Could not find CLDR data for {locale}; tried {candidates}.")


def fetch_json(url: str) -> dict[str, Any]:
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read())


def locale_source_candidates(locale: str) -> list[str]:
    parts = canonical_locale_key(locale).split("-")
    candidates = ["-".join(parts[:length]) for length in range(len(parts), 0, -1)]
    return list(dict.fromkeys(candidates))


def canonical_locale_key(locale: str) -> str:
    parts: list[str] = []
    for index, part in enumerate(locale.strip().replace("_", "-").split("-")):
        if not part:
            continue
        if len(part) == 1:
            break
        parts.append(canonical_subtag(index, part))
    return "-".join(parts)


def canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
