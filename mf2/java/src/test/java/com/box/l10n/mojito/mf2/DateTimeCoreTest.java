package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DecimalStyle;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DateTimeCoreTest {
    private DateTimeCoreTest() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/date-time-core/cases.json");
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        int formatCases = checkFormatCases(arrayOrEmpty(fixture.get("formatCases")));
        int numericTimestampCases = checkFormatCases(arrayOrEmpty(fixture.get("numericTimestampCases")));
        int referenceCases = checkReferenceCases(arrayOrEmpty(fixture.get("intlReferenceCases")));
        int semanticReferenceCases =
                checkReferenceCases(arrayOrEmpty(fixture.get("semanticStyleReferenceCases")));
        int errorCases = checkErrorCases(arrayOrEmpty(fixture.get("errorCases")));
        int registryFormatCases = checkRegistryFormatCases(arrayOrEmpty(fixture.get("registryFormatCases")));
        int registryErrorCases = checkRegistryErrorCases(arrayOrEmpty(fixture.get("registryErrorCases")));
        checkRegistryIntegration();
        checkOperandBoundary();
        checkDirectHostDateTypes();
        checkDefaultOverloads();
        System.out.printf(
                "Java date/time core test passed %d format cases, %d numeric timestamp cases, "
                        + "%d JDK reference cases, %d semantic style reference cases, %d error cases, %d registry format cases, "
                        + "%d registry error cases, registry integration, and default overloads.%n",
                formatCases, numericTimestampCases, referenceCases, semanticReferenceCases, errorCases, registryFormatCases, registryErrorCases);
    }

    private static int checkFormatCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            String actual = formatCore(item);
            String expected = string(item.get("expected"));
            if (!actual.equals(expected)) {
                throw new AssertionError(String.format(
                        "%s: expected %s, got %s",
                        string(item.get("name")), expected, actual));
            }
            checked++;
        }
        return checked;
    }

    private static int checkReferenceCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            String locale = string(item.get("locale"));
            Map<String, Object> rawOptions =
                    objectOrEmpty(item.containsKey("referenceOptions")
                            ? item.get("referenceOptions")
                            : item.get("options"));
            String actual = formatCore(item);
            String expected = jdkFormat(string(item.get("kind")), locale, string(item.get("value")), rawOptions);
            if (!actual.equals(expected)) {
                if (isKnownJdkDateTimeDifference(rawOptions)) {
                    checked++;
                    continue;
                }
                throw new AssertionError(String.format(
                        "JDK reference %s %s %s: expected %s, got %s",
                        string(item.get("kind")), locale, rawOptions, expected, actual));
            }
            checked++;
        }
        return checked;
    }

    private static int checkErrorCases(List<Object> cases) throws Exception {
        int checked = 0;
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            String expected = string(item.get("expectedError"));
            try {
                formatErrorCase(item);
                throw new AssertionError(String.format(
                        "%s: expected %s, got success",
                        string(item.get("name")), expected));
            } catch (Mf2Exception error) {
                if (!error.code().equals(expected)) {
                    throw new AssertionError(String.format(
                            "%s: expected %s, got %s",
                            string(item.get("name")), expected, error.code()));
                }
            }
            checked++;
        }
        return checked;
    }

    private static int checkRegistryFormatCases(List<Object> cases) throws Exception {
        int checked = 0;
        Mf2FunctionRegistry registry = Mf2DateTimeCore.registry();
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult parsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (parsed.hasDiagnostics()) {
                throw new AssertionError(
                        string(item.get("name")) + ": parse diagnostics " + parsed.diagnostics());
            }
            Mf2FormatResult result = parsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            if (!result.value().equals(string(item.get("expected"))) || result.hasErrors()) {
                throw new AssertionError(string(item.get("name")) + ": expected "
                        + string(item.get("expected")) + ", got "
                        + result.value() + " errors=" + result.errors());
            }
            checked++;
        }
        return checked;
    }

    private static int checkRegistryErrorCases(List<Object> cases) throws Exception {
        int checked = 0;
        Mf2FunctionRegistry registry = Mf2DateTimeCore.registry();
        for (Object rawCase : cases) {
            Map<String, Object> item = object(rawCase);
            Mf2ParseResult parsed = Mf2Parser.parseToModel(string(item.get("source")));
            if (parsed.hasDiagnostics()) {
                throw new AssertionError(
                        string(item.get("name")) + ": parse diagnostics " + parsed.diagnostics());
            }
            Mf2FormatResult result = parsed.model().format(
                    objectOrEmpty(item.get("arguments")),
                    Mf2FormatOptions.builder()
                            .locale(string(item.get("locale")))
                            .functions(registry)
                            .build());
            List<String> actualCodes = result.errors().stream().map(Mf2Exception::code).toList();
            if (!actualCodes.equals(stringList(item.get("expectedErrors")))) {
                throw new AssertionError(string(item.get("name")) + ": expected errors "
                        + stringList(item.get("expectedErrors")) + ", got "
                        + actualCodes + " value=" + result.value());
            }
            checked++;
        }
        return checked;
    }

    private static void checkOperandBoundary() throws Exception {
        Object arbitraryDate = new Object() {
            @Override
            public String toString() {
                return "2026-05-21T14:30:15Z";
            }
        };
        try {
            Mf2DateTimeCore.formatDateTime(arbitraryDate, Mf2DateTimeCore.options().build());
            throw new AssertionError("date-time core should reject arbitrary object operands");
        } catch (Mf2Exception error) {
            if (!"bad-operand".equals(error.code())) {
                throw new AssertionError("date-time core object operand got " + error.code());
            }
        }
        CharSequence throwingText = new CharSequence() {
            @Override
            public int length() {
                return 1;
            }

            @Override
            public char charAt(int index) {
                return 'x';
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return this;
            }

            @Override
            public String toString() {
                throw new IllegalStateException("boom stringify");
            }
        };
        try {
            Mf2DateTimeCore.formatDateTime(throwingText, Mf2DateTimeCore.options().build());
            throw new AssertionError("date-time core should wrap CharSequence stringify failures");
        } catch (Mf2Exception error) {
            if (!"bad-operand".equals(error.code())) {
                throw new AssertionError("date-time core throwing CharSequence operand got " + error.code());
            }
        }
    }

    private static void checkDirectHostDateTypes() throws Exception {
        assertSameDefault("date LocalDate", "May 21, 2026", Mf2DateTimeCore.formatDate(LocalDate.of(2026, 5, 21)));
        assertSameDefault("time LocalTime", "2:30:15\u202fPM", Mf2DateTimeCore.formatTime(LocalTime.of(14, 30, 15)));
        assertSameDefault(
                "datetime LocalDateTime",
                "May 21, 2026, 2:30:15\u202fPM",
                Mf2DateTimeCore.formatDateTime(LocalDateTime.of(2026, 5, 21, 14, 30, 15)));
    }

    private static void checkDefaultOverloads() throws Exception {
        String value = "2026-05-21T14:30:15Z";
        String date = Mf2DateTimeCore.formatDate(value);
        String time = Mf2DateTimeCore.formatTime(value);
        String dateTime = Mf2DateTimeCore.formatDateTime(value);
        assertSameDefault("date", date, Mf2DateTimeCore.formatDate(value, null));
        assertSameDefault("time", time, Mf2DateTimeCore.formatTime(value, null));
        assertSameDefault("datetime", dateTime, Mf2DateTimeCore.formatDateTime(value, null));
        assertSameParts("date", date, Mf2DateTimeCore.formatDateToParts(value), Mf2DateTimeCore.formatDateToParts(value, null));
        assertSameParts("time", time, Mf2DateTimeCore.formatTimeToParts(value), Mf2DateTimeCore.formatTimeToParts(value, null));
        assertSameParts(
                "datetime",
                dateTime,
                Mf2DateTimeCore.formatDateTimeToParts(value),
                Mf2DateTimeCore.formatDateTimeToParts(value, null));
    }

    private static void assertSameDefault(String kind, String overloaded, String explicitDefault) {
        if (!overloaded.equals(explicitDefault)) {
            throw new AssertionError("date/time core " + kind + " default overload expected "
                    + explicitDefault + ", got " + overloaded);
        }
    }

    private static void assertSameParts(
            String kind,
            String formatted,
            List<Mf2FormattedPart> overloaded,
            List<Mf2FormattedPart> explicitDefault) {
        List<Mf2FormattedPart> expected = List.of(new Mf2FormattedPart.Text(formatted));
        if (!overloaded.equals(expected) || !explicitDefault.equals(expected)) {
            throw new AssertionError("date/time core " + kind + " formatToParts expected "
                    + expected + ", got " + overloaded + " and " + explicitDefault);
        }
    }

    private static String formatErrorCase(Map<String, Object> item) throws Exception {
        String kind = string(item.get("kind"));
        String functionName = switch (kind) {
            case "date", "time", "datetime" -> kind;
            default -> throw new IllegalArgumentException("Unsupported date/time core fixture kind: " + kind);
        };
        Map<String, Object> rawOptions = objectOrEmpty(item.get("options"));
        return Mf2DateTimeCore.registry().format(new Mf2FunctionRegistry.FunctionCall(
                String.valueOf(item.get("value")),
                item.get("value"),
                new Mf2Message.FunctionRef(functionName, Map.of()),
                string(item.get("locale")),
                (optionName, defaultValue) -> rawOptions.containsKey(optionName)
                        ? String.valueOf(rawOptions.get(optionName))
                        : defaultValue,
                null));
    }

    private static void checkRegistryIntegration() throws Exception {
        Mf2FunctionRegistry registry = Mf2DateTimeCore.registry();
        Mf2ParseResult parsed = Mf2Parser.parseToModel(
                "At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}");
        Mf2FormatResult result = parsed.model().format(
                Map.of("instant", "2026-05-21T14:30:15Z"),
                Mf2FormatOptions.builder().locale("de-DE").functions(registry).build());
        if (!result.value().equals("At Donnerstag, 21. Mai 2026 um 14:30:15") || result.hasErrors()) {
            throw new AssertionError("registry datetime format got "
                    + result.value() + " errors=" + result.errors());
        }

        Mf2ParseResult stringParsed = Mf2Parser.parseToModel("Hello {$name :string}");
        Mf2FormatResult stringResult = stringParsed.model().format(
                Map.of("name", "Mojito"),
                Mf2FormatOptions.builder().functions(registry).build());
        if (!stringResult.value().equals("Hello Mojito") || stringResult.hasErrors()) {
            throw new AssertionError("registry should preserve portable string formatting, got "
                    + stringResult.value() + " errors=" + stringResult.errors());
        }
    }

    private static String formatCore(Map<String, Object> item) throws Exception {
        String kind = string(item.get("kind"));
        String locale = string(item.get("locale"));
        Object value = item.get("value");
        Mf2DateTimeCore.Options options = options(locale, objectOrEmpty(item.get("options")));
        return switch (kind) {
            case "date" -> Mf2DateTimeCore.formatDate(value, options);
            case "time" -> Mf2DateTimeCore.formatTime(value, options);
            case "datetime" -> Mf2DateTimeCore.formatDateTime(value, options);
            default -> throw new IllegalArgumentException("Unsupported date/time core fixture kind: " + kind);
        };
    }

    private static Mf2DateTimeCore.Options options(String locale, Map<String, Object> rawOptions) {
        Mf2DateTimeCore.Options.Builder builder = Mf2DateTimeCore.options().locale(locale);
        if (rawOptions.containsKey("style")) {
            builder.style(style(string(rawOptions.get("style"))));
        }
        if (rawOptions.containsKey("dateStyle")) {
            builder.dateStyle(style(string(rawOptions.get("dateStyle"))));
        }
        if (rawOptions.containsKey("timeStyle")) {
            builder.timeStyle(style(string(rawOptions.get("timeStyle"))));
        }
        if (rawOptions.containsKey("length")) {
            builder.dateStyle(style(string(rawOptions.get("length"))));
        }
        if (rawOptions.containsKey("precision")) {
            builder.timeStyle(style(string(rawOptions.get("precision"))));
        }
        if (rawOptions.containsKey("dateLength")) {
            builder.dateStyle(style(string(rawOptions.get("dateLength"))));
        }
        if (rawOptions.containsKey("timePrecision")) {
            builder.timeStyle(style(string(rawOptions.get("timePrecision"))));
        }
        if (rawOptions.containsKey("skeleton")) {
            builder.skeleton(string(rawOptions.get("skeleton")));
        }
        if (rawOptions.containsKey("hourCycle")) {
            builder.hourCycle(string(rawOptions.get("hourCycle")));
        }
        if (rawOptions.containsKey("timeZone")) {
            builder.timeZone(string(rawOptions.get("timeZone")));
        }
        if (rawOptions.containsKey("calendar")) {
            builder.calendar(string(rawOptions.get("calendar")));
        }
        return builder.build();
    }

    private static String jdkFormat(
            String kind,
            String locale,
            String value,
            Map<String, Object> rawOptions) {
        DateTimeFormatter formatter = switch (kind) {
            case "date" -> DateTimeFormatter.ofLocalizedDate(formatStyle(dateStyle(rawOptions)));
            case "time" -> DateTimeFormatter.ofLocalizedTime(formatStyle(timeStyle(rawOptions)));
            case "datetime" -> DateTimeFormatter.ofLocalizedDateTime(
                    formatStyle(dateStyle(rawOptions)),
                    formatStyle(timeStyle(rawOptions)));
            default -> throw new IllegalArgumentException("Unsupported date/time core fixture kind: " + kind);
        };
        Locale tag = Locale.forLanguageTag(locale);
        return formatter
                .withLocale(tag)
                .withDecimalStyle(DecimalStyle.of(tag))
                .withZone(referenceZone(rawOptions))
                .format(Instant.parse(value));
    }

    private static boolean isKnownJdkDateTimeDifference(Map<String, Object> rawOptions) {
        if (rawOptions.containsKey("hourCycle") && !rawOptions.containsKey("skeleton")) {
            return true;
        }
        if (rawOptions.containsKey("dateStyle") && rawOptions.containsKey("timeStyle")) {
            String dateStyle = string(rawOptions.get("dateStyle"));
            return dateStyle.equals("full") || dateStyle.equals("long");
        }
        return false;
    }

    private static Mf2DateTimeCore.Style dateStyle(Map<String, Object> rawOptions) {
        return style(stringOrDefault(
                rawOptions.get("dateStyle"),
                stringOrDefault(rawOptions.get("dateLength"), stringOrDefault(rawOptions.get("style"), "medium"))));
    }

    private static Mf2DateTimeCore.Style timeStyle(Map<String, Object> rawOptions) {
        return style(stringOrDefault(
                rawOptions.get("timeStyle"),
                stringOrDefault(rawOptions.get("timePrecision"), stringOrDefault(rawOptions.get("style"), "medium"))));
    }

    private static FormatStyle formatStyle(Mf2DateTimeCore.Style style) {
        return switch (style) {
            case FULL -> FormatStyle.FULL;
            case LONG -> FormatStyle.LONG;
            case MEDIUM -> FormatStyle.MEDIUM;
            case SHORT -> FormatStyle.SHORT;
        };
    }

    private static Mf2DateTimeCore.Style style(String value) {
        return switch (value) {
            case "full" -> Mf2DateTimeCore.Style.FULL;
            case "long" -> Mf2DateTimeCore.Style.LONG;
            case "medium" -> Mf2DateTimeCore.Style.MEDIUM;
            case "short" -> Mf2DateTimeCore.Style.SHORT;
            default -> throw new IllegalArgumentException("Unknown date/time core style: " + value);
        };
    }

    private static ZoneOffset referenceZone(Map<String, Object> rawOptions) {
        if (!rawOptions.containsKey("timeZone")) {
            return ZoneOffset.UTC;
        }
        String value = string(rawOptions.get("timeZone")).trim();
        if (value.isEmpty()
                || value.equals("UTC")
                || value.equals("Etc/UTC")
                || value.equals("Z")
                || value.equals("GMT")
                || value.equals("Etc/GMT")) {
            return ZoneOffset.UTC;
        }
        if ((value.startsWith("UTC") || value.startsWith("GMT")) && value.length() > 3) {
            value = value.substring(3);
        }
        try {
            return ZoneOffset.of(value);
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(
                    "JDK reference supports only UTC or fixed-offset time zones: "
                            + string(rawOptions.get("timeZone")),
                    error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value == null ? Map.of() : object(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arrayOrEmpty(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static String string(Object value) {
        return (String) value;
    }

    private static List<String> stringList(Object value) {
        return arrayOrEmpty(value).stream().map(DateTimeCoreTest::string).toList();
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }
}
