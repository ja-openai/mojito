package com.box.l10n.mojito.mf2.icu4j

import com.box.l10n.mojito.mf2.Mf2Error
import com.box.l10n.mojito.mf2.Mf2FunctionCall
import com.box.l10n.mojito.mf2.Mf2FunctionRegistry
import com.box.l10n.mojito.mf2.Mf2FunctionSource
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DisplayContext
import com.ibm.icu.text.NumberFormat
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.util.Currency
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

object Mf2Icu4jFunctions {
    private const val MAX_FRACTION_DIGITS = 100
    private const val MAX_LOCALE_LENGTH = 256
    private val epochDate: LocalDate = LocalDate.of(1970, 1, 1)

    @JvmStatic
    fun registry(): Mf2FunctionRegistry =
        Mf2FunctionRegistry.portable()
            .withFunction("number", ::formatNumber)
            .withFunction("percent", ::formatPercent)
            .withFunction("integer", ::formatInteger)
            .withFunction("currency", ::formatCurrency)
            .withFunction("date", ::formatDate)
            .withFunction("time", ::formatTime)
            .withFunction("datetime", ::formatDateTime)
            .withFunction("relativeTime", ::formatRelativeTime)

    private fun formatNumber(call: Mf2FunctionCall): String {
        val value = numericValue(call, "Number function requires a numeric operand.")
        val format = NumberFormat.getNumberInstance(locale(call))
        minimumFractionDigits(call)?.let { format.minimumFractionDigits = it }
        maximumFractionDigits(call)?.let { format.maximumFractionDigits = it }
        return applySignDisplay(format.format(value), value, call)
    }

    private fun formatPercent(call: Mf2FunctionCall): String {
        val value = numericValue(call, "Percent function requires a numeric operand.")
        val format = NumberFormat.getPercentInstance(locale(call))
        minimumFractionDigits(call)?.let { format.minimumFractionDigits = it }
        maximumFractionDigits(call)?.let { format.maximumFractionDigits = it }
        return applySignDisplay(format.format(value), value, call)
    }

    private fun formatInteger(call: Mf2FunctionCall): String {
        val value = numericValue(call, "Integer function requires a numeric operand.")
        val format = NumberFormat.getIntegerInstance(locale(call))
        return applySignDisplay(format.format(value.toLong()), value, call)
    }

    private fun formatCurrency(call: Mf2FunctionCall): String {
        val value = numericValue(call, "Currency function requires a numeric operand.")
        val currencyCode = currencyCode(call)
            ?: throw Mf2Error.badOption("Currency function requires a currency option.")
        val format = NumberFormat.getCurrencyInstance(locale(call))
        try {
            format.currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT))
        } catch (error: IllegalArgumentException) {
            throw Mf2Error.badOption("Currency option must be an ISO 4217 currency code.")
        }
        currencyFractionDigits(call)?.let {
            format.minimumFractionDigits = it
            format.maximumFractionDigits = it
        }
        return format.format(value)
    }

    private fun formatDate(call: Mf2FunctionCall): String {
        val zone = zoneId(call)
        val format = DateFormat.getDateInstance(dateStyle(dateStyleOption(call)), locale(call))
        format.timeZone = timeZone(zone)
        return format.format(dateValue(call, zone))
    }

    private fun formatTime(call: Mf2FunctionCall): String {
        val zone = zoneId(call)
        val format = DateFormat.getTimeInstance(timeStyle(timeStyleOption(call)), locale(call))
        format.timeZone = timeZone(zone)
        return format.format(timeValue(call, zone))
    }

    private fun formatDateTime(call: Mf2FunctionCall): String {
        val zone = zoneId(call)
        val sharedStyle = call.optionValue("style", null)
        val defaultStyle = sharedStyle ?: "medium"
        val dateStyleOption = call.optionValue(
            "dateStyle",
            call.optionValue("dateLength", defaultStyle),
        ) ?: defaultStyle
        val timeStyleOption = call.optionValue(
            "timeStyle",
            call.optionValue("timePrecision", defaultStyle),
        ) ?: defaultStyle
        val format = DateFormat.getDateTimeInstance(
            dateStyle(dateStyleOption),
            timeStyle(timeStyleOption),
            locale(call),
        )
        format.timeZone = timeZone(zone)
        return format.format(dateTimeValue(call, zone))
    }

    private fun formatRelativeTime(call: Mf2FunctionCall): String {
        val value = numericValue(call, "Relative time function requires a numeric operand.")
        val numeric = optionOneOf(call, "numeric", "always", "always", "auto")
        val formatter = RelativeDateTimeFormatter.getInstance(
            locale(call),
            null,
            relativeTimeStyle(call),
            DisplayContext.CAPITALIZATION_NONE,
        )
        return if (numeric == "auto") {
            formatter.format(value, relativeTimeUnit(call))
        } else {
            formatter.formatNumeric(value, relativeTimeUnit(call))
        }
    }

    private fun locale(call: Mf2FunctionCall): ULocale {
        val locale = call.locale
        if (locale.length > MAX_LOCALE_LENGTH) {
            throw Mf2Error.badOption("locale must not exceed 256 characters.")
        }
        val normalized = locale.replace('_', '-')
        if (!isWellFormedLocaleIdentifier(normalized)) {
            throw Mf2Error.badOption("Locale option must be a valid locale identifier.")
        }
        return ULocale.forLanguageTag(normalized)
    }

    private fun isWellFormedLocaleIdentifier(locale: String): Boolean {
        if (locale.isEmpty()) return false
        val subtags = localeSubtags(locale)
        val language = subtags.first()
        if (language.length !in 2..8 || !language.all(::isAsciiLetter)) return false
        var index = 1
        while (index < subtags.size) {
            val subtag = subtags[index]
            if (subtag.length == 1) {
                if (!subtag.all(::isAsciiAlphanumeric)) return false
                val isPrivateUse = subtag.equals("x", ignoreCase = true)
                index++
                val extensionStart = index
                while (index < subtags.size &&
                    subtags[index].length in (if (isPrivateUse) 1..8 else 2..8) &&
                    subtags[index].all(::isAsciiAlphanumeric)
                ) {
                    index++
                }
                if (index == extensionStart) return false
                if (isPrivateUse) return index == subtags.size
                continue
            }
            if (subtag.length !in 2..8 || !subtag.all(::isAsciiAlphanumeric)) return false
            index++
        }
        return true
    }

    private fun localeSubtags(locale: String): List<String> {
        val output = mutableListOf<String>()
        var start = 0
        for (index in locale.indices) {
            if (locale[index] == '-') {
                output += locale.substring(start, index)
                start = index + 1
            }
        }
        output += locale.substring(start)
        return output
    }

    private fun isAsciiLetter(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z'

    private fun isAsciiAlphanumeric(ch: Char): Boolean =
        isAsciiLetter(ch) || ch in '0'..'9'

    private fun numericValue(call: Mf2FunctionCall, message: String): Double {
        val value = when (val raw = call.rawValue) {
            is Number -> raw.toDouble()
            else -> call.value.toDoubleOrNull() ?: throw Mf2Error.badOperand(message)
        }
        if (!value.isFinite()) throw Mf2Error.badOperand(message)
        return value
    }

    private fun dateValue(call: Mf2FunctionCall, zone: ZoneId): Date {
        when (val raw = call.rawValue) {
            is LocalDate -> return Date.from(raw.atStartOfDay(zone).toInstant())
            is LocalDateTime -> return Date.from(
                raw.toLocalDate().atStartOfDay(zone).toInstant(),
            )
            is OffsetDateTime -> return Date.from(
                raw.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant(),
            )
            is ZonedDateTime -> return Date.from(
                raw.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant(),
            )
            is Instant -> {
                val date = raw.atZone(zone).toLocalDate()
                return Date.from(date.atStartOfDay(zone).toInstant())
            }
            is Date -> return raw
        }
        parseLocalDate(call.value)?.let {
            return Date.from(it.atStartOfDay(zone).toInstant())
        }
        parseZonedDateTime(call.value)?.let {
            return Date.from(it.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant())
        }
        throw Mf2Error.badOperand("Date function requires a date or datetime operand.")
    }

    private fun timeValue(call: Mf2FunctionCall, zone: ZoneId): Date {
        when (val raw = call.rawValue) {
            is LocalTime -> return Date.from(
                raw.atDate(epochDate).atZone(zone).toInstant(),
            )
            is LocalDateTime -> return Date.from(raw.atZone(zone).toInstant())
            is OffsetDateTime -> return Date.from(raw.toInstant())
            is ZonedDateTime -> return Date.from(raw.toInstant())
            is Instant -> return Date.from(raw)
            is Date -> return raw
        }
        parseLocalTime(call.value)?.let {
            return Date.from(it.atDate(epochDate).atZone(zone).toInstant())
        }
        parseZonedDateTime(call.value)?.let {
            return Date.from(it.toInstant())
        }
        throw Mf2Error.badOperand("Time function requires a time or datetime operand.")
    }

    private fun dateTimeValue(call: Mf2FunctionCall, zone: ZoneId): Date {
        when (val raw = call.rawValue) {
            is LocalDate -> return Date.from(raw.atStartOfDay(zone).toInstant())
            is LocalDateTime -> return Date.from(raw.atZone(zone).toInstant())
            is OffsetDateTime -> return Date.from(raw.toInstant())
            is ZonedDateTime -> return Date.from(raw.toInstant())
            is Instant -> return Date.from(raw)
            is Date -> return raw
        }
        parseZonedDateTime(call.value)?.let {
            return Date.from(it.toInstant())
        }
        parseLocalDate(call.value)?.let {
            return Date.from(it.atStartOfDay(zone).toInstant())
        }
        throw Mf2Error.badOperand("Datetime function requires a date or datetime operand.")
    }

    private fun parseLocalDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (error: DateTimeParseException) {
            null
        }

    private fun parseLocalTime(value: String): LocalTime? =
        try {
            LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME)
        } catch (error: DateTimeParseException) {
            null
        }

    private fun parseZonedDateTime(value: String): ZonedDateTime? {
        try {
            return ZonedDateTime.parse(value)
        } catch (error: DateTimeParseException) {
        }
        try {
            return OffsetDateTime.parse(
                value,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            ).toZonedDateTime()
        } catch (error: DateTimeParseException) {
        }
        try {
            return LocalDateTime.parse(
                value,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            ).atZone(ZoneOffset.UTC)
        } catch (error: DateTimeParseException) {
        }
        return try {
            Instant.parse(value).atZone(ZoneOffset.UTC)
        } catch (error: DateTimeParseException) {
            null
        }
    }

    private fun dateStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "dateStyle",
            call.optionValue("length", call.optionValue("style", "medium")),
        ) ?: "medium"

    private fun timeStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "timeStyle",
            call.optionValue("precision", call.optionValue("style", "medium")),
        ) ?: "medium"

    private fun zoneId(call: Mf2FunctionCall): ZoneId {
        val value = call.optionValue("timeZone", "UTC") ?: "UTC"
        return try {
            ZoneId.of(value)
        } catch (error: DateTimeException) {
            throw Mf2Error.badOption("timeZone option must be a valid time zone identifier.")
        }
    }

    private fun timeZone(zone: ZoneId): TimeZone = TimeZone.getTimeZone(zone.id)

    private fun dateStyle(value: String): Int =
        when (value) {
            "full" -> DateFormat.FULL
            "long" -> DateFormat.LONG
            "medium" -> DateFormat.MEDIUM
            "short" -> DateFormat.SHORT
            else -> throw Mf2Error.badOption(
                "Date style option must be full, long, medium, or short.",
            )
        }

    private fun timeStyle(value: String): Int =
        when (value) {
            "full" -> DateFormat.FULL
            "long" -> DateFormat.LONG
            "medium", "second" -> DateFormat.MEDIUM
            "short" -> DateFormat.SHORT
            else -> throw Mf2Error.badOption(
                "Time style option must be full, long, medium, short, or second.",
            )
        }

    private fun relativeTimeStyle(call: Mf2FunctionCall): RelativeDateTimeFormatter.Style =
        when (optionOneOf(call, "style", "long", "long", "short", "narrow")) {
            "long" -> RelativeDateTimeFormatter.Style.LONG
            "short" -> RelativeDateTimeFormatter.Style.SHORT
            "narrow" -> RelativeDateTimeFormatter.Style.NARROW
            else -> error("validated option is exhaustive")
        }

    private fun relativeTimeUnit(
        call: Mf2FunctionCall,
    ): RelativeDateTimeFormatter.RelativeDateTimeUnit =
        when (
            optionOneOf(
                call,
                "unit",
                null,
                "second",
                "minute",
                "hour",
                "day",
                "week",
                "month",
                "quarter",
                "year",
            )
        ) {
            "second" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
            "minute" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
            "hour" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
            "day" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
            "week" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK
            "month" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH
            "quarter" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.QUARTER
            "year" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
            else -> error("validated option is exhaustive")
        }

    private fun optionOneOf(
        call: Mf2FunctionCall,
        name: String,
        fallback: String?,
        vararg allowedValues: String,
    ): String {
        val value = call.optionValue(name, fallback)
            ?: throw Mf2Error.badOption("$name option is required.")
        if (value in allowedValues) return value
        throw Mf2Error.badOption("$name option must be one of ${allowedValues.joinToString(", ")}.")
    }

    private fun minimumFractionDigits(call: Mf2FunctionCall): Int? =
        nonNegativeOption(call, "minimumFractionDigits")

    private fun maximumFractionDigits(call: Mf2FunctionCall): Int? =
        nonNegativeOption(call, "maximumFractionDigits")

    private fun currencyFractionDigits(call: Mf2FunctionCall): Int? {
        val value = call.optionValue("fractionDigits", null)
        if (value == null || value == "auto") return null
        return parseNonNegativeInteger(
            value,
            "fractionDigits option must be auto or a non-negative integer.",
        )
    }

    private fun nonNegativeOption(call: Mf2FunctionCall, name: String): Int? {
        val value = call.optionValue(name, null) ?: return null
        return parseNonNegativeInteger(value, "$name option must be a non-negative integer.")
    }

    private fun parseNonNegativeInteger(value: String, message: String): Int =
        value.toIntOrNull()?.takeIf { it in 0..MAX_FRACTION_DIGITS }
            ?: throw Mf2Error.badOption(message)

    private fun currencyCode(call: Mf2FunctionCall): String? =
        call.optionValue("currency", null) ?: inheritedCurrencyCode(call.inheritedSource)

    private fun inheritedCurrencyCode(source: Mf2FunctionSource?): String? {
        if (source == null) return null
        if (source.function["name"] == "currency") {
            sourceOptionValue(source, "currency")?.let { return it }
        }
        return inheritedCurrencyCode(source.inherited)
    }

    private fun sourceOptionValue(source: Mf2FunctionSource, name: String): String? {
        val options = source.function["options"] as? Map<*, *> ?: return null
        val option = options[name] as? Map<*, *> ?: return null
        return if (option["type"] == "literal") option["value"] as? String else null
    }

    private fun applySignDisplay(formatted: String, value: Double, call: Mf2FunctionCall): String {
        val signDisplay = optionOneOf(call, "signDisplay", "auto", "auto", "always")
        return if (value >= 0.0 && signDisplay == "always") "+$formatted" else formatted
    }
}
