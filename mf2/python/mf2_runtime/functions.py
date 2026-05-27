from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from .errors import MF2Error


Formatter = Callable[["FunctionCall"], str]
OptionResolver = Callable[[str, str | None], str | None]


@dataclass(frozen=True)
class FunctionCall:
    value: str
    raw_value: Any
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
                "integer": _integer,
                "offset": _offset,
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


def _integer(call: FunctionCall) -> str:
    value = _parse_number(call.value, "Integer function requires a numeric operand.")
    return str(int(value))


def _offset(call: FunctionCall) -> str:
    value = _parse_integer(call.value, "Offset function requires a numeric operand.")
    add = call.option_value("add")
    subtract = call.option_value("subtract")
    if (add is None and subtract is None) or (add is not None and subtract is not None):
        raise MF2Error("bad-option", "Offset function requires exactly one of add or subtract.")
    delta = _parse_integer(
        add if add is not None else subtract,
        "Offset add option must be an integer." if add is not None else "Offset subtract option must be an integer.",
    )
    return str(value + (delta if add is not None else -delta))


def _parse_number(value: str | None, message: str) -> float:
    try:
        parsed = float(str(value if value is not None else ""))
    except ValueError as error:
        raise MF2Error("bad-operand", message) from error
    if parsed in (float("inf"), float("-inf")) or parsed != parsed:
        raise MF2Error("bad-operand", message)
    return parsed


def _parse_integer(value: str | None, message: str) -> int:
    text = str(value if value is not None else "")
    if not text or not (text.isdigit() or (text[0] in "+-" and text[1:].isdigit())):
        raise MF2Error("bad-option" if "option" in message.lower() else "bad-operand", message)
    return int(text)


DEFAULT_FUNCTION_REGISTRY = FunctionRegistry.defaults()
