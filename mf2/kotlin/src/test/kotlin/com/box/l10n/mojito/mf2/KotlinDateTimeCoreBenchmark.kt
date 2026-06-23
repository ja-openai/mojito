package com.box.l10n.mojito.mf2

import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

object KotlinDateTimeCoreBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixturePath = if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("../conformance/fixtures/date-time-core/cases.json")
        }
        val iterations = args.getOrNull(1)?.toInt() ?: 100_000
        val warmupIterations = args.getOrNull(2)?.toInt() ?: 10_000
        val fixture = KotlinJsonSupport.obj(KotlinJsonSupport.parse(fixturePath))
        val formatCases = KotlinJsonSupport.arrayOrEmpty(fixture["formatCases"])
            .map(::dateTimeCoreCase)
        val referenceCases = KotlinJsonSupport.arrayOrEmpty(fixture["intlReferenceCases"])
            .map(::jdkCase)

        bench("kotlin-datetime-core-format", iterations, warmupIterations, formatCases) { it.format() }
        bench("kotlin-jdk-datetime-format", iterations, warmupIterations, referenceCases) { it.format() }
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

    private fun dateTimeCoreCase(rawCase: Any?): DateTimeCoreCase {
        val item = KotlinJsonSupport.obj(rawCase)
        return DateTimeCoreCase(
            KotlinJsonSupport.string(item["kind"]),
            item["value"],
            options(
                KotlinJsonSupport.string(item["locale"]),
                KotlinJsonSupport.objOrEmpty(item["options"]),
            ),
        )
    }

    private fun jdkCase(rawCase: Any?): JdkCase {
        val item = KotlinJsonSupport.obj(rawCase)
        val kind = KotlinJsonSupport.string(item["kind"])
        val rawOptions = KotlinJsonSupport.objOrEmpty(item["options"])
        val formatter = when (kind) {
            "date" -> DateTimeFormatter.ofLocalizedDate(formatStyle(dateStyle(rawOptions)))
            "time" -> DateTimeFormatter.ofLocalizedTime(formatStyle(timeStyle(rawOptions)))
            "datetime" -> DateTimeFormatter.ofLocalizedDateTime(
                formatStyle(dateStyle(rawOptions)),
                formatStyle(timeStyle(rawOptions)),
            )
            else -> throw IllegalArgumentException("Unsupported date/time core fixture kind: $kind")
        }
        val locale = Locale.forLanguageTag(KotlinJsonSupport.string(item["locale"]))
        return JdkCase(
            Instant.parse(KotlinJsonSupport.string(item["value"])),
            formatter
                .withLocale(locale)
                .withDecimalStyle(DecimalStyle.of(locale))
                .withZone(referenceZone(rawOptions)),
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

    private data class DateTimeCoreCase(
        val kind: String,
        val value: Any?,
        val options: Mf2DateTimeCore.Options,
    ) {
        fun format(): String =
            when (kind) {
                "date" -> Mf2DateTimeCore.formatDate(value, options)
                "time" -> Mf2DateTimeCore.formatTime(value, options)
                "datetime" -> Mf2DateTimeCore.formatDateTime(value, options)
                else -> throw IllegalArgumentException("Unsupported date/time core fixture kind: $kind")
            }
    }

    private data class JdkCase(
        val instant: Instant,
        val formatter: DateTimeFormatter,
    ) {
        fun format(): String = formatter.format(instant)
    }
}
