#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_SYMBOLS = ("decimal", "group", "minusSign", "plusSign")
REQUIRED_LOCALE_FIELDS = (
    "requestedLocale",
    "numbersSourceLocale",
    "currenciesSourceLocale",
    "numberingSystem",
    "numberingSystemDigits",
    "minimumGroupingDigits",
    "symbols",
    "decimalPattern",
    "percentPattern",
    "currencyPattern",
    "currencies",
)
REQUIRED_FRACTION_FIELDS = ("digits", "rounding", "source")


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        raise SystemExit("Usage: validate_number_data.py path/to/number_data.json")
    path = Path(args[0])
    data = json.loads(path.read_text(encoding="utf-8"))
    validate(data)
    print(
        "Validated experimental CLDR number data "
        f"locales={len(data['locales'])} "
        f"currencies={len(data['currencyFractions']) - 1}"
    )
    return 0


def validate(data: dict[str, Any]) -> None:
    metadata = require_dict(data, "metadata")
    require_true(metadata, "experimental")
    require_true(metadata, "dropWithoutMigration")

    locales = require_dict(data, "locales")
    if not locales:
        raise ValueError("locales must not be empty")

    fractions = require_dict(data, "currencyFractions")
    default_fraction = require_dict(fractions, "DEFAULT")
    validate_fraction("DEFAULT", default_fraction)
    currencies = [currency for currency in fractions if currency != "DEFAULT"]
    if not currencies:
        raise ValueError("currencyFractions must include at least one currency")
    for currency in currencies:
        validate_currency_code(currency)
        validate_fraction(currency, require_dict(fractions, currency))

    for locale, locale_data in locales.items():
        validate_locale_key(locale)
        validate_locale_data(locale, locale_data, currencies)


def validate_locale_data(
    locale: str, locale_data: Any, currencies: list[str]
) -> None:
    if not isinstance(locale_data, dict):
        raise ValueError(f"{locale} must be an object")
    for field in REQUIRED_LOCALE_FIELDS:
        if field not in locale_data:
            raise ValueError(f"{locale} missing {field}")
    if locale_data["requestedLocale"] != locale:
        raise ValueError(f"{locale} requestedLocale mismatch")

    symbols = require_dict(locale_data, "symbols")
    for symbol in REQUIRED_SYMBOLS:
        if not isinstance(symbols.get(symbol), str) or not symbols[symbol]:
            raise ValueError(f"{locale} missing symbol {symbol}")
    if "percentSign" not in symbols or not isinstance(symbols["percentSign"], str):
        raise ValueError(f"{locale} missing percentSign")

    for field in (
        "decimalPattern",
        "percentPattern",
        "currencyPattern",
        "numberingSystem",
        "numbersSourceLocale",
        "currenciesSourceLocale",
    ):
        if not isinstance(locale_data[field], str) or not locale_data[field]:
            raise ValueError(f"{locale} has invalid {field}")
    if not isinstance(locale_data["minimumGroupingDigits"], int):
        raise ValueError(f"{locale} has invalid minimumGroupingDigits")
    digits = locale_data["numberingSystemDigits"]
    if digits is not None and (not isinstance(digits, str) or len(digits) != 10):
        raise ValueError(f"{locale} has invalid numberingSystemDigits")

    currency_symbols = require_dict(locale_data, "currencies")
    for currency in currencies:
        currency_data = require_dict(currency_symbols, currency)
        if not isinstance(currency_data.get("symbol"), str) or not currency_data["symbol"]:
            raise ValueError(f"{locale} {currency} missing symbol")


def validate_fraction(currency: str, fraction: dict[str, Any]) -> None:
    for field in REQUIRED_FRACTION_FIELDS:
        if field not in fraction:
            raise ValueError(f"{currency} fraction missing {field}")
    for field in ("digits", "rounding"):
        value = fraction[field]
        if not isinstance(value, int) or value < 0:
            raise ValueError(f"{currency} fraction has invalid {field}")
    source = fraction["source"]
    if not isinstance(source, str) or not source:
        raise ValueError(f"{currency} fraction has invalid source")


def require_dict(data: dict[str, Any], key: str) -> dict[str, Any]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"{key} must be an object")
    return value


def require_true(data: dict[str, Any], key: str) -> None:
    if data.get(key) is not True:
        raise ValueError(f"{key} must be true")


def validate_locale_key(locale: str) -> None:
    parts = locale.split("-")
    if not parts or not parts[0].islower():
        raise ValueError(f"Invalid locale key: {locale}")
    for part in parts:
        if not part.isalnum():
            raise ValueError(f"Invalid locale key: {locale}")


def validate_currency_code(currency: str) -> None:
    if len(currency) != 3 or not currency.isupper() or not currency.isalpha():
        raise ValueError(f"Invalid currency code: {currency}")


if __name__ == "__main__":
    raise SystemExit(main())
