package com.box.l10n.mojito.mf2

import kotlin.math.abs
import kotlin.math.floor

/** Test-only bridge; the standard functions always come from a production registry. */
object KotlinOfficialBridge {
    @JvmStatic fun main(args: Array<String>) {
        for (line in generateSequence(::readLine)) {
            val request = KotlinJsonSupport.obj(KotlinJsonSupport.parse(line))
            val parsed = Mf2Parser.parseToModel(request["source"] as String)
            val response = linkedMapOf<String, Any?>("diagnostics" to parsed.diagnostics.map { it.code }, "value" to "", "errors" to emptyList<String>(), "parts" to emptyList<Any>())
            if (!parsed.hasDiagnostics) {
                val registry = testFunctions(if (request["registry"] == "platform") Mf2FunctionRegistry.defaults() else Mf2FunctionRegistry.portable())
                val arguments = KotlinJsonSupport.objOrEmpty(request["arguments"])
                val locale = request["locale"] as? String ?: "en"
                try {
                    val result = Mf2Formatter.formatMessage(parsed.model!!, arguments, locale, Mf2BidiIsolation.fromName(request["bidiIsolation"] as? String ?: "none"), registry)
                    val parts = Mf2Formatter.formatMessageToParts(parsed.model!!, arguments, locale, registry)
                    response["value"] = result.value
                    response["errors"] = result.errors.map { it.code }
                    response["parts"] = parts.parts
                    response["partsErrors"] = parts.errors.map { it.code }
                } catch (error: Mf2Error) {
                    response["errors"] = listOf(error.code)
                    response["partsErrors"] = listOf(error.code)
                }
            }
            println(json(response))
        }
    }

    private fun testFunctions(base: Mf2FunctionRegistry): Mf2FunctionRegistry {
        fun format(call: Mf2FunctionCall, fails: Boolean): String {
            val state = state(call.value, call.inheritedSource, call::optionValue)
            if (fails && state.failsFormat) throw Mf2Error.badOption(":test function requested a format failure.")
            return state.render()
        }
        fun select(match: Mf2FunctionMatch): Int? {
            val state = state(match.value, match.inheritedSource, match::optionValue)
            if (state.failsSelect) throw Mf2Error.badSelector(":test function requested selection failure.")
            if (state.input.toLong() != 1L) return null
            return if (state.decimals == 1 && match.key == "1.0") 2 else if (match.key == "1") 1 else null
        }
        return base.withFunction("test:function") { format(it, true) }
            .withFunction("test:select") { format(it, false) }.withFunction("test:format") { format(it, false) }
            .withSelector("test:function", ::select).withSelector("test:select", ::select)
            .withSelector("test:format") { throw Mf2Error.badSelector(":test:format cannot be used for selection.") }
    }
    private data class State(val input: Double, var decimals: Int = 0, var failsFormat: Boolean = false, var failsSelect: Boolean = false) {
        fun render(): String {
            val sign = if (input < 0) "-" else ""
            val integer = floor(abs(input))
            return sign + integer.toLong().toString() + if (decimals == 1) "." + floor((abs(input) - integer) * 10).toLong() else ""
        }
        fun apply(options: (String, String?) -> String?) {
            options("decimalPlaces", null)?.let { decimals = when(it) { "0" -> 0; "1" -> 1; else -> throw Mf2Error.badOption(":test function decimalPlaces must be 0 or 1.") } }
            when(options("fails", null)) { "always" -> { failsFormat = true; failsSelect = true }; "format" -> failsFormat = true; "select" -> failsSelect = true }
        }
    }
    private fun state(value: String, source: Mf2FunctionSource?, options: (String, String?) -> String?): State {
        val chain = ArrayDeque<Mf2FunctionSource>()
        var current = source
        while (current != null) { chain.addFirst(current); current = current.inherited }
        val input = (chain.firstOrNull()?.value ?: value).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw Mf2Error.badOperand("Unicode test function requires a numeric operand.")
        val state = State(input)
        for (item in chain) if (item.function["name"] in listOf("test:function", "test:select", "test:format")) state.apply(item::optionValue)
        state.apply(options)
        return state
    }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (ch in value) when (ch) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch) }
            append('"')
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::json)
        else -> value.toString()
    }
}
