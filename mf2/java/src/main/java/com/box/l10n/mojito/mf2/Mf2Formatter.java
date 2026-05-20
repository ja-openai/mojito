package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Mf2Formatter {
    private static final Set<String> PASSTHROUGH_FUNCTIONS =
            Set.of("string", "number", "datetime", "date", "time");

    private Mf2Formatter() {}

    static String format(Mf2Message message, Map<String, ?> arguments, String locale)
            throws Mf2Exception {
        FormatContext context = new FormatContext(arguments, locale);
        context.apply(message.declarations());
        return switch (message) {
            case Mf2Message.Message simple -> context.formatPattern(simple.pattern());
            case Mf2Message.Select select -> context.formatSelect(select.selectors(), select.variants());
        };
    }

    private static final class FormatContext {
        private final Map<String, Object> values;
        private final Map<String, SelectorAnnotation> selectorAnnotations = new HashMap<>();
        private final String locale;

        FormatContext(Map<String, ?> arguments, String locale) {
            this.values = new HashMap<>();
            if (arguments != null) {
                this.values.putAll(arguments);
            }
            this.locale = locale == null || locale.isBlank() ? "en" : locale;
        }

        void apply(List<Mf2Message.Declaration> declarations) throws Mf2Exception {
            for (Mf2Message.Declaration declaration : declarations) {
                switch (declaration) {
                    case Mf2Message.InputDeclaration input -> {
                        Mf2Message.FunctionRef function = input.value().function();
                        if (function != null) {
                            selectorAnnotations.put(input.name(), SelectorAnnotation.from(function));
                        }
                    }
                    case Mf2Message.LocalDeclaration local -> values.put(
                            local.name(), formatExpression(local.value()));
                }
            }
        }

        String formatSelect(List<Mf2Message.VariableRef> selectors, List<Mf2Message.Variant> variants)
                throws Mf2Exception {
            List<SelectorValue> selectorValues = new ArrayList<>();
            for (Mf2Message.VariableRef selector : selectors) {
                selectorValues.add(selectorValue(selector));
            }

            Mf2Message.Variant selected = variants.stream()
                    .filter(variant -> variantMatches(variant, selectorValues))
                    .findFirst()
                    .or(() -> variants.stream().filter(Mf2Formatter::isFallbackVariant).findFirst())
                    .orElseThrow(Mf2Exception::missingSelectVariant);
            return formatPattern(selected.value());
        }

        private SelectorValue selectorValue(Mf2Message.VariableRef selector) throws Mf2Exception {
            if (!values.containsKey(selector.name())) {
                throw Mf2Exception.missingArgument(selector.name());
            }
            Object value = values.get(selector.name());
            return new SelectorValue(
                    valueToString(value),
                    exactMatch(selector.name()),
                    selectionKey(selector.name(), value));
        }

        String formatPattern(List<Mf2Message.PatternPart> pattern) throws Mf2Exception {
            StringBuilder output = new StringBuilder();
            for (Mf2Message.PatternPart part : pattern) {
                switch (part) {
                    case Mf2Message.TextPart text -> output.append(text.value());
                    case Mf2Message.ExpressionPart expression ->
                            output.append(formatExpression(expression.expression()));
                    case Mf2Message.MarkupPart ignored -> {
                    }
                }
            }
            return output.toString();
        }

        String formatExpression(Mf2Message.Expression expression) throws Mf2Exception {
            String value = switch (expression.arg()) {
                case null -> "";
                case Mf2Message.LiteralArgument literal -> literal.value();
                case Mf2Message.VariableArgument variable -> {
                    if (!values.containsKey(variable.name())) {
                        throw Mf2Exception.missingArgument(variable.name());
                    }
                    yield valueToString(values.get(variable.name()));
                }
            };

            Mf2Message.FunctionRef function = expression.function();
            if (function == null || PASSTHROUGH_FUNCTIONS.contains(function.name())) {
                return value;
            }
            throw Mf2Exception.unsupportedFunction(function.name());
        }

        private boolean exactMatch(String selectorName) {
            SelectorAnnotation annotation = selectorAnnotations.get(selectorName);
            return annotation == null || annotation.exactMatch();
        }

        private String selectionKey(String selectorName, Object value) {
            SelectorAnnotation annotation = selectorAnnotations.get(selectorName);
            if (annotation == null || !annotation.function().equals("number")) {
                return null;
            }
            return PluralRules.selectPluralCategory(locale, value, annotation.numberSelect());
        }
    }

    private static boolean variantMatches(
            Mf2Message.Variant variant, List<SelectorValue> selectorValues) {
        if (variant.keys().size() != selectorValues.size()) {
            return false;
        }
        for (int index = 0; index < variant.keys().size(); index++) {
            Mf2Message.VariantKey key = variant.keys().get(index);
            SelectorValue selector = selectorValues.get(index);
            if (!keyMatches(key, selector)) {
                return false;
            }
        }
        return true;
    }

    private static boolean keyMatches(Mf2Message.VariantKey key, SelectorValue selector) {
        return switch (key) {
            case Mf2Message.CatchAllVariantKey ignored -> true;
            case Mf2Message.LiteralVariantKey literal ->
                    (selector.exactMatch() && literal.value().equals(selector.rendered()))
                            || literal.value().equals(selector.selectionKey());
        };
    }

    private static boolean isFallbackVariant(Mf2Message.Variant variant) {
        return variant.keys().stream().allMatch(Mf2Message.CatchAllVariantKey.class::isInstance);
    }

    static String valueToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number) && Math.rint(number) == number) {
                return Long.toString((long) number);
            }
            return Double.toString(number);
        }
        return value.toString();
    }

    private record SelectorAnnotation(String function, NumberSelect numberSelect) {
        static SelectorAnnotation from(Mf2Message.FunctionRef function) {
            Mf2Message.ExpressionArgument option = function.options().get("select");
            String select = option instanceof Mf2Message.LiteralArgument literal
                    ? literal.value()
                    : "plural";
            NumberSelect numberSelect = switch (select) {
                case "ordinal" -> NumberSelect.ORDINAL;
                case "exact" -> NumberSelect.EXACT;
                default -> NumberSelect.PLURAL;
            };
            return new SelectorAnnotation(function.name(), numberSelect);
        }

        boolean exactMatch() {
            return function.equals("string") || (function.equals("number") && numberSelect == NumberSelect.EXACT);
        }
    }

    private record SelectorValue(String rendered, boolean exactMatch, String selectionKey) {}
}
