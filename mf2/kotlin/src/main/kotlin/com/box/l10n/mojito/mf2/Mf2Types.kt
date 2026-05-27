package com.box.l10n.mojito.mf2

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
)

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
