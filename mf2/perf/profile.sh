#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
MODE="${1:-rss}"
FIXTURES="$(CDPATH= cd -- "${2:-conformance/fixtures/source-to-model}" && pwd)"
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

if [ "$MODE" = python-cpu ]; then
  python3 "$ROOT/perf/corpus_manifest.py" "$FIXTURES" "$MODE" "$ITERATIONS" "$WARMUP_ITERATIONS"
  cd python
  exec sh run.sh profile "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS"
fi
case "$MODE" in
  rss) BENCH=bench; SWIFT_BENCH=--bench; GO_BENCH=BenchmarkFormatSharedFixtures; PHP_BENCH=--format; JS_BENCH=benchmark.js ;;
  rss-parse) BENCH=bench-parse; SWIFT_BENCH=--bench-parse; GO_BENCH=BenchmarkParseSharedFixtures; PHP_BENCH=--parse; JS_BENCH=parse-benchmark.js ;;
  *) echo "Usage: sh perf/profile.sh [rss|rss-parse|python-cpu] [fixture-dir] [iterations] [warmup-iterations]" >&2; exit 2 ;;
esac

# Build every executable before starting process RSS measurement. These commands
# intentionally remain visible: compilation and dependency failures are setup
# failures, not runtime memory samples.
PROFILE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/mojito-mf2-profile.XXXXXX")"
trap 'rm -rf "$PROFILE_TMP"' EXIT HUP INT TERM
(cd rust/mojito-mf2 && cargo build --release --bin mojito-mf2)
RUST_TARGET="$(cd rust/mojito-mf2 && cargo metadata --no-deps --format-version 1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"
(cd swift/MessageFormat2 && swift build -c release --product MessageFormat2Conformance)
SWIFT_BIN="$(cd swift/MessageFormat2 && swift build -c release --show-bin-path)"
(cd java && sh run.sh --prepare-only)
(cd kotlin && sh run.sh --prepare-only)
export GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}"
export GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}"
export GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
(cd go && go test -c -o "$PROFILE_TMP/go.test")
if [ "$MODE" = rss ]; then
  (cd reference/icu4j && sh run.sh --prepare-only)
  (cd reference/icu4cxx && sh run.sh --prepare-only "$FIXTURES")
fi
python3 "$ROOT/perf/corpus_manifest.py" "$FIXTURES" "$MODE" "$ITERATIONS" "$WARMUP_ITERATIONS"

run_with_time "rust release $MODE" "$RUST_TARGET/release/mojito-mf2" "$BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS"
run_with_time "swift release $MODE" "$SWIFT_BIN/MessageFormat2Conformance" "$SWIFT_BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS"
(cd python && run_with_time "python $MODE" sh run.sh "$BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS")
run_with_time "javascript $MODE" node "$ROOT/javascript/tools/$JS_BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS"
(cd java && run_with_time "java warmed $MODE" sh run.sh --no-prepare "$BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd kotlin && run_with_time "kotlin warmed $MODE" sh run.sh --no-prepare "$BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd go && run_with_time "go $MODE" env MF2_BENCH_FIXTURES="$FIXTURES" MF2_BENCH_WARMUP="$WARMUP_ITERATIONS" "$PROFILE_TMP/go.test" -test.run '^$' -test.bench "$GO_BENCH" -test.benchtime "${ITERATIONS}x" -test.count 1)
(cd php && run_with_time "php $MODE" php bench.php "$PHP_BENCH" "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS")
if [ "$MODE" = rss ]; then
  (cd reference/icu4j && run_with_time "icu4j reference warmed format" sh run.sh --no-prepare bench "$FIXTURES" "$ITERATIONS" "$WARMUP_ITERATIONS")
  (cd reference/icu4cxx && run_with_time "icu4cxx reference warmed format" sh run.sh --no-prepare bench "$ITERATIONS" "$WARMUP_ITERATIONS")
fi
