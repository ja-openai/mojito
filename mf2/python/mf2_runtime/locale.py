from __future__ import annotations

from collections.abc import Mapping
from typing import TypeVar


T = TypeVar("T")


def canonical_locale_key(locale: str) -> str:
    return locale.strip().replace("-", "_").lower()


def locale_lookup_chain(locale: str) -> list[str]:
    parts = [part for part in canonical_locale_key(locale).split("_") if part]
    return ["_".join(parts[:length]) for length in range(len(parts), 0, -1)]


def lookup_locale(
    values: Mapping[str, T],
    locale: str,
    fallback: str = "en",
) -> T | None:
    canonical_values = {canonical_locale_key(key): value for key, value in values.items()}
    for candidate in locale_lookup_chain(locale):
        if candidate in canonical_values:
            return canonical_values[candidate]
    return canonical_values.get(canonical_locale_key(fallback))
