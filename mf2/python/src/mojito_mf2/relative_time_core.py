from __future__ import annotations

from dataclasses import dataclass
import math
import re
from typing import Any, Mapping

from ._cldr_plural_rules import select_cardinal
from ._locale_key import locale_lookup_chain
from .errors import MF2Error
from .functions import FunctionCall, FunctionRegistry

__all__ = [
    "format_relative_time_core",
    "format_relative_time_core_to_parts",
    "relative_time_core_function_registry",
]

_DEFAULT_LOCALE = "en"
_MAX_LOCALE_LENGTH = 256
_MAX_OPTION_LENGTH = 256
_MAX_OPERAND_LENGTH = 256
_MAX_RELATIVE_TIME_QUANTITY = 1_000_000_000
_DECIMAL_NUMBER_RE = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
_STYLE_VALUES = {"long", "short", "narrow"}
_NUMERIC_VALUES = {"always", "auto"}
_POLICY_VALUES = {"precise", "compact", "chat"}
_UNIT_SECONDS = {
    "second": 1,
    "minute": 60,
    "hour": 3_600,
    "day": 86_400,
    "week": 604_800,
    "month": 2_592_000,
    "quarter": 7_776_000,
    "year": 31_536_000,
}
_UNIT_VALUES = {"auto", *_UNIT_SECONDS}
_POLICIES = {
    "precise": (
        (60, "second"),
        (3_600, "minute"),
        (86_400, "hour"),
        (604_800, "day"),
        (2_592_000, "week"),
        (31_536_000, "month"),
        (math.inf, "year"),
    ),
    "compact": (
        (60, "second"),
        (3_600, "minute"),
        (86_400, "hour"),
        (math.inf, "day"),
    ),
    "chat": (
        (45, "second"),
        (2_700, "minute"),
        (79_200, "hour"),
        (604_800, "day"),
        (math.inf, "week"),
    ),
}


@dataclass(frozen=True)
class _RelativeTimeData:
    locale_map: Mapping[str, str]
    pattern_sets: Mapping[str, Mapping[str, Any]]


def format_relative_time_core(
    value: Any,
    *,
    data: Mapping[str, Any],
    locale: str = _DEFAULT_LOCALE,
    style: str = "short",
    numeric: str = "always",
    policy: str = "precise",
    unit: str = "auto",
) -> str:
    prepared = _prepare_data(data)
    return _format_relative_time_prepared(
        value,
        data=prepared,
        locale=locale,
        style=style,
        numeric=numeric,
        policy=policy,
        unit=unit,
    )


def format_relative_time_core_to_parts(value: Any, **options: Any) -> list[dict[str, str]]:
    return [{"type": "text", "value": format_relative_time_core(value, **options)}]


def relative_time_core_function_registry(data: Mapping[str, Any]) -> FunctionRegistry:
    prepared = _prepare_data(data)
    return FunctionRegistry.portable().with_function(
        "relativeTime",
        lambda call: _format_call_relative_time(call, prepared),
    )


def _format_call_relative_time(call: FunctionCall, data: _RelativeTimeData) -> str:
    value = call.raw_value if call.raw_value is not None else call.value
    options = {
        "data": data,
        "locale": call.locale,
        "style": call.option_value("style", "short") or "short",
        "numeric": call.option_value("numeric", "always") or "always",
        "policy": call.option_value("policy", "precise") or "precise",
        "unit": call.option_value("unit", "auto") or "auto",
    }
    try:
        return _format_relative_time_prepared(value, **options)
    except MF2Error as error:
        source_value = _relative_time_source_value(call.inherited_source)
        if error.code != "bad-operand" or source_value is None:
            raise
        return _format_relative_time_prepared(source_value, **options)


def _relative_time_source_value(source: object | None) -> str | None:
    while source is not None:
        function = getattr(source, "function")
        if function.get("name") in {
            "number",
            "integer",
            "percent",
            "offset",
            "currency",
            "relativeTime",
        }:
            return getattr(source, "value")
        source = getattr(source, "inherited_source")
    return None


def _format_relative_time_prepared(
    value: Any,
    *,
    data: _RelativeTimeData,
    locale: str,
    style: str,
    numeric: str,
    policy: str,
    unit: str,
) -> str:
    locale = _coerce_string_option(locale, "locale")
    _validate_locale_length(locale)
    style = _option_one_of(style, _STYLE_VALUES, "style")
    numeric = _option_one_of(numeric, _NUMERIC_VALUES, "numeric")
    policy = _option_one_of(policy, _POLICY_VALUES, "policy")
    unit = _option_one_of(unit, _UNIT_VALUES, "unit")
    seconds = _parse_finite_number(value)
    selected_unit = _select_unit(seconds, policy) if unit == "auto" else unit
    quantity = _relative_time_quantity(seconds, selected_unit)

    if _use_relative_zero(policy, numeric, seconds):
        relative = _relative_term(data, locale, style, selected_unit, "0")
        if relative is not None:
            return relative

    if numeric == "auto":
        offset = _relative_offset(seconds, selected_unit, quantity)
        if offset is not None:
            relative = _relative_term(data, locale, style, selected_unit, offset)
            if relative is not None:
                return relative

    direction = "past" if _is_negative_relative_time(seconds) else "future"
    category = select_cardinal(quantity, locale)
    pattern = _relative_time_pattern(data, locale, style, selected_unit, direction, category)
    return pattern.replace("{0}", str(quantity))


def _prepare_data(data: Mapping[str, Any]) -> _RelativeTimeData:
    if not isinstance(data, Mapping):
        raise MF2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")
    locale_map = data.get("localeMap")
    pattern_sets = data.get("patternSets")
    if (
        not isinstance(locale_map, Mapping)
        or not locale_map
        or not isinstance(pattern_sets, list)
        or not pattern_sets
    ):
        raise MF2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")

    decoded_locale_map: dict[str, str] = {}
    for locale, set_id in locale_map.items():
        if not isinstance(locale, str) or not isinstance(set_id, str):
            raise MF2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")
        decoded_locale_map[locale] = set_id

    decoded_pattern_sets: dict[str, Mapping[str, Any]] = {}
    for item in pattern_sets:
        if (
            isinstance(item, Mapping)
            and isinstance(item.get("id"), str)
            and item["id"] != ""
            and isinstance(item.get("data"), Mapping)
            and bool(item["data"])
        ):
            decoded_pattern_sets[item["id"]] = item["data"]
    if not decoded_pattern_sets:
        raise MF2Error("missing-locale-data", "Relative-time core data has an unsupported shape.")
    return _RelativeTimeData(locale_map=decoded_locale_map, pattern_sets=decoded_pattern_sets)


def _parse_finite_number(value: Any) -> float:
    if isinstance(value, bool) or value is None:
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.")
    text = _coerce_string_operand(value).strip()
    if not text:
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.")
    if len(text) > _MAX_OPERAND_LENGTH:
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.")
    if not _DECIMAL_NUMBER_RE.fullmatch(text):
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.")
    try:
        parsed = float(text)
    except ValueError as error:
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.") from error
    if not math.isfinite(parsed):
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.")
    return parsed


def _coerce_string_operand(value: Any) -> str:
    try:
        return str(value)
    except Exception as error:
        raise MF2Error("bad-operand", "Relative-time core requires a finite numeric value.") from error


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


def _option_one_of(value: Any, allowed: set[str], name: str) -> str:
    text = _coerce_string_option(value, name)
    if text not in allowed:
        raise MF2Error("bad-option", f"{name} must be one of {', '.join(sorted(allowed))}.")
    return text


def _select_unit(seconds: float, policy: str) -> str:
    absolute = abs(seconds)
    for upper, unit in _POLICIES[policy]:
        if absolute < upper:
            return unit
    return "year"


def _relative_time_quantity(seconds: float, unit: str) -> int:
    absolute = abs(seconds)
    if absolute == 0:
        return 0
    quantity = max(1, math.floor(absolute / _UNIT_SECONDS[unit] + 0.5))
    if quantity > _MAX_RELATIVE_TIME_QUANTITY:
        raise MF2Error("bad-operand", "Relative-time core quantity is outside the supported range.")
    return quantity


def _use_relative_zero(policy: str, numeric: str, seconds: float) -> bool:
    return policy == "chat" and numeric == "auto" and abs(seconds) < 45


def _relative_offset(seconds: float, unit: str, quantity: int) -> str | None:
    if quantity == 0:
        return "0"
    if abs(seconds) != quantity * _UNIT_SECONDS[unit]:
        return None
    return f"-{quantity}" if _is_negative_relative_time(seconds) else str(quantity)


def _is_negative_relative_time(seconds: float) -> bool:
    return seconds < 0 or math.copysign(1.0, seconds) < 0


def _relative_term(
    data: _RelativeTimeData,
    locale: str,
    style: str,
    unit: str,
    offset: str,
) -> str | None:
    relative = _relative_unit_data(data, locale, style, unit).get("relative", {})
    if isinstance(relative, Mapping):
        value = relative.get(offset)
        return value if isinstance(value, str) else None
    return None


def _relative_time_pattern(
    data: _RelativeTimeData,
    locale: str,
    style: str,
    unit: str,
    direction: str,
    category: str,
) -> str:
    patterns = _relative_unit_data(data, locale, style, unit).get(direction)
    if isinstance(patterns, Mapping):
        pattern = patterns.get(category, patterns.get("other"))
        if isinstance(pattern, str):
            return pattern
    raise MF2Error(
        "missing-locale-data",
        f"Missing relative-time pattern for {locale}/{style}/{unit}/{direction}.",
    )


def _relative_unit_data(
    data: _RelativeTimeData,
    locale: str,
    style: str,
    unit: str,
) -> Mapping[str, Any]:
    pattern_set = _pattern_set_for(data, locale)
    style_data = pattern_set.get(style)
    unit_data = style_data.get(unit) if isinstance(style_data, Mapping) else None
    if isinstance(unit_data, Mapping):
        return unit_data
    raise MF2Error(
        "missing-locale-data",
        f"Missing relative-time unit data for {locale}/{style}/{unit}.",
    )


def _pattern_set_for(data: _RelativeTimeData, locale: str) -> Mapping[str, Any]:
    for candidate in locale_lookup_chain(locale or _DEFAULT_LOCALE):
        set_id = data.locale_map.get(candidate)
        if set_id is None:
            continue
        pattern_set = data.pattern_sets.get(set_id)
        if pattern_set is not None:
            return pattern_set
        raise MF2Error("missing-locale-data", f"Missing relative-time pattern set {set_id}.")
    raise MF2Error("missing-locale-data", f"Missing relative-time locale data for {locale}.")
