package com.box.l10n.mojito.mf2.icu4j;

import com.box.l10n.mojito.mf2.Mf2Exception;
import com.box.l10n.mojito.mf2.Mf2FunctionRegistry;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DisplayContext;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RelativeDateTimeFormatter;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public final class Mf2Icu4jFunctions {
    private static final int MAX_DATE_OPERAND_LENGTH = 256;
    private static final int MAX_FRACTION_DIGITS = 100;
    private static final int MAX_LOCALE_LENGTH = 256;
    private static final int MAX_NUMERIC_OPERAND_LENGTH = 256;
    private static final int MAX_NUMERIC_OPTION_LENGTH = 256;
    private static final int MAX_OPTION_LENGTH = 256;
    private static final int MAX_TIME_ZONE_OPTION_LENGTH = 256;
    private static final LocalDate EPOCH_DATE = LocalDate.of(1970, 1, 1);
    private static final Pattern ISO_DATE_TIME_OPERAND =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?");

    private Mf2Icu4jFunctions() {}

    public static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.portable()
                .withFunction("number", Mf2Icu4jFunctions::formatNumber)
                .withFunction("percent", Mf2Icu4jFunctions::formatPercent)
                .withFunction("integer", Mf2Icu4jFunctions::formatInteger)
                .withFunction("currency", Mf2Icu4jFunctions::formatCurrency)
                .withFunction("date", Mf2Icu4jFunctions::formatDate)
                .withFunction("time", Mf2Icu4jFunctions::formatTime)
                .withFunction("datetime", Mf2Icu4jFunctions::formatDateTime)
                .withFunction("relativeTime", Mf2Icu4jFunctions::formatRelativeTime);
    }

    private static String formatNumber(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = numericValue(call, "Number function requires a numeric operand.");
        NumberFormat format = NumberFormat.getNumberInstance(locale(call));
        minimumFractionDigits(call).ifPresent(format::setMinimumFractionDigits);
        maximumFractionDigits(call).ifPresent(format::setMaximumFractionDigits);
        return applySignDisplay(format.format(value), value, call);
    }

    private static String formatPercent(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = numericValue(call, "Percent function requires a numeric operand.");
        NumberFormat format = NumberFormat.getPercentInstance(locale(call));
        minimumFractionDigits(call).ifPresent(format::setMinimumFractionDigits);
        maximumFractionDigits(call).ifPresent(format::setMaximumFractionDigits);
        return applySignDisplay(format.format(value), value, call);
    }

    private static String formatInteger(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = numericValue(call, "Integer function requires a numeric operand.");
        NumberFormat format = NumberFormat.getIntegerInstance(locale(call));
        return applySignDisplay(format.format((long) value), value, call);
    }

    private static String formatCurrency(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = numericValue(call, "Currency function requires a numeric operand.");
        String currencyCode = currencyCode(call);
        if (currencyCode == null) {
            throw badOption("Currency function requires a currency option.");
        }
        String normalizedCurrency = normalizeCurrencyCode(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale(call));
        try {
            format.setCurrency(Currency.getInstance(normalizedCurrency));
        } catch (IllegalArgumentException error) {
            throw badOption("Currency option must be an ISO 4217 currency code.");
        }
        currencyFractionDigits(call).ifPresent(fractionDigits -> {
            format.setMinimumFractionDigits(fractionDigits);
            format.setMaximumFractionDigits(fractionDigits);
        });
        return format.format(value);
    }

    private static String formatDate(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        ZoneId zone = zoneId(call);
        Date value = dateValue(call, zone);
        DateFormat format = DateFormat.getDateInstance(
                dateStyle(dateStyleOption(call)),
                locale(call));
        format.setTimeZone(timeZone(zone));
        return format.format(value);
    }

    private static String formatTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        ZoneId zone = zoneId(call);
        Date value = timeValue(call, zone);
        DateFormat format = DateFormat.getTimeInstance(
                timeStyle(timeStyleOption(call)),
                locale(call));
        format.setTimeZone(timeZone(zone));
        return format.format(value);
    }

    private static String formatDateTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        ZoneId zone = zoneId(call);
        Date value = dateTimeValue(call, zone);
        String sharedStyle = option(call, "style", null);
        String defaultStyle = sharedStyle == null ? "medium" : sharedStyle;
        String dateStyleOption = firstOptionValue(call, defaultStyle, "dateStyle", "dateLength");
        String timeStyleOption =
                firstOptionValue(call, defaultStyle, "timeStyle", "timePrecision");
        int dateStyle = dateStyle(dateStyleOption);
        int timeStyle = timeStyle(timeStyleOption);
        DateFormat format = DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale(call));
        format.setTimeZone(timeZone(zone));
        return format.format(value);
    }

    private static String formatRelativeTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = numericValue(call, "Relative time function requires a numeric operand.");
        RelativeDateTimeFormatter.RelativeDateTimeUnit unit = relativeTimeUnit(call);
        String numeric = optionOneOf(call, "numeric", "always", "always", "auto");
        RelativeDateTimeFormatter formatter = RelativeDateTimeFormatter.getInstance(
                locale(call),
                null,
                relativeTimeStyle(call),
                DisplayContext.CAPITALIZATION_NONE);
        return numeric.equals("auto")
                ? formatter.format(value, unit)
                : formatter.formatNumeric(value, unit);
    }

    private static ULocale locale(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        String locale = call.locale();
        if (locale.length() > MAX_LOCALE_LENGTH) {
            throw badOption("locale must not exceed 256 characters.");
        }
        String normalized = locale.replace('_', '-');
        if (!isWellFormedLocaleIdentifier(normalized)) {
            throw badOption("Locale option must be a valid locale identifier.");
        }
        return ULocale.forLanguageTag(normalized);
    }

    private static boolean isWellFormedLocaleIdentifier(String locale) {
        if (locale.isEmpty()) {
            return false;
        }
        String[] subtags = locale.split("-", -1);
        String language = subtags[0];
        if (language.length() < 2
                || language.length() > 8
                || !language.chars().allMatch(Mf2Icu4jFunctions::isAsciiAlpha)) {
            return false;
        }
        int index = 1;
        while (index < subtags.length) {
            String subtag = subtags[index];
            if (subtag.length() == 1) {
                if (!subtag.chars().allMatch(Mf2Icu4jFunctions::isAsciiAlphanumeric)) {
                    return false;
                }
                boolean isPrivateUse = subtag.equalsIgnoreCase("x");
                index++;
                int extensionStart = index;
                while (index < subtags.length
                        && subtags[index].length() >= (isPrivateUse ? 1 : 2)
                        && subtags[index].length() <= 8
                        && subtags[index].chars().allMatch(Mf2Icu4jFunctions::isAsciiAlphanumeric)) {
                    index++;
                }
                if (index == extensionStart) {
                    return false;
                }
                if (isPrivateUse) {
                    return index == subtags.length;
                }
                continue;
            }
            if (subtag.length() < 2
                    || subtag.length() > 8
                    || !subtag.chars().allMatch(Mf2Icu4jFunctions::isAsciiAlphanumeric)) {
                return false;
            }
            index++;
        }
        return true;
    }

    private static boolean isAsciiAlpha(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isAsciiAlphanumeric(int ch) {
        return isAsciiAlpha(ch) || (ch >= '0' && ch <= '9');
    }

    private static double numericValue(Mf2FunctionRegistry.FunctionCall call, String message)
            throws Mf2Exception {
        Object rawValue = call.rawValue();
        double value;
        if (rawValue instanceof Number number) {
            value = number.doubleValue();
        } else {
            if (call.value().length() > MAX_NUMERIC_OPERAND_LENGTH) {
                throw badOperand(message);
            }
            try {
                value = Double.parseDouble(call.value());
            } catch (NumberFormatException error) {
                throw badOperand(message);
            }
        }
        if (!Double.isFinite(value)) {
            throw badOperand(message);
        }
        return value;
    }

    private static Date dateValue(Mf2FunctionRegistry.FunctionCall call, ZoneId zone)
            throws Mf2Exception {
        Object rawValue = call.rawValue();
        if (rawValue instanceof LocalDate date) {
            return Date.from(date.atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof LocalDateTime dateTime) {
            return Date.from(dateTime.toLocalDate().atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof OffsetDateTime dateTime) {
            return Date.from(dateTime.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof ZonedDateTime dateTime) {
            return Date.from(dateTime.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof Instant instant) {
            LocalDate date = instant.atZone(zone).toLocalDate();
            return Date.from(date.atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof Date date) {
            return date;
        }
        LocalDate parsedDate = parseLocalDate(call.value());
        if (parsedDate != null) {
            return Date.from(parsedDate.atStartOfDay(zone).toInstant());
        }
        ZonedDateTime parsedDateTime = parseZonedDateTime(call.value());
        if (parsedDateTime != null) {
            return Date.from(parsedDateTime.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toInstant());
        }
        throw badOperand("Date function requires a date or datetime operand.");
    }

    private static Date timeValue(Mf2FunctionRegistry.FunctionCall call, ZoneId zone)
            throws Mf2Exception {
        Object rawValue = call.rawValue();
        if (rawValue instanceof LocalTime time) {
            return Date.from(time.atDate(EPOCH_DATE).atZone(zone).toInstant());
        }
        if (rawValue instanceof LocalDateTime dateTime) {
            return Date.from(dateTime.atZone(zone).toInstant());
        }
        if (rawValue instanceof OffsetDateTime dateTime) {
            return Date.from(dateTime.toInstant());
        }
        if (rawValue instanceof ZonedDateTime dateTime) {
            return Date.from(dateTime.toInstant());
        }
        if (rawValue instanceof Instant instant) {
            return Date.from(instant);
        }
        if (rawValue instanceof Date date) {
            return date;
        }
        LocalTime parsedTime = parseLocalTime(call.value());
        if (parsedTime != null) {
            return Date.from(parsedTime.atDate(EPOCH_DATE).atZone(zone).toInstant());
        }
        ZonedDateTime parsedDateTime = parseZonedDateTime(call.value());
        if (parsedDateTime != null) {
            return Date.from(parsedDateTime.toInstant());
        }
        throw badOperand("Time function requires a time or datetime operand.");
    }

    private static Date dateTimeValue(Mf2FunctionRegistry.FunctionCall call, ZoneId zone)
            throws Mf2Exception {
        Object rawValue = call.rawValue();
        if (rawValue instanceof LocalDate date) {
            return Date.from(date.atStartOfDay(zone).toInstant());
        }
        if (rawValue instanceof LocalDateTime dateTime) {
            return Date.from(dateTime.atZone(zone).toInstant());
        }
        if (rawValue instanceof OffsetDateTime dateTime) {
            return Date.from(dateTime.toInstant());
        }
        if (rawValue instanceof ZonedDateTime dateTime) {
            return Date.from(dateTime.toInstant());
        }
        if (rawValue instanceof Instant instant) {
            return Date.from(instant);
        }
        if (rawValue instanceof Date date) {
            return date;
        }
        ZonedDateTime parsedDateTime = parseZonedDateTime(call.value());
        if (parsedDateTime != null) {
            return Date.from(parsedDateTime.toInstant());
        }
        LocalDate parsedDate = parseLocalDate(call.value());
        if (parsedDate != null) {
            return Date.from(parsedDate.atStartOfDay(zone).toInstant());
        }
        throw badOperand("Datetime function requires a date or datetime operand.");
    }

    private static LocalDate parseLocalDate(String value) {
        if (!hasValidDateOperandLength(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static LocalTime parseLocalTime(String value) {
        if (!hasValidDateOperandLength(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static ZonedDateTime parseZonedDateTime(String value) {
        if (!hasValidDateOperandLength(value) || !ISO_DATE_TIME_OPERAND.matcher(value).matches()) {
            return null;
        }
        try {
            OffsetDateTime dateTime =
                    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return dateTime.toZonedDateTime();
        } catch (DateTimeParseException error) {
        }
        try {
            LocalDateTime dateTime =
                    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dateTime.atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException error) {
        }
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static boolean hasValidDateOperandLength(String value) {
        return value.length() <= MAX_DATE_OPERAND_LENGTH;
    }

    private static OptionalInt minimumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return nonNegativeOption(call, "minimumFractionDigits");
    }

    private static OptionalInt maximumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return nonNegativeOption(call, "maximumFractionDigits");
    }

    private static OptionalInt currencyFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("fractionDigits", null);
        if (value == null || value.equals("auto")) {
            return OptionalInt.empty();
        }
        int fractionDigits = parseNonNegativeInteger(
                value, "fractionDigits option must be auto or a non-negative integer.");
        return OptionalInt.of(fractionDigits);
    }

    private static OptionalInt nonNegativeOption(
            Mf2FunctionRegistry.FunctionCall call, String name) throws Mf2Exception {
        String value = call.optionValue(name, null);
        if (value == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(
                parseNonNegativeInteger(value, name + " option must be a non-negative integer."));
    }

    private static int parseNonNegativeInteger(String value, String message) throws Mf2Exception {
        if (value.length() > MAX_NUMERIC_OPTION_LENGTH) {
            throw badOption(message);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 0 && parsed <= MAX_FRACTION_DIGITS) {
                return parsed;
            }
        } catch (NumberFormatException error) {
        }
        throw badOption(message);
    }

    private static String currencyCode(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String currency = call.optionValue("currency", null);
        if (currency != null) {
            return currency;
        }
        return inheritedCurrencyCode(call.inheritedSource());
    }

    private static String normalizeCurrencyCode(String currency) throws Mf2Exception {
        if (currency.length() != 3
                || !currency.chars().allMatch(Mf2Icu4jFunctions::isAsciiAlpha)) {
            throw badOption("Currency option must be an ISO 4217 currency code.");
        }
        return currency.toUpperCase(Locale.ROOT);
    }

    private static String inheritedCurrencyCode(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return null;
        }
        if (source.function().name().equals("currency")) {
            String currency = source.optionValue("currency", null);
            if (currency != null) {
                return currency;
            }
        }
        return inheritedCurrencyCode(source.inheritedSource());
    }

    private static String dateStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return firstOptionValue(call, "medium", "dateStyle", "length", "style");
    }

    private static String timeStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return firstOptionValue(call, "medium", "timeStyle", "precision", "style");
    }

    private static ZoneId zoneId(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = option(call, "timeZone", "UTC");
        if (value.length() > MAX_TIME_ZONE_OPTION_LENGTH) {
            throw badOption("timeZone option must not exceed 256 characters.");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException error) {
            throw badOption("timeZone option must be a valid time zone identifier.");
        }
    }

    private static TimeZone timeZone(ZoneId zone) {
        return TimeZone.getTimeZone(zone.getId());
    }

    private static int dateStyle(String value) throws Mf2Exception {
        return switch (value) {
            case "full" -> DateFormat.FULL;
            case "long" -> DateFormat.LONG;
            case "medium" -> DateFormat.MEDIUM;
            case "short" -> DateFormat.SHORT;
            default -> throw badOption("Date style option must be full, long, medium, or short.");
        };
    }

    private static int timeStyle(String value) throws Mf2Exception {
        return switch (value) {
            case "full" -> DateFormat.FULL;
            case "long" -> DateFormat.LONG;
            case "medium", "second" -> DateFormat.MEDIUM;
            case "short" -> DateFormat.SHORT;
            default -> throw badOption(
                    "Time style option must be full, long, medium, short, or second.");
        };
    }

    private static RelativeDateTimeFormatter.Style relativeTimeStyle(
            Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        return switch (optionOneOf(call, "style", "long", "long", "short", "narrow")) {
            case "long" -> RelativeDateTimeFormatter.Style.LONG;
            case "short" -> RelativeDateTimeFormatter.Style.SHORT;
            case "narrow" -> RelativeDateTimeFormatter.Style.NARROW;
            default -> throw new IllegalStateException("validated option is exhaustive");
        };
    }

    private static RelativeDateTimeFormatter.RelativeDateTimeUnit relativeTimeUnit(
            Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        return switch (optionOneOf(
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
                "year")) {
            case "second" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND;
            case "minute" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE;
            case "hour" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR;
            case "day" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY;
            case "week" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK;
            case "month" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH;
            case "quarter" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.QUARTER;
            case "year" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR;
            default -> throw new IllegalStateException("validated option is exhaustive");
        };
    }

    private static String optionOneOf(
            Mf2FunctionRegistry.FunctionCall call,
            String name,
            String fallback,
            String... allowedValues)
            throws Mf2Exception {
        String value = option(call, name, fallback);
        if (value == null) {
            throw badOption(name + " option is required.");
        }
        for (String allowedValue : allowedValues) {
            if (allowedValue.equals(value)) {
                return value;
            }
        }
        throw badOption(name + " option must be one of " + String.join(", ", allowedValues) + ".");
    }

    private static String option(
            Mf2FunctionRegistry.FunctionCall call, String name, String fallback)
            throws Mf2Exception {
        String value = call.optionValue(name, fallback);
        if (value != null && value.length() > MAX_OPTION_LENGTH) {
            throw badOption(name + " option must not exceed 256 characters.");
        }
        return value;
    }

    private static String firstOptionValue(
            Mf2FunctionRegistry.FunctionCall call, String fallback, String... names)
            throws Mf2Exception {
        for (String name : names) {
            String value = option(call, name, null);
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static String applySignDisplay(
            String formatted, double value, Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String signDisplay = optionOneOf(call, "signDisplay", "auto", "auto", "always");
        return value >= 0.0 && signDisplay.equals("always") ? "+" + formatted : formatted;
    }

    private static Mf2Exception badOperand(String message) {
        return new Mf2Exception("bad-operand", message);
    }

    private static Mf2Exception badOption(String message) {
        return new Mf2Exception("bad-option", message);
    }
}
