package com.box.l10n.mojito.mf2;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;

public final class JdkRegistryDemo {
    private static final String SOURCE = String.join(
            "; ",
            "number={$amount :number minimumFractionDigits=2}",
            "percent={$rate :percent minimumFractionDigits=1 maximumFractionDigits=1}",
            "currency={$price :currency currency=EUR}",
            "date={$due :date dateStyle=full timeZone=UTC}",
            "time={$start :time timeStyle=medium timeZone=UTC}",
            "datetime={$created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}");

    private static final double AMOUNT = 12345.678;
    private static final double RATE = 0.1234;
    private static final double PRICE = 9876.5;
    private static final LocalDate DUE = LocalDate.of(2026, 5, 21);
    private static final LocalTime START = LocalTime.of(14, 30, 15);
    private static final ZonedDateTime CREATED =
            ZonedDateTime.of(2026, 5, 21, 14, 30, 15, 0, ZoneOffset.UTC);

    private JdkRegistryDemo() {}

    public static void main(String[] args) throws Exception {
        boolean quiet = args.length > 0 && args[0].equals("--quiet");
        Mf2Message message = parse(SOURCE);
        Map<String, Object> arguments = Map.of(
                "amount", AMOUNT,
                "rate", RATE,
                "price", PRICE,
                "due", DUE,
                "start", START,
                "created", CREATED);

        for (String locale : new String[] {"en-US", "fr-FR", "ja-JP", "ar-EG"}) {
            Mf2FormatResult result = message.format(
                    arguments,
                    Mf2FormatOptions.builder()
                            .locale(locale)
                            .functions(Mf2FunctionRegistry.defaults())
                            .build());
            if (result.hasErrors()) {
                throw new AssertionError(locale + " returned errors: " + result.errors());
            }

            String expected = expected(locale);
            if (!result.value().equals(expected)) {
                throw new AssertionError(locale
                        + " expected \""
                        + expected
                        + "\", got \""
                        + result.value()
                        + "\"");
            }
            if (!quiet) {
                System.out.println(locale + " -> " + result.value());
            }
        }
        assertErrorCode("invalid currency option", "currency={$price :currency currency=||}", "bad-option");
        assertErrorCode("missing currency option", "currency={$price :currency}", "bad-option");
        assertLocaleErrorCode("malformed locale", "bad locale ???", "bad-option");
        assertLocaleErrorCode("private-use-only locale", "x-private", "bad-option");
        assertLocaleErrorCode("oversized locale", "a".repeat(257), "bad-option");
        assertLocaleFormats("private-use extension locale", "en-x-private");
        assertDateOperandErrorCode(
                "bracketed zone datetime",
                "datetime={$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
                "2026-05-21T14:30:15+02:00[Europe/Paris]",
                "bad-operand");
        if (!quiet) {
            System.out.println("Java JDK registry demo passed");
        }
    }

    private static Mf2Message parse(String source) throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model();
    }

    private static void assertErrorCode(String label, String source, String expectedCode) throws Mf2Exception {
        Mf2FormatResult result = parse(source).format(
                Map.of("price", PRICE),
                Mf2FormatOptions.builder()
                        .locale("en-US")
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (result.errors().size() != 1 || !expectedCode.equals(result.errors().get(0).code())) {
            throw new AssertionError(label + " expected " + expectedCode + ", got " + result.errors());
        }
    }

    private static void assertLocaleErrorCode(String label, String locale, String expectedCode) throws Mf2Exception {
        Mf2FormatResult result = parse("{$amount :number}").format(
                Map.of("amount", AMOUNT),
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (result.errors().size() != 1 || !expectedCode.equals(result.errors().get(0).code())) {
            throw new AssertionError(label + " expected " + expectedCode + ", got " + result.errors());
        }
    }

    private static void assertLocaleFormats(String label, String locale) throws Mf2Exception {
        Mf2FormatResult result = parse("{$amount :number}").format(
                Map.of("amount", AMOUNT),
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (result.hasErrors()) {
            throw new AssertionError(label + " returned errors: " + result.errors());
        }
    }

    private static void assertDateOperandErrorCode(
            String label, String source, String instant, String expectedCode) throws Mf2Exception {
        Mf2FormatResult result = parse(source).format(
                Map.of("instant", instant),
                Mf2FormatOptions.builder()
                        .locale("en-US")
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (result.errors().size() != 1 || !expectedCode.equals(result.errors().get(0).code())) {
            throw new AssertionError(label + " expected " + expectedCode + ", got " + result.errors());
        }
    }

    private static String expected(String localeTag) {
        Locale locale = Locale.forLanguageTag(localeTag);
        return String.join(
                "; ",
                "number=" + number(locale),
                "percent=" + percent(locale),
                "currency=" + currency(locale),
                "date=" + date(locale),
                "time=" + time(locale),
                "datetime=" + dateTime(locale));
    }

    private static String number(Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(2);
        return format.format(AMOUNT);
    }

    private static String percent(Locale locale) {
        NumberFormat format = NumberFormat.getPercentInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(RATE);
    }

    private static String currency(Locale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(Currency.getInstance("EUR"));
        return format.format(PRICE);
    }

    private static String date(Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(locale)
                .format(DUE);
    }

    private static String time(Locale locale) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(START);
    }

    private static String dateTime(Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(CREATED);
    }
}
