#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
export PYTHONPATH="${PYTHONPATH:+${PYTHONPATH}:}src"

command="${1:-conformance}"
case "$command" in
  conformance)
    python3 tools/conformance.py "${2:-../conformance/fixtures/source-to-model}"
    ;;
  inflection-release)
    python3 ../conformance/validate_inflection_release_fixture.py
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
