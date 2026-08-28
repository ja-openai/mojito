package com.box.l10n.mojito.mf2;

import java.util.Map;

final class Mf2PortableFunctions {
    private Mf2PortableFunctions() {}

    static void registerFormatters(Map<String, Mf2FunctionRegistry.Formatter> formatters) {
        formatters.put("string", call -> call.value());
        formatters.put("offset", Mf2PortableFunctions::formatOffset);
    }

    static void registerSelectors(Map<String, Mf2FunctionRegistry.Selector> selectors) {
        selectors.put("number", Mf2PortableFunctions::selectNumber);
        selectors.put("percent", Mf2PortableFunctions::selectPercent);
        selectors.put("integer", Mf2PortableFunctions::selectInteger);
        selectors.put("offset", Mf2PortableFunctions::selectOffset);
    }

    private static Integer selectNumber(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Number selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Number selector requires a numeric operand.");
        validateNumericVariantKey(match.key());
        return match.key().equals(numericMatchOperand(match, value, "number")) ? 2 : null;
    }

    private static Integer selectPercent(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Percent selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Percent selector requires a numeric operand.");
        validateNumericVariantKey(match.key());
        return match.key().equals(numericMatchOperand(match, value, "percent")) ? 2 : null;
    }

    private static Integer selectInteger(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Integer selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
        validateNumericVariantKey(match.key());
        return match.key().equals(numericMatchOperand(match, value, "integer")) ? 2 : null;
    }

    private static String formatOffset(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String add = call.optionValue("add", null);
        String subtract = call.optionValue("subtract", null);
        validateOffsetOptions(add, subtract);
        String operand = Mf2FunctionSupport.numericSourceOperand(call.inheritedSource());
        if (operand == null) {
            operand = call.value();
        }
        String result = Mf2FunctionSupport.adjustedOffsetOperand(operand, add, subtract);
        if (result == null) {
            throw Mf2Exception.badOperand("Offset function requires a numeric operand.");
        }
        return "always".equals(call.optionValue("signDisplay", null))
                        && !result.startsWith("-")
                ? "+" + result
                : result;
    }

    private static Integer selectOffset(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        double value = parseMatchDecimal(match, "Offset selector requires a numeric operand.");
        validateNumericVariantKey(match.key());
        return match.key().equals(numericMatchOperand(match, value, "number")) ? 2 : null;
    }

    private static String numericMatchOperand(
            Mf2FunctionRegistry.FunctionMatch match, double value, String functionName)
            throws Mf2Exception {
        int minimumFractionDigits = Mf2FunctionSupport.parseNonNegativeOption(
                match.optionValue("minimumFractionDigits", "0"),
                "minimumFractionDigits option must be a non-negative integer.");
        String maximum = match.optionValue("maximumFractionDigits", null);
        Integer maximumFractionDigits = maximum == null
                ? null
                : Mf2FunctionSupport.parseNonNegativeOption(
                        maximum,
                        "maximumFractionDigits option must be a non-negative integer.");
        return Mf2UnlocalizedNumericFunctions.selectionOperand(
                value, functionName, minimumFractionDigits, maximumFractionDigits);
    }

    private static void validateNumericVariantKey(String key) throws Mf2Exception {
        if (key.equals("zero")
                || key.equals("one")
                || key.equals("two")
                || key.equals("few")
                || key.equals("many")
                || key.equals("other")
                || Mf2FunctionSupport.isDecimalLiteral(key)) {
            return;
        }
        throw Mf2Exception.badVariantKey(
                "Numeric selector keys must be number literals or plural keywords.");
    }

    private static double parseMatchDecimal(Mf2FunctionRegistry.FunctionMatch match, String message)
            throws Mf2Exception {
        Double parsed = Mf2FunctionSupport.parseSourceDecimal(match.inheritedSource());
        if (parsed == null) {
            parsed = Mf2FunctionSupport.parseDecimalNumber(match.value());
        }
        if (parsed == null) {
            throw Mf2FunctionSupport.badSelector(message);
        }
        return parsed;
    }

    private static boolean invalidNumericSelector(
            Mf2Message.FunctionRef function,
            Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        return numericSelectUsesVariable(function)
                || (functionOptionLiteral(function, "select", null) == null
                        || !functionOptionLiteral(function, "select", null).equals("exact"))
                        && "exact".equals(Mf2FunctionSupport.inheritedNumericOptionValue(
                                function.name(), source, "select", null));
    }

    private static boolean numericSelectUsesVariable(Mf2Message.FunctionRef function) {
        return function.options().get("select") instanceof Mf2Message.VariableArgument;
    }

    private static String functionOptionLiteral(Mf2Message.FunctionRef function, String name, String fallback) {
        Mf2Message.ExpressionArgument option = function.options().get(name);
        return option instanceof Mf2Message.LiteralArgument literal ? literal.value() : fallback;
    }

    private static void validateOffsetOptions(String add, String subtract)
            throws Mf2Exception {
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2FunctionSupport.badOption("Offset function requires exactly one of add or subtract.");
        }
        if (add != null) {
            Long value = parseInteger(add);
            if (value == null) {
                throw Mf2FunctionSupport.badOption("Offset add option must be an integer.");
            }
            return;
        }
        Long value = parseInteger(subtract);
        if (value == null) {
            throw Mf2FunctionSupport.badOption("Offset subtract option must be an integer.");
        }
    }

    private static Long parseInteger(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static String formatIntegerNumber(long value, boolean signDisplayAlways) {
        return signDisplayAlways && value >= 0 ? "+" + value : Long.toString(value);
    }
}
