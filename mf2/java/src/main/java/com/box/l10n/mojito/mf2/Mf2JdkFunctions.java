package com.box.l10n.mojito.mf2;

import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class Mf2JdkFunctions {
    private static final int MAX_DATE_OPERAND_LENGTH = 256;
    private static final int MAX_LOCALE_LENGTH = 256;
    private static final int MAX_TIME_ZONE_OPTION_LENGTH = 256;
    private static final Pattern ISO_DATE_TIME_OPERAND =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?");

    private Mf2JdkFunctions() {}

    static void registerFormatters(Map<String, Mf2FunctionRegistry.Formatter> formatters) {
        formatters.put("number", Mf2JdkFunctions::formatNumber);
        formatters.put("percent", Mf2JdkFunctions::formatPercent);
        formatters.put("integer", Mf2JdkFunctions::formatInteger);
        formatters.put("currency", Mf2JdkFunctions::formatCurrency);
        formatters.put("datetime", Mf2JdkFunctions::formatDateTime);
        formatters.put("date", Mf2JdkFunctions::formatDate);
        formatters.put("time", Mf2JdkFunctions::formatTime);
    }

    private static String formatNumber(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Number function requires a numeric operand.");
        NumberFormat format = NumberFormat.getNumberInstance(locale(call.locale()));
        format.setGroupingUsed(false);
        Integer minimumFractionDigits = minimumFractionDigits(call);
        if (minimumFractionDigits != null) {
            format.setMinimumFractionDigits(minimumFractionDigits);
        }
        return applySignDisplay(format.format(value), value, call);
    }

    private static String formatPercent(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Percent function requires a numeric operand.");
        NumberFormat format = NumberFormat.getPercentInstance(locale(call.locale()));
        format.setGroupingUsed(false);
        Integer minimumFractionDigits = minimumFractionDigits(call);
        if (minimumFractionDigits != null) {
            format.setMinimumFractionDigits(minimumFractionDigits);
        }
        Integer maximumFractionDigits = maximumFractionDigits(call);
        if (maximumFractionDigits != null) {
            format.setMaximumFractionDigits(maximumFractionDigits);
        }
        return applySignDisplay(format.format(value), value, call);
    }

    private static String formatInteger(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Integer function requires a numeric operand.");
        NumberFormat format = NumberFormat.getIntegerInstance(locale(call.locale()));
        format.setGroupingUsed(false);
        return applySignDisplay(format.format((long) value), value, call);
    }

    private static String formatCurrency(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(
                call, "Currency function requires a numeric operand.");
        String currency = currencyCode(call);
        if (currency == null) {
            throw Mf2FunctionSupport.badOption("Currency function requires a currency option.");
        }
        String normalizedCurrency = normalizeCurrencyCode(currency);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale(call.locale()));
        try {
            format.setCurrency(Currency.getInstance(normalizedCurrency));
        } catch (IllegalArgumentException error) {
            throw Mf2FunctionSupport.badOption("Currency option must be an ISO 4217 currency code.");
        }
        Integer fractionDigits = currencyFractionDigits(call);
        if (fractionDigits != null) {
            format.setMinimumFractionDigits(fractionDigits);
            format.setMaximumFractionDigits(fractionDigits);
        }
        return format.format(value);
    }

    private static String formatDate(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        ZoneId zone = timeZone(call);
        LocalDate date = dateFrom(call.rawValue(), call.value(), zone)
                .or(() -> parseSourceLocalDate(call.inheritedSource(), zone))
                .orElseThrow(() -> Mf2Exception.badOperand("Date function requires a date or datetime operand."));
        return DateTimeFormatter.ofLocalizedDate(dateStyle(dateStyleOption(call)))
                .withLocale(locale(call.locale()))
                .format(date);
    }

    private static String formatTime(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        ZoneId zone = timeZone(call);
        LocalTime time = timeFrom(call.rawValue(), call.value(), zone)
                .or(() -> parseSourceLocalTime(call.inheritedSource(), zone))
                .orElseThrow(() -> Mf2Exception.badOperand("Datetime and time functions require a datetime operand."));
        return DateTimeFormatter.ofLocalizedTime(timeStyle(timeStyleOption(call)))
                .withLocale(locale(call.locale()))
                .format(time);
    }

    private static String formatDateTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        ZoneId zone = timeZone(call);
        ZonedDateTime dateTime = zonedDateTimeFrom(call.rawValue(), call.value(), zone)
                .or(() -> parseSourceZonedDateTime(call.inheritedSource(), zone))
                .orElseThrow(() -> Mf2Exception.badOperand("Datetime function requires a date or datetime operand."));
        FormatStyle dateStyle = dateStyle(dateTimeDateStyleOption(call));
        FormatStyle timeStyle = timeStyle(dateTimeTimeStyleOption(call));
        return DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle)
                .withLocale(locale(call.locale()))
                .format(dateTime.withZoneSameInstant(zone));
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
                || !currency.chars().allMatch(Mf2JdkFunctions::isAsciiAlpha)) {
            throw Mf2FunctionSupport.badOption("Currency option must be an ISO 4217 currency code.");
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

    private static Integer currencyFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("fractionDigits", null);
        if (value == null || value.equals("auto")) {
            return null;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(
                value, "fractionDigits option must be auto or a non-negative integer.");
    }

    private static Integer minimumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("minimumFractionDigits", null);
        if (value == null) {
            return null;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
    }

    private static Integer maximumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("maximumFractionDigits", null);
        if (value == null) {
            return null;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.");
    }

    private static String applySignDisplay(
            String formatted, double value, Mf2FunctionRegistry.FunctionCall call) {
        if (value >= 0.0 && "always".equals(functionOptionLiteral(call.function(), "signDisplay", null))) {
            return "+" + formatted;
        }
        return formatted;
    }

    private static String functionOptionLiteral(Mf2Message.FunctionRef function, String name, String fallback) {
        Mf2Message.ExpressionArgument option = function.options().get(name);
        return option instanceof Mf2Message.LiteralArgument literal ? literal.value() : fallback;
    }

    private static Locale locale(String locale) throws Mf2Exception {
        if (locale.length() > MAX_LOCALE_LENGTH) {
            throw Mf2FunctionSupport.badOption("locale must not exceed 256 characters.");
        }
        String normalized = locale.replace('_', '-');
        if (!isWellFormedLocaleIdentifier(normalized)) {
            throw Mf2FunctionSupport.badOption("Locale option must be a valid locale identifier.");
        }
        return Locale.forLanguageTag(normalized);
    }

    private static boolean isWellFormedLocaleIdentifier(String locale) {
        if (locale.isEmpty()) {
            return false;
        }
        String[] subtags = locale.split("-", -1);
        String language = subtags[0];
        if (language.length() < 2
                || language.length() > 8
                || !language.chars().allMatch(Mf2JdkFunctions::isAsciiAlpha)) {
            return false;
        }
        int index = 1;
        while (index < subtags.length) {
            String subtag = subtags[index];
            if (subtag.length() == 1) {
                if (!subtag.chars().allMatch(Mf2JdkFunctions::isAsciiAlphanumeric)) {
                    return false;
                }
                boolean isPrivateUse = subtag.equalsIgnoreCase("x");
                index++;
                int extensionStart = index;
                while (index < subtags.length
                        && subtags[index].length() >= (isPrivateUse ? 1 : 2)
                        && subtags[index].length() <= 8
                        && subtags[index].chars().allMatch(Mf2JdkFunctions::isAsciiAlphanumeric)) {
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
                    || !subtag.chars().allMatch(Mf2JdkFunctions::isAsciiAlphanumeric)) {
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

    private static String dateStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return call.optionValue(
                "dateStyle",
                call.optionValue("length", call.optionValue("style", "short")));
    }

    private static String timeStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return call.optionValue(
                "timeStyle",
                call.optionValue("precision", call.optionValue("style", "short")));
    }

    private static String dateTimeDateStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return call.optionValue(
                "dateStyle",
                call.optionValue("dateLength", call.optionValue("style", "short")));
    }

    private static String dateTimeTimeStyleOption(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return call.optionValue(
                "timeStyle",
                call.optionValue("timePrecision", call.optionValue("style", "short")));
    }

    private static ZoneId timeZone(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("timeZone", "UTC");
        if (value.length() > MAX_TIME_ZONE_OPTION_LENGTH) {
            throw Mf2FunctionSupport.badOption("timeZone option must not exceed 256 characters.");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException error) {
            throw Mf2FunctionSupport.badOption("timeZone option must be a valid time zone identifier.");
        }
    }

    private static FormatStyle dateStyle(String value) throws Mf2Exception {
        return switch (value) {
            case "full" -> FormatStyle.FULL;
            case "long" -> FormatStyle.LONG;
            case "medium" -> FormatStyle.MEDIUM;
            case "short" -> FormatStyle.SHORT;
            default -> throw Mf2FunctionSupport.badOption("Date style option must be full, long, medium, or short.");
        };
    }

    private static FormatStyle timeStyle(String value) throws Mf2Exception {
        return switch (value) {
            case "full", "long", "medium", "short", "second" -> FormatStyle.MEDIUM;
            default -> throw Mf2FunctionSupport.badOption("Time style option must be full, long, medium, short, or second.");
        };
    }

    private static Optional<LocalDate> dateFrom(Object rawValue, String renderedValue, ZoneId zone) {
        if (rawValue instanceof LocalDate value) {
            return Optional.of(value);
        }
        if (rawValue instanceof LocalDateTime value) {
            return Optional.of(value.toLocalDate());
        }
        if (rawValue instanceof OffsetDateTime value) {
            return Optional.of(value.atZoneSameInstant(zone).toLocalDate());
        }
        if (rawValue instanceof ZonedDateTime value) {
            return Optional.of(value.withZoneSameInstant(zone).toLocalDate());
        }
        if (rawValue instanceof Instant value) {
            return Optional.of(value.atZone(zone).toLocalDate());
        }
        if (rawValue instanceof java.util.Date value) {
            return Optional.of(value.toInstant().atZone(zone).toLocalDate());
        }
        return parseLocalDate(renderedValue)
                .or(() -> parseLocalDateTime(renderedValue).map(LocalDateTime::toLocalDate))
                .or(() -> parseZonedDateTime(renderedValue).map(value -> value.withZoneSameInstant(zone).toLocalDate()));
    }

    private static Optional<LocalTime> timeFrom(Object rawValue, String renderedValue, ZoneId zone) {
        if (rawValue instanceof LocalTime value) {
            return Optional.of(value);
        }
        if (rawValue instanceof LocalDateTime value) {
            return Optional.of(value.toLocalTime());
        }
        if (rawValue instanceof OffsetDateTime value) {
            return Optional.of(value.atZoneSameInstant(zone).toLocalTime());
        }
        if (rawValue instanceof ZonedDateTime value) {
            return Optional.of(value.withZoneSameInstant(zone).toLocalTime());
        }
        if (rawValue instanceof Instant value) {
            return Optional.of(value.atZone(zone).toLocalTime());
        }
        if (rawValue instanceof java.util.Date value) {
            return Optional.of(value.toInstant().atZone(zone).toLocalTime());
        }
        return parseLocalTime(renderedValue)
                .or(() -> parseLocalDateTime(renderedValue).map(LocalDateTime::toLocalTime))
                .or(() -> parseZonedDateTime(renderedValue).map(value -> value.withZoneSameInstant(zone).toLocalTime()));
    }

    private static Optional<ZonedDateTime> zonedDateTimeFrom(Object rawValue, String renderedValue, ZoneId zone) {
        if (rawValue instanceof ZonedDateTime value) {
            return Optional.of(value.withZoneSameInstant(zone));
        }
        if (rawValue instanceof OffsetDateTime value) {
            return Optional.of(value.atZoneSameInstant(zone));
        }
        if (rawValue instanceof Instant value) {
            return Optional.of(value.atZone(zone));
        }
        if (rawValue instanceof java.util.Date value) {
            return Optional.of(value.toInstant().atZone(zone));
        }
        if (rawValue instanceof LocalDateTime value) {
            return Optional.of(value.atZone(zone));
        }
        if (rawValue instanceof LocalDate value) {
            return Optional.of(value.atStartOfDay(zone));
        }
        return parseZonedDateTime(renderedValue)
                .map(value -> value.withZoneSameInstant(zone))
                .or(() -> parseLocalDateTime(renderedValue).map(value -> value.atZone(zone)))
                .or(() -> parseLocalDate(renderedValue).map(value -> value.atStartOfDay(zone)));
    }

    private static Optional<LocalDate> parseSourceLocalDate(
            Mf2FunctionRegistry.FunctionSourceRef source, ZoneId zone) {
        if (source == null) {
            return Optional.empty();
        }
        Optional<LocalDate> date = dateFrom(source.value(), source.value(), zone);
        return date.isPresent() ? date : parseSourceLocalDate(source.inheritedSource(), zone);
    }

    private static Optional<LocalTime> parseSourceLocalTime(
            Mf2FunctionRegistry.FunctionSourceRef source, ZoneId zone) {
        if (source == null) {
            return Optional.empty();
        }
        Optional<LocalTime> time = timeFrom(source.value(), source.value(), zone);
        return time.isPresent() ? time : parseSourceLocalTime(source.inheritedSource(), zone);
    }

    private static Optional<ZonedDateTime> parseSourceZonedDateTime(
            Mf2FunctionRegistry.FunctionSourceRef source, ZoneId zone) {
        if (source == null) {
            return Optional.empty();
        }
        Optional<ZonedDateTime> dateTime = zonedDateTimeFrom(source.value(), source.value(), zone);
        return dateTime.isPresent() ? dateTime : parseSourceZonedDateTime(source.inheritedSource(), zone);
    }

    private static Optional<LocalDate> parseLocalDate(String value) {
        if (!hasValidDateOperandLength(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (DateTimeParseException error) {
            return Optional.empty();
        }
    }

    private static Optional<LocalTime> parseLocalTime(String value) {
        if (!hasValidDateOperandLength(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME));
        } catch (DateTimeParseException error) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDateTime> parseLocalDateTime(String value) {
        if (!hasValidDateOperandLength(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (DateTimeParseException error) {
            return Optional.empty();
        }
    }

    private static Optional<ZonedDateTime> parseZonedDateTime(String value) {
        if (!hasValidDateOperandLength(value) || !ISO_DATE_TIME_OPERAND.matcher(value).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(Instant.parse(value).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static boolean hasValidDateOperandLength(String value) {
        return value.length() <= MAX_DATE_OPERAND_LENGTH;
    }
}
