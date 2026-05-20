#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

build() {
  mvn -q compile
}

if [ "${1:-}" = "--prepare-only" ]; then
  build
  exit 0
fi

if [ "${1:-}" = "--no-prepare" ]; then
  shift
else
  build
fi

command="${1:-conformance}"
case "$command" in
  conformance)
    java -cp target/classes com.box.l10n.mojito.mf2.Conformance "${2:-../conformance/fixtures/source-to-model}"
    ;;
  bench)
    java -cp target/classes com.box.l10n.mojito.mf2.Benchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  bench-parse)
    java -cp target/classes com.box.l10n.mojito.mf2.ParseBenchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  demo)
    java -cp target/classes com.box.l10n.mojito.mf2.TranslateDemo
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
