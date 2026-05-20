package com.box.l10n.mojito.mf2;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InlineTranslateDemo {
    private InlineTranslateDemo() {}

    public static void main(String[] args) throws Exception {
        Mf2FunctionRegistry functions = DemoFunctions.registry();
        Map<String, Object> demo =
                object(JsonParser.parse(Path.of("../examples/inline-source-demo.json")), "$");
        List<Object> cases = array(requiredField(demo, "cases", "$"), "$.cases");
        for (int index = 0; index < cases.size(); index++) {
            String path = "$.cases[" + index + "]";
            DemoCase demoCase = DemoCase.from(object(cases.get(index), path), path);
            String actual = translate(
                    demoCase.source(), demoCase.locale(), demoCase.arguments(), functions);
            if (!actual.equals(demoCase.expected())) {
                throw new AssertionError(
                        demoCase.label() + "[" + demoCase.locale() + "] expected \""
                                + demoCase.expected() + "\", got \"" + actual + "\"");
            }
            System.out.println(
                    demoCase.label() + "[" + demoCase.locale() + "] -> \"" + actual + "\"");
        }
    }

    private static String translate(
            String source,
            String locale,
            Map<String, ?> arguments,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        ParseResult result = Mf2Parser.parseToModel(source);
        if (result.hasDiagnostics()) {
            throw new Mf2Exception("parse-error", result.diagnostics().toString());
        }
        return result.model().format(arguments, locale, functions);
    }

    private record DemoCase(
            String label,
            String source,
            String locale,
            Map<String, Object> arguments,
            String expected) {
        static DemoCase from(Map<String, Object> raw, String path) {
            return new DemoCase(
                    string(requiredField(raw, "label", path), path + ".label"),
                    string(requiredField(raw, "source", path), path + ".source"),
                    string(requiredField(raw, "locale", path), path + ".locale"),
                    object(requiredField(raw, "arguments", path), path + ".arguments"),
                    string(requiredField(raw, "expected", path), path + ".expected"));
        }
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
}
