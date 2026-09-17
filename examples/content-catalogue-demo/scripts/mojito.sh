#!/usr/bin/env bash
set -euo pipefail

example_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
action="${1:-help}"
case "$action" in
  generate) node "$example_dir/scripts/generate.mjs"; exit 0 ;;
  create|push|seed|pull) ;;
  help|--help|-h)
    cat <<'USAGE'
Usage: MOJITO_BIN=/path/to/java-cli-wrapper bash scripts/mojito.sh ACTION
  generate  Recreate the ignored .generated/ English and French fixtures.
  create    Create a dedicated repository, with en source and fr target.
  push      Generate and push all 180 MDX assets using the real Java CLI.
  seed      Import 72 partial French demonstration fixtures (optional).
  pull      Export French MDX to localized/ using the real Java CLI.

MOJITO_REPO defaults to content-catalogue-demo. Use a dedicated development
repository: push removes assets omitted from this generated source tree.
The CLI wrapper supplies server/authentication. No server is started here.
USAGE
    exit 0 ;;
  *) printf 'Unknown action: %s\n' "$action" >&2; exit 2 ;;
esac

: "${MOJITO_BIN:?Set MOJITO_BIN to the Java CLI wrapper for your development server}"
repo_name="${MOJITO_REPO:-content-catalogue-demo}"
case "$action" in
  create) "$MOJITO_BIN" repo-create -n "$repo_name" -sl en -l fr ;;
  push)
    node "$example_dir/scripts/generate.mjs"
    "$MOJITO_BIN" push -r "$repo_name" -s "$example_dir/.generated/content" -ft MDX
    ;;
  seed)
    test -f "$example_dir/.generated/manifest.json" || node "$example_dir/scripts/generate.mjs"
    "$MOJITO_BIN" import -r "$repo_name" -s "$example_dir/.generated/seed-content" \
      -t "$example_dir/.generated/translations" -ft MDX -lm fr:fr -lmt MAP_ONLY
    ;;
  pull)
    test -f "$example_dir/.generated/manifest.json" || node "$example_dir/scripts/generate.mjs"
    mkdir -p "$example_dir/localized"
    "$MOJITO_BIN" pull -r "$repo_name" -s "$example_dir/.generated/content" \
      -t "$example_dir/localized" -ft MDX -lm fr:fr -lmt MAP_ONLY
    ;;
esac
