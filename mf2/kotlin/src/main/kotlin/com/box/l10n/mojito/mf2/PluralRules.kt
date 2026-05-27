package com.box.l10n.mojito.mf2

internal object PluralRules {
    fun selectCardinal(locale: String, value: String): String =
        NumberOperands.fromString(value)?.let { CldrPluralRules.selectCardinal(locale, it) } ?: "other"

    fun selectOrdinal(locale: String, value: String): String =
        NumberOperands.fromString(value)?.let { CldrPluralRules.selectOrdinal(locale, it) } ?: "other"
}
