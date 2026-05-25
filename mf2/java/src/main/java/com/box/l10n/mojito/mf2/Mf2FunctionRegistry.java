package com.box.l10n.mojito.mf2;

import java.util.HashMap;
import java.util.Map;

public final class Mf2FunctionRegistry {
    private final Map<String, Formatter> formatters;
    private final Map<String, Selector> selectors;

    Mf2FunctionRegistry(Map<String, Formatter> formatters, Map<String, Selector> selectors) {
        this.formatters = Map.copyOf(formatters);
        this.selectors = Map.copyOf(selectors);
    }

    public static Mf2FunctionRegistry portable() {
        return Mf2FunctionRegistries.portable();
    }

    public static Mf2FunctionRegistry defaults() {
        return Mf2FunctionRegistries.jdk();
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
}
