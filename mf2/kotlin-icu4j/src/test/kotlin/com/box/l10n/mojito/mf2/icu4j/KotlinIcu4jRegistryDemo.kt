package com.box.l10n.mojito.mf2.icu4j

import com.box.l10n.mojito.mf2.Mf2Error
import com.box.l10n.mojito.mf2.Mf2FormatResult
import com.box.l10n.mojito.mf2.Mf2Formatter
import com.box.l10n.mojito.mf2.Mf2Model
import com.box.l10n.mojito.mf2.Mf2Parser
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DisplayContext
import com.ibm.icu.text.NumberFormat
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.util.Currency
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date

object KotlinIcu4jRegistryDemo {
    private const val AMOUNT = 12345.678
    private const val RATE = 0.1234
    private const val PRICE = 9876.5
    private val due: LocalDate = LocalDate.of(2026, 5, 21)
    private val start: LocalTime = LocalTime.of(14, 30, 15)
    private val created: ZonedDateTime =
        ZonedDateTime.of(2026, 5, 21, 14, 30, 15, 0, ZoneOffset.UTC)
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val source = listOf(
        "number={${'$'}amount :number minimumFractionDigits=2}",
        "percent={${'$'}rate :percent minimumFractionDigits=1 maximumFractionDigits=1}",
        "currency={${'$'}price :currency currency=EUR}",
        "date={${'$'}due :date dateStyle=full timeZone=UTC}",
        "time={${'$'}start :time timeStyle=medium timeZone=UTC}",
        "datetime={${'$'}created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
        "relative={${'$'}days :relativeTime unit=day numeric=auto style=long}",
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
            "days" to 1,
        )
        for (locale in listOf("en-US", "fr-FR", "ja-JP", "ar-EG")) {
            val result = Mf2Formatter.formatMessage(
                model = message,
                arguments = arguments,
                locale = locale,
                functions = Mf2Icu4jFunctions.registry(),
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
        assertUnsupportedUnitFallsBack()
        assertOversizedFractionDigitsFallsBack()
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
        if (!quiet) println("Kotlin ICU4J registry demo passed")
    }

    private fun parse(source: String): Mf2Model {
        val result = Mf2Parser.parseToModel(source)
        val model = result.model
        if (result.hasDiagnostics || model == null) {
            throw Mf2Error("parse-error", result.diagnostics.toString())
        }
        return model
    }

    private fun expected(localeTag: String): String {
        val locale = ULocale.forLanguageTag(localeTag)
        return listOf(
            "number=${number(locale)}",
            "percent=${percent(locale)}",
            "currency=${currency(locale)}",
            "date=${date(locale)}",
            "time=${time(locale)}",
            "datetime=${dateTime(locale)}",
            "relative=${relative(locale)}",
        ).joinToString("; ")
    }

    private fun number(locale: ULocale): String =
        NumberFormat.getNumberInstance(locale).also {
            it.minimumFractionDigits = 2
        }.format(AMOUNT)

    private fun percent(locale: ULocale): String =
        NumberFormat.getPercentInstance(locale).also {
            it.minimumFractionDigits = 1
            it.maximumFractionDigits = 1
        }.format(RATE)

    private fun currency(locale: ULocale): String =
        NumberFormat.getCurrencyInstance(locale).also {
            it.currency = Currency.getInstance("EUR")
        }.format(PRICE)

    private fun date(locale: ULocale): String =
        DateFormat.getDateInstance(DateFormat.FULL, locale).also {
            it.timeZone = utc
        }.format(Date.from(due.atStartOfDay(ZoneOffset.UTC).toInstant()))

    private fun time(locale: ULocale): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).also {
            it.timeZone = utc
        }.format(Date.from(
            start.atDate(LocalDate.of(1970, 1, 1)).atZone(ZoneOffset.UTC).toInstant(),
        ))

    private fun dateTime(locale: ULocale): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale).also {
            it.timeZone = utc
        }.format(Date.from(created.toInstant()))

    private fun relative(locale: ULocale): String =
        RelativeDateTimeFormatter.getInstance(
            locale,
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_NONE,
        ).format(1.0, RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY)

    private fun assertUnsupportedUnitFallsBack() {
        val result: Mf2FormatResult = Mf2Formatter.formatMessage(
            model = parse("{${'$'}value :relativeTime unit=fortnight}"),
            arguments = mapOf("value" to 1),
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (!result.hasErrors || result.value != "{${'$'}value}") {
            error("unsupported relativeTime unit should recover with visible fallback")
        }
    }

    private fun assertOversizedFractionDigitsFallsBack() {
        val result: Mf2FormatResult = Mf2Formatter.formatMessage(
            model = parse("{${'$'}value :number minimumFractionDigits=10000}"),
            arguments = mapOf("value" to 1),
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (!result.hasErrors || result.value != "{${'$'}value}" || result.errors.first().code != "bad-option") {
            error("oversized fraction digits should recover with bad-option")
        }
    }

    private fun assertLocaleErrorCode(
        label: String,
        locale: String,
        expectedCode: String,
    ) {
        val result: Mf2FormatResult = Mf2Formatter.formatMessage(
            model = parse("{${'$'}amount :number}"),
            arguments = mapOf("amount" to AMOUNT),
            locale = locale,
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (result.errors.size != 1 || result.errors.first().code != expectedCode) {
            error("$label expected $expectedCode, got ${result.errors}")
        }
    }

    private fun assertLocaleFormats(label: String, locale: String) {
        val result: Mf2FormatResult = Mf2Formatter.formatMessage(
            model = parse("{${'$'}amount :number}"),
            arguments = mapOf("amount" to AMOUNT),
            locale = locale,
            functions = Mf2Icu4jFunctions.registry(),
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
        val result: Mf2FormatResult = Mf2Formatter.formatMessage(
            model = parse(source),
            arguments = mapOf("instant" to instant),
            locale = "en-US",
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (result.errors.size != 1 || result.errors.first().code != expectedCode) {
            error("$label expected $expectedCode, got ${result.errors}")
        }
    }
}
