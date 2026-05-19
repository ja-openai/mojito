from __future__ import annotations

from typing import Any

from .generated_plural_rules import select_cardinal


def select_cardinal_plural_category(locale: str, value: Any) -> str | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        return select_cardinal(value, locale)
    except ValueError:
        return None
