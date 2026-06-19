#!/usr/bin/env python3
"""Export and validate alternative grammar resource bundle storage shapes."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from grammar_bundle_loader import GrammarBundle


def flatten(data: dict[str, Any]) -> dict[str, Any]:
    output: dict[str, Any] = {}

    def visit(prefix: str, value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                visit(f"{prefix}/{key}" if prefix else key, child)
        else:
            output[prefix] = value

    visit("", data)
    return output


def unflatten(data: dict[str, Any]) -> dict[str, Any]:
    root: dict[str, Any] = {}
    for key, value in data.items():
        current = root
        parts = key.split("/")
        for part in parts[:-1]:
            current = current.setdefault(part, {})
        current[parts[-1]] = value
    return root


def fr_flags(morphology: dict[str, Any]) -> int:
    gender_values = {"unknown": 0, "masculine": 1, "feminine": 2}
    number_values = {"unknown": 0, "singular": 1, "plural": 2, "invariant": 3}
    flags = gender_values.get(morphology.get("gender", "unknown"), 0)
    flags |= number_values.get(morphology.get("number", "unknown"), 0) << 2
    phonology = morphology.get("phonology", {})
    if phonology.get("elides"):
        flags |= 1 << 4
    if phonology.get("hAspired"):
        flags |= 1 << 5
    if morphology.get("countability") == "count":
        flags |= 1 << 6
    if morphology.get("forms", {}).get("plural"):
        flags |= 1 << 7
    return flags


def decode_fr_flags(flags: int) -> dict[str, Any]:
    genders = ["unknown", "masculine", "feminine", "reserved"]
    numbers = ["unknown", "singular", "plural", "invariant"]
    morphology: dict[str, Any] = {
        "gender": genders[flags & 0b11],
        "number": numbers[(flags >> 2) & 0b11],
    }
    phonology: dict[str, Any] = {}
    if flags & (1 << 4):
        phonology["elides"] = True
    if flags & (1 << 5):
        phonology["hAspired"] = True
    if phonology:
        morphology["phonology"] = phonology
    if flags & (1 << 6):
        morphology["countability"] = "count"
    return morphology


def compact_fr(data: dict[str, Any]) -> dict[str, Any]:
    if data.get("profile") != "fr-v1":
        raise ValueError("compact tuple export currently supports fr-v1 only")
    terms: dict[str, Any] = {}
    for term_id, term in data.get("terms", {}).items():
        morphology = term.get("morphology", {})
        forms = morphology.get("forms", {})
        has_explicit_term_pattern = bool(term.get("forms", {}).get("default"))
        is_compact_noun = (
            not has_explicit_term_pattern
            and morphology.get("partOfSpeech") == "noun"
            and set(forms).issubset({"plural"})
        )
        if not is_compact_noun:
            terms[term_id] = term
            continue
        row: list[Any] = [term["text"], fr_flags(morphology)]
        plural = forms.get("plural")
        if plural:
            row.append(plural)
        terms[term_id] = row
    return {
        "schema": data.get("schema"),
        "l": data["locale"],
        "p": data["profile"],
        "m": {message_id: message["value"] for message_id, message in data["messages"].items()},
        "t": terms,
    }


def expand_compact_fr(data: dict[str, Any], examples_source: dict[str, Any]) -> dict[str, Any]:
    terms: dict[str, Any] = {}
    for term_id, row in data["t"].items():
        if isinstance(row, dict):
            terms[term_id] = row
            continue
        morphology = decode_fr_flags(row[1])
        morphology["partOfSpeech"] = "noun"
        if len(row) > 2:
            morphology.setdefault("forms", {})["plural"] = row[2]
        terms[term_id] = {"text": row[0], "morphology": morphology}
    return {
        "schema": data.get("schema"),
        "locale": data["l"],
        "profile": data["p"],
        "messages": {
            message_id: {
                "value": pattern,
                "examples": examples_source["messages"].get(message_id, {}).get("examples", []),
            }
            for message_id, pattern in data["m"].items()
        },
        "terms": terms,
    }


def validate_bundle(data: dict[str, Any], label: str) -> list[str]:
    return GrammarBundle.from_data(data, label).validate_examples()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--out-dir", type=Path)
    args = parser.parse_args()

    source = json.loads(args.fixture.read_text(encoding="utf-8"))
    flat = flatten(source)
    flat_roundtrip = unflatten(flat)
    failures = validate_bundle(flat_roundtrip, f"{args.fixture}:flat")
    if failures:
        print("\n".join(failures))
        raise SystemExit(1)

    print(f"ok flat {args.fixture}")
    outputs = {"flat": flat}

    if source.get("profile") == "fr-v1":
        compact = compact_fr(source)
        compact_roundtrip = expand_compact_fr(compact, source)
        failures = validate_bundle(compact_roundtrip, f"{args.fixture}:compact")
        if failures:
            print("\n".join(failures))
            raise SystemExit(1)
        print(f"ok compact {args.fixture}")
        outputs["compact"] = compact

    if args.out_dir:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        for shape, data in outputs.items():
            output = args.out_dir / f"{args.fixture.stem}.{shape}.json"
            output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
