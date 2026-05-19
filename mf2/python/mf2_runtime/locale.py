from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import TypeVar


T = TypeVar("T")


@dataclass(frozen=True)
class LocaleId:
    parts: tuple[str, ...]

    @classmethod
    def parse(cls, locale: str) -> "LocaleId":
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
        return cls(tuple(parts))

    def canonical_tag(self) -> str:
        return "-".join(self.parts)

    def lookup_chain(self) -> list[str]:
        return [
            "-".join(self.parts[:length])
            for length in range(len(self.parts), 0, -1)
        ]


def canonical_locale_key(locale: str) -> str:
    return LocaleId.parse(locale).canonical_tag()


def locale_lookup_chain(locale: str) -> list[str]:
    return LocaleId.parse(locale).lookup_chain()


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


def _canonical_subtag(index: int, part: str) -> str:
    if index == 0:
        return part.lower()
    if len(part) == 4 and part.isalpha():
        return part.title()
    if (len(part) == 2 and part.isalpha()) or (len(part) == 3 and part.isdigit()):
        return part.upper()
    return part.lower()
