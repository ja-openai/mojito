from __future__ import annotations

from typing import Any

from ._cldr_plural_rules import select_cardinal, select_ordinal


def select_plural_category(locale: str, value: Any, number_select: str = "plural") -> str | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        if number_select == "ordinal":
            return select_ordinal(value, locale)
        if number_select == "exact":
            return None
        return select_cardinal(value, locale)
    except ValueError:
        return None
