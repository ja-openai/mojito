#!/usr/bin/env python3
"""Pin GNU gettext's exact horizontal-only plural-expression token grammar."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures" / "gettext"
MANIFEST = ROOT / "manifest.json"
PREFIX = "gettext-plural-horizontal-whitespace-"
SKELETON_ID = "gettext-source-skeleton-preserves-horizontal-plural-formula"
IDENTITY = "harbor.beacon_count"
SOURCE_SINGULAR = "%d coastal beacon"
SOURCE_PLURAL = "%d coastal beacons"
TARGET_SINGULAR = "%d steady beacon"
TARGET_PLURAL = "%d steady beacons"


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def po(expression: str, *, declaration: str | None = None) -> str:
    fields = declaration or f"nplurals=2; plural={expression};"
    return (
        "# Neutral coastal beacon fixture; formula separators are intentionally original.\n"
        'msgid ""\n'
        'msgstr ""\n'
        '"Content-Type: text/plain; charset=UTF-8\\n"\n'
        '"Language: en\\n"\n'
        f'"Plural-Forms: {fields}\\n"\n'
        "\n"
        "#. Count the active beacons visible from the inlet.\n"
        "#, c-format\n"
        f'msgctxt "{IDENTITY}"\n'
        f'msgid "{SOURCE_SINGULAR}"\n'
        f'msgid_plural "{SOURCE_PLURAL}"\n'
        f'msgstr[0] "{TARGET_SINGULAR}"\n'
        f'msgstr[1] "{TARGET_PLURAL}"\n'
    )


def catalog(expression: str) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "sourceFormat": "gettext_po",
        "locale": "en",
        "messages": {
            IDENTITY: {
                "defaultMessage": (
                    "{arg0, plural, one {{arg0} steady beacon} "
                    "other {{arg0} steady beacons}}"
                ),
                "variants": {
                    "one": "{arg0} steady beacon",
                    "other": "{arg0} steady beacons",
                },
                "description": "Count the active beacons visible from the inlet.",
                "placeholders": [
                    {"name": "arg0", "source": "%d", "kind": "integer", "position": 1}
                ],
                "metadata": {
                    "sourceMessage": SOURCE_SINGULAR,
                    "sourcePlural": SOURCE_PLURAL,
                    "context": IDENTITY,
                    "flags": ["c-format"],
                    "gettextPluralIndexes": {"0": "one", "1": "other"},
                    "gettextPluralForms": {"nplurals": 2, "expression": expression},
                },
            }
        },
    }


def compiled(singular: str, plural: str) -> dict[str, object]:
    return {
        "plurals": {f"{IDENTITY}\u0004{SOURCE_SINGULAR}": {"0": singular, "1": plural}}
    }


def skeleton(source: str) -> dict[str, object]:
    slots = []
    for index, (variant, value) in enumerate(
        (("one", TARGET_SINGULAR), ("other", TARGET_PLURAL))
    ):
        prefix = f"msgstr[{index}] "
        start = source.index('"', source.index(prefix))
        slots.append(
            {
                "id": IDENTITY,
                "start": start,
                "end": start + len(value) + 2,
                "variant": variant,
            }
        )
    return {
        "schemaVersion": 1,
        "sourceFormat": "gettext_po",
        "encoding": "UTF-8",
        "source": source,
        "slots": slots,
    }


def main() -> None:
    accepted = {
        "none": "n!=1",
        "spaces": "  n  !=  1  ",
        "tabs": "\\tn\\t!=\\t1\\t",
        "mixed-parenthesized": " \\t( n\\t!=\\t1 )\\t ",
    }
    separators = {
        "vertical-tab": "\\v",
        "form-feed": "\\f",
        "carriage-return": "\\r",
        "file-separator": "\\034",
        "group-separator": "\\035",
        "record-separator": "\\036",
        "unit-separator": "\\037",
        "next-line": "\u0085",
        "no-break-space": "\u00a0",
        "ogham-space": "\u1680",
        "en-quad": "\u2000",
        "em-space": "\u2003",
        "figure-space": "\u2007",
        "zero-width-space": "\u200b",
        "line-separator": "\u2028",
        "paragraph-separator": "\u2029",
        "narrow-no-break-space": "\u202f",
        "medium-mathematical-space": "\u205f",
        "ideographic-space": "\u3000",
    }
    positions = {
        "leading": lambda value: f"{value}n!=1",
        "internal": lambda value: f"n{value}!={value}1",
        "trailing": lambda value: f"n!=1{value}",
    }
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(PREFIX)
    ]

    for name, expression in accepted.items():
        stem = f"plural-horizontal-whitespace-{name}"
        source = po(expression)
        (FIXTURES / f"{stem}.po").write_text(source, encoding="utf-8")
        decoded = expression.replace("\\t", "\t").strip(" \t")
        write_json(FIXTURES / f"{stem}.expected.json", catalog(decoded))
        write_json(
            FIXTURES / f"{stem}.compiled.json", compiled(TARGET_SINGULAR, TARGET_PLURAL)
        )
        manifest["cases"].append(
            {
                "id": PREFIX + name,
                "format": "gettext_po",
                "input": f"fixtures/gettext/{stem}.po",
                "expected": f"fixtures/gettext/{stem}.expected.json",
                "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
            }
        )

    for separator_name, separator in separators.items():
        for position_name, apply in positions.items():
            stem = f"plural-horizontal-whitespace-{separator_name}-{position_name}"
            (FIXTURES / f"{stem}.po").write_text(po(apply(separator)), encoding="utf-8")
            manifest["cases"].append(
                {
                    "id": PREFIX + separator_name + "-" + position_name,
                    "format": "gettext_po",
                    "input": f"fixtures/gettext/{stem}.po",
                    "error": "INVALID_GETTEXT_PLURAL_FORMS",
                    "gettextOracle": "reject",
                }
            )

    for name, declaration in {
        "plural-space-before-equals": "nplurals=2; plural =n!=1;",
        "plural-tab-before-equals": "nplurals=2; plural\\t=n!=1;",
        "count-space-before-equals": "nplurals =2; plural=n!=1;",
        "count-tab-before-equals": "nplurals\\t=2; plural=n!=1;",
    }.items():
        stem = f"plural-horizontal-whitespace-{name}"
        (FIXTURES / f"{stem}.po").write_text(
            po("", declaration=declaration), encoding="utf-8"
        )
        manifest["cases"].append(
            {
                "id": PREFIX + name,
                "format": "gettext_po",
                "input": f"fixtures/gettext/{stem}.po",
                "error": "INVALID_GETTEXT_PLURAL_FORMS",
                "gettextOracle": "reject",
            }
        )

    source_stem = "plural-horizontal-whitespace-mixed-parenthesized"
    source = po(accepted["mixed-parenthesized"])
    localized_stem = "plural-horizontal-whitespace-mixed-parenthesized.localized"
    localized = source.replace(TARGET_SINGULAR, "%d port beacon").replace(
        TARGET_PLURAL, "%d port beacons"
    )
    (FIXTURES / f"{localized_stem}.po").write_text(localized, encoding="utf-8")
    write_json(FIXTURES / f"{source_stem}.expected.skeleton.json", skeleton(source))
    write_json(
        FIXTURES / f"{source_stem}.translations.json",
        {
            IDENTITY + "#one": "{arg0} port beacon",
            IDENTITY + "#other": "{arg0} port beacons",
        },
    )
    write_json(
        FIXTURES / f"{localized_stem}.compiled.json",
        compiled("%d port beacon", "%d port beacons"),
    )
    manifest["sourceSkeletons"] = [
        case for case in manifest["sourceSkeletons"] if case["id"] != SKELETON_ID
    ]
    manifest["sourceSkeletons"].append(
        {
            "id": SKELETON_ID,
            "format": "gettext_po",
            "input": f"fixtures/gettext/{source_stem}.po",
            "expected": f"fixtures/gettext/{source_stem}.expected.skeleton.json",
            "translations": f"fixtures/gettext/{source_stem}.translations.json",
            "localized": f"fixtures/gettext/{localized_stem}.po",
            "gettextCompiled": f"fixtures/gettext/{source_stem}.compiled.json",
            "gettextLocalizedCompiled": f"fixtures/gettext/{localized_stem}.compiled.json",
        }
    )
    write_json(MANIFEST, manifest)
    print(
        f"Generated {len(accepted)} GNU-accepted plural formulas, "
        f"{len(separators) * len(positions) + 4} native-rejected formulas, "
        "and one byte-preserving plural source skeleton."
    )


if __name__ == "__main__":
    main()
