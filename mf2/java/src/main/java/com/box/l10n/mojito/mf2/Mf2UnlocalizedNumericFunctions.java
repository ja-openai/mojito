package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

final class Mf2UnlocalizedNumericFunctions {
    private static final int MAX_DECIMAL_OUTPUT_CHARS = 1000;

    private Mf2UnlocalizedNumericFunctions() {}

    static void registerFormatters(Map<String, Mf2FunctionRegistry.Formatter> formatters) {
        formatters.put("number", Mf2UnlocalizedNumericFunctions::formatNumber);
        formatters.put("percent", Mf2UnlocalizedNumericFunctions::formatPercent);
        formatters.put("integer", Mf2UnlocalizedNumericFunctions::formatInteger);
    }

    private static String formatNumber(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String message = "Number function requires a numeric operand.";
        BigDecimal value = Mf2FunctionSupport.parseCallDecimalOperand(call, message);
        int minimumFractionDigits = minimumFractionDigits(call);
        Integer maximumFractionDigits = maximumFractionDigits(call);
        validateFractionDigits(minimumFractionDigits, maximumFractionDigits);
        BigDecimal rounded = roundDecimalWithMaximumFractionDigits(value, maximumFractionDigits);
        ensureDecimalOutputBounded(rounded, minimumFractionDigits, message);
        return formatDecimalNumber(
                rounded, Mf2PortableFunctions.signDisplayAlways(call.function()), minimumFractionDigits);
    }

    private static String formatPercent(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String message = "Percent function requires a numeric operand.";
        BigDecimal value = Mf2FunctionSupport.parseCallDecimalOperand(call, message);
        int minimumFractionDigits = minimumFractionDigits(call);
        Integer maximumFractionDigits = maximumFractionDigits(call);
        validateFractionDigits(minimumFractionDigits, maximumFractionDigits);
        BigDecimal percentValue = roundDecimalWithMaximumFractionDigits(
                value.movePointRight(2), maximumFractionDigits);
        ensureDecimalOutputBounded(percentValue, minimumFractionDigits, message);
        String formatted = formatDecimalNumber(percentValue, false, 0);
        if (Mf2PortableFunctions.signDisplayAlways(call.function()) && value.signum() >= 0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits) + "%";
    }

    private static String formatInteger(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String message = "Integer function requires a numeric operand.";
        BigDecimal integer = Mf2FunctionSupport.parseCallDecimalOperand(call, message)
                .setScale(0, RoundingMode.DOWN);
        ensureDecimalOutputBounded(integer, 0, message);
        return formatDecimalNumber(integer, Mf2PortableFunctions.signDisplayAlways(call.function()), 0);
    }

    static String formatDecimalNumber(double value, boolean signDisplayAlways, int minimumFractionDigits) {
        return formatDecimalNumber(BigDecimal.valueOf(value), signDisplayAlways, minimumFractionDigits);
    }

    static String formatDecimalNumber(
            BigDecimal value, boolean signDisplayAlways, int minimumFractionDigits) {
        BigDecimal normalized = normalizeDecimal(value);
        String formatted = normalized.toPlainString();
        if (signDisplayAlways && normalized.signum() >= 0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits);
    }

    private static BigDecimal roundDecimalWithMaximumFractionDigits(
            BigDecimal value, Integer maximumFractionDigits) {
        if (maximumFractionDigits == null) {
            return value;
        }
        return value.setScale(maximumFractionDigits, RoundingMode.HALF_UP);
    }

    static String formatFixedFractionDigits(double value, int fractionDigits) {
        return BigDecimal.valueOf(value)
                .setScale(fractionDigits, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static void ensureDecimalOutputBounded(
            BigDecimal value, int minimumFractionDigits, String message) throws Mf2Exception {
        if (estimatedDecimalOutputChars(value, minimumFractionDigits) > MAX_DECIMAL_OUTPUT_CHARS) {
            throw Mf2Exception.badOperand(message);
        }
    }

    private static int estimatedDecimalOutputChars(BigDecimal value, int minimumFractionDigits) {
        BigDecimal normalized = normalizeDecimal(value);
        int sign = normalized.signum() < 0 ? 1 : 0;
        int precision = normalized.precision();
        int scale = normalized.scale();
        if (scale <= 0) {
            return sign + precision - scale;
        }
        int integerDigits = Math.max(precision - scale, 1);
        int fractionDigits = Math.max(scale, minimumFractionDigits);
        return sign + integerDigits + (fractionDigits > 0 ? 1 + fractionDigits : 0);
    }

    private static String appendMinimumFractionDigits(String formatted, int minimumFractionDigits) {
        if (minimumFractionDigits == 0) {
            return formatted;
        }
        int dot = formatted.indexOf('.');
        int fractionDigits = dot < 0 ? 0 : formatted.length() - dot - 1;
        StringBuilder output = new StringBuilder(formatted);
        if (fractionDigits == 0) {
            output.append('.');
        }
        for (int index = fractionDigits; index < minimumFractionDigits; index++) {
            output.append('0');
        }
        return output.toString();
    }

    private static int minimumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("minimumFractionDigits", null);
        if (value == null) {
            return 0;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(
                value, "minimumFractionDigits option must be a non-negative integer.");
    }

    private static Integer maximumFractionDigits(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String value = call.optionValue("maximumFractionDigits", null);
        if (value == null) {
            return null;
        }
        return Mf2FunctionSupport.parseNonNegativeOption(
                value, "maximumFractionDigits option must be a non-negative integer.");
    }

    private static void validateFractionDigits(
            int minimumFractionDigits, Integer maximumFractionDigits) throws Mf2Exception {
        if (maximumFractionDigits != null && minimumFractionDigits > maximumFractionDigits) {
            throw Mf2FunctionSupport.badOption(
                    "maximumFractionDigits must be greater than or equal to minimumFractionDigits.");
        }
    }
}
