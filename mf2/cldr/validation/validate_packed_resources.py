#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "generator"))

from generate_datetime_data import (  # noqa: E402
    pack_date_time_resource,
    subset_date_time_resource,
)
from generate_number_data import (  # noqa: E402
    pack_number_resource,
    subset_number_resource,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate packed CLDR JS resources.")
    parser.add_argument("kind", choices=["number", "datetime"])
    parser.add_argument("json_path", type=Path)
    parser.add_argument("js_path", type=Path)
    parser.add_argument(
        "--locale-dir",
        type=Path,
        help="Optional directory containing one-locale packed JS resources.",
    )
    args = parser.parse_args(argv)

    source = json.loads(args.json_path.read_text(encoding="utf-8"))
    pack_resource = (
        pack_number_resource if args.kind == "number" else pack_date_time_resource
    )
    expected = pack_resource(source)
    actual = read_exported_json(args.js_path)
    if actual != expected:
        print(f"FAIL: packed {args.kind} resource is stale: {args.js_path}", file=sys.stderr)
        return 1
    if args.locale_dir is not None:
        failure = validate_locale_resources(args.kind, source, args.locale_dir)
        if failure is not None:
            print(f"FAIL: {failure}", file=sys.stderr)
            return 1
    print(
        f"packed {args.kind} resource validated strings={len(actual['strings'])} "
        f"locales={len(actual['locales'])}"
    )
    return 0


def validate_locale_resources(
    kind: str, source: dict[str, Any], locale_dir: Path
) -> str | None:
    locales = sorted(source["locales"])
    expected_files = {f"{locale}.js" for locale in locales}
    actual_files = {path.name for path in locale_dir.glob("*.js")}
    if actual_files != expected_files:
        missing = sorted(expected_files - actual_files)
        extra = sorted(actual_files - expected_files)
        details: list[str] = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if extra:
            details.append("extra " + ", ".join(extra))
        return f"packed {kind} locale resources do not match source locales: {'; '.join(details)}"

    if kind == "number":
        subset_resource = subset_number_resource
        pack_resource = pack_number_resource
    else:
        subset_resource = subset_date_time_resource
        pack_resource = pack_date_time_resource
    for locale in locales:
        path = locale_dir / f"{locale}.js"
        expected = pack_resource(subset_resource(source, [locale]))
        actual = read_exported_json(path)
        if actual != expected:
            return f"packed {kind} locale resource is stale for {locale}: {path}"
        if len(actual.get("locales", [])) != 1:
            return f"packed {kind} locale resource must contain one locale for {locale}: {path}"
    return None


def read_exported_json(path: Path) -> Any:
    source = path.read_text(encoding="utf-8")
    marker = " = "
    start = source.index(marker) + len(marker)
    end = source.rindex(";")
    return json.loads(source[start:end])


if __name__ == "__main__":
    raise SystemExit(main())
