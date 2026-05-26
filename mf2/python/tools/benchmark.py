from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

from mojito_mf2 import format_message
from mojito_mf2.parser import parse_to_model


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    mode = "format"
    if args and args[0] in {"--format", "--parse"}:
        mode = args.pop(0)[2:]
    fixture_dir = (
        Path(args[0])
        if args
        else Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "source-to-model"
    )
    iterations = int(args[1]) if len(args) > 1 else 100_000
    warmup_iterations = int(args[2]) if len(args) > 2 else 10_000
    if mode == "parse":
        return _run_parse_benchmark(fixture_dir, iterations, warmup_iterations)

    cases = _load_cases(fixture_dir)
    if not cases:
        print("No format cases found.", file=sys.stderr)
        return 2

    for index in range(warmup_iterations):
        model, locale, arguments = cases[index % len(cases)]
        format_message(model, arguments, locale)

    started = time.perf_counter()
    byte_count = 0
    for index in range(iterations):
        model, locale, arguments = cases[index % len(cases)]
        output = format_message(model, arguments, locale)
        byte_count += len(output.value.encode("utf-8"))
    seconds = time.perf_counter() - started
    print(
        "python format "
        f"iterations={iterations} "
        f"warmup={warmup_iterations} "
        f"cases={len(cases)} "
        f"seconds={seconds:.6f} "
        f"ops_per_second={iterations / seconds:.0f} "
        f"bytes={byte_count}"
    )
    return 0


def _run_parse_benchmark(fixture_dir: Path, iterations: int, warmup_iterations: int) -> int:
    sources = _load_sources(fixture_dir)
    if not sources:
        print("No source cases found.", file=sys.stderr)
        return 2

    for index in range(warmup_iterations):
        parse_to_model(sources[index % len(sources)])

    started = time.perf_counter()
    parsed_count = 0
    diagnostic_count = 0
    byte_count = 0
    for index in range(iterations):
        source = sources[index % len(sources)]
        result = parse_to_model(source)
        parsed_count += 1 if result.model is not None else 0
        diagnostic_count += len(result.diagnostics)
        byte_count += len(source.encode("utf-8"))
    seconds = time.perf_counter() - started
    print(
        "python parse "
        f"iterations={iterations} "
        f"warmup={warmup_iterations} "
        f"cases={len(sources)} "
        f"seconds={seconds:.6f} "
        f"ops_per_second={iterations / seconds:.0f} "
        f"parsed={parsed_count} "
        f"diagnostics={diagnostic_count} "
        f"bytes={byte_count}"
    )
    return 0


def _load_cases(fixture_dir: Path) -> list[tuple[dict[str, Any], str, dict[str, Any]]]:
    cases: list[tuple[dict[str, Any], str, dict[str, Any]]] = []
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        with fixture_path.open(encoding="utf-8") as file:
            fixture = json.load(file)
        for format_case in fixture.get("formatCases", []):
            cases.append(
                (
                    fixture["expectedModel"],
                    format_case.get("locale", "en"),
                    format_case.get("arguments", {}),
                )
            )
    return cases


def _load_sources(fixture_dir: Path) -> list[str]:
    sources: list[str] = []
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        with fixture_path.open(encoding="utf-8") as file:
            fixture = json.load(file)
        source = fixture.get("source")
        if isinstance(source, str):
            sources.append(source)
    return sources


if __name__ == "__main__":
    raise SystemExit(main())
