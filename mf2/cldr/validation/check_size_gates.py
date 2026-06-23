#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_LOCALES = ("en", "fr", "ru", "ar", "ja", "pt-PT")


@dataclass(frozen=True)
class SizeGate:
    label: str
    path: str
    max_raw: int
    max_gzip: int


SIZE_GATES = (
    SizeGate("json", "generated/all/plural_rules.json", 150_000, 7_000),
    SizeGate("python", "generated/all/python/cldr_plural_rules.py", 140_000, 9_000),
    SizeGate("rust", "generated/all/rust/cldr_plural_rules.rs", 75_000, 7_000),
    SizeGate("swift", "generated/all/swift/CldrPluralRules.swift", 100_000, 7_000),
    SizeGate(
        "java",
        "generated/all/java/com/box/l10n/mojito/mf2/CldrPluralRules.java",
        90_000,
        8_000,
    ),
    SizeGate(
        "kotlin",
        "generated/all/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt",
        80_000,
        7_000,
    ),
    SizeGate("javascript", "generated/all/javascript/cldr_plural_rules.js", 65_000, 7_000),
    SizeGate("go", "generated/all/go/cldr_plural_rules.go", 85_000, 7_000),
    SizeGate("php", "generated/all/php/CldrPluralRules.php", 85_000, 7_000),
    SizeGate("experimental-number-json", "generated/experimental-number/number_data.json", 12_000, 2_500),
    SizeGate(
        "experimental-number-javascript",
        "generated/experimental-number/javascript/number_data.js",
        8_000,
        2_500,
    ),
    SizeGate(
        "experimental-number-javascript-packed",
        "generated/experimental-number/javascript/number_data_packed.js",
        2_500,
        1_200,
    ),
    SizeGate(
        "experimental-number-python",
        "generated/experimental-number/python/mojito_mf2/_cldr_number_data.py",
        9_000,
        2_500,
    ),
    SizeGate(
        "experimental-number-rust",
        "generated/experimental-number/rust/cldr_number_data.rs",
        8_700,
        2_500,
    ),
    SizeGate(
        "experimental-number-java",
        "generated/experimental-number/java/com/box/l10n/mojito/mf2/CldrNumberData.java",
        8_000,
        2_500,
    ),
    SizeGate(
        "experimental-number-kotlin",
        "generated/experimental-number/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt",
        9_000,
        2_500,
    ),
    SizeGate(
        "experimental-number-swift",
        "generated/experimental-number/swift/CldrNumberData.swift",
        10_000,
        3_000,
    ),
    SizeGate(
        "experimental-number-go",
        "generated/experimental-number/go/cldr_number_data.go",
        8_000,
        2_500,
    ),
    SizeGate(
        "experimental-number-php",
        "generated/experimental-number/php/CldrNumberData.php",
        8_000,
        2_500,
    ),
    SizeGate("experimental-datetime-json", "generated/experimental-datetime/date_time_data.json", 60_000, 6_000),
    SizeGate(
        "experimental-datetime-skeleton-coverage",
        "generated/experimental-datetime/skeleton_coverage.json",
        5_000,
        1_000,
    ),
    SizeGate(
        "experimental-datetime-javascript",
        "generated/experimental-datetime/javascript/date_time_data.js",
        34_000,
        5_000,
    ),
    SizeGate(
        "experimental-datetime-javascript-packed",
        "generated/experimental-datetime/javascript/date_time_data_packed.js",
        25_900,
        6_200,
    ),
    SizeGate(
        "experimental-datetime-python",
        "generated/experimental-datetime/python/mojito_mf2/_cldr_date_time_data.py",
        37_500,
        5_050,
    ),
    SizeGate(
        "experimental-datetime-rust",
        "generated/experimental-datetime/rust/cldr_date_time_data.rs",
        45_000,
        5_300,
    ),
    SizeGate(
        "experimental-datetime-java",
        "generated/experimental-datetime/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java",
        50_700,
        5_250,
    ),
    SizeGate(
        "experimental-datetime-kotlin",
        "generated/experimental-datetime/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt",
        44_000,
        5_150,
    ),
    SizeGate(
        "experimental-datetime-swift",
        "generated/experimental-datetime/swift/CldrDateTimeData.swift",
        41_300,
        5_100,
    ),
    SizeGate(
        "experimental-datetime-go",
        "generated/experimental-datetime/go/cldr_date_time_data.go",
        44_100,
        5_100,
    ),
    SizeGate(
        "experimental-datetime-php",
        "generated/experimental-datetime/php/CldrDateTimeData.php",
        42_800,
        5_250,
    ),
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check generated CLDR data size gates.")
    parser.add_argument(
        "--root",
        default=Path(__file__).resolve().parents[1],
        type=Path,
        help="mf2/cldr root directory.",
    )
    parser.add_argument(
        "--subset-locales",
        default=",".join(DEFAULT_LOCALES),
        help="Comma-separated locale allowlist for the compact subset smoke check.",
    )
    parser.add_argument("--quiet", action="store_true", help="Only print failures.")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    failures: list[str] = []
    for gate in SIZE_GATES:
        path = root / gate.path
        raw_size, gzip_size = file_sizes(path)
        if not args.quiet:
            print(
                f"cldr-size {gate.label}: raw={raw_size} "
                f"gzip={gzip_size} maxRaw={gate.max_raw} maxGzip={gate.max_gzip}"
            )
        if raw_size > gate.max_raw:
            failures.append(f"{gate.label} raw size {raw_size} exceeds {gate.max_raw}: {path}")
        if gzip_size > gate.max_gzip:
            failures.append(f"{gate.label} gzip size {gzip_size} exceeds {gate.max_gzip}: {path}")

    check_directory_size_gate(
        failures,
        root / "generated/experimental-number/javascript/packed-locales",
        "experimental-number-javascript-packed-locale",
        max_raw=1_200,
        max_gzip=700,
        quiet=args.quiet,
    )
    check_directory_size_gate(
        failures,
        root / "generated/experimental-datetime/javascript/packed-locales",
        "experimental-datetime-javascript-packed-locale",
        max_raw=8_500,
        max_gzip=2_300,
        quiet=args.quiet,
    )

    subset_locales = tuple(
        locale.strip()
        for locale in args.subset_locales.split(",")
        if locale.strip()
    )
    subset = build_compact_subset(root / "generated/all/plural_rules.json", subset_locales)
    subset_bytes = json.dumps(
        subset,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    subset_raw = len(subset_bytes)
    subset_gzip = len(gzip.compress(subset_bytes, compresslevel=9))
    if not args.quiet:
        print(
            "plural-subset-smoke "
            f"locales={','.join(subset_locales)} raw={subset_raw} gzip={subset_gzip} "
            "maxRaw=20000 maxGzip=4000"
        )
    if subset_raw > 20_000:
        failures.append(f"subset raw size {subset_raw} exceeds 20000")
    if subset_gzip > 4_000:
        failures.append(f"subset gzip size {subset_gzip} exceeds 4000")

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    return 1 if failures else 0


def file_sizes(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    return len(data), len(gzip.compress(data, compresslevel=9))


def check_directory_size_gate(
    failures: list[str],
    directory: Path,
    label: str,
    max_raw: int,
    max_gzip: int,
    quiet: bool,
) -> None:
    paths = sorted(directory.glob("*.js"))
    if not paths:
        failures.append(f"{label} has no generated chunks: {directory}")
        return
    for path in paths:
        raw_size, gzip_size = file_sizes(path)
        chunk_label = f"{label}:{path.stem}"
        if not quiet:
            print(
                f"cldr-size {chunk_label}: raw={raw_size} "
                f"gzip={gzip_size} maxRaw={max_raw} maxGzip={max_gzip}"
            )
        if raw_size > max_raw:
            failures.append(f"{chunk_label} raw size {raw_size} exceeds {max_raw}: {path}")
        if gzip_size > max_gzip:
            failures.append(f"{chunk_label} gzip size {gzip_size} exceeds {max_gzip}: {path}")


def build_compact_subset(path: Path, locales: tuple[str, ...]) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    available = set(data.get("locales", []))
    missing = [locale for locale in locales if locale not in available]
    if missing:
        raise SystemExit(f"Subset locale(s) not present in generated data: {', '.join(missing)}")

    subset: dict[str, Any] = {
        "metadata": data["metadata"],
        "locales": list(locales),
    }
    for plural_type in ("cardinal", "ordinal"):
        source = data[plural_type]
        locale_map = {
            locale: rule_id
            for locale, rule_id in source["locales"].items()
            if locale in locales
        }
        rule_ids = set(locale_map.values())
        parents = {
            child: parent
            for child, parent in source.get("parents", {}).items()
            if child in locales
        }
        rules = [
            rule
            for rule in source["rules"]
            if rule["id"] in rule_ids
        ]
        subset[plural_type] = {
            "locales": locale_map,
            "parents": parents,
            "rules": rules,
        }
    return subset


if __name__ == "__main__":
    raise SystemExit(main())
