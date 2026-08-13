#!/usr/bin/env python3
"""Pin compiler-owned Unicode characters in Android selected product identities."""

from __future__ import annotations

import copy
import json
import tempfile
from pathlib import Path

from generate_xml_encoding_boundary_fixtures import MANIFEST, load_oracles, write_json


ROOT = Path(__file__).resolve().parent
FOLDER = ROOT / "fixtures" / "android" / "overlays"
PREFIX = "portable-android-product-unicode-whitespace-"
POINTS = (
    0x0085,
    0x00A0,
    0x1680,
    *range(0x2000, 0x200B),
    0x2028,
    0x2029,
    0x202F,
    0x205F,
    0x3000,
)
PATHS = {
    "library": "src/library/res/values/strings.xml",
    "main": "src/main/res/values/strings.xml",
}


def snapshot(module, executable: Path, case: dict) -> dict:
    with tempfile.TemporaryDirectory(prefix="mojito-product-whitespace-") as temporary:
        status, value, diagnostic = module.compile_android_overlay(
            executable, case, Path(temporary)
        )
    assert status == 0 and value is not None, (case["id"], diagnostic)
    return value


def catalog(signal: str, anchor: str | None = None) -> dict:
    metadata = {
        "androidResourcePath": PATHS["main"],
        "androidResourceQualifiers": [],
        "androidOverlaySourceSet": "main",
    }
    messages = {"signal": {"defaultMessage": signal, "metadata": metadata}}
    if anchor is not None:
        messages["anchor"] = {
            "defaultMessage": anchor,
            "metadata": copy.deepcopy(metadata),
        }
    return {"schemaVersion": 1, "sourceFormat": "android", "messages": messages}


def overlays(manifest: dict, android, executable: Path) -> None:
    for point in POINTS:
        character = chr(point)
        products = {
            "leading": character + "tablet",
            "trailing": "tablet" + character,
            "both": character + "tablet" + character,
            "interior": "tab" + character + "let",
            "only": character,
        }
        for shape, product in products.items():
            name = f"u{point:04x}-{shape}"
            stem = f"product-unicode-whitespace-{name}"
            source = (
                "<resources>\n"
                '  <string name="signal">Default bay</string>\n'
                f'  <string name="signal" product="{product}">Selected bay</string>\n'
                "</resources>\n"
            )
            (FOLDER / f"{stem}.xml").write_text(source, encoding="utf-8")
            case = {
                "id": f"android-overlay-{PREFIX}{name}",
                "androidSelectedProducts": [product],
                "inputs": [
                    {
                        "sourceSet": "main",
                        "resourcePath": PATHS["main"],
                        "input": f"fixtures/android/overlays/{stem}.xml",
                    }
                ],
                "expected": f"fixtures/android/overlays/{stem}.expected.json",
                "androidLinked": f"fixtures/android/overlays/{stem}.linked.json",
            }
            write_json(FOLDER / f"{stem}.expected.json", catalog("Selected bay"))
            write_json(
                FOLDER / f"{stem}.linked.json", snapshot(android, executable, case)
            )
            manifest["androidOverlays"].append(case)


def codec(encoding: str) -> tuple[str, int]:
    return {
        "UTF-8": ("utf-8", 0),
        "UTF-8-BOM": ("utf-8", 3),
        "UTF-16LE": ("utf-16-le", 0),
        "UTF-16BE-BOM": ("utf-16-be", 2),
        "ISO-8859-1": ("iso-8859-1", 0),
    }[encoding]


def declaration(encoding: str) -> str:
    native = (
        "UTF-8"
        if encoding == "UTF-8-BOM"
        else "UTF-16BE" if encoding == "UTF-16BE-BOM" else encoding
    )
    return f'<?xml version="1.0" encoding="{native}"?>\n'


def source_templates(manifest: dict, android, executable: Path) -> None:
    matrix = {
        "nbsp-only": ("\u00a0", "ISO-8859-1", "UTF-16LE"),
        "em-space-only": ("\u2003", "UTF-16BE-BOM", "UTF-8-BOM"),
        "nel-leading": ("\u0085tablet", "ISO-8859-1", "UTF-16LE"),
        "narrow-trailing": ("tablet\u202f", "UTF-16LE", "UTF-8"),
    }
    for name, (product, library_encoding, main_encoding) in matrix.items():
        library = (
            declaration(library_encoding)
            + "<resources>\n"
            + '  <string name="signal">Library default</string>\n'
            + f'  <string name="signal" product="{product}">Library selected</string>\n'
            + '  <string name="anchor">Library anchor</string>\n'
            + "</resources>\n"
        )
        main = (
            declaration(main_encoding)
            + "<resources>\n"
            + f'  <string name="signal" product="{product}">Main selected</string>\n'
            + '  <string name="anchor">Main anchor</string>\n'
            + "</resources>\n"
        )
        localized = main.replace("Main selected", '"Marée choisie"').replace(
            "Main anchor", '"Ancre sûre"'
        )
        prefix = f"product-unicode-whitespace-source-{name}"
        for source_set, content in (
            ("library", library),
            ("main", main),
            ("main.localized", localized),
        ):
            (FOLDER / f"{prefix}-{source_set}.xml").write_text(
                content, encoding="utf-8"
            )
        write_json(
            FOLDER / f"{prefix}.expected.json", catalog("Main selected", "Main anchor")
        )
        write_json(
            FOLDER / f"{prefix}.translations.json",
            {"signal": "Marée choisie", "anchor": "Ancre sûre"},
        )

        main_codec, bom = codec(main_encoding)
        slots = []
        for identity, text in (
            (f"signal@product={product}", "Main selected"),
            ("anchor", "Main anchor"),
        ):
            start = main.index(text)
            slots.append(
                {
                    "id": identity,
                    "start": bom + len(main[:start].encode(main_codec)),
                    "end": bom + len(main[: start + len(text)].encode(main_codec)),
                }
            )
        skeleton = {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "sources": [
                {
                    "sourceSet": "library",
                    "resourcePath": PATHS["library"],
                    "skeleton": {
                        "schemaVersion": 1,
                        "sourceFormat": "android",
                        "encoding": library_encoding,
                        "source": library,
                        "androidResourcePath": PATHS["library"],
                        "slots": [],
                    },
                },
                {
                    "sourceSet": "main",
                    "resourcePath": PATHS["main"],
                    "skeleton": {
                        "schemaVersion": 1,
                        "sourceFormat": "android",
                        "encoding": main_encoding,
                        "source": main,
                        "androidResourcePath": PATHS["main"],
                        "slots": slots,
                    },
                },
            ],
            "androidSelectedProducts": [product],
            "androidRuntimeSlotOwners": {
                "anchor": "anchor",
                "signal": f"signal@product={product}",
            },
        }
        write_json(FOLDER / f"{prefix}.expected.skeleton.json", skeleton)
        inputs = [
            {
                "sourceSet": "library",
                "resourcePath": PATHS["library"],
                "input": f"fixtures/android/overlays/{prefix}-library.xml",
                "localized": f"fixtures/android/overlays/{prefix}-library.xml",
                "encoding": library_encoding,
            },
            {
                "sourceSet": "main",
                "resourcePath": PATHS["main"],
                "input": f"fixtures/android/overlays/{prefix}-main.xml",
                "localized": f"fixtures/android/overlays/{prefix}-main.localized.xml",
                "encoding": main_encoding,
            },
        ]
        case = {
            "id": f"android-overlay-source-{PREFIX}{name}",
            "inputs": inputs,
            "expected": f"fixtures/android/overlays/{prefix}.expected.skeleton.json",
            "catalog": f"fixtures/android/overlays/{prefix}.expected.json",
            "translations": f"fixtures/android/overlays/{prefix}.translations.json",
            "androidSelectedProducts": [product],
            "androidLinked": f"fixtures/android/overlays/{prefix}.linked.json",
            "androidLocalizedLinked": f"fixtures/android/overlays/{prefix}.localized.linked.json",
            "rejectBuilds": [
                {"androidSelectedProducts": [], "error": "INVALID_ANDROID_PRODUCT"},
                {
                    "androidSelectedProducts": [product, product],
                    "error": "INVALID_ANDROID_PRODUCT",
                },
                {
                    "androidSelectedProducts": [" tablet"],
                    "error": "INVALID_ANDROID_PRODUCT",
                },
            ],
            "reject": [
                {
                    "translations": {f"signal@product={product}": "forged"},
                    "error": "UNKNOWN_OVERLAY_SKELETON_SLOT",
                }
            ],
        }
        write_json(
            FOLDER / f"{prefix}.linked.json", snapshot(android, executable, case)
        )
        translated_case = copy.deepcopy(case)
        translated_case["id"] += "-localized"
        for entry in translated_case["inputs"]:
            entry["input"] = entry["localized"]
        write_json(
            FOLDER / f"{prefix}.localized.linked.json",
            snapshot(android, executable, translated_case),
        )
        manifest["androidOverlaySourceSkeletons"].append(case)


def main() -> None:
    android, _, executable = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for key in ("androidOverlays", "androidOverlaySourceSkeletons"):
        manifest[key] = [case for case in manifest[key] if PREFIX not in case["id"]]
    overlays(manifest, android, executable)
    source_templates(manifest, android, executable)
    write_json(MANIFEST, manifest)
    print(
        f"Generated {len(POINTS) * 5} native-linked Unicode product overlays "
        "and four mixed-encoding, byte-preserving multi-file source templates."
    )


if __name__ == "__main__":
    main()
