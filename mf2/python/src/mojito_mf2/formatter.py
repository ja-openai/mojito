from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import re
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
from .number_core import format_number_core
from ._plural import select_plural_category


_DECIMAL_LITERAL_RE = re.compile(r"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$")
_SOURCE_DECIMAL_RE = re.compile(
    r"^(?P<sign>-?)(?P<integer>0|[1-9][0-9]*)(?:\.(?P<fraction>[0-9]+))?(?:[eE](?P<exponent>[+-]?[0-9]+))?$"
)
_MAX_SOURCE_DECIMAL_EXPONENT = 1_000_000
_MAX_SOURCE_DECIMAL_KEY_LENGTH = 4096


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
    model_data = _model_data(model)
    _validate_model(model_data)
    try:
        normalized_locale = _locale_option(locale)
    except MF2Error as error:
        return PartsResult(parts=[], errors=[error])
    try:
        function_registry = _functions_option(functions)
    except MF2Error as error:
        return PartsResult(parts=[], errors=[error])
    try:
        argument_values = _arguments_option(arguments)
    except MF2Error as error:
        return PartsResult(parts=[], errors=[error])
    context = _FormatContext(
        argument_values,
        normalized_locale,
        function_registry,
        fallback=True,
        on_missing_argument=on_missing_argument,
        on_format_error=on_format_error,
    )
    context.apply_declarations(_model_list_field(model_data, "declarations"))

    message_type = model_data.get("type")
    if message_type == "message":
        parts = context.format_pattern_to_parts(_model_list_field(model_data, "pattern"))
    elif message_type == "select":
        parts = context.format_select_to_parts(
            _model_list_field(model_data, "selectors"),
            _model_list_field(model_data, "variants"),
        )
    else:
        raise MF2Error("unsupported-message-type", f"Unsupported message type: {message_type}")
    return PartsResult(parts=parts, errors=context.errors)


def _locale_option(locale: Any) -> str:
    try:
        value = str(locale if locale is not None else "en").strip()
    except Exception as error:
        raise MF2Error("bad-option", _safe_exception_message(error)) from error
    return value or "en"


def _functions_option(functions: Any) -> FunctionRegistry:
    if functions is None:
        return _DEFAULT_FUNCTION_REGISTRY
    if isinstance(functions, FunctionRegistry):
        return functions
    raise MF2Error("bad-option", "functions must be a FunctionRegistry.")


def _arguments_option(arguments: Any) -> dict[str, Any]:
    try:
        return dict(arguments or {})
    except Exception as error:
        raise MF2Error("bad-option", _safe_exception_message(error)) from error


def _model_data(model: Any) -> dict[str, Any]:
    if isinstance(model, dict):
        return cast(dict[str, Any], model)
    raise MF2Error("unsupported-message-type", "Unsupported message type: ")


def _model_list_field(model: Any, name: str) -> list[Any]:
    if not isinstance(model, dict):
        return []
    value = model.get(name)
    if value is None:
        return []
    if isinstance(value, list):
        return value
    raise MF2Error("bad-option", f"{name} must be an array.")


def _model_object_entries(values: list[Any], name: str) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for value in values:
        if not isinstance(value, dict):
            raise MF2Error("bad-option", f"{name} entries must be objects.")
        entries.append(cast(dict[str, Any], value))
    return entries


def _validate_model(model: dict[str, Any]) -> None:
    declarations = _model_object_entries(_model_list_field(model, "declarations"), "declarations")
    _validate_declarations(declarations)
    if model.get("type") == "message":
        _validate_pattern(_model_list_field(model, "pattern"))
    elif model.get("type") == "select":
        _validate_selector_annotations(
            declarations,
            _model_object_entries(_model_list_field(model, "selectors"), "selectors"),
        )
        for variant in _model_object_entries(_model_list_field(model, "variants"), "variants"):
            _model_object_entries(_model_list_field(variant, "keys"), "variant keys")
            _validate_pattern(_model_list_field(variant, "value"))


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
        if not isinstance(part, (str, dict)):
            raise MF2Error("unsupported-pattern-part", "Unsupported pattern part: ")
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
        self.on_missing_argument = _recovery_handler_option(on_missing_argument)
        self.on_format_error = _recovery_handler_option(on_format_error)

    def apply_declarations(self, declarations: list[dict[str, Any]]) -> None:
        self.selector_annotations = _selector_annotations(declarations)
        for declaration in declarations:
            if declaration.get("type") == "input":
                value = declaration.get("value", {})
                function_ref = value.get("function")
                if (
                    function_ref is None
                    or not self.functions.has_formatter(function_ref)
                    or not self.functions.has_selector(function_ref)
                    or declaration["name"] not in self.values
                ):
                    continue
                rendered = self._format_expression_output(value)
                if rendered.had_error:
                    self.failed_locals.add(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.raw_value
                    self.sources[declaration["name"]] = rendered.source
            elif declaration.get("type") == "local":
                rendered = self._format_expression_output(declaration["value"])
                if rendered.had_error:
                    self.failed_locals.add(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.raw_value
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
            if not self._has_value(name):
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
                    if annotation is not None and (
                        name in self.failed_locals
                        or self.functions.has_selector(annotation.function)
                    ):
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
            try:
                rendered = _render_value(value)
                selection_key = self._selection_key(name, value)
            except MF2Error as error:
                if not self.fallback:
                    raise
                self.errors.append(_fallback_error(error))
                if annotation is not None:
                    self.errors.append(
                        MF2Error("bad-selector", "Selector operand is not available.")
                    )
                selector_values.append(
                    _SelectorValue(
                        rendered="",
                        raw_value="",
                        normalized_rendered=(
                            _normalize_string_key("") if self._string_select(name) else None
                        ),
                        exact_match=False,
                        selection_key=None,
                        function=None,
                        source=None,
                    )
                )
                continue
            normalized_rendered = (
                _normalize_string_key(rendered) if self._string_select(name) else None
            )
            self._record_selector_resolution_errors(annotation)
            selector_values.append(
                _SelectorValue(
                    rendered=rendered,
                    raw_value=value,
                    normalized_rendered=normalized_rendered,
                    exact_match=self._exact_match(name),
                    selection_key=selection_key,
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
            if not isinstance(part, dict):
                raise MF2Error("unsupported-pattern-part", "Unsupported pattern part: ")
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
                    if rendered.direction is not None:
                        expression_part["direction"] = rendered.direction
                    parts.append(expression_part)
            elif part_type == "markup":
                if part.get("options", {}).get("u:dir") is not None:
                    error = MF2Error("bad-option", "u:dir is not valid on markup.")
                    if not self.fallback:
                        raise error
                    self.errors.append(error)
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
            if not self._has_value(name):
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
                try:
                    value = _render_value(raw_value)
                except MF2Error as error:
                    if not self.fallback:
                        raise
                    recoverable = _fallback_error(error)
                    self.errors.append(recoverable)
                    fallback_source = _fallback_source(expression)
                    return _ExpressionOutput(
                        value=self._recover_format_error(
                            expression,
                            fallback_source,
                            recoverable,
                        ),
                        had_error=True,
                        fallback_source=fallback_source,
                    )
                source = self.sources.get(name)
        else:
            raise MF2Error("unsupported-expression-arg", f"Unsupported expression arg: {arg}")

        if had_error:
            return _ExpressionOutput(
                value=value,
                raw_value=value,
                had_error=True,
                fallback_source=_fallback_source(expression),
            )

        function = expression.get("function")
        if function is None:
            try:
                value = _render_primitive_value(raw_value, self.locale)
                direction = _bidi_direction_from_source(source)
            except MF2Error as error:
                if not self.fallback:
                    raise
                recoverable = _fallback_error(error)
                self.errors.append(recoverable)
                fallback_source = _fallback_source(expression)
                return _ExpressionOutput(
                    value=self._recover_format_error(
                        expression,
                        fallback_source,
                        recoverable,
                    ),
                    had_error=True,
                    fallback_source=fallback_source,
                )
            return _ExpressionOutput(
                value=value,
                raw_value=raw_value,
                had_error=False,
                source=source,
                direction=direction,
            )
        try:
            source_value = value if source is None else source.value
            direction = _bidi_direction_for_function(function, source)
            formatted = self.functions.format(
                FunctionCall(
                    value=value,
                    raw_value=raw_value,
                    function=function,
                    locale=self.locale,
                    _option_resolver=lambda name, default: self._option_value(function, name, default),
                    inherited_source=source,
                )
            )
            return _ExpressionOutput(
                value=formatted,
                raw_value=formatted,
                had_error=False,
                source=FunctionSource(
                    source_value,
                    function,
                    source,
                    lambda name, default: self._option_value(function, name, default),
                ),
                direction=direction,
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
            return _render_option_value(self._argument(option["name"]))
        return default

    def _has_value(self, name: str) -> bool:
        return name not in self.failed_locals and name in self.values

    def _argument(self, name: str) -> Any:
        if not self._has_value(name):
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
        if selector.exact_match and _numeric_literal_key_matches_source(value, selector):
            return 3
        if selector.exact_match and not _is_numeric_function(selector.function) and _literal_key_matches(value, selector):
            return 2
        if value == selector.selection_key:
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

    def _record_selector_resolution_errors(self, annotation: _SelectorAnnotation | None) -> None:
        if annotation is None or annotation.function.get("name") != "currency":
            return
        error = MF2Error("bad-selector", "Currency selector is not supported.")
        if not self.fallback:
            raise error
        self.errors.append(error)


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
    raw_value: Any = ""
    source: FunctionSource | None = None
    fallback_source: str | None = None
    direction: str | None = None


def _default_recovery(context: MF2RecoveryContext) -> str:
    return context.fallback_value


def _recovery_handler_option(handler: Any) -> MF2RecoveryHandler:
    return handler if callable(handler) else _default_recovery


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


def _numeric_literal_key_matches_source(value: str, selector: _SelectorValue) -> bool:
    source_key = _preferred_numeric_source_key(selector)
    return source_key is not None and value == source_key and _is_decimal_literal(value)


def _preferred_numeric_source_key(selector: _SelectorValue) -> str | None:
    function_name = (selector.function or {}).get("name")
    if function_name not in {"number", "percent"}:
        return None
    source_value = _numeric_source_value(selector.source, function_name)
    if source_value is None:
        return None
    operand = _parse_source_decimal(source_value)
    if operand is None:
        return None
    if function_name == "percent":
        return _render_source_decimal(
            _SourceDecimal(
                negative=operand.negative,
                digits=operand.digits,
                scale=operand.scale - 2,
                has_exponent=operand.has_exponent,
            ),
            trim_fraction_zeros=False,
        )
    if operand.has_exponent:
        return _render_source_decimal(operand, trim_fraction_zeros=True)
    return source_value


def _numeric_source_value(source: FunctionSource | None, function_name: str) -> str | None:
    while source is not None:
        if source.function.get("name") == function_name:
            return source.value
        source = source.inherited_source
    return None


@dataclass(frozen=True)
class _SourceDecimal:
    negative: bool
    digits: str
    scale: int
    has_exponent: bool


def _parse_source_decimal(value: str) -> _SourceDecimal | None:
    match = _SOURCE_DECIMAL_RE.fullmatch(value)
    if match is None:
        return None
    exponent = _parse_source_exponent(match.group("exponent") or "")
    if exponent is None:
        return None
    fraction = match.group("fraction") or ""
    digits = f"{match.group('integer')}{fraction}".lstrip("0") or "0"
    return _SourceDecimal(
        negative=match.group("sign") == "-" and digits != "0",
        digits=digits,
        scale=len(fraction) - exponent,
        has_exponent=match.group("exponent") is not None,
    )


def _parse_source_exponent(value: str) -> int | None:
    if value == "":
        return 0
    negative = value.startswith("-")
    unsigned = value[1:] if negative or value.startswith("+") else value
    digits = unsigned.lstrip("0") or "0"
    if len(digits) > 7:
        return None
    parsed = int(digits)
    if parsed > _MAX_SOURCE_DECIMAL_EXPONENT:
        return None
    return -parsed if negative else parsed


def _render_source_decimal(
    operand: _SourceDecimal, *, trim_fraction_zeros: bool
) -> str | None:
    extra_length = (
        operand.scale - len(operand.digits)
        if operand.scale > len(operand.digits)
        else max(-operand.scale, 0)
    )
    if len(operand.digits) + extra_length + 2 > _MAX_SOURCE_DECIMAL_KEY_LENGTH:
        return None
    if operand.scale <= 0:
        text = f"{operand.digits}{'0' * -operand.scale}"
    elif operand.scale >= len(operand.digits):
        text = f"0.{'0' * (operand.scale - len(operand.digits))}{operand.digits}"
    else:
        split = len(operand.digits) - operand.scale
        text = f"{operand.digits[:split]}.{operand.digits[split:]}"
    if trim_fraction_zeros and "." in text:
        text = text.rstrip("0").rstrip(".")
    return f"-{text}" if operand.negative else text


def _is_decimal_literal(value: str) -> bool:
    if _DECIMAL_LITERAL_RE.fullmatch(value) is None:
        return False
    try:
        return Decimal(value).is_finite()
    except InvalidOperation:
        return False


def _is_numeric_function(function: dict[str, Any] | None) -> bool:
    return function is not None and function.get("name") in {"number", "integer", "percent", "offset"}


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


def _safe_exception_message(error: BaseException) -> str:
    try:
        message = str(error)
    except Exception:
        return "Formatting failed."
    return message or "Formatting failed."


def _fallback_source(expression: dict[str, Any]) -> str:
    arg = expression.get("arg")
    if arg is not None:
        return _expression_arg_source(arg)
    function = expression.get("function")
    if function is not None:
        return _function_name_source(function)
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


def _function_name_source(function: dict[str, Any]) -> str:
    return f":{function.get('name', '')}"


def _quote_literal_source(value: str) -> str:
    return "|" + value.replace("\\", "\\\\").replace("|", "\\|") + "|"


def _render_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    try:
        return str(value)
    except Exception as error:
        raise MF2Error("bad-operand", "Value could not be rendered.") from error


def _render_primitive_value(value: Any, locale: str) -> str:
    if isinstance(value, bool):
        return _render_value(value)
    if isinstance(value, (int, float, Decimal)):
        return format_number_core(value, locale=locale)
    return _render_value(value)


def _render_option_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    try:
        return str(value)
    except Exception as error:
        raise MF2Error("bad-option", "Function option value is not available.") from error


def _bidi_direction_for_function(
    function: dict[str, Any],
    source: FunctionSource | None,
) -> str | None:
    value = _function_option_literal(function, "u:dir", None)
    if value is not None:
        return _parse_bidi_direction(value)
    return _bidi_direction_from_source(source)


def _bidi_direction_from_source(source: FunctionSource | None) -> str | None:
    if source is None:
        return None
    value = _function_option_literal(source.function, "u:dir", None)
    if value is not None:
        return _parse_bidi_direction(value)
    return _bidi_direction_from_source(source.inherited_source)


def _function_option_literal(
    function: dict[str, Any],
    name: str,
    default: str | None,
) -> str | None:
    option = function.get("options", {}).get(name)
    if option is None:
        return default
    return str(option.get("value", "")) if option.get("type") == "literal" else default


def _parse_bidi_direction(value: str) -> str:
    if value in {"auto", "ltr", "rtl"}:
        return value
    raise MF2Error("bad-option", "u:dir option must be auto, ltr, or rtl.")


def _parts_to_string(parts: list[MF2FormattedPart], bidi_isolation: str = "none") -> str:
    output = []
    for part in parts:
        part_type = part.get("type")
        if part_type == "text":
            output.append(part.get("value", ""))
        elif part_type == "fallback":
            output.append(part["value"] if "value" in part else ("{" + part.get("source", "") + "}"))
        elif part_type == "expression":
            output.append(
                _isolate_expression(
                    part.get("value", ""),
                    bidi_isolation,
                    part.get("direction"),
                )
            )
    return "".join(output)


def _isolate_expression(value: str, bidi_isolation: str, direction: str | None = None) -> str:
    if bidi_isolation == "default":
        return f"{_bidi_marker(direction)}{value}\u2069"
    return value


def _bidi_marker(direction: str | None) -> str:
    if direction == "ltr":
        return "\u2066"
    if direction == "rtl":
        return "\u2067"
    return "\u2068"
