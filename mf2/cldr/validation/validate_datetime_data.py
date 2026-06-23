#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


STYLES = ("full", "long", "medium", "short")
SUPPORTED_SKELETON_FIELDS = frozenset("GyYwWQqMLdEecabBhHkKjJmsSAzZOvVXx")
SUPPORTED_PATTERN_FIELDS = frozenset("GyYwWQqMLdEecabBhHkKmsSAzZOvVXx")
REQUIRED_LOCALE_FIELDS = (
    "requestedLocale",
    "sourceLocale",
    "numbersSourceLocale",
    "calendar",
    "numberingSystem",
    "numberingSystemDigits",
    "decimalSeparator",
    "allowedHourFormats",
    "firstDayOfWeek",
    "minDaysInFirstWeek",
    "dateFormats",
    "timeFormats",
    "dateTimeFormats",
    "availableFormats",
    "appendItems",
    "fieldNames",
    "timeZoneNames",
    "months",
    "quarters",
    "weekdays",
    "eras",
    "dayPeriods",
    "dayPeriodRules",
)


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        raise SystemExit("Usage: validate_datetime_data.py path/to/date_time_data.json")
    path = Path(args[0])
    data = json.loads(path.read_text(encoding="utf-8"))
    validate(data)
    print(f"Validated experimental CLDR date/time data locales={len(data['locales'])}")
    return 0


def validate(data: dict[str, Any]) -> None:
    metadata = require_dict(data, "metadata")
    require_true(metadata, "experimental")
    require_true(metadata, "dropWithoutMigration")
    if metadata.get("calendar") != "gregorian":
        raise ValueError("metadata.calendar must be gregorian")

    locales = require_dict(data, "locales")
    if not locales:
        raise ValueError("locales must not be empty")
    for locale, locale_data in locales.items():
        validate_locale_key(locale)
        validate_locale_data(locale, locale_data)


def validate_locale_data(locale: str, locale_data: Any) -> None:
    if not isinstance(locale_data, dict):
        raise ValueError(f"{locale} must be an object")
    for field in REQUIRED_LOCALE_FIELDS:
        if field not in locale_data:
            raise ValueError(f"{locale} missing {field}")
    if locale_data["requestedLocale"] != locale:
        raise ValueError(f"{locale} requestedLocale mismatch")
    if locale_data["calendar"] != "gregorian":
        raise ValueError(f"{locale} calendar must be gregorian")

    for field in ("sourceLocale", "numbersSourceLocale", "numberingSystem"):
        if not isinstance(locale_data[field], str) or not locale_data[field]:
            raise ValueError(f"{locale} has invalid {field}")
    if (
        locale_data["numberingSystemDigits"] is not None
        and (
            not isinstance(locale_data["numberingSystemDigits"], str)
            or len(locale_data["numberingSystemDigits"]) != 10
        )
    ):
        raise ValueError(f"{locale} has invalid numberingSystemDigits")
    if not isinstance(locale_data["decimalSeparator"], str) or not locale_data["decimalSeparator"]:
        raise ValueError(f"{locale} has invalid decimalSeparator")
    validate_allowed_hour_formats(locale, require_string(locale_data, "allowedHourFormats"))
    first_day = locale_data["firstDayOfWeek"]
    if not isinstance(first_day, int) or first_day < 0 or first_day > 6:
        raise ValueError(f"{locale} has invalid firstDayOfWeek")
    min_days = locale_data["minDaysInFirstWeek"]
    if not isinstance(min_days, int) or min_days < 1 or min_days > 7:
        raise ValueError(f"{locale} has invalid minDaysInFirstWeek")
    for field in ("dateFormats", "timeFormats", "dateTimeFormats"):
        validate_style_map(locale, field, require_dict(locale_data, field))
    validate_available_formats(locale, require_dict(locale_data, "availableFormats"))
    validate_append_items(locale, require_dict(locale_data, "appendItems"))
    validate_field_names(locale, require_dict(locale_data, "fieldNames"))
    validate_time_zone_names(locale, require_dict(locale_data, "timeZoneNames"))

    months = require_dict(require_dict(require_dict(locale_data, "months"), "format"), "wide")
    for month in [str(index) for index in range(1, 13)]:
        if not isinstance(months.get(month), str) or not months[month]:
            raise ValueError(f"{locale} missing wide month {month}")

    quarters = require_dict(require_dict(require_dict(locale_data, "quarters"), "format"), "wide")
    for quarter in [str(index) for index in range(1, 5)]:
        if not isinstance(quarters.get(quarter), str) or not quarters[quarter]:
            raise ValueError(f"{locale} missing wide quarter {quarter}")

    weekdays = require_dict(require_dict(require_dict(locale_data, "weekdays"), "format"), "wide")
    for day in ("sun", "mon", "tue", "wed", "thu", "fri", "sat"):
        if not isinstance(weekdays.get(day), str) or not weekdays[day]:
            raise ValueError(f"{locale} missing wide weekday {day}")

    eras = require_dict(locale_data, "eras")
    if not require_dict(eras, "abbreviated"):
        raise ValueError(f"{locale} missing abbreviated eras")

    day_periods = require_dict(require_dict(require_dict(locale_data, "dayPeriods"), "format"), "wide")
    if "am" not in day_periods or "pm" not in day_periods:
        raise ValueError(f"{locale} missing wide am/pm day periods")
    validate_day_period_rules(locale, require_string(locale_data, "dayPeriodRules"), day_periods)


def validate_style_map(locale: str, field: str, value: dict[str, Any]) -> None:
    for style in STYLES:
        if not isinstance(value.get(style), str) or not value[style]:
            raise ValueError(f"{locale} missing {field}.{style}")


def validate_available_formats(locale: str, value: dict[str, Any]) -> None:
    for skeleton in ("yMMMd", "yMd", "Hm", "hm", "E", "yQQQ", "yQQQQ", "yw", "MMMMW"):
        if not isinstance(value.get(skeleton), str) or not value[skeleton]:
            raise ValueError(f"{locale} missing availableFormats.{skeleton}")
    for skeleton, pattern in value.items():
        if not isinstance(skeleton, str) or not skeleton.isascii() or not skeleton.isalpha():
            raise ValueError(f"{locale} has invalid availableFormats skeleton {skeleton!r}")
        if not isinstance(pattern, str) or not pattern:
            raise ValueError(f"{locale} has invalid availableFormats.{skeleton}")
        unsupported_skeleton = set(skeleton) - SUPPORTED_SKELETON_FIELDS
        if unsupported_skeleton:
            fields = "".join(sorted(unsupported_skeleton))
            raise ValueError(f"{locale} availableFormats.{skeleton} has unsupported skeleton fields {fields}")
        unsupported_pattern = set(pattern_fields(pattern)) - SUPPORTED_PATTERN_FIELDS
        if unsupported_pattern:
            fields = "".join(sorted(unsupported_pattern))
            raise ValueError(f"{locale} availableFormats.{skeleton} has unsupported pattern fields {fields}")


def validate_allowed_hour_formats(locale: str, value: str) -> None:
    tokens = value.split()
    if not tokens:
        raise ValueError(f"{locale} missing allowedHourFormats tokens")
    for token in tokens:
        if token not in {"h", "H", "K", "k", "hb", "hB"}:
            raise ValueError(f"{locale} has invalid allowedHourFormats token {token!r}")


def validate_append_items(locale: str, value: dict[str, Any]) -> None:
    for key, pattern in value.items():
        if not isinstance(key, str) or not key:
            raise ValueError(f"{locale} has invalid appendItems key {key!r}")
        if not isinstance(pattern, str) or "{0}" not in pattern or "{1}" not in pattern:
            raise ValueError(f"{locale} has invalid appendItems.{key}")


def validate_field_names(locale: str, value: dict[str, Any]) -> None:
    for field in ("Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second"):
        if not isinstance(value.get(field), str) or not value[field]:
            raise ValueError(f"{locale} missing fieldNames.{field}")


def validate_day_period_rules(locale: str, value: str, day_periods: dict[str, Any]) -> None:
    if not value:
        raise ValueError(f"{locale} missing dayPeriodRules")
    seen_range = False
    for raw_rule in value.split(";"):
        period, separator, span = raw_rule.partition("=")
        if separator != "=" or not period or not span:
            raise ValueError(f"{locale} has invalid dayPeriodRules item {raw_rule!r}")
        if not isinstance(day_periods.get(period), str) or not day_periods[period]:
            raise ValueError(f"{locale} dayPeriodRules references missing period {period}")
        if "-" in span:
            start_text, end_text = span.split("-", 1)
            start = int(start_text)
            end = int(end_text)
            if start == end:
                raise ValueError(f"{locale} dayPeriodRules has empty range {raw_rule!r}")
            seen_range = True
        else:
            start = int(span)
            end = start
        if not 0 <= start <= 1440 or not 0 <= end <= 1440:
            raise ValueError(f"{locale} dayPeriodRules has out-of-range minute {raw_rule!r}")
    if not seen_range:
        raise ValueError(f"{locale} dayPeriodRules must include at least one range")


def validate_time_zone_names(locale: str, value: dict[str, Any]) -> None:
    for field in ("gmtFormat", "gmtZeroFormat", "utcShort", "utcLong"):
        if not isinstance(value.get(field), str) or not value[field]:
            raise ValueError(f"{locale} missing timeZoneNames.{field}")
    if "{0}" not in value["gmtFormat"]:
        raise ValueError(f"{locale} timeZoneNames.gmtFormat must contain {{0}}")


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


def require_dict(data: dict[str, Any], key: str) -> dict[str, Any]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"{key} must be an object")
    return value


def require_string(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str):
        raise ValueError(f"{key} must be a string")
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


if __name__ == "__main__":
    raise SystemExit(main())
