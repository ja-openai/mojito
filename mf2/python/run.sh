#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
export PYTHONPATH="${PYTHONPATH:+${PYTHONPATH}:}src"

command="${1:-conformance}"
case "$command" in
  conformance)
    python3 tools/conformance.py "${2:-../conformance/fixtures/source-to-model}"
    ;;
  demo)
    python3 examples/translate_demo.py
    ;;
  babel-demo)
    python3 examples/babel_demo.py
    ;;
  bench)
    python3 tools/benchmark.py "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  bench-parse)
    python3 tools/benchmark.py --parse "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  number-core)
    python3 tools/conformance.py --number-core "${2:-../conformance/fixtures/number-core/cases.json}"
    ;;
  date-time-core)
    python3 tools/conformance.py --date-time-core "${2:-../conformance/fixtures/date-time-core/cases.json}"
    ;;
  relative-time-core)
    python3 tests/test_relative_time_core.py
    ;;
  number-core-bench)
    python3 tools/conformance.py --number-core-bench "${2:-../conformance/fixtures/number-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  date-time-core-bench)
    python3 tools/conformance.py --date-time-core-bench "${2:-../conformance/fixtures/date-time-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  profile)
    python3 tools/profiler.py "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  test)
    python3 -m unittest discover -s tests
    ;;
  typecheck)
    python3 -m mypy --strict tests/package_boundary_types.py
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
