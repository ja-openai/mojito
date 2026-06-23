#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
FIXTURES="${1:-$ROOT/conformance/fixtures/source-to-model}"

(cd "$ROOT/rust/mojito-mf2" && cargo run -- conformance "$FIXTURES")
(cd "$ROOT/rust/mojito-mf2" && cargo test --test conformance core_fixtures_pass)
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance "$FIXTURES")
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance --number-core)
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance --date-time-core)
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance --relative-time-core)
(cd "$ROOT/python" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/python" && sh run.sh number-core)
(cd "$ROOT/python" && sh run.sh date-time-core)
(cd "$ROOT/python" && sh run.sh relative-time-core)
(cd "$ROOT/java" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/java" && sh run.sh number-core-check)
(cd "$ROOT/java" && sh run.sh date-time-core-check)
(cd "$ROOT/java" && sh run.sh relative-time-core-check)
(cd "$ROOT/kotlin" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/kotlin" && sh run.sh number-core-check)
(cd "$ROOT/kotlin" && sh run.sh date-time-core-check)
(cd "$ROOT/kotlin" && sh run.sh relative-time-core-check)
(cd "$ROOT/javascript" && node tools/conformance.js "$FIXTURES")
(cd "$ROOT/javascript" && node tests/number-core-test.js)
(cd "$ROOT/javascript" && node tests/date-time-core-test.js)
(cd "$ROOT/javascript" && node tests/relative-time-core-test.js)
(cd "$ROOT/go" && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath-conformance}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache-conformance}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache-conformance}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test ./...)
(cd "$ROOT/php" && php tests/conformance.php)
