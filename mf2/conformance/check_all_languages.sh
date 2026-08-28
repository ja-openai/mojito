#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
FIXTURES="${1:-$ROOT/conformance/fixtures/source-to-model}"
if [ ! -d "$FIXTURES" ]; then
    printf 'Conformance fixture directory does not exist: %s\n' "$FIXTURES" >&2
    exit 1
fi
FIXTURES="$(CDPATH= cd -- "$FIXTURES" && pwd)"

python3 -c '
import json
import sys
from pathlib import Path

paths = sorted(Path(sys.argv[1]).glob("*.json"))
format_cases = sum(
    len(json.loads(path.read_text(encoding="utf-8")).get("formatCases", []))
    for path in paths
)
if not paths or not format_cases:
    raise SystemExit(
        "Conformance fixture suite must contain at least one source model and one "
        f"format case (found {len(paths)} source models and {format_cases} format cases)."
    )
' "$FIXTURES"

(cd "$ROOT/rust/mojito-mf2" && cargo run -- conformance "$FIXTURES")
(cd "$ROOT/swift/MessageFormat2" && swift run MessageFormat2Conformance "$FIXTURES")
(cd "$ROOT/python" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/java" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/kotlin" && sh run.sh conformance "$FIXTURES")
(cd "$ROOT/javascript" && node tools/conformance.js "$FIXTURES")
(cd "$ROOT/go" && env MF2_CONFORMANCE_FIXTURES="$FIXTURES" GOPATH="${GOPATH:-/private/tmp/mojito-mf2-go-gopath-conformance}" GOMODCACHE="${GOMODCACHE:-/private/tmp/mojito-mf2-go-modcache-conformance}" GOCACHE="${GOCACHE:-/private/tmp/mojito-mf2-go-cache-conformance}" GOTOOLCHAIN="${GOTOOLCHAIN:-local}" go test ./...)
(cd "$ROOT/php" && php tests/conformance.php "$FIXTURES")
