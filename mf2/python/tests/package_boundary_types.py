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
