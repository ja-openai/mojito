from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import unicodedata
from typing import Any, Callable, cast

from .errors import MF2Error
from .functions import (
    _DEFAULT_FUNCTION_REGISTRY,
    FunctionCall,
    FunctionMatch,
    FunctionRegistry,
    FunctionSource,
)
from .model import (
    MF2Arguments,
    MF2ExpressionPart,
    MF2FallbackPart,
    MF2FormattedPart,
    MF2MarkupPart,
    MF2MessageModel,
)
from ._plural import select_plural_category


@dataclass(frozen=True)
class FormatResult:
    value: str
    errors: list[MF2Error]

    @property
    def ok(self) -> bool:
        return not self.errors

    @property
    def has_errors(self) -> bool:
        return bool(self.errors)


@dataclass(frozen=True)
class PartsResult:
    parts: list[MF2FormattedPart]
    errors: list[MF2Error]

    @property
    def ok(self) -> bool:
        return not self.errors

    @property
    def has_errors(self) -> bool:
        return bool(self.errors)


@dataclass(frozen=True)
class MF2RecoveryContext:
    code: str
    message: str
    locale: str
    variable_name: str | None
    function_name: str | None
    source_expression: str
    fallback_value: str
    error: MF2Error


MF2RecoveryHandler = Callable[[MF2RecoveryContext], str | None]


def format_message(
    model: MF2MessageModel,
    arguments: MF2Arguments | None = None,
    locale: str = "en",
    functions: FunctionRegistry | None = None,
    bidi_isolation: str = "none",
    on_missing_argument: MF2RecoveryHandler | None = None,
    on_format_error: MF2RecoveryHandler | None = None,
) -> FormatResult:
    result = format_message_to_parts(
        model,
        arguments,
        locale,
        functions,
        on_missing_argument,
        on_format_error,
    )
    return FormatResult(
        value=_parts_to_string(result.parts, bidi_isolation),
        errors=result.errors,
    )


def format_message_to_parts(
    model: MF2MessageModel,
    arguments: MF2Arguments | None = None,
    locale: str = "en",
    functions: FunctionRegistry | None = None,
    on_missing_argument: MF2RecoveryHandler | None = None,
    on_format_error: MF2RecoveryHandler | None = None,
) -> PartsResult:
    model_data = cast(dict[str, Any], model)
    _validate_model(model_data)
    context = _FormatContext(
        dict(arguments or {}),
        locale,
        functions or _DEFAULT_FUNCTION_REGISTRY,
        fallback=True,
        on_missing_argument=on_missing_argument,
        on_format_error=on_format_error,
    )
    context.apply_declarations(model_data.get("declarations", []))

    message_type = model_data.get("type")
    if message_type == "message":
        parts = context.format_pattern_to_parts(model_data.get("pattern", []))
    elif message_type == "select":
        parts = context.format_select_to_parts(
            model_data.get("selectors", []), model_data.get("variants", [])
        )
    else:
        raise MF2Error("unsupported-message-type", f"Unsupported message type: {message_type}")
    return PartsResult(parts=parts, errors=context.errors)


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
    _validate_local_references(declarations)


def _validate_local_references(declarations: list[dict[str, Any]]) -> None:
    forbidden: set[str] = set()
    for declaration in reversed(declarations):
        if declaration.get("type") != "local":
            continue
        name = declaration.get("name", "")
        forbidden.add(name)
        if _expression_references_any(declaration.get("value", {}), forbidden):
            raise MF2Error(
                "duplicate-declaration",
                f"Local declaration ${name} must not reference itself or later local declarations.",
            )


def _expression_references_any(expression: dict[str, Any], names: set[str]) -> bool:
    return _arg_references_any(expression.get("arg", {}), names) or any(
        _arg_references_any(option, names)
        for option in expression.get("function", {}).get("options", {}).values()
    )


def _arg_references_any(arg: dict[str, Any], names: set[str]) -> bool:
    return arg.get("type") == "variable" and arg.get("name") in names


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


def _variant_key_signature(
    keys: list[dict[str, Any]], selector_values: list["_SelectorValue"]
) -> tuple[tuple[str, str], ...]:
    return tuple(
        ("*", "")
        if key.get("type") == "*"
        else ("=", _signature_key(key.get("value", ""), selector))
        for key, selector in zip(keys, selector_values)
    )


def _signature_key(value: str, selector: "_SelectorValue") -> str:
    if selector.normalized_rendered is None:
        return value
    return _normalize_string_key(value)


class _FormatContext:
    def __init__(
        self,
        values: dict[str, Any],
        locale: str,
        functions: FunctionRegistry,
        fallback: bool = False,
        on_missing_argument: MF2RecoveryHandler | None = None,
        on_format_error: MF2RecoveryHandler | None = None,
        ) -> None:
        self.values = values
        self.sources: dict[str, FunctionSource | None] = {name: None for name in values}
        self.locale = locale
        self.functions = functions
        self.selector_annotations: dict[str, _SelectorAnnotation] = {}
        self.failed_locals: set[str] = set()
        self.errors: list[MF2Error] = []
        self.fallback = fallback
        self.on_missing_argument = on_missing_argument or _default_recovery
        self.on_format_error = on_format_error or _default_recovery

    def apply_declarations(self, declarations: list[dict[str, Any]]) -> None:
        self.selector_annotations = _selector_annotations(declarations)
        for declaration in declarations:
            if declaration.get("type") == "input":
                value = declaration.get("value", {})
                if value.get("function") is None or declaration["name"] not in self.values:
                    continue
                rendered = self._format_expression_output(value)
                if rendered.had_error:
                    self.failed_locals.add(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.value
                    self.sources[declaration["name"]] = rendered.source
            elif declaration.get("type") == "local":
                rendered = self._format_expression_output(declaration["value"])
                if rendered.had_error:
                    self.failed_locals.add(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.value
                    self.sources[declaration["name"]] = rendered.source

    def format_select_to_parts(
        self,
        selectors: list[dict[str, Any]],
        variants: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        selector_values = []
        for selector in selectors:
            name = selector["name"]
            annotation = self.selector_annotations.get(name)
            if name not in self.values:
                if self.fallback:
                    if name not in self.failed_locals:
                        self.errors.append(_unresolved_variable(name))
                    selector_values.append(
                        _SelectorValue(
                            rendered="",
                            raw_value="",
                            normalized_rendered=(
                                _normalize_string_key("") if self._string_select(name) else None
                            ),
                            exact_match=False,
                            selection_key=None,
                            function=annotation.function if annotation else None,
                            source=None,
                        )
                    )
                    if annotation is not None and self.functions.has_selector(annotation.function):
                        if name not in self.failed_locals:
                            self.errors.append(
                                MF2Error("bad-operand", "Selector operand is not available.")
                            )
                        self.errors.append(
                            MF2Error("bad-selector", "Selector operand is not available.")
                        )
                    continue
                raise MF2Error("missing-argument", f"Missing argument ${name}.")
            value = self.values[name]
            rendered = _render_value(value)
            normalized_rendered = (
                _normalize_string_key(rendered) if self._string_select(name) else None
            )
            selector_values.append(
                _SelectorValue(
                    rendered=rendered,
                    raw_value=value,
                    normalized_rendered=normalized_rendered,
                    exact_match=self._exact_match(name),
                    selection_key=self._selection_key(name, value),
                    function=annotation.function if annotation else None,
                    source=self.sources.get(name),
                )
            )

        fallback = None
        selected = None
        selected_rank = None
        signatures: set[tuple[tuple[str, str], ...]] = set()
        for variant in variants:
            keys = variant.get("keys", [])
            if len(keys) != len(selector_values):
                raise MF2Error(
                    "variant-key-count-mismatch",
                    "Variant key count must match selector count.",
                )
            signature = _variant_key_signature(keys, selector_values)
            if signature in signatures:
                raise MF2Error(
                    "duplicate-variant",
                    "Select variants must have unique key tuples.",
                )
            signatures.add(signature)
            if all(key.get("type") == "*" for key in keys):
                fallback = variant
            rank = self._variant_match_rank(keys, selector_values)
            if rank is not None and (selected_rank is None or _compare_rank(rank, selected_rank) > 0):
                selected = variant
                selected_rank = rank

        if fallback is None:
            raise MF2Error(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.",
            )

        return self.format_pattern_to_parts((selected or fallback).get("value", []))

    def format_pattern(self, pattern: list[Any]) -> str:
        return _parts_to_string(self.format_pattern_to_parts(pattern))

    def format_pattern_to_parts(self, pattern: list[Any]) -> list[MF2FormattedPart]:
        parts: list[MF2FormattedPart] = []
        for part in pattern:
            if isinstance(part, str):
                parts.append({"type": "text", "value": part})
                continue
            part_type = part.get("type")
            if part_type == "expression":
                rendered = self._format_expression_output(part)
                if rendered.had_error:
                    source = rendered.fallback_source or _fallback_source(part)
                    fallback_part: MF2FallbackPart = {"type": "fallback", "source": source}
                    if rendered.value != _fallback_value(source):
                        fallback_part["value"] = rendered.value
                    parts.append(fallback_part)
                else:
                    expression_part: MF2ExpressionPart = {
                        "type": "expression",
                        "value": rendered.value,
                    }
                    if attributes := part.get("attributes"):
                        expression_part["attributes"] = attributes
                    parts.append(expression_part)
            elif part_type == "markup":
                markup_part: MF2MarkupPart = {
                    "type": "markup",
                    "kind": part.get("kind", ""),
                    "name": part.get("name", ""),
                }
                if options := part.get("options"):
                    markup_part["options"] = options
                if attributes := part.get("attributes"):
                    markup_part["attributes"] = attributes
                parts.append(markup_part)
            else:
                raise MF2Error("unsupported-pattern-part", f"Unsupported pattern part: {part_type}")
        return parts

    def format_expression(self, expression: dict[str, Any]) -> str:
        return self._format_expression_output(expression).value

    def _format_expression_output(self, expression: dict[str, Any]) -> "_ExpressionOutput":
        had_error = False
        source: FunctionSource | None = None
        arg = expression.get("arg")
        if arg is None:
            value = ""
            raw_value = ""
        elif arg.get("type") == "literal":
            value = arg.get("value", "")
            raw_value = value
        elif arg.get("type") == "variable":
            name = arg["name"]
            if name not in self.values:
                if self.fallback:
                    had_error = True
                    error = _unresolved_variable(name)
                    if name not in self.failed_locals:
                        self.errors.append(error)
                    if expression.get("function") is not None:
                        self.errors.append(
                            MF2Error("bad-operand", "Function operand is not available.")
                        )
                    fallback_source = _fallback_source(expression)
                    value = self._recover_missing_argument(
                        expression,
                        name,
                        fallback_source,
                        error,
                    )
                    raw_value = value
                else:
                    raise MF2Error("missing-argument", f"Missing argument ${name}.")
            else:
                raw_value = self.values[name]
                value = _render_value(raw_value)
                source = self.sources.get(name)
        else:
            raise MF2Error("unsupported-expression-arg", f"Unsupported expression arg: {arg}")

        if had_error:
            return _ExpressionOutput(
                value=value,
                had_error=True,
                fallback_source=_fallback_source(expression),
            )

        function = expression.get("function")
        if function is None:
            return _ExpressionOutput(value=value, had_error=False, source=source)
        try:
            source_value = value if source is None else source.value
            return _ExpressionOutput(
                value=self.functions.format(
                    FunctionCall(
                        value=value,
                        raw_value=raw_value,
                        function=function,
                        locale=self.locale,
                        _option_resolver=lambda name, default: self._option_value(function, name, default),
                        inherited_source=source,
                    )
                ),
                had_error=False,
                source=FunctionSource(
                    source_value,
                    function,
                    source,
                    lambda name, default: self._option_value(function, name, default),
                ),
            )
        except MF2Error as error:
            if not self.fallback:
                raise
            recoverable = _fallback_error(error)
            self.errors.append(recoverable)
            fallback_source = _fallback_source(expression)
            return _ExpressionOutput(
                value=self._recover_format_error(expression, fallback_source, recoverable),
                had_error=True,
                fallback_source=fallback_source,
            )

    def _recover_missing_argument(
        self,
        expression: dict[str, Any],
        variable_name: str,
        fallback_source: str,
        error: MF2Error,
    ) -> str:
        return _recover_value(
            self.on_missing_argument,
            MF2RecoveryContext(
                code=error.code,
                message=error.message,
                locale=self.locale,
                variable_name=variable_name,
                function_name=(expression.get("function") or {}).get("name"),
                source_expression=_expression_source(expression),
                fallback_value=_fallback_value(fallback_source),
                error=error,
            ),
        )

    def _recover_format_error(
        self,
        expression: dict[str, Any],
        fallback_source: str,
        error: MF2Error,
    ) -> str:
        arg = expression.get("arg") or {}
        return _recover_value(
            self.on_format_error,
            MF2RecoveryContext(
                code=error.code,
                message=error.message,
                locale=self.locale,
                variable_name=arg.get("name") if arg.get("type") == "variable" else None,
                function_name=(expression.get("function") or {}).get("name"),
                source_expression=_expression_source(expression),
                fallback_value=_fallback_value(fallback_source),
                error=error,
            ),
        )

    def _option_value(
        self,
        function: dict[str, Any],
        option_name: str,
        default: str | None,
    ) -> str | None:
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
        if annotation is None or not annotation.is_numeric:
            return None
        if annotation.function_name == "percent":
            value = _percent_plural_operand(value)
        return select_plural_category(self.locale, value, annotation.number_select)

    def _string_select(self, selector_name: str) -> bool:
        annotation = self.selector_annotations.get(selector_name)
        return annotation is not None and annotation.is_string

    def _variant_match_rank(
        self,
        keys: list[dict[str, Any]],
        selector_values: list["_SelectorValue"],
    ) -> list[int] | None:
        if len(keys) != len(selector_values):
            return None
        rank = []
        for key, selector in zip(keys, selector_values):
            item_rank = self._key_match_rank(key, selector)
            if item_rank is None:
                return None
            rank.append(item_rank)
        return rank

    def _key_match_rank(self, key: dict[str, Any], selector: "_SelectorValue") -> int | None:
        if key.get("type") == "*":
            return 0
        value = str(key.get("value", ""))
        if (selector.exact_match and _literal_key_matches(value, selector)) or value == selector.selection_key:
            return 1
        if selector.function is None:
            return None
        try:
            return self.functions.select(
                FunctionMatch(
                    value=selector.rendered,
                    raw_value=selector.raw_value,
                    function=selector.function,
                    key=value,
                    locale=self.locale,
                    _option_resolver=lambda name, default: self._option_value(
                        selector.function, name, default
                    ),
                    inherited_source=selector.source,
                )
            )
        except MF2Error as error:
            if not self.fallback:
                raise
            self.errors.append(_fallback_error(error))
            self.errors.append(MF2Error("bad-selector", "Selector failed to match."))
            return None


@dataclass(frozen=True)
class _SelectorAnnotation:
    function: dict[str, Any]
    number_select: str = "plural"

    @classmethod
    def from_function(cls, function: dict[str, Any]) -> "_SelectorAnnotation":
        options = function.get("options", {})
        select = options.get("select", {})
        number_select = select.get("value", "plural") if select.get("type") == "literal" else "plural"
        if number_select not in {"plural", "ordinal", "exact"}:
            number_select = "plural"
        return cls(function, number_select)

    @property
    def exact_match(self) -> bool:
        return self.function_name == "string" or (
            self.is_numeric and self.number_select == "exact"
        )

    @property
    def is_numeric(self) -> bool:
        return self.function_name in {"number", "integer", "percent", "offset"}

    @property
    def is_string(self) -> bool:
        return self.function_name == "string"

    @property
    def function_name(self) -> str:
        return self.function.get("name", "")


@dataclass(frozen=True)
class _SelectorValue:
    rendered: str
    raw_value: Any
    normalized_rendered: str | None
    exact_match: bool
    selection_key: str | None
    function: dict[str, Any] | None
    source: FunctionSource | None


@dataclass(frozen=True)
class _ExpressionOutput:
    value: str
    had_error: bool
    source: FunctionSource | None = None
    fallback_source: str | None = None


def _default_recovery(context: MF2RecoveryContext) -> str:
    return context.fallback_value


def _recover_value(handler: MF2RecoveryHandler, context: MF2RecoveryContext) -> str:
    value = handler(context)
    return context.fallback_value if value is None else str(value)


def _compare_rank(left: list[int], right: list[int]) -> int:
    for left_item, right_item in zip(left, right):
        if left_item != right_item:
            return left_item - right_item
    return len(left) - len(right)


def _literal_key_matches(value: str, selector: _SelectorValue) -> bool:
    if selector.normalized_rendered is None:
        return value == selector.rendered
    return _normalize_string_key(value) == selector.normalized_rendered


def _normalize_string_key(value: str) -> str:
    return unicodedata.normalize("NFC", value)


def _percent_plural_operand(value: Any) -> str:
    rendered = _render_value(value)
    if rendered.endswith("%"):
        return rendered[:-1]
    try:
        return str(Decimal(rendered) * Decimal(100))
    except (InvalidOperation, ValueError):
        return rendered


def _unresolved_variable(name: str) -> MF2Error:
    return MF2Error("unresolved-variable", f"Variable ${name} could not be resolved.")


def _fallback_error(error: MF2Error) -> MF2Error:
    if error.code == "unsupported-function":
        return MF2Error("unknown-function", error.message)
    return error


def _fallback_source(expression: dict[str, Any]) -> str:
    arg = expression.get("arg")
    if arg is not None:
        return _expression_arg_source(arg)
    function = expression.get("function")
    if function is not None:
        return _function_source(function)
    return ""


def _fallback_value(source: str) -> str:
    return "{" + source + "}"


def _expression_source(expression: dict[str, Any]) -> str:
    items = []
    if expression.get("arg") is not None:
        items.append(_expression_arg_source(expression["arg"]))
    if expression.get("function") is not None:
        items.append(_function_source(expression["function"]))
    return "{" + " ".join(items) + "}"


def _expression_arg_source(arg: dict[str, Any]) -> str:
    if arg.get("type") == "variable":
        return f"${arg.get('name', '')}"
    return _quote_literal_source(str(arg.get("value", "")))


def _function_source(function: dict[str, Any]) -> str:
    source = f":{function.get('name', '')}"
    for name, value in function.get("options", {}).items():
        source += f" {name}={_expression_arg_source(value)}"
    return source


def _quote_literal_source(value: str) -> str:
    return "|" + value.replace("\\", "\\\\").replace("|", "\\|") + "|"


def _render_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _parts_to_string(parts: list[MF2FormattedPart], bidi_isolation: str = "none") -> str:
    output = []
    for part in parts:
        part_type = part.get("type")
        if part_type == "text":
            output.append(part.get("value", ""))
        elif part_type == "fallback":
            output.append(part["value"] if "value" in part else ("{" + part.get("source", "") + "}"))
        elif part_type == "expression":
            output.append(_isolate_expression(part.get("value", ""), bidi_isolation))
    return "".join(output)


def _isolate_expression(value: str, bidi_isolation: str) -> str:
    if bidi_isolation == "default":
        return f"\u2068{value}\u2069"
    return value
