#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


def main(argv: list[str] | None = None) -> int:
    sys.dont_write_bytecode = True

    parser = argparse.ArgumentParser(
        description="Compare generated CLDR plural rules against ICU4J PluralRules."
    )
    parser.add_argument(
        "--generated-json",
        default="generated/all/plural_rules.json",
        help="Generated plural_rules.json file.",
    )
    parser.add_argument(
        "--generated-python",
        default="generated/all/python/plural_rules.py",
        help="Generated Python plural rule implementation.",
    )
    parser.add_argument(
        "--max-mismatches",
        type=int,
        default=50,
        help="Maximum mismatches to print before the summary.",
    )
    args = parser.parse_args(argv)

    cldr_dir = Path(__file__).resolve().parents[1]
    mf2_dir = cldr_dir.parent
    generated_json = resolve_path(cldr_dir, args.generated_json)
    generated_python = resolve_path(cldr_dir, args.generated_python)
    generated_rules = load_generated_rules(generated_python)

    total = 0
    failed = 0
    for row in icu4j_plural_categories(mf2_dir, generated_json):
        for locale in locale_variants(row["locale"]):
            total += 1
            actual = generated_rules.select(row["sample"], locale, row["type"])
            expected = row["category"]
            if actual != expected:
                failed += 1
                if failed <= args.max_mismatches:
                    print(
                        "MISMATCH "
                        f'{row["type"]}[{locale}] sample={row["sample"]}: '
                        f"expected {expected}, got {actual}"
                    )

    passed = total - failed
    print(f"icu4j plural compare total={total} passed={passed} failed={failed}")
    return 1 if failed else 0


def resolve_path(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path


def load_generated_rules(path: Path) -> Any:
    spec = importlib.util.spec_from_file_location("generated_plural_rules", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to load generated Python plural rules from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def icu4j_plural_categories(mf2_dir: Path, generated_json: Path) -> list[dict[str, str]]:
    reference_dir = mf2_dir / "reference" / "icu4j"
    command = [
        "sh",
        "run.sh",
        "plural-categories",
        str(generated_json),
    ]
    completed = subprocess.run(
        command,
        cwd=reference_dir,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]


def locale_variants(locale: str) -> list[str]:
    variants = [locale, locale.upper()]
    hyphenated = locale.replace("_", "-")
    variants.append(hyphenated)
    variants.append(bcp47_cased(hyphenated))
    underscored = hyphenated.replace("-", "_")
    variants.append(underscored)
    variants.append(underscored.upper())
    return list(dict.fromkeys(variants))


def bcp47_cased(locale: str) -> str:
    parts = locale.split("-")
    cased = []
    for index, part in enumerate(parts):
        if index == 0:
            cased.append(part.lower())
        elif len(part) == 2 or part.isdigit():
            cased.append(part.upper())
        elif len(part) == 4:
            cased.append(part.title())
        else:
            cased.append(part.lower())
    return "-".join(cased)


if __name__ == "__main__":
    raise SystemExit(main())
