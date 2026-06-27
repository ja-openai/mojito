from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
import re
from typing import Any

from ._cldr_date_time_data import DATE_TIME_DATA
from ._locale_key import locale_lookup_chain
from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry

__all__ = [
    "date_time_core_function_registry",
    "format_date_core",
    "format_date_core_to_parts",
    "format_date_time_core",
    "format_date_time_core_to_parts",
    "format_time_core",
    "format_time_core_to_parts",
]

_DEFAULT_LOCALE = "en-US"
_UTC = "UTC"
_MIN_TIMESTAMP_MS = -62_135_596_800_000
_MAX_TIMESTAMP_MS = 253_402_300_799_999
_MAX_LOCALE_LENGTH = 256
_MAX_OPTION_LENGTH = 256
_MAX_OPERAND_LENGTH = 256
_MAX_SKELETON_FIELD_WIDTH = 32
_MAX_SKELETON_LENGTH = 256
_STYLE_VALUES = {"full", "long", "medium", "short"}
_SEMANTIC_LENGTH_VALUES = {"full", "long", "medium", "short"}
_SEMANTIC_SKELETON_PREFIX = "semantic:"
_SEMANTIC_FIELD_ORDER = (
    "era",
    "year",
    "quarter",
    "month",
    "weekofmonth",
    "day",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
    "weekday",
    "weekofyear",
    "dayperiod",
    "hour",
    "minute",
    "second",
    "fractionalsecond",
    "millisecondsinday",
    "time",
    "zone",
)
_SEMANTIC_DATE_FIELD_ORDER = (
    "era",
    "year",
    "quarter",
    "month",
    "weekofmonth",
    "day",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
    "weekday",
    "weekofyear",
)
_SEMANTIC_TIME_FIELD_ORDER = ("hour", "minute", "second", "fractionalsecond", "millisecondsinday")
_SEMANTIC_OPTION_KEYS = {
    "fields",
    "length",
    "alignment",
    "yearstyle",
    "erastyle",
    "monthstyle",
    "quarterstyle",
    "daystyle",
    "weekdaystyle",
    "dayperiodstyle",
    "hourstyle",
    "minutestyle",
    "secondstyle",
    "timeprecision",
    "timestyle",
    "fractionalsecond",
    "hourcycle",
    "zonestyle",
}
_SEMANTIC_DIRECT_STYLE_OPTION_KEYS = {"fields", "length", "timestyle"}
_SEMANTIC_STYLE_OPTION_KEYS = {
    "yearstyle",
    "erastyle",
    "monthstyle",
    "quarterstyle",
    "daystyle",
    "weekdaystyle",
    "dayperiodstyle",
    "hourstyle",
    "minutestyle",
    "secondstyle",
}
_SEMANTIC_FIELD_STYLE_OPTION_ALIASES = {
    "era": "erastyle",
    "year": "yearstyle",
    "month": "monthstyle",
    "quarter": "quarterstyle",
    "day": "daystyle",
    "weekday": "weekdaystyle",
    "dayperiod": "dayperiodstyle",
    "hour": "hourstyle",
    "minute": "minutestyle",
    "second": "secondstyle",
}
_SEMANTIC_DATE_STYLE_VALUES = {"auto", "numeric", "2-digit", "short", "long", "narrow"}
_SEMANTIC_NUMERIC_STYLE_VALUES = {"auto", "numeric", "2-digit"}
_SEMANTIC_TEXT_STYLE_VALUES = {"auto", "short", "long", "narrow"}
_SEMANTIC_DATE_FIELD_SETS = {
    "day",
    "weekday",
    "day,weekday",
    "month,day",
    "month,day,weekday",
    "era,year,month,day",
    "era,year,month,day,weekday",
    "year,month,day",
    "year,month,day,weekday",
}
_SEMANTIC_CALENDAR_PERIOD_FIELD_SETS = {
    "era",
    "year",
    "quarter",
    "month",
    "era,year",
    "era,year,quarter",
    "era,year,month",
    "era,year,weekofyear",
    "era,year,month,weekofmonth",
    "year,quarter",
    "year,month",
    "year,weekofyear",
    "month,weekofmonth",
    "year,month,weekofmonth",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
}
_SEMANTIC_TIME_FIELD_SETS = {
    "hour",
    "minute",
    "second",
    "millisecondsinday",
    "hour,minute",
    "hour,minute,second",
    "hour,minute,second,fractionalsecond",
    "minute,second",
    "minute,second,fractionalsecond",
    "second,fractionalsecond",
}
_SKELETON_FIELD_ORDER = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx"
_SKELETON_TIME_FIELDS = set("abBhHkKJmsSAzZOvVXx")
_SKELETON_HOUR_FIELDS = {"h", "H", "k", "K"}
_WEEKDAY_KEYS = ("sun", "mon", "tue", "wed", "thu", "fri", "sat")
_DIGIT_ZERO = ord("0")
_ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_ISO_DATE_TIME_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$"
)


def format_date_core(
    value: Any,
    *,
    locale: str = _DEFAULT_LOCALE,
    style: str = "medium",
    dateStyle: str | None = None,
    length: str | None = None,
    skeleton: str | None = None,
    hourCycle: str | None = None,
    timeZone: str = _UTC,
    calendar: str | None = None,
) -> str:
    locale = _coerce_string_option(locale, "locale")
    _validate_locale_length(locale)
    locale_data = _resolve_numbering_system_data(_resolve_locale_data(locale), locale)
    _validate_calendar(_first_non_empty(calendar, _locale_unicode_extension(locale, "ca")))
    explicit_hour_cycle = _first_non_empty(hourCycle) is not None
    effective_hour_cycle = _validate_hour_cycle(_first_non_empty(hourCycle, _locale_unicode_extension(locale, "hc")))
    date_value = _parse_datetime(value).astimezone(_parse_time_zone(timeZone))
    if skeleton is not None:
        return _format_skeleton(skeleton, date_value, locale_data, effective_hour_cycle, explicit_hour_cycle)
    effective_style = _style_option(_first_non_empty(dateStyle, length, style), "dateStyle")
    return _format_pattern(locale_data["dateFormats"][effective_style], date_value, locale_data)


def format_time_core(
    value: Any,
    *,
    locale: str = _DEFAULT_LOCALE,
    style: str = "medium",
    timeStyle: str | None = None,
    precision: str | None = None,
    timePrecision: str | None = None,
    skeleton: str | None = None,
    hourCycle: str | None = None,
    timeZone: str = _UTC,
    calendar: str | None = None,
) -> str:
    locale = _coerce_string_option(locale, "locale")
    _validate_locale_length(locale)
    locale_data = _resolve_numbering_system_data(_resolve_locale_data(locale), locale)
    _validate_calendar(_first_non_empty(calendar, _locale_unicode_extension(locale, "ca")))
    explicit_hour_cycle = _first_non_empty(hourCycle) is not None
    effective_hour_cycle = _validate_hour_cycle(_first_non_empty(hourCycle, _locale_unicode_extension(locale, "hc")))
    date_value = _parse_datetime(value).astimezone(_parse_time_zone(timeZone))
    if skeleton is not None:
        return _format_skeleton(skeleton, date_value, locale_data, effective_hour_cycle, explicit_hour_cycle)
    effective_style = _time_style_option(timeStyle, timePrecision, precision, style, "medium")
    return _format_time_style_pattern(
        locale_data["timeFormats"][effective_style],
        date_value,
        locale_data,
        effective_hour_cycle,
        explicit_hour_cycle,
    )


def format_date_time_core(
    value: Any,
    *,
    locale: str = _DEFAULT_LOCALE,
    style: str = "medium",
    dateStyle: str | None = None,
    timeStyle: str | None = None,
    length: str | None = None,
    precision: str | None = None,
    dateLength: str | None = None,
    timePrecision: str | None = None,
    skeleton: str | None = None,
    hourCycle: str | None = None,
    timeZone: str = _UTC,
    calendar: str | None = None,
) -> str:
    locale = _coerce_string_option(locale, "locale")
    _validate_locale_length(locale)
    locale_data = _resolve_numbering_system_data(_resolve_locale_data(locale), locale)
    _validate_calendar(_first_non_empty(calendar, _locale_unicode_extension(locale, "ca")))
    explicit_hour_cycle = _first_non_empty(hourCycle) is not None
    effective_hour_cycle = _validate_hour_cycle(_first_non_empty(hourCycle, _locale_unicode_extension(locale, "hc")))
    date_value = _parse_datetime(value).astimezone(_parse_time_zone(timeZone))
    if skeleton is not None:
        return _format_skeleton(skeleton, date_value, locale_data, effective_hour_cycle, explicit_hour_cycle)
    effective_date_style = _style_option(_first_non_empty(dateStyle, dateLength, length, style), "dateStyle")
    effective_time_style = _time_style_option(timeStyle, timePrecision, precision, style, "medium")
    date_part = _format_pattern(
        locale_data["dateFormats"][effective_date_style],
        date_value,
        locale_data,
    )
    time_part = _format_time_style_pattern(
        locale_data["timeFormats"][effective_time_style],
        date_value,
        locale_data,
        effective_hour_cycle,
        explicit_hour_cycle,
    )
    return _date_time_style_join_pattern(locale_data, effective_date_style).replace("{1}", date_part).replace("{0}", time_part)


def format_date_core_to_parts(value: Any, **options: Any) -> list[dict[str, str]]:
    return [{"type": "text", "value": format_date_core(value, **options)}]


def format_time_core_to_parts(value: Any, **options: Any) -> list[dict[str, str]]:
    return [{"type": "text", "value": format_time_core(value, **options)}]


def format_date_time_core_to_parts(value: Any, **options: Any) -> list[dict[str, str]]:
    return [{"type": "text", "value": format_date_time_core(value, **options)}]


def date_time_core_function_registry() -> FunctionRegistry:
    return (
        FunctionRegistry.portable()
        .with_function("date", _format_call_date)
        .with_function("time", _format_call_time)
        .with_function("datetime", _format_call_date_time)
    )


def _format_call_date(call: FunctionCall) -> str:
    return format_date_core(
        _call_source_value(call),
        locale=call.locale,
        style=_call_non_empty_option(call, "style", "medium"),
        dateStyle=_call_non_empty_option(call, "dateStyle"),
        length=_call_non_empty_option(call, "length"),
        skeleton=_call_non_empty_option(call, "skeleton"),
        hourCycle=_call_non_empty_option(call, "hourCycle"),
        timeZone=_call_non_empty_option(call, "timeZone", _UTC),
        calendar=_call_non_empty_option(call, "calendar"),
    )


def _format_call_time(call: FunctionCall) -> str:
    return format_time_core(
        _call_source_value(call),
        locale=call.locale,
        style=_call_non_empty_option(call, "style", "medium"),
        timeStyle=_call_non_empty_option(call, "timeStyle"),
        precision=_call_non_empty_option(call, "precision"),
        skeleton=_call_non_empty_option(call, "skeleton"),
        hourCycle=_call_non_empty_option(call, "hourCycle"),
        timeZone=_call_non_empty_option(call, "timeZone", _UTC),
        calendar=_call_non_empty_option(call, "calendar"),
    )


def _format_call_date_time(call: FunctionCall) -> str:
    return format_date_time_core(
        _call_source_value(call),
        locale=call.locale,
        style=_call_non_empty_option(call, "style", "medium"),
        dateStyle=_call_non_empty_option(call, "dateStyle"),
        timeStyle=_call_non_empty_option(call, "timeStyle"),
        dateLength=_call_non_empty_option(call, "dateLength"),
        timePrecision=_call_non_empty_option(call, "timePrecision"),
        skeleton=_call_non_empty_option(call, "skeleton"),
        hourCycle=_call_non_empty_option(call, "hourCycle"),
        timeZone=_call_non_empty_option(call, "timeZone", _UTC),
        calendar=_call_non_empty_option(call, "calendar"),
    )


def _call_non_empty_option(call: FunctionCall, name: str, fallback: str | None = None) -> str | None:
    value = call.option_value(name, fallback)
    if value == "":
        raise MF2Error("bad-option", f"{name} must not be empty.")
    return value


def _call_source_value(call: FunctionCall) -> Any:
    if call.inherited_source is not None:
        return call.inherited_source.value
    return call.raw_value if call.raw_value is not None else call.value


def _resolve_locale_data(locale: str) -> dict[str, Any]:
    locales = DATE_TIME_DATA["locales"]
    for candidate in locale_lookup_chain(locale or _DEFAULT_LOCALE):
        exact = locales.get(candidate)
        if exact is not None:
            return exact
        for locale_data in locales.values():
            if (
                locale_data.get("sourceLocale") == candidate
                or locale_data.get("numbersSourceLocale") == candidate
            ):
                return locale_data
    return locales[_DEFAULT_LOCALE]


def _coerce_string_option(value: Any, name: str) -> str:
    try:
        text = str(value)
    except Exception as error:
        raise MF2Error("bad-option", f"{name} must be coercible to a string.") from error
    if len(text) > _MAX_OPTION_LENGTH:
        raise MF2Error("bad-option", f"{name} must not exceed 256 characters.")
    return text


def _validate_locale_length(locale: str) -> None:
    if len(locale) > _MAX_LOCALE_LENGTH:
        raise MF2Error("bad-option", "locale must not exceed 256 characters.")


def _first_non_empty(*values: Any) -> Any:
    for value in values:
        if value is None:
            continue
        if isinstance(value, str) and value == "":
            continue
        return value
    return None


def _locale_unicode_extension(locale: str, key: str) -> str | None:
    parts = [
        part.lower()
        for part in str(locale or "").strip().replace("_", "-").split("-")
        if part
    ]
    try:
        index = parts.index("u") + 1
    except ValueError:
        return None
    while index < len(parts):
        part = parts[index]
        if len(part) == 1:
            return None
        if len(part) != 2:
            index += 1
            continue
        end = index + 1
        while end < len(parts) and len(parts[end]) > 2:
            end += 1
        if part == key:
            return parts[index + 1] if end > index + 1 else None
        index = end
    return None


def _resolve_numbering_system_data(locale_data: dict[str, Any], locale: str) -> dict[str, Any]:
    numbering_system = _locale_unicode_extension(locale, "nu")
    if numbering_system in (None, ""):
        return locale_data
    digits = _numbering_system_digits(numbering_system)
    if digits is None:
        raise MF2Error("bad-option", "Date/time core does not include data for the requested numbering system.")
    effective = dict(locale_data)
    effective["numberingSystemDigits"] = digits
    return effective


def _numbering_system_digits(numbering_system: str) -> str | None:
    if numbering_system == "latn":
        return "0123456789"
    for locale_data in DATE_TIME_DATA["locales"].values():
        if locale_data.get("numberingSystem") == numbering_system and locale_data.get("numberingSystemDigits") is not None:
            return str(locale_data["numberingSystemDigits"])
    return None


def _validate_calendar(value: str | None) -> None:
    if value is None or value == "":
        return
    text = _coerce_string_option(value, "calendar")
    if text not in {"gregorian", "gregory"}:
        raise MF2Error("bad-option", "Date/time core currently supports only the gregorian/gregory calendar.")


def _validate_hour_cycle(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    text = _coerce_string_option(value, "hourCycle")
    if text in {"h11", "h12", "h23", "h24"}:
        return text
    raise MF2Error("bad-option", "hourCycle must be one of h11, h12, h23, h24.")


def _parse_time_zone(value: str | None) -> timezone:
    if value is None:
        return timezone.utc
    text = _coerce_string_option(value, "timeZone").strip()
    if text in {"", _UTC, "Etc/UTC", "Z", "GMT", "Etc/GMT"}:
        return timezone.utc
    etc_gmt_offset = _parse_etc_gmt_offset_minutes(text)
    if etc_gmt_offset is not None:
        return timezone(timedelta(minutes=etc_gmt_offset))
    match = re.fullmatch(r"(?:(?:UTC|GMT)([+-].+)|([+-].+))", text)
    offset_text = (match.group(1) or match.group(2)) if match else None
    offset_minutes = _parse_offset_minutes(offset_text) if offset_text is not None else None
    if offset_minutes is None:
        raise MF2Error("bad-option", "Date/time core supports only UTC or fixed-offset time zones.")
    return timezone(timedelta(minutes=offset_minutes))


def _parse_etc_gmt_offset_minutes(value: str) -> int | None:
    match = re.fullmatch(r"Etc/GMT([+-]\d{1,2})", value)
    if match is None:
        return None
    hours = int(match.group(1))
    if abs(hours) > 14:
        return None
    return -hours * 60


def _parse_offset_minutes(value: str) -> int | None:
    match = re.fullmatch(r"([+-])(\d{1,2})(?::?(\d{2}))?", value)
    if match is None:
        return None
    hours = int(match.group(2))
    minutes = int(match.group(3) or "0")
    if hours > 18 or minutes > 59 or (hours == 18 and minutes != 0):
        return None
    total = hours * 60 + minutes
    return -total if match.group(1) == "-" else total


def _style_option(value: str, name: str) -> str:
    text = _coerce_string_option(value, name)
    if text not in _STYLE_VALUES:
        raise MF2Error("bad-option", f"{name} must be one of full, long, medium, short.")
    return text


def _time_style_option(
    time_style: str | None,
    time_precision: str | None,
    precision: str | None,
    style: str,
    fallback: str,
) -> str:
    effective_time_style = _first_non_empty(time_style)
    if effective_time_style is not None:
        return _style_option(effective_time_style, "timeStyle")
    effective_time_precision = _first_non_empty(time_precision)
    if effective_time_precision is not None:
        return _time_precision_style_option(effective_time_precision, "timePrecision")
    effective_precision = _first_non_empty(precision)
    if effective_precision is not None:
        return _time_precision_style_option(effective_precision, "precision")
    return _style_option(_first_non_empty(style, fallback), "timeStyle")


def _time_precision_style_option(value: str, name: str) -> str:
    text = _coerce_string_option(value, name)
    return "medium" if text == "second" else _style_option(text, name)


def _parse_datetime(value: Any) -> datetime:
    if isinstance(value, datetime):
        try:
            return _to_utc(value)
        except (OverflowError, OSError, ValueError) as error:
            raise MF2Error(
                "bad-operand",
                "Date/time core requires a valid host date/time value or ISO date string.",
            ) from error
    if isinstance(value, date):
        return datetime.combine(value, time(), tzinfo=timezone.utc)
    if isinstance(value, time):
        return datetime.combine(date(1970, 1, 1), value, tzinfo=timezone.utc)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        try:
            timestamp = float(value)
            if timestamp < _MIN_TIMESTAMP_MS or timestamp > _MAX_TIMESTAMP_MS:
                raise ValueError("timestamp is outside the supported range")
            return datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=timestamp)
        except (OverflowError, OSError, ValueError) as error:
            raise MF2Error(
                "bad-operand",
                "Date/time core requires a valid host date/time value or ISO date string.",
            ) from error
    if not isinstance(value, str):
        raise MF2Error(
            "bad-operand",
            "Date/time core requires a valid host date/time value or ISO date string.",
        )
    text = value.strip()
    if len(text) > _MAX_OPERAND_LENGTH:
        raise MF2Error(
            "bad-operand",
            "Date/time core requires a valid host date/time value or ISO date string.",
        )
    try:
        if _ISO_DATE_RE.fullmatch(text):
            return datetime.combine(date.fromisoformat(text), time(), tzinfo=timezone.utc)
        match = _ISO_DATE_TIME_RE.fullmatch(text)
        if match is None:
            raise ValueError("invalid ISO date-time")
        date.fromisoformat(match.group(1))
        hour = int(match.group(2))
        minute = int(match.group(3))
        second = int(match.group(4) or "0")
        zone = match.group(6) or ""
        if hour > 23 or minute > 59 or second > 59:
            raise ValueError("invalid ISO date-time")
        if zone not in {"", "Z"} and _parse_offset_minutes(zone) is None:
            raise ValueError("invalid ISO date-time offset")
        return _to_utc(datetime.fromisoformat(text.replace("Z", "+00:00")))
    except (OverflowError, OSError, ValueError) as error:
        raise MF2Error(
            "bad-operand",
            "Date/time core requires a valid host date/time value or ISO date string.",
        ) from error


def _to_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _format_skeleton(
    skeleton: str,
    value: datetime,
    locale_data: dict[str, Any],
    hour_cycle: str | None,
    preserve_same_family_hour_cycle: bool,
) -> str:
    text = _coerce_string_option(skeleton, "skeleton")
    if len(text) > _MAX_SKELETON_LENGTH:
        raise MF2Error("bad-option", "Date/time skeleton is too large.")
    semantic_style = _format_semantic_style_skeleton(
        text,
        value,
        locale_data,
        hour_cycle,
        preserve_same_family_hour_cycle,
    )
    if semantic_style is not None:
        return semantic_style
    canonical = _canonical_skeleton(text, locale_data, hour_cycle, value)
    suppress_day_period = _should_suppress_day_period(text)
    date_time_join_style = _skeleton_date_time_join_style(text)
    pattern = _skeleton_pattern(canonical, locale_data)
    if pattern is None:
        return _format_composed_skeleton(
            text,
            canonical,
            value,
            locale_data,
            suppress_day_period,
            date_time_join_style,
        )
    if suppress_day_period:
        pattern = _strip_day_period_pattern_fields(pattern)
    return _format_pattern(pattern, value, locale_data)


def _skeleton_date_time_join_style(skeleton: str) -> str:
    text = str(skeleton)
    if not text.startswith(_SEMANTIC_SKELETON_PREFIX):
        return "medium"
    options = _parse_semantic_skeleton_options(text[len(_SEMANTIC_SKELETON_PREFIX) :])
    return _semantic_option(options, "length", "medium", _SEMANTIC_LENGTH_VALUES)


def _format_semantic_style_skeleton(
    skeleton: str,
    value: datetime,
    locale_data: dict[str, Any],
    hour_cycle: str | None,
    preserve_same_family_hour_cycle: bool,
) -> str | None:
    text = str(skeleton)
    if not text.startswith(_SEMANTIC_SKELETON_PREFIX):
        return None
    options = _parse_semantic_skeleton_options(text[len(_SEMANTIC_SKELETON_PREFIX) :])
    fields = _parse_semantic_skeleton_fields(options)
    _validate_semantic_skeleton(fields, options)
    if any(key not in _SEMANTIC_DIRECT_STYLE_OPTION_KEYS for key in options):
        return None

    length = _semantic_option(options, "length", "medium", _SEMANTIC_LENGTH_VALUES)
    time_style = _semantic_option(options, "timestyle", "auto", {"auto", "short", "medium", "long", "full"})
    date_key = _semantic_field_set_key(fields, _SEMANTIC_DATE_FIELD_ORDER)
    expected_date_key = "year,month,day,weekday" if length == "full" else "year,month,day"
    has_date = bool(date_key)
    has_time = "time" in fields
    has_zone = "zone" in fields
    if _semantic_field_set_key(fields, _SEMANTIC_TIME_FIELD_ORDER):
        return None
    if has_date and date_key != expected_date_key:
        return None
    if has_time and "timestyle" not in options:
        return None
    if not has_time and (has_zone or time_style != "auto"):
        return None
    if has_time and has_zone != (time_style in {"long", "full"}):
        return None
    expected_field_count = (len(expected_date_key.split(",")) if has_date else 0) + (1 if has_time else 0) + (1 if has_zone else 0)
    if len(fields) != expected_field_count:
        return None

    if has_date and has_time:
        date_part = _format_pattern(locale_data["dateFormats"][length], value, locale_data)
        time_part = _format_time_style_pattern(
            locale_data["timeFormats"][time_style],
            value,
            locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        )
        join_pattern = _date_time_style_join_pattern(locale_data, length)
        return join_pattern.replace("{1}", date_part).replace("{0}", time_part)
    if has_date:
        return _format_pattern(locale_data["dateFormats"][length], value, locale_data)
    if has_time:
        return _format_time_style_pattern(
            locale_data["timeFormats"][time_style],
            value,
            locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        )
    return None


def _format_time_style_pattern(
    pattern: str,
    value: datetime,
    locale_data: dict[str, Any],
    hour_cycle: str | None,
    preserve_same_family_hour_cycle: bool,
) -> str:
    if hour_cycle is None:
        return _format_pattern(pattern, value, locale_data)
    hour_symbol = _preferred_hour_symbol(locale_data, hour_cycle)
    pattern_hour_symbol = _time_style_pattern_hour_symbol(pattern)
    if (
        preserve_same_family_hour_cycle
        and pattern_hour_symbol is not None
        and _is_hour12_field(pattern_hour_symbol) == _is_hour12_field(hour_symbol)
    ):
        return _format_pattern(_replace_time_style_pattern_hour_symbol(pattern, hour_symbol), value, locale_data)
    skeleton = _time_style_pattern_skeleton(pattern, locale_data, hour_cycle)
    if skeleton is None:
        return _format_pattern(pattern, value, locale_data)
    canonical = _canonical_standard_skeleton(skeleton, locale_data, None)
    matched = _skeleton_pattern(canonical, locale_data)
    return _format_pattern(matched or pattern, value, locale_data)


def _date_time_style_join_pattern(locale_data: dict[str, Any], style: str) -> str:
    return (
        locale_data.get("dateTimeStyleJoinFormats", {}).get(style)
        or locale_data["dateTimeFormats"].get(style)
        or locale_data["dateTimeFormats"].get("medium")
        or "{1} {0}"
    )


def _time_style_pattern_hour_symbol(pattern: str) -> str | None:
    index = 0
    while index < len(pattern):
        symbol = pattern[index]
        if symbol == "'":
            _quoted, index = _read_quoted_pattern(pattern, index)
        elif _is_ascii_letter(symbol):
            end = index + 1
            while end < len(pattern) and pattern[end] == symbol:
                end += 1
            if _is_hour_field(symbol):
                return symbol
            index = end
        else:
            index += 1
    return None


def _replace_time_style_pattern_hour_symbol(pattern: str, hour_symbol: str) -> str:
    output: list[str] = []
    index = 0
    while index < len(pattern):
        symbol = pattern[index]
        if symbol == "'":
            start = index
            _quoted, index = _read_quoted_pattern(pattern, index)
            output.append(pattern[start:index])
        elif _is_ascii_letter(symbol):
            end = index + 1
            while end < len(pattern) and pattern[end] == symbol:
                end += 1
            output.append(hour_symbol * (end - index) if _is_hour_field(symbol) else pattern[index:end])
            index = end
        else:
            output.append(symbol)
            index += 1
    return "".join(output)


def _time_style_pattern_skeleton(
    pattern: str,
    locale_data: dict[str, Any],
    hour_cycle: str,
) -> str | None:
    widths: dict[str, int] = {}
    hour_symbol = _preferred_hour_symbol(locale_data, hour_cycle)
    has_hour = False
    index = 0
    while index < len(pattern):
        symbol = pattern[index]
        if symbol == "'":
            _quoted, index = _read_quoted_pattern(pattern, index)
        elif _is_ascii_letter(symbol):
            end = index + 1
            while end < len(pattern) and pattern[end] == symbol:
                end += 1
            if _is_hour_field(symbol):
                _set_skeleton_width(widths, hour_symbol, end - index)
                has_hour = True
            elif not _is_day_period_field(symbol) and symbol in _SKELETON_TIME_FIELDS:
                _set_skeleton_width(widths, symbol, end - index)
            index = end
        else:
            index += 1
    if not has_hour:
        return None
    return "".join(
        symbol * widths[symbol]
        for symbol in _SKELETON_FIELD_ORDER
        if symbol in widths
    )


def _skeleton_pattern(canonical: str, locale_data: dict[str, Any]) -> str | None:
    pattern = _skeleton_pattern_without_append(canonical, locale_data)
    if pattern is not None:
        return pattern
    return None if _has_date_and_time_fields(canonical) else _appended_skeleton_pattern(canonical, locale_data)


def _skeleton_pattern_without_append(canonical: str, locale_data: dict[str, Any]) -> str | None:
    direct = locale_data.get("availableFormats", {}).get(canonical)
    if direct is not None:
        return direct
    requested_fields = _skeleton_field_set(canonical)
    best_candidate: str | None = None
    best_pattern: str | None = None
    best_distance: int | None = None
    for candidate, pattern in locale_data.get("availableFormats", {}).items():
        if _skeleton_field_set(candidate) != requested_fields:
            continue
        distance = _skeleton_distance(canonical, candidate)
        if best_distance is None or distance < best_distance or (distance == best_distance and candidate < best_candidate):
            best_candidate = candidate
            best_pattern = pattern
            best_distance = distance
    if best_pattern is None or best_candidate is None:
        return _synthetic_skeleton_pattern(canonical, locale_data)
    return _adjust_pattern_widths(best_pattern, canonical, best_candidate)


def _appended_skeleton_pattern(canonical: str, locale_data: dict[str, Any]) -> str | None:
    requested_fields = _skeleton_field_set(canonical)
    best_candidate: str | None = None
    best_pattern: str | None = None
    best_field_count = -1
    best_distance: int | None = None
    for candidate, pattern in locale_data.get("availableFormats", {}).items():
        candidate_fields = _skeleton_field_set(candidate)
        if not candidate_fields or candidate_fields == requested_fields:
            continue
        if not _field_set_contains(requested_fields, candidate_fields):
            continue
        field_count = len(candidate_fields)
        distance = _skeleton_distance(canonical, candidate)
        if (
            field_count > best_field_count
            or best_distance is None
            or (field_count == best_field_count and (distance < best_distance or (distance == best_distance and candidate < best_candidate)))
        ):
            best_candidate = candidate
            best_pattern = pattern
            best_field_count = field_count
            best_distance = distance
    if best_pattern is None or best_candidate is None:
        return None
    output = _adjust_pattern_widths(best_pattern, canonical, best_candidate)
    current_fields = set(_skeleton_field_set(best_candidate))
    for symbol, width in _skeleton_widths(canonical).items():
        field = _field_set_symbol(symbol)
        if field in current_fields:
            continue
        key = _append_item_key(symbol)
        field_skeleton = symbol * width
        field_pattern = _skeleton_pattern_without_append(field_skeleton, locale_data) or field_skeleton
        if key is None or field_pattern is None:
            return None
        output = _apply_append_item_pattern(
            _append_item_template(locale_data, key),
            output,
            field_pattern,
            locale_data.get("fieldNames", {}).get(key, key),
        )
        current_fields.add(field)
    return output


def _field_set_contains(container: str, subset: str) -> bool:
    return all(field in container for field in subset)


def _apply_append_item_pattern(template: str, base_pattern: str, field_pattern: str, field_name: str) -> str:
    return template.replace("{0}", base_pattern).replace("{1}", field_pattern).replace("{2}", _quote_pattern_literal(field_name))


def _quote_pattern_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _append_item_template(locale_data: dict[str, Any], key: str) -> str:
    return locale_data.get("appendItems", {}).get(key) or _default_append_item_template(key)


def _default_append_item_template(key: str) -> str:
    if key in {"Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second"}:
        return "{0} ({2}: {1})"
    return "{0} {1}"


def _has_date_and_time_fields(canonical: str) -> bool:
    date_skeleton, time_skeleton = _split_date_time_skeleton(canonical)
    return bool(date_skeleton and time_skeleton)


def _append_item_key(symbol: str) -> str | None:
    if symbol == "G":
        return "Era"
    if _is_year_field(symbol):
        return "Year"
    if _is_quarter_field(symbol):
        return "Quarter"
    if _is_month_field(symbol):
        return "Month"
    if symbol in {"w", "W"}:
        return "Week"
    if symbol in {"d", "D", "F", "g"}:
        return "Day"
    if _is_weekday_field(symbol):
        return "Day-Of-Week"
    if _is_hour_field(symbol):
        return "Hour"
    if symbol == "m":
        return "Minute"
    if symbol in {"s", "S", "A"}:
        return "Second"
    if _is_time_zone_field(symbol):
        return "Timezone"
    return None


def _synthetic_skeleton_pattern(canonical: str, locale_data: dict[str, Any]) -> str | None:
    widths = _skeleton_widths(canonical)
    if len(widths) == 1:
        symbol, width = next(iter(widths.items()))
        if symbol == "G":
            return symbol * width
        if _is_day_period_field(symbol):
            return symbol * width
        if _is_quarter_field(symbol):
            return symbol * width
        if _is_synthetic_numeric_field(symbol):
            return symbol * width
        if symbol == "S":
            return symbol * width
        if _is_time_zone_field(symbol):
            return symbol * width
    fractional_second = _synthetic_fractional_second_pattern(canonical, locale_data, widths)
    if fractional_second is not None:
        return fractional_second
    return None


def _synthetic_fractional_second_pattern(
    canonical: str,
    locale_data: dict[str, Any],
    widths: dict[str, int],
) -> str | None:
    fraction_width = widths.get("S")
    if fraction_width is None or "s" not in widths:
        return None
    base_skeleton = _skeleton_without_field(canonical, "S")
    base_pattern = _skeleton_pattern(base_skeleton, locale_data) or _synthetic_seconds_pattern(base_skeleton)
    if base_pattern is None:
        return None
    return _insert_fractional_second(base_pattern, fraction_width, locale_data.get("decimalSeparator", "."))


def _synthetic_seconds_pattern(canonical: str) -> str | None:
    widths = _skeleton_widths(canonical)
    if len(widths) == 1 and "s" in widths:
        return "s" * widths["s"]
    return None


def _skeleton_without_field(skeleton: str, removed_symbol: str) -> str:
    output: list[str] = []
    for symbol, count in _skeleton_fields(skeleton):
        if symbol != removed_symbol:
            output.append(symbol * count)
    return "".join(output)


def _insert_fractional_second(pattern: str, width: int, decimal_separator: str) -> str | None:
    output: list[str] = []
    in_quote = False
    index = 0
    while index < len(pattern):
        ch = pattern[index]
        if ch == "'":
            output.append(ch)
            if index + 1 < len(pattern) and pattern[index + 1] == "'":
                output.append("'")
                index += 2
            else:
                in_quote = not in_quote
                index += 1
        elif not in_quote and ch == "s":
            end = index + 1
            while end < len(pattern) and pattern[end] == ch:
                end += 1
            output.append(pattern[index:end])
            output.append(decimal_separator)
            output.append("S" * width)
            output.append(pattern[end:])
            return "".join(output)
        else:
            output.append(ch)
            index += 1
    return None


def _format_composed_skeleton(
    raw_skeleton: str,
    canonical: str,
    value: datetime,
    locale_data: dict[str, Any],
    suppress_day_period: bool,
    date_time_join_style: str,
) -> str:
    date_skeleton, time_skeleton = _split_date_time_skeleton(canonical)
    if not date_skeleton or not time_skeleton:
        raise MF2Error("bad-option", f"Unsupported CLDR date/time skeleton: {raw_skeleton}.")
    date_pattern = _skeleton_pattern(date_skeleton, locale_data)
    time_pattern = _skeleton_pattern(time_skeleton, locale_data)
    if date_pattern is None or time_pattern is None:
        raise MF2Error("bad-option", f"Unsupported CLDR date/time skeleton: {raw_skeleton}.")
    if suppress_day_period:
        time_pattern = _strip_day_period_pattern_fields(time_pattern)
    date_part = _format_pattern(date_pattern, value, locale_data)
    time_part = _format_pattern(time_pattern, value, locale_data)
    join_pattern = locale_data["dateTimeFormats"].get(date_time_join_style) or locale_data["dateTimeFormats"].get(
        "medium", "{1} {0}"
    )
    return join_pattern.replace("{1}", date_part).replace("{0}", time_part)


def _canonical_skeleton(
    skeleton: str,
    locale_data: dict[str, Any],
    hour_cycle: str | None,
    value: datetime,
) -> str:
    text = str(skeleton)
    if text.startswith(_SEMANTIC_SKELETON_PREFIX):
        standard = _semantic_skeleton_to_standard(text[len(_SEMANTIC_SKELETON_PREFIX) :], locale_data, value)
        return _canonical_standard_skeleton(standard, locale_data, hour_cycle)
    return _canonical_standard_skeleton(text, locale_data, hour_cycle)


def _canonical_standard_skeleton(skeleton: str, locale_data: dict[str, Any], hour_cycle: str | None) -> str:
    widths: dict[str, int] = {}
    for symbol, count in _skeleton_fields(skeleton):
        if symbol == "C":
            _apply_c_hour_format(widths, locale_data, hour_cycle, count)
        else:
            normalized = _normalize_skeleton_symbol(symbol, locale_data, hour_cycle)
            _set_skeleton_width(widths, normalized, count)
    canonical = "".join(
        symbol * widths[symbol]
        for symbol in _SKELETON_FIELD_ORDER
        if symbol in widths
    )
    if not canonical:
        raise MF2Error("bad-option", "Date/time skeleton must not be empty.")
    return canonical


def _semantic_skeleton_to_standard(body: str, locale_data: dict[str, Any], value: datetime) -> str:
    options = _parse_semantic_skeleton_options(body)
    fields = _parse_semantic_skeleton_fields(options)
    _validate_semantic_skeleton(fields, options)
    length = _semantic_option(options, "length", "medium", _SEMANTIC_LENGTH_VALUES)
    alignment = _semantic_option(options, "alignment", "inline", {"inline", "column"})
    year_style = _semantic_option(options, "yearstyle", "auto", {"auto", "full", "with-era", "numeric", "2-digit"})
    era_style = _semantic_option(options, "erastyle", "auto", _SEMANTIC_TEXT_STYLE_VALUES)
    month_style = _semantic_option(options, "monthstyle", "auto", _SEMANTIC_DATE_STYLE_VALUES)
    quarter_style = _semantic_option(options, "quarterstyle", "auto", _SEMANTIC_DATE_STYLE_VALUES)
    day_style = _semantic_option(options, "daystyle", "auto", _SEMANTIC_NUMERIC_STYLE_VALUES)
    weekday_style = _semantic_option(options, "weekdaystyle", "auto", _SEMANTIC_TEXT_STYLE_VALUES)
    day_period_style = _semantic_option(options, "dayperiodstyle", "auto", _SEMANTIC_TEXT_STYLE_VALUES)
    _semantic_option(options, "hourstyle", "auto", _SEMANTIC_NUMERIC_STYLE_VALUES)
    _semantic_option(options, "minutestyle", "auto", _SEMANTIC_NUMERIC_STYLE_VALUES)
    _semantic_option(options, "secondstyle", "auto", _SEMANTIC_NUMERIC_STYLE_VALUES)
    time_precision = _semantic_option(
        options,
        "timeprecision",
        "second",
        {"hour", "minute", "minute-optional", "second", "fractional-second"},
    )
    time_style = _semantic_option(options, "timestyle", "auto", {"auto", "short", "medium", "long", "full"})
    effective_time_precision = _semantic_time_style_precision(time_style, time_precision)
    hour_cycle = _semantic_option(
        options,
        "hourcycle",
        "auto",
        {"auto", "h11", "h12", "h23", "h24", "clock12", "clock24"},
    )
    zone_style = _semantic_option(
        options,
        "zonestyle",
        "auto",
        {"auto", "generic", "specific", "location", "offset"},
    )
    effective_zone_style = _semantic_time_style_zone_style(time_style, zone_style)
    effective_zone_standalone = len(fields) == 1 or time_style == "full"
    effective_zone_length = time_style if time_style in {"long", "full"} else length
    date_widths = _semantic_date_field_widths(locale_data, length)
    output: list[str] = []
    if "era" in fields:
        output.append(_semantic_era_skeleton(date_widths, length, era_style))
    if "year" in fields:
        output.append(_semantic_year_skeleton(date_widths, year_style, "era" not in fields))
    if "quarter" in fields:
        output.append(_semantic_quarter_skeleton(fields, length, alignment, quarter_style))
    if "month" in fields:
        output.append(_semantic_month_skeleton(fields, date_widths, length, alignment, month_style))
    if "weekofmonth" in fields:
        output.append("W")
    if "day" in fields:
        output.append(_semantic_day_skeleton(date_widths, alignment, day_style))
    if "dayofyear" in fields:
        output.append("D" * (3 if alignment == "column" else 1))
    if "dayofweekinmonth" in fields:
        output.append("F" * (2 if alignment == "column" else 1))
    if "modifiedjulianday" in fields:
        output.append("g" * (6 if alignment == "column" else 1))
    if "weekday" in fields:
        output.append(_semantic_weekday_skeleton(fields, length, weekday_style))
    if "weekofyear" in fields:
        output.append("ww" if alignment == "column" else "w")
    if "dayperiod" in fields:
        output.append(_semantic_day_period_skeleton(length, day_period_style))
    if _has_semantic_time_components(fields):
        output.append(_semantic_explicit_time_skeleton(fields, hour_cycle, alignment, options))
    if "time" in fields:
        output.append(_semantic_time_skeleton(effective_time_precision, hour_cycle, alignment, value, options))
    if "zone" in fields:
        output.append(_semantic_zone_skeleton(effective_zone_style, effective_zone_standalone, effective_zone_length))
    standard = "".join(output)
    if not standard:
        raise MF2Error("bad-option", "Date/time semantic skeleton must include at least one field.")
    return standard


def _parse_semantic_skeleton_options(body: str) -> dict[str, str]:
    parts = [part.strip() for part in str(body).split(";") if part.strip()]
    if not parts:
        raise MF2Error("bad-option", "Date/time semantic skeleton must include fields.")
    options: dict[str, str] = {}
    implicit_date_style: str | None = None
    implicit_time_fields = False
    for index, part in enumerate(parts):
        if "=" in part:
            raw_key, raw_value = part.split("=", 1)
        else:
            raw_key, raw_value = ("fields", part) if index == 0 else ("", part)
        raw_key_alias = _normalize_semantic_key_alias(raw_key)
        key = _normalize_semantic_option_key(raw_key)
        value = _normalize_semantic_option_value(key, raw_value)
        if not key or not value or key in options or key not in _SEMANTIC_OPTION_KEYS:
            raise MF2Error("bad-option", "Invalid date/time semantic skeleton option.")
        if raw_key_alias in ("style", "datestyle", "datelength"):
            implicit_date_style = value
        if raw_key_alias == "timestyle":
            implicit_time_fields = True
        options[key] = value
    if "fields" not in options:
        fields = _implicit_semantic_fields(implicit_date_style, implicit_time_fields, options.get("timestyle"))
        if fields is not None:
            options["fields"] = fields
    return options


def _implicit_semantic_fields(date_style: str | None, has_time_style: bool, time_style: str | None) -> str | None:
    date_fields = "date,weekday" if date_style == "full" else "date"
    if date_style is not None and has_time_style:
        return f"{date_fields},time,zone" if time_style in ("long", "full") else f"{date_fields},time"
    if date_style is not None:
        return date_fields
    if has_time_style:
        return "time,zone" if time_style in ("long", "full") else "time"
    return None


def _normalize_semantic_key_alias(value: str) -> str:
    return value.strip().replace("-", "").replace("_", "").lower()


def _normalize_semantic_option_key(value: str) -> str:
    normalized = _normalize_semantic_key_alias(value)
    if normalized in ("style", "datestyle", "datelength"):
        return "length"
    if normalized == "precision":
        return "timeprecision"
    if normalized == "timestyle":
        return "timestyle"
    if normalized == "hour12":
        return "hourcycle"
    if normalized in ("zone", "timezonename", "timezonestyle"):
        return "zonestyle"
    if normalized == "fractionalseconddigits":
        return "fractionalsecond"
    if normalized in _SEMANTIC_FIELD_STYLE_OPTION_ALIASES:
        return _SEMANTIC_FIELD_STYLE_OPTION_ALIASES[normalized]
    return normalized


def _normalize_semantic_option_value(key: str, value: str) -> str:
    if key == "fields":
        return value.strip().lower()
    normalized = value.strip().replace("-", "").replace("_", "").lower()
    if key == "yearstyle" and normalized == "withera":
        return "with-era"
    if key in _SEMANTIC_STYLE_OPTION_KEYS and normalized in {"2digit", "twodigit"}:
        return "2-digit"
    if key in _SEMANTIC_STYLE_OPTION_KEYS and normalized == "wide":
        return "long"
    if key in _SEMANTIC_STYLE_OPTION_KEYS and normalized == "abbreviated":
        return "short"
    if key == "timeprecision" and normalized == "short":
        return "minute"
    if key == "timeprecision" and normalized == "medium":
        return "second"
    if key == "timeprecision" and normalized == "minuteoptional":
        return "minute-optional"
    if key == "timeprecision" and normalized == "fractionalsecond":
        return "fractional-second"
    if key == "zonestyle" and normalized in {"shortoffset", "longoffset"}:
        return "offset"
    if key == "zonestyle" and normalized in {"shortgeneric", "longgeneric"}:
        return "generic"
    if key == "zonestyle" and normalized in {"short", "long"}:
        return "specific"
    if key == "hourcycle" and normalized == "true":
        return "clock12"
    if key == "hourcycle" and normalized == "false":
        return "clock24"
    return normalized


def _parse_semantic_skeleton_fields(options: dict[str, str]) -> set[str]:
    fields_text = options.get("fields")
    if fields_text is None:
        raise MF2Error("bad-option", "Date/time semantic skeleton must include fields.")
    fields: set[str] = set()
    for field in fields_text.split(","):
        normalized = _normalize_semantic_field(field)
        canonical_fields = (
            ("year", "month", "day")
            if normalized == "date" or normalized == "yearmonthday"
            else ("era", "year", "month", "day")
            if normalized == "eradate" or normalized == "erayearmonthday"
            else ("era", "year", "month", "day", "weekday")
            if normalized in ("eradateweekday", "weekdayeradate", "erayearmonthdayweekday", "weekdayerayearmonthday")
            else ("era", "year", "month", "day", "time")
            if normalized == "eradatetime" or normalized == "erayearmonthdaytime"
            else ("era", "year", "month", "day", "weekday", "time")
            if normalized in (
                "eradatetimeweekday",
                "weekdayeradatetime",
                "erayearmonthdaytimeweekday",
                "weekdayerayearmonthdaytime",
            )
            else ("year", "month", "day", "time")
            if normalized == "datetime" or normalized == "yearmonthdaytime"
            else ("year", "month", "day", "weekday", "time")
            if normalized in ("datetimeweekday", "weekdaydatetime", "yearmonthdaytimeweekday", "weekdayyearmonthdaytime")
            else ("year", "month", "day", "weekday", "time", "zone")
            if normalized in (
                "datetimeweekdayzone",
                "weekdaydatetimezone",
                "zoneddatetimeweekday",
                "zonedweekdaydatetime",
                "yearmonthdaytimeweekdayzone",
                "weekdayyearmonthdaytimezone",
                "zonedyearmonthdaytimeweekday",
                "zonedweekdayyearmonthdaytime",
            )
            else ("era", "year", "month", "day", "time", "zone")
            if normalized in (
                "eradatetimezone",
                "zonederadatetime",
                "erayearmonthdaytimezone",
                "zonederayearmonthdaytime",
            )
            else ("era", "year", "month", "day", "weekday", "time", "zone")
            if normalized in (
                "eradatetimeweekdayzone",
                "weekdayeradatetimezone",
                "zonederadatetimeweekday",
                "zonedweekdayeradatetime",
                "erayearmonthdaytimeweekdayzone",
                "weekdayerayearmonthdaytimezone",
                "zonederayearmonthdaytimeweekday",
                "zonedweekdayerayearmonthdaytime",
            )
            else ("year", "month", "day", "weekday")
            if normalized in ("dateweekday", "weekdaydate", "yearmonthdayweekday", "weekdayyearmonthday")
            else ("year", "month", "day", "time", "zone")
            if normalized in ("datetimezone", "zoneddatetime", "yearmonthdaytimezone", "zonedyearmonthdaytime")
            else ("year", "month")
            if normalized == "yearmonth"
            else ("era", "year", "month")
            if normalized == "erayearmonth"
            else ("year", "quarter")
            if normalized == "yearquarter"
            else ("era", "year", "quarter")
            if normalized == "erayearquarter"
            else ("year", "weekofyear")
            if normalized == "yearweek"
            else ("era", "year", "weekofyear")
            if normalized == "erayearweek"
            else ("era", "year")
            if normalized == "erayear"
            else ("month", "weekofmonth")
            if normalized == "monthweek"
            else ("year", "month", "weekofmonth")
            if normalized == "yearmonthweek"
            else ("era", "year", "month", "weekofmonth")
            if normalized == "erayearmonthweek"
            else ("month", "day")
            if normalized == "monthday"
            else (normalized,)
        )
        for canonical in canonical_fields:
            if canonical not in _SEMANTIC_FIELD_ORDER or canonical in fields:
                raise MF2Error("bad-option", "Invalid date/time semantic skeleton field.")
            fields.add(canonical)
    if not fields:
        raise MF2Error("bad-option", "Date/time semantic skeleton must include fields.")
    return fields


def _normalize_semantic_field(value: str) -> str:
    normalized = value.strip().replace("-", "").replace("_", "").lower()
    if normalized == "dayofmonth":
        return "day"
    if normalized == "dayofweek":
        return "weekday"
    if normalized == "monthofyear":
        return "month"
    if normalized == "quarterofyear":
        return "quarter"
    if normalized == "yearofera":
        return "year"
    if normalized == "week":
        return "weekofyear"
    if normalized == "weekofyear":
        return "weekofyear"
    if normalized == "weekofmonth":
        return "weekofmonth"
    if normalized == "dayofyear":
        return "dayofyear"
    if normalized == "dayofweekinmonth":
        return "dayofweekinmonth"
    if normalized == "modifiedjulianday":
        return "modifiedjulianday"
    if normalized == "millisecondsinday":
        return "millisecondsinday"
    if normalized == "fractionalseconddigits":
        return "fractionalsecond"
    if normalized == "dayperiod":
        return "dayperiod"
    if normalized == "hourofday":
        return "hour"
    if normalized == "minuteofhour":
        return "minute"
    if normalized == "secondofminute":
        return "second"
    if normalized == "timezonename":
        return "zone"
    if normalized == "timezone":
        return "zone"
    return normalized


def _validate_semantic_skeleton(fields: set[str], options: dict[str, str]) -> None:
    date_key = _semantic_field_set_key(fields, _SEMANTIC_DATE_FIELD_ORDER)
    time_key = _semantic_field_set_key(fields, _SEMANTIC_TIME_FIELD_ORDER)
    has_date_fields = bool(date_key)
    has_explicit_time = bool(time_key)
    has_time = "time" in fields or has_explicit_time
    has_zone = "zone" in fields
    has_day_period = "dayperiod" in fields
    if has_time or has_zone:
        valid_date_fields = not has_date_fields or date_key in _SEMANTIC_DATE_FIELD_SETS
    else:
        valid_date_fields = (
            not has_date_fields
            or date_key in _SEMANTIC_DATE_FIELD_SETS
            or date_key in _SEMANTIC_CALENDAR_PERIOD_FIELD_SETS
        )
    if has_day_period:
        valid_field_set = valid_date_fields and (has_time or not has_zone)
    elif has_time or has_zone:
        valid_field_set = not has_date_fields or date_key in _SEMANTIC_DATE_FIELD_SETS
    else:
        valid_field_set = date_key in _SEMANTIC_DATE_FIELD_SETS or date_key in _SEMANTIC_CALENDAR_PERIOD_FIELD_SETS
    if not valid_field_set:
        raise MF2Error("bad-option", "Invalid date/time semantic skeleton field set.")
    if "time" in fields and has_explicit_time:
        raise MF2Error("bad-option", "time field cannot be combined with explicit time component fields.")
    if "timestyle" in options and "timeprecision" in options:
        raise MF2Error("bad-option", "timeStyle cannot be combined with timePrecision.")
    time_style = options.get("timestyle")
    if "timestyle" in options and "time" not in fields:
        raise MF2Error("bad-option", "timeStyle requires the time field.")
    if time_style in {"long", "full"} and not has_zone:
        raise MF2Error("bad-option", "timeStyle=long/full requires the zone field.")
    if time_style in {"long", "full"} and "zonestyle" in options:
        raise MF2Error("bad-option", "timeStyle=long/full cannot be combined with zoneStyle.")
    if has_explicit_time and time_key not in _SEMANTIC_TIME_FIELD_SETS:
        raise MF2Error("bad-option", "Invalid date/time semantic skeleton time field set.")
    if has_explicit_time and "timeprecision" in options:
        raise MF2Error("bad-option", "timePrecision requires the time field.")
    if has_explicit_time and "fractionalsecond" in options and "fractionalsecond" not in fields:
        raise MF2Error("bad-option", "fractionalSecond requires the fractionalSecond field.")
    if "fractionalsecond" in fields:
        _semantic_fractional_second_width(options)
    if has_explicit_time and "hour" not in fields and ("hourcycle" in options or has_day_period):
        raise MF2Error("bad-option", "hourCycle and dayPeriod require the hour field.")
    if "hour" not in fields and "hourstyle" in options:
        raise MF2Error("bad-option", "hourStyle requires the hour field.")
    if "minute" not in fields and "minutestyle" in options:
        raise MF2Error("bad-option", "minuteStyle requires the minute field.")
    if "second" not in fields and "secondstyle" in options:
        raise MF2Error("bad-option", "secondStyle requires the second field.")
    if "year" not in fields and "yearstyle" in options:
        raise MF2Error("bad-option", "yearStyle requires the year field.")
    if "era" not in fields and "erastyle" in options:
        raise MF2Error("bad-option", "eraStyle requires the era field.")
    if "month" not in fields and "monthstyle" in options:
        raise MF2Error("bad-option", "monthStyle requires the month field.")
    if "quarter" not in fields and "quarterstyle" in options:
        raise MF2Error("bad-option", "quarterStyle requires the quarter field.")
    if "day" not in fields and "daystyle" in options:
        raise MF2Error("bad-option", "dayStyle requires the day field.")
    if "weekday" not in fields and "weekdaystyle" in options:
        raise MF2Error("bad-option", "weekdayStyle requires the weekday field.")
    if not has_day_period and "dayperiodstyle" in options:
        raise MF2Error("bad-option", "dayPeriodStyle requires the dayPeriod field.")
    if not has_time and any(key in options for key in ("timeprecision", "timestyle", "fractionalsecond", "hourcycle")):
        raise MF2Error("bad-option", "timePrecision and hourCycle require the time field.")
    if not has_zone and "zonestyle" in options:
        raise MF2Error("bad-option", "zoneStyle requires the zone field.")
    if not (
        {"year", "quarter", "month", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday"} & fields
        or has_time
    ) and "alignment" in options:
        raise MF2Error("bad-option", "alignment requires a date or time field.")


def _semantic_option(options: dict[str, str], key: str, fallback: str, allowed_values: set[str]) -> str:
    value = options.get(key, fallback)
    if value not in allowed_values:
        raise MF2Error(
            "bad-option",
            f"Date/time semantic skeleton {key} must be one of {', '.join(sorted(allowed_values))}.",
        )
    return value


def _semantic_field_set_key(fields: set[str], order: tuple[str, ...]) -> str:
    return ",".join(field for field in order if field in fields)


def _semantic_date_field_widths(locale_data: dict[str, Any], length: str) -> dict[str, int]:
    pattern = locale_data.get("dateFormats", {}).get(length, "")
    widths: dict[str, int] = {}
    for symbol, width in _pattern_field_runs(pattern):
        if symbol == "G" or _is_year_field(symbol) or _is_month_field(symbol) or symbol == "d":
            _set_skeleton_width(widths, symbol, width)
    if not any(_is_year_field(symbol) for symbol in widths):
        widths["y"] = 2 if length == "short" else 1
    if not any(_is_month_field(symbol) for symbol in widths):
        widths["M"] = 4 if _is_wide_length(length) else 3 if length == "medium" else 1
    widths.setdefault("d", 1)
    return widths


def _pattern_field_runs(pattern: str) -> list[tuple[str, int]]:
    fields: list[tuple[str, int]] = []
    in_quote = False
    index = 0
    while index < len(pattern):
        ch = pattern[index]
        if ch == "'":
            if index + 1 < len(pattern) and pattern[index + 1] == "'":
                index += 2
            else:
                in_quote = not in_quote
                index += 1
        elif not in_quote and _is_ascii_letter(ch):
            end = index + 1
            while end < len(pattern) and pattern[end] == ch:
                end += 1
            fields.append((ch, end - index))
            index = end
        else:
            index += 1
    return fields


def _semantic_era_skeleton(date_widths: dict[str, int], length: str, era_style: str = "auto") -> str:
    width = date_widths.get("G", 4 if _is_wide_length(length) else 1) if era_style == "auto" else _era_style_width(era_style)
    return "G" * width


def _era_style_width(style: str) -> int:
    if style == "long":
        return 4
    if style == "narrow":
        return 5
    return 1


def _semantic_year_skeleton(date_widths: dict[str, int], year_style: str, include_era: bool = True) -> str:
    year_symbol = next((symbol for symbol in ("y", "u", "r") if symbol in date_widths), "y")
    source_width = date_widths.get(year_symbol, 1)
    year_width = _semantic_year_width(source_width, year_style)
    skeleton = year_symbol * year_width
    if include_era and "G" in date_widths:
        skeleton = "G" * date_widths["G"] + skeleton
    if include_era and year_style == "with-era" and "G" not in date_widths:
        skeleton = "G" + skeleton
    return skeleton


def _semantic_year_width(source_width: int, year_style: str) -> int:
    if year_style == "auto":
        return source_width
    if year_style == "2-digit":
        return 2
    if year_style == "numeric":
        return 1
    return 1 if source_width == 2 else source_width


def _semantic_quarter_skeleton(fields: set[str], length: str, alignment: str, quarter_style: str = "auto") -> str:
    symbol = "q" if len(fields) == 1 else "Q"
    width = _length_style_width(length) if quarter_style == "auto" else _date_field_style_width(quarter_style)
    return symbol * (max(width, 2) if alignment == "column" and width < 3 else width)


def _semantic_month_skeleton(
    fields: set[str],
    date_widths: dict[str, int],
    length: str,
    alignment: str,
    month_style: str = "auto",
) -> str:
    if len(fields) == 1:
        width = _length_style_width(length) if month_style == "auto" else _date_field_style_width(month_style)
        return "L" * (max(width, 2) if alignment == "column" and width < 3 else width)
    symbol = "M" if "M" in date_widths else "L" if "L" in date_widths else "M"
    width = date_widths.get(symbol, _length_style_width(length)) if month_style == "auto" else _date_field_style_width(month_style)
    return symbol * (max(width, 2) if alignment == "column" and width < 3 else width)


def _semantic_day_skeleton(date_widths: dict[str, int], alignment: str, day_style: str = "auto") -> str:
    width = date_widths.get("d", 1) if day_style == "auto" else _date_field_style_width(day_style)
    return "d" * (max(width, 2) if alignment == "column" and width < 3 else width)


def _length_style_width(length: str) -> int:
    return 4 if _is_wide_length(length) else 3 if length == "medium" else 1


def _is_wide_length(length: str) -> bool:
    return length in {"full", "long"}


def _date_field_style_width(style: str) -> int:
    if style == "numeric":
        return 1
    if style == "2-digit":
        return 2
    if style == "short":
        return 3
    if style == "long":
        return 4
    return 5


def _semantic_weekday_skeleton(fields: set[str], length: str, weekday_style: str = "auto") -> str:
    if weekday_style == "short":
        return "EEE"
    if weekday_style == "long":
        return "EEEE"
    if weekday_style == "narrow":
        return "EEEEE"
    if len(fields) == 1 and length == "short":
        return "EEEEE"
    if _is_wide_length(length):
        return "EEEE"
    return "EEE"


def _semantic_day_period_skeleton(length: str, day_period_style: str = "auto") -> str:
    style = length if day_period_style == "auto" else day_period_style
    return "B" * (4 if _is_wide_length(style) else 5 if style == "narrow" or (day_period_style == "auto" and length == "short") else 1)


def _has_semantic_time_components(fields: set[str]) -> bool:
    return (
        "hour" in fields
        or "minute" in fields
        or "second" in fields
        or "fractionalsecond" in fields
        or "millisecondsinday" in fields
    )


def _semantic_explicit_time_skeleton(fields: set[str], hour_cycle: str, alignment: str, options: dict[str, str]) -> str:
    has_hour = "hour" in fields
    has_minute = "minute" in fields
    has_second = "second" in fields
    has_fractional_second = "fractionalsecond" in fields
    has_milliseconds_in_day = "millisecondsinday" in fields
    output = []
    if has_hour:
        output.append(_semantic_hour_symbol(hour_cycle) * _semantic_numeric_field_width(options, "hourstyle", 2 if alignment == "column" else 1))
    if has_minute:
        output.append("m" * _semantic_numeric_field_width(options, "minutestyle", 2 if not has_hour and not has_second and alignment == "column" else 1))
    if has_second:
        output.append("s" * _semantic_numeric_field_width(options, "secondstyle", 2 if not has_hour and not has_minute and alignment == "column" else 1))
    if has_fractional_second:
        output.append("S" * _semantic_fractional_second_width(options))
    if has_milliseconds_in_day:
        output.append("A" * (8 if alignment == "column" else 1))
    return "".join(output)


def _semantic_numeric_field_width(options: dict[str, str], key: str, fallback_width: int) -> int:
    style = options.get(key, "auto")
    if style == "auto":
        return fallback_width
    if style == "2-digit":
        return 2
    return 1


def _semantic_fractional_second_width(options: dict[str, str]) -> int:
    try:
        width = int(options.get("fractionalsecond", ""))
    except ValueError as error:
        raise MF2Error("bad-option", "Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.") from error
    if width < 1 or width > 9:
        raise MF2Error("bad-option", "Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.")
    return width


def _semantic_time_skeleton(
    time_precision: str,
    hour_cycle: str,
    alignment: str,
    value: datetime,
    options: dict[str, str],
) -> str:
    skeleton = _semantic_hour_symbol(hour_cycle) * (2 if alignment == "column" else 1)
    if time_precision in {"minute", "second", "fractional-second"}:
        skeleton += "m"
    if time_precision == "minute-optional" and value.minute != 0:
        skeleton += "m"
    if time_precision in {"second", "fractional-second"}:
        skeleton += "s"
    if time_precision == "fractional-second":
        skeleton += "S" * _semantic_fractional_second_width(options)
    elif "fractionalsecond" in options:
        raise MF2Error("bad-option", "fractionalSecond requires timePrecision=fractional-second.")
    return skeleton


def _semantic_time_style_precision(time_style: str, time_precision: str) -> str:
    if time_style == "short":
        return "minute"
    if time_style in {"medium", "long", "full"}:
        return "second"
    return time_precision


def _semantic_time_style_zone_style(time_style: str, zone_style: str) -> str:
    if time_style in {"long", "full"}:
        return "specific"
    return zone_style


def _semantic_hour_symbol(hour_cycle: str) -> str:
    if hour_cycle == "h11":
        return "K"
    if hour_cycle in {"h12", "clock12"}:
        return "h"
    if hour_cycle in {"h23", "clock24"}:
        return "H"
    if hour_cycle == "h24":
        return "k"
    return "C"


def _semantic_zone_skeleton(zone_style: str, standalone: bool, length: str) -> str:
    style = "generic" if zone_style == "auto" else zone_style
    if style == "specific":
        return "zzzz" if standalone and length != "short" else "z"
    if style == "location":
        return "VVVV"
    if style == "offset":
        return "O"
    return "vvvv" if standalone and length != "short" else "v"


def _apply_c_hour_format(
    widths: dict[str, int],
    locale_data: dict[str, Any],
    hour_cycle: str | None,
    width: int,
) -> None:
    if hour_cycle is not None:
        hour_symbol = _preferred_hour_symbol(locale_data, hour_cycle)
        _set_skeleton_width(widths, hour_symbol, _c_hour_width(width))
        if _is_hour12_field(hour_symbol):
            _set_skeleton_width(widths, "B", _day_period_width_for_c(width))
        return
    for token in str(locale_data.get("allowedHourFormats", "")).split():
        if not _is_c_hour_format_token(token):
            continue
        _set_skeleton_width(widths, token[0], _c_hour_width(width))
        if len(token) > 1:
            _set_skeleton_width(widths, token[1], _day_period_width_for_c(width))
        return
    hour_symbol = _preferred_hour_symbol(locale_data, hour_cycle)
    _set_skeleton_width(widths, hour_symbol, _c_hour_width(width))


def _is_c_hour_format_token(token: str) -> bool:
    return len(token) in {1, 2} and token[0] in "hHkK" and (len(token) == 1 or token[1] in "bB")


def _set_skeleton_width(widths: dict[str, int], symbol: str, width: int) -> None:
    widths[symbol] = max(widths.get(symbol, 0), width)


def _skeleton_fields(skeleton: str) -> list[tuple[str, int]]:
    fields: list[tuple[str, int]] = []
    index = 0
    while index < len(skeleton):
        symbol = skeleton[index]
        if not _is_ascii_letter(symbol):
            raise MF2Error("bad-option", "Date/time skeleton must contain only ASCII pattern letters.")
        end = index + 1
        while end < len(skeleton) and skeleton[end] == symbol:
            end += 1
        width = end - index
        if width > _MAX_SKELETON_FIELD_WIDTH:
            raise MF2Error("bad-option", "Date/time skeleton field width is too large.")
        fields.append((symbol, width))
        index = end
    return fields


def _normalize_skeleton_symbol(symbol: str, locale_data: dict[str, Any], hour_cycle: str | None) -> str:
    if symbol == "l":
        return "L"
    if symbol in {"j", "J"}:
        return _preferred_hour_symbol(locale_data, hour_cycle)
    return symbol


def _c_hour_width(width: int) -> int:
    return 2 if width % 2 == 0 else 1


def _day_period_width_for_c(width: int) -> int:
    if width >= 5:
        return 5
    if width >= 3:
        return 4
    return 1


def _should_suppress_day_period(skeleton: str) -> bool:
    text = str(skeleton)
    return "J" in text and not any(symbol in text for symbol in ("a", "b", "B", "C"))


def _strip_day_period_pattern_fields(pattern: str) -> str:
    output: list[str] = []
    pending_whitespace: list[str] = []
    index = 0
    while index < len(pattern):
        ch = pattern[index]
        if ch == "'":
            _quoted, next_index = _read_quoted_pattern(pattern, index)
            output.extend(pending_whitespace)
            output.append(pattern[index:next_index])
            pending_whitespace = []
            index = next_index
        elif _is_ascii_letter(ch):
            end = index + 1
            while end < len(pattern) and pattern[end] == ch:
                end += 1
            if _is_day_period_field(ch):
                pending_whitespace = []
            else:
                output.extend(pending_whitespace)
                output.append(pattern[index:end])
                pending_whitespace = []
            index = end
        elif _is_pattern_whitespace(ch):
            pending_whitespace.append(ch)
            index += 1
        else:
            output.extend(pending_whitespace)
            output.append(ch)
            pending_whitespace = []
            index += 1
    output.extend(pending_whitespace)
    return "".join(output).strip()


def _is_pattern_whitespace(value: str) -> bool:
    return value in {" ", "\u00a0", "\u202f"} or value.isspace()


def _preferred_hour_symbol(locale_data: dict[str, Any], hour_cycle: str | None) -> str:
    if hour_cycle == "h11":
        return "K"
    if hour_cycle == "h12":
        return "h"
    if hour_cycle == "h23":
        return "H"
    if hour_cycle == "h24":
        return "k"
    short_time = locale_data.get("timeFormats", {}).get("short", "")
    if "H" in short_time:
        return "H"
    if "k" in short_time:
        return "k"
    if "K" in short_time:
        return "K"
    return "h"


def _skeleton_field_set(skeleton: str) -> str:
    return "".join(sorted({_field_set_symbol(symbol) for symbol in _skeleton_widths(skeleton)}))


def _field_set_symbol(symbol: str) -> str:
    if _is_year_field(symbol):
        return "y"
    if _is_hour_field(symbol):
        return "j"
    if _is_month_field(symbol):
        return "M"
    if _is_quarter_field(symbol):
        return "Q"
    if _is_day_period_field(symbol):
        return "B"
    if _is_weekday_field(symbol):
        return "E"
    if _is_time_zone_field(symbol):
        return "v"
    return symbol


def _skeleton_distance(requested: str, candidate: str) -> int:
    requested_widths = _skeleton_widths(requested)
    candidate_widths = _skeleton_widths(candidate)
    distance = 0
    for symbol, requested_width in requested_widths.items():
        candidate_symbol = _candidate_symbol_for_requested(symbol, candidate_widths)
        candidate_width = 0 if candidate_symbol is None else candidate_widths[candidate_symbol]
        distance += abs(requested_width - candidate_width)
        if _is_text_width(requested_width) != _is_text_width(candidate_width):
            distance += 8
        distance += _hour_field_distance(symbol, candidate_symbol)
    return distance


def _skeleton_widths(skeleton: str) -> dict[str, int]:
    widths: dict[str, int] = {}
    for symbol, count in _skeleton_fields(skeleton):
        widths[symbol] = max(widths.get(symbol, 0), count)
    return widths


def _is_text_width(width: int) -> bool:
    return width >= 3


def _is_hour_field(symbol: str | None) -> bool:
    return symbol in _SKELETON_HOUR_FIELDS


def _is_year_field(symbol: str | None) -> bool:
    return symbol in {"y", "u", "r"}


def _is_weekday_field(symbol: str) -> bool:
    return symbol in {"E", "e", "c"}


def _is_month_field(symbol: str) -> bool:
    return symbol in {"M", "L"}


def _is_quarter_field(symbol: str) -> bool:
    return symbol in {"Q", "q"}


def _is_day_period_field(symbol: str) -> bool:
    return symbol in {"a", "b", "B"}


def _is_synthetic_numeric_field(symbol: str) -> bool:
    return symbol in {"D", "F", "g", "m", "s", "A"}


def _is_time_zone_field(symbol: str) -> bool:
    return symbol in {"z", "Z", "O", "v", "V", "X", "x"}


def _candidate_symbol_for_requested(symbol: str, candidate_widths: dict[str, int]) -> str | None:
    if symbol in candidate_widths:
        return symbol
    if _is_year_field(symbol):
        for year_symbol in ("y", "u", "r"):
            if year_symbol in candidate_widths:
                return year_symbol
    if _is_hour_field(symbol):
        for hour_symbol in ("h", "H", "k", "K"):
            if hour_symbol in candidate_widths:
                return hour_symbol
        return None
    if _is_quarter_field(symbol):
        for quarter_symbol in ("Q", "q"):
            if quarter_symbol in candidate_widths:
                return quarter_symbol
    if _is_month_field(symbol):
        for month_symbol in ("M", "L"):
            if month_symbol in candidate_widths:
                return month_symbol
    if _is_day_period_field(symbol):
        for day_period_symbol in ("B", "b", "a"):
            if day_period_symbol in candidate_widths:
                return day_period_symbol
    if _is_weekday_field(symbol):
        for weekday_symbol in ("E", "e", "c"):
            if weekday_symbol in candidate_widths:
                return weekday_symbol
    if _is_time_zone_field(symbol):
        for time_zone_symbol in ("v", "z", "O", "Z", "X", "x", "V"):
            if time_zone_symbol in candidate_widths:
                return time_zone_symbol
    return None


def _hour_field_distance(requested_symbol: str, candidate_symbol: str | None) -> int:
    if requested_symbol == candidate_symbol or not _is_hour_field(requested_symbol) or not _is_hour_field(candidate_symbol):
        return 0
    return 1 if _is_hour12_field(requested_symbol) == _is_hour12_field(candidate_symbol) else 4


def _is_hour12_field(symbol: str) -> bool:
    return symbol in {"h", "K"}


def _requested_symbol_for_pattern(
    symbol: str,
    requested_widths: dict[str, int],
    candidate_widths: dict[str, int],
) -> str:
    if _is_year_field(symbol) and _candidate_symbol_for_requested(symbol, candidate_widths) is not None:
        return _candidate_symbol_for_requested(symbol, requested_widths) or symbol
    if _is_weekday_field(symbol) and _candidate_symbol_for_requested(symbol, candidate_widths) is not None:
        return _requested_weekday_symbol_for_pattern(symbol, requested_widths)
    if _is_day_period_field(symbol) and _candidate_symbol_for_requested(symbol, candidate_widths) is not None:
        return _requested_day_period_symbol_for_pattern(symbol, requested_widths)
    if _is_time_zone_field(symbol) and _candidate_symbol_for_requested(symbol, candidate_widths) is not None:
        return _requested_time_zone_symbol_for_pattern(symbol, requested_widths)
    if (
        not _is_year_field(symbol)
        and not _is_hour_field(symbol)
        and not _is_month_field(symbol)
        and not _is_quarter_field(symbol)
        and not _is_day_period_field(symbol)
        and not _is_time_zone_field(symbol)
    ) or _candidate_symbol_for_requested(symbol, candidate_widths) is None:
        return symbol
    return _candidate_symbol_for_requested(symbol, requested_widths) or symbol


def _requested_weekday_symbol_for_pattern(symbol: str, requested_widths: dict[str, int]) -> str:
    if "c" in requested_widths:
        return "c"
    if "e" in requested_widths:
        return "e"
    if "E" in requested_widths:
        return "E"
    return symbol


def _requested_day_period_symbol_for_pattern(symbol: str, requested_widths: dict[str, int]) -> str:
    if "a" in requested_widths:
        return "a"
    if "b" in requested_widths:
        return "b"
    if "B" in requested_widths:
        return "B"
    return symbol


def _requested_time_zone_symbol_for_pattern(symbol: str, requested_widths: dict[str, int]) -> str:
    for time_zone_symbol in ("z", "Z", "O", "v", "V", "X", "x"):
        if time_zone_symbol in requested_widths:
            return time_zone_symbol
    return symbol


def _width_for_pattern_symbol(symbol: str, widths: dict[str, int]) -> int | None:
    if symbol in widths:
        return widths[symbol]
    if _is_year_field(symbol):
        for year_symbol in ("y", "u", "r"):
            if year_symbol in widths:
                return widths[year_symbol]
    if _is_weekday_field(symbol):
        for weekday_symbol in ("E", "e", "c"):
            if weekday_symbol in widths:
                return widths[weekday_symbol]
    if _is_month_field(symbol):
        for month_symbol in ("M", "L"):
            if month_symbol in widths:
                return widths[month_symbol]
    if _is_day_period_field(symbol):
        for day_period_symbol in ("B", "b", "a"):
            if day_period_symbol in widths:
                return widths[day_period_symbol]
    if _is_quarter_field(symbol):
        for quarter_symbol in ("Q", "q"):
            if quarter_symbol in widths:
                return widths[quarter_symbol]
    if _is_time_zone_field(symbol):
        for time_zone_symbol in ("z", "Z", "O", "v", "V", "X", "x"):
            if time_zone_symbol in widths:
                return widths[time_zone_symbol]
    return None


def _adjust_pattern_widths(pattern: str, requested_skeleton: str, candidate_skeleton: str) -> str:
    requested_widths = _skeleton_widths(requested_skeleton)
    candidate_widths = _skeleton_widths(candidate_skeleton)
    output: list[str] = []
    in_quote = False
    index = 0
    while index < len(pattern):
        ch = pattern[index]
        if ch == "'":
            output.append(ch)
            if index + 1 < len(pattern) and pattern[index + 1] == "'":
                output.append("'")
                index += 2
            else:
                in_quote = not in_quote
                index += 1
        elif not in_quote and _is_ascii_letter(ch):
            end = index + 1
            while end < len(pattern) and pattern[end] == ch:
                end += 1
            requested_symbol = _requested_symbol_for_pattern(ch, requested_widths, candidate_widths)
            requested_width = _width_for_pattern_symbol(ch, requested_widths)
            candidate_width = _width_for_pattern_symbol(ch, candidate_widths)
            pattern_width = end - index
            width = (
                requested_width
                if _should_adjust_pattern_width(requested_symbol, requested_width, candidate_width, pattern_width)
                else pattern_width
            )
            output.append(requested_symbol * width)
            index = end
        else:
            output.append(ch)
            index += 1
    return "".join(output)


def _should_adjust_pattern_width(
    symbol: str,
    requested_width: int | None,
    candidate_width: int | None,
    pattern_width: int,
) -> bool:
    if requested_width is None or candidate_width is None:
        return False
    if symbol in {"e", "c"} and pattern_width >= 3 and requested_width <= 2:
        return True
    if _is_weekday_field(symbol) and pattern_width >= 3 and requested_width >= 4:
        return True
    return pattern_width == candidate_width


def _split_date_time_skeleton(skeleton: str) -> tuple[str, str]:
    date_skeleton: list[str] = []
    time_skeleton: list[str] = []
    for symbol in skeleton:
        if symbol in _SKELETON_TIME_FIELDS:
            time_skeleton.append(symbol)
        else:
            date_skeleton.append(symbol)
    return "".join(date_skeleton), "".join(time_skeleton)


def _format_pattern(pattern: str, value: datetime, locale_data: dict[str, Any]) -> str:
    output: list[str] = []
    index = 0
    while index < len(pattern):
        ch = pattern[index]
        if ch == "'":
            quoted, index = _read_quoted_pattern(pattern, index)
            output.append(quoted)
        elif _is_ascii_letter(ch):
            end = index + 1
            while end < len(pattern) and pattern[end] == ch:
                end += 1
            output.append(_format_field(ch, end - index, value, locale_data))
            index = end
        else:
            output.append(ch)
            index += 1
    return "".join(output)


def _read_quoted_pattern(pattern: str, start: int) -> tuple[str, int]:
    if start + 1 < len(pattern) and pattern[start + 1] == "'":
        return "'", start + 2
    output: list[str] = []
    index = start + 1
    while index < len(pattern):
        if pattern[index] == "'":
            if index + 1 < len(pattern) and pattern[index + 1] == "'":
                output.append("'")
                index += 2
            else:
                return "".join(output), index + 1
        else:
            output.append(pattern[index])
            index += 1
    return "".join(output), index


def _format_field(symbol: str, count: int, value: datetime, locale_data: dict[str, Any]) -> str:
    if symbol == "G":
        return _era_name(value, locale_data, count)
    if symbol == "y":
        return _year_value(value, locale_data, count)
    if symbol == "u":
        return _extended_year_value(value, locale_data, count)
    if symbol == "r":
        return _extended_year_value(value, locale_data, count)
    if symbol == "Y":
        return _week_year_value(value, locale_data, count)
    if symbol in {"Q", "q"}:
        return _quarter_value(value, locale_data, count, symbol == "q")
    if symbol in {"M", "L"}:
        return _month_value(value, locale_data, count, symbol == "L")
    if symbol == "d":
        return _integer_value(value.day, locale_data, count)
    if symbol == "D":
        return _integer_value(value.timetuple().tm_yday, locale_data, count)
    if symbol == "F":
        return _integer_value(_day_of_week_in_month(value), locale_data, count)
    if symbol == "g":
        return _integer_value(_modified_julian_day(value), locale_data, count)
    if symbol == "w":
        return _integer_value(_week_of_year(value, locale_data), locale_data, count)
    if symbol == "W":
        return _integer_value(_week_of_month(value, locale_data), locale_data, count)
    if symbol == "E":
        return _weekday_name(value, locale_data, count)
    if symbol == "e":
        return _local_weekday_value(value, locale_data, count, False)
    if symbol == "c":
        return _local_weekday_value(value, locale_data, count, True)
    if symbol in {"a", "b", "B"}:
        return _day_period_name(value, locale_data, count, symbol)
    if symbol == "H":
        return _integer_value(value.hour, locale_data, count)
    if symbol == "k":
        return _integer_value(24 if value.hour == 0 else value.hour, locale_data, count)
    if symbol == "h":
        return _integer_value(_hour12(value), locale_data, count)
    if symbol == "K":
        return _integer_value(value.hour % 12, locale_data, count)
    if symbol == "m":
        return _integer_value(value.minute, locale_data, count)
    if symbol == "s":
        return _integer_value(value.second, locale_data, count)
    if symbol == "S":
        return _fraction_value(value, locale_data, count)
    if symbol == "A":
        return _integer_value(_milliseconds_in_day(value), locale_data, count)
    if symbol in {"z", "Z", "O", "v", "V", "X", "x"}:
        return _time_zone_value(symbol, count, value, locale_data)
    raise MF2Error("bad-option", f"Unsupported CLDR date/time pattern field: {symbol}.")


def _time_zone_value(symbol: str, count: int, value: datetime, locale_data: dict[str, Any]) -> str:
    names = locale_data.get("timeZoneNames", {})
    offset_minutes = _offset_minutes(value)
    if offset_minutes != 0:
        if symbol == "X":
            return _iso_offset(offset_minutes, count, True)
        if symbol == "x":
            return _iso_offset(offset_minutes, count, False)
        if symbol == "V" and count == 1:
            return "unk"
        if symbol == "V" and count == 2:
            return _fixed_offset_gmt_id(offset_minutes, locale_data)
        if symbol == "V" and count == 3:
            return "Unknown Location"
        if symbol == "Z" and count <= 3:
            return _basic_offset(offset_minutes)
        if symbol == "Z" and count == 5:
            return _iso_offset(offset_minutes, 3, True)
        return _localized_gmt_offset(names, offset_minutes, count, locale_data)
    if symbol == "z":
        if count >= 4:
            return names.get("utcLong") or names.get("utcShort") or _UTC
        return names.get("utcShort") or _UTC
    if symbol in {"O", "v"}:
        return _localized_gmt_zero(names)
    if symbol == "V":
        return _localized_gmt_zero(names)
    if symbol == "Z":
        if count <= 3:
            return "+0000"
        if count == 5:
            return "Z"
        return _localized_gmt_zero(names)
    if symbol == "X":
        return "Z"
    if symbol == "x":
        return "+00" if count == 1 else "+0000" if count in {2, 4} else "+00:00"
    return _UTC


def _localized_gmt_zero(names: dict[str, str]) -> str:
    return names.get("gmtZeroFormat") or names.get("gmtFormat", "GMT{0}").replace("{0}", "")


def _localized_gmt_offset(
    names: dict[str, str],
    offset_minutes: int,
    count: int,
    locale_data: dict[str, Any],
) -> str:
    formatted = _extended_offset(offset_minutes, True) if count >= 4 else _short_offset(offset_minutes)
    return names.get("gmtFormat", "GMT{0}").replace(
        "{0}",
        _localize_digits(formatted, locale_data.get("numberingSystemDigits")),
    )


def _fixed_offset_gmt_id(offset_minutes: int, locale_data: dict[str, Any]) -> str:
    return f"GMT{_localize_digits(_extended_offset(offset_minutes, True), locale_data.get('numberingSystemDigits'))}"


def _offset_minutes(value: datetime) -> int:
    offset = value.utcoffset() or timedelta()
    return int(offset.total_seconds() // 60)


def _iso_offset(offset_minutes: int, count: int, use_zero_z: bool) -> str:
    if offset_minutes == 0 and use_zero_z:
        return "Z"
    if count == 1:
        return _short_iso_offset(offset_minutes)
    if count in {2, 4}:
        return _basic_offset(offset_minutes)
    return _extended_offset(offset_minutes, True)


def _short_iso_offset(offset_minutes: int) -> str:
    sign, hours, minutes = _offset_parts(offset_minutes)
    return f"{sign}{hours:02d}" if minutes == 0 else f"{sign}{hours:02d}{minutes:02d}"


def _short_offset(offset_minutes: int) -> str:
    sign, hours, minutes = _offset_parts(offset_minutes)
    return f"{sign}{hours}" if minutes == 0 else f"{sign}{hours}:{minutes:02d}"


def _basic_offset(offset_minutes: int) -> str:
    sign, hours, minutes = _offset_parts(offset_minutes)
    return f"{sign}{hours:02d}{minutes:02d}"


def _extended_offset(offset_minutes: int, padded_hour: bool) -> str:
    sign, hours, minutes = _offset_parts(offset_minutes)
    hour = f"{hours:02d}" if padded_hour else str(hours)
    return f"{sign}{hour}:{minutes:02d}"


def _offset_parts(offset_minutes: int) -> tuple[str, int, int]:
    sign = "-" if offset_minutes < 0 else "+"
    absolute = abs(offset_minutes)
    return sign, absolute // 60, absolute % 60


def _era_name(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    era = "0" if value.year <= 0 else "1"
    return _name_by_width(locale_data["eras"], _width_for_text(count), era)


def _year_value(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    year = value.year
    year_of_era = 1 - year if year <= 0 else year
    if count == 2:
        return _integer_text(year_of_era % 100, locale_data, 2)
    return _localize_digits(str(year_of_era), locale_data.get("numberingSystemDigits"))


def _extended_year_value(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    return _integer_value(value.year, locale_data, count)


def _week_year_value(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    week_year, _week = _week_year_info(value, locale_data)
    if count == 2:
        return _integer_text(week_year % 100, locale_data, 2)
    return _localize_digits(str(week_year), locale_data.get("numberingSystemDigits"))


def _month_value(
    value: datetime,
    locale_data: dict[str, Any],
    count: int,
    stand_alone: bool,
) -> str:
    if count <= 2:
        return _integer_value(value.month, locale_data, count)
    context = "stand-alone" if stand_alone else "format"
    return _contextual_name(locale_data["months"], context, _width_for_text(count), str(value.month))


def _quarter_value(
    value: datetime,
    locale_data: dict[str, Any],
    count: int,
    stand_alone: bool,
) -> str:
    quarter = (value.month - 1) // 3 + 1
    if count <= 2:
        return _integer_value(quarter, locale_data, count)
    context = "stand-alone" if stand_alone else "format"
    return _contextual_name(locale_data["quarters"], context, _width_for_text(count), str(quarter))


def _weekday_name(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    return _contextual_name(
        locale_data["weekdays"],
        "format",
        _width_for_weekday(count),
        _WEEKDAY_KEYS[(value.weekday() + 1) % 7],
    )


def _local_weekday_value(value: datetime, locale_data: dict[str, Any], count: int, stand_alone: bool) -> str:
    day = (value.weekday() + 1) % 7
    if count <= 2:
        local_day = _modulo(day - int(locale_data.get("firstDayOfWeek", 1)), 7) + 1
        return _integer_value(local_day, locale_data, count)
    return _contextual_name(
        locale_data["weekdays"],
        "stand-alone" if stand_alone else "format",
        _width_for_weekday(count),
        _WEEKDAY_KEYS[day],
    )


def _day_of_week_in_month(value: datetime) -> int:
    return ((value.day - 1) // 7) + 1


def _milliseconds_in_day(value: datetime) -> int:
    return ((value.hour * 60 + value.minute) * 60 + value.second) * 1000 + (value.microsecond // 1000)


def _modified_julian_day(value: datetime) -> int:
    return value.date().toordinal() - date(1858, 11, 17).toordinal()


def _week_of_year(value: datetime, locale_data: dict[str, Any]) -> int:
    _week_year, week = _week_year_info(value, locale_data)
    return week


def _week_year_info(value: datetime, locale_data: dict[str, Any]) -> tuple[int, int]:
    year = value.year
    ordinal = value.date().toordinal()
    week_start = _start_of_week(ordinal, int(locale_data.get("firstDayOfWeek", 1)))
    current_start = _first_week_start_of_year(year, locale_data)
    next_start = _first_week_start_of_year(year + 1, locale_data)
    if week_start >= next_start:
        return year + 1, 1
    if week_start < current_start:
        previous_year = year - 1
        previous_start = _first_week_start_of_year(previous_year, locale_data)
        return previous_year, (week_start - previous_start) // 7 + 1
    return year, (week_start - current_start) // 7 + 1


def _week_of_month(value: datetime, locale_data: dict[str, Any]) -> int:
    ordinal = value.date().toordinal()
    week_start = _start_of_week(ordinal, int(locale_data.get("firstDayOfWeek", 1)))
    first_start = _first_week_start(date(value.year, value.month, 1).toordinal(), locale_data)
    return (week_start - first_start) // 7 + 1


def _first_week_start_of_year(year: int, locale_data: dict[str, Any]) -> int:
    return _first_week_start(date(year, 1, 1).toordinal(), locale_data)


def _first_week_start(period_start: int, locale_data: dict[str, Any]) -> int:
    week_start = _start_of_week(period_start, int(locale_data.get("firstDayOfWeek", 1)))
    days_in_period = week_start + 7 - period_start
    return week_start if days_in_period >= int(locale_data.get("minDaysInFirstWeek", 1)) else week_start + 7


def _start_of_week(ordinal: int, first_day: int) -> int:
    return ordinal - ((_day_of_week(ordinal) - first_day) % 7)


def _day_of_week(ordinal: int) -> int:
    return ordinal % 7


def _modulo(value: int, divisor: int) -> int:
    return value % divisor


def _day_period_name(value: datetime, locale_data: dict[str, Any], count: int, symbol: str) -> str:
    period = _day_period_key(value, locale_data, symbol)
    return _contextual_name(
        locale_data["dayPeriods"],
        "format",
        _width_for_day_period(count),
        period,
    )


def _day_period_key(value: datetime, locale_data: dict[str, Any], symbol: str) -> str:
    fallback = "am" if value.hour < 12 else "pm"
    if symbol == "a":
        return fallback
    if symbol == "b":
        return _select_day_period_rule(value, locale_data.get("dayPeriodRules", ""), exact_only=True) or fallback
    return _select_day_period_rule(value, locale_data.get("dayPeriodRules", ""), exact_only=False) or fallback


def _select_day_period_rule(value: datetime, encoded_rules: str, *, exact_only: bool) -> str | None:
    if not encoded_rules:
        return None
    minute = value.hour * 60 + value.minute
    exact_minute = minute if value.second == 0 and value.microsecond == 0 else -1
    range_match: str | None = None
    for raw_rule in encoded_rules.split(";"):
        period, separator, span = raw_rule.partition("=")
        if separator != "=":
            continue
        if "-" not in span:
            if int(span) == exact_minute:
                return period
            continue
        if not exact_only:
            start_text, end_text = span.split("-", 1)
            if _minute_in_day_period_range(minute, int(start_text), int(end_text)):
                range_match = range_match or period
    return None if exact_only else range_match


def _minute_in_day_period_range(minute: int, start: int, end: int) -> bool:
    if start <= end:
        return start <= minute < end
    return minute >= start or minute < end


def _hour12(value: datetime) -> int:
    hour = value.hour % 12
    return 12 if hour == 0 else hour


def _fraction_value(value: datetime, locale_data: dict[str, Any], count: int) -> str:
    milliseconds = f"{value.microsecond // 1000:03d}"
    return _localize_digits((milliseconds + "000000000")[:count], locale_data.get("numberingSystemDigits"))


def _integer_value(value: int, locale_data: dict[str, Any], count: int) -> str:
    return _integer_text(value, locale_data, count if count >= 2 else 0)


def _integer_text(value: int, locale_data: dict[str, Any], minimum_digits: int) -> str:
    text = str(abs(value)).rjust(minimum_digits, "0")
    signed = "-" + text if value < 0 else text
    return _localize_digits(signed, locale_data.get("numberingSystemDigits"))


def _contextual_name(
    source: dict[str, dict[str, dict[str, str]]],
    context: str,
    width: str,
    key: str,
) -> str:
    context_data = source.get(context) or source.get("format") or source.get("stand-alone") or {}
    return _name_by_width(context_data, width, key)


def _name_by_width(source: dict[str, dict[str, str]], width: str, key: str) -> str:
    return (
        source.get(width, {}).get(key)
        or source.get("abbreviated", {}).get(key)
        or source.get("wide", {}).get(key)
        or source.get("short", {}).get(key)
        or source.get("narrow", {}).get(key)
        or key
    )


def _width_for_text(count: int) -> str:
    if count == 4:
        return "wide"
    if count == 5:
        return "narrow"
    return "abbreviated"


def _width_for_weekday(count: int) -> str:
    if count == 4:
        return "wide"
    if count == 5:
        return "narrow"
    if count >= 6:
        return "short"
    return "abbreviated"


def _width_for_day_period(count: int) -> str:
    if count == 4:
        return "wide"
    if count >= 5:
        return "narrow"
    return "abbreviated"


def _is_ascii_letter(value: str) -> bool:
    return ("A" <= value <= "Z") or ("a" <= value <= "z")


def _localize_digits(value: str, digits: str | None) -> str:
    if digits is None or digits == "0123456789":
        return value
    return "".join(
        digits[ord(ch) - _DIGIT_ZERO] if "0" <= ch <= "9" else ch
        for ch in value
    )
