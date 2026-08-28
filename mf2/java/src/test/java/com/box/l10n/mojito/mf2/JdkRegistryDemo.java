package com.box.l10n.mojito.mf2;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.List;
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
        assertNumericSelection();
        assertNumberMaximumFractionDigits();
        assertNumericOptionInheritance();
        assertResolvedNumericSemantics(
                Mf2FunctionRegistry.defaults(), "JDK", "other 1,29; integer 1");
        assertResolvedNumericSemantics(
                Mf2FunctionRegistry.portable(), "portable", "other 1.29; integer 1");
        assertResolvedOptionPropagation(Mf2FunctionRegistry.defaults(), "JDK");
        assertResolvedOptionPropagation(Mf2FunctionRegistry.portable(), "portable");
        assertCurrencyReannotation();
        if (!quiet) {
            System.out.println("Java JDK registry demo passed");
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
                            .functions(Mf2FunctionRegistry.defaults())
                            .build());
            if (result.hasErrors() || !result.value().equals(item.expected())) {
                throw new AssertionError(item + " returned " + result);
            }
        }
        Mf2FormatResult offset = parse(offsetSelectionSource()).format(
                Map.of("value", "1000001"),
                Mf2FormatOptions.builder()
                        .locale("fr")
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (offset.hasErrors() || !offset.value().equals("many")) {
            throw new AssertionError("offset selection returned " + offset);
        }
    }

    private static void assertNumberMaximumFractionDigits() throws Mf2Exception {
        Mf2FormatResult result = parse("{$value :number maximumFractionDigits=1}").format(
                Map.of("value", 1.29),
                Mf2FormatOptions.builder()
                        .locale("en-US")
                        .functions(Mf2FunctionRegistry.defaults())
                        .build());
        if (result.hasErrors() || !result.value().equals("1.3")) {
            throw new AssertionError("maximumFractionDigits returned " + result);
        }
    }

    private static void assertNumericOptionInheritance() throws Mf2Exception {
        Mf2FormatResult result = parse(
                        ".local $n = {1.2 :number minimumFractionDigits=2 signDisplay=always} "
                                + "{{Value {$n :number}}}")
                .format(
                        Map.of(),
                        Mf2FormatOptions.builder()
                                .locale("en-US")
                                .functions(Mf2FunctionRegistry.defaults())
                                .build());
        if (result.hasErrors() || !result.value().equals("Value +1.20")) {
            throw new AssertionError("numeric option inheritance returned " + result);
        }
    }

    private static void assertResolvedNumericSemantics(
            Mf2FunctionRegistry functions, String label, String provenanceExpected)
            throws Mf2Exception {
        assertFormatted(
                label + " rounded number source",
                ".local $n = {1.29 :number maximumFractionDigits=1} "
                        + "{{Value {$n :number maximumFractionDigits=2}}}",
                "Value 1.29",
                functions);
        assertFormatted(
                label + " integer source",
                ".local $x = {1.25 :integer}\n.local $y = {$x :number}\n{{{$y}}}",
                "1",
                functions);
        assertFormatted(
                label + " offset source",
                ".local $step = {1 :integer}\n"
                        + ".local $x = {3 :offset subtract=$step}\n"
                        + ".local $y = {$x :number}\n"
                        + "{{{$y}}}",
                "2",
                functions);
        assertFormatted(
                label + " fractional offset source",
                ".local $n = {-1.9 :number maximumFractionDigits=0}\n"
                        + ".local $o = {$n :offset add=1}\n"
                        + "{{{$o}; {$o :number maximumFractionDigits=1}}}",
                "-0.9; -0.9",
                functions);
        assertFormatted(
                label + " source provenance",
                ".local $n = {1.29 :number maximumFractionDigits=1}\n"
                        + ".local $m = {$n :number maximumFractionDigits=2}\n"
                        + ".local $i = {1.25 :integer}\n"
                        + ".local $copy = {$i :number}\n"
                        + ".match $m\n"
                        + "few {{few {$m}}}\n"
                        + "other {{other {$m}; integer {$copy}}}\n"
                        + "* {{fallback {$m}}}",
                provenanceExpected,
                "sr",
                functions);
    }

    private static void assertResolvedOptionPropagation(
            Mf2FunctionRegistry functions, String label) throws Mf2Exception {
        assertFormatted(
                label + " integer fraction-option barrier",
                ".local $base = {1.25 :number minimumFractionDigits=2 maximumFractionDigits=2}\n"
                        + ".local $integer = {$base :integer}\n"
                        + "{{{$integer :number}}}",
                "1",
                functions);
        assertFormatted(
                label + " percent select barrier",
                ".local $base = {1 :number select=exact}\n"
                        + ".local $percent = {$base :percent}\n"
                        + ".local $copy = {$percent :number}\n"
                        + "{{{$copy}}}",
                "1",
                functions);
        assertFormatted(
                label + " offset carries numeric options",
                ".local $base = {1.29 :number maximumFractionDigits=1}\n"
                        + ".local $offset = {$base :offset add=1}\n"
                        + "{{{$offset :number}}}",
                "2.3",
                functions);
        assertErrorCode(
                label + " offset delta does not propagate",
                ".local $offset = {1 :offset add=1}\n{{{$offset :offset}}}",
                "bad-option",
                functions);

        Mf2FunctionRegistry probeFunctions = functions.withFunction("number", call -> {
            String probe = call.optionValue("probe", null);
            return probe == null ? call.value() : call.optionValue(probe, "missing");
        });
        assertFormatted(
                label + " integer significant-option barrier",
                ".local $base = {1.25 :number minimumSignificantDigits=3}\n"
                        + ".local $integer = {$base :integer}\n"
                        + "{{{$integer :number probe=minimumSignificantDigits}}}",
                "missing",
                probeFunctions);
        for (String option : List.of("minimumIntegerDigits", "roundingIncrement")) {
            assertFormatted(
                    label + " percent " + option + " barrier",
                    ".local $base = {1 :number " + option + "=3}\n"
                            + ".local $percent = {$base :percent}\n"
                            + "{{{$percent :number probe=" + option + "}}}",
                    "missing",
                    probeFunctions);
        }
        for (String function : List.of("percent", "offset add=1")) {
            assertFormatted(
                    label + " " + function + " carries remaining options",
                    ".local $base = {1 :number minimumSignificantDigits=3}\n"
                            + ".local $derived = {$base :" + function + "}\n"
                            + "{{{$derived :number probe=minimumSignificantDigits}}}",
                    "3",
                    probeFunctions);
        }
    }

    private static void assertCurrencyReannotation() throws Mf2Exception {
        NumberFormat expected = NumberFormat.getCurrencyInstance(Locale.US);
        expected.setCurrency(Currency.getInstance("EUR"));
        assertFormatted(
                "JDK inherited currency",
                ".local $price = {42 :currency currency=EUR}\n{{{$price :currency}}}",
                expected.format(42),
                Mf2FunctionRegistry.defaults());
        assertFormatted(
                "JDK replacement currency after number",
                ".local $price = {42 :currency currency=USD}\n"
                        + ".local $plain = {$price :number}\n"
                        + "{{{$plain :currency currency=EUR}}}",
                expected.format(42),
                Mf2FunctionRegistry.defaults());
        for (String currency : List.of("EUR", "USD")) {
            assertErrorCode(
                    "JDK currency override " + currency,
                    ".local $price = {42 :currency currency=EUR}\n"
                            + "{{{$price :currency currency=" + currency + "}}}",
                    "bad-option",
                    Mf2FunctionRegistry.defaults());
        }
        assertErrorCode(
                "JDK missing currency",
                "{42 :currency}",
                "bad-operand",
                Mf2FunctionRegistry.defaults());
        assertErrorCode(
                "JDK numeric source missing currency",
                ".local $amount = {42 :number}\n{{{$amount :currency}}}",
                "bad-operand",
                Mf2FunctionRegistry.defaults());
    }

    private static void assertFormatted(
            String label, String source, String expected, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        assertFormatted(label, source, expected, "en-US", functions);
    }

    private static void assertFormatted(
            String label,
            String source,
            String expected,
            String locale,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        Mf2FormatResult result = parse(source).format(
                Map.of(),
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(functions)
                        .build());
        if (result.hasErrors() || !result.value().equals(expected)) {
            throw new AssertionError(label + " expected \"" + expected + "\", got " + result);
        }
    }

    private static void assertErrorCode(
            String label, String source, String expectedCode, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        Mf2FormatResult result = parse(source).format(
                Map.of(),
                Mf2FormatOptions.builder()
                        .locale("en-US")
                        .functions(functions)
                        .build());
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

    private static Mf2Message parse(String source) throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model();
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
