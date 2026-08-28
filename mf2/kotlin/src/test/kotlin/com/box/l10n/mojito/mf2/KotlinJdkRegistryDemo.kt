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
        assertNumericSelection()
        assertAdapterBoundaries()
        assertResolvedNumericSemantics(
            Mf2FunctionRegistry.defaults(),
            "JDK",
            "other 1,29; integer 1",
        )
        assertResolvedNumericSemantics(
            Mf2FunctionRegistry.portable(),
            "portable",
            "other 1.29; integer 1",
        )
        assertResolvedOptionPropagation(Mf2FunctionRegistry.defaults(), "JDK")
        assertResolvedOptionPropagation(Mf2FunctionRegistry.portable(), "portable")
        assertCurrencyReannotation()
        if (!quiet) println("Kotlin JDK registry demo passed")
    }

    private fun assertAdapterBoundaries() {
        val expected = NumberFormat.getNumberInstance(Locale.ENGLISH).also {
            it.isGroupingUsed = false
            it.maximumFractionDigits = 1
        }.format(1.29)
        val result = Mf2Formatter.formatMessage(
            model = parse("{${'$'}value :number maximumFractionDigits=1}"),
            arguments = mapOf("value" to 1.29),
            locale = "en",
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (expected != "1.3" || result.hasErrors || result.value != expected) {
            error("maximumFractionDigits expected $expected, returned $result")
        }

        val french = Locale.FRENCH
        val reannotatedExpected = NumberFormat.getNumberInstance(french).also {
            it.isGroupingUsed = false
            it.maximumFractionDigits = 0
        }.format(1000000.6)
        val reannotated = Mf2Formatter.formatMessage(
            model = parse(
                ".local ${'$'}n = {1000000.6 :number}\n" +
                    "{{Value {${'$'}n :number maximumFractionDigits=0}}}",
            ),
            locale = french.toLanguageTag(),
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (reannotated.hasErrors || reannotated.value != "Value $reannotatedExpected") {
            error("localized numeric declaration reannotation returned $reannotated")
        }

        val inheritedOptions = Mf2Formatter.formatMessage(
            model = parse(
                ".local ${'$'}n = {1.2 :number minimumFractionDigits=2} " +
                    "{{Value {${'$'}n :number}}}",
            ),
            locale = "en",
            functions = Mf2FunctionRegistry.defaults(),
        )
        if (inheritedOptions.hasErrors || inheritedOptions.value != "Value 1.20") {
            error("numeric option inheritance returned $inheritedOptions")
        }
    }

    private fun assertResolvedNumericSemantics(
        functions: Mf2FunctionRegistry,
        label: String,
        provenanceExpected: String,
    ) {
        assertFormatted(
            label = "$label rounded number source",
            source = ".local ${'$'}n = {1.29 :number maximumFractionDigits=1} " +
                "{{Value {${'$'}n :number maximumFractionDigits=2}}}",
            expected = "Value 1.29",
            functions = functions,
        )
        assertFormatted(
            label = "$label integer source",
            source = ".local ${'$'}x = {1.25 :integer}\n" +
                ".local ${'$'}y = {${'$'}x :number}\n" +
                "{{{${'$'}y}}}",
            expected = "1",
            functions = functions,
        )
        assertFormatted(
            label = "$label offset source",
            source = ".local ${'$'}step = {1 :integer}\n" +
                ".local ${'$'}x = {3 :offset subtract=${'$'}step}\n" +
                ".local ${'$'}y = {${'$'}x :number}\n" +
                "{{{${'$'}y}}}",
            expected = "2",
            functions = functions,
        )
        assertFormatted(
            label = "$label fractional offset source",
            source = ".local ${'$'}n = {-1.9 :number maximumFractionDigits=0}\n" +
                ".local ${'$'}o = {${'$'}n :offset add=1}\n" +
                "{{{${'$'}o}; {${'$'}o :number maximumFractionDigits=1}}}",
            expected = "-0.9; -0.9",
            functions = functions,
        )
        assertFormatted(
            label = "$label source provenance",
            source = ".local ${'$'}n = {1.29 :number maximumFractionDigits=1}\n" +
                ".local ${'$'}m = {${'$'}n :number maximumFractionDigits=2}\n" +
                ".local ${'$'}i = {1.25 :integer}\n" +
                ".local ${'$'}copy = {${'$'}i :number}\n" +
                ".match ${'$'}m\n" +
                "few {{few {${'$'}m}}}\n" +
                "other {{other {${'$'}m}; integer {${'$'}copy}}}\n" +
                "* {{fallback {${'$'}m}}}",
            expected = provenanceExpected,
            functions = functions,
            locale = "sr",
        )
    }

    private fun assertResolvedOptionPropagation(functions: Mf2FunctionRegistry, label: String) {
        assertFormatted(
            label = "$label integer fraction-option barrier",
            source = ".local ${'$'}base = {1.25 :number minimumFractionDigits=2 maximumFractionDigits=2}\n" +
                ".local ${'$'}integer = {${'$'}base :integer}\n" +
                "{{{${'$'}integer :number}}}",
            expected = "1",
            functions = functions,
        )
        assertFormatted(
            label = "$label percent select barrier",
            source = ".local ${'$'}base = {1 :number select=exact}\n" +
                ".local ${'$'}percent = {${'$'}base :percent}\n" +
                ".local ${'$'}copy = {${'$'}percent :number}\n" +
                "{{{${'$'}copy}}}",
            expected = "1",
            functions = functions,
        )
        assertFormatted(
            label = "$label offset carries numeric options",
            source = ".local ${'$'}base = {1.29 :number maximumFractionDigits=1}\n" +
                ".local ${'$'}offset = {${'$'}base :offset add=1}\n" +
                "{{{${'$'}offset :number}}}",
            expected = "2.3",
            functions = functions,
        )
        assertErrorCode(
            label = "$label offset delta does not propagate",
            source = ".local ${'$'}offset = {1 :offset add=1}\n{{{${'$'}offset :offset}}}",
            expectedCode = "bad-option",
            functions = functions,
        )

        val probeFunctions = functions.withFunction("number") { call ->
            val probe = call.optionValue("probe", null)
            if (probe == null) call.value else call.optionValue(probe, "missing") ?: "missing"
        }
        assertFormatted(
            label = "$label integer significant-option barrier",
            source = ".local ${'$'}base = {1.25 :number minimumSignificantDigits=3}\n" +
                ".local ${'$'}integer = {${'$'}base :integer}\n" +
                "{{{${'$'}integer :number probe=minimumSignificantDigits}}}",
            expected = "missing",
            functions = probeFunctions,
        )
        for (option in listOf("minimumIntegerDigits", "roundingIncrement")) {
            assertFormatted(
                label = "$label percent $option barrier",
                source = ".local ${'$'}base = {1 :number $option=3}\n" +
                    ".local ${'$'}percent = {${'$'}base :percent}\n" +
                    "{{{${'$'}percent :number probe=$option}}}",
                expected = "missing",
                functions = probeFunctions,
            )
        }
        for (function in listOf("percent", "offset add=1")) {
            assertFormatted(
                label = "$label $function carries remaining options",
                source = ".local ${'$'}base = {1 :number minimumSignificantDigits=3}\n" +
                    ".local ${'$'}derived = {${'$'}base :$function}\n" +
                    "{{{${'$'}derived :number probe=minimumSignificantDigits}}}",
                expected = "3",
                functions = probeFunctions,
            )
        }
    }

    private fun assertCurrencyReannotation() {
        val expected = NumberFormat.getCurrencyInstance(Locale.US).also {
            it.currency = Currency.getInstance("EUR")
        }
        assertFormatted(
            label = "JDK inherited currency",
            source = ".local ${'$'}price = {42 :currency currency=EUR}\n{{{${'$'}price :currency}}}",
            expected = expected.format(42),
            functions = Mf2FunctionRegistry.defaults(),
        )
        assertFormatted(
            label = "JDK replacement currency after number",
            source = ".local ${'$'}price = {42 :currency currency=USD}\n" +
                ".local ${'$'}plain = {${'$'}price :number}\n" +
                "{{{${'$'}plain :currency currency=EUR}}}",
            expected = expected.format(42),
            functions = Mf2FunctionRegistry.defaults(),
        )
        for (currency in listOf("EUR", "USD")) {
            assertErrorCode(
                label = "JDK currency override $currency",
                source = ".local ${'$'}price = {42 :currency currency=EUR}\n" +
                    "{{{${'$'}price :currency currency=$currency}}}",
                expectedCode = "bad-option",
                functions = Mf2FunctionRegistry.defaults(),
            )
        }
        assertErrorCode(
            label = "JDK missing currency",
            source = "{42 :currency}",
            expectedCode = "bad-operand",
            functions = Mf2FunctionRegistry.defaults(),
        )
        assertErrorCode(
            label = "JDK numeric source missing currency",
            source = ".local ${'$'}amount = {42 :number}\n{{{${'$'}amount :currency}}}",
            expectedCode = "bad-operand",
            functions = Mf2FunctionRegistry.defaults(),
        )
    }

    private fun assertFormatted(
        label: String,
        source: String,
        expected: String,
        functions: Mf2FunctionRegistry,
        locale: String = "en-US",
    ) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            locale = locale,
            functions = functions,
        )
        if (result.hasErrors || result.value != expected) {
            error("$label expected \"$expected\", got $result")
        }
    }

    private fun assertErrorCode(
        label: String,
        source: String,
        expectedCode: String,
        functions: Mf2FunctionRegistry,
    ) {
        val result = Mf2Formatter.formatMessage(
            model = parse(source),
            locale = "en-US",
            functions = functions,
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
                functions = Mf2FunctionRegistry.defaults(),
            )
            if (result.hasErrors || result.value != case.expected) {
                error("$case returned $result")
            }
        }
        val offset = Mf2Formatter.formatMessage(
            model = parse(offsetSelectionSource()),
            arguments = mapOf("value" to "1000001"),
            locale = "fr",
            functions = Mf2FunctionRegistry.defaults(),
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
        if (result.hasDiagnostics || result.model == null) {
            throw Mf2Error("parse-error", result.diagnostics.toString())
        }
        return result.model
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
