#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
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

rm -rf "$ROOT/cldr/generated/all"

copy_generated "$TMP_DIR/all/plural_rules.json" \
  "$ROOT/cldr/generated/all/plural_rules.json"
copy_generated "$TMP_DIR/all/python/cldr_plural_rules.py" \
  "$ROOT/cldr/generated/all/python/cldr_plural_rules.py"
copy_generated "$TMP_DIR/all/rust/cldr_plural_rules.rs" \
  "$ROOT/cldr/generated/all/rust/cldr_plural_rules.rs"
copy_generated "$TMP_DIR/all/swift/CldrPluralRules.swift" \
  "$ROOT/cldr/generated/all/swift/CldrPluralRules.swift"
copy_generated "$TMP_DIR/all/java/com/box/l10n/mojito/mf2/CldrPluralRules.java" \
  "$ROOT/cldr/generated/all/java/com/box/l10n/mojito/mf2/CldrPluralRules.java"
copy_generated "$TMP_DIR/all/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt" \
  "$ROOT/cldr/generated/all/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt"
copy_generated "$TMP_DIR/all/javascript/cldr_plural_rules.js" \
  "$ROOT/cldr/generated/all/javascript/cldr_plural_rules.js"
copy_generated "$TMP_DIR/all/go/cldr_plural_rules.go" \
  "$ROOT/cldr/generated/all/go/cldr_plural_rules.go"
copy_generated "$TMP_DIR/all/php/CldrPluralRules.php" \
  "$ROOT/cldr/generated/all/php/CldrPluralRules.php"

copy_generated "$TMP_DIR/all/python/cldr_plural_rules.py" \
  "$ROOT/python/src/mojito_mf2/_cldr_plural_rules.py"
copy_generated "$TMP_DIR/all/rust/cldr_plural_rules.rs" \
  "$ROOT/rust/mojito-mf2/src/cldr_plural_rules.rs"
copy_generated "$TMP_DIR/all/swift/CldrPluralRules.swift" \
  "$ROOT/swift/MessageFormat2/Sources/MessageFormat2Runtime/CldrPluralRules.swift"
copy_generated "$TMP_DIR/all/java/com/box/l10n/mojito/mf2/CldrPluralRules.java" \
  "$ROOT/java/src/main/java/com/box/l10n/mojito/mf2/CldrPluralRules.java"
copy_generated "$TMP_DIR/all/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt" \
  "$ROOT/kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt"
copy_generated "$TMP_DIR/all/javascript/cldr_plural_rules.js" \
  "$ROOT/javascript/src/cldr_plural_rules.js"
copy_generated "$TMP_DIR/all/go/cldr_plural_rules.go" \
  "$ROOT/go/cldr_plural_rules.go"
copy_generated "$TMP_DIR/all/php/CldrPluralRules.php" \
  "$ROOT/php/src/CldrPluralRules.php"

gofmt -w \
  "$ROOT/cldr/generated/all/go/cldr_plural_rules.go" \
  "$ROOT/go/cldr_plural_rules.go"
