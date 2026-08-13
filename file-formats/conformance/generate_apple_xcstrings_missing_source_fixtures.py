#!/usr/bin/env python3
"""Generate native Xcode fixtures for genuinely absent source-locale keys."""

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
STEM = "catalog-missing-source-insertion"

TARGETS = {
    "harbor.missing.plain🧭": 'Abri "paisible" 🧭',
    "North %n %@ 🧭": "Ouest  {arg1} 🧭",
    "%@ %n %@ anchorage": "{arg0}  {arg2} mouillage",
    "West %2$n %1$@ pier": "Ouest  {arg0} jetée",
    "Tide %%n %@ marker": "Marée %n {arg0} balise",
    "owned.scalar": "Stable {arg0} rive",
}

NATIVE_TARGETS = {
    "harbor.missing.plain🧭": 'Abri "paisible" 🧭',
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
        "harbor.missing.plain🧭": {
            "comment": "A neutral missing source with existing review ownership",
            "extractionState": "stale",
            "localizations": {"fr": unit("Abri existant", "needs_review")},
        },
        "North %n %@ 🧭": {
            "localizations": {
                "fr": unit("Nord %@ conservé", "machine_translated"),
                "de": None,
            }
        },
        "%@ %n %@ anchorage": {
            "localizations": {"fr": unit("%@ %@ mouillage existant", "future_review")}
        },
        "West %2$n %1$@ pier": {
            "localizations": {"fr": unit("Ouest %1$@ jetée existante", "translated")}
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
        "Private missing pier": {
            "shouldTranslate": False,
            "localizations": {"fr": unit("Anse privée protégée", "needs_review")},
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    messages = {}
    for identifier, entry in sorted(root["strings"].items()):
        if entry.get("shouldTranslate") is False:
            continue
        source = entry["localizations"].get("en")
        native = source["stringUnit"]["value"] if source else identifier
        message = descriptor(native)
        metadata = message.setdefault("metadata", {})
        if entry.get("extractionState"):
            metadata["extractionState"] = entry["extractionState"]
        if source:
            metadata["appleSourceLocalization"] = source
            metadata["sourceState"] = source["stringUnit"]["state"]
        translations = {}
        sources = {}
        for language, locale in entry["localizations"].items():
            if language == "en" or locale is None:
                continue
            translations[language] = {
                "value": locale["stringUnit"]["value"],
                "state": locale["stringUnit"]["state"],
            }
            sources[language] = locale
        if translations:
            metadata["localizations"] = translations
            metadata["appleLocalizationSources"] = sources
        if entry.get("comment"):
            message["description"] = entry["comment"]
        messages[identifier] = message
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": messages,
    }


def object_end(source: str, opening: int) -> int:
    depth = 0
    quoted = False
    escaped = False
    for index in range(opening, len(source)):
        current = source[index]
        if escaped:
            escaped = False
        elif quoted and current == "\\":
            escaped = True
        elif current == '"':
            quoted = not quoted
        elif not quoted and current == "{":
            depth += 1
        elif not quoted and current == "}":
            depth -= 1
            if depth == 0:
                return index
    raise RuntimeError("Unterminated Xcode localization object")


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    owned = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        beginning = source.index(key, cursor)
        field = source.index('"localizations"', beginning + len(key))
        opening = source.index("{", field)
        closing = object_end(source, opening)
        original = entry["localizations"].get("en")
        if "en" not in entry["localizations"]:
            position = closing
            while source[position - 1] in " \t\r\n":
                position -= 1
            start = end = position
        else:
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
            match = expression.search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing Xcode source ownership for {identifier}")
            start, end = match.span(1)
        cursor = closing + 1
        owned.append(
            {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
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
    result = source
    for slot in reversed(skeleton(source, "UTF-8")["slots"]):
        start = len(source.encode()[: slot["start"]].decode())
        end = len(source.encode()[: slot["end"]].decode())
        native = NATIVE_TARGETS[slot["id"]]
        if start == end or source[start:end] == "null":
            unit_value = json.dumps(
                {"stringUnit": {"state": "translated", "value": native}},
                ensure_ascii=False,
                separators=(",", ":"),
            )
            replacement = ',"en":' + unit_value if start == end else unit_value
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
    source_path.write_text(source, encoding="utf-8")
    target_path.write_text(target, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(target_path)
    )
    write_json(APPLE / f"{STEM}.translations.json", TARGETS)
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-missing-source-locale-runtime-fallback"
    manifest["cases"] = [case for case in manifest["cases"] if case["id"] != case_id]
    manifest["cases"].append(
        {
            "id": case_id,
            "format": "apple_xcstrings",
            "input": f"fixtures/apple/{STEM}.xcstrings",
            "expected": f"fixtures/apple/{STEM}.expected.json",
            "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
            "okapi": {
                "policy": "unsupported",
                "assetPath": "en.lproj/Localizable.xcstrings",
                "reason": "Legacy extension routing rejects Xcode catalogs and cannot insert genuinely absent source-localization keys or retain existing target review states.",
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-missing-source-locales"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsInsertSourceLocale": True,
        "xcstringsMissingSourceLocale": True,
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
