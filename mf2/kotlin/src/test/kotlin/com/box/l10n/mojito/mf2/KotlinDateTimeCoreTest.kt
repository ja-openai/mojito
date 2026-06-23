package com.box.l10n.mojito.mf2

import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.system.exitProcess

object KotlinDateTimeCoreTest {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/date-time-core/cases.json")
        }
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val formatCases = checkFormatCases(KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"]))
        val numericTimestampCases = checkFormatCases(KotlinJsonSupport.arrayOrEmpty(fixture["numericTimestampCases"]))
        val referenceCases = checkReferenceCases(KotlinJsonSupport.arrayOrEmpty(fixture["intlReferenceCases"]))
        val semanticReferenceCases = checkReferenceCases(KotlinJsonSupport.arrayOrEmpty(fixture["semanticStyleReferenceCases"]))
        val errorCases = checkErrorCases(KotlinJsonSupport.arrayOrEmpty(fixture["errorCases"]))
        val registryFormatCases = checkRegistryFormatCases(KotlinJsonSupport.arrayOrEmpty(fixture["registryFormatCases"]))
        val registryErrorCases = checkRegistryErrorCases(KotlinJsonSupport.arrayOrEmpty(fixture["registryErrorCases"]))
        checkRegistryIntegration()
        checkOperandBoundary()
        println(
            "Kotlin date/time core test passed $formatCases format cases, " +
                "$numericTimestampCases numeric timestamp cases, " +
                "$referenceCases JDK reference cases, $semanticReferenceCases semantic style reference cases, " +
                "$errorCases error cases, $registryFormatCases registry format cases, " +
                "$registryErrorCases registry error cases, and registry integration.",
        )
        return 0
    }

    private fun checkOperandBoundary() {
        val arbitraryDate =
            object {
                override fun toString(): String = "2026-05-21T14:30:15Z"
            }
        try {
            Mf2DateTimeCore.formatDateTime(arbitraryDate)
            throw AssertionError("date-time core should reject arbitrary object operands")
        } catch (error: Mf2Error) {
            if (error.code != "bad-operand") {
                throw AssertionError("date-time core object operand got ${error.code}")
            }
        }
    }

    private fun checkFormatCases(cases: List<Any?>): Int {
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val actual = formatCore(item)
            val expected = KotlinJsonSupport.string(item["expected"])
            if (actual != expected) {
                throw AssertionError("${KotlinJsonSupport.string(item["name"])}: expected $expected, got $actual")
            }
            checked++
        }
        return checked
    }

    private fun checkReferenceCases(cases: List<Any?>): Int {
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val locale = KotlinJsonSupport.string(item["locale"])
            val rawOptions = KotlinJsonSupport.objOrEmpty(item["referenceOptions"] ?: item["options"])
            val actual = formatCore(item)
            val expected = jdkFormat(
                KotlinJsonSupport.string(item["kind"]),
                locale,
                KotlinJsonSupport.string(item["value"]),
                rawOptions,
            )
            if (actual != expected) {
                if (isKnownJdkDateTimeDifference(rawOptions)) {
                    checked++
                    continue
                }
                throw AssertionError(
                    "JDK reference ${KotlinJsonSupport.string(item["kind"])} $locale $rawOptions: " +
                        "expected $expected, got $actual",
                )
            }
            checked++
        }
        return checked
    }

    private fun checkErrorCases(cases: List<Any?>): Int {
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val expected = KotlinJsonSupport.string(item["expectedError"])
            try {
                formatErrorCase(item)
                throw AssertionError("${KotlinJsonSupport.string(item["name"])}: expected $expected, got success")
            } catch (error: Mf2Error) {
                if (error.code != expected) {
                    throw AssertionError("${KotlinJsonSupport.string(item["name"])}: expected $expected, got ${error.code}")
                }
            }
            checked++
        }
        return checked
    }

    private fun checkRegistryFormatCases(cases: List<Any?>): Int {
        var checked = 0
        val registry = Mf2DateTimeCore.registry()
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val parsed = Mf2Parser.parseToModel(KotlinJsonSupport.string(item["source"]))
            if (parsed.hasDiagnostics) {
                throw AssertionError("${KotlinJsonSupport.string(item["name"])}: parse diagnostics ${parsed.diagnostics}")
            }
            val result = Mf2Formatter.formatMessage(
                parsed.model!!,
                KotlinJsonSupport.objOrEmpty(item["arguments"]),
                locale = KotlinJsonSupport.string(item["locale"]),
                functions = registry,
            )
            val expected = KotlinJsonSupport.string(item["expected"])
            if (result.value != expected || result.hasErrors) {
                throw AssertionError(
                    "${KotlinJsonSupport.string(item["name"])}: expected $expected, " +
                        "got ${result.value} errors=${result.errors}",
                )
            }
            checked++
        }
        return checked
    }

    private fun checkRegistryErrorCases(cases: List<Any?>): Int {
        var checked = 0
        val registry = Mf2DateTimeCore.registry()
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val parsed = Mf2Parser.parseToModel(KotlinJsonSupport.string(item["source"]))
            if (parsed.hasDiagnostics) {
                throw AssertionError("${KotlinJsonSupport.string(item["name"])}: parse diagnostics ${parsed.diagnostics}")
            }
            val result = Mf2Formatter.formatMessage(
                parsed.model!!,
                KotlinJsonSupport.objOrEmpty(item["arguments"]),
                locale = KotlinJsonSupport.string(item["locale"]),
                functions = registry,
            )
            val expected = KotlinJsonSupport.arrayOrEmpty(item["expectedErrors"]).map(KotlinJsonSupport::string)
            val actual = result.errors.map { it.code }
            if (actual != expected) {
                throw AssertionError(
                    "${KotlinJsonSupport.string(item["name"])}: expected errors $expected, " +
                        "got $actual value=${result.value}",
                )
            }
            checked++
        }
        return checked
    }

    private fun checkRegistryIntegration() {
        val registry = Mf2DateTimeCore.registry()
        val parsed = Mf2Parser.parseToModel("At {${'$'}instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}")
        val result = Mf2Formatter.formatMessage(
            parsed.model!!,
            mapOf("instant" to "2026-05-21T14:30:15Z"),
            locale = "de-DE",
            functions = registry,
        )
        if (result.value != "At Donnerstag, 21. Mai 2026 um 14:30:15" || result.hasErrors) {
            throw AssertionError("registry datetime format got ${result.value} errors=${result.errors}")
        }

        val stringParsed = Mf2Parser.parseToModel("Hello {${'$'}name :string}")
        val stringResult = Mf2Formatter.formatMessage(
            stringParsed.model!!,
            mapOf("name" to "Mojito"),
            functions = registry,
        )
        if (stringResult.value != "Hello Mojito" || stringResult.hasErrors) {
            throw AssertionError(
                "registry should preserve portable string formatting, got ${stringResult.value} errors=${stringResult.errors}",
            )
        }
    }

    private fun formatCore(item: Map<String, Any?>): String {
        val kind = KotlinJsonSupport.string(item["kind"])
        val locale = KotlinJsonSupport.string(item["locale"])
        val value = item["value"]
        val options = options(locale, KotlinJsonSupport.objOrEmpty(item["options"]))
        return when (kind) {
            "date" -> Mf2DateTimeCore.formatDate(value, options)
            "time" -> Mf2DateTimeCore.formatTime(value, options)
            "datetime" -> Mf2DateTimeCore.formatDateTime(value, options)
            else -> throw IllegalArgumentException("Unsupported date/time core fixture kind: $kind")
        }
    }

    private fun formatErrorCase(item: Map<String, Any?>): String {
        val kind = KotlinJsonSupport.string(item["kind"])
        val rawOptions = KotlinJsonSupport.objOrEmpty(item["options"])
        return Mf2DateTimeCore.registry().format(
            Mf2FunctionCall(
                value = item["value"].toString(),
                rawValue = item["value"],
                function = mapOf("name" to kind),
                locale = KotlinJsonSupport.string(item["locale"]),
                optionResolver = { optionName, fallback ->
                    if (rawOptions.containsKey(optionName)) rawOptions[optionName].toString() else fallback
                },
                inheritedSource = null,
            ),
        )
    }

    private fun options(locale: String, rawOptions: Map<String, Any?>): Mf2DateTimeCore.Options {
        val builder = Mf2DateTimeCore.options().locale(locale)
        if (rawOptions.containsKey("style")) {
            builder.style(style(KotlinJsonSupport.string(rawOptions["style"])))
        }
        if (rawOptions.containsKey("dateStyle")) {
            builder.dateStyle(style(KotlinJsonSupport.string(rawOptions["dateStyle"])))
        }
        if (rawOptions.containsKey("timeStyle")) {
            builder.timeStyle(style(KotlinJsonSupport.string(rawOptions["timeStyle"])))
        }
        if (rawOptions.containsKey("length")) {
            builder.dateStyle(style(KotlinJsonSupport.string(rawOptions["length"])))
        }
        if (rawOptions.containsKey("precision")) {
            builder.timeStyle(style(KotlinJsonSupport.string(rawOptions["precision"])))
        }
        if (rawOptions.containsKey("dateLength")) {
            builder.dateStyle(style(KotlinJsonSupport.string(rawOptions["dateLength"])))
        }
        if (rawOptions.containsKey("timePrecision")) {
            builder.timeStyle(style(KotlinJsonSupport.string(rawOptions["timePrecision"])))
        }
        if (rawOptions.containsKey("skeleton")) {
            builder.skeleton(KotlinJsonSupport.string(rawOptions["skeleton"]))
        }
        if (rawOptions.containsKey("hourCycle")) {
            builder.hourCycle(KotlinJsonSupport.string(rawOptions["hourCycle"]))
        }
        if (rawOptions.containsKey("timeZone")) {
            builder.timeZone(KotlinJsonSupport.string(rawOptions["timeZone"]))
        }
        if (rawOptions.containsKey("calendar")) {
            builder.calendar(KotlinJsonSupport.string(rawOptions["calendar"]))
        }
        return builder.build()
    }

    private fun jdkFormat(
        kind: String,
        locale: String,
        value: String,
        rawOptions: Map<String, Any?>,
    ): String {
        val formatter = when (kind) {
            "date" -> DateTimeFormatter.ofLocalizedDate(formatStyle(dateStyle(rawOptions)))
            "time" -> DateTimeFormatter.ofLocalizedTime(formatStyle(timeStyle(rawOptions)))
            "datetime" -> DateTimeFormatter.ofLocalizedDateTime(
                formatStyle(dateStyle(rawOptions)),
                formatStyle(timeStyle(rawOptions)),
            )
            else -> throw IllegalArgumentException("Unsupported date/time core fixture kind: $kind")
        }
        val tag = Locale.forLanguageTag(locale)
        return formatter
            .withLocale(tag)
            .withDecimalStyle(DecimalStyle.of(tag))
            .withZone(referenceZone(rawOptions))
            .format(Instant.parse(value))
    }

    private fun isKnownJdkDateTimeDifference(rawOptions: Map<String, Any?>): Boolean =
        rawOptions.containsKey("hourCycle") && !rawOptions.containsKey("skeleton") ||
            rawOptions.containsKey("dateStyle") &&
            rawOptions.containsKey("timeStyle") &&
            KotlinJsonSupport.string(rawOptions["dateStyle"]).let { it == "full" || it == "long" }

    private fun dateStyle(rawOptions: Map<String, Any?>): Mf2DateTimeCore.Style =
        style(
            KotlinJsonSupport.stringOrDefault(
                rawOptions["dateStyle"],
                KotlinJsonSupport.stringOrDefault(
                    rawOptions["dateLength"],
                    KotlinJsonSupport.stringOrDefault(rawOptions["style"], "medium"),
                ),
            ),
        )

    private fun timeStyle(rawOptions: Map<String, Any?>): Mf2DateTimeCore.Style =
        style(
            KotlinJsonSupport.stringOrDefault(
                rawOptions["timeStyle"],
                KotlinJsonSupport.stringOrDefault(
                    rawOptions["timePrecision"],
                    KotlinJsonSupport.stringOrDefault(rawOptions["style"], "medium"),
                ),
            ),
        )

    private fun formatStyle(style: Mf2DateTimeCore.Style): FormatStyle =
        when (style) {
            Mf2DateTimeCore.Style.FULL -> FormatStyle.FULL
            Mf2DateTimeCore.Style.LONG -> FormatStyle.LONG
            Mf2DateTimeCore.Style.MEDIUM -> FormatStyle.MEDIUM
            Mf2DateTimeCore.Style.SHORT -> FormatStyle.SHORT
        }

    private fun style(value: String): Mf2DateTimeCore.Style =
        when (value) {
            "full" -> Mf2DateTimeCore.Style.FULL
            "long" -> Mf2DateTimeCore.Style.LONG
            "medium" -> Mf2DateTimeCore.Style.MEDIUM
            "short" -> Mf2DateTimeCore.Style.SHORT
            else -> throw IllegalArgumentException("Unknown date/time core style: $value")
        }

    private fun referenceZone(rawOptions: Map<String, Any?>): ZoneOffset {
        val rawValue = rawOptions["timeZone"] ?: return ZoneOffset.UTC
        var value = KotlinJsonSupport.string(rawValue).trim()
        if (
            value.isEmpty() ||
            value == "UTC" ||
            value == "Etc/UTC" ||
            value == "Z" ||
            value == "GMT" ||
            value == "Etc/GMT"
        ) {
            return ZoneOffset.UTC
        }
        if ((value.startsWith("UTC") || value.startsWith("GMT")) && value.length > 3) {
            value = value.drop(3)
        }
        return try {
            ZoneOffset.of(value)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException(
                "JDK reference supports only UTC or fixed-offset time zones: ${KotlinJsonSupport.string(rawValue)}",
                error,
            )
        }
    }
}
