package com.box.l10n.mojito.mf2

import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object KotlinRuntimeRegressionTests {
    @JvmStatic fun main(args: Array<String>) { run() }
    fun run() {
        for (registry in listOf(Mf2FunctionRegistry.portable(), Mf2FunctionRegistry.defaults())) {
            val integer = Mf2Parser.parseToModel("{${'$'}x :integer}").model!!
            for (operand in listOf(1e19, -1e19, 9223372036854775808.0, Math.nextDown(-9223372036854775808.0))) {
                val result = Mf2Formatter.formatMessage(integer, mapOf("x" to operand), functions = registry)
                check(result.errors.any { it.code == "bad-operand" }) { "integer conversion bound: $operand: $result" }
            }
            for (operand in listOf(Math.nextDown(9223372036854775808.0), -9223372036854775808.0, Math.nextUp(-9223372036854775808.0))) {
                check(!Mf2Formatter.formatMessage(integer, mapOf("x" to operand), functions = registry).hasErrors) { "valid integer boundary: $operand" }
            }
            val native = Mf2Parser.parseToModel("{${'$'}x}").model!!
            check(Mf2Formatter.formatMessage(native, mapOf("x" to 1e19), functions = registry).value == "10000000000000000000")
        }
        check(truncateInteger(-9223372036854775808.0) == Long.MIN_VALUE)

        fun rejectMutation(function: Map<String, Any?>, source: Mf2FunctionSource?) {
            @Suppress("UNCHECKED_CAST")
            val literal = ((source!!.function["options"] as Map<*, *>)["maximumFractionDigits"] as MutableMap<String, Any?>)
            check(runCatching { literal["value"] = "2" }.exceptionOrNull() is UnsupportedOperationException)
            @Suppress("UNCHECKED_CAST")
            check(runCatching { (function as MutableMap<String, Any?>)["name"] = "changed" }.exceptionOrNull() is UnsupportedOperationException)
        }
        val ownershipModel = Mf2Parser.parseToModel(".local ${'$'}n = {1.29 :number maximumFractionDigits=0}\n.local ${'$'}m = {${'$'}n :number}\n{{{${'$'}n :probe}|{${'$'}m :number}|{${'$'}n :number}}}").model!!
        val ownershipRegistry = Mf2FunctionRegistry.portable().withFunction("probe") { call ->
            rejectMutation(call.function, call.inheritedSource)
            call.value
        }
        repeat(2) {
            val result = Mf2Formatter.formatMessage(ownershipModel, functions = ownershipRegistry)
            check(!result.hasErrors && result.value == "1|1|1") { result }
        }
        val selectorOwnershipModel = Mf2Parser.parseToModel(".local ${'$'}n = {1.29 :number maximumFractionDigits=0}\n.match ${'$'}n\none {{{${'$'}n :number}}}\n* {{other}}").model!!
        val selectorOwnershipRegistry = Mf2FunctionRegistry.portable().withSelector("number") { match ->
            rejectMutation(match.function, match.inheritedSource)
            1
        }
        repeat(2) {
            val result = Mf2Formatter.formatMessage(selectorOwnershipModel, functions = selectorOwnershipRegistry)
            check(!result.hasErrors && result.value == "1") { result }
        }
        val copiedSourceRegistry = Mf2FunctionRegistry.portable().withFunction("number") { call ->
            val inherited = call.inheritedSource
            if (inherited == null) call.value else {
                numericSourceOperand(inherited)
                numericSourceOperand(inherited.copy(value = "2", inherited = null))!!
            }
        }
        val copiedSourceModel = Mf2Parser.parseToModel(".local ${'$'}n = {1 :number}\n{{{${'$'}n :number}}}").model!!
        check(Mf2Formatter.formatMessage(copiedSourceModel, functions = copiedSourceRegistry).value == "2")
        val registry = Mf2FunctionRegistry.portable()
        val probe = Mf2Parser.parseToModel("{:probe u:dir=${'$'}direction}").model!!
        check(Mf2Formatter.formatMessage(probe, mapOf("direction" to "rtl"), functions = registry.withFunction("probe") { it.optionValue("u:dir", "removed")!! }).value == "removed")
        val numeric = Mf2Parser.parseToModel("{1 :number}").model!!
        for (locale in listOf("en", "ar", "ar-Latn", "en-Arab", "en-Qaaa", "zz", "und", "az-IR", "sd-IN", "en-u-nu-arab")) {
            val ltr = locale in setOf("en", "ar-Latn", "sd-IN", "en-u-nu-arab")
            val result = Mf2Formatter.formatMessage(numeric, locale = locale, bidiIsolation = Mf2BidiIsolation.DEFAULT, functions = registry)
            check(result.value == if (ltr) "1" else "\u20681\u2069") { "numeric direction $locale" }
        }
        val custom = Mf2Formatter.formatMessage(numeric, bidiIsolation = Mf2BidiIsolation.DEFAULT, functions = registry.withFunction("number") { "custom" })
        check(custom.value == "\u2068custom\u2069")
        val selector = Mf2Formatter.formatMessage(numeric, bidiIsolation = Mf2BidiIsolation.DEFAULT, functions = registry.withSelector("number") { null })
        check(selector.value == "1")
        val directed = Mf2Parser.parseToModel(".local ${'$'}n = {1 :number u:dir=rtl}\n{{{${'$'}n}}}").model!!
        check(Mf2Formatter.formatMessage(directed, bidiIsolation = Mf2BidiIsolation.DEFAULT, functions = registry).value == "\u20671\u2069")

        fun render(source: String, arguments: Map<String, Any?> = emptyMap(), locale: String = "en", functions: Mf2FunctionRegistry = Mf2FunctionRegistry.portable()): Mf2FormatResult {
            val parsed = Mf2Parser.parseToModel(source)
            check(!parsed.hasDiagnostics) { parsed.diagnostics }
            return Mf2Formatter.formatMessage(parsed.model!!, arguments, locale, functions = functions)
        }
        val digits = render("{1 :number minimumFractionDigits=1000}")
        check(!digits.hasErrors && digits.value == "1." + "0".repeat(1000))
        check(render("{1e-100000 :offset add=1}").errors.any { it.code == "bad-operand" })
        val chain = buildString {
            append(".local ${'$'}v0 = {1 :number}\n")
            for (index in 1 until 7000) append(".local ${'$'}v$index = {${'$'}v${index - 1} :number}\n")
            append("{{{${'$'}v6999 :number}}}")
        }
        val chained = render(chain)
        check(!chained.hasErrors && chained.value == "1")
        val model = Mf2Parser.parseToModel("{#a @role=|old|}Hello{/a}").model!!
        val parts = Mf2Formatter.formatMessageToParts(model)
        @Suppress("UNCHECKED_CAST")
        val attributes = parts.parts[0]["attributes"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val literal = attributes["role"] as MutableMap<String, Any?>
        literal["value"] = "new"
        val fresh = Mf2Formatter.formatMessageToParts(model)
        check(((fresh.parts[0]["attributes"] as Map<*, *>)["role"] as Map<*, *>)["value"] == "old")
        val instant = ZonedDateTime.parse("2026-05-21T14:30:15Z")
        for (tag in listOf("en-US", "fr-FR")) for (zone in listOf("UTC", "America/Los_Angeles")) for (style in FormatStyle.entries) {
            val name = style.name.lowercase(Locale.ROOT)
            val temporal = instant.withZoneSameInstant(ZoneId.of(zone))
            val expectedTime = DateTimeFormatter.ofLocalizedTime(style).withLocale(Locale.forLanguageTag(tag)).format(temporal)
            val expectedDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, style).withLocale(Locale.forLanguageTag(tag)).format(temporal)
            val time = render("{${'$'}x :time timeStyle=$name timeZone=|$zone|}", mapOf("x" to instant), tag, Mf2FunctionRegistry.defaults())
            val dateTime = render("{${'$'}x :datetime dateStyle=short timeStyle=$name timeZone=|$zone|}", mapOf("x" to instant), tag, Mf2FunctionRegistry.defaults())
            check(!time.hasErrors && time.value == expectedTime) { "time $tag $zone $name: $time" }
            check(!dateTime.hasErrors && dateTime.value == expectedDateTime)
        }
    }
}
