from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sample_catalog_functions import sample_catalog_registry
from mojito_mf2 import FunctionRegistry, format_message


class Catalog:
    def __init__(
        self,
        messages: dict[str, dict[str, dict[str, Any]]],
        functions: FunctionRegistry,
    ) -> None:
        self.messages = messages
        self.functions = functions

    @classmethod
    def load(cls, path: Path) -> "Catalog":
        with path.open(encoding="utf-8") as file:
            data = json.load(file)
        return cls(data["messages"], sample_catalog_registry())

    def translate(
        self,
        message_id: str,
        locale: str,
        arguments: dict[str, Any] | None = None,
        bidi_isolation: str = "none",
    ) -> str:
        model = self._model(message_id, locale)
        result = format_message(
            model,
            arguments or {},
            locale,
            self.functions,
            bidi_isolation=bidi_isolation,
        )
        if result.errors:
            raise RuntimeError(f"MF2 format errors for {message_id!r}: {result.errors!r}")
        return result.value

    def _model(self, message_id: str, locale: str) -> dict[str, Any]:
        localized = self.messages[message_id]
        model = lookup_locale(localized, locale)
        if model is None:
            raise KeyError(f"Missing locale {locale!r} and fallback 'en' for {message_id!r}.")
        return model


def main() -> int:
    catalog = Catalog.load(Path(__file__).resolve().parents[2] / "examples" / "catalog.json")

    examples = [
        ("welcome", "fr", {"name": "Mojito"}, "none", "Bienvenue, Mojito !"),
        ("welcome", "fr-CA", {"name": "Mojito"}, "none", "Bienvenue, Mojito !"),
        ("checkout.total", "en", {"amount": 1234.5}, "none", "Total: $1,234.50"),
        ("checkout.total", "fr", {"amount": 1234.5}, "none", "Total : 1\u202f234,50 €"),
        ("debug.raw", "en", {"value": 1234.5}, "none", "Raw: number=1234.5"),
        (
            "file.saved",
            "en",
            {"fileName": "שלום.txt"},
            "default",
            "File \u2068שלום.txt\u2069 saved.",
        ),
        ("cart.items", "en", {"count": 1}, "none", "1 item"),
        ("cart.items", "en", {"count": 5}, "none", "5 items"),
        ("cart.items", "ru", {"count": 2}, "none", "2 предмета"),
        ("cart.items", "ru", {"count": 5}, "none", "5 предметов"),
        ("assignee.files", "en", {"gender": "male", "count": 1}, "none", "He reviewed 1 file"),
        ("assignee.files", "en", {"gender": "female", "count": 3}, "none", "She reviewed 3 files"),
        ("assignee.files", "en", {"gender": "unknown", "count": 2}, "none", "They reviewed 2 files"),
    ]

    for message_id, locale, arguments, bidi_isolation, expected in examples:
        actual = catalog.translate(message_id, locale, arguments, bidi_isolation)
        if actual != expected:
            raise AssertionError(f"{message_id}/{locale}: expected {expected!r}, got {actual!r}")
        print(f'{message_id}[{locale}] -> "{actual}"')
        if bidi_isolation != "none":
            print(f'{message_id}[{locale}].escaped -> "{escaped_non_ascii(actual)}"')

    return 0


def escaped_non_ascii(value: str) -> str:
    return "".join(
        ch if 0x20 <= ord(ch) <= 0x7E else f"\\u{ord(ch):04X}"
        for ch in value
    )


def lookup_locale(values: dict[str, dict[str, Any]], locale: str, fallback: str = "en") -> dict[str, Any] | None:
    canonical_values = {canonical_locale_key(key): value for key, value in values.items()}
    for candidate in locale_lookup_chain(locale):
        if candidate in canonical_values:
            return canonical_values[candidate]
    return canonical_values.get(canonical_locale_key(fallback))


def locale_lookup_chain(locale: str) -> list[str]:
    parts = [part for part in canonical_locale_key(locale).split("-") if part]
    return ["-".join(parts[:length]) for length in range(len(parts), 0, -1)]


def canonical_locale_key(locale: str) -> str:
    output: list[str] = []
    for index, part in enumerate(locale.strip().replace("_", "-").split("-")):
        if not part:
            continue
        if len(part) == 1:
            break
        if index == 0:
            output.append(part.lower())
        elif len(part) == 4 and part.isalpha():
            output.append(part.title())
        elif (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
            output.append(part.upper())
        else:
            output.append(part.lower())
    return "-".join(output)


if __name__ == "__main__":
    raise SystemExit(main())
