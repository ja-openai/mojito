#!/usr/bin/env python3
"""Generate native-verified script identity and silent Xcode collision fixtures."""

from __future__ import annotations

import json
import re

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json
from generate_apple_xcstrings_missing_source_fixtures import object_end
from generate_apple_xcstrings_missing_target_plural_fixtures import (
    APPLE,
    GERMAN_STATES,
    GERMAN_VALUES,
    INSERT_SOURCE,
    ROOT,
    SOURCE_STATES,
    plural,
)


STEM = "catalog-first-serbian-script-locales"
LATIN_ID = "harbor.first.serbian.latin🧭"
CYRILLIC_ID = "harbor.first.serbian.cyrillic🧭"
PROTECTED_ID = "Private Serbian script harbor"
LOCALES = {LATIN_ID: "sr_Latn", CYRILLIC_ID: "sr-Cyrl"}
CATEGORIES = ("few", "one", "other")
LABELS = {
    "sr_Latn": {LATIN_ID: "Latinični", CYRILLIC_ID: "Ćirilični"},
    "sr-Cyrl": {LATIN_ID: "Латинични", CYRILLIC_ID: "Ћирилични"},
}
COLLISIONS = {
    "serbian-cyrillic": ("sr", "sr-Cyrl"),
    "serbian-cyrillic-region": ("sr-RS", "sr-Cyrl-RS"),
    "azerbaijani-latin": ("az", "az-Latn"),
    "azerbaijani-latin-region": ("az-AZ", "az-Latn-AZ"),
    "uzbek-latin": ("uz", "uz-Latn"),
    "uzbek-latin-region": ("uz-UZ", "uz-Latn-UZ"),
    "hebrew-deprecated": ("iw", "he"),
    "hebrew-deprecated-region": ("iw-IL", "he-IL"),
    "indonesian-deprecated": ("in", "id"),
    "indonesian-deprecated-region": ("in-ID", "id-ID"),
    "yiddish-deprecated": ("ji", "yi"),
    "norwegian-bokmal": ("no", "nb"),
    "english-region-case": ("en-us", "en-US"),
    "english-language-case": ("EN-us", "en-US"),
    "serbian-script-case": ("sr-LATN", "sr-Latn"),
    "serbian-cyrillic-case": ("sr-cyrl", "sr-Cyrl"),
    "mongolian-cyrillic": ("mn", "mn-Cyrl"),
    "mongolian-cyrillic-region": ("mn-MN", "mn-Cyrl-MN"),
    "kazakh-cyrillic": ("kk", "kk-Cyrl"),
    "kazakh-cyrillic-region": ("kk-KZ", "kk-Cyrl-KZ"),
    "bosnian-latin": ("bs", "bs-Latn"),
    "croatian-latin": ("hr", "hr-Latn"),
    "punjabi-gurmukhi": ("pa", "pa-Guru"),
    "hausa-latin": ("ha", "ha-Latn"),
    "hebrew-deprecated-underscore-region": ("iw_IL", "he_IL"),
    "portuguese-underscore-region-case": ("pt_BR", "pt_br"),
    "serbian-underscore-cyrillic-region": ("sr_Cyrl_RS", "sr_RS"),
    "azerbaijani-underscore-latin-region": ("az_Latn_AZ", "az_AZ"),
    "chinese-underscore-simplified-region": ("zh_Hans_CN", "zh_CN"),
    "chinese-underscore-traditional-region": ("zh_Hant_TW", "zh_TW"),
    "english-obsolete-united-kingdom": ("en-UK", "en-GB"),
    "english-obsolete-united-kingdom-underscore": ("en_UK", "en_GB"),
    "czech-obsolete-czechoslovakia": ("cs-CS", "cs-CZ"),
    "tagalog-filipino": ("tl", "fil"),
    "tagalog-filipino-region": ("tl-PH", "fil-PH"),
    "tagalog-filipino-underscore-region": ("tl_PH", "fil_PH"),
    "javanese-deprecated": ("jw", "jv"),
    "javanese-deprecated-region": ("jw-ID", "jv-ID"),
    "serbo-croatian-cyrillic-region": ("sh-RS", "sr-RS"),
    "serbo-croatian-underscore-region": ("sh_RS", "sr_RS"),
    "serbo-croatian-latin-region": ("sh-Latn-RS", "sr-Latn-RS"),
    "grandfathered-klingon": ("i-klingon", "tlh"),
    "grandfathered-klingon-region": ("i-klingon-US", "tlh-US"),
    "grandfathered-amis": ("i-ami", "ami"),
    "grandfathered-bunun": ("i-bnn", "bnn"),
    "grandfathered-hakka": ("i-hak", "hak"),
    "grandfathered-luxembourgish": ("i-lux", "lb"),
    "grandfathered-navajo": ("i-navajo", "nv"),
    "grandfathered-paiwan": ("i-pwn", "pwn"),
    "grandfathered-tao": ("i-tao", "tao"),
    "grandfathered-atayal": ("i-tay", "tay"),
    "grandfathered-tsou": ("i-tsu", "tsu"),
    "grandfathered-belgian-sign-language": ("sgn-BE-FR", "sfb"),
    "grandfathered-flemish-sign-language": ("sgn-BE-NL", "vgt"),
    "grandfathered-swiss-sign-language": ("sgn-CH-DE", "sgg"),
    "grandfathered-norwegian-bokmal": ("no-bok", "nb"),
    "grandfathered-norwegian-bokmal-region": ("no-bok-NO", "nb-NO"),
    "grandfathered-norwegian-nynorsk": ("no-nyn", "nn"),
    "grandfathered-norwegian-nynorsk-region": ("no-nyn-NO", "nn-NO"),
    "grandfathered-lojban": ("art-lojban", "jbo"),
    "grandfathered-min-nan": ("zh-min-nan", "nan"),
    "grandfathered-mandarin": ("zh-guoyu", "zh"),
    "grandfathered-mandarin-three-letter": ("zh-guoyu", "cmn"),
    "grandfathered-hakka-region": ("zh-hakka-TW", "hak-TW"),
    "grandfathered-xiang-region": ("zh-xiang-CN", "hsn-CN"),
    "grandfathered-cantonese-region": ("zh-yue-HK", "yue-HK"),
    "mandarin-three-letter": ("cmn", "zh"),
    "mandarin-three-letter-simplified": ("cmn-Hans", "zh-Hans"),
    "mandarin-three-letter-traditional": ("cmn-Hant", "zh-Hant"),
    "serbo-croatian-three-letter": ("hbs", "sr-Latn"),
    "serbo-croatian-three-letter-region": ("hbs-RS", "sr-Latn-RS"),
    "moldovan-three-letter": ("mol", "mo"),
    "moldovan-three-letter-region": ("mol-MD", "mo-MD"),
    "norwegian-bokmal-region": ("no-NO", "nb-NO"),
    "norwegian-bokmal-underscore-region": ("no_NO", "nb_NO"),
    "extlang-mandarin-simplified": ("zh-cmn-Hans", "zh-Hans"),
    "extlang-mandarin-traditional": ("zh-cmn-Hant", "zh-Hant"),
    "english-variant-case": ("en-US-posix", "en-US-POSIX"),
    "catalan-variant-case": ("ca-ES-valencia", "ca-ES-VALENCIA"),
    "private-extension-case": ("en-x-HARBOR", "en-x-harbor"),
    "unicode-numbering-system": ("en-US-u-nu-latn", "en-US-u-nu"),
    "unicode-numbering-system-case": ("en-US-u-nu-latn", "en-US-u-nu-Latn"),
}
DISTINCT = {
    "serbo-croatian-standalone-latin": ("sh", "sr-Latn"),
    "serbo-croatian-explicit-latin": ("sh-Latn", "sr-Latn"),
    "myanmar-historical-region": ("en-BU", "en-MM"),
    "german-historical-region": ("de-DD", "de-DE"),
    "soviet-historical-region": ("hy-SU", "hy-AM"),
    "arabic-numeric-world-region": ("ar-001", "ar"),
    "chinese-hong-kong-script": ("zh-HK", "zh-Hant-HK"),
    "unicode-calendar-extension": ("en-US-u-ca-gregory", "en-US"),
    "norwegian-bokmal-legacy-variant": ("no-BOKMAL", "nb"),
    "norwegian-nynorsk-legacy-variant": ("no-NYNORSK", "nn"),
    "swedish-aaland-legacy-variant": ("sv-AALAND", "sv-AX"),
    "greek-polytonic-legacy-variant": ("el-POLYTONI", "el-polyton"),
    "afar-saaho-legacy-variant": ("aa-SAAHO", "ssy"),
    "english-posix-variant-extension": ("en-US-POSIX", "en-US-u-va-posix"),
    "english-oxford-legacy-variant": ("en-GB-oed", "en-GB-oxendict"),
    "slovenian-variant-order": ("sl-rozaj-biske", "sl-biske-rozaj"),
    "gregorian-legacy-keyword": ("en-US-u-ca-gregorian", "en-US-u-ca-gregory"),
    "german-phonebook-legacy-keyword": ("de-DE-u-co-phonebook", "de-DE-u-co-phonebk"),
    "private-extension-payload": ("en-x-harbor-one", "en-x-harbor"),
    "unmapped-deprecated-language": ("aam", "aas"),
}


def document() -> dict[str, object]:
    strings = {
        identifier: {
            "comment": "Distinct Serbian scripts retain their catalog-owned identity",
            "localizations": {
                "en": plural(INSERT_SOURCE, SOURCE_STATES),
                "de": plural(GERMAN_VALUES, GERMAN_STATES),
                locale: None,
            },
        }
        for identifier, locale in LOCALES.items()
    }
    strings[PROTECTED_ID] = {
        "shouldTranslate": False,
        "localizations": {
            "en": plural(INSERT_SOURCE, SOURCE_STATES),
            "sr_Latn": None,
            "sr-Cyrl": None,
        },
    }
    return {"sourceLanguage": "en", "version": "1.0", "strings": strings}


def catalog(root: dict[str, object]) -> dict[str, object]:
    conversions = [{"position": 8, "source": "%3$n", "argumentPosition": 3}]
    messages = {}
    for identifier in LOCALES:
        entry = root["strings"][identifier]
        messages[identifier] = {
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
                {"name": "arg1", "source": "%2$@", "kind": "string", "position": 2},
            ],
            "metadata": {
                "appleSourceLocalization": entry["localizations"]["en"],
                "sourcePluralStates": SOURCE_STATES,
                "applePluralDisabledPrintfConversions": {
                    "count": {"one": conversions, "other": conversions}
                },
                "localizations": {
                    "de": {"variants": GERMAN_VALUES, "variantStates": GERMAN_STATES}
                },
                "appleLocalizationSources": {"de": entry["localizations"]["de"]},
            },
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": dict(sorted(messages.items())),
    }


def translations(locale: str) -> dict[str, str]:
    return {
        identifier: "{count, plural, "
        + " ".join(
            f"{category} {{{{arg1}} {{count}}  {LABELS[locale][identifier]} {category}}}"
            for category in CATEGORIES
        )
        + "}"
        for identifier in LOCALES
    }


def inserted(identifier: str, locale: str) -> dict[str, object]:
    return plural(
        {
            category: f"%2$@ %1$lld %3$n {LABELS[locale][identifier]} {category}"
            for category in CATEGORIES
        },
        {category: "translated" for category in CATEGORIES},
    )


def skeleton(source: str, encoding: str, target_locale: str) -> dict[str, object]:
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
        if target_locale in entry["localizations"]:
            match = re.compile(
                re.escape(json.dumps(target_locale)) + r"\s*:\s*(null)"
            ).search(source, opening, closing)
            if match is None:
                raise RuntimeError(f"Missing script locale null: {identifier}")
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
        "appleTargetLocale": target_locale,
        "slots": slots,
    }


def localized(source: str, target_locale: str) -> str:
    original = source.encode("utf-8")
    result = bytearray()
    previous = 0
    for slot in skeleton(source, "UTF-8", target_locale)["slots"]:
        result.extend(original[previous : slot["start"]])
        value = json.dumps(
            inserted(slot["id"], target_locale),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if slot["start"] == slot["end"]:
            value = "," + json.dumps(target_locale) + ":" + value
        result.extend(value.encode("utf-8"))
        previous = slot["end"]
    result.extend(original[previous:])
    return result.decode("utf-8")


def runtime_samples(locale: str) -> list[dict[str, object]]:
    return [
        {
            "message": identifier,
            "arguments": [count, "Rowan", 0],
            "expected": f"Rowan {count}  {LABELS[locale][identifier]} {category}",
        }
        for identifier in LOCALES
        for count, category in (
            (0, "other"),
            (1, "one"),
            (2, "few"),
            (5, "other"),
            (21, "one"),
            (22, "few"),
        )
    ]


def collision_document(first: str, second: str) -> dict[str, object]:
    return {
        "sourceLanguage": "en",
        "version": "1.0",
        "strings": {
            "harbor.native.script.collision🧭": {
                "localizations": {
                    "en": {"stringUnit": {"state": "translated", "value": "Source"}},
                    first: {
                        "stringUnit": {
                            "state": "translated",
                            "value": f"First catalog locale {first}",
                        }
                    },
                    second: {
                        "stringUnit": {
                            "state": "translated",
                            "value": f"Second catalog locale {second}",
                        }
                    },
                }
            }
        },
    }


def distinct_catalog(first: str, second: str) -> dict[str, object]:
    source = {"state": "translated", "value": "Source"}
    localizations = {
        locale: {
            "state": "translated",
            "value": f"{position} catalog locale {locale}",
        }
        for locale, position in ((first, "First"), (second, "Second"))
    }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": {
            "harbor.native.script.collision🧭": {
                "defaultMessage": "Source",
                "metadata": {
                    "appleSourceLocalization": {"stringUnit": source},
                    "sourceState": "translated",
                    "localizations": localizations,
                    "appleLocalizationSources": {
                        locale: {"stringUnit": entry}
                        for locale, entry in localizations.items()
                    },
                },
            }
        },
    }


def main() -> None:
    root = document()
    source = json.dumps(root, ensure_ascii=False, indent=2) + "\n"
    original_path = APPLE / f"{STEM}.xcstrings"
    original_path.write_text(source, encoding="utf-8")
    write_json(APPLE / f"{STEM}.expected.json", catalog(root))
    write_json(APPLE / f"{STEM}.compiled.json", compiled_xcatalog(original_path))

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    identifier = "apple-xcstrings-first-serbian-latin-cyrillic-script-locales"
    collision_prefix = "apple-xcstrings-native-script-bundle-collision-"
    distinct_prefix = "apple-xcstrings-native-distinct-locale-"
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if case["id"] != identifier
        and not case["id"].startswith(collision_prefix)
        and not case["id"].startswith(distinct_prefix)
    ]
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
                    "Legacy routing does not own distinct Serbian script plurals, "
                    "catalog spelling, minimized native bundle identities, or protected bytes."
                ),
            },
        }
    )

    for label, (first, second) in COLLISIONS.items():
        collision_stem = f"catalog-native-script-bundle-collision-{label}"
        path = APPLE / f"{collision_stem}.xcstrings"
        write_json(path, collision_document(first, second))
        compiled = compiled_xcatalog(path)
        write_json(APPLE / f"{collision_stem}.compiled.json", compiled)
        bundle = next(key for key in compiled if not key.startswith("en.lproj/"))
        alternatives = []
        for locale, position in ((first, "First"), (second, "Second")):
            alternative = json.loads(json.dumps(compiled, ensure_ascii=False))
            alternative[bundle]["harbor.native.script.collision🧭"] = (
                f"{position} catalog locale {locale}"
            )
            alternatives.append(alternative)
        write_json(APPLE / f"{collision_stem}.compiled.alternatives.json", alternatives)
        manifest["cases"].append(
            {
                "id": collision_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{collision_stem}.xcstrings",
                "error": "DUPLICATE_LOCALE",
                "xcstringsOracle": "accept",
                "xcstringsCompiled": f"fixtures/apple/{collision_stem}.compiled.json",
                "xcstringsCompiledAlternatives": (
                    f"fixtures/apple/{collision_stem}.compiled.alternatives.json"
                ),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode silently maps {first} and {second} to the same native "
                        "bundle and nondeterministically drops one translation; portable "
                        "extraction rejects the collision."
                    ),
                },
            }
        )

    for label, (first, second) in DISTINCT.items():
        distinct_stem = f"catalog-native-distinct-locale-{label}"
        path = APPLE / f"{distinct_stem}.xcstrings"
        write_json(path, collision_document(first, second))
        write_json(
            APPLE / f"{distinct_stem}.expected.json", distinct_catalog(first, second)
        )
        write_json(APPLE / f"{distinct_stem}.compiled.json", compiled_xcatalog(path))
        manifest["cases"].append(
            {
                "id": distinct_prefix + label,
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{distinct_stem}.xcstrings",
                "expected": f"fixtures/apple/{distinct_stem}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{distinct_stem}.compiled.json",
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": (
                        f"Xcode preserves distinct native bundles for {first} and {second}; "
                        "general Unicode/ICU alias minimization must not merge either value."
                    ),
                },
            }
        )

    prefix = "apple-xcstrings-source-skeleton-inserts-first-serbian-script-"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(prefix)
    ]
    for label, locale, runtime in (
        ("latin", "sr_Latn", "sr-Latn"),
        ("cyrillic", "sr-Cyrl", "sr"),
    ):
        translated = localized(source, locale)
        localized_path = APPLE / f"{STEM}.{label}.localized.xcstrings"
        localized_path.write_text(translated, encoding="utf-8")
        write_json(
            APPLE / f"{STEM}.{label}.localized.compiled.json",
            compiled_xcatalog(localized_path),
        )
        write_json(APPLE / f"{STEM}.{label}.translations.json", translations(locale))
        for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16")):
            write_json(
                APPLE / f"{STEM}.{label}{suffix}.expected.skeleton.json",
                skeleton(source, encoding, locale),
            )
            manifest["sourceSkeletons"].append(
                {
                    "id": prefix + label + ("-utf8" if not suffix else "-utf16"),
                    **({"encoding": encoding} if suffix else {}),
                    "expected": (
                        f"fixtures/apple/{STEM}.{label}{suffix}.expected.skeleton.json"
                    ),
                    "format": "apple_xcstrings",
                    "xcstringsTargetLocale": locale.replace("_", "-"),
                    "xcstringsTargetPlural": True,
                    "xcstringsTargetPluralInsertion": True,
                    "xcstringsFirstLocaleCategories": True,
                    "xcstringsScriptLocale": label,
                    "xcstringsRuntimeLocale": runtime,
                    "xcstringsFormattingLocale": locale.replace("_", "-"),
                    "input": f"fixtures/apple/{STEM}.xcstrings",
                    "translations": f"fixtures/apple/{STEM}.{label}.translations.json",
                    "localized": f"fixtures/apple/{STEM}.{label}.localized.xcstrings",
                    "xcstringsCompiled": f"fixtures/apple/{STEM}.compiled.json",
                    "xcstringsLocalizedCompiled": (
                        f"fixtures/apple/{STEM}.{label}.localized.compiled.json"
                    ),
                    "xcstringsOriginalRuntimeSamples": [],
                    "xcstringsLocalizedRuntimeSamples": runtime_samples(locale),
                }
            )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
