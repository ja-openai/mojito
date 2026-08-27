package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import org.junit.Test;

public class DollarTemplateTranslationIntegrityCheckerTest {

  private final DollarTemplateTranslationIntegrityChecker checker =
      new DollarTemplateTranslationIntegrityChecker();

  @Test
  public void acceptsEquivalentPlaceholderSpellings() {
    checker.check("Hello $name", "Bonjour ${name}");
  }

  @Test
  public void rejectsTargetContractFailure() {
    TranslationIntegrityCheckerException exception =
        assertThrows(
            TranslationIntegrityCheckerException.class,
            () -> checker.check("Hello $name", "Bonjour $other"));

    assertEquals(
        TranslationIntegrityDisposition.REJECT_TARGET, exception.getEvaluation().disposition());
    assertEquals(
        "DOLLAR_TEMPLATE translation integrity rejected target [REJECT_TARGET]: "
            + "variable-extra, variable-missing",
        exception.getMessage());
  }
}
