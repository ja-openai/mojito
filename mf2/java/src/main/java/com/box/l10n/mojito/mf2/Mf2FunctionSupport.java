package com.box.l10n.mojito.mf2;

import java.math.BigDecimal;

final class Mf2FunctionSupport {
    static final int MAX_FRACTION_DIGITS = 100;
    static final int MAX_DECIMAL_OPERAND_LENGTH = 256;
    private static final int MAX_DECIMAL_EXPONENT = 1_000_000;

    private Mf2FunctionSupport() {}

    static double parseCallDecimal(Mf2FunctionRegistry.FunctionCall call, String message)
            throws Mf2Exception {
        Double parsed = parseDecimalNumber(call.value());
        if (parsed == null) {
            parsed = parseSourceDecimal(call.inheritedSource());
        }
        if (parsed == null) {
            throw Mf2Exception.badOperand(message);
        }
        return parsed;
    }

    static BigDecimal parseCallDecimalOperand(
            Mf2FunctionRegistry.FunctionCall call, String message)
            throws Mf2Exception {
        BigDecimal parsed = parseDecimalOperand(call.value());
        if (parsed == null) {
            parsed = parseSourceDecimalOperand(call.inheritedSource());
        }
        if (parsed == null) {
            throw Mf2Exception.badOperand(message);
        }
        return parsed;
    }

    static Double parseSourceDecimal(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return null;
        }
        if (isDecimalSourceFunction(source.function())) {
            return parseDecimalNumber(source.value());
        }
        return parseSourceDecimal(source.inheritedSource());
    }

    static Double parseDecimalNumber(String value) {
        if (value.length() > MAX_DECIMAL_OPERAND_LENGTH) {
            return null;
        }
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

    static BigDecimal parseDecimalOperand(String value) {
        if (value.length() > MAX_DECIMAL_OPERAND_LENGTH) {
            return null;
        }
        if (!isWellFormedDecimalLiteral(value)) {
            return null;
        }
        if (parseBoundedDecimalExponent(value) == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static BigDecimal parseSourceDecimalOperand(Mf2FunctionRegistry.FunctionSourceRef source)
            throws Mf2Exception {
        if (source == null) {
            return null;
        }
        if (isDecimalSourceFunction(source.function())) {
            return parseDecimalOperand(source.value());
        }
        return parseSourceDecimalOperand(source.inheritedSource());
    }

    static int parseNonNegativeOption(String value, String message)
            throws Mf2Exception {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw badOption(message);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > MAX_FRACTION_DIGITS) {
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

    private static Integer parseBoundedDecimalExponent(String value) {
        int lower = value.indexOf('e');
        int upper = value.indexOf('E');
        int exponentIndex = Math.max(lower, upper);
        if (exponentIndex < 0) {
            return 0;
        }
        String exponent = value.substring(exponentIndex + 1);
        boolean negative = exponent.startsWith("-");
        String unsigned = negative || exponent.startsWith("+") ? exponent.substring(1) : exponent;
        String digits = unsigned.replaceFirst("^0+", "");
        if (digits.isEmpty()) {
            return 0;
        }
        if (digits.length() > 7) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(digits);
            if (parsed > MAX_DECIMAL_EXPONENT) {
                return null;
            }
            return negative ? -parsed : parsed;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean isDecimalSourceFunction(Mf2Message.FunctionRef function) {
        return isNumericFunction(function) || function.name().equals("currency");
    }
}
