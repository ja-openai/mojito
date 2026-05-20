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
            if (!isDone() && peekChar() == '$') {
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
            expression = new Mf2Message.Expression(
                    new Mf2Message.LiteralArgument(content.substring(1, close)), null, Map.of());
            rest = content.substring(close + 1).stripLeading();
        } else if (content.startsWith(":")) {
            expression = new Mf2Message.Expression(null, null, Map.of());
            rest = content;
        } else {
            pushDiagnostic(
                    "unsupported-expression",
                    "Only variables, quoted literals, and function annotations are supported.",
                    start,
                    end);
            return null;
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
            AttributeParseResult attribute = parseAttribute(token, start, end);
            if (attribute == null) {
                return null;
            }
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
            if (codePoint == '|') {
                inQuote = !inQuote;
                if (tokenStart < 0) {
                    tokenStart = index;
                }
                index += charCount;
                continue;
            }
            if (Character.isWhitespace(codePoint) && !inQuote) {
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
            String token = tokens.get(index++);
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
            NameSplit keySplit = splitIdentifier(key);
            if (keySplit.name().isEmpty() || !keySplit.rest().isEmpty()) {
                pushDiagnostic(
                        "invalid-function-option",
                        "Function option key must be a valid identifier.",
                        start,
                        end);
                return null;
            }
            if (options.containsKey(keySplit.name())) {
                pushDiagnostic(
                        "duplicate-option-name",
                        "Function option names must be unique within an expression.",
                        start,
                        end);
                return null;
            }
            options.put(keySplit.name(), parseLiteralOrVariable(rawValue));
        }

        return new FunctionParseResult(new Mf2Message.FunctionRef(split.name(), options), index);
    }

    private AttributeParseResult parseAttribute(String token, int start, int end) {
        String content = token.substring(1);
        if (content.isEmpty()) {
            pushDiagnostic("missing-attribute-name", "Attribute is missing a name.", start, end);
            return null;
        }
        int equals = content.indexOf('=');
        if (equals < 0) {
            NameSplit split = splitIdentifier(content);
            if (split.name().isEmpty() || !split.rest().isEmpty()) {
                pushDiagnostic(
                        "invalid-attribute",
                        "Attribute name must be a valid identifier.",
                        start,
                        end);
                return null;
            }
            return new AttributeParseResult(split.name(), new Mf2Message.PresentAttribute(true));
        }
        if (equals == 0 || equals == content.length() - 1) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute key and value must be non-empty.",
                    start,
                    end);
            return null;
        }
        NameSplit split = splitIdentifier(content.substring(0, equals));
        if (split.name().isEmpty() || !split.rest().isEmpty()) {
            pushDiagnostic(
                    "invalid-attribute",
                    "Attribute name must be a valid identifier.",
                    start,
                    end);
            return null;
        }
        return new AttributeParseResult(
                split.name(),
                new Mf2Message.LiteralAttribute(parseLiteralOrVariable(content.substring(equals + 1))));
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

        NameSplit split = splitIdentifier(rest.stripLeading());
        if (split.name().isEmpty()) {
            pushDiagnostic(
                    "missing-markup-name",
                    "Markup placeholder is missing a name.",
                    start,
                    end);
            return null;
        }
        if (split.rest().isBlank()) {
            return new Mf2Message.Markup(kind, split.name(), Map.of());
        }
        Tail tail = parseTail(split.rest(), start, end);
        if (tail == null) {
            return null;
        }
        if (tail.function() != null) {
            pushDiagnostic(
                    "unsupported-markup",
                    "Markup placeholders do not support function annotations.",
                    start,
                    end);
            return null;
        }
        return new Mf2Message.Markup(kind, split.name(), tail.attributes());
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
            NameSplit split = splitName(rawValue.substring(1));
            if (!split.name().isEmpty() && split.rest().isEmpty()) {
                return new Mf2Message.VariableArgument(split.name());
            }
            return new Mf2Message.VariableArgument(rawValue.substring(1));
        }
        if (rawValue.startsWith("|") && rawValue.endsWith("|") && rawValue.length() >= 2) {
            return new Mf2Message.LiteralArgument(rawValue.substring(1, rawValue.length() - 1));
        }
        return new Mf2Message.LiteralArgument(rawValue);
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
                && !Character.isWhitespace(codePoint)
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

    private static boolean isNoncharacter(int codePoint) {
        return (codePoint >= 0xFDD0 && codePoint <= 0xFDEF)
                || ((codePoint & 0xFFFE) == 0xFFFE);
    }

    private static String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record NameSplit(String name, String rest, int consumedLength) {}

    private record NameScan(String name, int endIndex) {}

    private record Tail(Mf2Message.FunctionRef function, Map<String, Mf2Message.AttributeValue> attributes) {}

    private record FunctionParseResult(Mf2Message.FunctionRef function, int nextIndex) {}

    private record AttributeParseResult(String name, Mf2Message.AttributeValue value) {}

    @FunctionalInterface
    private interface CharPredicate {
        boolean test(char ch);
    }
}
