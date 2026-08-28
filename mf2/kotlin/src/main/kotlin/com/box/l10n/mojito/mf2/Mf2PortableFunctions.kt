package com.box.l10n.mojito.mf2

import kotlin.math.truncate

internal object Mf2PortableFunctions {
    private val pluralCategoryKeys = setOf("zero", "one", "two", "few", "many", "other")

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
        validateNumericVariantKey(match.key)
        return if (match.key == numericMatchOperand(match, value, "number")) 2 else null
    }

    private fun selectPercent(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Percent selector cannot match this operand.")
        val value = parseMatchDecimal(match, "Percent selector requires a numeric operand.")
        validateNumericVariantKey(match.key)
        return if (match.key == numericMatchOperand(match, value, "percent")) 2 else null
    }

    private fun selectInteger(match: Mf2FunctionMatch): Int? {
        if (invalidNumericSelector(match.function, match.inheritedSource)) throw Mf2Error.badSelector("Integer selector cannot match this operand.")
        val value = parseMatchDecimal(match, "Integer selector requires a numeric operand.")
        validateNumericVariantKey(match.key)
        return if (match.key == numericMatchOperand(match, value, "integer")) 2 else null
    }

    private fun formatOffset(call: Mf2FunctionCall): String {
        val add = call.optionValue("add", null)
        val subtract = call.optionValue("subtract", null)
        validateOffsetOptions(add, subtract)
        val operand = numericSourceOperand(call.inheritedSource) ?: call.value
        val result = adjustedOffsetOperand(operand, add, subtract)
            ?: throw Mf2Error.badOperand("Offset function requires a numeric operand.")
        return if (call.optionValue("signDisplay", null) == "always" && !result.startsWith("-")) {
            "+$result"
        } else {
            result
        }
    }

    private fun selectOffset(match: Mf2FunctionMatch): Int? {
        val value = parseMatchDecimal(match, "Offset selector requires a numeric operand.")
        validateNumericVariantKey(match.key)
        return if (match.key == numericMatchOperand(match, value, "number")) 2 else null
    }

    private fun numericMatchOperand(match: Mf2FunctionMatch, value: Double, functionName: String): String {
        val minimumFractionDigits = parseNonNegativeOption(
            match.optionValue("minimumFractionDigits", "0") ?: "0",
            "minimumFractionDigits option must be a non-negative integer.",
        )
        val maximumFractionDigits = match.optionValue("maximumFractionDigits", null)?.let {
            parseNonNegativeOption(it, "maximumFractionDigits option must be a non-negative integer.")
        }
        return Mf2UnlocalizedNumericFunctions.selectionOperand(
            value,
            functionName,
            minimumFractionDigits,
            maximumFractionDigits,
        )
    }

    private fun validateNumericVariantKey(key: String) {
        if (key in pluralCategoryKeys || parseDecimalNumber(key) != null) {
            return
        }
        throw Mf2Error.badVariantKey("Numeric selector keys must be number literals or plural keywords.")
    }

    fun parseCallDecimal(call: Mf2FunctionCall, message: String): Double =
        parseSourceDecimal(call.inheritedSource) ?: parseDecimalNumber(call.value) ?: throw Mf2Error.badOperand(message)

    private fun parseMatchDecimal(match: Mf2FunctionMatch, message: String): Double =
        parseSourceDecimal(match.inheritedSource) ?: parseDecimalNumber(match.value) ?: throw Mf2Error.badSelector(message)

    private fun parseSourceDecimal(source: Mf2FunctionSource?): Double? {
        if (source == null || !isDecimalSourceFunction(source.function)) return null
        return numericSourceOperand(source)?.let(::parseDecimalNumber)
    }

    private val decimalRegex = Regex("""^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$""")

    fun parseDecimalNumber(value: String): Double? {
        if (!decimalRegex.matches(value)) return null
        val parsed = value.toDoubleOrNull()
        return if (parsed != null && parsed.isFinite()) parsed else null
    }

    fun parseNonNegativeOption(value: String, message: String): Int {
        if (!value.all { it.isDigit() }) throw Mf2Error.badOption(message)
        return value.toIntOrNull() ?: throw Mf2Error.badOption(message)
    }

    private fun validateOffsetOptions(add: String?, subtract: String?) {
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2Error.badOption("Offset function requires exactly one of add or subtract.")
        }
        if (parseInteger(add ?: subtract!!) == null) {
            throw Mf2Error.badOption(if (add != null) "Offset add option must be an integer." else "Offset subtract option must be an integer.")
        }
    }

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
    return source.optionValue(name, fallback)
}

internal fun isNumericFunction(functionRef: Map<String, Any?>): Boolean =
    stringValue(functionRef["name"]) in setOf("number", "integer", "percent", "offset")

internal fun numericSelectUsesVariable(functionRef: Map<String, Any?>): Boolean =
    asMap(asMap(functionRef["options"])["select"])["type"] == "variable"

private fun invalidNumericSelector(functionRef: Map<String, Any?>, source: Mf2FunctionSource?): Boolean {
    val select = functionOptionLiteral(functionRef, "select", null)
    return numericSelectUsesVariable(functionRef) || (
        select != "exact" &&
            inheritedNumericOptionValue(
                stringValue(functionRef["name"]),
                source,
                "select",
                null,
            ) == "exact"
    )
}

private fun isDecimalSourceFunction(functionRef: Map<String, Any?>): Boolean =
    isNumericFunction(functionRef) || functionRef["name"] == "currency"
