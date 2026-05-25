#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SOURCE_FIXTURES = ROOT / "conformance" / "fixtures" / "source-to-model"
REFERENCE_DIR = ROOT / "reference" / "icu4j"
GENERATED_JSON = ROOT / "cldr" / "generated" / "all" / "plural_rules.json"
CATEGORIES = ("zero", "one", "two", "few", "many", "other")


def main() -> int:
    rows = reference_rows()
    selected = select_cases(rows)
    written = []
    for (plural_type, fraction_digits), cases in sorted(selected.items()):
        fixture = build_fixture(plural_type, fraction_digits, cases)
        path = SOURCE_FIXTURES / fixture_name(plural_type, fraction_digits)
        path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written.append(path)
    for path in written:
        print(path.relative_to(ROOT))
    return 0


def reference_rows() -> list[dict[str, str]]:
    completed = subprocess.run(
        ["sh", "run.sh", "plural-categories", str(GENERATED_JSON)],
        cwd=REFERENCE_DIR,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]


def select_cases(rows: list[dict[str, str]]) -> dict[tuple[str, int], list[dict[str, str]]]:
    by_key: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    for row in rows:
        by_key[(row["type"], row["locale"], row["category"])].append(row["sample"])

    grouped: dict[tuple[str, int], list[dict[str, str]]] = defaultdict(list)
    for key, samples in sorted(by_key.items()):
        plural_type, locale, category = key
        sample = choose_sample(samples)
        grouped[(plural_type, fraction_digits(sample))].append(
            {
                "locale": locale,
                "sample": sample,
                "category": category,
            }
        )
    return grouped


def choose_sample(samples: list[str]) -> str:
    for sample in samples:
        if is_non_negative_integer(sample):
            return sample
    for sample in samples:
        if not sample.startswith("-"):
            return sample
    return samples[0]


def is_non_negative_integer(sample: str) -> bool:
    return re.fullmatch(r"\d+", sample) is not None


def fraction_digits(sample: str) -> int:
    base = sample.lower().split("e", 1)[0]
    return len(base.split(".", 1)[1]) if "." in base else 0


def fixture_name(plural_type: str, digits: int) -> str:
    suffix = "integers" if digits == 0 else f"fraction-{digits}"
    return f"plural-{plural_type}-cldr-{suffix}.json"


def build_fixture(plural_type: str, digits: int, cases: list[dict[str, str]]) -> dict[str, Any]:
    source = source_text(plural_type, digits)
    return {
        "name": fixture_name(plural_type, digits).removesuffix(".json"),
        "source": source,
        "expectedModel": expected_model(plural_type, digits),
        "formatCases": [
            {
                "locale": case["locale"],
                "arguments": {"count": case["sample"]},
                "expected": case["category"],
            }
            for case in cases
        ],
    }


def source_text(plural_type: str, digits: int) -> str:
    options = []
    if digits:
        options.append(f"minimumFractionDigits={digits}")
    if plural_type == "ordinal":
        options.append("select=ordinal")
    option_text = (" " + " ".join(options)) if options else ""
    variants = "\n".join(f"{category} {{{{{category}}}}}" for category in CATEGORIES)
    return f".input {{$count :number{option_text}}}\n.match $count\n{variants}\n* {{{{other}}}}"


def expected_model(plural_type: str, digits: int) -> dict[str, Any]:
    options = {}
    if digits:
        options["minimumFractionDigits"] = {"type": "literal", "value": str(digits)}
    if plural_type == "ordinal":
        options["select"] = {"type": "literal", "value": "ordinal"}

    function = {"type": "function", "name": "number"}
    if options:
        function["options"] = dict(sorted(options.items()))

    return {
        "type": "select",
        "declarations": [
            {
                "type": "input",
                "name": "count",
                "value": {
                    "type": "expression",
                    "arg": {"type": "variable", "name": "count"},
                    "function": function,
                },
            }
        ],
        "selectors": [{"type": "variable", "name": "count"}],
        "variants": [
            {
                "keys": [{"type": "literal", "value": category}],
                "value": [category],
            }
            for category in CATEGORIES
        ]
        + [
            {
                "keys": [{"type": "*"}],
                "value": ["other"],
            }
        ],
    }


if __name__ == "__main__":
    raise SystemExit(main())
