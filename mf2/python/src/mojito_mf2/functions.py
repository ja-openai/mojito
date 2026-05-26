from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from ._portable_functions import portable_formatters, portable_selectors
from .errors import MF2Error
from .model import MF2FunctionAnnotation


Formatter = Callable[["FunctionCall"], str]
Selector = Callable[["FunctionMatch"], int | None]
OptionResolver = Callable[[str, str | None], str | None]


@dataclass(frozen=True)
class FunctionSource:
    value: str
    function: MF2FunctionAnnotation
    inherited_source: "FunctionSource | None"
    _option_resolver: OptionResolver

    def option_value(self, name: str, default: str | None = None) -> str | None:
        return self._option_resolver(name, default)


@dataclass(frozen=True)
class FunctionCall:
    value: str
    raw_value: Any
    function: MF2FunctionAnnotation
    locale: str
    _option_resolver: OptionResolver
    inherited_source: FunctionSource | None = None

    def option_value(self, name: str, default: str | None = None) -> str | None:
        return self._option_resolver(name, default)


@dataclass(frozen=True)
class FunctionMatch:
    value: str
    raw_value: Any
    function: MF2FunctionAnnotation
    key: str
    locale: str
    _option_resolver: OptionResolver
    inherited_source: FunctionSource | None = None

    def option_value(self, name: str, default: str | None = None) -> str | None:
        return self._option_resolver(name, default)


class FunctionRegistry:
    def __init__(
        self,
        formatters: dict[str, Formatter] | None = None,
        selectors: dict[str, Selector] | None = None,
    ) -> None:
        self._formatters = dict(formatters or {})
        self._selectors = dict(selectors or {})

    @classmethod
    def defaults(cls) -> "FunctionRegistry":
        return cls.portable()

    @classmethod
    def portable(cls) -> "FunctionRegistry":
        return cls(portable_formatters(), portable_selectors())

    def with_function(self, name: str, formatter: Formatter) -> "FunctionRegistry":
        formatters = dict(self._formatters)
        formatters[name] = formatter
        return FunctionRegistry(formatters, self._selectors)

    def with_selector(self, name: str, selector: Selector) -> "FunctionRegistry":
        selectors = dict(self._selectors)
        selectors[name] = selector
        return FunctionRegistry(self._formatters, selectors)

    def has_formatter(self, function: MF2FunctionAnnotation) -> bool:
        return function.get("name", "") in self._formatters

    def has_selector(self, function: MF2FunctionAnnotation) -> bool:
        return function.get("name", "") in self._selectors

    def format(self, call: FunctionCall) -> str:
        name = call.function.get("name", "")
        formatter = self._formatters.get(name)
        if formatter is None:
            raise MF2Error(
                "unsupported-function",
                f"Function :{name} is not supported by this formatter registry.",
            )
        return formatter(call)

    def select(self, match: FunctionMatch) -> int | None:
        selector = self._selectors.get(match.function.get("name", ""))
        return None if selector is None else selector(match)


_DEFAULT_FUNCTION_REGISTRY = FunctionRegistry.defaults()
