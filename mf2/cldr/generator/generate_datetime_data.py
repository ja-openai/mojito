#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


GREGORIAN_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-dates-full/main/{locale}/ca-gregorian.json"
)
TIME_ZONE_NAMES_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-dates-full/main/{locale}/timeZoneNames.json"
)
DATE_FIELDS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-dates-full/main/{locale}/dateFields.json"
)
NUMBERS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-numbers-full/main/{locale}/numbers.json"
)
NUMBERING_SYSTEMS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/numberingSystems.json"
)
DAY_PERIODS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/dayPeriods.json"
)
WEEK_DATA_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/weekData.json"
)
TIME_DATA_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/supplemental/timeData.json"
)
DEFAULT_LOCALES = ("en-US", "fr-FR", "de-DE", "ja-JP", "ar-EG")
STYLES = ("full", "long", "medium", "short")
DATE_TIME_STYLE_JOIN_FORMAT_OVERRIDES = {
    "ar": {"full": "{1} في {0}", "long": "{1} في {0}"},
    "ar-EG": {"full": "{1} في {0}", "long": "{1} في {0}"},
    "de": {"full": "{1} um {0}", "long": "{1} um {0}"},
    "en": {"full": "{1} at {0}", "long": "{1} at {0}"},
    "fr": {"full": "{1} à {0}", "long": "{1} à {0}"},
}
NAME_WIDTHS = ("wide", "abbreviated", "short", "narrow")
SKELETON_FIELD_ORDER = "GyYuUrQqMLlwWdDFgEecabBhHkKjJmsSAzZOvVXx"
SUPPORTED_SKELETON_FIELDS = frozenset("GyYurwWQqMLldDFgEecabBhHkKjJmsSAzZOvVXx")
SUPPORTED_PATTERN_FIELDS = frozenset("GyYurwWQqMLdDFgEecabBhHkKmsSAzZOvVXx")
APPEND_ITEM_KEYS = (
    "Era",
    "Year",
    "Quarter",
    "Month",
    "Week",
    "Day",
    "Day-Of-Week",
    "Hour",
    "Minute",
    "Second",
    "Timezone",
)
DEFAULT_APPEND_ITEMS = {
    "Era": "{0} {1}",
    "Year": "{0} {1}",
    "Quarter": "{0} ({2}: {1})",
    "Month": "{0} ({2}: {1})",
    "Week": "{0} ({2}: {1})",
    "Day": "{0} ({2}: {1})",
    "Day-Of-Week": "{0} {1}",
    "Hour": "{0} ({2}: {1})",
    "Minute": "{0} ({2}: {1})",
    "Second": "{0} ({2}: {1})",
    "Timezone": "{0} {1}",
}
FIELD_NAME_SOURCE_KEYS = {
    "Era": "era",
    "Year": "year",
    "Quarter": "quarter",
    "Month": "month",
    "Week": "week",
    "Day": "day",
    "Day-Of-Week": "weekday",
    "Hour": "hour",
    "Minute": "minute",
    "Second": "second",
    "Timezone": "zone",
}
WEEKDAY_INDEX = {
    "sun": 0,
    "mon": 1,
    "tue": 2,
    "wed": 3,
    "thu": 4,
    "fri": 5,
    "sat": 6,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate experimental CLDR Gregorian date/time data."
    )
    parser.add_argument(
        "--locales",
        default=",".join(DEFAULT_LOCALES),
        help="Comma-separated BCP47-style locales.",
    )
    parser.add_argument(
        "--out",
        default="generated/experimental-datetime",
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
        help="Java package for generated Java date/time data.",
    )
    parser.add_argument(
        "--java-runtime-out",
        type=Path,
        help="Optional generated Java source path to vendor into a runtime package.",
    )
    parser.add_argument(
        "--kotlin-package",
        default="com.box.l10n.mojito.mf2",
        help="Kotlin package for generated Kotlin date/time data.",
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
    data, skeleton_coverage = build_generated_data(locales, args.cldr_ref)

    out = Path(args.out)
    if args.clean:
        shutil.rmtree(out, ignore_errors=True)
    path = out / "date_time_data.json"
    skeleton_coverage_path = out / "skeleton_coverage.json"
    javascript_path = out / "javascript" / "date_time_data.js"
    javascript_packed_path = out / "javascript" / "date_time_data_packed.js"
    javascript_packed_locale_dir = out / "javascript" / "packed-locales"
    python_path = out / "python" / "mojito_mf2" / "_cldr_date_time_data.py"
    rust_path = out / "rust" / "cldr_date_time_data.rs"
    java_path = out / "java" / java_package_path(args.java_package) / "CldrDateTimeData.java"
    kotlin_path = out / "kotlin" / java_package_path(args.kotlin_package) / "CldrDateTimeData.kt"
    swift_path = out / "swift" / "CldrDateTimeData.swift"
    go_path = out / "go" / "cldr_date_time_data.go"
    php_path = out / "php" / "CldrDateTimeData.php"
    write_json(path, data)
    write_json(skeleton_coverage_path, skeleton_coverage)
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
            "Generated experimental CLDR date/time data "
            f"locales={len(data['locales'])} "
            f"bytes={{'json': {path.stat().st_size}, "
            f"'skeletonCoverage': {skeleton_coverage_path.stat().st_size}, "
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


def build_generated_data(locales: list[str], cldr_ref: str) -> tuple[dict[str, Any], dict[str, Any]]:
    numbering_systems = fetch_json(NUMBERING_SYSTEMS_URL.format(ref=cldr_ref))[
        "supplemental"
    ]["numberingSystems"]
    day_period_rule_set = fetch_json(DAY_PERIODS_URL.format(ref=cldr_ref))[
        "supplemental"
    ]["dayPeriodRuleSet"]
    week_data = fetch_json(WEEK_DATA_URL.format(ref=cldr_ref))["supplemental"][
        "weekData"
    ]
    time_data = fetch_json(TIME_DATA_URL.format(ref=cldr_ref))["supplemental"][
        "timeData"
    ]
    generated: dict[str, Any] = {
        "metadata": {
            "cldrRef": cldr_ref,
            "calendar": "gregorian",
            "dropWithoutMigration": True,
            "experimental": True,
            "generator": "mf2/cldr/generator/generate_datetime_data.py",
            "localesRequested": locales,
            "source": (
                "unicode-org/cldr-json cldr-dates-full main ca-gregorian "
                "+ cldr-dates-full main timeZoneNames/dateFields "
                "+ cldr-numbers-full main numbers + cldr-core supplemental "
                "numberingSystems/dayPeriods/weekData/timeData"
            ),
        },
        "locales": {},
    }
    skeleton_coverage: dict[str, Any] = {
        "metadata": {
            "cldrRef": cldr_ref,
            "calendar": "gregorian",
            "dropWithoutMigration": True,
            "experimental": True,
            "generator": "mf2/cldr/generator/generate_datetime_data.py",
            "localesRequested": locales,
            "source": "CLDR Gregorian dateTimeFormats.availableFormats",
            "supportedSkeletonFields": "".join(sorted(SUPPORTED_SKELETON_FIELDS)),
            "supportedPatternFields": "".join(sorted(SUPPORTED_PATTERN_FIELDS)),
        },
        "locales": {},
    }

    for locale in locales:
        locale_data, locale_coverage = build_locale_data(
            locale,
            cldr_ref,
            numbering_systems,
            day_period_rule_set,
            week_data,
            time_data,
        )
        generated["locales"][locale] = locale_data
        skeleton_coverage["locales"][locale] = locale_coverage
    return generated, skeleton_coverage


def build_locale_data(
    locale: str,
    cldr_ref: str,
    numbering_systems: dict[str, dict[str, str]],
    day_period_rule_set: dict[str, dict[str, dict[str, str]]],
    week_data: dict[str, dict[str, str]],
    time_data: dict[str, dict[str, str]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_locale, source_data = fetch_main_data(GREGORIAN_URL, cldr_ref, locale)
    _time_zone_source, time_zone_data = fetch_main_data(TIME_ZONE_NAMES_URL, cldr_ref, locale)
    _date_fields_source, date_fields_data = fetch_main_data(DATE_FIELDS_URL, cldr_ref, locale)
    numbers_source, numbers_data = fetch_main_data(NUMBERS_URL, cldr_ref, locale)
    gregorian = source_data["main"][source_locale]["dates"]["calendars"]["gregorian"]
    time_zone_names = time_zone_data["main"][_time_zone_source]["dates"]["timeZoneNames"]
    date_fields = date_fields_data["main"][_date_fields_source]["dates"]["fields"]
    numbers = numbers_data["main"][numbers_source]["numbers"]
    numbering_system = numbers.get("defaultNumberingSystem", "latn")
    symbols = select_number_system_data(numbers, "symbols", numbering_system)
    raw_available_formats = gregorian["dateTimeFormats"].get("availableFormats", {})
    available_formats = available_format_map(raw_available_formats)
    locale_data = {
        "requestedLocale": locale,
        "sourceLocale": source_locale,
        "numbersSourceLocale": numbers_source,
        "calendar": "gregorian",
        "numberingSystem": numbering_system,
        "numberingSystemDigits": numbering_system_digits(numbering_systems, numbering_system),
        "decimalSeparator": symbols.get("decimal", "."),
        "allowedHourFormats": allowed_hour_formats(time_data, locale),
        "firstDayOfWeek": first_day_of_week(week_data, locale),
        "minDaysInFirstWeek": min_days_in_first_week(week_data, locale),
        "dateFormats": style_map(gregorian["dateFormats"]),
        "timeFormats": style_map(gregorian["timeFormats"]),
        "dateTimeFormats": style_map(gregorian["dateTimeFormats"]),
        "dateTimeStyleJoinFormats": date_time_style_join_format_map(locale, source_locale),
        "availableFormats": available_formats,
        "appendItems": append_item_map(gregorian["dateTimeFormats"].get("appendItems", {})),
        "fieldNames": field_name_map(
            date_fields,
            gregorian["dateTimeFormats"].get("appendItems", {}),
        ),
        "timeZoneNames": time_zone_name_map(time_zone_names),
        "months": names_by_context(gregorian["months"]),
        "quarters": quarter_names(gregorian["quarters"]),
        "weekdays": names_by_context(gregorian["days"]),
        "eras": {
            "wide": gregorian["eras"].get("eraNames", {}),
            "abbreviated": gregorian["eras"].get("eraAbbr", {}),
            "narrow": gregorian["eras"].get("eraNarrow", {}),
        },
        "dayPeriods": names_by_context(gregorian["dayPeriods"]),
        "dayPeriodRules": day_period_rules(day_period_rule_set, locale),
    }
    return locale_data, available_format_coverage(
        locale,
        source_locale,
        raw_available_formats,
        available_formats,
    )


def numbering_system_digits(
    numbering_systems: dict[str, dict[str, str]], numbering_system: str
) -> str | None:
    data = numbering_systems.get(numbering_system, {})
    if data.get("_type") != "numeric":
        return None
    return data.get("_digits")


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


def first_day_of_week(week_data: dict[str, dict[str, str]], locale: str) -> int:
    territory = locale_territory(locale)
    raw = week_data.get("firstDay", {}).get(
        territory,
        week_data.get("firstDay", {}).get("001", "mon"),
    )
    return WEEKDAY_INDEX.get(raw, WEEKDAY_INDEX["mon"])


def min_days_in_first_week(week_data: dict[str, dict[str, str]], locale: str) -> int:
    territory = locale_territory(locale)
    raw = week_data.get("minDays", {}).get(
        territory,
        week_data.get("minDays", {}).get("001", "1"),
    )
    return int(raw)


def allowed_hour_formats(time_data: dict[str, dict[str, str]], locale: str) -> str:
    for candidate in time_data_candidates(locale):
        row = time_data.get(candidate)
        if not row:
            continue
        allowed = row.get("_allowed")
        if isinstance(allowed, str) and allowed:
            return " ".join(allowed.split())
    return "H h"


def time_data_candidates(locale: str) -> list[str]:
    canonical = canonical_locale_key(locale)
    parts = canonical.split("-")
    candidates = locale_source_candidates(canonical)
    territory = locale_territory(canonical)
    if territory != "001":
        candidates.append(territory)
    if parts:
        candidates.append(parts[0] + "-001")
    candidates.append("001")
    return list(dict.fromkeys(candidates))


def locale_territory(locale: str) -> str:
    parts = canonical_locale_key(locale).split("-")
    for part in parts[1:]:
        if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
            return part.upper()
    return "001"


def style_map(source: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for style in STYLES:
        value = source.get(style)
        if isinstance(value, str):
            result[style] = value
    return result


def date_time_style_join_format_map(locale: str, source_locale: str) -> dict[str, str]:
    for candidate in (canonical_locale_key(locale), canonical_locale_key(source_locale), source_locale.split("-")[0]):
        override = DATE_TIME_STYLE_JOIN_FORMAT_OVERRIDES.get(candidate)
        if override is not None:
            return dict(override)
    return {}


def names_by_context(source: dict[str, Any]) -> dict[str, dict[str, dict[str, str]]]:
    result: dict[str, dict[str, dict[str, str]]] = {}
    for context in ("format", "stand-alone"):
        context_source = source.get(context, {})
        widths: dict[str, dict[str, str]] = {}
        for width in NAME_WIDTHS:
            values = context_source.get(width)
            if isinstance(values, dict):
                widths[width] = {
                    key: value
                    for key, value in values.items()
                    if isinstance(value, str)
                }
        if widths:
            result[context] = widths
    return result


def quarter_names(source: dict[str, Any]) -> dict[str, dict[str, dict[str, str]]]:
    result: dict[str, dict[str, dict[str, str]]] = {}
    format_source = source.get("format", {})
    format_widths = quarter_widths(format_source)
    if format_widths:
        result["format"] = format_widths
    standalone_widths: dict[str, dict[str, str]] = {}
    for width, values in quarter_widths(source.get("stand-alone", {})).items():
        if values != format_widths.get(width):
            standalone_widths[width] = values
    if standalone_widths:
        result["stand-alone"] = standalone_widths
    return result


def quarter_widths(source: dict[str, Any]) -> dict[str, dict[str, str]]:
    widths: dict[str, dict[str, str]] = {}
    for width in ("wide", "abbreviated", "narrow"):
        values = source.get(width)
        if isinstance(values, dict):
            widths[width] = {
                key: value
                for key, value in values.items()
                if isinstance(value, str)
            }
    return widths


def day_period_rules(
    day_period_rule_set: dict[str, dict[str, dict[str, str]]],
    locale: str,
) -> str:
    source = None
    for candidate in locale_source_candidates(locale):
        source = day_period_rule_set.get(candidate)
        if source is not None:
            break
    if not source:
        return ""
    rules: list[tuple[int, int, str]] = []
    for period, rule in source.items():
        if not isinstance(rule, dict):
            continue
        if isinstance(rule.get("_at"), str):
            minute = day_period_minute(rule["_at"])
            rules.append((minute, minute, period))
            continue
        start = day_period_minute(str(rule.get("_from", "00:00")))
        end_value = rule.get("_before", rule.get("_to", "24:00"))
        end = day_period_minute(str(end_value))
        rules.append((start, end, period))
    return ";".join(
        f"{period}={start}" if start == end else f"{period}={start}-{end}"
        for start, end, period in sorted(rules)
    )


def day_period_minute(value: str) -> int:
    hour_text, minute_text = value.split(":", 1)
    return int(hour_text) * 60 + int(minute_text)


def time_zone_name_map(source: dict[str, Any]) -> dict[str, str]:
    utc_zone = source.get("zone", {}).get("Etc", {}).get("UTC", {})
    return {
        "gmtFormat": string_value(source.get("gmtFormat"), "GMT{0}"),
        "gmtZeroFormat": string_value(source.get("gmtZeroFormat"), "GMT"),
        "utcShort": string_value(utc_zone.get("short", {}).get("standard"), "UTC"),
        "utcLong": string_value(
            utc_zone.get("long", {}).get("standard"),
            string_value(utc_zone.get("short", {}).get("standard"), "UTC"),
        ),
    }


def string_value(value: Any, fallback: str) -> str:
    return value if isinstance(value, str) and value else fallback


def available_format_map(source: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for skeleton, pattern in source.items():
        if not isinstance(skeleton, str) or not isinstance(pattern, str):
            continue
        normalized = available_format_skeleton(skeleton)
        if normalized is None:
            continue
        canonical = canonical_skeleton(normalized)
        if canonical and supported_available_format(canonical, pattern):
            result[canonical] = pattern
    return dict(sorted(result.items()))


def available_format_coverage(
    locale: str,
    source_locale: str,
    source: dict[str, Any],
    supported_formats: dict[str, str],
) -> dict[str, Any]:
    reason_counts: dict[str, int] = {}
    unsupported_skeleton_fields: dict[str, int] = {}
    unsupported_pattern_fields: dict[str, int] = {}
    supported_field_counts: dict[str, int] = {}
    unsupported_examples: list[dict[str, Any]] = []
    raw_entries = 0
    normalized_entries = 0
    candidate_entries = 0

    for skeleton, pattern in source.items():
        if not isinstance(skeleton, str) or not isinstance(pattern, str):
            increment(reason_counts, "invalidEntry")
            raw_entries += 1
            continue
        raw_entries += 1
        normalized = available_format_skeleton(skeleton)
        if normalized is None:
            increment(
                reason_counts,
                "altVariant" if "-alt-" in skeleton else "pluralCountVariant",
            )
            continue
        normalized_entries += 1
        canonical = canonical_skeleton(normalized)
        if not canonical:
            increment(reason_counts, "emptySkeleton")
            continue
        unsupported_skeleton = sorted(set(canonical) - SUPPORTED_SKELETON_FIELDS)
        unsupported_pattern = sorted(set(pattern_fields(pattern)) - SUPPORTED_PATTERN_FIELDS)
        if unsupported_skeleton or unsupported_pattern:
            increment(reason_counts, "unsupportedFields")
            increment_all(unsupported_skeleton_fields, unsupported_skeleton)
            increment_all(unsupported_pattern_fields, unsupported_pattern)
            if len(unsupported_examples) < 16:
                unsupported_examples.append(
                    {
                        "skeleton": skeleton,
                        "canonicalSkeleton": canonical,
                        "unsupportedSkeletonFields": unsupported_skeleton,
                        "unsupportedPatternFields": unsupported_pattern,
                    }
                )
            continue
        candidate_entries += 1

    for skeleton in supported_formats:
        increment_all(supported_field_counts, sorted(set(skeleton)))

    filtered_entries = sum(reason_counts.values())
    return {
        "requestedLocale": locale,
        "sourceLocale": source_locale,
        "rawEntries": raw_entries,
        "normalizedEntries": normalized_entries,
        "candidateEntries": candidate_entries,
        "supportedEntries": len(supported_formats),
        "duplicateCandidateEntries": candidate_entries - len(supported_formats),
        "filteredEntries": filtered_entries,
        "filteredByReason": dict(sorted(reason_counts.items())),
        "supportedSkeletonFields": dict(sorted(supported_field_counts.items())),
        "unsupportedSkeletonFields": dict(sorted(unsupported_skeleton_fields.items())),
        "unsupportedPatternFields": dict(sorted(unsupported_pattern_fields.items())),
        "unsupportedExamples": unsupported_examples,
    }


def increment(counts: dict[str, int], key: str) -> None:
    counts[key] = counts.get(key, 0) + 1


def increment_all(counts: dict[str, int], keys: list[str]) -> None:
    for key in keys:
        increment(counts, key)


def append_item_map(source: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for key in APPEND_ITEM_KEYS:
        value = source.get(key)
        if (
            isinstance(value, str)
            and "{0}" in value
            and "{1}" in value
            and value != DEFAULT_APPEND_ITEMS.get(key)
        ):
            result[key] = value
    return result


def field_name_map(source: dict[str, Any], append_items: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for key, source_key in FIELD_NAME_SOURCE_KEYS.items():
        pattern = append_items.get(key)
        if not isinstance(pattern, str):
            pattern = DEFAULT_APPEND_ITEMS.get(key, "")
        if "{2}" not in pattern:
            continue
        display_name = source.get(source_key, {}).get("displayName")
        if isinstance(display_name, str) and display_name:
            result[key] = display_name
    return result


def available_format_skeleton(skeleton: str) -> str | None:
    if "-alt-" in skeleton:
        return None
    if "-count-" not in skeleton:
        return skeleton
    base, count = skeleton.split("-count-", 1)
    return base if count == "other" else None


def supported_available_format(skeleton: str, pattern: str) -> bool:
    return set(skeleton).issubset(SUPPORTED_SKELETON_FIELDS) and set(
        pattern_fields(pattern)
    ).issubset(SUPPORTED_PATTERN_FIELDS)


def canonical_skeleton(skeleton: str) -> str:
    widths: dict[str, int] = {}
    for symbol, count in skeleton_fields(skeleton):
        symbol = "L" if symbol == "l" else symbol
        widths[symbol] = max(widths.get(symbol, 0), count)
    return "".join(
        symbol * widths[symbol]
        for symbol in SKELETON_FIELD_ORDER
        if symbol in widths
    )


def skeleton_fields(skeleton: str) -> list[tuple[str, int]]:
    fields: list[tuple[str, int]] = []
    index = 0
    while index < len(skeleton):
        symbol = skeleton[index]
        if not symbol.isascii() or not symbol.isalpha():
            index += 1
            continue
        end = index + 1
        while end < len(skeleton) and skeleton[end] == symbol:
            end += 1
        fields.append((symbol, end - index))
        index = end
    return fields


def pattern_fields(pattern: str) -> list[str]:
    fields: list[str] = []
    in_quote = False
    index = 0
    while index < len(pattern):
        symbol = pattern[index]
        if symbol == "'":
            if index + 1 < len(pattern) and pattern[index + 1] == "'":
                index += 2
                continue
            in_quote = not in_quote
            index += 1
            continue
        if not in_quote and symbol.isascii() and symbol.isalpha():
            fields.append(symbol)
        index += 1
    return fields


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
    raise SystemExit(f"Could not find CLDR date/time data for {locale}; tried {candidates}.")


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
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; "
        "do not edit by hand.\n"
        f"export const DATE_TIME_DATA = {payload};\n"
    )
    path.write_text(source, encoding="utf-8")


def write_javascript_packed(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(
        pack_date_time_resource(value),
        ensure_ascii=True,
        separators=(",", ":"),
    )
    source = (
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; "
        "do not edit by hand.\n"
        f"export const DATE_TIME_DATA_PACKED = {payload};\n"
    )
    path.write_text(source, encoding="utf-8")


def write_javascript_packed_locale_chunks(path: Path, value: dict[str, Any]) -> None:
    path.mkdir(parents=True, exist_ok=True)
    for locale in sorted(value["locales"]):
        locale_path = path / f"{locale}.js"
        locale_data = subset_date_time_resource(value, [locale])
        payload = json.dumps(
            pack_date_time_resource(locale_data),
            ensure_ascii=True,
            separators=(",", ":"),
        )
        source = (
            "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; "
            "do not edit by hand.\n"
            f"export const DATE_TIME_DATA_PACKED_LOCALE = {payload};\n"
        )
        locale_path.write_text(source, encoding="utf-8")


def subset_date_time_resource(value: dict[str, Any], locales: list[str]) -> dict[str, Any]:
    source_locales = value["locales"]
    missing = [locale for locale in locales if locale not in source_locales]
    if missing:
        raise ValueError(f"Unknown date/time locale(s): {', '.join(missing)}")
    metadata = dict(value.get("metadata", {}))
    metadata["localesRequested"] = list(locales)
    return {
        "metadata": metadata,
        "locales": {locale: source_locales[locale] for locale in locales},
    }


def pack_date_time_resource(value: dict[str, Any]) -> dict[str, Any]:
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

    def style_values(values: dict[str, str]) -> list[int]:
        return [sid(values.get(style)) for style in STYLES]

    def string_map(values: dict[str, str]) -> list[list[int]]:
        return [[sid(key), sid(item)] for key, item in sorted(values.items())]

    def nested_map_2(values: dict[str, dict[str, str]]) -> list[list[Any]]:
        return [[sid(key), string_map(item)] for key, item in sorted(values.items())]

    def nested_map_3(values: dict[str, dict[str, dict[str, str]]]) -> list[list[Any]]:
        return [[sid(key), nested_map_2(item)] for key, item in sorted(values.items())]

    packed_locales = []
    for locale, locale_data in sorted(value["locales"].items()):
        packed_locales.append(
            [
                sid(locale),
                sid(locale_data["requestedLocale"]),
                sid(locale_data["sourceLocale"]),
                sid(locale_data["numbersSourceLocale"]),
                sid(locale_data["calendar"]),
                sid(locale_data["numberingSystem"]),
                sid(locale_data.get("numberingSystemDigits")),
                sid(locale_data["allowedHourFormats"]),
                locale_data["firstDayOfWeek"],
                locale_data["minDaysInFirstWeek"],
                style_values(locale_data["dateFormats"]),
                style_values(locale_data["timeFormats"]),
                style_values(locale_data["dateTimeFormats"]),
                style_values(locale_data["dateTimeStyleJoinFormats"]),
                string_map(locale_data["availableFormats"]),
                string_map(locale_data["appendItems"]),
                string_map(locale_data["fieldNames"]),
                string_map(locale_data["timeZoneNames"]),
                nested_map_3(locale_data["months"]),
                nested_map_3(locale_data["quarters"]),
                nested_map_3(locale_data["weekdays"]),
                nested_map_2(locale_data["eras"]),
                nested_map_3(locale_data["dayPeriods"]),
                sid(locale_data["dayPeriodRules"]),
                sid(locale_data["decimalSeparator"]),
            ]
        )
    return {
        "version": 9,
        "styleOrder": list(STYLES),
        "strings": strings,
        "locales": packed_locales,
    }


def write_python(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    source = (
        "# Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; "
        "do not edit by hand.\n"
        "from __future__ import annotations\n\n"
        "from typing import Any\n\n"
        f"DATE_TIME_DATA: dict[str, Any] = {python_literal(value)}\n"
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
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; do not edit by hand.",
        "final class CldrDateTimeData {",
        "    private CldrDateTimeData() {}",
        "",
        "    record LocaleData(",
        "            String requestedLocale,",
        "            String sourceLocale,",
        "            String numbersSourceLocale,",
        "            String calendar,",
        "            String numberingSystem,",
        "            String numberingSystemDigits,",
        "            String decimalSeparator,",
        "            String allowedHourFormats,",
        "            int firstDayOfWeek,",
        "            int minDaysInFirstWeek,",
        "            Map<String, String> dateFormats,",
        "            Map<String, String> timeFormats,",
        "            Map<String, String> dateTimeFormats,",
        "            Map<String, String> dateTimeStyleJoinFormats,",
        "            Map<String, String> availableFormats,",
        "            Map<String, String> appendItems,",
        "            Map<String, String> fieldNames,",
        "            Map<String, String> timeZoneNames,",
        "            Map<String, Map<String, Map<String, String>>> months,",
        "            Map<String, Map<String, Map<String, String>>> quarters,",
        "            Map<String, Map<String, Map<String, String>>> weekdays,",
        "            Map<String, Map<String, String>> eras,",
        "            Map<String, Map<String, Map<String, String>>> dayPeriods,",
        "            String dayPeriodRules) {}",
        "",
        "    static final Map<String, LocaleData> LOCALES = Map.ofEntries(",
    ]
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append(
            "            entry("
            + java_literal(locale)
            + ", new LocaleData("
            + java_literal(locale_data["requestedLocale"])
            + ", "
            + java_literal(locale_data["sourceLocale"])
            + ", "
            + java_literal(locale_data["numbersSourceLocale"])
            + ", "
            + java_literal(locale_data["calendar"])
            + ", "
            + java_literal(locale_data["numberingSystem"])
            + ", "
            + java_literal(locale_data.get("numberingSystemDigits"))
            + ", "
            + java_literal(locale_data["decimalSeparator"])
            + ", "
            + java_literal(locale_data["allowedHourFormats"])
            + ", "
            + str(locale_data["firstDayOfWeek"])
            + ", "
            + str(locale_data["minDaysInFirstWeek"])
            + ", "
            + java_string_map(locale_data["dateFormats"])
            + ", "
            + java_string_map(locale_data["timeFormats"])
            + ", "
            + java_string_map(locale_data["dateTimeFormats"])
            + ", "
            + java_string_map(locale_data["dateTimeStyleJoinFormats"])
            + ", "
            + java_string_map(locale_data["availableFormats"])
            + ", "
            + java_string_map(locale_data["appendItems"])
            + ", "
            + java_string_map(locale_data["fieldNames"])
            + ", "
            + java_string_map(locale_data["timeZoneNames"])
            + ", "
            + java_nested_string_map_3(locale_data["months"])
            + ", "
            + java_nested_string_map_3(locale_data["quarters"])
            + ", "
            + java_nested_string_map_3(locale_data["weekdays"])
            + ", "
            + java_nested_string_map_2(locale_data["eras"])
            + ", "
            + java_nested_string_map_3(locale_data["dayPeriods"])
            + ", "
            + java_literal(locale_data["dayPeriodRules"])
            + "))"
            + suffix
        )
    lines.extend(["    );", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_rust(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; do not edit by hand.",
        "pub(crate) type CldrDateTimeStringMap = &'static [(&'static str, &'static str)];",
        "pub(crate) type CldrDateTimeNestedMap2 = &'static [(&'static str, CldrDateTimeStringMap)];",
        "pub(crate) type CldrDateTimeNestedMap3 = &'static [(&'static str, CldrDateTimeNestedMap2)];",
        "",
        "#[derive(Debug, Clone, Copy)]",
        "pub(crate) struct CldrDateTimeLocaleData {",
        "    pub(crate) requested_locale: &'static str,",
        "    pub(crate) source_locale: &'static str,",
        "    pub(crate) numbers_source_locale: &'static str,",
        "    pub(crate) calendar: &'static str,",
        "    pub(crate) numbering_system: &'static str,",
        "    pub(crate) numbering_system_digits: Option<&'static str>,",
        "    pub(crate) decimal_separator: &'static str,",
        "    pub(crate) allowed_hour_formats: &'static str,",
        "    pub(crate) first_day_of_week: u8,",
        "    pub(crate) min_days_in_first_week: u8,",
        "    pub(crate) date_formats: CldrDateTimeStringMap,",
        "    pub(crate) time_formats: CldrDateTimeStringMap,",
        "    pub(crate) date_time_formats: CldrDateTimeStringMap,",
        "    pub(crate) date_time_style_join_formats: CldrDateTimeStringMap,",
        "    pub(crate) available_formats: CldrDateTimeStringMap,",
        "    pub(crate) append_items: CldrDateTimeStringMap,",
        "    pub(crate) field_names: CldrDateTimeStringMap,",
        "    pub(crate) time_zone_names: CldrDateTimeStringMap,",
        "    pub(crate) months: CldrDateTimeNestedMap3,",
        "    pub(crate) quarters: CldrDateTimeNestedMap3,",
        "    pub(crate) weekdays: CldrDateTimeNestedMap3,",
        "    pub(crate) eras: CldrDateTimeNestedMap2,",
        "    pub(crate) day_periods: CldrDateTimeNestedMap3,",
        "    pub(crate) day_period_rules: &'static str,",
        "}",
        "",
        "pub(crate) static CLDR_DATE_TIME_LOCALES: &[(&str, CldrDateTimeLocaleData)] = &[",
    ]
    for locale, locale_data in value["locales"].items():
        lines.append("    (" + rust_literal(locale) + ", CldrDateTimeLocaleData {")
        lines.extend(
            [
                "        requested_locale: " + rust_literal(locale_data["requestedLocale"]) + ",",
                "        source_locale: " + rust_literal(locale_data["sourceLocale"]) + ",",
                "        numbers_source_locale: " + rust_literal(locale_data["numbersSourceLocale"]) + ",",
                "        calendar: " + rust_literal(locale_data["calendar"]) + ",",
                "        numbering_system: " + rust_literal(locale_data["numberingSystem"]) + ",",
                "        numbering_system_digits: " + rust_option_literal(locale_data.get("numberingSystemDigits")) + ",",
                "        decimal_separator: " + rust_literal(locale_data["decimalSeparator"]) + ",",
                "        allowed_hour_formats: " + rust_literal(locale_data["allowedHourFormats"]) + ",",
                "        first_day_of_week: " + str(locale_data["firstDayOfWeek"]) + ",",
                "        min_days_in_first_week: " + str(locale_data["minDaysInFirstWeek"]) + ",",
                "        date_formats: " + rust_string_map(locale_data["dateFormats"]) + ",",
                "        time_formats: " + rust_string_map(locale_data["timeFormats"]) + ",",
                "        date_time_formats: " + rust_string_map(locale_data["dateTimeFormats"]) + ",",
                "        date_time_style_join_formats: " + rust_string_map(locale_data["dateTimeStyleJoinFormats"]) + ",",
                "        available_formats: " + rust_string_map(locale_data["availableFormats"]) + ",",
                "        append_items: " + rust_string_map(locale_data["appendItems"]) + ",",
                "        field_names: " + rust_string_map(locale_data["fieldNames"]) + ",",
                "        time_zone_names: " + rust_string_map(locale_data["timeZoneNames"]) + ",",
                "        months: " + rust_nested_string_map_3(locale_data["months"]) + ",",
                "        quarters: " + rust_nested_string_map_3(locale_data["quarters"]) + ",",
                "        weekdays: " + rust_nested_string_map_3(locale_data["weekdays"]) + ",",
                "        eras: " + rust_nested_string_map_2(locale_data["eras"]) + ",",
                "        day_periods: " + rust_nested_string_map_3(locale_data["dayPeriods"]) + ",",
                "        day_period_rules: " + rust_literal(locale_data["dayPeriodRules"]) + ",",
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


def rust_nested_string_map_2(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "&[]"
    entries = [
        "(" + rust_literal(key) + ", " + rust_string_map(value) + ")"
        for key, value in values.items()
    ]
    return "&[" + ", ".join(entries) + "]"


def rust_nested_string_map_3(values: dict[str, dict[str, dict[str, str]]]) -> str:
    if not values:
        return "&[]"
    entries = [
        "(" + rust_literal(key) + ", " + rust_nested_string_map_2(value) + ")"
        for key, value in values.items()
    ]
    return "&[" + ", ".join(entries) + "]"


def java_string_map(values: dict[str, str]) -> str:
    if not values:
        return "Map.of()"
    entries = [
        "entry(" + java_literal(key) + ", " + java_literal(value) + ")"
        for key, value in values.items()
    ]
    return "Map.ofEntries(" + ", ".join(entries) + ")"


def java_nested_string_map_2(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "Map.of()"
    entries = [
        "entry(" + java_literal(key) + ", " + java_string_map(value) + ")"
        for key, value in values.items()
    ]
    return "Map.ofEntries(" + ", ".join(entries) + ")"


def java_nested_string_map_3(values: dict[str, dict[str, dict[str, str]]]) -> str:
    if not values:
        return "Map.of()"
    entries = [
        "entry(" + java_literal(key) + ", " + java_nested_string_map_2(value) + ")"
        for key, value in values.items()
    ]
    return "Map.ofEntries(" + ", ".join(entries) + ")"


def java_literal(value: str | None) -> str:
    if value is None:
        return "null"
    return json.dumps(value, ensure_ascii=True)


def write_kotlin(path: Path, value: dict[str, Any], kotlin_package: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package " + kotlin_package,
        "",
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; do not edit by hand.",
        "internal object CldrDateTimeData {",
        "    data class LocaleData(",
        "        val requestedLocale: String,",
        "        val sourceLocale: String,",
        "        val numbersSourceLocale: String,",
        "        val calendar: String,",
        "        val numberingSystem: String,",
        "        val numberingSystemDigits: String?,",
        "        val decimalSeparator: String,",
        "        val allowedHourFormats: String,",
        "        val firstDayOfWeek: Int,",
        "        val minDaysInFirstWeek: Int,",
        "        val dateFormats: Map<String, String>,",
        "        val timeFormats: Map<String, String>,",
        "        val dateTimeFormats: Map<String, String>,",
        "        val dateTimeStyleJoinFormats: Map<String, String>,",
        "        val availableFormats: Map<String, String>,",
        "        val appendItems: Map<String, String>,",
        "        val fieldNames: Map<String, String>,",
        "        val timeZoneNames: Map<String, String>,",
        "        val months: Map<String, Map<String, Map<String, String>>>,",
        "        val quarters: Map<String, Map<String, Map<String, String>>>,",
        "        val weekdays: Map<String, Map<String, Map<String, String>>>,",
        "        val eras: Map<String, Map<String, String>>,",
        "        val dayPeriods: Map<String, Map<String, Map<String, String>>>,",
        "        val dayPeriodRules: String,",
        "    )",
        "",
        "    val locales: Map<String, LocaleData> = mapOf(",
    ]
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append("        " + kotlin_literal(locale) + " to LocaleData(")
        lines.extend(
            [
                "            requestedLocale = " + kotlin_literal(locale_data["requestedLocale"]) + ",",
                "            sourceLocale = " + kotlin_literal(locale_data["sourceLocale"]) + ",",
                "            numbersSourceLocale = " + kotlin_literal(locale_data["numbersSourceLocale"]) + ",",
                "            calendar = " + kotlin_literal(locale_data["calendar"]) + ",",
                "            numberingSystem = " + kotlin_literal(locale_data["numberingSystem"]) + ",",
                "            numberingSystemDigits = " + kotlin_literal(locale_data.get("numberingSystemDigits")) + ",",
                "            decimalSeparator = " + kotlin_literal(locale_data["decimalSeparator"]) + ",",
                "            allowedHourFormats = " + kotlin_literal(locale_data["allowedHourFormats"]) + ",",
                "            firstDayOfWeek = " + str(locale_data["firstDayOfWeek"]) + ",",
                "            minDaysInFirstWeek = " + str(locale_data["minDaysInFirstWeek"]) + ",",
                "            dateFormats = " + kotlin_string_map(locale_data["dateFormats"]) + ",",
                "            timeFormats = " + kotlin_string_map(locale_data["timeFormats"]) + ",",
                "            dateTimeFormats = " + kotlin_string_map(locale_data["dateTimeFormats"]) + ",",
                "            dateTimeStyleJoinFormats = " + kotlin_string_map(locale_data["dateTimeStyleJoinFormats"]) + ",",
                "            availableFormats = " + kotlin_string_map(locale_data["availableFormats"]) + ",",
                "            appendItems = " + kotlin_string_map(locale_data["appendItems"]) + ",",
                "            fieldNames = " + kotlin_string_map(locale_data["fieldNames"]) + ",",
                "            timeZoneNames = " + kotlin_string_map(locale_data["timeZoneNames"]) + ",",
                "            months = " + kotlin_nested_string_map_3(locale_data["months"]) + ",",
                "            quarters = " + kotlin_nested_string_map_3(locale_data["quarters"]) + ",",
                "            weekdays = " + kotlin_nested_string_map_3(locale_data["weekdays"]) + ",",
                "            eras = " + kotlin_nested_string_map_2(locale_data["eras"]) + ",",
                "            dayPeriods = " + kotlin_nested_string_map_3(locale_data["dayPeriods"]) + ",",
                "            dayPeriodRules = " + kotlin_literal(locale_data["dayPeriodRules"]) + ",",
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


def kotlin_nested_string_map_2(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "emptyMap()"
    entries = [
        kotlin_literal(key) + " to " + kotlin_string_map(value)
        for key, value in values.items()
    ]
    return "mapOf(" + ", ".join(entries) + ")"


def kotlin_nested_string_map_3(values: dict[str, dict[str, dict[str, str]]]) -> str:
    if not values:
        return "emptyMap()"
    entries = [
        kotlin_literal(key) + " to " + kotlin_nested_string_map_2(value)
        for key, value in values.items()
    ]
    return "mapOf(" + ", ".join(entries) + ")"


def kotlin_literal(value: str | None) -> str:
    if value is None:
        return "null"
    encoded = json.dumps(value, ensure_ascii=True)
    return encoded[:-1].replace("$", "\\$") + encoded[-1]


def write_swift(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; do not edit by hand.",
        "import Foundation",
        "",
        "enum CldrDateTimeData {",
        "    struct LocaleData {",
        "        let requestedLocale: String",
        "        let sourceLocale: String",
        "        let numbersSourceLocale: String",
        "        let calendar: String",
        "        let numberingSystem: String",
        "        let numberingSystemDigits: String?",
        "        let decimalSeparator: String",
        "        let allowedHourFormats: String",
        "        let firstDayOfWeek: Int",
        "        let minDaysInFirstWeek: Int",
        "        let dateFormats: [String: String]",
        "        let timeFormats: [String: String]",
        "        let dateTimeFormats: [String: String]",
        "        let dateTimeStyleJoinFormats: [String: String]",
        "        let availableFormats: [String: String]",
        "        let appendItems: [String: String]",
        "        let fieldNames: [String: String]",
        "        let timeZoneNames: [String: String]",
        "        let months: [String: [String: [String: String]]]",
        "        let quarters: [String: [String: [String: String]]]",
        "        let weekdays: [String: [String: [String: String]]]",
        "        let eras: [String: [String: String]]",
        "        let dayPeriods: [String: [String: [String: String]]]",
        "        let dayPeriodRules: String",
        "    }",
        "",
        "    static let locales: [String: LocaleData] = [",
    ]
    locale_items = list(value["locales"].items())
    for index, (locale, locale_data) in enumerate(locale_items):
        suffix = "," if index < len(locale_items) - 1 else ""
        lines.append("        " + swift_literal(locale) + ": LocaleData(")
        lines.extend(
            [
                "            requestedLocale: " + swift_literal(locale_data["requestedLocale"]) + ",",
                "            sourceLocale: " + swift_literal(locale_data["sourceLocale"]) + ",",
                "            numbersSourceLocale: " + swift_literal(locale_data["numbersSourceLocale"]) + ",",
                "            calendar: " + swift_literal(locale_data["calendar"]) + ",",
                "            numberingSystem: " + swift_literal(locale_data["numberingSystem"]) + ",",
                "            numberingSystemDigits: " + swift_literal(locale_data.get("numberingSystemDigits")) + ",",
                "            decimalSeparator: " + swift_literal(locale_data["decimalSeparator"]) + ",",
                "            allowedHourFormats: " + swift_literal(locale_data["allowedHourFormats"]) + ",",
                "            firstDayOfWeek: " + str(locale_data["firstDayOfWeek"]) + ",",
                "            minDaysInFirstWeek: " + str(locale_data["minDaysInFirstWeek"]) + ",",
                "            dateFormats: " + swift_string_map(locale_data["dateFormats"]) + ",",
                "            timeFormats: " + swift_string_map(locale_data["timeFormats"]) + ",",
                "            dateTimeFormats: " + swift_string_map(locale_data["dateTimeFormats"]) + ",",
                "            dateTimeStyleJoinFormats: " + swift_string_map(locale_data["dateTimeStyleJoinFormats"]) + ",",
                "            availableFormats: " + swift_string_map(locale_data["availableFormats"]) + ",",
                "            appendItems: " + swift_string_map(locale_data["appendItems"]) + ",",
                "            fieldNames: " + swift_string_map(locale_data["fieldNames"]) + ",",
                "            timeZoneNames: " + swift_string_map(locale_data["timeZoneNames"]) + ",",
                "            months: " + swift_nested_string_map_3(locale_data["months"]) + ",",
                "            quarters: " + swift_nested_string_map_3(locale_data["quarters"]) + ",",
                "            weekdays: " + swift_nested_string_map_3(locale_data["weekdays"]) + ",",
                "            eras: " + swift_nested_string_map_2(locale_data["eras"]) + ",",
                "            dayPeriods: " + swift_nested_string_map_3(locale_data["dayPeriods"]) + ",",
                "            dayPeriodRules: " + swift_literal(locale_data["dayPeriodRules"]),
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
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; do not edit by hand.",
        "type cldrDateTimeLocaleData struct {",
        "\trequestedLocale string",
        "\tsourceLocale string",
        "\tnumbersSourceLocale string",
        "\tcalendar string",
        "\tnumberingSystem string",
        "\tnumberingSystemDigits string",
        "\tdecimalSeparator string",
        "\tallowedHourFormats string",
        "\tfirstDayOfWeek int",
        "\tminDaysInFirstWeek int",
        "\tdateFormats map[string]string",
        "\ttimeFormats map[string]string",
        "\tdateTimeFormats map[string]string",
        "\tdateTimeStyleJoinFormats map[string]string",
        "\tavailableFormats map[string]string",
        "\tappendItems map[string]string",
        "\tfieldNames map[string]string",
        "\ttimeZoneNames map[string]string",
        "\tmonths map[string]map[string]map[string]string",
        "\tquarters map[string]map[string]map[string]string",
        "\tweekdays map[string]map[string]map[string]string",
        "\teras map[string]map[string]string",
        "\tdayPeriods map[string]map[string]map[string]string",
        "\tdayPeriodRules string",
        "}",
        "",
        "var cldrDateTimeLocales = map[string]cldrDateTimeLocaleData{",
    ]
    for locale, locale_data in value["locales"].items():
        lines.append("\t" + go_literal(locale) + ": {")
        lines.extend(
            [
                "\t\trequestedLocale: " + go_literal(locale_data["requestedLocale"]) + ",",
                "\t\tsourceLocale: " + go_literal(locale_data["sourceLocale"]) + ",",
                "\t\tnumbersSourceLocale: " + go_literal(locale_data["numbersSourceLocale"]) + ",",
                "\t\tcalendar: " + go_literal(locale_data["calendar"]) + ",",
                "\t\tnumberingSystem: " + go_literal(locale_data["numberingSystem"]) + ",",
                "\t\tnumberingSystemDigits: " + go_literal(locale_data.get("numberingSystemDigits")) + ",",
                "\t\tdecimalSeparator: " + go_literal(locale_data["decimalSeparator"]) + ",",
                "\t\tallowedHourFormats: " + go_literal(locale_data["allowedHourFormats"]) + ",",
                "\t\tfirstDayOfWeek: " + str(locale_data["firstDayOfWeek"]) + ",",
                "\t\tminDaysInFirstWeek: " + str(locale_data["minDaysInFirstWeek"]) + ",",
                "\t\tdateFormats: " + go_string_map(locale_data["dateFormats"]) + ",",
                "\t\ttimeFormats: " + go_string_map(locale_data["timeFormats"]) + ",",
                "\t\tdateTimeFormats: " + go_string_map(locale_data["dateTimeFormats"]) + ",",
                "\t\tdateTimeStyleJoinFormats: " + go_string_map(locale_data["dateTimeStyleJoinFormats"]) + ",",
                "\t\tavailableFormats: " + go_string_map(locale_data["availableFormats"]) + ",",
                "\t\tappendItems: " + go_string_map(locale_data["appendItems"]) + ",",
                "\t\tfieldNames: " + go_string_map(locale_data["fieldNames"]) + ",",
                "\t\ttimeZoneNames: " + go_string_map(locale_data["timeZoneNames"]) + ",",
                "\t\tmonths: " + go_nested_string_map_3(locale_data["months"]) + ",",
                "\t\tquarters: " + go_nested_string_map_3(locale_data["quarters"]) + ",",
                "\t\tweekdays: " + go_nested_string_map_3(locale_data["weekdays"]) + ",",
                "\t\teras: " + go_nested_string_map_2(locale_data["eras"]) + ",",
                "\t\tdayPeriods: " + go_nested_string_map_3(locale_data["dayPeriods"]) + ",",
                "\t\tdayPeriodRules: " + go_literal(locale_data["dayPeriodRules"]) + ",",
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
        "// Generated from Unicode CLDR by mf2/cldr/generator/generate_datetime_data.py; "
        "do not edit by hand.\n\n"
        "const CLDR_DATE_TIME_DATA = "
        + php_literal(value)
        + ";\n\n"
        "function cldr_date_time_data(): array\n"
        "{\n"
        "    return CLDR_DATE_TIME_DATA;\n"
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


def go_nested_string_map_2(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "map[string]map[string]string{}"
    entries = [
        go_literal(key) + ": " + go_string_map(value)
        for key, value in values.items()
    ]
    return "map[string]map[string]string{" + ", ".join(entries) + "}"


def go_nested_string_map_3(values: dict[str, dict[str, dict[str, str]]]) -> str:
    if not values:
        return "map[string]map[string]map[string]string{}"
    entries = [
        go_literal(key) + ": " + go_nested_string_map_2(value)
        for key, value in values.items()
    ]
    return "map[string]map[string]map[string]string{" + ", ".join(entries) + "}"


def swift_string_map(values: dict[str, str]) -> str:
    if not values:
        return "[:]"
    entries = [
        swift_literal(key) + ": " + swift_literal(value)
        for key, value in values.items()
    ]
    return "[" + ", ".join(entries) + "]"


def swift_nested_string_map_2(values: dict[str, dict[str, str]]) -> str:
    if not values:
        return "[:]"
    entries = [
        swift_literal(key) + ": " + swift_string_map(value)
        for key, value in values.items()
    ]
    return "[" + ", ".join(entries) + "]"


def swift_nested_string_map_3(values: dict[str, dict[str, dict[str, str]]]) -> str:
    if not values:
        return "[:]"
    entries = [
        swift_literal(key) + ": " + swift_nested_string_map_2(value)
        for key, value in values.items()
    ]
    return "[" + ", ".join(entries) + "]"


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
