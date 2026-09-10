package com.box.l10n.mojito.mf2

internal object PluralRules {
    private fun supportedOperands(value: String): NumberOperands? {
        val number = value.toDoubleOrNull() ?: return null
        if (!number.isFinite() || kotlin.math.abs(number) >= 9223372036854775808.0) return null
        return NumberOperands.fromString(value)
    }

    fun selectCardinal(locale: String, value: String): String =
        supportedOperands(value)?.let { CldrPluralRules.selectCardinal(locale, it) } ?: throw Mf2Error.badSelector("Numeric plural operand is outside the supported range.")

    fun selectOrdinal(locale: String, value: String): String =
        supportedOperands(value)?.let { CldrPluralRules.selectOrdinal(locale, it) } ?: throw Mf2Error.badSelector("Numeric plural operand is outside the supported range.")
}
