#!/usr/bin/env python3
"""Estimate compact French noun lookup packs from Unicode dictionary data."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from fr_dictionary_report import DictionaryEntry, parse_dictionary, source_metadata


OLD_HEURISTIC_PROFILES = {
    "tiny-embed": {"nouns": 2_000, "compactBytes": 2_458},
    "web-medium": {"nouns": 20_000, "compactBytes": 14_643},
    "backend-large": {"nouns": 42_295, "compactBytes": 25_498},
}
SOURCE_LICENSE = "Unicode-3.0"
UNICODE_DICTIONARY_RESOURCE_PREFIX = "inflection/resources/org/unicode/inflection/dictionary"


@dataclass(frozen=True)
class SuffixRule:
    suffix: str
    gender: str
    confidence: float
    support: int


class BloomFilter:
    def __init__(self, items: list[str], false_positive_rate: float) -> None:
        self.count = len(items)
        if not items:
            self.bit_count = 0
            self.hash_count = 0
            self.bits = bytearray()
            return

        self.bit_count = max(8, math.ceil(-self.count * math.log(false_positive_rate) / (math.log(2) ** 2)))
        self.hash_count = max(1, round((self.bit_count / self.count) * math.log(2)))
        self.bits = bytearray(math.ceil(self.bit_count / 8))
        for item in items:
            for bit in self._hashes(item):
                self.bits[bit >> 3] |= 1 << (bit & 7)

    def __contains__(self, item: str) -> bool:
        if not self.bits:
            return False
        return all(self.bits[bit >> 3] & (1 << (bit & 7)) for bit in self._hashes(item))

    def _hashes(self, item: str):
        digest = hashlib.blake2b(item.encode("utf-8"), digest_size=16).digest()
        h1 = int.from_bytes(digest[:8], "little")
        h2 = int.from_bytes(digest[8:], "little") or 1
        for index in range(self.hash_count):
            yield (h1 + index * h2) % self.bit_count

    @property
    def byte_size(self) -> int:
        return len(self.bits)


def is_noun(entry: DictionaryEntry) -> bool:
    return "noun" in entry.parts_of_speech()


def is_real_pos_ambiguity(parts_of_speech: set[str]) -> bool:
    return len(parts_of_speech - {"proper-noun"}) > 1


def noun_surface_record(surface: str, entries: list[DictionaryEntry]) -> dict:
    genders = {gender for entry in entries for gender in entry.genders()}
    numbers = {number for entry in entries for number in entry.numbers()}
    parts_of_speech = {pos for entry in entries for pos in entry.parts_of_speech()}
    flags = {flag for entry in entries for flag in entry.flags()}
    inflections = {inflection for entry in entries for inflection in entry.inflections}

    reasons = []
    if not genders:
        reasons.append("missing-gender")
    if len(genders) > 1 or any(len(entry.genders()) > 1 for entry in entries):
        reasons.append("multiple-genders")
    if not numbers:
        reasons.append("missing-number")
    if len(numbers) > 1 or any(len(entry.numbers()) > 1 for entry in entries):
        reasons.append("multiple-numbers")
    if is_real_pos_ambiguity(parts_of_speech):
        reasons.append("multiple-parts-of-speech")
    if len(inflections) > 1 or any(len(entry.inflections) > 1 for entry in entries):
        reasons.append("multiple-inflections")

    return {
        "surface": surface,
        "genders": sorted(genders),
        "numbers": sorted(numbers),
        "partsOfSpeech": sorted(parts_of_speech),
        "flags": sorted(flags),
        "inflections": sorted(inflections),
        "reasons": reasons,
        "entries": len(entries),
    }


def estimate_table_bytes(records: list[dict]) -> dict:
    string_pool_bytes = sum(len(record["surface"].encode("utf-8")) + 1 for record in records)
    surface_count = len(records)
    inflection_ids = sorted({inflection for record in records for inflection in record["inflections"]})

    # Simple sorted-table lookup:
    # - uint32 string offset
    # - uint16 feature bits
    # - uint16 inflection-pattern index, with 0xffff for none/multiple
    simple_record_bytes = surface_count * 8

    # Minimal feature-only lower bound: one feature byte per surface plus strings.
    feature_lower_bound_bytes = surface_count

    # A trie or minimal-perfect-hash pack can beat the sorted table, but this is
    # intentionally a conservative first estimate that is trivial to implement.
    return {
        "surfaceStringPoolBytes": string_pool_bytes,
        "featureLowerBoundBytes": string_pool_bytes + feature_lower_bound_bytes,
        "simpleSortedTableBytes": string_pool_bytes + simple_record_bytes,
        "simpleSortedTableRecordBytes": simple_record_bytes,
        "uniqueInflectionPatternIds": len(inflection_ids),
    }


def usable_suffix_surface(surface: str) -> bool:
    return len(surface) > 1 and any(ch.isalpha() for ch in surface) and " " not in surface and "-" not in surface


def train_suffix_rules(records: list[dict], max_suffix_len: int, min_support: int, min_confidence: float) -> list[SuffixRule]:
    counts: dict[str, Counter[str]] = defaultdict(Counter)
    for record in records:
        word = record["surface"]
        gender = record["genders"][0]
        max_len = min(max_suffix_len, len(word))
        for size in range(1, max_len + 1):
            counts[word[-size:]][gender] += 1

    rules = []
    for suffix, counter in counts.items():
        support = counter["masculine"] + counter["feminine"]
        if support < min_support:
            continue
        gender, hits = counter.most_common(1)[0]
        confidence = hits / support
        if confidence >= min_confidence:
            rules.append(SuffixRule(suffix, gender, confidence, support))

    return sorted(rules, key=lambda rule: (-len(rule.suffix), -rule.confidence, -rule.support))


def suffix_guess(surface: str, rules: list[SuffixRule]) -> str | None:
    for rule in rules:
        if surface.endswith(rule.suffix):
            return rule.gender
    return None


def classify_without_correction(surface: str, rules: list[SuffixRule], blooms: dict[str, BloomFilter]) -> str | None:
    masculine_hit = surface in blooms["masculine"]
    feminine_hit = surface in blooms["feminine"]
    if masculine_hit and not feminine_hit:
        return "masculine"
    if feminine_hit and not masculine_hit:
        return "feminine"
    if masculine_hit and feminine_hit:
        return None
    return suffix_guess(surface, rules)


def estimate_suffix_classifier(
    records: list[dict],
    max_suffix_len: int,
    min_support: int,
    min_confidence: float,
    false_positive_rate: float,
) -> dict:
    rules = train_suffix_rules(records, max_suffix_len, min_support, min_confidence)
    exceptions = {"masculine": [], "feminine": []}
    suffix_correct = 0

    for record in records:
        surface = record["surface"]
        gender = record["genders"][0]
        if suffix_guess(surface, rules) == gender:
            suffix_correct += 1
        else:
            exceptions[gender].append(surface)

    blooms = {
        "masculine": BloomFilter(exceptions["masculine"], false_positive_rate),
        "feminine": BloomFilter(exceptions["feminine"], false_positive_rate),
    }

    corrections = {}
    for record in records:
        surface = record["surface"]
        gender = record["genders"][0]
        guess = classify_without_correction(surface, rules, blooms)
        if guess != gender:
            corrections[surface] = gender

    corrected_correct = 0
    for record in records:
        surface = record["surface"]
        gender = record["genders"][0]
        guess = corrections.get(surface) or classify_without_correction(surface, rules, blooms)
        if guess == gender:
            corrected_correct += 1

    suffix_bytes = sum(len(rule.suffix.encode("utf-8")) + 4 for rule in rules)
    bloom_bytes = blooms["masculine"].byte_size + blooms["feminine"].byte_size
    correction_compact_bytes = sum(len(surface.encode("utf-8")) + 1 for surface in corrections)

    return {
        "surfaces": len(records),
        "rules": len(rules),
        "suffixOnlyAccuracy": round(suffix_correct / len(records), 4) if records else 0,
        "validatedAccuracy": round(corrected_correct / len(records), 4) if records else 0,
        "exceptionSurfaces": len(exceptions["masculine"]) + len(exceptions["feminine"]),
        "corrections": len(corrections),
        "suffixRuleBytes": suffix_bytes,
        "bloomBytes": bloom_bytes,
        "correctionCompactBytes": correction_compact_bytes,
        "compactBytes": suffix_bytes + bloom_bytes + correction_compact_bytes,
        "falsePositiveRate": false_positive_rate,
        "minSupport": min_support,
        "minConfidence": min_confidence,
    }


def feature_bits(record: dict) -> int:
    bits = 0
    if record["genders"] == ["masculine"]:
        bits |= 1 << 0
    elif record["genders"] == ["feminine"]:
        bits |= 1 << 1
    if record["numbers"] == ["singular"]:
        bits |= 1 << 2
    elif record["numbers"] == ["plural"]:
        bits |= 1 << 3
    if "vowel-start" in record["flags"]:
        bits |= 1 << 4
    return bits


def ambiguity_analyses(record: dict) -> list[dict]:
    analyses = []
    genders = [gender for gender in record["genders"] if gender in {"masculine", "feminine"}]
    numbers = [number for number in record["numbers"] if number in {"singular", "plural"}]
    for gender in genders:
        for number in numbers:
            analyses.append(
                {
                    "gender": gender,
                    "number": number,
                    "elides": "vowel-start" in record["flags"],
                    "inflectionPattern": record["inflections"][0] if len(record["inflections"]) == 1 else None,
                }
            )
    return analyses


def pack_provenance(paths: list[Path]) -> dict:
    return {
        "license": SOURCE_LICENSE,
        "generator": "dev-docs/experiments/mf2-inflection/fr_noun_pack_report.py",
        "sources": [pack_source_metadata(path) for path in paths],
    }


def pack_source_metadata(path: Path) -> dict:
    metadata = source_metadata(path)
    metadata["path"] = stable_source_label(path)
    return metadata


def stable_source_label(path: Path) -> str:
    if path.name in {"dictionary_fr.lst", "inflection-dictionary-fr.lst"}:
        return f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/dictionary_fr.lst"
    if path.name == "supplemental_fr.lst":
        return f"{UNICODE_DICTIONARY_RESOURCE_PREFIX}/supplemental_fr.lst"
    return path.name


def build_sample_pack(
    records: list[dict],
    ambiguous_records: list[dict],
    limit: int,
    ambiguity_limit: int,
    provenance: dict,
) -> dict:
    sample_records = records[:limit] if limit > 0 else records
    sample_ambiguous_records = ambiguous_records[:ambiguity_limit] if ambiguity_limit > 0 else ambiguous_records
    strings: list[str] = []
    string_indexes: dict[str, int] = {}
    rows = []
    ambiguous_rows = []

    def string_index(value: str) -> int:
        if value not in string_indexes:
            string_indexes[value] = len(strings)
            strings.append(value)
        return string_indexes[value]

    for record in sample_records:
        rows.append(
            {
                "surface": string_index(record["surface"]),
                "featureBits": feature_bits(record),
                "gender": record["genders"][0],
                "number": record["numbers"][0],
                "elides": "vowel-start" in record["flags"],
                "inflectionPattern": record["inflections"][0] if len(record["inflections"]) == 1 else None,
            }
        )

    for record in sample_ambiguous_records:
        analyses = ambiguity_analyses(record)
        if len(analyses) < 2:
            continue
        ambiguous_rows.append(
            {
                "surface": string_index(record["surface"]),
                "reasons": record["reasons"],
                "analyses": analyses,
            }
        )

    string_pool_bytes = sum(len(value.encode("utf-8")) + 1 for value in strings)
    # Rough row lower bound: uint32 surface index, uint16 feature bits,
    # uint16 inflection-pattern index/sentinel.
    binary_lower_bound_bytes = string_pool_bytes + len(rows) * 8 + len(ambiguous_rows) * 8 + sum(len(row["analyses"]) * 4 for row in ambiguous_rows)

    return {
        "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
        "locale": "fr",
        "description": "Generated sample from Unicode French noun V0 article candidates; generator-side fixture, not a runtime format.",
        "provenance": provenance,
        "strings": strings,
        "rows": rows,
        "ambiguousRows": ambiguous_rows,
        "summary": {
            "rows": len(rows),
            "ambiguousRows": len(ambiguous_rows),
            "strings": len(strings),
            "stringPoolBytes": string_pool_bytes,
            "jsonBytes": 0,
            "binaryLowerBoundBytes": binary_lower_bound_bytes,
        },
    }


def load_suffix_training_records(paths: list[Path]) -> list[dict]:
    entries: list[DictionaryEntry] = []
    for path in paths:
        parsed_entries, _ = parse_dictionary(path)
        entries.extend(parsed_entries)

    noun_entries = [entry for entry in entries if is_noun(entry)]
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in noun_entries:
        by_surface[entry.normalized_surface].append(entry)

    records = [noun_surface_record(surface, surface_entries) for surface, surface_entries in sorted(by_surface.items())]
    gender_lookup_records = [
        record
        for record in records
        if len(record["genders"]) == 1
        and "missing-gender" not in record["reasons"]
        and "multiple-genders" not in record["reasons"]
    ]
    return [record for record in gender_lookup_records if usable_suffix_surface(record["surface"])]


def build_suffix_rule_pack(
    records: list[dict],
    max_suffix_len: int,
    min_support: int,
    min_confidence: float,
    limit: int,
    provenance: dict,
) -> dict:
    rules = train_suffix_rules(records, max_suffix_len, min_support, min_confidence)
    exported_rules = rules[:limit] if limit > 0 else rules
    suffix_correct = 0
    for record in records:
        if suffix_guess(record["surface"], rules) == record["genders"][0]:
            suffix_correct += 1

    suffix_rule_bytes = sum(len(rule.suffix.encode("utf-8")) + 4 for rule in rules)
    exported_suffix_rule_bytes = sum(len(rule.suffix.encode("utf-8")) + 4 for rule in exported_rules)

    return {
        "schema": "mojito-mf2-inflection/fr-gender-suffix-rule-pack/v0",
        "locale": "fr",
        "description": "Generated suffix-rule fallback from Unicode French noun gender data; heuristic-only, not deterministic production metadata.",
        "provenance": provenance,
        "rules": [
            {
                "suffix": rule.suffix,
                "gender": rule.gender,
                "confidence": round(rule.confidence, 4),
                "support": rule.support,
            }
            for rule in exported_rules
        ],
        "summary": {
            "trainingSurfaces": len(records),
            "rules": len(rules),
            "exportedRules": len(exported_rules),
            "suffixOnlyAccuracy": round(suffix_correct / len(records), 4) if records else 0,
            "suffixRuleBytes": suffix_rule_bytes,
            "exportedSuffixRuleBytes": exported_suffix_rule_bytes,
            "maxSuffixLen": max_suffix_len,
            "minSupport": min_support,
            "minConfidence": min_confidence,
        },
    }


def build_report(paths: list[Path], max_samples: int) -> dict:
    entries: list[DictionaryEntry] = []
    skipped = []
    for path in paths:
        parsed_entries, parsed_skipped = parse_dictionary(path)
        entries.extend(parsed_entries)
        skipped.extend(parsed_skipped)

    noun_entries = [entry for entry in entries if is_noun(entry)]
    by_surface: dict[str, list[DictionaryEntry]] = defaultdict(list)
    for entry in noun_entries:
        by_surface[entry.normalized_surface].append(entry)

    records = [noun_surface_record(surface, surface_entries) for surface, surface_entries in sorted(by_surface.items())]
    reason_counts: Counter[str] = Counter()
    for record in records:
        reason_counts.update(record["reasons"])

    exact_gender_records = [record for record in records if len(record["genders"]) == 1 and "multiple-genders" not in record["reasons"]]
    gender_lookup_records = [
        record
        for record in records
        if len(record["genders"]) == 1
        and "missing-gender" not in record["reasons"]
        and "multiple-genders" not in record["reasons"]
    ]
    v0_article_records = [
        record
        for record in records
        if len(record["genders"]) == 1
        and "missing-gender" not in record["reasons"]
        and "multiple-genders" not in record["reasons"]
        and "missing-number" not in record["reasons"]
    ]
    ambiguous_article_records = [
        record
        for record in records
        if "multiple-genders" in record["reasons"]
        and "missing-number" not in record["reasons"]
        and len(ambiguity_analyses(record)) > 1
    ]

    size_estimates = estimate_table_bytes(records)
    v0_size_estimates = estimate_table_bytes(v0_article_records)
    suffix_training_records = [record for record in gender_lookup_records if usable_suffix_surface(record["surface"])]
    suffix_classifier_profiles = {
        "unicode-web-medium-like": estimate_suffix_classifier(suffix_training_records, 8, 12, 0.90, 0.001),
        "unicode-backend-like": estimate_suffix_classifier(suffix_training_records, 8, 20, 0.88, 0.0005),
    }
    old_backend = OLD_HEURISTIC_PROFILES["backend-large"]

    return {
        "schema": "mojito-mf2-inflection/fr-noun-pack-report/v0",
        "locale": "fr",
        "sources": [pack_source_metadata(path) for path in paths],
        "counts": {
            "dictionaryEntries": len(entries),
            "nounEntries": len(noun_entries),
            "nounSurfaces": len(records),
            "exactGenderSurfaces": len(exact_gender_records),
            "genderLookupSurfaces": len(gender_lookup_records),
            "v0ArticleCandidateSurfaces": len(v0_article_records),
            "ambiguousArticleCandidateSurfaces": len(ambiguous_article_records),
            "suffixTrainingSurfaces": len(suffix_training_records),
            "suffixSkippedSurfaces": len(gender_lookup_records) - len(suffix_training_records),
            "skippedLines": len(skipped),
        },
        "features": {
            "reasons": dict(sorted(reason_counts.items())),
            "genders": dict(sorted(Counter(gender for record in records for gender in record["genders"]).items())),
            "numbers": dict(sorted(Counter(number for record in records for number in record["numbers"]).items())),
            "flags": dict(sorted(Counter(flag for record in records for flag in record["flags"]).items())),
        },
        "sizeEstimates": {
            "allNounSurfaces": size_estimates,
            "v0ArticleCandidates": v0_size_estimates,
            "oldHeuristicProfiles": OLD_HEURISTIC_PROFILES,
            "suffixClassifierProfiles": suffix_classifier_profiles,
            "simpleTableVsOldBackendLarge": {
                "bytes": v0_size_estimates["simpleSortedTableBytes"] - old_backend["compactBytes"],
                "ratio": round(v0_size_estimates["simpleSortedTableBytes"] / old_backend["compactBytes"], 2),
            },
            "suffixClassifierVsSimpleTable": {
                "bytes": v0_size_estimates["simpleSortedTableBytes"] - suffix_classifier_profiles["unicode-backend-like"]["compactBytes"],
                "ratio": round(v0_size_estimates["simpleSortedTableBytes"] / suffix_classifier_profiles["unicode-backend-like"]["compactBytes"], 2),
            },
        },
        "samples": {
            "blockingAmbiguities": [record for record in records if record["reasons"]][:max_samples],
            "v0ArticleCandidates": v0_article_records[:max_samples],
            "ambiguousArticleCandidates": ambiguous_article_records[:max_samples],
        },
    }


def write_json(path: Path | None, value: dict) -> str:
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", action="append", required=True, type=Path, help="Unicode-style dictionary .lst file. Can be repeated.")
    parser.add_argument("--out", type=Path, help="Write JSON report to this path. Defaults to stdout.")
    parser.add_argument("--max-samples", type=int, default=20)
    parser.add_argument("--sample-pack-out", type=Path, help="Write a generated compact noun metadata sample pack.")
    parser.add_argument("--sample-pack-limit", type=int, default=200, help="Maximum V0 article-candidate rows in the sample pack. Use 0 for all.")
    parser.add_argument("--sample-pack-ambiguity-limit", type=int, default=20, help="Maximum ambiguous noun rows in the sample pack. Use 0 for all.")
    parser.add_argument("--suffix-rule-pack-out", type=Path, help="Write a generated suffix-rule gender fallback pack.")
    parser.add_argument("--suffix-rule-limit", type=int, default=50, help="Maximum suffix rules in the fallback pack. Use 0 for all.")
    args = parser.parse_args()

    report_sample_count = args.max_samples
    if args.sample_pack_out:
        if args.sample_pack_limit == 0 or args.sample_pack_ambiguity_limit == 0:
            report_sample_count = 1_000_000_000
        else:
            report_sample_count = max(args.max_samples, args.sample_pack_limit, args.sample_pack_ambiguity_limit)
    report = build_report(args.dictionary, report_sample_count)
    if args.sample_pack_out:
        sample_pack = build_sample_pack(
            report["samples"]["v0ArticleCandidates"],
            report["samples"]["ambiguousArticleCandidates"],
            args.sample_pack_limit,
            args.sample_pack_ambiguity_limit,
            pack_provenance(args.dictionary),
        )
        for _ in range(3):
            sample_payload = json.dumps(sample_pack, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            sample_pack["summary"]["jsonBytes"] = len(sample_payload.encode("utf-8"))
        write_json(args.sample_pack_out, sample_pack)
    if args.suffix_rule_pack_out:
        suffix_pack = build_suffix_rule_pack(
            load_suffix_training_records(args.dictionary),
            max_suffix_len=8,
            min_support=20,
            min_confidence=0.88,
            limit=args.suffix_rule_limit,
            provenance=pack_provenance(args.dictionary),
        )
        write_json(args.suffix_rule_pack_out, suffix_pack)

    payload = write_json(args.out, report)
    if args.out:
        return
    print(payload, end="")


if __name__ == "__main__":
    main()
