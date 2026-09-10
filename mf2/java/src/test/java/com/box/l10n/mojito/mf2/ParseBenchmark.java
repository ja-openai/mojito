package com.box.l10n.mojito.mf2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ParseBenchmark {
    private ParseBenchmark() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Path fixtureDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/source-to-model");
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        int warmupIterations = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        if (iterations <= 0 || warmupIterations < 0) throw new IllegalArgumentException("Iterations must be positive; warmup must be nonnegative.");
        List<String> sources = loadSources(fixtureDir);
        if (sources.isEmpty()) {
            System.err.println("No source fixtures found.");
            return 2;
        }

        for (int index = 0; index < warmupIterations; index++) {
            Mf2Parser.parseToModel(sources.get(index % sources.size()));
        }

        long bytes = 0;
        long diagnostics = 0;
        long models = 0;
        long started = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            String source = sources.get(index % sources.size());
            Mf2ParseResult result = Mf2Parser.parseToModel(source);
            bytes += BenchmarkSupport.utf8Length(source);
            diagnostics += result.diagnostics().size();
            if (result.model() != null) {
                models++;
            }
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(
                "java parse iterations=%d warmup=%d sources=%d seconds=%.6f ops_per_second=%.0f bytes=%d diagnostics=%d models=%d%n",
                iterations,
                warmupIterations,
                sources.size(),
                seconds,
                iterations / seconds,
                bytes,
                diagnostics,
                models);
        return 0;
    }

    private static List<String> loadSources(Path fixtureDir) throws Exception {
        List<String> sources = new ArrayList<>();
        try (var stream = Files.list(fixtureDir)) {
            for (Path fixturePath : stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
                String source = string(fixture.get("source"));
                List<String> expected = new ArrayList<>();
                for (Object item : (List<?>) fixture.getOrDefault("expectedDiagnostics", List.of())) {
                    expected.add(string(object(item).get("code")));
                }
                if (fixture.get("expectedError") instanceof Map<?, ?>) {
                    expected.add(string(object(fixture.get("expectedError")).get("code")));
                }
                Mf2ParseResult result = Mf2Parser.parseToModel(source);
                List<String> actual = result.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList();
                if (result.hasDiagnostics() != !expected.isEmpty() || !actual.containsAll(expected)) {
                    throw new IllegalStateException(fixturePath + ": expected parser diagnostics " + expected + ", got " + actual);
                }
                sources.add(source);
            }
        }
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static String string(Object value) {
        return (String) value;
    }
}
