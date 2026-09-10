package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

final class Mf2UnlocalizedNumericFunctions {
    private Mf2UnlocalizedNumericFunctions() {}

    static void registerFormatters(Map<String, Mf2FunctionRegistry.Formatter> formatters) {
        formatters.put("number", Mf2UnlocalizedNumericFunctions::formatNumber);
        formatters.put("percent", Mf2UnlocalizedNumericFunctions::formatPercent);
        formatters.put("integer", Mf2UnlocalizedNumericFunctions::formatInteger);
    }

    private static String formatNumber(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Number function requires a numeric operand.");
        String formatted = formatDecimalWithMaximumFractionDigits(
                value, maximumFractionDigits(call));
        if (signDisplayAlways(call) && value >= 0.0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits(call));
    }

    private static String formatPercent(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Percent function requires a numeric operand.");
        return formatPercentNumber(
                value,
                signDisplayAlways(call),
                minimumFractionDigits(call),
                maximumFractionDigits(call));
    }

    private static String formatInteger(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Integer function requires a numeric operand.");
        return Mf2PortableFunctions.formatIntegerNumber(
                Mf2FunctionSupport.truncateInteger(value), signDisplayAlways(call));
    }

    static String selectionOperand(
            double value,
            String functionName,
            int minimumFractionDigits,
            Integer maximumFractionDigits) throws Mf2Exception {
        if (functionName.equals("integer")) {
            if (value < -0x1.0p63 || value >= 0x1.0p63) {
                throw Mf2FunctionSupport.badSelector("Integer selector is outside the supported signed-64-bit range.");
            }
            return Long.toString(Mf2FunctionSupport.truncateInteger(value));
        }
        if (functionName.equals("percent")) {
            value = scaledPercent(value);
        }
        if (functionName.equals("number") || functionName.equals("percent")) {
            return appendMinimumFractionDigits(
                    formatDecimalWithMaximumFractionDigits(value, maximumFractionDigits),
                    minimumFractionDigits);
        }
        return Double.toString(value);
    }

    static String formatDecimalNumber(double value, boolean signDisplayAlways, int minimumFractionDigits) {
        String formatted = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        if (signDisplayAlways && value >= 0.0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits);
    }

    private static double scaledPercent(double value) throws Mf2Exception {
        double scaled = BigDecimal.valueOf(value).movePointRight(2).doubleValue();
        if (!Double.isFinite(scaled)) throw Mf2Exception.badOperand("Scaled percent operand is outside the supported range.");
        return scaled;
    }

    private static String formatPercentNumber(
            double value,
            boolean signDisplayAlways,
            int minimumFractionDigits,
            Integer maximumFractionDigits) throws Mf2Exception {
        String formatted = formatDecimalWithMaximumFractionDigits(scaledPercent(value), maximumFractionDigits);
        if (signDisplayAlways && value >= 0.0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits) + "%";
    }

    private static String formatDecimalWithMaximumFractionDigits(double value, Integer digits) {
        if (digits == null) {
            return formatDecimalNumber(value, false, 0);
        }
        String formatted = formatFixedFractionDigits(value, digits);
        while (formatted.contains(".") && formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        if (formatted.endsWith(".")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    static String formatFixedFractionDigits(double value, int fractionDigits) {
        return BigDecimal.valueOf(value)
                .setScale(fractionDigits, RoundingMode.HALF_UP)
                .toPlainString();
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
        int minimum = Mf2FunctionSupport.parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
        Integer maximum = maximumFractionDigits(call);
        if (maximum != null && minimum > maximum) throw Mf2FunctionSupport.badOption("minimumFractionDigits must not exceed maximumFractionDigits.");
        return minimum;
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

    private static boolean signDisplayAlways(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        return "always".equals(call.optionValue("signDisplay", null));
    }
}
