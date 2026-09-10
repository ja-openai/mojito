package com.box.l10n.mojito.mf2;

import java.util.Locale;

/** Direction lookup from the same pinned CLDR tables in every portable runtime. */
final class LocaleDirection {
    private LocaleDirection() {}

    static boolean isLtr(String locale) {
        String[] subtags = locale.replace('_', '-').split("-", -1);
        String language = subtags[0].toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z]{2,8}")) return false;
        String script = null;
        String region = null;
        for (int index = 1; index < subtags.length; index++) {
            String subtag = subtags[index];
            if (subtag.length() == 1) break; // Unicode/private extensions do not name the message's script.
            if (!subtag.matches("[A-Za-z0-9]{2,8}")) return false;
            if (script == null && subtag.matches("[A-Za-z]{4}")) {
                script = subtag.substring(0, 1).toUpperCase(Locale.ROOT) + subtag.substring(1).toLowerCase(Locale.ROOT);
            } else if (region == null && subtag.matches("[A-Za-z]{2}|[0-9]{3}")) {
                region = subtag.toUpperCase(Locale.ROOT);
            }
        }
        if (script != null) return contains(LocaleDirectionData.LTR_SCRIPTS, script);
        if (region != null) {
            String key = language + "-" + region;
            if (contains(LocaleDirectionData.RTL_REGION_OVERRIDES, key)) return false;
            if (contains(LocaleDirectionData.LTR_REGION_OVERRIDES, key)) return true;
        }
        return !(language.equals("und") && region == null) && contains(LocaleDirectionData.LTR_LANGUAGES, language);
    }

    private static boolean contains(String table, String value) { return table.contains(" " + value + " "); }
}
