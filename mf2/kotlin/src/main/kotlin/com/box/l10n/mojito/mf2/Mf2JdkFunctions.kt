package com.box.l10n.mojito.mf2

import java.text.NumberFormat
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
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

internal object Mf2JdkFunctions {
    private const val MAX_LOCALE_LENGTH = 256

    fun registerFormatters(formatters: MutableMap<String, Mf2FunctionFormatter>) {
        formatters["number"] = ::formatNumber
        formatters["percent"] = ::formatPercent
        formatters["integer"] = ::formatInteger
        formatters["currency"] = ::formatCurrency
        formatters["datetime"] = ::formatDateTime
        formatters["date"] = ::formatDate
        formatters["time"] = ::formatTime
    }

    private fun formatNumber(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Number function requires a numeric operand.")
        val format = NumberFormat.getNumberInstance(locale(call.locale))
        format.isGroupingUsed = false
        minimumFractionDigits(call)?.let { format.minimumFractionDigits = it }
        return applySignDisplay(format.format(value), value, call)
    }

    private fun formatPercent(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Percent function requires a numeric operand.")
        val format = NumberFormat.getPercentInstance(locale(call.locale))
        format.isGroupingUsed = false
        minimumFractionDigits(call)?.let { format.minimumFractionDigits = it }
        maximumFractionDigits(call)?.let { format.maximumFractionDigits = it }
        return applySignDisplay(format.format(value), value, call)
    }

    private fun formatInteger(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Integer function requires a numeric operand.")
        val format = NumberFormat.getIntegerInstance(locale(call.locale))
        format.isGroupingUsed = false
        return applySignDisplay(format.format(value.toLong()), value, call)
    }

    private fun formatCurrency(call: Mf2FunctionCall): String {
        val value = Mf2PortableFunctions.parseCallDecimal(call, "Currency function requires a numeric operand.")
        val currency = currencyCode(call) ?: throw Mf2Error.badOption("Currency function requires a currency option.")
        val format = NumberFormat.getCurrencyInstance(locale(call.locale))
        try {
            format.currency = Currency.getInstance(currency)
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
        val zone = timeZone(call)
        val date = dateFrom(call.rawValue, call.value, zone)
            ?: parseSourceLocalDate(call.inheritedSource, zone)
            ?: throw Mf2Error.badOperand("Date function requires a date or datetime operand.")
        return DateTimeFormatter.ofLocalizedDate(dateStyle(dateStyleOption(call)))
            .withLocale(locale(call.locale))
            .format(date)
    }

    private fun formatTime(call: Mf2FunctionCall): String {
        val zone = timeZone(call)
        val time = timeFrom(call.rawValue, call.value, zone)
            ?: parseSourceLocalTime(call.inheritedSource, zone)
            ?: throw Mf2Error.badOperand("Datetime and time functions require a datetime operand.")
        return DateTimeFormatter.ofLocalizedTime(timeStyle(timeStyleOption(call)))
            .withLocale(locale(call.locale))
            .format(time)
    }

    private fun formatDateTime(call: Mf2FunctionCall): String {
        val zone = timeZone(call)
        val dateTime = zonedDateTimeFrom(call.rawValue, call.value, zone)
            ?: parseSourceZonedDateTime(call.inheritedSource, zone)
            ?: throw Mf2Error.badOperand("Datetime function requires a date or datetime operand.")
        val dateStyle = dateStyle(dateTimeDateStyleOption(call))
        val timeStyle = timeStyle(dateTimeTimeStyleOption(call))
        return DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle)
            .withLocale(locale(call.locale))
            .format(dateTime.withZoneSameInstant(zone))
    }

    private fun currencyCode(call: Mf2FunctionCall): String? =
        call.optionValue("currency", null) ?: inheritedCurrencyCode(call.inheritedSource)

    private fun inheritedCurrencyCode(source: Mf2FunctionSource?): String? {
        if (source == null) return null
        if (source.function["name"] == "currency") {
            sourceOptionValue(source, "currency", null)?.let { return it }
        }
        return inheritedCurrencyCode(source.inherited)
    }

    private fun currencyFractionDigits(call: Mf2FunctionCall): Int? {
        val value = call.optionValue("fractionDigits", null)
        if (value == null || value == "auto") return null
        return Mf2PortableFunctions.parseNonNegativeOption(value, "fractionDigits option must be auto or a non-negative integer.")
    }

    private fun minimumFractionDigits(call: Mf2FunctionCall): Int? =
        call.optionValue("minimumFractionDigits", null)
            ?.let { Mf2PortableFunctions.parseNonNegativeOption(it, "minimumFractionDigits option must be a non-negative integer.") }

    private fun maximumFractionDigits(call: Mf2FunctionCall): Int? =
        call.optionValue("maximumFractionDigits", null)
            ?.let { Mf2PortableFunctions.parseNonNegativeOption(it, "maximumFractionDigits option must be a non-negative integer.") }

    private fun applySignDisplay(formatted: String, value: Double, call: Mf2FunctionCall): String =
        if (value >= 0.0 && functionOptionLiteral(call.function, "signDisplay", null) == "always") "+$formatted" else formatted

    private fun locale(locale: String): Locale {
        if (locale.length > MAX_LOCALE_LENGTH) {
            throw Mf2Error.badOption("locale must not exceed 256 characters.")
        }
        val normalized = locale.replace('_', '-')
        if (!isWellFormedLocaleIdentifier(normalized)) {
            throw Mf2Error.badOption("Locale option must be a valid locale identifier.")
        }
        return Locale.forLanguageTag(normalized)
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

    private fun dateStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "dateStyle",
            call.optionValue("length", call.optionValue("style", "short")),
        ) ?: "short"

    private fun timeStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "timeStyle",
            call.optionValue("precision", call.optionValue("style", "short")),
        ) ?: "short"

    private fun dateTimeDateStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "dateStyle",
            call.optionValue("dateLength", call.optionValue("style", "short")),
        ) ?: "short"

    private fun dateTimeTimeStyleOption(call: Mf2FunctionCall): String =
        call.optionValue(
            "timeStyle",
            call.optionValue("timePrecision", call.optionValue("style", "short")),
        ) ?: "short"

    private fun timeZone(call: Mf2FunctionCall): ZoneId {
        val value = call.optionValue("timeZone", "UTC") ?: "UTC"
        return try {
            ZoneId.of(value)
        } catch (error: DateTimeException) {
            throw Mf2Error.badOption("timeZone option must be a valid time zone identifier.")
        }
    }

    private fun dateStyle(value: String): FormatStyle =
        when (value) {
            "full" -> FormatStyle.FULL
            "long" -> FormatStyle.LONG
            "medium" -> FormatStyle.MEDIUM
            "short" -> FormatStyle.SHORT
            else -> throw Mf2Error.badOption("Date style option must be full, long, medium, or short.")
        }

    private fun timeStyle(value: String): FormatStyle =
        when (value) {
            "full", "long", "medium", "short", "second" -> FormatStyle.MEDIUM
            else -> throw Mf2Error.badOption("Time style option must be full, long, medium, short, or second.")
        }

    private fun dateFrom(rawValue: Any?, renderedValue: String, zone: ZoneId): LocalDate? =
        when (rawValue) {
            is LocalDate -> rawValue
            is LocalDateTime -> rawValue.toLocalDate()
            is OffsetDateTime -> rawValue.atZoneSameInstant(zone).toLocalDate()
            is ZonedDateTime -> rawValue.withZoneSameInstant(zone).toLocalDate()
            is Instant -> rawValue.atZone(zone).toLocalDate()
            is java.util.Date -> rawValue.toInstant().atZone(zone).toLocalDate()
            else -> parseLocalDate(renderedValue)
                ?: parseLocalDateTime(renderedValue)?.toLocalDate()
                ?: parseZonedDateTime(renderedValue)?.withZoneSameInstant(zone)?.toLocalDate()
        }

    private fun timeFrom(rawValue: Any?, renderedValue: String, zone: ZoneId): LocalTime? =
        when (rawValue) {
            is LocalTime -> rawValue
            is LocalDateTime -> rawValue.toLocalTime()
            is OffsetDateTime -> rawValue.atZoneSameInstant(zone).toLocalTime()
            is ZonedDateTime -> rawValue.withZoneSameInstant(zone).toLocalTime()
            is Instant -> rawValue.atZone(zone).toLocalTime()
            is java.util.Date -> rawValue.toInstant().atZone(zone).toLocalTime()
            else -> parseLocalTime(renderedValue)
                ?: parseLocalDateTime(renderedValue)?.toLocalTime()
                ?: parseZonedDateTime(renderedValue)?.withZoneSameInstant(zone)?.toLocalTime()
        }

    private fun zonedDateTimeFrom(rawValue: Any?, renderedValue: String, zone: ZoneId): ZonedDateTime? =
        when (rawValue) {
            is ZonedDateTime -> rawValue.withZoneSameInstant(zone)
            is OffsetDateTime -> rawValue.atZoneSameInstant(zone)
            is Instant -> rawValue.atZone(zone)
            is java.util.Date -> rawValue.toInstant().atZone(zone)
            is LocalDateTime -> rawValue.atZone(zone)
            is LocalDate -> rawValue.atStartOfDay(zone)
            else -> parseZonedDateTime(renderedValue)?.withZoneSameInstant(zone)
                ?: parseLocalDateTime(renderedValue)?.atZone(zone)
                ?: parseLocalDate(renderedValue)?.atStartOfDay(zone)
        }

    private fun parseSourceLocalDate(source: Mf2FunctionSource?, zone: ZoneId): LocalDate? {
        if (source == null) return null
        return dateFrom(source.value, source.value, zone) ?: parseSourceLocalDate(source.inherited, zone)
    }

    private fun parseSourceLocalTime(source: Mf2FunctionSource?, zone: ZoneId): LocalTime? {
        if (source == null) return null
        return timeFrom(source.value, source.value, zone) ?: parseSourceLocalTime(source.inherited, zone)
    }

    private fun parseSourceZonedDateTime(source: Mf2FunctionSource?, zone: ZoneId): ZonedDateTime? {
        if (source == null) return null
        return zonedDateTimeFrom(source.value, source.value, zone) ?: parseSourceZonedDateTime(source.inherited, zone)
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

    private fun parseLocalDateTime(value: String): LocalDateTime? =
        try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (error: DateTimeParseException) {
            null
        }

    private fun parseZonedDateTime(value: String): ZonedDateTime? {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime()
        } catch (error: DateTimeParseException) {
        }
        return try {
            Instant.parse(value).atZone(ZoneOffset.UTC)
        } catch (error: DateTimeParseException) {
            null
        }
    }
}
