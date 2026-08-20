"""Verify translated HTML attributes with an independent HTML parser."""

from __future__ import annotations

import json
from collections import defaultdict
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent


class AttributeCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.elements: dict[str, list[dict[str, str | None]]] = defaultdict(list)

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        self.elements[tag].append(dict(attrs))

    handle_startendtag = handle_starttag


def main() -> int:
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    checked = 0
    for case in manifest["workflowCases"]:
        assertions = case.get("htmlRuntime")
        if not assertions:
            continue
        parser = AttributeCollector()
        parser.feed((ROOT / case["localized"]).read_text(encoding="utf-8"))
        parser.close()
        for assertion in assertions:
            tag = assertion["tag"]
            occurrence = assertion["occurrence"]
            elements = parser.elements.get(tag, [])
            if occurrence >= len(elements):
                raise AssertionError(
                    f"{case['id']}: missing {tag} occurrence {occurrence}"
                )
            attributes = elements[occurrence]
            for name, expected in assertion["attributes"].items():
                actual = attributes.get(name)
                if actual != expected:
                    raise AssertionError(
                        f"{case['id']}/{tag}[{occurrence}]/{name}: "
                        f"expected {expected!r}, got {actual!r}"
                    )
                checked += 1
            for name in assertion["absentAttributes"]:
                if name in attributes:
                    raise AssertionError(
                        f"{case['id']}/{tag}[{occurrence}]: injected attribute {name!r}"
                    )
    if checked == 0:
        raise AssertionError("No HTML runtime cases were declared")
    print(f"HTML parser verified {checked} translated attribute values remain data.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
