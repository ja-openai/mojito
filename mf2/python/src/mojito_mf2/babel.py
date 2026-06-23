from __future__ import annotations

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

from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry

__all__ = ["babel_function_registry"]

_MAX_FRACTION_DIGITS = 100


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
    value = _parse_decimal(call.value, "Number function requires a numeric operand.")
    return _apply_sign_display(
        format_decimal(
            value,
            format=_decimal_pattern(call),
            locale=call.locale,
            decimal_quantization=_maximum_fraction_digits(call) is not None,
        ),
        value,
        call,
    )


def _format_percent(call: FunctionCall) -> str:
    value = _parse_decimal(call.value, "Percent function requires a numeric operand.")
    return _apply_sign_display(
        format_percent(
            value,
            format=_decimal_pattern(call, suffix="%"),
            locale=call.locale,
            decimal_quantization=_maximum_fraction_digits(call) is not None,
        ),
        value,
        call,
    )


def _format_integer(call: FunctionCall) -> str:
    value = _parse_decimal(call.value, "Integer function requires a numeric operand.")
    integer = value.to_integral_value(rounding=ROUND_DOWN)
    return _apply_sign_display(
        format_decimal(integer, format="#,##0", locale=call.locale),
        integer,
        call,
    )


def _format_currency(call: FunctionCall) -> str:
    value = _parse_decimal(call.value, "Currency function requires a numeric operand.")
    currency = call.option_value("currency")
    if currency is None:
        raise MF2Error("bad-option", "Currency function requires a currency option.")
    try:
        return format_currency(value, currency.upper(), locale=call.locale)
    except Exception as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_date(call: FunctionCall) -> str:
    value = _date_from(call.raw_value, call.value)
    return format_date(
        value,
        format=_date_style(call),
        locale=call.locale,
    )


def _format_time(call: FunctionCall) -> str:
    value = _time_from(call.raw_value, call.value)
    return format_time(
        value,
        format=_time_style(call),
        locale=call.locale,
        tzinfo=_time_zone(call),
    )


def _format_datetime(call: FunctionCall) -> str:
    value = _datetime_from(call.raw_value, call.value)
    return format_datetime(
        value,
        format=_datetime_style(call),
        locale=call.locale,
        tzinfo=_time_zone(call),
    )


def _format_relative_time(call: FunctionCall) -> str:
    value = _parse_decimal(call.value, "Relative time function requires a numeric operand.")
    unit = _option_one_of(
        call,
        "unit",
        {"second", "minute", "hour", "day", "week", "month", "year"},
        "second",
    )
    style = _option_one_of(call, "style", {"long", "short", "narrow"}, "long")
    _option_one_of(call, "numeric", {"always", "auto"}, "always")
    return format_timedelta(
        _timedelta(value, unit),
        granularity=unit,
        add_direction=True,
        format=style,
        locale=call.locale,
    )


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
    if not value.isdigit():
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    if len(value) > len(str(_MAX_FRACTION_DIGITS)):
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    parsed = int(value)
    if parsed > _MAX_FRACTION_DIGITS:
        raise MF2Error("bad-option", f"{name} option must be a non-negative integer.")
    return parsed


def _parse_decimal(value: Any, message: str) -> Decimal:
    try:
        parsed = Decimal(str(value))
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    if not parsed.is_finite():
        raise MF2Error("bad-operand", message)
    return parsed


def _date_from(raw_value: Any, rendered: str) -> date:
    if isinstance(raw_value, datetime):
        return raw_value.date()
    if isinstance(raw_value, date):
        return raw_value
    try:
        return date.fromisoformat(rendered)
    except ValueError as error:
        parsed_datetime = _parse_datetime_or_none(rendered)
        if parsed_datetime is not None:
            return parsed_datetime.date()
        raise MF2Error("bad-operand", "Date function requires a date operand.") from error


def _time_from(raw_value: Any, rendered: str) -> time:
    if isinstance(raw_value, datetime):
        return raw_value.time()
    if isinstance(raw_value, time):
        return raw_value
    try:
        return time.fromisoformat(rendered)
    except ValueError as error:
        parsed_datetime = _parse_datetime_or_none(rendered)
        if parsed_datetime is not None:
            return parsed_datetime.time()
        raise MF2Error("bad-operand", "Time function requires a time operand.") from error


def _datetime_from(raw_value: Any, rendered: str) -> datetime:
    if isinstance(raw_value, datetime):
        return raw_value
    if isinstance(raw_value, date):
        return datetime.combine(raw_value, time())
    parsed_datetime = _parse_datetime_or_none(rendered)
    if parsed_datetime is not None:
        return parsed_datetime
    raise MF2Error("bad-operand", "Datetime function requires a datetime operand.")


def _parse_datetime_or_none(rendered: str) -> datetime | None:
    try:
        return datetime.fromisoformat(rendered.replace("Z", "+00:00"))
    except ValueError:
        return None


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
