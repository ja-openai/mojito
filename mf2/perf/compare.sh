#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

FIXTURES="$(CDPATH= cd -- "${1:-conformance/fixtures/source-to-model}" && pwd)"
ITERATIONS="${2:-100000}"
WARMUP_ITERATIONS="${3:-10000}"

python3 perf/corpus_manifest.py "$FIXTURES" format "$ITERATIONS" "$WARMUP_ITERATIONS"

(cd rust/mojito-mf2 && cargo run --release -- bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && sh run.sh bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:format -- "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd kotlin && sh run.sh bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" MF2_BENCH_FIXTURES="$FIXTURES" MF2_BENCH_WARMUP="$WARMUP_ITERATIONS" go test -run '^$' -bench BenchmarkFormatSharedFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd php && php bench.php --format "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd reference/icu4j && sh run.sh bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd reference/icu4cxx && sh run.sh bench "${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
