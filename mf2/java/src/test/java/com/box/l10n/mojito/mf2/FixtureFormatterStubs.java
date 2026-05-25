package com.box.l10n.mojito.mf2;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

// Test/sample-only formatter shims for dependency-free fixtures; not a production registry.
final class FixtureFormatterStubs {
    private static final DateTimeFormatter FIXTURE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private FixtureFormatterStubs() {}

    static Mf2FunctionRegistry registry() {
        return Mf2FunctionRegistry.portable()
                .withFunction("currency", FixtureFormatterStubs::formatCurrency)
                .withFunction("datetime", FixtureFormatterStubs::formatDateTime)
                .withFunction("date", FixtureFormatterStubs::formatDate)
                .withFunction("time", FixtureFormatterStubs::formatTime);
    }

    private static String formatCurrency(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(
                call, "Currency function requires a numeric operand.");
        String currency = currencyCode(call);
        if (currency == null) {
            throw Mf2Exception.badOperand("Currency function requires a currency option.");
        }
        Integer fractionDigits = currencyFractionDigits(call);
        String number = fractionDigits == null
                ? Mf2UnlocalizedNumericFunctions.formatDecimalNumber(value, false, 0)
                : Mf2UnlocalizedNumericFunctions.formatFixedFractionDigits(value, fractionDigits);
        return currency + " " + number;
    }

    private static String formatDateTime(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String fromRawValue = dateTimeFromRawValue(call.rawValue());
        if (fromRawValue != null) {
            return fromRawValue;
        }
        if (isIsoDate(call.value()) || isIsoDateTime(call.value())) {
            return normalizeIsoDateTime(call.value());
        }
        throw Mf2Exception.badOperand("Datetime function requires a date or datetime operand.");
    }

    private static String formatDate(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        String fromRawValue = dateFromRawValue(call.rawValue());
        if (fromRawValue != null) {
            return fromRawValue;
        }
        if (isIsoDate(call.value()) || isIsoDateTime(call.value())) {
            return call.value().split("T", 2)[0];
        }
        throw Mf2Exception.badOperand("Date function requires a date or datetime operand.");
    }

    private static String formatTime(Mf2FunctionRegistry.FunctionCall call) throws Mf2Exception {
        String fromRawValue = timeFromRawValue(call.rawValue());
        if (fromRawValue != null) {
            return fromRawValue;
        }
        if (isIsoDateTime(call.value())) {
            return normalizeIsoTime(call.value().split("T", 2)[1]);
        }
        if (isIsoTime(call.value())) {
            return normalizeIsoTime(call.value());
        }
        throw Mf2Exception.badOperand("Datetime and time functions require a datetime operand.");
    }

    private static String dateTimeFromRawValue(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate() + "T" + formatFixtureTime(dateTime.toLocalTime());
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return null;
    }

    private static String dateFromRawValue(Object value) {
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate().toString();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        return null;
    }

    private static String timeFromRawValue(Object value) {
        if (value instanceof LocalTime time) {
            return formatFixtureTime(time);
        }
        if (value instanceof LocalDateTime dateTime) {
            return formatFixtureTime(dateTime.toLocalTime());
        }
        if (value instanceof Instant instant) {
            return formatFixtureTime(instant.atZone(ZoneOffset.UTC).toLocalTime());
        }
        if (value instanceof Date date) {
            return formatFixtureTime(date.toInstant().atZone(ZoneOffset.UTC).toLocalTime());
        }
        return null;
    }

    private static String normalizeIsoDateTime(String value) {
        String[] parts = value.split("T", 2);
        return parts.length == 2 ? parts[0] + "T" + normalizeIsoTime(parts[1]) : value;
    }

    private static String normalizeIsoTime(String value) {
        if (value.length() == 5) {
            return value + ":00";
        }
        return value;
    }

    private static String formatFixtureTime(LocalTime time) {
        return time.format(FIXTURE_TIME_FORMAT);
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

    private static String currencyCode(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String currency = call.optionValue("currency", null);
        if (currency != null) {
            return currency;
        }
        return inheritedCurrencyCode(call.inheritedSource());
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

    private static boolean isIsoDateTime(String value) {
        int separator = value.indexOf('T');
        return separator >= 0
                && isIsoDate(value.substring(0, separator))
                && isIsoTime(value.substring(separator + 1));
    }

    private static boolean isIsoDate(String value) {
        return value.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static boolean isIsoTime(String value) {
        return value.matches("\\d{2}:\\d{2}(:\\d{2})?");
    }
}
