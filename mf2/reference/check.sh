#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

run_and_expect_summary() {
  label="$1"
  expected="$2"
  required_line="$3"
  shift 3

  if output="$("$@")"; then
    printf '%s\n' "$output"
  else
    status="$?"
    printf '%s\n' "$output"
    echo "$label command failed with status $status." >&2
    return "$status"
  fi

  actual="$(printf '%s\n' "$output" | tail -n 1)"
  if [ "$actual" != "$expected" ]; then
    echo "$label summary changed." >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    return 1
  fi

  if [ -n "$required_line" ] && ! printf '%s\n' "$output" | grep -Fqx "$required_line"; then
    echo "$label did not report the expected disposition." >&2
    echo "  expected line: $required_line" >&2
    return 1
  fi
}

case "${MF2_REQUIRE_ICU4C:-0}" in
  0 | 1) ;;
  *)
    echo "MF2_REQUIRE_ICU4C must be 0 or 1." >&2
    exit 2
    ;;
esac

run_and_expect_summary \
  "ICU4J common selection" \
  "icu4j compare total=37 passed=37 failed=0 unsupported=0" \
  "" \
  sh icu4j/run.sh compare ../fixtures/selection-operands/common
run_and_expect_summary \
  "ICU4J-specific selection" \
  "icu4j compare total=5 passed=5 failed=0 unsupported=0" \
  "" \
  sh icu4j/run.sh compare ../fixtures/selection-operands/icu4j
run_and_expect_summary \
  "ICU4J invalid-key recovery" \
  "icu4j compare total=1 passed=1 failed=0 unsupported=0" \
  "" \
  sh icu4j/run.sh compare ../fixtures/selection-recovery/common
run_and_expect_summary \
  "ICU4J currency resolved value" \
  "icu4j compare total=1 passed=1 failed=0 unsupported=0" \
  "" \
  sh icu4j/run.sh compare ../fixtures/resolved-values/common

if ICU4C_PREFIX="$(icu4cxx/run.sh --detect-prefix 2>/dev/null)"; then
  export ICU4C_PREFIX
  if [ "${MF2_REQUIRE_ICU4C:-0}" = "1" ]; then
    echo "ICU4C++ extension checks are required by MF2_REQUIRE_ICU4C=1."
  else
    echo "ICU4C++ extension checks are available and optional."
  fi
  run_and_expect_summary \
    "ICU4C++ common selection" \
    "icu4cxx compare total=37 passed=36 failed=0 unsupported=1" \
    "UNSUPPORTED reference-number-option-inheritance/inherited-visible-fraction-affects-selection[en]: U_MF_UNKNOWN_FUNCTION_ERROR" \
    sh icu4cxx/run.sh compare ../fixtures/selection-operands/common
  run_and_expect_summary \
    "ICU4C++ invalid-key recovery" \
    "icu4cxx compare total=1 passed=1 failed=0 unsupported=0" \
    "" \
    sh icu4cxx/run.sh compare ../fixtures/selection-recovery/common
  run_and_expect_summary \
    "ICU4C++ currency resolved value" \
    "icu4cxx compare total=1 passed=0 failed=0 unsupported=1" \
    "UNSUPPORTED reference-currency-through-number/implicit-currency-provenance[en]: U_MF_UNKNOWN_FUNCTION_ERROR" \
    sh icu4cxx/run.sh compare ../fixtures/resolved-values/common
elif [ "${MF2_REQUIRE_ICU4C:-0}" = "1" ]; then
  echo "ICU4C++ extension checks are required, but messageformat2.h was not found." >&2
  exit 1
else
  echo "ICU4C++ extension checks skipped (optional): messageformat2.h was not found."
fi
