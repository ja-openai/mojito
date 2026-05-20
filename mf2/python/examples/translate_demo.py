from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from demo_functions import demo_function_registry
from mf2_runtime import FunctionRegistry, format_message, lookup_locale


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
        return cls(data["messages"], demo_function_registry())

    def translate(
        self,
        message_id: str,
        locale: str,
        arguments: dict[str, Any] | None = None,
    ) -> str:
        model = self._model(message_id, locale)
        return format_message(model, arguments or {}, locale, self.functions)

    def _model(self, message_id: str, locale: str) -> dict[str, Any]:
        localized = self.messages[message_id]
        model = lookup_locale(localized, locale)
        if model is None:
            raise KeyError(f"Missing locale {locale!r} and fallback 'en' for {message_id!r}.")
        return model


def main() -> int:
    catalog = Catalog.load(Path(__file__).resolve().parents[2] / "examples" / "catalog.json")

    examples = [
        ("welcome", "fr", {"name": "Mojito"}, "Bienvenue, Mojito !"),
        ("welcome", "fr-CA", {"name": "Mojito"}, "Bienvenue, Mojito !"),
        ("checkout.total", "en", {"amount": 1234.5}, "Total: $1,234.50"),
        ("checkout.total", "fr", {"amount": 1234.5}, "Total : 1\u202f234,50 €"),
        ("cart.items", "en", {"count": 1}, "1 item"),
        ("cart.items", "en", {"count": 5}, "5 items"),
        ("cart.items", "ru", {"count": 2}, "2 предмета"),
        ("cart.items", "ru", {"count": 5}, "5 предметов"),
    ]

    for message_id, locale, arguments, expected in examples:
        actual = catalog.translate(message_id, locale, arguments)
        if actual != expected:
            raise AssertionError(f"{message_id}/{locale}: expected {expected!r}, got {actual!r}")
        print(f'{message_id}[{locale}] -> "{actual}"')

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
