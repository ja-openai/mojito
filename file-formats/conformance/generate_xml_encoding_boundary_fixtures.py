#!/usr/bin/env python3
"""Pin real Android/Foundation XML declaration, byte transport, and skeleton behavior."""

from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "manifest.json"
PREFIX = "portable-xml-encoding-boundary-"
ANDROID = ROOT / "fixtures" / "android"
APPLE = ROOT / "fixtures" / "apple"
ANDROID_ROOT = '<resources><string name="signal">Café tide</string></resources>'
APPLE_ROOT = (
    '<plist version="1.0"><dict><key>signal</key>'
    "<string>Café tide</string></dict></plist>"
)
STRINGSDICT_ROOT = (
    '<plist version="1.0"><dict><key>files.remaining</key><dict>'
    "<key>NSStringLocalizedFormatKey</key><string>%#@files@ remaining</string>"
    "<key>files</key><dict><key>NSStringFormatSpecTypeKey</key>"
    "<string>NSStringPluralRuleType</string>"
    "<key>NSStringFormatValueTypeKey</key><string>d</string>"
    "<key>one</key><string>%d café</string>"
    "<key>other</key><string>%d cafés</string>"
    "</dict></dict></dict></plist>"
)


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def load_oracles():
    modules = []
    for name in ("android_aapt2_oracle", "apple_plutil_oracle"):
        spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        modules.append(module)
    return modules[0], modules[1], modules[0].locate_aapt2(False)


def android_native(source: str, encoding: str | None, module, executable: Path):
    with tempfile.TemporaryDirectory(prefix="mojito-xml-encoding-android-") as value:
        directory = Path(value)
        resource = directory / "res" / "values" / "strings.xml"
        resource.parent.mkdir(parents=True)
        resource.write_bytes(module.encode_resource(source, encoding))
        compiled = directory / "compiled"
        compiled.mkdir()
        result = subprocess.run(
            [str(executable), "compile", str(resource), "-o", str(compiled)],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            return None
        result = subprocess.run(
            [str(executable), "dump", "apc", str(next(compiled.glob("*.arsc.flat")))],
            check=True,
            capture_output=True,
        )
        return module.compiled_catalog(result.stdout.decode("utf-8"))


def apple_native(source: str, encoding: str | None, suffix: str, module):
    with tempfile.TemporaryDirectory(prefix="mojito-xml-encoding-apple-") as value:
        resource = Path(value) / f"neutral.{suffix}"
        resource.write_bytes(module.encode(source, encoding))
        result = subprocess.run(
            ["/usr/bin/plutil", "-convert", "json", "-o", "-", str(resource)],
            capture_output=True,
            text=True,
        )
        return None if result.returncode else json.loads(result.stdout)


def expected(
    format_name: str,
    latin1_reinterpretation: bool = False,
    ascii_source: bool = False,
) -> dict:
    if format_name == "apple_stringsdict":
        result = copy.deepcopy(
            json.loads((APPLE / "plural.expected.json").read_text(encoding="utf-8"))
        )
        message = result["messages"]["files.remaining"]
        message["defaultMessage"] = (
            message["defaultMessage"]
            .replace("{files} file", "{files} café")
            .replace("{files} cafés", "{files} cafés")
        )
        message["variants"] = {
            "one": "{files} café",
            "other": "{files} cafés",
        }
        message["metadata"]["applePluralRules"]["files"]["variants"] = {
            "one": "%d café",
            "other": "%d cafés",
        }
        if ascii_source:
            serialized = json.dumps(result, ensure_ascii=False)
            result = json.loads(serialized.replace("café", "harbor"))
        if latin1_reinterpretation:
            serialized = json.dumps(result, ensure_ascii=False)
            result = json.loads(serialized.encode("utf-8").decode("iso-8859-1"))
        return result

    value = (
        "Harbor tide"
        if ascii_source
        else "CafÃ© tide" if latin1_reinterpretation else "Café tide"
    )
    return {
        "schemaVersion": 1,
        "sourceFormat": format_name,
        "messages": {"signal": {"defaultMessage": value}},
    }


def declaration(name: str, root: str) -> str:
    return f'<?xml version="1.0" encoding="{name}"?>\n{root}\n'


def scenarios(format_name: str):
    apple = format_name != "android"
    cases = [
        ("utf8-canonical", "UTF-8", None, None),
        ("utf8-case-insensitive", "utf-8", None, None),
        ("utf8-bom", "UTF-8", "UTF-8-BOM", None),
        ("latin1-native-octets", "ISO-8859-1", "ISO-8859-1", None),
        ("latin1-reinterprets-utf8-octets", "ISO-8859-1", None, None),
        ("ascii-native-octets", "US-ASCII", None, None),
        ("utf16le-generic", "UTF-16", "UTF-16LE-BOM", None),
        ("utf16le-explicit", "UTF-16LE", "UTF-16LE-BOM", None),
        ("utf16be-generic", "UTF-16", "UTF-16BE-BOM", None),
        ("utf16be-explicit", "UTF-16BE", "UTF-16BE-BOM", None),
        ("utf8-claims-utf16", "UTF-16", None, "INVALID_XML"),
        ("utf8-claims-utf16le", "UTF-16LE", None, "INVALID_XML"),
        ("utf8-claims-utf16be", "UTF-16BE", None, "INVALID_XML"),
        ("utf8-unknown-encoding", "X-NEUTRAL", None, "INVALID_XML"),
        ("ascii-declaration-rejects-nonascii", "US-ASCII", None, "INVALID_ENCODING"),
    ]
    if apple:
        cases.extend(
            [
                ("foundation-utf8-alias", "UTF8", None, None),
                ("foundation-utf8-underscore-alias", "UTF_8", None, None),
                ("foundation-latin1-alias", "latin1", "ISO-8859-1", None),
                ("foundation-utf16-bom-overrides-utf8", "UTF-8", "UTF-16LE-BOM", None),
                (
                    "foundation-utf16-bom-overrides-opposite-endian",
                    "UTF-16BE",
                    "UTF-16LE-BOM",
                    None,
                ),
                (
                    "foundation-bom-overrides-unknown-label",
                    "X-NEUTRAL",
                    "UTF-8-BOM",
                    None,
                ),
            ]
        )
    else:
        cases.extend(
            [
                ("android-rejects-utf8-alias", "UTF8", None, "INVALID_XML"),
                ("android-rejects-utf8-underscore", "UTF_8", None, "INVALID_XML"),
                ("android-rejects-latin1-alias", "latin1", None, "INVALID_XML"),
                ("utf16le-claims-utf8", "UTF-8", "UTF-16LE-BOM", "INVALID_XML"),
                ("utf16be-claims-utf8", "UTF-8", "UTF-16BE-BOM", "INVALID_XML"),
                (
                    "utf16le-claims-opposite-endian",
                    "UTF-16BE",
                    "UTF-16LE-BOM",
                    "INVALID_XML",
                ),
                (
                    "utf16be-claims-opposite-endian",
                    "UTF-16LE",
                    "UTF-16BE-BOM",
                    "INVALID_XML",
                ),
                ("utf8-bom-claims-latin1", "ISO-8859-1", "UTF-8-BOM", "INVALID_XML"),
            ]
        )
    return cases


def source_template(
    manifest: dict,
    format_name: str,
    name: str,
    source: str,
    encoding: str,
    android,
    apple,
    executable: Path,
) -> None:
    folder = ANDROID if format_name == "android" else APPLE
    suffix = "xml" if format_name == "android" else "strings"
    stem = f"xml-encoding-boundary-{name}"
    localized = source.replace(
        "Café tide", '"Marée calme"' if format_name == "android" else "Marée calme"
    )
    (folder / f"{stem}.localized.{suffix}").write_text(localized, encoding="utf-8")
    write_json(folder / f"{stem}.translations.json", {"signal": "Marée calme"})
    if encoding == "UTF-16LE-BOM":
        codec, offset = "utf-16-le", 2
    elif encoding == "UTF-16BE-BOM":
        codec, offset = "utf-16-be", 2
    else:
        codec, offset = "iso-8859-1", 0
    position = source.index("Café tide")
    start = offset + len(source[:position].encode(codec))
    end = start + len("Café tide".encode(codec))
    write_json(
        folder / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": format_name,
            "encoding": encoding,
            "source": source,
            "slots": [{"id": "signal", "start": start, "end": end}],
        },
    )
    native = (
        android_native(source, encoding, android, executable)
        if format_name == "android"
        else apple_native(source, encoding, suffix, apple)
    )
    localized_native = (
        android_native(localized, encoding, android, executable)
        if format_name == "android"
        else apple_native(localized, encoding, suffix, apple)
    )
    assert native is not None and localized_native is not None
    write_json(folder / f"{stem}.compiled.json", native)
    write_json(folder / f"{stem}.localized.compiled.json", localized_native)
    platform = "android" if format_name == "android" else "apple"
    manifest["sourceSkeletons"].append(
        {
            "id": f"{format_name.replace('_', '-')}-source-{PREFIX}{name}",
            "format": format_name,
            "input": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.{suffix}",
            "expected": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.expected.skeleton.json",
            "translations": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.translations.json",
            "localized": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.localized.{suffix}",
            f"{platform}Compiled": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.compiled.json",
            f"{platform}LocalizedCompiled": f"fixtures/{'android' if platform == 'android' else 'apple'}/{stem}.localized.compiled.json",
            "encoding": encoding,
        }
    )


def main() -> None:
    android, apple, executable = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for field in ("cases", "sourceSkeletons", "sourceSkeletonErrors"):
        manifest[field] = [case for case in manifest[field] if PREFIX not in case["id"]]

    roots = {
        "android": ANDROID_ROOT,
        "apple_strings": APPLE_ROOT,
        "apple_stringsdict": STRINGSDICT_ROOT,
    }
    for format_name, root in roots.items():
        folder = ANDROID if format_name == "android" else APPLE
        suffix = {
            "android": "xml",
            "apple_strings": "strings",
            "apple_stringsdict": "stringsdict",
        }[format_name]
        prefix = format_name.replace("_", "-")
        for name, declared, encoding, error in scenarios(format_name):
            stem = f"xml-encoding-boundary-{name}"
            source = declaration(
                declared,
                (
                    root.replace("Café", "Harbor").replace("café", "harbor")
                    if name == "ascii-native-octets"
                    else root
                ),
            )
            (folder / f"{stem}.{suffix}").write_text(source, encoding="utf-8")
            native = (
                android_native(source, encoding, android, executable)
                if format_name == "android"
                else apple_native(source, encoding, suffix, apple)
            )
            case = {
                "id": f"{prefix}-{PREFIX}{name}",
                "format": format_name,
                "input": f"fixtures/{'android' if format_name == 'android' else 'apple'}/{stem}.{suffix}",
            }
            if encoding is not None:
                case["encoding"] = encoding
            if error is None:
                assert native is not None, (format_name, name)
                output = expected(
                    format_name,
                    name == "latin1-reinterprets-utf8-octets",
                    name == "ascii-native-octets",
                )
                expected_name = f"{stem}.{suffix}.expected.json"
                write_json(folder / expected_name, output)
                case["expected"] = (
                    f"fixtures/{'android' if format_name == 'android' else 'apple'}/{expected_name}"
                )
            else:
                case["error"] = error

            platform = "android" if format_name == "android" else "apple"
            if native is None:
                case[f"{platform}Oracle"] = "reject"
            else:
                compiled_name = f"{stem}.{suffix}.compiled.json"
                write_json(folder / compiled_name, native)
                case[f"{platform}Compiled"] = (
                    f"fixtures/{'android' if platform == 'android' else 'apple'}/{compiled_name}"
                )
                if error is not None:
                    case[f"{platform}Oracle"] = "accept"
            manifest["cases"].append(case)

            if error is not None and name in {
                "utf8-claims-utf16",
                "utf8-unknown-encoding",
                "utf16le-claims-opposite-endian",
            }:
                skeleton = {
                    "id": f"{prefix}-source-{PREFIX}{name}",
                    "format": format_name,
                    "input": case["input"],
                    "error": error,
                }
                if encoding is not None:
                    skeleton["encoding"] = encoding
                manifest["sourceSkeletonErrors"].append(skeleton)

            if format_name in {"android", "apple_strings"} and name in {
                "latin1-native-octets",
                "utf16le-explicit",
                "foundation-utf16-bom-overrides-utf8",
            }:
                source_template(
                    manifest,
                    format_name,
                    name,
                    source,
                    encoding,
                    android,
                    apple,
                    executable,
                )

    write_json(MANIFEST, manifest)
    print(
        "Generated native XML declaration/byte-encoding boundaries, contradictory "
        "transport rejection, Latin-1 extraction, and exact source templates."
    )


if __name__ == "__main__":
    main()
