#!/usr/bin/env python3
"""Generate neutral Xcode selector-owned disabled Foundation printf fixtures."""

from __future__ import annotations

import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "catalog-disabled-substitution-printf"
TOOL = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def unit(value: str, state: str = "translated") -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def definition(one: str, other: str) -> dict[str, object]:
    return {
        "argNum": 1,
        "formatSpecifier": "lld",
        "variations": {"plural": {"one": unit(one), "other": unit(other)}},
    }


def compile_catalog(source: Path) -> dict[str, object]:
    executable = shutil.which("xcstringstool") or str(TOOL)
    with tempfile.TemporaryDirectory(
        prefix="mojito-xcode-disabled-substitution-"
    ) as directory:
        output = Path(directory)
        result = subprocess.run(
            [executable, "compile", str(source), "--output-directory", str(output)],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return {
            str(
                path.relative_to(output).parent / f"catalog{path.suffix}"
            ): plistlib.loads(path.read_bytes())
            for path in sorted(output.rglob("*"))
            if path.is_file()
        }


def canonical(category: str, noun: str, kind: str) -> str:
    suffix = noun if category == "one" else noun + "s"
    separator = "\n" if kind == "line" else "%n " if kind == "literal" else " "
    return "{count}" + separator + suffix


def main() -> None:
    source_device = {
        "substitutions": {
            "count": definition("%lld%n mobile lane", "%lld%n%n mobile lanes")
        },
        "variations": {
            "device": {
                "iphone": unit("Touch %#@count@", "needs_review"),
                "mac": unit("Click %#@count@"),
                "applewatch": unit("Watch %#@count@", "machine_translated"),
            }
        },
    }
    target_device = {
        "substitutions": {
            "count": definition("%lld voie protégée", "%lld voies protégées")
        },
        "variations": {"device": {"iphone": unit("Touchez %#@count@")}},
    }
    entries = {}
    values = {
        "zero": ("%lld%n beacon", "%lld%n%n beacons"),
        "literal": ("%lld%%n beacon", "%lld%%n beacons"),
        "line": ("%lld\nbeacon", "%lld\nbeacons"),
    }
    for kind, (one, other) in values.items():
        entries[f"harbor.{kind}"] = {
            "localizations": {
                "en": {
                    "stringUnit": {"state": "translated", "value": "Observe %#@count@"},
                    "substitutions": {"count": definition(one, other)},
                }
            }
        }
    entries["harbor.device.🧭"] = {
        "comment": "Root-owned native plural definitions shared by protected device branches.",
        "localizations": {"en": source_device, "fr": target_device},
    }
    root = {"sourceLanguage": "en", "strings": entries, "version": "1.0"}
    source = APPLE / f"{STEM}.xcstrings"
    write_json(source, root)

    placeholder = {"name": "count", "source": "%lld", "kind": "integer", "position": 1}
    messages = {}
    for kind in values:
        key = f"harbor.{kind}"
        localization = entries[key]["localizations"]["en"]
        one = canonical("one", "beacon", kind)
        other = canonical("other", "beacon", kind)
        metadata = {
            "appleSourceLocalization": localization,
            "sourceState": "translated",
            "sourceSubstitutions": localization["substitutions"],
        }
        if kind == "zero":
            metadata["applePluralDisabledPrintfConversions"] = {
                "count": {
                    "one": [{"position": 7, "source": "%n"}],
                    "other": [
                        {"position": 7, "source": "%n"},
                        {"position": 7, "source": "%n"},
                    ],
                }
            }
        messages[key] = {
            "defaultMessage": f"Observe {{count, plural, one {{{one}}} other {{{other}}}}}",
            "placeholders": [placeholder],
            "metadata": metadata,
        }
    messages["harbor.device.🧭"] = {
        "defaultMessage": (
            "Touch {count, plural, one {{count} mobile lane} other {{count} mobile lanes}}"
        ),
        "description": "Root-owned native plural definitions shared by protected device branches.",
        "placeholders": [placeholder],
        "metadata": {
            "applePluralDisabledPrintfConversions": {
                "count": {
                    "one": [{"position": 7, "source": "%n"}],
                    "other": [
                        {"position": 7, "source": "%n"},
                        {"position": 7, "source": "%n"},
                    ],
                }
            },
            "appleLocalizationSources": {"fr": target_device},
            "appleSourceLocalization": source_device,
            "defaultDevice": "iphone",
            "localizations": {"fr": {"variationAxes": target_device["variations"]}},
            "sourceState": "needs_review",
            "sourceSubstitutions": source_device["substitutions"],
            "sourceVariationAxes": source_device["variations"],
        },
    }
    write_json(
        APPLE / f"{STEM}.expected.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_xcstrings",
            "locale": "en",
            "messages": dict(sorted(messages.items())),
        },
    )
    write_json(APPLE / f"{STEM}.compiled.json", compile_catalog(source))

    normalized = APPLE / f"{STEM}.normalized.xcstrings"
    write_json(
        normalized,
        json.loads(json.dumps(deepcopy(root), ensure_ascii=False, sort_keys=True)),
    )
    write_json(APPLE / f"{STEM}.normalized.compiled.json", compile_catalog(normalized))

    translations = {}
    replacement_values = {}
    for kind in values:
        identity = f"harbor.{kind}"
        translations[identity] = "Regardez {count}"
        replacement_values[identity] = "Regardez %#@count@"
        for category in ("one", "other"):
            noun = "balise" if category == "one" else "balises"
            suffix = (
                "\n" + noun
                if kind == "line"
                else "%n " + noun
                if kind == "literal"
                else " " + noun
            )
            translations[f"{identity}#count#{category}"] = "{count}" + suffix
            native_separator = (
                "\n"
                if kind == "line"
                else "%%n "
                if kind == "literal"
                else "%n" * (1 if category == "one" else 2) + " "
            )
            replacement_values[f"{identity}#count#{category}"] = (
                "%lld" + native_separator + noun
            )
    translations["harbor.device.🧭"] = "Touchez {count}"
    replacement_values["harbor.device.🧭"] = "Touchez %#@count@"
    for category, noun in (("one", "voie"), ("other", "voies")):
        translations[f"harbor.device.🧭#count#{category}"] = "{count} " + noun
        replacement_values[f"harbor.device.🧭#count#{category}"] = (
            "%lld" + "%n" * (1 if category == "one" else 2) + " " + noun
        )

    original = source.read_text(encoding="utf-8")
    slots = []
    replacements = []
    search_offset = 0
    for key, descriptor in root["strings"].items():
        en = descriptor["localizations"]["en"]
        if "variations" in en:
            root_value = en["variations"]["device"]["iphone"]["stringUnit"]["value"]
        else:
            root_value = en["stringUnit"]["value"]
        owned = []
        for category in ("one", "other"):
            category_value = en["substitutions"]["count"]["variations"]["plural"][
                category
            ]["stringUnit"]["value"]
            owned.append((f"{key}#count#{category}", category_value))
        root_identity = (key, root_value)
        if "variations" in en:
            owned.append(root_identity)
        else:
            owned.insert(0, root_identity)
        for identity, native in owned:
            pattern = re.compile(
                r'"value"\s*:\s*"('
                + re.escape(json.dumps(native, ensure_ascii=False)[1:-1])
                + r')"'
            )
            match = pattern.search(original, search_offset)
            if match is None:
                raise RuntimeError(f"{identity}: missing native source occurrence")
            search_offset = match.end()
            name, *qualified = identity.split("#")
            slot = {
                "id": name,
                "start": len(original[: match.start(1)].encode("utf-8")),
                "end": len(original[: match.end(1)].encode("utf-8")),
            }
            if qualified:
                slot["selector"], slot["variant"] = qualified
            slots.append(slot)
            replacement = json.dumps(replacement_values[identity], ensure_ascii=False)[
                1:-1
            ]
            replacements.append((match.start(1), match.end(1), replacement))
    slots.sort(key=lambda slot: slot["start"])
    write_json(
        APPLE / f"{STEM}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_xcstrings",
            "encoding": "UTF-8",
            "source": original,
            "slots": slots,
        },
    )
    write_json(APPLE / f"{STEM}.translations.json", translations)
    localized = original
    for start, end, replacement in sorted(replacements, reverse=True):
        localized = localized[:start] + replacement + localized[end:]
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    localized_path.write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compile_catalog(localized_path)
    )

    all_translations = {
        **{
            identity: value
            for identity, value in translations.items()
            if identity != "harbor.device.🧭"
        },
        "harbor.device.🧭#@device#iphone": "Touchez {count}",
        "harbor.device.🧭#@device#mac": "Cliquez {count} au port",
        "harbor.device.🧭#@device#applewatch": "Montre {count} au quai",
    }
    all_native = {
        **replacement_values,
        "harbor.device.🧭#@device#iphone": "Touchez %#@count@",
        "harbor.device.🧭#@device#mac": "Cliquez %#@count@ au port",
        "harbor.device.🧭#@device#applewatch": "Montre %#@count@ au quai",
    }
    all_slots = [
        slot
        for slot in slots
        if not (slot["id"] == "harbor.device.🧭" and "selector" not in slot)
    ]
    all_replacements = []
    original_bytes = original.encode()
    for slot in all_slots:
        identity = slot["id"]
        if "selector" in slot:
            identity += "#" + slot["selector"] + "#" + slot["variant"]
        native = json.dumps(replacement_values[identity], ensure_ascii=False)[1:-1]
        start = len(original_bytes[: slot["start"]].decode())
        end = len(original_bytes[: slot["end"]].decode())
        all_replacements.append((start, end, native))

    device_root = root["strings"]["harbor.device.🧭"]["localizations"]["en"]
    devices = device_root["variations"]["device"]
    source_cursor = original.index('"harbor.device.🧭"')
    for device_name, descriptor in devices.items():
        value = descriptor["stringUnit"]["value"]
        pattern = re.compile(
            r'"value"\s*:\s*"('
            + re.escape(json.dumps(value, ensure_ascii=False)[1:-1])
            + r')"'
        )
        match = pattern.search(original, source_cursor)
        if match is None:
            raise RuntimeError(
                f"Missing independently owned Xcode device {device_name}"
            )
        source_cursor = match.end()
        identity = f"harbor.device.🧭#@device#{device_name}"
        all_slots.append(
            {
                "id": "harbor.device.🧭",
                "selector": "@device",
                "variant": device_name,
                "start": len(original[: match.start(1)].encode()),
                "end": len(original[: match.end(1)].encode()),
            }
        )
        native = json.dumps(all_native[identity], ensure_ascii=False)[1:-1]
        all_replacements.append((match.start(1), match.end(1), native))
    all_slots.sort(key=lambda slot: slot["start"])
    write_json(
        APPLE / f"{STEM}.all-devices.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "apple_xcstrings",
            "encoding": "UTF-8",
            "source": original,
            "slots": all_slots,
        },
    )
    write_json(APPLE / f"{STEM}.all-devices.translations.json", all_translations)
    all_localized = original
    for start, end, native in sorted(all_replacements, reverse=True):
        all_localized = all_localized[:start] + native + all_localized[end:]
    all_localized_path = APPLE / f"{STEM}.all-devices.localized.xcstrings"
    all_localized_path.write_text(all_localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.all-devices.localized.compiled.json",
        compile_catalog(all_localized_path),
    )


if __name__ == "__main__":
    main()
