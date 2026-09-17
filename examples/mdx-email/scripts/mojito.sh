#!/usr/bin/env bash
set -euo pipefail
demo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${MOJITO_BIN:?Set MOJITO_BIN to your configured Java CLI wrapper}"
repo_name="${MOJITO_REPO:-EmailDemo}"
case "${1:-help}" in
  push) "$MOJITO_BIN" push -r "$repo_name" -s "$demo_dir/content" -ft MDX ;;
  pull)
    mkdir -p "$demo_dir/localized"
    "$MOJITO_BIN" pull -r "$repo_name" -s "$demo_dir/content" -t "$demo_dir/localized" -ft MDX -lm fr:fr,de:de,es:es,ja:ja,ar:ar -lmt MAP_ONLY
    ;;
  *) echo 'Usage: MOJITO_BIN=/path/to/mojito MOJITO_REPO=EmailDemo bash scripts/mojito.sh push|pull'; exit 2 ;;
esac
