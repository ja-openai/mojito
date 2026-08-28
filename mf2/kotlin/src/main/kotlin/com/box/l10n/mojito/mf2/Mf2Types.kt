package com.box.l10n.mojito.mf2

import java.math.BigDecimal

typealias Mf2Model = Map<String, Any?>
typealias Mf2Part = Map<String, Any?>

data class Mf2ParseDiagnostic(
    val code: String,
    val message: String,
    val start: Int,
    val end: Int,
    val severity: String = "error",
)

data class Mf2ParseResult(
    val model: Mf2Model?,
    val diagnostics: List<Mf2ParseDiagnostic>,
) {
    val hasDiagnostics: Boolean
        get() = diagnostics.isNotEmpty()
}

class Mf2Error(
    val code: String,
    message: String,
) : Exception(message) {
    companion object {
        fun missingArgument(name: String) = Mf2Error("missing-argument", "Missing argument $$name.")

        fun unresolvedVariable(name: String) =
            Mf2Error("unresolved-variable", "Variable $$name could not be resolved.")

        fun badOperand(message: String) = Mf2Error("bad-operand", message)

        fun badVariantKey(message: String) = Mf2Error("bad-variant-key", message)

        fun badOption(message: String) = Mf2Error("bad-option", message)

        fun badSelector(message: String) = Mf2Error("bad-selector", message)
    }
}

data class Mf2FormatResult(
    val value: String,
    val errors: List<Mf2Error>,
) {
    val ok: Boolean
        get() = errors.isEmpty()

    val hasErrors: Boolean
        get() = errors.isNotEmpty()
}

data class Mf2PartsResult(
    val parts: List<Mf2Part>,
    val errors: List<Mf2Error>,
) {
    val ok: Boolean
        get() = errors.isEmpty()

    val hasErrors: Boolean
        get() = errors.isNotEmpty()
}

data class Mf2RecoveryContext(
    val code: String,
    val message: String,
    val locale: String,
    val variableName: String?,
    val functionName: String?,
    val sourceExpression: String,
    val fallbackValue: String,
    val error: Mf2Error,
)

typealias Mf2RecoveryHandler = (Mf2RecoveryContext) -> String?

typealias Mf2FunctionFormatter = (Mf2FunctionCall) -> String
typealias Mf2Selector = (Mf2FunctionMatch) -> Int?

data class Mf2FunctionSource(
    val value: String,
    val function: Map<String, Any?>,
    val inherited: Mf2FunctionSource?,
    private val optionResolver: ((String, String?) -> String?)? = null,
) {
    fun optionValue(name: String, fallback: String? = null): String? =
        optionResolver?.invoke(name, fallback) ?: functionOptionLiteral(function, name, fallback)
}

fun numericSourceOperand(source: Mf2FunctionSource?): String? {
    if (source == null || stringValue(source.function["name"]) !in decimalSourceFunctions) return null
    return numericSourceOperandChain(source)
}

internal fun inheritedNumericOptionValue(
    targetFunction: String,
    source: Mf2FunctionSource?,
    optionName: String,
    fallback: String?,
): String? {
    if (source == null || blocksInheritedOption(targetFunction, optionName)) return fallback
    val sourceFunction = stringValue(source.function["name"])
    if (
        !canInheritOptionsFrom(targetFunction, sourceFunction) ||
        blocksInheritedOption(sourceFunction, optionName)
    ) return fallback
    if (asMap(source.function["options"]).containsKey(optionName)) {
        return source.optionValue(optionName, fallback)
    }
    return inheritedNumericOptionValue(sourceFunction, source.inherited, optionName, fallback)
}

fun resolvedCurrencyCode(call: Mf2FunctionCall): String? {
    val inherited = inheritedCurrencyCode(call.inheritedSource)
    val hasDirectOption = asMap(call.function["options"]).containsKey("currency")
    if (inherited != null && hasDirectOption) {
        throw Mf2Error.badOption("Currency option cannot override an existing currency operand.")
    }
    if (inherited != null) return inherited
    return if (hasDirectOption) call.optionValue("currency", null) else null
}

private fun numericSourceOperandChain(source: Mf2FunctionSource?): String? {
    if (source == null) return null
    val operand = numericSourceOperandChain(source.inherited) ?: source.value
    val functionName = stringValue(source.function["name"])
    if (functionName !in decimalSourceFunctions) return operand
    val parsed = Mf2PortableFunctions.parseDecimalNumber(operand) ?: return null
    if (functionName == "integer") return parsed.toLong().toString()
    if (functionName == "offset") {
        val add = source.optionValue("add", null)
        val subtract = source.optionValue("subtract", null)
        return adjustedOffsetOperand(operand, add, subtract)
    }
    return operand
}

internal fun adjustedOffsetOperand(operand: String, add: String?, subtract: String?): String? {
    if (Mf2PortableFunctions.parseDecimalNumber(operand) == null || (add == null) == (subtract == null)) {
        return null
    }
    val delta = parseSemanticInteger(add ?: subtract) ?: return null
    val value = BigDecimal(operand)
    val adjustment = BigDecimal.valueOf(delta)
    val result = if (add == null) value.subtract(adjustment) else value.add(adjustment)
    return result.stripTrailingZeros().toPlainString()
}

private val decimalSourceFunctions = setOf("number", "integer", "percent", "offset", "currency")
private val numericOptionFunctions = setOf("number", "integer", "percent", "offset")
private val semanticIntegerRegex = Regex("""^[+-]?\d+$""")

private fun canInheritOptionsFrom(targetFunction: String, sourceFunction: String): Boolean =
    if (targetFunction == "currency") {
        sourceFunction == "currency"
    } else {
        targetFunction in numericOptionFunctions && sourceFunction in numericOptionFunctions
    }

private fun blocksInheritedOption(functionName: String, optionName: String): Boolean =
    when (functionName) {
        "integer" -> optionName in setOf(
            "minimumFractionDigits",
            "maximumFractionDigits",
            "minimumSignificantDigits",
        )
        "percent" -> optionName in setOf("minimumIntegerDigits", "roundingIncrement", "select")
        "offset" -> optionName in setOf("add", "subtract")
        else -> false
    }

private fun inheritedCurrencyCode(source: Mf2FunctionSource?): String? {
    if (source == null || source.function["name"] != "currency") return null
    source.optionValue("currency", null)?.let { return it }
    return inheritedCurrencyCode(source.inherited)
}

private fun parseSemanticInteger(value: String?): Long? =
    value?.takeIf(semanticIntegerRegex::matches)?.toLongOrNull()

class Mf2FunctionCall(
    val value: String,
    val rawValue: Any?,
    val function: Map<String, Any?>,
    val locale: String,
    private val optionResolver: (String, String?) -> String?,
    val inheritedSource: Mf2FunctionSource?,
) {
    fun optionValue(name: String, fallback: String? = null): String? = optionResolver.invoke(name, fallback)
}

class Mf2FunctionMatch(
    val value: String,
    val rawValue: Any?,
    val function: Map<String, Any?>,
    val key: String,
    val locale: String,
    private val optionResolver: (String, String?) -> String?,
    val inheritedSource: Mf2FunctionSource?,
) {
    fun optionValue(name: String, fallback: String? = null): String? = optionResolver.invoke(name, fallback)
}

enum class Mf2BidiIsolation {
    NONE,
    DEFAULT;

    companion object {
        fun fromName(value: String): Mf2BidiIsolation =
            if (value == "default") DEFAULT else NONE
    }
}

internal fun linkedMapOfNotNull(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> {
    val output = LinkedHashMap<String, Any?>()
    for ((key, value) in pairs) {
        if (value != null) {
            output[key] = value
        }
    }
    return output
}

@Suppress("UNCHECKED_CAST")
internal fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

internal fun typeOf(value: Any?): String = asMap(value)["type"] as? String ?: ""

internal fun stringValue(value: Any?, fallback: String = ""): String = value as? String ?: fallback

internal fun sortedMap(value: Map<String, Any?>): Map<String, Any?> =
    value.toSortedMap().let { LinkedHashMap(it) }
