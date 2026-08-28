from __future__ import annotations

from datetime import datetime, timedelta
import importlib
import importlib.util
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

        for locale in ["not_a_locale", "invalid__locale", ""]:
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
