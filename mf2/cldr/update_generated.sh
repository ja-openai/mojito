#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
DESTINATION_ROOT="$ROOT"
ALTERNATE_DESTINATION=0

if [ "$#" -eq 2 ] && [ "$1" = "--destination-root" ]; then
  DESTINATION_ROOT="$2"
  ALTERNATE_DESTINATION=1
elif [ "$#" -ne 0 ]; then
  echo "Usage: $0 [--destination-root DIR]" >&2
  exit 2
fi

if [ -z "$DESTINATION_ROOT" ] || [ "$DESTINATION_ROOT" = "/" ]; then
  echo "Refusing unsafe generated-file destination: $DESTINATION_ROOT" >&2
  exit 2
fi

if [ "$ALTERNATE_DESTINATION" -eq 1 ]; then
  if [ -e "$DESTINATION_ROOT" ] || [ -L "$DESTINATION_ROOT" ]; then
    echo "Alternate generated-file destination must not already exist: $DESTINATION_ROOT" >&2
    exit 2
  fi

  destination_parent="$(dirname "$DESTINATION_ROOT")"
  destination_name="$(basename "$DESTINATION_ROOT")"
  if [ ! -d "$destination_parent" ] || [ -L "$destination_parent" ]; then
    echo "Alternate generated-file destination requires an existing, non-symlink parent: $destination_parent" >&2
    exit 2
  fi

  logical_parent="$(CDPATH= cd -- "$destination_parent" && pwd -L)"
  physical_parent="$(CDPATH= cd -- "$destination_parent" && pwd -P)"
  if [ "$logical_parent" != "$physical_parent" ]; then
    echo "Alternate generated-file destination must not use symlinked parent components: $destination_parent" >&2
    exit 2
  fi

  if [ "$physical_parent" = "/" ]; then
    DESTINATION_ROOT="/$destination_name"
  else
    DESTINATION_ROOT="$physical_parent/$destination_name"
  fi
  mkdir "$DESTINATION_ROOT"
else
  DESTINATION_ROOT="$(CDPATH= cd -- "$DESTINATION_ROOT" && pwd -P)"
fi

if [ "$DESTINATION_ROOT" = "/" ]; then
  echo "Refusing unsafe generated-file destination after path resolution: $DESTINATION_ROOT" >&2
  exit 2
fi
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mojito-mf2-cldr.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

copy_generated() {
  source_file="$1"
  target_file="$2"
  mkdir -p "$(dirname "$target_file")"
  cp "$source_file" "$target_file"
}

PYTHONDONTWRITEBYTECODE=1 python3 "$ROOT/cldr/generator/generate_plural_rules.py" \
  --targets all \
  --out "$TMP_DIR/all" \
  --clean \
  --quiet

if [ "$ALTERNATE_DESTINATION" -eq 1 ]; then
  if [ -L "$DESTINATION_ROOT" ] || [ ! -d "$DESTINATION_ROOT" ]; then
    echo "Alternate generated-file destination changed before use: $DESTINATION_ROOT" >&2
    exit 2
  fi
  resolved_destination="$(CDPATH= cd -- "$DESTINATION_ROOT" && pwd -P)"
  if [ "$resolved_destination" != "$DESTINATION_ROOT" ]; then
    echo "Alternate generated-file destination resolved through a symlink before use: $DESTINATION_ROOT" >&2
    exit 2
  fi
  if [ -n "$(find "$DESTINATION_ROOT" ! -path "$DESTINATION_ROOT" -print | sed -n '1p')" ]; then
    echo "Alternate generated-file destination is no longer empty: $DESTINATION_ROOT" >&2
    exit 2
  fi
else
  rm -rf "$DESTINATION_ROOT/cldr/generated/all"
fi
mkdir -p "$DESTINATION_ROOT/cldr/generated"
cp -R "$TMP_DIR/all" "$DESTINATION_ROOT/cldr/generated/all"

copy_generated "$TMP_DIR/all/python/cldr_plural_rules.py" \
  "$DESTINATION_ROOT/python/src/mojito_mf2/_cldr_plural_rules.py"
copy_generated "$TMP_DIR/all/rust/cldr_plural_rules.rs" \
  "$DESTINATION_ROOT/rust/mojito-mf2/src/cldr_plural_rules.rs"
copy_generated "$TMP_DIR/all/swift/CldrPluralRules.swift" \
  "$DESTINATION_ROOT/swift/MessageFormat2/Sources/MessageFormat2/CldrPluralRules.swift"
copy_generated "$TMP_DIR/all/java/com/box/l10n/mojito/mf2/CldrPluralRules.java" \
  "$DESTINATION_ROOT/java/src/main/java/com/box/l10n/mojito/mf2/CldrPluralRules.java"
copy_generated "$TMP_DIR/all/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt" \
  "$DESTINATION_ROOT/kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt"
copy_generated "$TMP_DIR/all/javascript/cldr_plural_rules.js" \
  "$DESTINATION_ROOT/javascript/src/cldr_plural_rules.js"
copy_generated "$TMP_DIR/all/go/cldr_plural_rules.go" \
  "$DESTINATION_ROOT/go/cldr_plural_rules.go"
copy_generated "$TMP_DIR/all/php/CldrPluralRules.php" \
  "$DESTINATION_ROOT/php/src/CldrPluralRules.php"

gofmt -w \
  "$DESTINATION_ROOT/cldr/generated/all/go/cldr_plural_rules.go" \
  "$DESTINATION_ROOT/go/cldr_plural_rules.go"
