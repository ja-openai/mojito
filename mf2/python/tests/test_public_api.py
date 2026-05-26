from __future__ import annotations

import unittest

import mojito_mf2
import mojito_mf2.parser
from mojito_mf2 import (
    FunctionCall,
    FunctionMatch,
    FunctionRegistry,
    FunctionSource,
    FormatResult,
    MF2Error,
    MF2ParseDiagnostic,
    PartsResult,
    MF2RecoveryContext,
    format_message,
    format_message_to_parts,
    parse_to_model,
)


class PublicApiTest(unittest.TestCase):
    def test_parse_and_format_message_result(self) -> None:
        result = parse_to_model("Welcome, {$name}!")

        self.assertFalse(result.has_diagnostics, result.diagnostics)
        formatted = format_message(result.model, {"name": "Mojito"})
        self.assertEqual("Welcome, Mojito!", formatted.value)
        self.assertTrue(formatted.ok)
        self.assertFalse(formatted.has_errors)
        self.assertIs(MF2ParseDiagnostic, mojito_mf2.MF2ParseDiagnostic)
        self.assertIs(FunctionMatch, mojito_mf2.FunctionMatch)
        self.assertIs(FunctionSource, mojito_mf2.FunctionSource)
        self.assertTrue(FunctionRegistry.portable().has_formatter({"name": "string"}))

    def test_safe_format_result_and_parts_api(self) -> None:
        result = parse_to_model("Welcome, {$name}!")

        formatted = format_message(result.model, {"name": "Mojito"})
        self.assertIsInstance(formatted, FormatResult)
        self.assertEqual("Welcome, Mojito!", formatted.value)
        self.assertEqual([], formatted.errors)

        parts = format_message_to_parts(result.model, {"name": "Parts"})
        self.assertIsInstance(parts, PartsResult)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "expression", "value": "Parts"},
                {"type": "text", "value": "!"},
            ],
            parts.parts,
        )
        self.assertEqual([], parts.errors)
        self.assertTrue(parts.ok)
        self.assertFalse(parts.has_errors)

    def test_recovery_callbacks_can_replace_missing_arguments(self) -> None:
        result = parse_to_model("Welcome, {$name}!")
        seen: list[MF2RecoveryContext] = []

        def recover(context: MF2RecoveryContext) -> str:
            seen.append(context)
            return "[missing]"

        formatted = format_message(result.model, {}, on_missing_argument=recover)

        self.assertEqual("Welcome, [missing]!", formatted.value)
        self.assertFalse(formatted.ok)
        self.assertEqual(["unresolved-variable"], [error.code for error in formatted.errors])
        self.assertEqual("name", seen[0].variable_name)
        self.assertEqual("{$name}", seen[0].fallback_value)

    def test_missing_argument_recovery_can_replace_with_empty_string(self) -> None:
        result = parse_to_model("Welcome, {$name}!")

        def recover(context: MF2RecoveryContext) -> str:
            return ""

        formatted = format_message(result.model, {}, on_missing_argument=recover)
        parts = format_message_to_parts(result.model, {}, on_missing_argument=recover)

        self.assertEqual("Welcome, !", formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "fallback", "source": "$name", "value": ""},
                {"type": "text", "value": "!"},
            ],
            parts.parts,
        )
        self.assertEqual(["unresolved-variable"], [error.code for error in formatted.errors])
        self.assertEqual(["unresolved-variable"], [error.code for error in parts.errors])

    def test_format_error_recovery_can_replace_with_empty_string(self) -> None:
        result = parse_to_model("Welcome, {$name :integer}!")

        def recover(context: MF2RecoveryContext) -> str:
            return ""

        formatted = format_message(result.model, {"name": "abc"}, on_format_error=recover)
        parts = format_message_to_parts(result.model, {"name": "abc"}, on_format_error=recover)

        self.assertEqual("Welcome, !", formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "fallback", "source": "$name", "value": ""},
                {"type": "text", "value": "!"},
            ],
            parts.parts,
        )
        self.assertEqual(["bad-operand"], [error.code for error in formatted.errors])
        self.assertEqual(["bad-operand"], [error.code for error in parts.errors])

    def test_recovery_callbacks_can_decline_to_replace_visible_fallback(self) -> None:
        result = parse_to_model("Welcome, {$name :integer}!")

        def recover(context: MF2RecoveryContext) -> None:
            return None

        formatted = format_message(result.model, {"name": "abc"}, on_format_error=recover)
        parts = format_message_to_parts(result.model, {"name": "abc"}, on_format_error=recover)

        self.assertEqual("Welcome, {$name}!", formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "fallback", "source": "$name"},
                {"type": "text", "value": "!"},
            ],
            parts.parts,
        )
        self.assertEqual(["bad-operand"], [error.code for error in formatted.errors])

    def test_default_unknown_function_recovery_uses_visible_fallback(self) -> None:
        result = parse_to_model("Total: {$amount :currency currency=USD}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"amount": 12.5})
        parts = format_message_to_parts(result.model, {"amount": 12.5})

        self.assertEqual("Total: {$amount}", formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Total: "},
                {"type": "fallback", "source": "$amount"},
            ],
            parts.parts,
        )
        self.assertEqual(["unknown-function"], [error.code for error in formatted.errors])

    def test_custom_selector_can_match_variant_key(self) -> None:
        model = {
            "type": "select",
            "declarations": [
                {
                    "type": "input",
                    "name": "state",
                    "value": {
                        "type": "expression",
                        "arg": {"type": "variable", "name": "state"},
                        "function": {
                            "type": "function",
                            "name": "test:select",
                            "options": {},
                        },
                    },
                }
            ],
            "selectors": [{"type": "variable", "name": "state"}],
            "variants": [
                {"keys": [{"type": "literal", "value": "custom"}], "value": ["selected"]},
                {"keys": [{"type": "*"}], "value": ["fallback"]},
            ],
        }

        registry = (
            FunctionRegistry()
            .with_function("test:select", lambda call: call.value)
            .with_selector(
                "test:select",
                lambda match: 1 if match.value == "ready" and match.key == "custom" else None,
            )
        )

        formatted = format_message(model, {"state": "ready"}, functions=registry)

        self.assertEqual("selected", formatted.value)
        self.assertTrue(formatted.ok)

    def test_default_percent_function_formats_and_selects(self) -> None:
        message = {
            "type": "message",
            "declarations": [],
            "pattern": [
                {
                    "type": "expression",
                    "arg": {"type": "literal", "value": "0.125"},
                    "function": {
                        "type": "function",
                        "name": "percent",
                        "options": {
                            "maximumFractionDigits": {
                                "type": "literal",
                                "value": "1",
                            },
                        },
                    },
                }
            ],
        }
        select = {
            "type": "select",
            "declarations": [
                {
                    "type": "input",
                    "name": "ratio",
                    "value": {
                        "type": "expression",
                        "arg": {"type": "variable", "name": "ratio"},
                        "function": {"type": "function", "name": "percent", "options": {}},
                    },
                }
            ],
            "selectors": [{"type": "variable", "name": "ratio"}],
            "variants": [
                {"keys": [{"type": "literal", "value": "12.5"}], "value": ["selected"]},
                {"keys": [{"type": "*"}], "value": ["fallback"]},
            ],
        }

        self.assertEqual("12.5%", format_message(message).value)
        self.assertEqual("selected", format_message(select, {"ratio": "0.125"}).value)

    def test_default_registry_does_not_ship_currency_shim(self) -> None:
        result = parse_to_model("Total: {$amount :currency currency=USD}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"amount": 12.5})

        self.assertEqual("Total: {$amount}", formatted.value)
        self.assertEqual(["unknown-function"], [error.code for error in formatted.errors])

    def test_root_exports_stable_api_only(self) -> None:
        self.assertFalse(hasattr(mojito_mf2, "DEFAULT_FUNCTION_REGISTRY"))
        self.assertFalse(hasattr(mojito_mf2, "canonical_locale_key"))
        self.assertFalse(hasattr(mojito_mf2, "locale_lookup_chain"))
        self.assertFalse(hasattr(mojito_mf2, "lookup_locale"))
        self.assertFalse(hasattr(mojito_mf2, "format_message_strict"))
        self.assertFalse(hasattr(mojito_mf2, "format_message_to_parts_strict"))
        self.assertFalse(hasattr(mojito_mf2.parser, "ParseDiagnostic"))

def _call(name: str, value: str) -> FunctionCall:
    return FunctionCall(
        value=value,
        raw_value=value,
        function={"name": name, "options": {}},
        locale="en",
        _option_resolver=lambda _name, default=None: default,
    )

if __name__ == "__main__":
    unittest.main()
