package com.box.l10n.mojito.fileformat;

import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.util.Set;

/** Runtime ICU cardinal categories without silent fallback for unsupported languages. */
final class IcuCardinalCategories {

  private IcuCardinalCategories() {}

  static Set<String> forLocale(String locale) {
    String normalized = locale.replace('_', '-');
    if (normalized.equals("und") || normalized.startsWith("und-")) {
      return Set.of();
    }
    ULocale language = ULocale.forLanguageTag(normalized);
    if (language.getLanguage().isEmpty()) {
      return Set.of();
    }
    boolean[] available = {false};
    PluralRules.getFunctionalEquivalent(new ULocale(language.getLanguage()), available);
    if (!available[0]) {
      return Set.of();
    }
    return Set.copyOf(
        PluralRules.forLocale(language, PluralRules.PluralType.CARDINAL).getKeywords());
  }
}
