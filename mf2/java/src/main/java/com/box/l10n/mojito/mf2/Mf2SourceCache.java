package com.box.l10n.mojito.mf2;

import java.util.HashMap;
import java.util.Map;

/** Per-format source state; never attached to caller-created source records or stored globally. */
final class Mf2SourceCache implements Mf2FunctionRegistry.OptionResolver {
    private final Mf2FunctionRegistry.OptionResolver resolver;
    private Mf2FunctionRegistry.FunctionSourceRef owner;

    void bind(Mf2FunctionRegistry.FunctionSourceRef owner) { this.owner = owner; }
    final boolean cacheable;
    boolean operandResolved;
    String operand;
    final Map<String, String> inheritedOptions = new HashMap<>();

    Mf2SourceCache(Mf2FunctionRegistry.OptionResolver resolver, Mf2Message.FunctionRef function,
            Mf2FunctionRegistry.FunctionSourceRef inherited) {
        this.resolver = resolver;
        Mf2SourceCache previous = of(inherited);
        cacheable = function.options().values().stream().allMatch(option -> option instanceof Mf2Message.LiteralArgument)
                && (inherited == null || previous != null && previous.cacheable);
    }

    static Mf2SourceCache of(Mf2FunctionRegistry.FunctionSourceRef source) {
        return source != null && source.options() instanceof Mf2SourceCache cache && cache.owner == source ? cache : null;
    }

    void remember(String key, String value) {
        if (cacheable && inheritedOptions.size() < 64) inheritedOptions.put(key, value);
    }

    @Override public String optionValue(String name, String fallback) throws Mf2Exception {
        return resolver.optionValue(name, fallback);
    }
}
