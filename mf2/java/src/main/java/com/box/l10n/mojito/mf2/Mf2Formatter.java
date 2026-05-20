package com.box.l10n.mojito.mf2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Mf2Formatter {
    private static final Mf2FunctionRegistry DEFAULT_FUNCTIONS = Mf2FunctionRegistry.defaults();

    private Mf2Formatter() {}

    static String format(Mf2Message message, Map<String, ?> arguments, String locale)
            throws Mf2Exception {
        return format(message, arguments, locale, DEFAULT_FUNCTIONS);
    }

    static String format(
            Mf2Message message,
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        return partsToString(formatToParts(message, arguments, locale, functions));
    }

    static List<Mf2Message.FormattedPart> formatToParts(
            Mf2Message message, Map<String, ?> arguments, String locale) throws Mf2Exception {
        return formatToParts(message, arguments, locale, DEFAULT_FUNCTIONS);
    }

    static List<Mf2Message.FormattedPart> formatToParts(
            Mf2Message message,
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        validate(message);
        FormatContext context = new FormatContext(snapshotArguments(arguments), locale, functions);
        context.apply(message.declarations());
        return switch (message) {
            case Mf2Message.Message simple -> context.formatPatternToParts(simple.pattern());
            case Mf2Message.Select select -> context.formatSelectToParts(select.selectors(), select.variants());
        };
    }

    private static void validate(Mf2Message message) throws Mf2Exception {
        validateDeclarations(message.declarations());
        switch (message) {
            case Mf2Message.Message simple -> validatePattern(simple.pattern());
            case Mf2Message.Select select -> {
                validateSelectorAnnotations(select.declarations(), select.selectors());
                for (Mf2Message.Variant variant : select.variants()) {
                    validatePattern(variant.value());
                }
            }
        }
    }

    private static void validateDeclarations(List<Mf2Message.Declaration> declarations)
            throws Mf2Exception {
        Set<String> names = new HashSet<>();
        for (Mf2Message.Declaration declaration : declarations) {
            if (declaration instanceof Mf2Message.InputDeclaration input) {
                validateInputDeclaration(input);
            }
            if (!names.add(declaration.name())) {
                throw Mf2Exception.duplicateDeclaration(declaration.name());
            }
        }
    }

    private static void validateInputDeclaration(Mf2Message.InputDeclaration input)
            throws Mf2Exception {
        if (input.value().arg() instanceof Mf2Message.VariableArgument variable
                && variable.name().equals(input.name())) {
            return;
        }
        throw Mf2Exception.invalidInputDeclaration(input.name());
    }

    private static void validatePattern(List<Mf2Message.PatternPart> pattern)
            throws Mf2Exception {
        for (Mf2Message.PatternPart part : pattern) {
            if (part instanceof Mf2Message.TextPart text && text.value().isEmpty()) {
                throw Mf2Exception.invalidPatternText();
            }
            if (part instanceof Mf2Message.MarkupPart markup) {
                validateMarkup(markup.markup());
            }
        }
    }

    private static void validateMarkup(Mf2Message.Markup markup) throws Mf2Exception {
        switch (markup.kind()) {
            case "open", "standalone", "close" -> {}
            default -> throw Mf2Exception.invalidMarkupKind();
        }
    }

    private static void validateSelectorAnnotations(
            List<Mf2Message.Declaration> declarations, List<Mf2Message.VariableRef> selectors)
            throws Mf2Exception {
        Map<String, SelectorAnnotation> annotations = selectorAnnotations(declarations);
        for (Mf2Message.VariableRef selector : selectors) {
            if (!annotations.containsKey(selector.name())) {
                throw Mf2Exception.missingSelectorAnnotation(selector.name());
            }
        }
    }

    private static Map<String, SelectorAnnotation> selectorAnnotations(
            List<Mf2Message.Declaration> declarations) {
        Map<String, Mf2Message.Expression> expressions = new HashMap<>();
        Map<String, SelectorAnnotation> annotations = new HashMap<>();
        for (Mf2Message.Declaration declaration : declarations) {
            expressions.put(declaration.name(), declaration.value());
            Mf2Message.FunctionRef function = declaration.value().function();
            if (function != null) {
                annotations.put(declaration.name(), SelectorAnnotation.from(function));
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Mf2Message.Expression> entry : expressions.entrySet()) {
                if (annotations.containsKey(entry.getKey())) {
                    continue;
                }
                if (entry.getValue().arg() instanceof Mf2Message.VariableArgument variable) {
                    SelectorAnnotation annotation = annotations.get(variable.name());
                    if (annotation != null) {
                        annotations.put(entry.getKey(), annotation);
                        changed = true;
                    }
                }
            }
        }
        return annotations;
    }

    private static final class FormatContext {
        private final ArgumentValues arguments;
        private Map<String, Object> locals;
        private String selectorAnnotationName;
        private SelectorAnnotation selectorAnnotation;
        private Map<String, SelectorAnnotation> selectorAnnotations;
        private final String locale;
        private final Mf2FunctionRegistry functions;

        FormatContext(ArgumentValues arguments, String locale, Mf2FunctionRegistry functions) {
            this.arguments = arguments;
            this.locale = locale == null || locale.isBlank() ? "en" : locale;
            this.functions = Objects.requireNonNull(functions);
        }

        void apply(List<Mf2Message.Declaration> declarations) throws Mf2Exception {
            for (Map.Entry<String, SelectorAnnotation> entry : selectorAnnotations(declarations).entrySet()) {
                addSelectorAnnotation(entry.getKey(), entry.getValue());
            }
            for (Mf2Message.Declaration declaration : declarations) {
                switch (declaration) {
                    case Mf2Message.InputDeclaration ignored -> {}
                    case Mf2Message.LocalDeclaration local -> putLocal(local.name(), formatExpression(local.value()));
                }
            }
        }

        List<Mf2Message.FormattedPart> formatSelectToParts(
                List<Mf2Message.VariableRef> selectors, List<Mf2Message.Variant> variants)
                throws Mf2Exception {
            List<SelectorValue> selectorValues = new ArrayList<>(selectors.size());
            for (Mf2Message.VariableRef selector : selectors) {
                selectorValues.add(selectorValue(selector));
            }

            Set<List<Mf2Message.VariantKey>> signatures = new HashSet<>();
            Mf2Message.Variant fallback = null;
            Mf2Message.Variant selected = null;
            for (Mf2Message.Variant variant : variants) {
                validateVariant(variant, selectorValues.size(), signatures);
                if (fallback == null && isFallbackVariant(variant)) {
                    fallback = variant;
                }
                if (selected == null && variantMatches(variant, selectorValues)) {
                    selected = variant;
                }
            }
            if (fallback == null) {
                throw Mf2Exception.missingFallbackVariant();
            }
            return formatPatternToParts((selected == null ? fallback : selected).value());
        }

        private SelectorValue selectorValue(Mf2Message.VariableRef selector) throws Mf2Exception {
            if (!hasValue(selector.name())) {
                throw Mf2Exception.missingArgument(selector.name());
            }
            Object value = value(selector.name());
            return new SelectorValue(
                    valueToString(value),
                    exactMatch(selector.name()),
                    selectionKey(selector.name(), value));
        }

        String formatPattern(List<Mf2Message.PatternPart> pattern) throws Mf2Exception {
            return partsToString(formatPatternToParts(pattern));
        }

        List<Mf2Message.FormattedPart> formatPatternToParts(List<Mf2Message.PatternPart> pattern)
                throws Mf2Exception {
            List<Mf2Message.FormattedPart> output = new ArrayList<>(pattern.size());
            for (Mf2Message.PatternPart part : pattern) {
                switch (part) {
                    case Mf2Message.TextPart text -> output.add(new Mf2Message.FormattedText(text.value()));
                    case Mf2Message.ExpressionPart expression -> output.add(
                            new Mf2Message.FormattedExpression(
                                    formatExpression(expression.expression()),
                                    expression.expression().attributes()));
                    case Mf2Message.MarkupPart markup -> output.add(new Mf2Message.FormattedMarkup(
                            markup.markup().kind(),
                            markup.markup().name(),
                            markup.markup().attributes()));
                }
            }
            return output;
        }

        String formatExpression(Mf2Message.Expression expression) throws Mf2Exception {
            String value = switch (expression.arg()) {
                case null -> "";
                case Mf2Message.LiteralArgument literal -> literal.value();
                case Mf2Message.VariableArgument variable -> {
                    if (!hasValue(variable.name())) {
                        throw Mf2Exception.missingArgument(variable.name());
                    }
                    yield valueToString(value(variable.name()));
                }
            };

            Mf2Message.FunctionRef function = expression.function();
            if (function == null) {
                return value;
            }
            return functions.format(new Mf2FunctionRegistry.FunctionCall(
                    value,
                    function,
                    locale,
                    (optionName, defaultValue) -> optionValue(function, optionName, defaultValue)));
        }

        private String optionValue(
                Mf2Message.FunctionRef function, String optionName, String defaultValue)
                throws Mf2Exception {
            Mf2Message.ExpressionArgument option = function.options().get(optionName);
            if (option == null) {
                return defaultValue;
            }
            return switch (option) {
                case Mf2Message.LiteralArgument literal -> literal.value();
                case Mf2Message.VariableArgument variable -> {
                    if (!hasValue(variable.name())) {
                        throw Mf2Exception.missingArgument(variable.name());
                    }
                    yield valueToString(value(variable.name()));
                }
            };
        }

        private boolean exactMatch(String selectorName) {
            SelectorAnnotation annotation = selectorAnnotation(selectorName);
            return annotation == null || annotation.exactMatch();
        }

        private String selectionKey(String selectorName, Object value) {
            SelectorAnnotation annotation = selectorAnnotation(selectorName);
            if (annotation == null || !annotation.function().equals("number")) {
                return null;
            }
            return PluralRules.selectPluralCategory(locale, value, annotation.numberSelect());
        }

        private boolean hasValue(String name) {
            return (locals != null && locals.containsKey(name)) || arguments.contains(name);
        }

        private Object value(String name) {
            return locals != null && locals.containsKey(name) ? locals.get(name) : arguments.get(name);
        }

        private void putLocal(String name, Object value) {
            if (locals == null) {
                locals = new HashMap<>();
            }
            locals.put(name, value);
        }

        private void addSelectorAnnotation(String name, SelectorAnnotation annotation) {
            if (selectorAnnotationName == null) {
                selectorAnnotationName = name;
                selectorAnnotation = annotation;
                return;
            }
            if (selectorAnnotations == null) {
                selectorAnnotations = new HashMap<>(4);
                selectorAnnotations.put(selectorAnnotationName, selectorAnnotation);
            }
            selectorAnnotations.put(name, annotation);
        }

        private SelectorAnnotation selectorAnnotation(String name) {
            if (selectorAnnotations != null) {
                return selectorAnnotations.get(name);
            }
            return name.equals(selectorAnnotationName) ? selectorAnnotation : null;
        }
    }

    private static ArgumentValues snapshotArguments(Map<String, ?> arguments) {
        // Keep format() isolated from caller map mutation without penalizing the common one-arg path.
        if (arguments == null || arguments.isEmpty()) {
            return EmptyArgumentValues.INSTANCE;
        }
        if (arguments.size() == 1) {
            Map.Entry<String, ?> entry = arguments.entrySet().iterator().next();
            return new SingleArgumentValue(entry.getKey(), entry.getValue());
        }
        return new MapArgumentValues(new HashMap<>(arguments));
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

    private static void validateVariant(
            Mf2Message.Variant variant, int selectorCount, Set<List<Mf2Message.VariantKey>> signatures)
            throws Mf2Exception {
        if (variant.keys().size() != selectorCount) {
            throw Mf2Exception.variantKeyCountMismatch();
        }
        if (!signatures.add(variant.keys())) {
            throw Mf2Exception.duplicateVariant();
        }
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
        for (Mf2Message.VariantKey key : variant.keys()) {
            if (!(key instanceof Mf2Message.CatchAllVariantKey)) {
                return false;
            }
        }
        return true;
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

    private static String partsToString(List<Mf2Message.FormattedPart> parts) {
        StringBuilder output = new StringBuilder();
        for (Mf2Message.FormattedPart part : parts) {
            switch (part) {
                case Mf2Message.FormattedText text -> output.append(text.value());
                case Mf2Message.FormattedExpression expression -> output.append(expression.value());
                case Mf2Message.FormattedMarkup ignored -> {
                }
            }
        }
        return output.toString();
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

    private interface ArgumentValues {
        boolean contains(String name);

        Object get(String name);
    }

    private enum EmptyArgumentValues implements ArgumentValues {
        INSTANCE;

        @Override
        public boolean contains(String name) {
            return false;
        }

        @Override
        public Object get(String name) {
            return null;
        }
    }

    private record SingleArgumentValue(String name, Object value) implements ArgumentValues {
        @Override
        public boolean contains(String candidate) {
            return Objects.equals(name, candidate);
        }

        @Override
        public Object get(String candidate) {
            return Objects.equals(name, candidate) ? value : null;
        }
    }

    private record MapArgumentValues(Map<String, ?> values) implements ArgumentValues {
        @Override
        public boolean contains(String name) {
            return values.containsKey(name);
        }

        @Override
        public Object get(String name) {
            return values.get(name);
        }
    }
}
