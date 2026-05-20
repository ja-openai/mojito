package com.box.l10n.mojito.mf2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonParser {
    private final String source;
    private int index;

    private JsonParser(String source) {
        this.source = source;
    }

    static Object parse(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    static Object parse(String source) {
        JsonParser parser = new JsonParser(source);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw parser.error("Unexpected trailing input.");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (isEnd()) {
            throw error("Unexpected end of input.");
        }
        return switch (peek()) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return object;
        }
        do {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            object.put(key, parseValue());
            skipWhitespace();
        } while (consume(','));
        expect('}');
        return object;
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return array;
        }
        do {
            array.add(parseValue());
            skipWhitespace();
        } while (consume(','));
        expect(']');
        return array;
    }

    private String parseString() {
        expect('"');
        StringBuilder output = new StringBuilder();
        while (!isEnd()) {
            char ch = source.charAt(index++);
            if (ch == '"') {
                return output.toString();
            }
            if (ch != '\\') {
                output.append(ch);
                continue;
            }
            if (isEnd()) {
                throw error("Unclosed escape sequence.");
            }
            char escaped = source.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> output.append(escaped);
                case 'b' -> output.append('\b');
                case 'f' -> output.append('\f');
                case 'n' -> output.append('\n');
                case 'r' -> output.append('\r');
                case 't' -> output.append('\t');
                case 'u' -> output.append(parseUnicodeEscape());
                default -> throw error("Unsupported escape sequence.");
            }
        }
        throw error("Unclosed string.");
    }

    private char parseUnicodeEscape() {
        if (index + 4 > source.length()) {
            throw error("Short Unicode escape.");
        }
        int value = 0;
        for (int end = index + 4; index < end; index++) {
            int digit = Character.digit(source.charAt(index), 16);
            if (digit < 0) {
                throw error("Invalid Unicode escape.");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object parseNumber() {
        int start = index;
        consume('-');
        consumeDigits();
        boolean decimal = false;
        if (consume('.')) {
            decimal = true;
            consumeDigits();
        }
        if (consume('e') || consume('E')) {
            decimal = true;
            if (!consume('+')) {
                consume('-');
            }
            consumeDigits();
        }
        String token = source.substring(start, index);
        try {
            return decimal ? Double.valueOf(token) : Long.valueOf(token);
        } catch (NumberFormatException error) {
            return Double.valueOf(token);
        }
    }

    private void consumeDigits() {
        int start = index;
        while (!isEnd() && Character.isDigit(peek())) {
            index++;
        }
        if (index == start) {
            throw error("Expected digit.");
        }
    }

    private Object parseLiteral(String literal, Object value) {
        if (!source.startsWith(literal, index)) {
            throw error("Expected " + literal + ".");
        }
        index += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (!isEnd() && Character.isWhitespace(peek())) {
            index++;
        }
    }

    private boolean consume(char expected) {
        if (!isEnd() && peek() == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("Expected '" + expected + "'.");
        }
    }

    private char peek() {
        return source.charAt(index);
    }

    private boolean isEnd() {
        return index >= source.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " At offset " + index + ".");
    }
}
