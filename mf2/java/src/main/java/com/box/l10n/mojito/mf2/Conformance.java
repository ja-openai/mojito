package com.box.l10n.mojito.mf2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class Conformance {
    private Conformance() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Path fixtureDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../conformance/fixtures/source-to-model");

        int checkedCases = 0;
        int checkedModels = 0;
        for (Path fixturePath : jsonFiles(fixtureDir)) {
            Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
            Mf2Message expectedModel = Mf2Message.fromJson(fixture.get("expectedModel"));
            ParseResult parseResult = Mf2Parser.parseToModel(string(fixture.get("source")));
            if (parseResult.hasDiagnostics()) {
                System.err.printf(
                        "%s: expected parse success, got diagnostics %s%n",
                        fixturePath.getFileName(), parseResult.diagnostics());
                return 1;
            }
            if (!expectedModel.equals(parseResult.model())) {
                System.err.printf(
                        "%s: parsed model did not match expected model%n",
                        fixturePath.getFileName());
                return 1;
            }
            checkedModels++;
            for (Object rawCase : arrayOrEmpty(fixture.get("formatCases"))) {
                Map<String, Object> formatCase = object(rawCase);
                String actual = parseResult.model().format(
                        objectOrEmpty(formatCase.get("arguments")),
                        stringOrDefault(formatCase.get("locale"), "en"));
                String expected = string(formatCase.get("expected"));
                if (!actual.equals(expected)) {
                    System.err.printf(
                            "%s: expected %s, got %s%n", fixturePath.getFileName(), expected, actual);
                    return 1;
                }
                checkedCases++;
            }
        }

        int checkedInvalidSources = checkInvalidSourceFixtures(fixtureDir.getParent());
        int checkedErrorCases = checkFormatErrorFixtures(fixtureDir.getParent());
        System.out.printf(
                "Java MF2 conformance runner passed %d source models, %d format cases, "
                        + "%d invalid source cases, and %d format error cases.%n",
                checkedModels, checkedCases, checkedInvalidSources, checkedErrorCases);
        return 0;
    }

    private static int checkInvalidSourceFixtures(Path fixtureRoot) throws Exception {
        Path fixtureDir = fixtureRoot.resolve("invalid-source");
        if (!Files.isDirectory(fixtureDir)) {
            return 0;
        }

        int checkedCases = 0;
        for (Path fixturePath : jsonFiles(fixtureDir)) {
            Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
            ParseResult parseResult = Mf2Parser.parseToModel(string(fixture.get("source")));
            List<String> actualCodes = parseResult.diagnostics().stream()
                    .map(Diagnostic::code)
                    .toList();
            List<String> expectedCodes = arrayOrEmpty(fixture.get("expectedDiagnostics")).stream()
                    .map(Conformance::object)
                    .map(diagnostic -> string(diagnostic.get("code")))
                    .toList();
            if (!actualCodes.equals(expectedCodes)) {
                throw new ConformanceFailure(String.format(
                        "%s: expected diagnostics %s, got %s%n",
                        fixturePath.getFileName(), expectedCodes, actualCodes));
            }
            checkedCases++;
        }
        return checkedCases;
    }

    private static int checkFormatErrorFixtures(Path fixtureRoot) throws Exception {
        Path fixtureDir = fixtureRoot.resolve("format-errors");
        if (!Files.isDirectory(fixtureDir)) {
            return 0;
        }

        int checkedCases = 0;
        for (Path fixturePath : jsonFiles(fixtureDir)) {
            Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
            Mf2Message message = Mf2Message.fromJson(fixture.get("model"));
            try {
                String actual = message.format(
                        objectOrEmpty(fixture.get("arguments")),
                        stringOrDefault(fixture.get("locale"), "en"));
                throw new ConformanceFailure(String.format(
                        "%s: expected format error, got %s", fixturePath.getFileName(), actual));
            } catch (Mf2Exception error) {
                String expectedCode = string(object(fixture.get("expectedError")).get("code"));
                if (!error.code().equals(expectedCode)) {
                    throw new ConformanceFailure(String.format(
                            "%s: expected error %s, got %s%n",
                            fixturePath.getFileName(), expectedCode, error.code()));
                }
            }
            checkedCases++;
        }
        return checkedCases;
    }

    private static List<Path> jsonFiles(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
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

    private static final class ConformanceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConformanceFailure(String message) {
            super(message);
        }
    }
}
