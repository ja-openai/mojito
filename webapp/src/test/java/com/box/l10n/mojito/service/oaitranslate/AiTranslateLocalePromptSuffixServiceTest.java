package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public class AiTranslateLocalePromptSuffixServiceTest extends ServiceTestBase {

  @Autowired AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService;

  @Autowired AiTranslateLocalePromptSuffixRepository aiTranslateLocalePromptSuffixRepository;

  @Test
  @Transactional
  public void testUpsertUpdatesExistingLocaleWithoutAddingDuplicateRows() {
    int initialCount = aiTranslateLocalePromptSuffixService.getAll().size();

    var created = aiTranslateLocalePromptSuffixService.upsert("fr-FR", "Use Canadian French tone.");
    assertEquals("fr-FR", created.localeTag());
    assertEquals("Use Canadian French tone.", created.promptSuffix());
    assertNotNull(
        aiTranslateLocalePromptSuffixRepository.findByLocaleBcp47TagIgnoreCase("fr-FR"));

    var updated =
        aiTranslateLocalePromptSuffixService.upsert("fr-FR", "Prefer concise product language.");
    assertEquals("fr-FR", updated.localeTag());
    assertEquals("Prefer concise product language.", updated.promptSuffix());

    assertEquals(initialCount + 1, aiTranslateLocalePromptSuffixService.getAll().size());
    assertEquals(
        "Prefer concise product language.",
        aiTranslateLocalePromptSuffixRepository
            .findByLocaleBcp47TagIgnoreCase("fr-FR")
            .getPromptSuffix());
  }

  @Test
  @Transactional
  public void testDeleteRemovesLocalePromptSuffix() {
    aiTranslateLocalePromptSuffixService.upsert("ja-JP", "Keep honorifics neutral.");
    assertNotNull(
        aiTranslateLocalePromptSuffixRepository.findByLocaleBcp47TagIgnoreCase("ja-JP"));

    aiTranslateLocalePromptSuffixService.delete("ja-JP");

    assertNull(aiTranslateLocalePromptSuffixRepository.findByLocaleBcp47TagIgnoreCase("ja-JP"));
  }
}
