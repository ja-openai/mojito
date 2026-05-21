#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


PLURAL_CATEGORIES = {"zero", "one", "two", "few", "many", "other"}
DIRECTIONS = {"past", "future"}


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate generated relative-time data.")
    parser.add_argument(
        "path",
        nargs="?",
        default="generated/relative-time/all/relative_time.json",
        help="Generated relative_time.json path.",
    )
    args = parser.parse_args()

    data = json.loads(Path(args.path).read_text(encoding="utf-8"))
    validate_shape(data)
    validate_known_samples(data)
    print(
        "Validated CLDR relative-time data "
        f"locales={len(data['locales'])} "
        f"patternSets={len(data['patternSets'])} "
        f"styles={len(data['styles'])} "
        f"units={len(data['units'])}"
    )
    return 0


def validate_shape(data: dict[str, Any]) -> None:
    metadata = data.get("metadata", {})
    if metadata.get("generator") != "mf2/cldr/generator/generate_relative_time_data.py":
        raise AssertionError("Unexpected generator metadata.")

    locales = data["locales"]
    styles = data["styles"]
    units = data["units"]
    locale_map = data["localeMap"]
    source_locales = data["sourceLocales"]
    pattern_sets = {item["id"]: item["data"] for item in data["patternSets"]}

    if not locales or not styles or not units or not pattern_sets:
        raise AssertionError("Relative-time data must include locales, styles, units, and pattern sets.")
    if set(locales) != set(locale_map):
        raise AssertionError("localeMap must cover exactly the generated locale list.")
    if set(locales) != set(source_locales):
        raise AssertionError("sourceLocales must cover exactly the generated locale list.")

    for locale, set_id in locale_map.items():
        if set_id not in pattern_sets:
            raise AssertionError(f"{locale} points at missing pattern set {set_id}.")

    for set_id, pattern_set in pattern_sets.items():
        for style in styles:
            if style not in pattern_set:
                raise AssertionError(f"{set_id} missing style {style}.")
            for unit in units:
                unit_data = pattern_set[style].get(unit)
                if not unit_data:
                    raise AssertionError(f"{set_id}/{style} missing unit {unit}.")
                for direction in DIRECTIONS:
                    patterns = unit_data.get(direction)
                    if not patterns:
                        raise AssertionError(f"{set_id}/{style}/{unit} missing {direction} patterns.")
                    unknown_categories = set(patterns) - PLURAL_CATEGORIES
                    if unknown_categories:
                        raise AssertionError(
                            f"{set_id}/{style}/{unit}/{direction} has unknown categories {unknown_categories}."
                        )
                    if "other" not in patterns:
                        raise AssertionError(f"{set_id}/{style}/{unit}/{direction} missing other pattern.")


def validate_known_samples(data: dict[str, Any]) -> None:
    assert lookup(data, "en", "narrow", "minute", "past", "other") == "{0}m ago"
    assert lookup(data, "en", "narrow", "minute", "future", "other") == "in {0}m"
    assert relative(data, "en", "long", "day", "-1") == "yesterday"
    assert relative(data, "en", "long", "day", "0") == "today"
    assert relative(data, "en", "long", "day", "1") == "tomorrow"
    assert lookup(data, "ja", "narrow", "minute", "future", "other") == "{0}分後"
    assert lookup(data, "ar", "narrow", "minute", "past", "one") == "قبل دقيقة واحدة"


def lookup(
    data: dict[str, Any],
    locale: str,
    style: str,
    unit: str,
    direction: str,
    category: str,
) -> str:
    pattern_set = pattern_set_for(data, locale)
    patterns = pattern_set[style][unit][direction]
    return patterns.get(category, patterns["other"])


def relative(data: dict[str, Any], locale: str, style: str, unit: str, offset: str) -> str:
    pattern_set = pattern_set_for(data, locale)
    return pattern_set[style][unit]["relative"][offset]


def pattern_set_for(data: dict[str, Any], locale: str) -> dict[str, Any]:
    set_id = data["localeMap"][locale]
    for item in data["patternSets"]:
        if item["id"] == set_id:
            return item["data"]
    raise AssertionError(f"Missing pattern set {set_id}.")


if __name__ == "__main__":
    raise SystemExit(main())
