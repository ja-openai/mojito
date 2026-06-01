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
                f"time={format_time(instant.time(), format='medium', locale=locale, tzinfo=get_timezone('UTC'))}; "
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


if __name__ == "__main__":
    unittest.main()
