#!/usr/bin/env python3
"""Generate original native-verified Xcode disabled-printf conversion fixtures."""

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
STEM = "catalog-disabled-printf"
TOOL = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def unit(value: str, state: str = "translated") -> dict[str, object]:
    return {"stringUnit": {"state": state, "value": value}}


def compile_catalog(source: Path) -> dict[str, object]:
    executable = shutil.which("xcstringstool") or str(TOOL)
    with tempfile.TemporaryDirectory(prefix="mojito-disabled-xcstrings-") as directory:
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


def disabled(source: str, canonical: str) -> list[dict[str, object]]:
    occurrences = []
    consumed = 0
    visible = 0
    conversion = re.compile(r"%(?:[1-9][0-9]*\$)?n|%%")
    for match in conversion.finditer(source):
        visible += len(source[consumed : match.start()])
        if match.group() == "%%":
            visible += 1
        else:
            occurrences.append({"position": visible, "source": match.group()})
        consumed = match.end()
    assert visible + len(source[consumed:]) == len(canonical)
    return occurrences


def scalar_message(
    source: str, canonical: str, localization: dict[str, object]
) -> dict:
    metadata: dict[str, object] = {
        "appleSourceLocalization": localization,
        "sourceState": "translated",
    }
    if occurrences := disabled(source, canonical):
        metadata["appleDisabledPrintfConversions"] = occurrences
    return {"defaultMessage": canonical, "metadata": metadata}


def main() -> None:
    device = {
        "variations": {
            "device": {
                "iphone": unit("Palm%nDock"),
                "mac": unit("Desk%%nDock"),
                "other": unit("Wide\nDock"),
            }
        }
    }
    plural = {
        "variations": {
            "plural": {
                "one": unit("%d%n beacon"),
                "other": unit("%d%n%n beacons"),
            }
        }
    }
    localized_device = {"variations": {"device": {"iphone": unit("Quai existant")}}}
    entries = {
        "harbor.zero": {"localizations": {"en": unit("North%nSouth")}},
        "harbor.literal": {"localizations": {"en": unit("North%%nSouth")}},
        "harbor.line": {"localizations": {"en": unit("North\nSouth")}},
        "harbor.positioned.🧭": {"localizations": {"en": unit("🧭West%2$nEast%n")}},
        "harbor.device": {"localizations": {"en": device, "fr": localized_device}},
        "harbor.plural": {"localizations": {"en": plural}},
    }
    root = {"sourceLanguage": "en", "strings": entries, "version": "1.0"}
    source = APPLE / f"{STEM}.xcstrings"
    write_json(source, root)

    messages = {}
    for key, native, canonical in [
        ("harbor.zero", "North%nSouth", "NorthSouth"),
        ("harbor.literal", "North%%nSouth", "North%nSouth"),
        ("harbor.line", "North\nSouth", "North\nSouth"),
        ("harbor.positioned.🧭", "🧭West%2$nEast%n", "🧭WestEast"),
    ]:
        messages[key] = scalar_message(
            native, canonical, entries[key]["localizations"]["en"]
        )

    messages["harbor.device"] = {
        "defaultMessage": "PalmDock",
        "metadata": {
            "appleDisabledPrintfConversions": [{"position": 4, "source": "%n"}],
            "appleSourceLocalization": device,
            "appleLocalizationSources": {"fr": localized_device},
            "defaultDevice": "iphone",
            "localizations": {"fr": {"variationAxes": localized_device["variations"]}},
            "sourceState": "translated",
            "sourceVariationAxes": device["variations"],
        },
    }
    messages["harbor.plural"] = {
        "defaultMessage": "{count, plural, one {{count} beacon} other {{count} beacons}}",
        "variants": {"one": "{count} beacon", "other": "{count} beacons"},
        "placeholders": [
            {"name": "count", "source": "%d", "kind": "integer", "position": 1}
        ],
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
            "appleSourceLocalization": plural,
            "sourcePluralStates": {"one": "translated", "other": "translated"},
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
    normalized_root = json.loads(
        json.dumps(deepcopy(root), ensure_ascii=False, sort_keys=True)
    )
    write_json(normalized, normalized_root)
    write_json(APPLE / f"{STEM}.normalized.compiled.json", compile_catalog(normalized))

    translations = {
        "harbor.zero": "Quai tranquille",
        "harbor.literal": "Quai%n tranquille",
        "harbor.line": "Quai\ntranquille",
        "harbor.positioned.🧭": "🧭Cap vers le quai",
        "harbor.device": "Port mobile",
        "harbor.plural#one": "{count} balise",
        "harbor.plural#other": "{count} balises",
    }
    replacement_values = {
        "harbor.zero": "Quai tra%nnquille",
        "harbor.literal": "Quai%%n tranquille",
        "harbor.line": "Quai\ntranquille",
        "harbor.positioned.🧭": "🧭Cap vers%2$n le quai%n",
        "harbor.device": "Port m%nobile",
        "harbor.plural#one": "%d%n balise",
        "harbor.plural#other": "%d%n%n balises",
    }
    original = source.read_text(encoding="utf-8")
    tokens = list(re.finditer(r'"value"\s*:\s*"((?:\\.|[^"\\])*)"', original))
    slots = []
    replacements = []
    ids = [
        "harbor.zero",
        "harbor.literal",
        "harbor.line",
        "harbor.positioned.🧭",
        "harbor.device",
        None,
        None,
        None,
        "harbor.plural#one",
        "harbor.plural#other",
    ]
    assert len(tokens) == len(ids), (len(tokens), len(ids))
    for match, identity in zip(tokens, ids, strict=True):
        if identity is None:
            continue
        position = len(original[: match.start(1)].encode("utf-8"))
        end = len(original[: match.end(1)].encode("utf-8"))
        message, _, variant = identity.partition("#")
        slot = {"id": message, "start": position, "end": end}
        if variant:
            slot["variant"] = variant
        slots.append(slot)
        encoded = json.dumps(replacement_values[identity], ensure_ascii=False)[1:-1]
        replacements.append((match.start(1), match.end(1), encoded))
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
    for start, end, replacement in reversed(replacements):
        localized = localized[:start] + replacement + localized[end:]
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    localized_path.write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compile_catalog(localized_path)
    )

    all_translations = {
        **{key: value for key, value in translations.items() if key != "harbor.device"},
        "harbor.device#@device#iphone": "Port mobile",
        "harbor.device#@device#mac": "Bureau%n paisible",
        "harbor.device#@device#other": "Large\nquai",
    }
    all_native = {
        **replacement_values,
        "harbor.device#@device#iphone": "Port m%nobile",
        "harbor.device#@device#mac": "Bureau%%n paisible",
        "harbor.device#@device#other": "Large\nquai",
    }
    all_slots = [slot for slot in slots if slot["id"] != "harbor.device"]
    all_replacements = [
        item
        for item, identity in zip(
            replacements, [value for value in ids if value], strict=True
        )
        if identity != "harbor.device"
    ]
    source_device = root["strings"]["harbor.device"]["localizations"]["en"][
        "variations"
    ]["device"]
    source_cursor = original.index('"harbor.device"')
    for device_name, descriptor in source_device.items():
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
        identity = f"harbor.device#@device#{device_name}"
        all_slots.append(
            {
                "id": "harbor.device",
                "selector": "@device",
                "variant": device_name,
                "start": len(original[: match.start(1)].encode()),
                "end": len(original[: match.end(1)].encode()),
            }
        )
        replacement = json.dumps(all_native[identity], ensure_ascii=False)[1:-1]
        all_replacements.append((match.start(1), match.end(1), replacement))
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
    for start, end, replacement in sorted(all_replacements, reverse=True):
        all_localized = all_localized[:start] + replacement + all_localized[end:]
    all_localized_path = APPLE / f"{STEM}.all-devices.localized.xcstrings"
    all_localized_path.write_text(all_localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.all-devices.localized.compiled.json",
        compile_catalog(all_localized_path),
    )


if __name__ == "__main__":
    main()
