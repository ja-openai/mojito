#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

GO_CACHE_SCOPE=check . ./go_cache_env.sh

sh static_check.sh
(cd cldr && sh check_size_gates.sh)
(cd cldr && sh validate_plural_rules.sh)
(cd cldr && sh validate_number_data.sh)
(cd cldr && sh validate_datetime_data.sh)
(cd reference/icu4j && sh run.sh datetime-styles-strict ../../conformance/fixtures/date-time-core/cases.json)
(cd reference/icu4j && sh run.sh datetime-skeletons-strict ../../conformance/fixtures/date-time-core/cases.json)
(cd cldr && sh validate_relative_time_data.sh)
(cd conformance && python3 validate_relative_time_fixture.py)
sh conformance/check_all_languages.sh
(cd rust/mojito-mf2 && cargo run -- unicode-tests)
(cd rust/mojito-mf2 && cargo test --features icu4x)
(cd rust/mojito-mf2 && cargo run --example translate_demo)
(cd rust/mojito-mf2 && cargo run --example inline_translate_demo)
(cd rust/mojito-mf2 && cargo run --features icu4x --example icu4x_demo)
(cd swift/MessageFormat2 && sh run.sh run MessageFormat2TranslateDemo)
(cd swift/MessageFormat2 && sh run.sh run MessageFormat2FoundationDemo)
(cd python && sh run.sh demo)
if (cd python && python3 -c "import babel" >/dev/null 2>&1); then
  (cd python && sh run.sh babel-demo)
else
  echo "Skipping Python Babel demo; Babel is not installed."
fi
(cd kotlin && sh run.sh demo)
(cd go && env GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go run ./cmd/demo)
(cd php && php tests/unicode_tests.php)
(cd php && php tests/intl_functions.php)
(cd php && php examples/demo.php)
(cd php && php examples/intl_demo.php)
(cd javascript && npm run unicode-tests)
(cd javascript && npm run demo)
(cd javascript && npm run demo:intl)
if [ -d react/node_modules ]; then
  (cd react && npm run check)
fi
(cd java && sh run.sh unicode-tests)
(cd java && sh run.sh demo)
(cd java && sh run.sh inline-demo)
(cd java && sh run.sh datetime-demo)
(cd java-icu4j && sh run.sh check)
(cd kotlin-icu4j && sh run.sh check)
