#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

VALID_FIXTURES="${1:-conformance/fixtures/source-to-model}"
INVALID_FIXTURES="${2:-conformance/fixtures/invalid-source}"
ITERATIONS="${3:-100000}"
WARMUP_ITERATIONS="${4:-10000}"

echo "== valid sources =="
(cd rust/mf2-prototype && cargo run --release -- bench-parse "../../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "../${VALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")

echo "== invalid sources =="
(cd rust/mf2-prototype && cargo run --release -- bench-parse "../../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd javascript && npm run bench:parse -- "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench-parse "../${INVALID_FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
