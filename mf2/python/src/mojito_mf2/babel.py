from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta
from decimal import Decimal, InvalidOperation, ROUND_DOWN
from typing import Any

try:
    from babel.dates import (
        format_date,
        format_datetime,
        format_time,
        format_timedelta,
        get_timezone,
    )
    from babel.numbers import format_currency, format_decimal, format_percent
except ModuleNotFoundError as error:  # pragma: no cover - exercised when Babel is absent.
    raise ImportError(
        'Babel support is optional. Install it with: pip install "mojito-mf2[babel]"'
    ) from error

from ._locale_key import canonical_locale_key
from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry

__all__ = ["babel_function_registry"]

_MAX_FRACTION_DIGITS = 100
_MAX_DECIMAL_OPERAND_LENGTH = 256
_MAX_DECIMAL_EXPONENT = 1_000_000
_ABSENT_OPTION = "\x00__mojito_mf2_absent__"
_DECIMAL_RE = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
_ISO_DATE_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
_ISO_TIME_RE = re.compile(
    r"^[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?(Z|[+-][0-9]{2}:[0-9]{2})?$"
)
_ISO_DATE_TIME_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]{1,9})?)?(Z|[+-][0-9]{2}:[0-9]{2})?$"
)


def babel_function_registry() -> FunctionRegistry:
    return (
        FunctionRegistry.portable()
        .with_function("number", _format_number)
        .with_function("percent", _format_percent)
        .with_function("integer", _format_integer)
        .with_function("currency", _format_currency)
        .with_function("date", _format_date)
        .with_function("time", _format_time)
        .with_function("datetime", _format_datetime)
        .with_function("relativeTime", _format_relative_time)
    )


def _format_number(call: FunctionCall) -> str:
    message = "Number function requires a numeric operand."
    value = _parse_call_decimal(call, message)
    locale = _babel_locale(call)
    pattern = _decimal_pattern(call)
    decimal_quantization = _maximum_fraction_digits(call) is not None
    try:
        rendered = format_decimal(
            value,
            format=pattern,
            locale=locale,
            decimal_quantization=decimal_quantization,
        )
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error
    return _apply_sign_display(
        rendered,
        value,
        call,
    )


def _format_percent(call: FunctionCall) -> str:
    message = "Percent function requires a numeric operand."
    value = _parse_call_decimal(call, message)
    locale = _babel_locale(call)
    pattern = _decimal_pattern(call, suffix="%")
    decimal_quantization = _maximum_fraction_digits(call) is not None
    try:
        rendered = format_percent(
            value,
            format=pattern,
            locale=locale,
            decimal_quantization=decimal_quantization,
        )
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error
    return _apply_sign_display(
        rendered,
        value,
        call,
    )


def _format_integer(call: FunctionCall) -> str:
    message = "Integer function requires a numeric operand."
    value = _parse_call_decimal(call, message)
    integer = value.to_integral_value(rounding=ROUND_DOWN)
    locale = _babel_locale(call)
    try:
        rendered = format_decimal(integer, format="#,##0", locale=locale)
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error
    return _apply_sign_display(
        rendered,
        integer,
        call,
    )


def _format_currency(call: FunctionCall) -> str:
    message = "Currency function requires a numeric operand."
    value = _parse_call_decimal(call, message)
    currency = _inherited_option_value(call, "currency")
    if (
        currency is None
        or len(currency) != 3
        or not currency.isascii()
        or not currency.isalpha()
    ):
        raise MF2Error("bad-option", "Currency function requires a three-letter currency option.")
    locale = _babel_locale(call)
    try:
        return format_currency(value, currency.upper(), locale=locale)
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_date(call: FunctionCall) -> str:
    value = _parse_call_date(call, "Date function requires a date operand.")
    locale = _babel_locale(call)
    style = _date_style(call)
    try:
        return format_date(
            value,
            format=style,
            locale=locale,
        )
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_time(call: FunctionCall) -> str:
    value = _parse_call_time(call, "Time function requires a time operand.")
    locale = _babel_locale(call)
    style = _time_style(call)
    time_zone = _time_zone(call)
    try:
        return format_time(
            value,
            format=style,
            locale=locale,
            tzinfo=time_zone,
        )
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_datetime(call: FunctionCall) -> str:
    value = _parse_call_datetime(call, "Datetime function requires a datetime operand.")
    locale = _babel_locale(call)
    style = _datetime_style(call)
    time_zone = _time_zone(call)
    try:
        return format_datetime(
            value,
            format=style,
            locale=locale,
            tzinfo=time_zone,
        )
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_relative_time(call: FunctionCall) -> str:
    message = "Relative time function requires a numeric operand."
    value = _parse_call_decimal(call, message)
    unit = _option_one_of(
        call,
        "unit",
        {"second", "minute", "hour", "day", "week", "month", "year"},
        "second",
    )
    style = _option_one_of(call, "style", {"long", "short", "narrow"}, "long")
    _option_one_of(call, "numeric", {"always", "auto"}, "always")
    try:
        delta = _timedelta(value, unit)
    except (OverflowError, ValueError) as error:
        raise MF2Error("bad-operand", message) from error
    locale = _babel_locale(call)
    try:
        return format_timedelta(
            delta,
            granularity=unit,
            add_direction=True,
            format=style,
            locale=locale,
        )
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _babel_locale(call: FunctionCall) -> str:
    locale = canonical_locale_key(call.locale).replace("-", "_")
    if not locale:
        raise MF2Error("bad-option", "Locale option must be a valid locale identifier.")
    return locale


def _decimal_pattern(call: FunctionCall, suffix: str = "") -> str | None:
    minimum = _minimum_fraction_digits(call)
    maximum = _maximum_fraction_digits(call)
    if minimum is None and maximum is None:
        return None
    minimum = minimum or 0
    maximum = maximum if maximum is not None else minimum
    if maximum < minimum:
        raise MF2Error(
            "bad-option",
            "maximumFractionDigits option must be greater than or equal to minimumFractionDigits.",
        )
    if maximum == 0:
        return f"#,##0{suffix}"
    fraction = "0" * minimum + "#" * (maximum - minimum)
    return f"#,##0.{fraction}{suffix}"


def _minimum_fraction_digits(call: FunctionCall) -> int | None:
    return _non_negative_integer_option(call, "minimumFractionDigits")


def _maximum_fraction_digits(call: FunctionCall) -> int | None:
    return _non_negative_integer_option(call, "maximumFractionDigits")


def _non_negative_integer_option(call: FunctionCall, name: str) -> int | None:
    value = call.option_value(name)
    if value is None:
        return None
    if not value or not all("0" <= ch <= "9" for ch in value):
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    if len(value) > len(str(_MAX_FRACTION_DIGITS)):
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    parsed = int(value)
    if parsed > _MAX_FRACTION_DIGITS:
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    return parsed


def _parse_call_decimal(call: FunctionCall, message: str) -> Decimal:
    parsed = _parse_decimal_or_none(call.value)
    if parsed is None:
        parsed = _parse_source_decimal(call.inherited_source)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_source_decimal(source: object | None) -> Decimal | None:
    while source is not None:
        function = getattr(source, "function")
        if _is_decimal_source_function(function):
            return _parse_decimal_or_none(getattr(source, "value"))
        source = getattr(source, "inherited_source")
    return None


def _is_decimal_source_function(function: dict[str, Any]) -> bool:
    return function.get("name") in {
        "number",
        "integer",
        "percent",
        "offset",
        "currency",
        "relativeTime",
    }


def _inherited_option_value(call: FunctionCall, name: str) -> str | None:
    own = call.option_value(name, _ABSENT_OPTION)
    if own != _ABSENT_OPTION:
        return own
    source = call.inherited_source
    while source is not None:
        value = source.option_value(name, _ABSENT_OPTION)
        if value != _ABSENT_OPTION:
            return value
        source = source.inherited_source
    return None


def _parse_decimal_or_none(value: Any) -> Decimal | None:
    try:
        text = str(value)
    except Exception:
        return None
    if len(text) > _MAX_DECIMAL_OPERAND_LENGTH:
        return None
    if _DECIMAL_RE.fullmatch(text) is None or _parse_bounded_decimal_exponent(text) is None:
        return None
    try:
        parsed = Decimal(text)
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None


def _parse_bounded_decimal_exponent(value: str) -> int | None:
    exponent_start = max(value.find("e"), value.find("E"))
    if exponent_start < 0:
        return 0
    exponent_text = value[exponent_start + 1 :]
    negative = exponent_text.startswith("-")
    digits = exponent_text[1:] if exponent_text.startswith(("+", "-")) else exponent_text
    digits = digits.lstrip("0")
    if not digits:
        return 0
    if len(digits) > 7:
        return None
    parsed = int(digits)
    if parsed > _MAX_DECIMAL_EXPONENT:
        return None
    return -parsed if negative else parsed


def _parse_call_date(call: FunctionCall, message: str) -> date:
    parsed = _date_from(call.raw_value, call.value)
    if parsed is None:
        parsed = _parse_source_date(call.inherited_source)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_call_time(call: FunctionCall, message: str) -> time:
    parsed = _time_from(call.raw_value, call.value)
    if parsed is None:
        parsed = _parse_source_time(call.inherited_source)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_call_datetime(call: FunctionCall, message: str) -> datetime:
    parsed = _datetime_from(call.raw_value, call.value)
    if parsed is None:
        parsed = _parse_source_datetime(call.inherited_source)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_source_date(source: object | None) -> date | None:
    while source is not None:
        if _is_date_time_source_function(getattr(source, "function")):
            parsed = _date_from(None, getattr(source, "value"))
            if parsed is not None:
                return parsed
        source = getattr(source, "inherited_source")
    return None


def _parse_source_time(source: object | None) -> time | None:
    while source is not None:
        if _is_date_time_source_function(getattr(source, "function")):
            parsed = _time_from(None, getattr(source, "value"))
            if parsed is not None:
                return parsed
        source = getattr(source, "inherited_source")
    return None


def _parse_source_datetime(source: object | None) -> datetime | None:
    while source is not None:
        if _is_date_time_source_function(getattr(source, "function")):
            parsed = _datetime_from(None, getattr(source, "value"))
            if parsed is not None:
                return parsed
        source = getattr(source, "inherited_source")
    return None


def _is_date_time_source_function(function: dict[str, Any]) -> bool:
    return function.get("name") in {"date", "time", "datetime"}


def _date_from(raw_value: Any, rendered: str) -> date | None:
    if isinstance(raw_value, datetime):
        return raw_value.date()
    if isinstance(raw_value, date):
        return raw_value
    if _ISO_DATE_RE.fullmatch(rendered) is None:
        parsed_datetime = _parse_datetime_or_none(rendered)
        if parsed_datetime is not None:
            return parsed_datetime.date()
        return None
    try:
        return date.fromisoformat(rendered)
    except ValueError:
        return None


def _time_from(raw_value: Any, rendered: str) -> time | None:
    if isinstance(raw_value, datetime):
        return raw_value.time()
    if isinstance(raw_value, time):
        return raw_value
    if _ISO_TIME_RE.fullmatch(rendered) is None:
        parsed_datetime = _parse_datetime_or_none(rendered)
        if parsed_datetime is not None:
            return parsed_datetime.time()
        return None
    if not _has_valid_iso_offset(rendered):
        return None
    try:
        return time.fromisoformat(rendered.replace("Z", "+00:00"))
    except ValueError:
        return None


def _datetime_from(raw_value: Any, rendered: str) -> datetime | None:
    if isinstance(raw_value, datetime):
        return raw_value
    if isinstance(raw_value, date):
        return datetime.combine(raw_value, time())
    parsed_datetime = _parse_datetime_or_none(rendered)
    if parsed_datetime is not None:
        return parsed_datetime
    return None


def _parse_datetime_or_none(rendered: str) -> datetime | None:
    if _ISO_DATE_TIME_RE.fullmatch(rendered) is None or not _has_valid_iso_offset(rendered):
        return None
    try:
        return datetime.fromisoformat(rendered.replace("Z", "+00:00"))
    except ValueError:
        return None


def _has_valid_iso_offset(value: str) -> bool:
    if value.endswith("Z"):
        return True
    match = re.search(r"([+-])([0-9]{2}):([0-9]{2})$", value)
    if match is None:
        return True
    hours = int(match.group(2))
    minutes = int(match.group(3))
    return hours < 18 or (hours == 18 and minutes == 0)


def _date_style(call: FunctionCall) -> str:
    return _style(call, ("dateStyle", "length", "style"), "medium", "Date style")


def _time_style(call: FunctionCall) -> str:
    return _style(call, ("timeStyle", "precision", "style"), "medium", "Time style")


def _datetime_style(call: FunctionCall) -> str:
    shared = call.option_value("style")
    date_style = _first_option(call, ("dateStyle", "dateLength"))
    time_style = _first_option(call, ("timeStyle", "timePrecision"))
    if date_style is not None and time_style is not None and date_style != time_style:
        raise MF2Error(
            "bad-option",
            "Babel datetime formatting currently requires dateStyle and timeStyle to match.",
        )
    return _validate_style(date_style or time_style or shared or "medium", "Datetime style")


def _style(
    call: FunctionCall,
    option_names: tuple[str, ...],
    default: str,
    label: str,
) -> str:
    return _validate_style(_first_option(call, option_names) or default, label)


def _first_option(call: FunctionCall, option_names: tuple[str, ...]) -> str | None:
    for option_name in option_names:
        value = call.option_value(option_name)
        if value is not None:
            return value
    return None


def _validate_style(value: str, label: str) -> str:
    if value in {"full", "long", "medium", "short"}:
        return value
    raise MF2Error(
        "bad-option",
        f"{label} option must be one of full, long, medium, short.",
    )


def _option_one_of(
    call: FunctionCall,
    option_name: str,
    allowed: set[str],
    default: str,
) -> str:
    value = call.option_value(option_name, default) or default
    if value not in allowed:
        raise MF2Error(
            "bad-option",
            f"{option_name} option must be one of {', '.join(sorted(allowed))}.",
        )
    return value


def _time_zone(call: FunctionCall) -> Any:
    value = call.option_value("timeZone", "UTC") or "UTC"
    try:
        return get_timezone(value)
    except Exception as error:
        raise MF2Error(
            "bad-option",
            "timeZone option must be a valid time zone identifier.",
        ) from error


def _timedelta(value: Decimal, unit: str) -> timedelta:
    amount = float(value)
    return {
        "second": timedelta(seconds=amount),
        "minute": timedelta(minutes=amount),
        "hour": timedelta(hours=amount),
        "day": timedelta(days=amount),
        "week": timedelta(weeks=amount),
        "month": timedelta(days=amount * 30),
        "year": timedelta(days=amount * 365),
    }[unit]


def _apply_sign_display(rendered: str, value: Decimal, call: FunctionCall) -> str:
    if value >= 0 and _function_option_literal(call.function, "signDisplay") == "always":
        return f"+{rendered}"
    return rendered


def _function_option_literal(
    function_ref: dict[str, object],
    name: str,
    fallback: str | None = None,
) -> str | None:
    options = function_ref.get("options")
    option = options.get(name) if isinstance(options, dict) else None
    if isinstance(option, dict) and option.get("type") == "literal":
        return str(option.get("value", ""))
    return fallback
