package com.box.l10n.mojito.translationintegrity.literal;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates immutable email literals with the downstream Python compatibility regex. */
public final class EmailLiteralTranslationIntegrityEvaluator {

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return LiteralMultisetTranslationIntegrityEvaluator.evaluate(
        extractEmails(source),
        extractEmails(target),
        "immutable-email-missing",
        "immutable-email-extra");
  }

  /**
   * Extracts the same non-overlapping matches as the downstream compatibility regex without its
   * quadratic backtracking on long local-part near misses.
   */
  static List<String> extractEmails(CharSequence message) {
    Objects.requireNonNull(message, "message");

    List<String> matches = new ArrayList<>();
    int searchStart = 0;
    int cursor = 0;
    while (cursor < message.length()) {
      while (cursor < message.length() && message.charAt(cursor) != '@') {
        cursor++;
      }
      if (cursor == message.length()) {
        break;
      }

      int at = cursor++;
      int localStart = at;
      while (localStart > searchStart && isLocalPartCharacter(message.charAt(localStart - 1))) {
        localStart--;
      }
      if (localStart == at) {
        continue;
      }

      int domainStart = at + 1;
      int domainCursor = domainStart;
      int matchEnd = -1;
      while (domainCursor < message.length()) {
        char current = message.charAt(domainCursor);
        if (!isDomainCharacter(current)) {
          break;
        }
        if (current != '.' || domainCursor == domainStart) {
          domainCursor++;
          continue;
        }

        int letterEnd = domainCursor + 1;
        while (letterEnd < message.length() && isAsciiLetter(message.charAt(letterEnd))) {
          letterEnd++;
        }
        if (letterEnd - domainCursor > 2) {
          matchEnd = letterEnd;
        }
        domainCursor = letterEnd;
      }

      if (matchEnd >= 0) {
        matches.add(message.subSequence(localStart, matchEnd).toString());
        searchStart = matchEnd;
        cursor = matchEnd;
      }
    }

    return LiteralMultisetTranslationIntegrityEvaluator.immutableSorted(matches);
  }

  private static boolean isLocalPartCharacter(char value) {
    return isAsciiLetter(value)
        || (value >= '0' && value <= '9')
        || value == '.'
        || value == '_'
        || value == '%'
        || value == '+'
        || value == '-';
  }

  private static boolean isDomainCharacter(char value) {
    return isAsciiLetter(value) || (value >= '0' && value <= '9') || value == '.' || value == '-';
  }

  private static boolean isAsciiLetter(char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
  }
}
