#!/usr/bin/env bash
set -euo pipefail

example_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
action="${1:-help}"

case "$action" in
  create|push|seed|pull) ;;
  help|--help|-h)
    cat <<'USAGE'
Usage: MOJITO_BIN=/absolute/path/to/java-cli-wrapper bash scripts/mojito.sh ACTION

  create   Create the example repository with English source and French target.
  push     Upload every MDX page/module and MF2 JSON catalog in content/.
  seed     Import the checked-in French translations into the example repository.
  pull     Export French MDX and MF2 JSON into localized/ for npm run build.

The wrapper determines the server and authentication. Use a development server
with external asset-content and pollable-task blob storage. MOJITO_REPO defaults to
mdx-content-site. The seed action writes translations; it is optional when using
the normal Mojito translation/review UI.
USAGE
    exit 0
    ;;
  *) printf 'Unknown action: %s\n' "$action" >&2; exit 2 ;;
esac

: "${MOJITO_BIN:?Set MOJITO_BIN to the Java CLI wrapper for your development server}"
repo_name="${MOJITO_REPO:-mdx-content-site}"

case "$action" in
  create)
    "$MOJITO_BIN" repo-create -n "$repo_name" -sl en -l fr -it json:MF2
    ;;
  push)
    "$MOJITO_BIN" push -r "$repo_name" -s "$example_dir/content" -ft MDX JSON
    ;;
  seed)
    "$MOJITO_BIN" import -r "$repo_name" -s "$example_dir/content" \
      -t "$example_dir/translations" -ft MDX JSON -lm fr:fr -lmt MAP_ONLY
    ;;
  pull)
    mkdir -p "$example_dir/localized"
    "$MOJITO_BIN" pull -r "$repo_name" -s "$example_dir/content" \
      -t "$example_dir/localized" -ft MDX JSON -lm fr:fr -lmt MAP_ONLY
    ;;
esac
