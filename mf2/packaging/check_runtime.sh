#!/usr/bin/env sh
# Standalone runtime CI gate. Each selected job runs its own production bridge.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
RUNTIME="${1:?usage: check_runtime.sh python|javascript|java|kotlin|go|rust|swift|php|shared}"
REPORTS="${MF2_CI_REPORT_DIR:-${TMPDIR:-/tmp}/mojito-mf2-ci-reports}"
mkdir -p "$REPORTS"
REPORTS="$(CDPATH= cd -- "$REPORTS" && pwd)"
export PYTHONDONTWRITEBYTECODE=1

check_official() {
  registry="$1"
  shift
  if [ "$registry" = portable ]; then
    python3 "$ROOT/conformance/check_parser_progress.py" --runtime "$RUNTIME" -- "$@"
  fi
  dispositions="$ROOT/conformance/official-dispositions/$RUNTIME-$registry.json"
  if [ ! -f "$dispositions" ]; then
    echo "Missing maintained official-suite dispositions: $dispositions" >&2
    exit 1
  fi
  python3 "$ROOT/conformance/check_official.py" --runtime "$RUNTIME" \
    --registry "$registry" --dispositions "$dispositions" \
    --report "$REPORTS/$RUNTIME-$registry.json" -- "$@"
}

check_adapters() {
  dispositions="$ROOT/conformance/adapter-dispositions/$RUNTIME-platform.json"
  if [ -f "$dispositions" ]; then
    python3 "$ROOT/conformance/check_adapters.py" --runtime "$RUNTIME" --registry platform \
      --dispositions "$dispositions" --report "$REPORTS/$RUNTIME-adapters.json" -- "$@"
  else
    python3 "$ROOT/conformance/check_adapters.py" --runtime "$RUNTIME" --registry platform \
      --report "$REPORTS/$RUNTIME-adapters.json" -- "$@"
  fi
}

case "$RUNTIME" in
  python)
    (cd "$ROOT/python" && sh run.sh test && sh run.sh conformance && sh run.sh typecheck)
    for registry in portable platform; do
      check_official "$registry" env "PYTHONPATH=$ROOT/python/src" python3 "$ROOT/python/tools/official_bridge.py"
    done
    check_adapters env "PYTHONPATH=$ROOT/python/src" python3 "$ROOT/python/tools/official_bridge.py"
    ;;
  javascript)
    (cd "$ROOT/javascript" && npm run check)
    for registry in portable platform; do
      check_official "$registry" node "$ROOT/javascript/tools/official-bridge.js"
    done
    check_adapters node "$ROOT/javascript/tools/official-bridge.js"
    ;;
  java|kotlin)
    sh "$ROOT/$RUNTIME/run.sh" --prepare-only
    sh "$ROOT/$RUNTIME/run.sh" --no-prepare conformance
    sh "$ROOT/$RUNTIME/run.sh" --no-prepare jdk-check
    (cd "$ROOT/$RUNTIME-icu4j" && sh run.sh check)
    for registry in portable platform; do
      check_official "$registry" sh "$ROOT/$RUNTIME/run.sh" --no-prepare official-bridge
    done
    check_adapters sh "$ROOT/$RUNTIME/run.sh" --no-prepare official-bridge
    ;;
  go)
    (cd "$ROOT/go" && go vet ./... && go test ./...)
    (cd "$ROOT/go" && go test -c -o "$REPORTS/go-official-bridge")
    check_official portable env MF2_OFFICIAL_BRIDGE=1 "$REPORTS/go-official-bridge"
    ;;
  rust)
    (cd "$ROOT/rust/mojito-mf2" && cargo check --locked --all-targets --features icu4x && cargo test --locked --features icu4x)
    (cd "$ROOT/rust/mojito-mf2" && cargo run --locked --features icu4x -- conformance ../../conformance/fixtures/source-to-model)
    for registry in portable platform; do
      check_official "$registry" cargo run --quiet --locked --manifest-path "$ROOT/rust/mojito-mf2/Cargo.toml" --features icu4x -- official-bridge
    done
    check_adapters cargo run --quiet --locked --manifest-path "$ROOT/rust/mojito-mf2/Cargo.toml" --features icu4x -- official-bridge
    ;;
  swift)
    swift build --package-path "$ROOT/swift/MessageFormat2"
    "$ROOT/swift/MessageFormat2/.build/debug/MessageFormat2Conformance" "$ROOT/conformance/fixtures/source-to-model"
    (cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2FoundationDemo)
    for registry in portable platform; do
      check_official "$registry" "$ROOT/swift/MessageFormat2/.build/debug/MessageFormat2Conformance" official-bridge
    done
    check_adapters "$ROOT/swift/MessageFormat2/.build/debug/MessageFormat2Conformance" official-bridge
    ;;
  php)
    php "$ROOT/php/tests/conformance.php"
    php "$ROOT/php/tests/runtime_regressions.php"
    php "$ROOT/php/tests/intl_functions.php"
    for registry in portable platform; do
      check_official "$registry" php "$ROOT/php/tests/official_bridge.php"
    done
    check_adapters php "$ROOT/php/tests/official_bridge.php"
    ;;
  shared)
    python3 -m unittest discover -s "$ROOT/packaging" -p 'test_*.py'
    python3 -m unittest discover -s "$ROOT/conformance" -p 'test_official_harness.py'
    sh "$ROOT/cldr/check_generated.sh"
    sh "$ROOT/cldr/validate_plural_rules.sh"
    sh "$ROOT/cldr/validate_number_data.sh"
    sh "$ROOT/cldr/validate_relative_time_data.sh"
    python3 "$ROOT/conformance/validate_relative_time_fixture.py"
    sh "$ROOT/conformance/check_all_languages_test.sh"
    ;;
  *) echo "Unknown MF2 runtime: $RUNTIME" >&2; exit 2 ;;
esac
