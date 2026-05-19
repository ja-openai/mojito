#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

PYTHONDONTWRITEBYTECODE=1 python3 validation/compare_icu4j_plural_rules.py \
  --generated-json generated/all/plural_rules.json \
  --generated-python generated/all/python/plural_rules.py
