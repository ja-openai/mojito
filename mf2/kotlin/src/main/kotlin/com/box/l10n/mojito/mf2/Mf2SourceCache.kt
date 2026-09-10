package com.box.l10n.mojito.mf2

/** A source-local cache owned by one format call, with a bounded option table. */
internal class Mf2SourceCache(
    private val resolver: (String, String?) -> String?,
    function: Map<String, Any?>,
    inherited: Mf2FunctionSource?,
    val direction: String?,
    val forceIsolation: Boolean,
    val resolvedDirection: String?,
) : (String, String?) -> String? {
    var owner: Mf2FunctionSource? = null
    val cacheable: Boolean = asMap(function["options"]).values.all { asMap(it)["type"] == "literal" } &&
        (inherited == null || inherited.cache?.cacheable == true)
    var operandResolved = false
    var operand: String? = null
    val inheritedOptions = mutableMapOf<String, String?>()
    fun remember(key: String, value: String?) { if (cacheable && inheritedOptions.size < 64) inheritedOptions[key] = value }
    override fun invoke(name: String, fallback: String?): String? = resolver(name, fallback)
}
