#!/usr/bin/env sh
# Batch -exec with + propagates a failing child exit status through find.
set -eu
ROOT="${1:-$(dirname "$0")/php}"
find "$ROOT/src" "$ROOT/tests" "$ROOT/examples" -type f -name '*.php' -exec sh -c '
  for path do
    php -l "$path" || exit 1
  done
' sh {} +
