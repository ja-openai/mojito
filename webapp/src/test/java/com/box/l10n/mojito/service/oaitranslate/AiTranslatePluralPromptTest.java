package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import org.junit.jupiter.api.Test;

class AiTranslatePluralPromptTest {

  @Test
  void cardinalPromptNamesEveryArabicCategoryAndProtectsIcuSyntax() {
    String prompt =
        prompt("{n, plural, offset:1 =0 {Nobody} one {One person} other {# people}}", "ar");

    assertTrue(prompt.contains("target locale ar:"));
    assertTrue(
        prompt.contains(
            "cardinal plural rules have 6 categories: zero, one, two, few, many, other"));
    assertTrue(prompt.contains("even when absent from the source"));
    assertTrue(prompt.contains("exact-number branches, offsets"));
    assertTrue(prompt.contains("ICU other branches and # placeholders"));
  }

  @Test
  void nestedCardinalAndOrdinalAreDetectedSeparately() {
    String prompt =
        prompt(
            "{group, select, selected {{n, plural, one {One} other {#}}} other {None}} "
                + "{rank, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}",
            "en");

    assertTrue(prompt.contains("cardinal plural rules have 2 categories: one, other"));
    assertTrue(prompt.contains("ordinal plural rules have 4 categories: one, two, few, other"));
  }

  @Test
  void usesCurrentIcuCategoriesInsteadOfLegacyPoOverrides() {
    String prompt = prompt("{n, plural, one {Un} other {Plusieurs}}", "fr-FR");

    assertTrue(prompt.contains("cardinal plural rules have 3 categories: one, many, other"));
  }

  @Test
  void acceptsIcuFormattingAndArbitraryMarkupAroundPlurals() {
    String prompt =
        prompt(
            "<link href='/help'>{n, plural, one {One} other {#}}</link> {date, date, yyyy-MM-dd} {value, custom, style}",
            "ja");

    assertTrue(prompt.contains("cardinal plural rules have 1 category: other"));
  }

  @Test
  void skipsPlainQuotedSelectOnlyAndMalformedIcuSources() {
    for (String source :
        new String[] {
          "There are many plural forms",
          "Hello {name}",
          "'{n, plural, one {One} other {Many}}'",
          "{gender, select, one {One} other {Other}}",
          "{n, plural, one {One}}"
        }) {
      assertNull(prompt(source, "ar"), source);
    }
  }

  @Test
  void skipsMissingOrUnsupportedLocaleAndMissingSource() {
    String source = "{n, plural, one {One} other {Many}}";
    assertNull(prompt(source, null));
    assertNull(prompt(source, " "));
    assertNull(prompt(source, "und"));
    assertNull(prompt(source, "zz-ZZ"));
    assertNull(prompt(null, "ar"));
    assertNull(AiTranslatePluralPrompt.getPromptSuffix(null, "ar"));
  }

  @Test
  void mf2NumericSelectorAddsCategoriesAndPreservesFallbackAndContext() {
    String prompt = prompt(".input {$n :number}\n.match $n\n1 {{One}}\n* {{{$n} things}}", "ar");

    assertTrue(prompt.contains("cardinal plural rules have 6 categories"));
    assertTrue(prompt.contains("* fallback, which covers the other category"));
    assertTrue(prompt.contains("each relevant selector context"));
    assertFalse(prompt.contains("ICU other branches"));
  }

  @Test
  void mf2OrdinalResolvesThroughLocalAliases() {
    String prompt =
        prompt(
            ".input {$rank :number select=|ordinal|}\n.local $position = {$rank}\n"
                + ".local $alias = {$position}\n.match $alias\none {{First}}\n* {{Later}}",
            "en");

    assertTrue(prompt.contains("ordinal plural rules have 4 categories: one, two, few, other"));
    assertFalse(prompt.contains("cardinal plural rules"));
  }

  @Test
  void mf2NumericFunctionsAndPluralSelectionAliasAreRecognized() {
    for (String function : new String[] {"number", "integer", "percent", "offset"}) {
      String prompt =
          prompt(
              ".input {$n :" + function + " select=plural}\n.match $n\none {{One}}\n* {{Many}}",
              "ar");
      assertTrue(prompt.contains("cardinal plural rules have 6 categories"), function);
    }
  }

  @Test
  void mf2MixedSelectorsIncludeOnlyKnownPluralRequirements() {
    String prompt =
        prompt(
            ".input {$n :number}\n.input {$rank :integer select=ordinal}\n.input {$kind :string}\n"
                + ".match $n $rank $kind\none one a {{First}}\n* * * {{Others}}",
            "en");

    assertTrue(prompt.contains("cardinal plural rules have 2 categories"));
    assertTrue(prompt.contains("ordinal plural rules have 4 categories"));
    assertTrue(prompt.contains("non-plural selector values"));
  }

  @Test
  void mf2SkipsNonPluralUnknownAndInvalidSelectors() {
    for (String annotation :
        new String[] {
          ":string",
          ":custom",
          ":number select=exact",
          ":number select=$kind",
          ":number select=unknown"
        }) {
      assertNull(
          prompt(".input {$n " + annotation + "}\n.match $n\none {{One}}\n* {{Many}}", "ar"),
          annotation);
    }
    assertNull(prompt(".input {$n :number}\n{{You have {$n} items}}", "ar"));
    assertNull(prompt(".input {$n :number}\n.match $n\none {{One}}", "ar"));
  }

  @Test
  void mf2DetectionAcceptsByteOrderMarkAndLocaleUnderscores() {
    String prompt =
        prompt("\uFEFF.input {$n :number}\n.match $n\none {{One}}\n* {{Others}}", "fr_FR");
    assertTrue(prompt.contains("cardinal plural rules have 3 categories: one, many, other"));
  }

  private static String prompt(String source, String locale) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setSource(source);
    return AiTranslatePluralPrompt.getPromptSuffix(textUnit, locale);
  }
}
