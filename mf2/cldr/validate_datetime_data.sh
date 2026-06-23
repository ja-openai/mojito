#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
python3 validation/validate_datetime_data.py generated/experimental-datetime/date_time_data.json
python3 validation/validate_datetime_skeleton_coverage.py \
  --require-no-unsupported-fields \
  generated/experimental-datetime/date_time_data.json \
  generated/experimental-datetime/skeleton_coverage.json
python3 validation/compare_intl_datetime_data.py generated/experimental-datetime/date_time_data.json
python3 validation/validate_packed_resources.py \
  datetime \
  generated/experimental-datetime/date_time_data.json \
  generated/experimental-datetime/javascript/date_time_data_packed.js \
  --locale-dir generated/experimental-datetime/javascript/packed-locales
