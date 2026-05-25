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
        int checkedPartsCases = 0;
        int checkedFallbackCases = 0;
        int checkedFallbackPartsCases = 0;
        int checkedModels = 0;
        for (Path fixturePath : jsonFiles(fixtureDir)) {
            Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
            Mf2Message expectedModel = Mf2ModelDecoder.fromJson(fixture.get("expectedModel"));
            Mf2ParseResult parseResult = Mf2Parser.parseToModel(string(fixture.get("source")));
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
                Mf2FormatResult actual = parseResult.model().format(
                        objectOrEmpty(formatCase.get("arguments")),
                        Mf2FormatOptions.builder()
                                .locale(stringOrDefault(formatCase.get("locale"), "en"))
                                .bidiIsolation(Mf2BidiIsolation.fromName(
                                        stringOrDefault(formatCase.get("bidiIsolation"), "none")))
                                .functions(ConformanceFunctionRegistry.registry())
                                .build());
                String expected = string(formatCase.get("expected"));
                if (!actual.value().equals(expected) || !actual.errors().isEmpty()) {
                    System.err.printf(
                            "%s: expected %s, got %s errors=%s%n",
                            fixturePath.getFileName(), expected, actual.value(), actual.errors());
                    return 1;
                }
                checkedCases++;
            }
            for (Object rawCase : arrayOrEmpty(fixture.get("partsCases"))) {
                Map<String, Object> partsCase = object(rawCase);
                Mf2PartsResult result = parseResult.model().formatToParts(
                        objectOrEmpty(partsCase.get("arguments")),
                        Mf2FormatOptions.builder()
                                .locale(stringOrDefault(partsCase.get("locale"), "en"))
                                .functions(ConformanceFunctionRegistry.registry())
                                .build());
                List<Map<String, Object>> actual = result.parts().stream()
                        .map(FormattedPartJson::toMap)
                        .toList();
                List<Map<String, Object>> expected = arrayOrEmpty(partsCase.get("expected")).stream()
                        .map(Conformance::object)
                        .toList();
                if (!actual.equals(expected)) {
                    System.err.printf(
                            "%s: expected parts %s, got %s%n", fixturePath.getFileName(), expected, actual);
                    return 1;
                }
                if (!result.errors().isEmpty()) {
                    System.err.printf(
                            "%s: expected no parts errors, got %s%n",
                            fixturePath.getFileName(), result.errors());
                    return 1;
                }
                checkedPartsCases++;
            }
            for (Object rawCase : arrayOrEmpty(fixture.get("fallbackCases"))) {
                Map<String, Object> fallbackCase = object(rawCase);
                Mf2FormatResult actual = parseResult.model().format(
                        objectOrEmpty(fallbackCase.get("arguments")),
                        Mf2FormatOptions.builder()
                                .locale(stringOrDefault(fallbackCase.get("locale"), "en"))
                                .functions(ConformanceFunctionRegistry.registry())
                                .build());
                String expected = string(fallbackCase.get("expected"));
                if (!actual.value().equals(expected)) {
                    System.err.printf(
                            "%s: expected fallback %s, got %s%n",
                            fixturePath.getFileName(), expected, actual.value());
                    return 1;
                }
                assertErrorCodes(fixturePath, "fallback errors", actual.errors(), fallbackCase);
                checkedFallbackCases++;
            }
            for (Object rawCase : arrayOrEmpty(fixture.get("fallbackPartsCases"))) {
                Map<String, Object> partsCase = object(rawCase);
                Mf2PartsResult result = Mf2Formatter.formatMessageToParts(
                        parseResult.model(),
                        objectOrEmpty(partsCase.get("arguments")),
                        Mf2FormatOptions.builder()
                                .locale(stringOrDefault(partsCase.get("locale"), "en"))
                                .functions(ConformanceFunctionRegistry.registry())
                                .build());
                List<Map<String, Object>> actual = result.parts().stream()
                        .map(FormattedPartJson::toMap)
                        .toList();
                List<Map<String, Object>> expected = arrayOrEmpty(partsCase.get("expected")).stream()
                        .map(Conformance::object)
                        .toList();
                if (!actual.equals(expected)) {
                    System.err.printf(
                            "%s: expected fallback parts %s, got %s%n",
                            fixturePath.getFileName(), expected, actual);
                    return 1;
                }
                assertErrorCodes(fixturePath, "fallback parts errors", result.errors(), partsCase);
                checkedFallbackPartsCases++;
            }
        }

        int checkedInvalidSources = checkInvalidSourceFixtures(fixtureDir.getParent());
        int checkedErrorCases = checkFormatErrorFixtures(fixtureDir.getParent());
        int checkedLocaleKeyCases = checkLocaleKeyFixtures(fixtureDir.getParent());
        System.out.printf(
                "Java MF2 conformance runner passed %d source models, %d format cases, %d parts cases, "
                        + "%d fallback cases, %d fallback parts cases, "
                        + "%d invalid source cases, %d format error cases, and %d locale key cases.%n",
                checkedModels,
                checkedCases,
                checkedPartsCases,
                checkedFallbackCases,
                checkedFallbackPartsCases,
                checkedInvalidSources,
                checkedErrorCases,
                checkedLocaleKeyCases);
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
            Mf2ParseResult parseResult = Mf2Parser.parseToModel(string(fixture.get("source")));
            List<String> actualCodes = parseResult.diagnostics().stream()
                    .map(Mf2ParseDiagnostic::code)
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
            Mf2Message message = Mf2ModelDecoder.fromJson(fixture.get("model"));
            String expectedCode = string(object(fixture.get("expectedError")).get("code"));
            try {
                Mf2FormatResult actual = message.format(
                        objectOrEmpty(fixture.get("arguments")),
                        Mf2FormatOptions.builder()
                                .locale(stringOrDefault(fixture.get("locale"), "en"))
                                .build());
                if (actual.errors().stream().noneMatch(error -> error.code().equals(expectedCode))) {
                    throw new ConformanceFailure(String.format(
                            "%s: expected error %s, got %s",
                            fixturePath.getFileName(), expectedCode, actual.errors()));
                }
            } catch (Mf2Exception error) {
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

    private static int checkLocaleKeyFixtures(Path fixtureRoot) throws Exception {
        Path fixturePath = fixtureRoot.resolve("locale-key").resolve("cases.json");
        if (!Files.isRegularFile(fixturePath)) {
            return 0;
        }

        int checkedCases = 0;
        Map<String, Object> fixture = object(JsonParser.parse(fixturePath));
        for (Object rawCase : arrayOrEmpty(fixture.get("canonical"))) {
            Map<String, Object> item = object(rawCase);
            String actual = LocaleKey.canonicalKey(string(item.get("source")));
            String expected = string(item.get("expected"));
            if (!actual.equals(expected)) {
                throw new ConformanceFailure(String.format(
                        "%s: expected canonical %s, got %s", fixturePath.getFileName(), expected, actual));
            }
            checkedCases++;
        }
        for (Object rawCase : arrayOrEmpty(fixture.get("lookupChains"))) {
            Map<String, Object> item = object(rawCase);
            List<String> actual = LocaleKey.lookupChain(string(item.get("source")));
            List<String> expected = arrayOrEmpty(item.get("expected")).stream()
                    .map(Conformance::string)
                    .toList();
            if (!actual.equals(expected)) {
                throw new ConformanceFailure(String.format(
                        "%s: expected lookup chain %s, got %s", fixturePath.getFileName(), expected, actual));
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

    private static void assertErrorCodes(
            Path fixturePath, String label, List<Mf2Exception> actualErrors, Map<String, Object> item) {
        List<String> actualCodes = actualErrors.stream().map(Mf2Exception::code).toList();
        List<String> expectedCodes = arrayOrEmpty(item.get("expectedErrors")).stream()
                .map(Conformance::object)
                .map(error -> string(error.get("code")))
                .toList();
        if (!actualCodes.equals(expectedCodes)) {
            throw new ConformanceFailure(String.format(
                    "%s: expected %s %s, got %s",
                    fixturePath.getFileName(), label, expectedCodes, actualCodes));
        }
    }

    private static final class ConformanceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConformanceFailure(String message) {
            super(message);
        }
    }
}
