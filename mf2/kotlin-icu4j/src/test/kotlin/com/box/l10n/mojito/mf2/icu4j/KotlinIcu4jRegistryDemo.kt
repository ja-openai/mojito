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
        assertNumericSelection()
        assertAdapterBoundaries()
        assertResolvedOptionPropagation()
        assertCurrencyReannotation()
        if (!quiet) println("Kotlin ICU4J registry demo passed")
    }

    private fun assertAdapterBoundaries() {
        val englishNumberFormat = NumberFormat.getNumberInstance(ULocale.ENGLISH).also {
            it.maximumFractionDigits = 1
        }
        val roundedExpected = englishNumberFormat.format(1.29)
        if (roundedExpected != "1.3") error("ICU4J maximumFractionDigits reference returned $roundedExpected")
        assertFormatted(
            label = "maximumFractionDigits",
            source = "{1.29 :number maximumFractionDigits=1}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = roundedExpected,
        )

        val locale = ULocale.FRENCH
        val numberFormat = NumberFormat.getNumberInstance(locale).also {
            it.maximumFractionDigits = 0
        }
        assertFormatted(
            label = "localized numeric declaration reannotation",
            source = ".local ${'$'}n = {1000000 :number}\n{{Value {${'$'}n :number maximumFractionDigits=0}}}",
            locale = locale.toLanguageTag(),
            expected = "Value ${numberFormat.format(1000000)}",
        )

        assertFormatted(
            label = "rounded numeric semantic source",
            source = ".local ${'$'}n = {1.29 :number maximumFractionDigits=1}\n" +
                "{{Value {${'$'}n :number maximumFractionDigits=2}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "Value 1.29",
        )
        assertFormatted(
            label = "integer semantic source",
            source = ".local ${'$'}x = {1.25 :integer}\n" +
                ".local ${'$'}y = {${'$'}x :number}\n" +
                "{{{${'$'}y}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "1",
        )
        assertFormatted(
            label = "offset semantic source",
            source = ".local ${'$'}step = {1 :integer}\n" +
                ".local ${'$'}x = {3 :offset subtract=${'$'}step}\n" +
                ".local ${'$'}y = {${'$'}x :number}\n" +
                "{{{${'$'}y}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "2",
        )
        assertFormatted(
            label = "fractional offset semantic source",
            source = ".local ${'$'}n = {-1.9 :number maximumFractionDigits=0}\n" +
                ".local ${'$'}o = {${'$'}n :offset add=1}\n" +
                "{{{${'$'}o}; {${'$'}o :number maximumFractionDigits=1}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "-0.9; -0.9",
        )
        val serbian = ULocale.forLanguageTag("sr")
        val serbianNumber = NumberFormat.getNumberInstance(serbian).also {
            it.maximumFractionDigits = 2
        }
        assertFormatted(
            label = "combined source provenance",
            source = ".local ${'$'}n = {1.29 :number maximumFractionDigits=1}\n" +
                ".local ${'$'}m = {${'$'}n :number maximumFractionDigits=2}\n" +
                ".local ${'$'}i = {1.25 :integer}\n" +
                ".local ${'$'}copy = {${'$'}i :number}\n" +
                ".match ${'$'}m\n" +
                "few {{few {${'$'}m}}}\n" +
                "other {{other {${'$'}m}; integer {${'$'}copy}}}\n" +
                "* {{fallback {${'$'}m}}}",
            locale = serbian.toLanguageTag(),
            expected = "other ${serbianNumber.format(1.29)}; integer ${serbianNumber.format(1)}",
        )

        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).also {
            it.timeZone = utc
        }
        val date = LocalDate.of(2006, 1, 2)
        assertFormatted(
            label = "datetime to date declaration reannotation",
            source = ".local ${'$'}dt = {|2006-01-02T15:04:06| :datetime " +
                "dateStyle=medium timeStyle=medium timeZone=UTC}\n" +
                "{{Date {${'$'}dt :date dateStyle=medium timeZone=UTC}}}",
            locale = locale.toLanguageTag(),
            expected = "Date ${dateFormat.format(Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()))}",
        )
    }

    private fun assertResolvedOptionPropagation() {
        assertFormatted(
            label = "ICU4J integer fraction-option barrier",
            source = ".local ${'$'}base = {1.25 :number minimumFractionDigits=2 maximumFractionDigits=2}\n" +
                ".local ${'$'}integer = {${'$'}base :integer}\n" +
                "{{{${'$'}integer :number}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "1",
        )
        assertFormatted(
            label = "ICU4J percent select barrier",
            source = ".local ${'$'}base = {1 :number select=exact}\n" +
                ".local ${'$'}percent = {${'$'}base :percent}\n" +
                ".local ${'$'}copy = {${'$'}percent :number}\n" +
                "{{{${'$'}copy}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = "1",
        )
        val expected = NumberFormat.getNumberInstance(ULocale.ENGLISH).also {
            it.maximumFractionDigits = 1
        }
        assertFormatted(
            label = "ICU4J offset carries numeric options",
            source = ".local ${'$'}base = {1.29 :number maximumFractionDigits=1}\n" +
                ".local ${'$'}offset = {${'$'}base :offset add=1}\n" +
                "{{{${'$'}offset :number}}}",
            locale = ULocale.ENGLISH.toLanguageTag(),
            expected = expected.format(2.29),
        )
        assertErrorCode(
            label = "ICU4J offset delta does not propagate",
            source = ".local ${'$'}offset = {1 :offset add=1}\n{{{${'$'}offset :offset}}}",
            expectedCode = "bad-option",
        )
    }

    private fun assertCurrencyReannotation() {
        val expected = NumberFormat.getCurrencyInstance(ULocale.US).also {
            it.currency = Currency.getInstance("EUR")
        }
        assertFormatted(
            label = "ICU4J inherited currency",
            source = ".local ${'$'}price = {42 :currency currency=EUR}\n{{{${'$'}price :currency}}}",
            locale = ULocale.US.toLanguageTag(),
            expected = expected.format(42),
        )
        assertFormatted(
            label = "ICU4J replacement currency after number",
            source = ".local ${'$'}price = {42 :currency currency=USD}\n" +
                ".local ${'$'}plain = {${'$'}price :number}\n" +
                "{{{${'$'}plain :currency currency=EUR}}}",
            locale = ULocale.US.toLanguageTag(),
            expected = expected.format(42),
        )
        for (currency in listOf("EUR", "USD")) {
            assertErrorCode(
                label = "ICU4J currency override $currency",
                source = ".local ${'$'}price = {42 :currency currency=EUR}\n" +
                    "{{{${'$'}price :currency currency=$currency}}}",
                expectedCode = "bad-option",
            )
        }
        assertErrorCode(
            label = "ICU4J missing currency",
            source = "{42 :currency}",
            expectedCode = "bad-operand",
        )
        assertErrorCode(
            label = "ICU4J numeric source missing currency",
            source = ".local ${'$'}amount = {42 :number}\n{{{${'$'}amount :currency}}}",
            expectedCode = "bad-operand",
        )
    }

    private fun assertFormatted(label: String, source: String, locale: String, expected: String) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            locale = locale,
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (result.hasErrors || result.value != expected) {
            error("$label expected \"$expected\", got $result")
        }
    }

    private fun assertErrorCode(label: String, source: String, expectedCode: String) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            locale = ULocale.ENGLISH.toLanguageTag(),
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (result.errors.none { it.code == expectedCode }) {
            error("$label expected error $expectedCode, got $result")
        }
    }

    private fun assertNumericSelection() {
        val cases = listOf(
            SelectionCase(":number", "fr", "1000000", "many"),
            SelectionCase(":number minimumFractionDigits=1", "ru", 1, "other"),
            SelectionCase(":integer", "fr", "1000000.9", "many"),
            SelectionCase(":percent", "fr", "10000", "many"),
        )
        for (case in cases) {
            val result = Mf2Formatter.formatMessage(
                model = parse(selectionSource(case.function)),
                arguments = mapOf("value" to case.value),
                locale = case.locale,
                functions = Mf2Icu4jFunctions.registry(),
            )
            if (result.hasErrors || result.value != case.expected) {
                error("$case returned $result")
            }
        }
        val offset = Mf2Formatter.formatMessage(
            model = parse(offsetSelectionSource()),
            arguments = mapOf("value" to "1000001"),
            locale = "fr",
            functions = Mf2Icu4jFunctions.registry(),
        )
        if (offset.hasErrors || offset.value != "many") {
            error("offset selection returned $offset")
        }
    }

    private fun selectionSource(function: String): String =
        ".input {${'$'}value $function}\n.match ${'$'}value\n" +
            "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n" +
            "many {{many}}\nother {{other}}\n* {{other}}"

    private fun offsetSelectionSource(): String =
        ".input {${'$'}value :integer}\n.local ${'$'}adjusted = {${'$'}value :offset subtract=1}\n" +
            ".match ${'$'}adjusted\n" +
            "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n" +
            "many {{many}}\nother {{other}}\n* {{other}}"

    private data class SelectionCase(
        val function: String,
        val locale: String,
        val value: Any,
        val expected: String,
    )

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
}
