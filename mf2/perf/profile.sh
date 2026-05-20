#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

MODE="${1:-rss}"
FIXTURES="${2:-conformance/fixtures/source-to-model}"
ITERATIONS="${3:-100000}"
WARMUP_ITERATIONS="${4:-10000}"

run_with_time() {
  label="$1"
  shift
  printf '\n== %s ==\n' "$label"
  if /usr/bin/time -l true >/dev/null 2>&1; then
    /usr/bin/time -l "$@"
  elif /usr/bin/time -v true >/dev/null 2>&1; then
    /usr/bin/time -v "$@"
  else
    /usr/bin/time "$@"
  fi
}

case "${MODE}" in
  rss)
    (cd reference/icu4j && sh run.sh --prepare-only)
    (cd reference/icu4cxx && sh run.sh --prepare-only "../../${FIXTURES}")
    (cd java && sh run.sh --prepare-only)
    run_with_time \
      "rust release format" \
      sh -c "cd rust/mf2-prototype && cargo run --release -- bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "swift release format" \
      sh -c "cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "python format" \
      sh -c "cd python && python3 -m mf2_runtime.benchmark '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "java warmed format" \
      sh -c "cd java && sh run.sh --no-prepare bench '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "icu4j warmed format" \
      sh -c "cd reference/icu4j && sh run.sh --no-prepare bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "icu4cxx warmed format" \
      sh -c "cd reference/icu4cxx && sh run.sh --no-prepare bench '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    ;;
  python-cpu)
    cd python
    python3 -m mf2_runtime.profiler "../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}"
    ;;
  *)
    echo "Usage: sh perf/profile.sh [rss|python-cpu] [fixture-dir] [iterations] [warmup-iterations]" >&2
    exit 2
    ;;
esac
