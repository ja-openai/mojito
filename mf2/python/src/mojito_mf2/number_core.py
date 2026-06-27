from __future__ import annotations

from decimal import Decimal, InvalidOperation, ROUND_DOWN, ROUND_HALF_UP
import re
from typing import Any

from ._cldr_number_data import NUMBER_DATA
from ._locale_key import locale_lookup_chain
from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry

__all__ = [
    "format_number_core",
    "format_number_core_to_parts",
    "number_core_function_registry",
]

_DEFAULT_LOCALE = "en-US"
_ABSENT_OPTION = "\x00__mojito_mf2_absent__"
_MAX_LOCALE_LENGTH = 256
_MAX_OPTION_LENGTH = 256
_MAX_OPERAND_LENGTH = 256
_MAX_FRACTION_DIGITS = 100
_MAX_ABSOLUTE_FORMAT_VALUE = Decimal("1e21")
_DECIMAL_RE = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
_CURRENCY_RE = re.compile(r"^[A-Za-z]{3}$")
_NUMBER_PATTERN_RE = re.compile(r"[#0,.]+")
_DIGIT_ZERO = ord("0")


def format_number_core(
    value: Any,
    *,
    locale: str = _DEFAULT_LOCALE,
    style: str = "number",
    currency: str | None = None,
    currencyDisplay: str = "symbol",
    useGrouping: bool | str = True,
    minimumFractionDigits: int | str | None = None,
    maximumFractionDigits: int | str | None = None,
    signDisplay: str = "auto",
) -> str:
    locale = _coerce_string_option(locale, "locale")
    _validate_locale_length(locale)
    style = _option_one_of(style, {"number", "integer", "percent", "currency"}, "style")
    signDisplay = _option_one_of(signDisplay, {"auto", "always", "never"}, "signDisplay")
    locale_data = _resolve_locale_data(locale)
    parsed = _parse_finite_decimal(value)
    if parsed is None:
        raise MF2Error("bad-operand", "Number core requires a finite numeric value.")

    currency_code = _parse_currency(currency) if style == "currency" else None
    pattern = _pattern_for_style(locale_data, style)
    fraction = _fraction_options(
        style,
        currency_code,
        minimumFractionDigits,
        maximumFractionDigits,
        pattern,
    )
    normalized = parsed.to_integral_value(rounding=ROUND_DOWN) if style == "integer" else parsed
    scaled = normalized * Decimal(100) if style == "percent" else normalized
    _ensure_supported_magnitude(scaled)
    formatted = _format_decimal(
        abs(scaled),
        locale_data,
        pattern,
        fraction,
        _boolean_option(useGrouping, True),
    )

    if style == "percent":
        return _apply_signed_pattern(
            pattern,
            formatted,
            scaled,
            locale_data["symbols"],
            signDisplay,
            percent_sign=locale_data["symbols"]["percentSign"],
        )
    if style == "currency":
        assert currency_code is not None
        return _apply_signed_pattern(
            pattern,
            formatted,
            scaled,
            locale_data["symbols"],
            signDisplay,
            currency=_currency_display(locale_data, currency_code, currencyDisplay),
        )
    return _apply_sign(formatted, scaled, locale_data["symbols"], signDisplay)


def format_number_core_to_parts(value: Any, **options: Any) -> list[dict[str, str]]:
    return [{"type": "text", "value": format_number_core(value, **options)}]


def number_core_function_registry() -> FunctionRegistry:
    return (
        FunctionRegistry.portable()
        .with_function("number", lambda call: _format_call_number(call, "number"))
        .with_function("integer", lambda call: _format_call_number(call, "integer"))
        .with_function("percent", lambda call: _format_call_number(call, "percent"))
        .with_function("currency", lambda call: _format_call_number(call, "currency"))
    )


def _format_call_number(call: FunctionCall, style: str) -> str:
    return format_number_core(
        _call_number_value(call, style),
        locale=call.locale,
        style=style,
        currency=_currency_option(call, style),
        currencyDisplay=call.option_value("currencyDisplay", "symbol") or "symbol",
        minimumFractionDigits=call.option_value("minimumFractionDigits"),
        maximumFractionDigits=call.option_value("maximumFractionDigits"),
        signDisplay=call.option_value("signDisplay", "auto") or "auto",
        useGrouping=call.option_value("useGrouping", "true"),
    )


def _currency_option(call: FunctionCall, style: str) -> str | None:
    if style != "currency":
        return call.option_value("currency")
    return _inherited_option_value(call, "currency")


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


def _call_number_value(call: FunctionCall, style: str) -> Any:
    source = call.inherited_source
    if source is not None:
        if style == "number" and source.function.get("name") == "integer":
            parsed = _parse_finite_decimal(source.value)
            if parsed is not None:
                return parsed.to_integral_value(rounding=ROUND_DOWN)
        return source.value
    return call.raw_value if call.raw_value is not None else call.value


def _resolve_locale_data(locale: str) -> dict[str, Any]:
    locales = NUMBER_DATA["locales"]
    for candidate in locale_lookup_chain(locale or _DEFAULT_LOCALE):
        exact = locales.get(candidate)
        if exact is not None:
            return exact
        for locale_data in locales.values():
            if locale_data.get("numbersSourceLocale") == candidate:
                return locale_data
    return locales[_DEFAULT_LOCALE]


def _validate_locale_length(locale: str) -> None:
    if len(locale) > _MAX_LOCALE_LENGTH:
        raise MF2Error("bad-option", "locale must not exceed 256 characters.")


def _pattern_for_style(locale_data: dict[str, Any], style: str) -> str:
    if style == "percent":
        return str(locale_data["percentPattern"])
    if style == "currency":
        return str(locale_data["currencyPattern"])
    return str(locale_data["decimalPattern"])


def _fraction_options(
    style: str,
    currency: str | None,
    minimum_fraction_digits: int | str | None,
    maximum_fraction_digits: int | str | None,
    pattern: str,
) -> tuple[int, int]:
    minimum, maximum = _fraction_defaults_from_pattern(pattern)
    if style == "integer":
        minimum, maximum = 0, 0
    elif style == "currency":
        fractions = NUMBER_DATA["currencyFractions"]
        currency_defaults = fractions.get(currency, fractions["DEFAULT"])
        minimum = maximum = int(currency_defaults.get("digits", 2))

    minimum = _non_negative_integer_option(
        minimum_fraction_digits,
        minimum,
        "minimumFractionDigits",
    )
    maximum = _non_negative_integer_option(
        maximum_fraction_digits,
        maximum,
        "maximumFractionDigits",
    )
    if minimum_fraction_digits is not None and maximum_fraction_digits is None and maximum < minimum:
        maximum = minimum
    if maximum_fraction_digits is not None and minimum_fraction_digits is None and maximum < minimum:
        minimum = maximum
    if maximum < minimum:
        raise MF2Error(
            "bad-option",
            "maximumFractionDigits must be greater than or equal to minimumFractionDigits.",
        )
    return minimum, maximum


def _fraction_defaults_from_pattern(pattern: str) -> tuple[int, int]:
    number_pattern = _number_pattern(pattern)
    if "." not in number_pattern:
        return 0, 0
    fraction = number_pattern.split(".", 1)[1]
    return fraction.count("0"), len(fraction)


def _format_decimal(
    value: Decimal,
    locale_data: dict[str, Any],
    pattern: str,
    fraction: tuple[int, int],
    use_grouping: bool,
) -> str:
    minimum_fraction, maximum_fraction = fraction
    quant = Decimal(1).scaleb(-maximum_fraction)
    rounded = value.quantize(quant, rounding=ROUND_HALF_UP)
    integer, decimal = _decimal_parts(rounded)
    while len(decimal) > minimum_fraction and decimal.endswith("0"):
        decimal = decimal[:-1]
    while len(decimal) < minimum_fraction:
        decimal += "0"

    grouping = _grouping_info(pattern)
    if use_grouping and _should_group(integer, grouping, int(locale_data["minimumGroupingDigits"])):
        integer = _group_integer(integer, grouping, locale_data["symbols"]["group"])
    integer = _localize_digits(integer, locale_data.get("numberingSystemDigits"))
    if decimal:
        return (
            integer
            + locale_data["symbols"]["decimal"]
            + _localize_digits(decimal, locale_data.get("numberingSystemDigits"))
        )
    return integer


def _decimal_parts(value: Decimal) -> tuple[str, str]:
    text = format(value, "f")
    if "." not in text:
        return text, ""
    return tuple(text.split(".", 1))  # type: ignore[return-value]


def _grouping_info(pattern: str) -> tuple[int, int]:
    integer_pattern = _number_pattern(pattern).split(".", 1)[0]
    groups = integer_pattern.split(",")
    if len(groups) == 1:
        return 0, 0
    primary = _placeholder_count(groups[-1])
    secondary = _placeholder_count(groups[-2]) if len(groups) > 2 else primary
    return primary, secondary


def _should_group(integer: str, grouping: tuple[int, int], minimum_grouping_digits: int) -> bool:
    primary, _ = grouping
    return primary > 0 and len(integer) >= primary + minimum_grouping_digits


def _group_integer(integer: str, grouping: tuple[int, int], separator: str) -> str:
    primary, secondary = grouping
    groups: list[str] = []
    end = len(integer)
    size = primary
    while end > 0:
        start = max(0, end - size)
        groups.insert(0, integer[start:end])
        end = start
        size = secondary or primary
    return separator.join(groups)


def _apply_sign(
    formatted: str,
    value: Decimal,
    symbols: dict[str, str],
    sign_display: str,
) -> str:
    if sign_display == "never":
        return formatted
    if _is_negative(value):
        return symbols["minusSign"] + formatted
    if sign_display == "always":
        return symbols["plusSign"] + formatted
    return formatted


def _apply_pattern(
    pattern: str,
    formatted: str,
    *,
    percent_sign: str | None = None,
    currency: str | None = None,
) -> str:
    output = _NUMBER_PATTERN_RE.sub(lambda _: formatted, pattern, count=1)
    if percent_sign is not None:
        output = output.replace("%", percent_sign)
    if currency is not None:
        output = output.replace("¤", currency)
    return output


def _apply_signed_pattern(
    pattern: str,
    formatted: str,
    value: Decimal,
    symbols: dict[str, str],
    sign_display: str,
    *,
    percent_sign: str | None = None,
    currency: str | None = None,
) -> str:
    positive_pattern, separator, negative_pattern = pattern.partition(";")
    if _is_negative(value) and sign_display != "never":
        if separator:
            return _apply_pattern(
                negative_pattern,
                formatted,
                percent_sign=percent_sign,
                currency=currency,
            )
        return symbols["minusSign"] + _apply_pattern(
            positive_pattern,
            formatted,
            percent_sign=percent_sign,
            currency=currency,
        )
    output = _apply_pattern(
        positive_pattern,
        formatted,
        percent_sign=percent_sign,
        currency=currency,
    )
    if sign_display == "always":
        return symbols["plusSign"] + output
    return output


def _is_negative(value: Decimal) -> bool:
    return value.is_signed()


def _currency_display(locale_data: dict[str, Any], currency: str, display: str) -> str:
    display = _option_one_of(display, {"symbol", "narrowSymbol", "code"}, "currencyDisplay")
    if display == "code":
        return _currency_code_display(locale_data, currency)
    data = locale_data["currencies"].get(currency, {})
    return str(data.get(display) or data.get("symbol") or currency)


def _currency_code_display(locale_data: dict[str, Any], currency: str) -> str:
    positive_pattern = str(locale_data.get("currencyPattern", "")).split(";", 1)[0]
    before = (
        _currency_spacing_insert(locale_data, "beforeCurrency")
        if re.search(r"[#0]\u00a4", positive_pattern)
        else ""
    )
    after = (
        _currency_spacing_insert(locale_data, "afterCurrency")
        if re.search(r"\u00a4[#0]", positive_pattern)
        else ""
    )
    return before + currency + after


def _currency_spacing_insert(locale_data: dict[str, Any], direction: str) -> str:
    rule = locale_data.get("currencySpacing", {}).get(direction, {})
    if isinstance(rule, dict):
        return str(rule.get("insertBetween") or "\u00a0")
    return "\u00a0"


def _parse_finite_decimal(value: Any) -> Decimal | None:
    if isinstance(value, bool):
        return None
    text = _coerce_string_operand(value).strip()
    if len(text) > _MAX_OPERAND_LENGTH:
        return None
    if not _DECIMAL_RE.fullmatch(text):
        return None
    try:
        parsed = Decimal(text)
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None


def _ensure_supported_magnitude(value: Decimal) -> None:
    if not value.is_finite() or abs(value) >= _MAX_ABSOLUTE_FORMAT_VALUE:
        raise MF2Error("bad-operand", "Number core numeric value is outside the supported magnitude.")


def _parse_currency(value: Any) -> str:
    text = _coerce_string_option(value, "currency")
    if _CURRENCY_RE.fullmatch(text) is None:
        raise MF2Error("bad-option", "currency must be a three-letter ISO 4217 code.")
    return text.upper()


def _non_negative_integer_option(value: int | str | None, fallback: int, name: str) -> int:
    if value is None:
        return fallback
    text = _coerce_string_option(value, name)
    if not text or not all("0" <= ch <= "9" for ch in text):
        raise MF2Error("bad-option", f"{name} must be a non-negative integer.")
    if len(text) > len(str(_MAX_FRACTION_DIGITS)):
        raise MF2Error("bad-option", f"{name} must be a non-negative integer.")
    parsed = int(text)
    if parsed > _MAX_FRACTION_DIGITS:
        raise MF2Error("bad-option", f"{name} must be a non-negative integer.")
    return parsed


def _coerce_string_operand(value: Any) -> str:
    try:
        return str(value)
    except Exception as error:
        raise MF2Error("bad-operand", "Number core requires a finite numeric value.") from error


def _coerce_string_option(value: Any, name: str) -> str:
    try:
        text = str(value)
    except Exception as error:
        raise MF2Error("bad-option", f"{name} must be coercible to a string.") from error
    if len(text) > _MAX_OPTION_LENGTH:
        raise MF2Error("bad-option", f"{name} must not exceed 256 characters.")
    return text


def _option_one_of(value: Any, allowed: set[str], name: str) -> str:
    text = _coerce_string_option(value, name)
    if text not in allowed:
        raise MF2Error("bad-option", f"{name} must be one of {', '.join(sorted(allowed))}.")
    return text


def _boolean_option(value: bool | str, fallback: bool) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return fallback
    text = _coerce_string_option(value, "useGrouping")
    if text == "true":
        return True
    if text == "false":
        return False
    raise MF2Error("bad-option", "useGrouping must be true or false.")


def _number_pattern(pattern: str) -> str:
    match = _NUMBER_PATTERN_RE.search(pattern)
    return "" if match is None else match.group(0)


def _placeholder_count(pattern: str) -> int:
    return pattern.count("#") + pattern.count("0")


def _localize_digits(value: str, digits: str | None) -> str:
    if digits is None or digits == "0123456789":
        return value
    return "".join(
        digits[ord(ch) - _DIGIT_ZERO] if "0" <= ch <= "9" else ch
        for ch in value
    )
