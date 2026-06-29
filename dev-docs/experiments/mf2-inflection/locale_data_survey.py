#!/usr/bin/env python3
"""Survey Unicode inflection locale data availability for MF2 planning."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path


DEFAULT_UNICODE_ROOT = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection"
)
DEFAULT_CACHE_DIR = Path("/Users/ja/.cache/mf2-inflection-data")

RUNTIME_PROTOTYPE_LOCALES = {
    "ar",
    "da",
    "de",
    "es",
    "fr",
    "he",
    "hi",
    "it",
    "ml",
    "pt",
    "ru",
    "sr",
    "sv",
    "tr",
}

BLOCKED_SOURCE_DATA_LOCALES = {
    "pl": (
        "source-data-acquisition-required",
        "No locale.group.pl, dictionary_pl.lst, inflectional_pl.xml, or cached Polish data "
        "exists in the pinned local Unicode Inflection checkout used for this survey.",
    )
}


@dataclass(frozen=True)
class FileMetadata:
    path: str
    exists: bool
    byte_size: int | None = None
    sha256: str | None = None
    git_lfs_pointer: bool = False
    git_lfs_oid_sha256: str | None = None
    git_lfs_object_size: int | None = None

    def to_json(self) -> dict:
        data = {
            "path": self.path,
            "exists": self.exists,
            "gitLfsPointer": self.git_lfs_pointer,
        }
        if self.byte_size is not None:
            data["byteSize"] = self.byte_size
        if self.sha256 is not None:
            data["sha256"] = self.sha256
        if self.git_lfs_oid_sha256 is not None:
            data["gitLfsOidSha256"] = self.git_lfs_oid_sha256
        if self.git_lfs_object_size is not None:
            data["gitLfsObjectSize"] = self.git_lfs_object_size
        return data


def file_metadata(path: Path) -> FileMetadata:
    if not path.exists():
        return FileMetadata(path=str(path), exists=False)

    data = path.read_bytes()
    metadata = {
        "path": str(path),
        "exists": True,
        "byte_size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "git_lfs_pointer": False,
        "git_lfs_oid_sha256": None,
        "git_lfs_object_size": None,
    }
    text = data.decode("utf-8", errors="replace")
    if text.startswith("version https://git-lfs.github.com/spec/v1"):
        metadata["git_lfs_pointer"] = True
        for line in text.splitlines():
            if line.startswith("oid sha256:"):
                metadata["git_lfs_oid_sha256"] = line.removeprefix("oid sha256:")
            elif line.startswith("size "):
                try:
                    metadata["git_lfs_object_size"] = int(line.removeprefix("size "))
                except ValueError:
                    pass
    return FileMetadata(**metadata)


def parse_supported_locale_groups(path: Path) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or not line.startswith("locale.group."):
            continue
        key, value = line.split("=", 1)
        group = key.removeprefix("locale.group.")
        groups[group] = [locale.strip() for locale in value.split(",") if locale.strip()]
    return dict(sorted(groups.items()))


def data_state(*files: FileMetadata) -> str:
    existing = [file for file in files if file.exists]
    if not existing:
        return "missing"
    if all(file.exists and not file.git_lfs_pointer for file in files):
        return "checkout-materialized"
    if any(file.exists and not file.git_lfs_pointer for file in files):
        return "partial-materialized"
    return "checkout-lfs-pointer"


def materialized_state(
    checkout_dictionary: FileMetadata,
    checkout_inflectional: FileMetadata,
    cache_dictionary: FileMetadata,
    cache_inflectional: FileMetadata,
) -> str:
    if cache_dictionary.exists and cache_inflectional.exists:
        return "cache-materialized"
    if (
        checkout_dictionary.exists
        and checkout_inflectional.exists
        and not checkout_dictionary.git_lfs_pointer
        and not checkout_inflectional.git_lfs_pointer
    ):
        return "checkout-materialized"
    if checkout_dictionary.exists or checkout_inflectional.exists:
        return data_state(checkout_dictionary, checkout_inflectional)
    return "missing"


def locale_report(
    group: str, locales: list[str], unicode_root: Path, cache_dir: Path
) -> dict:
    dictionary_dir = unicode_root / "dictionary"
    inflection_dir = unicode_root / "inflection"
    contraction_dir = unicode_root / "contraction"

    checkout_dictionary = file_metadata(dictionary_dir / f"dictionary_{group}.lst")
    checkout_inflectional = file_metadata(dictionary_dir / f"inflectional_{group}.xml")
    cache_dictionary = file_metadata(cache_dir / f"dictionary_{group}.lst")
    cache_inflectional = file_metadata(cache_dir / f"inflectional_{group}.xml")
    supplemental = sorted(dictionary_dir.glob(f"supplemental_{group}.*"))

    runtime_status = (
        "runtime-prototype" if group in RUNTIME_PROTOTYPE_LOCALES else "not-started"
    )

    return {
        "localeGroup": group,
        "locales": locales,
        "runtimeStatus": runtime_status,
        "materializedState": materialized_state(
            checkout_dictionary,
            checkout_inflectional,
            cache_dictionary,
            cache_inflectional,
        ),
        "checkout": {
            "dictionary": checkout_dictionary.to_json(),
            "inflectional": checkout_inflectional.to_json(),
            "pronounCsv": file_metadata(inflection_dir / f"pronoun_{group}.csv").to_json(),
            "supplemental": [file_metadata(path).to_json() for path in supplemental],
            "contractionTable": file_metadata(
                contraction_dir / f"contractionExpandingTable_{group}.csv"
            ).to_json(),
        },
        "cache": {
            "dictionary": cache_dictionary.to_json(),
            "inflectional": cache_inflectional.to_json(),
        },
    }


def blocked_source_data_report(
    group: str, status: str, reason: str, locale_groups: dict[str, list[str]],
    unicode_root: Path, cache_dir: Path
) -> dict:
    dictionary_dir = unicode_root / "dictionary"
    inflection_dir = unicode_root / "inflection"
    contraction_dir = unicode_root / "contraction"

    return {
        "localeGroup": group,
        "status": status,
        "reason": reason,
        "supportedLocaleGroup": group in locale_groups,
        "locales": locale_groups.get(group, []),
        "expected": {
            "supportedLocalesKey": f"locale.group.{group}",
            "dictionary": file_metadata(dictionary_dir / f"dictionary_{group}.lst").to_json(),
            "inflectional": file_metadata(dictionary_dir / f"inflectional_{group}.xml").to_json(),
            "pronounCsv": file_metadata(inflection_dir / f"pronoun_{group}.csv").to_json(),
            "contractionTable": file_metadata(
                contraction_dir / f"contractionExpandingTable_{group}.csv"
            ).to_json(),
        },
        "cache": {
            "dictionary": file_metadata(cache_dir / f"dictionary_{group}.lst").to_json(),
            "inflectional": file_metadata(cache_dir / f"inflectional_{group}.xml").to_json(),
        },
    }


def build_report(unicode_root: Path, cache_dir: Path) -> dict:
    locale_groups = parse_supported_locale_groups(
        unicode_root / "locale" / "supported-locales.properties"
    )
    locales = [
        locale_report(group, group_locales, unicode_root, cache_dir)
        for group, group_locales in locale_groups.items()
    ]
    blocked_source_data = [
        blocked_source_data_report(group, status, reason, locale_groups, unicode_root, cache_dir)
        for group, (status, reason) in sorted(BLOCKED_SOURCE_DATA_LOCALES.items())
    ]

    return {
        "schema": "mojito-mf2-inflection/locale-data-survey/v0",
        "unicodeRoot": str(unicode_root),
        "cacheDir": str(cache_dir),
        "summary": {
            "localeGroupCount": len(locales),
            "runtimePrototypeLocaleCount": sum(
                1 for locale in locales if locale["runtimeStatus"] == "runtime-prototype"
            ),
            "cacheMaterializedLocaleCount": sum(
                1 for locale in locales if locale["materializedState"] == "cache-materialized"
            ),
            "checkoutMaterializedLocaleCount": sum(
                1
                for locale in locales
                if locale["materializedState"] == "checkout-materialized"
            ),
            "dictionaryLocaleCount": sum(
                1 for locale in locales if locale["checkout"]["dictionary"]["exists"]
            ),
            "inflectionalLocaleCount": sum(
                1 for locale in locales if locale["checkout"]["inflectional"]["exists"]
            ),
            "pronounCsvLocaleCount": sum(
                1 for locale in locales if locale["checkout"]["pronounCsv"]["exists"]
            ),
            "blockedSourceDataLocaleCount": len(blocked_source_data),
        },
        "locales": locales,
        "blockedSourceData": blocked_source_data,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unicode-root", type=Path, default=DEFAULT_UNICODE_ROOT)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--out", type=Path, help="Write JSON report to this path.")
    args = parser.parse_args()

    report = build_report(args.unicode_root, args.cache_dir)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
