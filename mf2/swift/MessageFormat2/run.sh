#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

command="${1:-build}"
shift || true

case "$command" in
  build)
    swift build \
      --disable-sandbox \
      --cache-path .build/cache \
      --config-path .build/config \
      --security-path .build/security \
      --manifest-cache local \
      "$@"
    ;;
  run)
    swift run \
      --disable-sandbox \
      --cache-path .build/cache \
      --config-path .build/config \
      --security-path .build/security \
      --manifest-cache local \
      "$@"
    ;;
  *)
    echo "unknown command: $command" >&2
    exit 2
    ;;
esac
