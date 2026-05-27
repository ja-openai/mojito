package com.box.l10n.mojito.mf2

import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap

internal object KotlinJsonSupport {
    fun parse(path: Path): Any? = parse(Files.readString(path))

    fun parse(source: String): Any? {
        val parser = Parser(source)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isEnd()) {
            parser.fail("Unexpected trailing input.")
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arrayOrEmpty(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun objOrEmpty(value: Any?): Map<String, Any?> = value?.let { obj(it) } ?: emptyMap()

    fun string(value: Any?): String = value as String

    fun stringOrDefault(value: Any?, fallback: String): String = value as? String ?: fallback

    private class Parser(private val source: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (isEnd()) {
                fail("Unexpected end of input.")
            }
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val output = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (consume('}')) {
                return output
            }
            do {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                output[key] = parseValue()
                skipWhitespace()
            } while (consume(','))
            expect('}')
            return output
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val output = mutableListOf<Any?>()
            skipWhitespace()
            if (consume(']')) {
                return output
            }
            do {
                output += parseValue()
                skipWhitespace()
            } while (consume(','))
            expect(']')
            return output
        }

        private fun parseString(): String {
            expect('"')
            val output = StringBuilder()
            while (!isEnd()) {
                val ch = source[index++]
                if (ch == '"') {
                    return output.toString()
                }
                if (ch != '\\') {
                    output.append(ch)
                    continue
                }
                if (isEnd()) {
                    fail("Unclosed escape sequence.")
                }
                when (val escaped = source[index++]) {
                    '"', '\\', '/' -> output.append(escaped)
                    'b' -> output.append('\b')
                    'f' -> output.append('\u000c')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    'u' -> output.append(parseUnicodeEscape())
                    else -> fail("Unsupported escape sequence.")
                }
            }
            fail("Unclosed string.")
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > source.length) {
                fail("Short Unicode escape.")
            }
            var value = 0
            val end = index + 4
            while (index < end) {
                val digit = source[index].digitToIntOrNull(16) ?: fail("Invalid Unicode escape.")
                value = value * 16 + digit
                index++
            }
            return value.toChar()
        }

        private fun parseNumber(): Number {
            val start = index
            consume('-')
            consumeDigits()
            var decimal = false
            if (consume('.')) {
                decimal = true
                consumeDigits()
            }
            if (consume('e') || consume('E')) {
                decimal = true
                if (!consume('+')) {
                    consume('-')
                }
                consumeDigits()
            }
            val token = source.substring(start, index)
            return if (decimal) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
        }

        private fun consumeDigits() {
            val start = index
            while (!isEnd() && peek().isDigit()) {
                index++
            }
            if (index == start) {
                fail("Expected digit.")
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, index)) {
                fail("Expected $literal.")
            }
            index += literal.length
            return value
        }

        fun skipWhitespace() {
            while (!isEnd() && peek().isWhitespace()) {
                index++
            }
        }

        private fun consume(expected: Char): Boolean {
            if (!isEnd() && peek() == expected) {
                index++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) {
                fail("Expected '$expected'.")
            }
        }

        private fun peek(): Char = source[index]

        fun isEnd(): Boolean = index >= source.length

        fun fail(message: String): Nothing {
            throw IllegalArgumentException("$message At offset $index.")
        }
    }
}
