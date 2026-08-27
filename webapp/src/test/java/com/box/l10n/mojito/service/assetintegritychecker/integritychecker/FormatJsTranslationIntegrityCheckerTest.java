package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import org.junit.Test;

public class FormatJsTranslationIntegrityCheckerTest {

  private final FormatJsTranslationIntegrityChecker checker =
      new FormatJsTranslationIntegrityChecker();

  @Test
  public void acceptsValidTarget() {
    checker.check("Hello {name}", "Bonjour {name}");
  }

  @Test
  public void rejectsTargetContractFailure() {
    TranslationIntegrityCheckerException exception =
        assertThrows(
            TranslationIntegrityCheckerException.class,
            () -> checker.check("Hello {name}", "Bonjour {other}"));

    assertEquals(
        TranslationIntegrityDisposition.REJECT_TARGET, exception.getEvaluation().disposition());
    assertEquals(
        "FORMATJS translation integrity rejected target [REJECT_TARGET]: "
            + "variable-extra, variable-missing",
        exception.getMessage());
  }

  @Test
  public void rejectsRepairableTargetWithoutApplyingRepair() {
    String target = "Bonjour {name}";

    TranslationIntegrityCheckerException exception =
        assertThrows(
            TranslationIntegrityCheckerException.class,
            () -> checker.check(" Hello {name} ", target));

    assertEquals(
        TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
        exception.getEvaluation().disposition());
    assertNotNull(exception.getEvaluation().safeRepair());
    assertEquals("Bonjour {name}", target);
    assertEquals(
        "FORMATJS translation integrity rejected target [AUTO_REPAIR_TARGET]: "
            + "boundary-whitespace-mismatch",
        exception.getMessage());
  }

  @Test
  public void allowsTargetWhenSourceIsInvalid() {
    checker.check("Hello {name", "Bonjour {name}");
  }
}
