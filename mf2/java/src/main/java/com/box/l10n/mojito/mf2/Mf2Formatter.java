package com.box.l10n.mojito.mf2;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Mf2Formatter {
    private static final Pattern SOURCE_DECIMAL_PATTERN =
            Pattern.compile("^(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$");
    private static final int MAX_SOURCE_DECIMAL_EXPONENT = 1_000_000;
    private static final int MAX_SOURCE_DECIMAL_KEY_LENGTH = 4096;

    private Mf2Formatter() {}

    public static Mf2FormatResult formatMessage(
            Mf2Message message, Map<String, ?> arguments, Mf2FormatOptions options)
            throws Mf2Exception {
        Mf2PartsResult result = formatMessageToParts(message, arguments, options);
        return new Mf2FormatResult(
                partsToString(result.parts(), options.bidiIsolation()), result.errors());
    }

    public static Mf2PartsResult formatMessageToParts(
            Mf2Message message, Map<String, ?> arguments, Mf2FormatOptions options)
            throws Mf2Exception {
        validate(message);
        FormatContext context =
                new FormatContext(snapshotArguments(arguments), options, true);
        context.apply(message.declarations());
        List<Mf2FormattedPart> parts = switch (message) {
            case Mf2Message.PatternMessage patternMessage -> context.formatPatternToParts(patternMessage.pattern());
            case Mf2Message.SelectMessage selectMessage -> context.formatSelectToParts(selectMessage.selectors(), selectMessage.variants());
        };
        return new Mf2PartsResult(parts, context.errors);
    }

    private static void validate(Mf2Message message) throws Mf2Exception {
        validateDeclarations(message.declarations());
        switch (message) {
            case Mf2Message.PatternMessage patternMessage -> validatePattern(patternMessage.pattern());
            case Mf2Message.SelectMessage selectMessage -> {
                validateSelectorAnnotations(selectMessage.declarations(), selectMessage.selectors());
                for (Mf2Message.Variant variant : selectMessage.variants()) {
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
        private final Mf2RecoveryHandler onMissingArgument;
        private final Mf2RecoveryHandler onFormatError;
        private final boolean fallback;

        FormatContext(ArgumentValues arguments, Mf2FormatOptions options, boolean fallback) {
            this.arguments = arguments;
            this.locale = options.locale();
            this.functions = options.functions();
            this.onMissingArgument = options.onMissingArgument();
            this.onFormatError = options.onFormatError();
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
                            putLocal(local.name(), new ResolvedValue(output.rawValue(), output.source()));
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
            recordFunctionResolutionErrors(function, inputValue.source());
            try {
                String rendered = inputValue.rendered();
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

        List<Mf2FormattedPart> formatSelectToParts(
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
                    if (annotation != null
                            && (isFailedLocal(selector.name())
                                    || functions.hasSelector(annotation.function()))) {
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
            SelectorAnnotation annotation = selectorAnnotation(selector.name());
            try {
                String rendered = value.rendered();
                recordSelectorResolutionErrors(annotation);
                return new SelectorValue(
                        rendered,
                        annotation != null && annotation.isString() ? normalizeStringKey(rendered) : null,
                        annotation == null || annotation.exactMatch(),
                        selectionKey(annotation, value),
                        annotation == null ? null : annotation.function(),
                        value.source());
            } catch (Mf2Exception error) {
                if (!fallback) {
                    throw error;
                }
                errors.add(fallbackError(error));
                if (annotation != null) {
                    errors.add(Mf2FunctionSupport.badSelector("Selector operand is not available."));
                }
                return new SelectorValue(
                        "",
                        annotation != null && annotation.isString() ? normalizeStringKey("") : null,
                        false,
                        null,
                        annotation == null ? null : annotation.function(),
                        value.source());
            }
        }

        List<Mf2FormattedPart> formatPatternToParts(List<Mf2Message.PatternPart> pattern)
                throws Mf2Exception {
            List<Mf2FormattedPart> output = new ArrayList<>(pattern.size());
            for (Mf2Message.PatternPart part : pattern) {
                switch (part) {
                    case Mf2Message.TextPart text -> output.add(new Mf2FormattedPart.Text(text.value()));
                    case Mf2Message.ExpressionPart expression -> {
                        ExpressionOutput rendered = formatExpressionOutput(expression.expression());
                        if (rendered.hadError()) {
                            String source = rendered.fallbackSource() == null
                                    ? fallbackSource(expression.expression())
                                    : rendered.fallbackSource();
                            output.add(new Mf2FormattedPart.Fallback(source, rendered.value()));
                        } else {
                            output.add(new Mf2FormattedPart.Expression(
                                    rendered.value(),
                                    expression.expression().attributes(),
                                    rendered.directionName()));
                        }
                    }
                    case Mf2Message.MarkupPart markup -> {
                        recordMarkupResolutionErrors(markup.markup());
                        output.add(new Mf2FormattedPart.Markup(
                                markup.markup().kind(),
                                markup.markup().name(),
                                markup.markup().options(),
                                markup.markup().attributes()));
                    }
                }
            }
            return output;
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
                            Mf2Exception error = Mf2Exception.unresolvedVariable(variable.name());
                            if (!isFailedLocal(variable.name())) {
                                errors.add(error);
                            }
                            if (expression.function() != null) {
                                errors.add(Mf2Exception.badOperand("Function operand is not available."));
                            }
                            String fallbackSource = fallbackSource(expression);
                            value = recoverMissingArgument(
                                    expression, variable.name(), fallbackSource, error);
                            rawValue = value;
                            break;
                        }
                        throw Mf2Exception.missingArgument(variable.name());
                    }
                    ResolvedValue resolved = value(variable.name());
                    rawValue = resolved.rawValue();
                    source = resolved.source();
                    try {
                        value = resolved.rendered();
                    } catch (Mf2Exception error) {
                        if (!fallback) {
                            throw error;
                        }
                        Mf2Exception recoverable = fallbackError(error);
                        errors.add(recoverable);
                        String fallbackSource = fallbackSource(expression);
                        return new ExpressionOutput(
                                recoverFormatError(expression, fallbackSource, recoverable),
                                true,
                                null,
                                null,
                                fallbackSource);
                    }
                }
            }

            if (hadError) {
                return new ExpressionOutput(value, true, null, null, fallbackSource(expression));
            }

            Mf2Message.FunctionRef function = expression.function();
            if (function == null) {
                try {
                    return new ExpressionOutput(
                            primitiveValueToString(rawValue, value),
                            rawValue,
                            false,
                            source,
                            bidiDirectionFromSource(source));
                } catch (Mf2Exception error) {
                    if (!fallback) {
                        throw error;
                    }
                    Mf2Exception recoverable = fallbackError(error);
                    errors.add(recoverable);
                    String fallbackSource = fallbackSource(expression);
                    return new ExpressionOutput(
                            recoverFormatError(expression, fallbackSource, recoverable),
                            true,
                            null,
                            null,
                            fallbackSource);
                }
            }
            recordFunctionResolutionErrors(function, source);
            try {
                BidiDirection direction = bidiDirectionForFunction(function, source);
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
                Mf2Exception recoverable = fallbackError(error);
                errors.add(recoverable);
                String fallbackSource = fallbackSource(expression);
                return new ExpressionOutput(
                        recoverFormatError(expression, fallbackSource, recoverable),
                        true,
                        null,
                        null,
                        fallbackSource);
            }
        }

        private String primitiveValueToString(Object rawValue, String fallbackValue)
                throws Mf2Exception {
            if (rawValue instanceof Number) {
                return Mf2NumberCore.format(
                        rawValue, Mf2NumberCore.options().locale(locale).build());
            }
            return fallbackValue;
        }

        private String recoverMissingArgument(
                Mf2Message.Expression expression,
                String variableName,
                String fallbackSource,
                Mf2Exception error) {
            return recover(onMissingArgument, new Mf2RecoveryContext(
                    error.code(),
                    error.getMessage(),
                    locale,
                    variableName,
                    expression.function() == null ? null : expression.function().name(),
                    expressionSource(expression),
                    fallbackValue(fallbackSource),
                    error));
        }

        private String recoverFormatError(
                Mf2Message.Expression expression, String fallbackSource, Mf2Exception error) {
            String variableName = expression.arg() instanceof Mf2Message.VariableArgument variable
                    ? variable.name()
                    : null;
            return recover(onFormatError, new Mf2RecoveryContext(
                    error.code(),
                    error.getMessage(),
                    locale,
                    variableName,
                    expression.function() == null ? null : expression.function().name(),
                    expressionSource(expression),
                    fallbackValue(fallbackSource),
                    error));
        }

        private String recover(Mf2RecoveryHandler handler, Mf2RecoveryContext context) {
            String value = handler.recover(context);
            return value == null ? context.fallbackValue() : value;
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
                    yield optionValueToString(value(variable.name()).rawValue());
                }
            };
        }

        private boolean exactMatch(String selectorName) {
            SelectorAnnotation annotation = selectorAnnotation(selectorName);
            return annotation == null || annotation.exactMatch();
        }

        private String selectionKey(SelectorAnnotation annotation, ResolvedValue value)
                throws Mf2Exception {
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
                    if (selector.exactMatch()
                            && numericLiteralKeyMatchesSource(literal.value(), selector)) {
                        yield 3;
                    }
                    if (selector.exactMatch()
                            && (selector.function() == null || !isNumericFunction(selector.function()))
                            && literalKeyMatches(literal.value(), selector)) {
                        yield 2;
                    }
                    if (literal.value().equals(selector.selectionKey())) {
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

    private record ExpressionOutput(
            String value,
            Object rawValue,
            boolean hadError,
            ResolvedFunctionSource source,
            BidiDirection direction,
            String fallbackSource) {
        ExpressionOutput(
                String value,
                Object rawValue,
                boolean hadError,
                ResolvedFunctionSource source,
                BidiDirection direction) {
            this(value, rawValue, hadError, source, direction, null);
        }

        ExpressionOutput(
                String value, boolean hadError, ResolvedFunctionSource source, BidiDirection direction) {
            this(value, value, hadError, source, direction, null);
        }

        ExpressionOutput(
                String value,
                boolean hadError,
                ResolvedFunctionSource source,
                BidiDirection direction,
                String fallbackSource) {
            this(value, value, hadError, source, direction, fallbackSource);
        }

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

        String rendered() throws Mf2Exception {
            return operandValueToString(rawValue);
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

    private static boolean numericLiteralKeyMatchesSource(String value, SelectorValue selector) {
        String sourceKey = preferredNumericSourceKey(selector);
        return sourceKey != null
                && value.equals(sourceKey)
                && Mf2FunctionSupport.parseDecimalOperand(value) != null;
    }

    private static String preferredNumericSourceKey(SelectorValue selector) {
        if (selector.function() == null) {
            return null;
        }
        String functionName = selector.function().name();
        if (!functionName.equals("number") && !functionName.equals("percent")) {
            return null;
        }
        String sourceValue = numericSourceValue(selector.source(), functionName);
        SourceDecimal operand = parseSourceDecimal(sourceValue);
        if (operand == null) {
            return null;
        }
        if (functionName.equals("percent")) {
            return renderSourceDecimal(
                    new SourceDecimal(
                            operand.negative(),
                            operand.digits(),
                            operand.scale() - 2,
                            operand.hasExponent()),
                    false);
        }
        return operand.hasExponent() ? renderSourceDecimal(operand, true) : sourceValue;
    }

    private static String numericSourceValue(ResolvedFunctionSource source, String functionName) {
        for (ResolvedFunctionSource current = source; current != null; current = current.inherited()) {
            if (current.function().name().equals(functionName)) {
                return current.value();
            }
        }
        return null;
    }

    private record SourceDecimal(boolean negative, String digits, int scale, boolean hasExponent) {}

    private static SourceDecimal parseSourceDecimal(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = SOURCE_DECIMAL_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        Integer exponent = parseSourceExponent(matcher.group(4) == null ? "" : matcher.group(4));
        if (exponent == null) {
            return null;
        }
        String fraction = matcher.group(3) == null ? "" : matcher.group(3);
        String digits = (matcher.group(2) + fraction).replaceFirst("^0+", "");
        if (digits.isEmpty()) {
            digits = "0";
        }
        return new SourceDecimal(
                matcher.group(1).equals("-") && !digits.equals("0"),
                digits,
                fraction.length() - exponent,
                matcher.group(4) != null);
    }

    private static Integer parseSourceExponent(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        boolean negative = value.startsWith("-");
        String unsigned = negative || value.startsWith("+") ? value.substring(1) : value;
        String digits = unsigned.replaceFirst("^0+", "");
        if (digits.isEmpty()) {
            digits = "0";
        }
        if (digits.length() > 7) {
            return null;
        }
        int parsed = Integer.parseInt(digits);
        if (parsed > MAX_SOURCE_DECIMAL_EXPONENT) {
            return null;
        }
        return negative ? -parsed : parsed;
    }

    private static String renderSourceDecimal(SourceDecimal operand, boolean trimFractionZeros) {
        int extraLength =
                operand.scale() > operand.digits().length()
                        ? operand.scale() - operand.digits().length()
                        : Math.max(-operand.scale(), 0);
        if (operand.digits().length() + extraLength + 2 > MAX_SOURCE_DECIMAL_KEY_LENGTH) {
            return null;
        }
        String text;
        if (operand.scale() <= 0) {
            text = operand.digits() + "0".repeat(-operand.scale());
        } else if (operand.scale() >= operand.digits().length()) {
            text = "0." + "0".repeat(operand.scale() - operand.digits().length()) + operand.digits();
        } else {
            int split = operand.digits().length() - operand.scale();
            text = operand.digits().substring(0, split) + "." + operand.digits().substring(split);
        }
        if (trimFractionZeros && text.contains(".")) {
            text = text.replaceFirst("\\.?0+$", "");
        }
        return operand.negative() ? "-" + text : text;
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
            return functionNameSource(expression.function());
        }
        return "";
    }

    private static String fallbackValue(String source) {
        return "{" + source + "}";
    }

    private static String expressionSource(Mf2Message.Expression expression) {
        List<String> items = new ArrayList<>(2);
        if (expression.arg() != null) {
            items.add(expressionArgumentSource(expression.arg()));
        }
        if (expression.function() != null) {
            items.add(functionSource(expression.function()));
        }
        return "{" + String.join(" ", items) + "}";
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

    private static String functionNameSource(Mf2Message.FunctionRef function) {
        return ":" + function.name();
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

    private static String operandValueToString(Object value) throws Mf2Exception {
        try {
            return valueToString(value);
        } catch (RuntimeException error) {
            throw Mf2Exception.badOperand("Value could not be rendered.");
        }
    }

    private static String optionValueToString(Object value) throws Mf2Exception {
        try {
            return valueToString(value);
        } catch (RuntimeException error) {
            throw Mf2FunctionSupport.badOption("Function option value could not be rendered.");
        }
    }

    private static String partsToString(
            List<Mf2FormattedPart> parts, Mf2BidiIsolation bidiIsolation) {
        StringBuilder output = new StringBuilder();
        for (Mf2FormattedPart part : parts) {
            switch (part) {
                case Mf2FormattedPart.Text text -> output.append(text.value());
                case Mf2FormattedPart.Fallback fallback -> output.append(fallback.value());
                case Mf2FormattedPart.Expression expression ->
                        output.append(isolateExpression(
                                expression.value(), bidiIsolation, expression.direction()));
                case Mf2FormattedPart.Markup ignored -> {
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

        String operandForSelection(ResolvedValue value) throws Mf2Exception {
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
            if (function.name().equals("number")) {
                return value.source() == null ? rendered : value.source().value();
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
