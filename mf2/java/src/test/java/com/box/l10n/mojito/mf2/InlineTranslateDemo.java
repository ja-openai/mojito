package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InlineTranslateDemo {
    private InlineTranslateDemo() {}

    public static void main(String[] args) throws Exception {
        Mf2FunctionRegistry functions = SampleCatalogFunctions.registry();
        Map<String, Object> demo =
                object(JsonParser.parse(Path.of("../examples/inline-source-demo.json")), "$");

        runFormatCases(demo, functions);
        runPartsCases(demo, functions);
        runFallbackCases(demo, functions);
    }

    private static void runFormatCases(Map<String, Object> demo, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        List<Object> cases = array(requiredField(demo, "cases", "$"), "$.cases");
        for (int index = 0; index < cases.size(); index++) {
            String path = "$.cases[" + index + "]";
            DemoCase demoCase = DemoCase.from(object(cases.get(index), path), path);
            String actual = translate(
                    demoCase.source(),
                    demoCase.locale(),
                    demoCase.arguments(),
                    functions,
                    demoCase.bidiIsolation());
            if (!actual.equals(demoCase.expected())) {
                throw new AssertionError(
                        demoCase.label() + "[" + demoCase.locale() + "] expected \""
                                + demoCase.expected() + "\", got \"" + actual + "\"");
            }
            System.out.println(
                    demoCase.label() + "[" + demoCase.locale() + "] -> \"" + actual + "\"");
            if (demoCase.bidiIsolation() != Mf2BidiIsolation.NONE) {
                System.out.println(demoCase.label()
                        + "["
                        + demoCase.locale()
                        + "].escaped -> \""
                        + escapedNonAscii(actual)
                        + "\"");
            }
        }
    }

    private static void runPartsCases(Map<String, Object> demo, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        List<Object> cases = array(optionalField(demo, "partsCases", List.of()), "$.partsCases");
        for (int index = 0; index < cases.size(); index++) {
            String path = "$.partsCases[" + index + "]";
            PartsCase partsCase = PartsCase.from(object(cases.get(index), path), path);
            Mf2Message model = parse(partsCase.source());
            Mf2PartsResult result = model.formatToParts(
                    partsCase.arguments(),
                    Mf2FormatOptions.builder()
                            .locale(partsCase.locale())
                            .functions(functions)
                            .build());
            if (result.hasErrors()) {
                throw new AssertionError(partsCase.label()
                        + "["
                        + partsCase.locale()
                        + "] unexpected parts errors "
                        + result.errors());
            }
            List<Map<String, Object>> actual = FormattedPartJson.toMaps(result.parts());
            if (!actual.equals(partsCase.expected())) {
                throw new AssertionError(partsCase.label()
                        + "["
                        + partsCase.locale()
                        + "] expected parts "
                        + partsCase.expected()
                        + ", got "
                        + actual);
            }
            System.out.println(partsCase.label()
                    + "["
                    + partsCase.locale()
                    + "].parts -> "
                    + actual);
        }
    }

    private static void runFallbackCases(Map<String, Object> demo, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        List<Object> cases = array(optionalField(demo, "fallbackCases", List.of()), "$.fallbackCases");
        for (int index = 0; index < cases.size(); index++) {
            String path = "$.fallbackCases[" + index + "]";
            FallbackCase fallbackCase = FallbackCase.from(object(cases.get(index), path), path);
            Mf2Message model = parse(fallbackCase.source());

            Mf2FormatResult actual = model.format(
                    fallbackCase.arguments(),
                    Mf2FormatOptions.builder().locale(fallbackCase.locale()).build());
            if (!actual.value().equals(fallbackCase.expected())) {
                throw new AssertionError(fallbackCase.label()
                        + "["
                        + fallbackCase.locale()
                        + "] expected \""
                        + fallbackCase.expected()
                        + "\", got \""
                        + actual.value()
                        + "\"");
            }
            List<String> actualErrors = actual.errors().stream().map(Mf2Exception::code).toList();
            if (!actualErrors.equals(fallbackCase.expectedErrors())) {
                throw new AssertionError(fallbackCase.label()
                        + "["
                        + fallbackCase.locale()
                        + "] expected errors "
                        + fallbackCase.expectedErrors()
                        + ", got "
                        + actualErrors);
            }

            Mf2PartsResult actualParts = Mf2Formatter.formatMessageToParts(
                    model,
                    fallbackCase.arguments(),
                    Mf2FormatOptions.builder()
                            .locale(fallbackCase.locale())
                            .functions(functions)
                            .build());
            List<Map<String, Object>> actualPartMaps = FormattedPartJson.toMaps(actualParts.parts());
            if (!actualPartMaps.equals(fallbackCase.expectedParts())) {
                throw new AssertionError(fallbackCase.label()
                        + "["
                        + fallbackCase.locale()
                        + "] expected fallback parts "
                        + fallbackCase.expectedParts()
                        + ", got "
                        + actualPartMaps);
            }

            System.out.println(fallbackCase.label()
                    + "["
                    + fallbackCase.locale()
                    + "].fallback -> \""
                    + actual.value()
                    + "\" errors="
                    + actualErrors);
            System.out.println(fallbackCase.label()
                    + "["
                    + fallbackCase.locale()
                    + "].fallback.parts -> "
                    + actualPartMaps);
        }
    }

    private static Mf2Message parse(String source) throws Mf2Exception {
        Mf2ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model();
    }

    private static String translate(
            String source,
            String locale,
            Map<String, ?> arguments,
            Mf2FunctionRegistry functions,
            Mf2BidiIsolation bidiIsolation)
            throws Mf2Exception {
        Mf2FormatResult result = parse(source).format(
                arguments,
                Mf2FormatOptions.builder()
                        .locale(locale)
                        .functions(functions)
                        .bidiIsolation(bidiIsolation)
                        .build());
        if (result.hasErrors()) {
            throw new Mf2Exception("format-error", result.errors().toString());
        }
        return result.value();
    }

    private record DemoCase(
            String label,
            String source,
            String locale,
            Mf2BidiIsolation bidiIsolation,
            Map<String, Object> arguments,
            String expected) {
        static DemoCase from(Map<String, Object> raw, String path) {
            return new DemoCase(
                    string(requiredField(raw, "label", path), path + ".label"),
                    string(requiredField(raw, "source", path), path + ".source"),
                    string(requiredField(raw, "locale", path), path + ".locale"),
                    Mf2BidiIsolation.fromName(
                            string(optionalField(raw, "bidiIsolation", "none"), path + ".bidiIsolation")),
                    object(requiredField(raw, "arguments", path), path + ".arguments"),
                    string(requiredField(raw, "expected", path), path + ".expected"));
        }
    }

    private record PartsCase(
            String label,
            String source,
            String locale,
            Map<String, Object> arguments,
            List<Map<String, Object>> expected) {
        static PartsCase from(Map<String, Object> raw, String path) {
            return new PartsCase(
                    string(requiredField(raw, "label", path), path + ".label"),
                    string(requiredField(raw, "source", path), path + ".source"),
                    string(requiredField(raw, "locale", path), path + ".locale"),
                    object(requiredField(raw, "arguments", path), path + ".arguments"),
                    mapArray(requiredField(raw, "expected", path), path + ".expected"));
        }
    }

    private record FallbackCase(
            String label,
            String source,
            String locale,
            Map<String, Object> arguments,
            String expected,
            List<Map<String, Object>> expectedParts,
            List<String> expectedErrors) {
        static FallbackCase from(Map<String, Object> raw, String path) {
            return new FallbackCase(
                    string(requiredField(raw, "label", path), path + ".label"),
                    string(requiredField(raw, "source", path), path + ".source"),
                    string(requiredField(raw, "locale", path), path + ".locale"),
                    object(requiredField(raw, "arguments", path), path + ".arguments"),
                    string(requiredField(raw, "expected", path), path + ".expected"),
                    mapArray(requiredField(raw, "expectedParts", path), path + ".expectedParts"),
                    errorCodes(requiredField(raw, "expectedErrors", path), path + ".expectedErrors"));
        }
    }

    private static List<Map<String, Object>> mapArray(Object value, String path) {
        return array(value, path).stream()
                .map(item -> object(item, path + "[]"))
                .toList();
    }

    private static List<String> errorCodes(Object value, String path) {
        return array(value, path).stream()
                .map(item -> object(item, path + "[]"))
                .map(error -> string(requiredField(error, "code", path + "[]"), path + "[].code"))
                .toList();
    }

    private static Object optionalField(Map<String, Object> object, String field, Object defaultValue) {
        return object.containsKey(field) ? object.get(field) : defaultValue;
    }

    private static Object requiredField(Map<String, Object> object, String field, String path) {
        if (!object.containsKey(field)) {
            throw new IllegalArgumentException("Missing required field " + path + "." + field + ".");
        }
        return object.get(field);
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw expected(path, "object", value);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected " + path + " object keys to be strings.");
            }
            object.put(key, entry.getValue());
        }
        return object;
    }

    private static List<Object> array(Object value, String path) {
        if (!(value instanceof List<?> raw)) {
            throw expected(path, "array", value);
        }
        return new ArrayList<>(raw);
    }

    private static String string(Object value, String path) {
        if (value instanceof String string) {
            return string;
        }
        throw expected(path, "string", value);
    }

    private static IllegalArgumentException expected(String path, String expected, Object actual) {
        return new IllegalArgumentException(
                "Expected "
                        + path
                        + " to be "
                        + expected
                        + ", got "
                        + (actual == null ? "null" : actual.getClass().getSimpleName())
                        + ".");
    }

    private static String escapedNonAscii(String value) {
        StringBuilder output = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint >= 0x20 && codePoint <= 0x7e) {
                output.appendCodePoint(codePoint);
            } else {
                output.append(String.format("\\u%04X", codePoint));
            }
            offset += Character.charCount(codePoint);
        }
        return output.toString();
    }
}
