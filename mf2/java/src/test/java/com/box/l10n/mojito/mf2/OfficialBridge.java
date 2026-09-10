package com.box.l10n.mojito.mf2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Test-only JSON-lines adapter for the shared upstream assertion checker. */
public final class OfficialBridge {
    private OfficialBridge() {}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            Map<String, Object> request = (Map<String, Object>) JsonParser.parse(line);
            var parsed = Mf2Parser.parseToModel((String) request.get("source"));
            Object response;
            if (parsed.hasDiagnostics()) {
                response = Map.of("diagnostics", parsed.diagnostics().stream().map(Mf2ParseDiagnostic::code).toList(), "value", "", "errors", List.of(), "parts", List.of());
            } else {
                var base = "platform".equals(request.get("registry")) ? Mf2FunctionRegistry.defaults() : Mf2FunctionRegistry.portable();
                var options = Mf2FormatOptions.builder().locale((String) request.getOrDefault("locale", "en"))
                        .bidiIsolation(Mf2BidiIsolation.fromName((String) request.getOrDefault("bidiIsolation", "none")))
                        .functions(ConformanceFunctionRegistry.withTestFunctions(base)).build();
                var arguments = (Map<String, Object>) request.getOrDefault("arguments", Map.of());
                try {
                    var result = parsed.model().format(arguments, options);
                    var parts = parsed.model().formatToParts(arguments, options);
                    response = Map.of("diagnostics", List.of(), "value", result.value(), "errors", result.errors().stream().map(Mf2Exception::code).toList(),
                            "parts", FormattedPartJson.toMaps(parts.parts()), "partsErrors", parts.errors().stream().map(Mf2Exception::code).toList());
                } catch (Mf2Exception error) {
                    response = Map.of("diagnostics", List.of(), "value", "", "errors", List.of(error.code()), "parts", List.of(), "partsErrors", List.of(error.code()));
                }
            }
            System.out.println(json(response));
        }
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder output = new StringBuilder("\"");
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                switch (ch) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> { if (ch < 0x20) output.append(String.format("\\u%04x", (int) ch)); else output.append(ch); }
                }
            }
            return output.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).collect(Collectors.joining(",", "{", "}"));
        if (value instanceof List<?> list) return list.stream().map(OfficialBridge::json).collect(Collectors.joining(",", "[", "]"));
        return value.toString();
    }
}
