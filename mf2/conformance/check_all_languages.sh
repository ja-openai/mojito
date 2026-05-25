#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
FIXTURES="${1:-$ROOT/conformance/fixtures/source-to-model}"

(cd "$ROOT/rust/mojito-mf2" && cargo run -- conformance "$FIXTURES")
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance "$FIXTURES")
(cd "$ROOT/python" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/java" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/kotlin" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/javascript" && node tools/conformance.js "$FIXTURES")
(cd "$ROOT/go" && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath-conformance}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache-conformance}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache-conformance}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test ./...)
(cd "$ROOT/php" && php tests/conformance.php)
