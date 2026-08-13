#!/usr/bin/env python3
"""Generate neutral Foundation fixtures for hidden `%n` argument ownership."""

from __future__ import annotations

import html
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"
STEM = "disabled-printf-arguments"
XCODE = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")
PRINTF = re.compile(r"%(?:(\d+)\$)?([@dn%])")

NATIVE = {
    "harbor.implicit": "North %n %@",
    "harbor.middle": "%@ dock %n %@",
    "harbor.repeated": "%n%n %@ port",
    "harbor.explicit": "%2$n %1$@ dock",
    "harbor.mixed": "%2$n %@ dock",
    "harbor.overlap": "%n %1$@ lane",
    "harbor.integer": "%n %d markers",
    "harbor.escaped": "%%n %@ tide",
    "harbor.unicode.🧭": "🧭%n %@ reef",
}

LOCALIZED = {
    "harbor.implicit": "Ouest %n %@",
    "harbor.middle": "%@ quai %n %@",
    "harbor.repeated": "%n%n %@ quai",
    "harbor.explicit": "%2$n %1$@ quai",
    "harbor.mixed": "%2$n %@ quai",
    "harbor.overlap": "%n %1$@ voie",
    "harbor.integer": "%n %d balises",
    "harbor.escaped": "%%n %@ mare",
    "harbor.unicode.🧭": "🧭%n %@ baie",
}


def write_json(path: Path, value: object, *, sort_keys: bool = False) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=sort_keys) + "\n",
        encoding="utf-8",
    )


def descriptor(native: str) -> dict[str, object]:
    result: list[str] = []
    placeholders: list[dict[str, object]] = []
    disabled: list[dict[str, object]] = []
    previous = 0
    implicit = 0
    for match in PRINTF.finditer(native):
        result.append(native[previous : match.start()])
        explicit, conversion = match.groups()
        if conversion == "%":
            result.append("%")
        else:
            if explicit:
                argument = int(explicit)
            else:
                implicit += 1
                argument = implicit
            if conversion == "n":
                disabled.append(
                    {
                        "position": len("".join(result)),
                        "source": match.group(),
                        "argumentPosition": argument,
                    }
                )
            else:
                placeholder = {
                    "name": f"arg{argument - 1}",
                    "source": match.group(),
                    "kind": "integer" if conversion == "d" else "string",
                    "position": argument,
                }
                if placeholder not in placeholders:
                    placeholders.append(placeholder)
                result.append("{" + placeholder["name"] + "}")
        previous = match.end()
    result.append(native[previous:])
    value: dict[str, object] = {"defaultMessage": "".join(result)}
    if placeholders:
        value["placeholders"] = placeholders
    if disabled:
        value["metadata"] = {"appleDisabledPrintfConversions": disabled}
    return value


def rendered(canonical: str, values: dict[str, object]) -> str:
    return re.sub(r"\{(arg\d+)\}", lambda match: str(values[match.group(1)]), canonical)


def samples(values: dict[str, str]) -> list[dict[str, object]]:
    result = []
    for key, native in values.items():
        message = descriptor(native)
        substitutions = {
            entry["name"]: 7 if entry["kind"] == "integer" else "Rowan"
            for entry in message.get("placeholders", [])
        }
        result.append(
            {
                "message": key,
                "values": substitutions,
                "expected": rendered(message["defaultMessage"], substitutions),
            }
        )
    return result


def direct_samples(values: dict[str, str]) -> list[dict[str, object]]:
    result = []
    for sample in samples(values):
        message = descriptor(values[sample["message"]])
        positions = {
            item["position"]: sample["values"][item["name"]]
            for item in message.get("placeholders", [])
        }
        for item in message.get("metadata", {}).get(
            "appleDisabledPrintfConversions", []
        ):
            positions.setdefault(item["argumentPosition"], 0)
        result.append(
            {
                "message": sample["message"],
                "arguments": [
                    positions[index] for index in range(1, max(positions) + 1)
                ],
                "expected": sample["expected"],
            }
        )
    return result


def openstep(values: dict[str, str]) -> str:
    return "".join(f'"{key}" = "{value}";\n' for key, value in values.items())


def xml(values: dict[str, str]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0">',
        "<dict>",
    ]
    for key, value in values.items():
        lines.append(
            f"  <key>{html.escape(key)}</key><string>{html.escape(value)}</string>"
        )
    return "\n".join([*lines, "</dict>", "</plist>", ""])


def normalized(values: dict[str, str]) -> str:
    return "".join(f'"{key}" = "{values[key]}";\n' for key in sorted(values))


def catalog(values: dict[str, str], source_format: str) -> dict[str, object]:
    messages = {key: descriptor(value) for key, value in sorted(values.items())}
    result: dict[str, object] = {
        "schemaVersion": 1,
        "sourceFormat": source_format,
        "messages": messages,
    }
    if source_format == "apple_xcstrings":
        result["locale"] = "en"
        for key, message in messages.items():
            metadata = message.setdefault("metadata", {})
            metadata["appleSourceLocalization"] = {
                "stringUnit": {"state": "translated", "value": values[key]}
            }
            metadata["sourceState"] = "translated"
    return result


def xcatalog(values: dict[str, str]) -> dict[str, object]:
    return {
        "sourceLanguage": "en",
        "strings": {
            key: {
                "localizations": {
                    "en": {"stringUnit": {"state": "translated", "value": value}}
                }
            }
            for key, value in values.items()
        },
        "version": "1.0",
    }


def compiled_xcatalog(path: Path) -> dict[str, object]:
    executable = shutil.which("xcstringstool") or str(XCODE)
    with tempfile.TemporaryDirectory(prefix="mojito-foundation-argument-") as directory:
        result = subprocess.run(
            [executable, "compile", str(path), "--output-directory", directory],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        output = Path(directory)
        return {
            str(
                path.relative_to(output).parent / f"catalog{path.suffix}"
            ): plistlib.loads(path.read_bytes())
            for path in sorted(output.rglob("*"))
            if path.is_file()
        }


def skeleton(source: str, kind: str, encoding: str = "UTF-8") -> dict[str, object]:
    expression = (
        re.compile(r'"([^"\\]+)"\s*=\s*"((?:\\.|[^"\\])*)"')
        if kind == "openstep"
        else re.compile(r"<key>([^<]+)</key><string>([^<]*)</string>")
    )
    codec = "utf-16-le" if encoding == "UTF-16LE-BOM" else "utf-8"
    bom = 2 if encoding == "UTF-16LE-BOM" else 0
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_strings",
        "encoding": encoding,
        "source": source,
        "slots": [
            {
                "id": html.unescape(match.group(1)),
                "start": bom + len(source[: match.start(2)].encode(codec)),
                "end": bom + len(source[: match.end(2)].encode(codec)),
            }
            for match in expression.finditer(source)
        ],
    }


def xcode_skeleton(source: str) -> dict[str, object]:
    tokens = list(re.finditer(r'"value"\s*:\s*"((?:\\.|[^"\\])*)"', source))
    return {
        "schemaVersion": 1,
        "sourceFormat": "apple_xcstrings",
        "encoding": "UTF-8",
        "source": source,
        "slots": [
            {
                "id": key,
                "start": len(source[: token.start(1)].encode("utf-8")),
                "end": len(source[: token.end(1)].encode("utf-8")),
            }
            for key, token in zip(NATIVE, tokens, strict=True)
        ],
    }


def update_manifest() -> None:
    path = ROOT / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    prefix = "apple-foundation-disabled-conversions-reserve-native-argument-slots"
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(prefix)
    ]
    runtime = samples(NATIVE)
    mutations = [
        {
            "message": "harbor.implicit",
            "metadata": {
                "appleDisabledPrintfConversions": [
                    {"position": 6, "source": "%n", "argumentPosition": 0}
                ]
            },
            "error": "INVALID_APPLE_PRINTF_CONVERSION",
        },
        {
            "message": "harbor.explicit",
            "metadata": {
                "appleDisabledPrintfConversions": [
                    {"position": 0, "source": "%2$n", "argumentPosition": 3}
                ]
            },
            "error": "INVALID_APPLE_PRINTF_CONVERSION",
        },
    ]
    base = {
        "format": "apple_strings",
        "expected": f"fixtures/apple/{STEM}.expected.json",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleNormalized": f"fixtures/apple/{STEM}.normalized.strings",
        "appleStringsRuntimeSamples": runtime,
        "writerMutations": mutations,
    }
    manifest["cases"].extend(
        [
            {
                "id": prefix + "-openstep",
                "input": f"fixtures/apple/{STEM}.strings",
                **base,
                "okapi": {
                    "policy": "different",
                    "assetPath": "en.lproj/Localizable.strings",
                    "expected": f"fixtures/okapi/apple-{STEM}.json",
                    "reason": "Foundation reserves hidden native argument slots for genuine %n conversions, while Okapi exposes raw conversions and cannot recover visible argument ownership.",
                },
            },
            {
                "id": prefix + "-xml-property-list",
                "input": f"fixtures/apple/{STEM}.plist.strings",
                **base,
            },
            {
                "id": prefix + "-xcode-catalog",
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{STEM}.xcstrings",
                "expected": f"fixtures/apple/{STEM}.xcstrings.expected.json",
                "xcstringsCompiled": f"fixtures/apple/{STEM}.xcstrings.compiled.json",
                "xcstringsNormalized": f"fixtures/apple/{STEM}.normalized.xcstrings",
                "xcstringsNormalizedCompiled": f"fixtures/apple/{STEM}.xcstrings.compiled.json",
                "xcstringsRuntimeSamples": runtime,
                "okapi": {
                    "policy": "unsupported",
                    "assetPath": "en.lproj/Localizable.xcstrings",
                    "reason": "The legacy extension mapper rejects Xcode catalogs; the portable implementation retains both visible and Foundation-disabled argument positions.",
                },
            },
        ]
    )

    skeleton_prefix = "apple-source-skeleton-preserves-hidden-foundation-argument-slots"
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(skeleton_prefix)
    ]
    common = {
        "format": "apple_strings",
        "translations": f"fixtures/apple/{STEM}.translations.json",
        "appleCompiled": f"fixtures/apple/{STEM}.compiled.json",
        "appleLocalizedCompiled": f"fixtures/apple/{STEM}.localized.compiled.json",
        "appleOriginalRuntimeSamples": direct_samples(NATIVE),
        "appleLocalizedRuntimeSamples": direct_samples(LOCALIZED),
    }
    manifest["sourceSkeletons"].extend(
        [
            {
                "id": skeleton_prefix + "-openstep",
                "input": f"fixtures/apple/{STEM}.strings",
                "expected": f"fixtures/apple/{STEM}.expected.skeleton.json",
                "localized": f"fixtures/apple/{STEM}.localized.strings",
                **common,
            },
            {
                "id": skeleton_prefix + "-utf16-openstep",
                "encoding": "UTF-16LE-BOM",
                "input": f"fixtures/apple/{STEM}.strings",
                "expected": f"fixtures/apple/{STEM}.utf16.expected.skeleton.json",
                "localized": f"fixtures/apple/{STEM}.localized.strings",
                **common,
            },
            {
                "id": skeleton_prefix + "-xml-property-list",
                "input": f"fixtures/apple/{STEM}.plist.strings",
                "expected": f"fixtures/apple/{STEM}.plist.expected.skeleton.json",
                "localized": f"fixtures/apple/{STEM}.localized.plist.strings",
                **common,
            },
            {
                "id": skeleton_prefix + "-xcode-catalog",
                "format": "apple_xcstrings",
                "input": f"fixtures/apple/{STEM}.xcstrings",
                "expected": f"fixtures/apple/{STEM}.xcstrings.expected.skeleton.json",
                "translations": f"fixtures/apple/{STEM}.translations.json",
                "localized": f"fixtures/apple/{STEM}.localized.xcstrings",
                "xcstringsCompiled": f"fixtures/apple/{STEM}.xcstrings.compiled.json",
                "xcstringsLocalizedCompiled": f"fixtures/apple/{STEM}.localized.xcstrings.compiled.json",
                "xcstringsOriginalRuntimeSamples": direct_samples(NATIVE),
                "xcstringsLocalizedRuntimeSamples": direct_samples(LOCALIZED),
            },
        ]
    )

    shadow_id = "shadow-apple-foundation-hidden-disabled-argument-ownership"
    manifest["shadowComparisons"] = [
        entry for entry in manifest["shadowComparisons"] if entry["id"] != shadow_id
    ]
    manifest["shadowComparisons"].append(
        {
            "id": shadow_id,
            "case": prefix + "-openstep",
            "expected": f"fixtures/shadow/apple-{STEM}.json",
        }
    )
    write_json(path, manifest)


def main() -> None:
    source = openstep(NATIVE)
    local = openstep(LOCALIZED)
    xml_source = xml(NATIVE)
    xml_local = xml(LOCALIZED)
    (APPLE / f"{STEM}.strings").write_text(source, encoding="utf-8")
    (APPLE / f"{STEM}.localized.strings").write_text(local, encoding="utf-8")
    (APPLE / f"{STEM}.plist.strings").write_text(xml_source, encoding="utf-8")
    (APPLE / f"{STEM}.localized.plist.strings").write_text(xml_local, encoding="utf-8")
    (APPLE / f"{STEM}.normalized.strings").write_text(
        normalized(NATIVE), encoding="utf-8"
    )
    write_json(APPLE / f"{STEM}.expected.json", catalog(NATIVE, "apple_strings"))
    write_json(APPLE / f"{STEM}.compiled.json", NATIVE)
    write_json(APPLE / f"{STEM}.localized.compiled.json", LOCALIZED)
    translations = {
        key: descriptor(value)["defaultMessage"] for key, value in LOCALIZED.items()
    }
    write_json(APPLE / f"{STEM}.translations.json", translations)
    write_json(APPLE / f"{STEM}.expected.skeleton.json", skeleton(source, "openstep"))
    write_json(
        APPLE / f"{STEM}.utf16.expected.skeleton.json",
        skeleton(source, "openstep", "UTF-16LE-BOM"),
    )
    write_json(
        APPLE / f"{STEM}.plist.expected.skeleton.json", skeleton(xml_source, "xml")
    )

    xcode = APPLE / f"{STEM}.xcstrings"
    write_json(xcode, xcatalog(NATIVE))
    write_json(
        APPLE / f"{STEM}.xcstrings.expected.json", catalog(NATIVE, "apple_xcstrings")
    )
    normalized_xcode = APPLE / f"{STEM}.normalized.xcstrings"
    write_json(normalized_xcode, xcatalog(NATIVE), sort_keys=True)
    write_json(APPLE / f"{STEM}.xcstrings.compiled.json", compiled_xcatalog(xcode))
    write_json(
        APPLE / f"{STEM}.xcstrings.expected.skeleton.json",
        xcode_skeleton(xcode.read_text(encoding="utf-8")),
    )
    local_xcode = APPLE / f"{STEM}.localized.xcstrings"
    write_json(local_xcode, xcatalog(LOCALIZED))
    write_json(
        APPLE / f"{STEM}.localized.xcstrings.compiled.json",
        compiled_xcatalog(local_xcode),
    )

    write_json(
        ROOT / "fixtures" / "okapi" / f"apple-{STEM}.json",
        {
            "filterConfigId": "okf_regex@mojito-macStrings",
            "units": [{"name": key, "source": value} for key, value in NATIVE.items()],
        },
    )
    write_json(
        ROOT / "fixtures" / "shadow" / f"apple-{STEM}.json",
        {
            "sourceFormat": "apple_strings",
            "canonicalUnits": len(NATIVE),
            "legacyUnits": len(NATIVE),
            "outcome": "mismatch",
            "differences": [
                {"category": "source_mismatch", "id": key} for key in sorted(NATIVE)
            ],
        },
    )
    update_manifest()


if __name__ == "__main__":
    main()
