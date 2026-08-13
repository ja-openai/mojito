#!/usr/bin/env python3
"""Generate native-safe Foundation plural-branch hidden-argument contracts."""

from __future__ import annotations

import html
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from generate_apple_disabled_argument_fixtures import compiled_xcatalog, write_json


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "disabled-printf-branch-arguments"
PRINTF = re.compile(r"%(?:(\d+)\$)?(lld|ld|d|@|n|%)")

BRANCHES = {
    "harbor.after": ("%d%n %@ beacon", "%d%n %@ beacons"),
    "harbor.repeated": ("%d%n%n %@ beacon", "%d%n%n %@ beacons"),
    "harbor.explicit": ("%d %3$n %2$@ beacon", "%d %3$n %2$@ beacons"),
    "harbor.overlap": ("%d %2$n %@ beacon", "%d %2$n %@ beacons"),
    "harbor.positioned": ("%d%n %3$@ beacon", "%d%n %3$@ beacons"),
    "harbor.escaped": ("%d%%n %@ beacon", "%d%%n %@ beacons"),
    "harbor.unicode.🧭": ("%d%n %@ 🧭", "%d%n %@ 🧭🧭"),
}

XCODE = {
    "harbor.after": ("Guide %#@count@", "%lld%n %@ lane", "%lld%n %@ lanes", 1),
    "harbor.repeated": (
        "Guide %#@count@",
        "%lld%n%n %@ lane",
        "%lld%n%n %@ lanes",
        1,
    ),
    "harbor.explicit": (
        "Guide %1$@ %2$#@count@",
        "%2$lld %3$n %4$@ lane",
        "%2$lld %3$n %4$@ lanes",
        2,
    ),
    "harbor.overlap": (
        "Guide %#@count@",
        "%lld %2$n %@ lane",
        "%lld %2$n %@ lanes",
        1,
    ),
    "harbor.escaped": (
        "Guide %#@count@",
        "%lld%%n %@ lane",
        "%lld%%n %@ lanes",
        1,
    ),
}


def branch_descriptor(
    native: str, selector_position: int, *, substitution: bool = False
) -> tuple[str, list[dict[str, object]], list[dict[str, object]]]:
    result: list[str] = []
    placeholders: list[dict[str, object]] = []
    disabled: list[dict[str, object]] = []
    previous = 0
    implicit = selector_position - 1 if substitution else 0
    visible_after_disabled = False
    for match in PRINTF.finditer(native):
        result.append(native[previous : match.start()])
        explicit, conversion = match.groups()
        if conversion == "%":
            result.append("%")
            previous = match.end()
            continue
        if explicit:
            position = int(explicit)
        else:
            implicit += 1
            position = (
                selector_position if conversion in {"d", "ld", "lld"} else implicit
            )
        if conversion == "n":
            disabled.append(
                {
                    "position": len("".join(result)),
                    "source": match.group(),
                    "argumentPosition": position,
                }
            )
        else:
            visible_after_disabled |= bool(disabled)
            numeric = conversion in {"d", "ld", "lld"}
            name = (
                "count"
                if numeric and position == selector_position
                else f"arg{position - 1}"
            )
            placeholder = {
                "name": name,
                "source": match.group(),
                "kind": "integer" if numeric else "string",
                "position": position,
            }
            if placeholder not in placeholders:
                placeholders.append(placeholder)
            result.append("{" + name + "}")
        previous = match.end()
    result.append(native[previous:])
    if not visible_after_disabled:
        for occurrence in disabled:
            occurrence.pop("argumentPosition")
    return "".join(result), placeholders, disabled


def stringsdict_definitions(localized: bool = False) -> dict[str, object]:
    definitions: dict[str, object] = {}
    for key, variants in BRANCHES.items():
        if localized:
            variants = tuple(value.replace("beacon", "balise") for value in variants)
        definitions[key] = {
            "NSStringLocalizedFormatKey": "%#@count@",
            "count": {
                "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
                "NSStringFormatValueTypeKey": "d",
                "one": variants[0],
                "other": variants[1],
            },
        }
    return definitions


def stringsdict_xml(definitions: dict[str, object], *, normalized: bool = False) -> str:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>']
    if not normalized:
        lines.append(
            '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
            '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
        )
    lines.extend(['<plist version="1.0">', "<dict>"])
    entries = sorted(definitions.items()) if normalized else definitions.items()
    for key, value in entries:
        lines.extend(
            [
                f"  <key>{html.escape(key)}</key>",
                "  <dict>",
                "    <key>NSStringLocalizedFormatKey</key>",
                "    <string>%#@count@</string>",
                "    <key>count</key>",
                "    <dict>",
                "      <key>NSStringFormatSpecTypeKey</key>",
                "      <string>NSStringPluralRuleType</string>",
                "      <key>NSStringFormatValueTypeKey</key>",
                "      <string>d</string>",
            ]
        )
        for category in ("one", "other"):
            lines.extend(
                [
                    f"      <key>{category}</key>",
                    f"      <string>{html.escape(value['count'][category])}</string>",
                ]
            )
        lines.extend(["    </dict>", "  </dict>"])
    return "\n".join([*lines, "</dict>", "</plist>", ""])


def stringsdict_catalog(definitions: dict[str, object]) -> dict[str, object]:
    messages = {}
    for key, definition in sorted(definitions.items()):
        variants: dict[str, str] = {}
        placeholders: list[dict[str, object]] = []
        disabled: dict[str, list[dict[str, object]]] = {}
        for category in ("one", "other"):
            text, current, occurrences = branch_descriptor(
                definition["count"][category], 1
            )
            variants[category] = text
            for placeholder in current:
                if placeholder not in placeholders:
                    placeholders.append(placeholder)
            if occurrences:
                disabled[category] = occurrences
        metadata = {
            "appleLocalizedFormat": "%#@count@",
            "applePluralRules": {
                "count": {
                    "valueType": "d",
                    "variants": {
                        category: definition["count"][category]
                        for category in ("one", "other")
                    },
                }
            },
            "pluralVariable": "count",
            "valueType": "d",
        }
        if disabled:
            metadata["applePluralDisabledPrintfConversions"] = {"count": disabled}
        messages[key] = {
            "defaultMessage": "{count, plural, "
            + " ".join(
                f"{category} {{{value}}}" for category, value in variants.items()
            )
            + "}",
            "variants": variants,
            "placeholders": placeholders,
            "metadata": metadata,
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "messages": messages,
    }


def xcode_root(localized: bool = False) -> dict[str, object]:
    entries = {}
    for key, (root, one, other, position) in XCODE.items():
        if localized:
            root = root.replace("Guide", "Suive")
            one = one.replace("lane", "voie")
            other = other.replace("lanes", "voies")
        entries[key] = {
            "localizations": {
                "en": {
                    "stringUnit": {"state": "translated", "value": root},
                    "substitutions": {
                        "count": {
                            "argNum": position,
                            "formatSpecifier": "lld",
                            "variations": {
                                "plural": {
                                    "one": {
                                        "stringUnit": {
                                            "state": "translated",
                                            "value": one,
                                        }
                                    },
                                    "other": {
                                        "stringUnit": {
                                            "state": "translated",
                                            "value": other,
                                        }
                                    },
                                }
                            },
                        }
                    },
                }
            }
        }
    return {"sourceLanguage": "en", "strings": entries, "version": "1.0"}


def xcode_catalog(root: dict[str, object]) -> dict[str, object]:
    messages = {}
    for key, descriptor in sorted(root["strings"].items()):
        source = descriptor["localizations"]["en"]
        definition = source["substitutions"]["count"]
        position = definition["argNum"]
        branches = definition["variations"]["plural"]
        variants: dict[str, str] = {}
        placeholders: list[dict[str, object]] = []
        disabled: dict[str, list[dict[str, object]]] = {}
        for category in ("one", "other"):
            native = branches[category]["stringUnit"]["value"]
            value, current, occurrences = branch_descriptor(
                native, position, substitution=True
            )
            variants[category] = value
            for placeholder in current:
                if placeholder not in placeholders:
                    placeholders.append(placeholder)
            if occurrences:
                disabled[category] = occurrences
        native_root = source["stringUnit"]["value"]
        prefix = native_root.split("%2$#@count@" if position == 2 else "%#@count@")[0]
        if "%1$@" in prefix:
            prefix = prefix.replace("%1$@", "{arg0}")
            placeholders.append(
                {"name": "arg0", "source": "%1$@", "kind": "string", "position": 1}
            )
        canonical = (
            prefix
            + "{count, plural, "
            + " ".join(
                f"{category} {{{variants[category]}}}" for category in ("one", "other")
            )
            + "}"
        )
        metadata = {
            "appleSourceLocalization": source,
            "sourceState": "translated",
            "sourceSubstitutions": source["substitutions"],
        }
        if disabled:
            metadata["applePluralDisabledPrintfConversions"] = {"count": disabled}
        messages[key] = {
            "defaultMessage": canonical,
            "placeholders": placeholders,
            "metadata": metadata,
        }
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "locale": "en",
        "messages": messages,
    }


def runtime_samples(
    catalog: dict[str, object], *, localized: bool = False
) -> list[dict]:
    results = []
    for key, message in catalog["messages"].items():
        for count in (1, 3):
            category = "one" if count == 1 else "other"
            values = {
                placeholder["name"]: (
                    count if placeholder["name"] == "count" else "Rowan"
                )
                for placeholder in message.get("placeholders", [])
            }
            variants = message.get("variants")
            if variants:
                canonical = variants[category]
            else:
                marker = "{count, plural, "
                prefix = message["defaultMessage"].split(marker)[0]
                native = XCODE[key][1 if count == 1 else 2]
                position = XCODE[key][3]
                if localized:
                    native = native.replace("lane", "voie").replace("lanes", "voies")
                    prefix = prefix.replace("Guide", "Suive")
                branch, _, _ = branch_descriptor(native, position, substitution=True)
                canonical = prefix + branch
            expected = re.sub(
                r"\{([^{}]+)\}", lambda match: str(values[match.group(1)]), canonical
            )
            results.append({"message": key, "values": values, "expected": expected})
    return results


def direct_samples(catalog: dict[str, object], localized: bool = False) -> list[dict]:
    results = []
    for sample in runtime_samples(catalog, localized=localized):
        message = catalog["messages"][sample["message"]]
        count = sample["values"]["count"]
        category = "one" if count == 1 else "other"
        positions = {
            placeholder["position"]: sample["values"][placeholder["name"]]
            for placeholder in message["placeholders"]
        }
        conversions = (
            message.get("metadata", {})
            .get("applePluralDisabledPrintfConversions", {})
            .get("count", {})
            .get(category, [])
        )
        for conversion in conversions:
            if "argumentPosition" in conversion:
                positions.setdefault(conversion["argumentPosition"], 0)
        results.append(
            {
                "message": sample["message"],
                "arguments": [
                    positions[index] for index in range(1, max(positions) + 1)
                ],
                "expected": sample["expected"],
            }
        )
    return results


def stringsdict_skeleton(source: str, encoding: str = "UTF-8") -> dict:
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    expression = re.compile(r"<key>([^<]+)</key>(?:\s*<string>([^<]*)</string>)?")
    current = None
    slots = []
    for match in expression.finditer(source):
        identifier = html.unescape(match.group(1))
        if identifier.startswith("harbor."):
            current = identifier
        if identifier in {"one", "other"} and current is not None and match.group(2):
            slots.append(
                {
                    "id": current,
                    "variant": identifier,
                    "start": bom + len(source[: match.start(2)].encode(codec)),
                    "end": bom + len(source[: match.end(2)].encode(codec)),
                }
            )
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_stringsdict",
        "encoding": encoding,
        "source": source,
        "slots": slots,
    }


def xcode_skeleton(source: str, root: dict[str, object]) -> dict:
    slots = []
    cursor = 0
    for key, descriptor in root["strings"].items():
        en = descriptor["localizations"]["en"]
        owned = [(key, en["stringUnit"]["value"])]
        branches = en["substitutions"]["count"]["variations"]["plural"]
        owned.extend(
            (f"{key}#count#{category}", branches[category]["stringUnit"]["value"])
            for category in ("one", "other")
        )
        for identity, value in owned:
            expression = re.compile(
                r'"value"\s*:\s*"('
                + re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
                + r')"'
            )
            match = expression.search(source, cursor)
            if match is None:
                raise RuntimeError(f"{identity}: missing Xcode source slot")
            cursor = match.end()
            name, *qualified = identity.split("#")
            slot = {
                "id": name,
                "start": len(source[: match.start(1)].encode()),
                "end": len(source[: match.end(1)].encode()),
            }
            if qualified:
                slot["selector"], slot["variant"] = qualified
            slots.append(slot)
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": "UTF-8",
        "source": source,
        "slots": slots,
    }


def source_translations(
    source_catalog: dict[str, object], localized_catalog: dict[str, object]
) -> dict[str, str]:
    results = {}
    for key, descriptor in localized_catalog["messages"].items():
        variants = descriptor.get("variants")
        if variants is not None:
            for category, value in variants.items():
                results[f"{key}#{category}"] = value
        else:
            native_root = xcode_root(localized=True)["strings"][key]["localizations"][
                "en"
            ]
            root = native_root["stringUnit"]["value"]
            position = native_root["substitutions"]["count"]["argNum"]
            marker = "%2$#@count@" if position == 2 else "%#@count@"
            prefix = root.split(marker)[0].replace("%1$@", "{arg0}")
            results[key] = prefix + "{count}"
            for category in ("one", "other"):
                value = native_root["substitutions"]["count"]["variations"]["plural"][
                    category
                ]["stringUnit"]["value"]
                results[f"{key}#count#{category}"] = branch_descriptor(
                    value, position, substitution=True
                )[0]
    return results


def main() -> None:
    definitions = stringsdict_definitions()
    localized_definitions = stringsdict_definitions(localized=True)
    source = stringsdict_xml(definitions)
    localized_xml = stringsdict_xml(localized_definitions)
    source_path = APPLE / f"{STEM}.stringsdict"
    source_path.write_text(source, encoding="utf-8")
    localized_path = APPLE / f"{STEM}.localized.stringsdict"
    localized_path.write_text(localized_xml, encoding="utf-8")
    normalized_path = APPLE / f"{STEM}.normalized.stringsdict"
    normalized_path.write_text(
        stringsdict_xml(definitions, normalized=True), encoding="utf-8"
    )
    catalog = stringsdict_catalog(definitions)
    translated_catalog = stringsdict_catalog(localized_definitions)
    write_json(APPLE / f"{STEM}.expected.json", catalog)
    write_json(APPLE / f"{STEM}.compiled.json", definitions)
    write_json(APPLE / f"{STEM}.localized.compiled.json", localized_definitions)
    translations = source_translations(catalog, translated_catalog)
    write_json(APPLE / f"{STEM}.translations.json", translations)
    write_json(APPLE / f"{STEM}.expected.skeleton.json", stringsdict_skeleton(source))
    write_json(
        APPLE / f"{STEM}.utf16.expected.skeleton.json",
        stringsdict_skeleton(source, "UTF-16LE-BOM"),
    )

    executable = shutil.which("plutil")
    if executable is None:
        raise SystemExit("Apple plutil is required for binary Foundation snapshots")
    with tempfile.TemporaryDirectory(prefix="mojito-foundation-branch-binary-") as temp:
        binary = Path(temp) / "native.binary"
        subprocess.run(
            [executable, "-convert", "binary1", "-o", str(binary), str(source_path)],
            check=True,
        )
        encoded = binary.read_bytes().hex()
    (APPLE / f"{STEM}.binary.hex").write_text(
        "\n".join(encoded[index : index + 64] for index in range(0, len(encoded), 64))
        + "\n",
        encoding="ascii",
    )

    root = xcode_root()
    translated_root = xcode_root(localized=True)
    xcode_path = APPLE / f"catalog-{STEM}.xcstrings"
    write_json(xcode_path, root)
    xcatalog = xcode_catalog(root)
    translated_xcatalog = xcode_catalog(translated_root)
    write_json(APPLE / f"catalog-{STEM}.expected.json", xcatalog)
    write_json(APPLE / f"catalog-{STEM}.compiled.json", compiled_xcatalog(xcode_path))
    normalized_xcode = APPLE / f"catalog-{STEM}.normalized.xcstrings"
    write_json(normalized_xcode, root, sort_keys=True)
    write_json(
        APPLE / f"catalog-{STEM}.normalized.compiled.json",
        compiled_xcatalog(normalized_xcode),
    )
    xcode_source = xcode_path.read_text(encoding="utf-8")
    xsidecar = xcode_skeleton(xcode_source, root)
    write_json(APPLE / f"catalog-{STEM}.expected.skeleton.json", xsidecar)
    xtranslations = source_translations(xcatalog, translated_xcatalog)
    write_json(APPLE / f"catalog-{STEM}.translations.json", xtranslations)
    xlocalized_path = APPLE / f"catalog-{STEM}.localized.xcstrings"
    write_json(xlocalized_path, translated_root)
    write_json(
        APPLE / f"catalog-{STEM}.localized.compiled.json",
        compiled_xcatalog(xlocalized_path),
    )

    legacy = []
    for key, definition in definitions.items():
        for category in ("zero", "one", "two", "few", "many", "other"):
            native = definition["count"]["one" if category == "one" else "other"]
            legacy.append(
                {
                    "name": f"{key}_count_{category}",
                    "source": native,
                    "pluralForm": category,
                    "pluralFormOther": f"{key}_count_other",
                }
            )
    write_json(
        ROOT / "fixtures" / "okapi" / f"apple-{STEM}.json",
        {"filterConfigId": "okf_macStringdict@mojito", "units": legacy},
    )
    write_json(
        ROOT / "fixtures" / "shadow" / f"apple-{STEM}.json",
        {
            "sourceFormat": "apple_stringsdict",
            "canonicalUnits": len(legacy),
            "legacyUnits": len(legacy),
            "outcome": "mismatch",
            "differences": [
                {"category": "source_mismatch", "id": unit["name"]}
                for unit in sorted(legacy, key=lambda item: item["name"])
            ],
        },
    )

    manifest_path = ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case_prefix = (
        "apple-foundation-plural-disabled-conversions-reserve-native-argument-slots"
    )
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(case_prefix)
    ]
    base = {
        "format": "apple_stringsdict",
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "expected": f"fixtures/apple/{STEM}.expected.json",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleStringsdictNormalized": f"fixtures/apple/{STEM}.normalized.stringsdict",
        "appleStringsdictRuntimeSamples": runtime_samples(catalog),
    }
    manifest["cases"].extend(
        [
            {
                "id": case_prefix + "-xml",
                **base,
                "okapi": {
                    "policy": "different",
                    "assetPath": "en.lproj/Localizable.stringsdict",
                    "expected": f"fixtures/okapi/apple-{STEM}.json",
                    "reason": "Actual Foundation plural branches reserve hidden %n argument slots; the existing stringsdict filter flattens every category and loses safe visible-argument ownership.",
                },
            },
            {
                "id": case_prefix + "-binary",
                **base,
                "encoding": "BINARY_PLIST",
                "binaryFixture": f"fixtures/apple/{STEM}.binary.hex",
            },
            {
                "id": case_prefix + "-xcode-substitution",
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/catalog-{STEM}.xcstrings",
                "expected": f"fixtures/apple/catalog-{STEM}.expected.json",
                "xcstringsCompiled": f"fixtures/apple/catalog-{STEM}.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/catalog-{STEM}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": f"fixtures/apple/catalog-{STEM}.normalized.compiled.json",
                "xcstringsRuntimeSamples": runtime_samples(xcatalog),
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": "Legacy routing rejects Xcode catalogs; replacement preserves selector-owned hidden Foundation arguments that are required to avoid native crashes.",
                },
            },
        ]
    )

    skeleton_prefix = (
        "apple-source-skeleton-preserves-category-owned-hidden-foundation-arguments"
    )
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]
    common = {
        "format": "apple_stringsdict",
        "appleStringsdictHiddenArgumentSlots": True,
        "input": f"fixtures/apple/{STEM}.stringsdict",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "localized": f"fixtures/apple/{STEM}.localized.stringsdict",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": direct_samples(catalog),
        "appleLocalizedRuntimeSamples": direct_samples(translated_catalog),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": skeleton_prefix + "-xml",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                **common,
            },
            {
                "id": skeleton_prefix + "-utf16",
                "encoding": "UTF-16LE-BOM",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                **common,
            },
            {
                "id": skeleton_prefix + "-xcode-substitution",
                "format": "apple_xcstrings",
                "xcstringsSubstitutionSlots": True,
                "xcstringsHiddenArgumentSlots": True,
                "input": f"fixtures/apple/catalog-{STEM}.xcstrings",
                "expected": f"fixtures/apple/catalog-{STEM}.expected.skeleton.json",
                "translations": f"fixtures/apple/catalog-{STEM}.translations.json",
                "localized": f"fixtures/apple/catalog-{STEM}.localized.xcstrings",
                "xcstringsCompiled": f"fixtures/apple/catalog-{STEM}.compiled.json",
                "xcstringsLocalizedCompiled": f"fixtures/apple/catalog-{STEM}.localized.compiled.json",
                "xcstringsOriginalRuntimeSamples": direct_samples(xcatalog),
                "xcstringsLocalizedRuntimeSamples": direct_samples(
                    translated_xcatalog, localized=True
                ),
            },
        ]
    )
    shadow_id = "shadow-apple-foundation-plural-hidden-disabled-argument-ownership"
    manifest["shadowComparisons"] = [
        item for item in manifest["shadowComparisons"] if item["id"] != shadow_id
    ]
    manifest["shadowComparisons"].append(
        {
            "id": shadow_id,
            "case": case_prefix + "-xml",
            "expected": f"fixtures/shadow/apple-{STEM}.json",
        }
    )
    write_json(manifest_path, manifest)


if __name__ == "__main__":
    main()
