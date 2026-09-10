package com.box.l10n.mojito.mf2

import java.util.Locale

internal fun localeIsLtr(locale: String): Boolean {
    val subtags = locale.replace('_', '-').split('-')
    val language = subtags[0].lowercase(Locale.ROOT)
    if (!language.matches(Regex("[a-z]{2,8}"))) return false
    var script: String? = null
    var region: String? = null
    for (subtag in subtags.drop(1)) {
        if (subtag.length == 1) break
        if (!subtag.matches(Regex("[A-Za-z0-9]{2,8}"))) return false
        if (script == null && subtag.matches(Regex("[A-Za-z]{4}"))) {
            script = subtag.take(1).uppercase(Locale.ROOT) + subtag.drop(1).lowercase(Locale.ROOT)
        } else if (region == null && subtag.matches(Regex("[A-Za-z]{2}|[0-9]{3}"))) {
            region = subtag.uppercase(Locale.ROOT)
        }
    }
    fun contains(table: String, value: String) = table.contains(" $value ")
    if (script != null) return contains(LocaleDirectionData.LTR_SCRIPTS, script)
    if (region != null) {
        val key = "$language-$region"
        if (contains(LocaleDirectionData.RTL_REGION_OVERRIDES, key)) return false
        if (contains(LocaleDirectionData.LTR_REGION_OVERRIDES, key)) return true
    }
    return !(language == "und" && region == null) && contains(LocaleDirectionData.LTR_LANGUAGES, language)
}
