#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

PYTHONDONTWRITEBYTECODE=1 python3 validation/check_size_gates.py
