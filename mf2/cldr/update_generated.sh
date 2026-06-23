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

copy_generated_dir() {
  source_dir="$1"
  target_dir="$2"
  rm -rf "$target_dir"
  mkdir -p "$(dirname "$target_dir")"
  cp -R "$source_dir" "$target_dir"
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
  "$ROOT/swift/MessageFormat2/Sources/MessageFormat2/CldrPluralRules.swift"
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

PYTHONDONTWRITEBYTECODE=1 python3 "$ROOT/cldr/generator/generate_number_data.py" \
  --out "$TMP_DIR/experimental-number" \
  --javascript-runtime-out "$TMP_DIR/runtime/cldr_number_data.js" \
  --python-runtime-out "$TMP_DIR/runtime/_cldr_number_data.py" \
  --rust-runtime-out "$TMP_DIR/runtime/cldr_number_data.rs" \
  --java-runtime-out "$TMP_DIR/runtime/CldrNumberData.java" \
  --kotlin-runtime-out "$TMP_DIR/runtime/CldrNumberData.kt" \
  --swift-runtime-out "$TMP_DIR/runtime/CldrNumberData.swift" \
  --go-runtime-out "$TMP_DIR/runtime/cldr_number_data.go" \
  --php-runtime-out "$TMP_DIR/runtime/CldrNumberData.php" \
  --java-package com.box.l10n.mojito.mf2 \
  --kotlin-package com.box.l10n.mojito.mf2 \
  --clean \
  --quiet

rm -rf "$ROOT/cldr/generated/experimental-number"

copy_generated "$TMP_DIR/experimental-number/number_data.json" \
  "$ROOT/cldr/generated/experimental-number/number_data.json"
copy_generated "$TMP_DIR/experimental-number/javascript/number_data.js" \
  "$ROOT/cldr/generated/experimental-number/javascript/number_data.js"
copy_generated "$TMP_DIR/experimental-number/javascript/number_data_packed.js" \
  "$ROOT/cldr/generated/experimental-number/javascript/number_data_packed.js"
copy_generated_dir "$TMP_DIR/experimental-number/javascript/packed-locales" \
  "$ROOT/cldr/generated/experimental-number/javascript/packed-locales"
copy_generated "$TMP_DIR/experimental-number/python/mojito_mf2/_cldr_number_data.py" \
  "$ROOT/cldr/generated/experimental-number/python/mojito_mf2/_cldr_number_data.py"
copy_generated "$TMP_DIR/experimental-number/rust/cldr_number_data.rs" \
  "$ROOT/cldr/generated/experimental-number/rust/cldr_number_data.rs"
copy_generated "$TMP_DIR/experimental-number/swift/CldrNumberData.swift" \
  "$ROOT/cldr/generated/experimental-number/swift/CldrNumberData.swift"
copy_generated "$TMP_DIR/experimental-number/java/com/box/l10n/mojito/mf2/CldrNumberData.java" \
  "$ROOT/cldr/generated/experimental-number/java/com/box/l10n/mojito/mf2/CldrNumberData.java"
copy_generated "$TMP_DIR/experimental-number/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt" \
  "$ROOT/cldr/generated/experimental-number/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt"
copy_generated "$TMP_DIR/experimental-number/go/cldr_number_data.go" \
  "$ROOT/cldr/generated/experimental-number/go/cldr_number_data.go"
copy_generated "$TMP_DIR/experimental-number/php/CldrNumberData.php" \
  "$ROOT/cldr/generated/experimental-number/php/CldrNumberData.php"

copy_generated "$TMP_DIR/runtime/cldr_number_data.js" \
  "$ROOT/javascript/src/cldr_number_data.js"
copy_generated "$TMP_DIR/runtime/_cldr_number_data.py" \
  "$ROOT/python/src/mojito_mf2/_cldr_number_data.py"
copy_generated "$TMP_DIR/runtime/cldr_number_data.rs" \
  "$ROOT/rust/mojito-mf2/src/cldr_number_data.rs"
copy_generated "$TMP_DIR/runtime/CldrNumberData.swift" \
  "$ROOT/swift/MessageFormat2/Sources/MessageFormat2/CldrNumberData.swift"
copy_generated "$TMP_DIR/runtime/CldrNumberData.java" \
  "$ROOT/java/src/main/java/com/box/l10n/mojito/mf2/CldrNumberData.java"
copy_generated "$TMP_DIR/runtime/CldrNumberData.kt" \
  "$ROOT/kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrNumberData.kt"
copy_generated "$TMP_DIR/runtime/cldr_number_data.go" \
  "$ROOT/go/cldr_number_data.go"
copy_generated "$TMP_DIR/runtime/CldrNumberData.php" \
  "$ROOT/php/src/CldrNumberData.php"

rustfmt \
  "$ROOT/cldr/generated/experimental-number/rust/cldr_number_data.rs" \
  "$ROOT/rust/mojito-mf2/src/cldr_number_data.rs"

gofmt -w \
  "$ROOT/cldr/generated/experimental-number/go/cldr_number_data.go" \
  "$ROOT/go/cldr_number_data.go"

PYTHONDONTWRITEBYTECODE=1 python3 "$ROOT/cldr/generator/generate_datetime_data.py" \
  --out "$TMP_DIR/experimental-datetime" \
  --javascript-runtime-out "$TMP_DIR/runtime/cldr_date_time_data.js" \
  --python-runtime-out "$TMP_DIR/runtime/_cldr_date_time_data.py" \
  --rust-runtime-out "$TMP_DIR/runtime/cldr_date_time_data.rs" \
  --java-runtime-out "$TMP_DIR/runtime/CldrDateTimeData.java" \
  --kotlin-runtime-out "$TMP_DIR/runtime/CldrDateTimeData.kt" \
  --swift-runtime-out "$TMP_DIR/runtime/CldrDateTimeData.swift" \
  --go-runtime-out "$TMP_DIR/runtime/cldr_date_time_data.go" \
  --php-runtime-out "$TMP_DIR/runtime/CldrDateTimeData.php" \
  --java-package com.box.l10n.mojito.mf2 \
  --kotlin-package com.box.l10n.mojito.mf2 \
  --clean \
  --quiet

rm -rf "$ROOT/cldr/generated/experimental-datetime"

copy_generated "$TMP_DIR/experimental-datetime/date_time_data.json" \
  "$ROOT/cldr/generated/experimental-datetime/date_time_data.json"
copy_generated "$TMP_DIR/experimental-datetime/skeleton_coverage.json" \
  "$ROOT/cldr/generated/experimental-datetime/skeleton_coverage.json"
copy_generated "$TMP_DIR/experimental-datetime/javascript/date_time_data.js" \
  "$ROOT/cldr/generated/experimental-datetime/javascript/date_time_data.js"
copy_generated "$TMP_DIR/experimental-datetime/javascript/date_time_data_packed.js" \
  "$ROOT/cldr/generated/experimental-datetime/javascript/date_time_data_packed.js"
copy_generated_dir "$TMP_DIR/experimental-datetime/javascript/packed-locales" \
  "$ROOT/cldr/generated/experimental-datetime/javascript/packed-locales"
copy_generated "$TMP_DIR/experimental-datetime/python/mojito_mf2/_cldr_date_time_data.py" \
  "$ROOT/cldr/generated/experimental-datetime/python/mojito_mf2/_cldr_date_time_data.py"
copy_generated "$TMP_DIR/experimental-datetime/rust/cldr_date_time_data.rs" \
  "$ROOT/cldr/generated/experimental-datetime/rust/cldr_date_time_data.rs"
copy_generated "$TMP_DIR/experimental-datetime/swift/CldrDateTimeData.swift" \
  "$ROOT/cldr/generated/experimental-datetime/swift/CldrDateTimeData.swift"
copy_generated "$TMP_DIR/experimental-datetime/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java" \
  "$ROOT/cldr/generated/experimental-datetime/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java"
copy_generated "$TMP_DIR/experimental-datetime/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt" \
  "$ROOT/cldr/generated/experimental-datetime/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt"
copy_generated "$TMP_DIR/experimental-datetime/go/cldr_date_time_data.go" \
  "$ROOT/cldr/generated/experimental-datetime/go/cldr_date_time_data.go"
copy_generated "$TMP_DIR/experimental-datetime/php/CldrDateTimeData.php" \
  "$ROOT/cldr/generated/experimental-datetime/php/CldrDateTimeData.php"

copy_generated "$TMP_DIR/runtime/cldr_date_time_data.js" \
  "$ROOT/javascript/src/cldr_date_time_data.js"
copy_generated "$TMP_DIR/runtime/_cldr_date_time_data.py" \
  "$ROOT/python/src/mojito_mf2/_cldr_date_time_data.py"
copy_generated "$TMP_DIR/runtime/cldr_date_time_data.rs" \
  "$ROOT/rust/mojito-mf2/src/cldr_date_time_data.rs"
copy_generated "$TMP_DIR/runtime/CldrDateTimeData.swift" \
  "$ROOT/swift/MessageFormat2/Sources/MessageFormat2/CldrDateTimeData.swift"
copy_generated "$TMP_DIR/runtime/CldrDateTimeData.java" \
  "$ROOT/java/src/main/java/com/box/l10n/mojito/mf2/CldrDateTimeData.java"
copy_generated "$TMP_DIR/runtime/CldrDateTimeData.kt" \
  "$ROOT/kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrDateTimeData.kt"
copy_generated "$TMP_DIR/runtime/cldr_date_time_data.go" \
  "$ROOT/go/cldr_date_time_data.go"
copy_generated "$TMP_DIR/runtime/CldrDateTimeData.php" \
  "$ROOT/php/src/CldrDateTimeData.php"

gofmt -w \
  "$ROOT/cldr/generated/experimental-datetime/go/cldr_date_time_data.go" \
  "$ROOT/go/cldr_date_time_data.go"
