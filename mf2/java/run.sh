#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

build() {
  mvn -q test-compile
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
tool_classpath="target/test-classes:target/classes"
case "$command" in
  conformance)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.Conformance "${2:-../conformance/fixtures/source-to-model}"
    ;;
  bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.Benchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  bench-parse)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.ParseBenchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.TranslateDemo
    ;;
  inline-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.InlineTranslateDemo
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
