#!/usr/bin/env python3
"""Generate native-verified first-locale scalar/device Xcode substitutions."""

from __future__ import annotations

import copy
import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_target_substitution_fixtures import (
    APPLE,
    DEVICE_ID,
    POSITIONS,
    ROOT,
    SCALAR_ID,
    SPECIFIERS,
    WORDS,
    catalog as existing_catalog,
    document as existing_document,
)


STEM = "catalog-first-russian-substitution-locale"
MISSING_SCALAR = "harbor.first.substitution.missing.scalar🧭"
NULL_SCALAR = "harbor.first.substitution.null.scalar🧭"
MISSING_DEVICE = "harbor.first.substitution.missing.device🧭"
NULL_DEVICE = "harbor.first.substitution.null.device🧭"
PROTECTED_MISSING = "Private first missing Russian substitution"
PROTECTED_NULL = "Private first null Russian substitution"
SCALAR_PREFIXES = {MISSING_SCALAR: "Первый", NULL_SCALAR: "Новый"}
DEVICE_SUFFIXES = {MISSING_DEVICE: "у причала", NULL_DEVICE: "в гавани"}
CATEGORIES = json.loads(
    (ROOT / "cldr-cardinal-categories.v1.json").read_text(encoding="utf-8")
)["cardinalCategories"]["ru"]


def plural(selector: str) -> str:
    return (
        f"{{{selector}, plural, "
        + " ".join(
            f"{category} {{{{{selector}}} первый {WORDS[selector][category]}}}"
            for category in CATEGORIES
        )
        + "}"
    )


def device_select(suffix: str) -> str:
    iphone = f"Экран {{arg2}}: {plural('lights')} затем {plural('lanes')} {suffix}"
    mac = f"Компьютер {{arg2}}: {plural('lanes')} после {plural('lights')} {suffix}"
    return f"{{device, select, iphone {{{iphone}}} mac {{{mac}}} other {{{iphone}}}}}"


TRANSLATIONS = {
    **{
        identifier: f"{prefix} {{arg2}}: {plural('lights')} перед {plural('lanes')}"
        for identifier, prefix in SCALAR_PREFIXES.items()
    },
    **{
        identifier: device_select(suffix)
        for identifier, suffix in DEVICE_SUFFIXES.items()
    },
}


def document() -> dict[str, object]:
    originals = existing_document()["strings"]
    entries = {}
    for identifier in (*SCALAR_PREFIXES, *DEVICE_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_PREFIXES else DEVICE_ID
        localizations = {
            "en": copy.deepcopy(originals[template]["localizations"]["en"]),
            "de": copy.deepcopy(originals[template]["localizations"]["en"]),
        }
        if identifier in {NULL_SCALAR, NULL_DEVICE}:
            localizations["ru"] = None
        entries[identifier] = {
            "comment": "Pinned CLDR owns first-locale Xcode substitutions",
            "localizations": localizations,
        }
    entries[PROTECTED_MISSING] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(originals[SCALAR_ID]["localizations"]["en"])
        },
    }
    entries[PROTECTED_NULL] = {
        "shouldTranslate": False,
        "localizations": {
            "en": copy.deepcopy(originals[DEVICE_ID]["localizations"]["en"]),
            "ru": None,
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": entries}


def catalog(root: dict[str, object]) -> dict[str, object]:
    originals = existing_catalog(existing_document())["messages"]
    messages = {}
    for identifier in (*SCALAR_PREFIXES, *DEVICE_SUFFIXES):
        template = SCALAR_ID if identifier in SCALAR_PREFIXES else DEVICE_ID
        descriptor = copy.deepcopy(originals[template])
        descriptor["description"] = root["strings"][identifier]["comment"]
        source = root["strings"][identifier]["localizations"]
        descriptor["metadata"]["localizations"] = {
            "de": (
                {"variationAxes": {"device": source["de"]["variations"]["device"]}}
                if identifier in DEVICE_SUFFIXES
                else {
                    "value": source["de"]["stringUnit"]["value"],
                    "state": source["de"]["stringUnit"]["state"],
                }
            )
        }
        descriptor["metadata"]["appleLocalizationSources"] = {"de": source["de"]}
        messages[identifier] = descriptor
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(sorted(messages.items())),
    }


def inserted_definitions() -> dict[str, object]:
    return {
        selector: {
            "argNum": POSITIONS[selector],
            "formatSpecifier": SPECIFIERS[selector],
            "variations": {
                "plural": {
                    category: {
                        "stringUnit": {
                            "state": "translated",
                            "value": (
                                f"%{POSITIONS[selector]}${SPECIFIERS[selector]} "
                                f"первый {WORDS[selector][category]}"
                            ),
                        }
                    }
                    for category in sorted(CATEGORIES)
                }
            },
        }
        for selector in sorted(POSITIONS)
    }


def target(identifier: str) -> dict[str, object]:
    if identifier in SCALAR_PREFIXES:
        return {
            "stringUnit": {
                "state": "translated",
                "value": (
                    f"{SCALAR_PREFIXES[identifier]} %3$@: "
                    "%2$#@lights@ перед %#@lanes@"
                ),
            },
            "substitutions": inserted_definitions(),
        }
    suffix = DEVICE_SUFFIXES[identifier]
    return {
        "substitutions": inserted_definitions(),
        "variations": {
            "device": {
                "iphone": {
                    "stringUnit": {
                        "state": "translated",
                        "value": (f"Экран %3$@: %2$#@lights@ затем %#@lanes@ {suffix}"),
                    }
                },
                "mac": {
                    "stringUnit": {
                        "state": "translated",
                        "value": (
                            f"Компьютер %3$@: %#@lanes@ после %2$#@lights@ {suffix}"
                        ),
                    }
                },
            }
        },
    }


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        start = source.index(key, cursor)
        localizations = source.index('"localizations"', start + len(key))
        opening = source.index("{", localizations)
        closing = object_end(source, opening)
        if "ru" in entry["localizations"]:
            match = re.compile(r'"ru"\s*:\s*(null)').search(source, opening, closing)
            if match is None:
                raise RuntimeError(
                    f"Missing first-locale substitution null: {identifier}"
                )
            beginning, end = match.span(1)
        else:
            beginning = end = closing
            while source[beginning - 1] in " \t\r\n":
                beginning -= 1
            end = beginning
        slots.append(
            {
                "id": identifier,
                "start": bom + len(source[:beginning].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
        )
        cursor = closing + 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": "ru",
        "slots": slots,
    }


def localized(source: str) -> str:
    original = source.encode("utf-8")
    result = bytearray()
    previous = 0
    for slot in skeleton(source, "UTF-8")["slots"]:
        result.extend(original[previous : slot["start"]])
        value = json.dumps(
            target(slot["id"]), ensure_ascii=False, separators=(",", ":")
        )
        if slot["start"] == slot["end"]:
            value = ',"ru":' + value
        result.extend(value.encode("utf-8"))
        previous = slot["end"]
    result.extend(original[previous:])
    return result.decode("utf-8")


def runtime_samples() -> list[dict[str, object]]:
    samples = []
    for lanes, lights, lane_category, light_category in (
        (1, 1, "one", "one"),
        (2, 5, "few", "many"),
        (5, 2, "many", "few"),
        (21, 22, "one", "few"),
    ):
        lane = f"{lanes} первый {WORDS['lanes'][lane_category]}"
        light = f"{lights} первый {WORDS['lights'][light_category]}"
        samples.extend(
            {
                "message": identifier,
                "arguments": [lanes, lights, "Rowan"],
                "expected": f"{prefix} Rowan: {light} перед {lane}",
            }
            for identifier, prefix in SCALAR_PREFIXES.items()
        )
        samples.extend(
            {
                "message": identifier,
                "arguments": [lanes, lights, "Rowan"],
                "expected": f"Компьютер Rowan: {lane} после {light} {suffix}",
            }
            for identifier, suffix in DEVICE_SUFFIXES.items()
        )
    return samples


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    localized_path.write_text(translated, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.translations.json", TRANSLATIONS)
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(localized_path)
    )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-cldr-first-russian-target-substitution-locale"
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
                    "Legacy routing cannot derive first-locale scalar/device substitution "
                    "categories from pinned CLDR, retain source-owned argument definitions, "
                    "avoid inventing hidden arguments, and preserve exact protected bytes."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-inserts-first-russian-cldr-substitutions"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetSubstitutionInsertion": True,
        "xcstringsTargetDeviceInsertion": True,
        "xcstringsFirstLocaleCategories": True,
        "xcstringsFirstLocaleSubstitutions": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": [],
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
