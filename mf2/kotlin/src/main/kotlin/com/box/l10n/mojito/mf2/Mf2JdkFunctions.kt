package com.box.l10n.mojito.mf2

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

internal object Mf2JdkFunctions {
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
        val currency = currencyCode(call) ?: throw Mf2Error.badOperand("Currency function requires a currency option.")
        val format = NumberFormat.getCurrencyInstance(locale(call.locale))
        try {
            format.currency = Currency.getInstance(currency)
        } catch (error: IllegalArgumentException) {
            throw Mf2Error.badOperand("Currency option must be an ISO 4217 currency code.")
        }
        currencyFractionDigits(call)?.let {
            format.minimumFractionDigits = it
            format.maximumFractionDigits = it
        }
        return format.format(value)
    }

    private fun formatDate(call: Mf2FunctionCall): String {
        val date = dateFrom(call.rawValue, call.value)
            ?: parseSourceLocalDate(call.inheritedSource)
            ?: throw Mf2Error.badOperand("Date function requires a date or datetime operand.")
        return DateTimeFormatter.ofLocalizedDate(dateStyle(call.optionValue("length", call.optionValue("style", "short")) ?: "short"))
            .withLocale(locale(call.locale))
            .format(date)
    }

    private fun formatTime(call: Mf2FunctionCall): String {
        val time = timeFrom(call.rawValue, call.value)
            ?: parseSourceLocalTime(call.inheritedSource)
            ?: throw Mf2Error.badOperand("Datetime and time functions require a datetime operand.")
        return DateTimeFormatter.ofLocalizedTime(timeStyle(call.optionValue("precision", call.optionValue("style", "short")) ?: "short"))
            .withLocale(locale(call.locale))
            .format(time)
    }

    private fun formatDateTime(call: Mf2FunctionCall): String {
        val dateTime = zonedDateTimeFrom(call.rawValue, call.value)
            ?: parseSourceZonedDateTime(call.inheritedSource)
            ?: throw Mf2Error.badOperand("Datetime function requires a date or datetime operand.")
        val style = call.optionValue("style", null)?.let { dateStyle(it) }
        val dateStyle = style ?: dateStyle(call.optionValue("dateLength", "short") ?: "short")
        val timeStyle = style ?: timeStyle(call.optionValue("timePrecision", "short") ?: "short")
        return DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle)
            .withLocale(locale(call.locale))
            .format(dateTime)
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

    private fun locale(locale: String): Locale = Locale.forLanguageTag(locale.replace('_', '-'))

    private fun dateStyle(value: String): FormatStyle =
        when (value) {
            "full" -> FormatStyle.FULL
            "long" -> FormatStyle.LONG
            "medium" -> FormatStyle.MEDIUM
            "short" -> FormatStyle.SHORT
            else -> throw Mf2Error.badOption("Date length option must be full, long, medium, or short.")
        }

    private fun timeStyle(value: String): FormatStyle =
        when (value) {
            "full", "long", "medium", "short", "second" -> FormatStyle.MEDIUM
            else -> throw Mf2Error.badOption("Time precision option must be full, long, medium, short, or second.")
        }

    private fun dateFrom(rawValue: Any?, renderedValue: String): LocalDate? =
        when (rawValue) {
            is LocalDate -> rawValue
            is LocalDateTime -> rawValue.toLocalDate()
            is OffsetDateTime -> rawValue.toLocalDate()
            is ZonedDateTime -> rawValue.toLocalDate()
            is Instant -> rawValue.atZone(ZoneOffset.UTC).toLocalDate()
            is java.util.Date -> rawValue.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
            else -> parseLocalDate(renderedValue)
                ?: parseLocalDateTime(renderedValue)?.toLocalDate()
                ?: parseZonedDateTime(renderedValue)?.toLocalDate()
        }

    private fun timeFrom(rawValue: Any?, renderedValue: String): LocalTime? =
        when (rawValue) {
            is LocalTime -> rawValue
            is LocalDateTime -> rawValue.toLocalTime()
            is OffsetDateTime -> rawValue.toLocalTime()
            is ZonedDateTime -> rawValue.toLocalTime()
            is Instant -> rawValue.atZone(ZoneOffset.UTC).toLocalTime()
            is java.util.Date -> rawValue.toInstant().atZone(ZoneOffset.UTC).toLocalTime()
            else -> parseLocalTime(renderedValue)
                ?: parseLocalDateTime(renderedValue)?.toLocalTime()
                ?: parseZonedDateTime(renderedValue)?.toLocalTime()
        }

    private fun zonedDateTimeFrom(rawValue: Any?, renderedValue: String): ZonedDateTime? =
        when (rawValue) {
            is ZonedDateTime -> rawValue
            is OffsetDateTime -> rawValue.toZonedDateTime()
            is Instant -> rawValue.atZone(ZoneOffset.UTC)
            is java.util.Date -> rawValue.toInstant().atZone(ZoneOffset.UTC)
            is LocalDateTime -> rawValue.atZone(ZoneOffset.UTC)
            is LocalDate -> rawValue.atStartOfDay(ZoneOffset.UTC)
            else -> parseZonedDateTime(renderedValue)
                ?: parseLocalDateTime(renderedValue)?.atZone(ZoneOffset.UTC)
                ?: parseLocalDate(renderedValue)?.atStartOfDay(ZoneOffset.UTC)
        }

    private fun parseSourceLocalDate(source: Mf2FunctionSource?): LocalDate? {
        if (source == null) return null
        return dateFrom(source.value, source.value) ?: parseSourceLocalDate(source.inherited)
    }

    private fun parseSourceLocalTime(source: Mf2FunctionSource?): LocalTime? {
        if (source == null) return null
        return timeFrom(source.value, source.value) ?: parseSourceLocalTime(source.inherited)
    }

    private fun parseSourceZonedDateTime(source: Mf2FunctionSource?): ZonedDateTime? {
        if (source == null) return null
        return zonedDateTimeFrom(source.value, source.value) ?: parseSourceZonedDateTime(source.inherited)
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
            return ZonedDateTime.parse(value)
        } catch (error: DateTimeParseException) {
        }
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
