from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .errors import MF2Error
from .plural import select_cardinal_plural_category


def format_message(
    model: dict[str, Any],
    arguments: dict[str, Any] | None = None,
    locale: str = "en",
) -> str:
    context = _FormatContext(dict(arguments or {}), locale)
    context.apply_declarations(model.get("declarations", []))

    message_type = model.get("type")
    if message_type == "message":
        return context.format_pattern(model.get("pattern", []))
    if message_type == "select":
        return context.format_select(model.get("selectors", []), model.get("variants", []))
    raise MF2Error("unsupported-message-type", f"Unsupported message type: {message_type}")


class _FormatContext:
    def __init__(self, values: dict[str, Any], locale: str) -> None:
        self.values = values
        self.locale = locale
        self.input_functions: dict[str, str] = {}

    def apply_declarations(self, declarations: list[dict[str, Any]]) -> None:
        for declaration in declarations:
            if declaration.get("type") == "input":
                function = declaration.get("value", {}).get("function")
                if function is not None:
                    self.input_functions[declaration["name"]] = function.get("name", "")
            elif declaration.get("type") == "local":
                self.values[declaration["name"]] = self.format_expression(declaration["value"])

    def format_select(
        self,
        selectors: list[dict[str, Any]],
        variants: list[dict[str, Any]],
    ) -> str:
        selector_values = []
        for selector in selectors:
            name = selector["name"]
            value = self._argument(name)
            selector_values.append(
                _SelectorValue(
                    rendered=_render_value(value),
                    plural_category=self._plural_category(name, value),
                )
            )

        fallback = None
        for variant in variants:
            keys = variant.get("keys", [])
            if len(keys) != len(selector_values):
                continue
            if all(key.get("type") == "*" for key in keys):
                fallback = variant
            if _variant_matches(keys, selector_values):
                return self.format_pattern(variant.get("value", []))

        if fallback is not None:
            return self.format_pattern(fallback.get("value", []))

        raise MF2Error(
            "missing-select-variant",
            "No select variant matched and no catch-all variant is present.",
        )

    def format_pattern(self, pattern: list[Any]) -> str:
        parts: list[str] = []
        for part in pattern:
            if isinstance(part, str):
                parts.append(part)
                continue
            part_type = part.get("type")
            if part_type == "expression":
                parts.append(self.format_expression(part))
            elif part_type == "markup":
                continue
            else:
                raise MF2Error("unsupported-pattern-part", f"Unsupported pattern part: {part_type}")
        return "".join(parts)

    def format_expression(self, expression: dict[str, Any]) -> str:
        arg = expression.get("arg")
        if arg is None:
            value = ""
        elif arg.get("type") == "literal":
            value = arg.get("value", "")
        elif arg.get("type") == "variable":
            value = _render_value(self._argument(arg["name"]))
        else:
            raise MF2Error("unsupported-expression-arg", f"Unsupported expression arg: {arg}")

        function = expression.get("function")
        if function is None or function.get("name") in {"string", "number", "datetime", "date", "time"}:
            return value

        raise MF2Error(
            "unsupported-function",
            f"Function :{function.get('name')} is not supported by this runtime slice.",
        )

    def _argument(self, name: str) -> Any:
        if name not in self.values:
            raise MF2Error("missing-argument", f"Missing argument ${name}.")
        return self.values[name]

    def _plural_category(self, selector_name: str, value: Any) -> str | None:
        if self.input_functions.get(selector_name) != "number":
            return None
        return select_cardinal_plural_category(self.locale, value)


@dataclass(frozen=True)
class _SelectorValue:
    rendered: str
    plural_category: str | None


def _variant_matches(keys: list[dict[str, Any]], selector_values: list[_SelectorValue]) -> bool:
    if len(keys) != len(selector_values):
        return False
    return all(
        key.get("type") == "*"
        or key.get("value") == selector.rendered
        or key.get("value") == selector.plural_category
        for key, selector in zip(keys, selector_values)
    )


def _render_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)
