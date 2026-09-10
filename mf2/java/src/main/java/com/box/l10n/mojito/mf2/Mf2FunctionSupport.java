package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;
import java.util.ArrayDeque;

public final class Mf2FunctionSupport {
    private Mf2FunctionSupport() {}

    /** Truncates a finite binary numeric operand within the supported signed-64-bit range. */
    public static long truncateInteger(double value) throws Mf2Exception {
        if (!Double.isFinite(value) || value < -0x1.0p63 || value >= 0x1.0p63) {
            throw Mf2Exception.badOperand("Integer operand is outside the supported signed-64-bit range.");
        }
        return (long) value;
    }

    static double parseCallDecimal(Mf2FunctionRegistry.FunctionCall call, String message)
            throws Mf2Exception {
        Double parsed = parseSourceDecimal(call.inheritedSource());
        if (parsed == null) {
            parsed = parseDecimalNumber(call.value());
        }
        if (parsed == null) {
            throw Mf2Exception.badOperand(message);
        }
        return parsed;
    }

    static Double parseSourceDecimal(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null || !isDecimalSourceFunction(source.function())) {
            return null;
        }
        return parseDecimalNumber(numericSourceOperand(source));
    }

    public static String numericSourceOperand(
            Mf2FunctionRegistry.FunctionSourceRef source) throws Mf2Exception {
        if (source == null || !isDecimalSourceFunction(source.function())) {
            return null;
        }
        return numericSourceOperandChain(source);
    }

    static String inheritedNumericOptionValue(
            String targetFunction,
            Mf2FunctionRegistry.FunctionSourceRef source,
            String optionName,
            String fallback)
            throws Mf2Exception {
        String result = null;
        var visited = new java.util.ArrayList<java.util.Map.Entry<Mf2SourceCache, String>>();
        while (source != null && !blocksInheritedOption(targetFunction, optionName)) {
            Mf2SourceCache cache = Mf2SourceCache.of(source);
            String key = targetFunction + ":" + optionName;
            if (cache != null && cache.cacheable) {
                if (cache.inheritedOptions.containsKey(key)) {
                    result = cache.inheritedOptions.get(key);
                    break;
                }
                visited.add(java.util.Map.entry(cache, key));
            }
            String sourceFunction = source.function().name();
            if (!canInheritOptionsFrom(targetFunction, sourceFunction)
                    || blocksInheritedOption(sourceFunction, optionName)) break;
            if (source.function().options().containsKey(optionName)) {
                result = source.optionValue(optionName, null);
                break;
            }
            targetFunction = sourceFunction;
            source = source.inheritedSource();
        }
        for (var item : visited) item.getKey().remember(item.getValue(), result);
        return result == null ? fallback : result;
    }

    public static String resolvedCurrencyCode(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String inherited = inheritedCurrencyCode(call.inheritedSource());
        boolean hasDirectOption = call.function().options().containsKey("currency");
        if (inherited != null && hasDirectOption) {
            throw new Mf2Exception(
                    "bad-option",
                    "Currency option cannot override an existing currency operand.");
        }
        if (inherited != null) {
            return inherited;
        }
        return hasDirectOption ? call.optionValue("currency", null) : null;
    }

    private static String numericSourceOperandChain(
            Mf2FunctionRegistry.FunctionSourceRef source) throws Mf2Exception {
        ArrayDeque<Mf2FunctionRegistry.FunctionSourceRef> chain = new ArrayDeque<>();
        String operand = null;
        for (var current = source; current != null; current = current.inheritedSource()) {
            Mf2SourceCache cache = Mf2SourceCache.of(current);
            if (cache != null && cache.cacheable && cache.operandResolved) {
                operand = cache.operand;
                break;
            }
            chain.push(current);
        }
        while (!chain.isEmpty()) {
            var current = chain.pop();
            if (operand == null) operand = current.value();
            if (isDecimalSourceFunction(current.function())) {
                Double parsed = parseDecimalNumber(operand);
                if (parsed == null) operand = null;
                else if (current.function().name().equals("integer")) operand = Long.toString(truncateInteger(parsed));
                else if (current.function().name().equals("offset")) operand = adjustedOffsetOperand(operand,
                        current.optionValue("add", null), current.optionValue("subtract", null));
            }
            Mf2SourceCache cache = Mf2SourceCache.of(current);
            if (cache != null && cache.cacheable) {
                cache.operand = operand;
                cache.operandResolved = true;
            }
        }
        return operand;
    }

    static String adjustedOffsetOperand(String operand, String add, String subtract) {
        if (parseDecimalNumber(operand) == null
                || (add == null) == (subtract == null)) {
            return null;
        }
        Long delta = parseInteger(add == null ? subtract : add);
        if (delta == null) {
            return null;
        }
        // Bound expansion before arithmetic; a small exponent must not allocate an enormous scale.
        BigDecimal value;
        try {
            if (operand.length() > 8192) return null;
            value = new BigDecimal(operand);
        } catch (NumberFormatException error) {
            return null;
        }
        if (!boundedDecimal(value)) return null;
        BigDecimal adjustment = BigDecimal.valueOf(delta);
        BigDecimal result = add == null
                ? value.subtract(adjustment)
                : value.add(adjustment);
        return boundedDecimal(result) ? result.stripTrailingZeros().toPlainString() : null;
    }

    private static boolean boundedDecimal(BigDecimal value) {
        long precision = value.precision();
        long scale = value.scale();
        return precision <= 4096 && Math.max(precision - scale, 1) + Math.max(scale, 0) <= 4096;
    }

    static Double parseDecimalNumber(String value) {
        if (!isWellFormedDecimalLiteral(value)) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static boolean isDecimalLiteral(String value) {
        return isWellFormedDecimalLiteral(value);
    }

    static int parseNonNegativeOption(String value, String message)
            throws Mf2Exception {
        if (value.isEmpty() || !value.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw badOption(message);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 1000) {
                throw badOption(message);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw badOption(message);
        }
    }

    static boolean isNumericFunction(Mf2Message.FunctionRef function) {
        return function.name().equals("number")
                || function.name().equals("integer")
                || function.name().equals("percent")
                || function.name().equals("offset");
    }

    static Mf2Exception badOption(String message) {
        return new Mf2Exception("bad-option", message);
    }

    static Mf2Exception badSelector(String message) {
        return new Mf2Exception("bad-selector", message);
    }

    private static boolean isWellFormedDecimalLiteral(String value) {
        int index = 0;
        if (index < value.length() && value.charAt(index) == '-') {
            index++;
        }
        if (index >= value.length()) {
            return false;
        }
        char first = value.charAt(index);
        if (first == '0') {
            index++;
        } else if (first >= '1' && first <= '9') {
            index++;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
        } else {
            return false;
        }
        if (index < value.length() && value.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (index == fractionStart) {
                return false;
            }
        }
        if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
                index++;
            }
            int exponentStart = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (index == exponentStart) {
                return false;
            }
        }
        return index == value.length();
    }

    private static boolean isDecimalSourceFunction(Mf2Message.FunctionRef function) {
        return isNumericFunction(function) || function.name().equals("currency");
    }

    private static boolean canInheritOptionsFrom(
            String targetFunction, String sourceFunction) {
        if (targetFunction.equals("currency")) {
            return sourceFunction.equals("currency");
        }
        return isNumericFunctionName(targetFunction)
                && isNumericFunctionName(sourceFunction);
    }

    private static boolean isNumericFunctionName(String functionName) {
        return functionName.equals("number")
                || functionName.equals("integer")
                || functionName.equals("percent")
                || functionName.equals("offset");
    }

    private static boolean blocksInheritedOption(
            String functionName, String optionName) {
        if (functionName.equals("integer")) {
            return optionName.equals("minimumFractionDigits")
                    || optionName.equals("maximumFractionDigits")
                    || optionName.equals("minimumSignificantDigits");
        }
        if (functionName.equals("percent")) {
            return optionName.equals("minimumIntegerDigits")
                    || optionName.equals("roundingIncrement")
                    || optionName.equals("select");
        }
        return functionName.equals("offset")
                && (optionName.equals("add") || optionName.equals("subtract"));
    }

    private static String inheritedCurrencyCode(
            Mf2FunctionRegistry.FunctionSourceRef source) throws Mf2Exception {
        Mf2SourceCache cache = Mf2SourceCache.of(source);
        if (cache != null && cache.cacheable) return inheritedNumericOptionValue("currency", source, "currency", null);
        while (source != null && source.function().name().equals("currency")) {
            String currency = source.optionValue("currency", null);
            if (currency != null) return currency;
            source = source.inheritedSource();
        }
        return null;
    }

    private static Long parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
