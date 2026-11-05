package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks named Valdi placeholders, including their optional string or integer suffix. */
public class ValdiIntegrityChecker extends AbstractTextUnitIntegrityChecker {
  // A bare % after a name also matches, so a malformed typed token produces a diagnostic.
  private static final Pattern TOKEN = Pattern.compile("\\{[a-zA-Z0-9_]+(?:(?:%[sd])?\\}|%)");

  @Override
  public void check(String sourceContent, String targetContent) {
    Map<String, Integer> expected = placeholders(sourceContent);
    Map<String, Integer> found = placeholders(targetContent);
    if (!expected.equals(found)) {
      throw new IntegrityCheckException(
          "Valdi placeholders differ: expected " + expected + ", found " + found);
    }
  }

  private static Map<String, Integer> placeholders(String text) {
    Map<String, Integer> counts = new TreeMap<>();
    Matcher token = TOKEN.matcher(text);
    while (token.find()) {
      String placeholder = token.group();
      if (placeholder.endsWith("%")) {
        throw new IntegrityCheckException("Invalid Valdi format suffix at index " + token.start());
      }
      counts.merge(placeholder, 1, Integer::sum);
    }
    return counts;
  }
}
