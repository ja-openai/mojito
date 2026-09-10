from __future__ import annotations

from dataclasses import dataclass
from copy import deepcopy
from decimal import Decimal, DecimalException, localcontext
import unicodedata
from typing import Any, Callable, cast

from .errors import MF2Error
from ._locale_direction import locale_is_ltr
from ._portable_functions import (
    _MAX_DECIMAL_INTEGER_MAGNITUDE,
    _MAX_DECIMAL_TEXT_LENGTH,
    _decimal_precision,
    _numeric_plural_operand,
    _validate_decimal_operand,
)
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
from ._portable_functions import (
    _inherited_exact_numeric_source,
    _iter_source_chain,
    _numeric_select_uses_variable,
    _numeric_selection_operand,
    _parse_non_negative_integer_option,
    _parse_source_decimal,
    _source_numeric_option_value,
)


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
    result, isolation = _format_parts_with_metadata(
        model,
        arguments,
        locale,
        functions,
        on_missing_argument,
        on_format_error,
    )
    return FormatResult(
        value=_parts_to_string(result.parts, bidi_isolation, isolation),
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
    return _format_parts_with_metadata(model, arguments, locale, functions, on_missing_argument, on_format_error)[0]


def _format_parts_with_metadata(
    model: MF2MessageModel,
    arguments: MF2Arguments | None = None,
    locale: str = "en",
    functions: FunctionRegistry | None = None,
    on_missing_argument: MF2RecoveryHandler | None = None,
    on_format_error: MF2RecoveryHandler | None = None,
) -> tuple[PartsResult, list[bool]]:
    model_data = cast(dict[str, Any], model)
    model_data = _validate_model(model_data)
    context = _FormatContext(
        {unicodedata.normalize("NFC", name): value for name, value in (arguments or {}).items()},
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
    return PartsResult(parts=parts, errors=context.errors), context.expression_isolation


def _validate_model(model: dict[str, Any]) -> dict[str, Any]:
    _validate_model_shape(model)
    model = _normalize_model_bindings(model)
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
    return model


class _ReadOnlyFunctionDict(dict):
    """A detached read-only callback annotation; compatible with dict readers."""
    def _immutable(self, *args: Any, **kwargs: Any) -> Any:
        raise TypeError("MF2 callback function annotations are read-only snapshots.")

    __setitem__ = __delitem__ = clear = pop = popitem = setdefault = update = __ior__ = _immutable

    def __deepcopy__(self, memo: dict[int, Any]) -> dict[str, Any]:
        return {key: deepcopy(value, memo) for key, value in self.items()}


def _normalize_model_bindings(model: dict[str, Any]) -> dict[str, Any]:
    """Use canonical variable identity without changing the caller's catalog."""
    def arg(value: dict[str, Any]) -> dict[str, Any]:
        if value.get("type") == "variable":
            return {**value, "name": unicodedata.normalize("NFC", value["name"])}
        return value

    def part(value: Any) -> Any:
        if isinstance(value, str):
            return value
        result = dict(value)
        if result.get("type") == "expression" and "arg" in result:
            result["arg"] = arg(result["arg"])
        if result.get("type") == "expression" and "function" in result:
            result["function"] = part(result["function"])
        if result.get("type") in ("function", "markup") and "options" in result:
            result["options"] = {name: arg(option) for name, option in result["options"].items()}
        if result.get("type") == "function":
            result = deepcopy(result)
            if "options" in result:
                result["options"] = _ReadOnlyFunctionDict({name: _ReadOnlyFunctionDict(option) for name, option in result["options"].items()})
            return _ReadOnlyFunctionDict(result)
        return result

    normalized = {**model, "declarations": [
        {**item, "name": unicodedata.normalize("NFC", item["name"]), "value": part(item["value"])}
        for item in model["declarations"]
    ]}
    if model["type"] == "message":
        normalized["pattern"] = [part(item) for item in model["pattern"]]
    else:
        normalized["selectors"] = [arg(item) for item in model["selectors"]]
        normalized["variants"] = [{**item, "value": [part(value) for value in item["value"]]} for item in model["variants"]]
    return normalized


def _require_model(condition: bool) -> None:
    if not condition:
        raise MF2Error("invalid-model", "Message model does not match the MF2 data model schema.")


def _validate_arg_shape(arg: Any, allowed: tuple[str, ...] = ("literal", "variable")) -> None:
    _require_model(isinstance(arg, dict))
    kind = arg.get("type")
    _require_model(kind in allowed)
    _require_model(isinstance(arg.get("value" if kind == "literal" else "name"), str))


def _validate_metadata_shape(node: dict[str, Any], fields: tuple[str, ...] = ("options", "attributes")) -> None:
    for field in fields:
        if field not in node:
            continue
        values = node[field]
        _require_model(isinstance(values, dict))
        for name, value in values.items():
            _require_model(isinstance(name, str))
            if field == "attributes" and value is True:
                continue
            _validate_arg_shape(value, ("literal",) if field == "attributes" else ("literal", "variable"))


def _validate_expression_shape(expression: Any) -> None:
    _require_model(isinstance(expression, dict))
    _require_model(expression.get("type") == "expression")
    _require_model("arg" in expression or "function" in expression)
    if "arg" in expression:
        _validate_arg_shape(expression["arg"])
    if "function" in expression:
        function = expression["function"]
        _require_model(isinstance(function, dict))
        _require_model(function.get("type") == "function" and isinstance(function.get("name"), str))
        _validate_metadata_shape(function, ("options",))
    _validate_metadata_shape(expression, ("attributes",))


def _validate_pattern_shape(pattern: Any) -> None:
    _require_model(isinstance(pattern, list))
    for part in pattern:
        if isinstance(part, str):
            continue
        _require_model(isinstance(part, dict))
        if part.get("type") == "markup":
            _require_model(isinstance(part.get("name"), str))
            _validate_markup(part)
            _validate_metadata_shape(part)
        else:
            _validate_expression_shape(part)


def _validate_model_shape(model: Any) -> None:
    # Validate only the standardized fields; extension metadata remains allowed.
    # The schema has bounded nesting, so this does not recurse through user data.
    _require_model(isinstance(model, dict))
    _require_model(model.get("type") in ("message", "select"))
    declarations = model.get("declarations")
    _require_model(isinstance(declarations, list))
    for declaration in declarations:
        _require_model(isinstance(declaration, dict))
        _require_model(declaration.get("type") in ("input", "local"))
        _require_model(isinstance(declaration.get("name"), str))
        _validate_expression_shape(declaration.get("value"))
    if model["type"] == "message":
        _validate_pattern_shape(model.get("pattern"))
        return
    selectors, variants = model.get("selectors"), model.get("variants")
    _require_model(isinstance(selectors, list) and isinstance(variants, list))
    for selector in selectors:
        _validate_arg_shape(selector, ("variable",))
    for variant in variants:
        _require_model(isinstance(variant, dict))
        keys = variant.get("keys")
        _require_model(isinstance(keys, list))
        for key in keys:
            _require_model(isinstance(key, dict))
            if key.get("type") == "*":
                _require_model("value" not in key or isinstance(key["value"], str))
            else:
                _validate_arg_shape(key, ("literal",))
        _validate_pattern_shape(variant.get("value"))


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
        self.failed_bindings: set[str] = set()
        self.failed_selectors: set[int] = set()
        self.errors: list[MF2Error] = []
        self.expression_isolation: list[bool] = []
        self.known_ltr_locale = locale_is_ltr(locale) is True
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
                    self._fail_binding(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.value
                    self.sources[declaration["name"]] = rendered.source
            elif declaration.get("type") == "local":
                rendered = self._format_expression_output(declaration["value"])
                if rendered.had_error:
                    self._fail_binding(declaration["name"])
                else:
                    self.values[declaration["name"]] = rendered.value
                    self.sources[declaration["name"]] = rendered.source

    def _fail_binding(self, name: str) -> None:
        self.failed_bindings.add(name)
        self.values.pop(name, None)
        self.sources.pop(name, None)

    def _has_value(self, name: str) -> bool:
        return name in self.values

    def format_select_to_parts(
        self,
        selectors: list[dict[str, Any]],
        variants: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        self.failed_selectors.clear()
        selector_values = []
        for selector in selectors:
            name = selector["name"]
            annotation = self.selector_annotations.get(name)
            if not self._has_value(name):
                if self.fallback:
                    if name not in self.failed_bindings:
                        self.errors.append(_unresolved_variable(name))
                    selector_value = _SelectorValue(
                        rendered="",
                        raw_value="",
                        normalized_rendered=(
                            _normalize_string_key("")
                            if self._string_select(name)
                            else None
                        ),
                        exact_match=False,
                        selection_key=None,
                        function=annotation.function if annotation else None,
                        source=None,
                        available=False,
                    )
                    self.failed_selectors.add(id(selector_value))
                    selector_values.append(selector_value)
                    if annotation is not None and not annotation.is_string:
                        if name not in self.failed_bindings and self.functions.has_selector(annotation.function):
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
            self._record_selector_resolution_errors(annotation)
            normalized_rendered = (
                _normalize_string_key(rendered) if self._string_select(name) else None
            )
            selector_values.append(
                _SelectorValue(
                    rendered=rendered,
                    raw_value=value,
                    normalized_rendered=normalized_rendered,
                    exact_match=self._exact_match(name),
                    selection_key=self._selection_key(
                        name, value, self.sources.get(name)
                    ),
                    function=annotation.function if annotation else None,
                    source=self.sources.get(name),
                    available=True,
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
                    self.expression_isolation.append(rendered.force_isolation or not (
                        rendered.resolved_direction == "ltr" and self.known_ltr_locale
                    ))
                    expression_part: MF2ExpressionPart = {
                        "type": "expression",
                        "value": rendered.value,
                    }
                    if attributes := part.get("attributes"):
                        expression_part["attributes"] = deepcopy(attributes)
                    if rendered.direction is not None:
                        expression_part["direction"] = rendered.direction
                    parts.append(expression_part)
            elif part_type == "markup":
                if "u:dir" in part.get("options", {}):
                    error = MF2Error(
                        "bad-option", "u:dir is not valid on markup."
                    )
                    if not self.fallback:
                        raise error
                    self.errors.append(error)
                markup_part: MF2MarkupPart = {
                    "type": "markup",
                    "kind": part.get("kind", ""),
                    "name": part.get("name", ""),
                }
                if options := part.get("options"):
                    markup_part["options"] = deepcopy(options)
                if attributes := part.get("attributes"):
                    markup_part["attributes"] = deepcopy(attributes)
                parts.append(markup_part)
            else:
                raise MF2Error("unsupported-pattern-part", f"Unsupported pattern part: {part_type}")
        return parts

    def format_expression(self, expression: dict[str, Any]) -> str:
        return self._format_expression_output(expression).value

    def _format_expression_output(self, expression: dict[str, Any]) -> "_ExpressionOutput":
        had_error = False
        source: FunctionSource | None = None
        function = expression.get("function")
        arg = expression.get("arg")
        if arg is None:
            value = ""
            raw_value = ""
        elif arg.get("type") == "literal":
            raw_value = arg.get("value", "")
            value = ""
        elif arg.get("type") == "variable":
            name = arg["name"]
            if not self._has_value(name):
                if self.fallback:
                    had_error = True
                    error = _unresolved_variable(name)
                    if name not in self.failed_bindings:
                        self.errors.append(error)
                    if function is not None:
                        self.errors.append(
                            MF2Error("bad-operand", "Function operand is not available.")
                            if self.functions.has_formatter(function)
                            else MF2Error("unknown-function", "Function is not defined by this registry.")
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
                value = ""
                source = self.sources.get(name)
        else:
            raise MF2Error("unsupported-expression-arg", f"Unsupported expression arg: {arg}")

        if had_error:
            return _ExpressionOutput(
                value=value,
                had_error=True,
                fallback_source=_fallback_source(expression),
            )

        if arg is not None:
            try:
                value = _render_value_or_error(
                    raw_value,
                    "bad-operand",
                    "Expression operand exceeds the supported rendering range.",
                )
            except MF2Error as error:
                return self._format_error_output(expression, error)

        if function is None and source is None and isinstance(raw_value, (int, float, Decimal)) and not isinstance(raw_value, bool):
            # Native numbers retain their type through an unannotated variable.
            # Let the selected registry supply its normal localized display.
            function = _ReadOnlyFunctionDict({"type": "function", "name": "number"})
        if function is None:
            direction, resolved_direction, force_isolation = _source_direction(source)
            return _ExpressionOutput(
                value=value,
                had_error=False,
                source=source,
                direction=direction,
                resolved_direction=resolved_direction,
                force_isolation=force_isolation,
            )
        self._record_function_resolution_errors(function, source)
        try:
            direction, resolved_direction, force_isolation = self._resolve_direction(function, source)
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
                    # A later input declaration can transform a variable used
                    # by an earlier option resolver. Cache only literal-only
                    # histories so those dynamic callbacks keep their behavior.
                    _memo={} if (source is None or source._memo is not None) and all(
                        option.get("type") == "literal" for option in function.get("options", {}).values()
                    ) else None,
                    _direction_info=(direction, resolved_direction, force_isolation),
                ),
                direction=direction,
                resolved_direction=resolved_direction,
                force_isolation=force_isolation,
            )
        except MF2Error as error:
            return self._format_error_output(expression, error)

    def _format_error_output(
        self, expression: dict[str, Any], error: MF2Error
    ) -> "_ExpressionOutput":
        if not self.fallback:
            raise error
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
        if option_name == "u:dir":
            return default  # Resolved by the message context before the handler.
        option = function.get("options", {}).get(option_name)
        if option is None:
            return default
        if option.get("type") == "literal":
            return _render_value_or_error(
                option.get("value", ""),
                "bad-option",
                "Function option exceeds the supported rendering range.",
            )
        if option.get("type") == "variable":
            return _render_value_or_error(
                self._argument(option["name"]),
                "bad-option",
                "Function option exceeds the supported rendering range.",
            )
        return default

    def _resolve_direction(self, function: dict[str, Any], source: FunctionSource | None) -> tuple[str | None, str | None, bool]:
        public_direction, resolved_direction, _ = _source_direction(source)
        force = False  # A new annotation defaults to u:dir=inherit.
        option = function.get("options", {}).get("u:dir")
        if option is not None:
            try:
                value = option.get("value") if option.get("type") == "literal" else self._argument(option["name"])
                if value not in ("ltr", "rtl", "auto", "inherit"):
                    raise MF2Error("bad-option", "u:dir option must be auto, ltr, rtl, or inherit.")
                if value != "inherit":
                    public_direction = value
                    resolved_direction = None if value == "auto" else value
                    force = True
            except MF2Error as error:
                if not self.fallback:
                    raise
                if error.code != "bad-option":
                    self.errors.append(_unresolved_variable(option["name"]) if error.code == "missing-argument" else _fallback_error(error))
                self.errors.append(MF2Error("bad-option", "Invalid u:dir option was ignored."))
        if public_direction is None and resolved_direction is None and self.known_ltr_locale and function.get("name") in self.functions._production_numeric_formatters:
            resolved_direction = "ltr"
        return public_direction, resolved_direction, force

    def _argument(self, name: str) -> Any:
        if not self._has_value(name):
            raise MF2Error("missing-argument", f"Missing argument ${name}.")
        return self.values[name]

    def _record_function_resolution_errors(
        self,
        function: dict[str, Any],
        source: FunctionSource | None,
    ) -> None:
        annotation = _SelectorAnnotation.from_function(function)
        if not annotation.is_numeric:
            return
        if not _numeric_select_uses_variable(function) and not (
            _inherited_exact_numeric_source(
                source, str(function.get("name", ""))
            )
        ):
            return
        error = MF2Error(
            "bad-option",
            "Numeric select option is not valid in this context.",
        )
        if not self.fallback:
            raise error
        self.errors.append(error)

    def _record_selector_resolution_errors(
        self, annotation: "_SelectorAnnotation | None"
    ) -> None:
        if annotation is None or annotation.function_name != "currency":
            return
        error = MF2Error(
            "bad-selector", "Currency selector is not supported."
        )
        if not self.fallback:
            raise error
        self.errors.append(error)

    def _exact_match(self, selector_name: str) -> bool:
        annotation = self.selector_annotations.get(selector_name)
        return True if annotation is None else annotation.exact_match

    def _selection_key(
        self,
        selector_name: str,
        value: Any,
        source: FunctionSource | None,
    ) -> str | None:
        annotation = self.selector_annotations.get(selector_name)
        if annotation is None or not annotation.is_numeric:
            return None
        operand = self._numeric_selection_operand(annotation, value, source)
        return select_plural_category(
            self.locale, operand, annotation.number_select
        )

    def _numeric_selection_operand(
        self,
        annotation: "_SelectorAnnotation",
        value: Any,
        source: FunctionSource | None,
    ) -> str | None:
        if annotation.number_select == "exact":
            return None
        source_value = (
            _parse_source_decimal(source)
            if annotation.function_name != "offset"
            else None
        )
        if source_value is None:
            source_value = _render_value(value)

        def option_value(name: str, default: str | None = None) -> str | None:
            current = self._option_value(annotation.function, name, None)
            if current is not None:
                return current
            return _source_numeric_option_value(
                source,
                name,
                default,
                target_function=annotation.function_name,
            )

        minimum = option_value("minimumFractionDigits", "0") or "0"
        maximum = option_value("maximumFractionDigits")
        try:
            minimum_digits = _parse_non_negative_integer_option(
                minimum,
                "minimumFractionDigits option must be a non-negative integer.",
            )
            maximum_digits = (
                None
                if maximum is None
                else _parse_non_negative_integer_option(
                    maximum,
                    "maximumFractionDigits option must be a non-negative integer.",
                )
            )
        except MF2Error:
            return None
        return _numeric_selection_operand(
            source_value,
            annotation.function_name,
            minimum_digits,
            maximum_digits,
        )

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
        if not selector.available:
            return None
        value = str(key.get("value", ""))
        if (selector.exact_match and _literal_key_matches(value, selector)) or value == selector.selection_key:
            return 1
        if selector.function is None or id(selector) in self.failed_selectors:
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
            if error.code != "bad-variant-key":
                self.failed_selectors.add(id(selector))
            self.errors.append(_fallback_error(error))
            if error.code not in {"bad-selector", "bad-variant-key"}:
                self.errors.append(
                    MF2Error("bad-selector", "Selector failed to match.")
                )
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
    available: bool


@dataclass(frozen=True)
class _ExpressionOutput:
    value: str
    had_error: bool
    source: FunctionSource | None = None
    fallback_source: str | None = None
    direction: str | None = None
    resolved_direction: str | None = None
    force_isolation: bool = False


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


def _percent_plural_operand(value: Any) -> str | None:
    rendered = _render_value(value)
    if len(rendered) > _MAX_DECIMAL_TEXT_LENGTH:
        return None
    has_percent_suffix = rendered.endswith("%")
    numeric = rendered[:-1].strip() if has_percent_suffix else rendered
    try:
        decimal = Decimal(numeric)
        _validate_decimal_operand(
            decimal, "Percent selector requires a bounded numeric operand."
        )
        if has_percent_suffix:
            return numeric
        with localcontext() as context:
            context.prec = _decimal_precision(decimal) + 2
            decimal *= Decimal(100)
        _validate_decimal_operand(
            decimal, "Percent selector requires a bounded numeric operand."
        )
        return str(decimal)
    except (DecimalException, MF2Error, ValueError):
        return None


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
        return f":{function.get('name', '')}"
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


def _render_value_or_error(value: Any, code: str, message: str) -> str:
    if (
        isinstance(value, int)
        and not isinstance(value, bool)
        and (
            value >= _MAX_DECIMAL_INTEGER_MAGNITUDE
            or value <= -_MAX_DECIMAL_INTEGER_MAGNITUDE
        )
    ):
        raise MF2Error(code, message)
    try:
        return _render_value(value)
    except (OverflowError, ValueError) as error:
        raise MF2Error(code, message) from error


def _parts_to_string(parts: list[MF2FormattedPart], bidi_isolation: str = "none", expression_isolation: list[bool] | None = None) -> str:
    output = []
    expression_index = 0
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
                    bidi_isolation if expression_isolation is None or expression_isolation[expression_index] else "none",
                    part.get("direction"),
                )
            )
            expression_index += 1
    return "".join(output)


def _isolate_expression(
    value: str, bidi_isolation: str, direction: str | None = None
) -> str:
    if bidi_isolation == "default":
        marker = {"ltr": "\u2066", "rtl": "\u2067"}.get(direction, "\u2068")
        return f"{marker}{value}\u2069"
    return value


def _source_direction(source: FunctionSource | None) -> tuple[str | None, str | None, bool]:
    return source._direction_info if source is not None and source._direction_info is not None else (None, None, False)
