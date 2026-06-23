#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any


BIDI_CONTROLS = "\u061c\u200e\u200f"


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        raise SystemExit("Usage: compare_intl_number_data.py path/to/number_data.json")

    path = Path(args[0])
    data = json.loads(path.read_text(encoding="utf-8"))
    intl_rows = read_intl_parts(tuple(data["locales"].keys()))

    total = 0
    failed = 0
    for locale, locale_data in data["locales"].items():
        row = intl_rows.get(locale)
        if row is None:
            print(f"SKIP {locale}: Intl did not return comparison data")
            continue
        checks = {
            "decimal": (
                locale_data["symbols"]["decimal"],
                row["decimal"],
                exact_symbol,
            ),
            "group": (
                locale_data["symbols"]["group"],
                row["group"],
                exact_symbol,
            ),
            "percentSign": (
                locale_data["symbols"]["percentSign"],
                row["percentSign"],
                bidi_relaxed_symbol,
            ),
        }
        for label, (actual, expected, comparator) in checks.items():
            total += 1
            if not comparator(actual, expected):
                failed += 1
                print(
                    f"MISMATCH {locale} {label}: "
                    f"generated={actual!r} intl={expected!r}"
                )

    passed = total - failed
    print(f"intl number compare total={total} passed={passed} failed={failed}")
    return 1 if failed else 0


def read_intl_parts(locales: tuple[str, ...]) -> dict[str, dict[str, str]]:
    script = r"""
const locales = JSON.parse(process.argv[1]);
const rows = {};
for (const locale of locales) {
  const decimalParts = new Intl.NumberFormat(locale, {
    useGrouping: true,
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).formatToParts(1234.5);
  const percentParts = new Intl.NumberFormat(locale, {
    style: "percent",
  }).formatToParts(0.12);
  const value = (parts, type) => {
    const part = parts.find((item) => item.type === type);
    return part == null ? null : part.value;
  };
  rows[locale] = {
    decimal: value(decimalParts, "decimal"),
    group: value(decimalParts, "group"),
    percentSign: value(percentParts, "percentSign"),
  };
}
process.stdout.write(JSON.stringify(rows));
"""
    completed = subprocess.run(
        ["node", "-e", script, json.dumps(locales)],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return json.loads(completed.stdout)


def exact_symbol(actual: str, expected: str) -> bool:
    return actual == expected


def bidi_relaxed_symbol(actual: str, expected: str) -> bool:
    return strip_bidi_controls(actual) == strip_bidi_controls(expected)


def strip_bidi_controls(value: str) -> str:
    return "".join(ch for ch in value if ch not in BIDI_CONTROLS)


if __name__ == "__main__":
    raise SystemExit(main())
