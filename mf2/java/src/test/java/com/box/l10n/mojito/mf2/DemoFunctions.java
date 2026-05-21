package com.box.l10n.mojito.mf2;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DemoFunctions {
    private static final Map<String, Long> RELATIVE_TIME_UNIT_SECONDS = Map.of(
            "second", 1L,
            "minute", 60L,
            "hour", 3_600L,
            "day", 86_400L,
            "week", 604_800L,
            "month", 2_592_000L,
            "quarter", 7_776_000L,
            "year", 31_536_000L);

    private DemoFunctions() {}

    static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.defaults()
                .withFunction("currency", DemoFunctions::formatCurrency)
                .withFunction("date", DemoFunctions::formatDate)
                .withFunction("datetime", DemoFunctions::formatDateTime)
                .withFunction("time", DemoFunctions::formatTime)
                .withFunction("rawType", DemoFunctions::formatRawType)
                .withFunction("relativeTime", DemoFunctions::formatRelativeTime);
    }

    private static String formatCurrency(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String currency = call.optionValue("currency", "USD");
        return formatCurrencyValue(call.value(), currency, call.locale());
    }

    private static String formatRawType(Mf2FunctionRegistry.FunctionCall call) {
        Object value = call.rawValue();
        String type;
        if (value instanceof Number) {
            type = "number";
        } else if (value instanceof Boolean) {
            type = "bool";
        } else if (value == null) {
            type = "null";
        } else {
            type = "string";
        }
        return type + "=" + call.value();
    }

    private static String formatCurrencyValue(String value, String currency, String locale)
            throws Mf2Exception {
        double amount;
        try {
            amount = Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw Mf2Exception.badOperand("Currency value must be numeric, got " + value + ".");
        }
        if (!Double.isFinite(amount)) {
            throw Mf2Exception.badOperand("Currency value must be finite.");
        }

        String normalizedCurrency = currency.toUpperCase(java.util.Locale.ROOT);
        int fractionDigits = currencyFractionDigits(normalizedCurrency);
        long scale = (long) Math.pow(10, fractionDigits);
        long rounded = Math.round(Math.abs(amount) * scale);
        long major = rounded / scale;
        long fraction = rounded % scale;
        boolean french = canonicalLocalePrefix(locale).equals("fr");
        String grouped = groupDigits(Long.toString(major), french ? "\u202f" : ",");
        String number = fractionDigits == 0
                ? grouped
                : grouped
                        + (french ? "," : ".")
                        + String.format(java.util.Locale.ROOT, "%0" + fractionDigits + "d", fraction);
        String symbol = currencySymbol(normalizedCurrency, french);
        String negative = amount < 0 ? "-" : "";
        if (french) {
            return negative + number + " " + symbol;
        }
        if (symbol.length() == 3) {
            return negative + symbol + " " + number;
        }
        return negative + symbol + number;
    }

    private static int currencyFractionDigits(String currency) {
        return switch (currency) {
            case "JPY", "KRW" -> 0;
            default -> 2;
        };
    }

    private static String currencySymbol(String currency, boolean french) {
        return switch (currency) {
            case "USD" -> french ? "$US" : "$";
            case "EUR" -> "€";
            case "JPY" -> "¥";
            case "GBP" -> "£";
            default -> currency;
        };
    }

    private static String canonicalLocalePrefix(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en";
        }
        int hyphen = locale.indexOf('-');
        int underscore = locale.indexOf('_');
        int end;
        if (hyphen < 0) {
            end = underscore < 0 ? locale.length() : underscore;
        } else if (underscore < 0) {
            end = hyphen;
        } else {
            end = Math.min(hyphen, underscore);
        }
        return locale.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static String groupDigits(String digits, String separator) {
        StringBuilder output = new StringBuilder();
        int firstGroup = digits.length() % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }
        output.append(digits, 0, firstGroup);
        for (int index = firstGroup; index < digits.length(); index += 3) {
            output.append(separator).append(digits, index, index + 3);
        }
        return output.toString();
    }

    private static String formatDate(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        LocalDate date = dateFrom(call.rawValue(), call.value());
        FormatStyle style = localizedStyle(call);
        if (style == null) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return DateTimeFormatter.ofLocalizedDate(style)
                .withLocale(javaLocale(call.locale()))
                .format(date);
    }

    private static String formatTime(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        LocalTime time = timeFrom(call.rawValue(), call.value());
        FormatStyle style = localizedStyle(call);
        if (style == null) {
            return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        return DateTimeFormatter.ofLocalizedTime(style)
                .withLocale(javaLocale(call.locale()))
                .format(time);
    }

    private static String formatDateTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        FormatStyle style = localizedStyle(call);
        if (style == null) {
            return dateTimeFrom(call.rawValue(), call.value());
        }
        return DateTimeFormatter.ofLocalizedDateTime(style)
                .withLocale(javaLocale(call.locale()))
                .format(zonedDateTimeFrom(call.rawValue(), call.value()));
    }

    private static FormatStyle localizedStyle(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String style = call.optionValue("style", "iso");
        return switch (style) {
            case "iso" -> null;
            case "short" -> FormatStyle.SHORT;
            case "medium" -> FormatStyle.MEDIUM;
            case "long" -> FormatStyle.LONG;
            case "full" -> FormatStyle.FULL;
            default -> throw new Mf2Exception(
                    "bad-option",
                    "Unsupported :" + call.function().name() + " style=" + style + ".");
        };
    }

    private static java.util.Locale javaLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return java.util.Locale.ROOT;
        }
        return java.util.Locale.forLanguageTag(locale.replace('_', '-'));
    }

    private static LocalDate dateFrom(Object rawValue, String renderedValue) throws Mf2Exception {
        if (rawValue instanceof LocalDate value) {
            return value;
        }
        if (rawValue instanceof LocalDateTime value) {
            return value.toLocalDate();
        }
        if (rawValue instanceof OffsetDateTime value) {
            return value.toLocalDate();
        }
        if (rawValue instanceof ZonedDateTime value) {
            return value.toLocalDate();
        }
        if (rawValue instanceof Instant value) {
            return value.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (rawValue instanceof java.util.Date value) {
            return value.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return parseDate(renderedValue);
    }

    private static LocalTime timeFrom(Object rawValue, String renderedValue) throws Mf2Exception {
        if (rawValue instanceof LocalTime value) {
            return value;
        }
        if (rawValue instanceof LocalDateTime value) {
            return value.toLocalTime();
        }
        if (rawValue instanceof OffsetDateTime value) {
            return value.toLocalTime();
        }
        if (rawValue instanceof ZonedDateTime value) {
            return value.toLocalTime();
        }
        if (rawValue instanceof Instant value) {
            return value.atZone(ZoneOffset.UTC).toLocalTime();
        }
        if (rawValue instanceof java.util.Date value) {
            return value.toInstant().atZone(ZoneOffset.UTC).toLocalTime();
        }
        return parseTime(renderedValue);
    }

    private static ZonedDateTime zonedDateTimeFrom(Object rawValue, String renderedValue)
            throws Mf2Exception {
        if (rawValue instanceof ZonedDateTime value) {
            return value;
        }
        if (rawValue instanceof OffsetDateTime value) {
            return value.toZonedDateTime();
        }
        if (rawValue instanceof Instant value) {
            return value.atZone(ZoneOffset.UTC);
        }
        if (rawValue instanceof java.util.Date value) {
            return value.toInstant().atZone(ZoneOffset.UTC);
        }
        if (rawValue instanceof LocalDateTime value) {
            return value.atZone(ZoneOffset.UTC);
        }
        if (rawValue instanceof LocalDate value) {
            return value.atStartOfDay(ZoneOffset.UTC);
        }
        return parseZonedDateTime(renderedValue);
    }

    private static String dateTimeFrom(Object rawValue, String renderedValue) throws Mf2Exception {
        if (rawValue instanceof Instant value) {
            return DateTimeFormatter.ISO_INSTANT.format(value);
        }
        if (rawValue instanceof java.util.Date value) {
            return DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
        }
        if (rawValue instanceof OffsetDateTime value) {
            return value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (rawValue instanceof ZonedDateTime value) {
            return value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (rawValue instanceof LocalDateTime value) {
            return value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (rawValue instanceof LocalDate value) {
            return value.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (rawValue instanceof LocalTime value) {
            return value.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        return parseDateTime(renderedValue);
    }

    private static LocalDate parseDate(String value) throws Mf2Exception {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        throw Mf2Exception.badOperand("Date function requires an ISO date, datetime, or date-like operand.");
    }

    private static LocalTime parseTime(String value) throws Mf2Exception {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalTime();
        } catch (DateTimeParseException ignored) {
        }
        throw Mf2Exception.badOperand("Time function requires an ISO time, datetime, or time-like operand.");
    }

    private static String parseDateTime(String value) throws Mf2Exception {
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
        }
        throw Mf2Exception.badOperand("Datetime function requires an ISO date or datetime operand.");
    }

    private static ZonedDateTime parseZonedDateTime(String value) throws Mf2Exception {
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toZonedDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        throw Mf2Exception.badOperand("Datetime function requires an ISO date or datetime operand.");
    }

    private static String formatRelativeTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String style = validatedOption(call, "style", "short", "long", "short", "narrow");
        String numeric = validatedOption(call, "numeric", "always", "always", "auto");
        String policy = validatedOption(call, "policy", "precise", "precise", "compact", "chat");
        String unit = validatedOption(
                call,
                "unit",
                "auto",
                "auto",
                "second",
                "minute",
                "hour",
                "day",
                "week",
                "month",
                "quarter",
                "year");

        double seconds = parseFiniteDouble(call.value(), "Relative time value");
        String selectedUnit = unit.equals("auto") ? selectRelativeTimeUnit(seconds, policy) : unit;
        long quantity = relativeTimeQuantity(seconds, selectedUnit);

        if (useRelativeZero(policy, numeric, seconds)) {
            String relative = relativeTerm(call.locale(), style, selectedUnit, "0");
            if (relative != null) {
                return relative;
            }
        }
        if (numeric.equals("auto")) {
            String offset = relativeOffset(seconds, quantity);
            if (offset != null) {
                String relative = relativeTerm(call.locale(), style, selectedUnit, offset);
                if (relative != null) {
                    return relative;
                }
            }
        }

        String direction = seconds < 0 ? "past" : "future";
        String category = PluralRules.selectCardinalPluralCategory(call.locale(), quantity);
        String pattern = relativeTimePattern(call.locale(), style, selectedUnit, direction, category);
        return pattern.replace("{0}", Long.toString(quantity));
    }

    private static String validatedOption(
            Mf2FunctionRegistry.FunctionCall call,
            String optionName,
            String defaultValue,
            String... allowedValues)
            throws Mf2Exception {
        String value = call.optionValue(optionName, defaultValue);
        for (String allowedValue : allowedValues) {
            if (allowedValue.equals(value)) {
                return value;
            }
        }
        throw new Mf2Exception(
                "bad-option",
                "Unsupported :" + call.function().name() + " option " + optionName + "=" + value + ".");
    }

    private static double parseFiniteDouble(String value, String label) throws Mf2Exception {
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw Mf2Exception.badOperand(label + " must be numeric, got " + value + ".");
        }
        if (!Double.isFinite(parsed)) {
            throw Mf2Exception.badOperand(label + " must be finite.");
        }
        return parsed;
    }

    private static String selectRelativeTimeUnit(double seconds, String policy) {
        double absolute = Math.abs(seconds);
        return switch (policy) {
            case "compact" -> {
                if (absolute < 60) yield "second";
                if (absolute < 3_600) yield "minute";
                if (absolute < 86_400) yield "hour";
                yield "day";
            }
            case "chat" -> {
                if (absolute < 45) yield "second";
                if (absolute < 2_700) yield "minute";
                if (absolute < 79_200) yield "hour";
                if (absolute < 604_800) yield "day";
                yield "week";
            }
            default -> {
                if (absolute < 60) yield "second";
                if (absolute < 3_600) yield "minute";
                if (absolute < 86_400) yield "hour";
                if (absolute < 604_800) yield "day";
                if (absolute < 2_592_000) yield "week";
                if (absolute < 31_536_000) yield "month";
                yield "year";
            }
        };
    }

    private static long relativeTimeQuantity(double seconds, String unit) {
        double absolute = Math.abs(seconds);
        if (absolute == 0) {
            return 0;
        }
        return Math.max(1L, (long) Math.floor((absolute / RELATIVE_TIME_UNIT_SECONDS.get(unit)) + 0.5));
    }

    private static boolean useRelativeZero(String policy, String numeric, double seconds) {
        return policy.equals("chat") && numeric.equals("auto") && Math.abs(seconds) < 45;
    }

    private static String relativeOffset(double seconds, long quantity) {
        if (seconds == 0) {
            return "0";
        }
        if (quantity != 1) {
            return null;
        }
        return seconds < 0 ? "-1" : "1";
    }

    private static String relativeTerm(String locale, String style, String unit, String offset)
            throws Mf2Exception {
        Object value = relativeUnitData(locale, style, unit).get("relative");
        if (!(value instanceof Map<?, ?> relative)) {
            return null;
        }
        Object term = relative.get(offset);
        return term instanceof String string ? string : null;
    }

    private static String relativeTimePattern(
            String locale, String style, String unit, String direction, String category)
            throws Mf2Exception {
        Map<String, Object> unitData = relativeUnitData(locale, style, unit);
        Map<String, Object> patterns = object(unitData.get(direction));
        Object pattern = patterns.get(category == null ? "other" : category);
        if (!(pattern instanceof String)) {
            pattern = patterns.get("other");
        }
        if (pattern instanceof String string) {
            return string;
        }
        throw new Mf2Exception(
                "missing-locale-data",
                "Missing relative-time pattern for " + locale + "/" + style + "/" + unit + "/" + direction + ".");
    }

    private static Map<String, Object> relativeUnitData(String locale, String style, String unit)
            throws Mf2Exception {
        RelativeTimeData data = relativeTimeData();
        String patternSetId = LocaleKey.lookup(data.localeMap(), locale, null);
        if (patternSetId == null) {
            throw new Mf2Exception("missing-locale-data", "Missing relative-time locale data for " + locale + ".");
        }
        Map<String, Object> styleData = object(object(data.patternSets().get(patternSetId)).get(style));
        return object(styleData.get(unit));
    }

    private static RelativeTimeData relativeTimeData() throws Mf2Exception {
        return RelativeTimeDataHolder.DATA;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private record RelativeTimeData(Map<String, String> localeMap, Map<String, Object> patternSets) {}

    private static final class RelativeTimeDataHolder {
        private static final RelativeTimeData DATA = loadRelativeTimeData();

        private static RelativeTimeData loadRelativeTimeData() {
            try {
                Map<String, Object> root = object(JsonParser.parse(
                        Path.of("../cldr/generated/relative-time/all/relative_time.json")));
                Map<String, String> localeMap = new HashMap<>();
                object(root.get("localeMap")).forEach((locale, setId) -> localeMap.put(locale, (String) setId));
                Map<String, Object> patternSets = new HashMap<>();
                for (Object rawPatternSet : (List<?>) root.get("patternSets")) {
                    Map<String, Object> patternSet = object(rawPatternSet);
                    patternSets.put((String) patternSet.get("id"), patternSet.get("data"));
                }
                return new RelativeTimeData(localeMap, patternSets);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Failed to load generated relative-time data.", error);
            }
        }
    }
}
