from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .errors import MF2Error
from .plural import select_plural_category


def format_message(
    model: dict[str, Any],
    arguments: dict[str, Any] | None = None,
    locale: str = "en",
) -> str:
    return _parts_to_string(format_message_to_parts(model, arguments, locale))


def format_message_to_parts(
    model: dict[str, Any],
    arguments: dict[str, Any] | None = None,
    locale: str = "en",
) -> list[dict[str, Any]]:
    _validate_model(model)
    context = _FormatContext(dict(arguments or {}), locale)
    context.apply_declarations(model.get("declarations", []))

    message_type = model.get("type")
    if message_type == "message":
        return context.format_pattern_to_parts(model.get("pattern", []))
    if message_type == "select":
        return context.format_select_to_parts(model.get("selectors", []), model.get("variants", []))
    raise MF2Error("unsupported-message-type", f"Unsupported message type: {message_type}")


def _validate_model(model: dict[str, Any]) -> None:
    _validate_declarations(model.get("declarations", []))
    if model.get("type") == "message":
        _validate_pattern(model.get("pattern", []))
    elif model.get("type") == "select":
        _validate_selector_annotations(
            model.get("declarations", []),
            model.get("selectors", []),
        )
        for variant in model.get("variants", []):
            _validate_pattern(variant.get("value", []))


def _validate_declarations(declarations: list[dict[str, Any]]) -> None:
    names: set[str] = set()
    for declaration in declarations:
        name = declaration.get("name", "")
        if declaration.get("type") == "input":
            _validate_input_declaration(declaration)
        if name in names:
            raise MF2Error(
                "duplicate-declaration",
                f"Declaration ${name} is defined more than once.",
            )
        names.add(name)


def _validate_input_declaration(declaration: dict[str, Any]) -> None:
    name = declaration.get("name", "")
    arg = declaration.get("value", {}).get("arg", {})
    if arg.get("type") == "variable" and arg.get("name") == name:
        return
    raise MF2Error(
        "invalid-input-declaration",
        f"Input declaration ${name} must bind the same variable name.",
    )


def _validate_pattern(pattern: list[Any]) -> None:
    for part in pattern:
        if isinstance(part, str) and part == "":
            raise MF2Error(
                "invalid-pattern-text",
                "Pattern text parts must be non-empty.",
            )
        if isinstance(part, dict) and part.get("type") == "markup":
            _validate_markup(part)


def _validate_markup(markup: dict[str, Any]) -> None:
    if markup.get("kind") in {"open", "standalone", "close"}:
        return
    raise MF2Error(
        "invalid-markup-kind",
        "Markup kind must be open, standalone, or close.",
    )


def _selector_annotations(
    declarations: list[dict[str, Any]],
) -> dict[str, "_SelectorAnnotation"]:
    expressions = {
        declaration.get("name", ""): declaration.get("value", {})
        for declaration in declarations
    }
    annotations = {
        name: _SelectorAnnotation.from_function(expression["function"])
        for name, expression in expressions.items()
        if expression.get("function") is not None
    }

    changed = True
    while changed:
        changed = False
        for name, expression in expressions.items():
            if name in annotations:
                continue
            arg = expression.get("arg", {})
            if arg.get("type") != "variable":
                continue
            annotation = annotations.get(arg.get("name"))
            if annotation is None:
                continue
            annotations[name] = annotation
            changed = True

    return annotations


def _validate_selector_annotations(
    declarations: list[dict[str, Any]],
    selectors: list[dict[str, Any]],
) -> None:
    annotations = _selector_annotations(declarations)
    for selector in selectors:
        name = selector.get("name", "")
        if name not in annotations:
            raise MF2Error(
                "missing-selector-annotation",
                f"Selector ${name} must reference a declaration with a function.",
            )


def _variant_key_signature(keys: list[dict[str, Any]]) -> tuple[tuple[str, str], ...]:
    return tuple(
        ("*", "") if key.get("type") == "*" else ("=", key.get("value", ""))
        for key in keys
    )


class _FormatContext:
    def __init__(self, values: dict[str, Any], locale: str) -> None:
        self.values = values
        self.locale = locale
        self.selector_annotations: dict[str, _SelectorAnnotation] = {}

    def apply_declarations(self, declarations: list[dict[str, Any]]) -> None:
        self.selector_annotations = _selector_annotations(declarations)
        for declaration in declarations:
            if declaration.get("type") == "local":
                self.values[declaration["name"]] = self.format_expression(declaration["value"])

    def format_select_to_parts(
        self,
        selectors: list[dict[str, Any]],
        variants: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        selector_values = []
        for selector in selectors:
            name = selector["name"]
            value = self._argument(name)
            selector_values.append(
                _SelectorValue(
                    rendered=_render_value(value),
                    exact_match=self._exact_match(name),
                    selection_key=self._selection_key(name, value),
                )
            )

        fallback = None
        selected = None
        signatures: set[tuple[tuple[str, str], ...]] = set()
        for variant in variants:
            keys = variant.get("keys", [])
            if len(keys) != len(selector_values):
                raise MF2Error(
                    "variant-key-count-mismatch",
                    "Variant key count must match selector count.",
                )
            signature = _variant_key_signature(keys)
            if signature in signatures:
                raise MF2Error(
                    "duplicate-variant",
                    "Select variants must have unique key tuples.",
                )
            signatures.add(signature)
            if all(key.get("type") == "*" for key in keys):
                fallback = variant
            if selected is None and _variant_matches(keys, selector_values):
                selected = variant

        if fallback is None:
            raise MF2Error(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.",
            )

        return self.format_pattern_to_parts((selected or fallback).get("value", []))

    def format_pattern(self, pattern: list[Any]) -> str:
        return _parts_to_string(self.format_pattern_to_parts(pattern))

    def format_pattern_to_parts(self, pattern: list[Any]) -> list[dict[str, Any]]:
        parts: list[dict[str, Any]] = []
        for part in pattern:
            if isinstance(part, str):
                parts.append({"type": "text", "value": part})
                continue
            part_type = part.get("type")
            if part_type == "expression":
                expression_part = {"type": "expression", "value": self.format_expression(part)}
                if attributes := part.get("attributes"):
                    expression_part["attributes"] = attributes
                parts.append(expression_part)
            elif part_type == "markup":
                markup_part = {
                    "type": "markup",
                    "kind": part.get("kind", ""),
                    "name": part.get("name", ""),
                }
                if attributes := part.get("attributes"):
                    markup_part["attributes"] = attributes
                parts.append(markup_part)
            else:
                raise MF2Error("unsupported-pattern-part", f"Unsupported pattern part: {part_type}")
        return parts

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
        if function.get("name") == "currency":
            return self._format_currency(value, function)

        raise MF2Error(
            "unsupported-function",
            f"Function :{function.get('name')} is not supported by this runtime slice.",
        )

    def _format_currency(self, value: str, function: dict[str, Any]) -> str:
        currency = self._option_value(function, "currency", "USD")
        return _format_currency_value(value, currency, self.locale)

    def _option_value(
        self,
        function: dict[str, Any],
        option_name: str,
        default: str,
    ) -> str:
        option = function.get("options", {}).get(option_name)
        if option is None:
            return default
        if option.get("type") == "literal":
            return str(option.get("value", ""))
        if option.get("type") == "variable":
            return _render_value(self._argument(option["name"]))
        return default

    def _argument(self, name: str) -> Any:
        if name not in self.values:
            raise MF2Error("missing-argument", f"Missing argument ${name}.")
        return self.values[name]

    def _exact_match(self, selector_name: str) -> bool:
        annotation = self.selector_annotations.get(selector_name)
        return True if annotation is None else annotation.exact_match

    def _selection_key(self, selector_name: str, value: Any) -> str | None:
        annotation = self.selector_annotations.get(selector_name)
        if annotation is None or annotation.function != "number":
            return None
        return select_plural_category(self.locale, value, annotation.number_select)


@dataclass(frozen=True)
class _SelectorAnnotation:
    function: str
    number_select: str = "plural"

    @classmethod
    def from_function(cls, function: dict[str, Any]) -> "_SelectorAnnotation":
        options = function.get("options", {})
        select = options.get("select", {})
        number_select = select.get("value", "plural") if select.get("type") == "literal" else "plural"
        if number_select not in {"plural", "ordinal", "exact"}:
            number_select = "plural"
        return cls(function.get("name", ""), number_select)

    @property
    def exact_match(self) -> bool:
        return self.function == "string" or (
            self.function == "number" and self.number_select == "exact"
        )


@dataclass(frozen=True)
class _SelectorValue:
    rendered: str
    exact_match: bool
    selection_key: str | None


def _variant_matches(keys: list[dict[str, Any]], selector_values: list[_SelectorValue]) -> bool:
    if len(keys) != len(selector_values):
        return False
    return all(
        key.get("type") == "*"
        or (selector.exact_match and key.get("value") == selector.rendered)
        or key.get("value") == selector.selection_key
        for key, selector in zip(keys, selector_values)
    )


def _render_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _parts_to_string(parts: list[dict[str, Any]]) -> str:
    return "".join(
        part.get("value", "")
        for part in parts
        if part.get("type") in {"text", "expression"}
    )


def _format_currency_value(value: str, currency: str, locale: str) -> str:
    try:
        amount = float(value)
    except ValueError as error:
        raise MF2Error("bad-operand", f"Currency value must be numeric, got {value}.") from error
    if amount in {float("inf"), float("-inf")} or amount != amount:
        raise MF2Error("bad-operand", "Currency value must be finite.")

    currency = currency.upper()
    fraction_digits = _currency_fraction_digits(currency)
    scale = 10**fraction_digits
    rounded = round(abs(amount) * scale)
    major = rounded // scale
    fraction = rounded % scale
    french = _canonical_locale_prefix(locale) == "fr"
    grouped = _group_digits(str(major), "\u202f" if french else ",")
    if fraction_digits == 0:
        number = grouped
    else:
        decimal = "," if french else "."
        number = f"{grouped}{decimal}{fraction:0{fraction_digits}d}"
    symbol = _currency_symbol(currency, french)
    negative = "-" if amount < 0 else ""

    if french:
        return f"{negative}{number} {symbol}"
    if len(symbol) == 3:
        return f"{negative}{symbol} {number}"
    return f"{negative}{symbol}{number}"


def _currency_fraction_digits(currency: str) -> int:
    return 0 if currency in {"JPY", "KRW"} else 2


def _currency_symbol(currency: str, french: bool) -> str:
    if currency == "USD":
        return "$US" if french else "$"
    if currency == "EUR":
        return "€"
    if currency == "JPY":
        return "¥"
    if currency == "GBP":
        return "£"
    return currency


def _canonical_locale_prefix(locale: str) -> str:
    return locale.replace("_", "-").split("-", 1)[0].lower() or "en"


def _group_digits(digits: str, separator: str) -> str:
    groups: list[str] = []
    while len(digits) > 3:
        groups.append(digits[-3:])
        digits = digits[:-3]
    groups.append(digits)
    return separator.join(reversed(groups))
