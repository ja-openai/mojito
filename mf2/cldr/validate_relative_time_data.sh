#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

python3 validation/validate_relative_time_data.py generated/relative-time/all/relative_time.json
