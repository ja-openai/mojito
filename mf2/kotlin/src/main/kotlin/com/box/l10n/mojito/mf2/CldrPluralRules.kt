// Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.
package com.box.l10n.mojito.mf2

private const val MAX_PLURAL_OPERAND_LENGTH = 256
private const val MAX_SAFE_PLURAL_INTEGER = 9_007_199_254_740_991.0
private const val MAX_SAFE_PLURAL_LONG = 9_007_199_254_740_991L
private val PLURAL_OPERAND_REGEX = Regex("^[+-]?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$")

internal object CldrPluralRules {
    fun selectCardinal(locale: String, operands: NumberOperands): String =
        when (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {
            "r0" -> selectCardinalR0(operands)
            "r1" -> selectCardinalR1(operands)
            "r2" -> selectCardinalR2(operands)
            "r3" -> selectCardinalR3(operands)
            "r4" -> selectCardinalR4(operands)
            "r5" -> selectCardinalR5(operands)
            "r6" -> selectCardinalR6(operands)
            "r7" -> selectCardinalR7(operands)
            "r8" -> selectCardinalR8(operands)
            "r9" -> selectCardinalR9(operands)
            "r10" -> selectCardinalR10(operands)
            "r11" -> selectCardinalR11(operands)
            "r12" -> selectCardinalR12(operands)
            "r13" -> selectCardinalR13(operands)
            "r14" -> selectCardinalR14(operands)
            "r15" -> selectCardinalR15(operands)
            "r16" -> selectCardinalR16(operands)
            "r17" -> selectCardinalR17(operands)
            "r18" -> selectCardinalR18(operands)
            "r19" -> selectCardinalR19(operands)
            "r20" -> selectCardinalR20(operands)
            "r21" -> selectCardinalR21(operands)
            "r22" -> selectCardinalR22(operands)
            "r23" -> selectCardinalR23(operands)
            "r24" -> selectCardinalR24(operands)
            "r25" -> selectCardinalR25(operands)
            "r26" -> selectCardinalR26(operands)
            "r27" -> selectCardinalR27(operands)
            "r28" -> selectCardinalR28(operands)
            "r29" -> selectCardinalR29(operands)
            "r30" -> selectCardinalR30(operands)
            "r31" -> selectCardinalR31(operands)
            "r32" -> selectCardinalR32(operands)
            "r33" -> selectCardinalR33(operands)
            "r34" -> selectCardinalR34(operands)
            "r35" -> selectCardinalR35(operands)
            "r36" -> selectCardinalR36(operands)
            "r37" -> selectCardinalR37(operands)
            "r38" -> selectCardinalR38(operands)
            "r39" -> selectCardinalR39(operands)
            else -> "other"
        }

    fun selectOrdinal(locale: String, operands: NumberOperands): String =
        when (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {
            "r0" -> selectOrdinalR0(operands)
            "r1" -> selectOrdinalR1(operands)
            "r2" -> selectOrdinalR2(operands)
            "r3" -> selectOrdinalR3(operands)
            "r4" -> selectOrdinalR4(operands)
            "r5" -> selectOrdinalR5(operands)
            "r6" -> selectOrdinalR6(operands)
            "r7" -> selectOrdinalR7(operands)
            "r8" -> selectOrdinalR8(operands)
            "r9" -> selectOrdinalR9(operands)
            "r10" -> selectOrdinalR10(operands)
            "r11" -> selectOrdinalR11(operands)
            "r12" -> selectOrdinalR12(operands)
            "r13" -> selectOrdinalR13(operands)
            "r14" -> selectOrdinalR14(operands)
            "r15" -> selectOrdinalR15(operands)
            "r16" -> selectOrdinalR16(operands)
            "r17" -> selectOrdinalR17(operands)
            "r18" -> selectOrdinalR18(operands)
            "r19" -> selectOrdinalR19(operands)
            "r20" -> selectOrdinalR20(operands)
            "r21" -> selectOrdinalR21(operands)
            "r22" -> selectOrdinalR22(operands)
            "r23" -> selectOrdinalR23(operands)
            "r24" -> selectOrdinalR24(operands)
            else -> "other"
        }

    private val CARDINAL_LOCALES: Map<String, String> = mapOf(
        "af" to "r0",
        "ak" to "r1",
        "am" to "r2",
        "an" to "r0",
        "ar" to "r3",
        "ars" to "r3",
        "as" to "r2",
        "asa" to "r0",
        "ast" to "r4",
        "az" to "r0",
        "bal" to "r0",
        "be" to "r5",
        "bem" to "r0",
        "bez" to "r0",
        "bg" to "r0",
        "bho" to "r1",
        "blo" to "r6",
        "bm" to "r7",
        "bn" to "r2",
        "bo" to "r7",
        "br" to "r8",
        "brx" to "r0",
        "bs" to "r9",
        "ca" to "r10",
        "ce" to "r0",
        "ceb" to "r11",
        "cgg" to "r0",
        "chr" to "r0",
        "ckb" to "r0",
        "cs" to "r12",
        "csw" to "r1",
        "cv" to "r6",
        "cy" to "r13",
        "da" to "r14",
        "de" to "r4",
        "doi" to "r2",
        "dsb" to "r15",
        "dv" to "r0",
        "dz" to "r7",
        "ee" to "r0",
        "el" to "r0",
        "en" to "r4",
        "eo" to "r0",
        "es" to "r16",
        "et" to "r4",
        "eu" to "r0",
        "fa" to "r2",
        "ff" to "r17",
        "fi" to "r4",
        "fil" to "r11",
        "fo" to "r0",
        "fr" to "r18",
        "fur" to "r0",
        "fy" to "r4",
        "ga" to "r19",
        "gd" to "r20",
        "gl" to "r4",
        "gsw" to "r0",
        "gu" to "r2",
        "guw" to "r1",
        "gv" to "r21",
        "ha" to "r0",
        "haw" to "r0",
        "he" to "r22",
        "hi" to "r2",
        "hnj" to "r7",
        "hr" to "r9",
        "hsb" to "r15",
        "hu" to "r0",
        "hy" to "r17",
        "ia" to "r4",
        "id" to "r7",
        "ie" to "r4",
        "ig" to "r7",
        "ii" to "r7",
        "io" to "r4",
        "is" to "r23",
        "it" to "r10",
        "iu" to "r24",
        "ja" to "r7",
        "jbo" to "r7",
        "jgo" to "r0",
        "jmc" to "r0",
        "jv" to "r7",
        "jw" to "r7",
        "ka" to "r0",
        "kab" to "r17",
        "kaj" to "r0",
        "kcg" to "r0",
        "kde" to "r7",
        "kea" to "r7",
        "kk" to "r0",
        "kkj" to "r0",
        "kl" to "r0",
        "km" to "r7",
        "kn" to "r2",
        "ko" to "r7",
        "kok" to "r2",
        "kok-Latn" to "r2",
        "ks" to "r0",
        "ksb" to "r0",
        "ksh" to "r6",
        "ku" to "r0",
        "kw" to "r25",
        "ky" to "r0",
        "lag" to "r26",
        "lb" to "r0",
        "lg" to "r0",
        "lij" to "r4",
        "lkt" to "r7",
        "lld" to "r10",
        "ln" to "r1",
        "lo" to "r7",
        "lt" to "r27",
        "lv" to "r28",
        "mas" to "r0",
        "mg" to "r1",
        "mgo" to "r0",
        "mk" to "r29",
        "ml" to "r0",
        "mn" to "r0",
        "mo" to "r30",
        "mr" to "r0",
        "ms" to "r7",
        "mt" to "r31",
        "my" to "r7",
        "nah" to "r0",
        "naq" to "r24",
        "nb" to "r0",
        "nd" to "r0",
        "ne" to "r0",
        "nl" to "r4",
        "nn" to "r0",
        "nnh" to "r0",
        "no" to "r0",
        "nqo" to "r7",
        "nr" to "r0",
        "nso" to "r1",
        "ny" to "r0",
        "nyn" to "r0",
        "om" to "r0",
        "or" to "r0",
        "os" to "r0",
        "osa" to "r7",
        "pa" to "r1",
        "pap" to "r0",
        "pcm" to "r2",
        "pl" to "r32",
        "prg" to "r28",
        "ps" to "r0",
        "pt" to "r33",
        "pt-PT" to "r10",
        "rm" to "r0",
        "ro" to "r30",
        "rof" to "r0",
        "ru" to "r34",
        "rwk" to "r0",
        "sah" to "r7",
        "saq" to "r0",
        "sat" to "r24",
        "sc" to "r4",
        "scn" to "r10",
        "sd" to "r0",
        "sdh" to "r0",
        "se" to "r24",
        "seh" to "r0",
        "ses" to "r7",
        "sg" to "r7",
        "sgs" to "r35",
        "sh" to "r9",
        "shi" to "r36",
        "si" to "r37",
        "sk" to "r12",
        "sl" to "r38",
        "sma" to "r24",
        "smi" to "r24",
        "smj" to "r24",
        "smn" to "r24",
        "sms" to "r24",
        "sn" to "r0",
        "so" to "r0",
        "sq" to "r0",
        "sr" to "r9",
        "ss" to "r0",
        "ssy" to "r0",
        "st" to "r0",
        "su" to "r7",
        "sv" to "r4",
        "sw" to "r4",
        "syr" to "r0",
        "ta" to "r0",
        "te" to "r0",
        "teo" to "r0",
        "th" to "r7",
        "ti" to "r1",
        "tig" to "r0",
        "tk" to "r0",
        "tl" to "r11",
        "tn" to "r0",
        "to" to "r7",
        "tpi" to "r7",
        "tr" to "r0",
        "ts" to "r0",
        "tzm" to "r39",
        "ug" to "r0",
        "uk" to "r34",
        "und" to "r7",
        "ur" to "r4",
        "uz" to "r0",
        "ve" to "r0",
        "vec" to "r10",
        "vi" to "r7",
        "vo" to "r0",
        "vun" to "r0",
        "wa" to "r1",
        "wae" to "r0",
        "wo" to "r7",
        "xh" to "r0",
        "xog" to "r0",
        "yi" to "r4",
        "yo" to "r7",
        "yue" to "r7",
        "zh" to "r7",
        "zu" to "r2",
    )

    private val ORDINAL_LOCALES: Map<String, String> = mapOf(
        "af" to "r0",
        "am" to "r0",
        "an" to "r0",
        "ar" to "r0",
        "as" to "r1",
        "ast" to "r0",
        "az" to "r2",
        "bal" to "r3",
        "be" to "r4",
        "bg" to "r0",
        "blo" to "r5",
        "bn" to "r1",
        "bs" to "r0",
        "ca" to "r6",
        "ce" to "r0",
        "cs" to "r0",
        "cv" to "r0",
        "cy" to "r7",
        "da" to "r0",
        "de" to "r0",
        "dsb" to "r0",
        "el" to "r0",
        "en" to "r8",
        "es" to "r0",
        "et" to "r0",
        "eu" to "r0",
        "fa" to "r0",
        "fi" to "r0",
        "fil" to "r3",
        "fr" to "r3",
        "fy" to "r0",
        "ga" to "r3",
        "gd" to "r9",
        "gl" to "r0",
        "gsw" to "r0",
        "gu" to "r10",
        "he" to "r0",
        "hi" to "r10",
        "hr" to "r0",
        "hsb" to "r0",
        "hu" to "r11",
        "hy" to "r3",
        "ia" to "r0",
        "id" to "r0",
        "ie" to "r0",
        "is" to "r0",
        "it" to "r12",
        "ja" to "r0",
        "ka" to "r13",
        "kk" to "r14",
        "km" to "r0",
        "kn" to "r0",
        "ko" to "r0",
        "kok" to "r15",
        "kok-Latn" to "r15",
        "kw" to "r16",
        "ky" to "r0",
        "lij" to "r17",
        "lld" to "r12",
        "lo" to "r3",
        "lt" to "r0",
        "lv" to "r0",
        "mk" to "r18",
        "ml" to "r0",
        "mn" to "r0",
        "mo" to "r3",
        "mr" to "r15",
        "ms" to "r3",
        "my" to "r0",
        "nb" to "r0",
        "ne" to "r19",
        "nl" to "r0",
        "no" to "r0",
        "or" to "r20",
        "pa" to "r0",
        "pl" to "r0",
        "prg" to "r0",
        "ps" to "r0",
        "pt" to "r0",
        "ro" to "r3",
        "ru" to "r0",
        "sc" to "r12",
        "scn" to "r17",
        "sd" to "r0",
        "sh" to "r0",
        "si" to "r0",
        "sk" to "r0",
        "sl" to "r0",
        "sq" to "r21",
        "sr" to "r0",
        "sv" to "r22",
        "sw" to "r0",
        "ta" to "r0",
        "te" to "r0",
        "th" to "r0",
        "tk" to "r23",
        "tl" to "r3",
        "tpi" to "r0",
        "tr" to "r0",
        "uk" to "r24",
        "und" to "r0",
        "ur" to "r0",
        "uz" to "r0",
        "vec" to "r12",
        "vi" to "r3",
        "yue" to "r0",
        "zh" to "r0",
        "zu" to "r0",
    )

    private val CARDINAL_PARENTS: Map<String, String> = emptyMap()

    private val ORDINAL_PARENTS: Map<String, String> = emptyMap()

    private fun lookupRuleId(
        locales: Map<String, String>,
        parents: Map<String, String>,
        locale: String,
    ): String? {
        for (candidate in LocaleKey.pluralLookupChain(locale, parents)) {
            locales[candidate]?.let { return it }
        }
        return null
    }
}

internal data class NumberOperands(
    val n: Double,
    val i: Long,
    val v: Long,
    val w: Long,
    val f: Long,
    val t: Long,
    val e: Long = 0,
    val c: Long = 0,
) {
    fun operandLong(name: String): Long =
        when (name) {
            "i" -> i
            "v" -> v
            "w" -> w
            "f" -> f
            "t" -> t
            "e" -> e
            "c" -> c
            "n" -> n.toLong()
            else -> 0
        }

    fun operandDouble(name: String): Double = if (name == "n") n else operandLong(name).toDouble()

    companion object {
        fun fromString(value: String): NumberOperands? {
            val raw = value.trim()
            if (raw.length > MAX_PLURAL_OPERAND_LENGTH) return null
            if (!PLURAL_OPERAND_REGEX.matches(raw)) return null
            val parsed = raw.toDoubleOrNull()?.let { kotlin.math.abs(it) } ?: return null
            if (!parsed.isFinite() || parsed > MAX_SAFE_PLURAL_INTEGER) return null
            val normalized = raw.trimStart('-', '+').lowercase()
            val base = normalized.substringBefore("e")
            val fraction = base.substringAfter(".", "")
            val trimmedFraction = fraction.trimEnd('0')
            val f = parsePluralLong(fraction) ?: return null
            val t = parsePluralLong(trimmedFraction) ?: return null
            return NumberOperands(
                n = parsed,
                i = parsed.toLong(),
                v = fraction.length.toLong(),
                w = trimmedFraction.length.toLong(),
                f = f,
                t = t,
            )
        }
    }
}

private fun parsePluralLong(value: String): Long? {
    if (value.isEmpty()) return 0
    val digits = value.trimStart('0').ifEmpty { "0" }
    val parsed = digits.toLongOrNull() ?: return null
    return parsed.takeIf { it <= MAX_SAFE_PLURAL_LONG }
}

private fun selectCardinalR0(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    return "other"
}

private fun selectCardinalR1(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    return "other"
}

private fun selectCardinalR2(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) return "one"
    return "other"
}

private fun selectCardinalR3(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) return "zero"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 10.0) return "few"
    if ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 99.0) return "many"
    return "other"
}

private fun selectCardinalR4(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "one"
    return "other"
}

private fun selectCardinalR5(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) return "one"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) return "few"
    if (((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 5.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) return "many"
    return "other"
}

private fun selectCardinalR6(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) return "zero"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    return "other"
}

private fun selectCardinalR7(_operands: NumberOperands): String {
    return "other"
}

private fun selectCardinalR8(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 71.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 71.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 91.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 91.0)))) return "one"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0 && !((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 72.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 72.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 92.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 92.0)))) return "two"
    if ((((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0)) && !((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 10.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 70.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 79.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 90.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 99.0)))) return "few"
    if (!(operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) && (operands.operandDouble("n") % 1000000.0) % 1.0 == 0.0 && 0.0 <= (operands.operandDouble("n") % 1000000.0) && (operands.operandDouble("n") % 1000000.0) <= 0.0) return "many"
    return "other"
}

private fun selectCardinalR9(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1 && !(11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 11)) || (1 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 1 && !(11 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 11))) return "one"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 4 && !(12 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 14)) || (2 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 4 && !(12 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 14))) return "few"
    return "other"
}

private fun selectCardinalR10(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "one"
    if ((0 <= operands.operandLong("e") && operands.operandLong("e") <= 0 && !(0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) && 0 <= (operands.operandLong("i") % 1000000) && (operands.operandLong("i") % 1000000) <= 0 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) || (!(0 <= operands.operandLong("e") && operands.operandLong("e") <= 5))) return "many"
    return "other"
}

private fun selectCardinalR11(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && ((1 <= operands.operandLong("i") && operands.operandLong("i") <= 1) || (2 <= operands.operandLong("i") && operands.operandLong("i") <= 2) || (3 <= operands.operandLong("i") && operands.operandLong("i") <= 3))) || (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && !(((4 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 4) || (6 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 6) || (9 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 9)))) || (!(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) && !(((4 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 4) || (6 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 6) || (9 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 9))))) return "one"
    return "other"
}

private fun selectCardinalR12(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "one"
    if (2 <= operands.operandLong("i") && operands.operandLong("i") <= 4 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "few"
    if (!(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0)) return "many"
    return "other"
}

private fun selectCardinalR13(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) return "zero"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) return "few"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) return "many"
    return "other"
}

private fun selectCardinalR14(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (!(0 <= operands.operandLong("t") && operands.operandLong("t") <= 0) && ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1)))) return "one"
    return "other"
}

private fun selectCardinalR15(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 1) || (1 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 1)) return "one"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 2) || (2 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 2)) return "two"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 3 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 4) || (3 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 4)) return "few"
    return "other"
}

private fun selectCardinalR16(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if ((0 <= operands.operandLong("e") && operands.operandLong("e") <= 0 && !(0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) && 0 <= (operands.operandLong("i") % 1000000) && (operands.operandLong("i") % 1000000) <= 0 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) || (!(0 <= operands.operandLong("e") && operands.operandLong("e") <= 5))) return "many"
    return "other"
}

private fun selectCardinalR17(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1)) return "one"
    return "other"
}

private fun selectCardinalR18(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1)) return "one"
    if ((0 <= operands.operandLong("e") && operands.operandLong("e") <= 0 && !(0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) && 0 <= (operands.operandLong("i") % 1000000) && (operands.operandLong("i") % 1000000) <= 0 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) || (!(0 <= operands.operandLong("e") && operands.operandLong("e") <= 5))) return "many"
    return "other"
}

private fun selectCardinalR19(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) return "few"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) return "many"
    return "other"
}

private fun selectCardinalR20(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0)) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0)) return "two"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 19.0)) return "few"
    return "other"
}

private fun selectCardinalR21(operands: NumberOperands): String {
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1) return "one"
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 2) return "two"
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && ((0 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 0) || (20 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 20) || (40 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 40) || (60 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 60) || (80 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 80))) return "few"
    if (!(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0)) return "many"
    return "other"
}

private fun selectCardinalR22(operands: NumberOperands): String {
    if ((1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) || (0 <= operands.operandLong("i") && operands.operandLong("i") <= 0 && !(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0))) return "one"
    if (2 <= operands.operandLong("i") && operands.operandLong("i") <= 2 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "two"
    return "other"
}

private fun selectCardinalR23(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("t") && operands.operandLong("t") <= 0 && 1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1 && !(11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 11)) || (1 <= (operands.operandLong("t") % 10) && (operands.operandLong("t") % 10) <= 1 && !(11 <= (operands.operandLong("t") % 100) && (operands.operandLong("t") % 100) <= 11))) return "one"
    return "other"
}

private fun selectCardinalR24(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    return "other"
}

private fun selectCardinalR25(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) return "zero"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if ((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 2.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 22.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 22.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 42.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 42.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 62.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 62.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 82.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 82.0)) || ((operands.operandDouble("n") % 1000.0) % 1.0 == 0.0 && 0.0 <= (operands.operandDouble("n") % 1000.0) && (operands.operandDouble("n") % 1000.0) <= 0.0 && (((operands.operandDouble("n") % 100000.0) % 1.0 == 0.0 && 1000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 20000.0) || ((operands.operandDouble("n") % 100000.0) % 1.0 == 0.0 && 40000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 40000.0) || ((operands.operandDouble("n") % 100000.0) % 1.0 == 0.0 && 60000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 60000.0) || ((operands.operandDouble("n") % 100000.0) % 1.0 == 0.0 && 80000.0 <= (operands.operandDouble("n") % 100000.0) && (operands.operandDouble("n") % 100000.0) <= 80000.0))) || (!(operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) && (operands.operandDouble("n") % 1000000.0) % 1.0 == 0.0 && 100000.0 <= (operands.operandDouble("n") % 1000000.0) && (operands.operandDouble("n") % 1000000.0) <= 100000.0)) return "two"
    if (((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 3.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 23.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 23.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 43.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 43.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 63.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 63.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 83.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 83.0)) return "few"
    if (!(operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) && (((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 1.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 21.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 21.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 41.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 41.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 61.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 61.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 81.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 81.0))) return "many"
    return "other"
}

private fun selectCardinalR26(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) return "zero"
    if (((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1)) && !(operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0)) return "one"
    return "other"
}

private fun selectCardinalR27(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) return "one"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) return "few"
    if (!(0 <= operands.operandLong("f") && operands.operandLong("f") <= 0)) return "many"
    return "other"
}

private fun selectCardinalR28(operands: NumberOperands): String {
    if (((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) || (2 <= operands.operandLong("v") && operands.operandLong("v") <= 2 && 11 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 19)) return "zero"
    if (((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) || (2 <= operands.operandLong("v") && operands.operandLong("v") <= 2 && 1 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 1 && !(11 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 11)) || (!(2 <= operands.operandLong("v") && operands.operandLong("v") <= 2) && 1 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 1)) return "one"
    return "other"
}

private fun selectCardinalR29(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1 && !(11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 11)) || (1 <= (operands.operandLong("f") % 10) && (operands.operandLong("f") % 10) <= 1 && !(11 <= (operands.operandLong("f") % 100) && (operands.operandLong("f") % 100) <= 11))) return "one"
    return "other"
}

private fun selectCardinalR30(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "one"
    if ((!(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0)) || (operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (!(operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) && (operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) return "few"
    return "other"
}

private fun selectCardinalR31(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 10.0)) return "few"
    if ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0) return "many"
    return "other"
}

private fun selectCardinalR32(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) return "one"
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 4 && !(12 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 14)) return "few"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && !(1 <= operands.operandLong("i") && operands.operandLong("i") <= 1) && 0 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1) || (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 5 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 9) || (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 12 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 14)) return "many"
    return "other"
}

private fun selectCardinalR33(operands: NumberOperands): String {
    if (0 <= operands.operandLong("i") && operands.operandLong("i") <= 1) return "one"
    if ((0 <= operands.operandLong("e") && operands.operandLong("e") <= 0 && !(0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) && 0 <= (operands.operandLong("i") % 1000000) && (operands.operandLong("i") % 1000000) <= 0 && 0 <= operands.operandLong("v") && operands.operandLong("v") <= 0) || (!(0 <= operands.operandLong("e") && operands.operandLong("e") <= 5))) return "many"
    return "other"
}

private fun selectCardinalR34(operands: NumberOperands): String {
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1 && !(11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 11)) return "one"
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 4 && !(12 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 14)) return "few"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 0 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 0) || (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 5 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 9) || (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 14)) return "many"
    return "other"
}

private fun selectCardinalR35(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if (!(operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) && (operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 19.0)) return "few"
    if (!(0 <= operands.operandLong("f") && operands.operandLong("f") <= 0)) return "many"
    return "other"
}

private fun selectCardinalR36(operands: NumberOperands): String {
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0) return "few"
    return "other"
}

private fun selectCardinalR37(operands: NumberOperands): String {
    if (((operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0)) || (0 <= operands.operandLong("i") && operands.operandLong("i") <= 0 && 1 <= operands.operandLong("f") && operands.operandLong("f") <= 1)) return "one"
    return "other"
}

private fun selectCardinalR38(operands: NumberOperands): String {
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 1 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 1) return "one"
    if (0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 2 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 2) return "two"
    if ((0 <= operands.operandLong("v") && operands.operandLong("v") <= 0 && 3 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 4) || (!(0 <= operands.operandLong("v") && operands.operandLong("v") <= 0))) return "few"
    return "other"
}

private fun selectCardinalR39(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 99.0)) return "one"
    return "other"
}

private fun selectOrdinalR0(_operands: NumberOperands): String {
    return "other"
}

private fun selectOrdinalR1(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0)) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "few"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) return "many"
    return "other"
}

private fun selectOrdinalR2(operands: NumberOperands): String {
    if (((1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1) || (2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 2) || (5 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 5) || (7 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 7) || (8 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 8)) || ((20 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 20) || (50 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 50) || (70 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 70) || (80 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 80))) return "one"
    if (((3 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 3) || (4 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 4)) || ((100 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 100) || (200 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 200) || (300 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 300) || (400 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 400) || (500 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 500) || (600 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 600) || (700 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 700) || (800 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 800) || (900 <= (operands.operandLong("i") % 1000) && (operands.operandLong("i") % 1000) <= 900))) return "few"
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || (6 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 6) || ((40 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 40) || (60 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 60) || (90 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 90))) return "many"
    return "other"
}

private fun selectOrdinalR3(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    return "other"
}

private fun selectOrdinalR4(operands: NumberOperands): String {
    if ((((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0)) && !((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)))) return "few"
    return "other"
}

private fun selectOrdinalR5(operands: NumberOperands): String {
    if (0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) return "zero"
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1) return "one"
    if ((2 <= operands.operandLong("i") && operands.operandLong("i") <= 2) || (3 <= operands.operandLong("i") && operands.operandLong("i") <= 3) || (4 <= operands.operandLong("i") && operands.operandLong("i") <= 4) || (5 <= operands.operandLong("i") && operands.operandLong("i") <= 5) || (6 <= operands.operandLong("i") && operands.operandLong("i") <= 6)) return "few"
    return "other"
}

private fun selectOrdinalR6(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "few"
    return "other"
}

private fun selectOrdinalR7(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 7.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 9.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0)) return "zero"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) return "two"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0)) return "few"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0)) return "many"
    return "other"
}

private fun selectOrdinalR8(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0)) return "one"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0)) return "two"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)) return "few"
    return "other"
}

private fun selectOrdinalR9(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0)) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 12.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 12.0)) return "two"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 13.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 13.0)) return "few"
    return "other"
}

private fun selectOrdinalR10(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "few"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) return "many"
    return "other"
}

private fun selectOrdinalR11(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0)) return "one"
    return "other"
}

private fun selectOrdinalR12(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 80.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 800.0)) return "many"
    return "other"
}

private fun selectOrdinalR13(operands: NumberOperands): String {
    if (1 <= operands.operandLong("i") && operands.operandLong("i") <= 1) return "one"
    if ((0 <= operands.operandLong("i") && operands.operandLong("i") <= 0) || ((2 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 20) || (40 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 40) || (60 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 60) || (80 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 80))) return "many"
    return "other"
}

private fun selectOrdinalR14(operands: NumberOperands): String {
    if (((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 6.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 6.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 0.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 0.0 && !(operands.operandDouble("n") % 1.0 == 0.0 && 0.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 0.0))) return "many"
    return "other"
}

private fun selectOrdinalR15(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "few"
    return "other"
}

private fun selectOrdinalR16(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) || (((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 4.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 21.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 24.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 41.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 44.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 61.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 64.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 81.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 84.0))) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 5.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 5.0)) return "many"
    return "other"
}

private fun selectOrdinalR17(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 11.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 11.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 8.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 8.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 80.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 89.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 800.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 899.0)) return "many"
    return "other"
}

private fun selectOrdinalR18(operands: NumberOperands): String {
    if (1 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 1 && !(11 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 11)) return "one"
    if (2 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 2 && !(12 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 12)) return "two"
    if (((7 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 7) || (8 <= (operands.operandLong("i") % 10) && (operands.operandLong("i") % 10) <= 8)) && !(((17 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 17) || (18 <= (operands.operandLong("i") % 100) && (operands.operandLong("i") % 100) <= 18)))) return "many"
    return "other"
}

private fun selectOrdinalR19(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "one"
    return "other"
}

private fun selectOrdinalR20(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 5.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 5.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 7.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 9.0)) return "one"
    if ((operands.operandDouble("n") % 1.0 == 0.0 && 2.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 2.0) || (operands.operandDouble("n") % 1.0 == 0.0 && 3.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 3.0)) return "two"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 4.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 4.0) return "few"
    if (operands.operandDouble("n") % 1.0 == 0.0 && 6.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 6.0) return "many"
    return "other"
}

private fun selectOrdinalR21(operands: NumberOperands): String {
    if (operands.operandDouble("n") % 1.0 == 0.0 && 1.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 1.0) return "one"
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 4.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 4.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 14.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 14.0)) return "many"
    return "other"
}

private fun selectOrdinalR22(operands: NumberOperands): String {
    if ((((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 1.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 1.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 2.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 2.0)) && !((((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 11.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 11.0) || ((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 12.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 12.0)))) return "one"
    return "other"
}

private fun selectOrdinalR23(operands: NumberOperands): String {
    if ((((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 6.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 6.0) || ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 9.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 9.0)) || (operands.operandDouble("n") % 1.0 == 0.0 && 10.0 <= operands.operandDouble("n") && operands.operandDouble("n") <= 10.0)) return "few"
    return "other"
}

private fun selectOrdinalR24(operands: NumberOperands): String {
    if ((operands.operandDouble("n") % 10.0) % 1.0 == 0.0 && 3.0 <= (operands.operandDouble("n") % 10.0) && (operands.operandDouble("n") % 10.0) <= 3.0 && !((operands.operandDouble("n") % 100.0) % 1.0 == 0.0 && 13.0 <= (operands.operandDouble("n") % 100.0) && (operands.operandDouble("n") % 100.0) <= 13.0)) return "few"
    return "other"
}
