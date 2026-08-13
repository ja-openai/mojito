#!/usr/bin/env python3
"""Create original neutral fixtures for customized Mojito XML resource formats."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "manifest.json"
DIRECTORY = ROOT / "fixtures" / "xml-resources"


def write(name: str, value: str | dict[str, object]) -> str:
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if isinstance(value, dict):
        value = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path = DIRECTORY / name
    path.write_text(value, encoding="utf-8")
    return path.relative_to(ROOT).as_posix()


def skeleton(format_name: str, source: str, ids: dict[str, str]) -> dict[str, object]:
    slots = []
    for identity, value in ids.items():
        marker = f">{value}</{'translation' if format_name == 'xtb' else 'value'}>"
        start = source.index(marker) + 1
        slots.append(
            {
                "id": identity,
                "start": len(source[:start].encode("utf-8")),
                "end": len(source[: start + len(value)].encode("utf-8")),
            }
        )
    slots.sort(key=lambda slot: slot["start"])
    return {
        "schemaVersion": 1,
        "sourceFormat": format_name,
        "encoding": "UTF-8",
        "source": source,
        "slots": slots,
    }


def resx(manifest: dict[str, object]) -> None:
    source = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <resheader name="version"><value>2.0</value></resheader>
  <data name="harbor" xml:space="preserve">
    <value>Calm &amp; steady 🧭</value>
    <comment>Message shown at the north pier</comment>
  </data>
  <data name="count" xml:space="preserve">
    <value>  {0} signals  </value>
  </data>
  <data name="$this.Text"><value>Window title</value><comment>Ignored note</comment></data>
  <data name=">metadata"><value>Protected metadata</value></data>
  <data name="navigation.Name"><value>Protected property</value></data>
  <data name="icon" type="System.Drawing.Bitmap"><value>Protected bitmap</value></data>
  <data name="blob" mimetype="application/octet-stream"><value>Protected binary</value></data>
  <data name="empty"><value /></data>
</root>
"""
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "resx",
        "messages": {
            "harbor": {
                "defaultMessage": "Calm & steady 🧭",
                "description": "Message shown at the north pier",
            },
            "count": {"defaultMessage": "  {0} signals  "},
            "$this.Text": {"defaultMessage": "Window title"},
        },
    }
    source_path = write("microsoft.resx", source)
    expected_path = write("microsoft.expected.json", expected)
    normalized = """<?xml version="1.0" encoding="UTF-8"?>
<root>
  <data name="$this.Text" xml:space="preserve">
    <value>Window title</value>
  </data>
  <data name="count" xml:space="preserve">
    <value>  {0} signals  </value>
  </data>
  <data name="harbor" xml:space="preserve">
    <value>Calm &amp; steady 🧭</value>
    <comment>Message shown at the north pier</comment>
  </data>
</root>
"""
    localized = (
        source.replace("Calm &amp; steady 🧭", "Paisible &amp; stable 🧭")
        .replace("  {0} signals  ", "  {0} signaux  ")
        .replace("Window title", "Titre de fenêtre")
    )
    ids = {
        "harbor": "Calm &amp; steady 🧭",
        "count": "  {0} signals  ",
        "$this.Text": "Window title",
    }
    manifest["cases"].append(
        {
            "id": "resx-customized-mojito-rules-preserve-notes-space-and-protected-content",
            "format": "resx",
            "input": source_path,
            "expected": expected_path,
            "resxNormalized": write("microsoft.normalized.resx", normalized),
        }
    )
    manifest["workflowCases"].append(
        {
            "id": "resx-mojito-output-preserves-protected-data-source-comments-and-formatting",
            "format": "resx",
            "input": source_path,
            "expected": expected_path,
            "filterOptions": [],
            "translations": {
                "harbor": "Paisible & stable 🧭",
                "count": "  {0} signaux  ",
                "$this.Text": "Titre de fenêtre",
            },
            "removeUntranslated": False,
            "localized": write("microsoft.localized.resx", localized),
        }
    )
    manifest["sourceSkeletons"].append(
        {
            "id": "resx-source-skeleton-preserves-comments-metadata-and-exact-xml-bytes",
            "format": "resx",
            "input": source_path,
            "expected": write(
                "microsoft.expected.skeleton.json", skeleton("resx", source, ids)
            ),
            "translations": write(
                "microsoft.translations.json",
                {
                    "harbor": "Paisible & stable 🧭",
                    "count": "  {0} signaux  ",
                    "$this.Text": "Titre de fenêtre",
                },
            ),
            "localized": write("microsoft.localized.resx", localized),
        }
    )


def xtb(manifest: dict[str, object]) -> None:
    source = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE translationbundle>
<translationbundle lang="en-US">
  <!-- Protected source context -->
  <translation id="17" key="harbor" source="pier.js" desc="Visible harbor status">  Calm &amp; steady 🧭  </translation>
  <translation key="count" desc="Number of docked vessels">Welcome <ph name="COUNT" example="3"/> vessels</translation>
  <translation key="empty"></translation>
</translationbundle>
"""
    expected = {
        "schemaVersion": 1,
        "sourceFormat": "xtb",
        "locale": "en-US",
        "messages": {
            "harbor": {
                "defaultMessage": "  Calm & steady 🧭  ",
                "description": "Visible harbor status",
            },
            "count": {
                "defaultMessage": "Welcome {COUNT} vessels",
                "description": "Number of docked vessels",
                "placeholders": [
                    {
                        "name": "COUNT",
                        "source": '<ph name="COUNT"/>',
                        "kind": "value",
                        "example": "3",
                    }
                ],
            },
        },
    }
    source_path = write("google.xtb", source)
    expected_path = write("google.expected.json", expected)
    normalized = """<?xml version="1.0" encoding="UTF-8"?>
<translationbundle lang="en-US">
  <translation key="count" desc="Number of docked vessels">Welcome <ph name="COUNT" example="3"/> vessels</translation>
  <translation key="harbor" desc="Visible harbor status">  Calm &amp; steady 🧭  </translation>
</translationbundle>
"""
    localized = source.replace(
        "  Calm &amp; steady 🧭  ", "  Paisible &amp; stable 🧭  "
    ).replace(
        'Welcome <ph name="COUNT" example="3"/> vessels',
        'Bienvenue <ph name="COUNT" example="3"/> navires',
    )
    ids = {
        "harbor": "  Calm &amp; steady 🧭  ",
        "count": 'Welcome <ph name="COUNT" example="3"/> vessels',
    }
    manifest["cases"].extend(
        [
            {
                "id": "xtb-customized-rules-preserve-language-notes-whitespace-and-inline-codes",
                "format": "xtb",
                "input": source_path,
                "expected": expected_path,
                "xtbNormalized": write("google.normalized.xtb", normalized),
            },
            {
                "id": "xtb-rejects-external-doctype-before-entity-resolution",
                "format": "xtb",
                "input": write(
                    "unsafe-external.xtb",
                    '<!DOCTYPE translationbundle SYSTEM "file:///etc/passwd">\n'
                    '<translationbundle lang="en"><translation key="a">A</translation></translationbundle>\n',
                ),
                "error": "UNSAFE_XML",
            },
            {
                "id": "xtb-rejects-internal-entity-doctype",
                "format": "xtb",
                "input": write(
                    "unsafe-internal.xtb",
                    '<!DOCTYPE translationbundle [<!ENTITY hidden "secret">]>\n'
                    '<translationbundle lang="en"><translation key="a">&hidden;</translation></translationbundle>\n',
                ),
                "error": "UNSAFE_XML",
            },
        ]
    )
    manifest["workflowCases"].append(
        {
            "id": "xtb-mojito-output-preserves-original-inline-placeholders-and-source-metadata",
            "format": "xtb",
            "input": source_path,
            "expected": expected_path,
            "filterOptions": [],
            "translations": {
                "harbor": "  Paisible & stable 🧭  ",
                "count": "Bienvenue {COUNT} navires",
            },
            "removeUntranslated": False,
            "localized": write("google.localized.xtb", localized),
        }
    )
    manifest["sourceSkeletons"].append(
        {
            "id": "xtb-source-skeleton-preserves-benign-doctype-inline-code-attributes-and-bytes",
            "format": "xtb",
            "input": source_path,
            "expected": write(
                "google.expected.skeleton.json", skeleton("xtb", source, ids)
            ),
            "translations": write(
                "google.translations.json",
                {
                    "harbor": "  Paisible & stable 🧭  ",
                    "count": "Bienvenue {COUNT} navires",
                },
            ),
            "localized": write("google.localized.xtb", localized),
        }
    )


def main() -> None:
    original = MANIFEST.read_text(encoding="utf-8")
    manifest = json.loads(original)
    lengths = {
        name: len(manifest[name])
        for name in ("workflowCases", "cases", "sourceSkeletons")
    }
    formats = {case["format"] for case in manifest["cases"]}
    if "resx" not in formats:
        resx(manifest)
    if "xtb" not in formats:
        xtb(manifest)
    sections = {
        "workflowCases": "cases",
        "cases": "androidOverlays",
        "sourceSkeletons": "appleBinarySourceSkeletons",
    }
    for section, following in sections.items():
        added = manifest[section][lengths[section] :]
        if not added:
            continue
        marker = f'\n  ],\n  "{following}": ['
        rendered = "".join(
            ",\n"
            + "\n".join(
                "    " + line
                for line in json.dumps(case, ensure_ascii=False, indent=2).splitlines()
            )
            for case in added
        )
        if marker not in original:
            raise ValueError(f"Missing {section} manifest boundary")
        original = original.replace(marker, rendered + marker, 1)
    MANIFEST.write_text(original, encoding="utf-8")


if __name__ == "__main__":
    main()
