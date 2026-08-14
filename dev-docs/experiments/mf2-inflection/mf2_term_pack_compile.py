#!/usr/bin/env python3
"""Compile a readable term pack into a compact row-oriented JSON shape."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


GENDER_BITS = {"masculine": 1, "feminine": 2, "neuter": 3, "ambiguous": 4}
NUMBER_BITS = {"singular": 1, "plural": 2, "invariant": 3}
PART_OF_SPEECH_BITS = {"noun": 1}
ARTICLE_CLASS_BITS = {"standard": 1, "lo": 2, "elision": 3}
TURKISH_SUFFIX_METADATA_BIT = 1 << 20
TURKISH_SUFFIX_BITS = {
    "vowelEnd": 1 << 21,
    "frontVowel": 1 << 22,
    "roundedVowel": 1 << 23,
    "hardConsonant": 1 << 24,
}


class StringPool:
    def __init__(self) -> None:
        self.strings: list[str] = []
        self.index: dict[str, int] = {}

    def add(self, value: str | None) -> int | None:
        if value is None:
            return None
        if value not in self.index:
            self.index[value] = len(self.strings)
            self.strings.append(value)
        return self.index[value]

    def byte_size(self) -> int:
        return sum(len(value.encode("utf-8")) + 1 for value in self.strings)


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def is_pattern(value: str) -> bool:
    return "{$" in value


def feature_bits(morphology: dict) -> int:
    bits = 0
    bits |= PART_OF_SPEECH_BITS.get(morphology.get("partOfSpeech"), 0)
    bits |= GENDER_BITS.get(morphology.get("gender"), 0) << 4
    bits |= NUMBER_BITS.get(morphology.get("number"), 0) << 8
    if morphology.get("startsWithVowelSound") is True:
        bits |= 1 << 12
    elif morphology.get("startsWithVowelSound") is False:
        bits |= 1 << 13
    if morphology.get("stressed") is True:
        bits |= 1 << 14
    bits |= ARTICLE_CLASS_BITS.get(morphology.get("articleClass"), 0) << 16
    turkish_suffix = morphology.get("turkishSuffix")
    if isinstance(turkish_suffix, dict):
        bits |= TURKISH_SUFFIX_METADATA_BIT
        for field, bit in TURKISH_SUFFIX_BITS.items():
            if turkish_suffix.get(field) is True:
                bits |= bit
    return bits


def required_forms_by_term(requirements_report: dict) -> tuple[dict[str, set[str]], dict]:
    required: dict[str, set[str]] = {}
    bindings: dict[str, dict[str, list[str]]] = {}

    for message_id, message in requirements_report.get("messages", {}).items():
        message_bindings = bindings.setdefault(message_id, {})
        for usage in message.get("termUsages", []):
            argument = usage["argument"]
            term_ids = usage.get("termIds", [])
            message_bindings.setdefault(argument, []).extend(term_ids)
            forms = [requirement.removeprefix("forms.") for requirement in usage.get("requirements", []) if requirement.startswith("forms.")]
            for term_id in term_ids:
                required.setdefault(term_id, set()).update(forms)

    return required, bindings


def compile_term_pack(term_pack: dict, requirements_report: dict) -> dict:
    required_forms, bindings = required_forms_by_term(requirements_report)
    pool = StringPool()
    terms = term_pack.get("terms", {})
    term_rows = []
    form_sets = []
    diagnostics = list(requirements_report.get("diagnostics", []))

    for term_id in sorted(required_forms):
        term = terms.get(term_id)
        if term is None:
            diagnostics.append({"termId": term_id, "code": "missing-term"})
            continue

        morphology = term.get("morphology", {})
        forms = term.get("forms", {})
        form_rows = []
        for form_key in sorted(required_forms[term_id]):
            form_value = forms.get(form_key)
            if form_value is None:
                diagnostics.append({"termId": term_id, "code": "missing-form", "form": form_key})
                continue
            form_rows.append(
                {
                    "key": pool.add(form_key),
                    "value": pool.add(form_value),
                    "kind": "pattern" if is_pattern(form_value) else "literal",
                }
            )

        form_set_index = len(form_sets)
        form_sets.append({"term": pool.add(term_id), "forms": form_rows})
        term_rows.append(
            {
                "id": pool.add(term_id),
                "text": pool.add(term.get("text", "")),
                "featureBits": feature_bits(morphology),
                "sense": pool.add(morphology.get("sense")),
                "formSet": form_set_index,
            }
        )

    compact = {
        "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
        "locale": term_pack.get("locale"),
        "strings": pool.strings,
        "terms": term_rows,
        "formSets": form_sets,
        "bindings": bindings,
        "diagnostics": diagnostics,
    }
    if term_pack.get("provenance") is not None:
        compact["provenance"] = term_pack["provenance"]
    compact_json_bytes = len(json.dumps(compact, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    binary_estimate = {
        "stringPoolBytes": pool.byte_size(),
        "termRowBytes": len(term_rows) * 20,
        "formRowBytes": sum(len(form_set["forms"]) for form_set in form_sets) * 12,
        "bindingReferenceBytes": 0,
    }
    binary_estimate["totalBytes"] = sum(binary_estimate.values())
    compact["sizeEstimates"] = {
        "compactJsonBytes": compact_json_bytes,
        "binaryLowerBoundBytes": binary_estimate,
    }
    return compact


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--term-pack", required=True, type=Path, help="Readable term pack JSON.")
    parser.add_argument("--requirements-report", required=True, type=Path, help="Output from mf2_term_requirements.py.")
    parser.add_argument("--out", type=Path, help="Write compact JSON to this path. Defaults to stdout.")
    args = parser.parse_args()

    compiled = compile_term_pack(load_json(args.term_pack), load_json(args.requirements_report))
    payload = json.dumps(compiled, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
