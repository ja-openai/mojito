package com.box.l10n.mojito.mf2

import java.math.RoundingMode
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object KotlinNumberCoreBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/number-core/cases.json")
        }
        val iterations = args.getOrNull(1)?.toInt() ?: 100_000
        val warmupIterations = args.getOrNull(2)?.toInt() ?: 10_000
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val formatCases = KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"])
            .map(::numberCoreCase)
        val referenceCases = KotlinJsonSupport.arrayOrEmpty(fixture["intlReferenceCases"])
            .map(::jdkCase)

        bench("kotlin-number-core-format", iterations, warmupIterations, formatCases) { it.format() }
        bench("kotlin-jdk-number-format", iterations, warmupIterations, referenceCases) { it.format() }
    }

    private fun <T> bench(
        label: String,
        iterations: Int,
        warmupIterations: Int,
        cases: List<T>,
        formatter: (T) -> String,
    ) {
        repeat(warmupIterations) { index ->
            formatter(cases[index % cases.size])
        }

        var bytes = 0L
        val started = System.nanoTime()
        repeat(iterations) { index ->
            bytes += KotlinBenchmarkSupport.utf8Length(formatter(cases[index % cases.size]))
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        System.out.printf(
            "%s iterations=%d warmup=%d cases=%d seconds=%.6f ns_per_op=%.1f ops_per_second=%.0f bytes=%d%n",
            label,
            iterations,
            warmupIterations,
            cases.size,
            seconds,
            seconds * 1_000_000_000.0 / iterations,
            iterations / seconds,
            bytes,
        )
    }

    private fun numberCoreCase(rawCase: Any?): NumberCoreCase {
        val item = KotlinJsonSupport.obj(rawCase)
        return NumberCoreCase(
            item["value"],
            options(
                KotlinJsonSupport.string(item["locale"]),
                KotlinJsonSupport.objOrEmpty(item["options"]),
            ),
        )
    }

    private fun jdkCase(rawCase: Any?): JdkCase {
        val item = KotlinJsonSupport.obj(rawCase)
        return JdkCase(
            numberValue(item["value"]),
            jdkFormatter(
                KotlinJsonSupport.string(item["locale"]),
                KotlinJsonSupport.objOrEmpty(item["options"]),
            ),
        )
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

    private fun jdkFormatter(locale: String, rawOptions: Map<String, Any?>): NumberFormat {
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
        return formatter
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

    private data class NumberCoreCase(
        val value: Any?,
        val options: Mf2NumberCore.Options,
    ) {
        fun format(): String = Mf2NumberCore.format(value, options)
    }

    private data class JdkCase(
        val value: Number,
        val formatter: NumberFormat,
    ) {
        fun format(): String = formatter.format(value)
    }
}
