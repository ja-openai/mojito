from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, cast

from .model import MF2MessageModel


BIDI_MARKERS = {0x061C, 0x200E, 0x200F, 0x2066, 0x2067, 0x2068, 0x2069}


@dataclass(frozen=True)
class MF2ParseDiagnostic:
    code: str
    message: str
    start: int
    end: int
    severity: str = "error"

    def to_json(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "start": self.start,
            "end": self.end,
            "severity": self.severity,
        }


@dataclass(frozen=True)
class ParseResult:
    model: MF2MessageModel | None
    diagnostics: list[MF2ParseDiagnostic]

    @property
    def has_diagnostics(self) -> bool:
        return bool(self.diagnostics)


def parse_to_model(source: str) -> ParseResult:
    parser = _Parser(str(source or ""), 0)
    model = parser.parse_message_model()
    return ParseResult(
        model=cast(MF2MessageModel | None, model) if not parser.diagnostics else None,
        diagnostics=parser.diagnostics,
    )


class _Parser:
    def __init__(self, source: str, base_offset: int) -> None:
        self.source = source
        self.base_offset = base_offset
        self.index = 0
        self.diagnostics: list[MF2ParseDiagnostic] = []

    def parse_message_model(self) -> dict[str, Any] | None:
        message_start = self.index
        declarations = self.parse_declarations()
        self.skip_syntax_whitespace()
        if self.starts_with(".match"):
            return self.parse_match(declarations)
        if self.starts_with("{{"):
            pattern = self.parse_quoted_pattern()
            if pattern is None:
                return None
            self.skip_syntax_whitespace()
            if not self.is_done():
                self.push_diagnostic(
                    "trailing-content",
                    "Unexpected content after complex message body.",
                    self.index,
                    len(self.source),
                )
            return {"type": "message", "declarations": declarations, "pattern": pattern}
        if declarations:
            self.push_diagnostic(
                "missing-complex-body",
                "Complex message declarations must be followed by a quoted pattern or matcher.",
                self.index,
                len(self.source),
            )
            return None
        if self.starts_with("."):
            self.push_diagnostic(
                "invalid-simple-start",
                "Simple messages cannot start with '.'.",
                self.index,
                self.index + 1,
            )
            return None
        self.index = message_start
        return {"type": "message", "declarations": declarations, "pattern": self.parse_pattern_until_end()}

    def parse_declarations(self) -> list[dict[str, Any]]:
        declarations: list[dict[str, Any]] = []
        while True:
            before_padding = self.index
            self.skip_syntax_whitespace()
            if self.starts_with(".input"):
                declaration = self.parse_input_declaration()
                if declaration is not None:
                    declarations.append(declaration)
                continue
            if self.starts_with(".local"):
                declaration = self.parse_local_declaration()
                if declaration is not None:
                    declarations.append(declaration)
                continue
            self.index = before_padding
            return declarations

    def parse_input_declaration(self) -> dict[str, Any] | None:
        self.consume_string(".input")
        self.skip_syntax_whitespace()
        start = self.index
        value = self.parse_expression_placeholder()
        if value is None:
            return None
        arg = value.get("arg", {})
        if arg.get("type") == "variable":
            return {"type": "input", "name": arg["name"], "value": value}
        self.push_diagnostic(
            "invalid-input-declaration",
            ".input declarations must reference a variable expression.",
            start,
            self.index,
        )
        return None

    def parse_local_declaration(self) -> dict[str, Any] | None:
        self.consume_string(".local")
        self.skip_syntax_whitespace()
        start = self.index
        name = self.parse_variable_name()
        if name is None:
            return None
        self.skip_syntax_whitespace()
        if self.peek() != "=":
            self.push_diagnostic(
                "missing-local-equals",
                ".local declarations must include '='.",
                start,
                self.index,
            )
            return None
        self.index += 1
        self.skip_syntax_whitespace()
        value = self.parse_expression_placeholder()
        return None if value is None else {"type": "local", "name": name, "value": value}

    def parse_match(self, declarations: list[dict[str, Any]]) -> dict[str, Any] | None:
        self.consume_string(".match")
        selectors: list[dict[str, str]] = []
        if not self.is_done() and self.peek() == "$":
            self.push_diagnostic(
                "missing-match-space",
                ".match selectors must be separated by whitespace.",
                self.index,
                self.index,
            )
            return None
        while True:
            skipped_space = self.skip_syntax_gap()
            if not self.is_done() and self.peek() == "$":
                if not skipped_space and selectors:
                    self.push_diagnostic(
                        "missing-match-space",
                        ".match selectors must be separated by whitespace.",
                        self.index,
                        self.index,
                    )
                    return None
                name = self.parse_variable_name()
                if name is not None:
                    selectors.append({"type": "variable", "name": name})
                if not self.is_done() and not is_whitespace(self.peek()):
                    self.push_diagnostic(
                        "missing-match-space",
                        ".match selectors must be separated from variants by whitespace.",
                        self.index,
                        self.index,
                    )
                    return None
                continue
            if not self.is_done() and self.peek() == "{":
                start = self.index
                content = self.consume_braced_content()
                if content is not None:
                    self.push_diagnostic(
                        "unsupported-match-selector-expression",
                        ".match selectors must be declared variables such as .input {$name :string} followed by .match $name; inline selector expressions are not supported.",
                        start,
                        self.index,
                    )
                return None
            break
        if not selectors:
            self.push_diagnostic(
                "missing-match-selector",
                ".match must include at least one selector variable.",
                self.index,
                self.index,
            )
            return None
        variants: list[dict[str, Any]] = []
        while True:
            self.skip_syntax_whitespace()
            if self.is_done():
                break
            variant_start = self.index
            keys = self.parse_variant_keys(variant_start)
            if keys is None:
                return None
            self.skip_syntax_whitespace()
            if not self.starts_with("{{"):
                self.push_diagnostic(
                    "missing-variant-pattern",
                    "Variant keys must be followed by a quoted pattern.",
                    variant_start,
                    self.index,
                )
                return None
            value = self.parse_quoted_pattern()
            if value is None:
                return None
            if len(keys) != len(selectors):
                self.push_diagnostic(
                    "variant-key-count-mismatch",
                    "Variant key count must match selector count.",
                    variant_start,
                    self.index,
                )
                return None
            variants.append({"keys": keys, "value": value})
        if not variants:
            self.push_diagnostic(
                "missing-match-variants",
                ".match must include at least one variant.",
                self.index,
                self.index,
            )
            return None
        return {
            "type": "select",
            "declarations": declarations,
            "selectors": selectors,
            "variants": variants,
        }

    def parse_variant_keys(self, start: int) -> list[dict[str, Any]] | None:
        keys: list[dict[str, Any]] = []
        while not self.is_done() and not self.starts_with("{{") and self.peek() != "\n":
            skipped_space = self.skip_syntax_gap()
            if self.starts_with("{{") or self.peek() == "\n" or self.is_done():
                break
            if keys and not skipped_space:
                self.push_diagnostic(
                    "missing-variant-key-space",
                    "Variant keys must be separated by whitespace.",
                    start,
                    self.index,
                )
                return None
            if self.peek() == "*":
                self.index += 1
                keys.append({"type": "*"})
                continue
            if self.peek() == "|":
                split = parse_quoted_literal(self.source[self.index :])
                if split is None:
                    self.push_diagnostic(
                        "unclosed-quoted-literal",
                        "Quoted variant key is missing closing '|'.",
                        self.index,
                        len(self.source),
                    )
                    return None
                self.index += split.consumed
                keys.append({"type": "literal", "value": split.value})
                continue
            key = self.take_while(lambda ch: not is_syntax_whitespace(ch) and ch != "{")
            if key:
                keys.append({"type": "literal", "value": key})
        return keys

    def parse_quoted_pattern(self) -> list[Any] | None:
        start = self.index
        if not self.consume_string("{{"):
            self.push_diagnostic(
                "missing-quoted-pattern",
                "Expected a quoted pattern starting with '{{'.",
                start,
                start,
            )
            return None
        content_start = self.index
        scan = self.index
        placeholder_depth = 0
        in_quote = False
        while scan < len(self.source):
            if placeholder_depth == 0 and self.source.startswith("}}", scan):
                content = self.source[content_start:scan]
                self.index = scan + 2
                nested = _Parser(content, self.base_offset + content_start)
                pattern = nested.parse_pattern_until_end()
                self.diagnostics.extend(nested.diagnostics)
                return pattern
            ch = self.source[scan]
            if ch == "\\":
                scan += 2
                continue
            if placeholder_depth > 0 and ch == "|":
                in_quote = not in_quote
            elif not in_quote and ch == "{":
                placeholder_depth += 1
            elif not in_quote and ch == "}" and placeholder_depth > 0:
                placeholder_depth -= 1
            scan += 1
        self.push_diagnostic(
            "unclosed-quoted-pattern",
            "Quoted pattern is missing closing '}}'.",
            start,
            len(self.source),
        )
        return None

    def parse_pattern_until_end(self) -> list[Any]:
        parts: list[Any] = []
        text = ""
        while not self.is_done():
            ch = self.peek()
            if ch == "\\":
                text += self.parse_escape()
            elif ch == "{":
                if text:
                    parts.append(text)
                    text = ""
                part = self.parse_braced_pattern_part()
                if part is not None:
                    parts.append(part)
            elif ch == "}":
                start = self.index
                self.index += 1
                self.push_diagnostic(
                    "unescaped-closing-brace",
                    "Closing brace must be escaped in text.",
                    start,
                    self.index,
                )
            else:
                text += ch
                self.index += 1
        if text:
            parts.append(text)
        return parts

    def parse_escape(self) -> str:
        start = self.index
        self.index += 1
        if self.is_done():
            self.push_diagnostic(
                "dangling-escape",
                "Backslash at end of message has no escaped character.",
                start,
                start + 1,
            )
            return ""
        ch = self.peek()
        if ch in {"{", "}", "|", "\\"}:
            self.index += 1
            return ch
        return "\\"

    def parse_braced_pattern_part(self) -> dict[str, Any] | None:
        start = self.index
        content = self.consume_braced_content()
        if content is None:
            return None
        trimmed = strip_syntax_whitespace(content)
        if trimmed.startswith("#") or trimmed.startswith("/"):
            return self.parse_markup_content(trimmed, start, start + len(content) + 2)
        return self.parse_expression_content(trimmed, start, start + len(content) + 2)

    def parse_expression_placeholder(self) -> dict[str, Any] | None:
        start = self.index
        content = self.consume_braced_content()
        if content is None:
            return None
        return self.parse_expression_content(strip_syntax_whitespace(content), start, start + len(content) + 2)

    def consume_braced_content(self) -> str | None:
        start = self.index
        if self.peek() != "{":
            self.push_diagnostic("missing-placeholder", "Expected a placeholder starting with '{'.", start, start)
            return None
        self.index += 1
        content_start = self.index
        in_quote = False
        while not self.is_done():
            ch = self.peek()
            if in_quote:
                if ch == "\\":
                    self.index += 1
                    if not self.is_done():
                        self.index += 1
                    continue
                if ch == "}":
                    content = self.source[content_start : self.index]
                    self.index += 1
                    return content
                if ch == "|":
                    in_quote = False
                self.index += 1
                continue
            if ch == "|":
                in_quote = True
                self.index += 1
                continue
            if ch == "}":
                content = self.source[content_start : self.index]
                self.index += 1
                return content
            self.index += 1
        self.push_diagnostic(
            "unclosed-placeholder",
            "Placeholder is missing a closing brace.",
            start,
            len(self.source),
        )
        return None

    def parse_expression_content(self, content: str, start: int, end: int) -> dict[str, Any] | None:
        if content.startswith("$"):
            split = split_name(content[1:])
            if split.name == "":
                self.push_diagnostic(
                    variable_name_diagnostic_code(content[1:]),
                    "Variable placeholder is missing a name.",
                    start,
                    end,
                )
                return None
            expression = expression_model({"type": "variable", "name": split.name})
            rest = self.rest_after_operand(split.rest, start, end)
            if rest is None:
                return None
        elif content.startswith("|"):
            split = parse_quoted_literal(content)
            if split is None:
                self.push_diagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
                return None
            expression = expression_model({"type": "literal", "value": split.value})
            rest = self.rest_after_operand(split.rest, start, end)
            if rest is None:
                return None
        elif content.startswith(":"):
            expression = expression_model(None)
            rest = content
        else:
            split = split_unquoted_literal(content)
            if split is None:
                self.push_diagnostic(
                    "missing-expression" if content == "" else "invalid-literal",
                    "Placeholder literal is invalid.",
                    start,
                    end,
                )
                return None
            expression = expression_model({"type": "literal", "value": split.value})
            rest = self.rest_after_operand(split.rest, start, end)
            if rest is None:
                return None
        if rest == "":
            return expression
        tail = self.parse_tail(rest, start, end)
        if tail is None:
            return None
        return expression_model(expression.get("arg"), tail.get("function"), tail.get("attributes"))

    def rest_after_operand(self, rest: str, start: int, end: int) -> str | None:
        if rest == "":
            return rest
        if not is_whitespace(rest[0]):
            self.push_diagnostic(
                "missing-expression-space",
                "Expression arguments must be separated from functions or attributes by whitespace.",
                start,
                end,
            )
            return None
        return strip_leading_syntax_whitespace(rest)

    def parse_tail(self, rest: str, start: int, end: int) -> dict[str, Any] | None:
        if rest.strip() == "":
            return {"function": None, "attributes": None}
        tokens = self.split_tail_tokens(rest, start, end)
        if tokens is None:
            return None
        index = 0
        function_ref = None
        attributes: dict[str, Any] = {}
        if index < len(tokens) and tokens[index].startswith(":"):
            result = self.parse_function_annotation(tokens, index, start, end)
            if result is None:
                return None
            function_ref = result["function"]
            index = result["next_index"]
        while index < len(tokens):
            token = tokens[index]
            if not token.startswith("@"):
                self.push_diagnostic(
                    "unsupported-expression",
                    "Expression content after the argument must be a function annotation or attribute.",
                    start,
                    end,
                )
                return None
            attribute = self.parse_attribute_tokens(tokens, index, start, end)
            if attribute is None:
                return None
            index = attribute["next_index"]
            if attribute["name"] in attributes:
                self.push_diagnostic(
                    "duplicate-attribute-name",
                    "Attribute names must be unique within an expression or markup placeholder.",
                    start,
                    end,
                )
                return None
            attributes[attribute["name"]] = attribute["value"]
        return {"function": function_ref, "attributes": attributes or None}

    def split_tail_tokens(self, rest: str, start: int, end: int) -> list[str] | None:
        tokens: list[str] = []
        token_start = -1
        in_quote = False
        index = 0
        while index < len(rest):
            ch = rest[index]
            if in_quote and ch == "\\":
                if token_start < 0:
                    token_start = index
                index += 2
                continue
            if ch == "|":
                in_quote = not in_quote
                if token_start < 0:
                    token_start = index
                index += 1
                continue
            if is_syntax_whitespace(ch) and not in_quote:
                if token_start >= 0:
                    tokens.append(rest[token_start:index])
                    token_start = -1
                index += 1
                continue
            if token_start < 0:
                token_start = index
            index += 1
        if in_quote:
            self.push_diagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
            return None
        if token_start >= 0:
            tokens.append(rest[token_start:])
        return tokens

    def parse_function_annotation(self, tokens: list[str], index: int, start: int, end: int) -> dict[str, Any] | None:
        content = tokens[index][1:]
        split = split_identifier(content)
        if split.name == "":
            self.push_diagnostic(
                "missing-function-name" if content == "" else "invalid-function-name",
                "Function annotation is missing a name.",
                start,
                end,
            )
            return None
        if split.rest != "":
            self.push_diagnostic("unsupported-expression", "Function annotation must separate options with whitespace.", start, end)
            return None
        options: dict[str, Any] = {}
        index += 1
        while index < len(tokens) and not tokens[index].startswith("@"):
            option = self.parse_option_tokens(tokens, index, start, end)
            if option is None:
                return None
            index = option["next_index"]
            if option["name"] in options:
                self.push_diagnostic(
                    "duplicate-option-name",
                    "Option names must be unique within a function or markup placeholder.",
                    start,
                    end,
                )
                return None
            options[option["name"]] = option["value"]
        return {"function": function_model(split.name, options or None), "next_index": index}

    def parse_option_tokens(self, tokens: list[str], index: int, start: int, end: int) -> dict[str, Any] | None:
        assignment = self.parse_required_assignment(tokens, index, start, end)
        if assignment is None:
            return None
        key_split = split_identifier(assignment["key"])
        if key_split.name == "" or key_split.rest != "":
            self.push_diagnostic("invalid-function-option", "Option key must be a valid identifier.", start, end)
            return None
        value = self.parse_option_value(assignment["raw_value"], start, end)
        if value is None:
            return None
        return {
            "name": key_split.name,
            "value": value,
            "next_index": assignment["next_index"],
        }

    def parse_option_value(self, raw_value: str, start: int, end: int) -> dict[str, str] | None:
        raw_value = strip_syntax_whitespace(raw_value)
        if raw_value.startswith("|"):
            split = parse_quoted_literal(raw_value)
            if split is None:
                self.push_diagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
                return None
            if split.rest != "":
                self.push_diagnostic(
                    "invalid-function-option",
                    "Option value must be a single literal or variable.",
                    start,
                    end,
                )
                return None
            return {"type": "literal", "value": split.value}
        if raw_value.startswith("$"):
            split = split_name(raw_value[1:])
            if split.name != "" and split.rest == "":
                return {"type": "variable", "name": split.name}
            self.push_diagnostic(
                variable_name_diagnostic_code(raw_value[1:]),
                "Option variable value must be a valid variable name.",
                start,
                end,
            )
            return None
        return parse_literal_or_variable(raw_value)

    def parse_required_assignment(self, tokens: list[str], index: int, start: int, end: int) -> dict[str, Any] | None:
        token = tokens[index]
        equals = token.find("=")
        if equals >= 0:
            return self.finish_assignment(token[:equals], token[equals + 1 :], tokens, index + 1, start, end)
        if index + 1 >= len(tokens) or not tokens[index + 1].startswith("="):
            self.push_diagnostic("invalid-function-option", "Options must use key=value syntax.", start, end)
            return None
        return self.finish_assignment(token, tokens[index + 1][1:], tokens, index + 2, start, end)

    def finish_assignment(
        self,
        key: str,
        raw_value: str,
        tokens: list[str],
        next_index: int,
        start: int,
        end: int,
    ) -> dict[str, Any] | None:
        if key == "":
            self.push_diagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
            return None
        if raw_value != "":
            return {"key": key, "raw_value": raw_value, "next_index": next_index}
        if next_index >= len(tokens):
            self.push_diagnostic("invalid-function-option", "Option key and value must be non-empty.", start, end)
            return None
        return {"key": key, "raw_value": tokens[next_index], "next_index": next_index + 1}

    def parse_attribute_tokens(self, tokens: list[str], index: int, start: int, end: int) -> dict[str, Any] | None:
        token = tokens[index]
        content = token[1:]
        if content == "":
            self.push_diagnostic("missing-attribute-name", "Attribute is missing a name.", start, end)
            return None
        if not self.has_attribute_assignment(content, tokens, index):
            split = split_identifier(content)
            if split.name == "" or split.rest != "":
                self.push_diagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
                return None
            return {"name": split.name, "value": True, "next_index": index + 1}
        assignment = self.parse_attribute_assignment(content, tokens, index, start, end)
        if assignment is None:
            return None
        value = self.parse_attribute_value(assignment["raw_value"], start, end)
        if value is None:
            return None
        return {"name": assignment["name"], "value": value, "next_index": assignment["next_index"]}

    def has_attribute_assignment(self, content: str, tokens: list[str], index: int) -> bool:
        return "=" in content or (index + 1 < len(tokens) and tokens[index + 1].startswith("="))

    def parse_attribute_assignment(
        self,
        content: str,
        tokens: list[str],
        index: int,
        start: int,
        end: int,
    ) -> dict[str, Any] | None:
        assignment = self.attribute_assignment_parts(content, tokens, index, start, end)
        if assignment is None:
            return None
        split = split_identifier(assignment["key"])
        if split.name == "" or split.rest != "":
            self.push_diagnostic("invalid-attribute", "Attribute name must be a valid identifier.", start, end)
            return None
        return {"name": split.name, "raw_value": assignment["raw_value"], "next_index": assignment["next_index"]}

    def attribute_assignment_parts(
        self,
        content: str,
        tokens: list[str],
        index: int,
        start: int,
        end: int,
    ) -> dict[str, Any] | None:
        equals = content.find("=")
        if equals >= 0:
            return self.finish_attribute_assignment(content[:equals], content[equals + 1 :], tokens, index + 1, start, end)
        if index + 1 >= len(tokens) or not tokens[index + 1].startswith("="):
            return None
        return self.finish_attribute_assignment(content, tokens[index + 1][1:], tokens, index + 2, start, end)

    def finish_attribute_assignment(
        self,
        key: str,
        raw_value: str,
        tokens: list[str],
        next_index: int,
        start: int,
        end: int,
    ) -> dict[str, Any] | None:
        if key == "":
            self.push_diagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
            return None
        if raw_value != "":
            return {"key": key, "raw_value": raw_value, "next_index": next_index}
        if next_index >= len(tokens):
            self.push_diagnostic("invalid-attribute", "Attribute key and value must be non-empty.", start, end)
            return None
        return {"key": key, "raw_value": tokens[next_index], "next_index": next_index + 1}

    def parse_attribute_value(self, raw_value: str, start: int, end: int) -> dict[str, str] | None:
        raw_value = strip_syntax_whitespace(raw_value)
        if raw_value.startswith("|") and raw_value.endswith("|") and len(raw_value) >= 2:
            split = parse_quoted_literal(raw_value)
            if split is None:
                self.push_diagnostic("unclosed-quoted-literal", "Quoted literal is missing closing '|'.", start, end)
                return None
            if split.rest != "":
                self.push_diagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
                return None
            return {"type": "literal", "value": split.value}
        split = split_unquoted_literal(raw_value)
        if split is None or split.rest != "":
            self.push_diagnostic("invalid-attribute", "Attribute value must be a single literal.", start, end)
            return None
        return {"type": "literal", "value": split.value}

    def parse_markup_content(self, content: str, start: int, end: int) -> dict[str, Any] | None:
        if content.startswith("#"):
            trimmed = strip_trailing_syntax_whitespace(content[1:])
            if trimmed.endswith("/"):
                kind = "standalone"
                rest = strip_trailing_syntax_whitespace(trimmed[:-1])
            else:
                kind = "open"
                rest = trimmed
        else:
            kind = "close"
            rest = strip_syntax_whitespace(content[1:])
        split = split_identifier(strip_leading_syntax_whitespace(rest))
        if split.name == "":
            self.push_diagnostic("missing-markup-name", "Markup placeholder is missing a name.", start, end)
            return None
        if strip_syntax_whitespace(split.rest) == "":
            return markup_model(kind, split.name)
        tail = self.parse_markup_tail(split.rest, start, end)
        if tail is None:
            return None
        return markup_model(kind, split.name, tail.get("options"), tail.get("attributes"))

    def parse_markup_tail(self, rest: str, start: int, end: int) -> dict[str, Any] | None:
        tokens = self.split_tail_tokens(rest, start, end)
        if tokens is None:
            return None
        options: dict[str, Any] = {}
        attributes: dict[str, Any] = {}
        seen_attribute = False
        index = 0
        while index < len(tokens):
            token = tokens[index]
            if token.startswith("@"):
                seen_attribute = True
                attribute = self.parse_attribute_tokens(tokens, index, start, end)
                if attribute is None:
                    return None
                index = attribute["next_index"]
                if attribute["name"] in attributes:
                    self.push_diagnostic(
                        "duplicate-attribute-name",
                        "Attribute names must be unique within an expression or markup placeholder.",
                        start,
                        end,
                    )
                    return None
                attributes[attribute["name"]] = attribute["value"]
                continue
            if seen_attribute:
                self.push_diagnostic("unsupported-markup", "Markup options must come before attributes.", start, end)
                return None
            if token.startswith(":"):
                self.push_diagnostic("unsupported-markup", "Markup placeholders do not support function annotations.", start, end)
                return None
            option = self.parse_option_tokens(tokens, index, start, end)
            if option is None:
                return None
            index = option["next_index"]
            if option["name"] in options:
                self.push_diagnostic(
                    "duplicate-option-name",
                    "Option names must be unique within a function or markup placeholder.",
                    start,
                    end,
                )
                return None
            options[option["name"]] = option["value"]
        return {"options": options or None, "attributes": attributes or None}

    def parse_variable_name(self) -> str | None:
        start = self.index
        if self.peek() != "$":
            self.push_diagnostic("missing-variable", "Expected a variable starting with '$'.", start, start)
            return None
        self.index += 1
        scan = scan_name(self.source, self.index)
        if scan.name == "":
            self.push_diagnostic(
                variable_name_diagnostic_code(self.source, self.index),
                "Variable is missing a name.",
                start,
                self.index,
            )
            return None
        self.index = scan.end_index
        return scan.name

    def skip_syntax_whitespace(self) -> bool:
        start = self.index
        while not self.is_done() and is_syntax_whitespace(self.peek()):
            self.index += 1
        return self.index != start

    def skip_syntax_gap(self) -> bool:
        saw_whitespace = False
        while not self.is_done():
            ch = self.peek()
            if is_whitespace(ch):
                saw_whitespace = True
                self.index += 1
                continue
            if is_bidi_marker(ch):
                self.index += 1
                continue
            break
        return saw_whitespace

    def take_while(self, predicate: Callable[[str], bool]) -> str:
        start = self.index
        while not self.is_done() and predicate(self.peek()):
            self.index += 1
        return self.source[start : self.index]

    def starts_with(self, expected: str) -> bool:
        return self.source.startswith(expected, self.index)

    def consume_string(self, expected: str) -> bool:
        if not self.starts_with(expected):
            return False
        self.index += len(expected)
        return True

    def is_done(self) -> bool:
        return self.index >= len(self.source)

    def peek(self) -> str:
        return "" if self.is_done() else self.source[self.index]

    def push_diagnostic(self, code: str, message: str, start: int, end: int) -> None:
        self.diagnostics.append(
            MF2ParseDiagnostic(
                code=code,
                message=message,
                start=self.base_offset + start,
                end=self.base_offset + end,
            )
        )


def expression_model(
    arg: dict[str, Any] | None,
    function_ref: dict[str, Any] | None = None,
    attributes: dict[str, Any] | None = None,
) -> dict[str, Any]:
    output: dict[str, Any] = {"type": "expression"}
    if arg is not None:
        output["arg"] = arg
    if function_ref is not None:
        output["function"] = function_ref
    if attributes:
        output["attributes"] = sort_keys(attributes)
    return output


def function_model(name: str, options: dict[str, Any] | None) -> dict[str, Any]:
    output: dict[str, Any] = {"type": "function", "name": name}
    if options:
        output["options"] = sort_keys(options)
    return output


def markup_model(
    kind: str,
    name: str,
    options: dict[str, Any] | None = None,
    attributes: dict[str, Any] | None = None,
) -> dict[str, Any]:
    output: dict[str, Any] = {"type": "markup", "kind": kind, "name": name}
    if options:
        output["options"] = sort_keys(options)
    if attributes:
        output["attributes"] = sort_keys(attributes)
    return output


def parse_literal_or_variable(raw_value: str) -> dict[str, str]:
    if raw_value.startswith("$"):
        split = split_name(raw_value[1:])
        if split.name != "" and split.rest == "":
            return {"type": "variable", "name": split.name}
        return {"type": "variable", "name": raw_value[1:]}
    quoted = parse_quoted_literal(raw_value)
    if quoted is not None and quoted.rest == "":
        return {"type": "literal", "value": quoted.value}
    return {"type": "literal", "value": raw_value}


@dataclass(frozen=True)
class _Split:
    value: str = ""
    name: str = ""
    rest: str = ""
    consumed: int = 0
    end_index: int = 0


def parse_quoted_literal(input_value: str) -> _Split | None:
    if not input_value.startswith("|"):
        return None
    output = ""
    index = 1
    while index < len(input_value):
        ch = input_value[index]
        index += 1
        if ch == "|":
            return _Split(value=output, rest=input_value[index:], consumed=index)
        if ch == "\\":
            if index >= len(input_value):
                output += "\\"
                break
            escaped = input_value[index]
            if escaped in {"\\", "{", "|", "}"}:
                output += escaped
                index += 1
            else:
                return None
        else:
            output += ch
    return None


def split_unquoted_literal(input_value: str) -> _Split | None:
    scan = 0
    saw_char = False
    while scan < len(input_value):
        ch = input_value[scan]
        if is_syntax_whitespace(ch) or ch in {":", "@"}:
            break
        if not is_unquoted_literal_char(ch):
            return None
        saw_char = True
        scan += 1
    if not saw_char:
        return None
    return _Split(value=input_value[:scan], rest=input_value[scan:], consumed=scan)


def is_unquoted_literal_char(ch: str) -> bool:
    code_point = ord(ch)
    if is_control(ch) or is_syntax_whitespace(ch) or is_noncharacter(code_point):
        return False
    return ch not in {"^", "!", "%", "*", "<", ">", "?", "~", "&", "\\", "$"}


def variable_name_diagnostic_code(input_value: str, offset: int = 0) -> str:
    if offset >= len(input_value):
        return "missing-variable-name"
    return "missing-variable-name" if input_value[offset] in {"}", " ", "\t", "\n", "\r"} else "invalid-variable-name"


def split_name(input_value: str) -> _Split:
    scan = scan_name(input_value, 0)
    return _Split(name=scan.name, rest=input_value[scan.end_index :], consumed=scan.end_index)


def scan_name(input_value: str, offset: int) -> _Split:
    scan = offset
    if scan < len(input_value) and is_bidi_marker(input_value[scan]):
        scan += 1
    name_start = scan
    if name_start >= len(input_value):
        return _Split(name="", end_index=offset)
    first = input_value[name_start]
    if ord(first) <= 0x7F:
        ascii_scan = scan_ascii_name(input_value, offset, name_start)
        if ascii_scan is not None:
            return ascii_scan
    if not is_name_start(first):
        return _Split(name="", end_index=offset)
    scan += 1
    while scan < len(input_value) and is_name_char(input_value[scan]):
        scan += 1
    name_end = scan
    if scan < len(input_value) and is_bidi_marker(input_value[scan]):
        scan += 1
    return _Split(name=input_value[name_start:name_end], end_index=scan)


def scan_ascii_name(input_value: str, offset: int, name_start: int) -> _Split | None:
    first = input_value[name_start]
    if not is_ascii_name_start(first):
        return _Split(name="", end_index=offset)
    scan = name_start + 1
    while scan < len(input_value) and is_ascii_name_char(input_value[scan]):
        scan += 1
    name_end = scan
    if scan < len(input_value):
        ch = input_value[scan]
        if ord(ch) > 0x7F:
            if not is_bidi_marker(ch):
                return None
            return _Split(name=input_value[name_start:name_end], end_index=scan + 1)
    return _Split(name=input_value[name_start:name_end], end_index=scan)


def split_identifier(input_value: str) -> _Split:
    namespace_or_name = split_name(input_value)
    if namespace_or_name.name == "":
        return _Split(name="", rest=input_value, consumed=0)
    if not namespace_or_name.rest.startswith(":"):
        return namespace_or_name
    name = split_name(namespace_or_name.rest[1:])
    if name.name == "":
        return namespace_or_name
    return _Split(
        name=f"{namespace_or_name.name}:{name.name}",
        rest=name.rest,
        consumed=namespace_or_name.consumed + 1 + name.consumed,
    )


def is_name_start(ch: str) -> bool:
    code_point = ord(ch)
    if code_point <= 0x7F:
        return is_ascii_name_start(ch)
    return (
        0xA1 <= code_point <= 0x10FFFD
        and not is_bidi_marker(ch)
        and not is_control(ch)
        and not (0xD800 <= code_point <= 0xDFFF)
        and not is_syntax_whitespace(ch)
        and not is_unicode_whitespace(ch)
        and not is_noncharacter(code_point)
    )


def is_name_char(ch: str) -> bool:
    return is_name_start(ch) or ch.isdigit() or unicodedata_mark(ch) or ch in {"-", "."}


def unicodedata_mark(ch: str) -> bool:
    return _is_mark(ch)


def _is_mark(ch: str) -> bool:
    import unicodedata

    return unicodedata.category(ch).startswith("M")


def is_ascii_name_start(ch: str) -> bool:
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z") or ch in {"+", "_"}


def is_ascii_name_char(ch: str) -> bool:
    return is_ascii_name_start(ch) or ("0" <= ch <= "9") or ch in {"-", "."}


def is_bidi_marker(ch: str) -> bool:
    return bool(ch) and ord(ch) in BIDI_MARKERS


def is_syntax_whitespace(ch: str) -> bool:
    return is_whitespace(ch) or is_bidi_marker(ch)


def is_whitespace(ch: str) -> bool:
    return ch in {"\t", "\n", "\r", " ", "\u3000"}


def is_unicode_whitespace(ch: str) -> bool:
    return bool(ch) and ch.isspace()


def is_control(ch: str) -> bool:
    code_point = ord(ch)
    return 0 <= code_point <= 0x1F or 0x7F <= code_point <= 0x9F


def is_noncharacter(code_point: int) -> bool:
    return 0xFDD0 <= code_point <= 0xFDEF or (code_point & 0xFFFE) == 0xFFFE


def strip_syntax_whitespace(value: str) -> str:
    return strip_trailing_syntax_whitespace(strip_leading_syntax_whitespace(value))


def strip_leading_syntax_whitespace(value: str) -> str:
    start = 0
    while start < len(value) and is_syntax_whitespace(value[start]):
        start += 1
    return value[start:]


def strip_trailing_syntax_whitespace(value: str) -> str:
    end = len(value)
    while end > 0 and is_syntax_whitespace(value[end - 1]):
        end -= 1
    return value[:end]


def sort_keys(value: dict[str, Any]) -> dict[str, Any]:
    return {key: value[key] for key in sorted(value)}
