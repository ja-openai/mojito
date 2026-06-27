package com.box.l10n.mojito.mf2

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

object Mf2DateTimeCore {
    private const val DEFAULT_LOCALE = "en-US"
    private const val UTC = "UTC"
    private const val MIN_TIMESTAMP_MS = -62_135_596_800_000.0
    private const val MAX_TIMESTAMP_MS = 253_402_300_799_999.0
    private val minInstant: Instant = Instant.ofEpochMilli(MIN_TIMESTAMP_MS.toLong())
    private val maxInstant: Instant = Instant.ofEpochMilli(MAX_TIMESTAMP_MS.toLong())
    private const val MAX_OPTION_LENGTH = 256
    private const val MAX_OPERAND_LENGTH = 256
    private const val MAX_SKELETON_FIELD_WIDTH = 32
    private const val MAX_SKELETON_LENGTH = 256
    private const val SEMANTIC_SKELETON_PREFIX = "semantic:"
    private val semanticFieldOrder = listOf("era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear", "dayperiod", "hour", "minute", "second", "fractionalsecond", "millisecondsinday", "time", "zone")
    private val semanticDateFieldOrder = listOf("era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear")
    private val semanticTimeFieldOrder = listOf("hour", "minute", "second", "fractionalsecond", "millisecondsinday")
    private val semanticOptionKeys =
        setOf("fields", "length", "alignment", "yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle", "timeprecision", "timestyle", "fractionalsecond", "hourcycle", "zonestyle")
    private val semanticDirectStyleOptionKeys = setOf("fields", "length", "timestyle")
    private val semanticStyleOptionKeys = setOf("yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle")
    private val semanticDateStyleValues = setOf("auto", "numeric", "2-digit", "short", "long", "narrow")
    private val semanticNumericStyleValues = setOf("auto", "numeric", "2-digit")
    private val semanticTextStyleValues = setOf("auto", "short", "long", "narrow")
    private val semanticDateFieldSets =
        setOf(
            "day", "weekday", "day,weekday", "month,day", "month,day,weekday",
            "era,year,month,day", "era,year,month,day,weekday", "year,month,day", "year,month,day,weekday",
        )
    private val semanticCalendarPeriodFieldSets =
        setOf(
            "era", "year", "quarter", "month", "era,year", "era,year,quarter", "era,year,month",
            "era,year,weekofyear", "era,year,month,weekofmonth", "year,quarter", "year,month",
            "year,weekofyear", "month,weekofmonth", "year,month,weekofmonth",
            "dayofyear", "dayofweekinmonth", "modifiedjulianday",
        )
    private val semanticTimeFieldSets =
        setOf(
            "hour", "minute", "second", "millisecondsinday", "hour,minute", "hour,minute,second",
            "hour,minute,second,fractionalsecond", "minute,second",
            "minute,second,fractionalsecond", "second,fractionalsecond",
        )
    private const val SKELETON_FIELD_ORDER = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx"
    private const val SKELETON_TIME_FIELDS = "abBhHkKJmsSAzZOvVXx"
    private const val SKELETON_HOUR_FIELDS = "hHkK"
    private val weekdayKeys = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

    @JvmStatic
    fun options(): Options.Builder = Options.Builder()

    @JvmStatic
    fun registry(): Mf2FunctionRegistry =
        Mf2FunctionRegistry.portable()
            .withFunction("date", ::formatCallDate)
            .withFunction("time", ::formatCallTime)
            .withFunction("datetime", ::formatCallDateTime)

    @JvmStatic
    fun formatDate(value: Any?, options: Options = Options()): String {
        val locale = LocaleKey.option(options.locale, DEFAULT_LOCALE)
        val localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale)
        validateOptions(options)
        val preserveSameFamilyHourCycle = !options.hourCycle.isNullOrEmpty()
        val hourCycle = validateHourCycle(options.hourCycle?.takeIf { it.isNotEmpty() } ?: localeUnicodeExtension(locale, "hc"))
        val date = parseDate(value).withZoneSameInstant(parseTimeZone(options.timeZone))
        options.skeleton?.let { return formatSkeleton(it, date, localeData, hourCycle, preserveSameFamilyHourCycle) }
        return formatPattern(localeData.dateFormats.getValue(styleKey(options.effectiveDateStyle())), date, localeData)
    }

    @JvmStatic
    fun formatDateToParts(value: Any?, options: Options = Options()): List<Mf2Part> =
        listOf(mapOf("type" to "text", "value" to formatDate(value, options)))

    @JvmStatic
    fun formatTime(value: Any?, options: Options = Options()): String {
        val locale = LocaleKey.option(options.locale, DEFAULT_LOCALE)
        val localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale)
        validateOptions(options)
        val preserveSameFamilyHourCycle = !options.hourCycle.isNullOrEmpty()
        val hourCycle = validateHourCycle(options.hourCycle?.takeIf { it.isNotEmpty() } ?: localeUnicodeExtension(locale, "hc"))
        val date = parseDate(value).withZoneSameInstant(parseTimeZone(options.timeZone))
        options.skeleton?.let { return formatSkeleton(it, date, localeData, hourCycle, preserveSameFamilyHourCycle) }
        return formatTimeStylePattern(localeData.timeFormats.getValue(styleKey(options.effectiveTimeStyle())), date, localeData, hourCycle, preserveSameFamilyHourCycle)
    }

    @JvmStatic
    fun formatTimeToParts(value: Any?, options: Options = Options()): List<Mf2Part> =
        listOf(mapOf("type" to "text", "value" to formatTime(value, options)))

    @JvmStatic
    fun formatDateTime(value: Any?, options: Options = Options()): String {
        val locale = LocaleKey.option(options.locale, DEFAULT_LOCALE)
        val localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale)
        validateOptions(options)
        val preserveSameFamilyHourCycle = !options.hourCycle.isNullOrEmpty()
        val hourCycle = validateHourCycle(options.hourCycle?.takeIf { it.isNotEmpty() } ?: localeUnicodeExtension(locale, "hc"))
        val date = parseDate(value).withZoneSameInstant(parseTimeZone(options.timeZone))
        options.skeleton?.let { return formatSkeleton(it, date, localeData, hourCycle, preserveSameFamilyHourCycle) }
        val dateStyle = options.effectiveDateStyle()
        val timeStyle = options.effectiveTimeStyle()
        val datePart = formatPattern(localeData.dateFormats.getValue(styleKey(dateStyle)), date, localeData)
        val timePart = formatTimeStylePattern(localeData.timeFormats.getValue(styleKey(timeStyle)), date, localeData, hourCycle, preserveSameFamilyHourCycle)
        return dateTimeStyleJoinPattern(localeData, styleKey(dateStyle))
            .replace("{1}", datePart)
            .replace("{0}", timePart)
    }

    @JvmStatic
    fun formatDateTimeToParts(value: Any?, options: Options = Options()): List<Mf2Part> =
        listOf(mapOf("type" to "text", "value" to formatDateTime(value, options)))

    private fun formatCallDate(call: Mf2FunctionCall): String =
        formatDate(
            callSourceValue(call),
            Options(
                locale = call.locale,
                dateStyle = callStyle(call, "dateStyle", "length", "medium", false),
                skeleton = nonEmptyCallOption(call, "skeleton", null),
                hourCycle = nonEmptyCallOption(call, "hourCycle", null),
                timeZone = nonEmptyCallOption(call, "timeZone", UTC) ?: UTC,
                calendar = nonEmptyCallOption(call, "calendar", null),
            ),
        )

    private fun formatCallTime(call: Mf2FunctionCall): String =
        formatTime(
            callSourceValue(call),
            Options(
                locale = call.locale,
                timeStyle = callStyle(call, "timeStyle", "precision", "medium", true),
                skeleton = nonEmptyCallOption(call, "skeleton", null),
                hourCycle = nonEmptyCallOption(call, "hourCycle", null),
                timeZone = nonEmptyCallOption(call, "timeZone", UTC) ?: UTC,
                calendar = nonEmptyCallOption(call, "calendar", null),
            ),
        )

    private fun formatCallDateTime(call: Mf2FunctionCall): String =
        formatDateTime(
            callSourceValue(call),
            Options(
                locale = call.locale,
                dateStyle = callStyle(call, "dateStyle", "dateLength", "medium", false),
                timeStyle = callStyle(call, "timeStyle", "timePrecision", "medium", true),
                skeleton = nonEmptyCallOption(call, "skeleton", null),
                hourCycle = nonEmptyCallOption(call, "hourCycle", null),
                timeZone = nonEmptyCallOption(call, "timeZone", UTC) ?: UTC,
                calendar = nonEmptyCallOption(call, "calendar", null),
            ),
        )

    private fun callSourceValue(call: Mf2FunctionCall): Any? =
        call.inheritedSource?.value ?: call.rawValue ?: call.value

    private fun resolveLocaleData(locale: String): CldrDateTimeData.LocaleData {
        for (candidate in LocaleKey.lookupChain(locale)) {
            CldrDateTimeData.locales[candidate]?.let { return it }
            CldrDateTimeData.locales.values
                .firstOrNull { it.sourceLocale == candidate || it.numbersSourceLocale == candidate }
                ?.let { return it }
        }
        return CldrDateTimeData.locales.getValue(DEFAULT_LOCALE)
    }

    private fun localeUnicodeExtension(locale: String, key: String): String? {
        val parts =
            locale
                .trim()
                .replace('_', '-')
                .split("-")
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
        var index = parts.indexOf("u")
        if (index < 0) return null
        index += 1
        while (index < parts.size) {
            val part = parts[index]
            if (part.length == 1) return null
            if (part.length != 2) {
                index += 1
                continue
            }
            var end = index + 1
            while (end < parts.size && parts[end].length > 2) {
                end += 1
            }
            if (part == key) return if (end > index + 1) parts[index + 1] else null
            index = end
        }
        return null
    }

    private fun validateOptions(options: Options) {
        val calendar = boundedOption(options.calendar?.takeIf { it.isNotEmpty() } ?: localeUnicodeExtension(options.locale, "ca"), "calendar")
        if (calendar != null && calendar != "gregorian" && calendar != "gregory") {
            throw Mf2Error.badOption("Date/time core currently supports only the gregorian/gregory calendar.")
        }
    }

    private fun resolveNumberingSystemData(
        localeData: CldrDateTimeData.LocaleData,
        locale: String,
    ): CldrDateTimeData.LocaleData {
        val numberingSystem = localeUnicodeExtension(locale, "nu") ?: return localeData
        if (numberingSystem.isEmpty()) return localeData
        val digits =
            numberingSystemDigits(numberingSystem)
                ?: throw Mf2Error.badOption("Date/time core does not include data for the requested numbering system.")
        return localeData.copy(numberingSystemDigits = digits)
    }

    private fun numberingSystemDigits(numberingSystem: String): String? {
        if (numberingSystem == "latn") return "0123456789"
        return CldrDateTimeData.locales.values
            .firstOrNull { it.numberingSystem == numberingSystem && it.numberingSystemDigits != null }
            ?.numberingSystemDigits
    }

    private fun validateHourCycle(value: String?): String? =
        when (val bounded = boundedOption(value, "hourCycle")) {
            null, "" -> null
            "h11", "h12", "h23", "h24" -> bounded
            else -> throw Mf2Error.badOption("hourCycle must be one of h11, h12, h23, h24.")
        }

    private fun parseTimeZone(value: String?): ZoneOffset {
        val text = boundedOption(value, "timeZone")?.trim().takeUnless { it.isNullOrEmpty() } ?: UTC
        if (text == UTC || text == "Etc/UTC" || text == "Z" || text == "GMT" || text == "Etc/GMT") {
            return ZoneOffset.UTC
        }
        parseEtcGmtOffsetMinutes(text)?.let { return ZoneOffset.ofTotalSeconds(it * 60) }
        val offsetText = if ((text.startsWith("UTC") || text.startsWith("GMT")) && text.length > 3) {
            text.substring(3)
        } else {
            text
        }
        val offsetMinutes = parseOffsetMinutes(offsetText)
            ?: throw Mf2Error.badOption("Date/time core supports only UTC or fixed-offset time zones.")
        return ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
    }

    private fun boundedOption(value: String?, name: String): String? {
        if (value != null && value.length > MAX_OPTION_LENGTH) {
            throw Mf2Error.badOption("$name must not exceed 256 characters.")
        }
        return value
    }

    private fun parseEtcGmtOffsetMinutes(value: String): Int? {
        val prefix = "Etc/GMT"
        if (!value.startsWith(prefix) || value.length <= prefix.length) return null
        val sign = value[prefix.length]
        if (sign != '+' && sign != '-') return null
        val hourText = value.substring(prefix.length + 1)
        if (hourText.isEmpty() || hourText.length > 2 || !hourText.all(::isAsciiDigit)) return null
        val hours = hourText.toInt()
        if (hours > 14) return null
        val offset = hours * 60
        return if (sign == '+') -offset else offset
    }

    private fun parseOffsetMinutes(value: String): Int? {
        if (value.length < 2) return null
        val sign = value[0]
        if (sign != '+' && sign != '-') return null
        val body = value.substring(1)
        var hourText = body
        var minuteText = "00"
        val colon = body.indexOf(':')
        if (colon >= 0) {
            hourText = body.substring(0, colon)
            minuteText = body.substring(colon + 1)
        } else if (body.length > 2) {
            hourText = body.substring(0, body.length - 2)
            minuteText = body.substring(body.length - 2)
        }
        if (hourText.isEmpty() || hourText.length > 2 || minuteText.length != 2) {
            return null
        }
        if (!hourText.all(::isAsciiDigit) || !minuteText.all(::isAsciiDigit)) {
            return null
        }
        val hours = hourText.toIntOrNull() ?: return null
        val minutes = minuteText.toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59 || (hours == 18 && minutes != 0)) {
            return null
        }
        val total = hours * 60 + minutes
        return if (sign == '-') -total else total
    }

    private fun parseDate(value: Any?): ZonedDateTime {
        return when (value) {
            is LocalDateTime -> validateDate(value.atZone(ZoneOffset.UTC))
            is LocalDate -> validateDate(value.atStartOfDay(ZoneOffset.UTC))
            is LocalTime -> validateDate(value.atDate(LocalDate.of(1970, 1, 1)).atZone(ZoneOffset.UTC))
            is ZonedDateTime -> validateDate(value.withZoneSameInstant(ZoneOffset.UTC))
            is OffsetDateTime -> validateDate(value.atZoneSameInstant(ZoneOffset.UTC))
            is Instant -> validateDate(value.atZone(ZoneOffset.UTC))
            is java.util.Date -> validateDate(value.toInstant().atZone(ZoneOffset.UTC))
            is Number -> {
                val epochMillis = value.toDouble()
                if (!epochMillis.isFinite() || epochMillis < MIN_TIMESTAMP_MS || epochMillis > MAX_TIMESTAMP_MS) {
                    throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
                }
                validateDate(Instant.ofEpochMilli(epochMillis.toLong()).atZone(ZoneOffset.UTC))
            }
            is CharSequence -> {
                val text = try {
                    value.toString().trim()
                } catch (_: RuntimeException) {
                    throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
                }
                parseDateString(text)
            }
            else -> throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
        }
    }

    private fun parseDateString(value: String): ZonedDateTime {
        if (value.length > MAX_OPERAND_LENGTH) {
            throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
        }
        try {
            return validateDate(Instant.parse(value).atZone(ZoneOffset.UTC))
        } catch (_: DateTimeParseException) {
        }
        try {
            return validateDate(OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC))
        } catch (_: DateTimeParseException) {
        }
        try {
            return validateDate(LocalDateTime.parse(value).atZone(ZoneOffset.UTC))
        } catch (_: DateTimeParseException) {
        }
        try {
            return validateDate(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC))
        } catch (_: DateTimeParseException) {
            throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
        }
    }

    private fun validateDate(value: ZonedDateTime): ZonedDateTime {
        val instant = value.toInstant()
        if (instant < minInstant || instant > maxInstant) {
            throw Mf2Error.badOperand("Date/time core requires a valid host date/time value or ISO date string.")
        }
        return value
    }

    private fun formatSkeleton(
        skeleton: String,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Boolean,
    ): String {
        if (skeleton.length > MAX_SKELETON_LENGTH) {
            throw Mf2Error.badOption("Date/time skeleton is too large.")
        }
        formatSemanticStyleSkeleton(skeleton, date, localeData, hourCycle, preserveSameFamilyHourCycle)?.let { return it }
        val canonical = canonicalSkeleton(skeleton, localeData, hourCycle, date)
        val suppressDayPeriod = shouldSuppressDayPeriod(skeleton)
        val dateTimeJoinStyle = skeletonDateTimeJoinStyle(skeleton)
        skeletonPattern(canonical, localeData)?.let {
            return formatPattern(if (suppressDayPeriod) stripDayPeriodPatternFields(it) else it, date, localeData)
        }
        return formatComposedSkeleton(skeleton, canonical, date, localeData, suppressDayPeriod, dateTimeJoinStyle)
    }

    private fun skeletonDateTimeJoinStyle(skeleton: String): String {
        if (!skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            return "medium"
        }
        val options = parseSemanticSkeletonOptions(skeleton.substring(SEMANTIC_SKELETON_PREFIX.length))
        return semanticOption(options, "length", "medium", setOf("full", "long", "medium", "short"))
    }

    private fun formatSemanticStyleSkeleton(
        skeleton: String,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Boolean,
    ): String? {
        if (!skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            return null
        }
        val options = parseSemanticSkeletonOptions(skeleton.substring(SEMANTIC_SKELETON_PREFIX.length))
        val fields = parseSemanticSkeletonFields(options)
        validateSemanticSkeleton(fields, options)
        if (options.keys.any { it !in semanticDirectStyleOptionKeys }) {
            return null
        }

        val length = semanticOption(options, "length", "medium", setOf("full", "long", "medium", "short"))
        val timeStyle = semanticOption(options, "timestyle", "auto", setOf("auto", "short", "medium", "long", "full"))
        val dateKey = semanticFieldSetKey(fields, semanticDateFieldOrder)
        val expectedDateKey = if (length == "full") "year,month,day,weekday" else "year,month,day"
        val hasDate = dateKey.isNotEmpty()
        val hasTime = "time" in fields
        val hasZone = "zone" in fields
        if (semanticFieldSetKey(fields, semanticTimeFieldOrder).isNotEmpty()) {
            return null
        }
        if (hasDate && dateKey != expectedDateKey) {
            return null
        }
        if (hasTime && "timestyle" !in options) {
            return null
        }
        if (!hasTime && (hasZone || timeStyle != "auto")) {
            return null
        }
        if (hasTime && hasZone != semanticTimeStyleHasZone(timeStyle)) {
            return null
        }
        val expectedFieldCount =
            (if (hasDate) expectedDateKey.split(",").size else 0) + (if (hasTime) 1 else 0) + (if (hasZone) 1 else 0)
        if (fields.size != expectedFieldCount) {
            return null
        }

        if (hasDate && hasTime) {
            val datePart = formatPattern(localeData.dateFormats.getValue(length), date, localeData)
            val timePart = formatTimeStylePattern(localeData.timeFormats.getValue(timeStyle), date, localeData, hourCycle, preserveSameFamilyHourCycle)
            val joinPattern = dateTimeStyleJoinPattern(localeData, length)
            return joinPattern.replace("{1}", datePart).replace("{0}", timePart)
        }
        if (hasDate) {
            return formatPattern(localeData.dateFormats.getValue(length), date, localeData)
        }
        if (hasTime) {
            return formatTimeStylePattern(localeData.timeFormats.getValue(timeStyle), date, localeData, hourCycle, preserveSameFamilyHourCycle)
        }
        return null
    }

    private fun formatTimeStylePattern(
        pattern: String,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        preserveSameFamilyHourCycle: Boolean,
    ): String {
        if (hourCycle == null) {
            return formatPattern(pattern, date, localeData)
        }
        val hourSymbol = preferredHourSymbol(localeData, hourCycle)
        val patternHourSymbol = timeStylePatternHourSymbol(pattern)
        if (preserveSameFamilyHourCycle && patternHourSymbol != null && isHour12Field(patternHourSymbol) == isHour12Field(hourSymbol)) {
            return formatPattern(replaceTimeStylePatternHourSymbol(pattern, hourSymbol), date, localeData)
        }
        val skeleton = timeStylePatternSkeleton(pattern, localeData, hourCycle)
            ?: return formatPattern(pattern, date, localeData)
        val canonical = canonicalStandardSkeleton(skeleton, localeData, null)
        return formatPattern(skeletonPattern(canonical, localeData) ?: pattern, date, localeData)
    }

    private fun dateTimeStyleJoinPattern(
        localeData: CldrDateTimeData.LocaleData,
        style: String,
    ): String = localeData.dateTimeStyleJoinFormats[style]
        ?: localeData.dateTimeFormats[style]
        ?: localeData.dateTimeFormats["medium"]
        ?: "{1} {0}"

    private fun timeStylePatternHourSymbol(pattern: String): Char? {
        var index = 0
        while (index < pattern.length) {
            val symbol = pattern[index]
            if (symbol == '\'') {
                index = readQuotedPattern(pattern, index).nextIndex
            } else if (isAsciiLetter(symbol)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == symbol) {
                    end++
                }
                if (isHourField(symbol)) {
                    return symbol
                }
                index = end
            } else {
                index++
            }
        }
        return null
    }

    private fun replaceTimeStylePatternHourSymbol(pattern: String, hourSymbol: Char): String {
        val output = StringBuilder(pattern.length)
        var index = 0
        while (index < pattern.length) {
            val symbol = pattern[index]
            if (symbol == '\'') {
                val quoted = readQuotedPattern(pattern, index)
                output.append(pattern.substring(index, quoted.nextIndex))
                index = quoted.nextIndex
            } else if (isAsciiLetter(symbol)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == symbol) {
                    end++
                }
                output.append(
                    if (isHourField(symbol)) {
                        hourSymbol.toString().repeat(end - index)
                    } else {
                        pattern.substring(index, end)
                    },
                )
                index = end
            } else {
                output.append(symbol)
                index++
            }
        }
        return output.toString()
    }

    private fun timeStylePatternSkeleton(
        pattern: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String,
    ): String? {
        val widths = mutableMapOf<Char, Int>()
        val hourSymbol = preferredHourSymbol(localeData, hourCycle)
        var hasHour = false
        for ((symbol, width) in patternFieldRuns(pattern)) {
            if (isHourField(symbol)) {
                setSkeletonWidth(widths, hourSymbol, width)
                hasHour = true
            } else if (!isDayPeriodField(symbol) && symbol in SKELETON_TIME_FIELDS) {
                setSkeletonWidth(widths, symbol, width)
            }
        }
        if (!hasHour) {
            return null
        }
        return buildString {
            for (symbol in SKELETON_FIELD_ORDER) {
                widths[symbol]?.let { append(symbol.toString().repeat(it)) }
            }
        }
    }

    private fun skeletonPattern(
        canonical: String,
        localeData: CldrDateTimeData.LocaleData,
    ): String? {
        skeletonPatternWithoutAppend(canonical, localeData)?.let { return it }
        return if (hasDateAndTimeFields(canonical)) {
            null
        } else {
            appendedSkeletonPattern(canonical, localeData)
        }
    }

    private fun skeletonPatternWithoutAppend(
        canonical: String,
        localeData: CldrDateTimeData.LocaleData,
    ): String? {
        localeData.availableFormats[canonical]?.let { return it }
        val requestedFields = skeletonFieldSet(canonical)
        var bestCandidate: String? = null
        var bestPattern: String? = null
        var bestDistance = Int.MAX_VALUE
        for ((candidate, pattern) in localeData.availableFormats) {
            if (skeletonFieldSet(candidate) != requestedFields) {
                continue
            }
            val distance = skeletonDistance(canonical, candidate)
            if (distance < bestDistance || (distance == bestDistance && (bestCandidate == null || candidate < bestCandidate))) {
                bestCandidate = candidate
                bestPattern = pattern
                bestDistance = distance
            }
        }
        return if (bestPattern == null || bestCandidate == null) {
            syntheticSkeletonPattern(canonical, localeData)
        } else {
            adjustPatternWidths(bestPattern, canonical, bestCandidate)
        }
    }

    private fun appendedSkeletonPattern(
        canonical: String,
        localeData: CldrDateTimeData.LocaleData,
    ): String? {
        val requestedFields = skeletonFieldSet(canonical)
        var bestCandidate: String? = null
        var bestPattern: String? = null
        var bestFieldCount = -1
        var bestDistance = Int.MAX_VALUE
        for ((candidate, pattern) in localeData.availableFormats) {
            val candidateFields = skeletonFieldSet(candidate)
            if (candidateFields.isEmpty() || candidateFields == requestedFields) {
                continue
            }
            if (!fieldSetContains(requestedFields, candidateFields)) {
                continue
            }
            val fieldCount = candidateFields.length
            val distance = skeletonDistance(canonical, candidate)
            if (
                fieldCount > bestFieldCount ||
                    (fieldCount == bestFieldCount && (distance < bestDistance || (distance == bestDistance && (bestCandidate == null || candidate < bestCandidate))))
            ) {
                bestCandidate = candidate
                bestPattern = pattern
                bestFieldCount = fieldCount
                bestDistance = distance
            }
        }
        if (bestPattern == null || bestCandidate == null) {
            return null
        }
        var output = adjustPatternWidths(bestPattern, canonical, bestCandidate)
        val currentFields = bestCandidate.map { fieldSetSymbol(it) }.toMutableSet()
        val requestedWidths = skeletonWidths(canonical)
        for (symbol in SKELETON_FIELD_ORDER) {
            val width = requestedWidths[symbol] ?: continue
            val field = fieldSetSymbol(symbol)
            if (field in currentFields) {
                continue
            }
            val key = appendItemKey(symbol) ?: return null
            val fieldSkeleton = symbol.toString().repeat(width)
            val fieldPattern = skeletonPatternWithoutAppend(fieldSkeleton, localeData) ?: fieldSkeleton
            output =
                applyAppendItemPattern(
                    appendItemTemplate(localeData, key),
                    output,
                    fieldPattern,
                    localeData.fieldNames[key] ?: key,
                )
            currentFields.add(field)
        }
        return output
    }

    private fun fieldSetContains(
        container: String,
        subset: String,
    ): Boolean = subset.all { it in container }

    private fun applyAppendItemPattern(
        template: String,
        basePattern: String,
        fieldPattern: String,
        fieldName: String,
    ): String =
        template
            .replace("{0}", basePattern)
            .replace("{1}", fieldPattern)
            .replace("{2}", quotePatternLiteral(fieldName))

    private fun quotePatternLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun appendItemTemplate(
        localeData: CldrDateTimeData.LocaleData,
        key: String,
    ): String = localeData.appendItems[key] ?: defaultAppendItemTemplate(key)

    private fun defaultAppendItemTemplate(key: String): String =
        when (key) {
            "Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second" -> "{0} ({2}: {1})"
            else -> "{0} {1}"
        }

    private fun hasDateAndTimeFields(canonical: String): Boolean {
        val (dateSkeleton, timeSkeleton) = splitDateTimeSkeleton(canonical)
        return dateSkeleton.isNotEmpty() && timeSkeleton.isNotEmpty()
    }

    private fun appendItemKey(symbol: Char): String? =
        when {
            symbol == 'G' -> "Era"
            isYearField(symbol) -> "Year"
            isQuarterField(symbol) -> "Quarter"
            isMonthField(symbol) -> "Month"
            symbol == 'w' || symbol == 'W' -> "Week"
            symbol == 'd' || symbol == 'D' || symbol == 'F' || symbol == 'g' -> "Day"
            isWeekdayField(symbol) -> "Day-Of-Week"
            isHourField(symbol) -> "Hour"
            symbol == 'm' -> "Minute"
            symbol == 's' || symbol == 'S' || symbol == 'A' -> "Second"
            isTimeZoneField(symbol) -> "Timezone"
            else -> null
        }

    private fun syntheticSkeletonPattern(
        canonical: String,
        localeData: CldrDateTimeData.LocaleData,
    ): String? {
        val widths = skeletonWidths(canonical)
        if (widths.size == 1) {
            val (symbol, width) = widths.entries.first()
            if (symbol == 'G') {
                return symbol.toString().repeat(width)
            }
            if (isDayPeriodField(symbol)) {
                return symbol.toString().repeat(width)
            }
            if (isQuarterField(symbol)) {
                return symbol.toString().repeat(width)
            }
            if (isSyntheticNumericField(symbol)) {
                return symbol.toString().repeat(width)
            }
            if (symbol == 'S') {
                return symbol.toString().repeat(width)
            }
            if (isTimeZoneField(symbol)) {
                return symbol.toString().repeat(width)
            }
        }
        return syntheticFractionalSecondPattern(canonical, localeData, widths)
    }

    private fun syntheticFractionalSecondPattern(
        canonical: String,
        localeData: CldrDateTimeData.LocaleData,
        widths: Map<Char, Int>,
    ): String? {
        val fractionWidth = widths['S'] ?: return null
        if (!widths.containsKey('s')) {
            return null
        }
        val baseSkeleton = skeletonWithoutField(canonical, 'S')
        val basePattern = skeletonPattern(baseSkeleton, localeData) ?: syntheticSecondsPattern(baseSkeleton)
        return basePattern?.let { insertFractionalSecond(it, fractionWidth, localeData.decimalSeparator) }
    }

    private fun syntheticSecondsPattern(canonical: String): String? {
        val widths = skeletonWidths(canonical)
        val width = widths['s']
        return if (widths.size == 1 && width != null) {
            "s".repeat(width)
        } else {
            null
        }
    }

    private fun skeletonWithoutField(skeleton: String, removedSymbol: Char): String =
        buildString {
            var index = 0
            while (index < skeleton.length) {
                val symbol = skeleton[index]
                var end = index + 1
                while (end < skeleton.length && skeleton[end] == symbol) {
                    end += 1
                }
                if (symbol != removedSymbol) {
                    append(skeleton.substring(index, end))
                }
                index = end
            }
        }

    private fun insertFractionalSecond(pattern: String, width: Int, decimalSeparator: String): String? {
        val output = StringBuilder()
        var inQuote = false
        var index = 0
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\'') {
                output.append(ch)
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') {
                    output.append('\'')
                    index += 2
                } else {
                    inQuote = !inQuote
                    index += 1
                }
            } else if (!inQuote && ch == 's') {
                var end = index + 1
                while (end < pattern.length && pattern[end] == ch) {
                    end += 1
                }
                output.append(pattern.substring(index, end))
                output.append(decimalSeparator)
                output.append("S".repeat(width))
                output.append(pattern.substring(end))
                return output.toString()
            } else {
                output.append(ch)
                index += 1
            }
        }
        return null
    }

    private fun formatComposedSkeleton(
        rawSkeleton: String,
        canonical: String,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        suppressDayPeriod: Boolean,
        dateTimeJoinStyle: String,
    ): String {
        val (dateSkeleton, timeSkeleton) = splitDateTimeSkeleton(canonical)
        if (dateSkeleton.isEmpty() || timeSkeleton.isEmpty()) {
            throw unsupportedSkeleton(rawSkeleton)
        }
        val datePattern = skeletonPattern(dateSkeleton, localeData) ?: throw unsupportedSkeleton(rawSkeleton)
        var timePattern = skeletonPattern(timeSkeleton, localeData) ?: throw unsupportedSkeleton(rawSkeleton)
        if (suppressDayPeriod) {
            timePattern = stripDayPeriodPatternFields(timePattern)
        }
        val datePart = formatPattern(datePattern, date, localeData)
        val timePart = formatPattern(timePattern, date, localeData)
        return (localeData.dateTimeFormats[dateTimeJoinStyle] ?: localeData.dateTimeFormats["medium"] ?: "{1} {0}")
            .replace("{1}", datePart)
            .replace("{0}", timePart)
    }

    private fun unsupportedSkeleton(skeleton: String): Mf2Error =
        Mf2Error.badOption("Unsupported CLDR date/time skeleton: $skeleton.")

    private fun canonicalSkeleton(
        skeleton: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        date: ZonedDateTime,
    ): String {
        if (skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            val standard = semanticSkeletonToStandard(skeleton.substring(SEMANTIC_SKELETON_PREFIX.length), localeData, date)
            return canonicalStandardSkeleton(standard, localeData, hourCycle)
        }
        return canonicalStandardSkeleton(skeleton, localeData, hourCycle)
    }

    private fun canonicalStandardSkeleton(
        skeleton: String,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
    ): String {
        val widths = mutableMapOf<Char, Int>()
        var index = 0
        while (index < skeleton.length) {
            val symbol = skeleton[index]
            if (!isAsciiLetter(symbol)) {
                throw Mf2Error.badOption("Date/time skeleton must contain only ASCII pattern letters.")
            }
            var end = index + 1
            while (end < skeleton.length && skeleton[end] == symbol) {
                end++
            }
            val width = end - index
            if (width > MAX_SKELETON_FIELD_WIDTH) {
                throw Mf2Error.badOption("Date/time skeleton field width is too large.")
            }
            if (symbol == 'C') {
                applyCHourFormat(widths, localeData, hourCycle, width)
            } else {
                val normalized = normalizeSkeletonSymbol(symbol, localeData, hourCycle)
                setSkeletonWidth(widths, normalized, width)
            }
            index = end
        }
        val canonical = buildString {
            for (symbol in SKELETON_FIELD_ORDER) {
                repeat(widths[symbol] ?: 0) {
                    append(symbol)
                }
            }
        }
        if (canonical.isEmpty()) {
            throw Mf2Error.badOption("Date/time skeleton must not be empty.")
        }
        return canonical
    }

    private fun semanticSkeletonToStandard(
        body: String,
        localeData: CldrDateTimeData.LocaleData,
        date: ZonedDateTime,
    ): String {
        val options = parseSemanticSkeletonOptions(body)
        val fields = parseSemanticSkeletonFields(options)
        validateSemanticSkeleton(fields, options)
        val length = semanticOption(options, "length", "medium", setOf("full", "long", "medium", "short"))
        val alignment = semanticOption(options, "alignment", "inline", setOf("inline", "column"))
        val yearStyle = semanticOption(options, "yearstyle", "auto", setOf("auto", "full", "with-era", "numeric", "2-digit"))
        val eraStyle = semanticOption(options, "erastyle", "auto", semanticTextStyleValues)
        val monthStyle = semanticOption(options, "monthstyle", "auto", semanticDateStyleValues)
        val quarterStyle = semanticOption(options, "quarterstyle", "auto", semanticDateStyleValues)
        val dayStyle = semanticOption(options, "daystyle", "auto", semanticNumericStyleValues)
        val weekdayStyle = semanticOption(options, "weekdaystyle", "auto", semanticTextStyleValues)
        val dayPeriodStyle = semanticOption(options, "dayperiodstyle", "auto", semanticTextStyleValues)
        semanticOption(options, "hourstyle", "auto", semanticNumericStyleValues)
        semanticOption(options, "minutestyle", "auto", semanticNumericStyleValues)
        semanticOption(options, "secondstyle", "auto", semanticNumericStyleValues)
        val timePrecision =
            semanticOption(options, "timeprecision", "second", setOf("hour", "minute", "minute-optional", "second", "fractional-second"))
        val timeStyle = semanticOption(options, "timestyle", "auto", setOf("auto", "short", "medium", "long", "full"))
        val effectiveTimePrecision = semanticTimeStylePrecision(timeStyle, timePrecision)
        val semanticHourCycle =
            semanticOption(options, "hourcycle", "auto", setOf("auto", "h11", "h12", "h23", "h24", "clock12", "clock24"))
        val zoneStyle = semanticOption(options, "zonestyle", "auto", setOf("auto", "generic", "specific", "location", "offset"))
        val effectiveZoneStyle = semanticTimeStyleZoneStyle(timeStyle, zoneStyle)
        val effectiveZoneStandalone = fields.size == 1 || timeStyle == "full"
        val effectiveZoneLength = if (semanticTimeStyleHasZone(timeStyle)) timeStyle else length
        val dateWidths = semanticDateFieldWidths(localeData, length)
        val standard = buildString {
            if ("era" in fields) append(semanticEraSkeleton(dateWidths, length, eraStyle))
            if ("year" in fields) append(semanticYearSkeleton(dateWidths, yearStyle, includeEra = "era" !in fields))
            if ("quarter" in fields) append(semanticQuarterSkeleton(fields, length, alignment, quarterStyle))
            if ("month" in fields) append(semanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle))
            if ("weekofmonth" in fields) append('W')
            if ("day" in fields) append(semanticDaySkeleton(dateWidths, alignment, dayStyle))
            if ("dayofyear" in fields) append("D".repeat(if (alignment == "column") 3 else 1))
            if ("dayofweekinmonth" in fields) append("F".repeat(if (alignment == "column") 2 else 1))
            if ("modifiedjulianday" in fields) append("g".repeat(if (alignment == "column") 6 else 1))
            if ("weekday" in fields) append(semanticWeekdaySkeleton(fields, length, weekdayStyle))
            if ("weekofyear" in fields) append(if (alignment == "column") "ww" else "w")
            if ("dayperiod" in fields) append(semanticDayPeriodSkeleton(length, dayPeriodStyle))
            if (hasSemanticTimeComponents(fields)) append(semanticExplicitTimeSkeleton(fields, semanticHourCycle, alignment, options))
            if ("time" in fields) append(semanticTimeSkeleton(effectiveTimePrecision, semanticHourCycle, alignment, date, options))
            if ("zone" in fields) append(semanticZoneSkeleton(effectiveZoneStyle, effectiveZoneStandalone, effectiveZoneLength))
        }
        if (standard.isEmpty()) {
            throw Mf2Error.badOption("Date/time semantic skeleton must include at least one field.")
        }
        return standard
    }

    private fun parseSemanticSkeletonOptions(body: String): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var seenParts = 0
        var implicitDateStyle: String? = null
        var implicitTimeFields = false
        for (rawPart in body.split(";")) {
            val part = rawPart.trim()
            if (part.isEmpty()) {
                continue
            }
            val equals = part.indexOf('=')
            val rawKey = if (equals < 0) {
                if (seenParts == 0) "fields" else ""
            } else {
                part.substring(0, equals)
            }
            val rawValue = if (equals < 0) part else part.substring(equals + 1)
            val rawKeyAlias = semanticNormalize(rawKey)
            val key = semanticNormalizeOptionKey(rawKey)
            val value = semanticNormalizeOptionValue(key, rawValue)
            if (key.isEmpty() || value.isEmpty() || key !in semanticOptionKeys || key in options) {
                throw Mf2Error.badOption("Invalid date/time semantic skeleton option.")
            }
            if (rawKeyAlias == "style" || rawKeyAlias == "datestyle" || rawKeyAlias == "datelength") {
                implicitDateStyle = value
            }
            if (rawKeyAlias == "timestyle") {
                implicitTimeFields = true
            }
            options[key] = value
            seenParts++
        }
        if (seenParts == 0) {
            throw Mf2Error.badOption("Date/time semantic skeleton must include fields.")
        }
        if ("fields" !in options) {
            val fields = implicitSemanticFields(implicitDateStyle, implicitTimeFields, options["timestyle"])
            if (fields.isNotEmpty()) {
                options["fields"] = fields
            }
        }
        return options
    }

    private fun implicitSemanticFields(
        dateStyle: String?,
        hasTimeStyle: Boolean,
        timeStyle: String?,
    ): String {
        val dateFields = if (dateStyle == "full") "date,weekday" else "date"
        if (dateStyle != null && hasTimeStyle) {
            return if (timeStyle == "long" || timeStyle == "full") "$dateFields,time,zone" else "$dateFields,time"
        }
        if (dateStyle != null) {
            return dateFields
        }
        if (hasTimeStyle) {
            return if (timeStyle == "long" || timeStyle == "full") "time,zone" else "time"
        }
        return ""
    }

    private fun semanticNormalizeOptionKey(value: String): String {
        val normalized = semanticNormalize(value)
        if (normalized == "style" || normalized == "datestyle" || normalized == "datelength") {
            return "length"
        }
        if (normalized == "precision") {
            return "timeprecision"
        }
        if (normalized == "timestyle") {
            return "timestyle"
        }
        if (normalized == "hour12") {
            return "hourcycle"
        }
        if (normalized == "zone" || normalized == "timezonename" || normalized == "timezonestyle") {
            return "zonestyle"
        }
        if (normalized == "fractionalseconddigits") {
            return "fractionalsecond"
        }
        when (normalized) {
            "era" -> return "erastyle"
            "year" -> return "yearstyle"
            "month" -> return "monthstyle"
            "quarter" -> return "quarterstyle"
            "day" -> return "daystyle"
            "weekday" -> return "weekdaystyle"
            "dayperiod" -> return "dayperiodstyle"
            "hour" -> return "hourstyle"
            "minute" -> return "minutestyle"
            "second" -> return "secondstyle"
        }
        return normalized
    }

    private fun semanticNormalizeOptionValue(key: String, value: String): String {
        if (key == "fields") {
            return value.trim().lowercase(Locale.ROOT)
        }
        val normalized = semanticNormalize(value)
        if (key == "yearstyle" && normalized == "withera") {
            return "with-era"
        }
        if (key in semanticStyleOptionKeys && (normalized == "2digit" || normalized == "twodigit")) {
            return "2-digit"
        }
        if (key in semanticStyleOptionKeys && normalized == "wide") {
            return "long"
        }
        if (key in semanticStyleOptionKeys && normalized == "abbreviated") {
            return "short"
        }
        if (key == "timeprecision" && normalized == "short") {
            return "minute"
        }
        if (key == "timeprecision" && normalized == "medium") {
            return "second"
        }
        if (key == "timeprecision" && normalized == "minuteoptional") {
            return "minute-optional"
        }
        if (key == "timeprecision" && normalized == "fractionalsecond") {
            return "fractional-second"
        }
        if (key == "zonestyle" && (normalized == "shortoffset" || normalized == "longoffset")) {
            return "offset"
        }
        if (key == "zonestyle" && (normalized == "shortgeneric" || normalized == "longgeneric")) {
            return "generic"
        }
        if (key == "zonestyle" && (normalized == "short" || normalized == "long")) {
            return "specific"
        }
        if (key == "hourcycle" && normalized == "true") {
            return "clock12"
        }
        if (key == "hourcycle" && normalized == "false") {
            return "clock24"
        }
        return normalized
    }

    private fun parseSemanticSkeletonFields(options: Map<String, String>): List<String> {
        val fieldsText = options["fields"] ?: throw Mf2Error.badOption("Date/time semantic skeleton must include fields.")
        val fields = mutableListOf<String>()
        for (field in fieldsText.split(",")) {
            val normalized = semanticNormalizeField(field)
            val canonicalFields =
                if (normalized == "date" || normalized == "yearmonthday") {
                    listOf("year", "month", "day")
                } else if (normalized == "eradate" || normalized == "erayearmonthday") {
                    listOf("era", "year", "month", "day")
                } else if (normalized == "eradateweekday" || normalized == "weekdayeradate" || normalized == "erayearmonthdayweekday" || normalized == "weekdayerayearmonthday") {
                    listOf("era", "year", "month", "day", "weekday")
                } else if (normalized == "eradatetime" || normalized == "erayearmonthdaytime") {
                    listOf("era", "year", "month", "day", "time")
                } else if (normalized == "eradatetimeweekday" || normalized == "weekdayeradatetime" || normalized == "erayearmonthdaytimeweekday" || normalized == "weekdayerayearmonthdaytime") {
                    listOf("era", "year", "month", "day", "weekday", "time")
                } else if (normalized == "datetime" || normalized == "yearmonthdaytime") {
                    listOf("year", "month", "day", "time")
                } else if (normalized == "datetimeweekday" || normalized == "weekdaydatetime" || normalized == "yearmonthdaytimeweekday" || normalized == "weekdayyearmonthdaytime") {
                    listOf("year", "month", "day", "weekday", "time")
                } else if (normalized == "datetimeweekdayzone" || normalized == "weekdaydatetimezone" || normalized == "zoneddatetimeweekday" || normalized == "zonedweekdaydatetime" || normalized == "yearmonthdaytimeweekdayzone" || normalized == "weekdayyearmonthdaytimezone" || normalized == "zonedyearmonthdaytimeweekday" || normalized == "zonedweekdayyearmonthdaytime") {
                    listOf("year", "month", "day", "weekday", "time", "zone")
                } else if (normalized == "eradatetimezone" || normalized == "zonederadatetime" || normalized == "erayearmonthdaytimezone" || normalized == "zonederayearmonthdaytime") {
                    listOf("era", "year", "month", "day", "time", "zone")
                } else if (normalized == "eradatetimeweekdayzone" || normalized == "weekdayeradatetimezone" || normalized == "zonederadatetimeweekday" || normalized == "zonedweekdayeradatetime" || normalized == "erayearmonthdaytimeweekdayzone" || normalized == "weekdayerayearmonthdaytimezone" || normalized == "zonederayearmonthdaytimeweekday" || normalized == "zonedweekdayerayearmonthdaytime") {
                    listOf("era", "year", "month", "day", "weekday", "time", "zone")
                } else if (normalized == "dateweekday" || normalized == "weekdaydate" || normalized == "yearmonthdayweekday" || normalized == "weekdayyearmonthday") {
                    listOf("year", "month", "day", "weekday")
                } else if (normalized == "datetimezone" || normalized == "zoneddatetime" || normalized == "yearmonthdaytimezone" || normalized == "zonedyearmonthdaytime") {
                    listOf("year", "month", "day", "time", "zone")
                } else if (normalized == "yearmonth") {
                    listOf("year", "month")
                } else if (normalized == "erayearmonth") {
                    listOf("era", "year", "month")
                } else if (normalized == "yearquarter") {
                    listOf("year", "quarter")
                } else if (normalized == "erayearquarter") {
                    listOf("era", "year", "quarter")
                } else if (normalized == "yearweek") {
                    listOf("year", "weekofyear")
                } else if (normalized == "erayearweek") {
                    listOf("era", "year", "weekofyear")
                } else if (normalized == "erayear") {
                    listOf("era", "year")
                } else if (normalized == "monthweek") {
                    listOf("month", "weekofmonth")
                } else if (normalized == "yearmonthweek") {
                    listOf("year", "month", "weekofmonth")
                } else if (normalized == "erayearmonthweek") {
                    listOf("era", "year", "month", "weekofmonth")
                } else if (normalized == "monthday") {
                    listOf("month", "day")
                } else {
                    listOf(normalized)
                }
            for (canonical in canonicalFields) {
                if (canonical !in semanticFieldOrder || canonical in fields) {
                    throw Mf2Error.badOption("Invalid date/time semantic skeleton field.")
                }
                fields.add(canonical)
            }
        }
        if (fields.isEmpty()) {
            throw Mf2Error.badOption("Date/time semantic skeleton must include fields.")
        }
        return fields
    }

    private fun semanticNormalizeField(value: String): String {
        val normalized = semanticNormalize(value)
        if (normalized == "dayofmonth") {
            return "day"
        }
        if (normalized == "dayofweek") {
            return "weekday"
        }
        if (normalized == "monthofyear") {
            return "month"
        }
        if (normalized == "quarterofyear") {
            return "quarter"
        }
        if (normalized == "yearofera") {
            return "year"
        }
        if (normalized == "week") {
            return "weekofyear"
        }
        if (normalized == "weekofyear") {
            return "weekofyear"
        }
        if (normalized == "weekofmonth") {
            return "weekofmonth"
        }
        if (normalized == "dayofyear") {
            return "dayofyear"
        }
        if (normalized == "dayofweekinmonth") {
            return "dayofweekinmonth"
        }
        if (normalized == "modifiedjulianday") {
            return "modifiedjulianday"
        }
        if (normalized == "millisecondsinday") {
            return "millisecondsinday"
        }
        if (normalized == "fractionalseconddigits") {
            return "fractionalsecond"
        }
        if (normalized == "dayperiod") {
            return "dayperiod"
        }
        if (normalized == "hourofday") {
            return "hour"
        }
        if (normalized == "minuteofhour") {
            return "minute"
        }
        if (normalized == "secondofminute") {
            return "second"
        }
        if (normalized == "timezonename") {
            return "zone"
        }
        if (normalized == "timezone") {
            return "zone"
        }
        return normalized
    }

    private fun validateSemanticSkeleton(fields: List<String>, options: Map<String, String>) {
        val dateKey = semanticFieldSetKey(fields, semanticDateFieldOrder)
        val timeKey = semanticFieldSetKey(fields, semanticTimeFieldOrder)
        val hasDateFields = dateKey.isNotEmpty()
        val hasExplicitTime = timeKey.isNotEmpty()
        val hasTime = "time" in fields || hasExplicitTime
        val hasZone = "zone" in fields
        val hasDayPeriod = "dayperiod" in fields
        val validDateFields =
            if (hasTime || hasZone) {
                !hasDateFields || dateKey in semanticDateFieldSets
            } else {
                !hasDateFields || dateKey in semanticDateFieldSets || dateKey in semanticCalendarPeriodFieldSets
            }
        val validFieldSet =
            if (hasDayPeriod) {
                validDateFields && (hasTime || !hasZone)
            } else if (hasTime || hasZone) {
                !hasDateFields || dateKey in semanticDateFieldSets
            } else {
                dateKey in semanticDateFieldSets || dateKey in semanticCalendarPeriodFieldSets
            }
        if (!validFieldSet) {
            throw Mf2Error.badOption("Invalid date/time semantic skeleton field set.")
        }
        if ("time" in fields && hasExplicitTime) {
            throw Mf2Error.badOption("time field cannot be combined with explicit time component fields.")
        }
        if ("timestyle" in options && "timeprecision" in options) {
            throw Mf2Error.badOption("timeStyle cannot be combined with timePrecision.")
        }
        val timeStyle = options["timestyle"]
        if ("timestyle" in options && "time" !in fields) {
            throw Mf2Error.badOption("timeStyle requires the time field.")
        }
        if (semanticTimeStyleHasZone(timeStyle) && !hasZone) {
            throw Mf2Error.badOption("timeStyle=long/full requires the zone field.")
        }
        if (semanticTimeStyleHasZone(timeStyle) && "zonestyle" in options) {
            throw Mf2Error.badOption("timeStyle=long/full cannot be combined with zoneStyle.")
        }
        if (hasExplicitTime && timeKey !in semanticTimeFieldSets) {
            throw Mf2Error.badOption("Invalid date/time semantic skeleton time field set.")
        }
        if (hasExplicitTime && "timeprecision" in options) {
            throw Mf2Error.badOption("timePrecision requires the time field.")
        }
        if (hasExplicitTime && "fractionalsecond" in options && "fractionalsecond" !in fields) {
            throw Mf2Error.badOption("fractionalSecond requires the fractionalSecond field.")
        }
        if ("fractionalsecond" in fields) {
            semanticFractionalSecondWidth(options)
        }
        if (hasExplicitTime && "hour" !in fields && ("hourcycle" in options || hasDayPeriod)) {
            throw Mf2Error.badOption("hourCycle and dayPeriod require the hour field.")
        }
        if ("hour" !in fields && "hourstyle" in options) {
            throw Mf2Error.badOption("hourStyle requires the hour field.")
        }
        if ("minute" !in fields && "minutestyle" in options) {
            throw Mf2Error.badOption("minuteStyle requires the minute field.")
        }
        if ("second" !in fields && "secondstyle" in options) {
            throw Mf2Error.badOption("secondStyle requires the second field.")
        }
        if ("year" !in fields && "yearstyle" in options) {
            throw Mf2Error.badOption("yearStyle requires the year field.")
        }
        if ("era" !in fields && "erastyle" in options) {
            throw Mf2Error.badOption("eraStyle requires the era field.")
        }
        if ("month" !in fields && "monthstyle" in options) {
            throw Mf2Error.badOption("monthStyle requires the month field.")
        }
        if ("quarter" !in fields && "quarterstyle" in options) {
            throw Mf2Error.badOption("quarterStyle requires the quarter field.")
        }
        if ("day" !in fields && "daystyle" in options) {
            throw Mf2Error.badOption("dayStyle requires the day field.")
        }
        if ("weekday" !in fields && "weekdaystyle" in options) {
            throw Mf2Error.badOption("weekdayStyle requires the weekday field.")
        }
        if (!hasDayPeriod && "dayperiodstyle" in options) {
            throw Mf2Error.badOption("dayPeriodStyle requires the dayPeriod field.")
        }
        if (!hasTime && ("timeprecision" in options || "timestyle" in options || "fractionalsecond" in options || "hourcycle" in options)) {
            throw Mf2Error.badOption("timePrecision and hourCycle require the time field.")
        }
        if (!hasZone && "zonestyle" in options) {
            throw Mf2Error.badOption("zoneStyle requires the zone field.")
        }
        if (!(fields.any { it == "year" || it == "quarter" || it == "month" || it == "day" || it == "dayofyear" || it == "dayofweekinmonth" || it == "modifiedjulianday" } || hasTime) && "alignment" in options) {
            throw Mf2Error.badOption("alignment requires a date or time field.")
        }
    }

    private fun semanticOption(
        options: Map<String, String>,
        key: String,
        fallback: String,
        allowedValues: Set<String>,
    ): String {
        val value = options[key] ?: fallback
        if (value !in allowedValues) {
            throw Mf2Error.badOption("Date/time semantic skeleton $key must be one of ${allowedValues.joinToString(", ")}.")
        }
        return value
    }

    private fun semanticNormalize(value: String): String =
        value.trim().replace("-", "").replace("_", "").lowercase(Locale.ROOT)

    private fun semanticFieldSetKey(fields: List<String>, order: List<String>): String =
        order.filter { it in fields }.joinToString(",")

    private fun semanticDateFieldWidths(
        localeData: CldrDateTimeData.LocaleData,
        length: String,
    ): MutableMap<Char, Int> {
        val widths = mutableMapOf<Char, Int>()
        for ((symbol, width) in patternFieldRuns(localeData.dateFormats[length] ?: "")) {
            if (symbol == 'G' || isYearField(symbol) || isMonthField(symbol) || symbol == 'd') {
                setSkeletonWidth(widths, symbol, width)
            }
        }
        if (widths.keys.none(::isYearField)) {
            setSkeletonWidth(widths, 'y', if (length == "short") 2 else 1)
        }
        if (widths.keys.none(::isMonthField)) {
            setSkeletonWidth(widths, 'M', if (isWideLength(length)) 4 else if (length == "medium") 3 else 1)
        }
        widths.putIfAbsent('d', 1)
        return widths
    }

    private fun patternFieldRuns(pattern: String): Map<Char, Int> {
        val fields = mutableMapOf<Char, Int>()
        var inQuote = false
        var index = 0
        while (index < pattern.length) {
            val symbol = pattern[index]
            if (symbol == '\'') {
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') {
                    index += 2
                } else {
                    inQuote = !inQuote
                    index++
                }
            } else if (!inQuote && isAsciiLetter(symbol)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == symbol) {
                    end++
                }
                setSkeletonWidth(fields, symbol, end - index)
                index = end
            } else {
                index++
            }
        }
        return fields
    }

    private fun semanticEraSkeleton(dateWidths: Map<Char, Int>, length: String, eraStyle: String): String =
        "G".repeat(if (eraStyle == "auto") dateWidths['G'] ?: if (isWideLength(length)) 4 else 1 else eraStyleWidth(eraStyle))

    private fun eraStyleWidth(style: String): Int =
        when (style) {
            "long" -> 4
            "narrow" -> 5
            else -> 1
        }

    private fun semanticYearSkeleton(
        dateWidths: Map<Char, Int>,
        yearStyle: String,
        includeEra: Boolean = true,
    ): String {
        val yearSymbol = when {
            'y' in dateWidths -> 'y'
            'u' in dateWidths -> 'u'
            'r' in dateWidths -> 'r'
            else -> 'y'
        }
        val sourceWidth = dateWidths[yearSymbol] ?: 1
        val yearWidth = semanticYearWidth(sourceWidth, yearStyle)
        return buildString {
            if (includeEra) {
                dateWidths['G']?.let { repeat(it) { append('G') } }
            }
            if (includeEra && yearStyle == "with-era" && 'G' !in dateWidths) append('G')
            repeat(yearWidth) { append(yearSymbol) }
        }
    }

    private fun semanticYearWidth(sourceWidth: Int, yearStyle: String): Int =
        when (yearStyle) {
            "auto" -> sourceWidth
            "2-digit" -> 2
            "numeric" -> 1
            else -> if (sourceWidth == 2) 1 else sourceWidth
        }

    private fun semanticQuarterSkeleton(
        fields: List<String>,
        length: String,
        alignment: String,
        quarterStyle: String,
    ): String {
        val symbol = if (fields.size == 1) 'q' else 'Q'
        var width = if (quarterStyle == "auto") lengthStyleWidth(length) else dateFieldStyleWidth(quarterStyle)
        if (alignment == "column" && width < 3) {
            width = maxOf(width, 2)
        }
        return symbol.toString().repeat(width)
    }

    private fun semanticMonthSkeleton(
        fields: List<String>,
        dateWidths: Map<Char, Int>,
        length: String,
        alignment: String,
        monthStyle: String,
    ): String {
        val symbol: Char
        var width: Int
        if (fields.size == 1) {
            symbol = 'L'
            width = if (monthStyle == "auto") lengthStyleWidth(length) else dateFieldStyleWidth(monthStyle)
        } else {
            symbol =
                when {
                    'M' in dateWidths -> 'M'
                    'L' in dateWidths -> 'L'
                    else -> 'M'
                }
            width = if (monthStyle == "auto") dateWidths[symbol] ?: lengthStyleWidth(length) else dateFieldStyleWidth(monthStyle)
        }
        if (alignment == "column" && width < 3) {
            width = maxOf(width, 2)
        }
        return symbol.toString().repeat(width)
    }

    private fun lengthStyleWidth(length: String): Int =
        if (isWideLength(length)) 4 else if (length == "medium") 3 else 1

    private fun isWideLength(length: String): Boolean =
        length == "full" || length == "long"

    private fun dateFieldStyleWidth(style: String): Int =
        when (style) {
            "numeric" -> 1
            "2-digit" -> 2
            "short" -> 3
            "long" -> 4
            else -> 5
        }

    private fun semanticDaySkeleton(dateWidths: Map<Char, Int>, alignment: String, dayStyle: String): String {
        var width = if (dayStyle == "auto") dateWidths['d'] ?: 1 else dateFieldStyleWidth(dayStyle)
        if (alignment == "column" && width < 3) {
            width = maxOf(width, 2)
        }
        return "d".repeat(width)
    }

    private fun semanticWeekdaySkeleton(fields: List<String>, length: String, weekdayStyle: String): String =
        when {
            weekdayStyle == "short" -> "EEE"
            weekdayStyle == "long" -> "EEEE"
            weekdayStyle == "narrow" -> "EEEEE"
            fields.size == 1 && length == "short" -> "EEEEE"
            isWideLength(length) -> "EEEE"
            else -> "EEE"
        }

    private fun semanticDayPeriodSkeleton(length: String, dayPeriodStyle: String): String {
        val style = if (dayPeriodStyle == "auto") length else dayPeriodStyle
        return "B".repeat(
            if (isWideLength(style)) {
                4
            } else if (style == "narrow" || (dayPeriodStyle == "auto" && length == "short")) {
                5
            } else {
                1
            },
        )
    }

    private fun hasSemanticTimeComponents(fields: List<String>): Boolean =
        "hour" in fields || "minute" in fields || "second" in fields || "fractionalsecond" in fields || "millisecondsinday" in fields

    private fun semanticExplicitTimeSkeleton(
        fields: List<String>,
        hourCycle: String,
        alignment: String,
        options: Map<String, String>,
    ): String {
        val hasHour = "hour" in fields
        val hasMinute = "minute" in fields
        val hasSecond = "second" in fields
        val hasFractionalSecond = "fractionalsecond" in fields
        val hasMillisecondsInDay = "millisecondsinday" in fields
        return buildString {
            if (hasHour) append(semanticHourSymbol(hourCycle).toString().repeat(semanticNumericFieldWidth(options, "hourstyle", if (alignment == "column") 2 else 1)))
            if (hasMinute) append("m".repeat(semanticNumericFieldWidth(options, "minutestyle", if (!hasHour && !hasSecond && alignment == "column") 2 else 1)))
            if (hasSecond) append("s".repeat(semanticNumericFieldWidth(options, "secondstyle", if (!hasHour && !hasMinute && alignment == "column") 2 else 1)))
            if (hasFractionalSecond) append("S".repeat(semanticFractionalSecondWidth(options)))
            if (hasMillisecondsInDay) append("A".repeat(if (alignment == "column") 8 else 1))
        }
    }

    private fun semanticNumericFieldWidth(options: Map<String, String>, key: String, fallbackWidth: Int): Int =
        when (options[key] ?: "auto") {
            "auto" -> fallbackWidth
            "2-digit" -> 2
            else -> 1
        }

    private fun semanticFractionalSecondWidth(options: Map<String, String>): Int {
        val text = options["fractionalsecond"]
        if (text == null || text.length != 1 || text[0] !in '1'..'9') {
            throw Mf2Error.badOption("Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.")
        }
        return text[0] - '0'
    }

    private fun semanticTimeSkeleton(
        timePrecision: String,
        hourCycle: String,
        alignment: String,
        date: ZonedDateTime,
        options: Map<String, String>,
    ): String {
        val skeleton = StringBuilder(semanticHourSymbol(hourCycle).toString().repeat(if (alignment == "column") 2 else 1))
        if (timePrecision in setOf("minute", "second", "fractional-second")) {
            skeleton.append('m')
        }
        if (timePrecision == "minute-optional" && date.minute != 0) {
            skeleton.append('m')
        }
        if (timePrecision in setOf("second", "fractional-second")) {
            skeleton.append('s')
        }
        if (timePrecision == "fractional-second") {
            skeleton.append("S".repeat(semanticFractionalSecondWidth(options)))
        } else if ("fractionalsecond" in options) {
            throw Mf2Error.badOption("fractionalSecond requires timePrecision=fractional-second.")
        }
        return skeleton.toString()
    }

    private fun semanticTimeStylePrecision(timeStyle: String, timePrecision: String): String =
        when (timeStyle) {
            "short" -> "minute"
            "medium", "long", "full" -> "second"
            else -> timePrecision
        }

    private fun semanticTimeStyleZoneStyle(timeStyle: String, zoneStyle: String): String =
        if (semanticTimeStyleHasZone(timeStyle)) "specific" else zoneStyle

    private fun semanticTimeStyleHasZone(timeStyle: String?): Boolean =
        timeStyle == "long" || timeStyle == "full"

    private fun semanticHourSymbol(hourCycle: String): Char =
        when (hourCycle) {
            "h11" -> 'K'
            "h12", "clock12" -> 'h'
            "h23", "clock24" -> 'H'
            "h24" -> 'k'
            else -> 'C'
        }

    private fun semanticZoneSkeleton(zoneStyle: String, standalone: Boolean, length: String): String {
        val style = if (zoneStyle == "auto") "generic" else zoneStyle
        return when (style) {
            "specific" -> if (standalone && length != "short") "zzzz" else "z"
            "location" -> "VVVV"
            "offset" -> "O"
            else -> if (standalone && length != "short") "vvvv" else "v"
        }
    }

    private fun applyCHourFormat(
        widths: MutableMap<Char, Int>,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
        width: Int,
    ) {
        if (hourCycle != null) {
            val hourSymbol = preferredHourSymbol(localeData, hourCycle)
            setSkeletonWidth(widths, hourSymbol, cHourWidth(width))
            if (isHour12Field(hourSymbol)) {
                setSkeletonWidth(widths, 'B', dayPeriodWidthForC(width))
            }
            return
        }
        for (token in localeData.allowedHourFormats.split(Regex("\\s+"))) {
            if (!isCHourFormatToken(token)) {
                continue
            }
            setSkeletonWidth(widths, token[0], cHourWidth(width))
            if (token.length > 1) {
                setSkeletonWidth(widths, token[1], dayPeriodWidthForC(width))
            }
            return
        }
        setSkeletonWidth(widths, preferredHourSymbol(localeData, hourCycle), cHourWidth(width))
    }

    private fun isCHourFormatToken(token: String): Boolean =
        Regex("[hHkK][bB]?").matches(token)

    private fun setSkeletonWidth(
        widths: MutableMap<Char, Int>,
        symbol: Char,
        width: Int,
    ) {
        widths[symbol] = maxOf(widths[symbol] ?: 0, width)
    }

    private fun normalizeSkeletonSymbol(
        symbol: Char,
        localeData: CldrDateTimeData.LocaleData,
        hourCycle: String?,
    ): Char =
        when (symbol) {
            'l' -> 'L'
            'j', 'J' -> preferredHourSymbol(localeData, hourCycle)
            else -> symbol
        }

    private fun cHourWidth(width: Int): Int = if (width % 2 == 0) 2 else 1

    private fun dayPeriodWidthForC(width: Int): Int =
        when {
            width >= 5 -> 5
            width >= 3 -> 4
            else -> 1
        }

    private fun shouldSuppressDayPeriod(skeleton: String): Boolean =
        'J' in skeleton && 'a' !in skeleton && 'b' !in skeleton && 'B' !in skeleton && 'C' !in skeleton

    private fun stripDayPeriodPatternFields(pattern: String): String {
        val output = StringBuilder()
        val pendingWhitespace = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\'') {
                val quoted = readQuotedPattern(pattern, index)
                output.append(pendingWhitespace).append(pattern.substring(index, quoted.nextIndex))
                pendingWhitespace.clear()
                index = quoted.nextIndex
            } else if (isAsciiLetter(ch)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == ch) {
                    end++
                }
                if (isDayPeriodField(ch)) {
                    pendingWhitespace.clear()
                } else {
                    output.append(pendingWhitespace).append(pattern.substring(index, end))
                    pendingWhitespace.clear()
                }
                index = end
            } else if (isPatternWhitespace(ch)) {
                pendingWhitespace.append(ch)
                index++
            } else {
                output.append(pendingWhitespace).append(ch)
                pendingWhitespace.clear()
                index++
            }
        }
        output.append(pendingWhitespace)
        return output.toString().trim { isPatternWhitespace(it) }
    }

    private fun isPatternWhitespace(value: Char): Boolean =
        value == ' ' || value == '\u00A0' || value == '\u202F' || value.isWhitespace()

    private fun preferredHourSymbol(localeData: CldrDateTimeData.LocaleData, hourCycle: String?): Char {
        when (hourCycle) {
            "h11" -> return 'K'
            "h12" -> return 'h'
            "h23" -> return 'H'
            "h24" -> return 'k'
        }
        val shortTime = localeData.timeFormats["short"].orEmpty()
        return when {
            'H' in shortTime -> 'H'
            'k' in shortTime -> 'k'
            'K' in shortTime -> 'K'
            else -> 'h'
        }
    }

    private fun skeletonFieldSet(skeleton: String): String =
        buildString {
            val normalized = skeletonWidths(skeleton).keys.map { fieldSetSymbol(it) }
            for (symbol in SKELETON_FIELD_ORDER) {
                if (symbol in normalized) {
                    append(symbol)
                }
            }
        }

    private fun fieldSetSymbol(symbol: Char): Char =
        when {
            isYearField(symbol) -> 'y'
            isHourField(symbol) -> 'J'
            isMonthField(symbol) -> 'M'
            isQuarterField(symbol) -> 'Q'
            isDayPeriodField(symbol) -> 'B'
            isWeekdayField(symbol) -> 'E'
            isTimeZoneField(symbol) -> 'v'
            else -> symbol
        }

    private fun skeletonDistance(
        requested: String,
        candidate: String,
    ): Int {
        val requestedWidths = skeletonWidths(requested)
        val candidateWidths = skeletonWidths(candidate)
        var distance = 0
        for ((symbol, requestedWidth) in requestedWidths) {
            val candidateSymbol = candidateSymbolForRequested(symbol, candidateWidths)
            val candidateWidth = candidateSymbol?.let { candidateWidths.getValue(it) } ?: 0
            distance += kotlin.math.abs(requestedWidth - candidateWidth)
            if (isTextWidth(requestedWidth) != isTextWidth(candidateWidth)) {
                distance += 8
            }
            distance += hourFieldDistance(symbol, candidateSymbol)
        }
        return distance
    }

    private fun skeletonWidths(skeleton: String): Map<Char, Int> {
        val widths = mutableMapOf<Char, Int>()
        var index = 0
        while (index < skeleton.length) {
            val symbol = skeleton[index]
            var end = index + 1
            while (end < skeleton.length && skeleton[end] == symbol) {
                end++
            }
            widths[symbol] = maxOf(widths[symbol] ?: 0, end - index)
            index = end
        }
        return widths
    }

    private fun isTextWidth(width: Int): Boolean = width >= 3

    private fun isHourField(symbol: Char): Boolean = symbol in SKELETON_HOUR_FIELDS

    private fun isYearField(symbol: Char): Boolean = symbol == 'y' || symbol == 'u' || symbol == 'r'

    private fun isWeekdayField(symbol: Char): Boolean = symbol == 'E' || symbol == 'e' || symbol == 'c'

    private fun isMonthField(symbol: Char): Boolean = symbol == 'M' || symbol == 'L'

    private fun isQuarterField(symbol: Char): Boolean = symbol == 'Q' || symbol == 'q'

    private fun isDayPeriodField(symbol: Char): Boolean = symbol == 'a' || symbol == 'b' || symbol == 'B'

    private fun isSyntheticNumericField(symbol: Char): Boolean = symbol == 'D' || symbol == 'F' || symbol == 'g' || symbol == 'm' || symbol == 's' || symbol == 'A'

    private fun isTimeZoneField(symbol: Char): Boolean = symbol in "zZOvVXx"

    private fun candidateSymbolForRequested(
        symbol: Char,
        candidateWidths: Map<Char, Int>,
    ): Char? {
        if (symbol in candidateWidths) {
            return symbol
        }
        if (isYearField(symbol)) {
            return listOf('y', 'u', 'r').firstOrNull { it in candidateWidths }
        }
        if (isHourField(symbol)) {
            return SKELETON_HOUR_FIELDS.firstOrNull { it in candidateWidths }
        }
        if (isQuarterField(symbol)) {
            return listOf('Q', 'q').firstOrNull { it in candidateWidths }
        }
        if (isMonthField(symbol)) {
            return listOf('M', 'L').firstOrNull { it in candidateWidths }
        }
        if (isDayPeriodField(symbol)) {
            return listOf('B', 'b', 'a').firstOrNull { it in candidateWidths }
        }
        if (isWeekdayField(symbol)) {
            return listOf('E', 'e', 'c').firstOrNull { it in candidateWidths }
        }
        if (isTimeZoneField(symbol)) {
            return listOf('v', 'z', 'O', 'Z', 'X', 'x', 'V').firstOrNull { it in candidateWidths }
        }
        return null
    }

    private fun hourFieldDistance(
        requestedSymbol: Char,
        candidateSymbol: Char?,
    ): Int {
        if (
            candidateSymbol == null ||
            requestedSymbol == candidateSymbol ||
            !isHourField(requestedSymbol) ||
            !isHourField(candidateSymbol)
        ) {
            return 0
        }
        return if (isHour12Field(requestedSymbol) == isHour12Field(candidateSymbol)) 1 else 4
    }

    private fun isHour12Field(symbol: Char): Boolean = symbol == 'h' || symbol == 'K'

    private fun requestedSymbolForPattern(
        symbol: Char,
        requestedWidths: Map<Char, Int>,
        candidateWidths: Map<Char, Int>,
    ): Char {
        if (isYearField(symbol)) {
            return if (candidateSymbolForRequested(symbol, candidateWidths) == null) {
                symbol
            } else {
                candidateSymbolForRequested(symbol, requestedWidths) ?: symbol
            }
        }
        if (isWeekdayField(symbol)) {
            return if (candidateSymbolForRequested(symbol, candidateWidths) == null) {
                symbol
            } else {
                requestedWeekdaySymbolForPattern(symbol, requestedWidths)
            }
        }
        if (isDayPeriodField(symbol)) {
            return if (candidateSymbolForRequested(symbol, candidateWidths) == null) {
                symbol
            } else {
                requestedDayPeriodSymbolForPattern(symbol, requestedWidths)
            }
        }
        if (isTimeZoneField(symbol)) {
            return if (candidateSymbolForRequested(symbol, candidateWidths) == null) {
                symbol
            } else {
                requestedTimeZoneSymbolForPattern(symbol, requestedWidths)
            }
        }
        if ((!isYearField(symbol) && !isHourField(symbol) && !isMonthField(symbol) && !isQuarterField(symbol) && !isDayPeriodField(symbol) && !isTimeZoneField(symbol)) || candidateSymbolForRequested(symbol, candidateWidths) == null) {
            return symbol
        }
        return candidateSymbolForRequested(symbol, requestedWidths) ?: symbol
    }

    private fun requestedWeekdaySymbolForPattern(
        symbol: Char,
        requestedWidths: Map<Char, Int>,
    ): Char =
        when {
            'c' in requestedWidths -> 'c'
            'e' in requestedWidths -> 'e'
            'E' in requestedWidths -> 'E'
            else -> symbol
        }

    private fun requestedDayPeriodSymbolForPattern(
        symbol: Char,
        requestedWidths: Map<Char, Int>,
    ): Char =
        when {
            'a' in requestedWidths -> 'a'
            'b' in requestedWidths -> 'b'
            'B' in requestedWidths -> 'B'
            else -> symbol
        }

    private fun requestedTimeZoneSymbolForPattern(
        symbol: Char,
        requestedWidths: Map<Char, Int>,
    ): Char {
        for (timeZoneSymbol in listOf('z', 'Z', 'O', 'v', 'V', 'X', 'x')) {
            if (timeZoneSymbol in requestedWidths) {
                return timeZoneSymbol
            }
        }
        return symbol
    }

    private fun widthForPatternSymbol(symbol: Char, widths: Map<Char, Int>): Int? {
        widths[symbol]?.let { return it }
        if (isYearField(symbol)) {
            for (yearSymbol in listOf('y', 'u', 'r')) {
                widths[yearSymbol]?.let { return it }
            }
        }
        if (isWeekdayField(symbol)) {
            for (weekdaySymbol in listOf('E', 'e', 'c')) {
                widths[weekdaySymbol]?.let { return it }
            }
        }
        if (isMonthField(symbol)) {
            for (monthSymbol in listOf('M', 'L')) {
                widths[monthSymbol]?.let { return it }
            }
        }
        if (isDayPeriodField(symbol)) {
            for (dayPeriodSymbol in listOf('B', 'b', 'a')) {
                widths[dayPeriodSymbol]?.let { return it }
            }
        }
        if (isQuarterField(symbol)) {
            for (quarterSymbol in listOf('Q', 'q')) {
                widths[quarterSymbol]?.let { return it }
            }
        }
        if (isTimeZoneField(symbol)) {
            for (timeZoneSymbol in listOf('z', 'Z', 'O', 'v', 'V', 'X', 'x')) {
                widths[timeZoneSymbol]?.let { return it }
            }
        }
        return null
    }

    private fun adjustPatternWidths(
        pattern: String,
        requestedSkeleton: String,
        candidateSkeleton: String,
    ): String {
        val requestedWidths = skeletonWidths(requestedSkeleton)
        val candidateWidths = skeletonWidths(candidateSkeleton)
        val output = StringBuilder()
        var inQuote = false
        var index = 0
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\'') {
                output.append(ch)
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') {
                    output.append('\'')
                    index += 2
                } else {
                    inQuote = !inQuote
                    index++
                }
            } else if (!inQuote && isAsciiLetter(ch)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == ch) {
                    end++
                }
                val requestedSymbol = requestedSymbolForPattern(ch, requestedWidths, candidateWidths)
                val requestedWidth = widthForPatternSymbol(ch, requestedWidths)
                val candidateWidth = widthForPatternSymbol(ch, candidateWidths)
                val patternWidth = end - index
                val width = if (shouldAdjustPatternWidth(requestedSymbol, requestedWidth, candidateWidth, patternWidth)) {
                    requestedWidth!!
                } else {
                    patternWidth
                }
                output.append(requestedSymbol.toString().repeat(width))
                index = end
            } else {
                output.append(ch)
                index++
            }
        }
        return output.toString()
    }

    private fun shouldAdjustPatternWidth(
        symbol: Char,
        requestedWidth: Int?,
        candidateWidth: Int?,
        patternWidth: Int,
    ): Boolean =
        requestedWidth != null &&
            candidateWidth != null &&
            (((symbol == 'e' || symbol == 'c') && patternWidth >= 3 && requestedWidth <= 2) ||
                (isWeekdayField(symbol) && patternWidth >= 3 && requestedWidth >= 4) ||
                patternWidth == candidateWidth)

    private fun splitDateTimeSkeleton(skeleton: String): Pair<String, String> {
        val dateSkeleton = StringBuilder()
        val timeSkeleton = StringBuilder()
        for (symbol in skeleton) {
            if (symbol in SKELETON_TIME_FIELDS) {
                timeSkeleton.append(symbol)
            } else {
                dateSkeleton.append(symbol)
            }
        }
        return dateSkeleton.toString() to timeSkeleton.toString()
    }

    private fun formatPattern(
        pattern: String,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): String {
        val output = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\'') {
                val quoted = readQuotedPattern(pattern, index)
                output.append(quoted.value)
                index = quoted.nextIndex
            } else if (isAsciiLetter(ch)) {
                var end = index + 1
                while (end < pattern.length && pattern[end] == ch) {
                    end++
                }
                output.append(formatField(ch, end - index, date, localeData))
                index = end
            } else {
                output.append(ch)
                index++
            }
        }
        return output.toString()
    }

    private fun readQuotedPattern(pattern: String, start: Int): QuotedPattern {
        if (start + 1 < pattern.length && pattern[start + 1] == '\'') {
            return QuotedPattern("'", start + 2)
        }
        val value = StringBuilder()
        var index = start + 1
        while (index < pattern.length) {
            if (pattern[index] == '\'') {
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') {
                    value.append('\'')
                    index += 2
                } else {
                    return QuotedPattern(value.toString(), index + 1)
                }
            } else {
                value.append(pattern[index])
                index++
            }
        }
        return QuotedPattern(value.toString(), index)
    }

    private fun formatField(
        symbol: Char,
        count: Int,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): String =
        when (symbol) {
            'G' -> eraName(date, localeData, count)
            'y' -> yearValue(date, localeData, count)
            'u' -> extendedYearValue(date, localeData, count)
            'r' -> extendedYearValue(date, localeData, count)
            'Y' -> weekYearValue(date, localeData, count)
            'Q', 'q' -> quarterValue(date, localeData, count, symbol == 'q')
            'M', 'L' -> monthValue(date, localeData, count, symbol == 'L')
            'd' -> integerValue(date.dayOfMonth, localeData, count)
            'D' -> integerValue(date.dayOfYear, localeData, count)
            'F' -> integerValue(dayOfWeekInMonth(date), localeData, count)
            'g' -> integerValue(modifiedJulianDay(date), localeData, count)
            'w' -> integerValue(weekOfYear(date, localeData), localeData, count)
            'W' -> integerValue(weekOfMonth(date, localeData), localeData, count)
            'E' -> weekdayName(date, localeData, count)
            'e' -> localWeekdayValue(date, localeData, count, false)
            'c' -> localWeekdayValue(date, localeData, count, true)
            'a', 'b', 'B' -> dayPeriodName(date, localeData, count, symbol)
            'H' -> integerValue(date.hour, localeData, count)
            'k' -> integerValue(if (date.hour == 0) 24 else date.hour, localeData, count)
            'h' -> integerValue(hour12(date), localeData, count)
            'K' -> integerValue(date.hour % 12, localeData, count)
            'm' -> integerValue(date.minute, localeData, count)
            's' -> integerValue(date.second, localeData, count)
            'S' -> fractionValue(date, localeData, count)
            'A' -> integerValue(millisecondsInDay(date), localeData, count)
            'z', 'Z', 'O', 'v', 'V', 'X', 'x' -> timeZoneValue(symbol, count, date, localeData)
            else -> throw Mf2Error.badOption("Unsupported CLDR date/time pattern field: $symbol.")
        }

    private fun timeZoneValue(
        symbol: Char,
        count: Int,
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): String =
        if (date.offset.totalSeconds != 0) {
            val offsetMinutes = date.offset.totalSeconds / 60
            when (symbol) {
                'X' -> isoOffset(offsetMinutes, count, true)
                'x' -> isoOffset(offsetMinutes, count, false)
                'V' ->
                    when (count) {
                        1 -> "unk"
                        2 -> fixedOffsetGmtId(localeData, offsetMinutes)
                        3 -> "Unknown Location"
                        else -> localizedGmtOffset(localeData, offsetMinutes, count)
                    }
                'Z' ->
                    when {
                        count <= 3 -> basicOffset(offsetMinutes)
                        count == 5 -> isoOffset(offsetMinutes, 3, true)
                        else -> localizedGmtOffset(localeData, offsetMinutes, count)
                    }
                else -> localizedGmtOffset(localeData, offsetMinutes, count)
            }
        } else {
            when (symbol) {
                'z' ->
                    if (count >= 4) {
                        localeData.timeZoneNames["utcLong"] ?: localeData.timeZoneNames["utcShort"] ?: UTC
                    } else {
                        localeData.timeZoneNames["utcShort"] ?: UTC
                    }
                'O', 'v' -> localizedGmtZero(localeData)
                'V' -> localizedGmtZero(localeData)
                'Z' ->
                    when {
                        count <= 3 -> "+0000"
                        count == 5 -> "Z"
                        else -> localizedGmtZero(localeData)
                    }
                'X' -> "Z"
                'x' -> if (count == 1) "+00" else if (count == 2 || count == 4) "+0000" else "+00:00"
                else -> UTC
            }
        }

    private fun localizedGmtZero(localeData: CldrDateTimeData.LocaleData): String =
        localeData.timeZoneNames["gmtZeroFormat"]
            ?: localeData.timeZoneNames.getOrDefault("gmtFormat", "GMT{0}").replace("{0}", "")

    private fun localizedGmtOffset(
        localeData: CldrDateTimeData.LocaleData,
        offsetMinutes: Int,
        count: Int,
    ): String {
        val formatted = if (count >= 4) extendedOffset(offsetMinutes, true) else shortOffset(offsetMinutes)
        return localeData.timeZoneNames
            .getOrDefault("gmtFormat", "GMT{0}")
            .replace("{0}", localizeDigits(formatted, localeData.numberingSystemDigits))
    }

    private fun fixedOffsetGmtId(
        localeData: CldrDateTimeData.LocaleData,
        offsetMinutes: Int,
    ): String = "GMT" + localizeDigits(extendedOffset(offsetMinutes, true), localeData.numberingSystemDigits)

    private fun isoOffset(
        offsetMinutes: Int,
        count: Int,
        useZeroZ: Boolean,
    ): String {
        if (offsetMinutes == 0 && useZeroZ) return "Z"
        if (count == 1) return shortIsoOffset(offsetMinutes)
        if (count == 2 || count == 4) return basicOffset(offsetMinutes)
        return extendedOffset(offsetMinutes, true)
    }

    private fun shortIsoOffset(offsetMinutes: Int): String {
        val parts = offsetParts(offsetMinutes)
        return if (parts.minutes == 0) {
            "${parts.sign}${parts.hours.toString().padStart(2, '0')}"
        } else {
            "${parts.sign}${parts.hours.toString().padStart(2, '0')}${parts.minutes.toString().padStart(2, '0')}"
        }
    }

    private fun shortOffset(offsetMinutes: Int): String {
        val parts = offsetParts(offsetMinutes)
        return if (parts.minutes == 0) {
            "${parts.sign}${parts.hours}"
        } else {
            "${parts.sign}${parts.hours}:${parts.minutes.toString().padStart(2, '0')}"
        }
    }

    private fun basicOffset(offsetMinutes: Int): String {
        val parts = offsetParts(offsetMinutes)
        return "${parts.sign}${parts.hours.toString().padStart(2, '0')}${parts.minutes.toString().padStart(2, '0')}"
    }

    private fun extendedOffset(
        offsetMinutes: Int,
        paddedHour: Boolean,
    ): String {
        val parts = offsetParts(offsetMinutes)
        val hour = if (paddedHour) parts.hours.toString().padStart(2, '0') else parts.hours.toString()
        return "${parts.sign}$hour:${parts.minutes.toString().padStart(2, '0')}"
    }

    private fun offsetParts(offsetMinutes: Int): OffsetParts {
        val sign = if (offsetMinutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(offsetMinutes)
        return OffsetParts(sign, absolute / 60, absolute % 60)
    }

    private fun eraName(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String {
        val era = if (date.year <= 0) "0" else "1"
        return nameByWidth(localeData.eras, widthForText(count), era)
    }

    private fun yearValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String {
        val year = date.year
        val yearOfEra = if (year <= 0) 1 - year else year
        if (count == 2) {
            return integerText(yearOfEra % 100, localeData, 2)
        }
        return localizeDigits(yearOfEra.toString(), localeData.numberingSystemDigits)
    }

    private fun extendedYearValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String = integerValue(date.year, localeData, count)

    private fun weekYearValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String {
        val weekYear = weekYearInfo(date, localeData).year
        if (count == 2) {
            return integerText(weekYear % 100, localeData, 2)
        }
        return localizeDigits(weekYear.toString(), localeData.numberingSystemDigits)
    }

    private fun monthValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Boolean,
    ): String {
        val month = date.monthValue
        if (count <= 2) {
            return integerValue(month, localeData, count)
        }
        val context = if (standAlone) "stand-alone" else "format"
        return contextualName(localeData.months, context, widthForText(count), month.toString())
    }

    private fun quarterValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Boolean,
    ): String {
        val quarter = (date.monthValue - 1) / 3 + 1
        if (count <= 2) {
            return integerValue(quarter, localeData, count)
        }
        val context = if (standAlone) "stand-alone" else "format"
        return contextualName(localeData.quarters, context, widthForText(count), quarter.toString())
    }

    private fun weekdayName(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String =
        contextualName(localeData.weekdays, "format", widthForWeekday(count), weekdayKeys[date.dayOfWeek.value % 7])

    private fun localWeekdayValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        standAlone: Boolean,
    ): String {
        val day = date.dayOfWeek.value % 7
        if (count <= 2) {
            val localDay = Math.floorMod(day - localeData.firstDayOfWeek, 7) + 1
            return integerValue(localDay, localeData, count)
        }
        return contextualName(
            localeData.weekdays,
            if (standAlone) "stand-alone" else "format",
            widthForWeekday(count),
            weekdayKeys[day],
        )
    }

    private fun dayOfWeekInMonth(date: ZonedDateTime): Int = ((date.dayOfMonth - 1) / 7) + 1

    private fun millisecondsInDay(date: ZonedDateTime): Int =
        ((date.hour * 60 + date.minute) * 60 + date.second) * 1000 + (date.nano / 1_000_000)

    private fun modifiedJulianDay(date: ZonedDateTime): Int =
        Math.toIntExact(date.toLocalDate().toEpochDay() + 40_587)

    private fun weekOfYear(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): Int = weekYearInfo(date, localeData).week

    private fun weekYearInfo(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): WeekYearInfo {
        val year = date.year
        val epochDay = date.toLocalDate().toEpochDay()
        val weekStart = startOfWeek(epochDay, localeData.firstDayOfWeek)
        val currentStart = firstWeekStartOfYear(year, localeData)
        val nextStart = firstWeekStartOfYear(year + 1, localeData)
        if (weekStart >= nextStart) {
            return WeekYearInfo(year + 1, 1)
        }
        if (weekStart < currentStart) {
            val previousYear = year - 1
            val previousStart = firstWeekStartOfYear(previousYear, localeData)
            return WeekYearInfo(previousYear, Math.floorDiv(weekStart - previousStart, 7).toInt() + 1)
        }
        return WeekYearInfo(year, Math.floorDiv(weekStart - currentStart, 7).toInt() + 1)
    }

    private fun weekOfMonth(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
    ): Int {
        val weekStart = startOfWeek(date.toLocalDate().toEpochDay(), localeData.firstDayOfWeek)
        val firstStart = firstWeekStart(date.toLocalDate().withDayOfMonth(1).toEpochDay(), localeData)
        return Math.floorDiv(weekStart - firstStart, 7).toInt() + 1
    }

    private fun firstWeekStartOfYear(
        year: Int,
        localeData: CldrDateTimeData.LocaleData,
    ): Long = firstWeekStart(LocalDate.of(year, 1, 1).toEpochDay(), localeData)

    private fun firstWeekStart(
        periodStart: Long,
        localeData: CldrDateTimeData.LocaleData,
    ): Long {
        val weekStart = startOfWeek(periodStart, localeData.firstDayOfWeek)
        val daysInPeriod = weekStart + 7 - periodStart
        return if (daysInPeriod >= localeData.minDaysInFirstWeek) weekStart else weekStart + 7
    }

    private fun startOfWeek(
        epochDay: Long,
        firstDay: Int,
    ): Long = epochDay - Math.floorMod(dayOfWeek(epochDay) - firstDay, 7)

    private fun dayOfWeek(epochDay: Long): Int = Math.floorMod(epochDay + 4, 7)

    private fun dayPeriodName(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
        symbol: Char,
    ): String {
        val period = dayPeriodKey(date, localeData, symbol)
        return contextualName(localeData.dayPeriods, "format", widthForDayPeriod(count), period)
    }

    private fun dayPeriodKey(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        symbol: Char,
    ): String {
        val fallback = if (date.hour < 12) "am" else "pm"
        if (symbol == 'a') {
            return fallback
        }
        if (symbol == 'b') {
            return selectDayPeriodRule(date, localeData.dayPeriodRules, exactOnly = true) ?: fallback
        }
        return selectDayPeriodRule(date, localeData.dayPeriodRules, exactOnly = false) ?: fallback
    }

    private fun selectDayPeriodRule(
        date: ZonedDateTime,
        encodedRules: String,
        exactOnly: Boolean,
    ): String? {
        if (encodedRules.isEmpty()) {
            return null
        }
        val minute = date.hour * 60 + date.minute
        val exactMinute = if (date.second == 0 && date.nano == 0) minute else -1
        var rangeMatch: String? = null
        for (rawRule in encodedRules.split(";")) {
            val separator = rawRule.indexOf('=')
            if (separator < 0) {
                continue
            }
            val period = rawRule.substring(0, separator)
            val span = rawRule.substring(separator + 1)
            val rangeSeparator = span.indexOf('-')
            if (rangeSeparator < 0) {
                if (span.toInt() == exactMinute) {
                    return period
                }
            } else if (!exactOnly) {
                val start = span.substring(0, rangeSeparator).toInt()
                val end = span.substring(rangeSeparator + 1).toInt()
                if (rangeMatch == null && minuteInDayPeriodRange(minute, start, end)) {
                    rangeMatch = period
                }
            }
        }
        return if (exactOnly) null else rangeMatch
    }

    private fun minuteInDayPeriodRange(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) {
            minute >= start && minute < end
        } else {
            minute >= start || minute < end
        }

    private fun hour12(date: ZonedDateTime): Int {
        val hour = date.hour % 12
        return if (hour == 0) 12 else hour
    }

    private fun fractionValue(
        date: ZonedDateTime,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String {
        val milliseconds = "%03d".format(Locale.ROOT, date.nano / 1_000_000)
        val value = (milliseconds + "000000000").substring(0, count)
        return localizeDigits(value, localeData.numberingSystemDigits)
    }

    private fun integerValue(
        value: Int,
        localeData: CldrDateTimeData.LocaleData,
        count: Int,
    ): String =
        integerText(value, localeData, if (count >= 2) count else 0)

    private fun integerText(
        value: Int,
        localeData: CldrDateTimeData.LocaleData,
        minimumDigits: Int,
    ): String {
        var text = kotlin.math.abs(value).toString()
        while (text.length < minimumDigits) {
            text = "0$text"
        }
        if (value < 0) {
            text = "-$text"
        }
        return localizeDigits(text, localeData.numberingSystemDigits)
    }

    private fun contextualName(
        source: Map<String, Map<String, Map<String, String>>>,
        context: String,
        width: String,
        key: String,
    ): String {
        val contextData = source[context] ?: source["format"] ?: source["stand-alone"] ?: return key
        return nameByWidth(contextData, width, key)
    }

    private fun nameByWidth(
        source: Map<String, Map<String, String>>,
        width: String,
        key: String,
    ): String =
        source[width]?.get(key)
            ?: source["abbreviated"]?.get(key)
            ?: source["wide"]?.get(key)
            ?: source["short"]?.get(key)
            ?: source["narrow"]?.get(key)
            ?: key

    private fun widthForText(count: Int): String =
        when (count) {
            4 -> "wide"
            5 -> "narrow"
            else -> "abbreviated"
        }

    private fun widthForWeekday(count: Int): String =
        when {
            count == 4 -> "wide"
            count == 5 -> "narrow"
            count >= 6 -> "short"
            else -> "abbreviated"
        }

    private fun widthForDayPeriod(count: Int): String =
        when {
            count == 4 -> "wide"
            count >= 5 -> "narrow"
            else -> "abbreviated"
        }

    private fun localizeDigits(value: String, digits: String?): String {
        if (digits == null || digits == "0123456789") {
            return value
        }
        val output = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '0'..'9') {
                output.append(digits[ch - '0'])
            } else {
                output.append(ch)
            }
        }
        return output.toString()
    }

    private fun isAsciiLetter(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z'

    private fun isAsciiDigit(ch: Char): Boolean =
        ch in '0'..'9'

    private fun styleKey(style: Style): String =
        style.name.lowercase(Locale.ROOT)

    private fun callStyle(
        call: Mf2FunctionCall,
        optionName: String,
        legacyOptionName: String,
        fallback: String,
        legacyTimePrecision: Boolean,
    ): Style {
        val absent = "\u0000mf2-absent-date-time-style"
        val shared = call.optionValue("style", absent) ?: absent
        val legacyValue = call.optionValue(legacyOptionName, absent) ?: absent
        val optionValue = call.optionValue(optionName, absent) ?: absent
        if (optionValue != absent) {
            return styleOption(optionValue)
        }
        if (legacyValue != absent) {
            return if (legacyTimePrecision) timePrecisionStyleOption(legacyValue) else styleOption(legacyValue)
        }
        if (shared != absent) {
            return styleOption(shared)
        }
        return styleOption(fallback)
    }

    private fun nonEmptyCallOption(
        call: Mf2FunctionCall,
        name: String,
        fallback: String?,
    ): String? {
        val value = call.optionValue(name, fallback)
        if (value == "") {
            throw Mf2Error.badOption("$name must not be empty.")
        }
        return value
    }

    private fun styleOption(value: String): Style {
        if (value.length > MAX_OPTION_LENGTH) {
            throw Mf2Error.badOption("date/time style must not exceed 256 characters.")
        }
        return when (value) {
            "full" -> Style.FULL
            "long" -> Style.LONG
            "medium" -> Style.MEDIUM
            "short" -> Style.SHORT
            else -> throw Mf2Error.badOption("date/time style must be full, long, medium, or short.")
        }
    }

    private fun timePrecisionStyleOption(value: String): Style =
        if (value == "second") Style.MEDIUM else styleOption(value)

    enum class Style {
        FULL,
        LONG,
        MEDIUM,
        SHORT,
    }

    data class Options(
        val locale: String = DEFAULT_LOCALE,
        val style: Style = Style.MEDIUM,
        val dateStyle: Style? = null,
        val timeStyle: Style? = null,
        val skeleton: String? = null,
        val hourCycle: String? = null,
        val timeZone: String = UTC,
        val calendar: String? = null,
    ) {
        fun effectiveDateStyle(): Style = dateStyle ?: style

        fun effectiveTimeStyle(): Style = timeStyle ?: style

        class Builder {
            private var locale = DEFAULT_LOCALE
            private var style = Style.MEDIUM
            private var dateStyle: Style? = null
            private var timeStyle: Style? = null
            private var skeleton: String? = null
            private var hourCycle: String? = null
            private var timeZone = UTC
            private var calendar: String? = null

            fun locale(value: String?) = apply {
                locale = value ?: DEFAULT_LOCALE
            }

            fun style(value: Style?) = apply {
                style = value ?: Style.MEDIUM
            }

            fun dateStyle(value: Style?) = apply {
                dateStyle = value
            }

            fun timeStyle(value: Style?) = apply {
                timeStyle = value
            }

            fun skeleton(value: String?) = apply {
                skeleton = value
            }

            fun hourCycle(value: String?) = apply {
                hourCycle = value
            }

            fun timeZone(value: String?) = apply {
                timeZone = value ?: UTC
            }

            fun calendar(value: String?) = apply {
                calendar = value
            }

            fun build(): Options =
                Options(
                    locale = locale,
                    style = style,
                    dateStyle = dateStyle,
                    timeStyle = timeStyle,
                    skeleton = skeleton,
                    hourCycle = hourCycle,
                    timeZone = timeZone,
                    calendar = calendar,
                )
        }
    }

    private data class QuotedPattern(val value: String, val nextIndex: Int)

    private data class OffsetParts(val sign: String, val hours: Int, val minutes: Int)

    private data class WeekYearInfo(val year: Int, val week: Int)
}
