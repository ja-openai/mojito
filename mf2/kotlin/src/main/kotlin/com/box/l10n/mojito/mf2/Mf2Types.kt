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
    internal fun bindCache() { (optionResolver as? Mf2SourceCache)?.owner = this }
    internal val cache: Mf2SourceCache? get() = (optionResolver as? Mf2SourceCache)?.takeIf { it.owner === this }
    fun optionValue(name: String, fallback: String? = null): String? =
        optionResolver?.invoke(name, fallback) ?: functionOptionLiteral(function, name, fallback)
}

/** Truncates a finite numeric operand within the supported signed-64-bit range. */
fun truncateInteger(value: Double): Long {
    if (!value.isFinite() || value < -9223372036854775808.0 || value >= 9223372036854775808.0) {
        throw Mf2Error.badOperand("Integer operand is outside the supported signed-64-bit range.")
    }
    return value.toLong()
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
    var current = source
    var target = targetFunction
    var result: String? = null
    val visited = mutableListOf<Pair<Mf2SourceCache, String>>()
    while (current != null && !blocksInheritedOption(target, optionName)) {
        val cache = current.cache
        val key = "$target:$optionName"
        if (cache?.cacheable == true) {
            if (cache.inheritedOptions.containsKey(key)) {
                result = cache.inheritedOptions[key]
                break
            }
            visited += cache to key
        }
        val sourceFunction = stringValue(current.function["name"])
        if (!canInheritOptionsFrom(target, sourceFunction) || blocksInheritedOption(sourceFunction, optionName)) break
        if (asMap(current.function["options"]).containsKey(optionName)) {
            result = current.optionValue(optionName, null)
            break
        }
        target = sourceFunction
        current = current.inherited
    }
    for ((cache, key) in visited) cache.remember(key, result)
    return result ?: fallback
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
    val chain = ArrayDeque<Mf2FunctionSource>()
    var current = source
    var operand: String? = null
    while (current != null) {
        val cache = current.cache
        if (cache?.cacheable == true && cache.operandResolved) {
            operand = cache.operand
            break
        }
        chain.addFirst(current)
        current = current.inherited
    }
    for (item in chain) {
        val value = operand ?: item.value
        operand = value
        val functionName = stringValue(item.function["name"])
        if (functionName in decimalSourceFunctions) {
            val parsed = Mf2PortableFunctions.parseDecimalNumber(value)
            operand = when {
                parsed == null -> null
                functionName == "integer" -> truncateInteger(parsed).toString()
                functionName == "offset" -> adjustedOffsetOperand(value, item.optionValue("add", null), item.optionValue("subtract", null))
                else -> value
            }
        }
        item.cache?.takeIf { it.cacheable }?.let { it.operand = operand; it.operandResolved = true }
    }
    return operand
}

internal fun adjustedOffsetOperand(operand: String, add: String?, subtract: String?): String? {
    if (Mf2PortableFunctions.parseDecimalNumber(operand) == null || (add == null) == (subtract == null)) {
        return null
    }
    val delta = parseSemanticInteger(add ?: subtract) ?: return null
    if (operand.length > 8192) return null
    val value = try { BigDecimal(operand) } catch (error: NumberFormatException) { return null }
    if (!boundedDecimal(value)) return null
    val adjustment = BigDecimal.valueOf(delta)
    val result = if (add == null) value.subtract(adjustment) else value.add(adjustment)
    return if (boundedDecimal(result)) result.stripTrailingZeros().toPlainString() else null
}

private fun boundedDecimal(value: BigDecimal): Boolean {
    val precision = value.precision().toLong()
    val scale = value.scale().toLong()
    return precision <= 4096 && maxOf(precision - scale, 1) + maxOf(scale, 0) <= 4096
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
    if (source?.cache?.cacheable == true) return inheritedNumericOptionValue("currency", source, "currency", null)
    var current = source
    while (current != null && current.function["name"] == "currency") {
        current.optionValue("currency", null)?.let { return it }
        current = current.inherited
    }
    return null
}

private fun parseSemanticInteger(value: String?): Long? =
    value?.takeIf(semanticIntegerRegex::matches)?.toLongOrNull()

class Mf2FunctionCall(
    val value: String,
    val rawValue: Any?,
    function: Map<String, Any?>,
    val locale: String,
    private val optionResolver: (String, String?) -> String?,
    val inheritedSource: Mf2FunctionSource?,
) {
    val function: Map<String, Any?> = immutableFunction(function)
    fun optionValue(name: String, fallback: String? = null): String? = optionResolver.invoke(name, fallback)
}

class Mf2FunctionMatch(
    val value: String,
    val rawValue: Any?,
    function: Map<String, Any?>,
    val key: String,
    val locale: String,
    private val optionResolver: (String, String?) -> String?,
    val inheritedSource: Mf2FunctionSource?,
) {
    val function: Map<String, Any?> = immutableFunction(function)
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
