package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static com.box.l10n.mojito.regex.PlaceholderRegularExpressions.PRINTF_LIKE_REGEX;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Checks that there are the same c-printf like placeholders in the source and target content, order
 * is not important.
 *
 * @author wyau
 */
public class PrintfLikeIntegrityChecker extends RegexIntegrityChecker {

  /**
   * Modified regex from Formatter#formatSpecifier = "%(\\d+\\$)?([-#+
   * 0,(\\<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])";
   * (%[argument_index$][flags][width][.precision][t]conversion)
   *
   * @return
   */
  @Override
  public String getRegex() {
    return PRINTF_LIKE_REGEX.getRegex();
  }

  @Override
  public void check(String sourceContent, String targetContent)
      throws PrintfLikeIntegrityCheckerException {
    if (!getPlaceholderCounts(sourceContent).equals(getPlaceholderCounts(targetContent))) {
      throw new PrintfLikeIntegrityCheckerException(
          "PrintfLike placeholders are different in source and target");
    }
  }

  private Map<String, Integer> getPlaceholderCounts(String content) {
    Map<String, Integer> placeholders = new LinkedHashMap<>();
    if (content != null) {
      Matcher matcher = getPattern().matcher(content);
      while (matcher.find()) {
        placeholders.merge(matcher.group(), 1, Integer::sum);
      }
    }
    return placeholders;
  }
}
