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
  unicode-tests)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.UnicodeOfficialTests "${2:-../third_party/message-format-wg/test}" "${3:-../conformance/unicode-official-baseline.json}"
    ;;
  demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.TranslateDemo
    ;;
  inline-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.InlineTranslateDemo
    ;;
  datetime-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeBoundaryDemo
    ;;
  showcase)
    echo "== catalog demo: precompiled model/resource bundle =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.TranslateDemo
    echo
    echo "== inline source demo: parser + runtime + parts/fallback =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.InlineTranslateDemo
    echo
    echo "== date/time boundary demo: typed host values =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeBoundaryDemo
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
