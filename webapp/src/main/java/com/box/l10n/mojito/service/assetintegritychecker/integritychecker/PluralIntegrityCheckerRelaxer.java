package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

@Component
public class PluralIntegrityCheckerRelaxer {

  /**
   * This is very ad hoc!
   *
   * <p>There are cases where a non-{@code other} plural form does not retain the formatted count.
   * Relax only one omitted placeholder occurrence. Added placeholders, type substitutions, and
   * multiple omissions still fail. Use the checker class to keep this exception constrained to
   * placeholder checks.
   */
  public boolean shouldRelaxIntegrityCheck(
      String source, String target, String pluralForm, TextUnitIntegrityChecker textUnitChecker) {

    boolean shouldRelax = false;

    if (pluralForm != null && !"other".equals(pluralForm)) {
      if (textUnitChecker instanceof PrintfLikeIntegrityChecker
          || textUnitChecker instanceof PrintfLikeIgnorePercentageAfterBracketsIntegrityChecker
          || textUnitChecker instanceof PrintfLikeVariableTypeIntegrityChecker
          || textUnitChecker instanceof SimplePrintfLikeIntegrityChecker) {

        RegexIntegrityChecker regexIntegrityChecker = (RegexIntegrityChecker) textUnitChecker;
        Map<String, Integer> sourcePlaceholders =
            getPlaceholderCounts(source, regexIntegrityChecker);
        Map<String, Integer> targetPlaceholders =
            getPlaceholderCounts(target, regexIntegrityChecker);

        int omittedOccurrences = 0;
        for (Map.Entry<String, Integer> targetPlaceholder : targetPlaceholders.entrySet()) {
          if (targetPlaceholder.getValue()
              > sourcePlaceholders.getOrDefault(targetPlaceholder.getKey(), 0)) {
            return false;
          }
        }
        for (Map.Entry<String, Integer> sourcePlaceholder : sourcePlaceholders.entrySet()) {
          omittedOccurrences +=
              sourcePlaceholder.getValue()
                  - targetPlaceholders.getOrDefault(sourcePlaceholder.getKey(), 0);
        }
        shouldRelax = omittedOccurrences == 1;
      }
    }

    return shouldRelax;
  }

  private Map<String, Integer> getPlaceholderCounts(
      String content, RegexIntegrityChecker integrityChecker) {
    Map<String, Integer> placeholders = new LinkedHashMap<>();
    if (content != null) {
      Matcher matcher = integrityChecker.getPattern().matcher(content);
      while (matcher.find()) {
        placeholders.merge(matcher.group(), 1, Integer::sum);
      }
    }
    return placeholders;
  }
}
