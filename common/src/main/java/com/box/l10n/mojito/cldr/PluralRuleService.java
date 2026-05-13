package com.box.l10n.mojito.cldr;

import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.util.Locale;
import java.util.Set;

/**
 * Centralizes CLDR plural keyword lookups so ICU/CLDR upgrades can be managed deliberately.
 *
 * <p>ICU keyword sets are data, not just implementation detail. Upgrading ICU can add or remove
 * CLDR forms for existing locales while other file formats, especially gettext PO, keep different
 * plural formulas. Keep call sites behind this wrapper so Mojito can pin or override keyword policy
 * in one place when those differences matter.
 */
public class PluralRuleService {

  public static Set<String> getKeywords(Locale locale) {
    return PluralRules.forLocale(locale).getKeywords();
  }

  public static Set<String> getKeywords(ULocale locale) {
    return PluralRules.forLocale(locale).getKeywords();
  }

  public static Set<String> getKeywordsForLanguageTag(String bcp47Tag) {
    return getKeywords(ULocale.forLanguageTag(bcp47Tag));
  }
}
