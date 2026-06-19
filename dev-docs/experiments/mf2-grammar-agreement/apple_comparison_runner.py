#!/usr/bin/env python3
"""Compare local Apple Foundation inflection against explicit term MF2 forms."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path
from typing import Any

from grammar_bundle_loader import GrammarBundle


ROOT = Path("dev-docs/experiments/mf2-grammar-agreement")


def run_apple_probe(root: Path, iterations: int) -> dict[str, Any]:
    command = [
        "swift",
        "-module-cache-path",
        "/private/tmp/swift-module-cache",
        str(root / "apple_morphology_probe.swift"),
        str(iterations),
    ]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return json.loads(completed.stdout)


def comparison_bundle() -> GrammarBundle:
    data = {
        "schema": "mf2-grammar-agreement/v0",
        "locale": "fr",
        "profile": "fr-v1",
        "messages": {
            "fr.sword": {"value": "Vous avez ramassé {$item :term article=definite}."},
            "fr.shield": {"value": "Vous avez ramassé {$item :term article=definite}."},
            "fr.hero": {"value": "Vous avez ramassé {$item :term article=definite}."},
            "fr.hotel": {"value": "Vous avez réservé {$item :term article=definite}."},
        },
        "terms": {
            "item.sword": {
                "text": "épée",
                "forms": {
                    "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{l'épée}}\n* * {{épée}}"
                },
                "morphology": {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"},
            },
            "item.shield": {
                "text": "bouclier",
                "forms": {
                    "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{le bouclier}}\n* * {{bouclier}}"
                },
                "morphology": {"partOfSpeech": "noun", "gender": "masculine", "number": "singular"},
            },
            "item.hero": {
                "text": "héros",
                "forms": {
                    "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{le héros}}\n* * {{héros}}"
                },
                "morphology": {"partOfSpeech": "noun", "gender": "masculine", "number": "singular"},
            },
            "item.hotel": {
                "text": "hôtel",
                "forms": {
                    "default": ".input {$usage :string}\n.input {$number :string}\n.match $usage $number\ndefinite singular {{l'hôtel}}\n* * {{hôtel}}"
                },
                "morphology": {"partOfSpeech": "noun", "gender": "masculine", "number": "singular"},
            },
        },
    }
    return GrammarBundle.from_data(data, "apple-comparison-fr")


def german_bundle() -> GrammarBundle:
    data = {
        "schema": "mf2-grammar-agreement/v0",
        "locale": "de",
        "profile": "de-v1",
        "messages": {
            "de.shield": {
                "value": "Du hast {$item :term article=definite case=accusative} aufgehoben."
            }
        },
        "terms": {
            "item.shield": {
                "text": "Schild",
                "forms": {
                    "default": ".input {$usage :string}\n.input {$case :string}\n.input {$number :string}\n.match $usage $case $number\ndefinite accusative singular {{den Schild}}\ndefinite nominative singular {{der Schild}}\ndefinite dative singular {{dem Schild}}\n* * * {{Schild}}"
                },
                "morphology": {"partOfSpeech": "noun", "gender": "masculine", "number": "singular"},
            }
        },
    }
    return GrammarBundle.from_data(data, "apple-comparison-de")


def ours_outputs() -> dict[str, str]:
    fr = comparison_bundle()
    de = german_bundle()
    return {
        "fr-explicit-feminine-sword": fr.format("fr.sword", {"item": "item.sword"}),
        "fr-explicit-masculine-shield": fr.format("fr.shield", {"item": "item.shield"}),
        "fr-auto-aspired-h": fr.format("fr.hero", {"item": "item.hero"}),
        "fr-auto-silent-h": fr.format("fr.hotel", {"item": "item.hotel"}),
        "de-explicit-accusative-shield": de.format("de.shield", {"item": "item.shield"}),
        "de-auto-shield": de.format("de.shield", {"item": "item.shield"}),
    }


def benchmark_ours(iterations: int) -> dict[str, Any]:
    bundle = german_bundle()
    start = time.perf_counter()
    checksum = 0
    for _ in range(iterations):
        checksum += len(bundle.format("de.shield", {"item": "item.shield"}))
    elapsed_ms = (time.perf_counter() - start) * 1000
    return {
        "caseId": "de-explicit-accusative-shield",
        "iterations": iterations,
        "elapsedMs": round(elapsed_ms, 3),
        "perOpUs": round(elapsed_ms * 1000 / iterations, 3),
        "checksum": checksum,
        "runtime": "python-prototype-raw-mf2-term-matcher",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--iterations", type=int, default=10_000)
    args = parser.parse_args()

    fixture = json.loads((args.root / "apple-comparison/apple-foundation-comparison.json").read_text())
    apple = run_apple_probe(args.root, args.iterations)
    apple_outputs = {case["id"]: case["output"] for case in apple["cases"]}
    ours = ours_outputs()

    rows = []
    failures = []
    for case in fixture["cases"]:
        case_id = case["id"]
        row = {
            "id": case_id,
            "intent": case["intent"],
            "apple": apple_outputs.get(case_id),
            "ours": ours.get(case_id),
            "oursExpected": case["oursExpected"],
            "appleMatchedFixture": apple_outputs.get(case_id) == case["appleObserved"],
            "oursMatchedExpected": ours.get(case_id) == case["oursExpected"],
        }
        rows.append(row)
        if not row["appleMatchedFixture"] or not row["oursMatchedExpected"]:
            failures.append(row)

    result = {
        "canInflect": apple["canInflect"],
        "rows": rows,
        "perf": {
            "apple": {**apple["perf"], "runtime": "apple-foundation-inflection"},
            "ours": benchmark_ours(args.iterations),
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
