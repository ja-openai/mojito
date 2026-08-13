#!/usr/bin/env python3
"""Regenerate neutral Foundation-owned binary source-template fixtures and snapshots."""

from __future__ import annotations

import json
import plistlib
import shutil
import subprocess
import tempfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parent
APPLE = ROOT / "fixtures" / "apple"


def main() -> None:
    executable = shutil.which("plutil")
    if executable is None:
        raise SystemExit(
            "Apple plutil is required to generate genuine Foundation binary objects"
        )

    binary_strings = APPLE / "source-skeleton.binary.strings"
    original_strings = json.loads(
        (APPLE / "source-skeleton.compiled.json").read_text(encoding="utf-8")
    )
    localized_strings = json.loads(
        (APPLE / "source-skeleton.localized.compiled.json").read_text(encoding="utf-8")
    )
    translations = json.loads(
        (APPLE / "source-skeleton.translations.json").read_text(encoding="utf-8")
    )
    for values in (original_strings, localized_strings, translations):
        values.pop("shorthand.key")
    original_strings_path = APPLE / "source-skeleton-binary.compiled.json"
    localized_strings_path = APPLE / "source-skeleton-binary.localized.compiled.json"
    translations_path = APPLE / "source-skeleton-binary.translations.json"
    for path, payload in (
        (original_strings_path, original_strings),
        (localized_strings_path, localized_strings),
        (translations_path, translations),
    ):
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    with tempfile.TemporaryDirectory(prefix="mojito-foundation-binary-") as directory:
        neutral = Path(directory) / "neutral-original.plist"
        neutral.write_bytes(
            plistlib.dumps(original_strings, fmt=plistlib.FMT_XML, sort_keys=False)
        )
        subprocess.run(
            [
                executable,
                "-convert",
                "binary1",
                "-o",
                str(binary_strings),
                str(neutral),
            ],
            check=True,
        )
    generate(
        binary_strings,
        "apple_strings",
        original_strings_path,
        localized_strings_path,
        APPLE / "source-skeleton.expected.skeleton.json",
        APPLE / "source-skeleton-binary.expected.skeleton.json",
        APPLE / "source-skeleton-binary.localized.strings",
    )
    generate(
        APPLE / "source-skeleton.binary.stringsdict",
        "apple_stringsdict",
        APPLE / "source-skeleton-stringsdict.compiled.json",
        APPLE / "source-skeleton-stringsdict.localized.compiled.json",
        APPLE / "source-skeleton-stringsdict.expected.skeleton.json",
        APPLE / "source-skeleton-binary-stringsdict.expected.skeleton.json",
        APPLE / "source-skeleton-binary.localized.stringsdict",
    )
    original_growth = {"signal.short": "steady", "signal.anchor": "north"}
    translated_growth = {
        "signal.short": "Balise 🧭 " + "calme " * 44,
        "signal.anchor": "ouest",
    }
    growth_original = APPLE / "source-skeleton-binary-growth.compiled.json"
    growth_localized = APPLE / "source-skeleton-binary-growth.localized.compiled.json"
    growth_translations = APPLE / "source-skeleton-binary-growth.translations.json"
    for path, payload in (
        (growth_original, original_growth),
        (growth_localized, translated_growth),
        (growth_translations, translated_growth),
    ):
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    growth_binary = APPLE / "source-skeleton-binary-growth.strings"
    with tempfile.TemporaryDirectory(
        prefix="mojito-foundation-binary-growth-"
    ) as directory:
        neutral = Path(directory) / "neutral-growth.plist"
        neutral.write_bytes(
            plistlib.dumps(original_growth, fmt=plistlib.FMT_XML, sort_keys=False)
        )
        subprocess.run(
            [executable, "-convert", "binary1", "-o", str(growth_binary), str(neutral)],
            check=True,
        )
    generate(
        growth_binary,
        "apple_strings",
        growth_original,
        growth_localized,
        None,
        APPLE / "source-skeleton-binary-growth.expected.skeleton.json",
        APPLE / "source-skeleton-binary-growth.localized.strings",
    )

    shared_values = {"anchor.alpha": "shared value", "anchor.beta": "shared value"}
    shared_translated = {
        "anchor.alpha": "rouge partagé",
        "anchor.beta": "bleu distinct",
    }
    generate_shared_fixture(
        executable,
        "shared-values",
        "apple_strings",
        shared_values,
        shared_translated,
        shared_translated,
    )

    key_alias = {"harbor.alias": "harbor.alias", "harbor.safe": "steady"}
    key_translated = {"harbor.alias": "quai indépendant", "harbor.safe": "stable"}
    generate_shared_fixture(
        executable,
        "shared-key-value",
        "apple_strings",
        key_alias,
        key_translated,
        key_translated,
    )

    plural = {
        "beacon.shared": {
            "NSStringLocalizedFormatKey": "%#@count@",
            "count": {
                "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
                "NSStringFormatValueTypeKey": "ld",
                "one": "%ld shared beacon",
                "other": "%ld shared beacon",
            },
        }
    }
    plural_translated = {
        "beacon.shared": {
            "NSStringLocalizedFormatKey": "%#@count@",
            "count": {
                "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
                "NSStringFormatValueTypeKey": "ld",
                "one": "%ld balise seule",
                "other": "%ld balises ensemble",
            },
        }
    }
    plural_translations = {
        "beacon.shared#one": "{count} balise seule",
        "beacon.shared#other": "{count} balises ensemble",
    }
    generate_shared_fixture(
        executable,
        "shared-plurals",
        "apple_stringsdict",
        plural,
        plural_translated,
        plural_translations,
        {
            "beacon.shared#one": {"id": "beacon.shared", "variant": "one"},
            "beacon.shared#other": {"id": "beacon.shared", "variant": "other"},
        },
    )

    many = {f"signal.{index:03}": f"beam-{index:03}" for index in range(127)}
    many["signal.000"] = "shared beam"
    many["signal.001"] = "shared beam"
    many_translated = dict(many)
    many_translated["signal.000"] = "balise zéro"
    many_translated["signal.001"] = "balise une"
    generate_shared_fixture(
        executable,
        "shared-reference-promotion",
        "apple_strings",
        many,
        many_translated,
        {
            "signal.000": "balise zéro",
            "signal.001": "balise une",
        },
    )

    nested = json.loads(json.dumps(plural))
    nested["beacon.shared"]["futureMessageList"] = [
        f"protected-metadata-{index:03}" for index in range(238)
    ]
    nested_translated = json.loads(json.dumps(nested))
    nested_translated["beacon.shared"]["count"]["one"] = "%ld balise seule"
    nested_translated["beacon.shared"]["count"]["other"] = "%ld balises ensemble"
    generate_shared_fixture(
        executable,
        "shared-nested-reference-promotion",
        "apple_stringsdict",
        nested,
        nested_translated,
        plural_translations,
        {
            "beacon.shared#one": {"id": "beacon.shared", "variant": "one"},
            "beacon.shared#other": {"id": "beacon.shared", "variant": "other"},
        },
    )


def generate_shared_fixture(
    executable: str,
    name: str,
    source_format: str,
    original: dict,
    localized: dict,
    translations: dict[str, str],
    identities: dict[str, dict[str, str]] | None = None,
) -> None:
    suffix = ".stringsdict" if source_format == "apple_stringsdict" else ".strings"
    original_path = APPLE / f"source-skeleton-binary-{name}.compiled.json"
    localized_path = APPLE / f"source-skeleton-binary-{name}.localized.compiled.json"
    translations_path = APPLE / f"source-skeleton-binary-{name}.translations.json"
    for path, payload in (
        (original_path, original),
        (localized_path, localized),
        (translations_path, translations),
    ):
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    binary = APPLE / f"source-skeleton-binary-{name}{suffix}"
    with tempfile.TemporaryDirectory(
        prefix="mojito-foundation-binary-shared-"
    ) as directory:
        neutral = Path(directory) / "neutral-shared.plist"
        neutral.write_bytes(
            plistlib.dumps(original, fmt=plistlib.FMT_XML, sort_keys=False)
        )
        subprocess.run(
            [executable, "-convert", "binary1", "-o", str(binary), str(neutral)],
            check=True,
        )
    generate(
        binary,
        source_format,
        original_path,
        localized_path,
        None,
        APPLE / f"source-skeleton-binary-{name}.expected.skeleton.json",
        APPLE / f"source-skeleton-binary-{name}.localized{suffix}",
        identities,
    )


def generate(
    input_path: Path,
    source_format: str,
    original_snapshot: Path,
    localized_snapshot: Path,
    xml_sidecar: Path | None,
    expected_sidecar: Path,
    localized_binary: Path,
    explicit_identities: dict[str, dict[str, str]] | None = None,
) -> None:
    source = input_path.read_bytes()
    original = json.loads(original_snapshot.read_text(encoding="utf-8"))
    localized = json.loads(localized_snapshot.read_text(encoding="utf-8"))
    assert plistlib.loads(source) == original, input_path
    identities = explicit_identities or (
        {
            key(slot): slot
            for slot in json.loads(xml_sidecar.read_text(encoding="utf-8"))["slots"]
        }
        if xml_sidecar is not None
        else {identity: {"id": identity} for identity in original}
    )
    layout = Layout(source)
    locations, references, key_objects, reference_positions, all_references = (
        layout.walk()
    )
    changes: dict[tuple[str, ...], str] = {}
    differences(original, localized, (), changes)
    slots = []
    for path, index in locations.items():
        if source_format == "apple_stringsdict" and path not in changes:
            continue
        identifier = (
            path[0]
            if len(path) == 1
            else next(
                name
                for name in (
                    f"{path[0]}#{path[1]}#{path[2]}",
                    f"{path[0]}#{path[2]}",
                    path[0],
                )
                if name in identities
            )
        )
        identity = identities[identifier]
        object_start = layout.offsets[index]
        count, content = layout.length(object_start)
        object_end = content + count * (2 if source[object_start] & 0xF0 == 0x60 else 1)
        shared = references[index] != 1 or index in key_objects
        start = reference_positions[path] if shared else object_start
        end = start + layout.reference_width if shared else object_end
        slot = {
            name: identity[name]
            for name in ("id", "selector", "variant")
            if name in identity
        }
        slot.update(start=start, end=end)
        if shared:
            slot["appleObjectIndex"] = index
        slots.append(slot)
    slots.sort(key=lambda slot: slot["start"])
    expected_sidecar.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "sourceFormat": source_format,
                "encoding": "BINARY_PLIST",
                "source": source.hex(),
                "slots": slots,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    replacements: dict[int, tuple[int, bytes]] = {}
    clones: list[bytes] = []
    overrides: dict[int, int] = {}
    for slot in slots:
        path = (
            next(
                path for path in changes if path_identity(path, identities) == key(slot)
            )
            if any(path_identity(path, identities) == key(slot) for path in changes)
            else None
        )
        if path is None:
            continue
        value = changes[path]
        if "appleObjectIndex" in slot:
            original_object = layout.offsets[slot["appleObjectIndex"]]
            overrides[slot["start"]] = layout.count + len(clones)
            clones.append(encode(value, source[original_object]))
        else:
            replacements[slot["start"]] = (
                slot["end"],
                encode(value, source[slot["start"]]),
            )
    object_count = layout.count + len(clones)
    reference_width = layout.reference_width
    while object_count >= 1 << (reference_width * 8):
        reference_width += 1
    for position, object_index in all_references.items():
        if position in overrides or reference_width != layout.reference_width:
            value = overrides.get(position, object_index)
            replacements[position] = (
                position + layout.reference_width,
                value.to_bytes(reference_width, "big"),
            )
    ordered = sorted(replacements.items())
    rebuilt = bytearray()
    cursor = 0
    for start, (end, replacement) in ordered:
        assert cursor <= start < end <= layout.end
        rebuilt.extend(source[cursor:start])
        rebuilt.extend(replacement)
        cursor = end
    rebuilt.extend(source[cursor : layout.end])
    clone_offsets = []
    for clone in clones:
        clone_offsets.append(len(rebuilt))
        rebuilt.extend(clone)
    new_end = len(rebuilt)
    width = layout.offset_width
    while width < 8 and new_end >= 1 << (8 * width):
        width += 1
    for offset in layout.offsets:
        shift = sum(
            len(value) - (end - start)
            for start, (end, value) in ordered
            if end <= offset
        )
        rebuilt.extend((offset + shift).to_bytes(width, "big"))
    for offset in clone_offsets:
        rebuilt.extend(offset.to_bytes(width, "big"))
    trailer = bytearray(source[-32:])
    trailer[6] = width
    trailer[7] = reference_width
    trailer[8:16] = object_count.to_bytes(8, "big")
    trailer[24:32] = new_end.to_bytes(8, "big")
    rebuilt.extend(trailer)
    assert plistlib.loads(rebuilt) == localized, localized_binary
    localized_binary.write_bytes(rebuilt)


def differences(
    original: object, localized: object, path: tuple[str, ...], result: dict
) -> None:
    if isinstance(original, dict):
        assert isinstance(localized, dict) and original.keys() == localized.keys(), path
        for key, value in original.items():
            differences(value, localized[key], (*path, key), result)
    elif isinstance(original, list):
        assert original == localized, path
    elif original != localized:
        assert isinstance(original, str) and isinstance(localized, str), path
        result[path] = localized


def key(slot: dict[str, object]) -> str:
    return "#".join(
        str(slot[name]) for name in ("id", "selector", "variant") if name in slot
    )


def path_identity(path: tuple[str, ...], identities: dict[str, dict[str, str]]) -> str:
    if len(path) == 1:
        return path[0]
    return next(
        name
        for name in (
            f"{path[0]}#{path[1]}#{path[2]}",
            f"{path[0]}#{path[2]}",
            path[0],
        )
        if name in identities
    )


class Layout:
    def __init__(self, source: bytes):
        self.source = source
        trailer = source[-32:]
        self.offset_width = trailer[6]
        self.reference_width = trailer[7]
        self.count = int.from_bytes(trailer[8:16], "big")
        self.top = int.from_bytes(trailer[16:24], "big")
        self.end = int.from_bytes(trailer[24:32], "big")
        self.offsets = [
            int.from_bytes(
                source[
                    self.end + index * self.offset_width : self.end
                    + (index + 1) * self.offset_width
                ],
                "big",
            )
            for index in range(self.count)
        ]

    def length(self, start: int) -> tuple[int, int]:
        count = self.source[start] & 15
        if count < 15:
            return count, start + 1
        width = 1 << (self.source[start + 1] & 15)
        return int.from_bytes(
            self.source[start + 2 : start + 2 + width], "big"
        ), start + 2 + width

    def walk(
        self,
    ) -> tuple[
        dict[tuple[str, ...], int],
        Counter[int],
        set[int],
        dict[tuple[str, ...], int],
        dict[int, int],
    ]:
        locations: dict[tuple[str, ...], int] = {}
        references: Counter[int] = Counter()
        key_objects: set[int] = set()
        value_references: dict[tuple[str, ...], int] = {}
        all_references: dict[int, int] = {}

        def visit(
            index: int,
            path: tuple[str, ...],
            key_object: bool = False,
            reference: int | None = None,
        ) -> None:
            references[index] += 1
            if key_object:
                key_objects.add(index)
            offset = self.offsets[index]
            marker = self.source[offset] & 0xF0
            if marker in {0x50, 0x60}:
                if not key_object:
                    locations[path] = index
                    assert reference is not None
                    value_references[path] = reference
                return
            if marker not in {0xD0, 0xA0}:
                return
            count, start = self.length(offset)
            if marker == 0xD0:
                for item in range(count):
                    key_start = start + item * self.reference_width
                    value_start = start + (count + item) * self.reference_width
                    key_index = self.reference(key_start)
                    value_index = self.reference(value_start)
                    all_references[key_start] = key_index
                    all_references[value_start] = value_index
                    visit(key_index, path, True, key_start)
                    visit(
                        value_index, (*path, self.string(key_index)), False, value_start
                    )
            else:
                for item in range(count):
                    position = start + item * self.reference_width
                    value = self.reference(position)
                    all_references[position] = value
                    visit(value, path, False, position)

        visit(self.top, ())
        return locations, references, key_objects, value_references, all_references

    def reference(self, start: int) -> int:
        return int.from_bytes(self.source[start : start + self.reference_width], "big")

    def string(self, index: int) -> str:
        offset = self.offsets[index]
        count, start = self.length(offset)
        if self.source[offset] & 0xF0 == 0x50:
            return self.source[start : start + count].decode("iso-8859-1")
        return self.source[start : start + count * 2].decode("utf-16-be")


def encode(value: str, marker: int) -> bytes:
    latin = marker & 0xF0 == 0x50 and value.isascii()
    content = value.encode("iso-8859-1" if latin else "utf-16-be")
    count = len(content) if latin else len(content) // 2
    kind = 0x50 if latin else 0x60
    if count < 15:
        return bytes([kind | count]) + content
    width = 1 if count <= 255 else 2 if count <= 65_535 else 4
    exponent = {1: 0, 2: 1, 4: 2}[width]
    return bytes([kind | 15, 0x10 | exponent]) + count.to_bytes(width, "big") + content


if __name__ == "__main__":
    main()
