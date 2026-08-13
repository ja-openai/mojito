#!/usr/bin/env python3
"""Generate native-verified Xcode combined device/plural source templates."""

from __future__ import annotations

import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "catalog-device.all-branches"
TOOL = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def compile_catalog(source: Path) -> dict[str, object]:
    executable = shutil.which("xcstringstool") or str(TOOL)
    with tempfile.TemporaryDirectory(prefix="mojito-xcode-device-plural-") as folder:
        output = Path(folder)
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


def offset(source: str, position: int, encoding: str) -> int:
    if encoding == "UTF-16LE-BOM":
        return 2 + len(source[:position].encode("utf-16-le"))
    return len(source[:position].encode())


def main() -> None:
    source = (APPLE / "catalog-device.xcstrings").read_text(encoding="utf-8")
    catalog = json.loads(source)
    translations = {
        "instruction#@device#iphone": "Touchez {arg0} rive mobile",
        "instruction#@device#mac": "Cliquez {arg0} rive bureau",
        "device.counter#@device=iphone#one": "{count} signal mobile sûr",
        "device.counter#@device=iphone#other": "{count} signaux mobiles sûrs",
        "device.counter#@device=mac#one": "{count} balise bureau calme",
        "device.counter#@device=mac#other": "{count} balises bureau calmes",
    }
    native = {
        "instruction#@device#iphone": "Touchez %@ rive mobile",
        "instruction#@device#mac": "Cliquez %@ rive bureau",
        "device.counter#@device=iphone#one": "%lld signal mobile sûr",
        "device.counter#@device=iphone#other": "%lld signaux mobiles sûrs",
        "device.counter#@device=mac#one": "%lld balise bureau calme",
        "device.counter#@device=mac#other": "%lld balises bureau calmes",
    }
    slots = []
    replacements = []
    cursor = 0
    for identity, descriptor in catalog["strings"].items():
        devices = descriptor["localizations"]["en"]["variations"]["device"]
        for device, branch in devices.items():
            values = (
                {None: branch["stringUnit"]}
                if "stringUnit" in branch
                else {
                    category: value["stringUnit"]
                    for category, value in branch["variations"]["plural"].items()
                }
            )
            for category, unit in values.items():
                original = json.dumps(unit["value"], ensure_ascii=False)[1:-1]
                pattern = re.compile(r'"value"\s*:\s*"(' + re.escape(original) + r')"')
                match = pattern.search(source, cursor)
                if match is None:
                    raise RuntimeError(
                        f"Missing Xcode source value {identity}/{device}/{category}"
                    )
                cursor = match.end()
                selector = "@device" if category is None else f"@device={device}"
                variant = device if category is None else category
                key = f"{identity}#{selector}#{variant}"
                slots.append(
                    {
                        "id": identity,
                        "selector": selector,
                        "variant": variant,
                        "start": match.start(1),
                        "end": match.end(1),
                    }
                )
                replacement = json.dumps(native[key], ensure_ascii=False)[1:-1]
                replacements.append((match.start(1), match.end(1), replacement))

    write_json(APPLE / f"{STEM}.translations.json", translations)
    for encoding, suffix in (("UTF-8", ""), ("UTF-16LE-BOM", ".utf16le")):
        owned = [
            {
                **slot,
                "start": offset(source, slot["start"], encoding),
                "end": offset(source, slot["end"], encoding),
            }
            for slot in slots
        ]
        write_json(
            APPLE / f"{STEM}{suffix}.expected.skeleton.json",
            {
                "schemaVersion": 1,
                "sourceFormat": "apple_xcstrings",
                "encoding": encoding,
                "source": source,
                "slots": owned,
            },
        )
    localized = source
    for start, end, replacement in reversed(replacements):
        localized = localized[:start] + replacement + localized[end:]
    localized_path = APPLE / f"{STEM}.localized.xcstrings"
    localized_path.write_text(localized, encoding="utf-8")
    write_json(
        APPLE / f"{STEM}.localized.compiled.json", compile_catalog(localized_path)
    )


if __name__ == "__main__":
    main()
