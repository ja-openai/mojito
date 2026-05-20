#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

FIXTURES="${1:-conformance/fixtures/source-to-model}"
ITERATIONS="${2:-100000}"
WARMUP_ITERATIONS="${3:-10000}"

(cd rust/mf2-prototype && cargo run --release -- bench "../../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd swift/MessageFormat2 && swift run -c release MessageFormat2Conformance --bench "../../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd python && python3 -m mf2_runtime.benchmark "../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd java && sh run.sh bench "../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd reference/icu4j && sh run.sh bench "../../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
(cd reference/icu4cxx && sh run.sh bench "../../${FIXTURES}" "${ITERATIONS}" "${WARMUP_ITERATIONS}")
