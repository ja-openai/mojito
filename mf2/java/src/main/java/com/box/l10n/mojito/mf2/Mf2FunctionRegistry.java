package com.box.l10n.mojito.mf2;

import java.util.HashMap;
import java.util.Map;

public final class Mf2FunctionRegistry {
    private final Map<String, Formatter> formatters;

    private Mf2FunctionRegistry(Map<String, Formatter> formatters) {
        this.formatters = Map.copyOf(formatters);
    }

    public static Mf2FunctionRegistry defaults() {
        Map<String, Formatter> formatters = new HashMap<>();
        Formatter passthrough = call -> call.value();
        for (String name :
                new String[] {"string", "number", "integer", "datetime", "date", "time"}) {
            formatters.put(name, passthrough);
        }
        return new Mf2FunctionRegistry(formatters);
    }

    public Mf2FunctionRegistry withFunction(String name, Formatter formatter) {
        Map<String, Formatter> next = new HashMap<>(formatters);
        next.put(name, formatter);
        return new Mf2FunctionRegistry(next);
    }

    String format(FunctionCall call) throws Mf2Exception {
        Formatter formatter = formatters.get(call.function().name());
        if (formatter == null) {
            throw Mf2Exception.unsupportedFunction(call.function().name());
        }
        return formatter.format(call);
    }

    @FunctionalInterface
    public interface Formatter {
        String format(FunctionCall call) throws Mf2Exception;
    }

    @FunctionalInterface
    public interface OptionResolver {
        String optionValue(String optionName, String defaultValue) throws Mf2Exception;
    }

    public record FunctionCall(
            String value,
            Mf2Message.FunctionRef function,
            String locale,
            OptionResolver options) {
        public String optionValue(String optionName, String defaultValue) throws Mf2Exception {
            return options.optionValue(optionName, defaultValue);
        }
    }
}
