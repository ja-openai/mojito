package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Checks for URLs in plain text.
 *
 * <p>Regex is simple and might under match but that's preferable too triggering false positive
 *
 * <p>Consider using another checker for format with links like Markdown:
 * MarkdownLinkIntegrityChecker
 *
 * @author jaurambault
 */
public class URLIntegrityChecker extends RegexIntegrityChecker {

  @Override
  Set<String> getPlaceholders(String string) {
    Set<String> normalizedPlaceholders = new LinkedHashSet<>();
    for (String placeholder : super.getPlaceholders(string)) {
      normalizedPlaceholders.add(trimTrailingSentencePunctuation(placeholder));
    }
    return normalizedPlaceholders;
  }

  @Override
  public String getRegex() {
    return "(https?|ftp)://(?:www\\.)?(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[a-zA-Z0-9/_\\.\\-?#+%]*)*|mailto:[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
  }

  @Override
  public void check(String sourceContent, String targetContent)
      throws CompositeFormatIntegrityCheckerException {
    try {
      super.check(sourceContent, targetContent);
    } catch (RegexCheckerException rce) {
      throw new URLIntegrityCheckerException("URLs in source and target are different");
    }
  }

  private String trimTrailingSentencePunctuation(String placeholder) {
    int end = placeholder.length();
    while (end > 0) {
      char c = placeholder.charAt(end - 1);
      if (c == '.' || c == '。') {
        end--;
      } else {
        break;
      }
    }
    return placeholder.substring(0, end);
  }
}
