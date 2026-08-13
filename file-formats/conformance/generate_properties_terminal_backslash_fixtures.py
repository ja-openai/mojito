#!/usr/bin/env python3
"""Pin JDK terminal-backslash ownership and Java-compatible message identities."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PROPERTIES = ROOT / "fixtures" / "properties"
MANIFEST = ROOT / "manifest.json"
PREFIX = "properties-jdk-terminal-backslash-"
SKELETON_PREFIX = "properties-source-skeleton-preserves-terminal-backslash-"
BLANK_PREFIX = "properties-rejects-jdk-empty-identity-"
DIFFERENTIAL_ID = PREFIX + "empty-key-declaration"
SHADOW_ID = "shadow-properties-terminal-backslash-key-ownership"
CONTINUED_DIFFERENTIAL_ID = PREFIX + "continued-key-whitespace-tail"
CONTINUED_SHADOW_ID = "shadow-properties-continued-key-whitespace-ownership"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def escape(value: str, *, key: bool) -> str:
    output = []
    leading = True
    for character in value:
        if character == " ":
            output.append("\\ " if key or leading else " ")
        elif character == "\t":
            output.append("\\t")
        elif character == "\n":
            output.append("\\n")
        elif character == "\r":
            output.append("\\r")
        elif character == "\f":
            output.append("\\f")
        elif character in "\\#!=:":
            output.append("\\" + character)
        elif ord(character) < 0x20 or ord(character) == 0x7F:
            output.append(f"\\u{ord(character):04X}")
        else:
            output.append(character)
        leading = False
    return "".join(output)


def catalog(values: dict[str, str]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "sourceFormat": "java_properties",
        "messages": {key: {"defaultMessage": value} for key, value in values.items()},
    }


def offsets(source: str, position: int, encoding: str) -> int:
    return len(source[:position].encode(encoding))


def main() -> None:
    cases = {
        "empty-key-declaration": (
            "anchor=steady\nharbor.route\\",
            {"anchor": "steady", "harbor.route": ""},
        ),
        "escaped-key-declaration": (
            "anchor=steady\nharbor.route\\\\\\",
            {"anchor": "steady", "harbor.route\\": ""},
        ),
        "empty-value-declaration": (
            "anchor=steady\nharbor.value=\\",
            {"anchor": "steady", "harbor.value": ""},
        ),
        "value-odd-backslash": (
            "anchor=steady\nharbor.value=west\\",
            {"anchor": "steady", "harbor.value": "west"},
        ),
        "value-three-backslashes": (
            "anchor=steady\nharbor.value=west\\\\\\",
            {"anchor": "steady", "harbor.value": "west\\"},
        ),
        "continued-key-declaration": (
            "anchor=steady\nhar\\\n  bor\\",
            {"anchor": "steady", "harbor": ""},
        ),
        "continued-key-whitespace-tail": (
            "anchor=steady\nharbor\\\n   ",
            {"anchor": "steady", "harbor": ""},
        ),
        "continued-key-multiple-whitespace-tails": (
            "anchor=steady\nhar\\\n  bor\\\n\t\f",
            {"anchor": "steady", "harbor": ""},
        ),
        "continued-key-escaped-separator-tail": (
            "anchor=steady\nhar\\=bor\\\n\t  ",
            {"anchor": "steady", "har=bor": ""},
        ),
        "continued-key-implicit-separator-tail": (
            "anchor=steady\nharbor \\\n   ",
            {"anchor": "steady", "harbor": ""},
        ),
        "continued-value-explicit-separator-tail": (
            "anchor=steady\nharbor = \\\n\t  ",
            {"anchor": "steady", "harbor": ""},
        ),
        "continued-key-comment-looking-tail": (
            "anchor=steady\nharbor\\\n   #dock\\\n \t",
            {"anchor": "steady", "harbor#dock": ""},
        ),
        "native-nonbreaking-identity": (
            "\\u00A0=no-break\n"
            "\\u2007=figure\n"
            "\\u202F=narrow\n"
            "\\u0085=next-line",
            {
                "\u00a0": "no-break",
                "\u2007": "figure",
                "\u202f": "narrow",
                "\u0085": "next-line",
            },
        ),
    }
    rejected = {
        "lone-eof-backslash": "\\",
        "lone-lf-continuation": "\\\n",
        "lone-cr-continuation": "\\\r",
        "lone-crlf-continuation": "\\\r\n",
        "whitespace-lf-continuation": " \t\\\n",
        "explicit-equals": "=hidden",
        "explicit-colon": ":hidden",
        "escaped-ascii-space": "\\u0020=hidden",
        "escaped-em-space": "\\u2003=hidden",
        "escaped-unit-separator": "\\u001F=hidden",
    }
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case
        for case in manifest["cases"]
        if not case["id"].startswith(PREFIX) and not case["id"].startswith(BLANK_PREFIX)
    ]
    for name, (source, values) in cases.items():
        stem = f"terminal-backslash-{name}"
        (PROPERTIES / f"{stem}.properties").write_text(source, encoding="utf-8")
        write_json(PROPERTIES / f"{stem}.expected.json", catalog(values))
        write_json(PROPERTIES / f"{stem}.compiled.json", values)
        normalized = "".join(
            f"{escape(key, key=True)}={escape(value, key=False)}\n"
            for key, value in sorted(values.items())
        )
        (PROPERTIES / f"{stem}.normalized.properties").write_text(
            normalized, encoding="utf-8"
        )
        case = {
            "id": PREFIX + name,
            "format": "java_properties",
            "input": f"fixtures/properties/{stem}.properties",
            "expected": f"fixtures/properties/{stem}.expected.json",
            "propertiesCompiled": f"fixtures/properties/{stem}.compiled.json",
            "propertiesNormalized": f"fixtures/properties/{stem}.normalized.properties",
        }
        if case["id"] == DIFFERENTIAL_ID:
            case["okapi"] = {
                "policy": "different",
                "assetPath": "messages.properties",
                "expected": "fixtures/okapi/properties-terminal-backslash.json",
                "reason": (
                    "The JDK consumes a final unpaired backslash and retains the "
                    "resulting empty-valued property; actual Okapi mishandles its "
                    "terminal source identity."
                ),
            }
        elif case["id"] == CONTINUED_DIFFERENTIAL_ID:
            case["okapi"] = {
                "policy": "different",
                "assetPath": "messages.properties",
                "expected": "fixtures/okapi/properties-continued-key-whitespace.json",
                "reason": (
                    "JDK continuation removes physical-line indentation, leaving a "
                    "real empty-valued key; legacy Okapi does not retain the native "
                    "continued-key identity."
                ),
            }
        manifest["cases"].append(case)

    for name, source in rejected.items():
        stem = f"invalid-empty-identity-{name}"
        (PROPERTIES / f"{stem}.properties").write_text(source, encoding="utf-8")
        manifest["cases"].append(
            {
                "id": BLANK_PREFIX + name,
                "format": "java_properties",
                "input": f"fixtures/properties/{stem}.properties",
                "error": "INVALID_MESSAGE_ID",
                "propertiesOracle": "accept",
            }
        )

    skeletons = {
        "empty-key": {
            "case": "empty-key-declaration",
            "translations": {"anchor": "Ancre stable", "harbor.route": "Quai sûr"},
            "localized": "anchor=Ancre stable\nharbor.route=Quai sûr",
        },
        "escaped-key": {
            "case": "escaped-key-declaration",
            "translations": {"anchor": "Ancre stable", "harbor.route\\": "Route sûre"},
            "localized": "anchor=Ancre stable\nharbor.route\\\\=Route sûre",
        },
        "empty-value": {
            "case": "empty-value-declaration",
            "translations": {"anchor": "Ancre stable", "harbor.value": "Ouest sûr"},
            "localized": "anchor=Ancre stable\nharbor.value=Ouest sûr",
        },
        "continued-key": {
            "case": "continued-key-declaration",
            "translations": {"anchor": "Ancre stable", "harbor": "Port clair"},
            "localized": "anchor=Ancre stable\nhar\\\n  bor=Port clair",
        },
        "continued-whitespace-tail": {
            "case": "continued-key-whitespace-tail",
            "translations": {"anchor": "Ancre stable", "harbor": "Quai sûr"},
            "localized": "anchor=Ancre stable\nharbor\\\n   =Quai sûr",
        },
        "continued-multiple-whitespace-tails": {
            "case": "continued-key-multiple-whitespace-tails",
            "translations": {"anchor": "Ancre stable", "harbor": "Quai sûr"},
            "localized": "anchor=Ancre stable\nhar\\\n  bor\\\n\t\f=Quai sûr",
        },
        "continued-escaped-separator-tail": {
            "case": "continued-key-escaped-separator-tail",
            "translations": {"anchor": "Ancre stable", "har=bor": "Quai sûr"},
            "localized": "anchor=Ancre stable\nhar\\=bor\\\n\t  =Quai sûr",
        },
        "continued-implicit-separator-tail": {
            "case": "continued-key-implicit-separator-tail",
            "translations": {"anchor": "Ancre stable", "harbor": "Quai sûr"},
            "localized": "anchor=Ancre stable\nharbor \\\n   Quai sûr",
        },
        "continued-explicit-separator-tail": {
            "case": "continued-value-explicit-separator-tail",
            "translations": {"anchor": "Ancre stable", "harbor": "Quai sûr"},
            "localized": "anchor=Ancre stable\nharbor = \\\n\t  Quai sûr",
        },
        "continued-comment-looking-tail": {
            "case": "continued-key-comment-looking-tail",
            "translations": {"anchor": "Ancre stable", "harbor#dock": "Quai sûr"},
            "localized": "anchor=Ancre stable\nharbor\\\n   #dock\\\n \t=Quai sûr",
        },
        "latin1-key": {
            "source": "préfixe=café\nrivière\\",
            "values": {"préfixe": "café", "rivière": ""},
            "translations": {"préfixe": "Entrée claire", "rivière": "Quai € 🧭"},
            "localized": "préfixe=Entrée claire\nrivière=Quai \\u20AC \\uD83E\\uDDED",
            "encoding": "ISO-8859-1",
        },
    }
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SKELETON_PREFIX)
    ]
    for name, fixture in skeletons.items():
        stem = f"terminal-backslash-source-{name}"
        if "case" in fixture:
            source, values = cases[fixture["case"]]
        else:
            source, values = fixture["source"], fixture["values"]
        localized = fixture["localized"]
        translations = fixture["translations"]
        encoding = fixture.get("encoding", "UTF-8")
        (PROPERTIES / f"{stem}.properties").write_text(source, encoding="utf-8")
        (PROPERTIES / f"{stem}.localized.properties").write_text(
            localized, encoding="utf-8"
        )
        write_json(PROPERTIES / f"{stem}.translations.json", translations)
        write_json(PROPERTIES / f"{stem}.compiled.json", values)
        localized_values = dict(values)
        localized_values.update(translations)
        write_json(PROPERTIES / f"{stem}.localized.compiled.json", localized_values)

        slots = []
        for identifier in values:
            if identifier == "anchor" or identifier == "préfixe":
                start = source.index("=") + 1
                end = source.index("\n")
            else:
                start = len(source) - 1 if source.endswith("\\") else len(source)
                end = len(source)
            slots.append(
                {
                    "id": identifier,
                    "start": offsets(source, start, encoding),
                    "end": offsets(source, end, encoding),
                }
            )
        expected = {
            "schemaVersion": 1,
            "sourceFormat": "java_properties",
            "encoding": encoding,
            "source": source,
            "slots": slots,
        }
        write_json(PROPERTIES / f"{stem}.expected.skeleton.json", expected)
        entry = {
            "id": SKELETON_PREFIX + name,
            "format": "java_properties",
            "input": f"fixtures/properties/{stem}.properties",
            "expected": f"fixtures/properties/{stem}.expected.skeleton.json",
            "translations": f"fixtures/properties/{stem}.translations.json",
            "localized": f"fixtures/properties/{stem}.localized.properties",
            "propertiesCompiled": f"fixtures/properties/{stem}.compiled.json",
            "propertiesLocalizedCompiled": (
                f"fixtures/properties/{stem}.localized.compiled.json"
            ),
        }
        if encoding != "UTF-8":
            entry["encoding"] = encoding
        manifest["sourceSkeletons"].append(entry)
        if name in {"continued-whitespace-tail", "continued-escaped-separator-tail"}:
            for line_ending, suffix in (("CR", "cr"), ("CRLF", "crlf")):
                delimiter = "\r" if line_ending == "CR" else "\r\n"
                adjusted = source.replace("\n", delimiter)
                adjusted_slots = []
                for identifier in values:
                    if identifier == "anchor":
                        start = adjusted.index("=") + 1
                        end = adjusted.index(delimiter)
                    else:
                        start = len(adjusted)
                        end = len(adjusted)
                    adjusted_slots.append(
                        {
                            "id": identifier,
                            "start": offsets(adjusted, start, encoding),
                            "end": offsets(adjusted, end, encoding),
                        }
                    )
                write_json(
                    PROPERTIES / f"{stem}.{suffix}.expected.skeleton.json",
                    {**expected, "source": adjusted, "slots": adjusted_slots},
                )
                manifest["sourceSkeletons"].append(
                    {
                        **entry,
                        "id": entry["id"] + "-" + suffix,
                        "lineEndings": line_ending,
                        "expected": (
                            f"fixtures/properties/{stem}.{suffix}.expected.skeleton.json"
                        ),
                    }
                )

    manifest["shadowComparisons"] = [
        case
        for case in manifest["shadowComparisons"]
        if case["id"] not in {SHADOW_ID, CONTINUED_SHADOW_ID}
    ]
    manifest["shadowComparisons"].extend(
        (
            {
                "id": SHADOW_ID,
                "case": DIFFERENTIAL_ID,
                "expected": "fixtures/shadow/properties-terminal-backslash.json",
            },
            {
                "id": CONTINUED_SHADOW_ID,
                "case": CONTINUED_DIFFERENTIAL_ID,
                "expected": "fixtures/shadow/properties-continued-key-whitespace.json",
            },
        )
    )
    write_json(MANIFEST, manifest)


if __name__ == "__main__":
    main()
