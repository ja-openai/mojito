#!/usr/bin/env python3
"""Verify selected Android products across native mixed source-set byte encodings."""

from __future__ import annotations

import copy
import json
import tempfile
from pathlib import Path

from generate_xml_encoding_boundary_fixtures import (
    ANDROID,
    MANIFEST,
    load_oracles,
    write_json,
)


PREFIX = "portable-android-product-encoding-"
FOLDER = ANDROID / "overlays"
REPLACEMENTS = {
    "Shared overlay anchor": "Shared café anchor",
    "Main default harbor route": "Main default café route",
    "Main tablet harbor route": "Main tablet café route",
    "Main default beacon": "Main default café beacon",
    "Main tablet beacon": "Main tablet café beacon",
    "Upper tablet beacon": "Upper café beacon",
    "Upper universal fallback": "Upper café fallback",
    "Main universal fallback": "Main café fallback",
    "One main default signal": "One main default café signal",
    "Several main default signals": "Several main default café signals",
    "One main tablet signal": "One main tablet café signal",
    "Several main tablet signals": "Several main tablet café signals",
    "Main default channel": "Main default café channel",
    "Main tablet channel": "Main tablet café channel",
    "Upper visible channel": "Upper visible café channel",
}
MATRIX = (
    ("tablet-le-be", ["tablet"], "UTF-16LE", "UTF-16BE", "tablet"),
    ("tablet-be-le", ["tablet"], "UTF-16BE", "UTF-16LE", "tablet"),
    ("tablet-latin1-le", ["tablet"], "ISO-8859-1", "UTF-16LE", "tablet"),
    ("tablet-bom-latin1", ["tablet"], "UTF-16LE-BOM", "ISO-8859-1", "tablet"),
    (
        "default-tablet-utf8-bebom",
        ["default", "tablet"],
        "UTF-8-BOM",
        "UTF-16BE-BOM",
        "tablet",
    ),
    ("default-bebom-latin1", ["default"], "UTF-16BE-BOM", "ISO-8859-1", "default"),
    ("watch-latin1-lebom", ["watch"], "ISO-8859-1", "UTF-16LE-BOM", "default"),
)


def rewrite(text: str) -> str:
    for original, replacement in REPLACEMENTS.items():
        text = text.replace(original, replacement)
    return text


def declaration(encoding: str) -> str:
    label = {
        "UTF-8-BOM": "UTF-8",
        "UTF-16LE": "UTF-16LE",
        "UTF-16BE": "UTF-16BE",
        "UTF-16LE-BOM": "UTF-16LE",
        "UTF-16BE-BOM": "UTF-16BE",
        "ISO-8859-1": "ISO-8859-1",
    }[encoding]
    return f'<?xml version="1.0" encoding="{label}"?>\n'


def codec(encoding: str) -> tuple[str, int]:
    return {
        "UTF-8-BOM": ("utf-8", 3),
        "UTF-16LE": ("utf-16-le", 0),
        "UTF-16BE": ("utf-16-be", 0),
        "UTF-16LE-BOM": ("utf-16-le", 2),
        "UTF-16BE-BOM": ("utf-16-be", 2),
        "ISO-8859-1": ("iso-8859-1", 0),
    }[encoding]


def native_snapshot(module, executable: Path, case: dict) -> dict:
    with tempfile.TemporaryDirectory(prefix="mojito-product-encoding-") as directory:
        status, linked, diagnostic = module.compile_android_overlay(
            executable, case, Path(directory)
        )
    assert status == 0 and linked is not None, (case["id"], diagnostic)
    return linked


def overlays(manifest: dict, module, executable: Path) -> None:
    base = next(
        value
        for value in manifest["androidOverlays"]
        if value["id"]
        == "android-overlay-selected-tablet-product-preserves-disabled-upper-fallback"
    )
    for name, products, lower_encoding, upper_encoding, expected_product in MATRIX:
        case = copy.deepcopy(base)
        case["id"] = f"android-overlay-{PREFIX}{name}"
        case["androidSelectedProducts"] = products
        case["inputs"] = []
        for original, encoding in zip(
            base["inputs"], (lower_encoding, upper_encoding), strict=True
        ):
            source = rewrite((MANIFEST.parent / original["input"]).read_text())
            stem = f"product-encoding-{name}-{original['sourceSet']}.xml"
            (FOLDER / stem).write_text(declaration(encoding) + source, encoding="utf-8")
            case["inputs"].append(
                {
                    "sourceSet": original["sourceSet"],
                    "resourcePath": original["resourcePath"],
                    "input": f"fixtures/android/overlays/{stem}",
                    "encoding": encoding,
                }
            )
        expected_file = FOLDER / f"product-feature-{expected_product}.expected.json"
        expected = json.loads(rewrite(expected_file.read_text(encoding="utf-8")))
        expected_name = f"product-encoding-{name}.expected.json"
        linked_name = f"product-encoding-{name}.linked.json"
        write_json(FOLDER / expected_name, expected)
        case["expected"] = f"fixtures/android/overlays/{expected_name}"
        case["androidLinked"] = f"fixtures/android/overlays/{linked_name}"
        write_json(FOLDER / linked_name, native_snapshot(module, executable, case))
        manifest["androidOverlays"].append(case)


def source_skeletons(manifest: dict, module, executable: Path) -> None:
    for product, encodings in (
        ("tablet", ("UTF-16LE", "ISO-8859-1", "UTF-16BE-BOM")),
        ("default", ("UTF-16BE", "UTF-16LE-BOM", "ISO-8859-1")),
    ):
        original = next(
            value
            for value in manifest["androidOverlaySourceSkeletons"]
            if value["id"]
            == f"android-overlay-source-skeleton-rebinds-selected-{product}-products-to-winning-source-declarations"
        )
        case = copy.deepcopy(original)
        case["id"] = (
            f"android-overlay-source-{PREFIX}{product}-mixed-native-byte-ownership"
        )
        skeleton = json.loads((MANIFEST.parent / original["expected"]).read_text())
        for entry, nested, encoding in zip(
            case["inputs"], skeleton["sources"], encodings, strict=True
        ):
            raw_source = (MANIFEST.parent / entry["input"]).read_text(encoding="utf-8")
            raw_localized = (MANIFEST.parent / entry["localized"]).read_text(
                encoding="utf-8"
            )
            prefix = declaration(encoding)
            original_prefix, original_body = raw_source.split("\n", 1)
            localized_prefix, localized_body = raw_localized.split("\n", 1)
            assert original_prefix.startswith("<?xml")
            assert localized_prefix.startswith("<?xml")
            original_prefix += "\n"
            source = prefix + original_body
            localized = prefix + localized_body
            stem = f"product-encoding-source-{product}-{entry['sourceSet']}"
            (FOLDER / f"{stem}.xml").write_text(source, encoding="utf-8")
            (FOLDER / f"{stem}.localized.xml").write_text(localized, encoding="utf-8")
            entry.update(
                {
                    "input": f"fixtures/android/overlays/{stem}.xml",
                    "localized": f"fixtures/android/overlays/{stem}.localized.xml",
                    "encoding": encoding,
                }
            )
            sidecar = nested["skeleton"]
            sidecar["source"] = source
            sidecar["encoding"] = encoding
            encoding_name, bom = codec(encoding)
            original_bytes = raw_source.encode("utf-8")
            for slot in sidecar["slots"]:
                start = len(original_bytes[: slot["start"]].decode("utf-8"))
                end = len(original_bytes[: slot["end"]].decode("utf-8"))
                start = len(prefix) + start - len(original_prefix)
                end = len(prefix) + end - len(original_prefix)
                slot["start"] = bom + len(source[:start].encode(encoding_name))
                slot["end"] = bom + len(source[:end].encode(encoding_name))

        skeleton_name = f"product-encoding-source-{product}.expected.skeleton.json"
        write_json(FOLDER / skeleton_name, skeleton)
        case["expected"] = f"fixtures/android/overlays/{skeleton_name}"
        for localized, field in (
            (False, "androidLinked"),
            (True, "androidLocalizedLinked"),
        ):
            snapshot_case = copy.deepcopy(case)
            snapshot_case["id"] += "-localized" if localized else "-original"
            if localized:
                for entry in snapshot_case["inputs"]:
                    entry["input"] = entry["localized"]
            suffix = "localized.linked" if localized else "linked"
            name = f"product-encoding-source-{product}.{suffix}.json"
            case[field] = f"fixtures/android/overlays/{name}"
            write_json(
                FOLDER / name, native_snapshot(module, executable, snapshot_case)
            )
        manifest["androidOverlaySourceSkeletons"].append(case)


def main() -> None:
    android, _, executable = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for field in ("androidOverlays", "androidOverlaySourceSkeletons"):
        manifest[field] = [case for case in manifest[field] if PREFIX not in case["id"]]
    overlays(manifest, android, executable)
    source_skeletons(manifest, android, executable)
    write_json(MANIFEST, manifest)
    print(
        "Generated seven native selected-product overlays and two mixed-encoding "
        "multi-file source templates with exact original/localized AAPT2 links."
    )


if __name__ == "__main__":
    main()
