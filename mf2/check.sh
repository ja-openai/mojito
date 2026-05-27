#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

sh static_check.sh
(cd cldr && sh validate_plural_rules.sh)
(cd cldr && sh validate_number_data.sh)
(cd cldr && sh validate_relative_time_data.sh)
(cd conformance && python3 validate_relative_time_fixture.py)
(cd rust/mojito-mf2 && cargo test)
(cd rust/mojito-mf2 && cargo run -- conformance ../../conformance/fixtures/source-to-model)
(cd rust/mojito-mf2 && cargo run -- unicode-tests)
(cd rust/mojito-mf2 && cargo run --example translate_demo)
(cd rust/mojito-mf2 && cargo run --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2Conformance)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && sh run.sh conformance)
(cd python && sh run.sh test)
(cd python && sh run.sh demo)
(cd kotlin && sh run.sh conformance)
(cd kotlin && sh run.sh demo)
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test ./...)
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go run ./cmd/demo)
(cd php && php tests/conformance.php)
(cd php && php tests/unicode_tests.php)
(cd php && php examples/demo.php)
(cd javascript && npm run check)
(cd javascript && npm run unicode-tests)
(cd javascript && npm run demo)
if [ -d react/node_modules ]; then
  (cd react && npm run check)
fi
(cd java && sh run.sh conformance)
(cd java && sh run.sh unicode-tests)
(cd java && sh run.sh demo)
(cd java && sh run.sh inline-demo)
(cd java && sh run.sh public-api-demo)
(cd java && sh run.sh datetime-demo)
