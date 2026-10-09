package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.whitespace.BoundaryWhitespaceTranslationIntegrityEvaluator;

/** Checks exact leading and trailing whitespace without modifying the translation. */
public class WhitespaceIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  private static final BoundaryWhitespaceTranslationIntegrityEvaluator EVALUATOR =
      new BoundaryWhitespaceTranslationIntegrityEvaluator();

  @Override
  public void check(String sourceContent, String targetContent)
      throws WhitespaceIntegrityCheckerException {
    TranslationIntegrityEvaluation evaluation = EVALUATOR.evaluate(sourceContent, targetContent);
    switch (evaluation.disposition()) {
        // A proposed repair is still a failure: integrity checks must not rewrite the target.
      case AUTO_REPAIR_TARGET, REJECT_TARGET ->
          throw new WhitespaceIntegrityCheckerException(
              "Leading or trailing whitespace differs between source and target");
      case PASS, EXEMPT, REJECT_SOURCE -> {
        // Match the shared checker policy: a source defect cannot be fixed by rejecting a target.
      }
    }
  }
}
