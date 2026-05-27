package com.box.l10n.mojito.mf2

import kotlin.math.truncate

internal object Mf2PortableFunctions {
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
        val value = parseMatchDecimal(match, "Number selector requires a numeric operand.")
        val key = parseDecimalNumber(match.key)
        return if (key != null && value.compareTo(key) == 0) 1 else null
    }

    private fun selectPercent(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Percent selector cannot match this operand.")
        val value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100.0
        val key = parseDecimalNumber(match.key)
        return if (key != null && value.compareTo(key) == 0) 1 else null
    }

    private fun selectInteger(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Integer selector cannot match this operand.")
        val value = parseMatchDecimal(match, "Integer selector requires a numeric operand.")
        val key = parseInteger(match.key)
        return if (key != null && truncate(value).toLong() == key) 1 else null
    }

    private fun formatOffset(call: Mf2FunctionCall): String {
        val value = parseRequiredInteger(call.value, "Offset function requires a numeric operand.")
        val result = value + offsetDelta(call)
        return formatIntegerNumber(result, inheritedSignDisplayAlways(call.inheritedSource))
    }

    private fun selectOffset(match: Mf2FunctionMatch): Int? {
        val value = parseRequiredInteger(match.value, "Offset selector requires a numeric operand.")
        val key = parseInteger(match.key)
        return if (key != null && value == key) 1 else null
    }

    fun parseCallDecimal(call: Mf2FunctionCall, message: String): Double =
        parseDecimalNumber(call.value) ?: parseSourceDecimal(call.inheritedSource) ?: throw Mf2Error.badOperand(message)

    private fun parseMatchDecimal(match: Mf2FunctionMatch, message: String): Double =
        parseDecimalNumber(match.value) ?: parseSourceDecimal(match.inheritedSource) ?: throw Mf2Error.badSelector(message)

    private fun parseSourceDecimal(source: Mf2FunctionSource?): Double? {
        if (source == null) return null
        if (isDecimalSourceFunction(source.function)) return parseDecimalNumber(source.value)
        return parseSourceDecimal(source.inherited)
    }

    private val decimalRegex = Regex("""^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$""")

    fun parseDecimalNumber(value: String): Double? {
        if (!decimalRegex.matches(value)) return null
        val parsed = value.toDoubleOrNull()
        return if (parsed != null && parsed.isFinite()) parsed else null
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
        return value.toIntOrNull() ?: throw Mf2Error.badOption(message)
    }

    private fun offsetDelta(call: Mf2FunctionCall): Long {
        val add = call.optionValue("add", null)
        val subtract = call.optionValue("subtract", null)
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2Error.badOption("Offset function requires exactly one of add or subtract.")
        }
        val value = parseInteger(add ?: subtract!!)
            ?: throw Mf2Error.badOption(if (add != null) "Offset add option must be an integer." else "Offset subtract option must be an integer.")
        return if (add != null) value else -value
    }

    private fun parseRequiredInteger(value: String, message: String): Long =
        parseInteger(value) ?: throw Mf2Error.badOperand(message)

    private val integerRegex = Regex("""^[+-]?\d+$""")

    private fun parseInteger(value: String): Long? =
        if (integerRegex.matches(value)) value.toLongOrNull() else null

    fun formatIntegerNumber(value: Long, signDisplayAlways: Boolean): String =
        if (signDisplayAlways && value >= 0) "+$value" else value.toString()
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
