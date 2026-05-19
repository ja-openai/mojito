from __future__ import annotations

import cProfile
import io
import pstats
import sys
import time
from pathlib import Path

from .benchmark import _load_cases
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
    limit = int(args[3]) if len(args) > 3 else 30
    cases = _load_cases(fixture_dir)
    if not cases:
        print("No format cases found.", file=sys.stderr)
        return 2

    for index in range(warmup_iterations):
        model, locale, arguments = cases[index % len(cases)]
        format_message(model, arguments, locale)

    byte_count = 0

    def run() -> None:
        nonlocal byte_count
        for index in range(iterations):
            model, locale, arguments = cases[index % len(cases)]
            output = format_message(model, arguments, locale)
            byte_count += len(output.encode("utf-8"))

    profile = cProfile.Profile()
    started = time.perf_counter()
    profile.runcall(run)
    seconds = time.perf_counter() - started

    print(
        "python cpu-profile "
        f"iterations={iterations} "
        f"warmup={warmup_iterations} "
        f"cases={len(cases)} "
        f"seconds={seconds:.6f} "
        f"ops_per_second={iterations / seconds:.0f} "
        f"bytes={byte_count}"
    )

    output = io.StringIO()
    pstats.Stats(profile, stream=output).strip_dirs().sort_stats("cumulative").print_stats(limit)
    print(output.getvalue())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
