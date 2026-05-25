#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$ROOT/.." && pwd)"

sh "$ROOT/cldr/update_generated.sh"

GENERATED_PATHS="
mf2/cldr/generated/all
mf2/python/src/mojito_mf2/_cldr_plural_rules.py
mf2/rust/mojito-mf2/src/cldr_plural_rules.rs
mf2/swift/MessageFormat2/Sources/MessageFormat2Runtime/CldrPluralRules.swift
mf2/java/src/main/java/com/box/l10n/mojito/mf2/CldrPluralRules.java
mf2/kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/CldrPluralRules.kt
mf2/javascript/src/cldr_plural_rules.js
mf2/go/cldr_plural_rules.go
mf2/php/src/CldrPluralRules.php
"

if ! git -C "$REPO_ROOT" diff --quiet -- $GENERATED_PATHS; then
  echo "Generated CLDR plural sources are stale. Run: sh mf2/cldr/update_generated.sh" >&2
  git -C "$REPO_ROOT" diff --stat -- $GENERATED_PATHS >&2
  exit 1
fi
