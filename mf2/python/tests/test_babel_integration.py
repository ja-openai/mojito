from __future__ import annotations

from datetime import datetime, timedelta
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import mojito_mf2
from mojito_mf2 import format_message, parse_to_model

BABEL_AVAILABLE = importlib.util.find_spec("babel") is not None


class BabelIntegrationTest(unittest.TestCase):
    def test_00_core_import_does_not_import_optional_babel_module(self) -> None:
        self.assertIsNotNone(mojito_mf2)
        self.assertNotIn("mojito_mf2.babel", sys.modules)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_formats_platform_values(self) -> None:
        from babel.dates import format_date, format_datetime, format_time, format_timedelta, get_timezone
        from babel.numbers import format_currency, format_decimal, format_percent

        babel_function_registry = importlib.import_module("mojito_mf2.babel").babel_function_registry
        functions = babel_function_registry()
        source = (
            "number={$amount :number maximumFractionDigits=2}; "
            "percent={$rate :percent maximumFractionDigits=1}; "
            "currency={$price :currency currency=EUR}; "
            "date={$instant :date dateStyle=full timeZone=UTC}; "
            "time={$instant :time timeStyle=medium timeZone=UTC}; "
            "datetime={$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}; "
            "relative={$delta :relativeTime unit=day numeric=always}"
        )
        parsed = parse_to_model(source)
        self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
        instant = datetime.fromisoformat("2026-05-21T14:30:15+00:00")
        arguments = {
            "amount": 12345.678,
            "rate": 0.1234,
            "price": 9876,
            "instant": instant,
            "delta": -3,
        }

        for locale in ["en", "fr", "ja", "ar"]:
            actual = format_message(
                parsed.model,
                arguments,
                locale=locale,
                functions=functions,
            )
            expected = (
                f"number={format_decimal(12345.678, format='#,##0.##', locale=locale)}; "
                f"percent={format_percent(0.1234, format='#,##0.#%', locale=locale)}; "
                f"currency={format_currency(9876, 'EUR', locale=locale)}; "
                f"date={format_date(instant.date(), format='full', locale=locale)}; "
                f"time={format_time(instant, format='medium', locale=locale, tzinfo=get_timezone('UTC'))}; "
                f"datetime={format_datetime(instant, format='medium', locale=locale, tzinfo=get_timezone('UTC'))}; "
                f"relative={format_timedelta(timedelta(days=-3), granularity='day', add_direction=True, format='long', locale=locale)}"
            )
            self.assertEqual(expected, actual.value)
            self.assertEqual([], actual.errors)

        mixed_styles = parse_to_model("{$instant :datetime dateStyle=full timeStyle=short}")
        mixed_result = format_message(
            mixed_styles.model,
            {"instant": instant},
            locale="en",
            functions=functions,
        )
        self.assertEqual(["bad-option"], [error.code for error in mixed_result.errors])

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_keeps_plural_operands_separate_from_display(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        reference_root = (
            Path(__file__).resolve().parents[2]
            / "reference"
            / "fixtures"
        )
        fixture_paths = list(
            (reference_root / "selection-operands").glob("*/*.json")
        ) + list(
            (reference_root / "resolved-values" / "adapters").glob("*.json")
        )

        checked = 0
        for fixture_path in sorted(fixture_paths):
            fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
            parsed = parse_to_model(fixture["source"])
            self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
            for case in fixture["formatCases"]:
                with self.subTest(fixture=fixture["name"], case=case["name"]):
                    actual = format_message(
                        parsed.model,
                        case["arguments"],
                        locale=case["locale"],
                        functions=functions,
                    )
                    self.assertEqual(case["expected"], actual.value)
                    self.assertEqual([], actual.errors)
                    checked += 1

        self.assertEqual(47, checked)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_preserves_source_values_across_function_chains(
        self,
    ) -> None:
        from babel.dates import (
            format_date,
            format_datetime,
            format_time,
            format_timedelta,
            get_timezone,
        )
        from babel.numbers import format_currency, format_decimal, format_percent

        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        instant = datetime.fromisoformat("2026-05-21T14:30:15+00:00")
        input_zone_instant = datetime.fromisoformat("2026-05-21T14:30:15+02:00")
        pacific = get_timezone("America/Los_Angeles")
        cases = [
            (
                ".local $n = {1000000 :number} "
                "{{Value {$n :number maximumFractionDigits=0}}}",
                "fr",
                f"Value {format_decimal(1000000, format='#,##0', locale='fr')}",
            ),
            (
                ".local $n = {1000000.9 :number} "
                "{{Value {$n :integer}}}",
                "fr",
                f"Value {format_decimal(1000000, format='#,##0', locale='fr')}",
            ),
            (
                ".local $n = {1.2 :number minimumFractionDigits=2} "
                "{{Value {$n :number}}}",
                "en",
                f"Value {format_decimal(1.2, format='#,##0.00', locale='en')}",
            ),
            (
                ".local $n = {0.01 :percent} {{Value {$n :percent}}}",
                "en",
                f"Value {format_percent(0.01, locale='en')}",
            ),
            (
                ".local $n = {42 :currency currency=EUR} "
                "{{Value {$n :currency}}}",
                "fr",
                f"Value {format_currency(42, 'EUR', locale='fr')}",
            ),
            (
                ".local $d = {|2026-05-21T14:30:15+00:00| :datetime} "
                "{{Value {$d :date dateStyle=full}}}",
                "en",
                f"Value {format_date(instant.date(), format='full', locale='en')}",
            ),
            (
                ".local $d = {|2026-05-21T14:30:15+00:00| :datetime} "
                "{{Value {$d :time timeStyle=medium timeZone=America/Los_Angeles}}}",
                "en",
                "Value "
                + format_time(
                    instant, format="medium", locale="en", tzinfo=pacific
                ),
            ),
            (
                ".local $d = {|2026-05-21T14:30:15+00:00| :datetime} "
                "{{Value {$d :datetime dateStyle=medium timeStyle=medium timeZone=America/Los_Angeles}}}",
                "en",
                "Value "
                + format_datetime(
                    instant, format="medium", locale="en", tzinfo=pacific
                ),
            ),
            (
                "Value {|2026-05-21T14:30:15+00:00| :time precision=second}",
                "en",
                "Value "
                + format_time(
                    instant,
                    format="medium",
                    locale="en",
                    tzinfo=get_timezone("UTC"),
                ),
            ),
            (
                "Value {|2026-05-21T14:30:15+00:00| :datetime "
                "dateLength=long timePrecision=second}",
                "en",
                "Value "
                + format_datetime(
                    instant,
                    format="long",
                    locale="en",
                    tzinfo=get_timezone("UTC"),
                ),
            ),
            (
                "Value {|2026-05-21T14:30:15+02:00| :datetime "
                "timeZone=input}",
                "en",
                "Value "
                + format_datetime(
                    input_zone_instant,
                    format="medium",
                    locale="en",
                    tzinfo=input_zone_instant.tzinfo,
                ),
            ),
            (
                ".local $n = {-3 :number} "
                "{{Value {$n :relativeTime unit=day numeric=always}}}",
                "en",
                "Value "
                + format_timedelta(
                    timedelta(days=-3),
                    granularity="day",
                    add_direction=True,
                    format="long",
                    locale="en",
                ),
            ),
        ]

        for source, locale, expected in cases:
            with self.subTest(source=source, locale=locale):
                parsed = parse_to_model(source)
                self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                actual = format_message(
                    parsed.model, locale=locale, functions=functions
                )
                self.assertEqual(expected, actual.value)
                self.assertEqual([], actual.errors)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_rejects_invalid_mf2_numeric_text(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        sources = [
            "{$amount :number}",
            "{$amount :percent}",
            "{$amount :integer}",
            "{$amount :currency currency=USD}",
            "{$amount :relativeTime unit=day numeric=always}",
        ]

        for source in sources:
            for amount in ["00", "+1", "1.", ".5"]:
                with self.subTest(source=source, amount=amount):
                    parsed = parse_to_model(source)
                    self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                    actual = format_message(
                        parsed.model,
                        {"amount": amount},
                        functions=functions,
                    )
                    self.assertEqual(
                        ["bad-operand"],
                        [error.code for error in actual.errors],
                    )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_reports_currency_resolution_errors(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        cases = [
            ("{42 :currency}", ["bad-operand"]),
            (
                ".local $amount = {42 :currency currency=EUR} "
                ".match $amount * {{fallback}}",
                ["bad-selector"],
            ),
        ]

        for source, expected_errors in cases:
            with self.subTest(source=source):
                parsed = parse_to_model(source)
                self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                actual = format_message(parsed.model, functions=functions)
                self.assertEqual(
                    expected_errors,
                    [error.code for error in actual.errors],
                )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_stops_currency_provenance_at_number(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        prefix = (
            ".local $usd = {42 :currency currency=USD} "
            ".local $plain = {$usd :number} "
        )

        missing = parse_to_model(prefix + "{{Value {$plain :currency}}}")
        self.assertFalse(missing.has_diagnostics, missing.diagnostics)
        missing_result = format_message(missing.model, functions=functions)
        self.assertEqual(
            ["bad-operand"],
            [error.code for error in missing_result.errors],
        )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_converts_aware_datetime_for_time(self) -> None:
        from babel.dates import format_time, get_timezone

        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        parsed = parse_to_model(
            "{$instant :time timeStyle=medium timeZone=America/Los_Angeles}"
        )
        self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
        instant = datetime.fromisoformat("2026-05-21T14:30:15+00:00")

        actual = format_message(
            parsed.model,
            {"instant": instant},
            locale="en",
            functions=functions,
        )

        self.assertEqual(
            format_time(
                instant,
                format="medium",
                locale="en",
                tzinfo=get_timezone("America/Los_Angeles"),
            ),
            actual.value,
        )
        self.assertEqual([], actual.errors)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_keeps_currency_out_of_portable_registry(self) -> None:
        from babel.numbers import format_currency

        babel_function_registry = importlib.import_module("mojito_mf2.babel").babel_function_registry
        parsed = parse_to_model("Total {$amount :currency currency=EUR}")

        portable = format_message(parsed.model, {"amount": 42})
        babel = format_message(
            parsed.model,
            {"amount": 42},
            locale="fr",
            functions=babel_function_registry(),
        )

        self.assertEqual("Total {$amount}", portable.value)
        self.assertEqual(["unknown-function"], [error.code for error in portable.errors])
        self.assertEqual(f"Total {format_currency(42, 'EUR', locale='fr')}", babel.value)
        self.assertEqual([], babel.errors)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_accepts_bcp47_and_babel_locale_identifiers(self) -> None:
        from babel import Locale
        from babel.numbers import format_decimal

        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        parsed = parse_to_model("{$amount :number}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        for provided, expected in [
            ("fr-FR", "fr_FR"),
            ("fr_FR", "fr_FR"),
            ("en-US", "en_US"),
            ("pt-BR", "pt_BR"),
            ("zh-Hant-TW", "zh_Hant_TW"),
            ("en-US-u-ca-gregory", "en_US"),
            ("iw-IL", "he_IL"),
        ]:
            with self.subTest(locale=provided):
                formatted = format_message(
                    parsed.model,
                    {"amount": "1234.5"},
                    locale=provided,
                    functions=functions,
                )
                self.assertEqual(
                    format_decimal("1234.5", locale=Locale.parse(expected)),
                    formatted.value,
                )
                self.assertEqual([], formatted.errors)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_recovers_for_invalid_locales(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        parsed = parse_to_model("{$amount :number}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        for locale in [
            "not_a_locale",
            "invalid__locale",
            "",
            "a" * 129,
            "-".join(["aa"] * 17),
            f"en@{'x' * 129}",
            "\ud800",
        ]:
            with self.subTest(locale=locale):
                formatted = format_message(
                    parsed.model,
                    {"amount": 42},
                    locale=locale,
                    functions=functions,
                )
                self.assertEqual(
                    ["bad-option"], [error.code for error in formatted.errors]
                )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_recovers_for_invalid_fraction_options(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        cases = [
            "{$amount :number minimumFractionDigits=|²|}",
            "{$amount :number maximumFractionDigits=|١|}",
            "{$amount :percent maximumFractionDigits=1001}",
            "{$amount :number minimumFractionDigits=3 maximumFractionDigits=2}",
        ]

        for source in cases:
            with self.subTest(source=source):
                parsed = parse_to_model(source)
                self.assertIsNotNone(parsed.model, parsed.diagnostics)
                formatted = format_message(
                    parsed.model, {"amount": "1.25"}, functions=functions
                )
                self.assertEqual(
                    ["bad-option"], [error.code for error in formatted.errors]
                )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_formats_large_bounded_numbers(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        cases = [
            "{$amount :number maximumFractionDigits=2}",
            "{$amount :percent maximumFractionDigits=2}",
            "{$amount :integer}",
            "{$amount :currency currency=USD}",
        ]

        for source in cases:
            with self.subTest(source=source):
                parsed = parse_to_model(source)
                self.assertIsNotNone(parsed.model, parsed.diagnostics)
                formatted = format_message(
                    parsed.model, {"amount": "1e100"}, functions=functions
                )
                self.assertEqual([], formatted.errors)
                self.assertNotIn("{$amount}", formatted.value)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_preserves_numeric_semantics_for_plural_selection(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        cases = [
            ("number", "", 1_000, "es-ES", "other"),
            ("number", "", 1_000_000, "fr-FR", "many"),
            ("integer", "", 1_000_000, "fr-FR", "many"),
            ("integer", "", 1.2, "en", "one"),
            ("percent", "", "0.01", "fr-FR", "one"),
            ("percent", "", "0.015", "cs", "many"),
            ("number", " minimumFractionDigits=1", 1, "ru", "other"),
            ("number", " maximumFractionDigits=0", "1.2", "en", "one"),
        ]

        for function, options, count, locale, expected in cases:
            with self.subTest(
                function=function,
                options=options,
                count=count,
                locale=locale,
            ):
                parsed = parse_to_model(
                    f".input {{$count :{function}{options}}}\n"
                    ".match $count\n"
                    f"{expected} {{{{{expected}}}}}\n"
                    "* {{fallback}}"
                )
                self.assertIsNotNone(parsed.model, parsed.diagnostics)

                formatted = format_message(
                    parsed.model,
                    {"count": count},
                    locale=locale,
                    functions=functions,
                )

                self.assertEqual([], formatted.errors)
                self.assertEqual(expected, formatted.value)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_keeps_exact_numeric_selection_semantics(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        cases = [
            ("number", 1_000_000, "1000000"),
            ("integer", 1.2, "1"),
            ("percent", "0.01", "1"),
        ]

        for function, count, key in cases:
            with self.subTest(function=function, count=count, key=key):
                parsed = parse_to_model(
                    f".input {{$count :{function} select=exact}}\n"
                    ".match $count\n"
                    f"{key} {{{{selected}}}}\n"
                    "* {{fallback}}"
                )
                self.assertIsNotNone(parsed.model, parsed.diagnostics)

                formatted = format_message(
                    parsed.model,
                    {"count": count},
                    locale="fr-FR",
                    functions=functions,
                )

                self.assertEqual([], formatted.errors)
                self.assertEqual("selected", formatted.value)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_currency_reannotation_handles_deep_source_chains(self) -> None:
        from babel.numbers import format_currency

        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        depth = 1_100
        declarations = [
            ".local $v0 = {1 :currency currency=USD}",
            *[
                f".local $v{index} = {{$v{index - 1} :currency}}"
                for index in range(1, depth + 1)
            ],
        ]
        body = "{{" + f"{{$v{depth} :currency}}" + "}}"
        parsed = parse_to_model("\n".join([*declarations, body]))
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        formatted = format_message(
            parsed.model,
            locale="en",
            functions=functions,
        )

        self.assertEqual(format_currency(1, "USD", locale="en"), formatted.value)
        self.assertEqual([], formatted.errors)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_recovers_for_unbounded_numeric_operands(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        sources = [
            "{$amount :number maximumFractionDigits=2}",
            "{$amount :percent maximumFractionDigits=2}",
            "{$amount :integer}",
            "{$amount :currency currency=USD}",
            "{$amount :relativeTime unit=day numeric=always}",
        ]

        for source in sources:
            for amount in ["1e1000000", "1e-5000", "9" * 1001]:
                with self.subTest(source=source, amount=amount[:20]):
                    parsed = parse_to_model(source)
                    self.assertIsNotNone(parsed.model, parsed.diagnostics)
                    formatted = format_message(
                        parsed.model,
                        {"amount": amount},
                        functions=functions,
                    )
                    self.assertEqual(
                        ["bad-operand"], [error.code for error in formatted.errors]
                    )

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_registry_rejects_unsupported_natural_relative_terms(self) -> None:
        functions = importlib.import_module(
            "mojito_mf2.babel"
        ).babel_function_registry()
        parsed = parse_to_model("{$amount :relativeTime unit=day numeric=auto}")
        self.assertIsNotNone(parsed.model, parsed.diagnostics)

        for amount in [-1, 0, 1]:
            with self.subTest(amount=amount):
                formatted = format_message(
                    parsed.model, {"amount": amount}, functions=functions
                )
                self.assertEqual("{$amount}", formatted.value)
                self.assertEqual(
                    ["bad-option"], [error.code for error in formatted.errors]
                )


if __name__ == "__main__":
    unittest.main()
