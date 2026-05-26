from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Literal, NotRequired, TypeAlias, TypedDict


MF2ArgumentValue: TypeAlias = Any
MF2Arguments: TypeAlias = Mapping[str, MF2ArgumentValue]


class MF2LiteralArgument(TypedDict):
    type: Literal["literal"]
    value: str


class MF2VariableArgument(TypedDict):
    type: Literal["variable"]
    name: str


MF2ExpressionArgument: TypeAlias = MF2LiteralArgument | MF2VariableArgument


class MF2FunctionAnnotation(TypedDict):
    type: Literal["function"]
    name: str
    options: NotRequired[dict[str, MF2ExpressionArgument]]


class MF2AttributeValue(TypedDict):
    type: Literal["literal"]
    value: str


class MF2Expression(TypedDict):
    type: Literal["expression"]
    arg: NotRequired[MF2ExpressionArgument]
    function: NotRequired[MF2FunctionAnnotation]
    attributes: NotRequired[dict[str, MF2AttributeValue]]


class MF2Markup(TypedDict):
    type: Literal["markup"]
    kind: Literal["open", "standalone", "close"]
    name: str
    options: NotRequired[dict[str, MF2ExpressionArgument]]
    attributes: NotRequired[dict[str, MF2AttributeValue]]


MF2PatternPart: TypeAlias = str | MF2Expression | MF2Markup
MF2Pattern: TypeAlias = list[MF2PatternPart]


class MF2InputDeclaration(TypedDict):
    type: Literal["input"]
    name: str
    value: MF2Expression


class MF2LocalDeclaration(TypedDict):
    type: Literal["local"]
    name: str
    value: MF2Expression


MF2Declaration: TypeAlias = MF2InputDeclaration | MF2LocalDeclaration


class MF2Selector(TypedDict):
    type: Literal["variable"]
    name: str


class MF2LiteralKey(TypedDict):
    type: Literal["literal"]
    value: str


class MF2CatchallKey(TypedDict):
    type: Literal["*"]


MF2VariantKey: TypeAlias = MF2LiteralKey | MF2CatchallKey


class MF2Variant(TypedDict):
    keys: list[MF2VariantKey]
    value: MF2Pattern


class MF2SimpleMessage(TypedDict):
    type: Literal["message"]
    declarations: list[MF2Declaration]
    pattern: MF2Pattern


class MF2SelectMessage(TypedDict):
    type: Literal["select"]
    declarations: list[MF2Declaration]
    selectors: list[MF2Selector]
    variants: list[MF2Variant]


MF2MessageModel: TypeAlias = MF2SimpleMessage | MF2SelectMessage


class MF2TextPart(TypedDict):
    type: Literal["text"]
    value: str


class MF2ExpressionPart(TypedDict):
    type: Literal["expression"]
    value: str
    attributes: NotRequired[dict[str, MF2AttributeValue]]


class MF2FallbackPart(TypedDict):
    type: Literal["fallback"]
    source: str
    value: NotRequired[str]


class MF2MarkupPart(TypedDict):
    type: Literal["markup"]
    kind: Literal["open", "standalone", "close"] | str
    name: str
    options: NotRequired[dict[str, MF2ExpressionArgument]]
    attributes: NotRequired[dict[str, MF2AttributeValue]]


MF2FormattedPart: TypeAlias = (
    MF2TextPart | MF2ExpressionPart | MF2FallbackPart | MF2MarkupPart
)


__all__ = [
    "MF2ArgumentValue",
    "MF2Arguments",
    "MF2AttributeValue",
    "MF2CatchallKey",
    "MF2Declaration",
    "MF2Expression",
    "MF2ExpressionArgument",
    "MF2ExpressionPart",
    "MF2FallbackPart",
    "MF2FormattedPart",
    "MF2FunctionAnnotation",
    "MF2InputDeclaration",
    "MF2LiteralArgument",
    "MF2LiteralKey",
    "MF2LocalDeclaration",
    "MF2Markup",
    "MF2MarkupPart",
    "MF2MessageModel",
    "MF2Pattern",
    "MF2PatternPart",
    "MF2SelectMessage",
    "MF2Selector",
    "MF2SimpleMessage",
    "MF2TextPart",
    "MF2VariableArgument",
    "MF2Variant",
    "MF2VariantKey",
]
