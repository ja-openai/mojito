package com.box.l10n.mojito.mf2

internal object LocaleKey {
    fun canonicalKey(locale: String?): String = parts(locale).joinToString("-")

    fun lookupChain(locale: String?): List<String> {
        val parts = canonicalKey(locale).split("-").filter { it.isNotEmpty() }
        return parts.indices.reversed().map { length -> parts.subList(0, length + 1).joinToString("-") }
    }

    fun pluralLookupChain(locale: String?, parents: Map<String, String> = emptyMap()): List<String> {
        val output = mutableListOf<String>()
        for (candidate in lookupChain(locale)) {
            if (candidate !in output) output += candidate
            val parent = parents[candidate]
            if (parent != null && parent !in output) output += parent
        }
        return output
    }

    fun <T> lookup(values: Map<String, T>, locale: String?, fallback: String = "en"): T? {
        val canonicalValues = values.mapKeys { canonicalKey(it.key) }
        for (candidate in lookupChain(locale)) {
            canonicalValues[candidate]?.let { return it }
        }
        return canonicalValues[canonicalKey(fallback)]
    }

    private fun parts(locale: String?): List<String> {
        val normalized = locale.orEmpty().trim().replace('_', '-')
        val output = mutableListOf<String>()
        for ((index, rawPart) in normalized.split("-").withIndex()) {
            if (rawPart.isEmpty()) {
                continue
            }
            if (rawPart.length == 1) {
                break
            }
            val part = when {
                index == 0 -> rawPart.lowercase()
                rawPart.length == 2 || rawPart.length == 3 && rawPart.all { it.isDigit() } -> rawPart.uppercase()
                rawPart.length == 4 -> rawPart.lowercase().replaceFirstChar { it.titlecase() }
                else -> rawPart.lowercase()
            }
            output += part
        }
        return output
    }
}
