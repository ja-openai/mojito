#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

VALID_FIXTURES="${1:-conformance/fixtures/source-to-model}"
INVALID_FIXTURES="${2:-conformance/fixtures/invalid-source}"
ITERATIONS="${3:-100000}"
WARMUP_ITERATIONS="${4:-10000}"

echo "== valid sources =="
(cd rust/mojito-mf2 && cargo run --release -- bench-parse "../../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && sh run.sh run -c release MessageFormat2Conformance --bench-parse "../../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && sh run.sh bench-parse "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd kotlin && sh run.sh bench-parse "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test -run '^$' -bench BenchmarkParseSharedFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd php && php bench.php --parse "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")

echo "== invalid sources =="
(cd rust/mojito-mf2 && cargo run --release -- bench-parse "../../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && sh run.sh run -c release MessageFormat2Conformance --bench-parse "../../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && sh run.sh bench-parse "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd kotlin && sh run.sh bench-parse "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd php && php bench.php --parse "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
