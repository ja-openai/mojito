#!/usr/bin/env python3
"""Pin AAPT2 ownership of XML's implicitly bound, reserved ``xml:`` prefix."""

from __future__ import annotations

import json

from generate_xml_encoding_boundary_fixtures import (
    ANDROID,
    MANIFEST,
    android_native,
    load_oracles,
    write_json,
)


PREFIX = "portable-android-intrinsic-xml-namespace-"
TRANSLATED = "Marée calme"
CASES = {
    "ordinary-root": '<resources><string name="signal">Quiet bay</string></resources>',
    "implicit-root": (
        '<xml:resources><string name="signal">Quiet bay</string></xml:resources>'
    ),
    "explicit-root": (
        '<xml:resources xmlns:xml="http://www.w3.org/XML/1998/namespace">'
        '<string name="signal">Quiet bay</string></xml:resources>'
    ),
    "prefixed-scalar": (
        '<resources><xml:string name="hidden">Hidden</xml:string>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-generic-scalar": (
        '<resources><xml:item type="string" name="hidden">Hidden</xml:item>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-array": (
        '<resources><xml:array name="hidden"><item>Hidden</item></xml:array>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-string-array": (
        '<resources><xml:string-array name="hidden"><item>Hidden</item>'
        '</xml:string-array><string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-integer-array": (
        '<resources><xml:integer-array name="hidden"><item>1</item>'
        '</xml:integer-array><string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-plural": (
        '<resources><xml:plurals name="hidden"><item quantity="one">Hidden</item>'
        '<item quantity="other">Hidden</item></xml:plurals>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-macro": (
        '<resources><xml:macro name="hidden">Hidden</xml:macro>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-generic-macro": (
        '<resources><xml:item type="macro" name="hidden">Hidden</xml:item>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-attr": (
        '<resources><xml:attr name="hidden" format="string"/>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-styleable": (
        '<resources><xml:declare-styleable name="Hidden">'
        '<attr name="hidden" format="string"/></xml:declare-styleable>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-skip": (
        '<resources><xml:skip/><string name="signal">Quiet bay</string></resources>'
    ),
    "prefixed-eat-comment": (
        "<resources><!-- neutral note --><xml:eat-comment/>"
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "array-prefixed-item": (
        '<resources><array name="routes"><xml:item>Hidden</xml:item>'
        "<item>Visible</item></array></resources>"
    ),
    "string-array-prefixed-item": (
        '<resources><string-array name="routes"><xml:item>Hidden</xml:item>'
        "<item>Visible</item></string-array></resources>"
    ),
    "integer-array-prefixed-item": (
        '<resources><integer-array name="routes"><xml:item>1</xml:item>'
        "<item>2</item></integer-array></resources>"
    ),
    "plural-prefixed-item": (
        '<resources><plurals name="routes"><xml:item quantity="one">Hidden</xml:item>'
        '<item quantity="other">Visible</item></plurals></resources>'
    ),
    "attr-prefixed-enum": (
        '<resources><attr name="tone"><xml:enum name="hidden" value="1"/>'
        '<enum name="visible" value="2"/></attr>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "styleable-prefixed-attr": (
        '<resources><declare-styleable name="Tone">'
        '<xml:attr name="hidden" format="string"/>'
        '<attr name="visible" format="string"/></declare-styleable>'
        '<string name="signal">Quiet bay</string></resources>'
    ),
    "inline-bold": (
        '<resources><string name="signal">Calm <xml:b>quiet</xml:b> bay</string>'
        "</resources>"
    ),
    "inline-font": (
        '<resources><string name="signal">Calm '
        '<xml:font color="#ff0000">quiet</xml:font> bay</string></resources>'
    ),
    "inline-annotation": (
        '<resources><string name="signal">Calm '
        '<xml:annotation key="tone">quiet</xml:annotation> bay</string></resources>'
    ),
    "inline-xliff-g": (
        '<resources><string name="signal">Calm '
        '<xml:g id="pilot">%1$s</xml:g> bay</string></resources>'
    ),
    "inline-nested": (
        '<resources><string name="signal">Calm '
        "<xml:b><i>quiet</i></xml:b> bay</string></resources>"
    ),
    "inline-inside-real": (
        '<resources><string name="signal">Calm '
        "<b><xml:i>quiet</xml:i></b> bay</string></resources>"
    ),
    "inline-product": (
        '<resources><string name="signal">Default</string>'
        '<string name="signal" product="tablet">Calm <xml:b>quiet</xml:b></string>'
        "</resources>"
    ),
    "inline-array": (
        '<resources><array name="routes"><item>Calm <xml:b>quiet</xml:b></item>'
        "</array></resources>"
    ),
    "inline-plural": (
        '<resources><plurals name="signals">'
        '<item quantity="one">One <xml:b>light</xml:b></item>'
        '<item quantity="other">Many <xml:b>lights</xml:b></item>'
        "</plurals></resources>"
    ),
    "attribute-xml-space": (
        '<resources xml:space="preserve"><string name="signal">Quiet bay</string>'
        "</resources>"
    ),
    "attribute-xml-lang": (
        '<resources xml:lang="en"><string name="signal">Quiet bay</string>'
        "</resources>"
    ),
    "default-reset-inline": (
        '<resources xmlns=""><string name="signal">Calm <xml:b>quiet</xml:b>'
        "</string></resources>"
    ),
}

ERRORS = {
    "implicit-root": "INVALID_XML",
    "explicit-root": "INVALID_XML",
    "array-prefixed-item": "INVALID_ANDROID_STRUCTURE",
    "string-array-prefixed-item": "INVALID_ANDROID_STRUCTURE",
    "integer-array-prefixed-item": "INVALID_ANDROID_STRUCTURE",
    "plural-prefixed-item": "INVALID_ANDROID_STRUCTURE",
    "attr-prefixed-enum": "INVALID_ANDROID_ATTRIBUTE_SYMBOL",
    "styleable-prefixed-attr": "INVALID_ANDROID_STYLEABLE",
}


def expected(name: str) -> dict:
    message = {"defaultMessage": "Quiet bay"}
    messages = {"signal": message}
    if name == "prefixed-eat-comment":
        message["description"] = "neutral note"
    elif name in {"inline-bold", "inline-font", "inline-annotation"}:
        message["defaultMessage"] = "Calm quiet bay"
    elif name == "inline-xliff-g":
        message["defaultMessage"] = "Calm {arg0} bay"
        message["placeholders"] = [
            {"name": "arg0", "source": "%1$s", "kind": "string", "position": 1}
        ]
    elif name == "inline-nested":
        message["defaultMessage"] = "Calm <i>quiet</i> bay"
    elif name == "inline-inside-real":
        message["defaultMessage"] = "Calm <b>quiet</b> bay"
    elif name == "inline-product":
        messages = {
            "signal": {"defaultMessage": "Default"},
            "signal@product=tablet": {
                "defaultMessage": "Calm quiet",
                "metadata": {"androidProduct": "tablet"},
            },
        }
    elif name == "inline-array":
        messages = {
            "routes[0]": {
                "defaultMessage": "Calm quiet",
                "metadata": {
                    "arrayIndex": 0,
                    "arrayName": "routes",
                    "androidGenericArray": True,
                },
            }
        }
    elif name == "inline-plural":
        messages = {
            "signals": {
                "defaultMessage": "{count, plural, one {One light} other {Many lights}}",
                "variants": {"one": "One light", "other": "Many lights"},
            }
        }
    elif name == "default-reset-inline":
        message["defaultMessage"] = "Calm quiet"
    return {"schemaVersion": 1, "sourceFormat": "android", "messages": messages}


def record(manifest: dict, name: str, source: str, android, executable) -> None:
    stem = f"xml-intrinsic-namespace-{name}"
    (ANDROID / f"{stem}.xml").write_text(source, encoding="utf-8")
    snapshot = android_native(source, None, android, executable)
    case = {
        "id": f"android-{PREFIX}{name}",
        "format": "android",
        "input": f"fixtures/android/{stem}.xml",
    }
    if name in ERRORS:
        assert snapshot is None, name
        case["error"] = ERRORS[name]
        case["androidOracle"] = "reject"
        if ERRORS[name] == "INVALID_XML":
            manifest["sourceSkeletonErrors"].append(
                {
                    "id": f"android-source-{PREFIX}{name}",
                    "format": "android",
                    "input": case["input"],
                    "error": ERRORS[name],
                }
            )
    else:
        assert snapshot is not None, name
        expected_name = f"{stem}.expected.json"
        compiled_name = f"{stem}.compiled.json"
        write_json(ANDROID / expected_name, expected(name))
        write_json(ANDROID / compiled_name, snapshot)
        case.update(
            {
                "expected": f"fixtures/android/{expected_name}",
                "androidCompiled": f"fixtures/android/{compiled_name}",
            }
        )
    manifest["cases"].append(case)


def source_template(
    manifest: dict,
    name: str,
    source: str,
    encoding: str | None,
    android,
    executable,
) -> None:
    stem = f"xml-intrinsic-namespace-source-{name}"
    localized = source.replace("Quiet bay", f'"{TRANSLATED}"')
    (ANDROID / f"{stem}.xml").write_text(source, encoding="utf-8")
    (ANDROID / f"{stem}.localized.xml").write_text(localized, encoding="utf-8")
    write_json(ANDROID / f"{stem}.translations.json", {"signal": TRANSLATED})
    encoding_name, codec, bom = {
        None: ("UTF-8", "utf-8", 0),
        "UTF-16LE": ("UTF-16LE", "utf-16-le", 0),
        "UTF-16BE-BOM": ("UTF-16BE-BOM", "utf-16-be", 2),
    }[encoding]
    position = source.index("Quiet bay")
    start = bom + len(source[:position].encode(codec))
    write_json(
        ANDROID / f"{stem}.expected.skeleton.json",
        {
            "schemaVersion": 1,
            "sourceFormat": "android",
            "encoding": encoding_name,
            "source": source,
            "slots": [
                {
                    "id": "signal",
                    "start": start,
                    "end": start + len("Quiet bay".encode(codec)),
                }
            ],
        },
    )
    original = android_native(source, encoding, android, executable)
    translated = android_native(localized, encoding, android, executable)
    assert original is not None and translated is not None, name
    write_json(ANDROID / f"{stem}.compiled.json", original)
    write_json(ANDROID / f"{stem}.localized.compiled.json", translated)
    case = {
        "id": f"android-source-{PREFIX}{name}",
        "format": "android",
        "input": f"fixtures/android/{stem}.xml",
        "expected": f"fixtures/android/{stem}.expected.skeleton.json",
        "translations": f"fixtures/android/{stem}.translations.json",
        "localized": f"fixtures/android/{stem}.localized.xml",
        "androidCompiled": f"fixtures/android/{stem}.compiled.json",
        "androidLocalizedCompiled": f"fixtures/android/{stem}.localized.compiled.json",
    }
    if encoding is not None:
        case["encoding"] = encoding
    manifest["sourceSkeletons"].append(case)


def main() -> None:
    android, _, executable = load_oracles()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for field in ("cases", "sourceSkeletons", "sourceSkeletonErrors"):
        manifest[field] = [case for case in manifest[field] if PREFIX not in case["id"]]
    for name, source in CASES.items():
        record(manifest, name, source, android, executable)

    declarations = {
        "utf8-hidden-resource": (
            None,
            "UTF-8",
            '<xml:string name="hidden">Private</xml:string>',
        ),
        "utf8-foreign-control": (
            None,
            "UTF-8",
            "<!-- neutral source note --><xml:eat-comment/>",
        ),
        "utf16le-hidden-array": (
            "UTF-16LE",
            "UTF-16LE",
            '<xml:array name="hidden"><item>Private</item></xml:array>',
        ),
        "utf16be-bom-hidden-macro": (
            "UTF-16BE-BOM",
            "UTF-16BE",
            '<xml:macro name="hidden">Private</xml:macro>',
        ),
    }
    for name, (encoding, declaration, hidden) in declarations.items():
        source = (
            f'<?xml version="1.0" encoding="{declaration}"?>\n'
            f'<resources>{hidden}<string name="signal">Quiet bay</string></resources>\n'
        )
        source_template(manifest, name, source, encoding, android, executable)

    MANIFEST.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"Generated {len(CASES)} intrinsic XML-namespace fixtures, "
        f"{sum(error == 'INVALID_XML' for error in ERRORS.values())} "
        f"source error contracts, and {len(declarations)} source templates"
    )


if __name__ == "__main__":
    main()
