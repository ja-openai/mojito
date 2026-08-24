package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

public class MarkdownLinkIntegrityChecker extends RegexIntegrityChecker {

  @Override
  public String getRegex() {
    return "\\[(?<text>.+?)]\\((?<url>.+?)\\)";
  }

  @Override
  Set<String> getPlaceholders(String string) {
    Set<String> placeholders = new LinkedHashSet<>();

    if (string != null) {
      Matcher matcher = getPattern().matcher(string);
      while (matcher.find()) {
        placeholders.add("[%s](%s)".formatted("--translatable--", matcher.group("url")));
      }
    }
    return placeholders;
  }

  @Override
  public void check(String content, String target) {
    if (!getPlaceholderCounts(content).equals(getPlaceholderCounts(target))) {
      throw new MarkdownLinkIntegrityCheckerException("Markdown Links do not match.");
    }
  }

  private Map<String, Integer> getPlaceholderCounts(String string) {
    Map<String, Integer> placeholders = new LinkedHashMap<>();
    if (string != null) {
      Matcher matcher = getPattern().matcher(string);
      while (matcher.find()) {
        String placeholder = "[%s](%s)".formatted("--translatable--", matcher.group("url"));
        placeholders.merge(placeholder, 1, Integer::sum);
      }
    }
    return placeholders;
  }
}
