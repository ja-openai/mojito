package com.box.l10n.mojito.mf2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class Benchmark {
    private Benchmark() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Path fixtureDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/source-to-model");
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        int warmupIterations = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        List<Case> cases = loadCases(fixtureDir);
        if (cases.isEmpty()) {
            System.err.println("No format cases found.");
            return 2;
        }

        for (int index = 0; index < warmupIterations; index++) {
            cases.get(index % cases.size()).format();
        }

        long bytes = 0;
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            String output = cases.get(index % cases.size()).format();
            bytes += BenchmarkSupport.utf8Length(output);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
                "java format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f bytes=%d%n",
                iterations, warmupIterations, cases.size(), seconds, iterations / seconds, bytes);
        return 0;
    }

    private static List<Case> loadCases(Path fixtureDir) throws Exception {
        List<Case> cases = new ArrayList<>();
        try (var stream = Files.list(fixtureDir)) {
            for (Path fixturePath : stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
                Mf2Message message = Mf2ModelDecoder.fromJson(fixture.get("expectedModel"));
                for (Object rawCase : arrayOrEmpty(fixture.get("formatCases"))) {
                    Map<String, Object> formatCase = object(rawCase);
                    cases.add(new Case(
                            message,
                            objectOrEmpty(formatCase.get("arguments")),
                            stringOrDefault(formatCase.get("locale"), "en")));
                }
            }
        }
        return cases;
    }

    private record Case(Mf2Message message, Map<String, Object> arguments, String locale) {
        String format() throws Mf2Exception {
            Mf2FormatResult result = message.format(
                    arguments, Mf2FormatOptions.builder().locale(locale).build());
            if (result.hasErrors()) {
                throw new Mf2Exception("format-error", result.errors().toString());
            }
            return result.value();
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

    private static String stringOrDefault(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

}
