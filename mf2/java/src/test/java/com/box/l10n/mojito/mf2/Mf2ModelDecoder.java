package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Mf2ModelDecoder {
    private Mf2ModelDecoder() {}

    static Mf2Message fromJson(Object value) {
        Map<String, Object> map = object(value, "message");
        return switch (string(map.get("type"), "message.type")) {
            case "message" -> new Mf2Message.PatternMessage(
                    parseDeclarations(map.get("declarations")),
                    parsePattern(map.get("pattern")));
            case "select" -> new Mf2Message.SelectMessage(
                    parseDeclarations(map.get("declarations")),
                    parseVariableRefs(map.get("selectors")),
                    parseVariants(map.get("variants")));
            default -> throw new IllegalArgumentException("Unsupported MF2 message type.");
        };
    }

    private static List<Mf2Message.Declaration> parseDeclarations(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Mf2Message.Declaration> declarations = new ArrayList<>();
        for (Object item : array(value, "declarations")) {
            Map<String, Object> map = object(item, "declaration");
            String name = string(map.get("name"), "declaration.name");
            Mf2Message.Expression expression = parseExpression(map.get("value"));
            switch (string(map.get("type"), "declaration.type")) {
                case "input" -> declarations.add(new Mf2Message.InputDeclaration(name, expression));
                case "local" -> declarations.add(new Mf2Message.LocalDeclaration(name, expression));
                default -> throw new IllegalArgumentException("Unsupported declaration type.");
            }
        }
        return declarations;
    }

    private static List<Mf2Message.PatternPart> parsePattern(Object value) {
        List<Mf2Message.PatternPart> parts = new ArrayList<>();
        for (Object item : array(value, "pattern")) {
            if (item instanceof String text) {
                parts.add(new Mf2Message.TextPart(text));
                continue;
            }
            Map<String, Object> map = object(item, "pattern part");
            switch (string(map.get("type"), "pattern part.type")) {
                case "expression" -> parts.add(new Mf2Message.ExpressionPart(parseExpression(item)));
                case "markup" -> parts.add(new Mf2Message.MarkupPart(parseMarkup(item)));
                default -> throw new IllegalArgumentException("Unsupported pattern part type.");
            }
        }
        return parts;
    }

    private static Mf2Message.Expression parseExpression(Object value) {
        Map<String, Object> map = object(value, "expression");
        Object rawArg = map.get("arg");
        Object rawFunction = map.get("function");
        return new Mf2Message.Expression(
                rawArg == null ? null : parseExpressionArgument(rawArg),
                rawFunction == null ? null : parseFunction(rawFunction),
                parseAttributes(map.get("attributes")));
    }

    private static Mf2Message.ExpressionArgument parseExpressionArgument(Object value) {
        Map<String, Object> map = object(value, "expression argument");
        return switch (string(map.get("type"), "expression argument.type")) {
            case "literal" -> new Mf2Message.LiteralArgument(string(map.get("value"), "literal.value"));
            case "variable" -> new Mf2Message.VariableArgument(string(map.get("name"), "variable.name"));
            default -> throw new IllegalArgumentException("Unsupported expression argument type.");
        };
    }

    private static Mf2Message.FunctionRef parseFunction(Object value) {
        Map<String, Object> map = object(value, "function");
        return new Mf2Message.FunctionRef(
                string(map.get("name"), "function.name"),
                parseOptions(map.get("options")));
    }

    private static Mf2Message.Markup parseMarkup(Object value) {
        Map<String, Object> map = object(value, "markup");
        return new Mf2Message.Markup(
                string(map.get("kind"), "markup.kind"),
                string(map.get("name"), "markup.name"),
                parseOptions(map.get("options")),
                parseAttributes(map.get("attributes")));
    }

    private static Map<String, Mf2Message.ExpressionArgument> parseOptions(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Mf2Message.ExpressionArgument> options = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value, "options").entrySet()) {
            options.put(entry.getKey(), parseExpressionArgument(entry.getValue()));
        }
        return options;
    }

    private static Map<String, Mf2Message.AttributeValue> parseAttributes(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Mf2Message.AttributeValue> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value, "attributes").entrySet()) {
            if (entry.getValue() instanceof Boolean present) {
                attributes.put(entry.getKey(), new Mf2Message.PresentAttribute(present));
            } else {
                attributes.put(
                        entry.getKey(),
                        new Mf2Message.LiteralAttribute(parseExpressionArgument(entry.getValue())));
            }
        }
        return attributes;
    }

    private static List<Mf2Message.VariableRef> parseVariableRefs(Object value) {
        List<Mf2Message.VariableRef> refs = new ArrayList<>();
        for (Object item : array(value, "selectors")) {
            Map<String, Object> map = object(item, "selector");
            refs.add(new Mf2Message.VariableRef(string(map.get("name"), "selector.name")));
        }
        return refs;
    }

    private static List<Mf2Message.Variant> parseVariants(Object value) {
        List<Mf2Message.Variant> variants = new ArrayList<>();
        for (Object item : array(value, "variants")) {
            Map<String, Object> map = object(item, "variant");
            variants.add(new Mf2Message.Variant(
                    parseVariantKeys(map.get("keys")),
                    parsePattern(map.get("value"))));
        }
        return variants;
    }

    private static List<Mf2Message.VariantKey> parseVariantKeys(Object value) {
        List<Mf2Message.VariantKey> keys = new ArrayList<>();
        for (Object item : array(value, "variant.keys")) {
            Map<String, Object> map = object(item, "variant key");
            switch (string(map.get("type"), "variant key.type")) {
                case "*" -> keys.add(new Mf2Message.CatchAllVariantKey());
                case "literal" -> keys.add(
                        new Mf2Message.LiteralVariantKey(string(map.get("value"), "variant key.value")));
                default -> throw new IllegalArgumentException("Unsupported variant key type.");
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String context) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected object for " + context + ".");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String context) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Expected array for " + context + ".");
    }

    private static String string(Object value, String context) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Expected string for " + context + ".");
    }
}
