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
    (cd kotlin && sh run.sh --prepare-only)
    run_with_time \
      "rust release format" \
      sh -c "cd rust/mojito-mf2 && cargo run --release -- bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "swift release format" \
      sh -c "cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "python format" \
      sh -c "cd python && sh run.sh bench '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "javascript format" \
      sh -c "cd javascript && npm run bench:format -- '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "java warmed format" \
      sh -c "cd java && sh run.sh --no-prepare bench '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "kotlin warmed format" \
      sh -c "cd kotlin && sh run.sh --no-prepare bench '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "go format" \
      sh -c "cd go && env GOPATH=\"\${GOPATH:-/private/tmp/mojito-mf2-go-gopath}\" GOMODCACHE=\"\${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}\" GOCACHE=\"\${GOCACHE:-/private/tmp/mojito-mf2-go-cache}\" GOTOOLCHAIN=\"\${GOTOOLCHAIN:-local}\" go test -run '^$' -bench BenchmarkFormatSharedFixtures -benchtime '${ITERATIONS}x' -count=1"
    run_with_time \
      "php format" \
      sh -c "cd php && php bench.php --format '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "icu4j warmed format" \
      sh -c "cd reference/icu4j && sh run.sh --no-prepare bench '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "icu4cxx warmed format" \
      sh -c "cd reference/icu4cxx && sh run.sh --no-prepare bench '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    ;;
  rss-parse)
    (cd java && sh run.sh --prepare-only)
    (cd kotlin && sh run.sh --prepare-only)
    run_with_time \
      "rust release parse" \
      sh -c "cd rust/mojito-mf2 && cargo run --release -- bench-parse '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "swift release parse" \
      sh -c "cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench-parse '../../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "python parse" \
      sh -c "cd python && sh run.sh bench-parse '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "javascript parse" \
      sh -c "cd javascript && npm run bench:parse -- '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "java warmed parse" \
      sh -c "cd java && sh run.sh --no-prepare bench-parse '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "kotlin warmed parse" \
      sh -c "cd kotlin && sh run.sh --no-prepare bench-parse '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    run_with_time \
      "go parse" \
      sh -c "cd go && env GOPATH=\"\${GOPATH:-/private/tmp/mojito-mf2-go-gopath}\" GOMODCACHE=\"\${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}\" GOCACHE=\"\${GOCACHE:-/private/tmp/mojito-mf2-go-cache}\" GOTOOLCHAIN=\"\${GOTOOLCHAIN:-local}\" go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime '${ITERATIONS}x' -count=1"
    run_with_time \
      "php parse" \
      sh -c "cd php && php bench.php --parse '../${FIXTURES}' '${ITERATIONS}' '${WARMUP_ITERATIONS}'"
    ;;
  python-cpu)
    cd python
    sh run.sh profile "../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}"
    ;;
  *)
    echo "Usage: sh perf/profile.sh [rss|rss-parse|python-cpu] [fixture-dir] [iterations] [warmup-iterations]" >&2
    exit 2
    ;;
esac
