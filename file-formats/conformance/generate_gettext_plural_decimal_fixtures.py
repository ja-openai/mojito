#!/usr/bin/env python3
"""Pin native decimal leading zeroes and reject ambiguous gettext declarations."""

from __future__ import annotations

import json

from generate_gettext_plural_whitespace_fixtures import (
    FIXTURES,
    IDENTITY,
    MANIFEST,
    TARGET_PLURAL,
    TARGET_SINGULAR,
    catalog,
    compiled,
    po,
    skeleton,
    write_json,
)


PREFIX = "gettext-plural-decimal-"
SKELETON_ID = "gettext-source-skeleton-preserves-leading-zero-plural-decimals"


def main() -> None:
    accepted = {
        "count-leading-zero": ("02", "n!=1"),
        "count-64-leading-zeroes": ("0" * 64 + "2", "n!=1"),
        "count-512-leading-zeroes": ("0" * 512 + "2", "n!=1"),
        "literal-leading-zero": ("2", "n!=01"),
        "literal-64-leading-zeroes": ("2", "n!=" + "0" * 64 + "1"),
        "literal-512-leading-zeroes": ("2", "n!=" + "0" * 512 + "1"),
        "multiple-leading-zero-literals": (
            "2",
            "(n+" + "0" * 96 + ")!=" + "0" * 96 + "1",
        ),
        "count-and-literal-leading-zeroes": (
            "0" * 256 + "2",
            "n!=" + "0" * 256 + "1",
        ),
    }
    rejected = {
        "count-zero": ("nplurals=0; plural=n!=1;", "reject"),
        "count-zero-512-leading-zeroes": (
            "nplurals=" + "0" * 512 + "; plural=n!=1;",
            "reject",
        ),
        "count-plus": ("nplurals=+2; plural=n!=1;", "reject"),
        "count-minus": ("nplurals=-2; plural=n!=1;", "reject"),
        "count-leading-em-space": ("nplurals=\u20032; plural=n!=1;", "reject"),
        "count-leading-no-break-space": ("nplurals=\u00a02; plural=n!=1;", "reject"),
        "count-decimal-suffix": ("nplurals=2.0; plural=n!=1;", "accept"),
        "count-exponent-suffix": ("nplurals=2e0; plural=n!=1;", "accept"),
        "count-underscore-suffix": ("nplurals=2_0; plural=n!=1;", "accept"),
        "count-internal-space": ("nplurals=2 0; plural=n!=1;", "accept"),
        "count-word-suffix": ("nplurals=2coast; plural=n!=1;", "accept"),
        "count-trailing-em-space": ("nplurals=2\u2003; plural=n!=1;", "accept"),
        "count-trailing-no-break-space": ("nplurals=2\u00a0; plural=n!=1;", "accept"),
        "count-unsigned-overflow": (
            "nplurals=18446744073709551616; plural=n!=1;",
            "reject",
        ),
        "literal-significant-overflow-after-zeroes": (
            "nplurals=2; plural=n!=" + "0" * 64 + "9223372036854775808;",
            "accept",
        ),
        "duplicate-count-same": ("nplurals=2; nplurals=2; plural=n!=1;", "accept"),
        "duplicate-count-conflicting": (
            "nplurals=2; nplurals=3; plural=n!=1;",
            "accept",
        ),
        "duplicate-count-invalid-first": (
            "nplurals=coast; nplurals=2; plural=n!=1;",
            "reject",
        ),
        "duplicate-count-invalid-second": (
            "nplurals=2; nplurals=coast; plural=n!=1;",
            "accept",
        ),
        "duplicate-expression-same": (
            "nplurals=2; plural=n!=1; plural=n!=1;",
            "accept",
        ),
        "duplicate-expression-conflicting": (
            "nplurals=2; plural=n!=1; plural=n==1;",
            "accept",
        ),
        "duplicate-expression-invalid-first": (
            "nplurals=2; plural=coast; plural=n!=1;",
            "reject",
        ),
        "duplicate-expression-invalid-second": (
            "nplurals=2; plural=n!=1; plural=coast;",
            "accept",
        ),
    }
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    manifest["cases"] = [
        case for case in manifest["cases"] if not case["id"].startswith(PREFIX)
    ]

    for name, (count, expression) in accepted.items():
        stem = "plural-decimal-" + name
        source = po(expression, declaration=f"nplurals={count}; plural={expression};")
        (FIXTURES / f"{stem}.po").write_text(source, encoding="utf-8")
        write_json(FIXTURES / f"{stem}.expected.json", catalog(expression))
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

    for name, (declaration, native) in rejected.items():
        stem = "plural-decimal-" + name
        (FIXTURES / f"{stem}.po").write_text(
            po("", declaration=declaration), encoding="utf-8"
        )
        manifest["cases"].append(
            {
                "id": PREFIX + name,
                "format": "gettext_po",
                "input": f"fixtures/gettext/{stem}.po",
                "error": "INVALID_GETTEXT_PLURAL_FORMS",
                "gettextOracle": native,
            }
        )

    count, expression = accepted["count-and-literal-leading-zeroes"]
    stem = "plural-decimal-count-and-literal-leading-zeroes"
    source = po(expression, declaration=f"nplurals={count}; plural={expression};")
    localized_stem = stem + ".localized"
    localized = source.replace(TARGET_SINGULAR, "%d port beacon").replace(
        TARGET_PLURAL, "%d port beacons"
    )
    (FIXTURES / f"{localized_stem}.po").write_text(localized, encoding="utf-8")
    write_json(FIXTURES / f"{stem}.expected.skeleton.json", skeleton(source))
    write_json(
        FIXTURES / f"{stem}.translations.json",
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
            "input": f"fixtures/gettext/{stem}.po",
            "expected": f"fixtures/gettext/{stem}.expected.skeleton.json",
            "translations": f"fixtures/gettext/{stem}.translations.json",
            "localized": f"fixtures/gettext/{localized_stem}.po",
            "gettextCompiled": f"fixtures/gettext/{stem}.compiled.json",
            "gettextLocalizedCompiled": f"fixtures/gettext/{localized_stem}.compiled.json",
        }
    )
    write_json(MANIFEST, manifest)
    print(
        f"Generated {len(accepted)} valid leading-zero plural declarations, "
        f"{len(rejected)} invalid or deliberately rejected headers, "
        "and one byte-preserving native plural skeleton."
    )


if __name__ == "__main__":
    main()
