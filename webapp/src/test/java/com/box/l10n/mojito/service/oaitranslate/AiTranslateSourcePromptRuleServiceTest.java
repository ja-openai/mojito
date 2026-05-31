package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;

public class AiTranslateSourcePromptRuleServiceTest {

  AiTranslateSourcePromptRuleService aiTranslateSourcePromptRuleService =
      new AiTranslateSourcePromptRuleService(
          mock(AiTranslateSourcePromptRuleRepository.class),
          mock(PlatformTransactionManager.class));

  @Test
  public void testMatchesSourceText() {
    var activeRules =
        List.of(
            new AiTranslateSourcePromptRuleService.ActiveSourcePromptRule(
                1L,
                "Bracketed editable slots",
                10,
                "\\[[^\\]\\r\\n]+\\](?!\\()",
                "Preserve bracketed slots.",
                Pattern.compile("\\[[^\\]\\r\\n]+\\](?!\\()")));
    var matched =
        aiTranslateSourcePromptRuleService.matchPromptSuffixes(
            "I'm filling out [tax form] as [an individual/a business etc.].", activeRules);

    assertEquals(1, matched.ruleIds().size());
    assertEquals("Bracketed editable slots", matched.ruleNames().get(0));
    assertEquals("Preserve bracketed slots.", matched.promptSuffix());
  }

  @Test
  public void testBracketRegexDoesNotMatchMarkdownLinks() {
    var result =
        aiTranslateSourcePromptRuleService.test(
            "\\[[^\\]\\r\\n]+\\](?!\\()", "[Upload form](https://example.com)");

    assertFalse(result.matches());
    assertTrue(result.matchesList().isEmpty());
  }

  @Test
  public void testMultipleRulesCombineSuffixesByPriority() {
    var activeRules =
        List.of(
            new AiTranslateSourcePromptRuleService.ActiveSourcePromptRule(
                1L, "First", 10, "\\[first\\]", "First suffix.", Pattern.compile("\\[first\\]")),
            new AiTranslateSourcePromptRuleService.ActiveSourcePromptRule(
                2L,
                "Second",
                20,
                "\\[second\\]",
                "Second suffix.",
                Pattern.compile("\\[second\\]")));

    var matched =
        aiTranslateSourcePromptRuleService.matchPromptSuffixes("[first] [second]", activeRules);

    assertEquals("First suffix. Second suffix.", matched.promptSuffix());
    assertEquals("First", matched.ruleNames().get(0));
    assertEquals("Second", matched.ruleNames().get(1));
  }

  @Test
  public void testInvalidRegexIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            aiTranslateSourcePromptRuleService.upsert(
                new AiTranslateSourcePromptRuleService.SourcePromptRuleInput(
                    null, "Broken", null, true, 0, "REGEX", "[", "Suffix.")));
  }
}
