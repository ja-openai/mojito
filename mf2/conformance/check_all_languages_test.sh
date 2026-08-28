#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
EMPTY_FIXTURES="$(mktemp -d "${TMPDIR:-/tmp}/mojito-mf2-empty-fixtures.XXXXXX")"
NO_FORMAT_FIXTURES="$(mktemp -d "${TMPDIR:-/tmp}/mojito-mf2-no-format-fixtures.XXXXXX")"
ln -s \
    "$ROOT/conformance/fixtures/source-to-model/namespaced-identifiers.json" \
    "$NO_FORMAT_FIXTURES/namespaced-identifiers.json"
trap 'unlink "$NO_FORMAT_FIXTURES/namespaced-identifiers.json"; rmdir "$NO_FORMAT_FIXTURES" "$EMPTY_FIXTURES"' EXIT

assert_incomplete_suite_fails() {
    label="$1"
    shift
    if output="$("$@" 2>&1)"; then
        printf '%s unexpectedly accepted an empty fixture suite.\n' "$label" >&2
        exit 1
    fi
    case "$output" in
        *"at least one source model and one format case"*) ;;
        *)
            printf '%s failed for the wrong reason:\n%s\n' "$label" "$output" >&2
            exit 1
            ;;
    esac
}

assert_incomplete_suite_fails \
    "all-language wrapper" \
    sh "$ROOT/conformance/check_all_languages.sh" "$EMPTY_FIXTURES"

assert_incomplete_suite_fails \
    "all-language wrapper with no format cases" \
    sh "$ROOT/conformance/check_all_languages.sh" "$NO_FORMAT_FIXTURES"

assert_incomplete_suite_fails \
    "Go runner" \
    sh -c 'cd "$1/go" && env MF2_CONFORMANCE_FIXTURES="$2" GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath-conformance-test}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache-conformance-test}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache-conformance-test}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test -run "^TestSourceToModelFixtures$" -count=1 .' sh "$ROOT" "$EMPTY_FIXTURES"

assert_incomplete_suite_fails \
    "PHP runner" \
    php "$ROOT/php/tests/conformance.php" "$EMPTY_FIXTURES"

printf 'Incomplete conformance fixture suite checks passed.\n'
