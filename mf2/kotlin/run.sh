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
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
