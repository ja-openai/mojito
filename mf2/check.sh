#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

(cd cldr && sh validate_plural_rules.sh)
(cd rust/mf2-prototype && cargo test)
(cd rust/mf2-prototype && cargo run --example translate_demo)
(cd rust/mf2-prototype && cargo run --example inline_translate_demo)
(cd swift/MessageFormat2 && swift run MessageFormat2Conformance)
(cd swift/MessageFormat2 && swift run MessageFormat2TranslateDemo)
(cd python && python3 -m mf2_runtime.conformance ../conformance/fixtures/source-to-model)
(cd python && python3 examples/translate_demo.py)
(cd java && sh run.sh conformance)
(cd java && sh run.sh demo)
