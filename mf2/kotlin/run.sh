#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

mvn_cmd() {
  if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then
    mvn -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" "$@"
  else
    mvn "$@"
  fi
}

build() {
  mvn_cmd -q clean test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/classpath.txt \
    -Dmdep.pathSeparator=:
}

classpath() {
  printf 'target/test-classes:target/classes:%s' "$(cat target/classpath.txt)"
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
tool_classpath="$(classpath)"
case "$command" in
  conformance)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinConformance "${2:-../conformance/fixtures/source-to-model}"
    ;;
  bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinBenchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  bench-parse)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinParseBenchmark "${2:-../conformance/fixtures/source-to-model}" "${3:-100000}" "${4:-10000}"
    ;;
  demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinTranslateDemo
    ;;
  jdk-demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinJdkRegistryDemo
    ;;
  jdk-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinJdkRegistryDemo --quiet
    ;;
  number-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinNumberCoreTest "${2:-../conformance/fixtures/number-core/cases.json}"
    ;;
  number-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinNumberCoreBenchmark "${2:-../conformance/fixtures/number-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  date-time-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinDateTimeCoreTest "${2:-../conformance/fixtures/date-time-core/cases.json}"
    ;;
  date-time-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinDateTimeCoreBenchmark "${2:-../conformance/fixtures/date-time-core/cases.json}" "${3:-100000}" "${4:-10000}"
    ;;
  relative-time-core-check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinRelativeTimeCoreTest "${2:-../conformance/fixtures/functions/relative-time-duration-v0.json}"
    ;;
  relative-time-core-bench)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.KotlinRelativeTimeCoreBenchmark "${2:-../conformance/fixtures/functions/relative-time-duration-v0.json}" "${3:-100000}" "${4:-10000}"
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
