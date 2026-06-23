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
NUMBERING_SYSTEMS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/numberingSystems.json"
)
DEFAULT_LOCALES = ("en-US", "fr-FR", "de-DE", "ja-JP", "ar-EG")
DEFAULT_CURRENCIES = ("USD", "EUR", "JPY", "GBP")
SYMBOL_KEYS = (
    "decimal",
    "group",
    "plusSign",
    "minusSign",
    "percentSign",
    "perMille",
    "approximatelySign",
)
FRACTION_KEYS = ("digits", "rounding", "cashDigits", "cashRounding")
DEFAULT_CURRENCY_SPACING = "\u00a0"


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
        "--javascript-runtime-out",
        type=Path,
        help="Optional generated JavaScript module path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--python-runtime-out",
        type=Path,
        help="Optional generated Python module path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--rust-runtime-out",
        type=Path,
        help="Optional generated Rust source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--java-package",
        default="com.box.l10n.mojito.mf2",
        help="Java package for generated Java number data.",
    )
    parser.add_argument(
        "--java-runtime-out",
        type=Path,
        help="Optional generated Java source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--kotlin-package",
        default="com.box.l10n.mojito.mf2",
        help="Kotlin package for generated Kotlin number data.",
    )
    parser.add_argument(
        "--kotlin-runtime-out",
        type=Path,
        help="Optional generated Kotlin source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--swift-runtime-out",
        type=Path,
        help="Optional generated Swift source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--go-runtime-out",
        type=Path,
        help="Optional generated Go source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--php-runtime-out",
        type=Path,
        help="Optional generated PHP source path to vendor into a runtime package.",
    )
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
    javascript_path = out / "javascript" / "number_data.js"
    javascript_packed_path = out / "javascript" / "number_data_packed.js"
    javascript_packed_locale_dir = out / "javascript" / "packed-locales"
    python_path = out / "python" / "mojito_mf2" / "_cldr_number_data.py"
    rust_path = out / "rust" / "cldr_number_data.rs"
    java_path = out / "java" / java_package_path(args.java_package) / "CldrNumberData.java"
    kotlin_path = out / "kotlin" / java_package_path(args.kotlin_package) / "CldrNumberData.kt"
    swift_path = out / "swift" / "CldrNumberData.swift"
    go_path = out / "go" / "cldr_number_data.go"
    php_path = out / "php" / "CldrNumberData.php"
    write_json(path, data)
    write_javascript(javascript_path, data)
    write_javascript_packed(javascript_packed_path, data)
    write_javascript_packed_locale_chunks(javascript_packed_locale_dir, data)
    write_python(python_path, data)
    write_rust(rust_path, data)
    write_java(java_path, data, args.java_package)
    write_kotlin(kotlin_path, data, args.kotlin_package)
    write_swift(swift_path, data)
    write_go(go_path, data)
    write_php(php_path, data)
    if args.javascript_runtime_out is not None:
        write_javascript(args.javascript_runtime_out, data)
    if args.python_runtime_out is not None:
        write_python(args.python_runtime_out, data)
    if args.rust_runtime_out is not None:
        write_rust(args.rust_runtime_out, data)
    if args.java_runtime_out is not None:
        write_java(args.java_runtime_out, data, args.java_package)
    if args.kotlin_runtime_out is not None:
        write_kotlin(args.kotlin_runtime_out, data, args.kotlin_package)
    if args.swift_runtime_out is not None:
        write_swift(args.swift_runtime_out, data)
    if args.go_runtime_out is not None:
        write_go(args.go_runtime_out, data)
    if args.php_runtime_out is not None:
        write_php(args.php_runtime_out, data)

    if not args.quiet:
        print(
            "Generated experimental CLDR number data "
            f"locales={len(data['locales'])} "
            f"currencies={len(data['currencyFractions']) - 1} "
            f"bytes={{'json': {path.stat().st_size}, "
            f"'javascript': {javascript_path.stat().st_size}, "
            f"'javascriptPacked': {javascript_packed_path.stat().st_size}, "
            f"'python': {python_path.stat().st_size}, "
            f"'rust': {rust_path.stat().st_size}, "
            f"'java': {java_path.stat().st_size}, "
            f"'kotlin': {kotlin_path.stat().st_size}, "
            f"'swift': {swift_path.stat().st_size}, "
            f"'go': {go_path.stat().st_size}, "
            f"'php': {php_path.stat().st_size}}}"
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
    numbering_systems = fetch_json(NUMBERING_SYSTEMS_URL.format(ref=cldr_ref))[
        "supplemental"
    ]["numberingSystems"]

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
        generated["locales"][locale] = build_locale_data(
            locale,
            currencies,
            cldr_ref,
            numbering_systems,
        )
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


def build_locale_data(
    locale: str,
    currencies: list[str],
    cldr_ref: str,
    numbering_systems: dict[str, dict[str, str]],
) -> dict[str, Any]:
    numbers_source, numbers_data = fetch_main_data(NUMBERS_URL, cldr_ref, locale)
    currencies_source, currencies_data = fetch_main_data(CURRENCIES_URL, cldr_ref, locale)

    numbers = numbers_data["main"][numbers_source]["numbers"]
    currency_entries = currencies_data["main"][currencies_source]["numbers"].get(
        "currencies", {}
    )
    numbering_system = numbers.get("defaultNumberingSystem", "latn")
    symbols = select_number_system_data(numbers, "symbols", numbering_system)
    decimal_formats = select_number_system_data(numbers, "decimalFormats", numbering_system)
    percent_formats = select_number_system_data(numbers, "percentFormats", numbering_system)
    currency_formats = select_number_system_data(numbers, "currencyFormats", numbering_system)

    locale_data: dict[str, Any] = {
        "requestedLocale": locale,
        "numbersSourceLocale": numbers_source,
        "currenciesSourceLocale": currencies_source,
        "numberingSystem": numbering_system,
        "numberingSystemDigits": numbering_system_digits(numbering_systems, numbering_system),
        "minimumGroupingDigits": int(numbers.get("minimumGroupingDigits", "1")),
        "symbols": {key: symbols[key] for key in SYMBOL_KEYS if key in symbols},
        "decimalPattern": decimal_formats.get("standard"),
        "percentPattern": percent_formats.get("standard"),
        "currencyPattern": currency_formats.get("standard"),
        "currencySpacing": currency_formats.get("currencySpacing", {}),
        "currencies": build_currency_symbols(currency_entries, currencies),
    }
    return locale_data


def numbering_system_digits(
    numbering_systems: dict[str, dict[str, str]], numbering_system: str
) -> str | None:
    data = numbering_systems.get(numbering_system, {})
    if data.get("_type") != "numeric":
        return None
    return data.get("_digits")


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


def write_javascript(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
    source = (
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; "
        "do not edit by hand.\n"
        f"export const NUMBER_DATA = {payload};\n"
    )
    path.write_text(source, encoding="utf-8")


def write_javascript_packed(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(
        pack_number_resource(value),
        ensure_ascii=True,
        separators=(",", ":"),
    )
    source = (
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; "
        "do not edit by hand.\n"
        f"export const NUMBER_DATA_PACKED = {payload};\n"
    )
    path.write_text(source, encoding="utf-8")


def write_javascript_packed_locale_chunks(path: Path, value: dict[str, Any]) -> None:
    path.mkdir(parents=True, exist_ok=True)
    for locale in sorted(value["locales"]):
        locale_path = path / f"{locale}.js"
        locale_data = subset_number_resource(value, [locale])
        payload = json.dumps(
            pack_number_resource(locale_data),
            ensure_ascii=True,
            separators=(",", ":"),
        )
        source = (
            "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; "
            "do not edit by hand.\n"
            f"export const NUMBER_DATA_PACKED_LOCALE = {payload};\n"
        )
        locale_path.write_text(source, encoding="utf-8")


def subset_number_resource(value: dict[str, Any], locales: list[str]) -> dict[str, Any]:
    source_locales = value["locales"]
    missing = [locale for locale in locales if locale not in source_locales]
    if missing:
        raise ValueError(f"Unknown number locale(s): {', '.join(missing)}")
    metadata = dict(value.get("metadata", {}))
    metadata["localesRequested"] = list(locales)
    return {
        "metadata": metadata,
        "locales": {locale: source_locales[locale] for locale in locales},
        "currencyFractions": value["currencyFractions"],
    }


def pack_number_resource(value: dict[str, Any]) -> dict[str, Any]:
    strings: list[str] = []
    string_ids: dict[str, int] = {}

    def sid(raw: str | None) -> int:
        if raw is None:
            return -1
        existing = string_ids.get(raw)
        if existing is not None:
            return existing
        string_ids[raw] = len(strings)
        strings.append(raw)
        return len(strings) - 1

    packed_fractions = [
        [
            sid(currency),
            int(fraction.get("digits", 2)),
            int(fraction.get("rounding", 0)),
            sid(fraction.get("source")),
        ]
        for currency, fraction in sorted(value["currencyFractions"].items())
    ]
    packed_locales = []
    for locale, locale_data in sorted(value["locales"].items()):
        symbols = [
            [sid(key), sid(symbol)]
            for key, symbol in sorted(locale_data["symbols"].items())
        ]
        currencies = [
            [
                sid(currency),
                sid(data.get("symbol")),
                sid(data.get("narrowSymbol")),
                sid(data.get("displayName")),
            ]
            for currency, data in sorted(locale_data["currencies"].items())
        ]
        packed_locales.append(
            [
                sid(locale),
                sid(locale_data["requestedLocale"]),
                sid(locale_data["numbersSourceLocale"]),
                sid(locale_data["currenciesSourceLocale"]),
                sid(locale_data["numberingSystem"]),
                sid(locale_data.get("numberingSystemDigits")),
                int(locale_data["minimumGroupingDigits"]),
                symbols,
                sid(locale_data["decimalPattern"]),
                sid(locale_data["percentPattern"]),
                sid(locale_data["currencyPattern"]),
                currencies,
            ]
        )
    return {"version": 1, "strings": strings, "currencyFractions": packed_fractions, "locales": packed_locales}


def write_python(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = (
        "# Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; "
        "do not edit by hand.\n"
        "from __future__ import annotations\n\n"
        "from typing import Any\n\n"
        f"NUMBER_DATA: dict[str, Any] = {python_literal(value)}\n"
    )
    path.write_text(source, encoding="utf-8")


def write_java(path: Path, value: dict[str, Any], java_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package " + java_package + ";",
        "",
        "import static java.util.Map.entry;",
        "",
        "import java.util.Map;",
        "",
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.",
        "final class CldrNumberData {",
        "    private CldrNumberData() {}",
        "",
        "    record CurrencyFraction(int digits, int rounding, String source) {}",
        "",
        "    record CurrencyData(String symbol, String narrowSymbol, String displayName) {}",
        "",
        "    record CurrencySpacing(String beforeCurrency, String afterCurrency) {}",
        "",
        "    record LocaleData(",
        "            String requestedLocale,",
        "            String numbersSourceLocale,",
        "            String currenciesSourceLocale,",
        "            String numberingSystem,",
        "            String numberingSystemDigits,",
        "            int minimumGroupingDigits,",
        "            Map<String, String> symbols,",
        "            String decimalPattern,",
        "            String percentPattern,",
        "            String currencyPattern,",
        "            CurrencySpacing currencySpacing,",
        "            Map<String, CurrencyData> currencies) {}",
        "",
        "    static final Map<String, CurrencyFraction> CURRENCY_FRACTIONS = Map.ofEntries(",
    ]
    fraction_items = list(value["currencyFractions"].items())
    for index, (currency, fraction) in enumerate(fraction_items):
        suffix = "," if index < len(fraction_items) - 1 else ""
        lines.append(
            "            entry("
            + java_literal(currency)
            + ", new CurrencyFraction("
            + str(fraction.get("digits", 2))
            + ", "
            + str(fraction.get("rounding", 0))
            + ", "
            + java_literal(fraction.get("source"))
            + "))"
            + suffix
        )
    lines.extend(
        [
            "    );",
            "",
            "    static final Map<String, LocaleData> LOCALES = Map.ofEntries(",
        ]
    )
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append(
            "            entry("
            + java_literal(locale)
            + ", new LocaleData("
            + java_literal(locale_data["requestedLocale"])
            + ", "
            + java_literal(locale_data["numbersSourceLocale"])
            + ", "
            + java_literal(locale_data["currenciesSourceLocale"])
            + ", "
            + java_literal(locale_data["numberingSystem"])
            + ", "
            + java_literal(locale_data.get("numberingSystemDigits"))
            + ", "
            + str(locale_data["minimumGroupingDigits"])
            + ", "
            + java_string_map(locale_data["symbols"])
            + ", "
            + java_literal(locale_data["decimalPattern"])
            + ", "
            + java_literal(locale_data["percentPattern"])
            + ", "
            + java_literal(locale_data["currencyPattern"])
            + ", "
            + java_currency_spacing(locale_data.get("currencySpacing", {}))
            + ", "
            + java_currency_map(locale_data["currencies"])
            + "))"
            + suffix
        )
    lines.extend(["    );", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_rust(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.",
        "#[derive(Debug, Clone, Copy)]",
        "pub(crate) struct CldrNumberCurrencyFraction {",
        "    pub(crate) digits: usize,",
        "    pub(crate) rounding: usize,",
        "    pub(crate) source: &'static str,",
        "}",
        "",
        "#[derive(Debug, Clone, Copy)]",
        "pub(crate) struct CldrNumberCurrencyData {",
        "    pub(crate) symbol: &'static str,",
        "    pub(crate) narrow_symbol: Option<&'static str>,",
        "    pub(crate) display_name: Option<&'static str>,",
        "}",
        "",
        "#[derive(Debug, Clone, Copy)]",
        "pub(crate) struct CldrNumberCurrencySpacing {",
        "    pub(crate) before_currency: &'static str,",
        "    pub(crate) after_currency: &'static str,",
        "}",
        "",
        "#[derive(Debug, Clone, Copy)]",
        "pub(crate) struct CldrNumberLocaleData {",
        "    pub(crate) requested_locale: &'static str,",
        "    pub(crate) numbers_source_locale: &'static str,",
        "    pub(crate) currencies_source_locale: &'static str,",
        "    pub(crate) numbering_system: &'static str,",
        "    pub(crate) numbering_system_digits: Option<&'static str>,",
        "    pub(crate) minimum_grouping_digits: usize,",
        "    pub(crate) symbols: &'static [(&'static str, &'static str)],",
        "    pub(crate) decimal_pattern: &'static str,",
        "    pub(crate) percent_pattern: &'static str,",
        "    pub(crate) currency_pattern: &'static str,",
        "    pub(crate) currency_spacing: CldrNumberCurrencySpacing,",
        "    pub(crate) currencies: &'static [(&'static str, CldrNumberCurrencyData)],",
        "}",
        "",
        "pub(crate) const CLDR_NUMBER_DEFAULT_CURRENCY_SPACING: CldrNumberCurrencySpacing = CldrNumberCurrencySpacing { before_currency: \"\\u{a0}\", after_currency: \"\\u{a0}\" };",
        "",
        "pub(crate) static CLDR_NUMBER_CURRENCY_FRACTIONS: &[(&str, CldrNumberCurrencyFraction)] = &[",
    ]
    for currency, fraction in value["currencyFractions"].items():
        lines.append(
            "    ("
            + rust_literal(currency)
            + ", CldrNumberCurrencyFraction { digits: "
            + str(fraction.get("digits", 2))
            + ", rounding: "
            + str(fraction.get("rounding", 0))
            + ", source: "
            + rust_literal(fraction.get("source"))
            + " }),"
        )
    lines.extend(["];", "", "pub(crate) static CLDR_NUMBER_LOCALES: &[(&str, CldrNumberLocaleData)] = &["])
    for locale, locale_data in value["locales"].items():
        lines.append("    (" + rust_literal(locale) + ", CldrNumberLocaleData {")
        lines.extend(
            [
                "        requested_locale: " + rust_literal(locale_data["requestedLocale"]) + ",",
                "        numbers_source_locale: " + rust_literal(locale_data["numbersSourceLocale"]) + ",",
                "        currencies_source_locale: " + rust_literal(locale_data["currenciesSourceLocale"]) + ",",
                "        numbering_system: " + rust_literal(locale_data["numberingSystem"]) + ",",
                "        numbering_system_digits: " + rust_option_literal(locale_data.get("numberingSystemDigits")) + ",",
                "        minimum_grouping_digits: " + str(locale_data["minimumGroupingDigits"]) + ",",
                "        symbols: " + rust_string_map(locale_data["symbols"]) + ",",
                "        decimal_pattern: " + rust_literal(locale_data["decimalPattern"]) + ",",
                "        percent_pattern: " + rust_literal(locale_data["percentPattern"]) + ",",
                "        currency_pattern: " + rust_literal(locale_data["currencyPattern"]) + ",",
                "        currency_spacing: " + rust_currency_spacing(locale_data.get("currencySpacing", {})) + ",",
                "        currencies: " + rust_currency_map(locale_data["currencies"]) + ",",
                "    }),",
            ]
        )
    lines.extend(["];"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def rust_string_map(values: dict[str, str]) -> str:
    if not values:
        return "&[]"
    entries = [
        "(" + rust_literal(key) + ", " + rust_literal(value) + ")"
        for key, value in values.items()
    ]
    return "&[" + ", ".join(entries) + "]"


def rust_currency_map(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "&[]"
    entries = []
    for currency, data in values.items():
        entries.append(
            "("
            + rust_literal(currency)
            + ", CldrNumberCurrencyData { symbol: "
            + rust_literal(data.get("symbol", currency))
            + ", narrow_symbol: "
            + rust_option_literal(data.get("narrowSymbol"))
            + ", display_name: "
            + rust_option_literal(data.get("displayName"))
            + " })"
        )
    return "&[" + ", ".join(entries) + "]"


def rust_currency_spacing(value: dict[str, Any]) -> str:
    before = currency_spacing_insert(value, "beforeCurrency")
    after = currency_spacing_insert(value, "afterCurrency")
    if before == DEFAULT_CURRENCY_SPACING and after == DEFAULT_CURRENCY_SPACING:
        return "CLDR_NUMBER_DEFAULT_CURRENCY_SPACING"
    return (
        "CldrNumberCurrencySpacing { before_currency: "
        + rust_literal(before)
        + ", after_currency: "
        + rust_literal(after)
        + " }"
    )


def java_string_map(values: dict[str, str]) -> str:
    if not values:
        return "Map.of()"
    entries = [
        "entry(" + java_literal(key) + ", " + java_literal(value) + ")"
        for key, value in values.items()
    ]
    return "Map.ofEntries(" + ", ".join(entries) + ")"


def java_currency_map(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "Map.of()"
    entries = []
    for currency, data in values.items():
        entries.append(
            "entry("
            + java_literal(currency)
            + ", new CurrencyData("
            + java_literal(data.get("symbol"))
            + ", "
            + java_literal(data.get("narrowSymbol"))
            + ", "
            + java_literal(data.get("displayName"))
            + "))"
        )
    return "Map.ofEntries(" + ", ".join(entries) + ")"


def java_currency_spacing(value: dict[str, Any]) -> str:
    return (
        "new CurrencySpacing("
        + java_literal(currency_spacing_insert(value, "beforeCurrency"))
        + ", "
        + java_literal(currency_spacing_insert(value, "afterCurrency"))
        + ")"
    )


def currency_spacing_insert(value: dict[str, Any], key: str) -> str:
    rule = value.get(key, {})
    if not isinstance(rule, dict):
        return DEFAULT_CURRENCY_SPACING
    insert = rule.get("insertBetween")
    return str(insert) if insert else DEFAULT_CURRENCY_SPACING


def java_literal(value: str | None) -> str:
    if value is None:
        return "null"
    return json.dumps(value, ensure_ascii=True)


def write_kotlin(path: Path, value: dict[str, Any], kotlin_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package " + kotlin_package,
        "",
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.",
        "internal object CldrNumberData {",
        "    data class CurrencyFraction(val digits: Int, val rounding: Int, val source: String)",
        "",
        "    data class CurrencyData(val symbol: String?, val narrowSymbol: String?, val displayName: String?)",
        "",
        "    data class CurrencySpacing(val beforeCurrency: String, val afterCurrency: String)",
        "",
        "    data class LocaleData(",
        "        val requestedLocale: String,",
        "        val numbersSourceLocale: String,",
        "        val currenciesSourceLocale: String,",
        "        val numberingSystem: String,",
        "        val numberingSystemDigits: String?,",
        "        val minimumGroupingDigits: Int,",
        "        val symbols: Map<String, String>,",
        "        val decimalPattern: String,",
        "        val percentPattern: String,",
        "        val currencyPattern: String,",
        "        val currencySpacing: CurrencySpacing,",
        "        val currencies: Map<String, CurrencyData>,",
        "    )",
        "",
        "    val currencyFractions: Map<String, CurrencyFraction> = mapOf(",
    ]
    fraction_items = list(value["currencyFractions"].items())
    for index, (currency, fraction) in enumerate(fraction_items):
        suffix = "," if index < len(fraction_items) - 1 else ""
        lines.append(
            "        "
            + kotlin_literal(currency)
            + " to CurrencyFraction("
            + str(fraction.get("digits", 2))
            + ", "
            + str(fraction.get("rounding", 0))
            + ", "
            + kotlin_literal(fraction.get("source"))
            + ")"
            + suffix
        )
    lines.extend(
        [
            "    )",
            "",
            "    val locales: Map<String, LocaleData> = mapOf(",
        ]
    )
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append(
            "        "
            + kotlin_literal(locale)
            + " to LocaleData(",
        )
        lines.extend(
            [
                "            requestedLocale = " + kotlin_literal(locale_data["requestedLocale"]) + ",",
                "            numbersSourceLocale = " + kotlin_literal(locale_data["numbersSourceLocale"]) + ",",
                "            currenciesSourceLocale = " + kotlin_literal(locale_data["currenciesSourceLocale"]) + ",",
                "            numberingSystem = " + kotlin_literal(locale_data["numberingSystem"]) + ",",
                "            numberingSystemDigits = " + kotlin_literal(locale_data.get("numberingSystemDigits")) + ",",
                "            minimumGroupingDigits = " + str(locale_data["minimumGroupingDigits"]) + ",",
                "            symbols = " + kotlin_string_map(locale_data["symbols"]) + ",",
                "            decimalPattern = " + kotlin_literal(locale_data["decimalPattern"]) + ",",
                "            percentPattern = " + kotlin_literal(locale_data["percentPattern"]) + ",",
                "            currencyPattern = " + kotlin_literal(locale_data["currencyPattern"]) + ",",
                "            currencySpacing = " + kotlin_currency_spacing(locale_data.get("currencySpacing", {})) + ",",
                "            currencies = " + kotlin_currency_map(locale_data["currencies"]) + ",",
                "        )" + suffix,
            ]
        )
    lines.extend(["    )", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def kotlin_string_map(values: dict[str, str]) -> str:
    if not values:
        return "emptyMap()"
    entries = [
        kotlin_literal(key) + " to " + kotlin_literal(value)
        for key, value in values.items()
    ]
    return "mapOf(" + ", ".join(entries) + ")"


def kotlin_currency_map(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "emptyMap()"
    entries = []
    for currency, data in values.items():
        entries.append(
            kotlin_literal(currency)
            + " to CurrencyData("
            + kotlin_literal(data.get("symbol"))
            + ", "
            + kotlin_literal(data.get("narrowSymbol"))
            + ", "
            + kotlin_literal(data.get("displayName"))
            + ")"
        )
    return "mapOf(" + ", ".join(entries) + ")"


def kotlin_currency_spacing(value: dict[str, Any]) -> str:
    return (
        "CurrencySpacing("
        + kotlin_literal(currency_spacing_insert(value, "beforeCurrency"))
        + ", "
        + kotlin_literal(currency_spacing_insert(value, "afterCurrency"))
        + ")"
    )


def kotlin_literal(value: str | None) -> str:
    if value is None:
        return "null"
    encoded = json.dumps(value, ensure_ascii=True)
    return encoded[:-1].replace("$", "\\$") + encoded[-1]


def write_swift(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.",
        "import Foundation",
        "",
        "enum CldrNumberData {",
        "    struct CurrencyFraction {",
        "        let digits: Int",
        "        let rounding: Int",
        "        let source: String",
        "    }",
        "",
        "    struct CurrencyData {",
        "        let symbol: String?",
        "        let narrowSymbol: String?",
        "        let displayName: String?",
        "    }",
        "",
        "    struct CurrencySpacing {",
        "        let beforeCurrency: String",
        "        let afterCurrency: String",
        "    }",
        "",
        "    struct LocaleData {",
        "        let requestedLocale: String",
        "        let numbersSourceLocale: String",
        "        let currenciesSourceLocale: String",
        "        let numberingSystem: String",
        "        let numberingSystemDigits: String?",
        "        let minimumGroupingDigits: Int",
        "        let symbols: [String: String]",
        "        let decimalPattern: String",
        "        let percentPattern: String",
        "        let currencyPattern: String",
        "        let currencySpacing: CurrencySpacing",
        "        let currencies: [String: CurrencyData]",
        "    }",
        "",
        "    static let currencyFractions: [String: CurrencyFraction] = [",
    ]
    fraction_items = list(value["currencyFractions"].items())
    for index, (currency, fraction) in enumerate(fraction_items):
        suffix = "," if index < len(fraction_items) - 1 else ""
        lines.append(
            "        "
            + swift_literal(currency)
            + ": CurrencyFraction(digits: "
            + str(fraction.get("digits", 2))
            + ", rounding: "
            + str(fraction.get("rounding", 0))
            + ", source: "
            + swift_literal(fraction.get("source"))
            + ")"
            + suffix
        )
    lines.extend(["    ]", "", "    static let locales: [String: LocaleData] = ["])
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append("        " + swift_literal(locale) + ": LocaleData(")
        lines.extend(
            [
                "            requestedLocale: " + swift_literal(locale_data["requestedLocale"]) + ",",
                "            numbersSourceLocale: " + swift_literal(locale_data["numbersSourceLocale"]) + ",",
                "            currenciesSourceLocale: " + swift_literal(locale_data["currenciesSourceLocale"]) + ",",
                "            numberingSystem: " + swift_literal(locale_data["numberingSystem"]) + ",",
                "            numberingSystemDigits: " + swift_literal(locale_data.get("numberingSystemDigits")) + ",",
                "            minimumGroupingDigits: " + str(locale_data["minimumGroupingDigits"]) + ",",
                "            symbols: " + swift_string_map(locale_data["symbols"]) + ",",
                "            decimalPattern: " + swift_literal(locale_data["decimalPattern"]) + ",",
                "            percentPattern: " + swift_literal(locale_data["percentPattern"]) + ",",
                "            currencyPattern: " + swift_literal(locale_data["currencyPattern"]) + ",",
                "            currencySpacing: " + swift_currency_spacing(locale_data.get("currencySpacing", {})) + ",",
                "            currencies: " + swift_currency_map(locale_data["currencies"]),
                "        )" + suffix,
            ]
        )
    lines.extend(["    ]", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_go(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package mf2",
        "",
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; do not edit by hand.",
        "type cldrNumberCurrencyFraction struct {",
        "\tdigits int",
        "\trounding int",
        "\tsource string",
        "}",
        "",
        "type cldrNumberCurrencyData struct {",
        "\tsymbol string",
        "\tnarrowSymbol string",
        "\tdisplayName string",
        "}",
        "",
        "type cldrNumberCurrencySpacing struct {",
        "\tbeforeCurrency string",
        "\tafterCurrency string",
        "}",
        "",
        "type cldrNumberLocaleData struct {",
        "\trequestedLocale string",
        "\tnumbersSourceLocale string",
        "\tcurrenciesSourceLocale string",
        "\tnumberingSystem string",
        "\tnumberingSystemDigits string",
        "\tminimumGroupingDigits int",
        "\tsymbols map[string]string",
        "\tdecimalPattern string",
        "\tpercentPattern string",
        "\tcurrencyPattern string",
        "\tcurrencySpacing cldrNumberCurrencySpacing",
        "\tcurrencies map[string]cldrNumberCurrencyData",
        "}",
        "",
        "var cldrNumberCurrencyFractions = map[string]cldrNumberCurrencyFraction{",
    ]
    for currency, fraction in value["currencyFractions"].items():
        lines.append(
            "\t"
            + go_literal(currency)
            + ": {digits: "
            + str(fraction.get("digits", 2))
            + ", rounding: "
            + str(fraction.get("rounding", 0))
            + ", source: "
            + go_literal(fraction.get("source"))
            + "},"
        )
    lines.extend(["}", "", "var cldrNumberLocales = map[string]cldrNumberLocaleData{"])
    for locale, locale_data in value["locales"].items():
        lines.append("\t" + go_literal(locale) + ": {")
        lines.extend(
            [
                "\t\trequestedLocale: " + go_literal(locale_data["requestedLocale"]) + ",",
                "\t\tnumbersSourceLocale: " + go_literal(locale_data["numbersSourceLocale"]) + ",",
                "\t\tcurrenciesSourceLocale: " + go_literal(locale_data["currenciesSourceLocale"]) + ",",
                "\t\tnumberingSystem: " + go_literal(locale_data["numberingSystem"]) + ",",
                "\t\tnumberingSystemDigits: " + go_literal(locale_data.get("numberingSystemDigits")) + ",",
                "\t\tminimumGroupingDigits: " + str(locale_data["minimumGroupingDigits"]) + ",",
                "\t\tsymbols: " + go_string_map(locale_data["symbols"]) + ",",
                "\t\tdecimalPattern: " + go_literal(locale_data["decimalPattern"]) + ",",
                "\t\tpercentPattern: " + go_literal(locale_data["percentPattern"]) + ",",
                "\t\tcurrencyPattern: " + go_literal(locale_data["currencyPattern"]) + ",",
                "\t\tcurrencySpacing: " + go_currency_spacing(locale_data.get("currencySpacing", {})) + ",",
                "\t\tcurrencies: " + go_currency_map(locale_data["currencies"]) + ",",
                "\t},",
            ]
        )
    lines.extend(["}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_php(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = (
        "<?php\n\n"
        "declare(strict_types=1);\n\n"
        "namespace Mojito\\MessageFormat2\\Internal;\n\n"
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_number_data.py; "
        "do not edit by hand.\n\n"
        "const CLDR_NUMBER_DATA = "
        + php_literal(value)
        + ";\n\n"
        "function cldr_number_data(): array\n"
        "{\n"
        "    return CLDR_NUMBER_DATA;\n"
        "}\n"
    )
    path.write_text(source, encoding="utf-8")


def go_string_map(values: dict[str, str]) -> str:
    if not values:
        return "map[string]string{}"
    entries = [
        go_literal(key) + ": " + go_literal(value)
        for key, value in values.items()
    ]
    return "map[string]string{" + ", ".join(entries) + "}"


def go_currency_map(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "map[string]cldrNumberCurrencyData{}"
    entries = []
    for currency, data in values.items():
        entries.append(
            go_literal(currency)
            + ": {symbol: "
            + go_literal(data.get("symbol"))
            + ", narrowSymbol: "
            + go_literal(data.get("narrowSymbol"))
            + ", displayName: "
            + go_literal(data.get("displayName"))
            + "}"
        )
    return "map[string]cldrNumberCurrencyData{" + ", ".join(entries) + "}"


def go_currency_spacing(value: dict[str, Any]) -> str:
    return (
        "cldrNumberCurrencySpacing{beforeCurrency: "
        + go_literal(currency_spacing_insert(value, "beforeCurrency"))
        + ", afterCurrency: "
        + go_literal(currency_spacing_insert(value, "afterCurrency"))
        + "}"
    )


def swift_string_map(values: dict[str, str]) -> str:
    if not values:
        return "[:]"
    entries = [
        swift_literal(key) + ": " + swift_literal(value)
        for key, value in values.items()
    ]
    return "[" + ", ".join(entries) + "]"


def swift_currency_map(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "[:]"
    entries = []
    for currency, data in values.items():
        entries.append(
            swift_literal(currency)
            + ": CurrencyData(symbol: "
            + swift_literal(data.get("symbol"))
            + ", narrowSymbol: "
            + swift_literal(data.get("narrowSymbol"))
            + ", displayName: "
            + swift_literal(data.get("displayName"))
            + ")"
        )
    return "[" + ", ".join(entries) + "]"


def swift_currency_spacing(value: dict[str, Any]) -> str:
    return (
        "CurrencySpacing(beforeCurrency: "
        + swift_literal(currency_spacing_insert(value, "beforeCurrency"))
        + ", afterCurrency: "
        + swift_literal(currency_spacing_insert(value, "afterCurrency"))
        + ")"
    )


def swift_literal(value: str | None) -> str:
    if value is None:
        return "nil"
    output = ['"']
    for ch in value:
        codepoint = ord(ch)
        if ch == "\\":
            output.append("\\\\")
        elif ch == '"':
            output.append('\\"')
        elif ch == "\n":
            output.append("\\n")
        elif ch == "\r":
            output.append("\\r")
        elif ch == "\t":
            output.append("\\t")
        elif 0x20 <= codepoint <= 0x7E:
            output.append(ch)
        else:
            output.append("\\u{" + format(codepoint, "x") + "}")
    output.append('"')
    return "".join(output)


def python_literal(value: Any) -> str:
    if value is None:
        return "None"
    if value is True:
        return "True"
    if value is False:
        return "False"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=True)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "[" + ", ".join(python_literal(item) for item in value) + "]"
    if isinstance(value, dict):
        entries = [
            python_literal(key) + ": " + python_literal(item)
            for key, item in value.items()
        ]
        return "{" + ", ".join(entries) + "}"
    raise TypeError(f"Unsupported Python literal value: {value!r}")


def go_literal(value: str | None) -> str:
    if value is None:
        return '""'
    return json.dumps(value, ensure_ascii=True)


def rust_option_literal(value: str | None) -> str:
    if value is None:
        return "None"
    return "Some(" + rust_literal(value) + ")"


def rust_literal(value: str | None) -> str:
    if value is None:
        return '""'
    output = ['"']
    for ch in value:
        codepoint = ord(ch)
        if ch == "\\":
            output.append("\\\\")
        elif ch == '"':
            output.append('\\"')
        elif ch == "\n":
            output.append("\\n")
        elif ch == "\r":
            output.append("\\r")
        elif ch == "\t":
            output.append("\\t")
        elif 0x20 <= codepoint <= 0x7E:
            output.append(ch)
        else:
            output.append("\\u{" + format(codepoint, "x") + "}")
    output.append('"')
    return "".join(output)


def php_literal(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return php_string_literal(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "[" + ", ".join(php_literal(item) for item in value) + "]"
    if isinstance(value, dict):
        entries = [
            php_literal(key) + " => " + php_literal(item)
            for key, item in value.items()
        ]
        return "[" + ", ".join(entries) + "]"
    raise TypeError(f"Unsupported PHP literal value: {value!r}")


def php_string_literal(value: str) -> str:
    output = ['"']
    for ch in value:
        codepoint = ord(ch)
        if ch == "\\":
            output.append("\\\\")
        elif ch == '"':
            output.append('\\"')
        elif ch == "$":
            output.append("\\$")
        elif ch == "\n":
            output.append("\\n")
        elif ch == "\r":
            output.append("\\r")
        elif ch == "\t":
            output.append("\\t")
        elif 0x20 <= codepoint <= 0x7E:
            output.append(ch)
        else:
            output.append("\\u{" + format(codepoint, "x") + "}")
    output.append('"')
    return "".join(output)


def java_package_path(java_package: str) -> Path:
    return Path(*java_package.split("."))


if __name__ == "__main__":
    raise SystemExit(main())
