#!/usr/bin/env python3
"""Generate native-verified, independently owned Xcode target substitutions."""

from __future__ import annotations

import copy
import json
import re
from pathlib import Path

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "catalog-target-russian-substitutions"
SCALAR_ID = "harbor.target.substitution.scalar🧭"
DEVICE_ID = "harbor.target.substitution.device🧭"
PROTECTED_ID = "Private target Russian substitution branches"
CATEGORIES = ("one", "few", "many", "other")
STATES = {
    "one": "needs_review",
    "few": "new",
    "many": "future_review",
    "other": "translated",
}
WORDS = {
    "lanes": {"one": "полоса", "few": "полосы", "many": "полос", "other": "полосы"},
    "lights": {"one": "огонь", "few": "огня", "many": "огней", "other": "огня"},
}
POSITIONS = {"lanes": 1, "lights": 2}
SPECIFIERS = {"lanes": "lld", "lights": "d"}


def unit(value: str, state: str) -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def definitions(target: bool) -> dict[str, object]:
    return {
        selector: {
            "argNum": POSITIONS[selector],
            "formatSpecifier": SPECIFIERS[selector],
            "variations": {
                "plural": {
                    category: unit(
                        (
                            (
                                f"%{POSITIONS[selector]}${SPECIFIERS[selector]} %4$n "
                                f"{WORDS[selector][category]}"
                            )
                            if target
                            else (
                                f"%{POSITIONS[selector]}${SPECIFIERS[selector]} "
                                f"{'open lane' if selector == 'lanes' else 'dock light'}"
                                f"{'s' if category == 'other' else ''}"
                            )
                        ),
                        (
                            STATES[category]
                            if target
                            else "needs_review" if category == "one" else "translated"
                        ),
                    )
                    for category in CATEGORIES
                    if target or category in {"one", "other"}
                }
            },
        }
        for selector in POSITIONS
    }


def document() -> dict[str, object]:
    scalar_source = {
        "stringUnit": {
            "state": "needs_review",
            "value": "Guide %3$@: %#@lanes@ beside %2$#@lights@",
        },
        "substitutions": definitions(False),
    }
    scalar_target = {
        "stringUnit": {
            "state": "future_review",
            "value": "Маршрут %3$@: %#@lanes@ рядом %2$#@lights@",
        },
        "substitutions": definitions(True),
    }
    device_source = {
        "substitutions": definitions(False),
        "variations": {
            "device": {
                "iphone": unit(
                    "Touch %3$@: %#@lanes@ beside %2$#@lights@", "needs_review"
                ),
                "mac": unit("Click %3$@: %2$#@lights@ around %#@lanes@", "translated"),
            }
        },
    }
    device_target = {
        "substitutions": definitions(True),
        "variations": {
            "device": {
                "iphone": unit("На iPhone %3$@: %#@lanes@ рядом %2$#@lights@", "new"),
                "mac": unit(
                    "На Mac %3$@: %2$#@lights@ вокруг %#@lanes@", "future_review"
                ),
            }
        },
    }
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            SCALAR_ID: {
                "comment": "Russian target definitions own plural categories independently",
                "localizations": {"en": scalar_source, "ru": scalar_target},
            },
            DEVICE_ID: {
                "comment": "Russian target substitutions are shared by iPhone and Mac roots",
                "localizations": {"en": device_source, "ru": device_target},
            },
            PROTECTED_ID: {
                "shouldTranslate": False,
                "localizations": {
                    "en": copy.deepcopy(scalar_source),
                    "ru": copy.deepcopy(scalar_target),
                },
            },
        },
    }


def message(identifier: str, entry: dict[str, object]) -> dict[str, object]:
    source = entry["localizations"]["en"]
    target = entry["localizations"]["ru"]
    device = "variations" in source
    effective = source["variations"]["device"]["iphone"] if device else source
    lanes = "{lanes, plural, one {{lanes} open lane} other {{lanes} open lanes}}"
    lights = "{lights, plural, one {{lights} dock light} other {{lights} dock lights}}"
    default = f"{'Touch' if device else 'Guide'} {{arg2}}: {lanes} beside {lights}"
    metadata = {
        "sourceSubstitutions": source["substitutions"],
        "appleSourceLocalization": source,
        "sourceState": effective["stringUnit"]["state"],
        "localizations": {
            "ru": (
                {"variationAxes": {"device": target["variations"]["device"]}}
                if device
                else {
                    "value": target["stringUnit"]["value"],
                    "state": target["stringUnit"]["state"],
                }
            )
        },
        "appleLocalizationSources": {"ru": target},
    }
    if device:
        metadata["sourceVariationAxes"] = {"device": source["variations"]["device"]}
        metadata["defaultDevice"] = "iphone"
    return {
        "defaultMessage": default,
        "description": entry["comment"],
        "placeholders": [
            {
                "name": "lanes",
                "source": "%1$lld",
                "kind": "integer",
                "position": 1,
            },
            {
                "name": "lights",
                "source": "%2$d",
                "kind": "integer",
                "position": 2,
            },
            {
                "name": "arg2",
                "source": "%3$@",
                "kind": "string",
                "position": 3,
            },
        ],
        "metadata": metadata,
    }


def catalog(root: dict[str, object]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": {
            identifier: message(identifier, entry)
            for identifier, entry in sorted(root["strings"].items())
            if entry.get("shouldTranslate") is not False
        },
    }


ROOT_TRANSLATIONS = {
    SCALAR_ID: "Маршрут {arg2}: {lights} впереди {lanes}",
    f"{DEVICE_ID}#@device#iphone": "Экран {arg2}: {lights} затем {lanes}",
    f"{DEVICE_ID}#@device#mac": "Рабочий стол {arg2}: {lanes} после {lights}",
}
TRANSLATIONS = {
    **ROOT_TRANSLATIONS,
    **{
        f"{identifier}#{selector}#{category}": (
            f"{{{selector}}}  обновлённый {WORDS[selector][category]}"
        )
        for identifier in (SCALAR_ID, DEVICE_ID)
        for selector in POSITIONS
        for category in CATEGORIES
    },
}


def localized(root: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(root)
    for identifier in (SCALAR_ID, DEVICE_ID):
        target = result["strings"][identifier]["localizations"]["ru"]
        for selector in POSITIONS:
            for category in CATEGORIES:
                target["substitutions"][selector]["variations"]["plural"][category][
                    "stringUnit"
                ]["value"] = (
                    f"%{POSITIONS[selector]}${SPECIFIERS[selector]} %4$n "
                    f"обновлённый {WORDS[selector][category]}"
                )
        if identifier == SCALAR_ID:
            target["stringUnit"][
                "value"
            ] = "Маршрут %3$@: %2$#@lights@ впереди %#@lanes@"
        else:
            target["variations"]["device"]["iphone"]["stringUnit"][
                "value"
            ] = "Экран %3$@: %2$#@lights@ затем %#@lanes@"
            target["variations"]["device"]["mac"]["stringUnit"][
                "value"
            ] = "Рабочий стол %3$@: %#@lanes@ после %2$#@lights@"
    return result


def ownership(target: dict[str, object]) -> list[tuple[str | None, str | None, str]]:
    result = []
    for field, value in target.items():
        if field == "stringUnit":
            result.append((None, None, value["value"]))
        elif field == "substitutions":
            for selector, definition in value.items():
                for category, branch in definition["variations"]["plural"].items():
                    result.append((selector, category, branch["stringUnit"]["value"]))
        elif field == "variations":
            for device, branch in value["device"].items():
                result.append(("@device", device, branch["stringUnit"]["value"]))
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
        localizations = source.index('"localizations"', beginning + len(key))
        opening = source.index("{", localizations)
        closing = object_end(source, opening)
        target = source.index('"ru"', opening, closing)
        target_opening = source.index("{", target)
        target_closing = object_end(source, target_opening)
        branch_cursor = target_opening
        for selector, variant, value in ownership(entry["localizations"]["ru"]):
            escaped = re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
            match = re.compile(r'"value"\s*:\s*"(' + escaped + r')"').search(
                source, branch_cursor, target_closing
            )
            if match is None:
                raise RuntimeError(
                    f"Missing Russian substitution: {identifier}/{selector}/{variant}"
                )
            start, end = match.span(1)
            slot = {
                "id": identifier,
                "start": bom + len(source[:start].encode(codec)),
                "end": bom + len(source[:end].encode(codec)),
            }
            if selector is not None:
                slot["selector"] = selector
                slot["variant"] = variant
            slots.append(slot)
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


def runtime_samples(translated: bool) -> list[dict[str, object]]:
    samples = []
    for lanes, lights, lane_category, light_category in (
        (1, 1, "one", "one"),
        (2, 5, "few", "many"),
        (5, 2, "many", "few"),
        (21, 22, "one", "few"),
        (25, 25, "many", "many"),
    ):
        lane = f"{lanes}  {'обновлённый ' if translated else ''}{WORDS['lanes'][lane_category]}"
        light = (
            f"{lights}  {'обновлённый ' if translated else ''}"
            f"{WORDS['lights'][light_category]}"
        )
        samples.extend(
            [
                {
                    "message": SCALAR_ID,
                    "arguments": [lanes, lights, "Rowan", 0],
                    "expected": (
                        f"Маршрут Rowan: {light} впереди {lane}"
                        if translated
                        else f"Маршрут Rowan: {lane} рядом {light}"
                    ),
                },
                {
                    "message": DEVICE_ID,
                    "arguments": [lanes, lights, "Rowan", 0],
                    "expected": (
                        f"Рабочий стол Rowan: {lane} после {light}"
                        if translated
                        else f"На Mac Rowan: {light} вокруг {lane}"
                    ),
                },
            ]
        )
    return samples


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    translated = json.dumps(localized(root), ensure_ascii=False, indent=2) + "\n"
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

    path = ROOT / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    case_id = "apple-xcstrings-independent-russian-target-substitution-categories-and-device-roots"
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
                "reason": (
                    "Legacy routing cannot independently preserve target-language native "
                    "substitution definitions, target-only plural categories, iPhone/Mac "
                    "root markers, review states, hidden arguments, and original bytes."
                ),
            },
        }
    )
    prefix = "apple-xcstrings-source-skeleton-target-russian-substitution-categories"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    common = {
        "format": "apple_xcstrings",
        "xcstringsTargetLocale": "ru",
        "xcstringsSubstitutionSlots": True,
        "xcstringsTargetSubstitutionSlots": True,
        "xcstringsTargetDeviceSlots": True,
        "xcstringsRuntimeLocale": "ru",
        "xcstringsFormattingLocale": "ru",
        "input": f"fixtures/apple/{STEM}.xcstrings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
        "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "xcstringsOriginalRuntimeSamples": runtime_samples(False),
        "xcstringsLocalizedRuntimeSamples": runtime_samples(True),
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
    write_json(path, manifest)


if __name__ == "__main__":
    main()
