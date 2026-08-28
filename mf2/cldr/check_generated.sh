#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$ROOT/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mojito-mf2-cldr-check.XXXXXX")"
TMP_DIR="$(CDPATH= cd -- "$TMP_DIR" && pwd -P)"
trap 'rm -rf "$TMP_DIR"' EXIT

EXPECTED_ROOT="$TMP_DIR/mf2"
EXPECTED_PATHS="$TMP_DIR/expected-paths"
WORKTREE_PATHS="$TMP_DIR/worktree-paths"
INDEX_PATHS="$TMP_DIR/index-paths"
STAGED_ROOT="$TMP_DIR/staged"
STATUS=0

sh "$ROOT/cldr/update_generated.sh" --destination-root "$EXPECTED_ROOT"

(
  cd "$EXPECTED_ROOT"
  find . -type f -print | sed 's#^\./#mf2/#'
) | LC_ALL=C sort -u >"$EXPECTED_PATHS"

{
  if [ -d "$REPO_ROOT/mf2/cldr/generated/all" ]; then
    (
      cd "$REPO_ROOT"
      find mf2/cldr/generated/all ! -type d -print
    )
  fi
  while IFS= read -r path; do
    case "$path" in
      mf2/cldr/generated/all/*) ;;
      *)
        if [ -e "$REPO_ROOT/$path" ] || [ -L "$REPO_ROOT/$path" ]; then
          printf '%s\n' "$path"
        fi
        ;;
    esac
  done <"$EXPECTED_PATHS"
} | LC_ALL=C sort -u >"$WORKTREE_PATHS"

{
  git -C "$REPO_ROOT" ls-files -- mf2/cldr/generated/all
  while IFS= read -r path; do
    case "$path" in
      mf2/cldr/generated/all/*) ;;
      *) git -C "$REPO_ROOT" ls-files -- "$path" ;;
    esac
  done <"$EXPECTED_PATHS"
} | LC_ALL=C sort -u >"$INDEX_PATHS"

report_missing_paths() {
  expected_paths="$1"
  actual_paths="$2"
  location="$3"

  while IFS= read -r path; do
    if ! grep -Fqx -- "$path" "$actual_paths"; then
      echo "Generated CLDR path is missing from the $location: $path" >&2
      STATUS=1
    fi
  done <"$expected_paths"
}

report_extra_paths() {
  expected_paths="$1"
  actual_paths="$2"
  location="$3"

  while IFS= read -r path; do
    if ! grep -Fqx -- "$path" "$expected_paths"; then
      echo "Unexpected generated CLDR path in the $location: $path" >&2
      STATUS=1
    fi
  done <"$actual_paths"
}

report_missing_paths "$EXPECTED_PATHS" "$WORKTREE_PATHS" "working tree"
report_extra_paths "$EXPECTED_PATHS" "$WORKTREE_PATHS" "working tree"
report_missing_paths "$EXPECTED_PATHS" "$INDEX_PATHS" "Git index"
report_extra_paths "$EXPECTED_PATHS" "$INDEX_PATHS" "Git index"

while IFS= read -r repo_path; do
  expected_file="$EXPECTED_ROOT/${repo_path#mf2/}"
  worktree_file="$REPO_ROOT/$repo_path"

  if [ -f "$worktree_file" ] && [ ! -L "$worktree_file" ]; then
    if ! cmp -s "$expected_file" "$worktree_file"; then
      echo "Generated CLDR file is stale in the working tree: $repo_path" >&2
      STATUS=1
    fi
  elif [ -e "$worktree_file" ] || [ -L "$worktree_file" ]; then
    echo "Generated CLDR destination is not a regular file: $repo_path" >&2
    STATUS=1
  fi

  index_entry="$(git -C "$REPO_ROOT" ls-files --stage -- "$repo_path")"
  if [ -n "$index_entry" ]; then
    index_mode="${index_entry%% *}"
    if [ "$index_mode" != "100644" ]; then
      echo "Generated CLDR file has unexpected Git mode $index_mode: $repo_path" >&2
      STATUS=1
    fi

    staged_file="$STAGED_ROOT/$repo_path"
    mkdir -p "$(dirname "$staged_file")"
    if git -C "$REPO_ROOT" show ":$repo_path" >"$staged_file"; then
      if ! cmp -s "$expected_file" "$staged_file"; then
        echo "Generated CLDR file is stale in the Git index: $repo_path" >&2
        STATUS=1
      fi
    else
      echo "Generated CLDR file cannot be read from the Git index: $repo_path" >&2
      STATUS=1
    fi
  fi
done <"$EXPECTED_PATHS"

if [ "$STATUS" -ne 0 ]; then
  echo "Regenerate with: sh mf2/cldr/update_generated.sh" >&2
  exit 1
fi

echo "Generated CLDR plural sources are current and tracked."
