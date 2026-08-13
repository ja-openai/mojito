#!/usr/bin/env python3
"""Generate compiler-verified Xcode explicit-null source-locale insertion fixtures."""

from __future__ import annotations

import json
import re
from pathlib import Path

from generate_apple_disabled_argument_fixtures import (
    compiled_xcatalog,
    descriptor,
    write_json,
)


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "catalog-null-source-insertion"

TARGETS = {
    "harbor.null.plain🧭": 'Abri "paisible" 🧭',
    "North %n %@ 🧭": "Ouest  {arg1} 🧭",
    "%@ %n %@ anchorage": "{arg0}  {arg2} mouillage",
    "West %2$n %1$@ pier": "Ouest  {arg0} jetée",
    "Tide %%n %@ marker": "Marée %n {arg0} balise",
    "owned.scalar": "Stable {arg0} rive",
}

NATIVE_TARGETS = {
    "harbor.null.plain🧭": 'Abri "paisible" 🧭',
    "North %n %@ 🧭": "Ouest %n %@ 🧭",
    "%@ %n %@ anchorage": "%@ %n %@ mouillage",
    "West %2$n %1$@ pier": "Ouest %2$n %1$@ jetée",
    "Tide %%n %@ marker": "Marée %%n %@ balise",
    "owned.scalar": "Stable %@ rive",
}


def unit(value: str, state: str) -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def document() -> dict[str, object]:
    entries: dict[str, object] = {
        "harbor.null.plain🧭": {
            "comment": "A neutral nullable source with existing review ownership",
            "extractionState": "stale",
            "localizations": {
                "en": None,
                "fr": unit("Abri existant", "needs_review"),
            },
        },
        "North %n %@ 🧭": {
            "localizations": {
                "en": None,
                "fr": unit("Nord %@ conservé", "machine_translated"),
            }
        },
        "%@ %n %@ anchorage": {
            "localizations": {
                "en": None,
                "fr": unit("%@ %@ mouillage existant", "future_review"),
            }
        },
        "West %2$n %1$@ pier": {
            "localizations": {
                "en": None,
                "fr": unit("Ouest %1$@ jetée existante", "translated"),
            }
        },
        "Tide %%n %@ marker": {
            "localizations": {
                "en": None,
                "fr": unit("Marée %%n %@ existante", "needs_review"),
            }
        },
        "owned.scalar": {
            "localizations": {
                "en": unit("Steady %@ shore", "needs_review"),
                "fr": unit("Rive %@ protégée", "stale"),
            }
        },
        "Private null pier": {
            "shouldTranslate": False,
            "localizations": {
                "en": None,
                "fr": unit("Jetée privée protégée", "translated"),
            },
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, entry in sorted(root["strings"].items()):
        if entry.get("shouldTranslate") is False:
            continue
        source = entry["localizations"]["en"]
        native = source["stringUnit"]["value"] if source else identifier
        message = descriptor(native)
        metadata = message.setdefault("metadata", {})
        if entry.get("extractionState"):
            metadata["extractionState"] = entry["extractionState"]
        if source:
            metadata["appleSourceLocalization"] = source
            metadata["sourceState"] = source["stringUnit"]["state"]
        locale = entry["localizations"]["fr"]
        metadata["localizations"] = {
            "fr": {
                "value": locale["stringUnit"]["value"],
                "state": locale["stringUnit"]["state"],
            }
        }
        metadata["appleLocalizationSources"] = {"fr": locale}
        if entry.get("comment"):
            message["description"] = entry["comment"]
        messages[identifier] = message
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": messages,
    }


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    owned = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        original = entry["localizations"]["en"]
        expression = (
            re.compile(r'"en"\s*:\s*(null)')
            if original is None
            else re.compile(
                r'"value"\s*:\s*"('
                + re.escape(
                    json.dumps(original["stringUnit"]["value"], ensure_ascii=False)[
                        1:-1
                    ]
                )
                + r')"'
            )
        )
        match = expression.search(source, cursor)
        if match is None:
            raise RuntimeError(f"Missing Xcode source ownership for {identifier}")
        cursor = match.end()
        owned.append(
            {
                "id": identifier,
                "start": bom + len(source[: match.start(1)].encode(codec)),
                "end": bom + len(source[: match.end(1)].encode(codec)),
            }
        )
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "slots": owned,
    }


def localized(source: str) -> str:
    owned = skeleton(source, "UTF-8")["slots"]
    result = source
    for slot in reversed(owned):
        start = len(source.encode()[: slot["start"]].decode())
        end = len(source.encode()[: slot["end"]].decode())
        native = NATIVE_TARGETS[slot["id"]]
        if source[start:end] == "null":
            replacement = json.dumps(
                {"stringUnit": {"state": "translated", "value": native}},
                ensure_ascii=False,
                separators=(",", ":"),
            )
        else:
            replacement = json.dumps(native, ensure_ascii=False)[1:-1]
        result = result[:start] + replacement + result[end:]
    return result


def runtime_samples() -> list[dict[str, object]]:
    values = []
    for identifier, native in NATIVE_TARGETS.items():
        message = descriptor(native)
        substitutions = {
            placeholder["name"]: (
                "Sky"
                if identifier == "%@ %n %@ anchorage" and placeholder["name"] == "arg0"
                else "Rowan"
            )
            for placeholder in message.get("placeholders", [])
        }
        arguments = {
            placeholder["position"]: substitutions[placeholder["name"]]
            for placeholder in message.get("placeholders", [])
        }
        for hidden in message.get("metadata", {}).get(
            "appleDisabledPrintfConversions", []
        ):
            arguments.setdefault(hidden["argumentPosition"], 0)
        expected = re.sub(
            r"\{(arg\d+)\}",
            lambda match: str(substitutions[match.group(1)]),
            message["defaultMessage"],
        )
        values.append(
            {
                "message": identifier,
                "arguments": [
                    arguments[index]
                    for index in range(1, max(arguments, default=0) + 1)
                ],
                "expected": expected,
            }
        )
    return values


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    target = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    target_path = APPLE / f"{STEM}.localized.xcstrings"
    pending_path = APPLE / f"{STEM}.new.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    target_path.write_text(target, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(target_path)
    )
    pending = json.loads(target)
    for identifier in TARGETS:
        if identifier != "owned.scalar":
            pending["strings"][identifier]["localizations"]["en"]["stringUnit"][
                "state"
            ] = "new"
    write_json(pending_path, pending)
    write_json(APPLE / f"{STEM}.new.expected.json", catalog(pending))
    write_json(APPLE / f"{STEM}.new.compiled.json", compiled_xcatalog(pending_path))
    write_json(APPLE / f"{STEM}.translations.json", TARGETS)
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-explicit-null-source-locale-runtime-fallback"
    pending_id = (
        "apple-xcstrings-new-source-review-state-is-excluded-from-native-output"
    )
    manifest["cases"] = [
        case for case in manifest["cases"] if case["id"] not in {case_id, pending_id}
    ]
    manifest["cases"].extend(
        [
            {
                "id": case_id,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{STEM}.xcstrings",
                "expected": f"fixtures/apple/{STEM}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": "Legacy extension routing rejects Xcode catalogs and cannot materialize explicit-null source locales or retain existing review states.",
                },
            },
            {
                "id": pending_id,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{STEM}.new.xcstrings",
                "expected": f"fixtures/apple/{STEM}.new.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{STEM}.new.compiled.json",
            },
        ]
    )
    prefix = "apple-xcstrings-source-skeleton-materializes-explicit-null-locales"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsInsertSourceLocale": True,
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [
            {
                "message": "owned.scalar",
                "arguments": ["Rowan"],
                "expected": "Steady Rowan shore",
            }
        ],
        "xcstringsLocalizedRuntimeSamples": runtime_samples(),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": prefix + "-utf8",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                **common,
            },
            {
                "id": prefix + "-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                **common,
            },
        ]
    )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
