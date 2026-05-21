use std::collections::BTreeMap;

use crate::diagnostic::Diagnostic;
use crate::model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    Pattern, PatternPart, VariableRef, Variant, VariantKey,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseResult {
    pub model: Option<MessageModel>,
    pub diagnostics: Vec<Diagnostic>,
}

pub fn parse_to_model(source: &str) -> ParseResult {
    let mut parser = Parser::new(source, 0);
    let model = parser.parse_message_model();
    let diagnostics = parser.diagnostics;
    ParseResult {
        model: if diagnostics.is_empty() { model } else { None },
        diagnostics,
    }
}

struct Tail {
    function: Option<FunctionRef>,
    attributes: BTreeMap<String, AttributeValue>,
}

struct MarkupTail {
    options: BTreeMap<String, ExpressionArg>,
    attributes: BTreeMap<String, AttributeValue>,
}

struct FunctionParseResult {
    function: FunctionRef,
    next_index: usize,
}

struct ParsedOption {
    name: String,
    value: ExpressionArg,
    next_index: usize,
}

struct ParsedAttribute {
    name: String,
    value: AttributeValue,
    next_index: usize,
}

struct Parser<'a> {
    source: &'a str,
    base_offset: usize,
    index: usize,
    diagnostics: Vec<Diagnostic>,
}

impl<'a> Parser<'a> {
    fn new(source: &'a str, base_offset: usize) -> Self {
        Self {
            source,
            base_offset,
            index: 0,
            diagnostics: vec![],
        }
    }

    fn parse_message_model(&mut self) -> Option<MessageModel> {
        let declarations = self.parse_declarations();
        self.skip_horizontal_whitespace();

        if self.starts_with(".match") {
            return self.parse_match(declarations);
        }

        if self.starts_with("{{") {
            let pattern = self.parse_quoted_pattern()?;
            self.skip_whitespace();
            if !self.is_done() {
                self.push_diagnostic(
                    "trailing-content",
                    "Unexpected content after complex message body.",
                    self.index,
                    self.source.len(),
                );
            }
            return Some(MessageModel::Message {
                declarations,
                pattern,
            });
        }

        if !declarations.is_empty() {
            self.push_diagnostic(
                "missing-complex-body",
                "Complex message declarations must be followed by a quoted pattern or matcher.",
                self.index,
                self.source.len(),
            );
            return None;
        }

        let pattern = self.parse_pattern_until_end();
        Some(MessageModel::Message {
            declarations,
            pattern,
        })
    }

    fn parse_declarations(&mut self) -> Vec<Declaration> {
        let mut declarations = Vec::new();
        loop {
            self.skip_whitespace();
            if self.starts_with(".input") {
                if let Some(declaration) = self.parse_input_declaration() {
                    declarations.push(declaration);
                }
                continue;
            }
            if self.starts_with(".local") {
                if let Some(declaration) = self.parse_local_declaration() {
                    declarations.push(declaration);
                }
                continue;
            }
            break;
        }
        declarations
    }

    fn parse_input_declaration(&mut self) -> Option<Declaration> {
        self.consume_str(".input");
        self.skip_horizontal_whitespace();
        let start = self.index;
        let expression = self.parse_expression_placeholder()?;
        let name = match &expression.arg {
            Some(ExpressionArg::Variable { name }) => name.clone(),
            _ => {
                self.push_diagnostic(
                    "invalid-input-declaration",
                    ".input declarations must reference a variable expression.",
                    start,
                    self.index,
                );
                return None;
            }
        };
        Some(Declaration::Input {
            name,
            value: expression,
        })
    }

    fn parse_local_declaration(&mut self) -> Option<Declaration> {
        self.consume_str(".local");
        self.skip_horizontal_whitespace();
        let start = self.index;
        let name = self.parse_variable_name()?;
        self.skip_horizontal_whitespace();
        if self.peek_char() != Some('=') {
            self.push_diagnostic(
                "missing-local-equals",
                ".local declarations must include '='.",
                start,
                self.index,
            );
            return None;
        }
        self.advance_char();
        self.skip_horizontal_whitespace();
        let value = self.parse_expression_placeholder()?;
        Some(Declaration::Local { name, value })
    }

    fn parse_match(&mut self, declarations: Vec<Declaration>) -> Option<MessageModel> {
        self.consume_str(".match");
        let mut selectors = Vec::new();
        loop {
            self.skip_horizontal_whitespace();
            if self.peek_char() == Some('$') {
                if let Some(name) = self.parse_variable_name() {
                    selectors.push(VariableRef::new(name));
                }
                continue;
            }
            break;
        }

        if selectors.is_empty() {
            self.push_diagnostic(
                "missing-match-selector",
                ".match must include at least one selector variable.",
                self.index,
                self.index,
            );
            return None;
        }

        let mut variants = Vec::new();
        loop {
            self.skip_whitespace();
            if self.is_done() {
                break;
            }
            let variant_start = self.index;
            let keys = self.parse_variant_keys();
            self.skip_horizontal_whitespace();
            if !self.starts_with("{{") {
                self.push_diagnostic(
                    "missing-variant-pattern",
                    "Variant keys must be followed by a quoted pattern.",
                    variant_start,
                    self.index,
                );
                return None;
            }
            let value = self.parse_quoted_pattern()?;
            if keys.len() != selectors.len() {
                self.push_diagnostic(
                    "variant-key-count-mismatch",
                    "Variant key count must match selector count.",
                    variant_start,
                    self.index,
                );
                return None;
            }
            variants.push(Variant { keys, value });
        }

        if variants.is_empty() {
            self.push_diagnostic(
                "missing-match-variants",
                ".match must include at least one variant.",
                self.index,
                self.index,
            );
            return None;
        }

        Some(MessageModel::Select {
            declarations,
            selectors,
            variants,
        })
    }

    fn parse_variant_keys(&mut self) -> Vec<VariantKey> {
        let mut keys = Vec::new();
        while !self.is_done() && !self.starts_with("{{") && self.peek_char() != Some('\n') {
            self.skip_horizontal_whitespace();
            if self.starts_with("{{") || self.peek_char() == Some('\n') || self.is_done() {
                break;
            }
            if self.peek_char() == Some('*') {
                self.advance_char();
                keys.push(VariantKey::CatchAll);
                continue;
            }
            let key = self.take_while(|ch| !ch.is_whitespace() && ch != '{');
            if !key.is_empty() {
                keys.push(VariantKey::Literal { value: key });
            }
        }
        keys
    }

    fn parse_quoted_pattern(&mut self) -> Option<Pattern> {
        let start = self.index;
        if !self.consume_str("{{") {
            self.push_diagnostic(
                "missing-quoted-pattern",
                "Expected a quoted pattern starting with '{{'.",
                start,
                start,
            );
            return None;
        }
        let content_start = self.index;
        let mut scan = self.index;
        let mut placeholder_depth = 0usize;
        while scan < self.source.len() {
            if placeholder_depth == 0 && self.source[scan..].starts_with("}}") {
                let content = &self.source[content_start..scan];
                self.index = scan + 2;
                let mut nested = Parser::new(content, self.base_offset + content_start);
                let pattern = nested.parse_pattern_until_end();
                if !nested.diagnostics.is_empty() {
                    self.diagnostics.extend(nested.diagnostics);
                }
                return Some(pattern);
            }
            let Some(ch) = self.source[scan..].chars().next() else {
                break;
            };
            if ch == '\\' {
                scan += ch.len_utf8();
                if let Some(next) = self.source[scan..].chars().next() {
                    scan += next.len_utf8();
                }
                continue;
            }
            if ch == '{' {
                placeholder_depth += 1;
            } else if ch == '}' && placeholder_depth > 0 {
                placeholder_depth -= 1;
            }
            scan += ch.len_utf8();
        }
        self.push_diagnostic(
            "unclosed-quoted-pattern",
            "Quoted pattern is missing closing '}}'.",
            start,
            self.source.len(),
        );
        None
    }

    fn parse_pattern_until_end(&mut self) -> Pattern {
        let mut parts = Vec::new();
        let mut text = String::new();

        while let Some(ch) = self.peek_char() {
            match ch {
                '\\' => self.parse_escape_into(&mut text),
                '{' => {
                    if !text.is_empty() {
                        parts.push(PatternPart::Text(std::mem::take(&mut text)));
                    }
                    if let Some(part) = self.parse_braced_pattern_part() {
                        parts.push(part);
                    }
                }
                '}' => {
                    let start = self.index;
                    self.advance_char();
                    self.push_diagnostic(
                        "unescaped-closing-brace",
                        "Closing brace must be escaped in text.",
                        start,
                        self.index,
                    );
                }
                _ => {
                    text.push(ch);
                    self.advance_char();
                }
            }
        }

        if !text.is_empty() {
            parts.push(PatternPart::Text(text));
        }
        parts
    }

    fn parse_escape_into(&mut self, text: &mut String) {
        let start = self.index;
        self.advance_char();
        match self.peek_char() {
            Some('{') | Some('}') | Some('\\') => {
                text.push(self.advance_char().expect("peeked char exists"));
            }
            Some(_) => {
                text.push('\\');
            }
            None => {
                self.push_diagnostic(
                    "dangling-escape",
                    "Backslash at end of message has no escaped character.",
                    start,
                    start + 1,
                );
            }
        }
    }

    fn parse_braced_pattern_part(&mut self) -> Option<PatternPart> {
        let start = self.index;
        let content = self.consume_braced_content()?;
        let trimmed = content.trim();
        if trimmed.starts_with('#') || trimmed.starts_with('/') {
            return self
                .parse_markup_content(trimmed, start, start + content.len() + 2)
                .map(PatternPart::Markup);
        }
        self.parse_expression_content(trimmed, start, start + content.len() + 2)
            .map(PatternPart::Expression)
    }

    fn parse_expression_placeholder(&mut self) -> Option<Expression> {
        let start = self.index;
        let content = self.consume_braced_content()?;
        self.parse_expression_content(content.trim(), start, start + content.len() + 2)
    }

    fn consume_braced_content(&mut self) -> Option<&'a str> {
        let start = self.index;
        if self.peek_char() != Some('{') {
            self.push_diagnostic(
                "missing-placeholder",
                "Expected a placeholder starting with '{'.",
                start,
                start,
            );
            return None;
        }
        self.advance_char();
        let content_start = self.index;
        while let Some(ch) = self.peek_char() {
            if ch == '}' {
                let content_end = self.index;
                self.advance_char();
                return Some(&self.source[content_start..content_end]);
            }
            self.advance_char();
        }
        self.push_diagnostic(
            "unclosed-placeholder",
            "Placeholder is missing a closing brace.",
            start,
            self.source.len(),
        );
        None
    }

    fn parse_expression_content(
        &mut self,
        content: &str,
        start: usize,
        end: usize,
    ) -> Option<Expression> {
        let (mut expression, rest) = if let Some(rest) = content.strip_prefix('$') {
            let (name, rest) = split_name(rest);
            if name.is_empty() {
                let code = variable_name_diagnostic_code(rest);
                self.push_diagnostic(code, "Variable placeholder is missing a name.", start, end);
                return None;
            }
            (
                Expression::variable(name),
                self.rest_after_operand(rest, start, end)?,
            )
        } else if let Some(rest) = content.strip_prefix('|') {
            let Some(close) = rest.find('|') else {
                self.push_diagnostic(
                    "unclosed-quoted-literal",
                    "Quoted literal is missing closing '|'.",
                    start,
                    end,
                );
                return None;
            };
            (
                Expression::literal(&rest[..close]),
                self.rest_after_operand(&rest[close + 1..], start, end)?,
            )
        } else if content.starts_with(':') {
            (Expression::function_only(), content)
        } else {
            let Some((literal, rest)) = split_unquoted_literal(content) else {
                let code = if content.is_empty() {
                    "missing-expression"
                } else {
                    "invalid-literal"
                };
                self.push_diagnostic(code, "Placeholder literal is invalid.", start, end);
                return None;
            };
            (
                Expression::literal(literal),
                self.rest_after_operand(rest, start, end)?,
            )
        };

        if rest.is_empty() {
            return Some(expression);
        }

        let tail = self.parse_tail(rest, start, end)?;
        if let Some(function) = tail.function {
            expression = expression.with_function(function);
        }
        expression = expression.with_attributes(tail.attributes);

        Some(expression)
    }

    fn rest_after_operand<'b>(
        &mut self,
        rest: &'b str,
        start: usize,
        end: usize,
    ) -> Option<&'b str> {
        if rest.is_empty() {
            return Some(rest);
        }
        if !rest.chars().next().is_some_and(|ch| ch.is_whitespace()) {
            self.push_diagnostic(
                "missing-expression-space",
                "Expression arguments must be separated from functions or attributes by whitespace.",
                start,
                end,
            );
            return None;
        }
        Some(rest.trim_start())
    }

    fn parse_tail(&mut self, rest: &str, start: usize, end: usize) -> Option<Tail> {
        if rest.is_empty() {
            return Some(Tail {
                function: None,
                attributes: BTreeMap::new(),
            });
        }

        let tokens = self.split_tail_tokens(rest, start, end)?;
        let mut index = 0;
        let function = if tokens.first().is_some_and(|token| token.starts_with(':')) {
            let parsed = self.parse_function_annotation(&tokens, index, start, end)?;
            index = parsed.next_index;
            Some(parsed.function)
        } else {
            None
        };

        let mut attributes = BTreeMap::new();
        while index < tokens.len() {
            let token = tokens[index];
            if !token.starts_with('@') {
                self.push_diagnostic(
                    "unsupported-expression",
                    "Expression content after the argument must be a function annotation or attribute.",
                    start,
                    end,
                );
                return None;
            }
            let parsed = self.parse_attribute_tokens(&tokens, index, start, end)?;
            index = parsed.next_index;
            let name = parsed.name;
            if attributes.contains_key(&name) {
                self.push_diagnostic(
                    "duplicate-attribute-name",
                    "Attribute names must be unique within an expression or markup placeholder.",
                    start,
                    end,
                );
                return None;
            }
            attributes.insert(name, parsed.value);
        }

        Some(Tail {
            function,
            attributes,
        })
    }

    fn split_tail_tokens<'b>(
        &mut self,
        rest: &'b str,
        start: usize,
        end: usize,
    ) -> Option<Vec<&'b str>> {
        let mut tokens = Vec::new();
        let mut token_start = None;
        let mut in_quote = false;

        for (index, ch) in rest.char_indices() {
            if ch == '|' {
                in_quote = !in_quote;
                token_start.get_or_insert(index);
                continue;
            }
            if ch.is_whitespace() && !in_quote {
                if let Some(start) = token_start.take() {
                    tokens.push(&rest[start..index]);
                }
                continue;
            }
            token_start.get_or_insert(index);
        }

        if in_quote {
            self.push_diagnostic(
                "unclosed-quoted-literal",
                "Quoted literal is missing closing '|'.",
                start,
                end,
            );
            return None;
        }
        if let Some(start) = token_start {
            tokens.push(&rest[start..]);
        }

        Some(tokens)
    }

    fn parse_function_annotation(
        &mut self,
        tokens: &[&str],
        mut index: usize,
        start: usize,
        end: usize,
    ) -> Option<FunctionParseResult> {
        let content = tokens[index]
            .strip_prefix(':')
            .expect("annotation starts with ':'");
        let (name, rest) = split_identifier(content);
        if name.is_empty() {
            let code = if content.is_empty() {
                "missing-function-name"
            } else {
                "invalid-function-name"
            };
            self.push_diagnostic(code, "Function annotation is missing a name.", start, end);
            return None;
        }
        if !rest.is_empty() {
            self.push_diagnostic(
                "unsupported-expression",
                "Function annotation must separate options with whitespace.",
                start,
                end,
            );
            return None;
        }

        let mut options = BTreeMap::new();
        index += 1;
        while index < tokens.len() && !tokens[index].starts_with('@') {
            let parsed = self.parse_option_tokens(&tokens, index, start, end)?;
            index = parsed.next_index;
            let key = parsed.name;
            if options.contains_key(&key) {
                self.push_diagnostic(
                    "duplicate-option-name",
                    "Option names must be unique within a function or markup placeholder.",
                    start,
                    end,
                );
                return None;
            }
            options.insert(key, parsed.value);
        }

        Some(FunctionParseResult {
            function: FunctionRef::new(name, options),
            next_index: index,
        })
    }

    fn parse_option_tokens(
        &mut self,
        tokens: &[&str],
        index: usize,
        start: usize,
        end: usize,
    ) -> Option<ParsedOption> {
        let (key, raw_value, next_index) =
            self.parse_required_assignment(tokens, index, start, end)?;
        let (name, rest) = split_identifier(key);
        if name.is_empty() || !rest.is_empty() {
            self.push_diagnostic(
                "invalid-function-option",
                "Option key must be a valid identifier.",
                start,
                end,
            );
            return None;
        }
        Some(ParsedOption {
            name,
            value: parse_literal_or_variable(raw_value),
            next_index,
        })
    }

    fn parse_required_assignment<'b>(
        &mut self,
        tokens: &[&'b str],
        index: usize,
        start: usize,
        end: usize,
    ) -> Option<(&'b str, &'b str, usize)> {
        let token = tokens[index];
        if let Some((key, raw_value)) = token.split_once('=') {
            return self.finish_assignment(key, raw_value, tokens, index + 1, start, end);
        }
        let Some(next) = tokens.get(index + 1) else {
            self.push_diagnostic(
                "invalid-function-option",
                "Options must use key=value syntax.",
                start,
                end,
            );
            return None;
        };
        let Some(raw_value) = next.strip_prefix('=') else {
            self.push_diagnostic(
                "invalid-function-option",
                "Options must use key=value syntax.",
                start,
                end,
            );
            return None;
        };
        self.finish_assignment(token, raw_value, tokens, index + 2, start, end)
    }

    fn finish_assignment<'b>(
        &mut self,
        key: &'b str,
        raw_value: &'b str,
        tokens: &[&'b str],
        next_index: usize,
        start: usize,
        end: usize,
    ) -> Option<(&'b str, &'b str, usize)> {
        if key.is_empty() {
            self.push_diagnostic(
                "invalid-function-option",
                "Option key and value must be non-empty.",
                start,
                end,
            );
            return None;
        }
        if !raw_value.is_empty() {
            return Some((key, raw_value, next_index));
        }
        let Some(next_value) = tokens.get(next_index) else {
            self.push_diagnostic(
                "invalid-function-option",
                "Option key and value must be non-empty.",
                start,
                end,
            );
            return None;
        };
        Some((key, *next_value, next_index + 1))
    }

    fn parse_attribute_tokens(
        &mut self,
        tokens: &[&str],
        index: usize,
        start: usize,
        end: usize,
    ) -> Option<ParsedAttribute> {
        let token = tokens[index];
        let content = token.strip_prefix('@').expect("attribute starts with @");
        if content.is_empty() {
            self.push_diagnostic(
                "missing-attribute-name",
                "Attribute is missing a name.",
                start,
                end,
            );
            return None;
        }

        if let Some((name, raw_value, next_index)) =
            self.parse_optional_attribute_assignment(content, tokens, index, start, end)?
        {
            let value = self.parse_attribute_value(raw_value, start, end)?;
            return Some(ParsedAttribute {
                name,
                value,
                next_index,
            });
        }

        let (name, rest) = split_identifier(content);
        if name.is_empty() || !rest.is_empty() {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute name must be a valid identifier.",
                start,
                end,
            );
            return None;
        }
        Some(ParsedAttribute {
            name,
            value: AttributeValue::Present(true),
            next_index: index + 1,
        })
    }

    fn parse_optional_attribute_assignment<'b>(
        &mut self,
        content: &'b str,
        tokens: &[&'b str],
        index: usize,
        start: usize,
        end: usize,
    ) -> Option<Option<(String, &'b str, usize)>> {
        let Some((name_raw, raw_value, next_index)) =
            self.attribute_assignment_parts(content, tokens, index, start, end)?
        else {
            return Some(None);
        };
        let (name, rest) = split_identifier(name_raw);
        if name.is_empty() || !rest.is_empty() {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute name must be a valid identifier.",
                start,
                end,
            );
            return None;
        }
        Some(Some((name, raw_value, next_index)))
    }

    fn attribute_assignment_parts<'b>(
        &mut self,
        content: &'b str,
        tokens: &[&'b str],
        index: usize,
        start: usize,
        end: usize,
    ) -> Option<Option<(&'b str, &'b str, usize)>> {
        if let Some((name, raw_value)) = content.split_once('=') {
            return self
                .finish_attribute_assignment(name, raw_value, tokens, index + 1, start, end)
                .map(Some);
        }
        let Some(next) = tokens.get(index + 1) else {
            return Some(None);
        };
        let Some(raw_value) = next.strip_prefix('=') else {
            return Some(None);
        };
        self.finish_attribute_assignment(content, raw_value, tokens, index + 2, start, end)
            .map(Some)
    }

    fn finish_attribute_assignment<'b>(
        &mut self,
        name: &'b str,
        raw_value: &'b str,
        tokens: &[&'b str],
        next_index: usize,
        start: usize,
        end: usize,
    ) -> Option<(&'b str, &'b str, usize)> {
        if name.is_empty() {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute key and value must be non-empty.",
                start,
                end,
            );
            return None;
        }
        if !raw_value.is_empty() {
            return Some((name, raw_value, next_index));
        }
        let Some(next_value) = tokens.get(next_index) else {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute key and value must be non-empty.",
                start,
                end,
            );
            return None;
        };
        Some((name, *next_value, next_index + 1))
    }

    fn parse_attribute_value(
        &mut self,
        raw_value: &str,
        start: usize,
        end: usize,
    ) -> Option<AttributeValue> {
        if raw_value.starts_with('|') && raw_value.ends_with('|') && raw_value.len() >= 2 {
            return Some(AttributeValue::Literal(ExpressionArg::Literal {
                value: raw_value[1..raw_value.len() - 1].to_string(),
            }));
        }
        let Some((literal, rest)) = split_unquoted_literal(raw_value) else {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute value must be a literal.",
                start,
                end,
            );
            return None;
        };
        if !rest.is_empty() {
            self.push_diagnostic(
                "invalid-attribute",
                "Attribute value must be a single literal.",
                start,
                end,
            );
            return None;
        }
        Some(AttributeValue::Literal(ExpressionArg::Literal {
            value: literal.to_string(),
        }))
    }

    fn parse_markup_content(&mut self, content: &str, start: usize, end: usize) -> Option<Markup> {
        let (kind, rest) = if let Some(rest) = content.strip_prefix('#') {
            let trimmed = rest.trim_end();
            if let Some(rest) = trimmed.strip_suffix('/') {
                ("standalone", rest.trim_end())
            } else {
                ("open", trimmed)
            }
        } else if let Some(rest) = content.strip_prefix('/') {
            ("close", rest.trim())
        } else {
            unreachable!("caller checked markup prefix")
        };

        let (name, rest) = split_identifier(rest.trim_start());
        if name.is_empty() {
            self.push_diagnostic(
                "missing-markup-name",
                "Markup placeholder is missing a name.",
                start,
                end,
            );
            return None;
        }
        if rest.trim().is_empty() {
            return Some(Markup::new(kind, name));
        }

        let tail = self.parse_markup_tail(rest, start, end)?;
        Some(
            Markup::new(kind, name)
                .with_options(tail.options)
                .with_attributes(tail.attributes),
        )
    }

    fn parse_markup_tail(&mut self, rest: &str, start: usize, end: usize) -> Option<MarkupTail> {
        let tokens = self.split_tail_tokens(rest, start, end)?;
        let mut index = 0;
        let mut options = BTreeMap::new();
        let mut attributes = BTreeMap::new();
        let mut seen_attribute = false;

        while index < tokens.len() {
            if tokens[index].starts_with('@') {
                seen_attribute = true;
                let parsed = self.parse_attribute_tokens(&tokens, index, start, end)?;
                index = parsed.next_index;
                if attributes.contains_key(&parsed.name) {
                    self.push_diagnostic(
                        "duplicate-attribute-name",
                        "Attribute names must be unique within an expression or markup placeholder.",
                        start,
                        end,
                    );
                    return None;
                }
                attributes.insert(parsed.name, parsed.value);
                continue;
            }
            if seen_attribute {
                self.push_diagnostic(
                    "unsupported-markup",
                    "Markup options must come before attributes.",
                    start,
                    end,
                );
                return None;
            }
            if tokens[index].starts_with(':') {
                self.push_diagnostic(
                    "unsupported-markup",
                    "Markup placeholders do not support function annotations.",
                    start,
                    end,
                );
                return None;
            }
            let parsed = self.parse_option_tokens(&tokens, index, start, end)?;
            index = parsed.next_index;
            if options.contains_key(&parsed.name) {
                self.push_diagnostic(
                    "duplicate-option-name",
                    "Option names must be unique within a function or markup placeholder.",
                    start,
                    end,
                );
                return None;
            }
            options.insert(parsed.name, parsed.value);
        }

        Some(MarkupTail {
            options,
            attributes,
        })
    }

    fn parse_variable_name(&mut self) -> Option<String> {
        let start = self.index;
        if self.peek_char() != Some('$') {
            self.push_diagnostic(
                "missing-variable",
                "Expected a variable starting with '$'.",
                start,
                start,
            );
            return None;
        }
        self.advance_char();
        let name_start = self.index;
        let raw_name = &self.source[name_start..];
        let (name, rest) = split_name(raw_name);
        if name.is_empty() {
            let code = variable_name_diagnostic_code(raw_name);
            self.push_diagnostic(code, "Variable is missing a name.", start, self.index);
            return None;
        }
        self.index = self.source.len() - rest.len();
        Some(name.to_string())
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek_char(), Some(ch) if ch.is_whitespace()) {
            self.advance_char();
        }
    }

    fn skip_horizontal_whitespace(&mut self) {
        while matches!(self.peek_char(), Some(' ' | '\t')) {
            self.advance_char();
        }
    }

    fn take_while(&mut self, predicate: impl Fn(char) -> bool) -> String {
        let start = self.index;
        while matches!(self.peek_char(), Some(ch) if predicate(ch)) {
            self.advance_char();
        }
        self.source[start..self.index].to_string()
    }

    fn starts_with(&self, expected: &str) -> bool {
        self.source[self.index..].starts_with(expected)
    }

    fn consume_str(&mut self, expected: &str) -> bool {
        if !self.starts_with(expected) {
            return false;
        }
        self.index += expected.len();
        true
    }

    fn is_done(&self) -> bool {
        self.index >= self.source.len()
    }

    fn peek_char(&self) -> Option<char> {
        self.source[self.index..].chars().next()
    }

    fn advance_char(&mut self) -> Option<char> {
        let ch = self.peek_char()?;
        self.index += ch.len_utf8();
        Some(ch)
    }

    fn push_diagnostic(
        &mut self,
        code: impl Into<String>,
        message: impl Into<String>,
        start: usize,
        end: usize,
    ) {
        self.diagnostics.push(Diagnostic::new(
            code,
            message,
            self.base_offset + start,
            self.base_offset + end,
        ));
    }
}

fn parse_literal_or_variable(raw_value: &str) -> ExpressionArg {
    if let Some(name) = raw_value.strip_prefix('$') {
        let (name, rest) = split_name(name);
        if !name.is_empty() && rest.is_empty() {
            return ExpressionArg::Variable {
                name: name.to_string(),
            };
        }
        ExpressionArg::Variable {
            name: name.to_string(),
        }
    } else if raw_value.starts_with('|') && raw_value.ends_with('|') && raw_value.len() >= 2 {
        ExpressionArg::Literal {
            value: raw_value[1..raw_value.len() - 1].to_string(),
        }
    } else {
        ExpressionArg::Literal {
            value: raw_value.to_string(),
        }
    }
}

fn split_unquoted_literal(input: &str) -> Option<(&str, &str)> {
    let mut scan = 0;
    let mut saw_char = false;
    while let Some(ch) = input[scan..].chars().next() {
        if ch.is_whitespace() || ch == ':' || ch == '@' {
            break;
        }
        if !is_unquoted_literal_char(ch) {
            return None;
        }
        saw_char = true;
        scan += ch.len_utf8();
    }
    saw_char.then_some((&input[..scan], &input[scan..]))
}

fn is_unquoted_literal_char(ch: char) -> bool {
    if ch.is_control() || ch.is_whitespace() {
        return false;
    }
    let code = ch as u32;
    if is_noncharacter(code) {
        return false;
    }
    !matches!(ch, '^' | '!' | '%' | '*' | '<' | '>' | '?' | '~' | '&')
}

fn variable_name_diagnostic_code(input: &str) -> &'static str {
    match input.chars().next() {
        None | Some('}' | ' ' | '\t' | '\n' | '\r') => "missing-variable-name",
        Some(_) => "invalid-variable-name",
    }
}

fn split_name(input: &str) -> (&str, &str) {
    if input.as_bytes().first().is_some_and(|byte| byte.is_ascii()) {
        if let Some(split) = split_ascii_name(input, 0) {
            return split;
        }
    }

    let mut scan = 0;
    if let Some(ch) = input.chars().next() {
        if is_bidi_marker(ch) {
            scan += ch.len_utf8();
        }
    }
    if input[scan..]
        .as_bytes()
        .first()
        .is_some_and(|byte| byte.is_ascii())
    {
        if let Some(split) = split_ascii_name(input, scan) {
            return split;
        }
    }

    let name_start = scan;
    let Some(ch) = input[name_start..].chars().next() else {
        return ("", input);
    };
    if !is_name_start(ch) {
        return ("", input);
    }
    scan += ch.len_utf8();

    while let Some(ch) = input[scan..].chars().next() {
        if !is_name_char(ch) {
            break;
        }
        scan += ch.len_utf8();
    }
    let name_end = scan;

    if let Some(ch) = input[scan..].chars().next() {
        if is_bidi_marker(ch) {
            scan += ch.len_utf8();
        }
    }

    (&input[name_start..name_end], &input[scan..])
}

fn split_ascii_name(input: &str, mut scan: usize) -> Option<(&str, &str)> {
    let name_start = scan;
    let bytes = input.as_bytes();
    let byte = bytes.get(scan).copied()?;
    if !byte.is_ascii() {
        return None;
    }
    if !is_ascii_name_start(byte) {
        return Some(("", input));
    }
    scan += 1;
    while let Some(byte) = bytes.get(scan).copied() {
        if !byte.is_ascii() {
            break;
        }
        if !is_ascii_name_char(byte) {
            break;
        }
        scan += 1;
    }
    if bytes.get(scan).is_some_and(|byte| !byte.is_ascii()) {
        let ch = input[scan..].chars().next().expect("non-ascii char exists");
        if !is_bidi_marker(ch) {
            return None;
        }
        let name_end = scan;
        scan += ch.len_utf8();
        return Some((&input[name_start..name_end], &input[scan..]));
    };
    let name_end = scan;
    Some((&input[name_start..name_end], &input[scan..]))
}

fn split_identifier(input: &str) -> (String, &str) {
    let (namespace_or_name, rest) = split_name(input);
    if namespace_or_name.is_empty() {
        return (String::new(), input);
    }
    let original_rest = rest;
    let Some(after_colon) = rest.strip_prefix(':') else {
        return (namespace_or_name.to_string(), rest);
    };
    let (name, rest) = split_name(after_colon);
    if name.is_empty() {
        return (namespace_or_name.to_string(), original_rest);
    }
    (format!("{namespace_or_name}:{name}"), rest)
}

fn is_name_start(ch: char) -> bool {
    if ch.is_ascii() {
        return ch.is_ascii_alphabetic() || ch == '+' || ch == '_';
    }
    let code = ch as u32;
    code >= 0xA1
        && code <= 0x10FFFD
        && !is_bidi_marker(ch)
        && !ch.is_control()
        && !ch.is_whitespace()
        && !is_noncharacter(code)
}

fn is_name_char(ch: char) -> bool {
    if ch.is_ascii() {
        return ch.is_ascii_alphanumeric() || matches!(ch, '+' | '_' | '-' | '.');
    }
    is_name_start(ch) || is_combining_mark(ch)
}

fn is_ascii_name_start(byte: u8) -> bool {
    byte.is_ascii_alphabetic() || matches!(byte, b'+' | b'_')
}

fn is_ascii_name_char(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'_' | b'-' | b'.')
}

fn is_bidi_marker(ch: char) -> bool {
    matches!(
        ch,
        '\u{061C}' | '\u{200E}' | '\u{200F}' | '\u{2066}'..='\u{2069}'
    )
}

fn is_combining_mark(ch: char) -> bool {
    matches!(
        ch,
        '\u{0300}'..='\u{036F}'
            | '\u{1AB0}'..='\u{1AFF}'
            | '\u{1DC0}'..='\u{1DFF}'
            | '\u{20D0}'..='\u{20FF}'
            | '\u{FE20}'..='\u{FE2F}'
    )
}

fn is_noncharacter(code: u32) -> bool {
    (0xFDD0..=0xFDEF).contains(&code) || (code & 0xFFFE == 0xFFFE)
}
