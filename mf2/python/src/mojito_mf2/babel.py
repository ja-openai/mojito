from __future__ import annotations

from datetime import date, datetime, time, timedelta
from decimal import Decimal, DecimalException, ROUND_DOWN, localcontext
from typing import Any

try:
    from babel import Locale, UnknownLocaleError
    from babel.dates import (
        format_date,
        format_datetime,
        format_time,
        format_timedelta,
        get_timezone,
    )
    from babel.numbers import format_currency, format_decimal, format_percent
except ModuleNotFoundError as error:
    raise ImportError(
        'Babel support is optional. Install it with: pip install "mojito-mf2[babel]"'
    ) from error

from ._locale_key import _validate_locale_input, canonical_locale_key
from ._portable_functions import (
    _decimal_precision,
    _iter_source_chain,
    _numeric_option_value,
    _parse_call_decimal,
    _parse_non_negative_integer_option,
)
from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry, FunctionSource

__all__ = ["babel_function_registry"]


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
    value = _parse_call_decimal(
        call, "Number function requires a numeric operand."
    )
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value, _maximum_fraction_digits(call))
            rendered = format_decimal(
                value,
                format=_decimal_pattern(call),
                locale=_babel_locale(call),
                decimal_quantization=_maximum_fraction_digits(call) is not None,
            )
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Number function requires a bounded numeric operand."
        ) from error
    return _apply_sign_display(rendered, value, call)


def _format_percent(call: FunctionCall) -> str:
    value = _parse_call_decimal(
        call, "Percent function requires a numeric operand."
    )
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value, _maximum_fraction_digits(call)) + 2
            rendered = format_percent(
                value,
                format=_decimal_pattern(call, suffix="%"),
                locale=_babel_locale(call),
                decimal_quantization=_maximum_fraction_digits(call) is not None,
            )
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Percent function requires a bounded numeric operand."
        ) from error
    return _apply_sign_display(rendered, value, call)


def _format_integer(call: FunctionCall) -> str:
    value = _parse_call_decimal(
        call, "Integer function requires a numeric operand."
    )
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value)
            integer = value.to_integral_value(rounding=ROUND_DOWN)
            rendered = format_decimal(
                integer, format="#,##0", locale=_babel_locale(call)
            )
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Integer function requires a bounded numeric operand."
        ) from error
    return _apply_sign_display(rendered, integer, call)


def _format_currency(call: FunctionCall) -> str:
    value = _parse_call_decimal(
        call, "Currency function requires a numeric operand."
    )
    direct_currency = call.option_value("currency")
    inherited_currency = _inherited_option_value(
        call.inherited_source, "currency", {"currency"}
    )
    if inherited_currency is not None and direct_currency is not None:
        raise MF2Error(
            "bad-option",
            "Currency option cannot override an existing currency operand.",
        )
    currency = inherited_currency or direct_currency
    if currency is None:
        raise MF2Error(
            "bad-operand",
            "Currency function requires a currency operand or currency option.",
        )
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value, 2)
            return format_currency(value, currency.upper(), locale=_babel_locale(call))
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Currency function requires a bounded numeric operand."
        ) from error
    except ValueError as error:
        raise MF2Error("bad-option", str(error)) from error


def _format_date(call: FunctionCall) -> str:
    value = _date_from(call.raw_value, call.value, call.inherited_source)
    return format_date(
        value,
        format=_date_style(call),
        locale=_babel_locale(call),
    )


def _format_time(call: FunctionCall) -> str:
    value = _time_from(call.raw_value, call.value, call.inherited_source)
    return format_time(
        value,
        format=_time_style(call),
        locale=_babel_locale(call),
        tzinfo=_time_zone(call, value),
    )


def _format_datetime(call: FunctionCall) -> str:
    value = _datetime_from(call.raw_value, call.value, call.inherited_source)
    return format_datetime(
        value,
        format=_datetime_style(call),
        locale=_babel_locale(call),
        tzinfo=_time_zone(call, value),
    )


def _format_relative_time(call: FunctionCall) -> str:
    value = _parse_call_decimal(
        call, "Relative time function requires a numeric operand."
    )
    unit = _option_one_of(
        call,
        "unit",
        {"second", "minute", "hour", "day", "week", "month", "year"},
        "second",
    )
    style = _option_one_of(call, "style", {"long", "short", "narrow"}, "long")
    numeric = _option_one_of(call, "numeric", {"always", "auto"}, "always")
    if numeric == "auto":
        raise MF2Error(
            "bad-option",
            "Babel relative time formatting does not support numeric=auto natural relative terms.",
        )
    try:
        return format_timedelta(
            _timedelta(value, unit),
            granularity=unit,
            add_direction=True,
            format=style,
            locale=_babel_locale(call),
        )
    except (DecimalException, OverflowError, ValueError) as error:
        raise MF2Error(
            "bad-operand", "Relative time function requires a bounded numeric operand."
        ) from error


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
    value = _numeric_option_value(call, name)
    if value is None:
        return None
    return _parse_non_negative_integer_option(
        value,
        f"{name} option must be a non-negative integer.",
    )


def _babel_locale(call: FunctionCall) -> Locale:
    try:
        _validate_locale_input(call.locale)
        raw_locale, separator, modifier = call.locale.partition("@")
        canonical = canonical_locale_key(raw_locale)
        if separator:
            canonical = f"{canonical}@{modifier}"
        return Locale.parse(canonical, sep="-")
    except (UnknownLocaleError, ValueError) as error:
        raise MF2Error("bad-option", "Unsupported locale identifier.") from error


def _date_from(
    raw_value: Any,
    rendered: str,
    source: FunctionSource | None,
) -> date:
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
        inherited = _inherited_source_value(source, {"date", "datetime"})
        if inherited is not None:
            inherited_date = _parse_date_or_datetime_date(inherited)
            if inherited_date is not None:
                return inherited_date
        raise MF2Error(
            "bad-operand", "Date function requires a date operand."
        ) from error


def _time_from(
    raw_value: Any,
    rendered: str,
    source: FunctionSource | None,
) -> time | datetime:
    if isinstance(raw_value, datetime):
        return raw_value
    if isinstance(raw_value, time):
        return raw_value
    try:
        return time.fromisoformat(rendered)
    except ValueError as error:
        parsed_datetime = _parse_datetime_or_none(rendered)
        if parsed_datetime is not None:
            return parsed_datetime
        inherited = _inherited_source_value(source, {"time", "datetime"})
        if inherited is not None:
            inherited_time = _parse_time_or_datetime(inherited)
            if inherited_time is not None:
                return inherited_time
        raise MF2Error(
            "bad-operand", "Time function requires a time operand."
        ) from error


def _datetime_from(
    raw_value: Any,
    rendered: str,
    source: FunctionSource | None,
) -> datetime:
    if isinstance(raw_value, datetime):
        return raw_value
    if isinstance(raw_value, date):
        return datetime.combine(raw_value, time())
    parsed_datetime = _parse_datetime_or_none(rendered)
    if parsed_datetime is not None:
        return parsed_datetime
    inherited = _inherited_source_value(source, {"date", "datetime"})
    if inherited is not None:
        inherited_datetime = _parse_datetime_or_none(inherited)
        if inherited_datetime is not None:
            return inherited_datetime
        try:
            return datetime.combine(date.fromisoformat(inherited), time())
        except ValueError:
            pass
    raise MF2Error("bad-operand", "Datetime function requires a datetime operand.")


def _parse_date_or_datetime_date(value: str) -> date | None:
    try:
        return date.fromisoformat(value)
    except ValueError:
        parsed_datetime = _parse_datetime_or_none(value)
        return parsed_datetime.date() if parsed_datetime is not None else None


def _parse_time_or_datetime(value: str) -> time | datetime | None:
    try:
        return time.fromisoformat(value)
    except ValueError:
        return _parse_datetime_or_none(value)


def _inherited_source_value(
    source: FunctionSource | None, function_names: set[str]
) -> str | None:
    if source is None:
        return None
    if source.function.get("name") not in function_names:
        return None
    return source.value


def _inherited_option_value(
    source: FunctionSource | None,
    option_name: str,
    function_names: set[str],
) -> str | None:
    for current in _iter_source_chain(source):
        if current.function.get("name") not in function_names:
            return None
        value = current.option_value(option_name)
        if value is not None:
            return value
    return None


def _parse_datetime_or_none(rendered: str) -> datetime | None:
    try:
        return datetime.fromisoformat(rendered.replace("Z", "+00:00"))
    except ValueError:
        return None


def _date_style(call: FunctionCall) -> str:
    return _style(call, ("dateStyle", "length", "style"), "medium", "Date style")


def _time_style(call: FunctionCall) -> str:
    time_style = call.option_value("timeStyle")
    if time_style is not None:
        return _validate_style(time_style, "Time style")
    precision = call.option_value("precision")
    if precision is not None:
        return _precision_style(precision, "Time precision")
    return _validate_style(
        call.option_value("style", "medium") or "medium", "Time style"
    )


def _datetime_style(call: FunctionCall) -> str:
    shared = call.option_value("style")
    date_style = call.option_value("dateStyle")
    time_style = call.option_value("timeStyle")
    if date_style is not None and time_style is not None and date_style != time_style:
        raise MF2Error(
            "bad-option",
            "Babel datetime formatting currently requires dateStyle and timeStyle to match.",
        )
    if date_style is not None or time_style is not None:
        return _validate_style(
            date_style or time_style or "medium", "Datetime style"
        )
    date_length = call.option_value("dateLength")
    time_precision = call.option_value("timePrecision")
    if date_length is not None:
        if time_precision is not None:
            _precision_style(time_precision, "Datetime timePrecision")
        return _validate_style(date_length, "Datetime dateLength")
    if time_precision is not None:
        return _precision_style(time_precision, "Datetime timePrecision")
    return _validate_style(shared or "medium", "Datetime style")


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


def _precision_style(value: str, label: str) -> str:
    if value == "second":
        return "medium"
    if value in {"hour", "minute"}:
        return "short"
    raise MF2Error(
        "bad-option",
        f"{label} option must be one of hour, minute, second.",
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


def _time_zone(call: FunctionCall, temporal_value: time | datetime) -> Any:
    option = call.option_value("timeZone", "UTC") or "UTC"
    if option == "input":
        input_timezone = getattr(temporal_value, "tzinfo", None)
        if input_timezone is None or temporal_value.utcoffset() is None:
            raise MF2Error(
                "bad-operand",
                "timeZone=input requires an operand with a time zone or offset.",
            )
        return input_timezone
    try:
        return get_timezone(option)
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
    if value >= 0 and _numeric_option_value(call, "signDisplay") == "always":
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
