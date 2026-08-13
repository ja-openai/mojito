#!/usr/bin/env python3
"""Generate native-verified atomic insertion of missing Xcode target plurals."""

from __future__ import annotations

import copy
import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_target_plural_fixtures import (
    APPLE,
    NATIVE_TRANSLATIONS as EXISTING_NATIVE,
    ROOT,
    TARGET_VALUES,
    TRANSLATIONS as EXISTING_TRANSLATIONS,
    catalog as existing_catalog,
    document as existing_document,
    plural,
    runtime_samples as existing_runtime_samples,
)


STEM = "catalog-target-russian-plural-insertion"
MISSING_ID = "harbor.target.russian.missing🧭"
NULL_ID = "harbor.target.russian.null🧭"
PROTECTED_MISSING = "Private missing Russian plural"
PROTECTED_NULL = "Private null Russian plural"
INSERT_SOURCE = {
    "one": "%1$lld %3$n beacon %2$@",
    "other": "%1$lld %3$n beacons %2$@",
}
SOURCE_STATES = {"one": "needs_review", "other": "translated"}
GERMAN_VALUES = {
    "one": "%1$lld Signal %2$@",
    "other": "%1$lld Signale %2$@",
}
GERMAN_STATES = {"one": "translated", "other": "future_review"}
ENDINGS = {
    "one": "маяк",
    "few": "маяка",
    "many": "маяков",
    "other": "маяка",
}
SUFFIXES = {MISSING_ID: "в гавани", NULL_ID: "у берега"}
ATOMIC_TRANSLATIONS = {
    identifier: "{count, plural, "
    + " ".join(
        category + " {{arg1} {count}  " + ENDINGS[category] + " " + suffix + "}"
        for category in TARGET_VALUES
    )
    + "}"
    for identifier, suffix in SUFFIXES.items()
}
TRANSLATIONS = {**EXISTING_TRANSLATIONS, **ATOMIC_TRANSLATIONS}


def document() -> dict[str, object]:
    root = copy.deepcopy(existing_document())
    entries = root["strings"]
    for identifier in (MISSING_ID, NULL_ID):
        localizations = {
            "en": plural(INSERT_SOURCE, SOURCE_STATES),
            "de": plural(GERMAN_VALUES, GERMAN_STATES),
        }
        if identifier == NULL_ID:
            localizations["ru"] = None
        entries[identifier] = {
            "comment": "One atomic ICU message owns every newly inserted target category",
            "localizations": localizations,
        }
    entries[PROTECTED_MISSING] = {
        "shouldTranslate": False,
        "localizations": {"en": plural(INSERT_SOURCE, SOURCE_STATES)},
    }
    entries[PROTECTED_NULL] = {
        "shouldTranslate": False,
        "localizations": {
            "en": plural(INSERT_SOURCE, SOURCE_STATES),
            "ru": None,
        },
    }
    return root


def catalog(root: dict[str, object]) -> dict[str, object]:
    result = existing_catalog(root)
    for identifier in (MISSING_ID, NULL_ID):
        entry = root["strings"][identifier]
        source = entry["localizations"]["en"]
        german = entry["localizations"]["de"]
        conversions = [{"position": 8, "source": "%3$n", "argumentPosition": 3}]
        result["messages"][identifier] = {
            "defaultMessage": (
                "{count, plural, one {{count}  beacon {arg1}} "
                "other {{count}  beacons {arg1}}}"
            ),
            "description": entry["comment"],
            "variants": {
                "one": "{count}  beacon {arg1}",
                "other": "{count}  beacons {arg1}",
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
            "metadata": {
                "appleSourceLocalization": source,
                "sourcePluralStates": SOURCE_STATES,
                "applePluralDisabledPrintfConversions": {
                    "count": {"one": conversions, "other": conversions}
                },
                "localizations": {
                    "de": {
                        "variants": GERMAN_VALUES,
                        "variantStates": GERMAN_STATES,
                    }
                },
                "appleLocalizationSources": {"de": german},
            },
        }
    result["messages"] = dict(sorted(result["messages"].items()))
    return result


def skeleton(source: str, encoding: str) -> dict[str, object]:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    slots = []
    cursor = 0
    for identifier, entry in document()["strings"].items():
        if entry.get("shouldTranslate") is False:
            continue
        key = json.dumps(identifier, ensure_ascii=False)
        beginning = source.index(key, cursor)
        field = source.index('"localizations"', beginning + len(key))
        opening = source.index("{", field)
        closing = object_end(source, opening)
        localizations = entry["localizations"]
        if "ru" not in localizations:
            position = closing
            while source[position - 1] in " \t\r\n":
                position -= 1
            start = end = position
            slots.append(slot(identifier, None, start, end, source, codec, bom))
        elif localizations["ru"] is None:
            match = re.compile(r'"ru"\s*:\s*(null)').search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing nullable Russian target: {identifier}")
            start, end = match.span(1)
            slots.append(slot(identifier, None, start, end, source, codec, bom))
        else:
            target_start = source.index('"ru"', opening, closing)
            target_opening = source.index("{", target_start)
            target_closing = object_end(source, target_opening)
            target = localizations["ru"]
            branches = (
                {
                    category: branch["stringUnit"]["value"]
                    for category, branch in target["variations"]["plural"].items()
                }
                if "variations" in target
                else {None: target["stringUnit"]["value"]}
            )
            branch_cursor = target_opening
            for category, value in branches.items():
                escaped = re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
                match = re.compile(r'"value"\s*:\s*"(' + escaped + r')"').search(
                    source, branch_cursor, target_closing
                )
                if match is None:
                    raise RuntimeError(
                        f"Missing Russian target branch: {identifier}/{category}"
                    )
                start, end = match.span(1)
                slots.append(slot(identifier, category, start, end, source, codec, bom))
                branch_cursor = match.end()
        cursor = closing + 1
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": encoding,
        "source": source,
        "appleTargetLocale": "ru",
        "slots": slots,
    }


def slot(
    identifier: str,
    category: str | None,
    start: int,
    end: int,
    source: str,
    codec: str,
    bom: int,
) -> dict[str, object]:
    result = {
        "id": identifier,
        "start": bom + len(source[:start].encode(codec)),
        "end": bom + len(source[:end].encode(codec)),
    }
    if category is not None:
        result["variant"] = category
    return result


def inserted(identifier: str) -> dict[str, object]:
    suffix = SUFFIXES[identifier]
    return plural(
        {
            category: "%2$@ %1$lld %3$n " + ending + " " + suffix
            for category, ending in sorted(ENDINGS.items())
        },
        {category: "translated" for category in ENDINGS},
    )


def localized(source: str) -> str:
    result = source
    for value in reversed(skeleton(source, "UTF-8")["slots"]):
        start = len(source.encode()[: value["start"]].decode())
        end = len(source.encode()[: value["end"]].decode())
        identifier = value["id"]
        if identifier in SUFFIXES:
            replacement = json.dumps(
                inserted(identifier), ensure_ascii=False, separators=(",", ":")
            )
            if start == end:
                replacement = ',"ru":' + replacement
        else:
            key = identifier + ("#" + value["variant"] if "variant" in value else "")
            replacement = json.dumps(EXISTING_NATIVE[key], ensure_ascii=False)[1:-1]
        result = result[:start] + replacement + result[end:]
    return result


def inserted_runtime_samples() -> list[dict[str, object]]:
    values = []
    for identifier, suffix in SUFFIXES.items():
        for count, category in (
            (0, "many"),
            (1, "one"),
            (2, "few"),
            (5, "many"),
            (21, "one"),
            (22, "few"),
            (25, "many"),
        ):
            values.append(
                {
                    "message": identifier,
                    "arguments": [count, "Rowan", 0],
                    "expected": f"Rowan {count}  {ENDINGS[category]} {suffix}",
                }
            )
    return values


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = localized(source)
    source_path = APPLE / f"{STEM}.xcstrings"
    translated_path = APPLE / f"{STEM}.localized.xcstrings"
    source_path.write_text(source, encoding="utf-8")
    translated_path.write_text(translated, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.translations.json", TRANSLATIONS)
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(source_path))
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compiled_xcatalog(translated_path)
    )
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            skeleton(source, encoding),
        )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-atomic-missing-and-null-russian-target-plural-trees"
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
                "reason": "Legacy routing cannot atomically insert missing/null target plural trees, infer target-only categories, or preserve native hidden arguments and untouched source bytes.",
            },
        }
    )
    prefix = (
        "apple-xcstrings-source-skeleton-inserts-missing-russian-target-plural-trees"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsTargetPlural": True,
        "xcstringsTargetPluralInsertion": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": existing_runtime_samples(False),
        "xcstringsLocalizedRuntimeSamples": (
            existing_runtime_samples(True) + inserted_runtime_samples()
        ),
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
