#!/usr/bin/env python3
"""Generate native Xcode development-language alias and source-slot fixtures."""

from __future__ import annotations

import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_target_plural_fixtures import (
    APPLE,
    GERMAN_STATES,
    GERMAN_VALUES,
    ROOT,
    SOURCE_STATES,
    plural,
)


PAIRS = {
    "hebrew-deprecated": ("he", "iw", "he"),
    "hebrew-deprecated-region": ("he-IL", "iw-IL", "he-IL"),
    "norwegian-bokmal": ("nb", "no-bok", "nb"),
    "norwegian-nynorsk": ("nn", "no-nyn", "nn"),
    "british-obsolete-territory": ("en-GB", "en-UK", "en-GB"),
    "serbian-default-script": ("sr", "sr-Cyrl", "sr"),
    "mandarin-three-letter": ("zh", "cmn", "zh"),
    "mandarin-simplified-extlang": ("zh-Hans", "zh-cmn-Hans", "zh-Hans"),
    "language-case": ("en-US", "EN-us", "en-US"),
    "region-separator": ("fr-CA", "fr_CA", "fr_CA"),
}
COMPETING = {
    "declared-active-alias": ("he", ("he", "iw"), "active"),
    "declared-null-alias": ("he", ("he", "iw"), "null"),
    "undeclared-double-alias": ("zh", ("cmn", "zh-cmn"), "active"),
    "protected-alias": ("he", ("he", "iw"), "protected"),
}
STEM = "catalog-development-source-locale-alias"
SCALAR_ID = "harbor.development.source.scalar🧭"
PLURAL_ID = "harbor.development.source.plural🧭"
PROTECTED_ID = "Private development-source harbor"
SCALAR_SOURCE = "%2$@ %1$lld original beacon"
SCALAR_LOCALIZED = "%2$@ %1$lld translated beacon"
SOURCE_VALUES = {
    "one": "%1$lld original beacon %2$@",
    "other": "%1$lld original beacons %2$@",
}


def document(development: str, owned: str) -> dict[str, object]:
    return {
        "sourceLanguage": development,
        "version": "1.0",
        "strings": {
            SCALAR_ID: {
                "comment": "Compiler-equivalent development localization owns source text",
                "localizations": {
                    owned: {
                        "stringUnit": {"state": "needs_review", "value": SCALAR_SOURCE}
                    },
                    "de": {
                        "stringUnit": {
                            "state": "future_review",
                            "value": "Deutscher geschützter Hafen",
                        }
                    },
                },
            },
            PLURAL_ID: {
                "comment": "Aliased development locale owns plural source and states",
                "localizations": {
                    owned: plural(SOURCE_VALUES, SOURCE_STATES),
                    "de": plural(GERMAN_VALUES, GERMAN_STATES),
                },
            },
            PROTECTED_ID: {
                "shouldTranslate": False,
                "localizations": {
                    owned: {
                        "stringUnit": {
                            "state": "needs_review",
                            "value": "Protected original source",
                        }
                    },
                    "de": {
                        "stringUnit": {
                            "state": "translated",
                            "value": "Geschützter Quellhafen",
                        }
                    },
                },
            },
        },
    }


def expected(root: dict[str, object], owned: str) -> dict[str, object]:
    source_scalar = root["strings"][SCALAR_ID]["localizations"][owned]
    source_plural = root["strings"][PLURAL_ID]["localizations"][owned]
    scalar_metadata = {
        "appleSourceLocalizationIdentifier": owned,
        "appleSourceLocalization": source_scalar,
        "sourceState": "needs_review",
        "localizations": {
            "de": {"value": "Deutscher geschützter Hafen", "state": "future_review"}
        },
        "appleLocalizationSources": {
            "de": root["strings"][SCALAR_ID]["localizations"]["de"]
        },
    }
    plural_metadata = {
        "appleSourceLocalizationIdentifier": owned,
        "appleSourceLocalization": source_plural,
        "sourcePluralStates": SOURCE_STATES,
        "localizations": {
            "de": {"variants": GERMAN_VALUES, "variantStates": GERMAN_STATES}
        },
        "appleLocalizationSources": {
            "de": root["strings"][PLURAL_ID]["localizations"]["de"]
        },
    }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": root["sourceLanguage"],
        "messages": {
            PLURAL_ID: {
                "defaultMessage": (
                    "{count, plural, one {{count} original beacon {arg1}} "
                    "other {{count} original beacons {arg1}}}"
                ),
                "description": root["strings"][PLURAL_ID]["comment"],
                "variants": {
                    "one": "{count} original beacon {arg1}",
                    "other": "{count} original beacons {arg1}",
                },
                "placeholders": [
                    {
                        "name": "count",
                        "source": "%1$lld",
                        "kind": "integer",
                        "position": 1,
                    },
                    {
                        "name": "arg1",
                        "source": "%2$@",
                        "kind": "string",
                        "position": 2,
                    },
                ],
                "metadata": plural_metadata,
            },
            SCALAR_ID: {
                "defaultMessage": "{arg1} {arg0} original beacon",
                "description": root["strings"][SCALAR_ID]["comment"],
                "placeholders": [
                    {"name": "arg1", "source": "%2$@", "kind": "string", "position": 2},
                    {
                        "name": "arg0",
                        "source": "%1$lld",
                        "kind": "integer",
                        "position": 1,
                    },
                ],
                "metadata": scalar_metadata,
            },
        },
    }


def skeleton(source: str, encoding: str, owned: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    previous_identifier = None
    for identifier, value, variant in (
        (SCALAR_ID, SCALAR_SOURCE, None),
        (PLURAL_ID, SOURCE_VALUES["one"], "one"),
        (PLURAL_ID, SOURCE_VALUES["other"], "other"),
    ):
        beginning = (
            cursor
            if identifier == previous_identifier
            else source.index(json.dumps(identifier, ensure_ascii=False), cursor)
        )
        locale = (
            beginning
            if identifier == previous_identifier
            else source.index(json.dumps(owned), beginning)
        )
        match = re.compile(
            r'"value"\s*:\s*"('
            + re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
            + r')"'
        ).search(source, locale)
        if match is None:
            raise RuntimeError(f"Missing development source value: {identifier}/{variant}")
        start, end = match.span(1)
        slot = {
            "id": identifier,
            "start": bom + len(source[:start].encode(codec)),
            "end": bom + len(source[:end].encode(codec)),
        }
        if variant is not None:
            slot["variant"] = variant
        slots.append(slot)
        cursor = end
        previous_identifier = identifier
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "slots": slots,
    }


def localized(source: str, encoding: str, owned: str) -> str:
    values = {
        SCALAR_ID: SCALAR_LOCALIZED,
        f"{PLURAL_ID}#one": "%1$lld translated beacon %2$@",
        f"{PLURAL_ID}#other": "%1$lld translated beacons %2$@",
    }
    result = source
    for slot in reversed(skeleton(source, encoding, owned)["slots"]):
        key = slot["id"] + ("#" + slot["variant"] if slot.get("variant") else "")
        codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
        bom = 2 if encoding == "UTF-16LE-BOM" else 0
        start = len(source.encode(codec)[: slot["start"] - bom].decode(codec))
        end = len(source.encode(codec)[: slot["end"] - bom].decode(codec))
        replacement = json.dumps(values[key], ensure_ascii=False)[1:-1]
        result = result[:start] + replacement + result[end:]
    return result


def translations() -> dict[str, str]:
    return {
        SCALAR_ID: "{arg1} {arg0} translated beacon",
        f"{PLURAL_ID}#one": "{count} translated beacon {arg1}",
        f"{PLURAL_ID}#other": "{count} translated beacons {arg1}",
    }


def runtime_samples(translated: bool, locale: str) -> list[dict[str, object]]:
    prefix = "translated" if translated else "original"
    name = "Rowan"
    one = "beacons" if locale.startswith("zh") else "beacon"
    return [
        {
            "message": SCALAR_ID,
            "arguments": [3, "Rowan"],
            "expected": f"{name} 3 {prefix} beacon",
        },
        {
            "message": PLURAL_ID,
            "arguments": [1, "Rowan"],
            "expected": f"1 {prefix} {one} {name}",
        },
        {
            "message": PLURAL_ID,
            "arguments": [2, "Rowan"],
            "expected": f"2 {prefix} beacons {name}",
        },
    ]


def competing_document(
    development: str, owners: tuple[str, str], kind: str
) -> dict[str, object]:
    descriptor: dict[str, object] = {
        "localizations": {
            owners[0]: (
                None
                if kind == "null"
                else {"stringUnit": {"state": "translated", "value": "First source owner"}}
            ),
            owners[1]: {
                "stringUnit": {"state": "translated", "value": "Second source owner"}
            },
        }
    }
    if kind == "protected":
        descriptor["shouldTranslate"] = False
    return {
        "sourceLanguage": development,
        "version": "1.0",
        "strings": {"harbor.competing.development.source🧭": descriptor},
    }


def main() -> None:
    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_prefix = "apple-xcstrings-development-source-locale-"
    skeleton_prefix = "apple-xcstrings-source-skeleton-development-locale-"
    competing_prefix = "apple-xcstrings-development-source-owner-collision-"
    competing_skeleton_prefix = (
        "apple-xcstrings-source-skeleton-development-owner-collision-"
    )
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if not case["id"].startswith((case_prefix, competing_prefix))
    ]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]
    manifest["sourceSkeletonErrors"] = [
        case
        for case in manifest["sourceSkeletonErrors"]
        if not case["id"].startswith(competing_skeleton_prefix)
    ]
    for label, (development, owned, runtime) in PAIRS.items():
        stem = f"{STEM}-{label}"
        root = document(development, owned)
        source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
        original_path = APPLE / f"{stem}.xcstrings"
        original_path.write_text(source, encoding="utf-8")
        write_json(APPLE / f"{stem}.expected.json", expected(root, owned))
        write_json(APPLE / f"{stem}.compiled.json", compiled_xcatalog(original_path))
        write_json(APPLE / f"{stem}.translations.json", translations())
        manifest["cases"].append(
            {
                "id": case_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{stem}.xcstrings",
                "expected": f"fixtures/apple/{stem}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{stem}.compiled.json",
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode resolves development language {development} to source-owned "
                        f"localization {owned}; legacy routing cannot extract its real scalar/"
                        "plural text, hidden arguments, states, or original source slots."
                    ),
                },
                "xcstringsNormalized": f"fixtures/apple/{stem}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": (
                    f"fixtures/apple/{stem}.normalized.compiled.json"
                ),
            }
        )
        normalized = {
            "sourceLanguage": development,
            "strings": {
                identifier: {
                    key: value
                    for key, value in descriptor.items()
                    if key != "shouldTranslate"
                }
                for identifier, descriptor in root["strings"].items()
                if descriptor.get("shouldTranslate") is not False
            },
            "version": "1.0",
        }
        normalized_path = APPLE / f"{stem}.normalized.xcstrings"
        normalized_path.write_text(
            json.dumps(normalized, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        write_json(
            APPLE / f"{stem}.normalized.compiled.json",
            compiled_xcatalog(normalized_path),
        )
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            translated = localized(source, encoding, owned)
            localized_path = APPLE / f"{stem}.localized.xcstrings"
            localized_path.write_text(translated, encoding="utf-8")
            write_json(
                APPLE / f"{stem}.localized.compiled.json", compiled_xcatalog(localized_path)
            )
            write_json(
                APPLE / f"{stem}{suffix}.expected.skeleton.json",
                skeleton(source, encoding, owned),
            )
            manifest["sourceSkeletons"].append(
                {
                    "id": skeleton_prefix + label + ("-utf8" if not suffix else "-utf16"),
                    **({"encoding": encoding} if suffix else {}),
                    "format": "apple_xcstrings",
                    "input": f"fixtures/apple/{stem}.xcstrings",
                    "expected": f"fixtures/apple/{stem}{suffix}.expected.skeleton.json",
                    "translations": f"fixtures/apple/{stem}.translations.json",
                    "localized": f"fixtures/apple/{stem}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{stem}.compiled.json",
                    "xcstringsLocalizedCompiled": f"fixtures/apple/{stem}.localized.compiled.json",
                    "xcstringsSourceLocaleAlias": label,
                    "xcstringsRuntimeLocale": runtime,
                    "xcstringsFormattingLocale": runtime,
                    "xcstringsOriginalRuntimeSamples": runtime_samples(False, runtime),
                    "xcstringsLocalizedRuntimeSamples": runtime_samples(True, runtime),
                }
            )
    for label, (development, owners, kind) in COMPETING.items():
        stem = f"catalog-development-source-owner-collision-{label}"
        path = APPLE / f"{stem}.xcstrings"
        write_json(path, competing_document(development, owners, kind))
        compiled = compiled_xcatalog(path)
        write_json(APPLE / f"{stem}.compiled.json", compiled)
        bundle = next(iter(compiled), f"{development}.lproj/catalog.strings")
        values = [{bundle: {"harbor.competing.development.source🧭": "First source owner"}}]
        if kind == "null":
            alternatives = [
                {},
                {bundle: {"harbor.competing.development.source🧭": "Second source owner"}},
            ]
        else:
            alternatives = values + [
                {bundle: {"harbor.competing.development.source🧭": "Second source owner"}}
            ]
        write_json(APPLE / f"{stem}.compiled.alternatives.json", alternatives)
        manifest["cases"].append(
            {
                "id": competing_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{stem}.xcstrings",
                "error": "DUPLICATE_LOCALE",
                "xcstringsOracle": "accept",
                "xcstringsCompiled": f"fixtures/apple/{stem}.compiled.json",
                "xcstringsCompiledAlternatives": (
                    f"fixtures/apple/{stem}.compiled.alternatives.json"
                ),
                "xcstringsDevelopmentSourceCollision": kind,
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        "Xcode nondeterministically overwrites or suppresses equivalent "
                        "development-source values; safe extraction and source-template "
                        "ownership must reject the ambiguous locale collision."
                    ),
                },
            }
        )
        for encoding, suffix in (("UTF-8", "utf8"), ("UTF-16LE-BOM", "utf16")):
            manifest["sourceSkeletonErrors"].append(
                {
                    "id": competing_skeleton_prefix + label + "-" + suffix,
                    **({"encoding": encoding} if suffix == "utf16" else {}),
                    "format": "apple_xcstrings",
                    "input": f"fixtures/apple/{stem}.xcstrings",
                    "error": "DUPLICATE_LOCALE",
                }
            )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
