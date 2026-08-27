package com.box.l10n.mojito.translationintegrity.literal;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import java.util.regex.Pattern;

/** Evaluates immutable email literals with the downstream Python compatibility regex. */
public final class EmailLiteralTranslationIntegrityEvaluator {

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return LiteralMultisetTranslationIntegrityEvaluator.evaluate(
        extractEmails(source),
        extractEmails(target),
        "immutable-email-missing",
        "immutable-email-extra");
  }

  static List<String> extractEmails(String message) {
    return LiteralMultisetTranslationIntegrityEvaluator.extract(message, "@", EMAIL_PATTERN);
  }
}
