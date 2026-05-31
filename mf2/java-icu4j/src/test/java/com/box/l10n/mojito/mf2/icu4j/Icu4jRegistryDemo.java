package com.box.l10n.mojito.mf2.icu4j;

import com.box.l10n.mojito.mf2.Mf2Exception;
import com.box.l10n.mojito.mf2.Mf2FormatOptions;
import com.box.l10n.mojito.mf2.Mf2FormatResult;
import com.box.l10n.mojito.mf2.Mf2Message;
import com.box.l10n.mojito.mf2.Mf2ParseResult;
import com.box.l10n.mojito.mf2.Mf2Parser;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.DisplayContext;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RelativeDateTimeFormatter;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;

public final class Icu4jRegistryDemo {
    private static final String SOURCE = String.join(
            "; ",
            "number={$amount :number minimumFractionDigits=2}",
            "percent={$rate :percent minimumFractionDigits=1 maximumFractionDigits=1}",
            "currency={$price :currency currency=EUR}",
            "date={$due :date dateStyle=full timeZone=UTC}",
            "time={$start :time timeStyle=medium timeZone=UTC}",
            "datetime={$created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "relative={$days :relativeTime unit=day numeric=auto style=long}");
    private static final double AMOUNT = 12345.678;
    private static final double RATE = 0.1234;
    private static final double PRICE = 9876.5;
    private static final LocalDate DUE = LocalDate.of(2026, 5, 21);
    private static final LocalTime START = LocalTime.of(14, 30, 15);
    private static final ZonedDateTime CREATED =
            ZonedDateTime.of(2026, 5, 21, 14, 30, 15, 0, ZoneOffset.UTC);
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private Icu4jRegistryDemo() {}

    public static void main(String[] args) throws Exception {
        boolean quiet = args.length > 0 && args[0].equals("--quiet");
        Mf2Message message = parse(SOURCE);
        Map<String, Object> arguments = Map.of(
                "amount", AMOUNT,
                "rate", RATE,
                "price", PRICE,
                "due", DUE,
                "start", START,
                "created", CREATED,
                "days", 1);

        for (String locale : new String[] {"en-US", "fr-FR", "ja-JP", "ar-EG"}) {
            Mf2FormatResult result = message.format(
                    arguments,
                    Mf2FormatOptions.builder()
                            .locale(locale)
                            .functions(Mf2Icu4jFunctions.registry())
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

        assertUnsupportedUnitFallsBack();

        if (!quiet) {
            System.out.println("Java ICU4J registry demo passed");
        }
    }

    private static Mf2Message parse(String source) throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model();
    }

    private static String expected(String localeTag) {
        ULocale locale = ULocale.forLanguageTag(localeTag);
        return String.join(
                "; ",
                "number=" + number(locale),
                "percent=" + percent(locale),
                "currency=" + currency(locale),
                "date=" + date(locale),
                "time=" + time(locale),
                "datetime=" + dateTime(locale),
                "relative=" + relative(locale));
    }

    private static String number(ULocale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(2);
        return format.format(AMOUNT);
    }

    private static String percent(ULocale locale) {
        NumberFormat format = NumberFormat.getPercentInstance(locale);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(RATE);
    }

    private static String currency(ULocale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(Currency.getInstance("EUR"));
        return format.format(PRICE);
    }

    private static String date(ULocale locale) {
        DateFormat format = DateFormat.getDateInstance(DateFormat.FULL, locale);
        format.setTimeZone(UTC);
        return format.format(Date.from(DUE.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    private static String time(ULocale locale) {
        DateFormat format = DateFormat.getTimeInstance(DateFormat.MEDIUM, locale);
        format.setTimeZone(UTC);
        return format.format(Date.from(
                START.atDate(LocalDate.of(1970, 1, 1)).atZone(ZoneOffset.UTC).toInstant()));
    }

    private static String dateTime(ULocale locale) {
        DateFormat format =
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale);
        format.setTimeZone(UTC);
        return format.format(Date.from(CREATED.toInstant()));
    }

    private static String relative(ULocale locale) {
        return RelativeDateTimeFormatter.getInstance(
                        locale,
                        null,
                        RelativeDateTimeFormatter.Style.LONG,
                        DisplayContext.CAPITALIZATION_NONE)
                .format(1, RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY);
    }

    private static void assertUnsupportedUnitFallsBack() throws Mf2Exception {
        Mf2Message message = parse("{$value :relativeTime unit=fortnight}");
        Mf2FormatResult result = message.format(
                Map.of("value", 1),
                Mf2FormatOptions.builder()
                        .functions(Mf2Icu4jFunctions.registry())
                        .build());
        if (!result.hasErrors() || !result.value().equals("{$value}")) {
            throw new AssertionError(
                    "unsupported relativeTime unit should recover with visible fallback");
        }
    }
}
