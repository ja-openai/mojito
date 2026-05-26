"""Dependency-free formatter functions with explicit unlocalized numeric output."""

from __future__ import annotations

from decimal import Decimal, InvalidOperation, ROUND_DOWN
import re
from typing import TYPE_CHECKING

from .errors import MF2Error

if TYPE_CHECKING:
    from .functions import Formatter, FunctionCall, FunctionMatch, Selector


def portable_formatters() -> dict[str, "Formatter"]:
    return {
        "string": _passthrough,
        "number": _format_unlocalized_number,
        "percent": _format_unlocalized_percent,
        "integer": _format_unlocalized_integer,
        "offset": _offset,
    }


def portable_selectors() -> dict[str, "Selector"]:
    return {
        "number": _select_number,
        "percent": _select_percent,
        "integer": _select_integer,
        "offset": _select_offset,
    }


def _passthrough(call: "FunctionCall") -> str:
    return call.value


_DECIMAL_RE = re.compile(r"^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$")


def _format_unlocalized_number(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Number function requires a numeric operand.")
    return _format_unlocalized_decimal(
        value,
        _minimum_fraction_digits(call),
        _sign_display_always(call.function),
    )


def _format_unlocalized_percent(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Percent function requires a numeric operand.")
    formatted = _format_unlocalized_decimal_with_maximum_fraction_digits(
        value * Decimal(100),
        _maximum_fraction_digits(call),
    )
    if _sign_display_always(call.function) and value >= 0:
        formatted = f"+{formatted}"
    return f"{_append_minimum_fraction_digits(formatted, _minimum_fraction_digits(call))}%"


def _format_unlocalized_integer(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Integer function requires a numeric operand.")
    integer = int(value.to_integral_value(rounding=ROUND_DOWN))
    return f"+{integer}" if _sign_display_always(call.function) and integer >= 0 else str(integer)


def _offset(call: "FunctionCall") -> str:
    value = _parse_integer(call.value, "Offset function requires a numeric operand.")
    add = call.option_value("add")
    subtract = call.option_value("subtract")
    if (add is None and subtract is None) or (add is not None and subtract is not None):
        raise MF2Error("bad-option", "Offset function requires exactly one of add or subtract.")
    delta = _parse_integer(
        add if add is not None else subtract,
        "Offset add option must be an integer." if add is not None else "Offset subtract option must be an integer.",
    )
    result = value + (delta if add is not None else -delta)
    return f"+{result}" if _inherited_sign_display_always(call.inherited_source) and result >= 0 else str(result)


def _select_number(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Number selector cannot match this operand.")
    value = _parse_match_decimal(match, "Number selector requires a numeric operand.")
    key = _parse_decimal_or_none(match.key)
    return 1 if key is not None and value == key else None


def _select_percent(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Percent selector cannot match this operand.")
    value = _parse_match_decimal(match, "Percent selector requires a numeric operand.") * Decimal(100)
    key = _parse_decimal_or_none(match.key)
    return 1 if key is not None and value == key else None


def _select_integer(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Integer selector cannot match this operand.")
    value = _parse_match_decimal(match, "Integer selector requires a numeric operand.")
    key = _parse_integer_or_none(match.key)
    return (
        1
        if key is not None and int(value.to_integral_value(rounding=ROUND_DOWN)) == key
        else None
    )


def _select_offset(match: "FunctionMatch") -> int | None:
    value = _parse_integer(match.value, "Offset selector requires a numeric operand.")
    key = _parse_integer_or_none(match.key)
    return 1 if key is not None and value == key else None


def _parse_call_decimal(call: "FunctionCall", message: str) -> Decimal:
    parsed = _parse_decimal_or_none(call.value)
    if parsed is None:
        parsed = _parse_source_decimal(call.inherited_source)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_match_decimal(match: "FunctionMatch", message: str) -> Decimal:
    parsed = _parse_decimal_or_none(match.value)
    if parsed is None:
        parsed = _parse_source_decimal(match.inherited_source)
    if parsed is None:
        raise MF2Error("bad-selector", message)
    return parsed


def _parse_source_decimal(source: object | None) -> Decimal | None:
    if source is None:
        return None
    function = getattr(source, "function")
    if _is_decimal_source_function(function):
        return _parse_decimal_or_none(getattr(source, "value"))
    return _parse_source_decimal(getattr(source, "inherited_source"))


def _parse_decimal(value: str | None, message: str) -> Decimal:
    text = str(value if value is not None else "")
    if not _DECIMAL_RE.fullmatch(text):
        raise MF2Error("bad-operand", message)
    try:
        parsed = Decimal(text)
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    if not parsed.is_finite():
        raise MF2Error("bad-operand", message)
    return parsed


def _format_unlocalized_decimal(
    value: Decimal,
    minimum_fraction_digits: int = 0,
    sign_always: bool = False,
) -> str:
    output = format(value.normalize(), "f")
    if "E" in output or "e" in output:
        output = format(value, "f")
    if "." in output:
        integer, fraction = output.split(".", 1)
        fraction = fraction.rstrip("0")
    else:
        integer, fraction = output, ""
    output = _append_minimum_fraction_digits(
        f"{integer}.{fraction}" if fraction else integer,
        minimum_fraction_digits,
    )
    output = output if output not in {"", "-0"} else "0"
    return f"+{output}" if sign_always and value >= 0 else output


def _format_unlocalized_decimal_with_maximum_fraction_digits(
    value: Decimal,
    maximum_fraction_digits: int | None,
) -> str:
    if maximum_fraction_digits is None:
        return _format_unlocalized_decimal(value)
    quantized = value.quantize(Decimal(1).scaleb(-maximum_fraction_digits))
    return _format_unlocalized_decimal(quantized)


def _append_minimum_fraction_digits(value: str, minimum_fraction_digits: int) -> str:
    if minimum_fraction_digits == 0:
        return value
    if "." in value:
        integer, fraction = value.split(".", 1)
    else:
        integer, fraction = value, ""
    if minimum_fraction_digits > len(fraction):
        fraction += "0" * (minimum_fraction_digits - len(fraction))
    if fraction:
        output = f"{integer}.{fraction}"
    else:
        output = integer
    return output if output not in {"", "-0"} else "0"


def _minimum_fraction_digits(call: "FunctionCall") -> int:
    value = call.option_value("minimumFractionDigits")
    if value is None:
        return 0
    return _parse_non_negative_integer_option(
        value,
        "minimumFractionDigits option must be a non-negative integer.",
    )


def _maximum_fraction_digits(call: "FunctionCall") -> int | None:
    value = call.option_value("maximumFractionDigits")
    if value is None:
        return None
    return _parse_non_negative_integer_option(
        value,
        "maximumFractionDigits option must be a non-negative integer.",
    )


def _parse_non_negative_integer_option(value: str, message: str) -> int:
    text = str(value)
    if not text or not text.isdigit():
        raise MF2Error("bad-option", message)
    return int(text)


def _sign_display_always(function_ref: dict[str, object]) -> bool:
    return _function_option_literal(function_ref, "signDisplay") == "always"


def _inherited_sign_display_always(source: object | None) -> bool:
    if source is None:
        return False
    function = getattr(source, "function")
    if function.get("name") in {"number", "integer"} and _source_option_value(source, "signDisplay") == "always":
        return True
    return _inherited_sign_display_always(getattr(source, "inherited_source"))


def _function_option_literal(function_ref: dict[str, object], name: str, fallback: str | None = None) -> str | None:
    option = function_ref.get("options", {}).get(name) if isinstance(function_ref.get("options"), dict) else None
    return str(option.get("value", "")) if isinstance(option, dict) and option.get("type") == "literal" else fallback


def _source_option_value(source: object | None, name: str, fallback: str | None = None) -> str | None:
    if source is None:
        return fallback
    option_value = getattr(source, "option_value")
    return option_value(name, fallback)


def _is_numeric_function(function_ref: dict[str, object]) -> bool:
    return function_ref.get("name") in {"number", "integer", "percent", "offset"}


def _is_decimal_source_function(function_ref: dict[str, object]) -> bool:
    return _is_numeric_function(function_ref) or function_ref.get("name") == "currency"


def _numeric_select_uses_variable(function_ref: dict[str, object]) -> bool:
    options = function_ref.get("options", {})
    select = options.get("select") if isinstance(options, dict) else None
    return isinstance(select, dict) and select.get("type") == "variable"


def _inherited_exact_numeric_source(source: object | None) -> bool:
    if source is None:
        return False
    function = getattr(source, "function")
    if _is_numeric_function(function) and _source_option_value(source, "select") == "exact":
        return True
    return _inherited_exact_numeric_source(getattr(source, "inherited_source"))


def _invalid_numeric_selector(function_ref: dict[str, object], source: object | None) -> bool:
    select = _function_option_literal(function_ref, "select")
    return _numeric_select_uses_variable(function_ref) or (select != "exact" and _inherited_exact_numeric_source(source))


def _parse_integer(value: str | None, message: str) -> int:
    text = str(value if value is not None else "")
    if not text or not (text.isdigit() or (text[0] in "+-" and text[1:].isdigit())):
        raise MF2Error("bad-option" if "option" in message.lower() else "bad-operand", message)
    return int(text)


def _parse_integer_or_none(value: str) -> int | None:
    text = str(value)
    if not text or not (text.isdigit() or (text[0] in "+-" and text[1:].isdigit())):
        return None
    return int(text)


def _parse_decimal_or_none(value: str) -> Decimal | None:
    text = str(value)
    if not _DECIMAL_RE.fullmatch(text):
        return None
    try:
        parsed = Decimal(text)
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None
