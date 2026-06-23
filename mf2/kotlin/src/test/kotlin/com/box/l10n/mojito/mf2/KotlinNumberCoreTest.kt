package com.box.l10n.mojito.mf2

import java.math.RoundingMode
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.system.exitProcess

object KotlinNumberCoreTest {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/number-core/cases.json")
        }
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val formatCases = checkFormatCases(KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"]))
        val referenceCases = checkReferenceCases(KotlinJsonSupport.arrayOrEmpty(fixture["intlReferenceCases"]))
        val errorCases = checkErrorCases(KotlinJsonSupport.arrayOrEmpty(fixture["errorCases"]))
        val registryCases = checkRegistryIntegration(KotlinJsonSupport.arrayOrEmpty(fixture["registryCases"]))
        val registryErrorCases = checkRegistryErrorCases(KotlinJsonSupport.arrayOrEmpty(fixture["registryErrorCases"]))
        checkDirectParts()
        println(
            "Kotlin number core test passed $formatCases format cases, " +
                "$referenceCases JDK reference cases, $errorCases error cases, " +
                "$registryCases registry cases, and $registryErrorCases registry error cases.",
        )
        return 0
    }

    private fun checkDirectParts() {
        val formatted = Mf2NumberCore.format(1234.5)
        val expected = listOf(mapOf("type" to "text", "value" to formatted))
        val actual = Mf2NumberCore.formatToParts(1234.5)
        if (actual != expected) {
            throw AssertionError("number core formatToParts expected $expected, got $actual")
        }
    }

    private fun checkFormatCases(cases: List<Any?>): Int {
        var checked = 0
        for (rawCase in cases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val actual = Mf2NumberCore.format(
                item["value"],
                options(
                    KotlinJsonSupport.string(item["locale"]),
                    KotlinJsonSupport.objOrEmpty(item["options"]),
                ),
            )
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
            val rawOptions = KotlinJsonSupport.objOrEmpty(item["options"])
            val locale = KotlinJsonSupport.string(item["locale"])
            val value = item["value"]
            val actual = Mf2NumberCore.format(value, options(locale, rawOptions))
            val expected = jdkFormat(locale, value, rawOptions)
            if (actual != expected) {
                throw AssertionError("JDK reference $locale $rawOptions: expected $expected, got $actual")
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
                Mf2NumberCore.format(
                    item["value"],
                    options(
                        KotlinJsonSupport.string(item["locale"]),
                        KotlinJsonSupport.objOrEmpty(item["options"]),
                    ),
                )
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

    private fun checkRegistryIntegration(registryCases: List<Any?>): Int {
        var checked = 0
        val registry = Mf2NumberCore.registry()
        val actual = registry.format(
            Mf2FunctionCall(
                value = "1234.5",
                rawValue = 1234.5,
                function = mapOf("name" to "currency"),
                locale = "en-US",
                optionResolver = { optionName, fallback -> if (optionName == "currency") "USD" else fallback },
                inheritedSource = null,
            ),
        )
        if (actual != "$1,234.50") {
            throw AssertionError("registry currency format expected $1,234.50, got $actual")
        }
        checked++

        val parsed = Mf2Parser.parseToModel(".input {${'$'}count :number}\n.match ${'$'}count\none {{one}}\n* {{other}}")
        val result = Mf2Formatter.formatMessage(
            parsed.model!!,
            mapOf("count" to 1),
            locale = "en",
            functions = registry,
        )
        if (result.value != "one" || result.hasErrors) {
            throw AssertionError(
                "registry should preserve portable number selectors, got ${result.value} errors=${result.errors}",
            )
        }
        checked++

        for (rawCase in registryCases) {
            val item = KotlinJsonSupport.obj(rawCase)
            val registryParsed = Mf2Parser.parseToModel(KotlinJsonSupport.string(item["source"]))
            if (registryParsed.hasDiagnostics) {
                throw AssertionError("${KotlinJsonSupport.string(item["name"])}: parse diagnostics ${registryParsed.diagnostics}")
            }
            val registryResult = Mf2Formatter.formatMessage(
                registryParsed.model!!,
                KotlinJsonSupport.objOrEmpty(item["arguments"]),
                locale = KotlinJsonSupport.string(item["locale"]),
                functions = registry,
            )
            val expected = KotlinJsonSupport.string(item["expected"])
            if (registryResult.value != expected || registryResult.hasErrors) {
                throw AssertionError(
                    "${KotlinJsonSupport.string(item["name"])}: expected $expected, " +
                        "got ${registryResult.value} errors=${registryResult.errors}",
                )
            }
            checked++
        }
        return checked
    }

    private fun checkRegistryErrorCases(registryErrorCases: List<Any?>): Int {
        var checked = 0
        val registry = Mf2NumberCore.registry()
        for (rawCase in registryErrorCases) {
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
            val actual = result.errors.map { it.code }
            val expected = KotlinJsonSupport.arrayOrEmpty(item["expectedErrors"]).map(KotlinJsonSupport::string)
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

    private fun options(locale: String, rawOptions: Map<String, Any?>): Mf2NumberCore.Options {
        val builder = Mf2NumberCore.options().locale(locale)
        if (rawOptions.containsKey("style")) {
            builder.style(style(KotlinJsonSupport.string(rawOptions["style"])))
        }
        if (rawOptions.containsKey("currency")) {
            builder.currency(KotlinJsonSupport.string(rawOptions["currency"]))
        }
        if (rawOptions.containsKey("currencyDisplay")) {
            builder.currencyDisplay(currencyDisplay(KotlinJsonSupport.string(rawOptions["currencyDisplay"])))
        }
        if (rawOptions.containsKey("useGrouping")) {
            builder.useGrouping(booleanValue(rawOptions["useGrouping"]))
        }
        if (rawOptions.containsKey("minimumFractionDigits")) {
            builder.minimumFractionDigits(intValue(rawOptions["minimumFractionDigits"]))
        }
        if (rawOptions.containsKey("maximumFractionDigits")) {
            builder.maximumFractionDigits(intValue(rawOptions["maximumFractionDigits"]))
        }
        if (rawOptions.containsKey("signDisplay")) {
            builder.signDisplay(signDisplay(KotlinJsonSupport.string(rawOptions["signDisplay"])))
        }
        return builder.build()
    }

    private fun jdkFormat(locale: String, rawValue: Any?, rawOptions: Map<String, Any?>): String {
        val tag = Locale.forLanguageTag(locale)
        val formatter = when (val style = KotlinJsonSupport.stringOrDefault(rawOptions["style"], "number")) {
            "number" -> NumberFormat.getNumberInstance(tag)
            "percent" -> NumberFormat.getPercentInstance(tag)
            "currency" -> NumberFormat.getCurrencyInstance(tag).also {
                it.currency = Currency.getInstance(KotlinJsonSupport.string(rawOptions["currency"]))
            }
            else -> throw IllegalArgumentException("Unsupported reference style: $style")
        }
        formatter.roundingMode = RoundingMode.HALF_UP
        return formatter.format(numberValue(rawValue))
    }

    private fun style(value: String): Mf2NumberCore.Style =
        when (value) {
            "number" -> Mf2NumberCore.Style.NUMBER
            "integer" -> Mf2NumberCore.Style.INTEGER
            "percent" -> Mf2NumberCore.Style.PERCENT
            "currency" -> Mf2NumberCore.Style.CURRENCY
            else -> throw IllegalArgumentException("Unknown number core style: $value")
        }

    private fun currencyDisplay(value: String): Mf2NumberCore.CurrencyDisplay =
        when (value) {
            "symbol" -> Mf2NumberCore.CurrencyDisplay.SYMBOL
            "narrowSymbol" -> Mf2NumberCore.CurrencyDisplay.NARROW_SYMBOL
            "code" -> Mf2NumberCore.CurrencyDisplay.CODE
            else -> throw IllegalArgumentException("Unknown currency display: $value")
        }

    private fun signDisplay(value: String): Mf2NumberCore.SignDisplay =
        when (value) {
            "auto" -> Mf2NumberCore.SignDisplay.AUTO
            "always" -> Mf2NumberCore.SignDisplay.ALWAYS
            "never" -> Mf2NumberCore.SignDisplay.NEVER
            else -> throw IllegalArgumentException("Unknown sign display: $value")
        }

    private fun numberValue(value: Any?): Number =
        value as? Number ?: value.toString().toDouble()

    private fun booleanValue(value: Any?): Boolean =
        value as? Boolean ?: value.toString().toBoolean()

    private fun intValue(value: Any?): Int =
        when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
}
