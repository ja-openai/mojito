package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Map;

final class Mf2PortableFunctions {
    private static final String MAX_OFFSET_INTEGER_TEXT = "1000000000000000000000";
    private static final BigInteger MAX_OFFSET_INTEGER = new BigInteger("1000000000000000000000");

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
        BigDecimal value = parseMatchDecimalOperand(match, "Number selector requires a numeric operand.");
        BigDecimal key = Mf2FunctionSupport.parseDecimalOperand(match.key());
        return key != null && value.compareTo(key) == 0 ? 2 : null;
    }

    private static Integer selectPercent(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Percent selector cannot match this operand.");
        }
        BigDecimal value = parseMatchDecimalOperand(match, "Percent selector requires a numeric operand.")
                .multiply(BigDecimal.valueOf(100));
        BigDecimal key = Mf2FunctionSupport.parseDecimalOperand(match.key());
        return key != null && value.compareTo(key) == 0 ? 2 : null;
    }

    private static Integer selectInteger(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw Mf2FunctionSupport.badSelector("Integer selector cannot match this operand.");
        }
        BigDecimal value = parseMatchDecimalOperand(match, "Integer selector requires a numeric operand.")
                .setScale(0, RoundingMode.DOWN);
        BigDecimal key = parseIntegerOperand(match.key());
        return key != null && value.compareTo(key) == 0 ? 2 : null;
    }

    private static String formatOffset(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        BigInteger value = parseRequiredOffsetInteger(call.value(), "Offset function requires a numeric operand.");
        BigInteger result = value.add(offsetDelta(call));
        if (!offsetIntegerInRange(result)) {
            throw Mf2Exception.badOperand("Offset result is outside the supported integer range.");
        }
        return formatOffsetInteger(result, inheritedSignDisplayAlways(call.inheritedSource()));
    }

    private static Integer selectOffset(Mf2FunctionRegistry.FunctionMatch match)
            throws Mf2Exception {
        BigInteger value = parseRequiredOffsetInteger(match.value(), "Offset selector requires a numeric operand.");
        BigInteger key = parseOffsetInteger(match.key());
        return key != null && value.compareTo(key) == 0 ? 2 : null;
    }

    private static BigDecimal parseMatchDecimalOperand(Mf2FunctionRegistry.FunctionMatch match, String message)
            throws Mf2Exception {
        BigDecimal parsed = Mf2FunctionSupport.parseSourceDecimalOperand(match.inheritedSource());
        if (parsed == null) {
            parsed = Mf2FunctionSupport.parseDecimalOperand(match.value());
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

    private static BigInteger offsetDelta(Mf2FunctionRegistry.FunctionCall call)
            throws Mf2Exception {
        String add = call.optionValue("add", null);
        String subtract = call.optionValue("subtract", null);
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw Mf2FunctionSupport.badOption("Offset function requires exactly one of add or subtract.");
        }
        if (add != null) {
            BigInteger value = parseOffsetInteger(add);
            if (value == null) {
                throw Mf2FunctionSupport.badOption("Offset add option must be an integer.");
            }
            return value;
        }
        BigInteger value = parseOffsetInteger(subtract);
        if (value == null) {
            throw Mf2FunctionSupport.badOption("Offset subtract option must be an integer.");
        }
        return value.negate();
    }

    private static BigInteger parseRequiredOffsetInteger(String value, String message)
            throws Mf2Exception {
        BigInteger parsed = parseOffsetInteger(value);
        if (parsed == null) {
            throw Mf2Exception.badOperand(message);
        }
        return parsed;
    }

    private static BigInteger parseOffsetInteger(String value) {
        if (!value.matches("^[+-]?[0-9]+$")) {
            return null;
        }
        boolean negative = value.startsWith("-");
        String digits = negative || value.startsWith("+") ? value.substring(1) : value;
        digits = digits.replaceFirst("^0+", "");
        if (digits.isEmpty()) {
            return BigInteger.ZERO;
        }
        if (digits.length() > MAX_OFFSET_INTEGER_TEXT.length()
                || digits.length() == MAX_OFFSET_INTEGER_TEXT.length()
                        && digits.compareTo(MAX_OFFSET_INTEGER_TEXT) >= 0) {
            return null;
        }
        try {
            return new BigInteger(negative ? "-" + digits : digits);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static BigDecimal parseIntegerOperand(String value) {
        if (value.length() > Mf2FunctionSupport.MAX_DECIMAL_OPERAND_LENGTH) {
            return null;
        }
        if (!value.matches("^[+-]?[0-9]+$")) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static String formatIntegerNumber(long value, boolean signDisplayAlways) {
        return signDisplayAlways && value >= 0 ? "+" + value : Long.toString(value);
    }

    private static String formatOffsetInteger(BigInteger value, boolean signDisplayAlways) {
        return signDisplayAlways && value.signum() >= 0 ? "+" + value : value.toString();
    }

    private static boolean offsetIntegerInRange(BigInteger value) {
        return value.abs().compareTo(MAX_OFFSET_INTEGER) < 0;
    }
}
