from __future__ import annotations

from typing import assert_type

from mojito_mf2 import (
    FormatResult,
    FunctionCall,
    FunctionRegistry,
    MF2FormattedPart,
    MF2FunctionAnnotation,
    MF2MessageModel,
    MF2RecoveryContext,
    PartsResult,
    format_message,
    format_message_to_parts,
    parse_to_model,
)
from mojito_mf2.date_time_core import (
    date_time_core_function_registry,
    format_date_core,
    format_date_core_to_parts,
    format_date_time_core,
    format_date_time_core_to_parts,
    format_time_core,
    format_time_core_to_parts,
)
from mojito_mf2.number_core import (
    format_number_core,
    format_number_core_to_parts,
    number_core_function_registry,
)
from mojito_mf2.relative_time_core import (
    format_relative_time_core,
    format_relative_time_core_to_parts,
    relative_time_core_function_registry,
)


parsed = parse_to_model("Welcome, {$name}!")
if parsed.model is None:
    raise AssertionError(parsed.diagnostics)

assert_type(parsed.model, MF2MessageModel)

arguments = {"name": "Mojito"}
formatted = format_message(parsed.model, arguments)
assert_type(formatted, FormatResult)
assert_type(formatted.value, str)

parts_result = format_message_to_parts(parsed.model, arguments)
assert_type(parts_result, PartsResult)
assert_type(parts_result.parts, list[MF2FormattedPart])


def recover(context: MF2RecoveryContext) -> str | None:
    assert_type(context.fallback_value, str)
    return None


format_message(parsed.model, {}, on_missing_argument=recover)


def upper(call: FunctionCall) -> str:
    assert_type(call.locale, str)
    return call.value.upper()


registry = FunctionRegistry.portable().with_function("app:upper", upper)
function: MF2FunctionAnnotation = {"type": "function", "name": "string"}
assert registry.has_formatter(function)

relative_time_data: dict[str, object] = {
    "localeMap": {"en": "rt"},
    "patternSets": [
        {
            "id": "rt",
            "data": {
                "short": {
                    "minute": {
                        "future": {"other": "in {0} min."},
                        "past": {"other": "{0} min. ago"},
                    }
                }
            },
        }
    ],
}
number_core_registry = number_core_function_registry()
assert_type(number_core_registry, FunctionRegistry)
number_core_output = format_number_core(
    1234.5,
    locale="en-US",
    style="currency",
    currency="USD",
)
assert_type(number_core_output, str)
number_core_parts = format_number_core_to_parts(
    1234.5,
    locale="en-US",
    style="currency",
    currency="USD",
)
assert_type(number_core_parts, list[dict[str, str]])

date_time_registry = date_time_core_function_registry()
assert_type(date_time_registry, FunctionRegistry)
date_core_output = format_date_core(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    dateStyle="medium",
    timeZone="UTC",
)
assert_type(date_core_output, str)
date_core_parts = format_date_core_to_parts(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    dateStyle="medium",
    timeZone="UTC",
)
assert_type(date_core_parts, list[dict[str, str]])
time_core_output = format_time_core(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    timeStyle="short",
    timeZone="UTC",
)
assert_type(time_core_output, str)
time_core_parts = format_time_core_to_parts(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    timeStyle="short",
    timeZone="UTC",
)
assert_type(time_core_parts, list[dict[str, str]])
date_time_core_output = format_date_time_core(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    dateStyle="medium",
    timeStyle="short",
    timeZone="UTC",
)
assert_type(date_time_core_output, str)
date_time_core_parts = format_date_time_core_to_parts(
    "2026-05-21T14:30:15Z",
    locale="en-US",
    dateStyle="medium",
    timeStyle="short",
    timeZone="UTC",
)
assert_type(date_time_core_parts, list[dict[str, str]])

relative_time_registry = relative_time_core_function_registry(relative_time_data)
assert_type(relative_time_registry, FunctionRegistry)
relative_time_output = format_relative_time_core(
    60,
    locale="en",
    unit="minute",
    data=relative_time_data,
)
assert_type(relative_time_output, str)
relative_time_parts = format_relative_time_core_to_parts(
    60,
    locale="en",
    unit="minute",
    data=relative_time_data,
)
assert_type(relative_time_parts, list[dict[str, str]])
