package com.box.l10n.mojito.mf2;

import java.util.HashMap;
import java.util.Map;

final class Mf2FunctionRegistries {
    private Mf2FunctionRegistries() {}

    static Mf2FunctionRegistry portable() {
        Map<String, Mf2FunctionRegistry.Formatter> formatters = new HashMap<>();
        Map<String, Mf2FunctionRegistry.Selector> selectors = new HashMap<>();
        Mf2PortableFunctions.registerFormatters(formatters);
        Mf2PortableFunctions.registerSelectors(selectors);
        Mf2UnlocalizedNumericFunctions.registerFormatters(formatters);
        return new Mf2FunctionRegistry(formatters, selectors);
    }

    static Mf2FunctionRegistry jdk() {
        Map<String, Mf2FunctionRegistry.Formatter> formatters = new HashMap<>();
        Map<String, Mf2FunctionRegistry.Selector> selectors = new HashMap<>();
        Mf2PortableFunctions.registerFormatters(formatters);
        Mf2PortableFunctions.registerSelectors(selectors);
        Mf2JdkFunctions.registerFormatters(formatters);
        return new Mf2FunctionRegistry(formatters, selectors);
    }
}
