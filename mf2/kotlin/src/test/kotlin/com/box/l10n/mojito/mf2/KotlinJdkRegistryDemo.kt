package com.box.l10n.mojito.mf2

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

object KotlinJdkRegistryDemo {
    private const val AMOUNT = 12345.678
    private const val RATE = 0.1234
    private const val PRICE = 9876.5
    private val due = LocalDate.of(2026, 5, 21)
    private val start = LocalTime.of(14, 30, 15)
    private val created = ZonedDateTime.of(2026, 5, 21, 14, 30, 15, 0, ZoneOffset.UTC)
    private val source = listOf(
        "number={${'$'}amount :number minimumFractionDigits=2}",
        "percent={${'$'}rate :percent minimumFractionDigits=1 maximumFractionDigits=1}",
        "currency={${'$'}price :currency currency=EUR}",
        "date={${'$'}due :date dateStyle=full timeZone=UTC}",
        "time={${'$'}start :time timeStyle=medium timeZone=UTC}",
        "datetime={${'$'}created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
    ).joinToString("; ")

    @JvmStatic
    fun main(args: Array<String>) {
        val quiet = args.firstOrNull() == "--quiet"
        val message = parse(source)
        val arguments = mapOf(
            "amount" to AMOUNT,
            "rate" to RATE,
            "price" to PRICE,
            "due" to due,
            "start" to start,
            "created" to created,
        )
        for (locale in listOf("en-US", "fr-FR", "ja-JP", "ar-EG")) {
            val result = Mf2Formatter.formatMessage(
                model = message,
                arguments = arguments,
                locale = locale,
                functions = Mf2FunctionRegistry.defaults(),
            )
            if (result.hasErrors) {
                error("$locale returned errors: ${result.errors}")
            }
            val expected = expected(locale)
            if (result.value != expected) {
                error("$locale expected \"$expected\", got \"${result.value}\"")
            }
            if (!quiet) println("$locale -> ${result.value}")
        }
        assertErrorCode("invalid currency option", "currency={${'$'}price :currency currency=||}", "bad-option")
        assertErrorCode("missing currency option", "currency={${'$'}price :currency}", "bad-option")
        assertLocaleErrorCode("malformed locale", "bad locale ???", "bad-option")
        assertLocaleErrorCode("private-use-only locale", "x-private", "bad-option")
        assertLocaleErrorCode("oversized locale", "a".repeat(257), "bad-option")
        assertLocaleFormats("private-use extension locale", "en-x-private")
        assertDateOperandErrorCode(
            "bracketed zone datetime",
            "datetime={${'$'}instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2026-05-21T14:30:15+02:00[Europe/Paris]",
            "bad-operand",
        )
        assertDateOperandErrorCode(
            "bare-hour offset datetime",
            "datetime={${'$'}instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2026-05-21T14:30:15+01",
            "bad-operand",
        )
        assertDateOperandErrorCode(
            "seconds offset datetime",
            "datetime={${'$'}instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "2026-05-21T14:30:15+01:02:03",
            "bad-operand",
        )
        if (!quiet) println("Kotlin JDK registry demo passed")
    }

    private fun parse(source: String): Mf2Model {
        val result = Mf2Parser.parseToModel(source)
        if (result.hasDiagnostics || result.model == null) {
            throw Mf2Error("parse-error", result.diagnostics.toString())
        }
        return result.model
    }

    private fun assertErrorCode(
        label: String,
        source: String,
        expectedCode: String,
    ) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            arguments = mapOf("price" to PRICE),
            locale = "en-US",
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (result.errors.size != 1 || result.errors[0].code != expectedCode) {
            error("$label expected $expectedCode, got ${result.errors}")
        }
    }

    private fun assertLocaleErrorCode(
        label: String,
        locale: String,
        expectedCode: String,
    ) {
        val result = Mf2Formatter.formatMessage(
            model = parse("{${'$'}amount :number}"),
            arguments = mapOf("amount" to AMOUNT),
            locale = locale,
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (result.errors.size != 1 || result.errors[0].code != expectedCode) {
            error("$label expected $expectedCode, got ${result.errors}")
        }
    }

    private fun assertLocaleFormats(label: String, locale: String) {
        val result = Mf2Formatter.formatMessage(
            model = parse("{${'$'}amount :number}"),
            arguments = mapOf("amount" to AMOUNT),
            locale = locale,
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (result.hasErrors) {
            error("$label returned errors: ${result.errors}")
        }
    }

    private fun assertDateOperandErrorCode(
        label: String,
        source: String,
        instant: String,
        expectedCode: String,
    ) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            arguments = mapOf("instant" to instant),
            locale = "en-US",
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (result.errors.size != 1 || result.errors[0].code != expectedCode) {
            error("$label expected $expectedCode, got ${result.errors}")
        }
    }

    private fun expected(localeTag: String): String {
        val locale = Locale.forLanguageTag(localeTag)
        return listOf(
            "number=${number(locale)}",
            "percent=${percent(locale)}",
            "currency=${currency(locale)}",
            "date=${date(locale)}",
            "time=${time(locale)}",
            "datetime=${dateTime(locale)}",
        ).joinToString("; ")
    }

    private fun number(locale: Locale): String =
        NumberFormat.getNumberInstance(locale).also {
            it.isGroupingUsed = false
            it.minimumFractionDigits = 2
        }.format(AMOUNT)

    private fun percent(locale: Locale): String =
        NumberFormat.getPercentInstance(locale).also {
            it.isGroupingUsed = false
            it.minimumFractionDigits = 1
            it.maximumFractionDigits = 1
        }.format(RATE)

    private fun currency(locale: Locale): String =
        NumberFormat.getCurrencyInstance(locale).also {
            it.currency = Currency.getInstance("EUR")
        }.format(PRICE)

    private fun date(locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
            .withLocale(locale)
            .format(due)

    private fun time(locale: Locale): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(start)

    private fun dateTime(locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(created)
}
