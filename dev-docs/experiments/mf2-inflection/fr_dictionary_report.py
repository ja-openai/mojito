#!/usr/bin/env python3
"""Generate a compact report from Unicode-style French dictionary files."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


NUMBER_GRAMMEMES = {"singular", "plural", "dual"}
GENDER_GRAMMEMES = {"masculine", "feminine", "neuter"}
PART_OF_SPEECH_GRAMMEMES = {
    "noun",
    "proper-noun",
    "adjective",
    "adverb",
    "verb",
    "numeral",
}
KNOWN_FLAGS = {
    "abbreviation",
    "animate",
    "inanimate",
    "rare",
    "vowel-start",
}
METADATA_PREFIXES = (
    "Copyright ",
    "Manually curated ",
    "generated with options",
)


@dataclass(frozen=True)
class DictionaryEntry:
    source: str
    line: int
    surface: str
    normalized_surface: str
    grammemes: tuple[str, ...]
    inflections: tuple[str, ...]

    def genders(self) -> tuple[str, ...]:
        return tuple(grammeme for grammeme in self.grammemes if grammeme in GENDER_GRAMMEMES)

    def numbers(self) -> tuple[str, ...]:
        return tuple(grammeme for grammeme in self.grammemes if grammeme in NUMBER_GRAMMEMES)

    def parts_of_speech(self) -> tuple[str, ...]:
        return tuple(grammeme for grammeme in self.grammemes if grammeme in PART_OF_SPEECH_GRAMMEMES)

    def flags(self) -> tuple[str, ...]:
        return tuple(grammeme for grammeme in self.grammemes if grammeme in KNOWN_FLAGS)


def normalize_surface(surface: str) -> str:
    return surface.strip().lower().replace("’", "'")


def source_metadata(path: Path) -> dict:
    data = path.read_bytes()
    metadata = {
        "path": str(path),
        "byteSize": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "gitLfsPointer": False,
    }
    text = data.decode("utf-8", errors="replace")
    if text.startswith("version https://git-lfs.github.com/spec/v1"):
        metadata["gitLfsPointer"] = True
        for line in text.splitlines():
            if line.startswith("oid sha256:"):
                metadata["gitLfsOidSha256"] = line.removeprefix("oid sha256:")
            elif line.startswith("size "):
                try:
                    metadata["gitLfsObjectSize"] = int(line.removeprefix("size "))
                except ValueError:
                    metadata["gitLfsObjectSize"] = line.removeprefix("size ")
    return metadata


def parse_dictionary(path: Path) -> tuple[list[DictionaryEntry], list[dict]]:
    entries: list[DictionaryEntry] = []
    skipped: list[dict] = []
    in_footer = False

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            skipped.append({"source": str(path), "line": line_number, "reason": "blank"})
            continue
        if line.startswith("version https://git-lfs.github.com/spec/v1"):
            skipped.append({"source": str(path), "line": line_number, "reason": "git-lfs-pointer"})
            in_footer = True
            continue
        if line.startswith("==="):
            skipped.append({"source": str(path), "line": line_number, "reason": "footer-separator"})
            in_footer = True
            continue
        if in_footer:
            skipped.append({"source": str(path), "line": line_number, "reason": "footer"})
            continue
        if line.startswith("#"):
            skipped.append({"source": str(path), "line": line_number, "reason": "comment"})
            continue
        if any(line.startswith(prefix) for prefix in METADATA_PREFIXES):
            skipped.append({"source": str(path), "line": line_number, "reason": "metadata"})
            continue
        if ":" not in line:
            skipped.append({"source": str(path), "line": line_number, "reason": "missing-colon", "value": line})
            continue

        surface, raw_grammemes = line.split(":", 1)
        surface = surface.strip()
        tokens = tuple(token for token in raw_grammemes.strip().split() if token)
        if not surface or not tokens:
            skipped.append({"source": str(path), "line": line_number, "reason": "empty-surface-or-features", "value": line})
            continue

        grammemes = []
        inflections = []
        for token in tokens:
            if token.startswith("inflection="):
                inflections.append(token.removeprefix("inflection="))
            else:
                grammemes.append(token)

        entries.append(
            DictionaryEntry(
                source=str(path),
                line=line_number,
                surface=surface,
                normalized_surface=normalize_surface(surface),
                grammemes=tuple(grammemes),
                inflections=tuple(inflections),
            )
        )

    return entries, skipped


def compact_entry(entry: DictionaryEntry) -> dict:
    return {
        "surface": entry.surface,
        "source": entry.source,
        "line": entry.line,
        "gender": list(entry.genders()),
        "number": list(entry.numbers()),
        "partOfSpeech": list(entry.parts_of_speech()),
        "flags": list(entry.flags()),
        "inflections": list(entry.inflections),
        "grammemes": list(entry.grammemes),
    }


def analysis_signature(entry: DictionaryEntry) -> tuple:
    return (
        tuple(sorted(entry.genders())),
        tuple(sorted(entry.numbers())),
        tuple(sorted(entry.parts_of_speech())),
        tuple(sorted(entry.flags())),
        tuple(sorted(entry.inflections)),
    )


def ambiguity_reasons(entries: list[DictionaryEntry]) -> list[str]:
    signatures = {analysis_signature(entry) for entry in entries}
    genders = {gender for entry in entries for gender in entry.genders()}
    numbers = {number for entry in entries for number in entry.numbers()}
    poses = {pos for entry in entries for pos in entry.parts_of_speech()}
    flags = {flag for entry in entries for flag in entry.flags()}
    inflections = {inflection for entry in entries for inflection in entry.inflections}

    reasons: list[str] = []
    if len(signatures) > 1:
        reasons.append("multiple-analyses")
    if len(genders) > 1 or any(len(entry.genders()) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if len(numbers) > 1 or any(len(entry.numbers()) > 1 for entry in entries):
        reasons.append("multiple-numbers")
    if len(poses) > 1 or any(len(entry.parts_of_speech()) > 1 for entry in entries):
        reasons.append("multiple-parts-of-speech")
    if len(flags) > 1 or any(len(entry.flags()) > 1 for entry in entries):
        reasons.append("multiple-flags")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")
    return reasons


def count_values(entries: Iterable[DictionaryEntry], extractor) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for entry in entries:
        values = extractor(entry)
        if not values:
            counter["<missing>"] += 1
        else:
            counter.update(values)
    return dict(sorted(counter.items()))


def build_report(paths: list[Path], max_samples: int) -> dict:
    all_entries: list[DictionaryEntry] = []
    all_skipped: list[dict] = []

    for path in paths:
        entries, skipped = parse_dictionary(path)
        all_entries.extend(entries)
        all_skipped.extend(skipped)

    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in all_entries:
        by_surface[entry.normalized_surface].append(entry)

    ambiguous_surfaces = []
    ambiguity_reason_counts: Counter[str] = Counter()
    for surface, entries in sorted(by_surface.items()):
        reasons = ambiguity_reasons(entries)
        if reasons:
            ambiguity_reason_counts.update(reasons)
            genders = {gender for entry in entries for gender in entry.genders()}
            numbers = {number for entry in entries for number in entry.numbers()}
            poses = {pos for entry in entries for pos in entry.parts_of_speech()}
            ambiguous_surfaces.append(
                {
                    "surface": surface,
                    "reasons": reasons,
                    "genders": sorted(genders),
                    "numbers": sorted(numbers),
                    "partsOfSpeech": sorted(poses),
                    "entries": [compact_entry(entry) for entry in entries[:max_samples]],
                }
            )

    unknown_grammemes = Counter()
    known = NUMBER_GRAMMEMES | GENDER_GRAMMEMES | PART_OF_SPEECH_GRAMMEMES | KNOWN_FLAGS
    for entry in all_entries:
        unknown_grammemes.update(grammeme for grammeme in entry.grammemes if grammeme not in known)

    return {
        "schema": "mojito-mf2-inflection/fr-dictionary-report/v0",
        "locale": "fr",
        "sources": [source_metadata(path) for path in paths],
        "counts": {
            "entries": len(all_entries),
            "uniqueSurfaces": len(by_surface),
            "ambiguousSurfaces": len(ambiguous_surfaces),
            "skippedLines": len(all_skipped),
        },
        "features": {
            "gender": count_values(all_entries, DictionaryEntry.genders),
            "number": count_values(all_entries, DictionaryEntry.numbers),
            "partOfSpeech": count_values(all_entries, DictionaryEntry.parts_of_speech),
            "flags": count_values(all_entries, DictionaryEntry.flags),
            "unknownGrammemes": dict(sorted(unknown_grammemes.items())),
            "ambiguityReasons": dict(sorted(ambiguity_reason_counts.items())),
        },
        "samples": {
            "entries": [compact_entry(entry) for entry in all_entries[:max_samples]],
            "ambiguousSurfaces": ambiguous_surfaces[:max_samples],
            "skippedLines": all_skipped[:max_samples],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", action="append", required=True, type=Path, help="Unicode-style dictionary .lst file. Can be repeated.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    parser.add_argument("--max-samples", type=int, default=20)
    args = parser.parse_args()

    report = build_report(args.dictionary, args.max_samples)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
