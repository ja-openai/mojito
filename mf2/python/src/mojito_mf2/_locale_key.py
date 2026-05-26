from __future__ import annotations

from collections.abc import Mapping
from typing import TypeVar


T = TypeVar("T")


def canonical_locale_key(locale: str) -> str:
    return "-".join(_locale_parts(locale))


def locale_lookup_chain(locale: str) -> list[str]:
    return _structural_lookup_chain(canonical_locale_key(locale))


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


def plural_lookup_chain(locale: str, parents: Mapping[str, str]) -> list[str]:
    chain: list[str] = []
    _append_plural_lookup_chain(canonical_locale_key(locale), parents, chain)
    return chain


def _append_plural_lookup_chain(locale: str, parents: Mapping[str, str], chain: list[str]) -> None:
    current = locale
    while current:
        if current in chain:
            return
        chain.append(current)
        parent = parents.get(current)
        if parent is not None:
            _append_plural_lookup_chain(parent, parents, chain)
        current = _structural_parent(current)


def _structural_lookup_chain(locale: str) -> list[str]:
    parts = [part for part in locale.split("-") if part]
    return [
        "-".join(parts[:length])
        for length in range(len(parts), 0, -1)
    ]


def _locale_parts(locale: str) -> list[str]:
    raw_parts = [
        part
        for part in locale.strip().replace("_", "-").split("-")
        if part
    ]
    parts: list[str] = []
    for index, part in enumerate(raw_parts):
        if len(part) == 1:
            break
        parts.append(_canonical_subtag(index, part))
    return parts


def _canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()


def _structural_parent(locale: str) -> str:
    return locale.rsplit("-", 1)[0] if "-" in locale else ""
