package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;

public final class Mf2FunctionSupport {
    private Mf2FunctionSupport() {}

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
        if (source == null || blocksInheritedOption(targetFunction, optionName)) {
            return fallback;
        }
        String sourceFunction = source.function().name();
        if (!canInheritOptionsFrom(targetFunction, sourceFunction)
                || blocksInheritedOption(sourceFunction, optionName)) {
            return fallback;
        }
        if (source.function().options().containsKey(optionName)) {
            return source.optionValue(optionName, fallback);
        }
        return inheritedNumericOptionValue(
                sourceFunction, source.inheritedSource(), optionName, fallback);
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
        if (source == null) {
            return null;
        }
        String operand = numericSourceOperandChain(source.inheritedSource());
        if (operand == null) {
            operand = source.value();
        }
        String functionName = source.function().name();
        if (!isDecimalSourceFunction(source.function())) {
            return operand;
        }
        Double parsed = parseDecimalNumber(operand);
        if (parsed == null) {
            return null;
        }
        if (functionName.equals("integer")) {
            return Long.toString((long) parsed.doubleValue());
        }
        if (functionName.equals("offset")) {
            String add = source.optionValue("add", null);
            String subtract = source.optionValue("subtract", null);
            return adjustedOffsetOperand(operand, add, subtract);
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
        BigDecimal value = new BigDecimal(operand);
        BigDecimal adjustment = BigDecimal.valueOf(delta);
        BigDecimal result = add == null
                ? value.subtract(adjustment)
                : value.add(adjustment);
        return result.stripTrailingZeros().toPlainString();
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
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw badOption(message);
        }
        try {
            return Integer.parseInt(value);
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
        if (source == null || !source.function().name().equals("currency")) {
            return null;
        }
        String currency = source.optionValue("currency", null);
        if (currency != null) {
            return currency;
        }
        return inheritedCurrencyCode(source.inheritedSource());
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
