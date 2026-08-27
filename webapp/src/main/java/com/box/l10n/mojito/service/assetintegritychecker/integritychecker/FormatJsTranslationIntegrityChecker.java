package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsTranslationIntegrityEvaluator;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsTranslationIntegrityOptions;

/** Applies the retained FormatJS translation-integrity profile as a prevention-only checker. */
public class FormatJsTranslationIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  private static final FormatJsTranslationIntegrityEvaluator EVALUATOR =
      new FormatJsTranslationIntegrityEvaluator();
  private static final FormatJsTranslationIntegrityOptions OPTIONS =
      FormatJsTranslationIntegrityOptions.web();

  @Override
  public void check(String sourceContent, String targetContent) throws IntegrityCheckException {
    TranslationIntegrityEvaluation evaluation =
        EVALUATOR.evaluate(sourceContent, targetContent, OPTIONS);
    TranslationIntegrityCheckerException.throwIfTargetRejected("FORMATJS", evaluation);
  }
}
