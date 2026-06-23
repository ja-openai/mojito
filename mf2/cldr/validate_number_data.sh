#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
python3 validation/validate_number_data.py generated/experimental-number/number_data.json
python3 validation/compare_intl_number_data.py generated/experimental-number/number_data.json
python3 validation/validate_packed_resources.py \
  number \
  generated/experimental-number/number_data.json \
  generated/experimental-number/javascript/number_data_packed.js \
  --locale-dir generated/experimental-number/javascript/packed-locales
