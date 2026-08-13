#!/usr/bin/env python3
"""Generate native Xcode target substitutions with aliased development owners."""

from __future__ import annotations

import copy
import json

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_target_substitution_fixtures import (
    APPLE,
    ROOT,
    TRANSLATIONS,
    catalog,
    document,
    localized,
    runtime_samples,
    skeleton,
)
from generate_apple_xcstrings_missing_target_substitution_fixtures import (
    TRANSLATIONS as ATOMIC_TRANSLATIONS,
    catalog as atomic_catalog,
    document as atomic_document,
    inserted_runtime_samples,
    localized_source as atomic_localized_source,
    skeleton as atomic_skeleton,
)
from generate_apple_xcstrings_first_locale_substitution_fixtures import (
    TRANSLATIONS as FIRST_TRANSLATIONS,
    catalog as first_catalog,
    document as first_document,
    localized as first_localized_source,
    runtime_samples as first_runtime_samples,
    skeleton as first_skeleton,
)


PAIRS = {
    "hebrew-deprecated": ("he", "iw"),
    "norwegian-bokmal": ("nb", "no-bok"),
    "serbian-default-script": ("sr", "sr-Cyrl"),
    "british-obsolete-territory": ("en-GB", "en-UK"),
    "mandarin-simplified-extlang": ("zh-Hans", "zh-cmn-Hans"),
}
STEM = "catalog-development-source-alias-target-substitutions"
ATOMIC_STEM = "catalog-development-source-alias-atomic-target-substitutions"
FIRST_STEM = "catalog-development-source-alias-first-target-substitutions"


def aliased(
    development: str, owned: str, root: dict[str, object] | None = None
) -> dict[str, object]:
    root = copy.deepcopy(document() if root is None else root)
    root["sourceLanguage"] = development
    for entry in root["strings"].values():
        source = entry["localizations"].pop("en")
        entry["localizations"] = {owned: source, **entry["localizations"]}
    return root


def expected(
    root: dict[str, object], owned: str, atomic: bool = False, first: bool = False
) -> dict[str, object]:
    compatible = copy.deepcopy(root)
    compatible["sourceLanguage"] = "en"
    for entry in compatible["strings"].values():
        source = entry["localizations"].pop(owned)
        entry["localizations"] = {"en": source, **entry["localizations"]}
    result = (
        first_catalog(compatible)
        if first
        else atomic_catalog(compatible)
        if atomic
        else catalog(compatible)
    )
    result["locale"] = root["sourceLanguage"]
    for descriptor in result["messages"].values():
        descriptor["metadata"]["appleSourceLocalizationIdentifier"] = owned
    return result


def normalized_document(root: dict[str, object]) -> dict[str, object]:
    entries = {}
    for identifier, original in root["strings"].items():
        if original.get("shouldTranslate") is False:
            continue
        entry = copy.deepcopy(original)
        entry.pop("shouldTranslate", None)
        entry["localizations"] = {
            locale: localization
            for locale, localization in entry["localizations"].items()
            if localization is not None
        }
        entries[identifier] = entry
    return {
        "sourceLanguage": root["sourceLanguage"],
        "strings": entries,
        "version": "1.0",
    }


def main() -> None:
    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_prefix = "apple-xcstrings-development-source-alias-target-substitutions-"
    skeleton_prefix = "apple-xcstrings-source-skeleton-development-alias-target-substitutions-"
    atomic_case_prefix = "apple-xcstrings-development-source-alias-atomic-target-substitutions-"
    atomic_skeleton_prefix = (
        "apple-xcstrings-source-skeleton-development-alias-atomic-target-substitutions-"
    )
    first_case_prefix = "apple-xcstrings-development-source-alias-first-target-substitutions-"
    first_skeleton_prefix = (
        "apple-xcstrings-source-skeleton-development-alias-first-target-substitutions-"
    )
    manifest["cases"] = [
        entry
        for entry in manifest["cases"]
        if not entry["id"].startswith(
            (case_prefix, atomic_case_prefix, first_case_prefix)
        )
    ]
    manifest["sourceSkeletons"] = [
        entry
        for entry in manifest["sourceSkeletons"]
        if not entry["id"].startswith(
            (skeleton_prefix, atomic_skeleton_prefix, first_skeleton_prefix)
        )
    ]
    for label, (development, owned) in PAIRS.items():
        stem = f"{STEM}-{label}"
        root = aliased(development, owned)
        source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
        original_path = APPLE / f"{stem}.xcstrings"
        original_path.write_text(source, encoding="utf-8")
        translated = localized(root)
        localized_path = APPLE / f"{stem}.localized.xcstrings"
        write_json(localized_path, translated)
        write_json(APPLE / f"{stem}.expected.json", expected(root, owned))
        write_json(APPLE / f"{stem}.compiled.json", compiled_xcatalog(original_path))
        write_json(
            APPLE / f"{stem}.localized.compiled.json", compiled_xcatalog(localized_path)
        )
        write_json(APPLE / f"{stem}.translations.json", TRANSLATIONS)
        normalized = normalized_document(root)
        normalized_path = APPLE / f"{stem}.normalized.xcstrings"
        normalized_path.write_text(
            json.dumps(normalized, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        write_json(
            APPLE / f"{stem}.normalized.compiled.json",
            compiled_xcatalog(normalized_path),
        )
        manifest["cases"].append(
            {
                "id": case_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{stem}.xcstrings",
                "expected": f"fixtures/apple/{stem}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{stem}.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/{stem}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": (
                    f"fixtures/apple/{stem}.normalized.compiled.json"
                ),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode binds development language {development} to {owned}; "
                        "legacy routing cannot own source device/substitution trees, "
                        "target-only plural branches, hidden arguments, or exact bytes."
                    ),
                },
            }
        )
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            snapshot = skeleton(source, encoding)
            write_json(APPLE / f"{stem}{suffix}.expected.skeleton.json", snapshot)
            manifest["sourceSkeletons"].append(
                {
                    "id": skeleton_prefix + label + ("-utf16" if suffix else "-utf8"),
                    **({"encoding": encoding} if suffix else {}),
                    "format": "apple_xcstrings",
                    "input": f"fixtures/apple/{stem}.xcstrings",
                    "expected": f"fixtures/apple/{stem}{suffix}.expected.skeleton.json",
                    "translations": f"fixtures/apple/{stem}.translations.json",
                    "localized": f"fixtures/apple/{stem}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{stem}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{stem}.localized.compiled.json"
                    ),
                    "xcstringsTargetLocale": "ru",
                    "xcstringsSubstitutionSlots": True,
                    "xcstringsTargetSubstitutionSlots": True,
                    "xcstringsTargetDeviceSlots": True,
                    "xcstringsSourceAliasTargetSubstitutions": label,
                    "xcstringsRuntimeLocale": "ru",
                    "xcstringsFormattingLocale": "ru",
                    "xcstringsOriginalRuntimeSamples": runtime_samples(False),
                    "xcstringsLocalizedRuntimeSamples": runtime_samples(True),
                }
            )
        atomic_stem = f"{ATOMIC_STEM}-{label}"
        atomic_root = aliased(development, owned, atomic_document())
        atomic_source = json.dumps(atomic_root, ensure_ascii=False, indent=2) + "\n"
        atomic_original_path = APPLE / f"{atomic_stem}.xcstrings"
        atomic_original_path.write_text(atomic_source, encoding="utf-8")
        atomic_localized = atomic_localized_source(atomic_source, atomic_root)
        atomic_localized_path = APPLE / f"{atomic_stem}.localized.xcstrings"
        atomic_localized_path.write_text(atomic_localized, encoding="utf-8")
        write_json(APPLE / f"{atomic_stem}.expected.json", expected(atomic_root, owned, True))
        write_json(APPLE / f"{atomic_stem}.compiled.json", compiled_xcatalog(atomic_original_path))
        write_json(
            APPLE / f"{atomic_stem}.localized.compiled.json",
            compiled_xcatalog(atomic_localized_path),
        )
        write_json(APPLE / f"{atomic_stem}.translations.json", ATOMIC_TRANSLATIONS)
        atomic_normalized = normalized_document(atomic_root)
        atomic_normalized_path = APPLE / f"{atomic_stem}.normalized.xcstrings"
        atomic_normalized_path.write_text(
            json.dumps(atomic_normalized, ensure_ascii=False, sort_keys=True, indent=2)
            + "\n",
            encoding="utf-8",
        )
        write_json(
            APPLE / f"{atomic_stem}.normalized.compiled.json",
            compiled_xcatalog(atomic_normalized_path),
        )
        manifest["cases"].append(
            {
                "id": atomic_case_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{atomic_stem}.xcstrings",
                "expected": f"fixtures/apple/{atomic_stem}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{atomic_stem}.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/{atomic_stem}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": (
                    f"fixtures/apple/{atomic_stem}.normalized.compiled.json"
                ),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode binds development language {development} to {owned}; "
                        "legacy routing cannot atomically insert missing/null Russian "
                        "device/substitution trees, hidden arguments, or exact bytes."
                    ),
                },
            }
        )
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            snapshot = atomic_skeleton(atomic_source, encoding)
            write_json(APPLE / f"{atomic_stem}{suffix}.expected.skeleton.json", snapshot)
            manifest["sourceSkeletons"].append(
                {
                    "id": atomic_skeleton_prefix
                    + label
                    + ("-utf16" if suffix else "-utf8"),
                    **({"encoding": encoding} if suffix else {}),
                    "format": "apple_xcstrings",
                    "input": f"fixtures/apple/{atomic_stem}.xcstrings",
                    "expected": (
                        f"fixtures/apple/{atomic_stem}{suffix}.expected.skeleton.json"
                    ),
                    "translations": f"fixtures/apple/{atomic_stem}.translations.json",
                    "localized": f"fixtures/apple/{atomic_stem}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{atomic_stem}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{atomic_stem}.localized.compiled.json"
                    ),
                    "xcstringsTargetLocale": "ru",
                    "xcstringsSubstitutionSlots": True,
                    "xcstringsTargetSubstitutionSlots": True,
                    "xcstringsTargetDeviceSlots": True,
                    "xcstringsTargetSubstitutionInsertion": True,
                    "xcstringsSourceAliasTargetSubstitutions": label,
                    "xcstringsSourceAliasAtomicSubstitutions": label,
                    "xcstringsRuntimeLocale": "ru",
                    "xcstringsFormattingLocale": "ru",
                    "xcstringsOriginalRuntimeSamples": runtime_samples(False),
                    "xcstringsLocalizedRuntimeSamples": runtime_samples(True)
                    + inserted_runtime_samples(),
                }
            )
        first_stem = f"{FIRST_STEM}-{label}"
        first_root = aliased(development, owned, first_document())
        first_source = json.dumps(first_root, ensure_ascii=False, indent=2) + "\n"
        first_original_path = APPLE / f"{first_stem}.xcstrings"
        first_original_path.write_text(first_source, encoding="utf-8")
        first_localized = first_localized_source(first_source)
        first_localized_path = APPLE / f"{first_stem}.localized.xcstrings"
        first_localized_path.write_text(first_localized, encoding="utf-8")
        write_json(
            APPLE / f"{first_stem}.expected.json",
            expected(first_root, owned, first=True),
        )
        write_json(APPLE / f"{first_stem}.compiled.json", compiled_xcatalog(first_original_path))
        write_json(
            APPLE / f"{first_stem}.localized.compiled.json",
            compiled_xcatalog(first_localized_path),
        )
        write_json(APPLE / f"{first_stem}.translations.json", FIRST_TRANSLATIONS)
        first_normalized_path = APPLE / f"{first_stem}.normalized.xcstrings"
        first_normalized_path.write_text(
            json.dumps(
                normalized_document(first_root), ensure_ascii=False, sort_keys=True, indent=2
            )
            + "\n",
            encoding="utf-8",
        )
        write_json(
            APPLE / f"{first_stem}.normalized.compiled.json",
            compiled_xcatalog(first_normalized_path),
        )
        manifest["cases"].append(
            {
                "id": first_case_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{first_stem}.xcstrings",
                "expected": f"fixtures/apple/{first_stem}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{first_stem}.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/{first_stem}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": (
                    f"fixtures/apple/{first_stem}.normalized.compiled.json"
                ),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode binds development language {development} to {owned}; "
                        "legacy routing cannot derive first-locale Russian substitution "
                        "categories from ICU rules or preserve alias-owned source bytes."
                    ),
                },
            }
        )
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            write_json(
                APPLE / f"{first_stem}{suffix}.expected.skeleton.json",
                first_skeleton(first_source, encoding),
            )
            manifest["sourceSkeletons"].append(
                {
                    "id": first_skeleton_prefix + label + ("-utf16" if suffix else "-utf8"),
                    **({"encoding": encoding} if suffix else {}),
                    "format": "apple_xcstrings",
                    "input": f"fixtures/apple/{first_stem}.xcstrings",
                    "expected": (
                        f"fixtures/apple/{first_stem}{suffix}.expected.skeleton.json"
                    ),
                    "translations": f"fixtures/apple/{first_stem}.translations.json",
                    "localized": f"fixtures/apple/{first_stem}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{first_stem}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{first_stem}.localized.compiled.json"
                    ),
                    "xcstringsTargetLocale": "ru",
                    "xcstringsTargetSubstitutionInsertion": True,
                    "xcstringsTargetDeviceInsertion": True,
                    "xcstringsFirstLocaleCategories": True,
                    "xcstringsFirstLocaleSubstitutions": True,
                    "xcstringsSourceAliasFirstLocaleSubstitutions": label,
                    "xcstringsRuntimeLocale": "ru",
                    "xcstringsFormattingLocale": "ru",
                    "xcstringsOriginalRuntimeSamples": [],
                    "xcstringsLocalizedRuntimeSamples": first_runtime_samples(),
                }
            )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
