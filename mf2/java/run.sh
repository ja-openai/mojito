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
  public-api-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.PublicApiDemo
    ;;
  jdk-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.JdkRegistryDemo
    ;;
  jdk-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.JdkRegistryDemo --quiet
    ;;
  number-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.NumberCoreTest "${2:-../conformance/fixtures/number-core/cases.json}"
    ;;
  number-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.NumberCoreBenchmark "${2:-../conformance/fixtures/number-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  date-time-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeCoreTest "${2:-../conformance/fixtures/date-time-core/cases.json}"
    ;;
  date-time-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeCoreBenchmark "${2:-../conformance/fixtures/date-time-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  relative-time-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.RelativeTimeCoreTest "${2:-../conformance/fixtures/functions/relative-time-duration-v0.json}"
    ;;
  relative-time-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.RelativeTimeCoreBenchmark "${2:-../conformance/fixtures/functions/relative-time-duration-v0.json}" "${3:-100000}" "${4:-10000}"
    ;;
  datetime-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeBoundaryDemo
    ;;
  showcase)
    echo "== catalog demo: precompiled model/resource bundle =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.TranslateDemo
    echo
    echo "== inline source demo: parser + formatter + parts/fallback =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.InlineTranslateDemo
    echo
    echo "== public API demo: result API and recovery callbacks =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.PublicApiDemo
    echo
    echo "== JDK registry demo: localized number/currency/date/time =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.JdkRegistryDemo
    echo
    echo "== date/time boundary demo: typed host values =="
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.DateTimeBoundaryDemo
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
