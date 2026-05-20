from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from .errors import MF2Error


Formatter = Callable[["FunctionCall"], str]
OptionResolver = Callable[[str, str | None], str | None]


@dataclass(frozen=True)
class FunctionCall:
    value: str
    function: dict[str, Any]
    locale: str
    _option_resolver: OptionResolver

    def option_value(self, name: str, default: str | None = None) -> str | None:
        return self._option_resolver(name, default)


class FunctionRegistry:
    def __init__(self, formatters: dict[str, Formatter] | None = None) -> None:
        self._formatters = dict(formatters or {})

    @classmethod
    def defaults(cls) -> "FunctionRegistry":
        return cls(
            {
                "string": _passthrough,
                "number": _passthrough,
                "integer": _passthrough,
                "datetime": _passthrough,
                "date": _passthrough,
                "time": _passthrough,
            }
        )

    def with_function(self, name: str, formatter: Formatter) -> "FunctionRegistry":
        formatters = dict(self._formatters)
        formatters[name] = formatter
        return FunctionRegistry(formatters)

    def format(self, call: FunctionCall) -> str:
        name = call.function.get("name", "")
        formatter = self._formatters.get(name)
        if formatter is None:
            raise MF2Error(
                "unsupported-function",
                f"Function :{name} is not supported by this formatter registry.",
            )
        return formatter(call)


def _passthrough(call: FunctionCall) -> str:
    return call.value


DEFAULT_FUNCTION_REGISTRY = FunctionRegistry.defaults()
