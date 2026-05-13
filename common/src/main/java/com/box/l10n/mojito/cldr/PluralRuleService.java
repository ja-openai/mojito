package com.box.l10n.mojito.cldr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

  private static final Set<String> ONE_OTHER_KEYWORDS = ImmutableSet.of("one", "other");

  private static final Map<String, Set<String>> KEYWORDS_BY_LANGUAGE_OVERRIDE =
      ImmutableMap.<String, Set<String>>builder()
          .put("ca", ONE_OTHER_KEYWORDS)
          .put("es", ONE_OTHER_KEYWORDS)
          .put("fr", ONE_OTHER_KEYWORDS)
          .put("it", ONE_OTHER_KEYWORDS)
          .put("pt", ONE_OTHER_KEYWORDS)
          .put("scn", ONE_OTHER_KEYWORDS)
          .put("mt", ImmutableSet.of("few", "many", "one", "other"))
          .put("he", ImmutableSet.of("many", "one", "two", "other"))
          .put("iw", ImmutableSet.of("many", "one", "two", "other"))
          .build();

  public static Set<String> getKeywords(Locale locale) {
    return getKeywordOverride(locale.toLanguageTag())
        .orElseGet(() -> PluralRules.forLocale(locale).getKeywords());
  }

  public static Set<String> getKeywords(ULocale locale) {
    return getKeywordOverride(locale.toLanguageTag())
        .orElseGet(() -> PluralRules.forLocale(locale).getKeywords());
  }

  public static Set<String> getKeywordsForLanguageTag(String bcp47Tag) {
    return getKeywords(ULocale.forLanguageTag(bcp47Tag));
  }

  private static Optional<Set<String>> getKeywordOverride(String bcp47Tag) {
    ULocale locale = ULocale.forLanguageTag(bcp47Tag);
    Set<String> keywords = KEYWORDS_BY_LANGUAGE_OVERRIDE.get(locale.getLanguage());
    return Optional.ofNullable(keywords);
  }
}
