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
  mvn_cmd -q -f ../kotlin/pom.xml install -DskipTests
  mvn_cmd -q test-compile dependency:build-classpath \
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

command="${1:-demo}"
tool_classpath="$(classpath)"
case "$command" in
  demo)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.icu4j.KotlinIcu4jRegistryDemo
    ;;
  check)
    java -cp "$tool_classpath" com.box.l10n.mojito.mf2.icu4j.KotlinIcu4jRegistryDemo --quiet
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
