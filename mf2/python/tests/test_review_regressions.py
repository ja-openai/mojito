from copy import deepcopy
from datetime import date, datetime
from decimal import Decimal
import importlib.util
import unittest
from unittest.mock import patch

from mojito_mf2 import FunctionRegistry, MF2Error, format_message, format_message_to_parts, parse_to_model


class ReviewRegressionsTest(unittest.TestCase):
    def test_callback_annotations_cannot_mutate_catalog_or_cached_source(self):
        source = ".input {$n :number maximumFractionDigits=0}\n.local $warm = {$n :number}\n.local $mutate = {$n :mutate}\n{{{$n :number}}}"
        model = parse_to_model(source).model
        original = deepcopy(model)
        def mutate(call):
            with self.assertRaises(TypeError):
                call.inherited_source.function["options"]["maximumFractionDigits"]["value"] = "2"
            with self.assertRaises(TypeError):
                call.function["name"] = "number"
            return call.value
        functions = FunctionRegistry.portable().with_function("mutate", mutate)
        self.assertEqual("1", format_message(model, {"n": Decimal("1.234")}, functions=functions).value)
        self.assertEqual(original, model)
        # Caller edits between calls remain valid and cannot reuse a prior cache.
        model["declarations"][0]["value"]["function"]["options"]["maximumFractionDigits"]["value"] = "2"
        self.assertEqual("1.23", format_message(model, {"n": Decimal("1.234")}, functions=functions).value)

    def test_literal_numeric_chain_visits_grow_linearly(self):
        from mojito_mf2 import _portable_functions
        count = 512
        source = ".local $n0 = {1.29 :number}\n" + "".join(
            f".local $n{i} = {{$n{i-1} :number}}\n" for i in range(1, count)
        ) + "{{{$n" + str(count - 1) + "}}}"
        original = _portable_functions._iter_source_chain
        visits = 0
        def counted(source):
            nonlocal visits
            for item in original(source):
                visits += 1
                yield item
        with patch.object(_portable_functions, "_iter_source_chain", counted):
            result = format_message(parse_to_model(source).model)
        self.assertEqual("1.29", result.value)
        self.assertEqual([], result.errors)
        self.assertLess(visits, count * 16)

    def test_bidi_numeric_metadata_is_private_and_custom_overrides_are_isolated(self):
        parsed = parse_to_model("{1 :number}")
        original = deepcopy(parsed.model)
        self.assertEqual("1", format_message(parsed.model, bidi_isolation="default").value)
        self.assertEqual([{"type": "expression", "value": "1"}], format_message_to_parts(parsed.model).parts)
        custom = FunctionRegistry.portable().with_function("number", lambda call: "custom")
        self.assertEqual("\u2068custom\u2069", format_message(parsed.model, functions=custom, bidi_isolation="default").value)
        inherited = parse_to_model(".local $n = {1 :number u:dir=ltr} {{{$n}}}")
        self.assertEqual("\u20661\u2069", format_message(inherited.model, bidi_isolation="default").value)
        self.assertEqual(original, parsed.model)

    def test_malformed_variant_terminates(self):
        source = ".input {$x :string} .match $x * {{ok}} {x}"
        result = parse_to_model(source)
        self.assertIsNone(result.model)
        self.assertEqual(["invalid-variant-key"], [item.code for item in result.diagnostics])

    def test_direction_is_context_metadata_and_not_a_handler_option(self):
        seen = []
        def custom(call):
            seen.append(call.option_value("u:dir"))
            return call.value
        functions = FunctionRegistry.portable().with_function("custom", custom)
        model = parse_to_model("{text :custom u:dir=$dir}").model
        self.assertEqual("\u2067text\u2069", format_message(model, {"dir": "rtl"}, functions=functions, bidi_isolation="default").value)
        self.assertEqual([None], seen)

    def test_parts_do_not_mutate_catalog_or_other_results(self):
        model = parse_to_model("{x @title=original} {#tag option=original @title=original /}").model
        original = deepcopy(model)
        first = format_message_to_parts(model)
        second = format_message_to_parts(model)
        first.parts[0]["attributes"]["title"]["value"] = "changed"
        first.parts[2]["options"]["option"]["value"] = "changed"
        first.parts[2]["attributes"]["title"]["value"] = "changed"
        self.assertEqual(original, model)
        self.assertEqual(second, format_message_to_parts(model))

    def test_imported_required_shapes_fail_as_mf2_errors(self):
        for model in [None, [], {}, {"type": "message", "pattern": []},
                      {"type": "message", "declarations": [], "pattern": [None]},
                      {"type": "message", "declarations": [], "pattern": [{"type": "expression"}]}]:
            with self.subTest(model=model), self.assertRaises(MF2Error) as caught:
                format_message(model)
            self.assertEqual("invalid-model", caught.exception.code)

    @unittest.skipIf(importlib.util.find_spec("babel") is None, "Babel is not installed")
    def test_babel_date_zone_and_calendar_date(self):
        from mojito_mf2.babel import babel_function_registry
        functions = babel_function_registry()
        model = parse_to_model("{$d :date timeZone=America/Los_Angeles}").model
        for operand in [datetime.fromisoformat("2026-01-01T01:00:00+00:00"), "2026-01-01T01:00:00+00:00"]:
            result = format_message(model, {"d": operand}, functions=functions)
            self.assertEqual("Dec 31, 2025", result.value)
            self.assertEqual([], result.errors)
        for operand in [date(2026, 1, 1), "2026-01-01", "2026-01-01T01:00:00"]:
            self.assertEqual("Jan 1, 2026", format_message(model, {"d": operand}, functions=functions).value)
        inherited = parse_to_model(".local $d = {|2026-01-01T01:00:00Z| :datetime} {{{$d :date timeZone=America/Los_Angeles}}}").model
        self.assertEqual("Dec 31, 2025", format_message(inherited, functions=functions).value)
        for source, operand, code in [("{$d :date timeZone=Invalid/Zone}", date(2026, 1, 1), "bad-option"),
                                      ("{$d :date timeZone=input}", date(2026, 1, 1), "bad-operand")]:
            result = format_message(parse_to_model(source).model, {"d": operand}, functions=functions)
            self.assertEqual([code], [error.code for error in result.errors])

    @unittest.skipIf(importlib.util.find_spec("babel") is None, "Babel is not installed")
    def test_babel_relative_time_falls_back_for_partial_style_data(self):
        from mojito_mf2.babel import babel_function_registry
        functions = babel_function_registry()
        for locale, style, future, past in [
            ("da", "short", "om 1 dag", "1 dag siden"),
            ("hu", "short", "1 nap múlva", "1 napja"),
            ("ur", "short", "1 دن میں", "1 دن پہلے"),
            ("el", "narrow", "σε 1 ημέρα", "1 ημ. πριν"),
        ]:
            model = parse_to_model("{$n :relativeTime unit=day style=" + style + "}").model
            for value, expected in [(1, future), (-1, past)]:
                with self.subTest(locale=locale, style=style, value=value):
                    actual = format_message(model, {"n": value}, locale=locale, functions=functions)
                    self.assertEqual(expected, actual.value)
                    self.assertEqual([], actual.errors)

        for locale in ["en-US-posix", "en-US-POSIX", "en_US_POSIX"]:
            for source, expected in [("{1 :relativeTime unit=day}", "in 1 day"), ("{1 :number}", "1"), ("{1e20 :number}", "100000000000000000000")]:
                with self.subTest(locale=locale, source=source):
                    actual = format_message(parse_to_model(source).model, locale=locale, functions=functions)
                    self.assertEqual(expected, actual.value)
                    self.assertEqual([], actual.errors)

        for locale in ["ccp", "ccp-IN", "ccp-BD"]:
            for unit, style, value, expected in [
                ("second", "narrow", 1, "1 𑄥𑄬𑄉𑄬𑄚𑄴𑄘𑄬"),
                ("month", "short", -1, "1 𑄟𑄏𑄧 𑄃𑄉𑄬"),
                ("month", "narrow", -1, "1 𑄟𑄏𑄧 𑄃𑄉𑄬"),
            ]:
                with self.subTest(locale=locale, unit=unit, style=style):
                    model = parse_to_model(f"{{$n :relativeTime unit={unit} style={style}}}").model
                    actual = format_message(model, {"n": value}, locale=locale, functions=functions)
                    self.assertEqual(expected, actual.value)
                    self.assertEqual([], actual.errors)

    @unittest.skipIf(importlib.util.find_spec("babel") is None, "Babel is not installed")
    def test_babel_minimum_only_fraction_precision(self):
        from mojito_mf2.babel import babel_function_registry
        functions = babel_function_registry()
        for locale in ["en", "en-US-POSIX"]:
            for digits in [8, 20, 100, 1000]:
                for function, value, integer, fraction, suffix in [
                    ("number", "1", "1", "", ""),
                    ("number", "0.29", "0", "29", ""),
                    ("percent", "1", "100", "", "%"),
                    ("percent", "0.29", "29", "", "%"),
                ]:
                    with self.subTest(locale=locale, digits=digits, function=function, value=value):
                        model = parse_to_model(f"{{{value} :{function} minimumFractionDigits={digits}}}").model
                        actual = format_message(model, locale=locale, functions=functions)
                        self.assertEqual(integer + "." + fraction.ljust(digits, "0") + suffix, actual.value)
                        self.assertEqual([], actual.errors)

    @unittest.skipIf(importlib.util.find_spec("babel") is None, "Babel is not installed")
    def test_babel_relative_time_preserves_requested_units_and_magnitude(self):
        from mojito_mf2.babel import babel_function_registry
        functions = babel_function_registry()
        for value, unit, expected in [(6, "day", "in 6 days"), (60, "second", "in 60 seconds"),
                                      (12, "month", "in 12 months"), (0, "day", "in 0 days"),
                                      (Decimal("1.5"), "day", "in 1.5 days"),
                                      (-6, "day", "6 days ago"), (3000000, "second", "in 3,000,000 seconds")]:
            with self.subTest(value=value, unit=unit):
                model = parse_to_model("{$n :relativeTime unit=" + unit + "}").model
                actual = format_message(model, {"n": value}, functions=functions)
                self.assertEqual(expected, actual.value)
                self.assertEqual([], actual.errors)
        for currency in ["US", "USDD", "123", "U$D", "éur"]:
            model = parse_to_model("{1 :currency currency=|" + currency + "|}").model
            self.assertEqual(["bad-option"], [error.code for error in format_message(model, functions=functions).errors])
