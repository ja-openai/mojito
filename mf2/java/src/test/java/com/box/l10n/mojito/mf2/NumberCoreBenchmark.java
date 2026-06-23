package com.box.l10n.mojito.mf2;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NumberCoreBenchmark {
    private NumberCoreBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/number-core/cases.json");
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        int warmupIterations = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        List<NumberCoreCase> formatCases = arrayOrEmpty(fixture.get("formatCases")).stream()
                .map(NumberCoreBenchmark::numberCoreCase)
                .toList();
        List<JdkCase> referenceCases = arrayOrEmpty(fixture.get("intlReferenceCases")).stream()
                .map(NumberCoreBenchmark::jdkCase)
                .toList();

        bench("java-number-core-format", iterations, warmupIterations, formatCases, NumberCoreCase::format);
        bench("jdk-number-format", iterations, warmupIterations, referenceCases, JdkCase::format);
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

    private static NumberCoreCase numberCoreCase(Object rawCase) {
        Map<String, Object> item = object(rawCase);
        return new NumberCoreCase(
                item.get("value"),
                options(string(item.get("locale")), objectOrEmpty(item.get("options"))));
    }

    private static JdkCase jdkCase(Object rawCase) {
        Map<String, Object> item = object(rawCase);
        return new JdkCase(
                item.get("value"),
                jdkFormatter(string(item.get("locale")), objectOrEmpty(item.get("options"))));
    }

    private static Mf2NumberCore.Options options(String locale, Map<String, Object> rawOptions) {
        Mf2NumberCore.Options.Builder builder = Mf2NumberCore.options().locale(locale);
        if (rawOptions.containsKey("style")) {
            builder.style(style(string(rawOptions.get("style"))));
        }
        if (rawOptions.containsKey("currency")) {
            builder.currency(string(rawOptions.get("currency")));
        }
        if (rawOptions.containsKey("currencyDisplay")) {
            builder.currencyDisplay(currencyDisplay(string(rawOptions.get("currencyDisplay"))));
        }
        if (rawOptions.containsKey("useGrouping")) {
            builder.useGrouping(booleanValue(rawOptions.get("useGrouping")));
        }
        if (rawOptions.containsKey("minimumFractionDigits")) {
            builder.minimumFractionDigits(intValue(rawOptions.get("minimumFractionDigits")));
        }
        if (rawOptions.containsKey("maximumFractionDigits")) {
            builder.maximumFractionDigits(intValue(rawOptions.get("maximumFractionDigits")));
        }
        if (rawOptions.containsKey("signDisplay")) {
            builder.signDisplay(signDisplay(string(rawOptions.get("signDisplay"))));
        }
        return builder.build();
    }

    private static NumberFormat jdkFormatter(String locale, Map<String, Object> rawOptions) {
        Locale tag = Locale.forLanguageTag(locale);
        String style = stringOrDefault(rawOptions.get("style"), "number");
        NumberFormat formatter = switch (style) {
            case "number" -> NumberFormat.getNumberInstance(tag);
            case "percent" -> NumberFormat.getPercentInstance(tag);
            case "currency" -> {
                NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(tag);
                currencyFormatter.setCurrency(Currency.getInstance(string(rawOptions.get("currency"))));
                yield currencyFormatter;
            }
            default -> throw new IllegalArgumentException("Unsupported reference style: " + style);
        };
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter;
    }

    private static Mf2NumberCore.Style style(String value) {
        return switch (value) {
            case "number" -> Mf2NumberCore.Style.NUMBER;
            case "integer" -> Mf2NumberCore.Style.INTEGER;
            case "percent" -> Mf2NumberCore.Style.PERCENT;
            case "currency" -> Mf2NumberCore.Style.CURRENCY;
            default -> throw new IllegalArgumentException("Unknown number core style: " + value);
        };
    }

    private static Mf2NumberCore.CurrencyDisplay currencyDisplay(String value) {
        return switch (value) {
            case "symbol" -> Mf2NumberCore.CurrencyDisplay.SYMBOL;
            case "narrowSymbol" -> Mf2NumberCore.CurrencyDisplay.NARROW_SYMBOL;
            case "code" -> Mf2NumberCore.CurrencyDisplay.CODE;
            default -> throw new IllegalArgumentException("Unknown currency display: " + value);
        };
    }

    private static Mf2NumberCore.SignDisplay signDisplay(String value) {
        return switch (value) {
            case "auto" -> Mf2NumberCore.SignDisplay.AUTO;
            case "always" -> Mf2NumberCore.SignDisplay.ALWAYS;
            case "never" -> Mf2NumberCore.SignDisplay.NEVER;
            default -> throw new IllegalArgumentException("Unknown sign display: " + value);
        };
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

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean booleanValue
                ? booleanValue
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    @FunctionalInterface
    private interface Formatter<T> {
        String format(T item) throws Exception;
    }

    private record NumberCoreCase(Object value, Mf2NumberCore.Options options) {
        String format() throws Mf2Exception {
            return Mf2NumberCore.format(value, options);
        }
    }

    private record JdkCase(Object value, NumberFormat formatter) {
        String format() {
            return formatter.format(value);
        }
    }
}
