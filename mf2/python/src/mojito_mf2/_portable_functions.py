"""Dependency-free formatter functions with explicit unlocalized numeric output."""

from __future__ import annotations

from collections.abc import Iterator
from decimal import (
    Decimal,
    DecimalException,
    InvalidOperation,
    ROUND_DOWN,
    localcontext,
)
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


_MAX_DECIMAL_DIGITS = 1_000
_MAX_DECIMAL_INTEGER_MAGNITUDE = 10**_MAX_DECIMAL_DIGITS
_MAX_DECIMAL_TEXT_LENGTH = (_MAX_DECIMAL_DIGITS * 2) + 8
_MAX_FRACTION_DIGITS = 1_000


def _format_unlocalized_number(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Number function requires a numeric operand.")
    formatted = _format_unlocalized_decimal_with_maximum_fraction_digits(
        value, _maximum_fraction_digits(call)
    )
    formatted = _append_minimum_fraction_digits(
        formatted, _minimum_fraction_digits(call)
    )
    return f"+{formatted}" if _sign_display_always(call) and value >= 0 else formatted


def _format_unlocalized_percent(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Percent function requires a numeric operand.")
    maximum_fraction_digits = _maximum_fraction_digits(call)
    minimum_fraction_digits = _minimum_fraction_digits(call)
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value, maximum_fraction_digits)
            percent = value * Decimal(100)
        _validate_decimal_operand(
            percent, "Percent function requires a bounded numeric operand."
        )
        formatted = _format_unlocalized_decimal_with_maximum_fraction_digits(
            percent,
            maximum_fraction_digits,
        )
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Percent function requires a bounded numeric operand."
        ) from error
    if _sign_display_always(call) and value >= 0:
        formatted = f"+{formatted}"
    return f"{_append_minimum_fraction_digits(formatted, minimum_fraction_digits)}%"


def _format_unlocalized_integer(call: "FunctionCall") -> str:
    value = _parse_call_decimal(call, "Integer function requires a numeric operand.")
    integer = value.to_integral_value(rounding=ROUND_DOWN)
    rendered = "0" if integer.is_zero() else format(integer, "f")
    return (
        f"+{rendered}"
        if _sign_display_always(call) and integer >= 0
        else rendered
    )


def _numeric_plural_operand(call: "FunctionCall") -> str:
    function_name = call.function.get("name")
    if function_name == "integer":
        value = _parse_call_decimal(
            call, "Integer function requires a numeric operand."
        ).to_integral_value(rounding=ROUND_DOWN)
        return _format_unlocalized_decimal(value)

    message = (
        "Percent function requires a numeric operand."
        if function_name == "percent"
        else "Number function requires a numeric operand."
    )
    value = _parse_call_decimal(call, message)
    if function_name == "percent":
        try:
            with localcontext() as context:
                context.prec = _decimal_precision(value) + 2
                value *= Decimal(100)
            _validate_decimal_operand(value, message)
        except DecimalException as error:
            raise MF2Error("bad-operand", message) from error

    formatted = _format_unlocalized_decimal_with_maximum_fraction_digits(
        value,
        _maximum_fraction_digits(call),
    )
    return _append_minimum_fraction_digits(
        formatted,
        _minimum_fraction_digits(call),
    )


def _offset(call: "FunctionCall") -> str:
    value = _parse_call_decimal(
        call, "Offset function requires a numeric operand."
    )
    add = call.option_value("add")
    subtract = call.option_value("subtract")
    if (add is None and subtract is None) or (add is not None and subtract is not None):
        raise MF2Error(
            "bad-option", "Offset function requires exactly one of add or subtract."
        )
    delta = _parse_integer(
        add if add is not None else subtract,
        "Offset add option must be an integer."
        if add is not None
        else "Offset subtract option must be an integer.",
    )
    result = _apply_integer_offset(
        value, delta if add is not None else -delta
    )
    return _format_unlocalized_decimal(
        result,
        sign_always=_sign_display_always(call),
    )


def _select_number(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Number selector cannot match this operand.")
    value = _parse_match_decimal(match, "Number selector requires a numeric operand.")
    _validate_numeric_variant_key(match.key)
    exact = _numeric_match_operand(match, value, "number")
    return 2 if exact is not None and match.key == exact else None


def _select_percent(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Percent selector cannot match this operand.")
    operand = _parse_match_decimal(
        match, "Percent selector requires a numeric operand."
    )
    _validate_numeric_variant_key(match.key)
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(operand) + 2
            value = operand * Decimal(100)
        _validate_decimal_operand(
            value, "Percent selector requires a bounded numeric operand."
        )
    except (DecimalException, MF2Error) as error:
        raise MF2Error(
            "bad-selector", "Percent selector requires a bounded numeric operand."
        ) from error
    exact = _numeric_match_operand(match, operand, "percent")
    return 2 if exact is not None and match.key == exact else None


def _select_integer(match: "FunctionMatch") -> int | None:
    if _invalid_numeric_selector(match.function, match.inherited_source):
        raise MF2Error("bad-selector", "Integer selector cannot match this operand.")
    value = _parse_match_decimal(match, "Integer selector requires a numeric operand.")
    _validate_numeric_variant_key(match.key)
    exact = _numeric_match_operand(match, value, "integer")
    return 2 if exact is not None and match.key == exact else None


def _select_offset(match: "FunctionMatch") -> int | None:
    value = _parse_match_decimal(
        match, "Offset selector requires a numeric operand."
    )
    _validate_numeric_variant_key(match.key)
    exact = _numeric_match_operand(match, value, "offset")
    return 2 if exact is not None and match.key == exact else None


def _validate_numeric_variant_key(key: str) -> None:
    if key in {"zero", "one", "two", "few", "many", "other"}:
        return
    if _parse_decimal_or_none(key) is None:
        raise MF2Error(
            "bad-variant-key",
            "Numeric selector keys must be number literals or plural keywords.",
        )


def _numeric_match_operand(
    match: "FunctionMatch", value: Decimal, function_name: str
) -> str | None:
    minimum = _numeric_option_value(match, "minimumFractionDigits", "0") or "0"
    maximum = _numeric_option_value(match, "maximumFractionDigits")
    minimum_digits = _parse_non_negative_integer_option(
        minimum,
        "minimumFractionDigits option must be a non-negative integer.",
    )
    maximum_digits = (
        None
        if maximum is None
        else _parse_non_negative_integer_option(
            maximum,
            "maximumFractionDigits option must be a non-negative integer.",
        )
    )
    return _numeric_selection_operand(
        value,
        function_name,
        minimum_digits,
        maximum_digits,
    )


def _numeric_selection_operand(
    value: object,
    function_name: str,
    minimum_fraction_digits: int = 0,
    maximum_fraction_digits: int | None = None,
) -> str | None:
    """Return a locale-neutral operand for CLDR plural selection.

    Platform formatters may render grouping separators and localized decimal
    symbols. Those display strings must never be reparsed as plural operands.
    This helper applies the numeric function's semantic transformation while
    retaining an ASCII decimal representation for the CLDR rules.
    """

    parsed = _parse_decimal_or_none(str(value))
    if parsed is None:
        return None
    try:
        if function_name == "integer":
            integer = parsed.to_integral_value(rounding=ROUND_DOWN)
            if integer.is_zero():
                return "0"
            if integer.adjusted() + 1 > _MAX_DECIMAL_DIGITS:
                return _integer_scientific_decimal(integer)
            return format(integer, "f")
        if function_name == "percent":
            with localcontext() as context:
                context.prec = _decimal_precision(parsed, maximum_fraction_digits) + 2
                parsed *= Decimal(100)
            _validate_decimal_operand(
                parsed, "Percent selector requires a bounded numeric operand."
            )
        if function_name in {"number", "percent", "offset"}:
            if (
                maximum_fraction_digits is None
                and minimum_fraction_digits == 0
                and parsed.adjusted() + 1 > _MAX_DECIMAL_DIGITS
            ):
                return _integer_scientific_decimal(parsed)
            rendered = _format_unlocalized_decimal_with_maximum_fraction_digits(
                parsed, maximum_fraction_digits
            )
            return _append_minimum_fraction_digits(
                rendered, minimum_fraction_digits
            )
    except (DecimalException, MF2Error):
        return None
    return str(value)


def _integer_scientific_decimal(value: Decimal) -> str:
    decimal_tuple = value.as_tuple()
    digits = list(decimal_tuple.digits)
    exponent = int(decimal_tuple.exponent)
    while len(digits) > 1 and digits[-1] == 0:
        digits.pop()
        exponent += 1
    sign = "-" if decimal_tuple.sign else ""
    coefficient = "".join(str(digit) for digit in digits)
    return f"{sign}{coefficient}E{exponent:+d}"


def _parse_call_decimal(call: "FunctionCall", message: str) -> Decimal:
    parsed = _parse_source_decimal(call.inherited_source)
    if parsed is None:
        parsed = _parse_decimal_or_none(call.value)
    if parsed is None:
        raise MF2Error("bad-operand", message)
    _validate_decimal_operand(parsed, message)
    return parsed


def _parse_match_decimal(match: "FunctionMatch", message: str) -> Decimal:
    parsed = _parse_source_decimal(match.inherited_source)
    if parsed is None:
        parsed = _parse_decimal_or_none(match.value)
    if parsed is None:
        raise MF2Error("bad-selector", message)
    try:
        _validate_decimal_operand(parsed, message)
    except MF2Error as error:
        raise MF2Error("bad-selector", message) from error
    return parsed


def _parse_source_decimal(source: object | None) -> Decimal | None:
    if source is None or not _is_decimal_source_function(
        getattr(source, "function")
    ):
        return None
    return _numeric_source_operand(source)


def _numeric_source_operand(source: object | None) -> Decimal | None:
    chain = list(_iter_source_chain(source))
    operand: Decimal | None = None
    for current in reversed(chain):
        if operand is None:
            operand = _parse_decimal_or_none(getattr(current, "value"))
        function = getattr(current, "function")
        if not _is_decimal_source_function(function) or operand is None:
            continue
        function_name = function.get("name")
        if function_name == "integer":
            operand = operand.to_integral_value(rounding=ROUND_DOWN)
        elif function_name == "offset":
            add = _source_option_value(current, "add")
            subtract = _source_option_value(current, "subtract")
            if (add is None) == (subtract is None):
                return None
            delta = _parse_integer_or_none(
                add if add is not None else subtract or ""
            )
            if delta is None:
                return None
            operand = _apply_integer_offset(
                operand, delta if add is not None else -delta
            )
    return operand


def _iter_source_chain(source: object | None) -> Iterator[object]:
    seen: set[int] = set()
    current = source
    while current is not None:
        identity = id(current)
        if identity in seen:
            raise MF2Error("bad-operand", "Function source chain contains a cycle.")
        seen.add(identity)
        yield current
        current = getattr(current, "inherited_source")


def _parse_decimal(value: str | None, message: str) -> Decimal:
    text = str(value if value is not None else "")
    if not _has_decimal_syntax(text):
        raise MF2Error("bad-operand", message)
    try:
        parsed = Decimal(text)
    except InvalidOperation as error:
        raise MF2Error("bad-operand", message) from error
    _validate_decimal_operand(parsed, message)
    return parsed


def _format_unlocalized_decimal(
    value: Decimal,
    minimum_fraction_digits: int = 0,
    sign_always: bool = False,
) -> str:
    _validate_decimal_operand(
        value, "Numeric operand exceeds the supported formatting range."
    )
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
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value, maximum_fraction_digits)
            quantized = value.quantize(Decimal(1).scaleb(-maximum_fraction_digits))
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Numeric operand exceeds the supported formatting range."
        ) from error
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
    value = _numeric_option_value(call, "minimumFractionDigits")
    if value is None:
        return 0
    return _parse_non_negative_integer_option(
        value,
        "minimumFractionDigits option must be a non-negative integer.",
    )


def _maximum_fraction_digits(call: "FunctionCall") -> int | None:
    value = _numeric_option_value(call, "maximumFractionDigits")
    if value is None:
        return None
    return _parse_non_negative_integer_option(
        value,
        "maximumFractionDigits option must be a non-negative integer.",
    )


def _parse_non_negative_integer_option(value: str, message: str) -> int:
    text = str(value)
    if not text or not text.isascii() or not text.isdecimal() or len(text) > 4:
        raise MF2Error("bad-option", message)
    parsed = int(text)
    if parsed > _MAX_FRACTION_DIGITS:
        raise MF2Error(
            "bad-option",
            f"{message} The maximum supported value is {_MAX_FRACTION_DIGITS}.",
        )
    return parsed


def _validate_decimal_operand(value: Decimal, message: str) -> None:
    if (
        not value.is_finite()
        or len(value.as_tuple().digits) > _MAX_DECIMAL_DIGITS
        or abs(value.adjusted()) > _MAX_DECIMAL_DIGITS
    ):
        raise MF2Error("bad-operand", message)


def _decimal_precision(
    value: Decimal, maximum_fraction_digits: int | None = None
) -> int:
    integer_digits = max(value.adjusted() + 1, 1)
    coefficient_digits = len(value.as_tuple().digits)
    return max(
        integer_digits + (maximum_fraction_digits or 0) + 4, coefficient_digits + 4
    )


def _sign_display_always(call: "FunctionCall") -> bool:
    return _numeric_option_value(call, "signDisplay") == "always"


def _inherited_sign_display_always(source: object | None) -> bool:
    for current in _iter_source_chain(source):
        function = getattr(current, "function")
        if (
            function.get("name") in {"number", "integer"}
            and _source_option_value(current, "signDisplay") == "always"
        ):
            return True
    return False


def _numeric_option_value(
    call: "FunctionCall | FunctionMatch",
    name: str,
    fallback: str | None = None,
) -> str | None:
    value = call.option_value(name)
    if value is not None:
        return value
    return _source_numeric_option_value(
        call.inherited_source,
        name,
        fallback,
        target_function=str(call.function.get("name", "")),
    )


def _source_numeric_option_value(
    source: object | None,
    name: str,
    fallback: str | None = None,
    target_function: str = "number",
) -> str | None:
    if source is None or _numeric_option_is_discarded(target_function, name):
        return fallback
    for current in _iter_source_chain(source):
        function = getattr(current, "function")
        source_function = str(function.get("name", ""))
        if not _is_numeric_function(function) or _numeric_option_is_discarded(
            source_function, name
        ):
            return fallback
        value = _source_option_value(current, name)
        if value is not None:
            return value
    return fallback


def _numeric_option_is_discarded(function_name: str, option_name: str) -> bool:
    if function_name == "integer":
        return option_name in {
            "minimumFractionDigits",
            "maximumFractionDigits",
            "minimumSignificantDigits",
        }
    if function_name == "percent":
        return option_name in {
            "minimumIntegerDigits",
            "roundingIncrement",
            "select",
        }
    if function_name == "offset":
        return option_name in {"add", "subtract"}
    return False


def _apply_integer_offset(value: Decimal, delta: int) -> Decimal:
    try:
        with localcontext() as context:
            context.prec = _decimal_precision(value) + len(str(abs(delta))) + 2
            result = value + Decimal(delta)
        _validate_decimal_operand(
            result, "Offset function requires a bounded numeric operand."
        )
        return result
    except DecimalException as error:
        raise MF2Error(
            "bad-operand", "Offset function requires a bounded numeric operand."
        ) from error


def _function_option_literal(
    function_ref: dict[str, object], name: str, fallback: str | None = None
) -> str | None:
    option = (
        function_ref.get("options", {}).get(name)
        if isinstance(function_ref.get("options"), dict)
        else None
    )
    return (
        str(option.get("value", ""))
        if isinstance(option, dict) and option.get("type") == "literal"
        else fallback
    )


def _source_option_value(
    source: object | None, name: str, fallback: str | None = None
) -> str | None:
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


def _inherited_exact_numeric_source(
    source: object | None, target_function: str | None = None
) -> bool:
    if target_function is None:
        for current in _iter_source_chain(source):
            function = getattr(current, "function")
            if (
                _is_numeric_function(function)
                and _source_option_value(current, "select") == "exact"
            ):
                return True
        return False
    return (
        _source_numeric_option_value(
            source, "select", target_function=target_function
        )
        == "exact"
    )


def _invalid_numeric_selector(
    function_ref: dict[str, object], source: object | None
) -> bool:
    select = _function_option_literal(function_ref, "select")
    return _numeric_select_uses_variable(function_ref) or (
        select != "exact"
        and _inherited_exact_numeric_source(
            source, str(function_ref.get("name", ""))
        )
    )


def _parse_integer(value: str | None, message: str) -> int:
    text = str(value if value is not None else "")
    digits = text[1:] if text and text[0] in "+-" else text
    if (
        not digits
        or not digits.isascii()
        or not digits.isdecimal()
        or len(digits) > _MAX_FRACTION_DIGITS
    ):
        raise MF2Error(
            "bad-option" if "option" in message.lower() else "bad-operand", message
        )
    return int(text)


def _parse_integer_or_none(value: str) -> int | None:
    text = str(value)
    digits = text[1:] if text and text[0] in "+-" else text
    if (
        not digits
        or not digits.isascii()
        or not digits.isdecimal()
        or len(digits) > _MAX_FRACTION_DIGITS
    ):
        return None
    return int(text)


def _parse_decimal_or_none(value: str) -> Decimal | None:
    text = str(value)
    if not _has_decimal_syntax(text):
        return None
    try:
        parsed = Decimal(text)
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None


def _has_decimal_syntax(text: str) -> bool:
    if not text or len(text) > _MAX_DECIMAL_TEXT_LENGTH or not text.isascii():
        return False

    unsigned = text.removeprefix("-")
    significand, separator, exponent = unsigned.partition("e")
    if not separator:
        significand, separator, exponent = unsigned.partition("E")
    if separator:
        exponent_digits = exponent[1:] if exponent.startswith(("+", "-")) else exponent
        if not exponent_digits.isdecimal():
            return False

    integer, decimal_point, fraction = significand.partition(".")
    if not integer.isdecimal() or (len(integer) > 1 and integer.startswith("0")):
        return False
    return not decimal_point or bool(fraction) and fraction.isdecimal()
