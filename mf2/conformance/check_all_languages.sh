#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
FIXTURES="${1:-$ROOT/conformance/fixtures/source-to-model}"
SWIFT_PACKAGE="$ROOT/swift/MessageFormat2"

swift_conformance() {
  (
    cd "$SWIFT_PACKAGE"
    swift run \
      --disable-sandbox \
      --cache-path .build/cache \
      --config-path .build/config \
      --security-path .build/security \
      --manifest-cache local \
      MessageFormat2Conformance "$@"
  )
}

(cd "$ROOT/rust/mojito-mf2" && cargo run -- conformance "$FIXTURES")
(cd "$ROOT/rust/mojito-mf2" && cargo test --test conformance core_fixtures_pass)
swift_conformance "$FIXTURES"
swift_conformance --number-core
swift_conformance --date-time-core
swift_conformance --relative-time-core
(cd "$ROOT/python" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/python" && sh run.sh test)
(cd "$ROOT/python" && sh run.sh typecheck)
(cd "$ROOT/python" && sh run.sh number-core)
(cd "$ROOT/python" && sh run.sh date-time-core)
(cd "$ROOT/python" && sh run.sh relative-time-core)
(
  cd "$ROOT/java"
  sh run.sh --prepare-only
  sh run.sh --no-prepare conformance "$FIXTURES"
  sh run.sh --no-prepare number-core-check
  sh run.sh --no-prepare date-time-core-check
  sh run.sh --no-prepare relative-time-core-check
)
(
  cd "$ROOT/kotlin"
  sh run.sh --prepare-only
  sh run.sh --no-prepare conformance "$FIXTURES"
  sh run.sh --no-prepare number-core-check
  sh run.sh --no-prepare date-time-core-check
  sh run.sh --no-prepare relative-time-core-check
)
(cd "$ROOT/javascript" && node tools/conformance.js "$FIXTURES")
(cd "$ROOT/javascript" && node tests/package-boundary-test.js)
(cd "$ROOT/javascript" && npm run check:types)
(cd "$ROOT/javascript" && node tests/number-core-test.js)
(cd "$ROOT/javascript" && node tests/date-time-core-test.js)
(cd "$ROOT/javascript" && node tests/relative-time-core-test.js)
(cd "$ROOT/go" && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath-conformance}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache-conformance}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache-conformance}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test ./...)
(cd "$ROOT/php" && php tests/conformance.php)
