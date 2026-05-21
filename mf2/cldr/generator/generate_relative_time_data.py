#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import shutil
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


AVAILABLE_LOCALES_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-core/availableLocales.json"
)
DATE_FIELDS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/"
    "cldr-json/cldr-dates-full/main/{locale}/dateFields.json"
)
DEFAULT_UNITS = ("second", "minute", "hour", "day", "week", "month", "quarter", "year")
DEFAULT_STYLES = ("long", "short", "narrow")
PATTERN_DIRECTIONS = ("past", "future")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate CLDR relative-time data.")
    parser.add_argument("--locales", default="all", help="Comma-separated locales or 'all'.")
    parser.add_argument(
        "--styles",
        default=",".join(DEFAULT_STYLES),
        help="Comma-separated styles: long,short,narrow.",
    )
    parser.add_argument(
        "--units",
        default=",".join(DEFAULT_UNITS),
        help="Comma-separated units: second,minute,hour,day,week,month,quarter,year.",
    )
    parser.add_argument(
        "--numeric-only",
        action="store_true",
        help="Omit natural relative terms such as yesterday/today/tomorrow.",
    )
    parser.add_argument(
        "--out",
        default="generated/relative-time/all",
        help="Output directory.",
    )
    parser.add_argument("--cldr-ref", default="main", help="unicode-org/cldr-json git ref.")
    parser.add_argument(
        "--jobs",
        type=int,
        default=16,
        help="Number of concurrent CLDR locale fetches.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove the output directory before writing generated files.",
    )
    parser.add_argument("--quiet", action="store_true", help="Do not print a summary.")
    args = parser.parse_args(argv)

    styles = parse_choice_list(args.styles, DEFAULT_STYLES, "style")
    units = parse_choice_list(args.units, DEFAULT_UNITS, "unit")
    available_locales = fetch_available_locales(args.cldr_ref)
    selected_locales = select_locales(args.locales, available_locales)
    generated = build_generated_data(
        selected_locales,
        styles,
        units,
        args.numeric_only,
        args.cldr_ref,
        max(1, args.jobs),
    )

    out = Path(args.out)
    if args.clean:
        shutil.rmtree(out, ignore_errors=True)
    path = out / "relative_time.json"
    write_json(path, generated)

    if not args.quiet:
        print(
            "Generated CLDR relative-time data "
            f"locales={len(generated['locales'])} "
            f"patternSets={len(generated['patternSets'])} "
            f"styles={','.join(styles)} "
            f"units={','.join(units)} "
            f"bytes={{'json': {path.stat().st_size}}}"
        )
    return 0


def parse_choice_list(value: str, allowed: tuple[str, ...], label: str) -> list[str]:
    selected = [item.strip().lower() for item in value.split(",") if item.strip()]
    unknown = sorted(set(selected) - set(allowed))
    if unknown:
        raise SystemExit(f"Unknown {label}s: {', '.join(unknown)}")
    if not selected:
        raise SystemExit(f"At least one {label} is required.")
    return list(dict.fromkeys(selected))


def fetch_available_locales(cldr_ref: str) -> list[str]:
    data = fetch_json(AVAILABLE_LOCALES_URL.format(ref=cldr_ref))
    locales = data["availableLocales"]["full"]
    canonical: set[str] = set()
    for locale in locales:
        key = canonical_locale_key(locale)
        if key:
            canonical.add(key)
    return sorted(canonical)


def select_locales(locale_arg: str, available_locales: list[str]) -> list[str]:
    if locale_arg.strip().lower() == "all":
        return available_locales

    available = set(available_locales)
    selected: list[str] = []
    for raw_locale in locale_arg.split(","):
        locale = canonical_locale_key(raw_locale)
        if not locale:
            continue
        if not any(candidate in available for candidate in locale_source_candidates(locale)):
            raise SystemExit(f"Locale '{locale}' is not present in CLDR locale data.")
        if locale not in selected:
            selected.append(locale)
    if not selected:
        raise SystemExit("At least one locale is required.")
    return selected


def build_generated_data(
    locales: list[str],
    styles: list[str],
    units: list[str],
    numeric_only: bool,
    cldr_ref: str,
    jobs: int,
) -> dict[str, Any]:
    locale_records = fetch_locale_records(locales, styles, units, numeric_only, cldr_ref, jobs)
    set_ids: dict[str, str] = {}
    pattern_sets: list[dict[str, Any]] = []
    locale_map: dict[str, str] = {}
    source_locales: dict[str, str] = {}

    for locale in locales:
        record = locale_records[locale]
        key = stable_json(record["data"])
        set_id = set_ids.get(key)
        if set_id is None:
            set_id = f"rt{len(pattern_sets)}"
            set_ids[key] = set_id
            pattern_sets.append({"id": set_id, "data": record["data"]})
        locale_map[locale] = set_id
        source_locales[locale] = record["sourceLocale"]

    return {
        "metadata": {
            "cldrRef": cldr_ref,
            "generator": "mf2/cldr/generator/generate_relative_time_data.py",
            "numericOnly": numeric_only,
            "source": "unicode-org/cldr-json cldr-dates-full main dateFields",
        },
        "locales": locales,
        "styles": styles,
        "units": units,
        "localeMap": locale_map,
        "sourceLocales": source_locales,
        "patternSets": pattern_sets,
    }


def fetch_locale_records(
    locales: list[str],
    styles: list[str],
    units: list[str],
    numeric_only: bool,
    cldr_ref: str,
    jobs: int,
) -> dict[str, dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = {
            executor.submit(build_locale_record, locale, styles, units, numeric_only, cldr_ref): locale
            for locale in locales
        }
        for future in concurrent.futures.as_completed(futures):
            locale = futures[future]
            records[locale] = future.result()
    return records


def build_locale_record(
    locale: str,
    styles: list[str],
    units: list[str],
    numeric_only: bool,
    cldr_ref: str,
) -> dict[str, Any]:
    source_locale, fields = fetch_date_fields(cldr_ref, locale)
    data: dict[str, Any] = {}
    for style in styles:
        style_data: dict[str, Any] = {}
        for unit in units:
            field_name = unit if style == "long" else f"{unit}-{style}"
            field = fields.get(field_name)
            if field is None:
                raise SystemExit(f"CLDR dateFields data is missing {field_name} for {source_locale}.")
            style_data[unit] = build_unit_data(field, numeric_only)
        data[style] = style_data
    return {"sourceLocale": source_locale, "data": data}


def build_unit_data(field: dict[str, Any], numeric_only: bool) -> dict[str, Any]:
    unit_data: dict[str, Any] = {}
    for direction in PATTERN_DIRECTIONS:
        raw_patterns = field.get(f"relativeTime-type-{direction}", {})
        patterns: dict[str, str] = {}
        for key, value in sorted(raw_patterns.items()):
            prefix = "relativeTimePattern-count-"
            if key.startswith(prefix):
                patterns[key.removeprefix(prefix)] = value
        if patterns:
            unit_data[direction] = patterns
    if not numeric_only:
        relatives = {
            key.removeprefix("relative-type-"): value
            for key, value in sorted(field.items())
            if key.startswith("relative-type-")
        }
        if relatives:
            unit_data["relative"] = relatives
    return unit_data


def fetch_date_fields(cldr_ref: str, locale: str) -> tuple[str, dict[str, Any]]:
    for candidate in locale_source_candidates(locale):
        try:
            data = fetch_json(DATE_FIELDS_URL.format(ref=cldr_ref, locale=candidate))
            return candidate, data["main"][candidate]["dates"]["fields"]
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    candidates = ", ".join(locale_source_candidates(locale))
    raise SystemExit(f"Could not find CLDR dateFields data for {locale}; tried {candidates}.")


def fetch_json(url: str) -> dict[str, Any]:
    attempts = 4
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError:
            raise
        except urllib.error.URLError:
            if attempt == attempts - 1:
                raise
            time.sleep(0.25 * (2**attempt))
    raise AssertionError("unreachable")


def locale_source_candidates(locale: str) -> list[str]:
    parts = canonical_locale_key(locale).split("-")
    candidates = ["-".join(parts[:length]) for length in range(len(parts), 0, -1)]
    return list(dict.fromkeys(candidates))


def canonical_locale_key(locale: str) -> str:
    parts: list[str] = []
    for index, part in enumerate(locale.strip().replace("_", "-").split("-")):
        if not part:
            continue
        if len(part) == 1:
            break
        parts.append(canonical_subtag(index, part))
    return "-".join(parts)


def canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
