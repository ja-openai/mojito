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
            "price": 9876.5,
            "instant": instant,
            "delta": -3,
        }

        for locale in ["en-US", "fr-FR", "ja-JP", "ar-EG"]:
            babel_locale = locale.replace("-", "_")
            actual = format_message(
                parsed.model,
                arguments,
                locale=locale,
                functions=functions,
            )
            expected = (
                f"number={format_decimal(12345.678, format='#,##0.##', locale=babel_locale)}; "
                f"percent={format_percent(0.1234, format='#,##0.#%', locale=babel_locale)}; "
                f"currency={format_currency(9876.5, 'EUR', locale=babel_locale)}; "
                f"date={format_date(instant.date(), format='full', locale=babel_locale)}; "
                f"time={format_time(instant.time(), format='medium', locale=babel_locale, tzinfo=get_timezone('UTC'))}; "
                f"datetime={format_datetime(instant, format='medium', locale=babel_locale, tzinfo=get_timezone('UTC'))}; "
                f"relative={format_timedelta(timedelta(days=-3), granularity='day', add_direction=True, format='long', locale=babel_locale)}"
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

        local_alias = parse_to_model(
            ".local $alias = {$instant}\n"
            "{{direct={$instant :datetime dateStyle=short timeStyle=short timeZone=UTC}; "
            "alias={$alias :datetime dateStyle=short timeStyle=short timeZone=UTC}}}"
        )
        local_result = format_message(
            local_alias.model,
            {"instant": instant},
            locale="en",
            functions=functions,
        )
        expected_datetime = format_datetime(
            instant,
            format="short",
            locale="en",
            tzinfo=get_timezone("UTC"),
        )
        self.assertEqual(f"direct={expected_datetime}; alias={expected_datetime}", local_result.value)
        self.assertEqual([], local_result.errors)

        inherited_date = parse_to_model(
            ".local $date = {$instant :date dateStyle=full timeZone=UTC}\n"
            "{{{$date :date dateStyle=short timeZone=UTC}}}"
        )
        inherited_date_result = format_message(
            inherited_date.model,
            {"instant": instant},
            locale="fr",
            functions=functions,
        )
        self.assertEqual(format_date(instant.date(), format="short", locale="fr"), inherited_date_result.value)
        self.assertEqual([], inherited_date_result.errors)

        def assert_date_bad_operand(label: str, source: str, value: str) -> None:
            result = format_message(
                parse_to_model(source).model,
                {"instant": value},
                locale="en",
                functions=functions,
                bidi_isolation="none",
            )
            self.assertEqual(["bad-operand"], [error.code for error in result.errors], label)

        assert_date_bad_operand(
            "Babel adapter rejects unpadded date strings",
            "{$instant :date dateStyle=medium timeZone=UTC}",
            "2020-1-2",
        )
        assert_date_bad_operand(
            "Babel adapter rejects impossible dates",
            "{$instant :date dateStyle=medium timeZone=UTC}",
            "2020-02-30",
        )
        assert_date_bad_operand(
            "Babel adapter rejects impossible datetimes",
            "{$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2020-02-30T03:04:05Z",
        )
        assert_date_bad_operand(
            "Babel adapter rejects out-of-range datetime offsets",
            "{$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2020-01-02T03:04:05+18:01",
        )
        assert_date_bad_operand(
            "Babel adapter rejects out-of-range time offsets",
            "{$instant :time timeStyle=medium timeZone=UTC}",
            "03:04:05+18:01",
        )
        assert_date_bad_operand(
            "Babel adapter rejects oversized date operands",
            "{$instant :date dateStyle=medium timeZone=UTC}",
            "2020-01-02" + "0" * 257,
        )
        assert_date_bad_operand(
            "Babel adapter rejects oversized time operands",
            "{$instant :time timeStyle=medium timeZone=UTC}",
            "03:04:05." + "0" * 257,
        )
        assert_date_bad_operand(
            "Babel adapter rejects oversized datetime operands",
            "{$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2020-01-02T03:04:05." + "0" * 257,
        )

        def assert_numeric_bad_operand(label: str, source: str, value: str) -> None:
            result = format_message(
                parse_to_model(source).model,
                {"amount": value},
                locale="en",
                functions=functions,
                bidi_isolation="none",
            )
            self.assertEqual(["bad-operand"], [error.code for error in result.errors], label)

        assert_numeric_bad_operand(
            "Babel adapter rejects Arabic-Indic numeric operands",
            "{$amount :number}",
            "\u0661\u0662.\u0663",
        )
        assert_numeric_bad_operand(
            "Babel adapter rejects underscore numeric operands",
            "{$amount :number}",
            "1_000",
        )
        assert_numeric_bad_operand(
            "Babel adapter reports oversized number exponents as bad operand",
            "{$amount :number}",
            "1e1000",
        )
        assert_numeric_bad_operand(
            "Babel adapter reports oversized currency exponents as bad operand",
            "{$amount :currency currency=USD}",
            "1e1000",
        )
        assert_numeric_bad_operand(
            "Babel adapter reports oversized relative time exponents as bad operand",
            "{$amount :relativeTime unit=day}",
            "1e1000",
        )

        oversized_digits = parse_to_model("{$amount :number minimumFractionDigits=" + ("1" * 257) + "}")
        oversized_result = format_message(
            oversized_digits.model,
            {"amount": 1},
            locale="en",
            functions=functions,
        )
        self.assertEqual(["bad-option"], [error.code for error in oversized_result.errors])

        oversized_time_zone = format_message(
            parse_to_model(
                "{$instant :datetime dateStyle=medium timeStyle=medium timeZone="
                + ("A" * 257)
                + "}"
            ).model,
            {"instant": "2020-01-02T03:04:05Z"},
            locale="en",
            functions=functions,
        )
        self.assertEqual(
            ["bad-option"], [error.code for error in oversized_time_zone.errors]
        )

        oversized_style = format_message(
            parse_to_model(
                "{$instant :datetime style=" + ("A" * 257) + " timeZone=UTC}"
            ).model,
            {"instant": "2020-01-02T03:04:05Z"},
            locale="en",
            functions=functions,
        )
        self.assertEqual(["bad-option"], [error.code for error in oversized_style.errors])

        oversized_sign_display = format_message(
            parse_to_model("{$amount :number signDisplay=" + ("A" * 257) + "}").model,
            {"amount": 1},
            locale="en",
            functions=functions,
        )
        self.assertEqual(
            ["bad-option"], [error.code for error in oversized_sign_display.errors]
        )

        unknown_locale = format_message(
            parse_to_model("{$amount :number}").model,
            {"amount": 1},
            locale="zz-ZZ",
            functions=functions,
            bidi_isolation="none",
        )
        self.assertEqual(["bad-option"], [error.code for error in unknown_locale.errors])

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

        inherited = parse_to_model(
            ".local $price = {$amount :currency currency=$currency}\n"
            "{{{$price :currency}}}"
        )
        inherited_babel = format_message(
            inherited.model,
            {"amount": 12.3, "currency": "EUR"},
            locale="en_US",
            functions=babel_function_registry(),
        )
        self.assertEqual(format_currency(12.3, "EUR", locale="en_US"), inherited_babel.value)
        self.assertEqual([], inherited_babel.errors)

        invalid_current = parse_to_model(
            ".local $price = {$amount :currency currency=USD}\n"
            "{{{$price :currency currency=||}}}"
        )
        invalid_current_babel = format_message(
            invalid_current.model,
            {"amount": 12.3},
            locale="en_US",
            functions=babel_function_registry(),
        )
        self.assertEqual(["bad-option"], [error.code for error in invalid_current_babel.errors])


if __name__ == "__main__":
    unittest.main()
