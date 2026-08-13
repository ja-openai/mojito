#!/usr/bin/env python3
"""Generate Xcode's opaque source/target review-state preservation matrix."""

from __future__ import annotations

import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_target_insertion_fixtures import APPLE, ROOT, catalog


STEM = "catalog-opaque-review-state-matrix"
TARGET = "fr_CA"
STATES = (
    "new",
    "needs_review",
    "translated",
    "machine_translated",
    "stale",
    "future_review",
    "untranslated",
    "invalid_future_state",
)


def document() -> dict[str, object]:
    strings = {}
    for index, state in enumerate(STATES):
        strings[f"harbor.review.{index}.{state}🧭"] = {
            "comment": "Unknown state strings remain platform-owned opaque metadata",
            "extractionState": "stale" if index % 2 else "manual",
            "localizations": {
                "en": {"stringUnit": {"state": state, "value": f"Source {state} %@"}},
                TARGET: {
                    "stringUnit": {
                        "state": STATES[-index - 1],
                        "value": f"Ancien {state} %@",
                    }
                },
                "de": {
                    "stringUnit": {
                        "state": f"preserved_future_state_{index}",
                        "value": f"Geschützt {state} %@",
                    }
                },
            },
        }
    strings["harbor.review.source.new.automatic🧭"] = {
        "comment": "Automatic new source entries remain absent from the development bundle",
        "extractionState": "invalid_future_extraction",
        "localizations": {
            "en": {"stringUnit": {"state": "new", "value": "Automatic new source %@"}},
            TARGET: {
                "stringUnit": {
                    "state": "invalid_future_state",
                    "value": "Automatique %@",
                }
            },
            "de": {
                "stringUnit": {"state": "machine_translated", "value": "Automatisch %@"}
            },
        },
    }
    strings["Private future review state"] = {
        "shouldTranslate": False,
        "localizations": {
            "en": {"stringUnit": {"state": "invalid_future_state", "value": "Private"}},
            TARGET: {
                "stringUnit": {"state": "future_review", "value": "Privé préservé"}
            },
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": strings}


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        beginning = source.index(json.dumps(identifier, ensure_ascii=False), cursor)
        target = source.index(json.dumps(TARGET), beginning)
        value = entry["localizations"][TARGET]["stringUnit"]["value"]
        match = re.compile(
            r'"value"\s*:\s*"('
            + re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
            + r')"'
        ).search(source, target)
        if match is None:
            raise RuntimeError(f"Missing review-state target body: {identifier}")
        start, end = match.span(1)
        slots.append(
            {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        )
        cursor = end
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": TARGET,
        "slots": slots,
    }


def localized(source: str) -> str:
    output = source
    for slot in reversed(skeleton(source, "UTF-8")["slots"]):
        start = len(source.encode("utf-8")[: slot["start"]].decode("utf-8"))
        end = len(source.encode("utf-8")[: slot["end"]].decode("utf-8"))
        state = (
            "automatic"
            if slot["id"] == "harbor.review.source.new.automatic🧭"
            else next(state for state in STATES if slot["id"].endswith(f".{state}🧭"))
        )
        replacement = json.dumps(f"Révisé {state} %@", ensure_ascii=False)[1:-1]
        output = output[:start] + replacement + output[end:]
    return output


def translations() -> dict[str, str]:
    values = {
        f"harbor.review.{index}.{state}🧭": f"Révisé {state} {{arg0}}"
        for index, state in enumerate(STATES)
    }
    values["harbor.review.source.new.automatic🧭"] = "Révisé automatic {arg0}"
    return values


def runtime_samples() -> list[dict[str, object]]:
    values = [
        {
            "message": f"harbor.review.{index}.{state}🧭",
            "arguments": ["Rowan"],
            "expected": f"Révisé {state} Rowan",
        }
        for index, state in enumerate(STATES)
    ]
    values.append(
        {
            "message": "harbor.review.source.new.automatic🧭",
            "arguments": ["Rowan"],
            "expected": "Révisé automatic Rowan",
        }
    )
    return values


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    target = localized(source)
    original_path = APPLE / f"{STEM}.xcstrings"
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    original_path.write_text(source, encoding="utf-8")
    localized_path.write_text(target, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(original_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(localized_path)
    )
    write_json(APPLE / f"{STEM}.translations.json", translations())

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-opaque-known-and-future-review-state-matrix"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != identifier]
    manifest["cases"].append(
        {
            "id": identifier,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": (
                    "Legacy routing rejects Xcode catalogs and cannot preserve opaque "
                    "source, target, machine-generated, future, and protected review states."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-preserves-opaque-review-states"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "fr-CA",
        "xcstringsRuntimeLocale": TARGET,
        "xcstringsOpaqueReviewStates": True,
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [],
        "xcstringsLocalizedRuntimeSamples": runtime_samples(),
    }
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json", skeleton(source, encoding)
        )
        manifest["sourceSkeletons"].append(
            {
                "id": prefix + ("-utf8" if not suffix else "-utf16"),
                **({"encoding": encoding} if suffix else {}),
                "expected": f"fixtures/apple/{STEM}{suffix}.expected.skeleton.json",
                **common,
            }
        )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
