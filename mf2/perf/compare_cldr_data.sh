#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

ITERATIONS="${1:-100000}"
WARMUP_ITERATIONS="${2:-10000}"

node --expose-gc javascript/tools/cldr-data-benchmark.js "$ITERATIONS" "$WARMUP_ITERATIONS"
(cd python && sh run.sh number-core-bench ../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd python && sh run.sh date-time-core-bench ../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd rust/mojito-mf2 && cargo run --quiet -- number-core-bench ../../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd rust/mojito-mf2 && cargo run --quiet -- date-time-core-bench ../../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd swift/MessageFormat2 && sh run.sh run MessageFormat2Conformance --number-core-bench ../../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd swift/MessageFormat2 && sh run.sh run MessageFormat2Conformance --date-time-core-bench ../../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd java && sh run.sh number-core-bench ../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd java && sh run.sh --no-prepare date-time-core-bench ../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd kotlin && sh run.sh number-core-bench ../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd kotlin && sh run.sh --no-prepare date-time-core-bench ../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test -run '^$' -bench BenchmarkNumberCoreFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test -run '^$' -bench BenchmarkDateTimeCoreFixtures -benchtime "${ITERATIONS}x" -count=1)
(cd php && php bench.php --number-core ../conformance/fixtures/number-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
(cd php && php bench.php --date-time-core ../conformance/fixtures/date-time-core/cases.json "$ITERATIONS" "$WARMUP_ITERATIONS")
