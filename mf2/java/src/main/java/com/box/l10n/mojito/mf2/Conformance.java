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
                        stringOrDefault(formatCase.get("locale"), "en"),
                        Mf2BidiIsolation.fromName(
                                stringOrDefault(formatCase.get("bidiIsolation"), "none")));
                String expected = string(formatCase.get("expected"));
                if (!actual.equals(expected)) {
                    System.err.printf(
                            "%s: expected %s, got %s%n", fixturePath.getFileName(), expected, actual);
                    return 1;
                }
                checkedCases++;
            }
            for (Object rawCase : arrayOrEmpty(fixture.get("partsCases"))) {
                Map<String, Object> partsCase = object(rawCase);
                List<Map<String, Object>> actual = parseResult.model().formatToParts(
                                objectOrEmpty(partsCase.get("arguments")),
                                stringOrDefault(partsCase.get("locale"), "en"))
                        .stream()
                        .map(Conformance::partToMap)
                        .toList();
                List<Map<String, Object>> expected = arrayOrEmpty(partsCase.get("expected")).stream()
                        .map(Conformance::object)
                        .toList();
                if (!actual.equals(expected)) {
                    System.err.printf(
                            "%s: expected parts %s, got %s%n", fixturePath.getFileName(), expected, actual);
                    return 1;
                }
                checkedPartsCases++;
            }
        }

        int checkedInvalidSources = checkInvalidSourceFixtures(fixtureDir.getParent());
        int checkedErrorCases = checkFormatErrorFixtures(fixtureDir.getParent());
        int checkedLocaleKeyCases = checkLocaleKeyFixtures(fixtureDir.getParent());
        System.out.printf(
                "Java MF2 conformance runner passed %d source models, %d format cases, %d parts cases, "
                        + "%d invalid source cases, %d format error cases, and %d locale key cases.%n",
                checkedModels,
                checkedCases,
                checkedPartsCases,
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

    private static Map<String, Object> partToMap(Mf2Message.FormattedPart part) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        switch (part) {
            case Mf2Message.FormattedText text -> {
                map.put("type", "text");
                map.put("value", text.value());
            }
            case Mf2Message.FormattedExpression expression -> {
                map.put("type", "expression");
                map.put("value", expression.value());
                putAttributes(map, expression.attributes());
            }
            case Mf2Message.FormattedMarkup markup -> {
                map.put("type", "markup");
                map.put("kind", markup.kind());
                map.put("name", markup.name());
                putOptions(map, markup.options());
                putAttributes(map, markup.attributes());
            }
        }
        return map;
    }

    private static void putOptions(
            Map<String, Object> output, Map<String, Mf2Message.ExpressionArgument> options) {
        if (options.isEmpty()) {
            return;
        }
        Map<String, Object> rawOptions = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Mf2Message.ExpressionArgument> entry : options.entrySet()) {
            rawOptions.put(entry.getKey(), expressionArgumentToMap(entry.getValue()));
        }
        output.put("options", rawOptions);
    }

    private static void putAttributes(
            Map<String, Object> output, Map<String, Mf2Message.AttributeValue> attributes) {
        if (attributes.isEmpty()) {
            return;
        }
        Map<String, Object> rawAttributes = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Mf2Message.AttributeValue> entry : attributes.entrySet()) {
            rawAttributes.put(entry.getKey(), attributeToMap(entry.getValue()));
        }
        output.put("attributes", rawAttributes);
    }

    private static Object attributeToMap(Mf2Message.AttributeValue attribute) {
        return switch (attribute) {
            case Mf2Message.PresentAttribute present -> present.value();
            case Mf2Message.LiteralAttribute literal -> expressionArgumentToMap(literal.value());
        };
    }

    private static Map<String, Object> expressionArgumentToMap(Mf2Message.ExpressionArgument argument) {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        switch (argument) {
            case Mf2Message.LiteralArgument literal -> {
                output.put("type", "literal");
                output.put("value", literal.value());
            }
            case Mf2Message.VariableArgument variable -> {
                output.put("type", "variable");
                output.put("name", variable.name());
            }
        }
        return output;
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
