#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != --worktree ]; }; then
  echo "Usage: sh mf2/check.sh [--worktree]" >&2
  exit 2
fi

sh cldr/check_generated.sh "$@"
sh static_check.sh
sh conformance/check_all_languages_test.sh
python3 -m unittest discover -s conformance -p 'test_official_harness.py'
python3 -m unittest discover -s packaging -p 'test_*.py'
sh cldr/validate_plural_rules.sh
sh cldr/validate_number_data.sh
sh cldr/validate_relative_time_data.sh
python3 conformance/validate_relative_time_fixture.py

# One maintained selector owns native, shared, hostile-parser, direct official
# and production-adapter checks for each runtime. Artifact installs are separate.
for runtime in python javascript java kotlin go rust swift php; do
  sh packaging/check_runtime.sh "$runtime"
done

(cd rust/mojito-mf2 && cargo run --locked --example translate_demo && cargo run --locked --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && sh run.sh demo)
(cd kotlin && sh run.sh demo)
(cd go && go run ./cmd/demo)
(cd php && php examples/demo.php)
(cd javascript && npm run demo)
if [ -d react/node_modules ]; then
  (cd react && npm run check)
fi
(cd java && sh run.sh --no-prepare demo && sh run.sh --no-prepare inline-demo && sh run.sh --no-prepare public-api-demo && sh run.sh --no-prepare datetime-demo)
sh reference/check.sh
