package com.box.l10n.mojito.cldr;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;
import com.ibm.icu.text.PluralRules;
import java.util.Set;
import org.junit.Test;

public class PluralRuleServiceTest {

  @Test
  public void preservesPinnedKeywordsForIcuUpgrade() {
    Set<String> oneOther = ImmutableSet.of("one", "other");

    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("ca"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("es"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("fr"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("fr-FR"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("it"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("pt"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("pt-BR"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("pt-PT"));
    assertEquals(oneOther, PluralRuleService.getKeywordsForLanguageTag("scn"));

    assertEquals(
        ImmutableSet.of("few", "many", "one", "other"),
        PluralRuleService.getKeywordsForLanguageTag("mt"));
    assertEquals(
        ImmutableSet.of("many", "one", "two", "other"),
        PluralRuleService.getKeywordsForLanguageTag("he"));
    assertEquals(
        ImmutableSet.of("many", "one", "two", "other"),
        PluralRuleService.getKeywordsForLanguageTag("iw"));
  }

  @Test
  public void messageFormatsUseCurrentIcuCategoriesAndPluralType() {
    assertEquals(
        Set.of("one", "many", "other"),
        PluralRuleService.getMessageFormatKeywordsForLanguageTag(
            "fr_FR", PluralRules.PluralType.CARDINAL));
    assertEquals(
        Set.of("one", "two", "other"),
        PluralRuleService.getMessageFormatKeywordsForLanguageTag(
            "he", PluralRules.PluralType.CARDINAL));
    assertEquals(
        Set.of("one", "two", "few", "other"),
        PluralRuleService.getMessageFormatKeywordsForLanguageTag(
            "en-US", PluralRules.PluralType.ORDINAL));
  }

  @Test
  public void messageFormatsDoNotInventCategoriesForUnknownLocales() {
    for (String locale : new String[] {null, "", "und", "zz-ZZ"}) {
      assertEquals(
          Set.of(),
          PluralRuleService.getMessageFormatKeywordsForLanguageTag(
              locale, PluralRules.PluralType.CARDINAL));
    }
  }
}
