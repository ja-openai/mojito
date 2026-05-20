#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
python3 validation/validate_number_data.py generated/experimental-number/number_data.json
