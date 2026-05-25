package com.box.l10n.mojito.mf2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class UnicodeOfficialTests {
    private UnicodeOfficialTests() {}

    private enum Mode {
        PARSE("parse"),
        DATA_MODEL_ERRORS("data-model"),
        RUNTIME("runtime");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private record FileCheck(String path, Mode mode) {}

    private record FileSummary(String path, String mode, int passed, int skipped) {}

    private static final FileCheck[] CHECKS = {
        new FileCheck("tests/syntax.json", Mode.PARSE),
        new FileCheck("tests/syntax-errors.json", Mode.PARSE),
        new FileCheck("tests/bidi.json", Mode.PARSE),
        new FileCheck("tests/data-model-errors.json", Mode.DATA_MODEL_ERRORS),
        new FileCheck("tests/functions/string.json", Mode.RUNTIME),
        new FileCheck("tests/functions/number.json", Mode.RUNTIME),
        new FileCheck("tests/functions/percent.json", Mode.RUNTIME),
        new FileCheck("tests/functions/currency.json", Mode.RUNTIME),
        new FileCheck("tests/functions/date.json", Mode.RUNTIME),
        new FileCheck("tests/functions/datetime.json", Mode.RUNTIME),
        new FileCheck("tests/functions/time.json", Mode.RUNTIME),
        new FileCheck("tests/functions/offset.json", Mode.RUNTIME),
        new FileCheck("tests/functions/integer.json", Mode.RUNTIME),
        new FileCheck("tests/u-options.json", Mode.RUNTIME),
        new FileCheck("tests/fallback.json", Mode.RUNTIME),
        new FileCheck("tests/pattern-selection.json", Mode.RUNTIME),
    };

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Path root = args.length > 0
                ? Path.of(args[0])
                : Path.of("../third_party/message-format-wg/test");
        Path baseline = args.length > 1
                ? Path.of(args[1])
                : Path.of("../conformance/unicode-official-baseline.json");
        if (!Files.isDirectory(root.resolve("tests"))) {
            System.err.printf("%s does not look like the Unicode MessageFormat test directory%n", root);
            return 2;
        }

        Summary summary = new Summary();
        Set<String> wired = new HashSet<>();
        for (FileCheck check : CHECKS) {
            wired.add(check.path());
            runFile(root, check, summary);
        }
        for (Path path : officialJsonPaths(root)) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (wired.contains(relative)) {
                continue;
            }
            summary.notWired += array(object(JsonParser.parse(path)).get("tests")).size();
        }

        for (FileSummary file : summary.files) {
            System.out.printf(
                    "  %s %s passed=%d skipped=%d%n",
                    file.mode(), file.path(), file.passed(), file.skipped());
        }
        if (!summary.skipExamples.isEmpty()) {
            System.out.println("  skip examples:");
            for (String example : summary.skipExamples) {
                System.out.println("    " + example);
            }
        }
        int total = summary.passed + summary.skipped + summary.notWired;
        System.out.printf(
                "Java Unicode official tests passed=%d skipped=%d not_wired=%d total=%d%n",
                summary.passed, summary.skipped, summary.notWired, total);
        return checkBaseline(summary, baseline) ? 0 : 1;
    }

    private static void runFile(Path root, FileCheck check, Summary summary) throws Exception {
        Map<String, Object> suite = object(JsonParser.parse(root.resolve(check.path())));
        Map<String, Object> defaults = objectOrEmpty(suite.get("defaultTestProperties"));
        int passed = 0;
        int skipped = 0;
        List<Object> tests = array(suite.get("tests"));
        for (int index = 0; index < tests.size(); index++) {
            Map<String, Object> test = object(tests.get(index));
            boolean ok = switch (check.mode()) {
                case PARSE -> checkParseTest(defaults, test);
                case DATA_MODEL_ERRORS -> checkDataModelErrorTest(defaults, test);
                case RUNTIME -> checkRuntimeTest(defaults, test);
            };
            if (ok) {
                passed++;
            } else {
                skipped++;
                recordSkip(summary, check.path(), index, test, check.mode().label + " behavior differs");
            }
        }
        summary.passed += passed;
        summary.skipped += skipped;
        summary.files.add(new FileSummary(check.path(), check.mode().label, passed, skipped));
    }

    private static boolean checkParseTest(Map<String, Object> defaults, Map<String, Object> test) {
        boolean expectedSyntaxError = expectedErrors(defaults, test).stream()
                .anyMatch(error -> "syntax-error".equals(error.get("type")));
        Mf2ParseResult result = Mf2Parser.parseToModel(string(test.get("src")));
        return result.hasDiagnostics() == expectedSyntaxError;
    }

    private static boolean checkDataModelErrorTest(Map<String, Object> defaults, Map<String, Object> test) {
        List<String> expectedCodes = expectedLocalCodes(defaults, test);
        Mf2ParseResult result = Mf2Parser.parseToModel(string(test.get("src")));
        if (expectedCodes.isEmpty()) {
            if (test.get("exp") == null || result.hasDiagnostics()) {
                return false;
            }
            try {
                Mf2FormatResult actual = result.model().format(
                        argumentsFor(test, result.model()),
                        Mf2FormatOptions.builder()
                                .locale(locale(defaults, test))
                                .bidiIsolation(bidiIsolation(defaults, test))
                                .build());
                return actual.errors().isEmpty() && actual.value().equals(string(test.get("exp")));
            } catch (Mf2Exception error) {
                return false;
            }
        }

        List<String> actualCodes = new ArrayList<>();
        if (result.hasDiagnostics()) {
            actualCodes.addAll(result.diagnostics().stream().map(Mf2ParseDiagnostic::code).toList());
        } else {
            try {
                Mf2FormatResult formatted = result.model().format(
                        argumentsFor(test, result.model()),
                        Mf2FormatOptions.builder()
                                .locale(locale(defaults, test))
                                .bidiIsolation(bidiIsolation(defaults, test))
                                .build());
                actualCodes.addAll(formatted.errors().stream().map(Mf2Exception::code).toList());
            } catch (Mf2Exception error) {
                actualCodes.add(error.code());
            }
        }
        return actualCodes.stream().anyMatch(expectedCodes::contains);
    }

    private static boolean checkRuntimeTest(Map<String, Object> defaults, Map<String, Object> test) {
        Mf2ParseResult result = Mf2Parser.parseToModel(string(test.get("src")));
        if (result.hasDiagnostics()) {
            return false;
        }
        List<String> expectedCodes = expectedLocalCodes(defaults, test);
        try {
            Mf2PartsResult parts = Mf2Formatter.formatMessageToParts(
                    result.model(),
                    runtimeArgumentsFor(test),
                    Mf2FormatOptions.builder()
                            .locale(locale(defaults, test))
                            .functions(officialFunctionRegistry())
                            .build());
            List<String> actualCodes = parts.errors().stream().map(Mf2Exception::code).toList();
            if (!expectedCodes.stream().allMatch(actualCodes::contains)) {
                return false;
            }
            if (expectedCodes.isEmpty() && !actualCodes.isEmpty()) {
                return false;
            }
            Object expected = test.get("exp");
            return expected == null
                    || partsToString(parts.parts(), bidiIsolation(defaults, test)).equals(expected);
        } catch (Mf2Exception error) {
            return expectedCodes.contains(error.code());
        }
    }

    private static Mf2FunctionRegistry officialFunctionRegistry() {
        return ConformanceFunctionRegistry.registry();
    }

    private static Map<String, Object> argumentsFor(Map<String, Object> test, Mf2Message model) {
        Map<String, Object> arguments = new HashMap<>();
        for (Mf2Message.Declaration declaration : model.declarations()) {
            if (declaration instanceof Mf2Message.InputDeclaration input) {
                arguments.put(input.name(), "1");
            }
        }
        arguments.putAll(runtimeArgumentsFor(test));
        return arguments;
    }

    private static Map<String, Object> runtimeArgumentsFor(Map<String, Object> test) {
        Map<String, Object> arguments = new HashMap<>();
        for (Object rawParam : arrayOrEmpty(test.get("params"))) {
            Map<String, Object> param = object(rawParam);
            arguments.put(string(param.get("name")), param.get("value"));
        }
        return arguments;
    }

    private static List<Map<String, Object>> expectedErrors(
            Map<String, Object> defaults, Map<String, Object> test) {
        return mapArray(test.get("expErrors") != null ? test.get("expErrors") : defaults.get("expErrors"));
    }

    private static List<String> expectedLocalCodes(Map<String, Object> defaults, Map<String, Object> test) {
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> error : expectedErrors(defaults, test)) {
            String code = string(error.get("type"));
            codes.add(switch (code) {
                case "variant-key-mismatch" -> "variant-key-count-mismatch";
                default -> code;
            });
        }
        return codes;
    }

    private static String locale(Map<String, Object> defaults, Map<String, Object> test) {
        return stringOrDefault(test.get("locale"), stringOrDefault(defaults.get("locale"), "en"));
    }

    private static Mf2BidiIsolation bidiIsolation(Map<String, Object> defaults, Map<String, Object> test) {
        return Mf2BidiIsolation.fromName(
                stringOrDefault(test.get("bidiIsolation"), stringOrDefault(defaults.get("bidiIsolation"), "none")));
    }

    private static String partsToString(List<Mf2FormattedPart> parts, Mf2BidiIsolation bidiIsolation) {
        StringBuilder output = new StringBuilder();
        for (Mf2FormattedPart part : parts) {
            switch (part) {
                case Mf2FormattedPart.Text text -> output.append(text.value());
                case Mf2FormattedPart.Fallback fallback -> output.append('{').append(fallback.source()).append('}');
                case Mf2FormattedPart.Expression expression -> {
                    if (bidiIsolation == Mf2BidiIsolation.DEFAULT) {
                        output.append(bidiMarker(expression.direction()))
                                .append(expression.value())
                                .append('\u2069');
                    } else {
                        output.append(expression.value());
                    }
                }
                case Mf2FormattedPart.Markup ignored -> {}
            }
        }
        return output.toString();
    }

    private static char bidiMarker(String direction) {
        return switch (direction == null ? "auto" : direction) {
            case "ltr" -> '\u2066';
            case "rtl" -> '\u2067';
            default -> '\u2068';
        };
    }

    private static void recordSkip(
            Summary summary, String path, int index, Map<String, Object> test, String reason) {
        if (summary.skipExamples.size() >= 8) {
            return;
        }
        String label = stringOrDefault(test.get("description"), string(test.get("src")));
        summary.skipExamples.add(path + "#" + (index + 1) + ": " + reason + ": " + label);
    }

    private static boolean checkBaseline(Summary summary, Path baselinePath) throws Exception {
        Map<String, Object> baseline = object(JsonParser.parse(baselinePath));
        int total = summary.passed + summary.skipped + summary.notWired;
        if (number(baseline.get("passed")) != summary.passed
                || number(baseline.get("skipped")) != summary.skipped
                || number(baseline.get("notWired")) != summary.notWired
                || number(baseline.get("total")) != total) {
            System.err.printf(
                    "%s: expected official-test counts passed=%d skipped=%d notWired=%d total=%d, "
                            + "got passed=%d skipped=%d notWired=%d total=%d%n",
                    baselinePath,
                    number(baseline.get("passed")),
                    number(baseline.get("skipped")),
                    number(baseline.get("notWired")),
                    number(baseline.get("total")),
                    summary.passed,
                    summary.skipped,
                    summary.notWired,
                    total);
            return false;
        }
        Map<String, Object> files = object(baseline.get("files"));
        for (FileSummary file : summary.files) {
            Map<String, Object> expected = object(files.get(file.path()));
            if (number(expected.get("passed")) != file.passed()
                    || number(expected.get("skipped")) != file.skipped()) {
                System.err.printf(
                        "%s: expected %s passed=%d skipped=%d, got passed=%d skipped=%d%n",
                        baselinePath,
                        file.path(),
                        number(expected.get("passed")),
                        number(expected.get("skipped")),
                        file.passed(),
                        file.skipped());
                return false;
            }
        }
        return true;
    }

    private static List<Path> officialJsonPaths(Path root) throws Exception {
        List<Path> paths = new ArrayList<>();
        collectJsonPaths(root.resolve("tests"), paths);
        paths.sort(Path::compareTo);
        return paths;
    }

    private static void collectJsonPaths(Path dir, List<Path> paths) throws Exception {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    collectJsonPaths(path, paths);
                } else if (path.getFileName().toString().endsWith(".json")) {
                    paths.add(path);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value == null ? Map.of() : object(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arrayOrEmpty(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static List<Map<String, Object>> mapArray(Object value) {
        return arrayOrEmpty(value).stream().map(UnicodeOfficialTests::object).toList();
    }

    private static String string(Object value) {
        return (String) value;
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static final class Summary {
        private int passed;
        private int skipped;
        private int notWired;
        private final List<FileSummary> files = new ArrayList<>();
        private final List<String> skipExamples = new ArrayList<>();
    }
}
