package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RelativeTimeCoreBenchmark {
    private RelativeTimeCoreBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path fixturePath = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/functions/relative-time-duration-v0.json");
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        int warmupIterations = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        Mf2RelativeTimeCore formatter = Mf2RelativeTimeCore.create(relativeTimeData());
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        List<RelativeTimeCoreCase> cases = arrayOrEmpty(fixture.get("cases")).stream()
                .map(RelativeTimeCoreBenchmark::relativeTimeCoreCase)
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No relative-time-core cases found.");
        }

        for (int index = 0; index < warmupIterations; index++) {
            RelativeTimeCoreCase item = cases.get(index % cases.size());
            formatter.format(item.value(), item.options());
        }

        long bytes = 0;
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            RelativeTimeCoreCase item = cases.get(index % cases.size());
            bytes += BenchmarkSupport.utf8Length(formatter.format(item.value(), item.options()));
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
                "java-relative-time-core-format iterations=%d warmup=%d cases=%d seconds=%.6f "
                        + "ns_per_op=%.1f ops_per_second=%.0f bytes=%d%n",
                iterations,
                warmupIterations,
                cases.size(),
                seconds,
                seconds * 1_000_000_000.0 / iterations,
                iterations / seconds,
                bytes);
    }

    private static RelativeTimeCoreCase relativeTimeCoreCase(Object rawCase) {
        Map<String, Object> item = object(rawCase);
        return new RelativeTimeCoreCase(
                object(item.get("arguments")).get("delta"),
                options(string(item.get("locale")), string(item.get("source"))));
    }

    private static Mf2RelativeTimeCore.Options options(String locale, String source) {
        Mf2RelativeTimeCore.Options.Builder builder = Mf2RelativeTimeCore.Options.builder()
                .locale(locale);
        String style = sourceOption(source, "style");
        if (style != null) {
            builder.style(option(() -> Mf2RelativeTimeCore.Style.fromName(style)));
        }
        String numeric = sourceOption(source, "numeric");
        if (numeric != null) {
            builder.numeric(option(() -> Mf2RelativeTimeCore.Numeric.fromName(numeric)));
        }
        String policy = sourceOption(source, "policy");
        if (policy != null) {
            builder.policy(option(() -> Mf2RelativeTimeCore.Policy.fromName(policy)));
        }
        String unit = sourceOption(source, "unit");
        if (unit != null) {
            builder.unit(option(() -> Mf2RelativeTimeCore.Unit.fromName(unit)));
        }
        return builder.build();
    }

    private static String sourceOption(String source, String name) {
        String marker = name + "=";
        int start = source.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = start;
        while (end < source.length()) {
            char ch = source.charAt(end);
            if (Character.isWhitespace(ch) || ch == '}') {
                break;
            }
            end++;
        }
        return source.substring(start, end);
    }

    private static <T> T option(OptionParser<T> parser) {
        try {
            return parser.parse();
        } catch (Mf2Exception error) {
            throw new IllegalArgumentException(error);
        }
    }

    private static Mf2RelativeTimeCore.Data relativeTimeData() throws Exception {
        return Mf2RelativeTimeCore.dataFromJson(object(
                JsonParser.parse(Path.of("../cldr/generated/relative-time/all/relative_time.json"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arrayOrEmpty(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static String string(Object value) {
        return (String) value;
    }

    @FunctionalInterface
    private interface OptionParser<T> {
        T parse() throws Mf2Exception;
    }

    private record RelativeTimeCoreCase(Object value, Mf2RelativeTimeCore.Options options) {}
}
