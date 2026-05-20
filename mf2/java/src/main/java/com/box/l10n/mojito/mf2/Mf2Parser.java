package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Mf2Parser {
    private final String source;
    private final int baseOffset;
    private int index;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private Mf2Parser(String source, int baseOffset) {
        this.source = source;
        this.baseOffset = baseOffset;
    }

    static ParseResult parseToModel(String source) {
        Mf2Parser parser = new Mf2Parser(source, 0);
        Mf2Message model = parser.parseMessageModel();
        return new ParseResult(parser.diagnostics.isEmpty() ? model : null, List.copyOf(parser.diagnostics));
    }

    private Mf2Message parseMessageModel() {
        List<Mf2Message.Declaration> declarations = parseDeclarations();
        skipHorizontalWhitespace();

        if (startsWith(".match")) {
            return parseMatch(declarations);
        }

        if (startsWith("{{")) {
            List<Mf2Message.PatternPart> pattern = parseQuotedPattern();
            if (pattern == null) {
                return null;
            }
            skipWhitespace();
            if (!isDone()) {
                pushDiagnostic(
                        "trailing-content",
                        "Unexpected content after complex message body.",
                        index,
                        source.length());
            }
            return new Mf2Message.Message(declarations, pattern);
        }

        if (!declarations.isEmpty()) {
            pushDiagnostic(
                    "missing-complex-body",
                    "Complex message declarations must be followed by a quoted pattern or matcher.",
                    index,
                    source.length());
            return null;
        }

        return new Mf2Message.Message(declarations, parsePatternUntilEnd());
    }

    private List<Mf2Message.Declaration> parseDeclarations() {
        List<Mf2Message.Declaration> declarations = new ArrayList<>();
        while (true) {
            skipWhitespace();
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
            return declarations;
        }
    }

    private Mf2Message.Declaration parseInputDeclaration() {
        consumeString(".input");
        skipHorizontalWhitespace();
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
        skipHorizontalWhitespace();
        int start = index;
        String name = parseVariableName();
        if (name == null) {
            return null;
        }
        skipHorizontalWhitespace();
        if (peekChar() != '=') {
            pushDiagnostic(
                    "missing-local-equals",
                    ".local declarations must include '='.",
                    start,
                    index);
            return null;
        }
        advanceChar();
        skipHorizontalWhitespace();
        Mf2Message.Expression value = parseExpressionPlaceholder();
        return value == null ? null : new Mf2Message.LocalDeclaration(name, value);
    }

    private Mf2Message parseMatch(List<Mf2Message.Declaration> declarations) {
        consumeString(".match");
        List<Mf2Message.VariableRef> selectors = new ArrayList<>();
        while (true) {
            skipHorizontalWhitespace();
            if (peekChar() == '$') {
                String name = parseVariableName();
                if (name != null) {
                    selectors.add(new Mf2Message.VariableRef(name));
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
            skipWhitespace();
            if (isDone()) {
                break;
            }
            int variantStart = index;
            List<Mf2Message.VariantKey> keys = parseVariantKeys();
            skipHorizontalWhitespace();
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

        return new Mf2Message.Select(declarations, selectors, variants);
    }

    private List<Mf2Message.VariantKey> parseVariantKeys() {
        List<Mf2Message.VariantKey> keys = new ArrayList<>();
        while (!isDone() && !startsWith("{{") && peekChar() != '\n') {
            skipHorizontalWhitespace();
            if (startsWith("{{") || peekChar() == '\n' || isDone()) {
                break;
            }
            if (peekChar() == '*') {
                advanceChar();
                keys.add(new Mf2Message.CatchAllVariantKey());
                continue;
            }
            String key = takeWhile(ch -> !Character.isWhitespace(ch) && ch != '{');
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
                diagnostics.addAll(nested.diagnostics);
                return mergeAdjacentText(pattern);
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
        return mergeAdjacentText(parts);
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
        String trimmed = content.trim();
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
        return content == null ? null : parseExpressionContent(content.trim(), start, start + content.length() + 2);
    }

    private String consumeBracedContent() {
        int start = index;
        expectChar('{');
        int contentStart = index;
        while (!isDone()) {
            if (peekChar() == '}') {
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
            NameSplit split = splitName(content.substring(1));
            if (split.name().isEmpty()) {
                pushDiagnostic(
                        "missing-variable-name",
                        "Variable placeholder is missing a name.",
                        start,
                        end);
                return null;
            }
            expression = new Mf2Message.Expression(new Mf2Message.VariableArgument(split.name()), null);
            rest = split.rest().stripLeading();
        } else if (content.startsWith("|")) {
            int close = content.indexOf('|', 1);
            if (close < 0) {
                pushDiagnostic(
                        "unclosed-quoted-literal",
                        "Quoted literal is missing closing '|'.",
                        start,
                        end);
                return null;
            }
            expression = new Mf2Message.Expression(new Mf2Message.LiteralArgument(content.substring(1, close)), null);
            rest = content.substring(close + 1).stripLeading();
        } else if (content.startsWith(":")) {
            expression = new Mf2Message.Expression(null, null);
            rest = content;
        } else {
            pushDiagnostic(
                    "unsupported-expression",
                    "Only variables, quoted literals, and function annotations are supported.",
                    start,
                    end);
            return null;
        }

        if (!rest.isEmpty()) {
            if (!rest.startsWith(":")) {
                pushDiagnostic(
                        "unsupported-expression",
                        "Expression content after the argument must be a function annotation.",
                        start,
                        end);
                return null;
            }
            Mf2Message.FunctionRef function = parseFunctionAnnotation(rest, start, end);
            if (function == null) {
                return null;
            }
            expression = new Mf2Message.Expression(expression.arg(), function);
        }

        return expression;
    }

    private Mf2Message.FunctionRef parseFunctionAnnotation(String content, int start, int end) {
        NameSplit split = splitName(content.substring(1));
        if (split.name().isEmpty()) {
            pushDiagnostic(
                    "missing-function-name",
                    "Function annotation is missing a name.",
                    start,
                    end);
            return null;
        }

        Map<String, Mf2Message.ExpressionArgument> options = new LinkedHashMap<>();
        String rest = split.rest().trim();
        if (!rest.isEmpty()) {
            for (String token : rest.split("\\s+")) {
                int equals = token.indexOf('=');
                if (equals <= 0 || equals == token.length() - 1) {
                    pushDiagnostic(
                            "invalid-function-option",
                            "Function options must use key=value syntax.",
                            start,
                            end);
                    return null;
                }
                String key = token.substring(0, equals);
                String rawValue = token.substring(equals + 1);
                options.put(key, parseLiteralOrVariable(rawValue));
            }
        }

        return new Mf2Message.FunctionRef(split.name(), options);
    }

    private Mf2Message.Markup parseMarkupContent(String content, int start, int end) {
        String kind;
        String rest;
        if (content.startsWith("#")) {
            String trimmed = stripTrailingWhitespace(content.substring(1));
            if (trimmed.endsWith("/")) {
                kind = "standalone";
                rest = stripTrailingWhitespace(trimmed.substring(0, trimmed.length() - 1));
            } else {
                kind = "open";
                rest = trimmed;
            }
        } else {
            kind = "close";
            rest = content.substring(1).trim();
        }

        NameSplit split = splitName(rest.stripLeading());
        if (split.name().isEmpty()) {
            pushDiagnostic(
                    "missing-markup-name",
                    "Markup placeholder is missing a name.",
                    start,
                    end);
            return null;
        }
        return new Mf2Message.Markup(kind, split.name());
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
        String name = takeWhile(Mf2Parser::isNameChar);
        if (name.isEmpty()) {
            pushDiagnostic(
                    "missing-variable-name",
                    "Variable is missing a name.",
                    start,
                    index);
            return null;
        }
        return name;
    }

    private void skipWhitespace() {
        while (!isDone() && Character.isWhitespace(peekChar())) {
            advanceChar();
        }
    }

    private void skipHorizontalWhitespace() {
        while (!isDone() && (peekChar() == ' ' || peekChar() == '\t')) {
            advanceChar();
        }
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

    private void expectChar(char expected) {
        char actual = advanceChar();
        assert actual == expected;
    }

    private void pushDiagnostic(String code, String message, int start, int end) {
        diagnostics.add(new Diagnostic(code, message, baseOffset + start, baseOffset + end));
    }

    private static Mf2Message.ExpressionArgument parseLiteralOrVariable(String rawValue) {
        if (rawValue.startsWith("$")) {
            return new Mf2Message.VariableArgument(rawValue.substring(1));
        }
        if (rawValue.startsWith("|") && rawValue.endsWith("|") && rawValue.length() >= 2) {
            return new Mf2Message.LiteralArgument(rawValue.substring(1, rawValue.length() - 1));
        }
        return new Mf2Message.LiteralArgument(rawValue);
    }

    private static NameSplit splitName(String input) {
        int end = 0;
        while (end < input.length()) {
            char ch = input.charAt(end);
            if (!isNameChar(ch)) {
                break;
            }
            end += Character.charCount(ch);
        }
        return new NameSplit(input.substring(0, end), input.substring(end));
    }

    private static List<Mf2Message.PatternPart> mergeAdjacentText(List<Mf2Message.PatternPart> pattern) {
        List<Mf2Message.PatternPart> merged = new ArrayList<>();
        for (Mf2Message.PatternPart part : pattern) {
            if (part instanceof Mf2Message.TextPart text
                    && !merged.isEmpty()
                    && merged.getLast() instanceof Mf2Message.TextPart previous) {
                merged.set(merged.size() - 1, new Mf2Message.TextPart(previous.value() + text.value()));
            } else {
                merged.add(part);
            }
        }
        return merged;
    }

    private static boolean isNameChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_'
                || ch == '-';
    }

    private static String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record NameSplit(String name, String rest) {}

    @FunctionalInterface
    private interface CharPredicate {
        boolean test(char ch);
    }
}
