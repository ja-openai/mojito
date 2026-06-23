package com.box.l10n.mojito.mf2

internal object LocaleKey {
    private const val MAX_LOCALE_OPTION_LENGTH = 256

    fun option(locale: String?, fallback: String): String {
        val value = locale.takeUnless { it.isNullOrEmpty() } ?: fallback
        if (value.length > MAX_LOCALE_OPTION_LENGTH) {
            throw Mf2Error.badOption("locale must not exceed 256 characters.")
        }
        return value
    }

    fun canonicalKey(locale: String?): String {
        if (locale != null && locale.length > MAX_LOCALE_OPTION_LENGTH) {
            return ""
        }
        return parts(locale).joinToString("-")
    }

    fun lookupChain(locale: String?): List<String> {
        val parts = canonicalKey(locale).split("-").filter { it.isNotEmpty() }
        return parts.indices.reversed().map { length -> parts.subList(0, length + 1).joinToString("-") }
    }

    fun pluralLookupChain(locale: String?, parents: Map<String, String> = emptyMap()): List<String> {
        return featureLookupChain(locale, parents)
    }

    fun featureLookupChain(locale: String?, parents: Map<String, String> = emptyMap()): List<String> {
        val output = mutableListOf<String>()
        appendFeatureLookupChain(canonicalKey(locale), parents, output)
        return output
    }

    private fun appendFeatureLookupChain(locale: String, parents: Map<String, String>, output: MutableList<String>) {
        var current = locale
        while (current.isNotEmpty()) {
            if (current.length > MAX_LOCALE_OPTION_LENGTH) return
            if (current in output) return
            output += current
            parents[current]?.let { appendFeatureLookupChain(it, parents, output) }
            current = structuralParent(current)
        }
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

    private fun structuralParent(locale: String): String {
        val index = locale.lastIndexOf('-')
        return if (index < 0) "" else locale.substring(0, index)
    }
}
