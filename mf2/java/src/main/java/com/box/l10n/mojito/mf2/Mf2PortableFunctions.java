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
        Double key = Mf2FunctionSupport.parseDecimalNumber(match.key());
        return key != null && Double.compare(value, key) == 0 ? 1 : null;
    }

    private static Integer selectPercent(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Percent selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100.0;
        Double key = Mf2FunctionSupport.parseDecimalNumber(match.key());
        return key != null && Double.compare(value, key) == 0 ? 1 : null;
    }

    private static Integer selectInteger(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Integer selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
        Long key = parseInteger(match.key());
        return key != null && (long) value == key ? 1 : null;
    }

    private static String formatOffset(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        long value = parseRequiredInteger(call.value(), "Offset function requires a numeric operand.");
        long result = value + offsetDelta(call);
        return formatIntegerNumber(result, inheritedSignDisplayAlways(call.inheritedSource()));
    }

    private static Integer selectOffset(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        long value = parseRequiredInteger(match.value(), "Offset selector requires a numeric operand.");
        Long key = parseInteger(match.key());
        return key != null && value == key ? 1 : null;
    }

    private static double parseMatchDecimal(Mf2FunctionRegistry.FunctionMatch match, String message)
            throws Mf2Exception {
        Double parsed = Mf2FunctionSupport.parseDecimalNumber(match.value());
        if (parsed == null) {
            parsed = Mf2FunctionSupport.parseSourceDecimal(match.inheritedSource());
        }
        if (parsed == null) {
            throw Mf2FunctionSupport.badSelector(message);
        }
        return parsed;
    }

    static boolean signDisplayAlways(Mf2Message.FunctionRef function) {
        return functionOptionLiteral(function, "signDisplay", null) != null
                && functionOptionLiteral(function, "signDisplay", null).equals("always");
    }

    private static boolean inheritedSignDisplayAlways(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return false;
        }
        if ((source.function().name().equals("number") || source.function().name().equals("integer"))
                && "always".equals(source.optionValue("signDisplay", null))) {
            return true;
        }
        return inheritedSignDisplayAlways(source.inheritedSource());
    }

    private static boolean invalidNumericSelector(
            Mf2Message.FunctionRef function,
            Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        return numericSelectUsesVariable(function)
                || (functionOptionLiteral(function, "select", null) == null
                        || !functionOptionLiteral(function, "select", null).equals("exact"))
                        && inheritedExactNumericSource(source);
    }

    private static boolean numericSelectUsesVariable(Mf2Message.FunctionRef function) {
        return function.options().get("select") instanceof Mf2Message.VariableArgument;
    }

    private static boolean inheritedExactNumericSource(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return false;
        }
        if (Mf2FunctionSupport.isNumericFunction(source.function()) && "exact".equals(source.optionValue("select", null))) {
            return true;
        }
        return inheritedExactNumericSource(source.inheritedSource());
    }

    private static String functionOptionLiteral(Mf2Message.FunctionRef function, String name, String fallback) {
        Mf2Message.ExpressionArgument option = function.options().get(name);
        return option instanceof Mf2Message.LiteralArgument literal ? literal.value() : fallback;
    }

    private static long offsetDelta(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String add = call.optionValue("add", null);
        String subtract = call.optionValue("subtract", null);
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2FunctionSupport.badOption("Offset function requires exactly one of add or subtract.");
        }
        if (add != null) {
            Long value = parseInteger(add);
            if (value == null) {
                throw Mf2FunctionSupport.badOption("Offset add option must be an integer.");
            }
            return value;
        }
        Long value = parseInteger(subtract);
        if (value == null) {
            throw Mf2FunctionSupport.badOption("Offset subtract option must be an integer.");
        }
        return -value;
    }

    private static long parseRequiredInteger(String value, String message)
            throws Mf2Exception {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw Mf2Exception.badOperand(message);
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
