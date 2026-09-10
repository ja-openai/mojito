package com.box.l10n.mojito.mf2

internal object Mf2FunctionRegistries {
    fun portable(): Mf2FunctionRegistry {
        val formatters = linkedMapOf<String, Mf2FunctionFormatter>()
        val selectors = linkedMapOf<String, Mf2Selector>()
        Mf2PortableFunctions.registerFormatters(formatters)
        Mf2PortableFunctions.registerSelectors(selectors)
        Mf2UnlocalizedNumericFunctions.registerFormatters(formatters)
        return Mf2FunctionRegistry(formatters, selectors, setOf("number", "integer", "percent"))
    }

    fun jdk(): Mf2FunctionRegistry {
        val formatters = linkedMapOf<String, Mf2FunctionFormatter>()
        val selectors = linkedMapOf<String, Mf2Selector>()
        Mf2PortableFunctions.registerFormatters(formatters)
        Mf2PortableFunctions.registerSelectors(selectors)
        Mf2JdkFunctions.registerFormatters(formatters)
        return Mf2FunctionRegistry(formatters, selectors, setOf("number", "integer", "percent", "currency"))
    }
}
