from __future__ import annotations

from decimal import Decimal
import unittest
from unittest.mock import patch

from mojito_mf2 import (
    FunctionCall,
    FunctionMatch,
    FunctionRegistry,
    FunctionSource,
    MF2Error,
    format_message,
    parse_to_model,
)
from mojito_mf2._locale_key import canonical_locale_key
from mojito_mf2._portable_functions import (
    _inherited_exact_numeric_source,
    _inherited_sign_display_always,
    _parse_source_decimal,
)


class SecurityBoundariesTest(unittest.TestCase):
    def test_failed_numeric_input_is_quarantined_before_plural_selection(
        self,
    ) -> None:
        parsed = parse_to_model(
            ".input {$n :number}\n"
            ".match $n\n"
            "one {{unsafe}}\n"
            "* {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        with patch(
            "mojito_mf2.formatter.select_plural_category",
            return_value="one",
        ) as select_plural:
            formatted = format_message(parsed.model, {"n": "1e1000000"})

        self.assertEqual("fallback", formatted.value)
        self.assertEqual(
            ["bad-operand", "bad-selector"],
            [error.code for error in formatted.errors],
        )
        select_plural.assert_not_called()

    def test_failed_binding_never_reaches_custom_selector(self) -> None:
        parsed = parse_to_model(
            ".input {$value :test:reject}\n"
            ".input {$state :string}\n"
            ".match $value $state\n"
            "unsafe ready {{unsafe}}\n"
            "* ready {{ready}}\n"
            "* * {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        selected_values: list[object] = []

        def reject(_call: FunctionCall) -> str:
            raise MF2Error("bad-operand", "Rejected test operand.")

        def select(match: FunctionMatch) -> int | None:
            selected_values.append(match.raw_value)
            return 1

        registry = (
            FunctionRegistry.portable()
            .with_function("test:reject", reject)
            .with_selector("test:reject", select)
        )
        formatted = format_message(
            parsed.model,
            {"value": "attacker", "state": "ready"},
            functions=registry,
        )

        self.assertEqual("ready", formatted.value)
        self.assertEqual([], selected_values)
        self.assertEqual(
            ["bad-operand", "bad-selector"],
            [error.code for error in formatted.errors],
        )

    def test_unavailable_string_selector_still_validates_normalized_keys(
        self,
    ) -> None:
        first_key = "\u1e0a\u0323"
        second_key = "\u1e0c\u0307"
        parsed = parse_to_model(
            ".input {$value :string}\n"
            ".match $value\n"
            f"{first_key} {{{{first}}}}\n"
            f"{second_key} {{{{second}}}}\n"
            "* {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        for arguments in [{}, {"value": "different"}]:
            with self.subTest(arguments=arguments):
                with self.assertRaises(MF2Error) as raised:
                    format_message(parsed.model, arguments)
                self.assertEqual("duplicate-variant", raised.exception.code)

    def test_failed_binding_never_reaches_dependent_formatter(self) -> None:
        parsed = parse_to_model(
            ".input {$value :test:reject}\n"
            ".local $copy = {$value :test:observe}\n"
            "{{{$copy}}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        observed_values: list[object] = []

        def reject(_call: FunctionCall) -> str:
            raise MF2Error("bad-operand", "Rejected test operand.")

        def observe(call: FunctionCall) -> str:
            observed_values.append(call.raw_value)
            return "observed"

        registry = (
            FunctionRegistry()
            .with_function("test:reject", reject)
            .with_function("test:observe", observe)
        )
        formatted = format_message(
            parsed.model,
            {"value": "attacker"},
            functions=registry,
        )

        self.assertEqual("{$copy}", formatted.value)
        self.assertNotIn("attacker", formatted.value)
        self.assertEqual([], observed_values)

    def test_failed_binding_never_reaches_function_options(self) -> None:
        parsed = parse_to_model(
            ".input {$value :test:reject}\n"
            "{{{|ok| :test:option observed=$value}}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        observed_options: list[str | None] = []

        def reject(_call: FunctionCall) -> str:
            raise MF2Error("bad-operand", "Rejected test operand.")

        def observe_option(call: FunctionCall) -> str:
            observed_options.append(call.option_value("observed"))
            return call.value

        registry = (
            FunctionRegistry()
            .with_function("test:reject", reject)
            .with_function("test:option", observe_option)
        )
        formatted = format_message(
            parsed.model,
            {"value": "attacker"},
            functions=registry,
        )

        self.assertEqual([], observed_options)
        self.assertNotIn("attacker", formatted.value)

    def test_failed_local_shadows_same_named_argument(self) -> None:
        parsed = parse_to_model(
            ".local $value = {|not-a-number| :integer}\n"
            "{{Value: {$value}}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        formatted = format_message(parsed.model, {"value": "attacker"})

        self.assertEqual("Value: {$value}", formatted.value)
        self.assertNotIn("attacker", formatted.value)
        self.assertEqual(
            ["bad-operand"], [error.code for error in formatted.errors]
        )

    def test_plural_operands_enforce_numeric_bounds_after_custom_formatting(
        self,
    ) -> None:
        parsed = parse_to_model(
            ".input {$n :number}\n"
            ".match $n\n"
            "other {{selected}}\n"
            "* {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        registry = FunctionRegistry().with_function("number", lambda call: call.value)

        for locale in ["en", "zz"]:
            with self.subTest(locale=locale):
                accepted = format_message(
                    parsed.model,
                    {"n": "1e1000"},
                    locale=locale,
                    functions=registry,
                )
                excessive_exponent = format_message(
                    parsed.model,
                    {"n": "1e1001"},
                    locale=locale,
                    functions=registry,
                )
                excessive_coefficient = format_message(
                    parsed.model,
                    {"n": "9" * 1001},
                    locale=locale,
                    functions=registry,
                )

                self.assertEqual("selected", accepted.value)
                self.assertEqual("fallback", excessive_exponent.value)
                self.assertEqual("fallback", excessive_coefficient.value)

    def test_portable_number_accepts_exact_numeric_boundaries(self) -> None:
        parsed = parse_to_model("{$n :number}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        maximum_coefficient = "9" * 1_000
        maximum_fixed_fraction = f"0.{('0' * 999)}{'1' * 1_000}"

        for value in [
            maximum_coefficient,
            f"-{maximum_coefficient}",
            maximum_fixed_fraction,
            f"-{maximum_fixed_fraction}",
            "1e1000",
            "1e-1000",
        ]:
            with self.subTest(length=len(value), prefix=value[:10]):
                formatted = format_message(parsed.model, {"n": value})
                self.assertEqual([], formatted.errors)

    def test_percent_plural_operands_are_bounded_before_scaling(self) -> None:
        parsed = parse_to_model(
            ".input {$n :percent}\n"
            ".match $n\n"
            "other {{selected}}\n"
            "* {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        registry = FunctionRegistry().with_function("percent", lambda call: call.value)

        for locale in ["en", "zz"]:
            with self.subTest(locale=locale):
                accepted = format_message(
                    parsed.model,
                    {"n": "1e998"},
                    locale=locale,
                    functions=registry,
                )
                excessive_scaled_exponent = format_message(
                    parsed.model,
                    {"n": "1e999"},
                    locale=locale,
                    functions=registry,
                )
                excessive_input_exponent = format_message(
                    parsed.model,
                    {"n": "1e1000000"},
                    locale=locale,
                    functions=registry,
                )
                excessive_text = format_message(
                    parsed.model,
                    {"n": f"{'9' * 2008}%"},
                    locale=locale,
                    functions=registry,
                )

                self.assertEqual("selected", accepted.value)
                self.assertEqual("fallback", excessive_scaled_exponent.value)
                self.assertEqual("fallback", excessive_input_exponent.value)
                self.assertEqual("fallback", excessive_text.value)

    def test_argument_text_is_never_reparsed_as_code_or_mf2(self) -> None:
        parsed = parse_to_model("Value: {$value}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        payload = (
            "{$admin} {{message}} __import__('os').system('id'); "
            "${IFS}; <script>alert(1)</script>"
        )

        formatted = format_message(parsed.model, {"value": payload})

        self.assertEqual(f"Value: {payload}", formatted.value)
        self.assertEqual([], formatted.errors)

    def test_huge_python_integers_recover_before_string_conversion(self) -> None:
        maximum_integer = 10**999
        huge_integer = 10**1_000
        expression = parse_to_model("Value: {$value}")
        select = parse_to_model(
            ".input {$value :number}\n"
            ".match $value\n"
            "other {{unsafe}}\n"
            "* {{fallback}}"
        )
        option = parse_to_model("{|ok| :test:option value=$digits}")
        self.assertIsNotNone(expression.model, expression.diagnostics)
        self.assertIsNotNone(select.model, select.diagnostics)
        self.assertIsNotNone(option.model, option.diagnostics)

        rendered_expression = format_message(
            expression.model, {"value": huge_integer}
        )
        rendered_maximum = format_message(
            expression.model, {"value": maximum_integer}
        )
        rendered_select = format_message(select.model, {"value": huge_integer})
        rendered_option = format_message(
            option.model,
            {"digits": huge_integer},
            functions=FunctionRegistry().with_function(
                "test:option", lambda call: call.option_value("value") or ""
            ),
        )

        self.assertEqual("Value: {$value}", rendered_expression.value)
        self.assertEqual(
            ["bad-operand"],
            [error.code for error in rendered_expression.errors],
        )
        self.assertEqual(f"Value: {maximum_integer}", rendered_maximum.value)
        self.assertEqual([], rendered_maximum.errors)
        self.assertEqual("fallback", rendered_select.value)
        self.assertEqual(
            ["bad-operand", "bad-selector"],
            [error.code for error in rendered_select.errors],
        )
        self.assertEqual("{|ok|}", rendered_option.value)
        self.assertEqual(
            ["bad-option"],
            [error.code for error in rendered_option.errors],
        )

    def test_deep_function_source_chain_does_not_recurse(self) -> None:
        depth = 1_100
        declarations = [
            ".local $v0 = {1 :number signDisplay=always}",
            *[
                f".local $v{index} = {{$v{index - 1} :number}}"
                for index in range(1, depth + 1)
            ],
        ]
        body = "{{" + f"{{$v{depth} :offset add=1}}" + "}}"
        parsed = parse_to_model("\n".join([*declarations, body]))
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        formatted = format_message(parsed.model)

        self.assertEqual("+2", formatted.value)
        self.assertEqual([], formatted.errors)

        root_options = {"select": "exact", "signDisplay": "always"}
        source = FunctionSource(
            value="1",
            function={"name": "number", "options": {}},
            inherited_source=None,
            _option_resolver=lambda name, default: root_options.get(name, default),
        )
        for _ in range(depth):
            source = FunctionSource(
                value="localized",
                function={"name": "test:pass", "options": {}},
                inherited_source=source,
                _option_resolver=lambda _name, default: default,
            )

        self.assertEqual(Decimal("1"), _parse_source_decimal(source))
        self.assertTrue(_inherited_sign_display_always(source))
        self.assertTrue(_inherited_exact_numeric_source(source))

    def test_cyclic_function_source_chain_is_rejected(self) -> None:
        source = FunctionSource(
            value="localized",
            function={"name": "test:pass", "options": {}},
            inherited_source=None,
            _option_resolver=lambda _name, default: default,
        )
        object.__setattr__(source, "inherited_source", source)

        for operation in [
            _parse_source_decimal,
            _inherited_sign_display_always,
            _inherited_exact_numeric_source,
        ]:
            with self.subTest(operation=operation.__name__):
                with self.assertRaises(MF2Error) as raised:
                    operation(source)
                self.assertEqual("bad-operand", raised.exception.code)

    def test_locale_identifiers_have_byte_and_subtag_bounds(self) -> None:
        maximum_locale = "-".join(["abcdefgh", *(["abcdefg"] * 15)])
        self.assertEqual(128, len(maximum_locale.encode("utf-8")))
        self.assertEqual(maximum_locale, canonical_locale_key(maximum_locale))

        invalid_locales = [
            f"{maximum_locale}x",
            "-".join(["aa"] * 17),
            "é" * 65,
            "\ud800",
        ]
        for locale in invalid_locales:
            with self.subTest(length=len(locale), subtags=locale.count("-") + 1):
                with self.assertRaises(ValueError):
                    canonical_locale_key(locale)

        parsed = parse_to_model(
            ".input {$n :number}\n"
            ".match $n\n"
            "one {{selected}}\n"
            "* {{fallback}}"
        )
        self.assertIsNotNone(parsed.model, parsed.diagnostics)
        formatted = format_message(parsed.model, {"n": 1}, locale="a" * 129)
        self.assertEqual("fallback", formatted.value)


if __name__ == "__main__":
    unittest.main()
