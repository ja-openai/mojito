package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.dollartemplate.DollarTemplateTranslationIntegrityEvaluator;
import com.box.l10n.mojito.translationintegrity.dollartemplate.DollarTemplateTranslationIntegrityOptions;

/** Applies the retained dollar-template profile as a prevention-only checker. */
public class DollarTemplateTranslationIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  private static final DollarTemplateTranslationIntegrityEvaluator EVALUATOR =
      new DollarTemplateTranslationIntegrityEvaluator();
  private static final DollarTemplateTranslationIntegrityOptions OPTIONS =
      DollarTemplateTranslationIntegrityOptions.common();

  @Override
  public void check(String sourceContent, String targetContent) throws IntegrityCheckException {
    TranslationIntegrityEvaluation evaluation =
        EVALUATOR.evaluate(sourceContent, targetContent, OPTIONS);
    TranslationIntegrityCheckerException.throwIfTargetRejected("DOLLAR_TEMPLATE", evaluation);
  }
}
