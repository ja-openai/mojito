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

    def test_pipe_quoted_literal_can_contain_closing_brace(self) -> None:
        result = parse_to_model("{|a}b|}")

        self.assertFalse(result.has_diagnostics, result.diagnostics)
        self.assertEqual("a}b", format_message(result.model).value)

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
        self.assertEqual(
            ["unresolved-variable"], [error.code for error in formatted.errors]
        )
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
        self.assertEqual(
            ["unresolved-variable"], [error.code for error in formatted.errors]
        )
        self.assertEqual(
            ["unresolved-variable"], [error.code for error in parts.errors]
        )

    def test_format_error_recovery_can_replace_with_empty_string(self) -> None:
        result = parse_to_model("Welcome, {$name :integer}!")

        def recover(context: MF2RecoveryContext) -> str:
            return ""

        formatted = format_message(
            result.model, {"name": "abc"}, on_format_error=recover
        )
        parts = format_message_to_parts(
            result.model, {"name": "abc"}, on_format_error=recover
        )

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

        formatted = format_message(
            result.model, {"name": "abc"}, on_format_error=recover
        )
        parts = format_message_to_parts(
            result.model, {"name": "abc"}, on_format_error=recover
        )

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
        self.assertEqual(
            ["unknown-function"], [error.code for error in formatted.errors]
        )

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
                {
                    "keys": [{"type": "literal", "value": "custom"}],
                    "value": ["selected"],
                },
                {"keys": [{"type": "*"}], "value": ["fallback"]},
            ],
        }

        registry = (
            FunctionRegistry()
            .with_function("test:select", lambda call: call.value)
            .with_selector(
                "test:select",
                lambda match: (
                    1 if match.value == "ready" and match.key == "custom" else None
                ),
            )
        )

        formatted = format_message(model, {"state": "ready"}, functions=registry)

        self.assertEqual("selected", formatted.value)
        self.assertTrue(formatted.ok)

    def test_custom_selector_failure_is_reported_once(self) -> None:
        result = parse_to_model(
            ".input {$state :test:select} "
            ".match $state "
            "ready {{ready}} "
            "waiting {{waiting}} "
            "* {{fallback}}"
        )
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        def fail_selector(_: FunctionMatch) -> int | None:
            raise MF2Error("bad-selector", "Selector failed.")

        registry = (
            FunctionRegistry()
            .with_function("test:select", lambda call: call.value)
            .with_selector("test:select", fail_selector)
        )

        formatted = format_message(
            result.model,
            {"state": "ready"},
            functions=registry,
        )

        self.assertEqual("fallback", formatted.value)
        self.assertEqual(
            ["bad-selector"], [error.code for error in formatted.errors]
        )

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
                        "function": {
                            "type": "function",
                            "name": "percent",
                            "options": {},
                        },
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

    def test_numeric_exact_match_outranks_plural_category(self) -> None:
        result = parse_to_model(
            ".input {$count :integer} "
            ".match $count "
            "one {{plural one}} "
            "1 {{exact one}} "
            "* {{fallback}}"
        )
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        self.assertEqual(
            "exact one",
            format_message(result.model, {"count": 1}, locale="en").value,
        )
        self.assertEqual(
            "exact one",
            format_message(result.model, {"count": 1.2}, locale="en").value,
        )

    def test_number_exact_match_uses_canonical_integer_serialization(self) -> None:
        result = parse_to_model(
            ".input {$value :number} "
            ".match $value "
            "1.0 {{decimal spelling}} "
            "1 {{integer spelling}} "
            "* {{fallback}}"
        )
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"value": 1}, locale="en")

        self.assertEqual("integer spelling", formatted.value)
        self.assertEqual([], formatted.errors)

    def test_numeric_selector_reports_invalid_variant_key(self) -> None:
        result = parse_to_model(
            ".input {$value :number} "
            ".match $value "
            "horse {{horse}} "
            "1 {{exact}} "
            "* {{fallback}}"
        )
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"value": 1}, locale="en")

        self.assertEqual("exact", formatted.value)
        self.assertEqual(
            ["bad-variant-key"], [error.code for error in formatted.errors]
        )

    def test_failed_annotated_input_does_not_leak_raw_argument(self) -> None:
        result = parse_to_model(".input {$value :number} {{Value {$value}}}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"value": "not-a-number"})

        self.assertEqual("Value {$value}", formatted.value)
        self.assertEqual(["bad-operand"], [error.code for error in formatted.errors])

    def test_numeric_select_option_must_be_literal(self) -> None:
        result = parse_to_model("Value {1 :number select=$mode}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"mode": "exact"})

        self.assertEqual("Value 1", formatted.value)
        self.assertEqual(["bad-option"], [error.code for error in formatted.errors])

        inherited = parse_to_model(
            ".local $source = {1 :number select=exact} "
            ".local $value = {$source :number} "
            ".match $value "
            "1 {{exact}} "
            "* {{fallback {$value}}}"
        )
        self.assertFalse(inherited.has_diagnostics, inherited.diagnostics)

        inherited_formatted = format_message(inherited.model)

        self.assertEqual("fallback 1", inherited_formatted.value)
        self.assertEqual(
            ["bad-option", "bad-selector"],
            [error.code for error in inherited_formatted.errors],
        )

    def test_bidi_direction_controls_isolation_and_propagates(self) -> None:
        cases = [
            ("ltr", "\u2066"),
            ("rtl", "\u2067"),
            ("auto", "\u2068"),
        ]
        for direction, marker in cases:
            with self.subTest(direction=direction):
                result = parse_to_model(
                    f"Value {{text :string u:dir={direction}}}"
                )
                self.assertFalse(result.has_diagnostics, result.diagnostics)
                formatted = format_message(
                    result.model, bidi_isolation="default"
                )
                parts = format_message_to_parts(result.model)
                self.assertEqual(f"Value {marker}text\u2069", formatted.value)
                self.assertEqual(direction, parts.parts[1].get("direction"))

        propagated = parse_to_model(
            ".local $value = {text :string u:dir=rtl} {{Value {$value}}}"
        )
        self.assertEqual(
            "Value \u2067text\u2069",
            format_message(
                propagated.model, bidi_isolation="default"
            ).value,
        )

    def test_bidi_direction_is_invalid_on_markup(self) -> None:
        result = parse_to_model("{#tag u:dir=rtl}value{/tag}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model)

        self.assertEqual("value", formatted.value)
        self.assertEqual(["bad-option"], [error.code for error in formatted.errors])

    def test_default_registry_does_not_ship_currency_shim(self) -> None:
        result = parse_to_model("Total: {$amount :currency currency=USD}")
        self.assertFalse(result.has_diagnostics, result.diagnostics)

        formatted = format_message(result.model, {"amount": 12.5})

        self.assertEqual("Total: {$amount}", formatted.value)
        self.assertEqual(
            ["unknown-function"], [error.code for error in formatted.errors]
        )

    def test_portable_numeric_options_reject_non_ascii_and_unbounded_digits(
        self,
    ) -> None:
        cases = [
            ("{$amount :number minimumFractionDigits=|²|}", {"amount": "1.25"}),
            ("{$amount :percent maximumFractionDigits=|²|}", {"amount": "1.25"}),
            ("{$amount :number minimumFractionDigits=|١|}", {"amount": "1.25"}),
            ("{$amount :percent maximumFractionDigits=1001}", {"amount": "1.25"}),
            ("{$amount :offset add=|²|}", {"amount": "1"}),
        ]

        for source, arguments in cases:
            with self.subTest(source=source):
                parsed = parse_to_model(source)
                self.assertIsNotNone(parsed.model, parsed.diagnostics)
                formatted = format_message(parsed.model, arguments)
                self.assertEqual(
                    ["bad-option"], [error.code for error in formatted.errors]
                )

    def test_portable_number_accepts_only_ascii_mf2_decimal_syntax(self) -> None:
        parsed = parse_to_model("{$amount :number}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        valid = {
            "0": "0",
            "-0": "0",
            "1.250": "1.25",
            "1e+2": "100",
            "1E-2": "0.01",
            "-2.5e2": "-250",
        }
        for amount, expected in valid.items():
            with self.subTest(amount=amount):
                formatted = format_message(parsed.model, {"amount": amount})
                self.assertEqual(expected, formatted.value)
                self.assertEqual([], formatted.errors)

        invalid = [
            "",
            "00",
            "+1",
            "1.",
            ".5",
            "1e",
            "1e+",
            "1e1.2",
            "1e2e3",
            "١",
            "1_0",
            "NaN",
            "Infinity",
            " 1",
            "1 ",
        ]
        for amount in invalid:
            with self.subTest(amount=amount):
                formatted = format_message(parsed.model, {"amount": amount})
                self.assertEqual(
                    ["bad-operand"], [error.code for error in formatted.errors]
                )

    def test_portable_number_applies_fraction_options(self) -> None:
        cases = [
            ("{$amount :number maximumFractionDigits=1}", "1.29", "1.3"),
            (
                ".local $amount = {1.2 :number minimumFractionDigits=2} "
                "{{{$amount :number}}}",
                None,
                "1.20",
            ),
        ]

        for source, amount, expected in cases:
            with self.subTest(source=source):
                parsed = parse_to_model(source)
                self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                arguments = {} if amount is None else {"amount": amount}
                formatted = format_message(parsed.model, arguments)
                self.assertEqual(expected, formatted.value)
                self.assertEqual([], formatted.errors)

    def test_portable_numeric_formatting_handles_large_bounded_values(self) -> None:
        cases = [
            ("{$amount :number}", "1e100", f"1{'0' * 100}"),
            ("{$amount :percent maximumFractionDigits=2}", "1e100", f"1{'0' * 102}%"),
            ("{$amount :percent maximumFractionDigits=2}", "9" * 40, f"{'9' * 40}00%"),
        ]

        for source, amount, expected in cases:
            with self.subTest(source=source, amount=amount):
                parsed = parse_to_model(source)
                self.assertIsNotNone(parsed.model, parsed.diagnostics)
                formatted = format_message(parsed.model, {"amount": amount})
                self.assertEqual(expected, formatted.value)
                self.assertEqual([], formatted.errors)

    def test_portable_numeric_formatting_recovers_for_unbounded_values(self) -> None:
        cases = [
            ("{$amount :number}", "1e1000000"),
            ("{$amount :percent maximumFractionDigits=2}", "1e1000000"),
            ("{$amount :integer}", "1e1000000"),
            ("{$amount :number}", "1e-5000"),
            ("{$amount :integer}", "9" * 1001),
            ("{$amount :offset add=1}", "²"),
        ]

        for source, amount in cases:
            with self.subTest(source=source, amount=amount):
                parsed = parse_to_model(source)
                self.assertIsNotNone(parsed.model, parsed.diagnostics)
                formatted = format_message(parsed.model, {"amount": amount})
                self.assertEqual(
                    ["bad-operand"], [error.code for error in formatted.errors]
                )

    def test_presence_only_attributes_are_preserved_in_models_and_parts(self) -> None:
        parsed = parse_to_model("{$name @visible @label=example}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        self.assertEqual(
            {"visible": True, "label": {"type": "literal", "value": "example"}},
            parsed.model["pattern"][0]["attributes"],
        )

        parts = format_message_to_parts(parsed.model, {"name": "Mojito"})
        self.assertEqual(
            [
                {
                    "type": "expression",
                    "value": "Mojito",
                    "attributes": {
                        "visible": True,
                        "label": {"type": "literal", "value": "example"},
                    },
                }
            ],
            parts.parts,
        )

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
