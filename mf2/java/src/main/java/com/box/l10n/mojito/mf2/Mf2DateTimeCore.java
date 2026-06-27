package com.box.l10n.mojito.mf2;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class Mf2DateTimeCore {
    private static final String DEFAULT_LOCALE = "en-US";
    private static final String UTC = "UTC";
    private static final double MIN_TIMESTAMP_MS = -62_135_596_800_000D;
    private static final double MAX_TIMESTAMP_MS = 253_402_300_799_999D;
    private static final Instant MIN_INSTANT = Instant.ofEpochMilli((long) MIN_TIMESTAMP_MS);
    private static final Instant MAX_INSTANT = Instant.ofEpochMilli((long) MAX_TIMESTAMP_MS);
    private static final int MAX_OPTION_LENGTH = 256;
    private static final int MAX_OPERAND_LENGTH = 256;
    private static final int MAX_SKELETON_FIELD_WIDTH = 32;
    private static final int MAX_SKELETON_LENGTH = 256;
    private static final Pattern ISO_DATE_TIME_OPERAND =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?)?");
    private static final String SEMANTIC_SKELETON_PREFIX = "semantic:";
    private static final List<String> SEMANTIC_FIELD_ORDER =
            List.of(
                    "era",
                    "year",
                    "quarter",
                    "month",
                    "weekofmonth",
                    "day",
                    "dayofyear",
                    "dayofweekinmonth",
                    "modifiedjulianday",
                    "weekday",
                    "weekofyear",
                    "dayperiod",
                    "hour",
                    "minute",
                    "second",
                    "fractionalsecond",
                    "millisecondsinday",
                    "time",
                    "zone");
    private static final List<String> SEMANTIC_DATE_FIELD_ORDER =
            List.of(
                    "era",
                    "year",
                    "quarter",
                    "month",
                    "weekofmonth",
                    "day",
                    "dayofyear",
                    "dayofweekinmonth",
                    "modifiedjulianday",
                    "weekday",
                    "weekofyear");
    private static final List<String> SEMANTIC_TIME_FIELD_ORDER =
            List.of("hour", "minute", "second", "fractionalsecond", "millisecondsinday");
    private static final List<String> SEMANTIC_OPTION_KEYS =
            List.of(
                    "fields",
                    "length",
                    "alignment",
                    "yearstyle",
                    "erastyle",
                    "monthstyle",
                    "quarterstyle",
                    "daystyle",
                    "weekdaystyle",
                    "dayperiodstyle",
                    "hourstyle",
                    "minutestyle",
                    "secondstyle",
                    "timeprecision",
                    "timestyle",
                    "fractionalsecond",
                    "hourcycle",
                    "zonestyle");
    private static final List<String> SEMANTIC_DIRECT_STYLE_OPTION_KEYS =
            List.of("fields", "length", "timestyle");
    private static final List<String> SEMANTIC_STYLE_OPTION_KEYS =
            List.of(
                    "yearstyle",
                    "erastyle",
                    "monthstyle",
                    "quarterstyle",
                    "daystyle",
                    "weekdaystyle",
                    "dayperiodstyle",
                    "hourstyle",
                    "minutestyle",
                    "secondstyle");
    private static final List<String> SEMANTIC_DATE_STYLE_VALUES =
            List.of("auto", "numeric", "2-digit", "short", "long", "narrow");
    private static final List<String> SEMANTIC_NUMERIC_STYLE_VALUES =
            List.of("auto", "numeric", "2-digit");
    private static final List<String> SEMANTIC_TEXT_STYLE_VALUES =
            List.of("auto", "short", "long", "narrow");
    private static final List<String> SEMANTIC_DATE_FIELD_SETS =
            List.of(
                    "day",
                    "weekday",
                    "day,weekday",
                    "month,day",
                    "month,day,weekday",
                    "era,year,month,day",
                    "era,year,month,day,weekday",
                    "year,month,day",
                    "year,month,day,weekday");
    private static final List<String> SEMANTIC_CALENDAR_PERIOD_FIELD_SETS =
            List.of(
                    "era",
                    "year",
                    "quarter",
                    "month",
                    "era,year",
                    "era,year,quarter",
                    "era,year,month",
                    "era,year,weekofyear",
                    "era,year,month,weekofmonth",
                    "year,quarter",
                    "year,month",
                    "year,weekofyear",
                    "month,weekofmonth",
                    "year,month,weekofmonth",
                    "dayofyear",
                    "dayofweekinmonth",
                    "modifiedjulianday");
    private static final List<String> SEMANTIC_TIME_FIELD_SETS =
            List.of(
                    "hour",
                    "minute",
                    "second",
                    "millisecondsinday",
                    "hour,minute",
                    "hour,minute,second",
                    "hour,minute,second,fractionalsecond",
                    "minute,second",
                    "minute,second,fractionalsecond",
                    "second,fractionalsecond");
    private static final String SKELETON_FIELD_ORDER = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx";
    private static final String SKELETON_TIME_FIELDS = "abBhHkKJmsSAzZOvVXx";
    private static final String SKELETON_HOUR_FIELDS = "hHkK";
    private static final List<String> WEEKDAY_KEYS =
            List.of("sun", "mon", "tue", "wed", "thu", "fri", "sat");

    private Mf2DateTimeCore() {}

    public static Options.Builder options() {
        return Options.builder();
    }

    public static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.portable()
                .withFunction("date", Mf2DateTimeCore::formatCallDate)
                .withFunction("time", Mf2DateTimeCore::formatCallTime)
                .withFunction("datetime", Mf2DateTimeCore::formatCallDateTime);
    }

    public static String formatDate(Object value) throws Mf2Exception {
        return formatDate(value, null);
    }

    public static String formatDate(Object value, Options options) throws Mf2Exception {
        Options effectiveOptions = options == null ? Options.builder().build() : options;
        String locale = LocaleKey.option(effectiveOptions.locale(), DEFAULT_LOCALE);
        CldrDateTimeData.LocaleData localeData = resolveLocaleData(locale);
        validateOptions(effectiveOptions);
        localeData = resolveNumberingSystemData(localeData, locale);
        boolean preserveSameFamilyHourCycle = firstNonBlank(effectiveOptions.hourCycle(), null) != null;
        String hourCycle =
                validateHourCycle(
                        firstNonBlank(
                                effectiveOptions.hourCycle(),
                                localeUnicodeExtension(locale, "hc")));
        ZonedDateTime date = parseDate(value).withZoneSameInstant(parseTimeZone(effectiveOptions.timeZone()));
        if (effectiveOptions.skeleton() != null) {
            return formatSkeleton(effectiveOptions.skeleton(), date, localeData, hourCycle, preserveSameFamilyHourCycle);
        }
        return formatPattern(localeData.dateFormats().get(styleKey(effectiveOptions.effectiveDateStyle())), date, localeData);
    }

    public static List<Mf2FormattedPart> formatDateToParts(Object value) throws Mf2Exception {
        return formatDateToParts(value, null);
    }

    public static List<Mf2FormattedPart> formatDateToParts(Object value, Options options) throws Mf2Exception {
        return List.of(new Mf2FormattedPart.Text(formatDate(value, options)));
    }

    public static String formatTime(Object value) throws Mf2Exception {
        return formatTime(value, null);
    }

    public static String formatTime(Object value, Options options) throws Mf2Exception {
        Options effectiveOptions = options == null ? Options.builder().build() : options;
        String locale = LocaleKey.option(effectiveOptions.locale(), DEFAULT_LOCALE);
        CldrDateTimeData.LocaleData localeData = resolveLocaleData(locale);
        validateOptions(effectiveOptions);
        localeData = resolveNumberingSystemData(localeData, locale);
        boolean preserveSameFamilyHourCycle = firstNonBlank(effectiveOptions.hourCycle(), null) != null;
        String hourCycle =
                validateHourCycle(
                        firstNonBlank(
                                effectiveOptions.hourCycle(),
                                localeUnicodeExtension(locale, "hc")));
        ZonedDateTime date = parseDate(value).withZoneSameInstant(parseTimeZone(effectiveOptions.timeZone()));
        if (effectiveOptions.skeleton() != null) {
            return formatSkeleton(effectiveOptions.skeleton(), date, localeData, hourCycle, preserveSameFamilyHourCycle);
        }
        return formatTimeStylePattern(
                localeData.timeFormats().get(styleKey(effectiveOptions.effectiveTimeStyle())),
                date,
                localeData,
                hourCycle,
                preserveSameFamilyHourCycle);
    }

    public static List<Mf2FormattedPart> formatTimeToParts(Object value) throws Mf2Exception {
        return formatTimeToParts(value, null);
    }

    public static List<Mf2FormattedPart> formatTimeToParts(Object value, Options options) throws Mf2Exception {
        return List.of(new Mf2FormattedPart.Text(formatTime(value, options)));
    }

    public static String formatDateTime(Object value) throws Mf2Exception {
        return formatDateTime(value, null);
    }

    public static String formatDateTime(Object value, Options options) throws Mf2Exception {
        Options effectiveOptions = options == null ? Options.builder().build() : options;
        String locale = LocaleKey.option(effectiveOptions.locale(), DEFAULT_LOCALE);
        CldrDateTimeData.LocaleData localeData = resolveLocaleData(locale);
        validateOptions(effectiveOptions);
        localeData = resolveNumberingSystemData(localeData, locale);
        boolean preserveSameFamilyHourCycle = firstNonBlank(effectiveOptions.hourCycle(), null) != null;
        String hourCycle =
                validateHourCycle(
                        firstNonBlank(
                                effectiveOptions.hourCycle(),
                                localeUnicodeExtension(locale, "hc")));
        ZonedDateTime date = parseDate(value).withZoneSameInstant(parseTimeZone(effectiveOptions.timeZone()));
        if (effectiveOptions.skeleton() != null) {
            return formatSkeleton(effectiveOptions.skeleton(), date, localeData, hourCycle, preserveSameFamilyHourCycle);
        }
        Style dateStyle = effectiveOptions.effectiveDateStyle();
        Style timeStyle = effectiveOptions.effectiveTimeStyle();
        String datePart = formatPattern(localeData.dateFormats().get(styleKey(dateStyle)), date, localeData);
        String timePart =
                formatTimeStylePattern(
                        localeData.timeFormats().get(styleKey(timeStyle)),
                        date,
                        localeData,
                        hourCycle,
                        preserveSameFamilyHourCycle);
        return dateTimeStyleJoinPattern(localeData, styleKey(dateStyle))
                .replace("{1}", datePart)
                .replace("{0}", timePart);
    }

    public static List<Mf2FormattedPart> formatDateTimeToParts(Object value) throws Mf2Exception {
        return formatDateTimeToParts(value, null);
    }

    public static List<Mf2FormattedPart> formatDateTimeToParts(Object value, Options options)
            throws Mf2Exception {
        return List.of(new Mf2FormattedPart.Text(formatDateTime(value, options)));
    }

    private static String formatCallDate(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        return formatDate(
                callSourceValue(call),
                Options.builder()
                        .locale(call.locale())
                        .dateStyle(callStyle(call, "dateStyle", "length", "medium", false))
                        .skeleton(nonEmptyCallOption(call, "skeleton", null))
                        .hourCycle(nonEmptyCallOption(call, "hourCycle", null))
                        .timeZone(nonEmptyCallOption(call, "timeZone", UTC))
                        .calendar(nonEmptyCallOption(call, "calendar", null))
                        .build());
    }

    private static String formatCallTime(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        return formatTime(
                callSourceValue(call),
                Options.builder()
                        .locale(call.locale())
                        .timeStyle(callStyle(call, "timeStyle", "precision", "medium", true))
                        .skeleton(nonEmptyCallOption(call, "skeleton", null))
                        .hourCycle(nonEmptyCallOption(call, "hourCycle", null))
                        .timeZone(nonEmptyCallOption(call, "timeZone", UTC))
                        .calendar(nonEmptyCallOption(call, "calendar", null))
                        .build());
    }

    private static String formatCallDateTime(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        return formatDateTime(
                callSourceValue(call),
                Options.builder()
                        .locale(call.locale())
                        .dateStyle(callStyle(call, "dateStyle", "dateLength", "medium", false))
                        .timeStyle(callStyle(call, "timeStyle", "timePrecision", "medium", true))
                        .skeleton(nonEmptyCallOption(call, "skeleton", null))
                        .hourCycle(nonEmptyCallOption(call, "hourCycle", null))
                        .timeZone(nonEmptyCallOption(call, "timeZone", UTC))
                        .calendar(nonEmptyCallOption(call, "calendar", null))
                        .build());
    }

    private static Object callSourceValue(Mf2FunctionRegistry.FunctionCall call) {
        if (call.inheritedSource() != null) {
            return call.inheritedSource().value();
        }
        return call.rawValue() != null ? call.rawValue() : call.value();
    }

    private static CldrDateTimeData.LocaleData resolveLocaleData(String locale) {
        for (String candidate : LocaleKey.lookupChain(locale)) {
            CldrDateTimeData.LocaleData exact = CldrDateTimeData.LOCALES.get(candidate);
            if (exact != null) {
                return exact;
            }
            for (CldrDateTimeData.LocaleData localeData : CldrDateTimeData.LOCALES.values()) {
                if (localeData.sourceLocale().equals(candidate)
                        || localeData.numbersSourceLocale().equals(candidate)) {
                    return localeData;
                }
            }
        }
        return CldrDateTimeData.LOCALES.get(DEFAULT_LOCALE);
    }

    private static String localeUnicodeExtension(String locale, String key) {
        String[] parts = (locale == null ? "" : locale.trim().replace('_', '-')).split("-");
        int index = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("u".equals(parts[i].toLowerCase(Locale.ROOT))) {
                index = i + 1;
                break;
            }
        }
        while (index > 0 && index < parts.length) {
            String part = parts[index].toLowerCase(Locale.ROOT);
            if (part.length() == 1) {
                return null;
            }
            if (part.length() != 2) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < parts.length && parts[end].length() > 2) {
                end++;
            }
            if (part.equals(key)) {
                return end > index + 1 ? parts[index + 1].toLowerCase(Locale.ROOT) : null;
            }
            index = end;
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static CldrDateTimeData.LocaleData resolveNumberingSystemData(
            CldrDateTimeData.LocaleData localeData, String locale) throws Mf2Exception {
        String numberingSystem = localeUnicodeExtension(locale, "nu");
        if (numberingSystem == null || numberingSystem.isBlank()) {
            return localeData;
        }
        String digits = numberingSystemDigits(numberingSystem);
        if (digits == null) {
            throw Mf2FunctionSupport.badOption(
                    "Date/time core does not include data for the requested numbering system.");
        }
        return new CldrDateTimeData.LocaleData(
                localeData.requestedLocale(),
                localeData.sourceLocale(),
                localeData.numbersSourceLocale(),
                localeData.calendar(),
                localeData.numberingSystem(),
                digits,
                localeData.decimalSeparator(),
                localeData.allowedHourFormats(),
                localeData.firstDayOfWeek(),
                localeData.minDaysInFirstWeek(),
                localeData.dateFormats(),
                localeData.timeFormats(),
                localeData.dateTimeFormats(),
                localeData.dateTimeStyleJoinFormats(),
                localeData.availableFormats(),
                localeData.appendItems(),
                localeData.fieldNames(),
                localeData.timeZoneNames(),
                localeData.months(),
                localeData.quarters(),
                localeData.weekdays(),
                localeData.eras(),
                localeData.dayPeriods(),
                localeData.dayPeriodRules());
    }

    private static String numberingSystemDigits(String numberingSystem) {
        if ("latn".equals(numberingSystem)) {
            return "0123456789";
        }
        for (CldrDateTimeData.LocaleData localeData : CldrDateTimeData.LOCALES.values()) {
            if (localeData.numberingSystem().equals(numberingSystem)
                    && localeData.numberingSystemDigits() != null) {
                return localeData.numberingSystemDigits();
            }
        }
        return null;
    }

    private static void validateOptions(Options options) throws Mf2Exception {
        String calendar = boundedOption(firstNonBlank(options.calendar(), localeUnicodeExtension(options.locale(), "ca")), "calendar");
        if (calendar != null && !"gregorian".equals(calendar) && !"gregory".equals(calendar)) {
            throw Mf2FunctionSupport.badOption(
                    "Date/time core currently supports only the gregorian/gregory calendar.");
        }
    }

    private static String validateHourCycle(String value) throws Mf2Exception {
        if (value == null || value.isBlank()) {
            return null;
        }
        value = boundedOption(value, "hourCycle");
        return switch (value) {
            case "h11", "h12", "h23", "h24" -> value;
            default -> throw Mf2FunctionSupport.badOption("hourCycle must be one of h11, h12, h23, h24.");
        };
    }

    private static ZoneOffset parseTimeZone(String value) throws Mf2Exception {
        String bounded = boundedOption(value, "timeZone");
        String text = bounded == null || bounded.isBlank() ? UTC : bounded.trim();
        if (UTC.equals(text)
                || "Etc/UTC".equals(text)
                || "Z".equals(text)
                || "GMT".equals(text)
                || "Etc/GMT".equals(text)) {
            return ZoneOffset.UTC;
        }
        Integer etcGmtOffsetMinutes = parseEtcGmtOffsetMinutes(text);
        if (etcGmtOffsetMinutes != null) {
            return ZoneOffset.ofTotalSeconds(etcGmtOffsetMinutes * 60);
        }
        String offsetText = text;
        if ((text.startsWith("UTC") || text.startsWith("GMT")) && text.length() > 3) {
            offsetText = text.substring(3);
        }
        Integer offsetMinutes = parseOffsetMinutes(offsetText);
        if (offsetMinutes == null) {
            throw Mf2FunctionSupport.badOption("Date/time core supports only UTC or fixed-offset time zones.");
        }
        return ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
    }

    private static String boundedOption(String value, String name) throws Mf2Exception {
        if (value != null && value.length() > MAX_OPTION_LENGTH) {
            throw Mf2FunctionSupport.badOption(name + " must not exceed 256 characters.");
        }
        return value;
    }

    private static Integer parseEtcGmtOffsetMinutes(String value) {
        String prefix = "Etc/GMT";
        if (!value.startsWith(prefix) || value.length() <= prefix.length()) {
            return null;
        }
        char sign = value.charAt(prefix.length());
        if (sign != '+' && sign != '-') {
            return null;
        }
        String hourText = value.substring(prefix.length() + 1);
        if (hourText.isBlank() || hourText.length() > 2 || !hourText.chars().allMatch(Mf2DateTimeCore::isAsciiDigit)) {
            return null;
        }
        int hours = Integer.parseInt(hourText);
        if (hours > 14) {
            return null;
        }
        int offset = hours * 60;
        return sign == '+' ? -offset : offset;
    }

    private static Integer parseOffsetMinutes(String value) {
        if (value == null || value.length() < 2) {
            return null;
        }
        char sign = value.charAt(0);
        if (sign != '+' && sign != '-') {
            return null;
        }
        String body = value.substring(1);
        String hourText = body;
        String minuteText = "00";
        int colon = body.indexOf(':');
        if (colon >= 0) {
            hourText = body.substring(0, colon);
            minuteText = body.substring(colon + 1);
        } else if (body.length() > 2) {
            hourText = body.substring(0, body.length() - 2);
            minuteText = body.substring(body.length() - 2);
        }
        if (hourText.isEmpty() || hourText.length() > 2 || minuteText.length() != 2) {
            return null;
        }
        if (!hourText.chars().allMatch(Mf2DateTimeCore::isAsciiDigit)
                || !minuteText.chars().allMatch(Mf2DateTimeCore::isAsciiDigit)) {
            return null;
        }
        try {
            int hours = Integer.parseInt(hourText);
            int minutes = Integer.parseInt(minuteText);
            if (hours > 18 || minutes > 59 || (hours == 18 && minutes != 0)) {
                return null;
            }
            int total = hours * 60 + minutes;
            return sign == '-' ? -total : total;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static ZonedDateTime parseDate(Object value) throws Mf2Exception {
        if (value instanceof LocalDateTime localDateTime) {
            return validateDate(localDateTime.atZone(ZoneOffset.UTC));
        }
        if (value instanceof LocalDate localDate) {
            return validateDate(localDate.atStartOfDay(ZoneOffset.UTC));
        }
        if (value instanceof LocalTime localTime) {
            return validateDate(localTime.atDate(LocalDate.of(1970, 1, 1)).atZone(ZoneOffset.UTC));
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return validateDate(zonedDateTime.withZoneSameInstant(ZoneOffset.UTC));
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return validateDate(offsetDateTime.atZoneSameInstant(ZoneOffset.UTC));
        }
        if (value instanceof Instant instant) {
            return validateDate(instant.atZone(ZoneOffset.UTC));
        }
        if (value instanceof Date date) {
            return validateDate(date.toInstant().atZone(ZoneOffset.UTC));
        }
        if (value instanceof Number number) {
            double epochMillis = number.doubleValue();
            if (!Double.isFinite(epochMillis) || epochMillis < MIN_TIMESTAMP_MS || epochMillis > MAX_TIMESTAMP_MS) {
                throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
            }
            return Instant.ofEpochMilli((long) epochMillis).atZone(ZoneOffset.UTC);
        }
        if (!(value instanceof CharSequence)) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
        String text;
        try {
            text = value.toString().trim();
        } catch (RuntimeException error) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
        if (text.length() > MAX_OPERAND_LENGTH) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
        if (!ISO_DATE_TIME_OPERAND.matcher(text).matches()) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
        try {
            return validateDate(Instant.parse(text).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // Try the other ISO host types below.
        }
        try {
            return validateDate(OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // Try the other ISO host types below.
        }
        try {
            return validateDate(LocalDateTime.parse(text).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // Try the date-only form below.
        }
        try {
            return validateDate(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
    }

    private static ZonedDateTime validateDate(ZonedDateTime value) throws Mf2Exception {
        Instant instant = value.toInstant();
        if (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
            throw Mf2Exception.badOperand("Date/time core requires a valid host date/time value or ISO date string.");
        }
        return value;
    }

    private static String formatSkeleton(
            String skeleton,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle,
            boolean preserveSameFamilyHourCycle) throws Mf2Exception {
        if (skeleton.length() > MAX_SKELETON_LENGTH) {
            throw Mf2FunctionSupport.badOption("Date/time skeleton is too large.");
        }
        String semanticStyle = formatSemanticStyleSkeleton(skeleton, date, localeData, hourCycle, preserveSameFamilyHourCycle);
        if (semanticStyle != null) {
            return semanticStyle;
        }
        String canonical = canonicalSkeleton(skeleton, localeData, hourCycle, date);
        boolean suppressDayPeriod = shouldSuppressDayPeriod(skeleton);
        String dateTimeJoinStyle = skeletonDateTimeJoinStyle(skeleton);
        String pattern = skeletonPattern(canonical, localeData);
        if (pattern != null) {
            if (suppressDayPeriod) {
                pattern = stripDayPeriodPatternFields(pattern);
            }
            return formatPattern(pattern, date, localeData);
        }
        return formatComposedSkeleton(skeleton, canonical, date, localeData, suppressDayPeriod, dateTimeJoinStyle);
    }

    private static String skeletonDateTimeJoinStyle(String skeleton) throws Mf2Exception {
        if (!skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            return "medium";
        }
        Map<String, String> options =
                parseSemanticSkeletonOptions(skeleton.substring(SEMANTIC_SKELETON_PREFIX.length()));
        return semanticOption(options, "length", "medium", List.of("full", "long", "medium", "short"));
    }

    private static String formatSemanticStyleSkeleton(
            String skeleton,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle,
            boolean preserveSameFamilyHourCycle) throws Mf2Exception {
        if (!skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            return null;
        }
        Map<String, String> options =
                parseSemanticSkeletonOptions(skeleton.substring(SEMANTIC_SKELETON_PREFIX.length()));
        List<String> fields = parseSemanticSkeletonFields(options);
        validateSemanticSkeleton(fields, options);
        for (String key : options.keySet()) {
            if (!SEMANTIC_DIRECT_STYLE_OPTION_KEYS.contains(key)) {
                return null;
            }
        }

        String length = semanticOption(options, "length", "medium", List.of("full", "long", "medium", "short"));
        String timeStyle =
                semanticOption(options, "timestyle", "auto", List.of("auto", "short", "medium", "long", "full"));
        String dateKey = semanticFieldSetKey(fields, SEMANTIC_DATE_FIELD_ORDER);
        String expectedDateKey = "full".equals(length) ? "year,month,day,weekday" : "year,month,day";
        boolean hasDate = !dateKey.isEmpty();
        boolean hasTime = fields.contains("time");
        boolean hasZone = fields.contains("zone");
        if (!semanticFieldSetKey(fields, SEMANTIC_TIME_FIELD_ORDER).isEmpty()) {
            return null;
        }
        if (hasDate && !dateKey.equals(expectedDateKey)) {
            return null;
        }
        if (hasTime && !options.containsKey("timestyle")) {
            return null;
        }
        if (!hasTime && (hasZone || !"auto".equals(timeStyle))) {
            return null;
        }
        if (hasTime && hasZone != List.of("long", "full").contains(timeStyle)) {
            return null;
        }
        int expectedFieldCount =
                (hasDate ? expectedDateKey.split(",").length : 0) + (hasTime ? 1 : 0) + (hasZone ? 1 : 0);
        if (fields.size() != expectedFieldCount) {
            return null;
        }

        if (hasDate && hasTime) {
            String datePart = formatPattern(localeData.dateFormats().get(length), date, localeData);
            String timePart =
                    formatTimeStylePattern(
                            localeData.timeFormats().get(timeStyle),
                            date,
                            localeData,
                            hourCycle,
                            preserveSameFamilyHourCycle);
            String joinPattern = dateTimeStyleJoinPattern(localeData, length);
            return joinPattern
                    .replace("{1}", datePart)
                    .replace("{0}", timePart);
        }
        if (hasDate) {
            return formatPattern(localeData.dateFormats().get(length), date, localeData);
        }
        if (hasTime) {
            return formatTimeStylePattern(
                    localeData.timeFormats().get(timeStyle),
                    date,
                    localeData,
                    hourCycle,
                    preserveSameFamilyHourCycle);
        }
        return null;
    }

    private static String formatTimeStylePattern(
            String pattern,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle,
            boolean preserveSameFamilyHourCycle) throws Mf2Exception {
        if (hourCycle == null) {
            return formatPattern(pattern, date, localeData);
        }
        char hourSymbol = preferredHourSymbol(localeData, hourCycle);
        Character patternHourSymbol = timeStylePatternHourSymbol(pattern);
        if (preserveSameFamilyHourCycle
                && patternHourSymbol != null
                && isHour12Field(patternHourSymbol) == isHour12Field(hourSymbol)) {
            return formatPattern(replaceTimeStylePatternHourSymbol(pattern, hourSymbol), date, localeData);
        }
        String skeleton = timeStylePatternSkeleton(pattern, localeData, hourCycle);
        if (skeleton == null) {
            return formatPattern(pattern, date, localeData);
        }
        String canonical = canonicalStandardSkeleton(skeleton, localeData, null);
        String matched = skeletonPattern(canonical, localeData);
        return formatPattern(matched == null ? pattern : matched, date, localeData);
    }

    private static String dateTimeStyleJoinPattern(CldrDateTimeData.LocaleData localeData, String style) {
        return localeData.dateTimeStyleJoinFormats().getOrDefault(
                style,
                localeData.dateTimeFormats().getOrDefault(
                        style,
                        localeData.dateTimeFormats().getOrDefault("medium", "{1} {0}")));
    }

    private static Character timeStylePatternHourSymbol(String pattern) {
        int index = 0;
        while (index < pattern.length()) {
            char symbol = pattern.charAt(index);
            if (symbol == '\'') {
                index = readQuotedPattern(pattern, index).nextIndex();
            } else if (isAsciiLetter(symbol)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == symbol) {
                    end++;
                }
                if (isHourField(symbol)) {
                    return symbol;
                }
                index = end;
            } else {
                index++;
            }
        }
        return null;
    }

    private static String replaceTimeStylePatternHourSymbol(String pattern, char hourSymbol) {
        StringBuilder output = new StringBuilder(pattern.length());
        int index = 0;
        while (index < pattern.length()) {
            char symbol = pattern.charAt(index);
            if (symbol == '\'') {
                QuotedPattern quoted = readQuotedPattern(pattern, index);
                output.append(pattern, index, quoted.nextIndex());
                index = quoted.nextIndex();
            } else if (isAsciiLetter(symbol)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == symbol) {
                    end++;
                }
                output.append(isHourField(symbol)
                        ? String.valueOf(hourSymbol).repeat(end - index)
                        : pattern.substring(index, end));
                index = end;
            } else {
                output.append(symbol);
                index++;
            }
        }
        return output.toString();
    }

    private static String timeStylePatternSkeleton(
            String pattern,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle) {
        Map<Character, Integer> widths = new java.util.HashMap<>();
        char hourSymbol = preferredHourSymbol(localeData, hourCycle);
        boolean hasHour = false;
        for (Map.Entry<Character, Integer> field : patternFieldRuns(pattern).entrySet()) {
            char symbol = field.getKey();
            if (isHourField(symbol)) {
                setSkeletonWidth(widths, hourSymbol, field.getValue());
                hasHour = true;
            } else if (!isDayPeriodField(symbol) && SKELETON_TIME_FIELDS.indexOf(symbol) >= 0) {
                setSkeletonWidth(widths, symbol, field.getValue());
            }
        }
        if (!hasHour) {
            return null;
        }
        StringBuilder skeleton = new StringBuilder();
        for (int orderIndex = 0; orderIndex < SKELETON_FIELD_ORDER.length(); orderIndex++) {
            char symbol = SKELETON_FIELD_ORDER.charAt(orderIndex);
            if (widths.containsKey(symbol)) {
                skeleton.append(String.valueOf(symbol).repeat(widths.get(symbol)));
            }
        }
        return skeleton.toString();
    }

    private static String skeletonPattern(
            String canonical,
            CldrDateTimeData.LocaleData localeData) {
        String pattern = skeletonPatternWithoutAppend(canonical, localeData);
        if (pattern != null) {
            return pattern;
        }
        return hasDateAndTimeFields(canonical) ? null : appendedSkeletonPattern(canonical, localeData);
    }

    private static String skeletonPatternWithoutAppend(
            String canonical,
            CldrDateTimeData.LocaleData localeData) {
        String direct = localeData.availableFormats().get(canonical);
        if (direct != null) {
            return direct;
        }
        String requestedFields = skeletonFieldSet(canonical);
        String bestCandidate = null;
        String bestPattern = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : localeData.availableFormats().entrySet()) {
            if (!skeletonFieldSet(entry.getKey()).equals(requestedFields)) {
                continue;
            }
            int distance = skeletonDistance(canonical, entry.getKey());
            if (distance < bestDistance
                    || (distance == bestDistance
                            && (bestCandidate == null || entry.getKey().compareTo(bestCandidate) < 0))) {
                bestCandidate = entry.getKey();
                bestPattern = entry.getValue();
                bestDistance = distance;
            }
        }
        return bestPattern == null ? syntheticSkeletonPattern(canonical, localeData) : adjustPatternWidths(bestPattern, canonical, bestCandidate);
    }

    private static String appendedSkeletonPattern(String canonical, CldrDateTimeData.LocaleData localeData) {
        String requestedFields = skeletonFieldSet(canonical);
        String bestCandidate = null;
        String bestPattern = null;
        int bestFieldCount = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : localeData.availableFormats().entrySet()) {
            String candidateFields = skeletonFieldSet(entry.getKey());
            if (candidateFields.isEmpty() || candidateFields.equals(requestedFields)) {
                continue;
            }
            if (!fieldSetContains(requestedFields, candidateFields)) {
                continue;
            }
            int fieldCount = candidateFields.length();
            int distance = skeletonDistance(canonical, entry.getKey());
            if (fieldCount > bestFieldCount
                    || (fieldCount == bestFieldCount
                            && (distance < bestDistance
                                    || (distance == bestDistance
                                            && (bestCandidate == null
                                                    || entry.getKey().compareTo(bestCandidate) < 0))))) {
                bestCandidate = entry.getKey();
                bestPattern = entry.getValue();
                bestFieldCount = fieldCount;
                bestDistance = distance;
            }
        }
        if (bestPattern == null || bestCandidate == null) {
            return null;
        }
        String output = adjustPatternWidths(bestPattern, canonical, bestCandidate);
        java.util.Set<Character> currentFields = new java.util.HashSet<>();
        for (int index = 0; index < bestCandidate.length(); index++) {
            currentFields.add(fieldSetSymbol(bestCandidate.charAt(index)));
        }
        Map<Character, Integer> requestedWidths = skeletonWidths(canonical);
        for (int orderIndex = 0; orderIndex < SKELETON_FIELD_ORDER.length(); orderIndex++) {
            char symbol = SKELETON_FIELD_ORDER.charAt(orderIndex);
            Integer width = requestedWidths.get(symbol);
            if (width == null) {
                continue;
            }
            char field = fieldSetSymbol(symbol);
            if (currentFields.contains(field)) {
                continue;
            }
            String key = appendItemKey(symbol);
            String fieldSkeleton = String.valueOf(symbol).repeat(width);
            String fieldPattern = skeletonPatternWithoutAppend(fieldSkeleton, localeData);
            if (fieldPattern == null) {
                fieldPattern = fieldSkeleton;
            }
            if (key == null) {
                return null;
            }
            output = applyAppendItemPattern(
                    appendItemTemplate(localeData, key),
                    output,
                    fieldPattern,
                    localeData.fieldNames().getOrDefault(key, key));
            currentFields.add(field);
        }
        return output;
    }

    private static boolean fieldSetContains(String container, String subset) {
        for (int index = 0; index < subset.length(); index++) {
            if (container.indexOf(subset.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String applyAppendItemPattern(
            String template,
            String basePattern,
            String fieldPattern,
            String fieldName) {
        return template.replace("{0}", basePattern).replace("{1}", fieldPattern).replace("{2}", quotePatternLiteral(fieldName));
    }

    private static String quotePatternLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String appendItemTemplate(CldrDateTimeData.LocaleData localeData, String key) {
        return localeData.appendItems().getOrDefault(key, defaultAppendItemTemplate(key));
    }

    private static String defaultAppendItemTemplate(String key) {
        return switch (key) {
            case "Quarter", "Month", "Week", "Day", "Hour", "Minute", "Second" -> "{0} ({2}: {1})";
            default -> "{0} {1}";
        };
    }

    private static boolean hasDateAndTimeFields(String canonical) {
        String[] parts = splitDateTimeSkeleton(canonical);
        return !parts[0].isEmpty() && !parts[1].isEmpty();
    }

    private static String appendItemKey(char symbol) {
        if (symbol == 'G') {
            return "Era";
        }
        if (isYearField(symbol)) {
            return "Year";
        }
        if (isQuarterField(symbol)) {
            return "Quarter";
        }
        if (isMonthField(symbol)) {
            return "Month";
        }
        if (symbol == 'w' || symbol == 'W') {
            return "Week";
        }
        if (symbol == 'd' || symbol == 'D' || symbol == 'F' || symbol == 'g') {
            return "Day";
        }
        if (isWeekdayField(symbol)) {
            return "Day-Of-Week";
        }
        if (isHourField(symbol)) {
            return "Hour";
        }
        if (symbol == 'm') {
            return "Minute";
        }
        if (symbol == 's' || symbol == 'S' || symbol == 'A') {
            return "Second";
        }
        if (isTimeZoneField(symbol)) {
            return "Timezone";
        }
        return null;
    }

    private static String syntheticSkeletonPattern(String canonical, CldrDateTimeData.LocaleData localeData) {
        Map<Character, Integer> widths = skeletonWidths(canonical);
        if (widths.size() == 1) {
            Map.Entry<Character, Integer> entry = widths.entrySet().iterator().next();
            if (entry.getKey() == 'G') {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
            if (isDayPeriodField(entry.getKey())) {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
            if (isQuarterField(entry.getKey())) {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
            if (isSyntheticNumericField(entry.getKey())) {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
            if (entry.getKey() == 'S') {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
            if (isTimeZoneField(entry.getKey())) {
                return String.valueOf(entry.getKey()).repeat(entry.getValue());
            }
        }
        return syntheticFractionalSecondPattern(canonical, localeData, widths);
    }

    private static String syntheticFractionalSecondPattern(
            String canonical,
            CldrDateTimeData.LocaleData localeData,
            Map<Character, Integer> widths) {
        Integer fractionWidth = widths.get('S');
        if (fractionWidth == null || !widths.containsKey('s')) {
            return null;
        }
        String baseSkeleton = skeletonWithoutField(canonical, 'S');
        String basePattern = skeletonPattern(baseSkeleton, localeData);
        if (basePattern == null) {
            basePattern = syntheticSecondsPattern(baseSkeleton);
        }
        return basePattern == null ? null : insertFractionalSecond(basePattern, fractionWidth, localeData.decimalSeparator());
    }

    private static String syntheticSecondsPattern(String canonical) {
        Map<Character, Integer> widths = skeletonWidths(canonical);
        Integer width = widths.get('s');
        return widths.size() == 1 && width != null ? "s".repeat(width) : null;
    }

    private static String skeletonWithoutField(String skeleton, char removedSymbol) {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < skeleton.length()) {
            char symbol = skeleton.charAt(index);
            int end = index + 1;
            while (end < skeleton.length() && skeleton.charAt(end) == symbol) {
                end++;
            }
            if (symbol != removedSymbol) {
                output.append(skeleton, index, end);
            }
            index = end;
        }
        return output.toString();
    }

    private static String insertFractionalSecond(String pattern, int width, String decimalSeparator) {
        StringBuilder output = new StringBuilder();
        boolean inQuote = false;
        int index = 0;
        while (index < pattern.length()) {
            char ch = pattern.charAt(index);
            if (ch == '\'') {
                output.append(ch);
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
                    output.append('\'');
                    index += 2;
                } else {
                    inQuote = !inQuote;
                    index++;
                }
            } else if (!inQuote && ch == 's') {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == ch) {
                    end++;
                }
                return output.append(pattern, index, end)
                        .append(decimalSeparator)
                        .append("S".repeat(width))
                        .append(pattern.substring(end))
                        .toString();
            } else {
                output.append(ch);
                index++;
            }
        }
        return null;
    }

    private static String formatComposedSkeleton(
            String rawSkeleton,
            String canonical,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            boolean suppressDayPeriod,
            String dateTimeJoinStyle) throws Mf2Exception {
        String[] parts = splitDateTimeSkeleton(canonical);
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw unsupportedSkeleton(rawSkeleton);
        }
        String datePattern = skeletonPattern(parts[0], localeData);
        if (datePattern == null) {
            throw unsupportedSkeleton(rawSkeleton);
        }
        String timePattern = skeletonPattern(parts[1], localeData);
        if (timePattern == null) {
            throw unsupportedSkeleton(rawSkeleton);
        }
        if (suppressDayPeriod) {
            timePattern = stripDayPeriodPatternFields(timePattern);
        }
        String datePart = formatPattern(datePattern, date, localeData);
        String timePart = formatPattern(timePattern, date, localeData);
        String joinPattern =
                localeData.dateTimeFormats().getOrDefault(
                        dateTimeJoinStyle,
                        localeData.dateTimeFormats().getOrDefault("medium", "{1} {0}"));
        return joinPattern
                .replace("{1}", datePart)
                .replace("{0}", timePart);
    }

    private static Mf2Exception unsupportedSkeleton(String skeleton) {
        return Mf2FunctionSupport.badOption("Unsupported CLDR date/time skeleton: " + skeleton + ".");
    }

    private static String canonicalSkeleton(
            String skeleton,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle,
            ZonedDateTime date) throws Mf2Exception {
        if (skeleton.startsWith(SEMANTIC_SKELETON_PREFIX)) {
            String standard =
                    semanticSkeletonToStandard(
                            skeleton.substring(SEMANTIC_SKELETON_PREFIX.length()), localeData, date);
            return canonicalStandardSkeleton(standard, localeData, hourCycle);
        }
        return canonicalStandardSkeleton(skeleton, localeData, hourCycle);
    }

    private static String canonicalStandardSkeleton(
            String skeleton,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle) throws Mf2Exception {
        Map<Character, Integer> widths = new java.util.HashMap<>();
        int index = 0;
        while (index < skeleton.length()) {
            char symbol = skeleton.charAt(index);
            if (!isAsciiLetter(symbol)) {
                throw Mf2FunctionSupport.badOption(
                        "Date/time skeleton must contain only ASCII pattern letters.");
            }
            int end = index + 1;
            while (end < skeleton.length() && skeleton.charAt(end) == symbol) {
                end++;
            }
            int width = end - index;
            if (width > MAX_SKELETON_FIELD_WIDTH) {
                throw Mf2FunctionSupport.badOption("Date/time skeleton field width is too large.");
            }
            if (symbol == 'C') {
                applyCHourFormat(widths, localeData, hourCycle, width);
            } else {
                char normalized = normalizeSkeletonSymbol(symbol, localeData, hourCycle);
                setSkeletonWidth(widths, normalized, width);
            }
            index = end;
        }
        StringBuilder canonical = new StringBuilder();
        for (int orderIndex = 0; orderIndex < SKELETON_FIELD_ORDER.length(); orderIndex++) {
            char symbol = SKELETON_FIELD_ORDER.charAt(orderIndex);
            int width = widths.getOrDefault(symbol, 0);
            canonical.append(String.valueOf(symbol).repeat(width));
        }
        if (canonical.length() == 0) {
            throw Mf2FunctionSupport.badOption("Date/time skeleton must not be empty.");
        }
        return canonical.toString();
    }

    private static String semanticSkeletonToStandard(
            String body,
            CldrDateTimeData.LocaleData localeData,
            ZonedDateTime date) throws Mf2Exception {
        Map<String, String> options = parseSemanticSkeletonOptions(body);
        List<String> fields = parseSemanticSkeletonFields(options);
        validateSemanticSkeleton(fields, options);
        String length = semanticOption(options, "length", "medium", List.of("full", "long", "medium", "short"));
        String alignment = semanticOption(options, "alignment", "inline", List.of("inline", "column"));
        String yearStyle =
                semanticOption(options, "yearstyle", "auto", List.of("auto", "full", "with-era", "numeric", "2-digit"));
        String eraStyle = semanticOption(options, "erastyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
        String monthStyle = semanticOption(options, "monthstyle", "auto", SEMANTIC_DATE_STYLE_VALUES);
        String quarterStyle =
                semanticOption(options, "quarterstyle", "auto", SEMANTIC_DATE_STYLE_VALUES);
        String dayStyle = semanticOption(options, "daystyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
        String weekdayStyle =
                semanticOption(options, "weekdaystyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
        String dayPeriodStyle =
                semanticOption(options, "dayperiodstyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
        semanticOption(options, "hourstyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
        semanticOption(options, "minutestyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
        semanticOption(options, "secondstyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
        String timePrecision =
                semanticOption(
                        options,
                        "timeprecision",
                        "second",
                        List.of("hour", "minute", "minute-optional", "second", "fractional-second"));
        String timeStyle =
                semanticOption(options, "timestyle", "auto", List.of("auto", "short", "medium", "long", "full"));
        String effectiveTimePrecision = semanticTimeStylePrecision(timeStyle, timePrecision);
        String semanticHourCycle =
                semanticOption(
                        options,
                        "hourcycle",
                        "auto",
                        List.of("auto", "h11", "h12", "h23", "h24", "clock12", "clock24"));
        String zoneStyle =
                semanticOption(
                        options,
                        "zonestyle",
                        "auto",
                        List.of("auto", "generic", "specific", "location", "offset"));
        String effectiveZoneStyle = semanticTimeStyleZoneStyle(timeStyle, zoneStyle);
        boolean effectiveZoneStandalone = fields.size() == 1 || "full".equals(timeStyle);
        String effectiveZoneLength = List.of("long", "full").contains(timeStyle) ? timeStyle : length;
        Map<Character, Integer> dateWidths = semanticDateFieldWidths(localeData, length);
        StringBuilder standard = new StringBuilder();
        if (fields.contains("era")) {
            standard.append(semanticEraSkeleton(dateWidths, length, eraStyle));
        }
        if (fields.contains("year")) {
            standard.append(semanticYearSkeleton(dateWidths, yearStyle, !fields.contains("era")));
        }
        if (fields.contains("quarter")) {
            standard.append(semanticQuarterSkeleton(fields, length, alignment, quarterStyle));
        }
        if (fields.contains("month")) {
            standard.append(semanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle));
        }
        if (fields.contains("weekofmonth")) {
            standard.append('W');
        }
        if (fields.contains("day")) {
            standard.append(semanticDaySkeleton(dateWidths, alignment, dayStyle));
        }
        if (fields.contains("dayofyear")) {
            standard.append("D".repeat("column".equals(alignment) ? 3 : 1));
        }
        if (fields.contains("dayofweekinmonth")) {
            standard.append("F".repeat("column".equals(alignment) ? 2 : 1));
        }
        if (fields.contains("modifiedjulianday")) {
            standard.append("g".repeat("column".equals(alignment) ? 6 : 1));
        }
        if (fields.contains("weekday")) {
            standard.append(semanticWeekdaySkeleton(fields, length, weekdayStyle));
        }
        if (fields.contains("weekofyear")) {
            standard.append("column".equals(alignment) ? "ww" : "w");
        }
        if (fields.contains("dayperiod")) {
            standard.append(semanticDayPeriodSkeleton(length, dayPeriodStyle));
        }
        if (hasSemanticTimeComponents(fields)) {
            standard.append(semanticExplicitTimeSkeleton(fields, semanticHourCycle, alignment, options));
        }
        if (fields.contains("time")) {
            standard.append(semanticTimeSkeleton(effectiveTimePrecision, semanticHourCycle, alignment, date, options));
        }
        if (fields.contains("zone")) {
            standard.append(semanticZoneSkeleton(effectiveZoneStyle, effectiveZoneStandalone, effectiveZoneLength));
        }
        if (standard.length() == 0) {
            throw Mf2FunctionSupport.badOption("Date/time semantic skeleton must include at least one field.");
        }
        return standard.toString();
    }

    private static Map<String, String> parseSemanticSkeletonOptions(String body) throws Mf2Exception {
        Map<String, String> options = new java.util.HashMap<>();
        String[] parts = body.split(";");
        int seenParts = 0;
        String implicitDateStyle = null;
        boolean implicitTimeFields = false;
        for (String rawPart : parts) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            String rawKey = equals < 0 ? (seenParts == 0 ? "fields" : "") : part.substring(0, equals);
            String rawValue = equals < 0 ? part : part.substring(equals + 1);
            String rawKeyAlias = semanticNormalize(rawKey);
            String key = semanticNormalizeOptionKey(rawKey);
            String value = semanticNormalizeOptionValue(key, rawValue);
            if (key.isEmpty()
                    || value.isEmpty()
                    || !SEMANTIC_OPTION_KEYS.contains(key)
                    || options.containsKey(key)) {
                throw Mf2FunctionSupport.badOption("Invalid date/time semantic skeleton option.");
            }
            if ("style".equals(rawKeyAlias)
                    || "datestyle".equals(rawKeyAlias)
                    || "datelength".equals(rawKeyAlias)) {
                implicitDateStyle = value;
            }
            if ("timestyle".equals(rawKeyAlias)) {
                implicitTimeFields = true;
            }
            options.put(key, value);
            seenParts++;
        }
        if (seenParts == 0) {
            throw Mf2FunctionSupport.badOption("Date/time semantic skeleton must include fields.");
        }
        if (!options.containsKey("fields")) {
            String fields = implicitSemanticFields(implicitDateStyle, implicitTimeFields, options.get("timestyle"));
            if (!fields.isEmpty()) {
                options.put("fields", fields);
            }
        }
        return options;
    }

    private static String implicitSemanticFields(
            String dateStyle, boolean hasTimeStyle, String timeStyle) {
        String dateFields = "full".equals(dateStyle) ? "date,weekday" : "date";
        if (dateStyle != null && hasTimeStyle) {
            return "long".equals(timeStyle) || "full".equals(timeStyle)
                    ? dateFields + ",time,zone"
                    : dateFields + ",time";
        }
        if (dateStyle != null) {
            return dateFields;
        }
        if (hasTimeStyle) {
            return "long".equals(timeStyle) || "full".equals(timeStyle) ? "time,zone" : "time";
        }
        return "";
    }

    private static String semanticNormalizeOptionKey(String value) {
        String normalized = semanticNormalize(value);
        if ("style".equals(normalized) || "datestyle".equals(normalized) || "datelength".equals(normalized)) {
            return "length";
        }
        if ("precision".equals(normalized)) {
            return "timeprecision";
        }
        if ("timestyle".equals(normalized)) {
            return "timestyle";
        }
        if ("hour12".equals(normalized)) {
            return "hourcycle";
        }
        if ("zone".equals(normalized)
                || "timezonename".equals(normalized)
                || "timezonestyle".equals(normalized)) {
            return "zonestyle";
        }
        if ("fractionalseconddigits".equals(normalized)) {
            return "fractionalsecond";
        }
        switch (normalized) {
            case "era":
                return "erastyle";
            case "year":
                return "yearstyle";
            case "month":
                return "monthstyle";
            case "quarter":
                return "quarterstyle";
            case "day":
                return "daystyle";
            case "weekday":
                return "weekdaystyle";
            case "dayperiod":
                return "dayperiodstyle";
            case "hour":
                return "hourstyle";
            case "minute":
                return "minutestyle";
            case "second":
                return "secondstyle";
            default:
                return normalized;
        }
    }

    private static String semanticNormalizeOptionValue(String key, String value) {
        if ("fields".equals(key)) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
        String normalized = semanticNormalize(value);
        if ("yearstyle".equals(key) && "withera".equals(normalized)) {
            return "with-era";
        }
        if (SEMANTIC_STYLE_OPTION_KEYS.contains(key)
                && ("2digit".equals(normalized) || "twodigit".equals(normalized))) {
            return "2-digit";
        }
        if (SEMANTIC_STYLE_OPTION_KEYS.contains(key) && "wide".equals(normalized)) {
            return "long";
        }
        if (SEMANTIC_STYLE_OPTION_KEYS.contains(key) && "abbreviated".equals(normalized)) {
            return "short";
        }
        if ("timeprecision".equals(key) && "short".equals(normalized)) {
            return "minute";
        }
        if ("timeprecision".equals(key) && "medium".equals(normalized)) {
            return "second";
        }
        if ("timeprecision".equals(key) && "minuteoptional".equals(normalized)) {
            return "minute-optional";
        }
        if ("timeprecision".equals(key) && "fractionalsecond".equals(normalized)) {
            return "fractional-second";
        }
        if ("zonestyle".equals(key) && ("shortoffset".equals(normalized) || "longoffset".equals(normalized))) {
            return "offset";
        }
        if ("zonestyle".equals(key) && ("shortgeneric".equals(normalized) || "longgeneric".equals(normalized))) {
            return "generic";
        }
        if ("zonestyle".equals(key) && ("short".equals(normalized) || "long".equals(normalized))) {
            return "specific";
        }
        if ("hourcycle".equals(key) && "true".equals(normalized)) {
            return "clock12";
        }
        if ("hourcycle".equals(key) && "false".equals(normalized)) {
            return "clock24";
        }
        return normalized;
    }

    private static List<String> parseSemanticSkeletonFields(Map<String, String> options)
            throws Mf2Exception {
        String fieldsText = options.get("fields");
        if (fieldsText == null) {
            throw Mf2FunctionSupport.badOption("Date/time semantic skeleton must include fields.");
        }
        List<String> fields = new java.util.ArrayList<>();
        for (String field : fieldsText.split(",")) {
            String normalized = semanticNormalizeField(field);
            List<String> canonicalFields =
                    ("date".equals(normalized) || "yearmonthday".equals(normalized))
                            ? List.of("year", "month", "day")
                            : ("eradate".equals(normalized) || "erayearmonthday".equals(normalized))
                                    ? List.of("era", "year", "month", "day")
                            : ("eradateweekday".equals(normalized)
                                            || "weekdayeradate".equals(normalized)
                                            || "erayearmonthdayweekday".equals(normalized)
                                            || "weekdayerayearmonthday".equals(normalized))
                                    ? List.of("era", "year", "month", "day", "weekday")
                            : ("eradatetime".equals(normalized)
                                            || "erayearmonthdaytime".equals(normalized))
                                    ? List.of("era", "year", "month", "day", "time")
                            : ("eradatetimeweekday".equals(normalized)
                                            || "weekdayeradatetime".equals(normalized)
                                            || "erayearmonthdaytimeweekday".equals(normalized)
                                            || "weekdayerayearmonthdaytime".equals(normalized))
                                    ? List.of("era", "year", "month", "day", "weekday", "time")
                            : ("datetime".equals(normalized) || "yearmonthdaytime".equals(normalized))
                                    ? List.of("year", "month", "day", "time")
                            : ("datetimeweekday".equals(normalized)
                                            || "weekdaydatetime".equals(normalized)
                                            || "yearmonthdaytimeweekday".equals(normalized)
                                            || "weekdayyearmonthdaytime".equals(normalized))
                                    ? List.of("year", "month", "day", "weekday", "time")
                            : ("datetimeweekdayzone".equals(normalized)
                                            || "weekdaydatetimezone".equals(normalized)
                                            || "zoneddatetimeweekday".equals(normalized)
                                            || "zonedweekdaydatetime".equals(normalized)
                                            || "yearmonthdaytimeweekdayzone".equals(normalized)
                                            || "weekdayyearmonthdaytimezone".equals(normalized)
                                            || "zonedyearmonthdaytimeweekday".equals(normalized)
                                            || "zonedweekdayyearmonthdaytime".equals(normalized))
                                    ? List.of("year", "month", "day", "weekday", "time", "zone")
                            : ("eradatetimezone".equals(normalized)
                                            || "zonederadatetime".equals(normalized)
                                            || "erayearmonthdaytimezone".equals(normalized)
                                            || "zonederayearmonthdaytime".equals(normalized))
                                    ? List.of("era", "year", "month", "day", "time", "zone")
                            : ("eradatetimeweekdayzone".equals(normalized)
                                            || "weekdayeradatetimezone".equals(normalized)
                                            || "zonederadatetimeweekday".equals(normalized)
                                            || "zonedweekdayeradatetime".equals(normalized)
                                            || "erayearmonthdaytimeweekdayzone".equals(normalized)
                                            || "weekdayerayearmonthdaytimezone".equals(normalized)
                                            || "zonederayearmonthdaytimeweekday".equals(normalized)
                                            || "zonedweekdayerayearmonthdaytime".equals(normalized))
                                    ? List.of(
                                            "era",
                                            "year",
                                            "month",
                                            "day",
                                            "weekday",
                                            "time",
                                            "zone")
                                    : ("dateweekday".equals(normalized)
                                                    || "weekdaydate".equals(normalized)
                                                    || "yearmonthdayweekday".equals(normalized)
                                                    || "weekdayyearmonthday".equals(normalized))
                                            ? List.of("year", "month", "day", "weekday")
                                            : ("datetimezone".equals(normalized)
                                                            || "zoneddatetime".equals(normalized)
                                                            || "yearmonthdaytimezone".equals(normalized)
                                                            || "zonedyearmonthdaytime".equals(normalized))
                                                    ? List.of("year", "month", "day", "time", "zone")
                                                    : "yearmonth".equals(normalized)
                                                            ? List.of("year", "month")
                                                            : "erayearmonth".equals(normalized)
                                                                    ? List.of("era", "year", "month")
                                                            : "yearquarter".equals(normalized)
                                                                    ? List.of("year", "quarter")
                                                                    : "erayearquarter".equals(normalized)
                                                                            ? List.of("era", "year", "quarter")
                                                                            : "yearweek".equals(normalized)
                                                                                    ? List.of("year", "weekofyear")
                                                                                    : "erayearweek".equals(normalized)
                                                                                            ? List.of("era", "year", "weekofyear")
                                                                                            : "erayear".equals(normalized)
                                                                                                    ? List.of("era", "year")
                                                                                                    : "monthweek".equals(normalized)
                                                                                                            ? List.of("month", "weekofmonth")
                                                                                                            : "yearmonthweek".equals(normalized)
                                                                                                                    ? List.of("year", "month", "weekofmonth")
                                                                                                                    : "erayearmonthweek"
                                                                                                                                    .equals(normalized)
                                                                                                                            ? List.of("era", "year", "month", "weekofmonth")
                                                                                                                            : "monthday".equals(normalized)
                                                                                                                                    ? List.of("month", "day")
                                                                                                                                    : List.of(normalized);
            for (String canonical : canonicalFields) {
                if (!SEMANTIC_FIELD_ORDER.contains(canonical) || fields.contains(canonical)) {
                    throw Mf2FunctionSupport.badOption("Invalid date/time semantic skeleton field.");
                }
                fields.add(canonical);
            }
        }
        if (fields.isEmpty()) {
            throw Mf2FunctionSupport.badOption("Date/time semantic skeleton must include fields.");
        }
        return fields;
    }

    private static String semanticNormalizeField(String value) {
        String normalized = semanticNormalize(value);
        if ("dayofmonth".equals(normalized)) {
            return "day";
        }
        if ("dayofweek".equals(normalized)) {
            return "weekday";
        }
        if ("monthofyear".equals(normalized)) {
            return "month";
        }
        if ("quarterofyear".equals(normalized)) {
            return "quarter";
        }
        if ("yearofera".equals(normalized)) {
            return "year";
        }
        if ("week".equals(normalized)) {
            return "weekofyear";
        }
        if ("weekofyear".equals(normalized)) {
            return "weekofyear";
        }
        if ("weekofmonth".equals(normalized)) {
            return "weekofmonth";
        }
        if ("dayofyear".equals(normalized)) {
            return "dayofyear";
        }
        if ("dayofweekinmonth".equals(normalized)) {
            return "dayofweekinmonth";
        }
        if ("modifiedjulianday".equals(normalized)) {
            return "modifiedjulianday";
        }
        if ("millisecondsinday".equals(normalized)) {
            return "millisecondsinday";
        }
        if ("fractionalseconddigits".equals(normalized)) {
            return "fractionalsecond";
        }
        if ("dayperiod".equals(normalized)) {
            return "dayperiod";
        }
        if ("hourofday".equals(normalized)) {
            return "hour";
        }
        if ("minuteofhour".equals(normalized)) {
            return "minute";
        }
        if ("secondofminute".equals(normalized)) {
            return "second";
        }
        if ("timezonename".equals(normalized)) {
            return "zone";
        }
        if ("timezone".equals(normalized)) {
            return "zone";
        }
        return normalized;
    }

    private static void validateSemanticSkeleton(List<String> fields, Map<String, String> options)
            throws Mf2Exception {
        String dateKey = semanticFieldSetKey(fields, SEMANTIC_DATE_FIELD_ORDER);
        String timeKey = semanticFieldSetKey(fields, SEMANTIC_TIME_FIELD_ORDER);
        boolean hasDateFields = !dateKey.isEmpty();
        boolean hasExplicitTime = !timeKey.isEmpty();
        boolean hasTime = fields.contains("time") || hasExplicitTime;
        boolean hasZone = fields.contains("zone");
        boolean hasDayPeriod = fields.contains("dayperiod");
        boolean validDateFields =
                hasTime || hasZone
                        ? !hasDateFields || SEMANTIC_DATE_FIELD_SETS.contains(dateKey)
                        : !hasDateFields
                                || SEMANTIC_DATE_FIELD_SETS.contains(dateKey)
                                || SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.contains(dateKey);
        boolean validFieldSet =
                hasDayPeriod
                        ? validDateFields && (hasTime || !hasZone)
                        : hasTime || hasZone
                                ? !hasDateFields || SEMANTIC_DATE_FIELD_SETS.contains(dateKey)
                                : SEMANTIC_DATE_FIELD_SETS.contains(dateKey)
                                        || SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.contains(dateKey);
        if (!validFieldSet) {
            throw Mf2FunctionSupport.badOption("Invalid date/time semantic skeleton field set.");
        }
        if (fields.contains("time") && hasExplicitTime) {
            throw Mf2FunctionSupport.badOption(
                    "time field cannot be combined with explicit time component fields.");
        }
        if (options.containsKey("timestyle") && options.containsKey("timeprecision")) {
            throw Mf2FunctionSupport.badOption("timeStyle cannot be combined with timePrecision.");
        }
        String timeStyle = options.get("timestyle");
        if (options.containsKey("timestyle") && !fields.contains("time")) {
            throw Mf2FunctionSupport.badOption("timeStyle requires the time field.");
        }
        if (semanticTimeStyleHasZone(timeStyle) && !hasZone) {
            throw Mf2FunctionSupport.badOption("timeStyle=long/full requires the zone field.");
        }
        if (semanticTimeStyleHasZone(timeStyle) && options.containsKey("zonestyle")) {
            throw Mf2FunctionSupport.badOption("timeStyle=long/full cannot be combined with zoneStyle.");
        }
        if (hasExplicitTime && !SEMANTIC_TIME_FIELD_SETS.contains(timeKey)) {
            throw Mf2FunctionSupport.badOption("Invalid date/time semantic skeleton time field set.");
        }
        if (hasExplicitTime && options.containsKey("timeprecision")) {
            throw Mf2FunctionSupport.badOption("timePrecision requires the time field.");
        }
        if (hasExplicitTime && options.containsKey("fractionalsecond") && !fields.contains("fractionalsecond")) {
            throw Mf2FunctionSupport.badOption("fractionalSecond requires the fractionalSecond field.");
        }
        if (fields.contains("fractionalsecond")) {
            semanticFractionalSecondWidth(options);
        }
        if (hasExplicitTime
                && !fields.contains("hour")
                && (options.containsKey("hourcycle") || hasDayPeriod)) {
            throw Mf2FunctionSupport.badOption("hourCycle and dayPeriod require the hour field.");
        }
        if (!fields.contains("hour") && options.containsKey("hourstyle")) {
            throw Mf2FunctionSupport.badOption("hourStyle requires the hour field.");
        }
        if (!fields.contains("minute") && options.containsKey("minutestyle")) {
            throw Mf2FunctionSupport.badOption("minuteStyle requires the minute field.");
        }
        if (!fields.contains("second") && options.containsKey("secondstyle")) {
            throw Mf2FunctionSupport.badOption("secondStyle requires the second field.");
        }
        if (!fields.contains("year") && options.containsKey("yearstyle")) {
            throw Mf2FunctionSupport.badOption("yearStyle requires the year field.");
        }
        if (!fields.contains("era") && options.containsKey("erastyle")) {
            throw Mf2FunctionSupport.badOption("eraStyle requires the era field.");
        }
        if (!fields.contains("month") && options.containsKey("monthstyle")) {
            throw Mf2FunctionSupport.badOption("monthStyle requires the month field.");
        }
        if (!fields.contains("quarter") && options.containsKey("quarterstyle")) {
            throw Mf2FunctionSupport.badOption("quarterStyle requires the quarter field.");
        }
        if (!fields.contains("day") && options.containsKey("daystyle")) {
            throw Mf2FunctionSupport.badOption("dayStyle requires the day field.");
        }
        if (!fields.contains("weekday") && options.containsKey("weekdaystyle")) {
            throw Mf2FunctionSupport.badOption("weekdayStyle requires the weekday field.");
        }
        if (!hasDayPeriod && options.containsKey("dayperiodstyle")) {
            throw Mf2FunctionSupport.badOption("dayPeriodStyle requires the dayPeriod field.");
        }
        if (!hasTime
                && (options.containsKey("timeprecision")
                        || options.containsKey("timestyle")
                        || options.containsKey("fractionalsecond")
                        || options.containsKey("hourcycle"))) {
            throw Mf2FunctionSupport.badOption("timePrecision and hourCycle require the time field.");
        }
        if (!hasZone && options.containsKey("zonestyle")) {
            throw Mf2FunctionSupport.badOption("zoneStyle requires the zone field.");
        }
        if (!(fields.contains("year")
                        || fields.contains("quarter")
                        || fields.contains("month")
                        || fields.contains("day")
                        || fields.contains("dayofyear")
                        || fields.contains("dayofweekinmonth")
                        || fields.contains("modifiedjulianday")
                        || hasTime)
                && options.containsKey("alignment")) {
            throw Mf2FunctionSupport.badOption("alignment requires a date or time field.");
        }
    }

    private static String semanticOption(
            Map<String, String> options,
            String key,
            String fallback,
            List<String> allowedValues) throws Mf2Exception {
        String value = options.getOrDefault(key, fallback);
        if (!allowedValues.contains(value)) {
            throw Mf2FunctionSupport.badOption(
                    "Date/time semantic skeleton "
                            + key
                            + " must be one of "
                            + String.join(", ", allowedValues)
                            + ".");
        }
        return value;
    }

    private static String semanticNormalize(String value) {
        return value.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String semanticFieldSetKey(List<String> fields, List<String> order) {
        List<String> output = new java.util.ArrayList<>();
        for (String field : order) {
            if (fields.contains(field)) {
                output.add(field);
            }
        }
        return String.join(",", output);
    }

    private static Map<Character, Integer> semanticDateFieldWidths(
            CldrDateTimeData.LocaleData localeData,
            String length) {
        Map<Character, Integer> widths = new java.util.HashMap<>();
        for (Map.Entry<Character, Integer> field : patternFieldRuns(localeData.dateFormats().getOrDefault(length, "")).entrySet()) {
            char symbol = field.getKey();
            if (symbol == 'G' || isYearField(symbol) || isMonthField(symbol) || symbol == 'd') {
                setSkeletonWidth(widths, symbol, field.getValue());
            }
        }
        if (!widths.keySet().stream().anyMatch(Mf2DateTimeCore::isYearField)) {
            setSkeletonWidth(widths, 'y', "short".equals(length) ? 2 : 1);
        }
        if (!widths.keySet().stream().anyMatch(Mf2DateTimeCore::isMonthField)) {
            setSkeletonWidth(widths, 'M', isWideLength(length) ? 4 : "medium".equals(length) ? 3 : 1);
        }
        widths.putIfAbsent('d', 1);
        return widths;
    }

    private static Map<Character, Integer> patternFieldRuns(String pattern) {
        Map<Character, Integer> fields = new java.util.HashMap<>();
        boolean inQuote = false;
        int index = 0;
        while (index < pattern.length()) {
            char symbol = pattern.charAt(index);
            if (symbol == '\'') {
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
                    index += 2;
                } else {
                    inQuote = !inQuote;
                    index++;
                }
            } else if (!inQuote && isAsciiLetter(symbol)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == symbol) {
                    end++;
                }
                setSkeletonWidth(fields, symbol, end - index);
                index = end;
            } else {
                index++;
            }
        }
        return fields;
    }

    private static String semanticEraSkeleton(
            Map<Character, Integer> dateWidths, String length, String eraStyle) {
        int width =
                "auto".equals(eraStyle)
                        ? dateWidths.getOrDefault('G', isWideLength(length) ? 4 : 1)
                        : eraStyleWidth(eraStyle);
        return "G".repeat(width);
    }

    private static int eraStyleWidth(String style) {
        return "long".equals(style) ? 4 : "narrow".equals(style) ? 5 : 1;
    }

    private static String semanticYearSkeleton(
            Map<Character, Integer> dateWidths, String yearStyle, boolean includeEra) {
        char yearSymbol = dateWidths.containsKey('y') ? 'y' : dateWidths.containsKey('u') ? 'u' : dateWidths.containsKey('r') ? 'r' : 'y';
        int sourceWidth = dateWidths.getOrDefault(yearSymbol, 1);
        int yearWidth = semanticYearWidth(sourceWidth, yearStyle);
        StringBuilder skeleton = new StringBuilder();
        if (includeEra && dateWidths.containsKey('G')) {
            skeleton.append("G".repeat(dateWidths.get('G')));
        }
        if (includeEra && "with-era".equals(yearStyle) && !dateWidths.containsKey('G')) {
            skeleton.append('G');
        }
        skeleton.append(String.valueOf(yearSymbol).repeat(yearWidth));
        return skeleton.toString();
    }

    private static int semanticYearWidth(int sourceWidth, String yearStyle) {
        if ("auto".equals(yearStyle)) {
            return sourceWidth;
        }
        if ("2-digit".equals(yearStyle)) {
            return 2;
        }
        if ("numeric".equals(yearStyle)) {
            return 1;
        }
        return sourceWidth == 2 ? 1 : sourceWidth;
    }

    private static String semanticQuarterSkeleton(
            List<String> fields, String length, String alignment, String quarterStyle) {
        char symbol = fields.size() == 1 ? 'q' : 'Q';
        int width = "auto".equals(quarterStyle) ? lengthStyleWidth(length) : dateFieldStyleWidth(quarterStyle);
        if ("column".equals(alignment) && width < 3) {
            width = Math.max(width, 2);
        }
        return String.valueOf(symbol).repeat(width);
    }

    private static String semanticMonthSkeleton(
            List<String> fields,
            Map<Character, Integer> dateWidths,
            String length,
            String alignment,
            String monthStyle) {
        char symbol;
        int width;
        if (fields.size() == 1) {
            symbol = 'L';
            width = "auto".equals(monthStyle) ? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle);
        } else {
            symbol = dateWidths.containsKey('M') ? 'M' : dateWidths.containsKey('L') ? 'L' : 'M';
            width =
                    "auto".equals(monthStyle)
                            ? dateWidths.getOrDefault(symbol, lengthStyleWidth(length))
                            : dateFieldStyleWidth(monthStyle);
        }
        if ("column".equals(alignment) && width < 3) {
            width = Math.max(width, 2);
        }
        return String.valueOf(symbol).repeat(width);
    }

    private static String semanticDaySkeleton(
            Map<Character, Integer> dateWidths, String alignment, String dayStyle) {
        int width =
                "auto".equals(dayStyle)
                        ? dateWidths.getOrDefault('d', 1)
                        : dateFieldStyleWidth(dayStyle);
        if ("column".equals(alignment) && width < 3) {
            width = Math.max(width, 2);
        }
        return "d".repeat(width);
    }

    private static int lengthStyleWidth(String length) {
        return isWideLength(length) ? 4 : "medium".equals(length) ? 3 : 1;
    }

    private static boolean isWideLength(String length) {
        return "full".equals(length) || "long".equals(length);
    }

    private static int dateFieldStyleWidth(String style) {
        return switch (style) {
            case "numeric" -> 1;
            case "2-digit" -> 2;
            case "short" -> 3;
            case "long" -> 4;
            default -> 5;
        };
    }

    private static String semanticWeekdaySkeleton(List<String> fields, String length, String weekdayStyle) {
        if ("short".equals(weekdayStyle)) {
            return "EEE";
        }
        if ("long".equals(weekdayStyle)) {
            return "EEEE";
        }
        if ("narrow".equals(weekdayStyle)) {
            return "EEEEE";
        }
        if (fields.size() == 1 && "short".equals(length)) {
            return "EEEEE";
        }
        return isWideLength(length) ? "EEEE" : "EEE";
    }

    private static String semanticDayPeriodSkeleton(String length, String dayPeriodStyle) {
        String style = "auto".equals(dayPeriodStyle) ? length : dayPeriodStyle;
        return "B".repeat(
                isWideLength(style)
                        ? 4
                        : "narrow".equals(style)
                                        || ("auto".equals(dayPeriodStyle) && "short".equals(length))
                                ? 5
                                : 1);
    }

    private static boolean hasSemanticTimeComponents(List<String> fields) {
        return fields.contains("hour")
                || fields.contains("minute")
                || fields.contains("second")
                || fields.contains("fractionalsecond")
                || fields.contains("millisecondsinday");
    }

    private static String semanticExplicitTimeSkeleton(
            List<String> fields, String hourCycle, String alignment, Map<String, String> options) throws Mf2Exception {
        boolean hasHour = fields.contains("hour");
        boolean hasMinute = fields.contains("minute");
        boolean hasSecond = fields.contains("second");
        boolean hasFractionalSecond = fields.contains("fractionalsecond");
        boolean hasMillisecondsInDay = fields.contains("millisecondsinday");
        StringBuilder skeleton = new StringBuilder();
        if (hasHour) {
            skeleton.append(
                    String.valueOf(semanticHourSymbol(hourCycle))
                            .repeat(semanticNumericFieldWidth(
                                    options, "hourstyle", "column".equals(alignment) ? 2 : 1)));
        }
        if (hasMinute) {
            skeleton.append(
                    "m".repeat(
                            semanticNumericFieldWidth(
                                    options,
                                    "minutestyle",
                                    !hasHour && !hasSecond && "column".equals(alignment) ? 2 : 1)));
        }
        if (hasSecond) {
            skeleton.append(
                    "s".repeat(
                            semanticNumericFieldWidth(
                                    options,
                                    "secondstyle",
                                    !hasHour && !hasMinute && "column".equals(alignment) ? 2 : 1)));
        }
        if (hasFractionalSecond) {
            skeleton.append("S".repeat(semanticFractionalSecondWidth(options)));
        }
        if (hasMillisecondsInDay) {
            skeleton.append("A".repeat("column".equals(alignment) ? 8 : 1));
        }
        return skeleton.toString();
    }

    private static int semanticNumericFieldWidth(
            Map<String, String> options, String key, int fallbackWidth) {
        String style = options.getOrDefault(key, "auto");
        if ("auto".equals(style)) {
            return fallbackWidth;
        }
        if ("2-digit".equals(style)) {
            return 2;
        }
        return 1;
    }

    private static int semanticFractionalSecondWidth(Map<String, String> options) throws Mf2Exception {
        String text = options.getOrDefault("fractionalsecond", "");
        if (text.length() != 1 || text.charAt(0) < '1' || text.charAt(0) > '9') {
            throw Mf2FunctionSupport.badOption("Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.");
        }
        return text.charAt(0) - '0';
    }

    private static String semanticTimeSkeleton(
            String timePrecision,
            String hourCycle,
            String alignment,
            ZonedDateTime date,
            Map<String, String> options) throws Mf2Exception {
        StringBuilder skeleton = new StringBuilder();
        skeleton.append(String.valueOf(semanticHourSymbol(hourCycle)).repeat("column".equals(alignment) ? 2 : 1));
        if (List.of("minute", "second", "fractional-second").contains(timePrecision)) {
            skeleton.append('m');
        }
        if ("minute-optional".equals(timePrecision) && date.getMinute() != 0) {
            skeleton.append('m');
        }
        if (List.of("second", "fractional-second").contains(timePrecision)) {
            skeleton.append('s');
        }
        if ("fractional-second".equals(timePrecision)) {
            skeleton.append("S".repeat(semanticFractionalSecondWidth(options)));
        } else if (options.containsKey("fractionalsecond")) {
            throw Mf2FunctionSupport.badOption("fractionalSecond requires timePrecision=fractional-second.");
        }
        return skeleton.toString();
    }

    private static String semanticTimeStylePrecision(String timeStyle, String timePrecision) {
        return switch (timeStyle) {
            case "short" -> "minute";
            case "medium", "long", "full" -> "second";
            default -> timePrecision;
        };
    }

    private static String semanticTimeStyleZoneStyle(String timeStyle, String zoneStyle) {
        return semanticTimeStyleHasZone(timeStyle) ? "specific" : zoneStyle;
    }

    private static boolean semanticTimeStyleHasZone(String timeStyle) {
        return "long".equals(timeStyle) || "full".equals(timeStyle);
    }

    private static char semanticHourSymbol(String hourCycle) {
        return switch (hourCycle) {
            case "h11" -> 'K';
            case "h12", "clock12" -> 'h';
            case "h23", "clock24" -> 'H';
            case "h24" -> 'k';
            default -> 'C';
        };
    }

    private static String semanticZoneSkeleton(String zoneStyle, boolean standalone, String length) {
        String style = "auto".equals(zoneStyle) ? "generic" : zoneStyle;
        return switch (style) {
            case "specific" -> standalone && !"short".equals(length) ? "zzzz" : "z";
            case "location" -> "VVVV";
            case "offset" -> "O";
            default -> standalone && !"short".equals(length) ? "vvvv" : "v";
        };
    }

    private static void applyCHourFormat(
            Map<Character, Integer> widths,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle,
            int width) {
        if (hourCycle != null) {
            char hourSymbol = preferredHourSymbol(localeData, hourCycle);
            setSkeletonWidth(widths, hourSymbol, cHourWidth(width));
            if (isHour12Field(hourSymbol)) {
                setSkeletonWidth(widths, 'B', dayPeriodWidthForC(width));
            }
            return;
        }
        for (String token : localeData.allowedHourFormats().split("\\s+")) {
            if (!isCHourFormatToken(token)) {
                continue;
            }
            setSkeletonWidth(widths, token.charAt(0), cHourWidth(width));
            if (token.length() > 1) {
                setSkeletonWidth(widths, token.charAt(1), dayPeriodWidthForC(width));
            }
            return;
        }
        setSkeletonWidth(widths, preferredHourSymbol(localeData, hourCycle), cHourWidth(width));
    }

    private static boolean isCHourFormatToken(String token) {
        return token.matches("[hHkK][bB]?");
    }

    private static void setSkeletonWidth(Map<Character, Integer> widths, char symbol, int width) {
        widths.put(symbol, Math.max(widths.getOrDefault(symbol, 0), width));
    }

    private static char normalizeSkeletonSymbol(
            char symbol,
            CldrDateTimeData.LocaleData localeData,
            String hourCycle) {
        return switch (symbol) {
            case 'l' -> 'L';
            case 'j', 'J' -> preferredHourSymbol(localeData, hourCycle);
            default -> symbol;
        };
    }

    private static int cHourWidth(int width) {
        return width % 2 == 0 ? 2 : 1;
    }

    private static int dayPeriodWidthForC(int width) {
        if (width >= 5) {
            return 5;
        }
        return width >= 3 ? 4 : 1;
    }

    private static boolean shouldSuppressDayPeriod(String skeleton) {
        return skeleton.indexOf('J') >= 0
                && skeleton.indexOf('a') < 0
                && skeleton.indexOf('b') < 0
                && skeleton.indexOf('B') < 0
                && skeleton.indexOf('C') < 0;
    }

    private static String stripDayPeriodPatternFields(String pattern) {
        StringBuilder output = new StringBuilder();
        StringBuilder pendingWhitespace = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char ch = pattern.charAt(index);
            if (ch == '\'') {
                QuotedPattern quoted = readQuotedPattern(pattern, index);
                output.append(pendingWhitespace).append(pattern, index, quoted.nextIndex());
                pendingWhitespace.setLength(0);
                index = quoted.nextIndex();
            } else if (isAsciiLetter(ch)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == ch) {
                    end++;
                }
                if (isDayPeriodField(ch)) {
                    pendingWhitespace.setLength(0);
                } else {
                    output.append(pendingWhitespace).append(pattern, index, end);
                    pendingWhitespace.setLength(0);
                }
                index = end;
            } else if (isPatternWhitespace(ch)) {
                pendingWhitespace.append(ch);
                index++;
            } else {
                output.append(pendingWhitespace).append(ch);
                pendingWhitespace.setLength(0);
                index++;
            }
        }
        output.append(pendingWhitespace);
        return trimPatternWhitespace(output.toString());
    }

    private static String trimPatternWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPatternWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPatternWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPatternWhitespace(char value) {
        return value == ' ' || value == '\u00A0' || value == '\u202F' || Character.isWhitespace(value);
    }

    private static char preferredHourSymbol(CldrDateTimeData.LocaleData localeData, String hourCycle) {
        if ("h11".equals(hourCycle)) {
            return 'K';
        }
        if ("h12".equals(hourCycle)) {
            return 'h';
        }
        if ("h23".equals(hourCycle)) {
            return 'H';
        }
        if ("h24".equals(hourCycle)) {
            return 'k';
        }
        String shortTime = localeData.timeFormats().getOrDefault("short", "");
        if (shortTime.indexOf('H') >= 0) {
            return 'H';
        }
        if (shortTime.indexOf('k') >= 0) {
            return 'k';
        }
        if (shortTime.indexOf('K') >= 0) {
            return 'K';
        }
        return 'h';
    }

    private static String skeletonFieldSet(String skeleton) {
        StringBuilder normalized = new StringBuilder();
        for (Character symbol : skeletonWidths(skeleton).keySet()) {
            normalized.append(fieldSetSymbol(symbol));
        }
        StringBuilder output = new StringBuilder();
        for (int orderIndex = 0; orderIndex < SKELETON_FIELD_ORDER.length(); orderIndex++) {
            char symbol = SKELETON_FIELD_ORDER.charAt(orderIndex);
            if (normalized.indexOf(String.valueOf(symbol)) >= 0) {
                output.append(symbol);
            }
        }
        return output.toString();
    }

    private static char fieldSetSymbol(char symbol) {
        if (isYearField(symbol)) {
            return 'y';
        }
        if (isHourField(symbol)) {
            return 'J';
        }
        if (isMonthField(symbol)) {
            return 'M';
        }
        if (isQuarterField(symbol)) {
            return 'Q';
        }
        if (isDayPeriodField(symbol)) {
            return 'B';
        }
        if (isWeekdayField(symbol)) {
            return 'E';
        }
        if (isTimeZoneField(symbol)) {
            return 'v';
        }
        return symbol;
    }

    private static int skeletonDistance(String requested, String candidate) {
        Map<Character, Integer> requestedWidths = skeletonWidths(requested);
        Map<Character, Integer> candidateWidths = skeletonWidths(candidate);
        int distance = 0;
        for (Map.Entry<Character, Integer> entry : requestedWidths.entrySet()) {
            int requestedWidth = entry.getValue();
            Character candidateSymbol = candidateSymbolForRequested(entry.getKey(), candidateWidths);
            int candidateWidth = candidateSymbol == null ? 0 : candidateWidths.get(candidateSymbol);
            distance += Math.abs(requestedWidth - candidateWidth);
            if (isTextWidth(requestedWidth) != isTextWidth(candidateWidth)) {
                distance += 8;
            }
            distance += hourFieldDistance(entry.getKey(), candidateSymbol);
        }
        return distance;
    }

    private static Map<Character, Integer> skeletonWidths(String skeleton) {
        Map<Character, Integer> widths = new java.util.HashMap<>();
        int index = 0;
        while (index < skeleton.length()) {
            char symbol = skeleton.charAt(index);
            int end = index + 1;
            while (end < skeleton.length() && skeleton.charAt(end) == symbol) {
                end++;
            }
            widths.put(symbol, Math.max(widths.getOrDefault(symbol, 0), end - index));
            index = end;
        }
        return widths;
    }

    private static boolean isTextWidth(int width) {
        return width >= 3;
    }

    private static boolean isHourField(char symbol) {
        return SKELETON_HOUR_FIELDS.indexOf(symbol) >= 0;
    }

    private static boolean isYearField(char symbol) {
        return symbol == 'y' || symbol == 'u' || symbol == 'r';
    }

    private static boolean isWeekdayField(char symbol) {
        return symbol == 'E' || symbol == 'e' || symbol == 'c';
    }

    private static boolean isMonthField(char symbol) {
        return symbol == 'M' || symbol == 'L';
    }

    private static boolean isQuarterField(char symbol) {
        return symbol == 'Q' || symbol == 'q';
    }

    private static boolean isDayPeriodField(char symbol) {
        return symbol == 'a' || symbol == 'b' || symbol == 'B';
    }

    private static boolean isSyntheticNumericField(char symbol) {
        return symbol == 'D' || symbol == 'F' || symbol == 'g' || symbol == 'm' || symbol == 's' || symbol == 'A';
    }

    private static boolean isTimeZoneField(char symbol) {
        return symbol == 'z'
                || symbol == 'Z'
                || symbol == 'O'
                || symbol == 'v'
                || symbol == 'V'
                || symbol == 'X'
                || symbol == 'x';
    }

    private static Character candidateSymbolForRequested(
            char symbol,
            Map<Character, Integer> candidateWidths) {
        if (candidateWidths.containsKey(symbol)) {
            return symbol;
        }
        if (isYearField(symbol)) {
            for (char candidate : new char[] {'y', 'u', 'r'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (isHourField(symbol)) {
            for (int index = 0; index < SKELETON_HOUR_FIELDS.length(); index++) {
                char candidate = SKELETON_HOUR_FIELDS.charAt(index);
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        if (isQuarterField(symbol)) {
            for (char candidate : new char[] {'Q', 'q'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (isMonthField(symbol)) {
            for (char candidate : new char[] {'M', 'L'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (isDayPeriodField(symbol)) {
            for (char candidate : new char[] {'B', 'b', 'a'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (isWeekdayField(symbol)) {
            for (char candidate : new char[] {'E', 'e', 'c'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (isTimeZoneField(symbol)) {
            for (char candidate : new char[] {'v', 'z', 'O', 'Z', 'X', 'x', 'V'}) {
                if (candidateWidths.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static int hourFieldDistance(char requestedSymbol, Character candidateSymbol) {
        if (candidateSymbol == null
                || requestedSymbol == candidateSymbol
                || !isHourField(requestedSymbol)
                || !isHourField(candidateSymbol)) {
            return 0;
        }
        return isHour12Field(requestedSymbol) == isHour12Field(candidateSymbol) ? 1 : 4;
    }

    private static boolean isHour12Field(char symbol) {
        return symbol == 'h' || symbol == 'K';
    }

    private static char requestedSymbolForPattern(
            char symbol,
            Map<Character, Integer> requestedWidths,
            Map<Character, Integer> candidateWidths) {
        if (isYearField(symbol)) {
            Character requestedSymbol = candidateSymbolForRequested(symbol, requestedWidths);
            return candidateSymbolForRequested(symbol, candidateWidths) == null || requestedSymbol == null
                    ? symbol
                    : requestedSymbol;
        }
        if (isWeekdayField(symbol)) {
            return candidateSymbolForRequested(symbol, candidateWidths) == null
                    ? symbol
                    : requestedWeekdaySymbolForPattern(symbol, requestedWidths);
        }
        if (isDayPeriodField(symbol)) {
            return candidateSymbolForRequested(symbol, candidateWidths) == null
                    ? symbol
                    : requestedDayPeriodSymbolForPattern(symbol, requestedWidths);
        }
        if (isTimeZoneField(symbol)) {
            return candidateSymbolForRequested(symbol, candidateWidths) == null
                    ? symbol
                    : requestedTimeZoneSymbolForPattern(symbol, requestedWidths);
        }
        if ((!isYearField(symbol)
                        && !isHourField(symbol)
                        && !isMonthField(symbol)
                        && !isQuarterField(symbol)
                        && !isDayPeriodField(symbol)
                        && !isTimeZoneField(symbol))
                || candidateSymbolForRequested(symbol, candidateWidths) == null) {
            return symbol;
        }
        Character requestedSymbol = candidateSymbolForRequested(symbol, requestedWidths);
        return requestedSymbol == null ? symbol : requestedSymbol;
    }

    private static char requestedWeekdaySymbolForPattern(
            char symbol,
            Map<Character, Integer> requestedWidths) {
        if (requestedWidths.containsKey('c')) {
            return 'c';
        }
        if (requestedWidths.containsKey('e')) {
            return 'e';
        }
        if (requestedWidths.containsKey('E')) {
            return 'E';
        }
        return symbol;
    }

    private static char requestedDayPeriodSymbolForPattern(
            char symbol,
            Map<Character, Integer> requestedWidths) {
        if (requestedWidths.containsKey('a')) {
            return 'a';
        }
        if (requestedWidths.containsKey('b')) {
            return 'b';
        }
        if (requestedWidths.containsKey('B')) {
            return 'B';
        }
        return symbol;
    }

    private static char requestedTimeZoneSymbolForPattern(
            char symbol,
            Map<Character, Integer> requestedWidths) {
        for (char timeZoneSymbol : new char[] {'z', 'Z', 'O', 'v', 'V', 'X', 'x'}) {
            if (requestedWidths.containsKey(timeZoneSymbol)) {
                return timeZoneSymbol;
            }
        }
        return symbol;
    }

    private static Integer widthForPatternSymbol(char symbol, Map<Character, Integer> widths) {
        Integer width = widths.get(symbol);
        if (width != null) {
            return width;
        }
        if (isYearField(symbol)) {
            for (char yearSymbol : new char[] {'y', 'u', 'r'}) {
                width = widths.get(yearSymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        if (isWeekdayField(symbol)) {
            for (char weekdaySymbol : new char[] {'E', 'e', 'c'}) {
                width = widths.get(weekdaySymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        if (isMonthField(symbol)) {
            for (char monthSymbol : new char[] {'M', 'L'}) {
                width = widths.get(monthSymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        if (isDayPeriodField(symbol)) {
            for (char dayPeriodSymbol : new char[] {'B', 'b', 'a'}) {
                width = widths.get(dayPeriodSymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        if (isQuarterField(symbol)) {
            for (char quarterSymbol : new char[] {'Q', 'q'}) {
                width = widths.get(quarterSymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        if (isTimeZoneField(symbol)) {
            for (char timeZoneSymbol : new char[] {'z', 'Z', 'O', 'v', 'V', 'X', 'x'}) {
                width = widths.get(timeZoneSymbol);
                if (width != null) {
                    return width;
                }
            }
        }
        return null;
    }

    private static String adjustPatternWidths(
            String pattern,
            String requestedSkeleton,
            String candidateSkeleton) {
        Map<Character, Integer> requestedWidths = skeletonWidths(requestedSkeleton);
        Map<Character, Integer> candidateWidths = skeletonWidths(candidateSkeleton);
        StringBuilder output = new StringBuilder();
        boolean inQuote = false;
        int index = 0;
        while (index < pattern.length()) {
            char ch = pattern.charAt(index);
            if (ch == '\'') {
                output.append(ch);
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
                    output.append('\'');
                    index += 2;
                } else {
                    inQuote = !inQuote;
                    index++;
                }
            } else if (!inQuote && isAsciiLetter(ch)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == ch) {
                    end++;
                }
                char requestedSymbol = requestedSymbolForPattern(ch, requestedWidths, candidateWidths);
                Integer requestedWidth = widthForPatternSymbol(ch, requestedWidths);
                Integer candidateWidth = widthForPatternSymbol(ch, candidateWidths);
                int patternWidth = end - index;
                output.append(String.valueOf(requestedSymbol).repeat(
                        shouldAdjustPatternWidth(requestedSymbol, requestedWidth, candidateWidth, patternWidth)
                                ? requestedWidth
                                : patternWidth));
                index = end;
            } else {
                output.append(ch);
                index++;
            }
        }
        return output.toString();
    }

    private static boolean shouldAdjustPatternWidth(
            char symbol,
            Integer requestedWidth,
            Integer candidateWidth,
            int patternWidth) {
        if (requestedWidth == null || candidateWidth == null) {
            return false;
        }
        if ((symbol == 'e' || symbol == 'c') && patternWidth >= 3 && requestedWidth <= 2) {
            return true;
        }
        if (isWeekdayField(symbol) && patternWidth >= 3 && requestedWidth >= 4) {
            return true;
        }
        return patternWidth == candidateWidth;
    }

    private static String[] splitDateTimeSkeleton(String skeleton) {
        StringBuilder dateSkeleton = new StringBuilder();
        StringBuilder timeSkeleton = new StringBuilder();
        for (int index = 0; index < skeleton.length(); index++) {
            char symbol = skeleton.charAt(index);
            if (SKELETON_TIME_FIELDS.indexOf(symbol) >= 0) {
                timeSkeleton.append(symbol);
            } else {
                dateSkeleton.append(symbol);
            }
        }
        return new String[] {dateSkeleton.toString(), timeSkeleton.toString()};
    }

    private static String formatPattern(
            String pattern,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData) throws Mf2Exception {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char ch = pattern.charAt(index);
            if (ch == '\'') {
                QuotedPattern quoted = readQuotedPattern(pattern, index);
                output.append(quoted.value());
                index = quoted.nextIndex();
            } else if (isAsciiLetter(ch)) {
                int end = index + 1;
                while (end < pattern.length() && pattern.charAt(end) == ch) {
                    end++;
                }
                output.append(formatField(ch, end - index, date, localeData));
                index = end;
            } else {
                output.append(ch);
                index++;
            }
        }
        return output.toString();
    }

    private static QuotedPattern readQuotedPattern(String pattern, int start) {
        if (start + 1 < pattern.length() && pattern.charAt(start + 1) == '\'') {
            return new QuotedPattern("'", start + 2);
        }
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < pattern.length()) {
            if (pattern.charAt(index) == '\'') {
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
                    value.append('\'');
                    index += 2;
                } else {
                    return new QuotedPattern(value.toString(), index + 1);
                }
            } else {
                value.append(pattern.charAt(index));
                index++;
            }
        }
        return new QuotedPattern(value.toString(), index);
    }

    private static String formatField(
            char symbol,
            int count,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData) throws Mf2Exception {
        return switch (symbol) {
            case 'G' -> eraName(date, localeData, count);
            case 'y' -> yearValue(date, localeData, count);
            case 'u' -> extendedYearValue(date, localeData, count);
            case 'r' -> extendedYearValue(date, localeData, count);
            case 'Y' -> weekYearValue(date, localeData, count);
            case 'Q', 'q' -> quarterValue(date, localeData, count, symbol == 'q');
            case 'M', 'L' -> monthValue(date, localeData, count, symbol == 'L');
            case 'd' -> integerValue(date.getDayOfMonth(), localeData, count);
            case 'D' -> integerValue(date.getDayOfYear(), localeData, count);
            case 'F' -> integerValue(dayOfWeekInMonth(date), localeData, count);
            case 'g' -> integerValue(modifiedJulianDay(date), localeData, count);
            case 'w' -> integerValue(weekOfYear(date, localeData), localeData, count);
            case 'W' -> integerValue(weekOfMonth(date, localeData), localeData, count);
            case 'E' -> weekdayName(date, localeData, count);
            case 'e' -> localWeekdayValue(date, localeData, count, false);
            case 'c' -> localWeekdayValue(date, localeData, count, true);
            case 'a', 'b', 'B' -> dayPeriodName(date, localeData, count, symbol);
            case 'H' -> integerValue(date.getHour(), localeData, count);
            case 'k' -> integerValue(date.getHour() == 0 ? 24 : date.getHour(), localeData, count);
            case 'h' -> integerValue(hour12(date), localeData, count);
            case 'K' -> integerValue(date.getHour() % 12, localeData, count);
            case 'm' -> integerValue(date.getMinute(), localeData, count);
            case 's' -> integerValue(date.getSecond(), localeData, count);
            case 'S' -> fractionValue(date, localeData, count);
            case 'A' -> integerValue(millisecondsInDay(date), localeData, count);
            case 'z', 'Z', 'O', 'v', 'V', 'X', 'x' -> timeZoneValue(symbol, count, date, localeData);
            default -> throw Mf2FunctionSupport.badOption(
                    "Unsupported CLDR date/time pattern field: " + symbol + ".");
        };
    }

    private static String timeZoneValue(
            char symbol,
            int count,
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData) {
        int offsetMinutes = date.getOffset().getTotalSeconds() / 60;
        if (offsetMinutes != 0) {
            return switch (symbol) {
                case 'X' -> isoOffset(offsetMinutes, count, true);
                case 'x' -> isoOffset(offsetMinutes, count, false);
                case 'V' -> switch (count) {
                    case 1 -> "unk";
                    case 2 -> fixedOffsetGmtId(localeData, offsetMinutes);
                    case 3 -> "Unknown Location";
                    default -> localizedGmtOffset(localeData, offsetMinutes, count);
                };
                case 'Z' -> {
                    if (count <= 3) {
                        yield basicOffset(offsetMinutes);
                    }
                    if (count == 5) {
                        yield isoOffset(offsetMinutes, 3, true);
                    }
                    yield localizedGmtOffset(localeData, offsetMinutes, count);
                }
                default -> localizedGmtOffset(localeData, offsetMinutes, count);
            };
        }
        return switch (symbol) {
            case 'z' -> count >= 4
                    ? localeData.timeZoneNames().getOrDefault(
                            "utcLong",
                            localeData.timeZoneNames().getOrDefault("utcShort", UTC))
                    : localeData.timeZoneNames().getOrDefault("utcShort", UTC);
            case 'O', 'v' -> localizedGmtZero(localeData);
            case 'V' -> localizedGmtZero(localeData);
            case 'Z' -> {
                if (count <= 3) {
                    yield "+0000";
                }
                if (count == 5) {
                    yield "Z";
                }
                yield localizedGmtZero(localeData);
            }
            case 'X' -> "Z";
            case 'x' -> count == 1 ? "+00" : (count == 2 || count == 4 ? "+0000" : "+00:00");
            default -> UTC;
        };
    }

    private static String localizedGmtZero(CldrDateTimeData.LocaleData localeData) {
        String value = localeData.timeZoneNames().get("gmtZeroFormat");
        if (value != null) {
            return value;
        }
        return localeData.timeZoneNames().getOrDefault("gmtFormat", "GMT{0}").replace("{0}", "");
    }

    private static String localizedGmtOffset(
            CldrDateTimeData.LocaleData localeData,
            int offsetMinutes,
            int count) {
        String formatted = count >= 4 ? extendedOffset(offsetMinutes, true) : shortOffset(offsetMinutes);
        return localeData.timeZoneNames()
                .getOrDefault("gmtFormat", "GMT{0}")
                .replace("{0}", localizeDigits(formatted, localeData.numberingSystemDigits()));
    }

    private static String fixedOffsetGmtId(CldrDateTimeData.LocaleData localeData, int offsetMinutes) {
        return "GMT" + localizeDigits(extendedOffset(offsetMinutes, true), localeData.numberingSystemDigits());
    }

    private static String isoOffset(int offsetMinutes, int count, boolean useZeroZ) {
        if (offsetMinutes == 0 && useZeroZ) {
            return "Z";
        }
        if (count == 1) {
            return shortIsoOffset(offsetMinutes);
        }
        if (count == 2 || count == 4) {
            return basicOffset(offsetMinutes);
        }
        return extendedOffset(offsetMinutes, true);
    }

    private static String shortIsoOffset(int offsetMinutes) {
        OffsetParts parts = offsetParts(offsetMinutes);
        return parts.minutes() == 0
                ? "%s%02d".formatted(parts.sign(), parts.hours())
                : "%s%02d%02d".formatted(parts.sign(), parts.hours(), parts.minutes());
    }

    private static String shortOffset(int offsetMinutes) {
        OffsetParts parts = offsetParts(offsetMinutes);
        return parts.minutes() == 0
                ? "%s%d".formatted(parts.sign(), parts.hours())
                : "%s%d:%02d".formatted(parts.sign(), parts.hours(), parts.minutes());
    }

    private static String basicOffset(int offsetMinutes) {
        OffsetParts parts = offsetParts(offsetMinutes);
        return "%s%02d%02d".formatted(parts.sign(), parts.hours(), parts.minutes());
    }

    private static String extendedOffset(int offsetMinutes, boolean paddedHour) {
        OffsetParts parts = offsetParts(offsetMinutes);
        return paddedHour
                ? "%s%02d:%02d".formatted(parts.sign(), parts.hours(), parts.minutes())
                : "%s%d:%02d".formatted(parts.sign(), parts.hours(), parts.minutes());
    }

    private static OffsetParts offsetParts(int offsetMinutes) {
        String sign = offsetMinutes < 0 ? "-" : "+";
        int absolute = Math.abs(offsetMinutes);
        return new OffsetParts(sign, absolute / 60, absolute % 60);
    }

    private static String eraName(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        String era = date.getYear() <= 0 ? "0" : "1";
        return nameByWidth(localeData.eras(), widthForText(count), era);
    }

    private static String yearValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        int year = date.getYear();
        int yearOfEra = year <= 0 ? 1 - year : year;
        if (count == 2) {
            return integerText(yearOfEra % 100, localeData, 2);
        }
        return localizeDigits(String.valueOf(yearOfEra), localeData.numberingSystemDigits());
    }

    private static String extendedYearValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        return integerValue(date.getYear(), localeData, count);
    }

    private static String weekYearValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        int weekYear = weekYearInfo(date, localeData).year();
        if (count == 2) {
            return integerText(weekYear % 100, localeData, 2);
        }
        return localizeDigits(String.valueOf(weekYear), localeData.numberingSystemDigits());
    }

    private static String monthValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count,
            boolean standAlone) {
        int month = date.getMonthValue();
        if (count <= 2) {
            return integerValue(month, localeData, count);
        }
        String context = standAlone ? "stand-alone" : "format";
        return contextualName(localeData.months(), context, widthForText(count), String.valueOf(month));
    }

    private static String quarterValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count,
            boolean standAlone) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        if (count <= 2) {
            return integerValue(quarter, localeData, count);
        }
        String context = standAlone ? "stand-alone" : "format";
        return contextualName(localeData.quarters(), context, widthForText(count), String.valueOf(quarter));
    }

    private static String weekdayName(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        return contextualName(
                localeData.weekdays(),
                "format",
                widthForWeekday(count),
                WEEKDAY_KEYS.get(date.getDayOfWeek().getValue() % 7));
    }

    private static String localWeekdayValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count,
            boolean standAlone) {
        int day = date.getDayOfWeek().getValue() % 7;
        if (count <= 2) {
            int localDay = Math.floorMod(day - localeData.firstDayOfWeek(), 7) + 1;
            return integerValue(localDay, localeData, count);
        }
        return contextualName(
                localeData.weekdays(),
                standAlone ? "stand-alone" : "format",
                widthForWeekday(count),
                WEEKDAY_KEYS.get(day));
    }

    private static int dayOfWeekInMonth(ZonedDateTime date) {
        return ((date.getDayOfMonth() - 1) / 7) + 1;
    }

    private static int millisecondsInDay(ZonedDateTime date) {
        return ((date.getHour() * 60 + date.getMinute()) * 60 + date.getSecond()) * 1000
                + (date.getNano() / 1_000_000);
    }

    private static int modifiedJulianDay(ZonedDateTime date) {
        return Math.toIntExact(date.toLocalDate().toEpochDay() + 40_587);
    }

    private static int weekOfYear(ZonedDateTime date, CldrDateTimeData.LocaleData localeData) {
        return weekYearInfo(date, localeData).week();
    }

    private static WeekYearInfo weekYearInfo(ZonedDateTime date, CldrDateTimeData.LocaleData localeData) {
        int year = date.getYear();
        long epochDay = date.toLocalDate().toEpochDay();
        long weekStart = startOfWeek(epochDay, localeData.firstDayOfWeek());
        long currentStart = firstWeekStartOfYear(year, localeData);
        long nextStart = firstWeekStartOfYear(year + 1, localeData);
        if (weekStart >= nextStart) {
            return new WeekYearInfo(year + 1, 1);
        }
        if (weekStart < currentStart) {
            int previousYear = year - 1;
            long previousStart = firstWeekStartOfYear(previousYear, localeData);
            return new WeekYearInfo(previousYear, (int) Math.floorDiv(weekStart - previousStart, 7) + 1);
        }
        return new WeekYearInfo(year, (int) Math.floorDiv(weekStart - currentStart, 7) + 1);
    }

    private static int weekOfMonth(ZonedDateTime date, CldrDateTimeData.LocaleData localeData) {
        long epochDay = date.toLocalDate().toEpochDay();
        long weekStart = startOfWeek(epochDay, localeData.firstDayOfWeek());
        long firstStart = firstWeekStart(date.toLocalDate().withDayOfMonth(1).toEpochDay(), localeData);
        return (int) Math.floorDiv(weekStart - firstStart, 7) + 1;
    }

    private static long firstWeekStartOfYear(int year, CldrDateTimeData.LocaleData localeData) {
        return firstWeekStart(LocalDate.of(year, 1, 1).toEpochDay(), localeData);
    }

    private static long firstWeekStart(long periodStart, CldrDateTimeData.LocaleData localeData) {
        long weekStart = startOfWeek(periodStart, localeData.firstDayOfWeek());
        long daysInPeriod = weekStart + 7 - periodStart;
        return daysInPeriod >= localeData.minDaysInFirstWeek() ? weekStart : weekStart + 7;
    }

    private static long startOfWeek(long epochDay, int firstDay) {
        return epochDay - Math.floorMod(dayOfWeek(epochDay) - firstDay, 7);
    }

    private static int dayOfWeek(long epochDay) {
        return Math.floorMod(epochDay + 4, 7);
    }

    private static String dayPeriodName(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count,
            char symbol) {
        String period = dayPeriodKey(date, localeData, symbol);
        return contextualName(localeData.dayPeriods(), "format", widthForDayPeriod(count), period);
    }

    private static String dayPeriodKey(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            char symbol) {
        String fallback = date.getHour() < 12 ? "am" : "pm";
        if (symbol == 'a') {
            return fallback;
        }
        if (symbol == 'b') {
            String fixed = selectDayPeriodRule(date, localeData.dayPeriodRules(), true);
            return fixed == null ? fallback : fixed;
        }
        String flexible = selectDayPeriodRule(date, localeData.dayPeriodRules(), false);
        return flexible == null ? fallback : flexible;
    }

    private static String selectDayPeriodRule(
            ZonedDateTime date,
            String encodedRules,
            boolean exactOnly) {
        if (encodedRules == null || encodedRules.isEmpty()) {
            return null;
        }
        int minute = date.getHour() * 60 + date.getMinute();
        int exactMinute = date.getSecond() == 0 && date.getNano() == 0 ? minute : -1;
        String rangeMatch = null;
        for (String rawRule : encodedRules.split(";")) {
            int separator = rawRule.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String period = rawRule.substring(0, separator);
            String span = rawRule.substring(separator + 1);
            int rangeSeparator = span.indexOf('-');
            if (rangeSeparator < 0) {
                if (Integer.parseInt(span) == exactMinute) {
                    return period;
                }
            } else if (!exactOnly) {
                int start = Integer.parseInt(span.substring(0, rangeSeparator));
                int end = Integer.parseInt(span.substring(rangeSeparator + 1));
                if (rangeMatch == null && minuteInDayPeriodRange(minute, start, end)) {
                    rangeMatch = period;
                }
            }
        }
        return exactOnly ? null : rangeMatch;
    }

    private static boolean minuteInDayPeriodRange(int minute, int start, int end) {
        if (start <= end) {
            return minute >= start && minute < end;
        }
        return minute >= start || minute < end;
    }

    private static int hour12(ZonedDateTime date) {
        int hour = date.getHour() % 12;
        return hour == 0 ? 12 : hour;
    }

    private static String fractionValue(
            ZonedDateTime date,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        String milliseconds = String.format(Locale.ROOT, "%03d", date.getNano() / 1_000_000);
        String value = (milliseconds + "000000000").substring(0, count);
        return localizeDigits(value, localeData.numberingSystemDigits());
    }

    private static String integerValue(
            int value,
            CldrDateTimeData.LocaleData localeData,
            int count) {
        return integerText(value, localeData, count >= 2 ? count : 0);
    }

    private static String integerText(
            int value,
            CldrDateTimeData.LocaleData localeData,
            int minimumDigits) {
        String text = String.valueOf(Math.abs(value));
        while (text.length() < minimumDigits) {
            text = "0" + text;
        }
        if (value < 0) {
            text = "-" + text;
        }
        return localizeDigits(text, localeData.numberingSystemDigits());
    }

    private static String contextualName(
            Map<String, Map<String, Map<String, String>>> source,
            String context,
            String width,
            String key) {
        Map<String, Map<String, String>> contextData = source.get(context);
        if (contextData == null) {
            contextData = source.get("format");
        }
        if (contextData == null) {
            contextData = source.get("stand-alone");
        }
        return contextData == null ? key : nameByWidth(contextData, width, key);
    }

    private static String nameByWidth(
            Map<String, Map<String, String>> source,
            String width,
            String key) {
        String value = valueAt(source, width, key);
        if (value != null) {
            return value;
        }
        for (String fallback : List.of("abbreviated", "wide", "short", "narrow")) {
            value = valueAt(source, fallback, key);
            if (value != null) {
                return value;
            }
        }
        return key;
    }

    private static String valueAt(
            Map<String, Map<String, String>> source,
            String width,
            String key) {
        Map<String, String> values = source.get(width);
        return values == null ? null : values.get(key);
    }

    private static String widthForText(int count) {
        if (count == 4) {
            return "wide";
        }
        if (count == 5) {
            return "narrow";
        }
        return "abbreviated";
    }

    private static String widthForWeekday(int count) {
        if (count == 4) {
            return "wide";
        }
        if (count == 5) {
            return "narrow";
        }
        if (count >= 6) {
            return "short";
        }
        return "abbreviated";
    }

    private static String widthForDayPeriod(int count) {
        if (count == 4) {
            return "wide";
        }
        if (count >= 5) {
            return "narrow";
        }
        return "abbreviated";
    }

    private static String localizeDigits(String value, String digits) {
        if (digits == null || digits.equals("0123456789")) {
            return value;
        }
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch >= '0' && ch <= '9') {
                output.append(digits.charAt(ch - '0'));
            } else {
                output.append(ch);
            }
        }
        return output.toString();
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isAsciiDigit(int ch) {
        return ch >= '0' && ch <= '9';
    }

    private static String styleKey(Style style) {
        return style.name().toLowerCase(Locale.ROOT);
    }

    private static Style callStyle(
            Mf2FunctionRegistry.FunctionCall call,
            String optionName,
            String legacyOptionName,
            String fallback,
            boolean legacyTimePrecision) throws Mf2Exception {
        String absent = "\u0000mf2-absent-date-time-style";
        String shared = call.optionValue("style", absent);
        String legacyValue = call.optionValue(legacyOptionName, absent);
        String optionValue = call.optionValue(optionName, absent);
        if (!optionValue.equals(absent)) {
            return styleOption(optionValue);
        }
        if (!legacyValue.equals(absent)) {
            return legacyTimePrecision ? timePrecisionStyleOption(legacyValue) : styleOption(legacyValue);
        }
        if (!shared.equals(absent)) {
            return styleOption(shared);
        }
        return styleOption(fallback);
    }

    private static String nonEmptyCallOption(
            Mf2FunctionRegistry.FunctionCall call, String name, String fallback) throws Mf2Exception {
        String value = call.optionValue(name, fallback);
        if ("".equals(value)) {
            throw Mf2FunctionSupport.badOption(name + " must not be empty.");
        }
        return value;
    }

    private static Style styleOption(String value) throws Mf2Exception {
        String text = boundedOption(value, "date/time style");
        return switch (text) {
            case "full" -> Style.FULL;
            case "long" -> Style.LONG;
            case "medium" -> Style.MEDIUM;
            case "short" -> Style.SHORT;
            default -> throw Mf2FunctionSupport.badOption("date/time style must be full, long, medium, or short.");
        };
    }

    private static Style timePrecisionStyleOption(String value) throws Mf2Exception {
        return "second".equals(value) ? Style.MEDIUM : styleOption(value);
    }

    public enum Style {
        FULL,
        LONG,
        MEDIUM,
        SHORT
    }

    public record Options(
            String locale,
            Style style,
            Style dateStyle,
            Style timeStyle,
            String skeleton,
            String hourCycle,
            String timeZone,
            String calendar) {
        public static Builder builder() {
            return new Builder();
        }

        Style effectiveDateStyle() {
            return dateStyle == null ? style : dateStyle;
        }

        Style effectiveTimeStyle() {
            return timeStyle == null ? style : timeStyle;
        }

        public static final class Builder {
            private String locale = DEFAULT_LOCALE;
            private Style style = Style.MEDIUM;
            private Style dateStyle;
            private Style timeStyle;
            private String skeleton;
            private String hourCycle;
            private String timeZone = UTC;
            private String calendar;

            public Builder locale(String locale) {
                this.locale = locale == null ? DEFAULT_LOCALE : locale;
                return this;
            }

            public Builder style(Style style) {
                this.style = style == null ? Style.MEDIUM : style;
                return this;
            }

            public Builder dateStyle(Style dateStyle) {
                this.dateStyle = dateStyle;
                return this;
            }

            public Builder timeStyle(Style timeStyle) {
                this.timeStyle = timeStyle;
                return this;
            }

            public Builder skeleton(String skeleton) {
                this.skeleton = skeleton;
                return this;
            }

            public Builder hourCycle(String hourCycle) {
                this.hourCycle = hourCycle;
                return this;
            }

            public Builder timeZone(String timeZone) {
                this.timeZone = timeZone == null ? UTC : timeZone;
                return this;
            }

            public Builder calendar(String calendar) {
                this.calendar = calendar;
                return this;
            }

            public Options build() {
                return new Options(locale, style, dateStyle, timeStyle, skeleton, hourCycle, timeZone, calendar);
            }
        }
    }

    private record QuotedPattern(String value, int nextIndex) {}

    private record OffsetParts(String sign, int hours, int minutes) {}

    private record WeekYearInfo(int year, int week) {}
}
