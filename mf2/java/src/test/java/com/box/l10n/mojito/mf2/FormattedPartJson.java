package com.box.l10n.mojito.mf2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FormattedPartJson {
    private FormattedPartJson() {}

    static List<Map<String, Object>> toMaps(List<Mf2Message.FormattedPart> parts) {
        return parts.stream().map(FormattedPartJson::toMap).toList();
    }

    static Map<String, Object> toMap(Mf2Message.FormattedPart part) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (part) {
            case Mf2Message.FormattedText text -> {
                map.put("type", "text");
                map.put("value", text.value());
            }
            case Mf2Message.FormattedFallback fallback -> {
                map.put("type", "fallback");
                map.put("source", fallback.source());
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
        Map<String, Object> rawOptions = new LinkedHashMap<>();
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
        Map<String, Object> rawAttributes = new LinkedHashMap<>();
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
        Map<String, Object> output = new LinkedHashMap<>();
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
}
