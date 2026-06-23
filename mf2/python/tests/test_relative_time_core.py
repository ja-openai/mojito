from __future__ import annotations

from datetime import timedelta
import importlib.util
import json
from pathlib import Path
import unittest

from mojito_mf2 import format_message, parse_to_model
from mojito_mf2.errors import MF2Error
from mojito_mf2.relative_time_core import (
    format_relative_time_core,
    format_relative_time_core_to_parts,
    relative_time_core_function_registry,
)


_ROOT = Path(__file__).resolve().parents[2]
BABEL_AVAILABLE = importlib.util.find_spec("babel") is not None


class RelativeTimeCoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = _read_json("cldr/generated/relative-time/all/relative_time.json")
        cls.fixture = _read_json("conformance/fixtures/functions/relative-time-duration-v0.json")
        cls.registry = relative_time_core_function_registry(cls.data)

    def test_fixture_format_cases(self) -> None:
        for case in self.fixture["cases"]:
            with self.subTest(case=case["label"]):
                parsed = parse_to_model(case["source"])
                self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                formatted = format_message(
                    parsed.model,
                    case["arguments"],
                    locale=case["locale"],
                    functions=self.registry,
                )
                self.assertEqual(case["expected"], formatted.value)
                self.assertEqual([], formatted.errors)

    def test_fixture_error_cases(self) -> None:
        for case in self.fixture["errorCases"]:
            with self.subTest(case=case["label"]):
                parsed = parse_to_model(case["source"])
                self.assertFalse(parsed.has_diagnostics, parsed.diagnostics)
                formatted = format_message(
                    parsed.model,
                    case["arguments"],
                    locale=case["locale"],
                    functions=self.registry,
                )
                self.assertEqual(
                    [case["expectedError"]["code"]],
                    [error.code for error in formatted.errors],
                )

    def test_direct_api(self) -> None:
        self.assertEqual(
            "in 1h",
            format_relative_time_core(
                3_600,
                locale="en",
                style="narrow",
                numeric="always",
                policy="precise",
                unit="auto",
                data=self.data,
            ),
        )
        self.assertEqual(
            "0 seconds ago",
            format_relative_time_core(
                -0.0,
                locale="en",
                style="long",
                numeric="always",
                policy="precise",
                unit="second",
                data=self.data,
            ),
        )
        self.assertEqual(
            "après-demain",
            format_relative_time_core(
                172_800,
                locale="fr",
                style="long",
                numeric="auto",
                policy="precise",
                unit="day",
                data=self.data,
            ),
        )
        self.assertEqual(
            [{"type": "text", "value": "yesterday"}],
            format_relative_time_core_to_parts(
                -86_400,
                locale="en",
                style="long",
                numeric="auto",
                unit="day",
                data=self.data,
            ),
        )
        self.assertTrue(
            relative_time_core_function_registry(self.data).has_formatter({"name": "relativeTime"})
        )
        with self.assertRaises(MF2Error) as empty_locale_map:
            format_relative_time_core(
                1,
                data={
                    "localeMap": {},
                    "patternSets": [
                        {
                            "id": "rt",
                            "data": {
                                "short": {
                                    "second": {"future": {"other": "in {0} sec."}}
                                }
                            },
                        }
                    ],
                },
            )
        self.assertEqual("missing-locale-data", empty_locale_map.exception.code)
        with self.assertRaises(MF2Error) as empty_pattern_sets:
            format_relative_time_core(
                1,
                data={"localeMap": {"en": "rt"}, "patternSets": []},
            )
        self.assertEqual("missing-locale-data", empty_pattern_sets.exception.code)
        with self.assertRaises(MF2Error) as empty_registry:
            relative_time_core_function_registry({"localeMap": {"en": "rt"}, "patternSets": []})
        self.assertEqual("missing-locale-data", empty_registry.exception.code)
        with self.assertRaises(MF2Error) as error:
            format_relative_time_core(
                "1e30",
                locale="en",
                style="narrow",
                numeric="always",
                policy="precise",
                unit="auto",
                data=self.data,
            )
        self.assertEqual("bad-operand", error.exception.code)

    def test_direct_api_rejects_arbitrary_object_coercion(self) -> None:
        class BadObject:
            def __str__(self) -> str:
                raise RuntimeError("should not be called")

        with self.assertRaises(MF2Error) as operand_error:
            format_relative_time_core(BadObject(), locale="en", data=self.data)
        self.assertEqual("bad-operand", operand_error.exception.code)

        with self.assertRaises(MF2Error) as option_error:
            format_relative_time_core(1, locale=BadObject(), data=self.data)
        self.assertEqual("bad-option", option_error.exception.code)

        with self.assertRaises(MF2Error) as style_error:
            format_relative_time_core(1, locale="en", style=[], data=self.data)
        self.assertEqual("bad-option", style_error.exception.code)

    @unittest.skipIf(not BABEL_AVAILABLE, "Babel is not installed")
    def test_babel_reference_witnesses(self) -> None:
        from babel.dates import format_timedelta

        cases = [
            {
                "locale": "en",
                "seconds": -30,
                "unit": "second",
                "style": "narrow",
            },
            {
                "locale": "en",
                "seconds": 3_600,
                "unit": "hour",
                "style": "narrow",
            },
            {
                "locale": "en",
                "seconds": -86_400,
                "unit": "day",
                "style": "long",
            },
            {
                "locale": "fr",
                "seconds": 3 * 86_400,
                "unit": "day",
                "style": "short",
            },
        ]
        for case in cases:
            with self.subTest(case=case):
                unit = str(case["unit"])
                seconds = int(case["seconds"])
                style = str(case["style"])
                locale = str(case["locale"])
                actual = format_relative_time_core(
                    seconds,
                    locale=locale,
                    style=style,
                    numeric="always",
                    unit=unit,
                    data=self.data,
                )
                expected = format_timedelta(
                    timedelta(seconds=seconds),
                    granularity=unit,
                    add_direction=True,
                    format=style,
                    locale=locale,
                )
                self.assertEqual(expected, actual)


def _read_json(path: str) -> dict[str, object]:
    return json.loads((_ROOT / path).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
