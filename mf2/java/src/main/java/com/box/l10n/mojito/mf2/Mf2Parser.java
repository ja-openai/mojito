package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Mf2Parser {
    private final String source;
    private final int baseOffset;
    private int index;
    private final List<Mf2ParseDiagnostic> diagnostics = new ArrayList<>();

    private Mf2Parser(String source, int baseOffset) {
        this.source = source;
        this.baseOffset = baseOffset;
    }

    public static Mf2ParseResult parseToModel(String source) {
        Mf2Parser parser = new Mf2Parser(source, 0);
        Mf2Message model = parser.parseMessageModel();
        return new Mf2ParseResult(parser.diagnostics.isEmpty() ? model : null, List.copyOf(parser.diagnostics));
    }

    private Mf2Message parseMessageModel() {
        int messageStart = index;
        List<Mf2Message.Declaration> declarations = parseDeclarations();
        skipSyntaxWhitespace();

        if (startsWith(".match")) {
            return parseMatch(declarations);
        }

        if (startsWith("{{")) {
            List<Mf2Message.PatternPart> pattern = parseQuotedPattern();
            if (pattern == null) {
                return null;
            }
            skipSyntaxWhitespace();
            if (!isDone()) {
                pushDiagnostic(
                        "trailing-content",
                        "Unexpected content after complex message body.",
                        index,
                        source.length());
            }
            return new Mf2Message.PatternMessage(declarations, pattern);
        }

        if (!declarations.isEmpty()) {
            pushDiagnostic(
                    "missing-complex-body",
                    "Complex message declarations must be followed by a quoted pattern or matcher.",
                    index,
                    source.length());
            return null;
        }

        if (startsWith(".")) {
            pushDiagnostic(
                    "invalid-simple-start",
                    "Simple messages cannot start with '.'.",
                    index,
                    index + 1);
            return null;
        }

        index = messageStart;
        return new Mf2Message.PatternMessage(declarations, parsePatternUntilEnd());
    }

    private List<Mf2Message.Declaration> parseDeclarations() {
        List<Mf2Message.Declaration> declarations = new ArrayList<>();
        while (true) {
            int beforePadding = index;
            skipSyntaxWhitespace();
            if (startsWith(".input")) {
                Mf2Message.Declaration declaration = parseInputDeclaration();
                if (declaration != null) {
                    declarations.add(declaration);
                }
                continue;
            }
            if (startsWith(".local")) {
                Mf2Message.Declaration declaration = parseLocalDeclaration();
                if (declaration != null) {
                    declarations.add(declaration);
                }
                continue;
            }
            index = beforePadding;
            return declarations;
        }
    }

    private Mf2Message.Declaration parseInputDeclaration() {
        consumeString(".input");
        skipSyntaxWhitespace();
        int start = index;
        Mf2Message.Expression expression = parseExpressionPlaceholder();
        if (expression == null) {
            return null;
        }
        if (expression.arg() instanceof Mf2Message.VariableArgument variable) {
            return new Mf2Message.InputDeclaration(variable.name(), expression);
        }
        pushDiagnostic(
                "invalid-input-declaration",
                ".input declarations must reference a variable expression.",
                start,
                index);
        return null;
    }

    private Mf2Message.Declaration parseLocalDeclaration() {
        consumeString(".local");
        skipSyntaxWhitespace();
        int start = index;
        String name = parseVariableName();
        if (name == null) {
            return null;
        }
        skipSyntaxWhitespace();
        if (peekChar() != '=') {
            pushDiagnostic(
                    "missing-local-equals",
                    ".local declarations must include '='.",
                    start,
                    index);
            return null;
        }
        advanceChar();
        skipSyntaxWhitespace();
        Mf2Message.Expression value = parseExpressionPlaceholder();
        return value == null ? null : new Mf2Message.LocalDeclaration(name, value);
    }

    private Mf2Message parseMatch(List<Mf2Message.Declaration> declarations) {
        consumeString(".match");
        List<Mf2Message.VariableRef> selectors = new ArrayList<>();
        if (!isDone() && peekChar() == '$') {
            pushDiagnostic(
                    "missing-match-space",
                    ".match selectors must be separated by whitespace.",
                    index,
                    index);
            return null;
        }
        while (true) {
            boolean skippedSpace = skipSyntaxGap();
            if (!isDone() && peekChar() == '$') {
                if (!skippedSpace && !selectors.isEmpty()) {
                    pushDiagnostic(
                            "missing-match-space",
                            ".match selectors must be separated by whitespace.",
                            index,
                            index);
                    return null;
                }
                String name = parseVariableName();
                if (name != null) {
                    selectors.add(new Mf2Message.VariableRef(name));
                }
                if (!isDone() && !Character.isWhitespace(peekChar())) {
                    pushDiagnostic(
                            "missing-match-space",
                            ".match selectors must be separated from variants by whitespace.",
                            index,
                            index);
                    return null;
                }
                continue;
            }
            break;
        }

        if (selectors.isEmpty()) {
            pushDiagnostic(
                    "missing-match-selector",
                    ".match must include at least one selector variable.",
                    index,
                    index);
            return null;
        }

        List<Mf2Message.Variant> variants = new ArrayList<>();
        while (true) {
            skipSyntaxWhitespace();
            if (isDone()) {
                break;
            }
            int variantStart = index;
            List<Mf2Message.VariantKey> keys = parseVariantKeys(variantStart);
            if (keys == null) {
                return null;
            }
            skipSyntaxWhitespace();
            if (!startsWith("{{")) {
                pushDiagnostic(
                        "missing-variant-pattern",
                        "Variant keys must be followed by a quoted pattern.",
                        variantStart,
                        index);
                return null;
            }
            List<Mf2Message.PatternPart> value = parseQuotedPattern();
            if (value == null) {
                return null;
            }
            if (keys.size() != selectors.size()) {
                pushDiagnostic(
                        "variant-key-count-mismatch",
                        "Variant key count must match selector count.",
                        variantStart,
                        index);
                return null;
            }
            variants.add(new Mf2Message.Variant(keys, value));
        }

        if (variants.isEmpty()) {
            pushDiagnostic(
                    "missing-match-variants",
                    ".match must include at least one variant.",
                    index,
                    index);
            return null;
        }

        return new Mf2Message.SelectMessage(declarations, selectors, variants);
    }

    private List<Mf2Message.VariantKey> parseVariantKeys(int start) {
        List<Mf2Message.VariantKey> keys = new ArrayList<>();
        while (!isDone() && !startsWith("{{") && peekChar() != '\n') {
            boolean skippedSpace = skipSyntaxGap();
            if (startsWith("{{") || peekChar() == '\n' || isDone()) {
                break;
            }
            if (!keys.isEmpty() && !skippedSpace) {
                pushDiagnostic(
                        "missing-variant-key-space",
                        "Variant keys must be separated by whitespace.",
                        start,
                        index);
                return null;
            }
            if (peekChar() == '*') {
                advanceChar();
                keys.add(new Mf2Message.CatchAllVariantKey());
                continue;
            }
            if (peekChar() == '|') {
                String rest = source.substring(index);
                LiteralSplit split = parseQuotedLiteral(rest);
                if (split == null) {
                    pushDiagnostic(
                            "unclosed-quoted-literal",
                            "Quoted variant key is missing closing '|'.",
                            index,
                            source.length());
                    return null;
                }
                index += rest.length() - split.rest().length();
                keys.add(new Mf2Message.LiteralVariantKey(split.value()));
                continue;
            }
            String key = takeWhile(ch -> !isSyntaxWhitespace(ch) && ch != '{');
            if (!key.isEmpty()) {
                keys.add(new Mf2Message.LiteralVariantKey(key));
            }
        }
        return keys;
    }

    private List<Mf2Message.PatternPart> parseQuotedPattern() {
        int start = index;
        if (!consumeString("{{")) {
            pushDiagnostic(
                    "missing-quoted-pattern",
                    "Expected a quoted pattern starting with '{{'.",
                    start,
                    start);
            return null;
        }

        int contentStart = index;
        int scan = index;
        int placeholderDepth = 0;
        while (scan < source.length()) {
            if (placeholderDepth == 0 && source.startsWith("}}", scan)) {
                String content = source.substring(contentStart, scan);
                index = scan + 2;
                Mf2Parser nested = new Mf2Parser(content, baseOffset + contentStart);
                List<Mf2Message.PatternPart> pattern = nested.parsePatternUntilEnd();
                if (!nested.diagnostics.isEmpty()) {
                    diagnostics.addAll(nested.diagnostics);
                }
                return pattern;
            }

            int codePoint = source.codePointAt(scan);
            if (codePoint == '\\') {
                scan += Character.charCount(codePoint);
                if (scan < source.length()) {
                    scan += Character.charCount(source.codePointAt(scan));
                }
                continue;
            }
            if (codePoint == '{') {
                placeholderDepth++;
            } else if (codePoint == '}' && placeholderDepth > 0) {
                placeholderDepth--;
            }
            scan += Character.charCount(codePoint);
        }

        pushDiagnostic(
                "unclosed-quoted-pattern",
                "Quoted pattern is missing closing '}}'.",
                start,
                source.length());
        return null;
    }

    private List<Mf2Message.PatternPart> parsePatternUntilEnd() {
        List<Mf2Message.PatternPart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        while (!isDone()) {
            char ch = peekChar();
            switch (ch) {
                case '\\' -> parseEscapeInto(text);
                case '{' -> {
                    if (!text.isEmpty()) {
                        parts.add(new Mf2Message.TextPart(text.toString()));
                        text.setLength(0);
                    }
                    Mf2Message.PatternPart part = parseBracedPatternPart();
                    if (part != null) {
                        parts.add(part);
                    }
                }
                case '}' -> {
                    int start = index;
                    advanceChar();
                    pushDiagnostic(
                            "unescaped-closing-brace",
                            "Closing brace must be escaped in text.",
                            start,
                            index);
                }
                default -> {
                    text.append(ch);
                    advanceChar();
                }
            }
        }

        if (!text.isEmpty()) {
            parts.add(new Mf2Message.TextPart(text.toString()));
        }
        return parts;
    }

    private void parseEscapeInto(StringBuilder text) {
        int start = index;
        advanceChar();
        if (isDone()) {
            pushDiagnostic(
                    "dangling-escape",
                    "Backslash at end of message has no escaped character.",
                    start,
                    start + 1);
            return;
        }
        char ch = peekChar();
        if (ch == '{' || ch == '}' || ch == '\\') {
            text.append(advanceChar());
        } else {
            text.append('\\');
        }
    }

    private Mf2Message.PatternPart parseBracedPatternPart() {
        int start = index;
        String content = consumeBracedContent();
        if (content == null) {
            return null;
        }
        String trimmed = stripSyntaxWhitespace(content);
        if (trimmed.startsWith("#") || trimmed.startsWith("/")) {
            Mf2Message.Markup markup = parseMarkupContent(trimmed, start, start + content.length() + 2);
            return markup == null ? null : new Mf2Message.MarkupPart(markup);
        }
        Mf2Message.Expression expression = parseExpressionContent(trimmed, start, start + content.length() + 2);
        return expression == null ? null : new Mf2Message.ExpressionPart(expression);
    }

    private Mf2Message.Expression parseExpressionPlaceholder() {
        int start = index;
        String content = consumeBracedContent();
        return content == null
                ? null
                : parseExpressionContent(stripSyntaxWhitespace(content), start, start + content.length() + 2);
    }

    private String consumeBracedContent() {
        int start = index;
        if (peekChar() != '{') {
            pushDiagnostic(
                    "missing-placeholder",
                    "Expected a placeholder starting with '{'.",
                    start,
                    start);
            return null;
        }
        advanceChar();
        int contentStart = index;
        boolean inQuote = false;
        while (!isDone()) {
            char ch = peekChar();
            if (inQuote) {
                if (ch == '\\') {
                    advanceChar();
                    if (!isDone()) {
                        advanceChar();
                    }
                    continue;
                }
                if (ch == '}') {
                    String content = source.substring(contentStart, index);
                    advanceChar();
                    return content;
                }
                if (ch == '|') {
                    inQuote = false;
                }
                advanceChar();
                continue;
            }
            if (ch == '|') {
                inQuote = true;
                advanceChar();
                continue;
            }
            if (ch == '}') {
                String content = source.substring(contentStart, index);
                advanceChar();
                return content;
            }
            advanceChar();
        }
        pushDiagnostic(
                "unclosed-placeholder",
                "Placeholder is missing a closing brace.",
                start,
                source.length());
        return null;
    }

    private Mf2Message.Expression parseExpressionContent(String content, int start, int end) {
        Mf2Message.Expression expression;
        String rest;
        if (content.startsWith("$")) {
            String rawName = content.substring(1);
            NameSplit split = splitName(rawName);
            if (split.name().isEmpty()) {
                pushDiagnostic(
                        variableNameDiagnosticCode(rawName),
                        "Variable placeholder is missing a name.",
                        start,
                        end);
                return null;
            }
            expression = new Mf2Message.Expression(
                    new Mf2Message.VariableArgument(split.name()), null, Map.of());
            rest = restAfterOperand(split.rest(), start, end);
            if (rest == null) {
                return null;
            }
        } else if (content.startsWith("|")) {
            LiteralSplit split = parseQuotedLiteral(content);
            if (split == null) {
                pushDiagnostic(
                        "unclosed-quoted-literal",
                        "Quoted literal is missing closing '|'.",
                        start,
                        end);
                return null;
            }
            expression = new Mf2Message.Expression(
                    new Mf2Message.LiteralArgument(split.value()), null, Map.of());
            rest = restAfterOperand(split.rest(), start, end);
            if (rest == null) {
                return null;
            }
        } else if (content.startsWith(":")) {
            expression = new Mf2Message.Expression(null, null, Map.of());
            rest = content;
        } else {
            LiteralSplit split = splitUnquotedLiteral(content);
            if (split == null) {
                pushDiagnostic(
                        content.isEmpty() ? "missing-expression" : "invalid-literal",
                        "Placeholder literal is invalid.",
                        start,
                        end);
                return null;
            }
            expression = new Mf2Message.Expression(
                    new Mf2Message.LiteralArgument(split.value()), null, Map.of());
            rest = restAfterOperand(split.rest(), start, end);
            if (rest == null) {
                return null;
            }
        }

        if (rest.isEmpty()) {
            return expression;
        }

        Tail tail = parseTail(rest, start, end);
        if (tail == null) {
            return null;
        }
        return new Mf2Message.Expression(expression.arg(), tail.function(), tail.attributes());
    }

    private String restAfterOperand(String rest, int start, int end) {
        if (rest.isEmpty()) {
            return rest;
        }
        if (!Character.isWhitespace(rest.codePointAt(0))) {
            pushDiagnostic(
                    "missing-expression-space",
                    "Expression arguments must be separated from functions or attributes by whitespace.",
                    start,
                    end);
            return null;
        }
        return stripLeadingSyntaxWhitespace(rest);
    }

    private Tail parseTail(String rest, int start, int end) {
        if (rest.isBlank()) {
            return new Tail(null, Map.of());
        }

        Mf2Message.FunctionRef function = null;
        Map<String, Mf2Message.AttributeValue> attributes = new LinkedHashMap<>();
        List<String> tokens = splitTailTokens(rest, start, end);
        if (tokens == null) {
            return null;
        }
        int index = 0;
        if (index < tokens.size() && tokens.get(index).startsWith(":")) {
            FunctionParseResult result = parseFunctionAnnotation(tokens, index, start, end);
            if (result == null) {
                return null;
            }
            function = result.function();
            index = result.nextIndex();
        }
        while (index < tokens.size()) {
            String token = tokens.get(index++);
            if (!token.startsWith("@")) {
                pushDiagnostic(
                        "unsupported-expression",
                        "Expression content after the argument must be a function annotation or attribute.",
                        start,
                        end);
                return null;
            }
            AttributeParseResult attribute = parseAttributeTokens(tokens, index - 1, start, end);
            if (attribute == null) {
                return null;
            }
            index = attribute.nextIndex();
            if (attributes.containsKey(attribute.name())) {
                pushDiagnostic(
                        "duplicate-attribute-name",
                        "Attribute names must be unique within an expression or markup placeholder.",
                        start,
                        end);
                return null;
            }
            attributes.put(attribute.name(), attribute.value());
        }

        return new Tail(function, attributes);
    }

    private List<String> splitTailTokens(String rest, int start, int end) {
        List<String> tokens = new ArrayList<>();
        int tokenStart = -1;
        boolean inQuote = false;

        for (int index = 0; index < rest.length(); ) {
            int codePoint = rest.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            if (inQuote && codePoint == '\\') {
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                index += charCount;
                if (index < rest.length()) {
                    index += Character.charCount(rest.codePointAt(index));
                }
                continue;
            }
            if (codePoint == '|') {
                inQuote = !inQuote;
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                index += charCount;
                continue;
            }
            if (isSyntaxWhitespace(codePoint) && !inQuote) {
                if (tokenStart >= 0) {
                    tokens.add(rest.substring(tokenStart, index));
                    tokenStart = -1;
                }
                index += charCount;
                continue;
            }
            if (tokenStart < 0) {
                tokenStart = index;
            }
            index += charCount;
        }

        if (inQuote) {
            pushDiagnostic(
                    "unclosed-quoted-literal",
                    "Quoted literal is missing closing '|'.",
                    start,
                    end);
            return null;
        }
        if (tokenStart >= 0) {
            tokens.add(rest.substring(tokenStart));
        }
        return tokens;
    }

    private FunctionParseResult parseFunctionAnnotation(List<String> tokens, int index, int start, int end) {
        String content = tokens.get(index).substring(1);
        NameSplit split = splitIdentifier(content);
        if (split.name().isEmpty()) {
            pushDiagnostic(
                    content.isEmpty() ? "missing-function-name" : "invalid-function-name",
                    "Function annotation is missing a name.",
                    start,
                    end);
            return null;
        }
        if (!split.rest().isEmpty()) {
            pushDiagnostic(
                    "unsupported-expression",
                    "Function annotation must separate options with whitespace.",
                    start,
                    end);
            return null;
        }

        Map<String, Mf2Message.ExpressionArgument> options = new LinkedHashMap<>();
        index++;
        while (index < tokens.size() && !tokens.get(index).startsWith("@")) {
            OptionParseResult option = parseOptionTokens(tokens, index, start, end);
            if (option == null) {
                return null;
            }
            index = option.nextIndex();
            if (options.containsKey(option.name())) {
                pushDiagnostic(
                        "duplicate-option-name",
                        "Option names must be unique within a function or markup placeholder.",
                        start,
                        end);
                return null;
            }
            options.put(option.name(), option.value());
        }

        return new FunctionParseResult(new Mf2Message.FunctionRef(split.name(), options), index);
    }

    private OptionParseResult parseOptionTokens(List<String> tokens, int index, int start, int end) {
        Assignment assignment = parseRequiredAssignment(tokens, index, start, end);
        if (assignment == null) {
            return null;
        }
        NameSplit keySplit = splitIdentifier(assignment.key());
        if (keySplit.name().isEmpty() || !keySplit.rest().isEmpty()) {
            pushDiagnostic(
                    "invalid-function-option",
                    "Option key must be a valid identifier.",
                    start,
                    end);
            return null;
        }
        return new OptionParseResult(
                keySplit.name(),
                parseLiteralOrVariable(stripSyntaxWhitespace(assignment.rawValue())),
                assignment.nextIndex());
    }

    private Assignment parseRequiredAssignment(List<String> tokens, int index, int start, int end) {
        String token = tokens.get(index);
        int equals = token.indexOf('=');
        if (equals >= 0) {
            return finishAssignment(
                    token.substring(0, equals),
                    token.substring(equals + 1),
                    tokens,
                    index + 1,
                    start,
                    end);
        }
        if (index + 1 >= tokens.size()) {
            pushDiagnostic(
                    "invalid-function-option",
                    "Options must use key=value syntax.",
                    start,
                    end);
            return null;
        }
        String next = tokens.get(index + 1);
        if (!next.startsWith("=")) {
            pushDiagnostic(
                    "invalid-function-option",
                    "Options must use key=value syntax.",
                    start,
                    end);
            return null;
        }
        return finishAssignment(token, next.substring(1), tokens, index + 2, start, end);
    }

    private Assignment finishAssignment(
            String key, String rawValue, List<String> tokens, int nextIndex, int start, int end) {
        if (key.isEmpty()) {
            pushDiagnostic(
                    "invalid-function-option",
                    "Option key and value must be non-empty.",
                    start,
                    end);
            return null;
        }
        if (!rawValue.isEmpty()) {
            return new Assignment(key, rawValue, nextIndex);
        }
        if (nextIndex >= tokens.size()) {
            pushDiagnostic(
                    "invalid-function-option",
                    "Option key and value must be non-empty.",
                    start,
                    end);
            return null;
        }
        return new Assignment(key, tokens.get(nextIndex), nextIndex + 1);
    }

    private AttributeParseResult parseAttributeTokens(List<String> tokens, int index, int start, int end) {
        String token = tokens.get(index);
        String content = token.substring(1);
        if (content.isEmpty()) {
            pushDiagnostic("missing-attribute-name", "Attribute is missing a name.", start, end);
            return null;
        }

        if (!hasAttributeAssignment(content, tokens, index)) {
            NameSplit split = splitIdentifier(content);
            if (split.name().isEmpty() || !split.rest().isEmpty()) {
                pushDiagnostic(
                        "invalid-attribute",
                        "Attribute name must be a valid identifier.",
                        start,
                        end);
                return null;
            }
            return new AttributeParseResult(split.name(), new Mf2Message.PresentAttribute(true), index + 1);
        }

        AttributeAssignment assignment = parseAttributeAssignment(content, tokens, index, start, end);
        if (assignment == null) {
            return null;
        }
        Mf2Message.AttributeValue value = parseAttributeValue(assignment.rawValue(), start, end);
        return value == null ? null : new AttributeParseResult(assignment.name(), value, assignment.nextIndex());
    }

    private boolean hasAttributeAssignment(String content, List<String> tokens, int index) {
        return content.indexOf('=') >= 0
                || (index + 1 < tokens.size() && tokens.get(index + 1).startsWith("="));
    }

    private AttributeAssignment parseAttributeAssignment(
            String content, List<String> tokens, int index, int start, int end) {
        Assignment assignment = attributeAssignmentParts(content, tokens, index, start, end);
        if (assignment == null) {
            return null;
        }
        NameSplit split = splitIdentifier(assignment.key());
        if (split.name().isEmpty() || !split.rest().isEmpty()) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute name must be a valid identifier.",
                    start,
                    end);
            return null;
        }
        return new AttributeAssignment(split.name(), assignment.rawValue(), assignment.nextIndex());
    }

    private Assignment attributeAssignmentParts(
            String content, List<String> tokens, int index, int start, int end) {
        int equals = content.indexOf('=');
        if (equals >= 0) {
            return finishAttributeAssignment(
                    content.substring(0, equals),
                    content.substring(equals + 1),
                    tokens,
                    index + 1,
                    start,
                    end);
        }
        if (index + 1 >= tokens.size()) {
            return null;
        }
        String next = tokens.get(index + 1);
        if (!next.startsWith("=")) {
            return null;
        }
        return finishAttributeAssignment(content, next.substring(1), tokens, index + 2, start, end);
    }

    private Assignment finishAttributeAssignment(
            String key, String rawValue, List<String> tokens, int nextIndex, int start, int end) {
        if (key.isEmpty()) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute key and value must be non-empty.",
                    start,
                    end);
            return null;
        }
        if (!rawValue.isEmpty()) {
            return new Assignment(key, rawValue, nextIndex);
        }
        if (nextIndex >= tokens.size()) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute key and value must be non-empty.",
                    start,
                    end);
            return null;
        }
        return new Assignment(key, tokens.get(nextIndex), nextIndex + 1);
    }

    private Mf2Message.AttributeValue parseAttributeValue(String rawValue, int start, int end) {
        rawValue = stripSyntaxWhitespace(rawValue);
        if (rawValue.startsWith("|") && rawValue.endsWith("|") && rawValue.length() >= 2) {
            LiteralSplit split = parseQuotedLiteral(rawValue);
            if (split == null) {
                pushDiagnostic(
                        "unclosed-quoted-literal",
                        "Quoted literal is missing closing '|'.",
                        start,
                        end);
                return null;
            }
            if (!split.rest().isEmpty()) {
                pushDiagnostic(
                        "invalid-attribute",
                        "Attribute value must be a single literal.",
                        start,
                        end);
                return null;
            }
            return new Mf2Message.LiteralAttribute(new Mf2Message.LiteralArgument(split.value()));
        }
        LiteralSplit split = splitUnquotedLiteral(rawValue);
        if (split == null || !split.rest().isEmpty()) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute value must be a single literal.",
                    start,
                    end);
            return null;
        }
        return new Mf2Message.LiteralAttribute(new Mf2Message.LiteralArgument(split.value()));
    }

    private Mf2Message.Markup parseMarkupContent(String content, int start, int end) {
        String kind;
        String rest;
        if (content.startsWith("#")) {
            String trimmed = stripTrailingSyntaxWhitespace(content.substring(1));
            if (trimmed.endsWith("/")) {
                kind = "standalone";
                rest = stripTrailingSyntaxWhitespace(trimmed.substring(0, trimmed.length() - 1));
            } else {
                kind = "open";
                rest = trimmed;
            }
        } else {
            kind = "close";
            rest = stripSyntaxWhitespace(content.substring(1));
        }

        NameSplit split = splitIdentifier(stripLeadingSyntaxWhitespace(rest));
        if (split.name().isEmpty()) {
            pushDiagnostic(
                    "missing-markup-name",
                    "Markup placeholder is missing a name.",
                    start,
                    end);
            return null;
        }
        if (stripSyntaxWhitespace(split.rest()).isEmpty()) {
            return new Mf2Message.Markup(kind, split.name(), Map.of(), Map.of());
        }
        MarkupTail tail = parseMarkupTail(split.rest(), start, end);
        if (tail == null) {
            return null;
        }
        return new Mf2Message.Markup(kind, split.name(), tail.options(), tail.attributes());
    }

    private MarkupTail parseMarkupTail(String rest, int start, int end) {
        List<String> tokens = splitTailTokens(rest, start, end);
        if (tokens == null) {
            return null;
        }
        Map<String, Mf2Message.ExpressionArgument> options = new LinkedHashMap<>();
        Map<String, Mf2Message.AttributeValue> attributes = new LinkedHashMap<>();
        boolean seenAttribute = false;
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (token.startsWith("@")) {
                seenAttribute = true;
                AttributeParseResult attribute = parseAttributeTokens(tokens, index, start, end);
                if (attribute == null) {
                    return null;
                }
                index = attribute.nextIndex();
                if (attributes.containsKey(attribute.name())) {
                    pushDiagnostic(
                            "duplicate-attribute-name",
                            "Attribute names must be unique within an expression or markup placeholder.",
                            start,
                            end);
                    return null;
                }
                attributes.put(attribute.name(), attribute.value());
                continue;
            }
            if (seenAttribute) {
                pushDiagnostic(
                        "unsupported-markup",
                        "Markup options must come before attributes.",
                        start,
                        end);
                return null;
            }
            if (token.startsWith(":")) {
                pushDiagnostic(
                        "unsupported-markup",
                        "Markup placeholders do not support function annotations.",
                        start,
                        end);
                return null;
            }
            OptionParseResult option = parseOptionTokens(tokens, index, start, end);
            if (option == null) {
                return null;
            }
            index = option.nextIndex();
            if (options.containsKey(option.name())) {
                pushDiagnostic(
                        "duplicate-option-name",
                        "Option names must be unique within a function or markup placeholder.",
                        start,
                        end);
                return null;
            }
            options.put(option.name(), option.value());
        }
        return new MarkupTail(options, attributes);
    }

    private String parseVariableName() {
        int start = index;
        if (peekChar() != '$') {
            pushDiagnostic(
                    "missing-variable",
                    "Expected a variable starting with '$'.",
                    start,
                    start);
            return null;
        }
        advanceChar();
        NameScan scan = scanName(source, index);
        if (scan.name().isEmpty()) {
            pushDiagnostic(
                    variableNameDiagnosticCode(source, index),
                    "Variable is missing a name.",
                    start,
                    index);
            return null;
        }
        index = scan.endIndex();
        return scan.name();
    }

    private boolean skipSyntaxWhitespace() {
        int start = index;
        while (!isDone()) {
            int codePoint = source.codePointAt(index);
            if (!isSyntaxWhitespace(codePoint)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index != start;
    }

    private boolean skipSyntaxGap() {
        boolean sawWhitespace = false;
        while (!isDone()) {
            int codePoint = source.codePointAt(index);
            if (Character.isWhitespace(codePoint)) {
                sawWhitespace = true;
                index += Character.charCount(codePoint);
                continue;
            }
            if (isBidiMarker(codePoint)) {
                index += Character.charCount(codePoint);
                continue;
            }
            break;
        }
        return sawWhitespace;
    }

    private String takeWhile(CharPredicate predicate) {
        int start = index;
        while (!isDone() && predicate.test(peekChar())) {
            advanceChar();
        }
        return source.substring(start, index);
    }

    private boolean startsWith(String expected) {
        return source.startsWith(expected, index);
    }

    private boolean consumeString(String expected) {
        if (!startsWith(expected)) {
            return false;
        }
        index += expected.length();
        return true;
    }

    private boolean isDone() {
        return index >= source.length();
    }

    private char peekChar() {
        return source.charAt(index);
    }

    private char advanceChar() {
        char ch = source.charAt(index);
        index += Character.charCount(ch);
        return ch;
    }

    private void pushDiagnostic(String code, String message, int start, int end) {
        diagnostics.add(new Mf2ParseDiagnostic(code, message, baseOffset + start, baseOffset + end));
    }

    private static Mf2Message.ExpressionArgument parseLiteralOrVariable(String rawValue) {
        if (rawValue.startsWith("$")) {
            NameSplit split = splitName(rawValue.substring(1));
            if (!split.name().isEmpty() && split.rest().isEmpty()) {
                return new Mf2Message.VariableArgument(split.name());
            }
            return new Mf2Message.VariableArgument(rawValue.substring(1));
        }
        LiteralSplit quoted = parseQuotedLiteral(rawValue);
        if (quoted != null && quoted.rest().isEmpty()) {
            return new Mf2Message.LiteralArgument(quoted.value());
        }
        return new Mf2Message.LiteralArgument(rawValue);
    }

    private static LiteralSplit parseQuotedLiteral(String input) {
        if (!input.startsWith("|")) {
            return null;
        }
        StringBuilder output = new StringBuilder();
        int index = 1;
        while (index < input.length()) {
            int codePoint = input.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '|' -> {
                    return new LiteralSplit(output.toString(), input.substring(index));
                }
                case '\\' -> {
                    if (index >= input.length()) {
                        output.append('\\');
                        break;
                    }
                    int escaped = input.codePointAt(index);
                    if (escaped == '\\' || escaped == '{' || escaped == '|' || escaped == '}') {
                        output.appendCodePoint(escaped);
                        index += Character.charCount(escaped);
                    } else {
                        output.append('\\');
                    }
                }
                default -> output.appendCodePoint(codePoint);
            }
        }
        return null;
    }

    private static LiteralSplit splitUnquotedLiteral(String input) {
        int scan = 0;
        boolean sawChar = false;
        while (scan < input.length()) {
            int codePoint = input.codePointAt(scan);
            if (isSyntaxWhitespace(codePoint) || codePoint == ':' || codePoint == '@') {
                break;
            }
            if (!isUnquotedLiteralChar(codePoint)) {
                return null;
            }
            sawChar = true;
            scan += Character.charCount(codePoint);
        }
        return sawChar ? new LiteralSplit(input.substring(0, scan), input.substring(scan)) : null;
    }

    private static boolean isUnquotedLiteralChar(int codePoint) {
        if (Character.isISOControl(codePoint) || isSyntaxWhitespace(codePoint) || isNoncharacter(codePoint)) {
            return false;
        }
        return switch (codePoint) {
            case '^', '!', '%', '*', '<', '>', '?', '~', '&', '\\', '$' -> false;
            default -> true;
        };
    }

    private static String variableNameDiagnosticCode(String input) {
        return variableNameDiagnosticCode(input, 0);
    }

    private static String variableNameDiagnosticCode(String input, int offset) {
        if (offset >= input.length()) {
            return "missing-variable-name";
        }
        return switch (input.charAt(offset)) {
            case '}', ' ', '\t', '\n', '\r' -> "missing-variable-name";
            default -> "invalid-variable-name";
        };
    }

    private static NameSplit splitName(String input) {
        NameScan scan = scanName(input, 0);
        return new NameSplit(scan.name(), input.substring(scan.endIndex()), scan.endIndex());
    }

    private static NameScan scanName(String input, int offset) {
        int absoluteScan = offset;
        if (absoluteScan < input.length()) {
            int codePoint = input.codePointAt(absoluteScan);
            if (isBidiMarker(codePoint)) {
                absoluteScan += Character.charCount(codePoint);
            }
        }

        int nameStart = absoluteScan;
        if (nameStart >= input.length()) {
            return new NameScan("", offset);
        }
        char first = input.charAt(nameStart);
        if (first <= 0x7F) {
            NameScan ascii = scanAsciiName(input, offset, nameStart);
            if (ascii != null) {
                return ascii;
            }
        }
        int codePoint = input.codePointAt(nameStart);
        if (!isNameStart(codePoint)) {
            return new NameScan("", offset);
        }
        absoluteScan += Character.charCount(codePoint);

        while (absoluteScan < input.length()) {
            codePoint = input.codePointAt(absoluteScan);
            if (!isNameChar(codePoint)) {
                break;
            }
            absoluteScan += Character.charCount(codePoint);
        }
        int nameEnd = absoluteScan;

        if (absoluteScan < input.length()) {
            codePoint = input.codePointAt(absoluteScan);
            if (isBidiMarker(codePoint)) {
                absoluteScan += Character.charCount(codePoint);
            }
        }

        return new NameScan(input.substring(nameStart, nameEnd), absoluteScan);
    }

    private static NameScan scanAsciiName(String input, int offset, int nameStart) {
        char first = input.charAt(nameStart);
        if (!isAsciiNameStart(first)) {
            return new NameScan("", offset);
        }
        int scan = nameStart + 1;
        while (scan < input.length() && isAsciiNameChar(input.charAt(scan))) {
            scan++;
        }
        int nameEnd = scan;
        if (scan < input.length()) {
            int codePoint = input.codePointAt(scan);
            if (codePoint > 0x7F) {
                if (!isBidiMarker(codePoint)) {
                    return null;
                }
                return new NameScan(
                        input.substring(nameStart, nameEnd),
                        scan + Character.charCount(codePoint));
            }
        }
        return new NameScan(input.substring(nameStart, nameEnd), scan);
    }

    private static NameSplit splitIdentifier(String input) {
        NameSplit namespaceOrName = splitName(input);
        if (namespaceOrName.name().isEmpty()) {
            return new NameSplit("", input, 0);
        }
        if (!namespaceOrName.rest().startsWith(":")) {
            return namespaceOrName;
        }
        NameSplit name = splitName(namespaceOrName.rest().substring(1));
        if (name.name().isEmpty()) {
            return namespaceOrName;
        }
        return new NameSplit(
                namespaceOrName.name() + ":" + name.name(),
                name.rest(),
                namespaceOrName.consumedLength() + 1 + name.consumedLength());
    }

    private static boolean isNameStart(int codePoint) {
        if (codePoint <= 0x7F) {
            return isAsciiNameStart((char) codePoint);
        }
        int type = Character.getType(codePoint);
        return codePoint >= 0xA1
                && codePoint <= 0x10FFFD
                && !isBidiMarker(codePoint)
                && type != Character.CONTROL
                && type != Character.SURROGATE
                && !isSyntaxWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)
                && !isNoncharacter(codePoint);
    }

    private static boolean isNameChar(int codePoint) {
        if (codePoint <= 0x7F) {
            return isAsciiNameChar((char) codePoint);
        }
        return isNameStart(codePoint)
                || (codePoint >= '0' && codePoint <= '9')
                || isCombiningMark(codePoint)
                || codePoint == '-'
                || codePoint == '.';
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isAsciiNameStart(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || ch == '+'
                || ch == '_';
    }

    private static boolean isAsciiNameChar(char ch) {
        return isAsciiNameStart(ch) || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.';
    }

    private static boolean isBidiMarker(int codePoint) {
        return codePoint == 0x061C
                || codePoint == 0x200E
                || codePoint == 0x200F
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static boolean isSyntaxWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || isBidiMarker(codePoint);
    }

    private static boolean isNoncharacter(int codePoint) {
        return (codePoint >= 0xFDD0 && codePoint <= 0xFDEF)
                || ((codePoint & 0xFFFE) == 0xFFFE);
    }

    private static String stripSyntaxWhitespace(String value) {
        return stripTrailingSyntaxWhitespace(stripLeadingSyntaxWhitespace(value));
    }

    private static String stripLeadingSyntaxWhitespace(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isSyntaxWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        return value.substring(start);
    }

    private static String stripTrailingSyntaxWhitespace(String value) {
        int end = value.length();
        while (end > 0) {
            int codePoint = value.codePointBefore(end);
            if (!isSyntaxWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private record NameSplit(String name, String rest, int consumedLength) {}

    private record NameScan(String name, int endIndex) {}

    private record LiteralSplit(String value, String rest) {}

    private record Tail(Mf2Message.FunctionRef function, Map<String, Mf2Message.AttributeValue> attributes) {}

    private record MarkupTail(
            Map<String, Mf2Message.ExpressionArgument> options,
            Map<String, Mf2Message.AttributeValue> attributes) {}

    private record FunctionParseResult(Mf2Message.FunctionRef function, int nextIndex) {}

    private record OptionParseResult(
            String name, Mf2Message.ExpressionArgument value, int nextIndex) {}

    private record AttributeParseResult(String name, Mf2Message.AttributeValue value, int nextIndex) {}

    private record AttributeAssignment(String name, String rawValue, int nextIndex) {}

    private record Assignment(String key, String rawValue, int nextIndex) {}

    @FunctionalInterface
    private interface CharPredicate {
        boolean test(char ch);
    }
}
