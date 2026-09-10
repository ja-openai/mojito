package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.box.l10n.mojito.translationintegrity.messageformat.Mf2TranslationIntegrityEvaluator;

/** Opt-in MF2 prevention checker. The repository/extension configuration declares the format. */
public final class Mf2TranslationIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  @Override
  public void check(String sourceContent, String targetContent) throws IntegrityCheckException {
    check(sourceContent, targetContent, null);
  }

  @Override
  public void check(String sourceContent, String targetContent, String targetLocale)
      throws IntegrityCheckException {
    TranslationIntegrityCheckerException.throwIfTargetRejected(
        "MF2",
        Mf2TranslationIntegrityEvaluator.evaluate(sourceContent, targetContent, targetLocale));
  }
}
