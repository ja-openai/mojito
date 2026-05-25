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
        return formatDecimalNumber(value, Mf2PortableFunctions.signDisplayAlways(call.function()), minimumFractionDigits(call));
    }

    private static String formatPercent(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Percent function requires a numeric operand.");
        return formatPercentNumber(
                value,
                Mf2PortableFunctions.signDisplayAlways(call.function()),
                minimumFractionDigits(call),
                maximumFractionDigits(call));
    }

    private static String formatInteger(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        double value = Mf2FunctionSupport.parseCallDecimal(call, "Integer function requires a numeric operand.");
        return Mf2PortableFunctions.formatIntegerNumber(
                (long) value, Mf2PortableFunctions.signDisplayAlways(call.function()));
    }

    static String formatDecimalNumber(double value, boolean signDisplayAlways, int minimumFractionDigits) {
        String formatted = Double.toString(value);
        if (formatted.endsWith(".0")) {
            formatted = formatted.substring(0, formatted.length() - 2);
        }
        if (signDisplayAlways && value >= 0.0) {
            formatted = "+" + formatted;
        }
        return appendMinimumFractionDigits(formatted, minimumFractionDigits);
    }

    private static String formatPercentNumber(
            double value,
            boolean signDisplayAlways,
            int minimumFractionDigits,
            Integer maximumFractionDigits) {
        String formatted = formatDecimalWithMaximumFractionDigits(value * 100.0, maximumFractionDigits);
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
}
