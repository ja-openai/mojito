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
import java.util.List;
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
        assertNumericSelection();
        assertNumericReannotationPreservesSource();
        assertResolvedOptionPropagation();
        assertCurrencyReannotation();

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

    private static void assertNumericSelection() throws Mf2Exception {
        for (SelectionCase item : List.of(
                new SelectionCase(":number", "fr", "1000000", "many"),
                new SelectionCase(":number minimumFractionDigits=1", "ru", 1, "other"),
                new SelectionCase(":integer", "fr", "1000000.9", "many"),
                new SelectionCase(":percent", "fr", "10000", "many"))) {
            Mf2FormatResult result = parse(selectionSource(item.function())).format(
                    Map.of("value", item.value()),
                    Mf2FormatOptions.builder()
                            .locale(item.locale())
                            .functions(Mf2Icu4jFunctions.registry())
                            .build());
            if (result.hasErrors() || !result.value().equals(item.expected())) {
                throw new AssertionError(item + " returned " + result);
            }
        }
        Mf2FormatResult offset = parse(offsetSelectionSource()).format(
                Map.of("value", "1000001"),
                Mf2FormatOptions.builder()
                        .locale("fr")
                        .functions(Mf2Icu4jFunctions.registry())
                        .build());
        if (offset.hasErrors() || !offset.value().equals("many")) {
            throw new AssertionError("offset selection returned " + offset);
        }
    }

    private static void assertNumericReannotationPreservesSource() throws Mf2Exception {
        Mf2FormatResult result = format(
                ".local $n = {1.29 :number maximumFractionDigits=1} "
                        + "{{Value {$n :number maximumFractionDigits=2}}}");
        String expected = "Value 1.29";
        if (result.hasErrors() || !result.value().equals(expected)) {
            throw new AssertionError("numeric reannotation returned " + result);
        }

        assertFormatted(
                "integer semantic source",
                ".local $x = {1.25 :integer}\n.local $y = {$x :number}\n{{{$y}}}",
                "1");
        assertFormatted(
                "offset semantic source",
                ".local $step = {1 :integer}\n"
                        + ".local $x = {3 :offset subtract=$step}\n"
                        + ".local $y = {$x :number}\n"
                        + "{{{$y}}}",
                "2");
        assertFormatted(
                "fractional offset semantic source",
                ".local $n = {-1.9 :number maximumFractionDigits=0}\n"
                        + ".local $o = {$n :offset add=1}\n"
                        + "{{{$o}; {$o :number maximumFractionDigits=1}}}",
                "-0.9; -0.9");
        NumberFormat serbian = NumberFormat.getNumberInstance(ULocale.forLanguageTag("sr"));
        serbian.setMaximumFractionDigits(2);
        assertFormatted(
                "combined source provenance",
                ".local $n = {1.29 :number maximumFractionDigits=1}\n"
                        + ".local $m = {$n :number maximumFractionDigits=2}\n"
                        + ".local $i = {1.25 :integer}\n"
                        + ".local $copy = {$i :number}\n"
                        + ".match $m\n"
                        + "few {{few {$m}}}\n"
                        + "other {{other {$m}; integer {$copy}}}\n"
                        + "* {{fallback {$m}}}",
                "other " + serbian.format(1.29) + "; integer " + serbian.format(1),
                "sr");
    }

    private static void assertResolvedOptionPropagation() throws Mf2Exception {
        assertFormatted(
                "ICU4J integer fraction-option barrier",
                ".local $base = {1.25 :number minimumFractionDigits=2 maximumFractionDigits=2}\n"
                        + ".local $integer = {$base :integer}\n"
                        + "{{{$integer :number}}}",
                "1");
        assertFormatted(
                "ICU4J percent select barrier",
                ".local $base = {1 :number select=exact}\n"
                        + ".local $percent = {$base :percent}\n"
                        + ".local $copy = {$percent :number}\n"
                        + "{{{$copy}}}",
                "1");
        NumberFormat expected = NumberFormat.getNumberInstance(ULocale.ENGLISH);
        expected.setMaximumFractionDigits(1);
        assertFormatted(
                "ICU4J offset carries numeric options",
                ".local $base = {1.29 :number maximumFractionDigits=1}\n"
                        + ".local $offset = {$base :offset add=1}\n"
                        + "{{{$offset :number}}}",
                expected.format(2.29));
        assertErrorCode(
                "ICU4J offset delta does not propagate",
                ".local $offset = {1 :offset add=1}\n{{{$offset :offset}}}",
                "bad-option");
    }

    private static void assertCurrencyReannotation() throws Mf2Exception {
        NumberFormat expected = NumberFormat.getCurrencyInstance(ULocale.US);
        expected.setCurrency(Currency.getInstance("EUR"));
        assertFormatted(
                "ICU4J inherited currency",
                ".local $price = {42 :currency currency=EUR}\n{{{$price :currency}}}",
                expected.format(42));
        assertFormatted(
                "ICU4J replacement currency after number",
                ".local $price = {42 :currency currency=USD}\n"
                        + ".local $plain = {$price :number}\n"
                        + "{{{$plain :currency currency=EUR}}}",
                expected.format(42));
        for (String currency : List.of("EUR", "USD")) {
            assertErrorCode(
                    "ICU4J currency override " + currency,
                    ".local $price = {42 :currency currency=EUR}\n"
                            + "{{{$price :currency currency=" + currency + "}}}",
                    "bad-option");
        }
        assertErrorCode(
                "ICU4J missing currency", "{42 :currency}", "bad-operand");
        assertErrorCode(
                "ICU4J numeric source missing currency",
                ".local $amount = {42 :number}\n{{{$amount :currency}}}",
                "bad-operand");
    }

    private static Mf2FormatResult format(String source) throws Mf2Exception {
        return format(source, "en");
    }

    private static Mf2FormatResult format(String source, String locale) throws Mf2Exception {
        return parse(source).format(
                Map.of(),
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(Mf2Icu4jFunctions.registry())
                        .build());
    }

    private static void assertFormatted(String label, String source, String expected)
            throws Mf2Exception {
        assertFormatted(label, source, expected, "en");
    }

    private static void assertFormatted(
            String label, String source, String expected, String locale)
            throws Mf2Exception {
        Mf2FormatResult result = format(source, locale);
        if (result.hasErrors() || !result.value().equals(expected)) {
            throw new AssertionError(label + " expected \"" + expected + "\", got " + result);
        }
    }

    private static void assertErrorCode(String label, String source, String expectedCode)
            throws Mf2Exception {
        Mf2FormatResult result = format(source);
        if (result.errors().stream().noneMatch(error -> error.code().equals(expectedCode))) {
            throw new AssertionError(
                    label + " expected error " + expectedCode + ", got " + result);
        }
    }

    private static String selectionSource(String function) {
        return ".input {$value " + function + "}\n.match $value\n"
                + "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n"
                + "many {{many}}\nother {{other}}\n* {{other}}";
    }

    private static String offsetSelectionSource() {
        return ".input {$value :integer}\n.local $adjusted = {$value :offset subtract=1}\n"
                + ".match $adjusted\n"
                + "zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n"
                + "many {{many}}\nother {{other}}\n* {{other}}";
    }

    private record SelectionCase(String function, String locale, Object value, String expected) {}
}
