package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TranslationIntegrityCheckerTypeTest {

  @Test
  public void registersFormatJsCheckerInBackendAndRestClient() {
    assertEquals(
        FormatJsTranslationIntegrityChecker.class.getName(),
        IntegrityCheckerType.FORMATJS.getClassName());
    assertEquals(
        IntegrityCheckerType.FORMATJS.name(),
        com.box.l10n.mojito.rest.entity.IntegrityCheckerType.FORMATJS.name());
  }

  @Test
  public void registersDollarTemplateCheckerInBackendAndRestClient() {
    assertEquals(
        DollarTemplateTranslationIntegrityChecker.class.getName(),
        IntegrityCheckerType.DOLLAR_TEMPLATE.getClassName());
    assertEquals(
        IntegrityCheckerType.DOLLAR_TEMPLATE.name(),
        com.box.l10n.mojito.rest.entity.IntegrityCheckerType.DOLLAR_TEMPLATE.name());
  }

  @Test
  public void preservesExistingFormatJsRichTextChecker() {
    assertEquals(
        FormatJsRichTextIntegrityChecker.class.getName(),
        IntegrityCheckerType.FORMATJS_RICH_TEXT.getClassName());
  }
}
