#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

VALID_FIXTURES="$(CDPATH= cd -- "${1:-conformance/fixtures/source-to-model}" && pwd)"
INVALID_FIXTURES="$(CDPATH= cd -- "${2:-conformance/fixtures/invalid-source}" && pwd)"
ITERATIONS="${3:-100000}"
WARMUP_ITERATIONS="${4:-10000}"

python3 perf/corpus_manifest.py "$VALID_FIXTURES" parse "$ITERATIONS" "$WARMUP_ITERATIONS"

echo "== valid sources =="
(cd rust/mojito-mf2 && cargo run --release -- bench-parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench-parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && sh run.sh bench-parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd kotlin && sh run.sh bench-parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" MF2_BENCH_FIXTURES="$VALID_FIXTURES" MF2_BENCH_WARMUP="$WARMUP_ITERATIONS" go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd php && php bench.php --parse "${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")

python3 perf/corpus_manifest.py "$INVALID_FIXTURES" parse "$ITERATIONS" "$WARMUP_ITERATIONS"

echo "== invalid sources =="
(cd rust/mojito-mf2 && cargo run --release -- bench-parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench-parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && sh run.sh bench-parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd kotlin && sh run.sh bench-parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" MF2_BENCH_FIXTURES="$INVALID_FIXTURES" MF2_BENCH_WARMUP="$WARMUP_ITERATIONS" go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd php && php bench.php --parse "${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
