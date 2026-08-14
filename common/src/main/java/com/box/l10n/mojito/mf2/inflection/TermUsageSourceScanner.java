package com.box.l10n.mojito.mf2.inflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Source-order scanner for MF2 `{$arg :term ...}` expressions and their Java-string spans. */
final class TermUsageSourceScanner {

  private TermUsageSourceScanner() {}

  static List<ScannedTermUsage> scan(String message) {
    Objects.requireNonNull(message, "message");
    List<ScannedTermUsage> usages = new ArrayList<>();
    int index = 0;
    while (index < message.length()) {
      int start = message.indexOf('{', index);
      if (start < 0) {
        break;
      }
      ScannedTermUsage usage = scanAt(message, start);
      if (usage == null) {
        index = start + 1;
      } else {
        usages.add(usage);
        index = usage.end();
      }
    }
    return List.copyOf(usages);
  }

  private static ScannedTermUsage scanAt(String message, int start) {
    int cursor = start + 1;
    if (cursor >= message.length() || message.charAt(cursor) != '$') {
      return null;
    }
    cursor++;
    if (cursor >= message.length() || !isNameStart(message.charAt(cursor))) {
      return null;
    }

    int argumentStart = cursor;
    cursor++;
    while (cursor < message.length() && isNamePart(message.charAt(cursor))) {
      cursor++;
    }
    String argument = message.substring(argumentStart, cursor);

    cursor = skipWhitespace(message, cursor);
    if (!message.startsWith(":term", cursor)) {
      return null;
    }
    cursor += ":term".length();
    if (cursor < message.length()
        && !Character.isWhitespace(message.charAt(cursor))
        && message.charAt(cursor) != '}') {
      return null;
    }

    int optionsStart = cursor;
    int end = findExpressionEnd(message, cursor);
    if (end < 0) {
      end = message.indexOf('}', cursor);
      if (end < 0) {
        return null;
      }
    }
    return new ScannedTermUsage(argument, message.substring(optionsStart, end), start, end + 1);
  }

  private static int findExpressionEnd(String message, int cursor) {
    Character quote = null;
    boolean escaped = false;
    for (int i = cursor; i < message.length(); i++) {
      char current = message.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (quote != null) {
        if (current == '\\') {
          escaped = true;
        } else if (current == quote) {
          quote = null;
        }
        continue;
      }
      if (current == '"' || current == '|') {
        quote = current;
      } else if (current == '}') {
        return i;
      } else if (current == '{') {
        return -1;
      }
    }
    return -1;
  }

  private static int skipWhitespace(String message, int cursor) {
    while (cursor < message.length() && Character.isWhitespace(message.charAt(cursor))) {
      cursor++;
    }
    return cursor;
  }

  private static boolean isNameStart(char value) {
    return value == '_' || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
  }

  private static boolean isNamePart(char value) {
    return isNameStart(value) || (value >= '0' && value <= '9') || value == '.' || value == '-';
  }

  record ScannedTermUsage(String argument, String rawOptions, int start, int end) {}
}
