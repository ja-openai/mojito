package com.box.l10n.mojito.translationintegrity.literal;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import java.util.regex.Pattern;

/** Evaluates immutable URL literals with the downstream Python compatibility regex. */
public final class UrlLiteralTranslationIntegrityEvaluator {

  private static final Pattern URL_PATTERN =
      Pattern.compile("https?://[a-zA-Z0-9.]+\\.[a-zA-Z]{2,}[a-zA-Z0-9/_\\-?#+%]*");

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return LiteralMultisetTranslationIntegrityEvaluator.evaluate(
        extractUrls(source), extractUrls(target), "immutable-url-missing", "immutable-url-extra");
  }

  static List<String> extractUrls(String message) {
    return LiteralMultisetTranslationIntegrityEvaluator.extract(message, "http", URL_PATTERN);
  }
}
