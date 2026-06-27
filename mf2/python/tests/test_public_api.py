from __future__ import annotations

import unittest

import mojito_mf2
import mojito_mf2.parser
from mojito_mf2._cldr_plural_rules import NumberOperands
from mojito_mf2.date_time_core import (
    date_time_core_function_registry,
    format_date_core,
    format_date_core_to_parts,
    format_date_time_core,
    format_date_time_core_to_parts,
    format_time_core,
    format_time_core_to_parts,
)
from mojito_mf2.number_core import format_number_core, format_number_core_to_parts
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

    def test_oversized_plural_operand_error_is_bounded(self) -> None:
        with self.assertRaisesRegex(ValueError, "^Unsupported plural operand value$"):
            NumberOperands("1" * 257)

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

    def test_invalid_recovery_handlers_default_to_visible_fallback(self) -> None:
        missing = parse_to_model("Welcome, {$name}!")
        missing_formatted = format_message(missing.model, {}, on_missing_argument=1)
        missing_parts = format_message_to_parts(
            missing.model, {}, on_missing_argument=1
        )

        self.assertEqual("Welcome, {$name}!", missing_formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "fallback", "source": "$name"},
                {"type": "text", "value": "!"},
            ],
            missing_parts.parts,
        )
        self.assertEqual(
            ["unresolved-variable"],
            [error.code for error in missing_formatted.errors],
        )
        self.assertEqual(
            ["unresolved-variable"], [error.code for error in missing_parts.errors]
        )

        bad_integer = parse_to_model("Welcome, {$name :integer}!")
        bad_formatted = format_message(
            bad_integer.model, {"name": "abc"}, on_format_error=1
        )
        bad_parts = format_message_to_parts(
            bad_integer.model, {"name": "abc"}, on_format_error=1
        )

        self.assertEqual("Welcome, {$name}!", bad_formatted.value)
        self.assertEqual(
            [
                {"type": "text", "value": "Welcome, "},
                {"type": "fallback", "source": "$name"},
                {"type": "text", "value": "!"},
            ],
            bad_parts.parts,
        )
        self.assertEqual(["bad-operand"], [error.code for error in bad_formatted.errors])
        self.assertEqual(["bad-operand"], [error.code for error in bad_parts.errors])

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

    def test_date_time_core_rejects_arbitrary_object_coercion(self) -> None:
        class IsoObject:
            def __str__(self) -> str:
                return "2026-05-21T14:30:15Z"

        class BadObject:
            def __str__(self) -> str:
                raise RuntimeError("should not be called")

        class BadTruthiness:
            def __bool__(self) -> bool:
                raise RuntimeError("should not be called")

        with self.assertRaises(MF2Error) as direct_error:
            format_date_time_core(IsoObject())
        self.assertEqual("bad-operand", direct_error.exception.code)

        for label, options in {
            "locale": {"locale": BadObject(), "timeZone": "UTC"},
            "skeleton": {"locale": "en-US", "skeleton": BadObject(), "timeZone": "UTC"},
            "timeZone": {"locale": "en-US", "timeZone": BadObject()},
            "calendar": {"locale": "en-US", "calendar": BadTruthiness(), "timeZone": "UTC"},
            "hourCycle": {"locale": "en-US", "hourCycle": BadTruthiness(), "timeZone": "UTC"},
            "dateStyle": {"locale": "en-US", "dateStyle": [], "timeZone": "UTC"},
            "timeStyle": {"locale": "en-US", "timeStyle": [], "timeZone": "UTC"},
        }.items():
            with self.subTest(label=label):
                with self.assertRaises(MF2Error) as direct_option_error:
                    format_date_time_core("2026-05-21T14:30:15Z", **options)
                self.assertEqual("bad-option", direct_option_error.exception.code)

        result = parse_to_model("At {$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}")
        formatted = format_message(
            result.model,
            {"instant": BadObject()},
            functions=date_time_core_function_registry(),
        )
        self.assertEqual(["bad-operand"], [error.code for error in formatted.errors])

        option_result = parse_to_model("At {$instant :datetime timeZone=$tz}")
        option_formatted = format_message(
            option_result.model,
            {"instant": "2026-05-21T14:30:15Z", "tz": BadObject()},
            functions=date_time_core_function_registry(),
        )
        self.assertEqual(["bad-option"], [error.code for error in option_formatted.errors])

    def test_number_core_rejects_arbitrary_object_coercion(self) -> None:
        class BadObject:
            def __str__(self) -> str:
                raise RuntimeError("should not be called")

        with self.assertRaises(MF2Error) as operand_error:
            format_number_core(BadObject(), locale="en-US")
        self.assertEqual("bad-operand", operand_error.exception.code)

        for label, options in {
            "locale": {"locale": BadObject()},
            "style": {"locale": "en-US", "style": []},
            "minimumFractionDigits": {"locale": "en-US", "minimumFractionDigits": BadObject()},
            "maximumFractionDigits": {"locale": "en-US", "maximumFractionDigits": BadObject()},
        }.items():
            with self.subTest(label=label):
                with self.assertRaises(MF2Error) as option_error:
                    format_number_core(1, **options)
                self.assertEqual("bad-option", option_error.exception.code)

    def test_core_parts_helpers_are_public_submodule_apis(self) -> None:
        number_options = {"locale": "fr-FR"}
        date_options = {"locale": "fr-FR", "dateStyle": "short", "timeZone": "UTC"}
        time_options = {"locale": "fr-FR", "timeStyle": "short", "timeZone": "UTC"}
        date_time_options = {
            "locale": "fr-FR",
            "dateStyle": "short",
            "timeStyle": "short",
            "timeZone": "UTC",
        }

        self.assertEqual(
            [{"type": "text", "value": format_number_core(1234.5, **number_options)}],
            format_number_core_to_parts(1234.5, **number_options),
        )
        self.assertEqual(
            [{"type": "text", "value": format_date_core("2026-05-21T14:30:15Z", **date_options)}],
            format_date_core_to_parts("2026-05-21T14:30:15Z", **date_options),
        )
        self.assertEqual(
            [{"type": "text", "value": format_time_core("2026-05-21T14:30:15Z", **time_options)}],
            format_time_core_to_parts("2026-05-21T14:30:15Z", **time_options),
        )
        self.assertEqual(
            [
                {
                    "type": "text",
                    "value": format_date_time_core(
                        "2026-05-21T14:30:15Z", **date_time_options
                    ),
                }
            ],
            format_date_time_core_to_parts("2026-05-21T14:30:15Z", **date_time_options),
        )

    def test_host_object_rendering_failures_are_recoverable(self) -> None:
        class BadObject:
            def __str__(self) -> str:
                raise RuntimeError("should not be called")

        placeholder = parse_to_model("Hello {$name}")
        formatted = format_message(placeholder.model, {"name": BadObject()})
        self.assertFalse(formatted.ok)
        self.assertEqual("Hello {$name}", formatted.value)
        self.assertEqual(["bad-operand"], [error.code for error in formatted.errors])

        string_placeholder = parse_to_model("Hello {$name :string}")
        string_formatted = format_message(string_placeholder.model, {"name": BadObject()})
        self.assertFalse(string_formatted.ok)
        self.assertEqual("Hello {$name}", string_formatted.value)
        self.assertEqual(["bad-operand"], [error.code for error in string_formatted.errors])

        selector = parse_to_model(
            ".input {$name :string}\n.match $name\nok {{selected}}\n* {{fallback}}"
        )
        selector_formatted = format_message(selector.model, {"name": BadObject()})
        self.assertFalse(selector_formatted.ok)
        self.assertEqual("fallback", selector_formatted.value)
        self.assertEqual(
            ["bad-operand", "bad-selector"],
            [error.code for error in selector_formatted.errors],
        )

    def test_top_level_locale_coercion_failure_is_recoverable(self) -> None:
        class BadObject:
            def __str__(self) -> str:
                raise RuntimeError("locale coercion failed")

        plural = parse_to_model(
            ".input {$n :number}\n.match $n\none {{one}}\n* {{other}}"
        )
        formatted = format_message(plural.model, {"n": 1}, locale=BadObject())
        self.assertFalse(formatted.ok)
        self.assertEqual("", formatted.value)
        self.assertEqual(["bad-option"], [error.code for error in formatted.errors])

        parts = format_message_to_parts(plural.model, {"n": 1}, locale=BadObject())
        self.assertFalse(parts.ok)
        self.assertEqual([], parts.parts)
        self.assertEqual(["bad-option"], [error.code for error in parts.errors])

    def test_top_level_functions_option_must_be_registry(self) -> None:
        message = parse_to_model("Hello {$name}")
        formatted = format_message(message.model, {"name": "Mojito"}, functions=1)
        self.assertFalse(formatted.ok)
        self.assertEqual("", formatted.value)
        self.assertEqual(["bad-option"], [error.code for error in formatted.errors])

        parts = format_message_to_parts(message.model, {"name": "Mojito"}, functions=1)
        self.assertFalse(parts.ok)
        self.assertEqual([], parts.parts)
        self.assertEqual(["bad-option"], [error.code for error in parts.errors])

    def test_top_level_arguments_enumeration_failure_is_recoverable(self) -> None:
        class BadArguments:
            def keys(self) -> list[str]:
                raise RuntimeError("arguments enumeration failed")

        message = parse_to_model("Hello {$name}")
        formatted = format_message(message.model, BadArguments())
        self.assertFalse(formatted.ok)
        self.assertEqual("", formatted.value)
        self.assertEqual(["bad-option"], [error.code for error in formatted.errors])

        parts = format_message_to_parts(message.model, BadArguments())
        self.assertFalse(parts.ok)
        self.assertEqual([], parts.parts)
        self.assertEqual(["bad-option"], [error.code for error in parts.errors])

    def test_non_mapping_model_raises_mf2_error(self) -> None:
        for model in (None, []):
            with self.subTest(model=model):
                with self.assertRaises(MF2Error) as formatted:
                    format_message(model)  # type: ignore[arg-type]
                self.assertEqual("unsupported-message-type", formatted.exception.code)

                with self.assertRaises(MF2Error) as parts:
                    format_message_to_parts(model)  # type: ignore[arg-type]
                self.assertEqual("unsupported-message-type", parts.exception.code)

    def test_non_list_model_fields_raise_mf2_error(self) -> None:
        for field in ("declarations", "pattern"):
            with self.subTest(field=field):
                with self.assertRaises(MF2Error) as formatted:
                    format_message({"type": "message", field: 1})
                self.assertEqual("bad-option", formatted.exception.code)

        for field in ("selectors", "variants"):
            with self.subTest(field=field):
                with self.assertRaises(MF2Error) as formatted:
                    format_message({"type": "select", field: 1})
                self.assertEqual("bad-option", formatted.exception.code)

        with self.assertRaises(MF2Error) as formatted:
            format_message({"type": "select", "variants": [{"keys": 1, "value": []}]})
        self.assertEqual("bad-option", formatted.exception.code)

    def test_non_mapping_model_field_entries_raise_mf2_error(self) -> None:
        cases = (
            {"type": "message", "declarations": [1]},
            {"type": "select", "selectors": [1]},
            {"type": "select", "variants": [1]},
            {"type": "select", "variants": [{"keys": [1], "value": []}]},
        )
        for model in cases:
            with self.subTest(model=model):
                with self.assertRaises(MF2Error) as formatted:
                    format_message(model)
                self.assertEqual("bad-option", formatted.exception.code)

    def test_non_mapping_pattern_part_raises_mf2_error(self) -> None:
        with self.assertRaises(MF2Error) as formatted:
            format_message({"type": "message", "pattern": [1]})
        self.assertEqual("unsupported-pattern-part", formatted.exception.code)

        with self.assertRaises(MF2Error) as unknown:
            format_message({"type": "message", "pattern": [{"type": "bogus"}]})
        self.assertEqual("unsupported-pattern-part", unknown.exception.code)

    def test_unselected_invalid_variant_pattern_raises_mf2_error(self) -> None:
        with self.assertRaises(MF2Error) as formatted:
            format_message(
                {
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
                                    "name": "string",
                                    "options": {},
                                },
                            },
                        }
                    ],
                    "selectors": [{"type": "variable", "name": "state"}],
                    "variants": [
                        {"keys": [{"type": "literal", "value": "bad"}], "value": [1]},
                        {"keys": [{"type": "*"}], "value": ["fallback"]},
                    ],
                },
                {"state": "ok"},
            )
        self.assertEqual("unsupported-pattern-part", formatted.exception.code)

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

    def test_selector_only_annotation_preserves_raw_value(self) -> None:
        parsed = parse_to_model(
            ".input {$flag :raw}\n.match $flag\nraw {{raw}}\n* {{fallback}}"
        )
        registry = FunctionRegistry.portable().with_selector(
            "raw",
            lambda match: 1 if match.raw_value is True and match.key == "raw" else None,
        )

        formatted = format_message(parsed.model, {"flag": True}, functions=registry)

        self.assertEqual("raw", formatted.value)
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
        self.assertFalse(hasattr(mojito_mf2, "format_number_core"))
        self.assertFalse(hasattr(mojito_mf2, "format_number_core_to_parts"))
        self.assertFalse(hasattr(mojito_mf2, "format_date_core"))
        self.assertFalse(hasattr(mojito_mf2, "format_date_core_to_parts"))
        self.assertFalse(hasattr(mojito_mf2, "format_time_core"))
        self.assertFalse(hasattr(mojito_mf2, "format_time_core_to_parts"))
        self.assertFalse(hasattr(mojito_mf2, "format_date_time_core"))
        self.assertFalse(hasattr(mojito_mf2, "format_date_time_core_to_parts"))
        self.assertFalse(hasattr(mojito_mf2, "format_relative_time_core"))
        self.assertFalse(hasattr(mojito_mf2, "format_relative_time_core_to_parts"))
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
