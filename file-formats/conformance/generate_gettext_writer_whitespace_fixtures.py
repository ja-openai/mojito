#!/usr/bin/env python3
"""Pin GNU-native metadata writing to Java's exact whitespace safety boundary."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GETTEXT = ROOT / "fixtures" / "gettext"
MANIFEST = ROOT / "manifest.json"
PREFIX = "gettext-native-writer-whitespace-"
SOURCE_PREFIX = "gettext-source-skeleton-preserves-domain-whitespace-"
NATIVE = (0x0085, 0x00A0, 0x2007, 0x202F)
CONTROLS = (0x001C, 0x001D, 0x001E, 0x001F)
HEADER = (
    'msgid ""\n'
    'msgstr ""\n'
    '"Content-Type: text/plain; charset=UTF-8\\n"\n'
    '"Language: en_US\\n"\n\n'
)
ENTRY = 'msgid "Quiet bay"\nmsgstr "Port calme"\n'


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def descriptor(**metadata: object) -> dict[str, object]:
    return {
        "defaultMessage": "Port calme",
        "metadata": {"sourceMessage": "Quiet bay", **metadata},
    }


def record(
    manifest: dict,
    name: str,
    source: str,
    message: dict[str, object] | None,
    *,
    domain: str | None = None,
    writer_error: str | None = None,
    parse_error: str | None = None,
    mutations: list[dict[str, object]] | None = None,
    locale: str = "en-US",
) -> None:
    stem = f"writer-whitespace-{name}"
    input_path = GETTEXT / f"{stem}.po"
    input_path.write_text(source, encoding="utf-8")
    case: dict[str, object] = {
        "id": PREFIX + name,
        "format": "gettext_po",
        "input": f"fixtures/gettext/{stem}.po",
    }
    if parse_error:
        case.update({"error": parse_error, "gettextOracle": "accept"})
    else:
        assert message is not None
        expected = {
            "schemaVersion": 1,
            "sourceFormat": "gettext_po",
            "locale": locale,
            "messages": {"Quiet bay": message},
        }
        write_json(GETTEXT / f"{stem}.expected.json", expected)
        write_json(
            GETTEXT / f"{stem}.compiled.json",
            {"entries": {"Quiet bay": "Port calme"}},
        )
        case.update(
            {
                "expected": f"fixtures/gettext/{stem}.expected.json",
                "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
            }
        )
        if writer_error:
            case["writerReject"] = {"format": "gettext_po", "error": writer_error}
        else:
            (GETTEXT / f"{stem}.normalized.po").write_text(source, encoding="utf-8")
            case["gettextNormalized"] = f"fixtures/gettext/{stem}.normalized.po"
        if domain is not None:
            case["gettextNativeDomains"] = {
                "source": [domain],
                "normalized": [domain],
            }
        if mutations:
            case["writerMutations"] = mutations
    manifest["cases"].append(case)


def source_template(
    manifest: dict,
    name: str,
    domain: str,
    encoding: str,
    *,
    line_endings: str | None = None,
) -> None:
    charset = "ISO-8859-1" if encoding == "ISO-8859-1" else "UTF-8"
    header = HEADER.replace("charset=UTF-8", f"charset={charset}")
    source = (
        f'domain "{domain}"\n'
        + header
        + f"#: north{domain[-1]}dock\n"
        + f"#, {domain[-1]}\n"
        + ENTRY
    )
    if line_endings == "CRLF":
        source = source.replace("\n", "\r\n")
    localized = source.replace('msgstr "Port calme"', 'msgstr "Marée sûre"')
    stem = f"writer-whitespace-source-{name}"
    (GETTEXT / f"{stem}.po").write_text(source, encoding="utf-8", newline="")
    (GETTEXT / f"{stem}.localized.po").write_text(
        localized, encoding="utf-8", newline=""
    )
    write_json(GETTEXT / f"{stem}.translations.json", {"Quiet bay": "Marée sûre"})
    marker = 'msgstr "Port calme"'
    start = source.index(marker) + len("msgstr ")
    write_json(
        GETTEXT / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "gettext_po",
            "encoding": encoding,
            "source": source,
            "slots": [
                {
                    "id": "Quiet bay",
                    "start": len(source[:start].encode(encoding)),
                    "end": len(source[: start + len('"Port calme"')].encode(encoding)),
                }
            ],
        },
    )
    write_json(
        GETTEXT / f"{stem}.compiled.json", {"entries": {"Quiet bay": "Port calme"}}
    )
    write_json(
        GETTEXT / f"{stem}.localized.compiled.json",
        {"entries": {"Quiet bay": "Marée sûre"}},
    )
    case: dict[str, object] = {
        "id": SOURCE_PREFIX + name,
        "format": "gettext_po",
        "input": f"fixtures/gettext/{stem}.po",
        "expected": f"fixtures/gettext/{stem}.expected.skeleton.json",
        "translations": f"fixtures/gettext/{stem}.translations.json",
        "localized": f"fixtures/gettext/{stem}.localized.po",
        "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
        "gettextLocalizedCompiled": f"fixtures/gettext/{stem}.localized.compiled.json",
        "gettextSourceDomain": domain,
    }
    if encoding != "UTF-8":
        case["encoding"] = encoding
    if line_endings:
        case["lineEndings"] = line_endings
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
    for index, scalar in enumerate(NATIVE):
        character = chr(scalar)
        control = chr(CONTROLS[index])
        for shape, text in (
            ("leading", character + "north"),
            ("trailing", "north" + character),
            ("both", character + "north" + character),
            ("interior", "north" + character + "dock"),
            ("only", character),
        ):
            record(
                manifest,
                f"reference-u{scalar:04x}-{shape}",
                HEADER + f"#: {text}\n" + ENTRY,
                descriptor(references=[text]),
            )
            record(
                manifest,
                f"domain-u{scalar:04x}-{shape}",
                f'domain "{text}"\n' + HEADER + ENTRY,
                descriptor(
                    gettextDomain=text,
                    gettextDomainHeader={"locale": "en-US"},
                ),
                domain=text,
            )
        record(
            manifest,
            f"flag-u{scalar:04x}-only",
            HEADER + f"#, {character}\n" + ENTRY,
            descriptor(flags=[character]),
            mutations=[
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "references": [f"north{control}dock"],
                    },
                    "error": "INVALID_GETTEXT_REFERENCE",
                },
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "flags": [control],
                    },
                    "error": "INVALID_GETTEXT_FLAG",
                },
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "gettextDomain": f"north{control}dock",
                    },
                    "error": "INVALID_GETTEXT_DOMAIN",
                },
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "gettextDomainHeader": {"locale": control},
                    },
                    "error": "INVALID_GETTEXT_DOMAIN_HEADER",
                },
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "gettextDomainHeader": {
                            "pluralForms": {
                                "nplurals": 2,
                                "expression": control,
                            }
                        },
                    },
                    "error": "INVALID_GETTEXT_DOMAIN_HEADER",
                },
                {
                    "message": "Quiet bay",
                    "metadata": {
                        "sourceMessage": "Quiet bay",
                        "gettextOriginalId": control,
                    },
                    "error": "INVALID_GETTEXT_DOMAIN_ID",
                },
            ],
        )
        record(
            manifest,
            f"domain-locale-u{scalar:04x}-only",
            'domain "north"\n'
            + HEADER.replace("Language: en_US", "Language: " + character)
            + ENTRY,
            descriptor(
                gettextDomain="north", gettextDomainHeader={"locale": character}
            ),
            domain="north",
            locale=character,
        )

    for scalar in CONTROLS:
        character = chr(scalar)
        record(
            manifest,
            f"reference-u{scalar:04x}-interior",
            HEADER + f"#: north{character}dock\n" + ENTRY,
            descriptor(references=[f"north{character}dock"]),
            writer_error="INVALID_GETTEXT_REFERENCE",
        )
        for shape, text in (
            ("leading", character + "north"),
            ("trailing", "north" + character),
            ("both", character + "north" + character),
            ("interior", "north" + character + "dock"),
            ("only", character),
        ):
            record(
                manifest,
                f"domain-u{scalar:04x}-{shape}",
                f'domain "{text}"\n' + HEADER + ENTRY,
                None,
                parse_error="INVALID_GETTEXT_DOMAIN",
            )

    for name, domain, encoding, endings in (
        ("utf8-nel", "north\u0085", "UTF-8", None),
        ("utf8-narrow", "north\u202f", "UTF-8", None),
        ("latin1-nbsp", "north\u00a0", "ISO-8859-1", None),
        ("crlf-figure", "north\u2007", "UTF-8", "CRLF"),
    ):
        source_template(manifest, name, domain, encoding, line_endings=endings)

    write_json(MANIFEST, manifest)
    print(
        "Generated 48 native-compatible normalized PO resources, four safely "
        "rejected writer references, 20 rejected unsafe domains, and four "
        "source-preserving Unicode-domain templates."
    )


if __name__ == "__main__":
    main()
