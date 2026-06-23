package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DecimalStyle;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DateTimeCoreBenchmark {
    private DateTimeCoreBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/date-time-core/cases.json");
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        int warmupIterations = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        List<DateTimeCoreCase> formatCases = arrayOrEmpty(fixture.get("formatCases")).stream()
                .map(DateTimeCoreBenchmark::dateTimeCoreCase)
                .toList();
        List<JdkCase> referenceCases = arrayOrEmpty(fixture.get("intlReferenceCases")).stream()
                .map(DateTimeCoreBenchmark::jdkCase)
                .toList();

        bench("java-datetime-core-format", iterations, warmupIterations, formatCases, DateTimeCoreCase::format);
        bench("jdk-datetime-format", iterations, warmupIterations, referenceCases, JdkCase::format);
    }

    private static <T> void bench(
            String label,
            int iterations,
            int warmupIterations,
            List<T> cases,
            Formatter<T> formatter) throws Exception {
        for (int index = 0; index < warmupIterations; index++) {
            formatter.format(cases.get(index % cases.size()));
        }

        long bytes = 0;
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            bytes += BenchmarkSupport.utf8Length(formatter.format(cases.get(index % cases.size())));
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
                "%s iterations=%d warmup=%d cases=%d seconds=%.6f ns_per_op=%.1f ops_per_second=%.0f bytes=%d%n",
                label,
                iterations,
                warmupIterations,
                cases.size(),
                seconds,
                seconds * 1_000_000_000.0 / iterations,
                iterations / seconds,
                bytes);
    }

    private static DateTimeCoreCase dateTimeCoreCase(Object rawCase) {
        Map<String, Object> item = object(rawCase);
        return new DateTimeCoreCase(
                string(item.get("kind")),
                item.get("value"),
                options(string(item.get("locale")), objectOrEmpty(item.get("options"))));
    }

    private static JdkCase jdkCase(Object rawCase) {
        Map<String, Object> item = object(rawCase);
        String kind = string(item.get("kind"));
        Map<String, Object> rawOptions = objectOrEmpty(item.get("options"));
        DateTimeFormatter formatter = switch (kind) {
            case "date" -> DateTimeFormatter.ofLocalizedDate(formatStyle(dateStyle(rawOptions)));
            case "time" -> DateTimeFormatter.ofLocalizedTime(formatStyle(timeStyle(rawOptions)));
            case "datetime" -> DateTimeFormatter.ofLocalizedDateTime(
                    formatStyle(dateStyle(rawOptions)),
                    formatStyle(timeStyle(rawOptions)));
            default -> throw new IllegalArgumentException("Unsupported date/time core fixture kind: " + kind);
        };
        Locale locale = Locale.forLanguageTag(string(item.get("locale")));
        return new JdkCase(
                Instant.parse(string(item.get("value"))),
                formatter
                        .withLocale(locale)
                        .withDecimalStyle(DecimalStyle.of(locale))
                        .withZone(referenceZone(rawOptions)));
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

    private static String stringOrDefault(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    @FunctionalInterface
    private interface Formatter<T> {
        String format(T item) throws Exception;
    }

    private record DateTimeCoreCase(String kind, Object value, Mf2DateTimeCore.Options options) {
        String format() throws Mf2Exception {
            return switch (kind) {
                case "date" -> Mf2DateTimeCore.formatDate(value, options);
                case "time" -> Mf2DateTimeCore.formatTime(value, options);
                case "datetime" -> Mf2DateTimeCore.formatDateTime(value, options);
                default -> throw new IllegalArgumentException("Unsupported date/time core fixture kind: " + kind);
            };
        }
    }

    private record JdkCase(Instant instant, DateTimeFormatter formatter) {
        String format() {
            return formatter.format(instant);
        }
    }
}
