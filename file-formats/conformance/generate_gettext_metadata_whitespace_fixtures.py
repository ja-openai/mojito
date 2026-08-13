#!/usr/bin/env python3
"""Pin GNU-valid gettext metadata to Java's exact Unicode whitespace grammar."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GETTEXT = ROOT / "fixtures" / "gettext"
MANIFEST = ROOT / "manifest.json"
PREFIX = "gettext-jdk-metadata-whitespace-"
SOURCE_PREFIX = "gettext-source-skeleton-preserves-metadata-whitespace-"
BOUNDARIES = (0x001C, 0x001D, 0x001E, 0x001F, 0x0085, 0x00A0, 0x2007, 0x202F)


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def java_whitespace(character: str) -> bool:
    scalar = ord(character)
    return 0x001C <= scalar <= 0x001F or (
        character.isspace() and scalar not in {0x0085, 0x00A0, 0x2007, 0x202F}
    )


def java_strip(source: str) -> str:
    left = 0
    right = len(source)
    while left < right and java_whitespace(source[left]):
        left += 1
    while right > left and java_whitespace(source[right - 1]):
        right -= 1
    return source[left:right]


def header(
    encoding: str = "UTF-8", locale: str | None = "en_US", extra: str | None = None
) -> str:
    lines = [
        'msgid ""',
        'msgstr ""',
        f'"Content-Type: text/plain; charset={encoding}\\n"',
    ]
    if locale is not None:
        lines.append(f'"Language: {locale}\\n"')
    if extra is not None:
        lines.append(f'"X-Neutral: {extra}\\n"')
    return "\n".join(lines) + "\n\n"


def message(identity: str, comments: list[str], translated: str = "Port calme") -> str:
    return (
        "\n".join((*comments, f'msgid "{identity}"', f'msgstr "{translated}"')) + "\n\n"
    )


def catalog(messages: dict, locale: str | None) -> dict:
    result = {"schemaVersion": 1, "sourceFormat": "gettext_po", "messages": messages}
    if locale is not None:
        result["locale"] = locale
    return result


def record(
    manifest: dict,
    name: str,
    source: str,
    expected: dict,
    compiled: dict[str, str],
) -> None:
    stem = f"metadata-whitespace-{name}"
    (GETTEXT / f"{stem}.po").write_text(source, encoding="utf-8")
    write_json(GETTEXT / f"{stem}.expected.json", expected)
    write_json(GETTEXT / f"{stem}.compiled.json", {"entries": compiled})
    manifest["cases"].append(
        {
            "id": PREFIX + name,
            "format": "gettext_po",
            "input": f"fixtures/gettext/{stem}.po",
            "expected": f"fixtures/gettext/{stem}.expected.json",
            "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
        }
    )


def native_message(identity: str, **metadata) -> dict:
    value = {
        "defaultMessage": "Port calme",
        "metadata": {"sourceMessage": identity},
    }
    value["metadata"].update(metadata)
    return value


def comment_cases(manifest: dict, scalar: int) -> None:
    character = chr(scalar)
    source = header()
    expected = {}
    compiled = {}

    identity = f"extract.u{scalar:04x}"
    text = character + "neutral" + character
    source += message(identity, ["#." + text])
    value = native_message(identity)
    if java_strip(text):
        value["description"] = java_strip(text)
    expected[identity] = value
    compiled[identity] = "Port calme"

    identity = f"extract-only.u{scalar:04x}"
    source += message(identity, ["#." + character])
    value = native_message(identity)
    if not java_whitespace(character):
        value["description"] = character
    expected[identity] = value
    compiled[identity] = "Port calme"

    identity = f"translator.u{scalar:04x}"
    text = character + "neutral" + character
    source += message(identity, ["# " + text])
    expected[identity] = native_message(identity, translatorComments=[java_strip(text)])
    compiled[identity] = "Port calme"

    identity = f"translator-only.u{scalar:04x}"
    source += message(identity, ["# " + character])
    expected[identity] = (
        native_message(identity)
        if java_whitespace(character)
        else native_message(identity, translatorComments=[character])
    )
    compiled[identity] = "Port calme"

    identity = f"reference.u{scalar:04x}"
    text = "first" + character + "last"
    source += message(identity, ["#:" + text])
    expected[identity] = native_message(identity, references=[text])
    compiled[identity] = "Port calme"

    identity = f"reference-edge.u{scalar:04x}"
    text = character + "neutral" + character
    source += message(identity, ["#:" + text])
    expected[identity] = native_message(identity, references=[java_strip(text)])
    compiled[identity] = "Port calme"

    identity = f"flags.u{scalar:04x}"
    text = character + "no-c-format" + character
    source += message(identity, ["#, " + text])
    expected[identity] = native_message(identity, flags=[java_strip(text)])
    compiled[identity] = "Port calme"

    record(
        manifest,
        f"comments-u{scalar:04x}",
        source,
        catalog(expected, "en-US"),
        compiled,
    )


def field_cases(manifest: dict, scalar: int) -> None:
    character = chr(scalar)
    identity = "Quiet bay"
    locale = java_strip(character + "en_US" + character).replace("_", "-")
    record(
        manifest,
        f"language-u{scalar:04x}",
        header(locale=character + "en_US" + character) + message(identity, []),
        catalog({identity: native_message(identity)}, locale),
        {identity: "Port calme"},
    )

    value = java_strip(character + "harbor" + character)
    record(
        manifest,
        f"header-u{scalar:04x}",
        header(locale=None, extra=character + "harbor" + character)
        + message(identity, []),
        catalog(
            {
                identity: native_message(
                    identity,
                    gettextDomainHeader={
                        "fields": [{"name": "X-Neutral", "value": value}]
                    },
                )
            },
            None,
        ),
        {identity: "Port calme"},
    )


def source_template(
    manifest: dict,
    name: str,
    encoding: str,
    comments: list[str],
) -> None:
    identity = "Quiet bay"
    original_value = "Port calme"
    translated = "Marée sûre"
    source = header(encoding=encoding) + message(identity, comments, original_value)
    localized = source.replace(f'msgstr "{original_value}"', f'msgstr "{translated}"')
    stem = f"metadata-whitespace-source-{name}"
    (GETTEXT / f"{stem}.po").write_text(source, encoding="utf-8")
    (GETTEXT / f"{stem}.localized.po").write_text(localized, encoding="utf-8")
    write_json(GETTEXT / f"{stem}.translations.json", {identity: translated})
    position = source.index(f'msgstr "{original_value}"') + len("msgstr ")
    write_json(
        GETTEXT / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "gettext_po",
            "encoding": encoding,
            "source": source,
            "slots": [
                {
                    "id": identity,
                    "start": len(source[:position].encode(encoding)),
                    "end": len(
                        source[: position + len(original_value) + 2].encode(encoding)
                    ),
                }
            ],
        },
    )
    write_json(
        GETTEXT / f"{stem}.compiled.json", {"entries": {identity: original_value}}
    )
    write_json(
        GETTEXT / f"{stem}.localized.compiled.json", {"entries": {identity: translated}}
    )
    case = {
        "id": SOURCE_PREFIX + name,
        "format": "gettext_po",
        "input": f"fixtures/gettext/{stem}.po",
        "expected": f"fixtures/gettext/{stem}.expected.skeleton.json",
        "translations": f"fixtures/gettext/{stem}.translations.json",
        "localized": f"fixtures/gettext/{stem}.localized.po",
        "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
        "gettextLocalizedCompiled": f"fixtures/gettext/{stem}.localized.compiled.json",
    }
    if encoding != "UTF-8":
        case["encoding"] = encoding
    manifest["sourceSkeletons"].append(case)


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(PREFIX)
    ]
    manifest["sourceSkeletons"] = [
        case
        for case in manifest["sourceSkeletons"]
        if not case["id"].startswith(SOURCE_PREFIX)
    ]
    for scalar in BOUNDARIES:
        comment_cases(manifest, scalar)
        field_cases(manifest, scalar)

    source_template(
        manifest,
        "utf8-control-notes-references-flags",
        "UTF-8",
        [
            "#.\u001c neutral\u001f",
            "# \u001d translator\u001e",
            "#:first\u001clast",
            "#, \u001fno-c-format\u001c",
        ],
    )
    source_template(
        manifest,
        "utf8-no-break-notes-references-flags",
        "UTF-8",
        ["#.\u202f", "# \u2007", "#:first\u00a0last", "#, \u0085no-c-format\u0085"],
    )
    source_template(
        manifest,
        "latin1-no-break-notes-references-flags",
        "ISO-8859-1",
        ["#.\u00a0", "# \u0085", "#:first\u00a0last", "#, \u0085no-c-format\u0085"],
    )

    write_json(MANIFEST, manifest)
    print(
        f"Generated {len(BOUNDARIES) * 3} GNU-valid gettext metadata fixtures, "
        "72 cross-language message contracts, and three exact source templates."
    )


if __name__ == "__main__":
    main()
