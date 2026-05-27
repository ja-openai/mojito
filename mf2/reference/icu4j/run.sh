#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

MAIN_CLASS="com.box.l10n.mojito.mf2.reference.icu4j.Icu4jReference"
CLASSPATH_FILE="target/classpath.txt"

prepare() {
  mvn -q \
    -DincludeScope=runtime \
    -Dmdep.outputFile="${CLASSPATH_FILE}" \
    dependency:build-classpath \
    compile
}

if [ "${1:-}" = "--prepare-only" ]; then
  prepare
  exit 0
fi

if [ "${1:-}" = "--no-prepare" ]; then
  shift
else
  prepare
fi

java -cp "target/classes:$(cat "${CLASSPATH_FILE}")" "${MAIN_CLASS}" "$@"
