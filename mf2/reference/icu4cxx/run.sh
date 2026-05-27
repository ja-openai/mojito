#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

TARGET_DIR="target"
BIN="${TARGET_DIR}/icu4cxx-reference"
GENERATED="${TARGET_DIR}/generated_cases.hpp"

detect_icu_prefix() {
  if [ -n "${ICU4C_PREFIX:-}" ]; then
    printf '%s\n' "${ICU4C_PREFIX}"
    return
  fi

  for candidate in \
    /opt/homebrew/opt/icu4c@77 \
    /opt/homebrew/Cellar/icu4c@77/77.1 \
    /opt/homebrew/opt/icu4c \
    /usr/local/opt/icu4c@77 \
    /usr/local/opt/icu4c
  do
    if [ -f "${candidate}/include/unicode/messageformat2.h" ]; then
      printf '%s\n' "${candidate}"
      return
    fi
  done

  echo "ICU4C++ with messageformat2.h was not found. Set ICU4C_PREFIX." >&2
  exit 2
}

prepare() {
  fixtures="$1"
  icu_prefix="$(detect_icu_prefix)"
  mkdir -p "${TARGET_DIR}"
  python3 generate_cases.py "${fixtures}" "${GENERATED}"
  clang++ \
    -std=c++17 \
    -Wall \
    -Wextra \
    -Wno-deprecated-declarations \
    -Wno-unnecessary-virtual-specifier \
    -I"${TARGET_DIR}" \
    -I"${icu_prefix}/include" \
    -L"${icu_prefix}/lib" \
    -Wl,-rpath,"${icu_prefix}/lib" \
    src/icu4cxx_reference.cpp \
    -licui18n \
    -licuuc \
    -licudata \
    -o "${BIN}"
}

if [ "${1:-}" = "--prepare-only" ]; then
  prepare "${2:-../../conformance/fixtures/source-to-model}"
  exit 0
fi

if [ "${1:-}" = "--no-prepare" ]; then
  shift
  "${BIN}" "$@"
  exit 0
else
  command="${1:-compare}"
  fixtures="${2:-../../conformance/fixtures/source-to-model}"
  prepare "${fixtures}"
fi

case "${command}" in
  compare)
    "${BIN}" compare
    ;;
  bench)
    "${BIN}" bench "${3:-100000}" "${4:-10000}"
    ;;
  *)
    echo "Usage: sh run.sh [compare|bench] [fixture-dir] [iterations] [warmup-iterations]" >&2
    exit 2
    ;;
esac
