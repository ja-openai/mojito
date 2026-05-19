from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

from .model import format_message


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    fixture_dir = (
        Path(args[0])
        if args
        else Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "source-to-model"
    )
    iterations = int(args[1]) if len(args) > 1 else 100_000
    warmup_iterations = int(args[2]) if len(args) > 2 else 10_000
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
        byte_count += len(output.encode("utf-8"))
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


if __name__ == "__main__":
    raise SystemExit(main())
