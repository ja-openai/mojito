package com.box.l10n.mojito.mf2;

import java.util.HashMap;
import java.util.Map;

public final class Mf2FunctionRegistry {
    private final Map<String, Formatter> formatters;
    private final Map<String, Selector> selectors;

    private Mf2FunctionRegistry(Map<String, Formatter> formatters, Map<String, Selector> selectors) {
        this.formatters = Map.copyOf(formatters);
        this.selectors = Map.copyOf(selectors);
    }

    public static Mf2FunctionRegistry defaults() {
        Map<String, Formatter> formatters = new HashMap<>();
        Map<String, Selector> selectors = new HashMap<>();
        register(formatters, "string", call -> call.value());
        register(formatters, "number", Mf2FunctionRegistry::formatNumber);
        register(selectors, "number", Mf2FunctionRegistry::selectNumber);
        register(formatters, "percent", Mf2FunctionRegistry::formatPercent);
        register(selectors, "percent", Mf2FunctionRegistry::selectPercent);
        register(formatters, "currency", Mf2FunctionRegistry::formatCurrency);
        register(selectors, "currency", Mf2FunctionRegistry::selectCurrency);
        register(formatters, "integer", Mf2FunctionRegistry::formatInteger);
        register(selectors, "integer", Mf2FunctionRegistry::selectInteger);
        register(formatters, "datetime", Mf2FunctionRegistry::formatDateTime);
        register(formatters, "date", Mf2FunctionRegistry::formatDate);
        register(formatters, "time", Mf2FunctionRegistry::formatTime);
        register(formatters, "offset", Mf2FunctionRegistry::formatOffset);
        register(selectors, "offset", Mf2FunctionRegistry::selectOffset);
        return new Mf2FunctionRegistry(formatters, selectors);
    }

    private static <T> void register(Map<String, T> target, String name, T value) {
        target.put(name, value);
    }

    public Mf2FunctionRegistry withFunction(String name, Formatter formatter) {
        Map<String, Formatter> next = new HashMap<>(formatters);
        next.put(name, formatter);
        return new Mf2FunctionRegistry(next, selectors);
    }

    public Mf2FunctionRegistry withSelector(String name, Selector selector) {
        Map<String, Selector> next = new HashMap<>(selectors);
        next.put(name, selector);
        return new Mf2FunctionRegistry(formatters, next);
    }

    boolean hasSelector(Mf2Message.FunctionRef function) {
        return selectors.containsKey(function.name());
    }

    boolean hasFormatter(Mf2Message.FunctionRef function) {
        return formatters.containsKey(function.name());
    }

    String format(FunctionCall call) throws Mf2Exception {
        Formatter formatter = formatters.get(call.function().name());
        if (formatter == null) {
            throw Mf2Exception.unsupportedFunction(call.function().name());
        }
        return formatter.format(call);
    }

    Integer select(FunctionMatch match) throws Mf2Exception {
        Selector selector = selectors.get(match.function().name());
        return selector == null ? null : selector.select(match);
    }

    @FunctionalInterface
    public interface Formatter {
        String format(FunctionCall call) throws Mf2Exception;
    }

    @FunctionalInterface
    public interface Selector {
        Integer select(FunctionMatch match) throws Mf2Exception;
    }

    @FunctionalInterface
    public interface OptionResolver {
        String optionValue(String optionName, String defaultValue) throws Mf2Exception;
    }

    public record FunctionCall(
            String value,
            Object rawValue,
            Mf2Message.FunctionRef function,
            String locale,
            OptionResolver options,
            FunctionSourceRef inheritedSource) {
        public String optionValue(String optionName, String defaultValue) throws Mf2Exception {
            return options.optionValue(optionName, defaultValue);
        }
    }

    public record FunctionMatch(
            String value,
            Object rawValue,
            Mf2Message.FunctionRef function,
            String key,
            String locale,
            OptionResolver options,
            FunctionSourceRef inheritedSource) {
        public String optionValue(String optionName, String defaultValue) throws Mf2Exception {
            return options.optionValue(optionName, defaultValue);
        }
    }

    public record FunctionSourceRef(
            String value,
            Mf2Message.FunctionRef function,
            OptionResolver options,
            FunctionSourceRef inheritedSource) {
        public String optionValue(String optionName, String defaultValue) throws Mf2Exception {
            return options.optionValue(optionName, defaultValue);
        }
    }

    private static String formatNumber(FunctionCall call) throws Mf2Exception {
        double value = parseCallDecimal(call, "Number function requires a numeric operand.");
        return formatDecimalNumber(value, signDisplayAlways(call.function()), minimumFractionDigits(call));
    }

    private static Integer selectNumber(FunctionMatch match) throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw badSelector("Number selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Number selector requires a numeric operand.");
        Double key = parseDecimalNumber(match.key());
        return key != null && Double.compare(value, key) == 0 ? 1 : null;
    }

    private static String formatPercent(FunctionCall call) throws Mf2Exception {
        double value = parseCallDecimal(call, "Percent function requires a numeric operand.");
        return formatPercentNumber(
                value,
                signDisplayAlways(call.function()),
                minimumFractionDigits(call),
                maximumFractionDigits(call));
    }

    private static Integer selectPercent(FunctionMatch match) throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw badSelector("Percent selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100.0;
        Double key = parseDecimalNumber(match.key());
        return key != null && Double.compare(value, key) == 0 ? 1 : null;
    }

    private static String formatCurrency(FunctionCall call) throws Mf2Exception {
        double value = parseCallDecimal(call, "Currency function requires a numeric operand.");
        String currency = currencyCode(call);
        if (currency == null) {
            throw Mf2Exception.badOperand("Currency function requires a currency option.");
        }
        return formatCurrencyNumber(value, currency, currencyFractionDigits(call));
    }

    private static Integer selectCurrency(FunctionMatch ignored) throws Mf2Exception {
        throw badSelector("Currency selector is not supported.");
    }

    private static String formatInteger(FunctionCall call) throws Mf2Exception {
        double value = parseCallDecimal(call, "Integer function requires a numeric operand.");
        return formatIntegerNumber((long) value, signDisplayAlways(call.function()));
    }

    private static Integer selectInteger(FunctionMatch match) throws Mf2Exception {
        if (invalidNumericSelector(match.function(), match.inheritedSource())) {
            throw badSelector("Integer selector cannot match this operand.");
        }
        double value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
        Long key = parseInteger(match.key());
        return key != null && (long) value == key ? 1 : null;
    }

    private static String formatOffset(FunctionCall call) throws Mf2Exception {
        long value = parseRequiredInteger(call.value(), "Offset function requires a numeric operand.");
        long result = value + offsetDelta(call);
        return formatIntegerNumber(result, inheritedSignDisplayAlways(call.inheritedSource()));
    }

    private static Integer selectOffset(FunctionMatch match) throws Mf2Exception {
        long value = parseRequiredInteger(match.value(), "Offset selector requires a numeric operand.");
        Long key = parseInteger(match.key());
        return key != null && value == key ? 1 : null;
    }

    private static String formatDateTime(FunctionCall call) throws Mf2Exception {
        String value = call.value();
        if (isIsoDate(value) || isIsoDateTime(value)) {
            return value;
        }
        throw Mf2Exception.badOperand("Datetime function requires a date or datetime operand.");
    }

    private static String formatDate(FunctionCall call) throws Mf2Exception {
        String value = call.value();
        if (isIsoDate(value) || isIsoDateTime(value)) {
            return value;
        }
        throw Mf2Exception.badOperand("Date function requires a date or datetime operand.");
    }

    private static String formatTime(FunctionCall call) throws Mf2Exception {
        if (isIsoDateTime(call.value())) {
            return call.value();
        }
        throw Mf2Exception.badOperand("Datetime and time functions require a datetime operand.");
    }

    private static double parseCallDecimal(FunctionCall call, String message) throws Mf2Exception {
        Double parsed = parseDecimalNumber(call.value());
        if (parsed == null) {
            parsed = parseSourceDecimal(call.inheritedSource());
        }
        if (parsed == null) {
            throw Mf2Exception.badOperand(message);
        }
        return parsed;
    }

    private static double parseMatchDecimal(FunctionMatch match, String message) throws Mf2Exception {
        Double parsed = parseDecimalNumber(match.value());
        if (parsed == null) {
            parsed = parseSourceDecimal(match.inheritedSource());
        }
        if (parsed == null) {
            throw badSelector(message);
        }
        return parsed;
    }

    private static Double parseSourceDecimal(FunctionSourceRef source) throws Mf2Exception {
        if (source == null) {
            return null;
        }
        if (isDecimalSourceFunction(source.function())) {
            return parseDecimalNumber(source.value());
        }
        return parseSourceDecimal(source.inheritedSource());
    }

    private static Double parseDecimalNumber(String value) {
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

    private static String formatDecimalNumber(double value, boolean signDisplayAlways, int minimumFractionDigits) {
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
        String formatted = String.format(java.util.Locale.ROOT, "%." + digits + "f", value);
        while (formatted.contains(".") && formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        if (formatted.endsWith(".")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    private static String formatCurrencyNumber(double value, String currency, Integer fractionDigits) {
        String number = fractionDigits == null
                ? formatDecimalNumber(value, false, 0)
                : String.format(java.util.Locale.ROOT, "%." + fractionDigits + "f", value);
        return currency + " " + number;
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

    private static int minimumFractionDigits(FunctionCall call) throws Mf2Exception {
        String value = call.optionValue("minimumFractionDigits", null);
        if (value == null) {
            return 0;
        }
        return parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
    }

    private static Integer maximumFractionDigits(FunctionCall call) throws Mf2Exception {
        String value = call.optionValue("maximumFractionDigits", null);
        if (value == null) {
            return null;
        }
        return parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.");
    }

    private static String currencyCode(FunctionCall call) throws Mf2Exception {
        String currency = call.optionValue("currency", null);
        if (currency != null) {
            return currency;
        }
        return inheritedCurrencyCode(call.inheritedSource());
    }

    private static String inheritedCurrencyCode(FunctionSourceRef source) throws Mf2Exception {
        if (source == null) {
            return null;
        }
        if (source.function().name().equals("currency")) {
            String currency = source.optionValue("currency", null);
            if (currency != null) {
                return currency;
            }
        }
        return inheritedCurrencyCode(source.inheritedSource());
    }

    private static Integer currencyFractionDigits(FunctionCall call) throws Mf2Exception {
        String value = call.optionValue("fractionDigits", null);
        if (value == null || value.equals("auto")) {
            return null;
        }
        return parseNonNegativeOption(value, "fractionDigits option must be auto or a non-negative integer.");
    }

    private static int parseNonNegativeOption(String value, String message) throws Mf2Exception {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw badOption(message);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw badOption(message);
        }
    }

    private static boolean signDisplayAlways(Mf2Message.FunctionRef function) {
        return functionOptionLiteral(function, "signDisplay", null) != null
                && functionOptionLiteral(function, "signDisplay", null).equals("always");
    }

    private static boolean inheritedSignDisplayAlways(FunctionSourceRef source) throws Mf2Exception {
        if (source == null) {
            return false;
        }
        if ((source.function().name().equals("number") || source.function().name().equals("integer"))
                && "always".equals(source.optionValue("signDisplay", null))) {
            return true;
        }
        return inheritedSignDisplayAlways(source.inheritedSource());
    }

    private static boolean invalidNumericSelector(Mf2Message.FunctionRef function, FunctionSourceRef source)
            throws Mf2Exception {
        return numericSelectUsesVariable(function)
                || (functionOptionLiteral(function, "select", null) == null
                        || !functionOptionLiteral(function, "select", null).equals("exact"))
                        && inheritedExactNumericSource(source);
    }

    private static boolean numericSelectUsesVariable(Mf2Message.FunctionRef function) {
        return function.options().get("select") instanceof Mf2Message.VariableArgument;
    }

    private static boolean inheritedExactNumericSource(FunctionSourceRef source) throws Mf2Exception {
        if (source == null) {
            return false;
        }
        if (isNumericFunction(source.function()) && "exact".equals(source.optionValue("select", null))) {
            return true;
        }
        return inheritedExactNumericSource(source.inheritedSource());
    }

    private static boolean isNumericFunction(Mf2Message.FunctionRef function) {
        return function.name().equals("number")
                || function.name().equals("integer")
                || function.name().equals("percent")
                || function.name().equals("offset");
    }

    private static boolean isDecimalSourceFunction(Mf2Message.FunctionRef function) {
        return isNumericFunction(function) || function.name().equals("currency");
    }

    private static String functionOptionLiteral(Mf2Message.FunctionRef function, String name, String fallback) {
        Mf2Message.ExpressionArgument option = function.options().get(name);
        return option instanceof Mf2Message.LiteralArgument literal ? literal.value() : fallback;
    }

    private static long offsetDelta(FunctionCall call) throws Mf2Exception {
        String add = call.optionValue("add", null);
        String subtract = call.optionValue("subtract", null);
        if ((add == null && subtract == null) || (add != null && subtract != null)) {
            throw badOption("Offset function requires exactly one of add or subtract.");
        }
        if (add != null) {
            Long value = parseInteger(add);
            if (value == null) {
                throw badOption("Offset add option must be an integer.");
            }
            return value;
        }
        Long value = parseInteger(subtract);
        if (value == null) {
            throw badOption("Offset subtract option must be an integer.");
        }
        return -value;
    }

    private static long parseRequiredInteger(String value, String message) throws Mf2Exception {
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

    private static String formatIntegerNumber(long value, boolean signDisplayAlways) {
        return signDisplayAlways && value >= 0 ? "+" + value : Long.toString(value);
    }

    private static boolean isIsoDateTime(String value) {
        int separator = value.indexOf('T');
        return separator >= 0
                && isIsoDate(value.substring(0, separator))
                && isIsoTime(value.substring(separator + 1));
    }

    private static boolean isIsoDate(String value) {
        return value.length() == 10
                && value.charAt(4) == '-'
                && value.charAt(7) == '-'
                && digits(value, 0, 4)
                && digits(value, 5, 7)
                && digits(value, 8, 10);
    }

    private static boolean isIsoTime(String value) {
        return value.length() == 8
                && value.charAt(2) == ':'
                && value.charAt(5) == ':'
                && digits(value, 0, 2)
                && digits(value, 3, 5)
                && digits(value, 6, 8);
    }

    private static boolean digits(String value, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static Mf2Exception badOption(String message) {
        return new Mf2Exception("bad-option", message);
    }

    private static Mf2Exception badSelector(String message) {
        return new Mf2Exception("bad-selector", message);
    }
}
