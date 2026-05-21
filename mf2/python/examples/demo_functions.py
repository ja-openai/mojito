from __future__ import annotations

from mf2_runtime import FunctionCall, FunctionRegistry, MF2Error


def demo_function_registry() -> FunctionRegistry:
    return (
        FunctionRegistry.defaults()
        .with_function("currency", _format_currency)
        .with_function("rawType", _format_raw_type)
    )


def _format_currency(call: FunctionCall) -> str:
    currency = call.option_value("currency", "USD")
    return _format_currency_value(call.value, currency or "USD", call.locale)


def _format_raw_type(call: FunctionCall) -> str:
    value = call.raw_value
    if isinstance(value, bool):
        kind = "bool"
    elif isinstance(value, int | float):
        kind = "number"
    elif value is None:
        kind = "null"
    else:
        kind = "string"
    return f"{kind}={call.value}"


def _format_currency_value(value: str, currency: str, locale: str) -> str:
    try:
        amount = float(value)
    except ValueError as error:
        raise MF2Error("bad-operand", f"Currency value must be numeric, got {value}.") from error
    if amount in {float("inf"), float("-inf")} or amount != amount:
        raise MF2Error("bad-operand", "Currency value must be finite.")

    currency = currency.upper()
    fraction_digits = _currency_fraction_digits(currency)
    scale = 10**fraction_digits
    rounded = round(abs(amount) * scale)
    major = rounded // scale
    fraction = rounded % scale
    french = _canonical_locale_prefix(locale) == "fr"
    grouped = _group_digits(str(major), "\u202f" if french else ",")
    if fraction_digits == 0:
        number = grouped
    else:
        decimal = "," if french else "."
        number = f"{grouped}{decimal}{fraction:0{fraction_digits}d}"
    symbol = _currency_symbol(currency, french)
    negative = "-" if amount < 0 else ""

    if french:
        return f"{negative}{number} {symbol}"
    if len(symbol) == 3:
        return f"{negative}{symbol} {number}"
    return f"{negative}{symbol}{number}"


def _currency_fraction_digits(currency: str) -> int:
    return 0 if currency in {"JPY", "KRW"} else 2


def _currency_symbol(currency: str, french: bool) -> str:
    if currency == "USD":
        return "$US" if french else "$"
    if currency == "EUR":
        return "€"
    if currency == "JPY":
        return "¥"
    if currency == "GBP":
        return "£"
    return currency


def _canonical_locale_prefix(locale: str) -> str:
    return locale.replace("_", "-").split("-", 1)[0].lower() or "en"


def _group_digits(digits: str, separator: str) -> str:
    groups: list[str] = []
    while len(digits) > 3:
        groups.append(digits[-3:])
        digits = digits[:-3]
    groups.append(digits)
    return separator.join(reversed(groups))
