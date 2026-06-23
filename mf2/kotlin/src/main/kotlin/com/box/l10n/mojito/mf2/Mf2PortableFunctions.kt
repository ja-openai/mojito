package com.box.l10n.mojito.mf2

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

internal object Mf2PortableFunctions {
    const val MAX_FRACTION_DIGITS = 100
    private const val MAX_DECIMAL_OPERAND_LENGTH = 256
    private const val MAX_DECIMAL_EXPONENT = 1_000_000
    private const val MAX_OFFSET_INTEGER_TEXT = "1000000000000000000000"
    private val MAX_OFFSET_INTEGER = BigInteger(MAX_OFFSET_INTEGER_TEXT)

    fun registerFormatters(formatters: MutableMap<String, Mf2FunctionFormatter>) {
        formatters["string"] = { call -> call.value }
        formatters["offset"] = ::formatOffset
    }

    fun registerSelectors(selectors: MutableMap<String, Mf2Selector>) {
        selectors["number"] = ::selectNumber
        selectors["percent"] = ::selectPercent
        selectors["integer"] = ::selectInteger
        selectors["offset"] = ::selectOffset
    }

    private fun selectNumber(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Number selector cannot match this operand.")
        val value = parseMatchDecimalOperand(match, "Number selector requires a numeric operand.")
        val key = parseDecimalOperand(match.key)
        return if (key != null && value.compareTo(key) == 0) 2 else null
    }

    private fun selectPercent(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Percent selector cannot match this operand.")
        val value = parseMatchDecimalOperand(match, "Percent selector requires a numeric operand.").multiply(BigDecimal.valueOf(100))
        val key = parseDecimalOperand(match.key)
        return if (key != null && value.compareTo(key) == 0) 2 else null
    }

    private fun selectInteger(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Integer selector cannot match this operand.")
        val value = parseMatchDecimalOperand(match, "Integer selector requires a numeric operand.").setScale(0, RoundingMode.DOWN)
        val key = parseIntegerOperand(match.key)
        return if (key != null && value.compareTo(key) == 0) 2 else null
    }

    private fun formatOffset(call: Mf2FunctionCall): String {
        val value = parseRequiredInteger(call.value, "Offset function requires a numeric operand.")
        val result = value.add(offsetDelta(call))
        if (!offsetIntegerInRange(result)) throw Mf2Error.badOperand("Offset result is outside the supported integer range.")
        return formatOffsetInteger(result, inheritedSignDisplayAlways(call.inheritedSource))
    }

    private fun selectOffset(match: Mf2FunctionMatch): Int? {
        val value = parseRequiredInteger(match.value, "Offset selector requires a numeric operand.")
        val key = parseInteger(match.key)
        return if (key != null && value.compareTo(key) == 0) 2 else null
    }

    fun parseCallDecimal(call: Mf2FunctionCall, message: String): Double =
        parseDecimalNumber(call.value) ?: parseSourceDecimal(call.inheritedSource) ?: throw Mf2Error.badOperand(message)

    fun parseCallDecimalOperand(call: Mf2FunctionCall, message: String): BigDecimal =
        parseDecimalOperand(call.value) ?: parseSourceDecimalOperand(call.inheritedSource) ?: throw Mf2Error.badOperand(message)

    private fun parseMatchDecimalOperand(match: Mf2FunctionMatch, message: String): BigDecimal =
        parseSourceDecimalOperand(match.inheritedSource) ?: parseDecimalOperand(match.value) ?: throw Mf2Error.badSelector(message)

    private fun parseSourceDecimal(source: Mf2FunctionSource?): Double? {
        if (source == null) return null
        if (isDecimalSourceFunction(source.function)) return parseDecimalNumber(source.value)
        return parseSourceDecimal(source.inherited)
    }

    private fun parseSourceDecimalOperand(source: Mf2FunctionSource?): BigDecimal? {
        if (source == null) return null
        if (isDecimalSourceFunction(source.function)) return parseDecimalOperand(source.value)
        return parseSourceDecimalOperand(source.inherited)
    }

    private val decimalRegex = Regex("""^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$""")

    fun parseDecimalNumber(value: String): Double? {
        if (value.length > MAX_DECIMAL_OPERAND_LENGTH) return null
        if (!decimalRegex.matches(value)) return null
        val parsed = value.toDoubleOrNull()
        return if (parsed != null && parsed.isFinite()) parsed else null
    }

    private fun parseDecimalOperand(value: String): BigDecimal? =
        if (
            value.length <= MAX_DECIMAL_OPERAND_LENGTH &&
            decimalRegex.matches(value) &&
            parseBoundedDecimalExponent(value) != null
        ) {
            value.toBigDecimalOrNull()
        } else {
            null
        }

    private fun parseBoundedDecimalExponent(value: String): Int? {
        val index = maxOf(value.indexOf('e'), value.indexOf('E'))
        if (index < 0) return 0
        val exponent = value.substring(index + 1)
        val negative = exponent.startsWith("-")
        val unsigned = if (negative || exponent.startsWith("+")) exponent.drop(1) else exponent
        val digits = unsigned.trimStart('0').ifEmpty { "0" }
        if (digits.length > 7) return null
        val parsed = digits.toIntOrNull() ?: return null
        if (parsed > MAX_DECIMAL_EXPONENT) return null
        return if (negative) -parsed else parsed
    }

    fun signDisplayAlways(functionRef: Map<String, Any?>): Boolean =
        functionOptionLiteral(functionRef, "signDisplay", null) == "always"

    private fun inheritedSignDisplayAlways(source: Mf2FunctionSource?): Boolean {
        if (source == null) return false
        if (source.function["name"] in setOf("number", "integer") && sourceOptionValue(source, "signDisplay", null) == "always") return true
        return inheritedSignDisplayAlways(source.inherited)
    }

    fun parseNonNegativeOption(value: String, message: String): Int {
        if (!value.all { it.isDigit() }) throw Mf2Error.badOption(message)
        val parsed = value.toIntOrNull() ?: throw Mf2Error.badOption(message)
        if (parsed > MAX_FRACTION_DIGITS) throw Mf2Error.badOption(message)
        return parsed
    }

    private fun offsetDelta(call: Mf2FunctionCall): BigInteger {
        val add = call.optionValue("add", null)
        val subtract = call.optionValue("subtract", null)
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2Error.badOption("Offset function requires exactly one of add or subtract.")
        }
        val value = parseInteger(add ?: subtract!!)
            ?: throw Mf2Error.badOption(if (add != null) "Offset add option must be an integer." else "Offset subtract option must be an integer.")
        return if (add != null) value else value.negate()
    }

    private fun parseRequiredInteger(value: String, message: String): BigInteger =
        parseInteger(value) ?: throw Mf2Error.badOperand(message)

    private val integerRegex = Regex("""^[+-]?\d+$""")

    private fun parseInteger(value: String): BigInteger? {
        if (!integerRegex.matches(value)) return null
        val negative = value.startsWith("-")
        var digits = if (negative || value.startsWith("+")) value.drop(1) else value
        digits = digits.trimStart('0')
        if (digits.isEmpty()) return BigInteger.ZERO
        if (digits.length > MAX_OFFSET_INTEGER_TEXT.length || (digits.length == MAX_OFFSET_INTEGER_TEXT.length && digits >= MAX_OFFSET_INTEGER_TEXT)) return null
        return (if (negative) "-$digits" else digits).toBigIntegerOrNull()
    }

    private fun parseIntegerOperand(value: String): BigDecimal? =
        if (value.length <= MAX_DECIMAL_OPERAND_LENGTH && integerRegex.matches(value)) value.toBigDecimalOrNull() else null

    fun formatIntegerNumber(value: Long, signDisplayAlways: Boolean): String =
        if (signDisplayAlways && value >= 0) "+$value" else value.toString()

    private fun formatOffsetInteger(value: BigInteger, signDisplayAlways: Boolean): String =
        if (signDisplayAlways && value.signum() >= 0) "+$value" else value.toString()

    private fun offsetIntegerInRange(value: BigInteger): Boolean =
        value.abs() < MAX_OFFSET_INTEGER
}

internal fun functionOptionLiteral(functionRef: Map<String, Any?>, name: String, fallback: String?): String? {
    val option = asMap(functionRef["options"])[name] as? Map<String, Any?> ?: return fallback
    return if (option["type"] == "literal") stringValue(option["value"]) else fallback
}

internal fun sourceOptionValue(source: Mf2FunctionSource?, name: String, fallback: String?): String? {
    if (source == null) return fallback
    return functionOptionLiteral(source.function, name, fallback)
}

internal fun isNumericFunction(functionRef: Map<String, Any?>): Boolean =
    stringValue(functionRef["name"]) in setOf("number", "integer", "percent", "offset")

internal fun numericSelectUsesVariable(functionRef: Map<String, Any?>): Boolean =
    asMap(asMap(functionRef["options"])["select"])["type"] == "variable"

internal fun inheritedExactNumericSource(source: Mf2FunctionSource?): Boolean {
    if (source == null) return false
    if (isNumericFunction(source.function) && sourceOptionValue(source, "select", null) == "exact") return true
    return inheritedExactNumericSource(source.inherited)
}

private fun invalidNumericSelector(functionRef: Map<String, Any?>, source: Mf2FunctionSource?): Boolean {
    val select = functionOptionLiteral(functionRef, "select", null)
    return numericSelectUsesVariable(functionRef) || (select != "exact" && inheritedExactNumericSource(source))
}

private fun isDecimalSourceFunction(functionRef: Map<String, Any?>): Boolean =
    isNumericFunction(functionRef) || functionRef["name"] == "currency"
