package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface Mf2Message permits Mf2Message.Message, Mf2Message.Select {
    List<Declaration> declarations();

    default String format(Map<String, ?> arguments) throws Mf2Exception {
        return format(arguments, "en");
    }

    default String format(Map<String, ?> arguments, String locale) throws Mf2Exception {
        return Mf2Formatter.format(this, arguments, locale);
    }

    default String format(
            Map<String, ?> arguments, String locale, Mf2BidiIsolation bidiIsolation)
            throws Mf2Exception {
        return Mf2Formatter.format(this, arguments, locale, bidiIsolation);
    }

    default String format(
            Map<String, ?> arguments, String locale, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        return Mf2Formatter.format(this, arguments, locale, functions);
    }

    default String format(
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions,
            Mf2BidiIsolation bidiIsolation)
            throws Mf2Exception {
        return Mf2Formatter.format(this, arguments, locale, functions, bidiIsolation);
    }

    default List<FormattedPart> formatToParts(Map<String, ?> arguments, String locale)
            throws Mf2Exception {
        return Mf2Formatter.formatToParts(this, arguments, locale);
    }

    default List<FormattedPart> formatToParts(
            Map<String, ?> arguments, String locale, Mf2FunctionRegistry functions)
            throws Mf2Exception {
        return Mf2Formatter.formatToParts(this, arguments, locale, functions);
    }

    static Mf2Message fromJson(Object value) {
        Map<String, Object> map = object(value, "message");
        return switch (string(map.get("type"), "message.type")) {
            case "message" -> new Message(
                    parseDeclarations(map.get("declarations")),
                    parsePattern(map.get("pattern")));
            case "select" -> new Select(
                    parseDeclarations(map.get("declarations")),
                    parseVariableRefs(map.get("selectors")),
                    parseVariants(map.get("variants")));
            default -> throw new IllegalArgumentException("Unsupported MF2 message type.");
        };
    }

    record Message(List<Declaration> declarations, List<PatternPart> pattern)
            implements Mf2Message {
        public Message {
            declarations = List.copyOf(declarations);
            pattern = List.copyOf(pattern);
        }
    }

    record Select(
            List<Declaration> declarations,
            List<VariableRef> selectors,
            List<Variant> variants)
            implements Mf2Message {
        public Select {
            declarations = List.copyOf(declarations);
            selectors = List.copyOf(selectors);
            variants = List.copyOf(variants);
        }
    }

    sealed interface Declaration permits InputDeclaration, LocalDeclaration {
        String name();

        Expression value();
    }

    record InputDeclaration(String name, Expression value) implements Declaration {}

    record LocalDeclaration(String name, Expression value) implements Declaration {}

    sealed interface PatternPart permits TextPart, ExpressionPart, MarkupPart {}

    record TextPart(String value) implements PatternPart {}

    record ExpressionPart(Expression expression) implements PatternPart {}

    record MarkupPart(Markup markup) implements PatternPart {}

    record Expression(
            ExpressionArgument arg, FunctionRef function, Map<String, AttributeValue> attributes) {
        public Expression {
            attributes = Map.copyOf(attributes);
        }
    }

    sealed interface ExpressionArgument permits LiteralArgument, VariableArgument {}

    record LiteralArgument(String value) implements ExpressionArgument {}

    record VariableArgument(String name) implements ExpressionArgument {}

    record FunctionRef(String name, Map<String, ExpressionArgument> options) {
        public FunctionRef {
            options = Map.copyOf(options);
        }
    }

    sealed interface AttributeValue permits LiteralAttribute, PresentAttribute {}

    record LiteralAttribute(ExpressionArgument value) implements AttributeValue {}

    record PresentAttribute(boolean value) implements AttributeValue {}

    record Markup(
            String kind,
            String name,
            Map<String, ExpressionArgument> options,
            Map<String, AttributeValue> attributes) {
        public Markup {
            options = Map.copyOf(options);
            attributes = Map.copyOf(attributes);
        }
    }

    record VariableRef(String name) {}

    record Variant(List<VariantKey> keys, List<PatternPart> value) {
        public Variant {
            keys = List.copyOf(keys);
            value = List.copyOf(value);
        }
    }

    sealed interface VariantKey permits LiteralVariantKey, CatchAllVariantKey {}

    record LiteralVariantKey(String value) implements VariantKey {}

    record CatchAllVariantKey() implements VariantKey {}

    sealed interface FormattedPart permits FormattedText, FormattedExpression, FormattedMarkup {}

    record FormattedText(String value) implements FormattedPart {}

    record FormattedExpression(String value, Map<String, AttributeValue> attributes)
            implements FormattedPart {
        public FormattedExpression {
            attributes = Map.copyOf(attributes);
        }
    }

    record FormattedMarkup(
            String kind,
            String name,
            Map<String, ExpressionArgument> options,
            Map<String, AttributeValue> attributes)
            implements FormattedPart {
        public FormattedMarkup {
            options = Map.copyOf(options);
            attributes = Map.copyOf(attributes);
        }
    }

    private static List<Declaration> parseDeclarations(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Declaration> declarations = new ArrayList<>();
        for (Object item : array(value, "declarations")) {
            Map<String, Object> map = object(item, "declaration");
            String name = string(map.get("name"), "declaration.name");
            Expression expression = parseExpression(map.get("value"));
            switch (string(map.get("type"), "declaration.type")) {
                case "input" -> declarations.add(new InputDeclaration(name, expression));
                case "local" -> declarations.add(new LocalDeclaration(name, expression));
                default -> throw new IllegalArgumentException("Unsupported declaration type.");
            }
        }
        return declarations;
    }

    private static List<PatternPart> parsePattern(Object value) {
        List<PatternPart> parts = new ArrayList<>();
        for (Object item : array(value, "pattern")) {
            if (item instanceof String text) {
                parts.add(new TextPart(text));
                continue;
            }
            Map<String, Object> map = object(item, "pattern part");
            switch (string(map.get("type"), "pattern part.type")) {
                case "expression" -> parts.add(new ExpressionPart(parseExpression(item)));
                case "markup" -> parts.add(new MarkupPart(parseMarkup(item)));
                default -> throw new IllegalArgumentException("Unsupported pattern part type.");
            }
        }
        return parts;
    }

    private static Expression parseExpression(Object value) {
        Map<String, Object> map = object(value, "expression");
        Object rawArg = map.get("arg");
        Object rawFunction = map.get("function");
        return new Expression(
                rawArg == null ? null : parseExpressionArgument(rawArg),
                rawFunction == null ? null : parseFunction(rawFunction),
                parseAttributes(map.get("attributes")));
    }

    private static ExpressionArgument parseExpressionArgument(Object value) {
        Map<String, Object> map = object(value, "expression argument");
        return switch (string(map.get("type"), "expression argument.type")) {
            case "literal" -> new LiteralArgument(string(map.get("value"), "literal.value"));
            case "variable" -> new VariableArgument(string(map.get("name"), "variable.name"));
            default -> throw new IllegalArgumentException("Unsupported expression argument type.");
        };
    }

    private static FunctionRef parseFunction(Object value) {
        Map<String, Object> map = object(value, "function");
        return new FunctionRef(string(map.get("name"), "function.name"), parseOptions(map.get("options")));
    }

    private static Markup parseMarkup(Object value) {
        Map<String, Object> map = object(value, "markup");
        return new Markup(
                string(map.get("kind"), "markup.kind"),
                string(map.get("name"), "markup.name"),
                parseOptions(map.get("options")),
                parseAttributes(map.get("attributes")));
    }

    private static Map<String, ExpressionArgument> parseOptions(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, ExpressionArgument> options = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value, "options").entrySet()) {
            options.put(entry.getKey(), parseExpressionArgument(entry.getValue()));
        }
        return options;
    }

    private static Map<String, AttributeValue> parseAttributes(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value, "attributes").entrySet()) {
            if (entry.getValue() instanceof Boolean present) {
                attributes.put(entry.getKey(), new PresentAttribute(present));
            } else {
                attributes.put(entry.getKey(), new LiteralAttribute(parseExpressionArgument(entry.getValue())));
            }
        }
        return attributes;
    }

    private static List<VariableRef> parseVariableRefs(Object value) {
        List<VariableRef> refs = new ArrayList<>();
        for (Object item : array(value, "selectors")) {
            Map<String, Object> map = object(item, "selector");
            refs.add(new VariableRef(string(map.get("name"), "selector.name")));
        }
        return refs;
    }

    private static List<Variant> parseVariants(Object value) {
        List<Variant> variants = new ArrayList<>();
        for (Object item : array(value, "variants")) {
            Map<String, Object> map = object(item, "variant");
            variants.add(new Variant(parseVariantKeys(map.get("keys")), parsePattern(map.get("value"))));
        }
        return variants;
    }

    private static List<VariantKey> parseVariantKeys(Object value) {
        List<VariantKey> keys = new ArrayList<>();
        for (Object item : array(value, "variant.keys")) {
            Map<String, Object> map = object(item, "variant key");
            switch (string(map.get("type"), "variant key.type")) {
                case "*" -> keys.add(new CatchAllVariantKey());
                case "literal" -> keys.add(new LiteralVariantKey(string(map.get("value"), "variant key.value")));
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
