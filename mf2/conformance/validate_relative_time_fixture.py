#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "conformance" / "fixtures" / "functions" / "relative-time-duration-v0.json"
DATA_PATH = ROOT / "cldr" / "generated" / "relative-time" / "all" / "relative_time.json"

UNIT_SECONDS = {
    "second": 1,
    "minute": 60,
    "hour": 3600,
    "day": 86400,
    "week": 604800,
    "month": 2592000,
    "quarter": 7776000,
    "year": 31536000,
}
POLICIES = {
    "precise": [
        (60, "second"),
        (3600, "minute"),
        (86400, "hour"),
        (604800, "day"),
        (2592000, "week"),
        (31536000, "month"),
        (math.inf, "year"),
    ],
    "compact": [
        (60, "second"),
        (3600, "minute"),
        (86400, "hour"),
        (math.inf, "day"),
    ],
    "chat": [
        (45, "second"),
        (2700, "minute"),
        (79200, "hour"),
        (604800, "day"),
        (math.inf, "week"),
    ],
}
OPTION_RE = re.compile(r"\b(style|numeric|policy|unit)=([A-Za-z]+)\b")


def main() -> int:
    fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    for case in fixture["cases"]:
        validate_case(data, fixture["defaultOptions"], case)
    for case in fixture["errorCases"]:
        validate_error_case(data, case)
    print(
        "Validated relative-time function draft fixtures "
        f"cases={len(fixture['cases'])} errorCases={len(fixture['errorCases'])}"
    )
    return 0


def validate_case(data: dict[str, Any], defaults: dict[str, str], case: dict[str, Any]) -> None:
    options = options_from_source(defaults, case["source"])
    seconds = numeric(case["arguments"]["delta"])
    selected_unit = select_unit(seconds, options)
    quantity = quantity_for(seconds, selected_unit)
    resolved = case["resolved"]
    assert_equal(case, "unit", resolved["unit"], selected_unit)
    assert_equal(case, "quantity", resolved["quantity"], quantity)

    if use_relative_zero(options, seconds):
        actual = relative(data, case["locale"], options["style"], selected_unit, "0")
        assert_equal(case, "relativeOffset", resolved.get("relativeOffset"), "0")
    elif options["numeric"] == "auto":
        offset = relative_offset(seconds, quantity)
        actual = relative(data, case["locale"], options["style"], selected_unit, offset)
        if actual is None:
            actual = numeric_output(data, case, options, seconds, selected_unit, quantity)
        else:
            assert_equal(case, "relativeOffset", resolved.get("relativeOffset"), offset)
    else:
        actual = numeric_output(data, case, options, seconds, selected_unit, quantity)

    assert_equal(case, "expected", case["expected"], actual)


def validate_error_case(data: dict[str, Any], case: dict[str, Any]) -> None:
    options = options_from_source(
        {"style": "short", "numeric": "always", "policy": "precise", "unit": "auto"},
        case["source"],
    )
    expected_code = case["expectedError"]["code"]
    actual_code = None
    try:
        numeric(case["arguments"]["delta"])
        if options["style"] not in {"long", "short", "narrow"}:
            actual_code = "bad-option"
        elif options["policy"] not in POLICIES:
            actual_code = "bad-option"
        elif options["unit"] != "auto" and options["unit"] not in UNIT_SECONDS:
            actual_code = "bad-option"
        elif case["locale"] not in data["localeMap"]:
            actual_code = "missing-locale-data"
    except ValueError:
        actual_code = "bad-operand"
    assert_equal(case, "expectedError.code", expected_code, actual_code)


def numeric_output(
    data: dict[str, Any],
    case: dict[str, Any],
    options: dict[str, str],
    seconds: float,
    unit: str,
    quantity: int,
) -> str:
    direction = "past" if seconds < 0 else "future"
    category = case["resolved"]["pluralCategory"]
    pattern = pattern_for(data, case["locale"], options["style"], unit, direction, category)
    assert_equal(case, "pattern", case["resolved"]["pattern"], pattern)
    assert_equal(case, "direction", case["resolved"]["direction"], direction)
    return pattern.replace("{0}", str(quantity))


def options_from_source(defaults: dict[str, str], source: str) -> dict[str, str]:
    options = dict(defaults)
    options.update(OPTION_RE.findall(source))
    return options


def select_unit(seconds: float, options: dict[str, str]) -> str:
    if options["unit"] != "auto":
        return options["unit"]
    absolute = abs(seconds)
    for upper, unit in POLICIES[options["policy"]]:
        if absolute < upper:
            return unit
    raise AssertionError("unreachable")


def quantity_for(seconds: float, unit: str) -> int:
    absolute = abs(seconds)
    if absolute == 0:
        return 0
    quantity = math.floor((absolute / UNIT_SECONDS[unit]) + 0.5)
    return max(1, quantity)


def use_relative_zero(options: dict[str, str], seconds: float) -> bool:
    return options["policy"] == "chat" and options["numeric"] == "auto" and abs(seconds) < 45


def relative_offset(seconds: float, quantity: int) -> str:
    if seconds == 0:
        return "0"
    if quantity != 1:
        return ""
    return "-1" if seconds < 0 else "1"


def pattern_for(
    data: dict[str, Any],
    locale: str,
    style: str,
    unit: str,
    direction: str,
    category: str,
) -> str:
    unit_data = pattern_set(data, locale)[style][unit][direction]
    return unit_data.get(category, unit_data["other"])


def relative(
    data: dict[str, Any], locale: str, style: str, unit: str, offset: str
) -> str | None:
    if not offset:
        return None
    return pattern_set(data, locale)[style][unit].get("relative", {}).get(offset)


def pattern_set(data: dict[str, Any], locale: str) -> dict[str, Any]:
    set_id = data["localeMap"][locale]
    for item in data["patternSets"]:
        if item["id"] == set_id:
            return item["data"]
    raise AssertionError(f"Missing pattern set {set_id}.")


def numeric(value: Any) -> float:
    if isinstance(value, bool):
        raise ValueError("booleans are not numeric operands")
    if isinstance(value, (int, float)):
        result = float(value)
    elif isinstance(value, str):
        result = float(value)
    else:
        raise ValueError(f"unsupported numeric operand {value!r}")
    if not math.isfinite(result):
        raise ValueError(f"unsupported numeric operand {value!r}")
    return result


def assert_equal(case: dict[str, Any], field: str, expected: Any, actual: Any) -> None:
    if expected != actual:
        raise AssertionError(
            f"{case['label']} {field}: expected {expected!r}, got {actual!r}."
        )


if __name__ == "__main__":
    raise SystemExit(main())
