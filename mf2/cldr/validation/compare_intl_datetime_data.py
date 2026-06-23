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
        raise SystemExit("Usage: compare_intl_datetime_data.py path/to/date_time_data.json")

    path = Path(args[0])
    data = json.loads(path.read_text(encoding="utf-8"))
    intl_rows = read_intl_parts(tuple(data["locales"].keys()))
    mf2_dir = Path(__file__).resolve().parents[2]

    total = 0
    failed = 0
    for locale, locale_data in data["locales"].items():
        row = intl_rows.get(locale)
        if row is None:
            print(f"SKIP {locale}: Intl did not return comparison data")
            continue
        checks = {
            "monthWide.1": (
                locale_data["months"]["format"]["wide"]["1"],
                row["monthLong"],
            ),
            "weekdayWide.sun": (
                locale_data["weekdays"]["format"]["wide"]["sun"],
                row["weekdayLong"],
            ),
            "dayPeriodWide.am": (
                locale_data["dayPeriods"]["format"]["wide"]["am"],
                row["dayPeriodAm"],
            ),
            "dayPeriodWide.pm": (
                locale_data["dayPeriods"]["format"]["wide"]["pm"],
                row["dayPeriodPm"],
            ),
        }
        for label, (actual, expected) in checks.items():
            total += 1
            if strip_bidi_controls(actual) != strip_bidi_controls(expected):
                failed += 1
                print(
                    f"MISMATCH {locale} {label}: "
                    f"generated={actual!r} intl={expected!r}"
                )

    for row in read_intl_style_reference_rows(mf2_dir):
        total += 1
        actual = row["actual"]
        expected = row["expected"]
        if actual != expected:
            failed += 1
            print(
                "MISMATCH "
                f"{row['kind']} {row['locale']} {row['options']}: "
                f"generated={actual!r} intl={expected!r}"
            )

    passed = total - failed
    print(f"intl date/time compare total={total} passed={passed} failed={failed}")
    return 1 if failed else 0


def read_intl_parts(locales: tuple[str, ...]) -> dict[str, dict[str, str]]:
    script = r"""
const locales = JSON.parse(process.argv[1]);
const rows = {};
for (const locale of locales) {
  const monthParts = new Intl.DateTimeFormat(locale, {
    timeZone: "UTC",
    month: "long",
    day: "numeric",
  }).formatToParts(new Date(Date.UTC(2020, 0, 1, 0, 0, 0)));
  const weekdayParts = new Intl.DateTimeFormat(locale, {
    timeZone: "UTC",
    weekday: "long",
  }).formatToParts(new Date(Date.UTC(2020, 0, 5, 0, 0, 0)));
  const amParts = new Intl.DateTimeFormat(locale, {
    timeZone: "UTC",
    hour: "numeric",
    hour12: true,
  }).formatToParts(new Date(Date.UTC(2020, 0, 1, 1, 0, 0)));
  const pmParts = new Intl.DateTimeFormat(locale, {
    timeZone: "UTC",
    hour: "numeric",
    hour12: true,
  }).formatToParts(new Date(Date.UTC(2020, 0, 1, 13, 0, 0)));
  const value = (parts, type) => {
    const part = parts.find((item) => item.type === type);
    return part == null ? null : part.value;
  };
  const monthValue = (parts) => {
    const index = parts.findIndex((item) => item.type === "month");
    if (index < 0) return null;
    let value = parts[index].value;
    const next = parts[index + 1];
    if (next != null && next.type === "literal" && !/\s/.test(next.value)) {
      value += next.value;
    }
    return value;
  };
  rows[locale] = {
    monthLong: monthValue(monthParts),
    weekdayLong: value(weekdayParts, "weekday"),
    dayPeriodAm: value(amParts, "dayPeriod"),
    dayPeriodPm: value(pmParts, "dayPeriod"),
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


def read_intl_style_reference_rows(mf2_dir: Path) -> list[dict[str, Any]]:
    script = r"""
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const runtimePath = process.argv[1];
const fixturePath = process.argv[2];
const { formatDateCore, formatDateTimeCore, formatTimeCore } = await import(pathToFileURL(runtimePath).href);
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const rows = [];
for (const item of fixture.intlReferenceCases) {
  const options = { locale: item.locale, ...item.options };
  let actual;
  if (item.kind === "date") {
    actual = formatDateCore(item.value, options);
  } else if (item.kind === "time") {
    actual = formatTimeCore(item.value, options);
  } else if (item.kind === "datetime") {
    actual = formatDateTimeCore(item.value, options);
  } else {
    throw new Error(`Unsupported date/time core fixture kind: ${item.kind}`);
  }
  const expected = new Intl.DateTimeFormat(item.locale, {
    timeZone: "UTC",
    ...item.options,
  }).format(new Date(item.value));
  rows.push({
    kind: item.kind,
    locale: item.locale,
    options: item.options,
    actual,
    expected,
  });
}
process.stdout.write(JSON.stringify(rows));
"""
    completed = subprocess.run(
        [
            "node",
            "--input-type=module",
            "-e",
            script,
            str(mf2_dir / "javascript" / "src" / "date_time_core.js"),
            str(mf2_dir / "conformance" / "fixtures" / "date-time-core" / "cases.json"),
        ],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return json.loads(completed.stdout)


def strip_bidi_controls(value: str) -> str:
    return "".join(ch for ch in value if ch not in BIDI_CONTROLS)


if __name__ == "__main__":
    raise SystemExit(main())
