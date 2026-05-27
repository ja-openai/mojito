package com.box.l10n.mojito.mf2;

import java.text.Normalizer;
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
            Mf2BidiIsolation bidiIsolation)
            throws Mf2Exception {
        return format(message, arguments, locale, DEFAULT_FUNCTIONS, bidiIsolation);
    }

    static String format(
            Mf2Message message,
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        return format(message, arguments, locale, functions, Mf2BidiIsolation.NONE);
    }

    static String format(
            Mf2Message message,
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions,
            Mf2BidiIsolation bidiIsolation)
            throws Mf2Exception {
        return partsToString(formatToParts(message, arguments, locale, functions), bidiIsolation);
    }

    static Mf2Message.FallbackFormatResult formatWithFallback(
            Mf2Message message, Map<String, ?> arguments, String locale) throws Mf2Exception {
        FallbackPartsResult result =
                formatToPartsWithFallback(message, arguments, locale, DEFAULT_FUNCTIONS);
        return new Mf2Message.FallbackFormatResult(
                partsToString(result.parts(), Mf2BidiIsolation.NONE), result.errors());
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

    static FallbackPartsResult formatToPartsWithFallback(
            Mf2Message message,
            Map<String, ?> arguments,
            String locale,
            Mf2FunctionRegistry functions)
            throws Mf2Exception {
        validate(message);
        FormatContext context =
                new FormatContext(snapshotArguments(arguments), locale, functions, true);
        context.apply(message.declarations());
        List<Mf2Message.FormattedPart> parts = switch (message) {
            case Mf2Message.Message simple -> context.formatPatternToParts(simple.pattern());
            case Mf2Message.Select select -> context.formatSelectToParts(select.selectors(), select.variants());
        };
        return new FallbackPartsResult(parts, context.errors);
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
        validateLocalReferences(declarations);
    }

    private static void validateLocalReferences(List<Mf2Message.Declaration> declarations)
            throws Mf2Exception {
        Set<String> forbidden = new HashSet<>();
        for (int index = declarations.size() - 1; index >= 0; index--) {
            Mf2Message.Declaration declaration = declarations.get(index);
            if (!(declaration instanceof Mf2Message.LocalDeclaration local)) {
                continue;
            }
            forbidden.add(local.name());
            if (expressionReferencesAny(local.value(), forbidden)) {
                throw Mf2Exception.duplicateDeclaration(local.name());
            }
        }
    }

    private static boolean expressionReferencesAny(
            Mf2Message.Expression expression, Set<String> names) {
        return expressionArgumentReferencesAny(expression.arg(), names)
                || functionReferencesAny(expression.function(), names);
    }

    private static boolean functionReferencesAny(
            Mf2Message.FunctionRef function, Set<String> names) {
        if (function == null) {
            return false;
        }
        for (Mf2Message.ExpressionArgument option : function.options().values()) {
            if (expressionArgumentReferencesAny(option, names)) {
                return true;
            }
        }
        return false;
    }

    private static boolean expressionArgumentReferencesAny(
            Mf2Message.ExpressionArgument arg, Set<String> names) {
        return arg instanceof Mf2Message.VariableArgument variable
                && names.contains(variable.name());
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
        private Map<String, ResolvedValue> locals;
        private Set<String> failedLocals;
        private final List<Mf2Exception> errors;
        private String selectorAnnotationName;
        private SelectorAnnotation selectorAnnotation;
        private Map<String, SelectorAnnotation> selectorAnnotations;
        private final String locale;
        private final Mf2FunctionRegistry functions;
        private final boolean fallback;

        FormatContext(ArgumentValues arguments, String locale, Mf2FunctionRegistry functions) {
            this(arguments, locale, functions, false);
        }

        FormatContext(
                ArgumentValues arguments,
                String locale,
                Mf2FunctionRegistry functions,
                boolean fallback) {
            this.arguments = arguments;
            this.locale = locale == null || locale.isBlank() ? "en" : locale;
            this.functions = Objects.requireNonNull(functions);
            this.errors = new ArrayList<>();
            this.fallback = fallback;
        }

        void apply(List<Mf2Message.Declaration> declarations) throws Mf2Exception {
            for (Map.Entry<String, SelectorAnnotation> entry : selectorAnnotations(declarations).entrySet()) {
                addSelectorAnnotation(entry.getKey(), entry.getValue());
            }
            for (Mf2Message.Declaration declaration : declarations) {
                switch (declaration) {
                    case Mf2Message.InputDeclaration input -> applyInputDeclaration(input);
                    case Mf2Message.LocalDeclaration local -> {
                        ExpressionOutput output = formatExpressionOutput(local.value());
                        if (output.hadError()) {
                            addFailedLocal(local.name());
                        } else {
                            putLocal(local.name(), ResolvedValue.string(output.value(), output.source()));
                        }
                    }
                }
            }
        }

        private void applyInputDeclaration(Mf2Message.InputDeclaration input) throws Mf2Exception {
            Mf2Message.FunctionRef function = input.value().function();
            if (function == null || !functions.hasSelector(function) || !functions.hasFormatter(function)) {
                return;
            }
            if (!hasValue(input.name())) {
                if (fallback) {
                    addFailedLocal(input.name());
                    errors.add(Mf2Exception.unresolvedVariable(input.name()));
                    errors.add(Mf2Exception.badOperand("Function operand is not available."));
                    return;
                }
                throw Mf2Exception.missingArgument(input.name());
            }
            ResolvedValue inputValue = value(input.name());
            String rendered = inputValue.rendered();
            recordFunctionResolutionErrors(function, inputValue.source());
            try {
                String formatted = functions.format(new Mf2FunctionRegistry.FunctionCall(
                        rendered,
                        inputValue.rawValue(),
                        function,
                        locale,
                        (optionName, defaultValue) -> optionValue(function, optionName, defaultValue),
                        sourceRef(inputValue.source())));
                String sourceValue = inputValue.source() == null ? rendered : inputValue.source().value();
                putLocal(input.name(), ResolvedValue.string(
                        formatted,
                        new ResolvedFunctionSource(sourceValue, function, inputValue.source())));
            } catch (Mf2Exception error) {
                if (!fallback) {
                    throw error;
                }
                errors.add(fallbackError(error));
                addFailedLocal(input.name());
            }
        }

        List<Mf2Message.FormattedPart> formatSelectToParts(
                List<Mf2Message.VariableRef> selectors, List<Mf2Message.Variant> variants)
                throws Mf2Exception {
            List<SelectorValue> selectorValues = new ArrayList<>(selectors.size());
            for (Mf2Message.VariableRef selector : selectors) {
                selectorValues.add(selectorValue(selector));
            }

            Set<List<String>> signatures = new HashSet<>();
            Mf2Message.Variant fallback = null;
            Mf2Message.Variant selected = null;
            List<Integer> selectedRank = null;
            for (Mf2Message.Variant variant : variants) {
                validateVariant(variant, selectorValues, signatures);
                if (fallback == null && isFallbackVariant(variant)) {
                    fallback = variant;
                }
                List<Integer> rank = variantMatchRank(variant, selectorValues);
                if (rank != null && (selectedRank == null || compareRank(rank, selectedRank) > 0)) {
                    selected = variant;
                    selectedRank = rank;
                }
            }
            if (fallback == null) {
                throw Mf2Exception.missingFallbackVariant();
            }
            return formatPatternToParts((selected == null ? fallback : selected).value());
        }

        private SelectorValue selectorValue(Mf2Message.VariableRef selector) throws Mf2Exception {
            if (!hasValue(selector.name())) {
                if (fallback) {
                    if (!isFailedLocal(selector.name())) {
                        errors.add(Mf2Exception.unresolvedVariable(selector.name()));
                    }
                    SelectorAnnotation annotation = selectorAnnotation(selector.name());
                    if (annotation != null && functions.hasSelector(annotation.function())) {
                        if (!isFailedLocal(selector.name())) {
                            errors.add(Mf2Exception.badOperand("Selector operand is not available."));
                        }
                        errors.add(new Mf2Exception("bad-selector", "Selector operand is not available."));
                    }
                    return new SelectorValue(
                            "",
                            annotation != null && annotation.isString() ? normalizeStringKey("") : null,
                            false,
                            null,
                            annotation == null ? null : annotation.function(),
                            null);
                }
                throw Mf2Exception.missingArgument(selector.name());
            }
            ResolvedValue value = value(selector.name());
            String rendered = value.rendered();
            SelectorAnnotation annotation = selectorAnnotation(selector.name());
            recordSelectorResolutionErrors(annotation);
            return new SelectorValue(
                    rendered,
                    annotation != null && annotation.isString() ? normalizeStringKey(rendered) : null,
                    annotation == null || annotation.exactMatch(),
                    selectionKey(annotation, value),
                    annotation == null ? null : annotation.function(),
                    value.source());
        }

        String formatPattern(List<Mf2Message.PatternPart> pattern) throws Mf2Exception {
            return partsToString(formatPatternToParts(pattern), Mf2BidiIsolation.NONE);
        }

        List<Mf2Message.FormattedPart> formatPatternToParts(List<Mf2Message.PatternPart> pattern)
                throws Mf2Exception {
            List<Mf2Message.FormattedPart> output = new ArrayList<>(pattern.size());
            for (Mf2Message.PatternPart part : pattern) {
                switch (part) {
                    case Mf2Message.TextPart text -> output.add(new Mf2Message.FormattedText(text.value()));
                    case Mf2Message.ExpressionPart expression -> {
                        ExpressionOutput rendered = formatExpressionOutput(expression.expression());
                        if (rendered.hadError()) {
                            output.add(new Mf2Message.FormattedFallback(
                                    fallbackSource(expression.expression())));
                        } else {
                            output.add(new Mf2Message.FormattedExpression(
                                    rendered.value(),
                                    expression.expression().attributes(),
                                    rendered.directionName()));
                        }
                    }
                    case Mf2Message.MarkupPart markup -> {
                        recordMarkupResolutionErrors(markup.markup());
                        output.add(new Mf2Message.FormattedMarkup(
                                markup.markup().kind(),
                                markup.markup().name(),
                                markup.markup().options(),
                                markup.markup().attributes()));
                    }
                }
            }
            return output;
        }

        String formatExpression(Mf2Message.Expression expression) throws Mf2Exception {
            return formatExpressionOutput(expression).value();
        }

        ExpressionOutput formatExpressionOutput(Mf2Message.Expression expression)
                throws Mf2Exception {
            boolean hadError = false;
            String value;
            Object rawValue;
            ResolvedFunctionSource source = null;
            switch (expression.arg()) {
                case null -> {
                    value = "";
                    rawValue = "";
                }
                case Mf2Message.LiteralArgument literal -> {
                    value = literal.value();
                    rawValue = literal.value();
                }
                case Mf2Message.VariableArgument variable -> {
                    if (!hasValue(variable.name())) {
                        if (fallback) {
                            hadError = true;
                            if (!isFailedLocal(variable.name())) {
                                errors.add(Mf2Exception.unresolvedVariable(variable.name()));
                            }
                            if (expression.function() != null) {
                                errors.add(Mf2Exception.badOperand("Function operand is not available."));
                            }
                            String fallbackValue = fallbackSource(expression);
                            value = fallbackValue;
                            rawValue = fallbackValue;
                            break;
                        }
                        throw Mf2Exception.missingArgument(variable.name());
                    }
                    ResolvedValue resolved = value(variable.name());
                    rawValue = resolved.rawValue();
                    value = resolved.rendered();
                    source = resolved.source();
                }
            }

            if (hadError) {
                return new ExpressionOutput(value, true, null, null);
            }

            Mf2Message.FunctionRef function = expression.function();
            if (function == null) {
                return new ExpressionOutput(value, false, source, bidiDirectionFromSource(source));
            }
            recordFunctionResolutionErrors(function, source);
            BidiDirection direction = bidiDirectionForFunction(function, source);
            try {
                String sourceValue = source == null ? value : source.value();
                return new ExpressionOutput(
                        functions.format(new Mf2FunctionRegistry.FunctionCall(
                                value,
                                rawValue,
                                function,
                                locale,
                                (optionName, defaultValue) -> optionValue(function, optionName, defaultValue),
                                sourceRef(source))),
                        false,
                        new ResolvedFunctionSource(sourceValue, function, source),
                        direction);
            } catch (Mf2Exception error) {
                if (!fallback) {
                    throw error;
                }
                errors.add(fallbackError(error));
                return new ExpressionOutput(fallbackSource(expression), true, null, null);
            }
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
                    yield value(variable.name()).rendered();
                }
            };
        }

        private boolean exactMatch(String selectorName) {
            SelectorAnnotation annotation = selectorAnnotation(selectorName);
            return annotation == null || annotation.exactMatch();
        }

        private String selectionKey(SelectorAnnotation annotation, ResolvedValue value) {
            if (annotation == null || !annotation.isNumeric()) {
                return null;
            }
            String operand = annotation.operandForSelection(value);
            return operand == null ? null : PluralRules.selectPluralCategory(locale, operand, annotation.numberSelect());
        }

        private boolean hasValue(String name) {
            if (isFailedLocal(name)) {
                return false;
            }
            return (locals != null && locals.containsKey(name)) || arguments.contains(name);
        }

        private ResolvedValue value(String name) {
            if (locals != null && locals.containsKey(name)) {
                return locals.get(name);
            }
            return ResolvedValue.raw(arguments.get(name));
        }

        private void putLocal(String name, ResolvedValue value) {
            if (locals == null) {
                locals = new HashMap<>();
            }
            locals.put(name, value);
        }

        private void addFailedLocal(String name) {
            if (failedLocals == null) {
                failedLocals = new HashSet<>();
            }
            failedLocals.add(name);
            if (locals != null) {
                locals.remove(name);
            }
        }

        private boolean isFailedLocal(String name) {
            return failedLocals != null && failedLocals.contains(name);
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

        private List<Integer> variantMatchRank(
                Mf2Message.Variant variant, List<SelectorValue> selectorValues)
                throws Mf2Exception {
            if (variant.keys().size() != selectorValues.size()) {
                return null;
            }
            List<Integer> ranks = new ArrayList<>(variant.keys().size());
            for (int index = 0; index < variant.keys().size(); index++) {
                Integer rank = keyMatchRank(variant.keys().get(index), selectorValues.get(index));
                if (rank == null) {
                    return null;
                }
                ranks.add(rank);
            }
            return ranks;
        }

        private Integer keyMatchRank(Mf2Message.VariantKey key, SelectorValue selector)
                throws Mf2Exception {
            return switch (key) {
                case Mf2Message.CatchAllVariantKey ignored -> 0;
                case Mf2Message.LiteralVariantKey literal -> {
                    if ((selector.exactMatch() && literalKeyMatches(literal.value(), selector))
                            || literal.value().equals(selector.selectionKey())) {
                        yield 1;
                    }
                    if (selector.function() == null) {
                        yield null;
                    }
                    try {
                        yield functions.select(new Mf2FunctionRegistry.FunctionMatch(
                                selector.rendered(),
                                selector.rendered(),
                                selector.function(),
                                literal.value(),
                                locale,
                                (optionName, defaultValue) -> optionValue(selector.function(), optionName, defaultValue),
                                sourceRef(selector.source())));
                    } catch (Mf2Exception error) {
                        if (!fallback) {
                            throw error;
                        }
                        errors.add(fallbackError(error));
                        errors.add(new Mf2Exception("bad-selector", "Selector failed to match."));
                        yield null;
                    }
                }
            };
        }

        private static int compareRank(List<Integer> left, List<Integer> right) {
            for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
                int comparison = Integer.compare(left.get(index), right.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(left.size(), right.size());
        }

        private void recordFunctionResolutionErrors(
                Mf2Message.FunctionRef function, ResolvedFunctionSource source)
                throws Mf2Exception {
            if (!isNumericFunction(function)) {
                return;
            }
            if (numericSelectUsesVariable(function) || inheritedExactNumericSource(sourceRef(source))) {
                Mf2Exception error = new Mf2Exception(
                        "bad-option",
                        "Numeric select option is not valid in this context.");
                if (fallback) {
                    errors.add(error);
                    return;
                }
                throw error;
            }
        }

        private void recordSelectorResolutionErrors(SelectorAnnotation annotation)
                throws Mf2Exception {
            if (annotation == null || !annotation.function().name().equals("currency")) {
                return;
            }
            Mf2Exception error = new Mf2Exception("bad-selector", "Currency selector is not supported.");
            if (fallback) {
                errors.add(error);
                return;
            }
            throw error;
        }

        private void recordMarkupResolutionErrors(Mf2Message.Markup markup)
                throws Mf2Exception {
            if (!markup.options().containsKey("u:dir")) {
                return;
            }
            Mf2Exception error = new Mf2Exception("bad-option", "u:dir is not valid on markup.");
            if (fallback) {
                errors.add(error);
                return;
            }
            throw error;
        }

        private Mf2FunctionRegistry.FunctionSourceRef sourceRef(ResolvedFunctionSource source) {
            if (source == null) {
                return null;
            }
            return new Mf2FunctionRegistry.FunctionSourceRef(
                    source.value(),
                    source.function(),
                    (optionName, defaultValue) -> optionValue(source.function(), optionName, defaultValue),
                    sourceRef(source.inherited()));
        }
    }

    record FallbackPartsResult(
            List<Mf2Message.FormattedPart> parts, List<Mf2Exception> errors) {}

    private record ExpressionOutput(
            String value, boolean hadError, ResolvedFunctionSource source, BidiDirection direction) {
        String directionName() {
            return direction == null ? null : direction.name;
        }
    }

    private record ResolvedValue(Object rawValue, ResolvedFunctionSource source) {
        static ResolvedValue raw(Object value) {
            return new ResolvedValue(value, null);
        }

        static ResolvedValue string(String value, ResolvedFunctionSource source) {
            return new ResolvedValue(value, source);
        }

        String rendered() {
            return valueToString(rawValue);
        }
    }

    private record ResolvedFunctionSource(
            String value, Mf2Message.FunctionRef function, ResolvedFunctionSource inherited) {}

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
            Mf2Message.Variant variant, List<SelectorValue> selectorValues, Set<List<String>> signatures)
            throws Mf2Exception {
        if (variant.keys().size() != selectorValues.size()) {
            throw Mf2Exception.variantKeyCountMismatch();
        }
        if (!signatures.add(variantKeySignature(variant.keys(), selectorValues))) {
            throw Mf2Exception.duplicateVariant();
        }
    }

    private static boolean keyMatches(Mf2Message.VariantKey key, SelectorValue selector) {
        return switch (key) {
            case Mf2Message.CatchAllVariantKey ignored -> true;
            case Mf2Message.LiteralVariantKey literal ->
                    (selector.exactMatch() && literalKeyMatches(literal.value(), selector))
                            || literal.value().equals(selector.selectionKey());
        };
    }

    private static List<String> variantKeySignature(
            List<Mf2Message.VariantKey> keys, List<SelectorValue> selectorValues) {
        List<String> signature = new ArrayList<>(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            Mf2Message.VariantKey key = keys.get(index);
            SelectorValue selector = selectorValues.get(index);
            signature.add(switch (key) {
                case Mf2Message.CatchAllVariantKey ignored -> "*";
                case Mf2Message.LiteralVariantKey literal -> "="
                        + (selector.normalizedRendered() == null
                                ? literal.value()
                                : normalizeStringKey(literal.value()));
            });
        }
        return signature;
    }

    private static boolean literalKeyMatches(String value, SelectorValue selector) {
        return selector.normalizedRendered() == null
                ? value.equals(selector.rendered())
                : normalizeStringKey(value).equals(selector.normalizedRendered());
    }

    private static String normalizeStringKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static Mf2Exception fallbackError(Mf2Exception error) {
        if (error.code().equals("unsupported-function")) {
            return new Mf2Exception("unknown-function", error.getMessage());
        }
        return error;
    }

    private static String fallbackSource(Mf2Message.Expression expression) {
        if (expression.arg() != null) {
            return expressionArgumentSource(expression.arg());
        }
        if (expression.function() != null) {
            return functionSource(expression.function());
        }
        return "";
    }

    private static String expressionArgumentSource(Mf2Message.ExpressionArgument argument) {
        return switch (argument) {
            case Mf2Message.LiteralArgument literal -> quoteLiteralSource(literal.value());
            case Mf2Message.VariableArgument variable -> "$" + variable.name();
        };
    }

    private static String functionSource(Mf2Message.FunctionRef function) {
        StringBuilder source = new StringBuilder(":").append(function.name());
        for (Map.Entry<String, Mf2Message.ExpressionArgument> option : function.options().entrySet()) {
            source.append(' ')
                    .append(option.getKey())
                    .append('=')
                    .append(expressionArgumentSource(option.getValue()));
        }
        return source.toString();
    }

    private static String quoteLiteralSource(String value) {
        StringBuilder source = new StringBuilder("|");
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == '\\' || codePoint == '|') {
                source.append('\\');
            }
            source.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return source.append('|').toString();
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

    private static String partsToString(
            List<Mf2Message.FormattedPart> parts, Mf2BidiIsolation bidiIsolation) {
        StringBuilder output = new StringBuilder();
        for (Mf2Message.FormattedPart part : parts) {
            switch (part) {
                case Mf2Message.FormattedText text -> output.append(text.value());
                case Mf2Message.FormattedFallback fallback -> output.append('{').append(fallback.source()).append('}');
                case Mf2Message.FormattedExpression expression ->
                        output.append(isolateExpression(
                                expression.value(), bidiIsolation, expression.direction()));
                case Mf2Message.FormattedMarkup ignored -> {
                }
            }
        }
        return output.toString();
    }

    private static String isolateExpression(
            String value, Mf2BidiIsolation bidiIsolation, String direction) {
        if (bidiIsolation == Mf2BidiIsolation.DEFAULT) {
            return bidiMarker(direction) + value + "\u2069";
        }
        return value;
    }

    private static char bidiMarker(String direction) {
        return switch (direction == null ? "auto" : direction) {
            case "ltr" -> '\u2066';
            case "rtl" -> '\u2067';
            default -> '\u2068';
        };
    }

    private static boolean isNumericFunction(Mf2Message.FunctionRef function) {
        return function.name().equals("number")
                || function.name().equals("integer")
                || function.name().equals("percent")
                || function.name().equals("offset");
    }

    private static boolean numericSelectUsesVariable(Mf2Message.FunctionRef function) {
        return function.options().get("select") instanceof Mf2Message.VariableArgument;
    }

    private static boolean inheritedExactNumericSource(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return false;
        }
        if (isNumericFunction(source.function()) && "exact".equals(source.optionValue("select", null))) {
            return true;
        }
        return inheritedExactNumericSource(source.inheritedSource());
    }

    private static BidiDirection bidiDirectionForFunction(
            Mf2Message.FunctionRef function, ResolvedFunctionSource source) throws Mf2Exception {
        String value = functionOptionLiteral(function, "u:dir", null);
        if (value != null) {
            return parseBidiDirection(value);
        }
        return bidiDirectionFromSource(source);
    }

    private static BidiDirection bidiDirectionFromSource(ResolvedFunctionSource source)
            throws Mf2Exception {
        if (source == null) {
            return null;
        }
        String value = functionOptionLiteral(source.function(), "u:dir", null);
        if (value != null) {
            return parseBidiDirection(value);
        }
        return bidiDirectionFromSource(source.inherited());
    }

    private static BidiDirection parseBidiDirection(String value) throws Mf2Exception {
        return switch (value) {
            case "auto" -> BidiDirection.AUTO;
            case "ltr" -> BidiDirection.LTR;
            case "rtl" -> BidiDirection.RTL;
            default -> throw new Mf2Exception("bad-option", "u:dir option must be auto, ltr, or rtl.");
        };
    }

    private static String functionOptionLiteral(
            Mf2Message.FunctionRef function, String optionName, String fallback) {
        Mf2Message.ExpressionArgument option = function.options().get(optionName);
        return option instanceof Mf2Message.LiteralArgument literal ? literal.value() : fallback;
    }

    private enum BidiDirection {
        AUTO("auto"),
        LTR("ltr"),
        RTL("rtl");

        private final String name;

        BidiDirection(String name) {
            this.name = name;
        }
    }

    private record SelectorAnnotation(Mf2Message.FunctionRef function, NumberSelect numberSelect) {
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
            return new SelectorAnnotation(function, numberSelect);
        }

        boolean exactMatch() {
            return function.name().equals("string") || (isNumeric() && numberSelect == NumberSelect.EXACT);
        }

        boolean isString() {
            return function.name().equals("string");
        }

        boolean isNumeric() {
            return isNumericFunction(function);
        }

        String operandForSelection(ResolvedValue value) {
            String rendered = value.rendered();
            if (function.name().equals("percent")) {
                if (rendered.endsWith("%")) {
                    return rendered.substring(0, rendered.length() - 1);
                }
                String sourceValue = value.source() == null ? rendered : value.source().value();
                try {
                    return Double.toString(Double.parseDouble(sourceValue) * 100.0);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return rendered;
        }
    }

    private record SelectorValue(
            String rendered,
            String normalizedRendered,
            boolean exactMatch,
            String selectionKey,
            Mf2Message.FunctionRef function,
            ResolvedFunctionSource source) {}

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
